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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.apache.druid.data.input.HandlingInputRowIterator;
import org.apache.druid.data.input.InputFormat;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.InputSource;
import org.apache.druid.data.input.Rows;
import org.apache.druid.data.input.StringTuple;
import org.apache.druid.indexer.TaskStatus;
import org.apache.druid.indexer.granularity.GranularitySpec;
import org.apache.druid.indexer.partitions.DimensionRangePartitionsSpec;
import org.apache.druid.indexing.common.TaskToolbox;
import org.apache.druid.indexing.common.actions.SurrogateTaskActionClient;
import org.apache.druid.indexing.common.actions.TaskActionClient;
import org.apache.druid.indexing.common.task.AbstractBatchIndexTask;
import org.apache.druid.indexing.common.task.TaskResource;
import org.apache.druid.indexing.common.task.batch.parallel.distribution.StringDistribution;
import org.apache.druid.indexing.common.task.batch.parallel.distribution.StringSketch;
import org.apache.druid.indexing.common.task.batch.parallel.iterator.RangePartitionIndexTaskInputRowIteratorBuilder;
import org.apache.druid.java.util.common.granularity.Granularity;
import org.apache.druid.java.util.common.parsers.CloseableIterator;
import org.apache.druid.segment.incremental.ParseExceptionHandler;
import org.apache.druid.segment.incremental.RowIngestionMeters;
import org.apache.druid.segment.indexing.DataSchema;
import org.apache.druid.server.security.AuthorizationUtils;
import org.apache.druid.server.security.ResourceAction;
import org.joda.time.Interval;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The worker task of {@link PartialDimensionDistributionParallelIndexTaskRunner}. This task
 * determines the distribution of dimension values of input data.
 */

public class PartialDimensionDistributionTask extends PerfectRollupWorkerTask
{
  public static final String TYPE = "partial_dimension_distribution";

  // Do not skip nulls as StringDistribution can handle null values.
  // This behavior is different from hadoop indexing.
  private static final boolean SKIP_NULL = false;

  private final int numAttempts;
  private final ParallelIndexIngestionSpec ingestionSchema;
  private final String subtaskSpecId;

  // For testing
  private final Supplier<DedupInputRowFilter> dedupInputRowFilterSupplier;

  @JsonCreator
  PartialDimensionDistributionTask(
      // id shouldn't be null except when this task is created by ParallelIndexSupervisorTask
      @JsonProperty("id") @Nullable String id,
      @JsonProperty("groupId") final String groupId,
      @JsonProperty("resource") final TaskResource taskResource,
      @JsonProperty("supervisorTaskId") final String supervisorTaskId,
      // subtaskSpecId can be null only for old task versions.
      @JsonProperty("subtaskSpecId") @Nullable final String subtaskSpecId,
      @JsonProperty("numAttempts") final int numAttempts, // zero-based counting
      @JsonProperty("spec") final ParallelIndexIngestionSpec ingestionSchema,
      @JsonProperty("context") final Map<String, Object> context
  )
  {
    this(
        id,
        groupId,
        taskResource,
        supervisorTaskId,
        subtaskSpecId,
        numAttempts,
        ingestionSchema,
        context,
        () -> new DedupInputRowFilter(
            ingestionSchema.getDataSchema().getGranularitySpec().getQueryGranularity()
        )
    );
  }

  @VisibleForTesting
  PartialDimensionDistributionTask(
      @Nullable String id,
      final String groupId,
      final TaskResource taskResource,
      final String supervisorTaskId,
      @Nullable final String subtaskSpecId,
      final int numAttempts,
      final ParallelIndexIngestionSpec ingestionSchema,
      final Map<String, Object> context,
      Supplier<DedupInputRowFilter> dedupRowDimValueFilterSupplier
  )
  {
    super(
        getOrMakeId(id, TYPE, ingestionSchema.getDataSchema().getDataSource()),
        groupId,
        taskResource,
        ingestionSchema.getDataSchema(),
        ingestionSchema.getTuningConfig(),
        context,
        supervisorTaskId
    );

    Preconditions.checkArgument(
        ingestionSchema.getTuningConfig().getPartitionsSpec() instanceof DimensionRangePartitionsSpec,
        "%s partitionsSpec required",
        DimensionRangePartitionsSpec.NAME
    );

    this.subtaskSpecId = subtaskSpecId;
    this.numAttempts = numAttempts;
    this.ingestionSchema = ingestionSchema;
    this.dedupInputRowFilterSupplier = dedupRowDimValueFilterSupplier;
  }

