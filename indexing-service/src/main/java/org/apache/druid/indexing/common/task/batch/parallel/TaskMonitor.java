/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.indexing.common.task.batch.parallel;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import org.apache.druid.client.indexing.TaskStatusResponse;
import org.apache.druid.common.guava.FutureUtils;
import org.apache.druid.indexer.TaskState;
import org.apache.druid.indexer.TaskStatusPlus;
import org.apache.druid.indexing.common.task.Task;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.Stopwatch;
import org.apache.druid.java.util.common.concurrent.Execs;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.rpc.StandardRetryPolicy;
import org.apache.druid.rpc.indexing.OverlordClient;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Responsible for submitting tasks, monitoring task statuses, resubmitting failed tasks, and returning the final task
 * status.
 */
public class TaskMonitor<T extends Task, SubTaskReportType extends SubTaskReport>
{
  private static final Logger log = new Logger(TaskMonitor.class);

  private final ScheduledExecutorService taskStatusChecker = Execs.scheduledSingleThreaded("task-monitor-%d");

  /**
   * A map of subTaskSpecId to {@link MonitorEntry}. This map stores the state of running {@link SubTaskSpec}s. This is
   * read in {@link java.util.concurrent.Callable} executed by {@link #taskStatusChecker} and updated in {@link #submit}
   * and {@link #retry}. This can also be read by calling {@link #getRunningTaskMonitorEntry},
   * {@link #getRunningTaskIds}, and {@link #getRunningSubTaskSpecs}.
   */
  private final ConcurrentMap<String, MonitorEntry> runningTasks = new ConcurrentHashMap<>();

  /**
   * A map of subTaskSpecId to {@link TaskHistory}. This map stores the history of complete {@link SubTaskSpec}s
   * whether their final state is succeeded or failed. This is updated in {@link MonitorEntry#setLastStatus} which is
   * called by the {@link java.util.concurrent.Callable} executed by {@link #taskStatusChecker} and can be
   * read by outside of this class.
   */
  private final ConcurrentMap<String, TaskHistory<T>> taskHistories = new ConcurrentHashMap<>();

  // subTaskId -> report
  private final ConcurrentHashMap<String, SubTaskReportType> reportsMap = new ConcurrentHashMap<>();

  // lock for updating numRunningTasks, numSucceededTasks, and numFailedTasks
  private final Object taskCountLock = new Object();

  // lock for updating running state
  private final Object startStopLock = new Object();

  // overlord client
  private final OverlordClient overlordClient;
  private final int maxRetry;
  private final int estimatedNumSucceededTasks;
  private final long taskTimeoutMillis;

  @GuardedBy("taskCountLock")
  private int numRunningTasks;
  @GuardedBy("taskCountLock")
  private int numSucceededTasks;
  @GuardedBy("taskCountLock")
  private int numFailedTasks;
  /**
   * This metric is used only for unit tests because the current task status system doesn't track the canceled task
   * status. Currently, this metric only represents the number of canceled tasks by {@link ParallelIndexTaskRunner}.
   * See {@link #stop()}, {@link ParallelIndexPhaseRunner#run()}, and
   * {@link ParallelIndexPhaseRunner#stopGracefully(String)} ()}.
   */
  private int numCanceledTasks;

  @GuardedBy("startStopLock")
  private boolean running = false;

  TaskMonitor(OverlordClient overlordClient, int maxRetry, int estimatedNumSucceededTasks, long taskTimeoutMillis)
  {
    // Unlimited retries for Overlord APIs: if it goes away, we'll wait indefinitely for it to come back.
    this.overlordClient = Preconditions.checkNotNull(overlordClient, "overlordClient")
                                       .withRetryPolicy(StandardRetryPolicy.unlimited());
    this.maxRetry = maxRetry;
    this.estimatedNumSucceededTasks = estimatedNumSucceededTasks;
    this.taskTimeoutMillis = taskTimeoutMillis;
    log.info("TaskMonitor is initialized with estimatedNumSucceededTasks[%d] and sub-task timeout[%d millis].", estimatedNumSucceededTasks, taskTimeoutMillis);
  }

