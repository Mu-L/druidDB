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

package org.apache.druid.indexing.common.actions;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableSet;
import org.apache.druid.indexing.common.task.IndexTaskUtils;
import org.apache.druid.indexing.common.task.Task;
import org.apache.druid.indexing.overlord.CriticalAction;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.java.util.emitter.service.ServiceMetricEvent;
import org.apache.druid.query.DruidMetrics;
import org.apache.druid.segment.SegmentUtils;
import org.apache.druid.timeline.DataSegment;
import org.joda.time.Interval;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Permanently deletes unused segments from the metadata store.
 */
public class SegmentNukeAction implements TaskAction<Void>
{
  private static final Logger log = new Logger(SegmentNukeAction.class);

  private final Set<DataSegment> segments;

  @JsonCreator
  public SegmentNukeAction(
      @JsonProperty("segments") Set<DataSegment> segments
  )
  {
    this.segments = ImmutableSet.copyOf(segments);
  }

  @JsonProperty
  public Set<DataSegment> getSegments()
  {
    return segments;
  }

  @Override
  public TypeReference<Void> getReturnTypeReference()
  {
    return new TypeReference<>() {};
  }

  @Override
  public Void perform(Task task, TaskActionToolbox toolbox)
  {
    TaskLocks.checkLockCoversSegments(task, toolbox.getTaskLockbox(), segments);

    try {
      final Set<Interval> intervals = segments.stream().map(DataSegment::getInterval).collect(Collectors.toSet());
      int numDeletedSegments = toolbox.getTaskLockbox().doInCriticalSection(
          task,
          intervals,
          CriticalAction.<Integer>builder().onValidLocks(
              () -> toolbox.getIndexerMetadataStorageCoordinator().deleteSegments(segments)
          ).onInvalidLocks(
              () -> {
                throw new ISE("Some locks for task[%s] are already revoked", task.getId());
              }
          ).build()
      );

      log.info(
          "Deleted [%d] segments from metadata store out of requested[%d],"
          + " across [%d] intervals[%s], for task[%s] of datasource[%s].",
          numDeletedSegments, segments.size(),
          intervals.size(), intervals,
          task.getId(), task.getDataSource()
      );
    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }

    // Emit metrics
    final ServiceMetricEvent.Builder metricBuilder = new ServiceMetricEvent.Builder();
    IndexTaskUtils.setTaskDimensions(metricBuilder, task);

    for (DataSegment segment : segments) {
      metricBuilder.setDimension(DruidMetrics.INTERVAL, segment.getInterval().toString());
      toolbox.getEmitter().emit(metricBuilder.setMetric("segment/nuked/bytes", segment.getSize()));
    }

    return null;
  }

  @Override
  public String toString()
  {
    return "SegmentNukeAction{" +
           "segments=" + SegmentUtils.commaSeparatedIdentifiers(segments) +
           '}';
  }
}