  @JsonProperty
  private int getNumAttempts()
  {
    return numAttempts;
  }

  @JsonProperty("spec")
  private ParallelIndexIngestionSpec getIngestionSchema()
  {
    return ingestionSchema;
  }

  @JsonProperty
  @Override
  public String getSubtaskSpecId()
  {
    return subtaskSpecId;
  }

  @Override
  public String getType()
  {
    return TYPE;
  }

  @Nonnull
  @JsonIgnore
  @Override
  public Set<ResourceAction> getInputSourceResources()
  {
    return getIngestionSchema().getIOConfig().getInputSource() != null ?
           getIngestionSchema().getIOConfig().getInputSource().getTypes()
                               .stream()
                               .map(AuthorizationUtils::createExternalResourceReadAction)
                               .collect(Collectors.toSet()) :
           ImmutableSet.of();
  }

  @Override
  public boolean isReady(TaskActionClient taskActionClient) throws Exception
  {
    if (!getIngestionSchema().getDataSchema().getGranularitySpec().inputIntervals().isEmpty()) {
      return tryTimeChunkLock(
          new SurrogateTaskActionClient(getSupervisorTaskId(), taskActionClient),
          getIngestionSchema().getDataSchema().getGranularitySpec().inputIntervals()
      );
    } else {
      return true;
    }
  }

  @Override
  public TaskStatus runTask(TaskToolbox toolbox) throws Exception
  {
    DataSchema dataSchema = ingestionSchema.getDataSchema();
    GranularitySpec granularitySpec = dataSchema.getGranularitySpec();
    ParallelIndexTuningConfig tuningConfig = ingestionSchema.getTuningConfig();

    DimensionRangePartitionsSpec partitionsSpec = (DimensionRangePartitionsSpec) tuningConfig.getPartitionsSpec();
    Preconditions.checkNotNull(partitionsSpec, "partitionsSpec required in tuningConfig");
    final List<String> partitionDimensions = partitionsSpec.getPartitionDimensions();
    Preconditions.checkArgument(
        partitionDimensions != null && !partitionDimensions.isEmpty(),
        "partitionDimension required in partitionsSpec"
    );
    boolean isAssumeGrouped = partitionsSpec.isAssumeGrouped();

    InputSource inputSource = ingestionSchema.getIOConfig().getNonNullInputSource(toolbox);
    InputFormat inputFormat = inputSource.needsFormat()
                              ? ParallelIndexSupervisorTask.getInputFormat(ingestionSchema)
                              : null;
    final RowIngestionMeters buildSegmentsMeters = toolbox.getRowIngestionMetersFactory().createRowIngestionMeters();
    final ParseExceptionHandler parseExceptionHandler = new ParseExceptionHandler(
        buildSegmentsMeters,
        tuningConfig.isLogParseExceptions(),
        tuningConfig.getMaxParseExceptions(),
        tuningConfig.getMaxSavedParseExceptions()
    );
    try (
        final CloseableIterator<InputRow> inputRowIterator = AbstractBatchIndexTask.inputSourceReader(
            toolbox.getIndexingTmpDir(),
            dataSchema,
            inputSource,
            inputFormat,
            allowNonNullRowsWithinInputIntervalsOf(granularitySpec),
            buildSegmentsMeters,
            parseExceptionHandler
        );
        HandlingInputRowIterator iterator =
            new RangePartitionIndexTaskInputRowIteratorBuilder(partitionDimensions, SKIP_NULL)
                .delegate(inputRowIterator)
                .granularitySpec(granularitySpec)
                .build()
    ) {
      Map<Interval, StringDistribution> distribution = determineDistribution(
          iterator,
          granularitySpec,
          partitionDimensions,
          isAssumeGrouped
      );
      sendReport(toolbox, new DimensionDistributionReport(getId(), distribution));
    }

    return TaskStatus.success(getId());
  }