  public void start(long taskStatusCheckingPeriod)
  {
    synchronized (startStopLock) {
      running = true;
      log.info("Starting taskMonitor");
      // NOTE: This polling can be improved to event-driven pushing by registering TaskRunnerListener to TaskRunner.
      // That listener should be able to send the events reported to TaskRunner to this TaskMonitor.
      taskStatusChecker.scheduleAtFixedRate(
          () -> {
            try {
              final Iterator<Entry<String, MonitorEntry>> iterator = runningTasks.entrySet().iterator();
              while (iterator.hasNext()) {
                final Entry<String, MonitorEntry> entry = iterator.next();
                final String specId = entry.getKey();
                final MonitorEntry monitorEntry = entry.getValue();
                final String taskId = monitorEntry.runningTask.getId();

                final long elapsed = monitorEntry.getStopwatch().millisElapsed();
                if (taskTimeoutMillis > 0 && elapsed > taskTimeoutMillis) {
                  log.warn("Cancelling task[%s] as it has already run for [%d] millis (taskTimeoutMillis=[%d]).", taskId, elapsed, taskTimeoutMillis);
                  FutureUtils.getUnchecked(overlordClient.cancelTask(taskId), true);
                  final TaskStatusPlus cancelledTaskStatus = FutureUtils.getUnchecked(
                          overlordClient.taskStatus(taskId), true).getStatus();
                  reportsMap.remove(taskId);
                  incrementNumFailedTasks();
                  monitorEntry.setLastStatus(cancelledTaskStatus);
                  iterator.remove();
                  continue;
                }

                // Could improve this by switching to the bulk taskStatuses API.
                final TaskStatusResponse taskStatusResponse =
                    FutureUtils.getUnchecked(overlordClient.taskStatus(taskId), true);
                final TaskStatusPlus taskStatus = taskStatusResponse.getStatus();
                if (taskStatus != null) {
                  switch (Preconditions.checkNotNull(taskStatus.getStatusCode(), "taskState")) {
                    case SUCCESS:
                      // Succeeded tasks must have sent a report
                      if (!reportsMap.containsKey(taskId)) {
                        throw new ISE("Missing reports from task[%s]!", taskId);
                      }
                      incrementNumSucceededTasks();

                      // Remote the current entry after updating taskHistories to make sure that task history
                      // exists either runningTasks or taskHistories.
                      monitorEntry.setLastStatus(taskStatus);
                      iterator.remove();
                      break;
                    case FAILED:
                      // We don't need reports from failed tasks
                      reportsMap.remove(taskId);
                      incrementNumFailedTasks();

                      log.warn("task[%s] failed!", taskId);
                      if (monitorEntry.numTries() < maxRetry) {
                        log.info(
                            "We still have more chances[%d/%d] to process the spec[%s].",
                            monitorEntry.numTries(),
                            maxRetry,
                            monitorEntry.spec.getId()
                        );
                        retry(specId, monitorEntry, taskStatus);
                      } else {
                        log.error(
                            "spec[%s] failed after [%d] tries",
                            monitorEntry.spec.getId(),
                            monitorEntry.numTries()
                        );
                        // Remote the current entry after updating taskHistories to make sure that task history
                        // exists either runningTasks or taskHistories.
                        monitorEntry.setLastStatus(taskStatus);
                        iterator.remove();
                      }
                      break;
                    case RUNNING:
                      monitorEntry.updateStatus(taskStatus);
                      break;
                    default:
                      throw new ISE("Unknown taskStatus[%s] for task[%s[", taskStatus.getStatusCode(), taskId);
                  }
                }
              }
            }
            catch (Throwable t) {
              // Note that we only log the message here so that task monitoring continues to happen or else
              // the task which created this monitor will keep on waiting endlessly assuming monitored tasks
              // are still running.
              log.error(t, "Error while monitoring");
            }
          },
          taskStatusCheckingPeriod,
          taskStatusCheckingPeriod,
          TimeUnit.MILLISECONDS
      );
    }
  }

