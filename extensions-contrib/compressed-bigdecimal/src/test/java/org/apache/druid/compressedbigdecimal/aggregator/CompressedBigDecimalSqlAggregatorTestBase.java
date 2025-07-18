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

package org.apache.druid.compressedbigdecimal.aggregator;

import com.google.common.collect.ImmutableList;
import org.apache.druid.compressedbigdecimal.CompressedBigDecimalModule;
import org.apache.druid.compressedbigdecimal.aggregator.CompressedBigDecimalSqlAggregatorTestBase.CompressedBigDecimalComponentSupplier;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.InputRowSchema;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.data.input.impl.TimestampSpec;
import org.apache.druid.initialization.DruidModule;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.query.Druids;
import org.apache.druid.query.aggregation.CountAggregatorFactory;
import org.apache.druid.query.aggregation.DoubleSumAggregatorFactory;
import org.apache.druid.query.spec.MultipleIntervalSegmentSpec;
import org.apache.druid.segment.IndexBuilder;
import org.apache.druid.segment.QueryableIndex;
import org.apache.druid.segment.incremental.IncrementalIndexSchema;
import org.apache.druid.segment.writeout.OffHeapMemorySegmentWriteOutMediumFactory;
import org.apache.druid.server.SpecificSegmentsQuerySegmentWalker;
import org.apache.druid.sql.calcite.BaseCalciteQueryTest;
import org.apache.druid.sql.calcite.SqlTestFrameworkConfig;
import org.apache.druid.sql.calcite.TempDirProducer;
import org.apache.druid.sql.calcite.filtration.Filtration;
import org.apache.druid.sql.calcite.util.CalciteTests;
import org.apache.druid.sql.calcite.util.DruidModuleCollection;
import org.apache.druid.sql.calcite.util.SqlTestFramework.StandardComponentSupplier;
import org.apache.druid.sql.calcite.util.TestDataBuilder;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.partition.LinearShardSpec;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@SqlTestFrameworkConfig.ComponentSupplier(CompressedBigDecimalComponentSupplier.class)
public abstract class CompressedBigDecimalSqlAggregatorTestBase extends BaseCalciteQueryTest
{
  protected static class CompressedBigDecimalComponentSupplier extends StandardComponentSupplier
  {
    public CompressedBigDecimalComponentSupplier(TempDirProducer tempFolderProducer)
    {
      super(tempFolderProducer);
    }

    private static final InputRowSchema SCHEMA = new InputRowSchema(
        new TimestampSpec(TestDataBuilder.TIMESTAMP_COLUMN, "iso", null),
        new DimensionsSpec(
            DimensionsSpec.getDefaultSchemas(ImmutableList.of("dim1", "dim2", "dim3", "m2"))
        ),
        null
    );

    private static final List<InputRow> ROWS1 =
        TestDataBuilder.RAW_ROWS1.stream().map(m -> TestDataBuilder.createRow(m, SCHEMA)).collect(Collectors.toList());

    @Override
    public DruidModule getCoreModule()
    {
      return DruidModuleCollection.of(super.getCoreModule(), new CompressedBigDecimalModule());
    }

    @Override
    public SpecificSegmentsQuerySegmentWalker addSegmentsToWalker(SpecificSegmentsQuerySegmentWalker walker)
    {
      QueryableIndex index =
          IndexBuilder.create()
                      .tmpDir(tempDirProducer.newTempFolder())
                      .segmentWriteOutMediumFactory(OffHeapMemorySegmentWriteOutMediumFactory.instance())
                      .schema(
                          new IncrementalIndexSchema.Builder()
                              .withMetrics(
                                  new CountAggregatorFactory("cnt"),
                                  new DoubleSumAggregatorFactory("m1", "m1")
                              )
                              .withRollup(false)
                              .build()
                      )
                      .rows(ROWS1)
                      .buildMMappedIndex();

      return walker.add(
          DataSegment.builder()
                     .dataSource(CalciteTests.DATASOURCE1)
                     .interval(index.getDataInterval())
                     .version("1")
                     .shardSpec(new LinearShardSpec(0))
                     .size(0)
                     .build(),
          index
      );
    }
  }

  @Test
  public abstract void testCompressedBigDecimalAggWithNumberParse();

  // expected: NumberFormatException.class
  @Test
  public abstract void testCompressedBigDecimalAggWithStrictNumberParse();

  @Test
  public abstract void testCompressedBigDecimalAggDefaultNumberParseAndCustomSizeAndScale();

