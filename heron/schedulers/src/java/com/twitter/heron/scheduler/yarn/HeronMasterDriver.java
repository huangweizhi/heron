// Copyright 2016 Twitter. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.twitter.heron.scheduler.yarn;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.inject.Inject;

import com.google.common.annotations.VisibleForTesting;

import org.apache.reef.driver.context.ActiveContext;
import org.apache.reef.driver.context.ContextConfiguration;
import org.apache.reef.driver.evaluator.AllocatedEvaluator;
import org.apache.reef.driver.evaluator.EvaluatorRequest;
import org.apache.reef.driver.evaluator.EvaluatorRequestor;
import org.apache.reef.driver.evaluator.FailedEvaluator;
import org.apache.reef.driver.task.CompletedTask;
import org.apache.reef.driver.task.FailedTask;
import org.apache.reef.driver.task.RunningTask;
import org.apache.reef.driver.task.TaskConfiguration;
import org.apache.reef.runtime.common.files.REEFFileNames;
import org.apache.reef.tang.Configuration;
import org.apache.reef.tang.annotations.Parameter;
import org.apache.reef.tang.annotations.Unit;
import org.apache.reef.wake.EventHandler;
import org.apache.reef.wake.time.event.StartTime;

import com.twitter.heron.scheduler.SchedulerMain;
import com.twitter.heron.scheduler.yarn.HeronConfigurationOptions.Cluster;
import com.twitter.heron.scheduler.yarn.HeronConfigurationOptions.Environ;
import com.twitter.heron.scheduler.yarn.HeronConfigurationOptions.HeronCorePackageName;
import com.twitter.heron.scheduler.yarn.HeronConfigurationOptions.HttpPort;
import com.twitter.heron.scheduler.yarn.HeronConfigurationOptions.Role;
import com.twitter.heron.scheduler.yarn.HeronConfigurationOptions.TopologyJar;
import com.twitter.heron.scheduler.yarn.HeronConfigurationOptions.TopologyName;
import com.twitter.heron.scheduler.yarn.HeronConfigurationOptions.TopologyPackageName;
import com.twitter.heron.scheduler.yarn.HeronConfigurationOptions.VerboseLogMode;
import com.twitter.heron.spi.packing.PackingPlan;
import com.twitter.heron.spi.packing.PackingPlan.ContainerPlan;
import com.twitter.heron.spi.packing.Resource;

/**
 * {@link HeronMasterDriver} serves Heron's YARN Scheduler by managing containers / processes for
 * Heron TMaster and workers using REEF framework. This includes making container request for
 * topology, providing package needed to start Heron components and killing containers.
 */
@Unit
public class HeronMasterDriver {
  static final int TM_MEM_SIZE_MB = 1024;
  static final String TMASTER_CONTAINER_ID = "0";
  static final int MB = 1024 * 1024;
  private static final Logger LOG = Logger.getLogger(HeronMasterDriver.class.getName());
  private final String topologyPackageName;
  private final String heronCorePackageName;
  private final EvaluatorRequestor requestor;
  private final REEFFileNames reefFileNames;
  private final String localHeronConfDir;
  private final String cluster;
  private final String role;
  private final String topologyName;
  private final String env;
  private final String topologyJar;
  private final int httpPort;
  private final boolean verboseMode;

  // This map contains all the active workers managed my this scheduler. The workers can be
  // looked up by heron's executor id or REEF's container id.
  private MultiKeyWorkerMap multiKeyWorkerMap;

  // TODO: https://github.com/twitter/heron/issues/949: implement Driver HA

  // TODO: https://github.com/twitter/heron/issues/950, support homogeneous container allocation
  // Currently yarn does not support mapping container requests to allocation (YARN-4879). As a
  // result it is not possible to concurrently start containers of different sizes. This Executor
  // and Queue will ensure containers are started serially.
  private ExecutorService executor = Executors.newSingleThreadExecutor();
  private BlockingQueue<AllocatedEvaluator> allocatedContainerQ = new LinkedBlockingDeque<>();
  private PackingPlan packing;
  private AtomicBoolean isTopologyKilled = new AtomicBoolean(false);