  /**
   * Stop task monitoring and cancel all running tasks.
   */
  public void stop()
  {
    synchronized (startStopLock) {
      if (running) {
        running = false;
        taskStatusChecker.shutdownNow();


        synchronized (taskCountLock) {
          if (numRunningTasks > 0) {
            final Iterator<MonitorEntry> iterator = runningTasks.values().iterator();
            while (iterator.hasNext()) {
              final MonitorEntry entry = iterator.next();
              iterator.remove();
              final String taskId = entry.runningTask.getId();
              log.info("Request to cancel subtask[%s]", taskId);
              FutureUtils.getUnchecked(overlordClient.cancelTask(taskId), true);
              numRunningTasks--;
              numCanceledTasks++;
            }

            if (numRunningTasks > 0) {
              log.warn(
                  "Inconsistent state: numRunningTasks[%d] is still not zero after trying to cancel all running tasks.",
                  numRunningTasks
              );
            }
          }
        }

        log.info("Stopped taskMonitor");
      }
    }
  }

  /**
   * Submits a {@link SubTaskSpec} to process to this TaskMonitor. TaskMonitor can issue one or more tasks to process
   * the given spec. The returned future is done when
   * 1) a sub task successfully processed the given spec or
   * 2) the last sub task for the spec failed after all retries were exhausted.
   */
  public ListenableFuture<SubTaskCompleteEvent<T>> submit(SubTaskSpec<T> spec)
  {
    synchronized (startStopLock) {
      if (!running) {
        return Futures.immediateFailedFuture(new ISE("TaskMonitor is not running"));
      }
      final T task = submitTask(spec, 0);
      log.info("Submitted a new task[%s] for spec[%s]", task.getId(), spec.getId());
      incrementNumRunningTasks();

      final SettableFuture<SubTaskCompleteEvent<T>> taskFuture = SettableFuture.create();
      final TaskStatusResponse statusResponse = FutureUtils.getUnchecked(overlordClient.taskStatus(task.getId()), true);
      runningTasks.put(
          spec.getId(),
          new MonitorEntry(spec, task, statusResponse.getStatus(), taskFuture)
      );

      return taskFuture;
    }
  }

  public void collectReport(SubTaskReportType report)
  {
    // subTasks might send their reports multiple times because of the HTTP retry.
    // Here, we simply make sure the current report is exactly the same as the previous one.
    reportsMap.compute(report.getTaskId(), (taskId, prevReport) -> {
      if (prevReport != null) {
        log.warn("Received duplicate report for task [%s]", taskId);
        Preconditions.checkState(
            prevReport.equals(report),
            "task[%s] sent two or more reports and previous report[%s] is different from the current one[%s]",
            taskId,
            prevReport,
            report
        );
      }
      return report;
    });
  }

  public Map<String, SubTaskReportType> getReports()
  {
    return reportsMap;
  }

  /**
   * Submit a retry task for a failed spec. This method should be called inside of the
   * {@link java.util.concurrent.Callable} executed by {@link #taskStatusChecker}.
   */
  private void retry(String subTaskSpecId, MonitorEntry monitorEntry, TaskStatusPlus lastFailedTaskStatus)
  {
    synchronized (startStopLock) {
      if (running) {
        final SubTaskSpec<T> spec = monitorEntry.spec;
        final T task = submitTask(spec, monitorEntry.taskHistory.size() + 1);
        log.info("Submitted a new task[%s] for retrying spec[%s]", task.getId(), spec.getId());
        incrementNumRunningTasks();

        final TaskStatusResponse statusResponse =
            FutureUtils.getUnchecked(overlordClient.taskStatus(task.getId()), true);
        runningTasks.put(
            subTaskSpecId,
            monitorEntry.withNewRunningTask(task, statusResponse.getStatus(), lastFailedTaskStatus)
        );
      }
    }
  }