  private Map<Interval, StringDistribution> determineDistribution(
      HandlingInputRowIterator inputRowIterator,
      GranularitySpec granularitySpec,
      List<String> partitionDimensions,
      boolean isAssumeGrouped
  )
  {
    Map<Interval, StringDistribution> intervalToDistribution = new HashMap<>();
    InputRowFilter inputRowFilter =
        !isAssumeGrouped && granularitySpec.isRollup()
        ? dedupInputRowFilterSupplier.get()
        : new PassthroughInputRowFilter();

    while (inputRowIterator.hasNext()) {
      InputRow inputRow = inputRowIterator.next();
      if (inputRow == null) {
        continue;
      }

      final Interval interval;
      if (granularitySpec.inputIntervals().isEmpty()) {
        interval = granularitySpec.getSegmentGranularity().bucket(inputRow.getTimestamp());
      } else {
        final Optional<Interval> optInterval = granularitySpec.bucketInterval(inputRow.getTimestamp());
        // this interval must exist since it passed the rowFilter
        assert optInterval.isPresent();
        interval = optInterval.get();
      }
      String[] values = new String[partitionDimensions.size()];
      for (int i = 0; i < partitionDimensions.size(); ++i) {
        List<String> dimensionValues = inputRow.getDimension(partitionDimensions.get(i));
        if (dimensionValues != null && !dimensionValues.isEmpty()) {
          values[i] = Iterables.getOnlyElement(dimensionValues);
        }
      }
      final StringTuple partitionDimensionValues = StringTuple.create(values);

      if (inputRowFilter.accept(interval, partitionDimensionValues, inputRow)) {
        StringDistribution stringDistribution =
            intervalToDistribution.computeIfAbsent(interval, k -> new StringSketch());
        stringDistribution.put(partitionDimensionValues);
      }
    }

    // DedupInputRowFilter may not accept the min/max dimensionValue. If needed, add the min/max
    // values to the distributions so they have an accurate min/max.
    inputRowFilter.getIntervalToMinPartitionDimensionValue()
                  .forEach((interval, min) -> intervalToDistribution.get(interval).putIfNewMin(min));
    inputRowFilter.getIntervalToMaxPartitionDimensionValue()
                  .forEach((interval, max) -> intervalToDistribution.get(interval).putIfNewMax(max));

    return intervalToDistribution;
  }

  private void sendReport(TaskToolbox toolbox, DimensionDistributionReport report)
  {
    final ParallelIndexSupervisorTaskClient taskClient = toolbox.getSupervisorTaskClientProvider().build(
        getSupervisorTaskId(),
        ingestionSchema.getTuningConfig().getChatHandlerTimeout(),
        ingestionSchema.getTuningConfig().getChatHandlerNumRetries()
    );
    taskClient.report(report);
  }

  private interface InputRowFilter
  {
    /**
     * @return True if input row should be accepted, else false
     */
    boolean accept(Interval interval, StringTuple partitionDimensionValues, InputRow inputRow);

    /**
     * @return Minimum partition dimension value for each interval processed so far.
     */
    Map<Interval, StringTuple> getIntervalToMinPartitionDimensionValue();

    /**
     * @return Maximum partition dimension value for each interval processed so far.
     */
    Map<Interval, StringTuple> getIntervalToMaxPartitionDimensionValue();
  }

  /**
   * Filters out reoccurrences of rows that have timestamps with the same query granularity and dimension values.
   * Approximate matching is used, so there is a small probability that rows that are not reoccurences are discarded.
   */
  @VisibleForTesting
  static class DedupInputRowFilter implements InputRowFilter
  {
    // A bloom filter is used to approximately group rows by query granularity. These values assume
    // time chunks have fewer than BLOOM_FILTER_EXPECTED_INSERTIONS rows. With the below values, the
    // Bloom filter will use about 170MB of memory.
    //
    // For more details on the Bloom filter memory consumption:
    // https://github.com/google/guava/issues/2520#issuecomment-231233736
    private static final int BLOOM_FILTER_EXPECTED_INSERTIONS = 100_000_000;
    private static final double BLOOM_FILTER_EXPECTED_FALSE_POSITIVE_PROBABILTY = 0.001;