  @Inject
  public HeronMasterDriver(EvaluatorRequestor requestor,
                           final REEFFileNames fileNames,
                           @Parameter(Cluster.class) String cluster,
                           @Parameter(Role.class) String role,
                           @Parameter(TopologyName.class) String topologyName,
                           @Parameter(Environ.class) String env,
                           @Parameter(TopologyJar.class) String topologyJar,
                           @Parameter(TopologyPackageName.class) String topologyPackageName,
                           @Parameter(HeronCorePackageName.class) String heronCorePackageName,
                           @Parameter(HttpPort.class) int httpPort,
                           @Parameter(VerboseLogMode.class) boolean verboseMode)
      throws IOException {

    // REEF related initialization
    this.requestor = requestor;
    this.reefFileNames = fileNames;

    // Heron related initialization
    this.localHeronConfDir = ".";
    this.cluster = cluster;
    this.role = role;
    this.topologyName = topologyName;
    this.topologyPackageName = topologyPackageName;
    this.heronCorePackageName = heronCorePackageName;
    this.env = env;
    this.topologyJar = topologyJar;
    this.httpPort = httpPort;
    this.verboseMode = verboseMode;
    this.multiKeyWorkerMap = new MultiKeyWorkerMap();

    // This instance of Driver will be used for managing topology containers
    HeronMasterDriverProvider.setInstance(this);
  }

  /**
   * Requests container for TMaster as container/executor id 0.
   */
  boolean scheduleTMasterContainer() {
    LOG.log(Level.INFO, "Scheduling container for TM: {0}", topologyName);
    // TODO: https://github.com/twitter/heron/issues/951: colocate TMaster and Scheduler
    AllocatedEvaluator container =
        launchContainerForExecutor(new HeronWorker(TMASTER_CONTAINER_ID, 1, TM_MEM_SIZE_MB));
    return container != null;
  }

  /**
   * Container allocation is asynchronous. Request containers serially to ensure allocated resources
   * match the required resources
   */
  boolean scheduleHeronWorkers(PackingPlan topologyPacking) {
    this.packing = topologyPacking;
    for (Entry<String, ContainerPlan> entry : topologyPacking.containers.entrySet()) {
      Resource reqResource = entry.getValue().resource;
      int mem = getMemInMBForExecutor(reqResource);
      int cores = getCpuForExecutor(reqResource);
      AllocatedEvaluator container = launchContainerForExecutor(entry.getKey(), cores, mem);
      if (container == null) {
        return false;
      }
    }
    return true;
  }

  public void killTopology() {
    LOG.log(Level.INFO, "Kill topology: {0}", topologyName);
    isTopologyKilled.set(true);

    for (HeronWorker worker : multiKeyWorkerMap.getHeronWorkers()) {
      LOG.log(Level.INFO, "Killing container {0} for executor {1}",
          new Object[]{worker.evaluator.getId(), worker.workerId});
      worker.evaluator.close();
    }
  }

  public boolean restartTopology() {
    boolean result = true;
    for (HeronWorker worker : multiKeyWorkerMap.getHeronWorkers()) {
      result &= restartWorker(worker.workerId);
    }
    return result;
  }

  public boolean restartWorker(String id) {
    LOG.log(Level.INFO, "Find & restart container for id={0}", id);

    HeronWorker worker = multiKeyWorkerMap.lookupByWorkerId(id);
    if (worker == null) {
      LOG.log(Level.WARNING, "Active container for {0} not found. Requesting a new one.", id);
      ContainerPlan containerPlan = packing.containers.get(id);
      if (containerPlan == null) {
        LOG.log(Level.WARNING, "There is no container for {0} in packing plan.", id);
        return false;
      }
      Resource resource = containerPlan.resource;
      worker = new HeronWorker(id, getCpuForExecutor(resource), getMemInMBForExecutor(resource));
    } else {
      AllocatedEvaluator evaluator = multiKeyWorkerMap.detachEvaluatorAndRemove(worker);
      LOG.log(Level.INFO, "Shutting down container {0}", evaluator.getId());
      evaluator.close();
    }

    AllocatedEvaluator container = launchContainerForExecutor(worker);
    return container != null;
  }