  private T submitTask(SubTaskSpec<T> spec, int numAttempts)
  {
    T task = spec.newSubTask(numAttempts);
    try {
      FutureUtils.getUnchecked(overlordClient.runTask(task.getId(), task), true);
    }
    catch (Exception e) {
      if (isUnknownTypeIdException(e)) {
        log.warn(e, "Got an unknown type id error. Retrying with a backward compatible type.");
        task = spec.newSubTaskWithBackwardCompatibleType(numAttempts);
        FutureUtils.getUnchecked(overlordClient.runTask(task.getId(), task), true);
      } else {
        throw e;
      }
    }
    return task;
  }

  private boolean isUnknownTypeIdException(Throwable e)
  {
    if (e instanceof IllegalStateException) {
      if (e.getMessage() != null && e.getMessage().contains("Could not resolve type id")) {
        return true;
      }
    }
    if (e.getCause() != null) {
      return isUnknownTypeIdException(e.getCause());
    } else {
      return false;
    }
  }

  private void incrementNumRunningTasks()
  {
    synchronized (taskCountLock) {
      numRunningTasks++;
    }
  }

  private void incrementNumSucceededTasks()
  {
    synchronized (taskCountLock) {
      numRunningTasks--;
      numSucceededTasks++;
      log.info("[%d/%d] tasks succeeded", numSucceededTasks, estimatedNumSucceededTasks);
    }
  }

  private void incrementNumFailedTasks()
  {
    synchronized (taskCountLock) {
      numRunningTasks--;
      numFailedTasks++;
    }
  }

  int getNumSucceededTasks()
  {
    synchronized (taskCountLock) {
      return numSucceededTasks;
    }
  }

  int getNumRunningTasks()
  {
    synchronized (taskCountLock) {
      return numRunningTasks;
    }
  }

  @VisibleForTesting
  int getNumCanceledTasks()
  {
    return numCanceledTasks;
  }

  ParallelIndexingPhaseProgress getProgress()
  {
    synchronized (taskCountLock) {
      return new ParallelIndexingPhaseProgress(
          numRunningTasks,
          numSucceededTasks,
          numFailedTasks,
          numSucceededTasks + numFailedTasks,
          numRunningTasks + numSucceededTasks + numFailedTasks,
          estimatedNumSucceededTasks
      );
    }
  }

  Set<String> getRunningTaskIds()
  {
    return runningTasks.values().stream().map(entry -> entry.runningTask.getId()).collect(Collectors.toSet());
  }

  List<SubTaskSpec<T>> getRunningSubTaskSpecs()
  {
    return runningTasks.values().stream().map(monitorEntry -> monitorEntry.spec).collect(Collectors.toList());
  }

  @Nullable
  MonitorEntry getRunningTaskMonitorEntry(String subTaskSpecId)
  {
    return runningTasks.values()
                       .stream()
                       .filter(monitorEntry -> monitorEntry.spec.getId().equals(subTaskSpecId))
                       .findFirst()
                       .orElse(null);
  }

  List<SubTaskSpec<T>> getCompleteSubTaskSpecs()
  {
    return taskHistories.values().stream().map(TaskHistory::getSpec).collect(Collectors.toList());
  }

  @Nullable
  TaskHistory<T> getCompleteSubTaskSpecHistory(String subTaskSpecId)
  {
    return taskHistories.get(subTaskSpecId);
  }

  class MonitorEntry
  {
    private final SubTaskSpec<T> spec;
    private final T runningTask;
    // old tasks to recent tasks. running task is not included
    private final CopyOnWriteArrayList<TaskStatusPlus> taskHistory;
    private final SettableFuture<SubTaskCompleteEvent<T>> completeEventFuture;
    private final Stopwatch stopwatch;

    /**
     * This variable is updated inside of the {@link java.util.concurrent.Callable} executed by
     * {@link #taskStatusChecker}, and can be read by calling {@link #getRunningStatus}.
     */
    @Nullable
    private volatile TaskStatusPlus runningStatus;