    private final PassthroughInputRowFilter delegate;
    private final Granularity queryGranularity;
    private final BloomFilter<CharSequence> groupingBloomFilter;

    DedupInputRowFilter(Granularity queryGranularity)
    {
      this(queryGranularity, BLOOM_FILTER_EXPECTED_INSERTIONS, BLOOM_FILTER_EXPECTED_FALSE_POSITIVE_PROBABILTY);
    }

    @VisibleForTesting
      // to allow controlling false positive rate of bloom filter
    DedupInputRowFilter(
        Granularity queryGranularity,
        int bloomFilterExpectedInsertions,
        double bloomFilterFalsePositiveProbability
    )
    {
      delegate = new PassthroughInputRowFilter();
      this.queryGranularity = queryGranularity;
      groupingBloomFilter = BloomFilter.create(
          Funnels.unencodedCharsFunnel(),
          bloomFilterExpectedInsertions,
          bloomFilterFalsePositiveProbability
      );
    }

    @Override
    public boolean accept(Interval interval, StringTuple partitionDimensionValue, InputRow inputRow)
    {
      delegate.accept(interval, partitionDimensionValue, inputRow);

      long bucketTimestamp = getBucketTimestamp(inputRow);
      List<Object> groupKey = Rows.toGroupKey(bucketTimestamp, inputRow);
      String serializedGroupKey = groupKey.toString();
      if (groupingBloomFilter.mightContain(serializedGroupKey)) {
        return false;
      } else {
        groupingBloomFilter.put(serializedGroupKey);
        return true;
      }
    }

    private long getBucketTimestamp(InputRow inputRow)
    {
      final long timestamp = inputRow.getTimestampFromEpoch();
      return queryGranularity.bucketStart(timestamp);
    }

    @Override
    public Map<Interval, StringTuple> getIntervalToMinPartitionDimensionValue()
    {
      return delegate.getIntervalToMinPartitionDimensionValue();
    }

    @Override
    public Map<Interval, StringTuple> getIntervalToMaxPartitionDimensionValue()
    {
      return delegate.getIntervalToMaxPartitionDimensionValue();
    }
  }

  /**
   * Accepts all input rows, even if they are reoccurrences of timestamps with the same query granularity and dimension
   * value.
   */
  private static class PassthroughInputRowFilter implements InputRowFilter
  {
    private final Map<Interval, StringTuple> intervalToMinDimensionValue;
    private final Map<Interval, StringTuple> intervalToMaxDimensionValue;

    PassthroughInputRowFilter()
    {
      this.intervalToMinDimensionValue = new HashMap<>();
      this.intervalToMaxDimensionValue = new HashMap<>();
    }

    @Override
    public boolean accept(Interval interval, StringTuple partitionDimensionValue, InputRow inputRow)
    {
      updateMinDimensionValue(interval, partitionDimensionValue);
      updateMaxDimensionValue(interval, partitionDimensionValue);
      return true;
    }

    private void updateMinDimensionValue(Interval interval, StringTuple dimensionValue)
    {
      intervalToMinDimensionValue.compute(
          interval,
          (intervalKey, currentMinValue) -> {
            if (currentMinValue == null || currentMinValue.compareTo(dimensionValue) > 0) {
              return dimensionValue;
            } else {
              return currentMinValue;
            }
          }
      );
    }

    private void updateMaxDimensionValue(Interval interval, StringTuple dimensionValue)
    {
      intervalToMaxDimensionValue.compute(
          interval,
          (intervalKey, currentMaxValue) -> {
            if (currentMaxValue == null || currentMaxValue.compareTo(dimensionValue) < 0) {
              return dimensionValue;
            } else {
              return currentMaxValue;
            }
          }
      );
    }

    @Override
    public Map<Interval, StringTuple> getIntervalToMinPartitionDimensionValue()
    {
      return intervalToMinDimensionValue;
    }

    @Override
    public Map<Interval, StringTuple> getIntervalToMaxPartitionDimensionValue()
    {
      return intervalToMaxDimensionValue;
    }
  }
}