  @VisibleForTesting
  AllocatedEvaluator launchContainerForExecutor(final String executorId,
                                                        final int cpu,
                                                        final int mem) {
    return launchContainerForExecutor(new HeronWorker(executorId, cpu, mem));
  }

  private AllocatedEvaluator launchContainerForExecutor(final HeronWorker worker) {
    Future<AllocatedEvaluator> result = executor.submit(new Callable<AllocatedEvaluator>() {
      @Override
      public AllocatedEvaluator call() throws Exception {
        AllocatedEvaluator evaluator = allocateContainer(worker.workerId, worker.cores, worker.mem);
        if (evaluator == null) {
          LOG.log(Level.WARNING, "Could not allocate container for id {0}", worker.workerId);
          return null;
        }

        Configuration context = createContextConfig(worker.workerId);

        LOG.log(Level.INFO,
            "Activating container {0} for heron executor, id: {1}",
            new Object[]{evaluator.getId(), worker.workerId});
        evaluator.submitContext(context);
        multiKeyWorkerMap.assignEvaluatorToWorker(worker, evaluator);
        return evaluator;
      }
    });

    try {
      return result.get();
    } catch (InterruptedException e) {
      LOG.log(Level.SEVERE, "Interrupted while waiting for container", e);
    } catch (ExecutionException e) {
      LOG.log(Level.SEVERE, "Error while waiting for container", e);
    }
    return null;
  }

  AllocatedEvaluator allocateContainer(String id, int cpu, int mem) {
    EvaluatorRequest evaluatorRequest = createEvaluatorRequest(cpu, mem);

    LOG.log(Level.INFO, "Requesting container for executor, id: {0}, mem: {1}, cpu: {2}",
        new Object[]{id, mem, cpu});
    requestor.submit(evaluatorRequest);

    try {
      AllocatedEvaluator evaluator = allocatedContainerQ.take();
      LOG.log(Level.INFO,
          "Container {0} is allocated for executor, id: {1}",
          new Object[]{evaluator.getId(), id});
      return evaluator;
    } catch (InterruptedException e) {
      LOG.log(Level.WARNING, "Interrupted while waiting for container allocation", e);
      return null;
    }
  }

  Configuration createContextConfig(String executorId) {
    return ContextConfiguration.CONF
        .set(ContextConfiguration.IDENTIFIER, executorId)
        .build();
  }

  EvaluatorRequest createEvaluatorRequest(int cpu, int mem) {
    return EvaluatorRequest
        .newBuilder()
        .setNumber(1)
        .setMemory(mem)
        .setNumberOfCores(cpu)
        .build();
  }

  private int getCpuForExecutor(Resource resource) {
    return (int) Math.ceil(resource.cpu);
  }

  private int getMemInMBForExecutor(Resource resource) {
    Long ram = resource.ram / MB;
    return ram.intValue();
  }

  String getPackingAsString() {
    return packing.getInstanceDistribution();
  }

  String getComponentRamMap() {
    return packing.getComponentRamDistribution();
  }