    private MonitorEntry(
        SubTaskSpec<T> spec,
        T runningTask,
        @Nullable TaskStatusPlus runningStatus,
        SettableFuture<SubTaskCompleteEvent<T>> completeEventFuture
    )
    {
      this(spec, runningTask, runningStatus, new CopyOnWriteArrayList<>(), completeEventFuture);
    }

    private MonitorEntry(
        SubTaskSpec<T> spec,
        T runningTask,
        @Nullable TaskStatusPlus runningStatus,
        CopyOnWriteArrayList<TaskStatusPlus> taskHistory,
        SettableFuture<SubTaskCompleteEvent<T>> completeEventFuture
    )
    {
      this.spec = spec;
      this.runningTask = runningTask;
      this.runningStatus = runningStatus;
      this.taskHistory = taskHistory;
      this.completeEventFuture = completeEventFuture;
      this.stopwatch = Stopwatch.createStarted();
    }

    MonitorEntry withNewRunningTask(T newTask, @Nullable TaskStatusPlus newStatus, TaskStatusPlus statusOfLastTask)
    {
      taskHistory.add(statusOfLastTask);
      return new MonitorEntry(
          spec,
          newTask,
          newStatus,
          taskHistory,
          completeEventFuture
      );
    }

    int numTries()
    {
      return taskHistory.size() + 1; // count runningTask as well
    }

    void updateStatus(TaskStatusPlus statusPlus)
    {
      if (!runningTask.getId().equals(statusPlus.getId())) {
        throw new ISE(
            "Task id[%s] of lastStatus is different from the running task[%s]",
            statusPlus.getId(),
            runningTask.getId()
        );
      }
      this.runningStatus = statusPlus;
    }

    void setLastStatus(TaskStatusPlus lastStatus)
    {
      if (!runningTask.getId().equals(lastStatus.getId())) {
        throw new ISE(
            "Task id[%s] of lastStatus is different from the running task[%s]",
            lastStatus.getId(),
            runningTask.getId()
        );
      }

      this.runningStatus = lastStatus;
      taskHistory.add(lastStatus);
      taskHistories.put(spec.getId(), new TaskHistory<>(spec, taskHistory));
      completeEventFuture.set(SubTaskCompleteEvent.success(spec, lastStatus));
    }

    SubTaskSpec<T> getSpec()
    {
      return spec;
    }

    @Nullable
    TaskStatusPlus getRunningStatus()
    {
      return runningStatus;
    }

    List<TaskStatusPlus> getTaskHistory()
    {
      return taskHistory;
    }

    Stopwatch getStopwatch()
    {
      return stopwatch;
    }
  }

  static class SubTaskCompleteEvent<T extends Task>
  {
    private final SubTaskSpec<T> spec;
    @Nullable
    private final TaskStatusPlus lastStatus;
    @Nullable
    private final Throwable throwable;

    static <T extends Task> SubTaskCompleteEvent<T> success(SubTaskSpec<T> spec, TaskStatusPlus lastStatus)
    {
      return new SubTaskCompleteEvent<>(spec, Preconditions.checkNotNull(lastStatus, "lastStatus"), null);
    }

    static <T extends Task> SubTaskCompleteEvent<T> fail(SubTaskSpec<T> spec, Throwable t)
    {
      return new SubTaskCompleteEvent<>(spec, null, t);
    }

    private SubTaskCompleteEvent(
        SubTaskSpec<T> spec,
        @Nullable TaskStatusPlus lastStatus,
        @Nullable Throwable throwable
    )
    {
      this.spec = Preconditions.checkNotNull(spec, "spec");
      this.lastStatus = lastStatus;
      this.throwable = throwable;
    }

    SubTaskSpec<T> getSpec()
    {
      return spec;
    }

    TaskState getLastState()
    {
      return lastStatus == null ? TaskState.FAILED : lastStatus.getStatusCode();
    }

    @Nullable
    TaskStatusPlus getLastStatus()
    {
      return lastStatus;
    }

    @Nullable
    Throwable getThrowable()
    {
      return throwable;
    }
  }
}