  @Test
  public abstract void testCompressedBigDecimalAggDefaultScale();

  @Test
  public abstract void testCompressedBigDecimalAggDefaultSizeAndScale();

  protected void testCompressedBigDecimalAggWithNumberParseHelper(
      String functionName,
      Object[] expectedResults,
      CompressedBigDecimalAggregatorFactoryCreator factoryCreator
  )
  {
    cannotVectorize();
    testQuery(
        StringUtils.format(
            "SELECT %s(m1, 9, 9), %s(m2, 9, 9), %s(dim1, 9, 9, false) FROM foo",
            functionName,
            functionName,
            functionName
        ),
        Collections.singletonList(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(new MultipleIntervalSegmentSpec(ImmutableList.of(Filtration.eternity())))
                  .granularity(Granularities.ALL)
                  .aggregators(
                      factoryCreator.create("a0", "m1", 9, 9, false),
                      factoryCreator.create("a1", "m2", 9, 9, false),
                      factoryCreator.create("a2", "dim1", 9, 9, false)

                  )
                  .context(QUERY_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(expectedResults)
    );
  }

  protected void testCompressedBigDecimalAggWithStrictNumberParseHelper(
      String functionName,
      CompressedBigDecimalAggregatorFactoryCreator factoryCreator
  )
  {
    cannotVectorize();
    testQuery(
        StringUtils.format("SELECT %s(dim1, 9, 9, true) FROM foo", functionName),
        Collections.singletonList(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(new MultipleIntervalSegmentSpec(ImmutableList.of(Filtration.eternity())))
                  .granularity(Granularities.ALL)
                  .aggregators(factoryCreator.create("a0", "dim1", 9, 9, true))
                  .context(QUERY_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(new Object[]{"unused"})
    );
  }

  public void testCompressedBigDecimalAggDefaultNumberParseAndCustomSizeAndScaleHelper(
      String functionName,
      Object[] expectedResults,
      CompressedBigDecimalAggregatorFactoryCreator factoryCreator
  )
  {
    cannotVectorize();
    testQuery(
        StringUtils.format(
            "SELECT %s(m1, 9, 3), %s(m2, 9, 3), %s(dim1, 9, 3) FROM foo",
            functionName,
            functionName,
            functionName
        ),
        Collections.singletonList(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(new MultipleIntervalSegmentSpec(ImmutableList.of(Filtration.eternity())))
                  .granularity(Granularities.ALL)
                  .aggregators(
                      factoryCreator.create("a0", "m1", 9, 3, false),
                      factoryCreator.create("a1", "m2", 9, 3, false),
                      factoryCreator.create("a2", "dim1", 9, 3, false)
                  )
                  .context(QUERY_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(expectedResults)
    );
  }

  public void testCompressedBigDecimalAggDefaultScaleHelper(
      String functionName,
      Object[] expectedResults,
      CompressedBigDecimalAggregatorFactoryCreator factoryCreator
  )
  {
    cannotVectorize();
    testQuery(
        StringUtils.format(
            "SELECT %s(m1, 9), %s(m2, 9), %s(dim1, 9) FROM foo",
            functionName,
            functionName,
            functionName
        ),
        Collections.singletonList(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(new MultipleIntervalSegmentSpec(ImmutableList.of(Filtration.eternity())))
                  .granularity(Granularities.ALL)
                  .aggregators(
                      factoryCreator.create("a0", "m1", 9, 9, false),
                      factoryCreator.create("a1", "m2", 9, 9, false),
                      factoryCreator.create("a2", "dim1", 9, 9, false)
                  )
                  .context(QUERY_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(expectedResults)
    );
  }

  public void testCompressedBigDecimalAggDefaultSizeAndScaleHelper(
      String functionName,
      Object[] expectedResults,
      CompressedBigDecimalAggregatorFactoryCreator factoryCreator
  )
  {
    cannotVectorize();
    testQuery(
        StringUtils.format("SELECT %s(m1), %s(m2), %s(dim1) FROM foo", functionName, functionName, functionName),
        Collections.singletonList(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(new MultipleIntervalSegmentSpec(ImmutableList.of(Filtration.eternity())))
                  .granularity(Granularities.ALL)
                  .aggregators(
                      factoryCreator.create("a0", "m1", 6, 9, false),
                      factoryCreator.create("a1", "m2", 6, 9, false),
                      factoryCreator.create("a2", "dim1", 6, 9, false)
                  )
                  .context(QUERY_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(expectedResults)
    );
  }
}