  void submitHeronExecutorTask(String workerId) {
    HeronWorker worker = multiKeyWorkerMap.lookupByWorkerId(workerId);
    if (worker == null) {
      LOG.log(Level.SEVERE, "Container for id: {0} not found.", workerId);
      return;
    }

    LOG.log(Level.INFO, "Submitting evaluator task for id: {0}", workerId);

    // topologyName and other configurations are required by Heron Executor and Task to load
    // configuration files. Using REEF configuration model is better than depending on external
    // persistence.
    final Configuration taskConf = HeronTaskConfiguration.CONF
        .set(TaskConfiguration.TASK, HeronExecutorTask.class)
        .set(TaskConfiguration.ON_CLOSE, HeronExecutorTask.HeronExecutorTaskTerminator.class)
        .set(TaskConfiguration.IDENTIFIER, workerId)
        .set(HeronTaskConfiguration.TOPOLOGY_NAME, topologyName)
        .set(HeronTaskConfiguration.TOPOLOGY_JAR, topologyJar)
        .set(HeronTaskConfiguration.TOPOLOGY_PACKAGE_NAME, topologyPackageName)
        .set(HeronTaskConfiguration.HERON_CORE_PACKAGE_NAME, heronCorePackageName)
        .set(HeronTaskConfiguration.ROLE, role)
        .set(HeronTaskConfiguration.ENV, env)
        .set(HeronTaskConfiguration.CLUSTER, cluster)
        .set(HeronTaskConfiguration.PACKED_PLAN, getPackingAsString())
        .set(HeronTaskConfiguration.COMPONENT_RAM_MAP, getComponentRamMap())
        .set(HeronTaskConfiguration.CONTAINER_ID, workerId)
        .set(HeronTaskConfiguration.VERBOSE, verboseMode)
        .build();
    worker.context.submitTask(taskConf);
  }

  /**
   * {@link HeronWorker} is a data class which connects reef ids, heron ids and related objects.
   * All the pointers in an instance are related to one container. A container is a reef object,
   * owns one heron worker id and its handlers
   */
  private static final class HeronWorker {
    private String workerId;
    private int cores;
    private int mem;
    private AllocatedEvaluator evaluator;
    private ActiveContext context;

    HeronWorker(String id, int cores, int mem) {
      this.workerId = id;
      this.cores = cores;
      this.mem = mem;
    }
  }

  /**
   * {@link MultiKeyWorkerMap} is a helper class to provide multi key lookup of a
   * {@link HeronWorker} instance. It also ensures thread safety and update order.
   */
  private static final class MultiKeyWorkerMap {
    private Map<String, HeronWorker> workerMap = new HashMap<>();
    private Map<String, HeronWorker> evaluatorWorkerMap = new HashMap<>();

    void assignEvaluatorToWorker(HeronWorker worker, AllocatedEvaluator evaluator) {
      worker.evaluator = evaluator;
      synchronized (workerMap) {
        workerMap.put(worker.workerId, worker);
        evaluatorWorkerMap.put(evaluator.getId(), worker);
      }
    }

    HeronWorker lookupByEvaluatorId(String evaluatorId) {
      synchronized (workerMap) {
        return evaluatorWorkerMap.get(evaluatorId);
      }
    }

    HeronWorker lookupByWorkerId(String workerId) {
      HeronWorker worker;
      synchronized (workerMap) {
        worker = workerMap.get(workerId);
      }
      if (worker == null) {
        LOG.log(Level.WARNING, "Container for executor id: {0} not found.", workerId);
      }
      return worker;
    }

    AllocatedEvaluator detachEvaluatorAndRemove(HeronWorker worker) {
      synchronized (workerMap) {
        workerMap.remove(worker.workerId);
        evaluatorWorkerMap.remove(worker.evaluator.getId());
      }
      AllocatedEvaluator evaluator = worker.evaluator;
      worker.evaluator = null;
      return evaluator;
    }

    ArrayList<HeronWorker> getHeronWorkers() {
      synchronized (workerMap) {
        return new ArrayList<>(workerMap.values());
      }
    }
  }

  /**
   * {@link HeronSchedulerLauncher} is the first class initialized on the server by REEF. This is
   * responsible for unpacking binaries and launching Heron Scheduler.
   */
  class HeronSchedulerLauncher implements EventHandler<StartTime> {
    @Override
    public void onNext(StartTime value) {
      String globalFolder = reefFileNames.getGlobalFolder().getPath();

      HeronReefUtils.extractPackageInSandbox(globalFolder, topologyPackageName, localHeronConfDir);
      HeronReefUtils.extractPackageInSandbox(globalFolder, heronCorePackageName, localHeronConfDir);

      launchScheduler();
    }

    private void launchScheduler() {
      try {
        LOG.log(Level.INFO, "Launching Heron scheduler");
        SchedulerMain schedulerMain = SchedulerMain.createInstance(cluster,
            role,
            env,
            topologyJar,
            topologyName,
            httpPort,
            false);
        schedulerMain.runScheduler();
      } catch (IOException e) {
        throw new RuntimeException("Failed to launch Heron Scheduler", e);
      }
    }
  }

  /**
   * Initializes worker on the allocated container
   */
  class ContainerAllocationHandler implements EventHandler<AllocatedEvaluator> {
    @Override
    public void onNext(AllocatedEvaluator evaluator) {
      LOG.log(Level.INFO, "New container received, id: {0}", evaluator.getId());
      try {
        allocatedContainerQ.put(evaluator);
        LOG.log(Level.INFO, "{0} containers waiting for activation", allocatedContainerQ.size());
      } catch (InterruptedException e) {
        // Fail deployment of topology if there is a error starting any worker
        LOG.log(Level.SEVERE, "Container allocation for id:{0} failed. Trying to close currently"
            + " allocated resources for {1}", new Object[]{evaluator.getId(), topologyName});
        killTopology();
        throw new RuntimeException("Failed to allocate container for workers", e);
      }
    }
  }

  /**
   * Initializes worker on the allocated container
   */
  class FailedContainerHandler implements EventHandler<FailedEvaluator> {
    @Override
    public void onNext(FailedEvaluator evaluator) {
      LOG.log(Level.WARNING, "Container:{0} failed", evaluator.getId());
      HeronWorker worker = multiKeyWorkerMap.lookupByEvaluatorId(evaluator.getId());
      if (worker == null) {
        LOG.log(Level.WARNING,
            "Unknown executor id for failed container: {0}, skip renew action",
            evaluator.getId());
        return;
      }
      LOG.log(Level.INFO, "Trying to relaunch executor {0} running on failed container {1}",
          new Object[]{worker.workerId, evaluator.getId()});
      multiKeyWorkerMap.detachEvaluatorAndRemove(worker);

      launchContainerForExecutor(worker);
    }
  }

  /**
   * Once the container starts, this class starts Heron's executor process. Heron executor is
   * started as a task. This task can be killed and the container can be reused.
   */
  public final class HeronWorkerLauncher implements EventHandler<ActiveContext> {
    @Override
    public void onNext(ActiveContext context) {
      if (isTopologyKilled.get()) {
        LOG.log(Level.WARNING, "Topology has been killed, close new context: {0}", context.getId());
        context.close();
        return;
      }

      String workerId = context.getId();
      HeronWorker worker = multiKeyWorkerMap.lookupByWorkerId(workerId);
      if (worker == null) {
        context.close();
        return;
      }

      worker.context = context;
      submitHeronExecutorTask(workerId);
    }
  }

  /**
   * This class manages active tasks. The task handlers provided by REEF will be memorized to be
   * used later for operations like topology restart. Restarting a task does not require new
   * container request.
   */
  public final class HeronWorkerStartHandler implements EventHandler<RunningTask> {
    @Override
    public void onNext(RunningTask runningTask) {
      LOG.log(Level.INFO, "Task, id:{0}, has started.", runningTask.getId());
    }
  }

  public final class HeronWorkerTaskFailureHandler implements EventHandler<FailedTask> {
    @Override
    public void onNext(FailedTask failedTask) {
      LOG.log(Level.WARNING, "Task {0} failed. Relaunching the task", failedTask.getId());
      if (isTopologyKilled.get()) {
        LOG.info("The topology is killed. Ignore task fail event");
        return;
      }

      submitHeronExecutorTask(failedTask.getId());
    }
  }

  public final class HeronWorkerTaskCompletedErrorHandler implements EventHandler<CompletedTask> {
    @Override
    public void onNext(CompletedTask task) {
      LOG.log(Level.INFO, "Task {0} completed.", task.getId());
      if (isTopologyKilled.get()) {
        LOG.info("The topology is killed. Ignore task complete event");
        return;
      }
      LOG.log(Level.WARNING, "Task should not complete, relaunching {0}", task.getId());
      submitHeronExecutorTask(task.getId());
    }
  }
}
