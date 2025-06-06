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

package org.apache.druid.sql.calcite;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.apache.calcite.avatica.SqlType;
import org.apache.druid.java.util.common.HumanReadableBytes;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.math.expr.ExprEval;
import org.apache.druid.math.expr.ExprMacroTable;
import org.apache.druid.math.expr.ExpressionType;
import org.apache.druid.query.Druids;
import org.apache.druid.query.FilteredDataSource;
import org.apache.druid.query.InlineDataSource;
import org.apache.druid.query.LookupDataSource;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryContexts;
import org.apache.druid.query.QueryDataSource;
import org.apache.druid.query.TableDataSource;
import org.apache.druid.query.UnnestDataSource;
import org.apache.druid.query.aggregation.CountAggregatorFactory;
import org.apache.druid.query.aggregation.DoubleSumAggregatorFactory;
import org.apache.druid.query.aggregation.ExpressionLambdaAggregatorFactory;
import org.apache.druid.query.aggregation.FilteredAggregatorFactory;
import org.apache.druid.query.aggregation.LongSumAggregatorFactory;
import org.apache.druid.query.aggregation.post.ExpressionPostAggregator;
import org.apache.druid.query.dimension.DefaultDimensionSpec;
import org.apache.druid.query.expression.TestExprMacroTable;
import org.apache.druid.query.extraction.SubstringDimExtractionFn;
import org.apache.druid.query.filter.ArrayContainsElementFilter;
import org.apache.druid.query.filter.ExpressionDimFilter;
import org.apache.druid.query.filter.InDimFilter;
import org.apache.druid.query.filter.LikeDimFilter;
import org.apache.druid.query.groupby.GroupByQuery;
import org.apache.druid.query.groupby.having.DimFilterHavingSpec;
import org.apache.druid.query.groupby.orderby.DefaultLimitSpec;
import org.apache.druid.query.groupby.orderby.NoopLimitSpec;
import org.apache.druid.query.groupby.orderby.OrderByColumnSpec;
import org.apache.druid.query.ordering.StringComparators;
import org.apache.druid.query.scan.ScanQuery;
import org.apache.druid.query.spec.MultipleIntervalSegmentSpec;
import org.apache.druid.query.topn.DimensionTopNMetricSpec;
import org.apache.druid.query.topn.TopNQueryBuilder;
import org.apache.druid.segment.VirtualColumns;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.RowSignature;
import org.apache.druid.segment.join.JoinType;
import org.apache.druid.segment.virtual.ExpressionVirtualColumn;
import org.apache.druid.sql.calcite.DecoupledTestConfig.IgnoreDefaultsReson;
import org.apache.druid.sql.calcite.DecoupledTestConfig.IgnoreQueriesReason;
import org.apache.druid.sql.calcite.DecoupledTestConfig.QuidemTestCaseReason;
import org.apache.druid.sql.calcite.NotYetSupported.Modes;
import org.apache.druid.sql.calcite.filtration.Filtration;
import org.apache.druid.sql.calcite.planner.PlannerConfig;
import org.apache.druid.sql.calcite.util.CalciteTests;
import org.apache.druid.sql.calcite.util.SqlTestFramework.StandardComponentSupplier;
import org.apache.druid.sql.http.SqlParameter;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Tests for array functions and array types
 */
@SqlTestFrameworkConfig.ComponentSupplier(StandardComponentSupplier.class)
public class CalciteArraysQueryTest extends BaseCalciteQueryTest
{
  private static final Map<String, Object> QUERY_CONTEXT_UNNEST =
      ImmutableMap.<String, Object>builder()
                  .putAll(QUERY_CONTEXT_DEFAULT)
                  .put(QueryContexts.CTX_SQL_STRINGIFY_ARRAYS, false)
                  .build();

  public static void assertResultsDeepEquals(String sql, List<Object[]> expected, List<Object[]> results)
  {
    for (int row = 0; row < results.size(); row++) {
      for (int col = 0; col < results.get(row).length; col++) {
        final String rowString = StringUtils.format("result #%d: %s", row + 1, sql);
        assertDeepEquals(rowString + " - column: " + col + ":", expected.get(row)[col], results.get(row)[col]);
      }
    }
  }

  public static void assertDeepEquals(String path, Object expected, Object actual)
  {
    if (expected instanceof List && actual instanceof List) {
      List expectedList = (List) expected;
      List actualList = (List) actual;
      Assert.assertEquals(path + " arrays length mismatch", expectedList.size(), actualList.size());
      for (int i = 0; i < expectedList.size(); i++) {
        assertDeepEquals(path + "[" + i + "]", expectedList.get(i), actualList.get(i));
      }
    } else {
      Assert.assertEquals(path, expected, actual);
    }
  }

  @Test
  public void testSelectConstantArrayExpressionFromTable()
  {
    testQuery(
        "SELECT ARRAY[1,2] as arr, dim1 FROM foo LIMIT 1",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(expressionVirtualColumn("v0", "array(1,2)", ColumnType.LONG_ARRAY))
                .columns("v0", "dim1")
                .columnTypes(ColumnType.LONG_ARRAY, ColumnType.STRING)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(1)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[1,2]", ""}
        )
    );
  }

  @Test
  public void testGroupByArrayFromCase()
  {
    cannotVectorize();
    testQuery(
        "SELECT CASE WHEN dim4 = 'a' THEN ARRAY['foo','bar','baz'] END as mv_value, count(1) from numfoo GROUP BY 1",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setVirtualColumns(expressionVirtualColumn(
                            "v0",
                            "case_searched((\"dim4\" == 'a'),array('foo','bar','baz'),null)",
                            ColumnType.STRING_ARRAY
                        ))
                        .setDimensions(new DefaultDimensionSpec("v0", "d0", ColumnType.STRING_ARRAY))
                        .setGranularity(Granularities.ALL)
                        .setAggregatorSpecs(new CountAggregatorFactory("a0"))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{null, 3L},
            new Object[]{ImmutableList.of("foo", "bar", "baz"), 3L}
        )
    );
  }

  @Test
  public void testGroupByArrayColumnFromCase()
  {
    cannotVectorize();
    testQuery(
        "SELECT CASE WHEN arrayStringNulls = ARRAY['a', 'b'] THEN arrayLongNulls END as arr, count(1) from arrays GROUP BY 1",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.ARRAYS_DATASOURCE)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setVirtualColumns(expressionVirtualColumn(
                            "v0",
                            "case_searched((\"arrayStringNulls\" == array('a','b')),\"arrayLongNulls\",null)",
                            ColumnType.LONG_ARRAY
                        ))
                        .setDimensions(new DefaultDimensionSpec("v0", "d0", ColumnType.LONG_ARRAY))
                        .setGranularity(Granularities.ALL)
                        .setAggregatorSpecs(new CountAggregatorFactory("a0"))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{null, 11L},
            new Object[]{Arrays.asList(1L, null, 3L), 1L},
            new Object[]{Arrays.asList(2L, 3L), 2L}
        )
    );
  }

  @Test
  public void testSelectNonConstantArrayExpressionFromTable()
  {
    testQuery(
        "SELECT ARRAY[CONCAT(dim1, 'word'),'up'] as arr, dim1 FROM foo LIMIT 5",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(expressionVirtualColumn(
                    "v0",
                    "array(concat(\"dim1\",'word'),'up')",
                    ColumnType.STRING_ARRAY
                ))
                .columns("v0", "dim1")
                .columnTypes(ColumnType.STRING_ARRAY, ColumnType.STRING)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"word\",\"up\"]", ""},
            new Object[]{"[\"10.1word\",\"up\"]", "10.1"},
            new Object[]{"[\"2word\",\"up\"]", "2"},
            new Object[]{"[\"1word\",\"up\"]", "1"},
            new Object[]{"[\"defword\",\"up\"]", "def"}
        )
    );
  }

  @Test
  public void testSelectNonConstantArrayExpressionFromTableForMultival()
  {
    // Produces nested string array, that MSQ can't infer from the selector
    msqIncompatible();
    final String sql = "SELECT ARRAY[CONCAT(dim3, 'word'),'up'] as arr, dim1 FROM foo LIMIT 5";
    final Query<?> scanQuery = newScanQueryBuilder()
        .dataSource(CalciteTests.DATASOURCE1)
        .intervals(querySegmentSpec(Filtration.eternity()))
        .virtualColumns(expressionVirtualColumn("v0", "array(concat(\"dim3\",'word'),'up')", ColumnType.STRING_ARRAY))
        .columns("v0", "dim1")
        .columnTypes(ColumnType.STRING_ARRAY, ColumnType.STRING)
        .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
        .limit(5)
        .context(QUERY_CONTEXT_DEFAULT)
        .build();

    // dim3 is a multi-valued string column, so the automatic translation will turn this
    // expression into
    //
    //    `map((dim3) -> array(concat(dim3,'word'),'up'), dim3)`
    //
    // this works, but we still translate the output into a string since that is the current output type
    // in some future this might not auto-convert to a string type (when we support grouping on arrays maybe?)

    testQuery(
        sql,
        ImmutableList.of(scanQuery),
        ImmutableList.of(
            new Object[]{"[[\"aword\",\"up\"],[\"bword\",\"up\"]]", ""},
            new Object[]{"[[\"bword\",\"up\"],[\"cword\",\"up\"]]", "10.1"},
            new Object[]{"[[\"dword\",\"up\"]]", "2"},
            new Object[]{"[[\"word\",\"up\"]]", "1"},
            new Object[]{"[[null,\"up\"]]", "def"}
        )
    );

  }

  @Test
  public void testSomeArrayFunctionsWithScanQuery()
  {
    List<Object[]> expectedResults = ImmutableList.of(
        new Object[]{
            "",
            "a",
            "[\"a\",\"b\"]",
            7L,
            null,
            1.0,
            null,
            "[\"a\",\"b\",\"c\"]",
            "[1,2,3]",
            "[1.9,2.2,4.3]",
            "[\"a\",\"b\",\"foo\"]",
            "[\"foo\",\"a\"]",
            "[1,2,7]",
            "[null,1,2]",
            "[1.2,2.2,1.0]",
            "[null,1.1,2.2]",
            "[\"a\",\"a\",\"b\"]",
            "[7,null]",
            "[1.0,null]",
            7L,
            1.0,
            7L,
            1.0
        }
    );
    RowSignature resultSignature = RowSignature.builder()
                .add("dim1", ColumnType.STRING)
                .add("dim2", ColumnType.STRING)
                .add("dim3", ColumnType.STRING)
                .add("l1", ColumnType.LONG)
                .add("l2", ColumnType.LONG)
                .add("dbl1", ColumnType.DOUBLE)
                .add("dbl2", ColumnType.DOUBLE)
                .add("EXPR$7", ColumnType.STRING_ARRAY)
                .add("EXPR$8", ColumnType.LONG_ARRAY)
                .add("EXPR$9", ColumnType.DOUBLE_ARRAY)
                .add("EXPR$10", ColumnType.STRING_ARRAY)
                .add("EXPR$11", ColumnType.STRING_ARRAY)
                .add("EXPR$12", ColumnType.LONG_ARRAY)
                .add("EXPR$13", ColumnType.LONG_ARRAY)
                .add("EXPR$14", ColumnType.DOUBLE_ARRAY)
                .add("EXPR$15", ColumnType.DOUBLE_ARRAY)
                .add("EXPR$16", ColumnType.STRING_ARRAY)
                .add("EXPR$17", ColumnType.LONG_ARRAY)
                .add("EXPR$18", ColumnType.DOUBLE_ARRAY)
                .add("EXPR$19", ColumnType.LONG)
                .add("EXPR$20", ColumnType.DOUBLE)
                .add("EXPR$21", ColumnType.LONG)
                .add("EXPR$22", ColumnType.DOUBLE)
                .build();
    testQuery(
        "SELECT"
        + " dim1,"
        + " dim2,"
        + " dim3,"
        + " l1,"
        + " l2,"
        + " dbl1,"
        + " dbl2,"
        + " ARRAY['a', 'b', 'c'],"
        + " ARRAY[1,2,3],"
        + " ARRAY[1.9, 2.2, 4.3],"
        + " ARRAY_APPEND(dim3, 'foo'),"
        + " ARRAY_PREPEND('foo', ARRAY[dim2]),"
        + " ARRAY_APPEND(ARRAY[1,2], l1),"
        + " ARRAY_PREPEND(l2, ARRAY[1,2]),"
        + " ARRAY_APPEND(ARRAY[1.2,2.2], dbl1),"
        + " ARRAY_PREPEND(dbl2, ARRAY[1.1,2.2]),"
        + " ARRAY_CONCAT(dim2,dim3),"
        + " ARRAY_CONCAT(ARRAY[l1],ARRAY[l2]),"
        + " ARRAY_CONCAT(ARRAY[dbl1],ARRAY[dbl2]),"
        + " ARRAY_OFFSET(ARRAY[l1],0),"
        + " ARRAY_OFFSET(ARRAY[dbl1],0),"
        + " ARRAY_ORDINAL(ARRAY[l1],1),"
        + " ARRAY_ORDINAL(ARRAY[dbl1],1)"
        + " FROM druid.numfoo"
        + " LIMIT 1",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE3)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(
                    // these report as strings even though they are not, someday this will not be so
                    expressionVirtualColumn("v0", "array('a','b','c')", ColumnType.STRING_ARRAY),
                    expressionVirtualColumn("v1", "array(1,2,3)", ColumnType.LONG_ARRAY),
                    expressionVirtualColumn("v10", "array_concat(array(\"l1\"),array(\"l2\"))", ColumnType.LONG_ARRAY),
                    expressionVirtualColumn(
                        "v11",
                        "array_concat(array(\"dbl1\"),array(\"dbl2\"))",
                        ColumnType.DOUBLE_ARRAY
                    ),
                    expressionVirtualColumn("v12", "array_offset(array(\"l1\"),0)", ColumnType.LONG),
                    expressionVirtualColumn("v13", "array_offset(array(\"dbl1\"),0)", ColumnType.DOUBLE),
                    expressionVirtualColumn("v14", "array_ordinal(array(\"l1\"),1)", ColumnType.LONG),
                    expressionVirtualColumn("v15", "array_ordinal(array(\"dbl1\"),1)", ColumnType.DOUBLE),
                    expressionVirtualColumn("v2", "array(1.9,2.2,4.3)", ColumnType.DOUBLE_ARRAY),
                    expressionVirtualColumn("v3", "array_append(\"dim3\",'foo')", ColumnType.STRING_ARRAY),
                    expressionVirtualColumn("v4", "array_prepend('foo',array(\"dim2\"))", ColumnType.STRING_ARRAY),
                    expressionVirtualColumn("v5", "array_append(array(1,2),\"l1\")", ColumnType.LONG_ARRAY),
                    expressionVirtualColumn("v6", "array_prepend(\"l2\",array(1,2))", ColumnType.LONG_ARRAY),
                    expressionVirtualColumn("v7", "array_append(array(1.2,2.2),\"dbl1\")", ColumnType.DOUBLE_ARRAY),
                    expressionVirtualColumn("v8", "array_prepend(\"dbl2\",array(1.1,2.2))", ColumnType.DOUBLE_ARRAY),
                    expressionVirtualColumn("v9", "array_concat(\"dim2\",\"dim3\")", ColumnType.STRING_ARRAY)
                )
                .columns(
                    "dim1",
                    "dim2",
                    "dim3",
                    "l1",
                    "l2",
                    "dbl1",
                    "dbl2",
                    "v0",
                    "v1",
                    "v2",
                    "v3",
                    "v4",
                    "v5",
                    "v6",
                    "v7",
                    "v8",
                    "v9",
                    "v10",
                    "v11",
                    "v12",
                    "v13",
                    "v14",
                    "v15"
                )
                .columnTypes(resultSignature.getColumnTypes())
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(1)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        expectedResults,
        resultSignature
    );
  }

  @Test
  public void testSomeArrayFunctionsWithScanQueryArrayColumns()
  {
    List<Object[]> expectedResults = ImmutableList.of(
        new Object[]{
            null,
            "[]",
            null,
            null,
            null,
            "[1]",
            "[2]",
            null,
            null,
            null,
            "[1,2,3]",
            null,
            null,
            null,
            null,
            null,
            null,
            null
        },
        new Object[]{
            "[\"a\",\"b\"]",
            "[2,3]",
            "[null]",
            "[\"a\",\"b\",\"foo\"]",
            "[\"foo\",\"a\",\"b\"]",
            "[2,3,1]",
            "[2,2,3]",
            "[null,1.1]",
            "[2.2,null]",
            null,
            null,
            null,
            "a",
            2L,
            null,
            "a",
            2L,
            null
        },
        new Object[]{
            "[\"b\",\"b\"]",
            "[1]",
            null,
            "[\"b\",\"b\",\"foo\"]",
            "[\"foo\",\"b\",\"b\"]",
            "[1,1]",
            "[2,1]",
            null,
            null,
            "[\"d\",\"e\",\"b\",\"b\"]",
            "[1,4,1]",
            null,
            "b",
            1L,
            null,
            "b",
            1L,
            null
        },
        new Object[]{
            null,
            "[null,2,9]",
            "[999.0,5.5,null]",
            null,
            null,
            "[null,2,9,1]",
            "[2,null,2,9]",
            "[999.0,5.5,null,1.1]",
            "[2.2,999.0,5.5,null]",
            null,
            null,
            null,
            null,
            null,
            999.0D,
            null,
            null,
            999.0D
        },
        new Object[]{
            "[\"a\",\"b\"]",
            "[1,null,3]",
            "[1.1,2.2,null]",
            "[\"a\",\"b\",\"foo\"]",
            "[\"foo\",\"a\",\"b\"]",
            "[1,null,3,1]",
            "[2,1,null,3]",
            "[1.1,2.2,null,1.1]",
            "[2.2,1.1,2.2,null]",
            "[\"a\",\"b\",\"a\",\"b\"]",
            "[1,2,3,1,null,3]",
            "[1.1,2.2,3.3,1.1,2.2,null]",
            "a",
            1L,
            1.1D,
            "a",
            1L,
            1.1D
        },
        new Object[]{
            "[\"d\",null,\"b\"]",
            "[1,2,3]",
            "[null,2.2,null]",
            "[\"d\",null,\"b\",\"foo\"]",
            "[\"foo\",\"d\",null,\"b\"]",
            "[1,2,3,1]",
            "[2,1,2,3]",
            "[null,2.2,null,1.1]",
            "[2.2,null,2.2,null]",
            "[\"b\",\"c\",\"d\",null,\"b\"]",
            "[1,2,3,4,1,2,3]",
            "[1.1,3.3,null,2.2,null]",
            "d",
            1L,
            null,
            "d",
            1L,
            null
        },
        new Object[]{
            "[null,\"b\"]",
            null,
            "[999.0,null,5.5]",
            "[null,\"b\",\"foo\"]",
            "[\"foo\",null,\"b\"]",
            null,
            null,
            "[999.0,null,5.5,1.1]",
            "[2.2,999.0,null,5.5]",
            "[\"a\",\"b\",\"c\",null,\"b\"]",
            null,
            "[3.3,4.4,5.5,999.0,null,5.5]",
            null,
            null,
            999.0D,
            null,
            null,
            999.0D
        },
        new Object[]{
            null,
            null,
            "[]",
            null,
            null,
            null,
            null,
            "[1.1]",
            "[2.2]",
            null,
            null,
            "[1.1,2.2,3.3]",
            null,
            null,
            null,
            null,
            null,
            null
        },
        new Object[]{
            "[\"a\",\"b\"]",
            "[2,3]",
            "[null,1.1]",
            "[\"a\",\"b\",\"foo\"]",
            "[\"foo\",\"a\",\"b\"]",
            "[2,3,1]",
            "[2,2,3]",
            "[null,1.1,1.1]",
            "[2.2,null,1.1]",
            null,
            null,
            null,
            "a",
            2L,
            null,
            "a",
            2L,
            null
        },
        new Object[]{
            "[\"b\",\"b\"]",
            "[null]",
            null,
            "[\"b\",\"b\",\"foo\"]",
            "[\"foo\",\"b\",\"b\"]",
            "[null,1]",
            "[2,null]",
            null,
            null,
            "[\"d\",\"e\",\"b\",\"b\"]",
            "[1,4,null]",
            null,
            "b",
            null,
            null,
            "b",
            null,
            null
        },
        new Object[]{
            "[null]",
            "[null,2,9]",
            "[999.0,5.5,null]",
            "[null,\"foo\"]",
            "[\"foo\",null]",
            "[null,2,9,1]",
            "[2,null,2,9]",
            "[999.0,5.5,null,1.1]",
            "[2.2,999.0,5.5,null]",
            "[\"a\",\"b\",null]",
            null,
            null,
            null,
            null,
            999.0D,
            null,
            null,
            999.0D
        },
        new Object[]{
            "[]",
            "[1,null,3]",
            "[1.1,2.2,null]",
            "[\"foo\"]",
            "[\"foo\"]",
            "[1,null,3,1]",
            "[2,1,null,3]",
            "[1.1,2.2,null,1.1]",
            "[2.2,1.1,2.2,null]",
            "[\"a\",\"b\"]",
            "[1,2,3,1,null,3]",
            "[1.1,2.2,3.3,1.1,2.2,null]",
            null,
            1L,
            1.1D,
            null,
            1L,
            1.1D
        },
        new Object[]{
            "[\"d\",null,\"b\"]",
            "[1,2,3]",
            "[null,2.2,null]",
            "[\"d\",null,\"b\",\"foo\"]",
            "[\"foo\",\"d\",null,\"b\"]",
            "[1,2,3,1]",
            "[2,1,2,3]",
            "[null,2.2,null,1.1]",
            "[2.2,null,2.2,null]",
            "[\"b\",\"c\",\"d\",null,\"b\"]",
            "[1,2,3,4,1,2,3]",
            "[1.1,3.3,null,2.2,null]",
            "d",
            1L,
            null,
            "d",
            1L,
            null
        },
        new Object[]{
            "[null,\"b\"]",
            null,
            "[999.0,null,5.5]",
            "[null,\"b\",\"foo\"]",
            "[\"foo\",null,\"b\"]",
            null,
            null,
            "[999.0,null,5.5,1.1]",
            "[2.2,999.0,null,5.5]",
            "[\"a\",\"b\",\"c\",null,\"b\"]",
            null,
            "[3.3,4.4,5.5,999.0,null,5.5]",
            null,
            null,
            999.0D,
            null,
            null,
            999.0D
        }
    );
    RowSignature resultSignature = RowSignature.builder()
                .add("arrayStringNulls", ColumnType.STRING_ARRAY)
                .add("arrayLongNulls", ColumnType.LONG_ARRAY)
                .add("arrayDoubleNulls", ColumnType.DOUBLE_ARRAY)
                .add("EXPR$3", ColumnType.STRING_ARRAY)
                .add("EXPR$4", ColumnType.STRING_ARRAY)
                .add("EXPR$5", ColumnType.LONG_ARRAY)
                .add("EXPR$6", ColumnType.LONG_ARRAY)
                .add("EXPR$7", ColumnType.DOUBLE_ARRAY)
                .add("EXPR$8", ColumnType.DOUBLE_ARRAY)
                .add("EXPR$9", ColumnType.STRING_ARRAY)
                .add("EXPR$10", ColumnType.LONG_ARRAY)
                .add("EXPR$11", ColumnType.DOUBLE_ARRAY)
                .add("EXPR$12", ColumnType.STRING)
                .add("EXPR$13", ColumnType.LONG)
                .add("EXPR$14", ColumnType.DOUBLE)
                .add("EXPR$15", ColumnType.STRING)
                .add("EXPR$16", ColumnType.LONG)
                .add("EXPR$17", ColumnType.DOUBLE)
                .build();
    testQuery(
        "SELECT"
        + " arrayStringNulls,"
        + " arrayLongNulls,"
        + " arrayDoubleNulls,"
        + " ARRAY_APPEND(arrayStringNulls, 'foo'),"
        + " ARRAY_PREPEND('foo', arrayStringNulls),"
        + " ARRAY_APPEND(arrayLongNulls, 1),"
        + " ARRAY_PREPEND(2, arrayLongNulls),"
        + " ARRAY_APPEND(arrayDoubleNulls, 1.1),"
        + " ARRAY_PREPEND(2.2, arrayDoubleNulls),"
        + " ARRAY_CONCAT(arrayString,arrayStringNulls),"
        + " ARRAY_CONCAT(arrayLong,arrayLongNulls),"
        + " ARRAY_CONCAT(arrayDouble,arrayDoubleNulls),"
        + " ARRAY_OFFSET(arrayStringNulls,0),"
        + " ARRAY_OFFSET(arrayLongNulls,0),"
        + " ARRAY_OFFSET(arrayDoubleNulls,0),"
        + " ARRAY_ORDINAL(arrayStringNulls,1),"
        + " ARRAY_ORDINAL(arrayLongNulls,1),"
        + " ARRAY_ORDINAL(arrayDoubleNulls,1)"
        + " FROM druid.arrays",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.ARRAYS_DATASOURCE)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(
                    // these report as strings even though they are not, someday this will not be so
                    expressionVirtualColumn("v0", "array_append(\"arrayStringNulls\",'foo')", ColumnType.STRING_ARRAY),
                    expressionVirtualColumn("v1", "array_prepend('foo',\"arrayStringNulls\")", ColumnType.STRING_ARRAY),
                    expressionVirtualColumn("v10", "array_offset(\"arrayLongNulls\",0)", ColumnType.LONG),
                    expressionVirtualColumn("v11", "array_offset(\"arrayDoubleNulls\",0)", ColumnType.DOUBLE),
                    expressionVirtualColumn("v12", "array_ordinal(\"arrayStringNulls\",1)", ColumnType.STRING),
                    expressionVirtualColumn("v13", "array_ordinal(\"arrayLongNulls\",1)", ColumnType.LONG),
                    expressionVirtualColumn("v14", "array_ordinal(\"arrayDoubleNulls\",1)", ColumnType.DOUBLE),
                    expressionVirtualColumn("v2", "array_append(\"arrayLongNulls\",1)", ColumnType.LONG_ARRAY),
                    expressionVirtualColumn("v3", "array_prepend(2,\"arrayLongNulls\")", ColumnType.LONG_ARRAY),
                    expressionVirtualColumn("v4", "array_append(\"arrayDoubleNulls\",1.1)", ColumnType.DOUBLE_ARRAY),
                    expressionVirtualColumn("v5", "array_prepend(2.2,\"arrayDoubleNulls\")", ColumnType.DOUBLE_ARRAY),
                    expressionVirtualColumn("v6", "array_concat(\"arrayString\",\"arrayStringNulls\")", ColumnType.STRING_ARRAY),
                    expressionVirtualColumn("v7", "array_concat(\"arrayLong\",\"arrayLongNulls\")", ColumnType.LONG_ARRAY),
                    expressionVirtualColumn("v8", "array_concat(\"arrayDouble\",\"arrayDoubleNulls\")", ColumnType.DOUBLE_ARRAY),
                    expressionVirtualColumn("v9", "array_offset(\"arrayStringNulls\",0)", ColumnType.STRING)
                )
                .columns(
                    "arrayStringNulls",
                    "arrayLongNulls",
                    "arrayDoubleNulls",
                    "v0",
                    "v1",
                    "v2",
                    "v3",
                    "v4",
                    "v5",
                    "v6",
                    "v7",
                    "v8",
                    "v9",
                    "v10",
                    "v11",
                    "v12",
                    "v13",
                    "v14"
                )
                .columnTypes(resultSignature.getColumnTypes())
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        expectedResults,
        resultSignature
    );
  }

  @Test
  public void testSomeArrayFunctionsWithScanQueryNoStringify()
  {
    // when not stringifying arrays, some things are still stringified, because they are inferred to be typed as strings
    // the planner context which controls stringification of arrays does not apply to multi-valued string columns,
    // which will still always be stringified to ultimately adhere to the varchar type
    // as array support increases in the engine this will likely change since using explict array functions should
    // probably kick it into an array
    List<Object[]> expectedResults = ImmutableList.of(
        new Object[]{
            "",
            "a",
            "[\"a\",\"b\"]",
            Arrays.asList("a", "b", "c"),
            Arrays.asList(1L, 2L, 3L),
            Arrays.asList(1.9, 2.2, 4.3),
            Arrays.asList("a", "b", "foo"),
            Arrays.asList("foo", "a"),
            Arrays.asList(1L, 2L, 7L),
            Arrays.asList(null, 1L, 2L),
            Arrays.asList(1.2, 2.2, 1.0),
            Arrays.asList(null, 1.1, 2.2),
            Arrays.asList("a", "a", "b"),
            Arrays.asList(7L, null),
            Arrays.asList(1.0, null)
        }
    );
    testQuery(
        "SELECT"
        + " dim1,"
        + " dim2,"
        + " dim3,"
        + " ARRAY['a', 'b', 'c'],"
        + " ARRAY[1,2,3],"
        + " ARRAY[1.9, 2.2, 4.3],"
        + " ARRAY_APPEND(dim3, 'foo'),"
        + " ARRAY_PREPEND('foo', ARRAY[dim2]),"
        + " ARRAY_APPEND(ARRAY[1,2], l1),"
        + " ARRAY_PREPEND(l2, ARRAY[1,2]),"
        + " ARRAY_APPEND(ARRAY[1.2,2.2], dbl1),"
        + " ARRAY_PREPEND(dbl2, ARRAY[1.1,2.2]),"
        + " ARRAY_CONCAT(dim2,dim3),"
        + " ARRAY_CONCAT(ARRAY[l1],ARRAY[l2]),"
        + " ARRAY_CONCAT(ARRAY[dbl1],ARRAY[dbl2])"
        + " FROM druid.numfoo"
        + " LIMIT 1",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE3)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(
                    expressionVirtualColumn("v0", "array('a','b','c')", ColumnType.STRING_ARRAY),
                    expressionVirtualColumn("v1", "array(1,2,3)", ColumnType.LONG_ARRAY),
                    expressionVirtualColumn("v10", "array_concat(array(\"l1\"),array(\"l2\"))", ColumnType.LONG_ARRAY),
                    expressionVirtualColumn(
                        "v11",
                        "array_concat(array(\"dbl1\"),array(\"dbl2\"))",
                        ColumnType.DOUBLE_ARRAY
                    ),
                    expressionVirtualColumn("v2", "array(1.9,2.2,4.3)", ColumnType.DOUBLE_ARRAY),
                    expressionVirtualColumn("v3", "array_append(\"dim3\",'foo')", ColumnType.STRING_ARRAY),
                    expressionVirtualColumn("v4", "array_prepend('foo',array(\"dim2\"))", ColumnType.STRING_ARRAY),
                    expressionVirtualColumn("v5", "array_append(array(1,2),\"l1\")", ColumnType.LONG_ARRAY),
                    expressionVirtualColumn("v6", "array_prepend(\"l2\",array(1,2))", ColumnType.LONG_ARRAY),
                    expressionVirtualColumn("v7", "array_append(array(1.2,2.2),\"dbl1\")", ColumnType.DOUBLE_ARRAY),
                    expressionVirtualColumn("v8", "array_prepend(\"dbl2\",array(1.1,2.2))", ColumnType.DOUBLE_ARRAY),
                    expressionVirtualColumn("v9", "array_concat(\"dim2\",\"dim3\")", ColumnType.STRING_ARRAY)
                )
                .columns(
                    "dim1",
                    "dim2",
                    "dim3",
                    "v0",
                    "v1",
                    "v2",
                    "v3",
                    "v4",
                    "v5",
                    "v6",
                    "v7",
                    "v8",
                    "v9",
                    "v10",
                    "v11"
                )
                .columnTypes(ColumnType.STRING, ColumnType.STRING, ColumnType.STRING, ColumnType.ofArray(ColumnType.STRING), ColumnType.ofArray(ColumnType.LONG), ColumnType.ofArray(ColumnType.DOUBLE), ColumnType.ofArray(ColumnType.STRING), ColumnType.ofArray(ColumnType.STRING), ColumnType.ofArray(ColumnType.LONG), ColumnType.ofArray(ColumnType.LONG), ColumnType.ofArray(ColumnType.DOUBLE), ColumnType.ofArray(ColumnType.DOUBLE), ColumnType.ofArray(ColumnType.STRING), ColumnType.ofArray(ColumnType.LONG), ColumnType.ofArray(ColumnType.DOUBLE))
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(1)
                .context(QUERY_CONTEXT_NO_STRINGIFY_ARRAY)
                .build()
        ),
        expectedResults
    );
  }

  @Test
  public void testArrayOverlapFilter()
  {
    testQuery(
        "SELECT dim3 FROM druid.numfoo WHERE ARRAY_OVERLAP(dim3, ARRAY['a','b']) LIMIT 5",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE3)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(in("dim3", ImmutableList.of("a", "b")))
                .columns("dim3")
                .columnTypes(ColumnType.STRING)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"a\",\"b\"]"},
            new Object[]{"[\"b\",\"c\"]"}
        )
    );
  }

  @Test
  public void testArrayOverlapFilterStringArrayColumn()
  {
    testQuery(
        "SELECT arrayStringNulls FROM druid.arrays WHERE ARRAY_OVERLAP(arrayStringNulls, ARRAY['a','b']) LIMIT 5",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.ARRAYS_DATASOURCE)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(
                    or(
                        new ArrayContainsElementFilter("arrayStringNulls", ColumnType.STRING, "a", null),
                        new ArrayContainsElementFilter("arrayStringNulls", ColumnType.STRING, "b", null)
                    )
                )
                .columns("arrayStringNulls")
                .columnTypes(ColumnType.STRING_ARRAY)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"a\",\"b\"]"},
            new Object[]{"[\"b\",\"b\"]"},
            new Object[]{"[\"a\",\"b\"]"},
            new Object[]{"[\"d\",null,\"b\"]"},
            new Object[]{"[null,\"b\"]"}
        )
    );
  }

  @Test
  public void testArrayOverlapFilterLongArrayColumn()
  {
    testQuery(
        "SELECT arrayLongNulls FROM druid.arrays WHERE ARRAY_OVERLAP(arrayLongNulls, ARRAY[1, 2]) LIMIT 5",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.ARRAYS_DATASOURCE)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(
                    or(
                        new ArrayContainsElementFilter("arrayLongNulls", ColumnType.LONG, 1L, null),
                        new ArrayContainsElementFilter("arrayLongNulls", ColumnType.LONG, 2L, null)
                    )
                )
                .columns("arrayLongNulls")
                .columnTypes(ColumnType.LONG_ARRAY)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[2,3]"},
            new Object[]{"[1]"},
            new Object[]{"[null,2,9]"},
            new Object[]{"[1,null,3]"},
            new Object[]{"[1,2,3]"}
        )
    );
  }

  @Test
  public void testArrayOverlapFilterDoubleArrayColumn()
  {
    testQuery(
        "SELECT arrayDoubleNulls FROM druid.arrays WHERE ARRAY_OVERLAP(arrayDoubleNulls, ARRAY[1.1, 2.2]) LIMIT 5",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.ARRAYS_DATASOURCE)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(
                    or(
                        new ArrayContainsElementFilter("arrayDoubleNulls", ColumnType.DOUBLE, 1.1, null),
                        new ArrayContainsElementFilter("arrayDoubleNulls", ColumnType.DOUBLE, 2.2, null)
                    )
                )
                .columns("arrayDoubleNulls")
                .columnTypes(ColumnType.DOUBLE_ARRAY)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[1.1,2.2,null]"},
            new Object[]{"[null,2.2,null]"},
            new Object[]{"[null,1.1]"},
            new Object[]{"[1.1,2.2,null]"},
            new Object[]{"[null,2.2,null]"}
        )
    );
  }

  @Test
  public void testArrayOverlapFilterWithExtractionFn()
  {
    testQuery(
        "SELECT dim3 FROM druid.numfoo WHERE ARRAY_OVERLAP(SUBSTRING(dim3, 1, 1), ARRAY['a','b']) LIMIT 5",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE3)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(
                    new InDimFilter("dim3", ImmutableList.of("a", "b"), new SubstringDimExtractionFn(0, 1))
                )
                .columns("dim3")
                .columnTypes(ColumnType.STRING)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"a\",\"b\"]"},
            new Object[]{"[\"b\",\"c\"]"}
        )
    );
  }

  @Test
  public void testArrayOverlapFilterNonLiteral()
  {
    testQuery(
        "SELECT dim3 FROM druid.numfoo WHERE ARRAY_OVERLAP(dim3, ARRAY[dim2]) LIMIT 5",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE3)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(expressionFilter("array_overlap(\"dim3\",array(\"dim2\"))"))
                .columns("dim3")
                .columnTypes(ColumnType.STRING)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"a\",\"b\"]"}
        )
    );
  }

  @Test
  public void testArrayOverlapFilterArrayStringColumns()
  {
    testQuery(
        "SELECT arrayStringNulls, arrayString FROM druid.arrays WHERE ARRAY_OVERLAP(arrayStringNulls, arrayString) LIMIT 5",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.ARRAYS_DATASOURCE)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(expressionFilter("array_overlap(\"arrayStringNulls\",\"arrayString\")"))
                .columns("arrayStringNulls", "arrayString")
                .columnTypes(ColumnType.STRING_ARRAY, ColumnType.STRING_ARRAY)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"a\",\"b\"]", "[\"a\",\"b\"]"},
            new Object[]{"[\"d\",null,\"b\"]", "[\"b\",\"c\"]"},
            new Object[]{"[null,\"b\"]", "[\"a\",\"b\",\"c\"]"},
            new Object[]{"[\"d\",null,\"b\"]", "[\"b\",\"c\"]"},
            new Object[]{"[null,\"b\"]", "[\"a\",\"b\",\"c\"]"}
        )
    );
  }

  @Test
  public void testArrayOverlapFilterArrayLongColumns()
  {
    testQuery(
        "SELECT arrayLongNulls, arrayLong FROM druid.arrays WHERE ARRAY_OVERLAP(arrayLongNulls, arrayLong) LIMIT 5",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.ARRAYS_DATASOURCE)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(expressionFilter("array_overlap(\"arrayLongNulls\",\"arrayLong\")"))
                .columns("arrayLongNulls", "arrayLong")
                .columnTypes(ColumnType.LONG_ARRAY, ColumnType.LONG_ARRAY)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[1]", "[1,4]"},
            new Object[]{"[1,null,3]", "[1,2,3]"},
            new Object[]{"[1,2,3]", "[1,2,3,4]"},
            new Object[]{"[1,null,3]", "[1,2,3]"},
            new Object[]{"[1,2,3]", "[1,2,3,4]"}
        )
    );
  }

  @Test
  public void testArrayOverlapFilterArrayDoubleColumns()
  {
    testQuery(
        "SELECT arrayDoubleNulls, arrayDouble FROM druid.arrays WHERE ARRAY_OVERLAP(arrayDoubleNulls, arrayDouble) LIMIT 5",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.ARRAYS_DATASOURCE)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(expressionFilter("array_overlap(\"arrayDoubleNulls\",\"arrayDouble\")"))
                .columns("arrayDoubleNulls", "arrayDouble")
                .columnTypes(ColumnType.DOUBLE_ARRAY, ColumnType.DOUBLE_ARRAY)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[1.1,2.2,null]", "[1.1,2.2,3.3]"},
            new Object[]{"[999.0,null,5.5]", "[3.3,4.4,5.5]"},
            new Object[]{"[1.1,2.2,null]", "[1.1,2.2,3.3]"},
            new Object[]{"[999.0,null,5.5]", "[3.3,4.4,5.5]"}
        )
    );
  }

  @Test
  public void testArrayOverlapFilterWithDynamicParameter()
  {
    Druids.ScanQueryBuilder builder = newScanQueryBuilder()
            .dataSource(CalciteTests.DATASOURCE3)
            .intervals(querySegmentSpec(Filtration.eternity()))
            .filters(expressionFilter("array_overlap(array(1.0,1.7,null),array(\"dbl1\"))"))
            .columns("dim3")
            .columnTypes(ColumnType.STRING)
            .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
            .limit(5)
            .context(QUERY_CONTEXT_DEFAULT);

    testQuery(
            PLANNER_CONFIG_DEFAULT,
            QUERY_CONTEXT_DEFAULT,
            ImmutableList.of(
                    new SqlParameter(SqlType.ARRAY, Arrays.asList(1.0, 1.7, null))
            ),
            "SELECT dim3 FROM druid.numfoo WHERE ARRAY_OVERLAP(?, ARRAY[dbl1]) LIMIT 5",
            CalciteTests.REGULAR_USER_AUTH_RESULT,
            ImmutableList.of(builder.build()),
            ImmutableList.of(
                    new Object[]{"[\"a\",\"b\"]"},
                    new Object[]{"[\"b\",\"c\"]"},
                    new Object[]{""},
                    new Object[]{null},
                    new Object[]{null}
            )
    );
  }

  @Test
  public void testArrayContainsFilter()
  {
    testQuery(
        "SELECT dim3 FROM druid.numfoo WHERE ARRAY_CONTAINS(dim3, ARRAY['a','b']) LIMIT 5",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE3)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(
                    and(
                        equality("dim3", "a", ColumnType.STRING),
                        equality("dim3", "b", ColumnType.STRING)
                    )
                )
                .columns("dim3")
                .columnTypes(ColumnType.STRING)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"a\",\"b\"]"}
        )
    );
  }

  @Test
  public void testArrayContainsFilterArrayStringColumn()
  {
    testQuery(
        "SELECT arrayStringNulls FROM druid.arrays WHERE ARRAY_CONTAINS(arrayStringNulls, ARRAY['a','b']) LIMIT 5",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.ARRAYS_DATASOURCE)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(
                    and(
                        new ArrayContainsElementFilter("arrayStringNulls", ColumnType.STRING, "a", null),
                        new ArrayContainsElementFilter("arrayStringNulls", ColumnType.STRING, "b", null)
                    )

                )
                .columns("arrayStringNulls")
                .columnTypes(ColumnType.STRING_ARRAY)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"a\",\"b\"]"},
            new Object[]{"[\"a\",\"b\"]"},
            new Object[]{"[\"a\",\"b\"]"}
        )
    );
  }

  @Test
  public void testArrayContainsFilterArrayLongColumn()
  {
    testQuery(
        "SELECT arrayLongNulls FROM druid.arrays WHERE ARRAY_CONTAINS(arrayLongNulls, ARRAY[1, null]) LIMIT 5",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.ARRAYS_DATASOURCE)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(
                    and(
                        new ArrayContainsElementFilter("arrayLongNulls", ColumnType.LONG, 1L, null),
                        new ArrayContainsElementFilter("arrayLongNulls", ColumnType.LONG, null, null)
                    )
                )
                .columns("arrayLongNulls")
                .columnTypes(ColumnType.LONG_ARRAY)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[1,null,3]"},
            new Object[]{"[1,null,3]"}
        )
    );
  }

  @Test
  public void testArrayContainsFilterArrayDoubleColumn()
  {
    testQuery(
        "SELECT arrayDoubleNulls FROM druid.arrays WHERE ARRAY_CONTAINS(arrayDoubleNulls, ARRAY[1.1, null]) LIMIT 5",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.ARRAYS_DATASOURCE)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(
                    and(
                        new ArrayContainsElementFilter("arrayDoubleNulls", ColumnType.DOUBLE, 1.1, null),
                        new ArrayContainsElementFilter("arrayDoubleNulls", ColumnType.DOUBLE, null, null)
                    )
                )
                .columns("arrayDoubleNulls")
                .columnTypes(ColumnType.DOUBLE_ARRAY)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[1.1,2.2,null]"},
            new Object[]{"[null,1.1]"},
            new Object[]{"[1.1,2.2,null]"}
        )
    );
  }

  @Test
  public void testArrayContainsFilterWithExtractionFn()
  {
    Druids.ScanQueryBuilder builder = newScanQueryBuilder()
        .dataSource(CalciteTests.DATASOURCE3)
        .intervals(querySegmentSpec(Filtration.eternity()))
        .columns("dim3")
        .columnTypes(ColumnType.STRING)
        .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
        .limit(5)
        .context(QUERY_CONTEXT_DEFAULT)
        .virtualColumns(expressionVirtualColumn("v0", "substring(\"dim3\", 0, 1)", ColumnType.STRING))
        .filters(
            and(
                equality("v0", "a", ColumnType.STRING),
                equality("v0", "b", ColumnType.STRING)
            )
        );
    testQuery(
        "SELECT dim3 FROM druid.numfoo WHERE ARRAY_CONTAINS(SUBSTRING(dim3, 1, 1), ARRAY['a','b']) LIMIT 5",
        ImmutableList.of(builder.build()),
        ImmutableList.of(
            new Object[]{"[\"a\",\"b\"]"}
        )
    );
  }

  @Test
  public void testArrayContainsArrayOfOneElement()
  {
    testQuery(
        "SELECT dim3 FROM druid.numfoo WHERE ARRAY_CONTAINS(dim3, ARRAY['a']) LIMIT 5",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE3)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(equality("dim3", "a", ColumnType.STRING))
                .columns("dim3")
                .columnTypes(ColumnType.STRING)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"a\",\"b\"]"}
        )
    );
  }

  @Test
  public void testArrayContainsArrayOfNonLiteral()
  {
    testQuery(
        "SELECT dim3 FROM druid.numfoo WHERE ARRAY_CONTAINS(dim3, ARRAY[dim2]) LIMIT 5",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE3)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(expressionFilter("array_contains(\"dim3\",array(\"dim2\"))"))
                .columns("dim3")
                .columnTypes(ColumnType.STRING)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_NO_STRINGIFY_ARRAY)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"a\",\"b\"]"}
        )
    );
  }

  @Test
  public void testArrayContainsFilterArrayStringColumns()
  {
    testQuery(
        "SELECT arrayStringNulls, arrayString FROM druid.arrays WHERE ARRAY_CONTAINS(arrayStringNulls, arrayString) LIMIT 5",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.ARRAYS_DATASOURCE)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(
                    expressionFilter("array_contains(\"arrayStringNulls\",\"arrayString\")")
                )
                .columns("arrayStringNulls", "arrayString")
                .columnTypes(ColumnType.STRING_ARRAY, ColumnType.STRING_ARRAY)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"a\",\"b\"]", "[\"a\",\"b\"]"}
        )
    );
  }

  @Test
  public void testArrayContainsArrayStringColumns()
  {
    testQuery(
        "SELECT ARRAY_CONTAINS(arrayStringNulls, ARRAY['a', 'b']), ARRAY_CONTAINS(arrayStringNulls, arrayString) FROM druid.arrays LIMIT 5",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.ARRAYS_DATASOURCE)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .columns("v0", "v1")
                .columnTypes(ColumnType.LONG, ColumnType.LONG)
                .virtualColumns(
                    expressionVirtualColumn("v0", "array_contains(\"arrayStringNulls\",array('a','b'))", ColumnType.LONG),
                    expressionVirtualColumn("v1", "array_contains(\"arrayStringNulls\",\"arrayString\")", ColumnType.LONG)
                )
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{null, null},
            new Object[]{true, false},
            new Object[]{false, false},
            new Object[]{null, null},
            new Object[]{true, true}
        )
    );
  }

  @Test
  public void testArrayContainsFilterArrayLongColumns()
  {
    testQuery(
        "SELECT arrayLong, arrayLongNulls FROM druid.arrays WHERE ARRAY_CONTAINS(arrayLong, arrayLongNulls) LIMIT 5",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.ARRAYS_DATASOURCE)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(
                    expressionFilter("array_contains(\"arrayLong\",\"arrayLongNulls\")")
                )
                .columns("arrayLong", "arrayLongNulls")
                .columnTypes(ColumnType.LONG_ARRAY, ColumnType.LONG_ARRAY)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[1,2,3]", "[]"},
            new Object[]{"[1,4]", "[1]"},
            new Object[]{"[1,2,3,4]", "[1,2,3]"},
            new Object[]{"[1,2,3,4]", "[1,2,3]"}
        )
    );
  }

  @Test
  public void testArrayContainsFilterArrayDoubleColumns()
  {
    testQuery(
        "SELECT arrayDoubleNulls, arrayDouble FROM druid.arrays WHERE ARRAY_CONTAINS(arrayDoubleNulls, arrayDouble) LIMIT 5",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.ARRAYS_DATASOURCE)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(
                    expressionFilter("array_contains(\"arrayDoubleNulls\",\"arrayDouble\")")
                )
                .columns("arrayDoubleNulls", "arrayDouble")
                .columnTypes(ColumnType.DOUBLE_ARRAY, ColumnType.DOUBLE_ARRAY)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(5)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of()
    );
  }

  @Test
  public void testArrayContainsConstantNull()
  {
    testQuery(
        "SELECT ARRAY_CONTAINS(null, ARRAY['a','b'])",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(
                    InlineDataSource.fromIterable(
                        ImmutableList.of(new Object[]{null}),
                        RowSignature.builder().add("EXPR$0", ColumnType.LONG).build()
                    )
                )
                .intervals(querySegmentSpec(Filtration.eternity()))
                .columns("EXPR$0")
                .columnTypes(ColumnType.LONG)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{null}
        )
    );
  }

  @Test
  public void testArrayContainsFilterWithDynamicParameter()
  {
    Druids.ScanQueryBuilder builder = newScanQueryBuilder()
            .dataSource(CalciteTests.DATASOURCE3)
            .intervals(querySegmentSpec(Filtration.eternity()))
            .filters(expressionFilter("array_contains(array(1,null),array((\"dbl1\" > 1)))"))
            .columns("dim3")
            .columnTypes(ColumnType.STRING)
            .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
            .limit(5)
            .context(QUERY_CONTEXT_DEFAULT);

    testQuery(
            PLANNER_CONFIG_DEFAULT,
            QUERY_CONTEXT_DEFAULT,
            ImmutableList.of(
                    new SqlParameter(SqlType.ARRAY, Arrays.asList(true, null))
            ),
            "SELECT dim3 FROM druid.numfoo WHERE ARRAY_CONTAINS(?, ARRAY[dbl1>1]) LIMIT 5",
            CalciteTests.REGULAR_USER_AUTH_RESULT,
            ImmutableList.of(builder.build()),
            ImmutableList.of(
                    new Object[]{"[\"b\",\"c\"]"},
                    new Object[]{""},
                    new Object[]{null},
                    new Object[]{null}
            )
    );
  }

  @Test
  public void testScalarInArrayFilter()
  {
    testQuery(
        "SELECT dim2 FROM druid.numfoo\n"
        + "WHERE\n"
        + "  SCALAR_IN_ARRAY(dim2, ARRAY['a', 'd'])\n"
        + "  OR SCALAR_IN_ARRAY(SUBSTRING(dim1, 1, 1), ARRAY[NULL, 'foo', 'bar'])\n"
        + "  OR SCALAR_IN_ARRAY(cnt * 2, ARRAY[3])\n",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE3)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(
                    VirtualColumns.create(
                        ImmutableList.of(
                            expressionVirtualColumn("v0", "substring(\"dim1\", 0, 1)", ColumnType.STRING),
                            expressionVirtualColumn("v1", "(\"cnt\" * 2)", ColumnType.LONG)
                        )
                    )
                )
                .filters(
                    or(
                        in("dim2", Arrays.asList("a", "d")),
                        in("v0", Arrays.asList(null, "foo", "bar")),
                        in("v1", ColumnType.LONG, Collections.singletonList(3L))
                    )
                )
                .columns("dim2")
                .columnTypes(ColumnType.STRING)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"a"}
        )
    );
  }

  @Test
  public void testNotScalarInArrayFilter()
  {
    testQuery(
        "SELECT dim2 FROM druid.numfoo\n"
        + "WHERE NOT SCALAR_IN_ARRAY(dim2, ARRAY['a', 'd'])\n",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE3)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .filters(not(in("dim2", Arrays.asList("a", "d"))))
                .columns("dim2")
                .columnTypes(ColumnType.STRING)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{""},
            new Object[]{"abc"}
        )
    );
  }

  @Test
  public void testArrayScalarInFilter_MVD()
  {
    // In the fifth row, dim3 is an empty list. The Scan query in MSQ reads this with makeDimensionSelector, whereas
    // the Scan query in native reads this makeColumnValueSelector. Behavior of those selectors is inconsistent.
    // The DimensionSelector returns an empty list; the ColumnValueSelector returns a list containing a single null.
    final String expectedValueForEmptyMvd =
        queryFramework().engine().name().equals("msq-task")
        ? null
        : "not abd";

    testBuilder()
        .sql(
            "SELECT dim3, (CASE WHEN scalar_in_array(dim3, Array['a', 'b', 'd']) THEN 'abd' ELSE 'not abd' END) " +
            "FROM druid.numfoo"
        )
        .expectedQueries(
            ImmutableList.of(
                newScanQueryBuilder()
                    .dataSource(CalciteTests.DATASOURCE3)
                    .intervals(querySegmentSpec(Filtration.eternity()))
                    .virtualColumns(
                        expressionVirtualColumn(
                            "v0",
                            "case_searched(scalar_in_array(\"dim3\",array('a','b','d')),'abd','not abd')",
                            ColumnType.STRING
                        )
                    )
                    .columns("dim3", "v0")
                    .columnTypes(ColumnType.STRING, ColumnType.STRING)
                    .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                    .context(QUERY_CONTEXT_DEFAULT)
                    .build()
            )
        )
        .expectedResults(
            ImmutableList.of(
                new Object[]{"[\"a\",\"b\"]", "[\"abd\",\"abd\"]"},
                new Object[]{"[\"b\",\"c\"]", "[\"abd\",\"not abd\"]"},
                new Object[]{"d", "abd"},
                new Object[]{"", "not abd"},
                new Object[]{null, expectedValueForEmptyMvd},
                new Object[]{null, "not abd"}
            )
        )
        .run();
  }

  @Test
  public void testArraySlice()
  {
    testQuery(
        "SELECT ARRAY_SLICE(dim3, 1) FROM druid.numfoo",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            new Druids.ScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE3)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(expressionVirtualColumn("v0", "array_slice(\"dim3\",1)", ColumnType.STRING_ARRAY))
                .columns("v0")
                .columnTypes(ColumnType.STRING_ARRAY)
                .context(QUERY_CONTEXT_NO_STRINGIFY_ARRAY)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .build()
        ),
        ImmutableList.of(
            new Object[]{Collections.singletonList("b")},
            new Object[]{Collections.singletonList("c")},
            new Object[]{Collections.emptyList()},
            new Object[]{Collections.emptyList()},
            new Object[]{null},
            new Object[]{null}
        )
    );
  }

  @Test
  public void testArraySliceArrayColumns()
  {
    testQuery(
        "SELECT ARRAY_SLICE(arrayString, 1), ARRAY_SLICE(arrayLong, 2), ARRAY_SLICE(arrayDoubleNulls, 1) FROM druid.arrays",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            new Druids.ScanQueryBuilder()
                .dataSource(CalciteTests.ARRAYS_DATASOURCE)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(
                    expressionVirtualColumn("v0", "array_slice(\"arrayString\",1)", ColumnType.STRING_ARRAY),
                    expressionVirtualColumn("v1", "array_slice(\"arrayLong\",2)", ColumnType.LONG_ARRAY),
                    expressionVirtualColumn("v2", "array_slice(\"arrayDoubleNulls\",1)", ColumnType.DOUBLE_ARRAY)
                )
                .columns("v0", "v1", "v2")
                .columnTypes(ColumnType.STRING_ARRAY, ColumnType.LONG_ARRAY, ColumnType.DOUBLE_ARRAY)
                .context(QUERY_CONTEXT_NO_STRINGIFY_ARRAY)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .build()
        ),
        ImmutableList.of(
            new Object[]{null, Collections.singletonList(3L), null},
            new Object[]{null, null, Collections.emptyList()},
            new Object[]{ImmutableList.of("e"), Collections.emptyList(), null},
            new Object[]{ImmutableList.of("b"), null, Arrays.asList(5.5D, null)},
            new Object[]{ImmutableList.of("b"), Collections.singletonList(3L), Arrays.asList(2.2D, null)},
            new Object[]{ImmutableList.of("c"), Arrays.asList(3L, 4L), Arrays.asList(2.2D, null)},
            new Object[]{ImmutableList.of("b", "c"), Collections.emptyList(), Arrays.asList(null, 5.5D)},
            new Object[]{null, Collections.singletonList(3L), null},
            new Object[]{null, null, Collections.singletonList(1.1D)},
            new Object[]{ImmutableList.of("e"), Collections.emptyList(), null},
            new Object[]{ImmutableList.of("b"), null, Arrays.asList(5.5D, null)},
            new Object[]{ImmutableList.of("b"), Collections.singletonList(3L), Arrays.asList(2.2D, null)},
            new Object[]{ImmutableList.of("c"), Arrays.asList(3L, 4L), Arrays.asList(2.2D, null)},
            new Object[]{ImmutableList.of("b", "c"), Collections.emptyList(), Arrays.asList(null, 5.5D)}
        )
    );
  }

  @Test
  public void testArrayLength()
  {
    cannotVectorizeUnlessFallback();
    testQuery(
        "SELECT dim1, ARRAY_LENGTH(dim3), SUM(cnt) FROM druid.numfoo GROUP BY 1, 2 ORDER BY 2 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn("v0", "array_length(\"dim3\")", ColumnType.LONG))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("dim1", "d0", ColumnType.STRING),
                                new DefaultDimensionSpec("v0", "d1", ColumnType.LONG)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "d1",
                                OrderByColumnSpec.Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"", 2, 1L},
            new Object[]{"10.1", 2, 1L},
            new Object[]{"1", 1, 1L},
            new Object[]{"2", 1, 1L},
            new Object[]{"abc", null, 1L},
            new Object[]{"def", null, 1L}
        )
    );
  }

  @Test
  public void testArrayLengthArrayColumn()
  {
    // Cannot vectorize due to array expressions.
    cannotVectorize();

    testQuery(
        "SELECT arrayStringNulls, ARRAY_LENGTH(arrayStringNulls), SUM(cnt) FROM druid.arrays GROUP BY 1, 2 ORDER BY 2 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.ARRAYS_DATASOURCE)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn("v0", "array_length(\"arrayStringNulls\")", ColumnType.LONG))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("arrayStringNulls", "d0", ColumnType.STRING_ARRAY),
                                new DefaultDimensionSpec("v0", "d1", ColumnType.LONG)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec(
                                        "d1",
                                        OrderByColumnSpec.Direction.DESCENDING,
                                        StringComparators.NUMERIC
                                    )
                                ),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"d\",null,\"b\"]", 3, 2L},
            new Object[]{"[null,\"b\"]", 2, 2L},
            new Object[]{"[\"a\",\"b\"]", 2, 3L},
            new Object[]{"[\"b\",\"b\"]", 2, 2L},
            new Object[]{"[null]", 1, 1L},
            new Object[]{"[]", 0, 1L},
            new Object[]{null, null, 3L}
        )
    );
  }

  @Test
  public void testArrayAppend()
  {
    // Cannot vectorize due to array expressions.
    cannotVectorize();

    ImmutableList<Object[]> results = ImmutableList.of(
        new Object[]{null, 2L},
        new Object[]{ImmutableList.of("", "foo"), 1L},
        new Object[]{ImmutableList.of("a", "b", "foo"), 1L},
        new Object[]{ImmutableList.of("b", "c", "foo"), 1L},
        new Object[]{ImmutableList.of("d", "foo"), 1L}
    );
    testQuery(
        "SELECT ARRAY_APPEND(dim3, 'foo'), SUM(cnt) FROM druid.numfoo GROUP BY 1 ORDER BY 2 DESC",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn(
                            "v0",
                            "array_append(\"dim3\",'foo')",
                            ColumnType.STRING_ARRAY
                        ))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "d0", ColumnType.STRING_ARRAY)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                OrderByColumnSpec.Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_NO_STRINGIFY_ARRAY)
                        .build()
        ),
        results
    );
  }

  @Test
  public void testArrayPrepend()
  {
    // Cannot vectorize due to array expressions.
    cannotVectorize();

    ImmutableList<Object[]> results = ImmutableList.of(
        new Object[]{null, 2L},
        new Object[]{ImmutableList.of("foo", ""), 1L},
        new Object[]{ImmutableList.of("foo", "a", "b"), 1L},
        new Object[]{ImmutableList.of("foo", "b", "c"), 1L},
        new Object[]{ImmutableList.of("foo", "d"), 1L}
    );
    testQuery(
        "SELECT ARRAY_PREPEND('foo', dim3), SUM(cnt) FROM druid.numfoo GROUP BY 1 ORDER BY 2 DESC",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn(
                            "v0",
                            "array_prepend('foo',\"dim3\")",
                            ColumnType.STRING_ARRAY
                        ))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "d0", ColumnType.STRING_ARRAY)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                OrderByColumnSpec.Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_NO_STRINGIFY_ARRAY)
                        .build()
        ),
        results
    );
  }

  @Test
  public void testArrayPrependAppend()
  {
    cannotVectorizeUnlessFallback();
    ImmutableList<Object[]> results = ImmutableList.of(
        new Object[]{null, null, 2L},
        new Object[]{"foo,", ",foo", 1L},
        new Object[]{"foo,a,b", "a,b,foo", 1L},
        new Object[]{"foo,b,c", "b,c,foo", 1L},
        new Object[]{"foo,d", "d,foo", 1L}
    );
    testQuery(
        "SELECT ARRAY_TO_STRING(ARRAY_PREPEND('foo', dim3), ','), ARRAY_TO_STRING(ARRAY_APPEND(dim3, 'foo'), ','), SUM(cnt) FROM druid.numfoo GROUP BY 1,2 ORDER BY 3 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn(
                                "v0",
                                "array_to_string(array_prepend('foo',\"dim3\"),',')",
                                ColumnType.STRING
                            ),
                            expressionVirtualColumn(
                                "v1",
                                "array_to_string(array_append(\"dim3\",'foo'),',')",
                                ColumnType.STRING
                            )
                        )
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "d0", ColumnType.STRING),
                                new DefaultDimensionSpec("v1", "d1", ColumnType.STRING)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                OrderByColumnSpec.Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        results
    );
  }

  @Test
  public void testArrayConcat()
  {
    // Cannot vectorize due to array expressions.
    cannotVectorize();

    ImmutableList<Object[]> results = ImmutableList.of(
        new Object[]{null, 2L},
        new Object[]{ImmutableList.of("", ""), 1L},
        new Object[]{ImmutableList.of("a", "b", "a", "b"), 1L},
        new Object[]{ImmutableList.of("b", "c", "b", "c"), 1L},
        new Object[]{ImmutableList.of("d", "d"), 1L}
    );
    testQuery(
        "SELECT ARRAY_CONCAT(dim3, dim3), SUM(cnt) FROM druid.numfoo GROUP BY 1 ORDER BY 2 DESC",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn(
                            "v0",
                            "array_concat(\"dim3\",\"dim3\")",
                            ColumnType.STRING_ARRAY
                        ))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "d0", ColumnType.STRING_ARRAY)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                OrderByColumnSpec.Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_NO_STRINGIFY_ARRAY)
                        .build()
        ),
        results
    );
  }

  @Test
  public void testArrayOffset()
  {
    cannotVectorizeUnlessFallback();
    testQuery(
        "SELECT ARRAY_OFFSET(dim3, 1), SUM(cnt) FROM druid.numfoo GROUP BY 1 ORDER BY 2 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn("v0", "array_offset(\"dim3\",1)", ColumnType.STRING))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "d0", ColumnType.STRING)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                OrderByColumnSpec.Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{null, 4L},
            new Object[]{"b", 1L},
            new Object[]{"c", 1L}
        )
    );
  }

  @Test
  public void testArrayGroupAsLongArray()
  {
    // Cannot vectorize as we donot have support in native query subsytem for grouping on arrays
    cannotVectorize();
    testQuery(
        "SELECT ARRAY[l1], SUM(cnt) FROM druid.numfoo GROUP BY 1 ORDER BY 2 DESC",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn(
                            "v0",
                            "array(\"l1\")",
                            ColumnType.LONG_ARRAY
                        ))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "d0", ColumnType.LONG_ARRAY)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                OrderByColumnSpec.Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_NO_STRINGIFY_ARRAY)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{Collections.singletonList(null), 3L},
            new Object[]{ImmutableList.of(0L), 1L},
            new Object[]{ImmutableList.of(7L), 1L},
            new Object[]{ImmutableList.of(325323L), 1L}
        )
    );
  }

  @Test
  public void testArrayGroupAsLongArrayColumn()
  {
    // Cannot vectorize as we donot have support in native query subsytem for grouping on arrays
    cannotVectorize();
    testQuery(
        "SELECT arrayLongNulls, SUM(cnt) FROM druid.arrays GROUP BY 1 ORDER BY 2 DESC",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.ARRAYS_DATASOURCE)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("arrayLongNulls", "d0", ColumnType.LONG_ARRAY)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec(
                                        "a0",
                                        OrderByColumnSpec.Direction.DESCENDING,
                                        StringComparators.NUMERIC
                                    )
                                ),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_NO_STRINGIFY_ARRAY)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{null, 3L},
            new Object[]{Arrays.asList(null, 2L, 9L), 2L},
            new Object[]{Arrays.asList(1L, null, 3L), 2L},
            new Object[]{Arrays.asList(1L, 2L, 3L), 2L},
            new Object[]{Arrays.asList(2L, 3L), 2L},
            new Object[]{Collections.emptyList(), 1L},
            new Object[]{Collections.singletonList(null), 1L},
            new Object[]{Collections.singletonList(1L), 1L}
        )
    );
  }


  @Test
  public void testArrayGroupAsDoubleArray()
  {
    // Cannot vectorize as we donot have support in native query subsytem for grouping on arrays as keys
    cannotVectorize();
    testQuery(
        "SELECT ARRAY[dbl1], SUM(cnt) FROM druid.numfoo GROUP BY 1 ORDER BY 2 DESC",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn(
                            "v0",
                            "array(\"dbl1\")",
                            ColumnType.DOUBLE_ARRAY
                        ))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "d0", ColumnType.DOUBLE_ARRAY)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                OrderByColumnSpec.Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_NO_STRINGIFY_ARRAY)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{Collections.singletonList(null), 3L},
            new Object[]{ImmutableList.of(0.0), 1L},
            new Object[]{ImmutableList.of(1.0), 1L},
            new Object[]{ImmutableList.of(1.7), 1L}
        )
    );
  }

  @Test
  public void testArrayGroupAsDoubleArrayColumn()
  {
    // Cannot vectorize as we donot have support in native query subsytem for grouping on arrays
    cannotVectorize();
    testQuery(
        "SELECT arrayDoubleNulls, SUM(cnt) FROM druid.arrays GROUP BY 1 ORDER BY 2 DESC",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.ARRAYS_DATASOURCE)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("arrayDoubleNulls", "d0", ColumnType.DOUBLE_ARRAY)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec(
                                        "a0",
                                        OrderByColumnSpec.Direction.DESCENDING,
                                        StringComparators.NUMERIC
                                    )
                                ),
                                Integer.MAX_VALUE
                            )
                        )
                        .setContext(QUERY_CONTEXT_NO_STRINGIFY_ARRAY)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{null, 3L},
            new Object[]{Arrays.asList(null, 2.2D, null), 2L},
            new Object[]{Arrays.asList(1.1D, 2.2D, null), 2L},
            new Object[]{Arrays.asList(999.0D, null, 5.5D), 2L},
            new Object[]{Arrays.asList(999.0D, 5.5D, null), 2L},
            new Object[]{Collections.emptyList(), 1L},
            new Object[]{Collections.singletonList(null), 1L},
            new Object[]{Arrays.asList(null, 1.1D), 1L}
        )
    );
  }

  @Test
  public void testArrayGroupAsFloatArray()
  {
    // Cannot vectorize as we donot have support in native query subsytem for grouping on arrays as keys
    cannotVectorize();
    testQuery(
        "SELECT ARRAY[f1], SUM(cnt) FROM druid.numfoo GROUP BY 1 ORDER BY 2 DESC",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn(
                            "v0",
                            "array(\"f1\")",
                            ColumnType.ofArray(ColumnType.FLOAT)
                        ))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "d0", ColumnType.ofArray(ColumnType.FLOAT))
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                OrderByColumnSpec.Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_NO_STRINGIFY_ARRAY)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{Collections.singletonList(null), 3L},
            new Object[]{ImmutableList.of(0.0F), 1L},
            new Object[]{ImmutableList.of(0.10000000149011612F), 1L},
            new Object[]{ImmutableList.of(1.0F), 1L}
        )
    );
  }

  @Test
  public void testArrayGroupAsArrayWithFunction()
  {
    // Cannot vectorize due to array expressions.
    cannotVectorize();
    testQuery(
        "SELECT ARRAY[ARRAY_ORDINAL(dim3, 2)], SUM(cnt) FROM druid.numfoo GROUP BY 1 ORDER BY 2 DESC",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn(
                            "v0",
                            "array(array_ordinal(\"dim3\",2))",
                            ColumnType.STRING_ARRAY
                        ))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "d0", ColumnType.STRING_ARRAY)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                                          ImmutableList.of(new OrderByColumnSpec(
                                              "a0",
                                              OrderByColumnSpec.Direction.DESCENDING,
                                              StringComparators.NUMERIC
                                          )),
                                          Integer.MAX_VALUE
                                      )
                        )
                        .setContext(QUERY_CONTEXT_NO_STRINGIFY_ARRAY)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{Collections.singletonList(null), 4L},
            new Object[]{ImmutableList.of("b"), 1L},
            new Object[]{ImmutableList.of("c"), 1L}
        )
    );
  }

  @Test
  public void testArrayOrdinal()
  {
    cannotVectorizeUnlessFallback();
    testQuery(
        "SELECT ARRAY_ORDINAL(dim3, 2), SUM(cnt) FROM druid.numfoo GROUP BY 1 ORDER BY 2 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn(
                            "v0",
                            "array_ordinal(\"dim3\",2)",
                            ColumnType.STRING
                        ))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "d0", ColumnType.STRING)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                OrderByColumnSpec.Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{null, 4L},
            new Object[]{"b", 1L},
            new Object[]{"c", 1L}
        )
    );
  }

  @Test
  public void testArrayOffsetOf()
  {
    cannotVectorizeUnlessFallback();
    testQuery(
        "SELECT ARRAY_OFFSET_OF(dim3, 'b'), SUM(cnt) FROM druid.numfoo GROUP BY 1 ORDER BY 2 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn(
                            "v0",
                            "array_offset_of(\"dim3\",'b')",
                            ColumnType.LONG
                        ))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "d0", ColumnType.LONG)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                OrderByColumnSpec.Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{null, 4L},
            new Object[]{0, 1L},
            new Object[]{1, 1L}
        )
    );
  }

  @Test
  public void testArrayOrdinalOf()
  {
    cannotVectorizeUnlessFallback();
    testQuery(
        "SELECT ARRAY_ORDINAL_OF(dim3, 'b'), SUM(cnt) FROM druid.numfoo GROUP BY 1 ORDER BY 2 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn(
                            "v0",
                            "array_ordinal_of(\"dim3\",'b')",
                            ColumnType.LONG
                        ))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "d0", ColumnType.LONG)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                OrderByColumnSpec.Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{null, 4L},
            new Object[]{1, 1L},
            new Object[]{2, 1L}
        )
    );
  }

  @Test
  public void testArrayToString()
  {
    cannotVectorizeUnlessFallback();
    ImmutableList<Object[]> results = ImmutableList.of(
        new Object[]{null, 2L},
        new Object[]{"", 1L},
        new Object[]{"a,b", 1L},
        new Object[]{"b,c", 1L},
        new Object[]{"d", 1L}
    );
    testQuery(
        "SELECT ARRAY_TO_STRING(dim3, ','), SUM(cnt) FROM druid.numfoo GROUP BY 1 ORDER BY 2 DESC",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(expressionVirtualColumn(
                            "v0",
                            "array_to_string(\"dim3\",',')",
                            ColumnType.STRING
                        ))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v0", "d0", ColumnType.STRING)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                OrderByColumnSpec.Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        results
    );
  }

  @Test
  public void testArrayToStringToMultiValueString()
  {
    // Cannot vectorize due to array expressions.
    cannotVectorize();

    ImmutableList<Object[]> results = ImmutableList.of(
        new Object[]{ImmutableList.of("", "d"), 1L},
        new Object[]{ImmutableList.of("a", "b", "d"), 1L},
        new Object[]{ImmutableList.of("b", "c", "d"), 1L},
        new Object[]{ImmutableList.of("d", "d"), 1L}
    );
    testQuery(
        "SELECT STRING_TO_ARRAY(CONCAT(ARRAY_TO_STRING(dim3, ','), ',d'), ','), SUM(cnt) FROM druid.numfoo WHERE ARRAY_LENGTH(dim3) > 0 GROUP BY 1 ORDER BY 2 DESC",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.DATASOURCE3)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn("v0", "array_length(\"dim3\")", ColumnType.LONG),
                            expressionVirtualColumn(
                                "v1",
                                "string_to_array(concat(array_to_string(\"dim3\",','),',d'),',')",
                                ColumnType.STRING_ARRAY
                            )
                        )
                        .setDimFilter(range("v0", ColumnType.LONG, 0L, null, true, false))
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("v1", "d0", ColumnType.STRING_ARRAY)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(new DefaultLimitSpec(
                            ImmutableList.of(new OrderByColumnSpec(
                                "a0",
                                OrderByColumnSpec.Direction.DESCENDING,
                                StringComparators.NUMERIC
                            )),
                            Integer.MAX_VALUE
                        ))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        results
    );
  }

  @Test
  public void testArrayAgg()
  {
    cannotVectorize();
    testQuery(
        "SELECT ARRAY_AGG(dim1), ARRAY_AGG(DISTINCT dim1), ARRAY_AGG(DISTINCT dim1) FILTER(WHERE dim1 = 'shazbot') FROM foo WHERE dim1 is not null",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .filters(notNull("dim1"))
                  .aggregators(
                      aggregators(
                          new ExpressionLambdaAggregatorFactory(
                              "a0",
                              ImmutableSet.of("dim1"),
                              "__acc",
                              "ARRAY<STRING>[]",
                              "ARRAY<STRING>[]",
                              true,
                              true,
                              false,
                              "array_append(\"__acc\", \"dim1\")",
                              "array_concat(\"__acc\", \"a0\")",
                              null,
                              null,
                              ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                              TestExprMacroTable.INSTANCE
                          ),
                          new ExpressionLambdaAggregatorFactory(
                              "a1",
                              ImmutableSet.of("dim1"),
                              "__acc",
                              "ARRAY<STRING>[]",
                              "ARRAY<STRING>[]",
                              true,
                              true,
                              false,
                              "array_set_add(\"__acc\", \"dim1\")",
                              "array_set_add_all(\"__acc\", \"a1\")",
                              null,
                              null,
                              ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                              TestExprMacroTable.INSTANCE
                          ),
                          new FilteredAggregatorFactory(
                              new ExpressionLambdaAggregatorFactory(
                                  "a2",
                                  ImmutableSet.of("dim1"),
                                  "__acc",
                                  "ARRAY<STRING>[]",
                                  "ARRAY<STRING>[]",
                                  true,
                                  true,
                                  false,
                                  "array_set_add(\"__acc\", \"dim1\")",
                                  "array_set_add_all(\"__acc\", \"a2\")",
                                  null,
                                  null,
                                  ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                                  TestExprMacroTable.INSTANCE
                              ),
                              equality("dim1", "shazbot", ColumnType.STRING)
                          )
                      )
                  )
                  .context(QUERY_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{
                "[\"\",\"10.1\",\"2\",\"1\",\"def\",\"abc\"]",
                "[\"\",\"1\",\"10.1\",\"2\",\"abc\",\"def\"]",
                null
            }
        )
    );
  }

  @Test
  public void testArrayAggMultiValue()
  {
    cannotVectorize();
    testQuery(
        "SELECT ARRAY_AGG(dim3), ARRAY_AGG(DISTINCT dim3) FROM foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(
                      aggregators(
                          new ExpressionLambdaAggregatorFactory(
                              "a0",
                              ImmutableSet.of("dim3"),
                              "__acc",
                              "ARRAY<STRING>[]",
                              "ARRAY<STRING>[]",
                              true,
                              true,
                              false,
                              "array_append(\"__acc\", \"dim3\")",
                              "array_concat(\"__acc\", \"a0\")",
                              null,
                              null,
                              ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                              TestExprMacroTable.INSTANCE
                          ),
                          new ExpressionLambdaAggregatorFactory(
                              "a1",
                              ImmutableSet.of("dim3"),
                              "__acc",
                              "ARRAY<STRING>[]",
                              "ARRAY<STRING>[]",
                              true,
                              true,
                              false,
                              "array_set_add(\"__acc\", \"dim3\")",
                              "array_set_add_all(\"__acc\", \"a1\")",
                              null,
                              null,
                              ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                              TestExprMacroTable.INSTANCE
                          )
                      )
                  )
                  .context(QUERY_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"a\",\"b\",\"b\",\"c\",\"d\",\"\",null,null]", "[null,\"\",\"a\",\"b\",\"c\",\"d\"]"}
        )
    );
  }

  @Test
  public void testArrayAggNumeric()
  {
    cannotVectorize();
    testQuery(
        "SELECT ARRAY_AGG(l1), ARRAY_AGG(DISTINCT l1), ARRAY_AGG(dbl1), ARRAY_AGG(DISTINCT dbl1), ARRAY_AGG(f1), ARRAY_AGG(DISTINCT f1) FROM numfoo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE3)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(
                      aggregators(
                          new ExpressionLambdaAggregatorFactory(
                              "a0",
                              ImmutableSet.of("l1"),
                              "__acc",
                              "ARRAY<LONG>[]",
                              "ARRAY<LONG>[]",
                              true,
                              true,
                              false,
                              "array_append(\"__acc\", \"l1\")",
                              "array_concat(\"__acc\", \"a0\")",
                              null,
                              null,
                              ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                              TestExprMacroTable.INSTANCE
                          ),
                          new ExpressionLambdaAggregatorFactory(
                              "a1",
                              ImmutableSet.of("l1"),
                              "__acc",
                              "ARRAY<LONG>[]",
                              "ARRAY<LONG>[]",
                              true,
                              true,
                              false,
                              "array_set_add(\"__acc\", \"l1\")",
                              "array_set_add_all(\"__acc\", \"a1\")",
                              null,
                              null,
                              ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                              TestExprMacroTable.INSTANCE
                          ),
                          new ExpressionLambdaAggregatorFactory(
                              "a2",
                              ImmutableSet.of("dbl1"),
                              "__acc",
                              "ARRAY<DOUBLE>[]",
                              "ARRAY<DOUBLE>[]",
                              true,
                              true,
                              false,
                              "array_append(\"__acc\", \"dbl1\")",
                              "array_concat(\"__acc\", \"a2\")",
                              null,
                              null,
                              ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                              TestExprMacroTable.INSTANCE
                          ),
                          new ExpressionLambdaAggregatorFactory(
                              "a3",
                              ImmutableSet.of("dbl1"),
                              "__acc",
                              "ARRAY<DOUBLE>[]",
                              "ARRAY<DOUBLE>[]",
                              true,
                              true,
                              false,
                              "array_set_add(\"__acc\", \"dbl1\")",
                              "array_set_add_all(\"__acc\", \"a3\")",
                              null,
                              null,
                              ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                              TestExprMacroTable.INSTANCE
                          ),
                          new ExpressionLambdaAggregatorFactory(
                              "a4",
                              ImmutableSet.of("f1"),
                              "__acc",
                              "ARRAY<DOUBLE>[]",
                              "ARRAY<DOUBLE>[]",
                              true,
                              true,
                              false,
                              "array_append(\"__acc\", \"f1\")",
                              "array_concat(\"__acc\", \"a4\")",
                              null,
                              null,
                              ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                              TestExprMacroTable.INSTANCE
                          ),
                          new ExpressionLambdaAggregatorFactory(
                              "a5",
                              ImmutableSet.of("f1"),
                              "__acc",
                              "ARRAY<DOUBLE>[]",
                              "ARRAY<DOUBLE>[]",
                              true,
                              true,
                              false,
                              "array_set_add(\"__acc\", \"f1\")",
                              "array_set_add_all(\"__acc\", \"a5\")",
                              null,
                              null,
                              ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                              TestExprMacroTable.INSTANCE
                          )
                      )
                  )
                  .context(QUERY_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{
                "[7,325323,0,null,null,null]",
                "[null,0,7,325323]",
                "[1.0,1.7,0.0,null,null,null]",
                "[null,0.0,1.0,1.7]",
                "[1.0,0.10000000149011612,0.0,null,null,null]",
                "[null,0.0,0.10000000149011612,1.0]"
            }
        )
    );
  }

  @Test
  public void testArrayAggQuantile()
  {
    cannotVectorize();
    testQuery(
        "SELECT ARRAY_QUANTILE(ARRAY_AGG(l1), 0.9) FROM numfoo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE3)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(
                      aggregators(
                          new ExpressionLambdaAggregatorFactory(
                              "a0",
                              ImmutableSet.of("l1"),
                              "__acc",
                              "ARRAY<LONG>[]",
                              "ARRAY<LONG>[]",
                              true,
                              true,
                              false,
                              "array_append(\"__acc\", \"l1\")",
                              "array_concat(\"__acc\", \"a0\")",
                              null,
                              null,
                              ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                              TestExprMacroTable.INSTANCE
                          )
                      )
                  )
                  .postAggregators(
                      expressionPostAgg("p0", "array_quantile(\"a0\",0.9)", ColumnType.DOUBLE)
                  )
                  .context(QUERY_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(new Object[]{260259.80000000002})
    );
  }

  @Test
  public void testArrayAggArrays()
  {
    // Produces nested array - ARRAY<ARRAY<LONG>>, which frame writers don't support. A way to get this query
    // to run would be to use nested columns.
    msqIncompatible();
    cannotVectorize();
    testQuery(
        "SELECT ARRAY_AGG(ARRAY[l1, l2]), ARRAY_AGG(DISTINCT ARRAY[l1, l2]) FROM numfoo",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE3)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .virtualColumns(
                      expressionVirtualColumn("v0", "array(\"l1\",\"l2\")", ColumnType.LONG_ARRAY)
                  )
                  .aggregators(
                      aggregators(
                          new ExpressionLambdaAggregatorFactory(
                              "a0",
                              ImmutableSet.of("v0"),
                              "__acc",
                              "ARRAY<ARRAY<LONG>>[]",
                              "ARRAY<ARRAY<LONG>>[]",
                              true,
                              true,
                              false,
                              "array_append(\"__acc\", \"v0\")",
                              "array_concat(\"__acc\", \"a0\")",
                              null,
                              null,
                              ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                              TestExprMacroTable.INSTANCE
                          ),
                          new ExpressionLambdaAggregatorFactory(
                              "a1",
                              ImmutableSet.of("v0"),
                              "__acc",
                              "ARRAY<ARRAY<LONG>>[]",
                              "ARRAY<ARRAY<LONG>>[]",
                              true,
                              true,
                              false,
                              "array_set_add(\"__acc\", \"v0\")",
                              "array_set_add_all(\"__acc\", \"a1\")",
                              null,
                              null,
                              ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                              TestExprMacroTable.INSTANCE
                          )
                      )
                  )
                  .context(QUERY_CONTEXT_NO_STRINGIFY_ARRAY)
                  .build()
        ),
        (sql, queryResults) -> {
          // ordering is not stable in array_agg and array_concat_agg
          List<Object[]> expected = ImmutableList.of(
              new Object[]{
                  Arrays.asList(
                      Arrays.asList(7L, null),
                      Arrays.asList(325323L, 325323L),
                      Arrays.asList(0L, 0L),
                      Arrays.asList(null, null),
                      Arrays.asList(null, null),
                      Arrays.asList(null, null)
                  ),
                  Arrays.asList(
                      Arrays.asList(null, null),
                      Arrays.asList(0L, 0L),
                      Arrays.asList(7L, null),
                      Arrays.asList(325323L, 325323L)
                  )
              }
          );
          assertResultsDeepEquals(sql, expected, queryResults.results);
        }
    );
  }

  @Test
  public void testArrayAggArraysWithMaxSizeBytes()
  {
    // Produces nested array - ARRAY<ARRAY<LONG>>, which frame writers don't support. A way to get this query
    // to run would be to use nested columns.
    msqIncompatible();
    cannotVectorize();
    testQuery(
        "SELECT ARRAY_AGG(ARRAY[l1, l2], 10000), ARRAY_AGG(DISTINCT ARRAY[l1, l2], CAST(10000 AS INTEGER)) FROM numfoo",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE3)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .virtualColumns(
                      expressionVirtualColumn("v0", "array(\"l1\",\"l2\")", ColumnType.LONG_ARRAY)
                  )
                  .aggregators(
                      aggregators(
                          new ExpressionLambdaAggregatorFactory(
                              "a0",
                              ImmutableSet.of("v0"),
                              "__acc",
                              "ARRAY<ARRAY<LONG>>[]",
                              "ARRAY<ARRAY<LONG>>[]",
                              true,
                              true,
                              false,
                              "array_append(\"__acc\", \"v0\")",
                              "array_concat(\"__acc\", \"a0\")",
                              null,
                              null,
                              new HumanReadableBytes(10000),
                              TestExprMacroTable.INSTANCE
                          ),
                          new ExpressionLambdaAggregatorFactory(
                              "a1",
                              ImmutableSet.of("v0"),
                              "__acc",
                              "ARRAY<ARRAY<LONG>>[]",
                              "ARRAY<ARRAY<LONG>>[]",
                              true,
                              true,
                              false,
                              "array_set_add(\"__acc\", \"v0\")",
                              "array_set_add_all(\"__acc\", \"a1\")",
                              null,
                              null,
                              new HumanReadableBytes(10000),
                              TestExprMacroTable.INSTANCE
                          )
                      )
                  )
                  .context(QUERY_CONTEXT_NO_STRINGIFY_ARRAY)
                  .build()
        ),
        (sql, queryResults) -> {
          // ordering is not stable in array_agg and array_concat_agg
          List<Object[]> expected = ImmutableList.of(
              new Object[]{
                  Arrays.asList(
                      Arrays.asList(7L, null),
                      Arrays.asList(325323L, 325323L),
                      Arrays.asList(0L, 0L),
                      Arrays.asList(null, null),
                      Arrays.asList(null, null),
                      Arrays.asList(null, null)
                  ),
                  Arrays.asList(
                      Arrays.asList(null, null),
                      Arrays.asList(0L, 0L),
                      Arrays.asList(7L, null),
                      Arrays.asList(325323L, 325323L)
                  )
              }
          );
          assertResultsDeepEquals(sql, expected, queryResults.results);
        }
    );
  }


  @Test
  public void testArrayConcatAggArrays()
  {
    cannotVectorize();
    testQuery(
        "SELECT ARRAY_CONCAT_AGG(ARRAY[l1, l2]), ARRAY_CONCAT_AGG(DISTINCT ARRAY[l1, l2]) FROM numfoo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE3)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .virtualColumns(
                      expressionVirtualColumn("v0", "array(\"l1\",\"l2\")", ColumnType.LONG_ARRAY)
                  )
                  .aggregators(
                      aggregators(
                          new ExpressionLambdaAggregatorFactory(
                              "a0",
                              ImmutableSet.of("v0"),
                              "__acc",
                              "ARRAY<LONG>[]",
                              "ARRAY<LONG>[]",
                              true,
                              false,
                              false,
                              "array_concat(\"__acc\", \"v0\")",
                              "array_concat(\"__acc\", \"a0\")",
                              null,
                              null,
                              ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                              TestExprMacroTable.INSTANCE
                          ),
                          new ExpressionLambdaAggregatorFactory(
                              "a1",
                              ImmutableSet.of("v0"),
                              "__acc",
                              "ARRAY<LONG>[]",
                              "ARRAY<LONG>[]",
                              true,
                              false,
                              false,
                              "array_set_add_all(\"__acc\", \"v0\")",
                              "array_set_add_all(\"__acc\", \"a1\")",
                              null,
                              null,
                              ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                              TestExprMacroTable.INSTANCE
                          )
                      )
                  )
                  .context(QUERY_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"[7,null,325323,325323,0,0,null,null,null,null,null,null]", "[null,0,7,325323]"}
        )
    );
  }

  @Test
  public void testArrayConcatAggArraysWithMaxSizeBytes()
  {
    cannotVectorize();
    testQuery(
        "SELECT ARRAY_CONCAT_AGG(ARRAY[l1, l2], 10000), ARRAY_CONCAT_AGG(DISTINCT ARRAY[l1, l2], CAST(10000 AS INTEGER)) "
        + "FROM numfoo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE3)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .virtualColumns(
                      expressionVirtualColumn("v0", "array(\"l1\",\"l2\")", ColumnType.LONG_ARRAY)
                  )
                  .aggregators(
                      aggregators(
                          new ExpressionLambdaAggregatorFactory(
                              "a0",
                              ImmutableSet.of("v0"),
                              "__acc",
                              "ARRAY<LONG>[]",
                              "ARRAY<LONG>[]",
                              true,
                              false,
                              false,
                              "array_concat(\"__acc\", \"v0\")",
                              "array_concat(\"__acc\", \"a0\")",
                              null,
                              null,
                              new HumanReadableBytes(10000),
                              TestExprMacroTable.INSTANCE
                          ),
                          new ExpressionLambdaAggregatorFactory(
                              "a1",
                              ImmutableSet.of("v0"),
                              "__acc",
                              "ARRAY<LONG>[]",
                              "ARRAY<LONG>[]",
                              true,
                              false,
                              false,
                              "array_set_add_all(\"__acc\", \"v0\")",
                              "array_set_add_all(\"__acc\", \"a1\")",
                              null,
                              null,
                              new HumanReadableBytes(10000),
                              TestExprMacroTable.INSTANCE
                          )
                      )
                  )
                  .context(QUERY_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"[7,null,325323,325323,0,0,null,null,null,null,null,null]", "[null,0,7,325323]"}
        )
    );
  }



  @Test
  public void testArrayAggArrayColumns()
  {
    msqIncompatible();
    // nested array party
    cannotVectorize();
    testQuery(
        "SELECT ARRAY_AGG(arrayLongNulls), ARRAY_AGG(DISTINCT arrayDouble), ARRAY_AGG(DISTINCT arrayStringNulls) FILTER(WHERE arrayLong = ARRAY[2,3]) FROM arrays WHERE arrayDoubleNulls is not null",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.ARRAYS_DATASOURCE)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .filters(notNull("arrayDoubleNulls"))
                  .aggregators(
                      aggregators(
                          new ExpressionLambdaAggregatorFactory(
                              "a0",
                              ImmutableSet.of("arrayLongNulls"),
                              "__acc",
                              "ARRAY<ARRAY<LONG>>[]",
                              "ARRAY<ARRAY<LONG>>[]",
                              true,
                              true,
                              false,
                              "array_append(\"__acc\", \"arrayLongNulls\")",
                              "array_concat(\"__acc\", \"a0\")",
                              null,
                              null,
                              ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                              TestExprMacroTable.INSTANCE
                          ),
                          new ExpressionLambdaAggregatorFactory(
                              "a1",
                              ImmutableSet.of("arrayDouble"),
                              "__acc",
                              "ARRAY<ARRAY<DOUBLE>>[]",
                              "ARRAY<ARRAY<DOUBLE>>[]",
                              true,
                              true,
                              false,
                              "array_set_add(\"__acc\", \"arrayDouble\")",
                              "array_set_add_all(\"__acc\", \"a1\")",
                              null,
                              null,
                              ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                              TestExprMacroTable.INSTANCE
                          ),
                          new FilteredAggregatorFactory(
                              new ExpressionLambdaAggregatorFactory(
                                  "a2",
                                  ImmutableSet.of("arrayStringNulls"),
                                  "__acc",
                                  "ARRAY<ARRAY<STRING>>[]",
                                  "ARRAY<ARRAY<STRING>>[]",
                                  true,
                                  true,
                                  false,
                                  "array_set_add(\"__acc\", \"arrayStringNulls\")",
                                  "array_set_add_all(\"__acc\", \"a2\")",
                                  null,
                                  null,
                                  ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                                  TestExprMacroTable.INSTANCE
                              ),
                              equality("arrayLong", ImmutableList.of(2, 3), ColumnType.LONG_ARRAY)
                          )
                      )
                  )
                  .context(QUERY_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{
                "[[2,3],[null,2,9],[1,null,3],[1,2,3],null,null,[2,3],[null,2,9],[1,null,3],[1,2,3],null]",
                "[null,[1.1,2.2,3.3],[1.1,3.3],[3.3,4.4,5.5]]",
                "[[null,\"b\"]]"
            }
        )
    );
  }

  @Test
  public void testArrayConcatAggArrayColumns()
  {
    cannotVectorize();
    testQuery(
        "SELECT ARRAY_CONCAT_AGG(arrayLongNulls), ARRAY_CONCAT_AGG(DISTINCT arrayDouble), ARRAY_CONCAT_AGG(DISTINCT arrayStringNulls) FILTER(WHERE arrayLong = ARRAY[2,3]) FROM arrays WHERE arrayDoubleNulls is not null",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.ARRAYS_DATASOURCE)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .filters(notNull("arrayDoubleNulls"))
                  .aggregators(
                      aggregators(
                          new ExpressionLambdaAggregatorFactory(
                              "a0",
                              ImmutableSet.of("arrayLongNulls"),
                              "__acc",
                              "ARRAY<LONG>[]",
                              "ARRAY<LONG>[]",
                              true,
                              false,
                              false,
                              "array_concat(\"__acc\", \"arrayLongNulls\")",
                              "array_concat(\"__acc\", \"a0\")",
                              null,
                              null,
                              ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                              TestExprMacroTable.INSTANCE
                          ),
                          new ExpressionLambdaAggregatorFactory(
                              "a1",
                              ImmutableSet.of("arrayDouble"),
                              "__acc",
                              "ARRAY<DOUBLE>[]",
                              "ARRAY<DOUBLE>[]",
                              true,
                              false,
                              false,
                              "array_set_add_all(\"__acc\", \"arrayDouble\")",
                              "array_set_add_all(\"__acc\", \"a1\")",
                              null,
                              null,
                              ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                              TestExprMacroTable.INSTANCE
                          ),
                          new FilteredAggregatorFactory(
                              new ExpressionLambdaAggregatorFactory(
                                  "a2",
                                  ImmutableSet.of("arrayStringNulls"),
                                  "__acc",
                                  "ARRAY<STRING>[]",
                                  "ARRAY<STRING>[]",
                                  true,
                                  false,
                                  false,
                                  "array_set_add_all(\"__acc\", \"arrayStringNulls\")",
                                  "array_set_add_all(\"__acc\", \"a2\")",
                                  null,
                                  null,
                                  ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                                  TestExprMacroTable.INSTANCE
                              ),
                              equality("arrayLong", ImmutableList.of(2, 3), ColumnType.LONG_ARRAY)
                          )
                      )
                  )
                  .context(QUERY_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{
                "[2,3,null,2,9,1,null,3,1,2,3,2,3,null,2,9,1,null,3,1,2,3]",
                "[1.1,2.2,3.3,4.4,5.5]",
                "[null,\"b\"]"
            }
        )
    );
  }

  @Test
  public void testArrayAggToString()
  {
    cannotVectorize();
    testQuery(
        "SELECT ARRAY_TO_STRING(ARRAY_AGG(DISTINCT dim1), ',') FROM foo WHERE dim1 is not null",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .filters(notNull("dim1"))
                  .aggregators(
                      aggregators(
                          new ExpressionLambdaAggregatorFactory(
                              "a0",
                              ImmutableSet.of("dim1"),
                              "__acc",
                              "ARRAY<STRING>[]",
                              "ARRAY<STRING>[]",
                              true,
                              true,
                              false,
                              "array_set_add(\"__acc\", \"dim1\")",
                              "array_set_add_all(\"__acc\", \"a0\")",
                              null,
                              null,
                              ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                              TestExprMacroTable.INSTANCE
                          )
                      )
                  )
                  .postAggregators(expressionPostAgg("p0", "array_to_string(\"a0\",',')", ColumnType.STRING))
                  .context(QUERY_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{",1,10.1,2,abc,def"}
        )
    );
  }

  @Test
  public void testArrayAggExpression()
  {
    cannotVectorize();
    testQuery(
        "SELECT ARRAY_TO_STRING(ARRAY_AGG(DISTINCT CONCAT(dim1, dim2)), ',') FROM foo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE1)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .virtualColumns(
                      expressionVirtualColumn("v0", "concat(\"dim1\",\"dim2\")", ColumnType.STRING)
                  )
                  .aggregators(
                      aggregators(
                          new ExpressionLambdaAggregatorFactory(
                              "a0",
                              ImmutableSet.of("v0"),
                              "__acc",
                              "ARRAY<STRING>[]",
                              "ARRAY<STRING>[]",
                              true,
                              true,
                              false,
                              "array_set_add(\"__acc\", \"v0\")",
                              "array_set_add_all(\"__acc\", \"a0\")",
                              null,
                              null,
                              ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                              TestExprMacroTable.INSTANCE
                          )
                      )
                  )
                  .postAggregators(expressionPostAgg("p0", "array_to_string(\"a0\",',')", ColumnType.STRING))
                  .context(QUERY_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"null,1a,2,a,defabc"}
        )
    );
  }

  @Test
  public void testArrayAggMaxBytes()
  {
    cannotVectorize();
    testQuery(
        "SELECT ARRAY_AGG(l1, 128), ARRAY_AGG(DISTINCT l1, CAST(128 AS INTEGER)) FROM numfoo",
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(CalciteTests.DATASOURCE3)
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .granularity(Granularities.ALL)
                  .aggregators(
                      aggregators(
                          new ExpressionLambdaAggregatorFactory(
                              "a0",
                              ImmutableSet.of("l1"),
                              "__acc",
                              "ARRAY<LONG>[]",
                              "ARRAY<LONG>[]",
                              true,
                              true,
                              false,
                              "array_append(\"__acc\", \"l1\")",
                              "array_concat(\"__acc\", \"a0\")",
                              null,
                              null,
                              new HumanReadableBytes(128),
                              TestExprMacroTable.INSTANCE
                          ),
                          new ExpressionLambdaAggregatorFactory(
                              "a1",
                              ImmutableSet.of("l1"),
                              "__acc",
                              "ARRAY<LONG>[]",
                              "ARRAY<LONG>[]",
                              true,
                              true,
                              false,
                              "array_set_add(\"__acc\", \"l1\")",
                              "array_set_add_all(\"__acc\", \"a1\")",
                              null,
                              null,
                              new HumanReadableBytes(128),
                              TestExprMacroTable.INSTANCE
                          )
                      )
                  )
                  .context(QUERY_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"[7,325323,0,null,null,null]", "[null,0,7,325323]"}
        )
    );
  }

  @Test
  public void testArrayAggAsArrayFromJoin()
  {
    cannotVectorize();
    List<Object[]> expectedResults = ImmutableList.of(
        new Object[]{"a", "[\"\",\"10.1\",\"2\"]", ",10.1,2"},
        new Object[]{"a", "[\"\",\"10.1\",\"2\"]", ",10.1,2"},
        new Object[]{"a", "[\"\",\"10.1\",\"2\"]", ",10.1,2"},
        new Object[]{"b", "[\"1\",\"abc\",\"def\"]", "1,abc,def"},
        new Object[]{"b", "[\"1\",\"abc\",\"def\"]", "1,abc,def"},
        new Object[]{"b", "[\"1\",\"abc\",\"def\"]", "1,abc,def"}
    );
    testQuery(
        "SELECT numfoo.dim4, j.arr, ARRAY_TO_STRING(j.arr, ',') FROM numfoo INNER JOIN (SELECT dim4, ARRAY_AGG(DISTINCT dim1) as arr FROM numfoo WHERE dim1 is not null GROUP BY 1) as j ON numfoo.dim4 = j.dim4",
        QUERY_CONTEXT_DEFAULT,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(
                      join(
                          new TableDataSource(CalciteTests.DATASOURCE3),
                          new QueryDataSource(
                              GroupByQuery.builder()
                                          .setDataSource(CalciteTests.DATASOURCE3)
                                          .setInterval(querySegmentSpec(Filtration.eternity()))
                                          .setGranularity(Granularities.ALL)
                                          .setDimFilter(notNull("dim1"))
                                          .setDimensions(new DefaultDimensionSpec("dim4", "d0"))
                                          .setAggregatorSpecs(
                                              aggregators(
                                                  new ExpressionLambdaAggregatorFactory(
                                                      "a0",
                                                      ImmutableSet.of("dim1"),
                                                      "__acc",
                                                      "ARRAY<STRING>[]",
                                                      "ARRAY<STRING>[]",
                                                      true,
                                                      true,
                                                      false,
                                                      "array_set_add(\"__acc\", \"dim1\")",
                                                      "array_set_add_all(\"__acc\", \"a0\")",
                                                      null,
                                                      null,
                                                      ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                                                      TestExprMacroTable.INSTANCE
                                                  )
                                              )
                                          )
                                          .setContext(QUERY_CONTEXT_DEFAULT)
                                          .build()
                          ),
                          "j0.",
                          "(\"dim4\" == \"j0.d0\")",
                          JoinType.INNER,
                          null
                      )
                  )
                  .virtualColumns(
                      expressionVirtualColumn("v0", "array_to_string(\"j0.a0\",',')", ColumnType.STRING)
                  )
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .columns("dim4", "j0.a0", "v0")
                  .columnTypes(ColumnType.STRING, ColumnType.STRING_ARRAY, ColumnType.STRING)
                  .context(QUERY_CONTEXT_DEFAULT)
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .build()

        ),
        expectedResults
    );
  }

  @Test
  public void testArrayAggGroupByArrayAggFromSubquery()
  {
    final Map<String, Object> context =
        QueryContexts.override(QUERY_CONTEXT_NO_STRINGIFY_ARRAY, PlannerConfig.CTX_KEY_USE_LEXICOGRAPHIC_TOPN, true);
    testQuery(
        "SELECT dim2, arr, COUNT(*) FROM (SELECT dim2, ARRAY_AGG(DISTINCT dim1) as arr FROM foo WHERE dim1 is not null GROUP BY 1 LIMIT 5) GROUP BY 1,2",
        context,
        ImmutableList.of(
            new TopNQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE1)
                .dimension(new DefaultDimensionSpec(
                    "dim2",
                    "d0",
                    ColumnType.STRING
                ))
                .metric(new DimensionTopNMetricSpec(
                    null,
                    StringComparators.LEXICOGRAPHIC
                ))
                .filters(notNull("dim1"))
                .threshold(5)
                .aggregators(new ExpressionLambdaAggregatorFactory(
                    "a0",
                    ImmutableSet.of("dim1"),
                    "__acc",
                    "ARRAY<STRING>[]",
                    "ARRAY<STRING>[]",
                    true,
                    true,
                    false,
                    "array_set_add(\"__acc\", \"dim1\")",
                    "array_set_add_all(\"__acc\", \"a0\")",
                    null,
                    null,
                    new HumanReadableBytes(1024),
                    ExprMacroTable.nil()
                ))
                .intervals(querySegmentSpec(Filtration.eternity()))
                .granularity(Granularities.ALL)
                .context(context)
                .postAggregators(expressionPostAgg("s0", "1", ColumnType.LONG))
                .build()
        ),
        ImmutableList.of(
            new Object[]{null, ImmutableList.of("10.1", "abc"), 1L},
            new Object[]{"", ImmutableList.of("2"), 1L},
            new Object[]{"a", ImmutableList.of("", "1"), 1L},
            new Object[]{"abc", ImmutableList.of("def"), 1L}
        )
    );
  }

  @SqlTestFrameworkConfig.NumMergeBuffers(3)
  @Test
  public void testArrayAggGroupByArrayAggOfLongsFromSubquery()
  {
    cannotVectorize();
    testQuery(
        "select cntarray, count(*) from ( select dim1, dim2, ARRAY_AGG(cnt) as cntarray from ( select dim1, dim2, dim3, count(*) as cnt from foo group by 1, 2, 3 ) group by 1, 2 ) group by 1",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(new QueryDataSource(
                            GroupByQuery.builder()
                                        .setDataSource(new QueryDataSource(
                                            GroupByQuery.builder()
                                                        .setDataSource(new TableDataSource(CalciteTests.DATASOURCE1))
                                                        .setQuerySegmentSpec(querySegmentSpec(Filtration.eternity()))
                                                        .setGranularity(Granularities.ALL)
                                                        .setInterval(querySegmentSpec(Filtration.eternity()))
                                                        .setContext(QUERY_CONTEXT_NO_STRINGIFY_ARRAY)
                                                        .setDimensions(
                                                            new DefaultDimensionSpec("dim1", "d0"),
                                                            new DefaultDimensionSpec("dim2", "d1"),
                                                            new DefaultDimensionSpec("dim3", "d2"
                                                            )
                                                        )
                                                        .setAggregatorSpecs(
                                                            new CountAggregatorFactory("a0"))
                                                        .build()))
                                        .setQuerySegmentSpec(
                                            querySegmentSpec(Filtration.eternity()))
                                        .setGranularity(Granularities.ALL)
                                        .setDimensions(
                                            new DefaultDimensionSpec("d0", "_d0"),
                                            new DefaultDimensionSpec("d1", "_d1")
                                        )
                                        .setAggregatorSpecs(new ExpressionLambdaAggregatorFactory(
                                            "_a0",
                                            ImmutableSet.of("a0"),
                                            "__acc",
                                            "ARRAY<LONG>[]",
                                            "ARRAY<LONG>[]",
                                            true,
                                            true,
                                            false,
                                            "array_append(\"__acc\", \"a0\")",
                                            "array_concat(\"__acc\", \"_a0\")",
                                            null,
                                            null,
                                            new HumanReadableBytes(1024),
                                            ExprMacroTable.nil()
                                        ))
                                        .build()))
                        .setQuerySegmentSpec(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setContext(QUERY_CONTEXT_NO_STRINGIFY_ARRAY)
                        .setDimensions(new DefaultDimensionSpec("_a0", "d0", ColumnType.LONG_ARRAY))
                        .setAggregatorSpecs(new CountAggregatorFactory("a0"))
                        .build()
        ),
        ImmutableList.of(
            new Object[]{ImmutableList.of(1L), 4L},
            new Object[]{ImmutableList.of(1L, 1L), 2L}
        )
    );
  }

  @SqlTestFrameworkConfig.NumMergeBuffers(3)
  @Test
  public void testArrayAggGroupByArrayAggOfStringsFromSubquery()
  {
    cannotVectorize();
    testQuery(
        "select cntarray, count(*) from ( select dim1, dim2, ARRAY_AGG(cnt) as cntarray from ( select dim1, dim2, dim3, cast( count(*) as VARCHAR ) as cnt from foo group by 1, 2, 3 ) group by 1, 2 ) group by 1",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(new QueryDataSource(
                            GroupByQuery.builder()
                                        .setDataSource(new QueryDataSource(
                                            GroupByQuery.builder()
                                                        .setDataSource(new TableDataSource(CalciteTests.DATASOURCE1))
                                                        .setQuerySegmentSpec(querySegmentSpec(Filtration.eternity()))
                                                        .setGranularity(Granularities.ALL)
                                                        .setInterval(querySegmentSpec(Filtration.eternity()))
                                                        .setContext(QUERY_CONTEXT_NO_STRINGIFY_ARRAY)
                                                        .setDimensions(
                                                            new DefaultDimensionSpec("dim1", "d0"),
                                                            new DefaultDimensionSpec("dim2", "d1"),
                                                            new DefaultDimensionSpec("dim3", "d2")
                                                        )
                                                        .setAggregatorSpecs(new CountAggregatorFactory("a0"))
                                                        .build()))
                                        .setQuerySegmentSpec(querySegmentSpec(Filtration.eternity()))
                                        .setGranularity(Granularities.ALL)
                                        .setDimensions(
                                            new DefaultDimensionSpec("d0", "_d0"),
                                            new DefaultDimensionSpec("d1", "_d1")
                                        )
                                        .setAggregatorSpecs(new ExpressionLambdaAggregatorFactory(
                                            "_a0",
                                            ImmutableSet.of("a0"),
                                            "__acc",
                                            "ARRAY<STRING>[]",
                                            "ARRAY<STRING>[]",
                                            true,
                                            true,
                                            false,
                                            "array_append(\"__acc\", \"a0\")",
                                            "array_concat(\"__acc\", \"_a0\")",
                                            null,
                                            null,
                                            new HumanReadableBytes(1024),
                                            ExprMacroTable.nil()
                                        ))
                                        .build()))
                        .setQuerySegmentSpec(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setContext(QUERY_CONTEXT_NO_STRINGIFY_ARRAY)
                        .setDimensions(new DefaultDimensionSpec("_a0", "d0", ColumnType.STRING_ARRAY))
                        .setAggregatorSpecs(new CountAggregatorFactory("a0"))
                        .build()
        ),
        ImmutableList.of(
            new Object[]{ImmutableList.of("1"), 4L},
            new Object[]{ImmutableList.of("1", "1"), 2L}
        )
    );
  }

  @SqlTestFrameworkConfig.NumMergeBuffers(3)
  @Test
  public void testArrayAggGroupByArrayAggOfDoubleFromSubquery()
  {
    cannotVectorize();
    testQuery(
        "select cntarray, count(*) from ( select dim1, dim2, ARRAY_AGG(cnt) as cntarray from ( select dim1, dim2, dim3, cast( count(*) as DOUBLE ) as cnt from foo group by 1, 2, 3 ) group by 1, 2 ) group by 1",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            GroupByQuery
                .builder()
                .setDataSource(new QueryDataSource(
                    GroupByQuery.builder()
                                .setDataSource(new QueryDataSource(
                                    GroupByQuery.builder()
                                                .setDataSource(new TableDataSource(CalciteTests.DATASOURCE1))
                                                .setQuerySegmentSpec(querySegmentSpec(Filtration.eternity()))
                                                .setGranularity(Granularities.ALL)
                                                .setInterval(querySegmentSpec(Filtration.eternity()))
                                                .setContext(QUERY_CONTEXT_NO_STRINGIFY_ARRAY)
                                                .setDimensions(
                                                    new DefaultDimensionSpec("dim1", "d0"),
                                                    new DefaultDimensionSpec("dim2", "d1"),
                                                    new DefaultDimensionSpec("dim3", "d2")
                                                )
                                                .setAggregatorSpecs(new CountAggregatorFactory("a0"))
                                                .build()))
                                .setQuerySegmentSpec(querySegmentSpec(Filtration.eternity()))
                                .setGranularity(Granularities.ALL)
                                .setDimensions(
                                    new DefaultDimensionSpec("d0", "_d0"),
                                    new DefaultDimensionSpec("d1", "_d1")
                                )
                                .setAggregatorSpecs(new ExpressionLambdaAggregatorFactory(
                                    "_a0",
                                    ImmutableSet.of("a0"),
                                    "__acc",
                                    "ARRAY<DOUBLE>[]",
                                    "ARRAY<DOUBLE>[]",
                                    true,
                                    true,
                                    false,
                                    "array_append(\"__acc\", \"a0\")",
                                    "array_concat(\"__acc\", \"_a0\")",
                                    null,
                                    null,
                                    new HumanReadableBytes(1024),
                                    ExprMacroTable.nil()
                                ))
                                .build()))
                .setQuerySegmentSpec(querySegmentSpec(Filtration.eternity()))
                .setGranularity(Granularities.ALL)
                .setInterval(querySegmentSpec(Filtration.eternity()))
                .setContext(QUERY_CONTEXT_NO_STRINGIFY_ARRAY)
                .setDimensions(new DefaultDimensionSpec("_a0", "d0", ColumnType.DOUBLE_ARRAY))
                .setAggregatorSpecs(new CountAggregatorFactory("a0"))
                .build()
        ),
        ImmutableList.of(
            new Object[]{ImmutableList.of(1.0), 4L},
            new Object[]{ImmutableList.of(1.0, 1.0), 2L}
        )
    );
  }

  @Test
  public void testArrayAggArrayContainsSubquery()
  {
    cannotVectorize();
    List<Object[]> expectedResults = ImmutableList.of(
        new Object[]{"", "a"},
        new Object[]{"10.1", null},
        new Object[]{"2", ""},
        new Object[]{"1", "a"},
        new Object[]{"def", "abc"},
        new Object[]{"abc", null}
    );
    testQuery(
        "SELECT dim1,dim2 FROM foo WHERE ARRAY_CONTAINS((SELECT ARRAY_AGG(DISTINCT dim1) FROM foo WHERE dim1 is not null), dim1)",
        QUERY_CONTEXT_DEFAULT,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(
                      join(
                          new TableDataSource(CalciteTests.DATASOURCE1),
                          new QueryDataSource(
                              Druids.newTimeseriesQueryBuilder()
                                    .dataSource(CalciteTests.DATASOURCE1)
                                    .intervals(querySegmentSpec(Filtration.eternity()))
                                    .granularity(Granularities.ALL)
                                    .filters(notNull("dim1"))
                                    .aggregators(
                                        aggregators(
                                            new ExpressionLambdaAggregatorFactory(
                                                "a0",
                                                ImmutableSet.of("dim1"),
                                                "__acc",
                                                "ARRAY<STRING>[]",
                                                "ARRAY<STRING>[]",
                                                true,
                                                true,
                                                false,
                                                "array_set_add(\"__acc\", \"dim1\")",
                                                "array_set_add_all(\"__acc\", \"a0\")",
                                                null,
                                                null,
                                                ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                                                TestExprMacroTable.INSTANCE
                                            )
                                        )
                                    )
                                    .context(QUERY_CONTEXT_DEFAULT)
                                    .build()
                          ),
                          "j0.",
                          "1",
                          JoinType.LEFT,
                          null
                      )
                  )
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .filters(
                      new ExpressionDimFilter(
                          "array_contains(\"j0.a0\",\"dim1\")",
                          TestExprMacroTable.INSTANCE
                      )
                  )
                  .columns("dim1", "dim2")
                  .columnTypes(ColumnType.STRING, ColumnType.STRING)
                  .context(QUERY_CONTEXT_DEFAULT)
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .build()

        ),
        expectedResults
    );
  }

  @Test
  public void testArrayAggGroupByArrayContainsSubquery()
  {
    cannotVectorize();
    List<Object[]> expectedResults = ImmutableList.of(
        new Object[]{null, 2L},
        new Object[]{"", 1L},
        new Object[]{"a", 2L},
        new Object[]{"abc", 1L}
    );
    testQuery(
        "SELECT dim2, COUNT(*) FROM foo WHERE ARRAY_CONTAINS((SELECT ARRAY_AGG(DISTINCT dim1) FROM foo WHERE dim1 is not null), dim1) GROUP BY 1",
        QUERY_CONTEXT_DEFAULT,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(
                            join(
                                new TableDataSource(CalciteTests.DATASOURCE1),
                                new QueryDataSource(
                                    Druids.newTimeseriesQueryBuilder()
                                          .dataSource(CalciteTests.DATASOURCE1)
                                          .intervals(querySegmentSpec(Filtration.eternity()))
                                          .granularity(Granularities.ALL)
                                          .filters(notNull("dim1"))
                                          .aggregators(
                                              aggregators(
                                                  new ExpressionLambdaAggregatorFactory(
                                                      "a0",
                                                      ImmutableSet.of("dim1"),
                                                      "__acc",
                                                      "ARRAY<STRING>[]",
                                                      "ARRAY<STRING>[]",
                                                      true,
                                                      true,
                                                      false,
                                                      "array_set_add(\"__acc\", \"dim1\")",
                                                      "array_set_add_all(\"__acc\", \"a0\")",
                                                      null,
                                                      null,
                                                      ExpressionLambdaAggregatorFactory.DEFAULT_MAX_SIZE_BYTES,
                                                      TestExprMacroTable.INSTANCE
                                                  )
                                              )
                                          )
                                          .context(QUERY_CONTEXT_DEFAULT)
                                          .build()
                                ),
                                "j0.",
                                "1",
                                JoinType.LEFT,
                                null
                            )
                        )
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setDimFilter(
                            new ExpressionDimFilter(
                                "array_contains(\"j0.a0\",\"dim1\")",
                                TestExprMacroTable.INSTANCE
                            )
                        )
                        .setDimensions(dimensions(new DefaultDimensionSpec("dim2", "d0")))
                        .setAggregatorSpecs(aggregators(new CountAggregatorFactory("a0")))
                        .setGranularity(Granularities.ALL)
                        .setLimitSpec(NoopLimitSpec.instance())
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()

        ),
        expectedResults
    );

  }

  @NotYetSupported(Modes.UNNEST_INLINED)
  @Test
  public void testUnnestInline()
  {
    testQuery(
        "SELECT * FROM UNNEST(ARRAY[1,2,3])",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(
                      InlineDataSource.fromIterable(
                          ImmutableList.of(
                              new Object[]{1L},
                              new Object[]{2L},
                              new Object[]{3L}
                          ),
                          RowSignature.builder().add("EXPR$0", ColumnType.LONG).build()
                      )
                  )
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("EXPR$0")
                  .columnTypes(ColumnType.LONG)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1},
            new Object[]{2},
            new Object[]{3}
        )
    );
  }

  @NotYetSupported(Modes.UNNEST_INLINED)
  @Test
  public void testUnnestInlineWithCount()
  {
    cannotVectorize();
    testQuery(
        "SELECT COUNT(*) FROM (select c from UNNEST(ARRAY[1,2,3]) as unnested(c))",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(
                      InlineDataSource.fromIterable(
                          ImmutableList.of(
                              new Object[]{1L},
                              new Object[]{2L},
                              new Object[]{3L}
                          ),
                          RowSignature.builder().add("EXPR$0", ColumnType.LONG).build()
                      )
                  )
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .context(QUERY_CONTEXT_UNNEST)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .build()
        ),
        ImmutableList.of(
            new Object[]{3L}
        )
    );
  }

  @NotYetSupported(Modes.UNNEST_RESULT_MISMATCH)
  @Test
  public void testUnnest()
  {
    testQuery(
        "SELECT d3 FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE3),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{"b"},
            new Object[]{"c"},
            new Object[]{"d"},
            new Object[]{""},
            new Object[]{null},
            new Object[]{null}
        )
    );
  }

  @Test
  public void testUnnestArrayColumnsString()
  {
    testQuery(
        "SELECT a FROM druid.arrays, UNNEST(arrayString) as unnested (a)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.ARRAYS_DATASOURCE),
                      expressionVirtualColumn("j0.unnest", "\"arrayString\"", ColumnType.STRING_ARRAY),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"d"},
            new Object[]{"e"},
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{"b"},
            new Object[]{"c"},
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{"c"},
            new Object[]{"d"},
            new Object[]{"e"},
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{"b"},
            new Object[]{"c"},
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{"c"}
        )
    );
  }

  @Test
  public void testUnnestArrayColumnsStringThenFunction()
  {
    // Regresson test for https://github.com/apache/druid/issues/16543.
    testQuery(
        "SELECT a || '.txt' FROM druid.arrays, UNNEST(arrayString) as unnested (a)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.ARRAYS_DATASOURCE),
                      expressionVirtualColumn("j0.unnest", "\"arrayString\"", ColumnType.STRING_ARRAY),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .virtualColumns(expressionVirtualColumn("v0", "concat(\"j0.unnest\",'.txt')", ColumnType.STRING))
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("v0")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"d.txt"},
            new Object[]{"e.txt"},
            new Object[]{"a.txt"},
            new Object[]{"b.txt"},
            new Object[]{"a.txt"},
            new Object[]{"b.txt"},
            new Object[]{"b.txt"},
            new Object[]{"c.txt"},
            new Object[]{"a.txt"},
            new Object[]{"b.txt"},
            new Object[]{"c.txt"},
            new Object[]{"d.txt"},
            new Object[]{"e.txt"},
            new Object[]{"a.txt"},
            new Object[]{"b.txt"},
            new Object[]{"a.txt"},
            new Object[]{"b.txt"},
            new Object[]{"b.txt"},
            new Object[]{"c.txt"},
            new Object[]{"a.txt"},
            new Object[]{"b.txt"},
            new Object[]{"c.txt"}
        )
    );
  }

  @Test
  public void testUnnestArrayColumnsStringNulls()
  {
    testQuery(
        "SELECT a FROM druid.arrays, UNNEST(arrayStringNulls) as unnested (a)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.ARRAYS_DATASOURCE),
                      expressionVirtualColumn("j0.unnest", "\"arrayStringNulls\"", ColumnType.STRING_ARRAY),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{"b"},
            new Object[]{"b"},
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{"d"},
            new Object[]{null},
            new Object[]{"b"},
            new Object[]{null},
            new Object[]{"b"},
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{"b"},
            new Object[]{"b"},
            new Object[]{null},
            new Object[]{"d"},
            new Object[]{null},
            new Object[]{"b"},
            new Object[]{null},
            new Object[]{"b"}
        )
    );
  }

  @Test
  public void testUnnestArrayColumnsLong()
  {
    testQuery(
        "SELECT a FROM druid.arrays, UNNEST(arrayLong) as unnested (a)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.ARRAYS_DATASOURCE),
                      expressionVirtualColumn("j0.unnest", "\"arrayLong\"", ColumnType.LONG_ARRAY),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.LONG)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1L},
            new Object[]{2L},
            new Object[]{3L},
            new Object[]{1L},
            new Object[]{4L},
            new Object[]{1L},
            new Object[]{2L},
            new Object[]{3L},
            new Object[]{1L},
            new Object[]{2L},
            new Object[]{3L},
            new Object[]{4L},
            new Object[]{2L},
            new Object[]{3L},
            new Object[]{1L},
            new Object[]{2L},
            new Object[]{3L},
            new Object[]{1L},
            new Object[]{4L},
            new Object[]{1L},
            new Object[]{2L},
            new Object[]{3L},
            new Object[]{1L},
            new Object[]{2L},
            new Object[]{3L},
            new Object[]{4L},
            new Object[]{2L},
            new Object[]{3L}
        )
    );
  }

  @Test
  public void testUnnestArrayColumnsLongNulls()
  {
    testQuery(
        "SELECT a FROM druid.arrays, UNNEST(arrayLongNulls) as unnested (a)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.ARRAYS_DATASOURCE),
                      expressionVirtualColumn("j0.unnest", "\"arrayLongNulls\"", ColumnType.LONG_ARRAY),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.LONG)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{2L},
            new Object[]{3L},
            new Object[]{1L},
            new Object[]{null},
            new Object[]{2L},
            new Object[]{9L},
            new Object[]{1L},
            new Object[]{null},
            new Object[]{3L},
            new Object[]{1L},
            new Object[]{2L},
            new Object[]{3L},
            new Object[]{2L},
            new Object[]{3L},
            new Object[]{null},
            new Object[]{null},
            new Object[]{2L},
            new Object[]{9L},
            new Object[]{1L},
            new Object[]{null},
            new Object[]{3L},
            new Object[]{1L},
            new Object[]{2L},
            new Object[]{3L}
        )
    );
  }

  @Test
  public void testUnnestArrayColumnsDouble()
  {
    testQuery(
        "SELECT a FROM druid.arrays, UNNEST(arrayDouble) as unnested (a)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.ARRAYS_DATASOURCE),
                      expressionVirtualColumn("j0.unnest", "\"arrayDouble\"", ColumnType.DOUBLE_ARRAY),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.DOUBLE)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1.1D},
            new Object[]{2.2D},
            new Object[]{3.3D},
            new Object[]{2.2D},
            new Object[]{3.3D},
            new Object[]{4.0D},
            new Object[]{1.1D},
            new Object[]{2.2D},
            new Object[]{3.3D},
            new Object[]{1.1D},
            new Object[]{3.3D},
            new Object[]{3.3D},
            new Object[]{4.4D},
            new Object[]{5.5D},
            new Object[]{1.1D},
            new Object[]{2.2D},
            new Object[]{3.3D},
            new Object[]{2.2D},
            new Object[]{3.3D},
            new Object[]{4.0D},
            new Object[]{1.1D},
            new Object[]{2.2D},
            new Object[]{3.3D},
            new Object[]{1.1D},
            new Object[]{3.3D},
            new Object[]{3.3D},
            new Object[]{4.4D},
            new Object[]{5.5D}
        )
    );
  }

  @Test
  public void testUnnestArrayColumnsDoubleNulls()
  {
    testQuery(
        "SELECT a FROM druid.arrays, UNNEST(arrayDoubleNulls) as unnested (a)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.ARRAYS_DATASOURCE),
                      expressionVirtualColumn("j0.unnest", "\"arrayDoubleNulls\"", ColumnType.DOUBLE_ARRAY),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.DOUBLE)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{null},
            new Object[]{999.0D},
            new Object[]{5.5D},
            new Object[]{null},
            new Object[]{1.1D},
            new Object[]{2.2D},
            new Object[]{null},
            new Object[]{null},
            new Object[]{2.2D},
            new Object[]{null},
            new Object[]{999.0D},
            new Object[]{null},
            new Object[]{5.5D},
            new Object[]{null},
            new Object[]{1.1D},
            new Object[]{999.0D},
            new Object[]{5.5D},
            new Object[]{null},
            new Object[]{1.1D},
            new Object[]{2.2D},
            new Object[]{null},
            new Object[]{null},
            new Object[]{2.2D},
            new Object[]{null},
            new Object[]{999.0D},
            new Object[]{null},
            new Object[]{5.5D}
        )
    );
  }

  @NotYetSupported(Modes.UNNEST_RESULT_MISMATCH)
  @Test
  public void testUnnestTwice()
  {
    testQuery(
        "SELECT dim1, MV_TO_ARRAY(dim3), STRING_TO_ARRAY(dim1, U&'\\005C.') AS dim1_split, dim1_split_unnest, dim3_unnest\n"
        + "FROM\n"
        + "  druid.numfoo,\n"
        + "  UNNEST(STRING_TO_ARRAY(dim1, U&'\\005C.')) as t2 (dim1_split_unnest),\n"
        + "  UNNEST(MV_TO_ARRAY(dim3)) as t3 (dim3_unnest)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(
                      UnnestDataSource.create(
                          UnnestDataSource.create(
                              new TableDataSource(CalciteTests.DATASOURCE3),
                              expressionVirtualColumn(
                                  "j0.unnest",
                                  "string_to_array(\"dim1\",'\\u005C.')",
                                  ColumnType.STRING_ARRAY
                              ),
                              null
                          ),
                          nestedExpressionVirtualColumn("_j0.unnest", "\"dim3\"", ColumnType.STRING),
                          null
                      )
                  )
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .virtualColumns(
                      expressionVirtualColumn(
                          "v0",
                          "mv_to_array(\"dim3\")",
                          ColumnType.STRING_ARRAY
                      ),
                      expressionVirtualColumn(
                          "v1",
                          "string_to_array(\"dim1\",'\\u005C.')",
                          ColumnType.STRING_ARRAY
                      )
                  )
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("dim1", "v0", "v1", "j0.unnest", "_j0.unnest")
                  .columnTypes(ColumnType.STRING, ColumnType.STRING_ARRAY, ColumnType.STRING_ARRAY, ColumnType.STRING, ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"", ImmutableList.of("a", "b"), ImmutableList.of(""), "", "a"},
            new Object[]{"", ImmutableList.of("a", "b"), ImmutableList.of(""), "", "b"},
            new Object[]{"10.1", ImmutableList.of("b", "c"), ImmutableList.of("10", "1"), "10", "b"},
            new Object[]{"10.1", ImmutableList.of("b", "c"), ImmutableList.of("10", "1"), "10", "c"},
            new Object[]{"10.1", ImmutableList.of("b", "c"), ImmutableList.of("10", "1"), "1", "b"},
            new Object[]{"10.1", ImmutableList.of("b", "c"), ImmutableList.of("10", "1"), "1", "c"},
            new Object[]{"2", ImmutableList.of("d"), ImmutableList.of("2"), "2", "d"},
            new Object[]{"1", ImmutableList.of(""), ImmutableList.of("1"), "1", ""},
            new Object[]{"def", null, ImmutableList.of("def"), "def", null},
            new Object[]{"abc", null, ImmutableList.of("abc"), "abc", null}
        )
    );
  }

  @Test
  public void testUnnestTwiceArrayColumns()
  {
    testQuery(
        "SELECT arrayStringNulls, arrayLongNulls, usn, uln"
        + "  FROM\n"
        + "  druid.arrays,\n"
        + "  UNNEST(arrayStringNulls) as t2 (usn),\n"
        + "  UNNEST(arrayLongNulls) as t3 (uln)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(
                      UnnestDataSource.create(
                          UnnestDataSource.create(
                              new TableDataSource(CalciteTests.ARRAYS_DATASOURCE),
                              expressionVirtualColumn(
                                  "j0.unnest",
                                  "\"arrayStringNulls\"",
                                  ColumnType.STRING_ARRAY
                              ),
                              null
                          ),
                          expressionVirtualColumn(
                              "_j0.unnest",
                              "\"arrayLongNulls\"",
                              ColumnType.LONG_ARRAY
                          ),
                          null
                      )
                  )
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("arrayStringNulls", "arrayLongNulls", "j0.unnest", "_j0.unnest")
                  .columnTypes(ColumnType.STRING_ARRAY, ColumnType.LONG_ARRAY, ColumnType.STRING, ColumnType.LONG)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{Arrays.asList("a", "b"), Arrays.asList(2L, 3L), "a", 2L},
            new Object[]{Arrays.asList("a", "b"), Arrays.asList(2L, 3L), "a", 3L},
            new Object[]{Arrays.asList("a", "b"), Arrays.asList(2L, 3L), "b", 2L},
            new Object[]{Arrays.asList("a", "b"), Arrays.asList(2L, 3L), "b", 3L},
            new Object[]{Arrays.asList("b", "b"), Collections.singletonList(1L), "b", 1L},
            new Object[]{Arrays.asList("b", "b"), Collections.singletonList(1L), "b", 1L},
            new Object[]{Arrays.asList("a", "b"), Arrays.asList(1L, null, 3L), "a", 1L},
            new Object[]{Arrays.asList("a", "b"), Arrays.asList(1L, null, 3L), "a", null},
            new Object[]{Arrays.asList("a", "b"), Arrays.asList(1L, null, 3L), "a", 3L},
            new Object[]{Arrays.asList("a", "b"), Arrays.asList(1L, null, 3L), "b", 1L},
            new Object[]{Arrays.asList("a", "b"), Arrays.asList(1L, null, 3L), "b", null},
            new Object[]{Arrays.asList("a", "b"), Arrays.asList(1L, null, 3L), "b", 3L},
            new Object[]{Arrays.asList("d", null, "b"), Arrays.asList(1L, 2L, 3L), "d", 1L},
            new Object[]{Arrays.asList("d", null, "b"), Arrays.asList(1L, 2L, 3L), "d", 2L},
            new Object[]{Arrays.asList("d", null, "b"), Arrays.asList(1L, 2L, 3L), "d", 3L},
            new Object[]{Arrays.asList("d", null, "b"), Arrays.asList(1L, 2L, 3L), null, 1L},
            new Object[]{Arrays.asList("d", null, "b"), Arrays.asList(1L, 2L, 3L), null, 2L},
            new Object[]{Arrays.asList("d", null, "b"), Arrays.asList(1L, 2L, 3L), null, 3L},
            new Object[]{Arrays.asList("d", null, "b"), Arrays.asList(1L, 2L, 3L), "b", 1L},
            new Object[]{Arrays.asList("d", null, "b"), Arrays.asList(1L, 2L, 3L), "b", 2L},
            new Object[]{Arrays.asList("d", null, "b"), Arrays.asList(1L, 2L, 3L), "b", 3L},
            new Object[]{Arrays.asList("a", "b"), Arrays.asList(2L, 3L), "a", 2L},
            new Object[]{Arrays.asList("a", "b"), Arrays.asList(2L, 3L), "a", 3L},
            new Object[]{Arrays.asList("a", "b"), Arrays.asList(2L, 3L), "b", 2L},
            new Object[]{Arrays.asList("a", "b"), Arrays.asList(2L, 3L), "b", 3L},
            new Object[]{Arrays.asList("b", "b"), Collections.singletonList(null), "b", null},
            new Object[]{Arrays.asList("b", "b"), Collections.singletonList(null), "b", null},
            new Object[]{Collections.singletonList(null), Arrays.asList(null, 2L, 9L), null, null},
            new Object[]{Collections.singletonList(null), Arrays.asList(null, 2L, 9L), null, 2L},
            new Object[]{Collections.singletonList(null), Arrays.asList(null, 2L, 9L), null, 9L},
            new Object[]{Arrays.asList("d", null, "b"), Arrays.asList(1L, 2L, 3L), "d", 1L},
            new Object[]{Arrays.asList("d", null, "b"), Arrays.asList(1L, 2L, 3L), "d", 2L},
            new Object[]{Arrays.asList("d", null, "b"), Arrays.asList(1L, 2L, 3L), "d", 3L},
            new Object[]{Arrays.asList("d", null, "b"), Arrays.asList(1L, 2L, 3L), null, 1L},
            new Object[]{Arrays.asList("d", null, "b"), Arrays.asList(1L, 2L, 3L), null, 2L},
            new Object[]{Arrays.asList("d", null, "b"), Arrays.asList(1L, 2L, 3L), null, 3L},
            new Object[]{Arrays.asList("d", null, "b"), Arrays.asList(1L, 2L, 3L), "b", 1L},
            new Object[]{Arrays.asList("d", null, "b"), Arrays.asList(1L, 2L, 3L), "b", 2L},
            new Object[]{Arrays.asList("d", null, "b"), Arrays.asList(1L, 2L, 3L), "b", 3L}
        )
    );
  }

  @Test
  public void testUnnestTwiceWithFiltersAndExpressions()
  {
    testQuery(
        "SELECT dim1, MV_TO_ARRAY(dim3), STRING_TO_ARRAY(dim1, U&'\\005C.') AS dim1_split, dim1_split_unnest, dim3_unnest || 'xx'\n"
        + "FROM\n"
        + "  druid.numfoo,\n"
        + "  UNNEST(STRING_TO_ARRAY(dim1, U&'\\005C.')) as t2 (dim1_split_unnest),\n"
        + "  UNNEST(MV_TO_ARRAY(dim3)) as t3 (dim3_unnest)"
        + "WHERE dim1_split_unnest IN ('1', '2') AND dim3_unnest LIKE '_'",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(
                      UnnestDataSource.create(
                          UnnestDataSource.create(
                              new TableDataSource(CalciteTests.DATASOURCE3),
                              expressionVirtualColumn(
                                  "j0.unnest",
                                  "string_to_array(\"dim1\",'\\u005C.')",
                                  ColumnType.STRING_ARRAY
                              ),
                              in("j0.unnest", ImmutableList.of("1", "2"))
                          ),
                          nestedExpressionVirtualColumn("_j0.unnest", "\"dim3\"", ColumnType.STRING),
                          new LikeDimFilter("_j0.unnest", "_", null, null)
                      )
                  )
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .virtualColumns(
                      expressionVirtualColumn(
                          "v0",
                          "mv_to_array(\"dim3\")",
                          ColumnType.STRING_ARRAY
                      ),
                      expressionVirtualColumn(
                          "v1",
                          "string_to_array(\"dim1\",'\\u005C.')",
                          ColumnType.STRING_ARRAY
                      ),
                      expressionVirtualColumn(
                          "v2",
                          "concat(\"_j0.unnest\",'xx')",
                          ColumnType.STRING
                      )
                  )
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("dim1", "v0", "v1", "j0.unnest", "v2")
                  .columnTypes(ColumnType.STRING, ColumnType.STRING_ARRAY, ColumnType.STRING_ARRAY, ColumnType.STRING, ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"10.1", ImmutableList.of("b", "c"), ImmutableList.of("10", "1"), "1", "bxx"},
            new Object[]{"10.1", ImmutableList.of("b", "c"), ImmutableList.of("10", "1"), "1", "cxx"},
            new Object[]{"2", ImmutableList.of("d"), ImmutableList.of("2"), "2", "dxx"}
        )
    );
  }

  @NotYetSupported(Modes.UNNEST_RESULT_MISMATCH)
  @DecoupledTestConfig(ignoreExpectedQueriesReason = IgnoreQueriesReason.UNNEST_EXTRA_SCANQUERY)
  @Test
  public void testUnnestThriceWithFiltersOnDimAndUnnestCol()
  {
    String sql = "    SELECT dimZipf, dim3_unnest1, dim3_unnest2, dim3_unnest3 FROM \n"
                 + "      ( SELECT * FROM \n"
                 + "           ( SELECT * FROM lotsocolumns, UNNEST(MV_TO_ARRAY(dimMultivalEnumerated)) as ut(dim3_unnest1) )"
                 + "           ,UNNEST(MV_TO_ARRAY(dimMultivalEnumerated)) as ut(dim3_unnest2) \n"
                 + "      ), UNNEST(MV_TO_ARRAY(dimMultivalEnumerated)) as ut(dim3_unnest3) "
                 + " WHERE dimZipf=27 AND dim3_unnest1='Baz'";
    List<Query<?>> expectedQuerySc = ImmutableList.of(
        Druids.newScanQueryBuilder()
              .dataSource(
                  UnnestDataSource.create(
                      UnnestDataSource.create(
                          FilteredDataSource.create(
                              UnnestDataSource.create(
                                  new TableDataSource(CalciteTests.DATASOURCE5),
                                  nestedExpressionVirtualColumn("j0.unnest", "\"dimMultivalEnumerated\"", ColumnType.STRING),
                                  null
                              ),
                              and(
                                  equality("dimZipf", "27", ColumnType.LONG),
                                  equality("j0.unnest", "Baz", ColumnType.STRING)
                              )
                          ),
                          nestedExpressionVirtualColumn("_j0.unnest", "\"dimMultivalEnumerated\"", ColumnType.STRING), null
                      ),
                      nestedExpressionVirtualColumn("__j0.unnest", "\"dimMultivalEnumerated\"", ColumnType.STRING),
                      null
                  )
              )
              .intervals(querySegmentSpec(Filtration.eternity()))
              .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
              .context(QUERY_CONTEXT_UNNEST)
              .virtualColumns(expressionVirtualColumn(
                  "v0",
                  "'Baz'",
                  ColumnType.STRING
              ))
              .columns("dimZipf", "v0", "_j0.unnest", "__j0.unnest")
              .columnTypes(ColumnType.STRING, ColumnType.STRING, ColumnType.STRING, ColumnType.STRING)
              .build()
    );
    testQuery(
        sql,
        QUERY_CONTEXT_UNNEST,
        expectedQuerySc,
        ImmutableList.of(
            new Object[]{"27", "Baz", "Baz", "Baz"},
            new Object[]{"27", "Baz", "Baz", "Baz"},
            new Object[]{"27", "Baz", "Baz", "Hello"},
            new Object[]{"27", "Baz", "Baz", "World"},
            new Object[]{"27", "Baz", "Baz", "Baz"},
            new Object[]{"27", "Baz", "Baz", "Baz"},
            new Object[]{"27", "Baz", "Baz", "Hello"},
            new Object[]{"27", "Baz", "Baz", "World"},
            new Object[]{"27", "Baz", "Hello", "Baz"},
            new Object[]{"27", "Baz", "Hello", "Baz"},
            new Object[]{"27", "Baz", "Hello", "Hello"},
            new Object[]{"27", "Baz", "Hello", "World"},
            new Object[]{"27", "Baz", "World", "Baz"},
            new Object[]{"27", "Baz", "World", "Baz"},
            new Object[]{"27", "Baz", "World", "Hello"},
            new Object[]{"27", "Baz", "World", "World"},
            new Object[]{"27", "Baz", "Baz", "Baz"},
            new Object[]{"27", "Baz", "Baz", "Baz"},
            new Object[]{"27", "Baz", "Baz", "Hello"},
            new Object[]{"27", "Baz", "Baz", "World"},
            new Object[]{"27", "Baz", "Baz", "Baz"},
            new Object[]{"27", "Baz", "Baz", "Baz"},
            new Object[]{"27", "Baz", "Baz", "Hello"},
            new Object[]{"27", "Baz", "Baz", "World"},
            new Object[]{"27", "Baz", "Hello", "Baz"},
            new Object[]{"27", "Baz", "Hello", "Baz"},
            new Object[]{"27", "Baz", "Hello", "Hello"},
            new Object[]{"27", "Baz", "Hello", "World"},
            new Object[]{"27", "Baz", "World", "Baz"},
            new Object[]{"27", "Baz", "World", "Baz"},
            new Object[]{"27", "Baz", "World", "Hello"},
            new Object[]{"27", "Baz", "World", "World"}
        )
    );
  }

  @DecoupledTestConfig(quidemReason = QuidemTestCaseReason.IMPROVED_PLAN, ignoreDefaultsMode = IgnoreDefaultsReson.UNNEST_ARRAY_ISSUE)
  @Test
  public void testUnnestThriceWithFiltersOnDimAndAllUnnestColumns()
  {
    String sql = "    SELECT dimZipf, dim3_unnest1, dim3_unnest2, dim3_unnest3 FROM \n"
                 + "      ( SELECT * FROM \n"
                 + "           ( SELECT * FROM lotsocolumns, UNNEST(MV_TO_ARRAY(dimMultivalEnumerated)) as ut(dim3_unnest1) )"
                 + "           ,UNNEST(MV_TO_ARRAY(dimMultivalEnumerated)) as ut(dim3_unnest2) \n"
                 + "      ), UNNEST(MV_TO_ARRAY(dimMultivalEnumerated)) as ut(dim3_unnest3) "
                 + " WHERE dimZipf=27 AND dim3_unnest1='Baz' AND dim3_unnest2='Hello' AND dim3_unnest3='World'";
    List<Query<?>> expectedQuerySc = ImmutableList.of(
        Druids.newScanQueryBuilder()
              .dataSource(
                  UnnestDataSource.create(
                      UnnestDataSource.create(
                          FilteredDataSource.create(
                              UnnestDataSource.create(
                                  new TableDataSource(CalciteTests.DATASOURCE5),
                                  expressionVirtualColumn(
                                      "j0.unnest",
                                      "\"dimMultivalEnumerated\"",
                                      ColumnType.STRING
                                  ),
                                  null
                              ),
                              and(
                                  equality("dimZipf", "27", ColumnType.LONG),
                                  equality("j0.unnest", "Baz", ColumnType.STRING)
                              )
                          ),
                          expressionVirtualColumn(
                              "_j0.unnest",
                              "\"dimMultivalEnumerated\"",
                              ColumnType.STRING
                          ), equality("_j0.unnest", "Hello", ColumnType.STRING)
                      ),
                      expressionVirtualColumn(
                          "__j0.unnest",
                          "\"dimMultivalEnumerated\"",
                          ColumnType.STRING
                      ),
                      equality("__j0.unnest", "World", ColumnType.STRING)
                  )
              )
              .intervals(querySegmentSpec(Filtration.eternity()))
              .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
              .virtualColumns(expressionVirtualColumn(
                  "v0",
                  "'Baz'",
                  ColumnType.STRING
              ))
              .context(QUERY_CONTEXT_UNNEST)
              .columns("dimZipf", "v0", "_j0.unnest", "__j0.unnest")
              .columnTypes(ColumnType.STRING, ColumnType.STRING, ColumnType.STRING, ColumnType.STRING)
              .build()
    );
    testQuery(
        sql,
        QUERY_CONTEXT_UNNEST,
        expectedQuerySc,
        ImmutableList.of(
            new Object[]{"27", "Baz", "Hello", "World"},
            new Object[]{"27", "Baz", "Hello", "World"}
        )
    );
  }

  @DecoupledTestConfig(quidemReason = QuidemTestCaseReason.IMPROVED_PLAN, ignoreDefaultsMode = IgnoreDefaultsReson.UNNEST_ARRAY_ISSUE)
  @Test
  public void testUnnestThriceWithFiltersOnDimAndAllUnnestColumnsArrayColumns()
  {
    String sql = "    SELECT arrayString, uln, udn, usn FROM \n"
                 + "      ( SELECT * FROM \n"
                 + "           ( SELECT * FROM arrays, UNNEST(arrayLongNulls) as ut(uln))"
                 + "           ,UNNEST(arrayDoubleNulls) as ut(udn) \n"
                 + "      ), UNNEST(arrayStringNulls) as ut(usn) "
                 + " WHERE arrayString = ARRAY['a','b'] AND uln = 1 AND udn = 2.2 AND usn = 'a'";
    List<Query<?>> expectedQuerySc = ImmutableList.of(
        Druids.newScanQueryBuilder()
              .dataSource(
                  UnnestDataSource.create(
                      UnnestDataSource.create(
                          FilteredDataSource.create(
                              UnnestDataSource.create(
                                  new TableDataSource(CalciteTests.ARRAYS_DATASOURCE),
                                  expressionVirtualColumn(
                                      "j0.unnest",
                                      "\"arrayLongNulls\"",
                                      ColumnType.LONG_ARRAY
                                  ),
                                  null
                              ),
                              and(
                                  equality("arrayString", ImmutableList.of("a", "b"), ColumnType.STRING_ARRAY),
                                  equality("j0.unnest", 1, ColumnType.LONG)
                              )
                          ),
                          expressionVirtualColumn(
                              "_j0.unnest",
                              "\"arrayDoubleNulls\"",
                              ColumnType.DOUBLE_ARRAY
                          ),
                          equality("_j0.unnest", 2.2, ColumnType.DOUBLE)
                      ),
                      expressionVirtualColumn(
                          "__j0.unnest",
                          "\"arrayStringNulls\"",
                          ColumnType.STRING_ARRAY
                      ),
                      equality("__j0.unnest", "a", ColumnType.STRING)
                  )
              )
              .intervals(querySegmentSpec(Filtration.eternity()))
              .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
              .virtualColumns(
                  expressionVirtualColumn("v0", "array('a','b')", ColumnType.STRING_ARRAY),
                  expressionVirtualColumn("v1", "1", ColumnType.LONG)
              )
              .context(QUERY_CONTEXT_UNNEST)
              .columns("v0", "v1", "_j0.unnest", "__j0.unnest")
              .columnTypes(ColumnType.STRING_ARRAY, ColumnType.LONG, ColumnType.DOUBLE, ColumnType.STRING)
              .build()
    );
    testQuery(
        sql,
        QUERY_CONTEXT_UNNEST,
        expectedQuerySc,
        ImmutableList.of(
            new Object[]{ImmutableList.of("a", "b"), 1L, 2.2D, "a"}
        )
    );
  }

  @DecoupledTestConfig(quidemReason = QuidemTestCaseReason.IMPROVED_PLAN)
  @Test
  public void testUnnestThriceWithFiltersOnDimAndUnnestColumnsORCombinations()
  {
    String sql = "    SELECT dimZipf, dim3_unnest1, dim3_unnest2, dim3_unnest3 FROM \n"
                 + "      ( SELECT * FROM \n"
                 + "           ( SELECT * FROM lotsocolumns, UNNEST(MV_TO_ARRAY(dimMultivalEnumerated)) as ut(dim3_unnest1) )"
                 + "           ,UNNEST(MV_TO_ARRAY(dimMultivalEnumerated)) as ut(dim3_unnest2) \n"
                 + "      ), UNNEST(MV_TO_ARRAY(dimMultivalEnumerated)) as ut(dim3_unnest3) "
                 + " WHERE dimZipf=27 AND (dim3_unnest1='Baz' OR dim3_unnest2='Hello') AND dim3_unnest3='World'";
    List<Query<?>> expectedQuerySqlCom = ImmutableList.of(
        Druids.newScanQueryBuilder()
              .dataSource(
                  UnnestDataSource.create(
                      FilteredDataSource.create(
                          UnnestDataSource.create(
                              FilteredDataSource.create(
                                  UnnestDataSource.create(
                                      new TableDataSource(CalciteTests.DATASOURCE5),
                                      nestedExpressionVirtualColumn("j0.unnest", "\"dimMultivalEnumerated\"", ColumnType.STRING),
                                      null
                                  ),
                                  equality("dimZipf", "27", ColumnType.LONG)
                              ),
                              nestedExpressionVirtualColumn("_j0.unnest", "\"dimMultivalEnumerated\"", ColumnType.STRING),
                              null
                          ),
                          or(
                              equality("j0.unnest", "Baz", ColumnType.STRING),
                              equality("_j0.unnest", "Hello", ColumnType.STRING)
                          ) // (j0.unnest = Baz || _j0.unnest = Hello)
                      ),
                      nestedExpressionVirtualColumn("__j0.unnest", "\"dimMultivalEnumerated\"", ColumnType.STRING),
                      equality("__j0.unnest", "World", ColumnType.STRING)
                  )
              )
              .intervals(querySegmentSpec(Filtration.eternity()))
              .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
              .context(QUERY_CONTEXT_UNNEST)
              .columns("dimZipf", "j0.unnest", "_j0.unnest", "__j0.unnest")
              .columnTypes(ColumnType.STRING, ColumnType.STRING, ColumnType.STRING, ColumnType.STRING)
              .build()
    );
    testQuery(
        sql,
        QUERY_CONTEXT_UNNEST,
        expectedQuerySqlCom,
        ImmutableList.of(
            new Object[]{"27", "Baz", "Baz", "World"},
            new Object[]{"27", "Baz", "Baz", "World"},
            new Object[]{"27", "Baz", "Hello", "World"},
            new Object[]{"27", "Baz", "World", "World"},
            new Object[]{"27", "Baz", "Baz", "World"},
            new Object[]{"27", "Baz", "Baz", "World"},
            new Object[]{"27", "Baz", "Hello", "World"},
            new Object[]{"27", "Baz", "World", "World"},
            new Object[]{"27", "Hello", "Hello", "World"},
            new Object[]{"27", "World", "Hello", "World"}
        )
    );
  }

  @DecoupledTestConfig(quidemReason = QuidemTestCaseReason.IMPROVED_PLAN)
  @Test
  public void testUnnestThriceWithFiltersOnDimAndAllUnnestColumnsArrayColumnsOrFilters()
  {
    String sql = "    SELECT arrayString, uln, udn, usn FROM \n"
                 + "      ( SELECT * FROM \n"
                 + "           ( SELECT * FROM arrays, UNNEST(arrayLongNulls) as ut(uln))"
                 + "           ,UNNEST(arrayDoubleNulls) as ut(udn) \n"
                 + "      ), UNNEST(arrayStringNulls) as ut(usn) "
                 + " WHERE arrayString = ARRAY['a','b'] AND (uln = 1 OR udn = 2.2) AND usn = 'a'";
    List<Query<?>> expectedQuerySc = ImmutableList.of(
        Druids.newScanQueryBuilder()
              .dataSource(
                  UnnestDataSource.create(
                      FilteredDataSource.create(
                        UnnestDataSource.create(
                            FilteredDataSource.create(
                                UnnestDataSource.create(
                                    new TableDataSource(CalciteTests.ARRAYS_DATASOURCE),
                                    expressionVirtualColumn(
                                        "j0.unnest",
                                        "\"arrayLongNulls\"",
                                        ColumnType.LONG_ARRAY
                                    ),
                                    null
                                ),
                                equality("arrayString", ImmutableList.of("a", "b"), ColumnType.STRING_ARRAY)
                            ),
                            expressionVirtualColumn(
                                "_j0.unnest",
                                "\"arrayDoubleNulls\"",
                                ColumnType.DOUBLE_ARRAY
                            ),
                            null
                        ),
                        or(
                            equality("j0.unnest", 1, ColumnType.LONG),
                            equality("_j0.unnest", 2.2, ColumnType.DOUBLE)
                        )
                      ),
                      expressionVirtualColumn(
                          "__j0.unnest",
                          "\"arrayStringNulls\"",
                          ColumnType.STRING_ARRAY
                      ),
                      equality("__j0.unnest", "a", ColumnType.STRING)
                  )
              )
              .intervals(querySegmentSpec(Filtration.eternity()))
              .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
              .virtualColumns(
                  expressionVirtualColumn("v0", "array('a','b')", ColumnType.STRING_ARRAY)
              )
              .context(QUERY_CONTEXT_UNNEST)
              .columns("v0", "j0.unnest", "_j0.unnest", "__j0.unnest")
              .columnTypes(ColumnType.STRING_ARRAY, ColumnType.LONG, ColumnType.DOUBLE, ColumnType.STRING)
              .build()
    );
    testQuery(
        sql,
        QUERY_CONTEXT_UNNEST,
        expectedQuerySc,
        ImmutableList.of(
            new Object[]{ImmutableList.of("a", "b"), 1L, 1.1D, "a"},
            new Object[]{ImmutableList.of("a", "b"), 1L, 2.2D, "a"},
            new Object[]{ImmutableList.of("a", "b"), 1L, null, "a"},
            new Object[]{ImmutableList.of("a", "b"), null, 2.2D, "a"},
            new Object[]{ImmutableList.of("a", "b"), 3L, 2.2D, "a"}
        )
    );
  }

  @NotYetSupported(Modes.UNNEST_RESULT_MISMATCH)
  @Test
  public void testUnnestWithGroupBy()
  {
    cannotVectorize();
    testQuery(
        "SELECT d3 FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3) GROUP BY d3 ",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(UnnestDataSource.create(
                            new TableDataSource(CalciteTests.DATASOURCE3),
                            nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                            null
                        ))
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setContext(QUERY_CONTEXT_UNNEST)
                        .setDimensions(new DefaultDimensionSpec("j0.unnest", "d0", ColumnType.STRING))
                        .setGranularity(Granularities.ALL)
                        .setContext(QUERY_CONTEXT_UNNEST)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{null},
            new Object[]{""},
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{"c"},
            new Object[]{"d"}
        )
    );
  }

  @Test
  public void testUnnestWithGroupByArrayColumn()
  {
    cannotVectorize();
    testQuery(
        "SELECT usn FROM druid.arrays, UNNEST(arrayStringNulls) as u (usn) GROUP BY usn ",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(UnnestDataSource.create(
                            new TableDataSource(CalciteTests.ARRAYS_DATASOURCE),
                            expressionVirtualColumn("j0.unnest", "\"arrayStringNulls\"", ColumnType.STRING_ARRAY),
                            null
                        ))
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setContext(QUERY_CONTEXT_UNNEST)
                        .setDimensions(new DefaultDimensionSpec("j0.unnest", "d0", ColumnType.STRING))
                        .setGranularity(Granularities.ALL)
                        .setContext(QUERY_CONTEXT_UNNEST)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{null},
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{"d"}
        )
    );
  }

  @NotYetSupported(Modes.UNNEST_RESULT_MISMATCH)
  @Test
  public void testUnnestWithGroupByOrderBy()
  {
    cannotVectorize();
    testQuery(
        "SELECT d3, COUNT(*) FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) AS unnested(d3) GROUP BY d3 ORDER BY d3 DESC ",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(UnnestDataSource.create(
                            new TableDataSource(CalciteTests.DATASOURCE3),
                            nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                            null
                        ))
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setContext(QUERY_CONTEXT_UNNEST)
                        .setDimensions(new DefaultDimensionSpec("j0.unnest", "d0", ColumnType.STRING))
                        .setGranularity(Granularities.ALL)
                        .setLimitSpec(
                            DefaultLimitSpec
                                .builder()
                                .orderBy(new OrderByColumnSpec(
                                    "d0",
                                    OrderByColumnSpec.Direction.DESCENDING,
                                    StringComparators.LEXICOGRAPHIC
                                ))
                                .build()
                        )
                        .setAggregatorSpecs(new CountAggregatorFactory("a0"))
                        .setContext(QUERY_CONTEXT_UNNEST)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"d", 1L},
            new Object[]{"c", 1L},
            new Object[]{"b", 2L},
            new Object[]{"a", 1L},
            new Object[]{"", 1L},
            new Object[]{null, 2L}
        )
    );
  }

  @NotYetSupported(Modes.UNNEST_RESULT_MISMATCH)
  @Test
  public void testUnnestWithGroupByOrderByWithLimit()
  {
    final Map<String, Object> context =
        QueryContexts.override(QUERY_CONTEXT_UNNEST, PlannerConfig.CTX_KEY_USE_LEXICOGRAPHIC_TOPN, true);
    testQuery(
        "SELECT d3, COUNT(*) FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) AS unnested(d3) GROUP BY d3 ORDER BY d3 ASC LIMIT 4 ",
        context,
        ImmutableList.of(
            new TopNQueryBuilder()
                .dataSource(UnnestDataSource.create(
                    new TableDataSource(CalciteTests.DATASOURCE3),
                    nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                    null
                ))
                .intervals(querySegmentSpec(Filtration.eternity()))
                .dimension(new DefaultDimensionSpec("j0.unnest", "d0", ColumnType.STRING))
                .metric(new DimensionTopNMetricSpec(null, StringComparators.LEXICOGRAPHIC))
                .threshold(4)
                .aggregators(aggregators(new CountAggregatorFactory("a0")))
                .context(context)
                .build()
        ),
        ImmutableList.of(
            new Object[]{null, 2L},
            new Object[]{"", 1L},
            new Object[]{"a", 1L},
            new Object[]{"b", 2L}
        )
    );
  }

  @Test
  public void testUnnestWithGroupByHaving()
  {
    cannotVectorize();
    testQuery(
        "SELECT d3, COUNT(*) FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) AS unnested(d3) GROUP BY d3 HAVING COUNT(*) = 1",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(UnnestDataSource.create(
                            new TableDataSource(CalciteTests.DATASOURCE3),
                            nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                            null
                        ))
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setContext(QUERY_CONTEXT_UNNEST)
                        .setDimensions(new DefaultDimensionSpec("j0.unnest", "d0", ColumnType.STRING))
                        .setGranularity(Granularities.ALL)
                        .setAggregatorSpecs(new CountAggregatorFactory("a0"))
                        .setHavingSpec(new DimFilterHavingSpec(equality("a0", 1L, ColumnType.LONG), true))
                        .setContext(QUERY_CONTEXT_UNNEST)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"", 1L},
            new Object[]{"a", 1L},
            new Object[]{"c", 1L},
            new Object[]{"d", 1L}
        )
    );
  }

  @Test
  public void testUnnestWithLimit()
  {
    testQuery(
        "SELECT d3 FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3) LIMIT 3",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE3),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .limit(3)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{"b"}
        )
    );
  }

  @NotYetSupported(Modes.UNNEST_RESULT_MISMATCH)
  @Test
  public void testUnnestFirstQueryOnSelect()
  {
    testQuery(
        "SELECT d3 FROM (select dim1, dim2, dim3 from druid.numfoo), UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE3),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{"b"},
            new Object[]{"c"},
            new Object[]{"d"},
            new Object[]{""},
            new Object[]{null},
            new Object[]{null}
        )
    );
  }

  @Test
  public void testUnnestVirtualWithColumns1()
  {
    testQuery(
        "SELECT strings, m1 FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) as unnested (strings) where (strings='a' and (m1<=10 or strings='b'))",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(Druids.newScanQueryBuilder()
                               .dataSource(UnnestDataSource.create(
                                   new TableDataSource(CalciteTests.DATASOURCE3),
                                   nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                                   equality("j0.unnest", "a", ColumnType.STRING)
                               ))
                               .intervals(querySegmentSpec(Filtration.eternity()))
                               .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                               .filters(or(
                                   range("m1", ColumnType.LONG, null, "10", false, false),
                                   equality("j0.unnest", "b", ColumnType.STRING)
                               ))
                               .context(QUERY_CONTEXT_UNNEST)
                               .columns("j0.unnest", "m1")
                               .columnTypes(ColumnType.STRING, ColumnType.FLOAT)
                               .build()),
        ImmutableList.of(new Object[]{"a", 1.0f})
    );
  }

  @Test
  public void testUnnestVirtualWithColumns2()
  {
    testQuery(
        "SELECT strings, m1 FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) as unnested (strings) where (strings='a' or (m1=2 and strings='b'))",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(Druids.newScanQueryBuilder()
                               .dataSource(UnnestDataSource.create(
                                   new TableDataSource(CalciteTests.DATASOURCE3),
                                   nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                                   null
                               ))
                               .intervals(querySegmentSpec(Filtration.eternity()))
                               .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                               // (j0.unnest = a || (m1 = 2 && j0.unnest = b))
                               .filters(or(
                                   equality("j0.unnest", "a", ColumnType.STRING),
                                   and(
                                       equality("m1", "2", ColumnType.FLOAT),
                                       equality("j0.unnest", "b", ColumnType.STRING)
                                   )
                               ))
                               .context(QUERY_CONTEXT_UNNEST)
                               .columns("j0.unnest", "m1")
                               .columnTypes(ColumnType.STRING, ColumnType.FLOAT)
                               .build()),
        ImmutableList.of(
            new Object[]{"a", 1.0f},
            new Object[]{"b", 2.0f}
        )
    );
  }

  @Test
  public void testUnnestWithFilters()
  {
    testQuery(
        "SELECT d3 FROM (select * from druid.numfoo where dim2='a'), UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      FilteredDataSource.create(
                          new TableDataSource(CalciteTests.DATASOURCE3),
                          equality("dim2", "a", ColumnType.STRING)
                      ),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{""}
        )
    );
  }

  @DecoupledTestConfig(quidemReason = QuidemTestCaseReason.UNNEST_EXTRA_SCAN)
  @Test
  public void testUnnestWithFiltersWithExpressionInInnerQuery()
  {
    testQuery(
        "SELECT t,d3 FROM (select FLOOR(__time to hour) t, dim3 from druid.numfoo where dim2='a'), UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      FilteredDataSource.create(
                          new TableDataSource(CalciteTests.DATASOURCE3),
                          equality("dim2", "a", ColumnType.STRING)
                      ),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .virtualColumns(expressionVirtualColumn(
                      "v0",
                      "timestamp_floor(\"__time\",'PT1H',null,'UTC')",
                      ColumnType.LONG
                  ))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("v0", "j0.unnest")
                  .columnTypes(ColumnType.LONG, ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{946684800000L, "a"},
            new Object[]{946684800000L, "b"},
            new Object[]{978307200000L, ""}
        )
    );
  }

  @DecoupledTestConfig(quidemReason = QuidemTestCaseReason.UNNEST_EXTRA_SCAN)
  @Test
  public void testUnnestWithInFiltersWithExpressionInInnerQuery()
  {
    testQuery(
        "SELECT t,d3 FROM (select FLOOR(__time to hour) t, dim3 from druid.numfoo where dim2 IN ('a','b')), UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      FilteredDataSource.create(
                          new TableDataSource(CalciteTests.DATASOURCE3),
                          in("dim2", ImmutableList.of("a", "b"))
                      ),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .virtualColumns(expressionVirtualColumn("v0",
                                                          "timestamp_floor(\"__time\",'PT1H',null,'UTC')",
                                                          ColumnType.LONG))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("v0", "j0.unnest")
                  .columnTypes(ColumnType.LONG, ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{946684800000L, "a"},
            new Object[]{946684800000L, "b"},
            new Object[]{978307200000L, ""}
        )
    );
  }

  @DecoupledTestConfig(quidemReason = QuidemTestCaseReason.UNUSED_VIRTUALCOLUMN)
  @Test
  public void testUnnestWithFiltersInnerLimit()
  {
    testQuery(
        "SELECT d3 FROM (select dim2,dim3 from druid.numfoo where dim2='a' LIMIT 2), UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new QueryDataSource(
                          newScanQueryBuilder()
                              .dataSource(
                                  new TableDataSource(CalciteTests.DATASOURCE3)
                              )
                              .intervals(querySegmentSpec(Filtration.eternity()))
                              .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                              .filters(equality("dim2", "a", ColumnType.STRING))
                              .columns("dim3")
                              .columnTypes(ColumnType.STRING)
                              .limit(2)
                              .context(QUERY_CONTEXT_UNNEST)
                              .build()
                      ),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{""}
        )
    );
  }

  @Test
  public void testUnnestWithFiltersInsideAndOutside()
  {
    testQuery(
        "SELECT d3 FROM\n"
        + "  (select * from druid.numfoo where dim2='a') as t,\n"
        + "  UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3)\n"
        + "WHERE t.dim1 <> 'foo'\n"
        + "AND unnested.d3 <> 'b'",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      FilteredDataSource.create(
                          new TableDataSource(CalciteTests.DATASOURCE3),
                          and(
                              equality("dim2", "a", ColumnType.STRING),
                              not(equality("dim1", "foo", ColumnType.STRING))
                          )
                      ),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      not(equality("j0.unnest", "b", ColumnType.STRING))
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{""}
        )
    );
  }

  @Test
  public void testUnnestWithFiltersInsideAndOutside1()
  {
    testQuery(
        "SELECT d3 FROM\n"
        + "  (select * from druid.numfoo where dim2='a'),\n"
        + "  UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3)\n"
        + "WHERE dim1 <> 'foo'\n"
        + "AND (unnested.d3 IN ('a', 'c') OR unnested.d3 LIKE '_')",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      FilteredDataSource.create(
                          new TableDataSource(CalciteTests.DATASOURCE3),
                          and(
                              equality("dim2", "a", ColumnType.STRING),
                              not(equality("dim1", "foo", ColumnType.STRING))
                          )
                      ),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      or(
                          in("j0.unnest", ImmutableList.of("a", "c")),
                          new LikeDimFilter("j0.unnest", "_", null, null)
                      )
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"b"}
        )
    );
  }

  @Test
  public void testUnnestWithFiltersOutside()
  {
    testQuery(
        "SELECT d3 FROM\n"
        + "  druid.numfoo t,\n"
        + "  UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3)\n"
        + "WHERE t.dim2='a'\n"
        + "AND t.dim1 <> 'foo'\n"
        + "AND (unnested.d3 IN ('a', 'c') OR unnested.d3 LIKE '_')",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      FilteredDataSource.create(
                          new TableDataSource(CalciteTests.DATASOURCE3),
                          and(
                              equality("dim2", "a", ColumnType.STRING),
                              not(equality("dim1", "foo", ColumnType.STRING))
                          )
                      ),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      or(
                          in("j0.unnest", ImmutableList.of("a", "c")),
                          new LikeDimFilter("j0.unnest", "_", null, null)
                      )
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"b"}
        )
    );
  }

  @NotYetSupported(Modes.UNNEST_RESULT_MISMATCH)
  @Test
  public void testUnnestWithInFilters()
  {
    testQuery(
        "SELECT d3 FROM (select * from druid.numfoo where dim2 IN ('a','b','ab','abc')), UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      FilteredDataSource.create(
                          new TableDataSource(CalciteTests.DATASOURCE3),
                          in("dim2", ImmutableList.of("a", "b", "ab", "abc"))
                      ),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{""},
            new Object[]{null}
        )
    );
  }

  @Test
  public void testUnnestVirtualWithColumns()
  {
    testQuery(
        "SELECT strings FROM druid.numfoo, UNNEST(ARRAY[dim4, dim5]) as unnested (strings)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE3),
                      expressionVirtualColumn("j0.unnest", "array(\"dim4\",\"dim5\")", ColumnType.STRING_ARRAY),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"aa"},
            new Object[]{"a"},
            new Object[]{"ab"},
            new Object[]{"a"},
            new Object[]{"ba"},
            new Object[]{"b"},
            new Object[]{"ad"},
            new Object[]{"b"},
            new Object[]{"aa"},
            new Object[]{"b"},
            new Object[]{"ab"}
        )
    );
  }

  @Test
  public void testUnnestWithGroupByOrderByOnVirtualColumn()
  {
    cannotVectorize();
    testQuery(
        "SELECT d24, COUNT(*) FROM druid.numfoo, UNNEST(ARRAY[dim2, dim4]) AS unnested(d24) GROUP BY d24 ORDER BY d24 DESC ",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(
                            UnnestDataSource.create(
                                new TableDataSource(CalciteTests.DATASOURCE3),
                                expressionVirtualColumn(
                                    "j0.unnest",
                                    "array(\"dim2\",\"dim4\")",
                                    ColumnType.STRING_ARRAY
                                ),
                                null
                            )
                        )
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setContext(QUERY_CONTEXT_UNNEST)
                        .setDimensions(new DefaultDimensionSpec("j0.unnest", "d0", ColumnType.STRING))
                        .setGranularity(Granularities.ALL)
                        .setLimitSpec(
                            DefaultLimitSpec
                                .builder()
                                .orderBy(new OrderByColumnSpec(
                                    "d0",
                                    OrderByColumnSpec.Direction.DESCENDING,
                                    StringComparators.LEXICOGRAPHIC
                                ))
                                .build()
                        )
                        .setAggregatorSpecs(new CountAggregatorFactory("a0"))
                        .setContext(QUERY_CONTEXT_UNNEST)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"b", 3L},
            new Object[]{"abc", 1L},
            new Object[]{"a", 5L},
            new Object[]{"", 1L},
            new Object[]{null, 2L}
        )
    );
  }

  @NotYetSupported(Modes.UNNEST_RESULT_MISMATCH)
  @DecoupledTestConfig(ignoreExpectedQueriesReason = IgnoreQueriesReason.UNNEST_EXTRA_SCANQUERY)
  @Test
  public void testUnnestWithJoinOnTheLeft()
  {
    testQuery(
        "SELECT d3 from (SELECT * from druid.numfoo JOIN (select dim2 as t from druid.numfoo where dim2 IN ('a','b','ab','abc')) ON dim2=t), UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      join(
                          new TableDataSource(CalciteTests.DATASOURCE3),
                          new QueryDataSource(
                              newScanQueryBuilder()
                                  .dataSource(
                                      new TableDataSource(CalciteTests.DATASOURCE3)
                                  )
                                  .intervals(querySegmentSpec(Filtration.eternity()))
                                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                                  .filters(in("dim2", ImmutableList.of("a", "b", "ab", "abc")))
                                  .columns("dim2")
                                  .columnTypes(ColumnType.STRING)
                                  .context(QUERY_CONTEXT_UNNEST)
                                  .build()
                          ),
                          "j0.",
                          "(\"dim2\" == \"j0.dim2\")",
                          JoinType.INNER
                      ),
                      nestedExpressionVirtualColumn("_j0.unnest", "\"dim3\"", ColumnType.STRING),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("_j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{""},
            new Object[]{""},
            new Object[]{null}
        )
    );
  }

  @NotYetSupported(Modes.UNNEST_INLINED)
  @Test
  public void testUnnestWithConstant()
  {
    // Since there is a constant on the right,
    // Druid will plan this as a join query
    // as there is nothing to correlate between left and right
    testQuery(
        "SELECT longs FROM druid.numfoo, UNNEST(ARRAY[1,2,3]) as unnested (longs)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(
                      join(
                          new TableDataSource(CalciteTests.DATASOURCE3),
                          InlineDataSource.fromIterable(
                              ImmutableList.of(
                                  new Object[]{1L},
                                  new Object[]{2L},
                                  new Object[]{3L}
                              ),
                              RowSignature.builder().add("EXPR$0", ColumnType.LONG).build()
                          ),
                          "j0.",
                          "1",
                          JoinType.INNER
                      )
                  )
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.EXPR$0")
                  .columnTypes(ColumnType.LONG)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1},
            new Object[]{2},
            new Object[]{3},
            new Object[]{1},
            new Object[]{2},
            new Object[]{3},
            new Object[]{1},
            new Object[]{2},
            new Object[]{3},
            new Object[]{1},
            new Object[]{2},
            new Object[]{3},
            new Object[]{1},
            new Object[]{2},
            new Object[]{3},
            new Object[]{1},
            new Object[]{2},
            new Object[]{3}
        )
    );
  }

  @NotYetSupported(Modes.UNNEST_RESULT_MISMATCH)
  @Test
  public void testUnnestWithSQLFunctionOnUnnestedColumn()
  {
    testQuery(
        "SELECT strlen(d3) FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE3),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .virtualColumns(expressionVirtualColumn("v0", "strlen(\"j0.unnest\")", ColumnType.LONG))
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("v0")
                  .columnTypes(ColumnType.LONG)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1},
            new Object[]{1},
            new Object[]{1},
            new Object[]{1},
            new Object[]{1},
            new Object[]{0},
            new Object[]{null},
            new Object[]{null}
        )
    );
  }

  @Test
  public void testUnnestWithINFiltersWithLeftRewrite()
  {
    testQuery(
        "SELECT d3 FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3) where d3 IN ('a','b') and m1 < 10",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      FilteredDataSource.create(
                          new TableDataSource(CalciteTests.DATASOURCE3),
                          range("m1", ColumnType.LONG, null, 10L, false, true)
                      ),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      in("j0.unnest", ImmutableSet.of("a", "b"))
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{"b"}
        )
    );
  }

  @Test
  public void testUnnestWithINFiltersWithNoLeftRewrite()
  {
    testQuery(
        "SELECT d45 FROM druid.numfoo, UNNEST(ARRAY[dim4,dim5]) as unnested (d45) where d45 IN ('a','b')",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE3),
                      expressionVirtualColumn("j0.unnest", "array(\"dim4\",\"dim5\")", ColumnType.STRING_ARRAY),
                      in("j0.unnest", ImmutableSet.of("a", "b"))
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"a"},
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{"b"},
            new Object[]{"b"}
        )
    );
  }

  @Test
  public void testUnnestWithInvalidINFiltersOnUnnestedColumn()
  {
    testQuery(
        "SELECT d3 FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3) where d3 IN ('foo','bar')",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE3),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      in("j0.unnest", ImmutableSet.of("foo", "bar"))
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of()
    );
  }

  @DecoupledTestConfig(quidemReason = QuidemTestCaseReason.UNNEST_DIFFERENT_RESULTSET)
  @Test
  public void testUnnestWithNotFiltersOnUnnestedColumn()
  {
    testQuery(
        "SELECT d3 FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3) where d3!='d' ",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE3),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      not(equality("j0.unnest", "d", ColumnType.STRING))
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{"b"},
            new Object[]{"c"},
            new Object[]{""}
        )
    );
  }

  @Test
  public void testUnnestWithSelectorFiltersOnSelectedColumn()
  {
    testQuery(
        "SELECT d3 FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3) where d3='b'",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE3),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      equality("j0.unnest", "b", ColumnType.STRING)
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"b"},
            new Object[]{"b"}
        )
    );
  }

  @Test
  public void testUnnestWithSelectorFiltersOnVirtualColumn()
  {
    testQuery(
        "SELECT d12 FROM druid.numfoo, UNNEST(ARRAY[m1,m2]) as unnested (d12) where d12=1",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE3),
                      expressionVirtualColumn("j0.unnest", "array(\"m1\",\"m2\")", ColumnType.FLOAT_ARRAY),
                      equality("j0.unnest", 1.0, ColumnType.FLOAT)
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.FLOAT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1.0f},
            new Object[]{1.0f}
        )
    );
  }

  @Test
  public void testUnnestWithSelectorFiltersOnVirtualStringColumn()
  {
    testQuery(
        "SELECT d45 FROM druid.numfoo, UNNEST(ARRAY[dim4,dim5]) as unnested (d45) where d45 IN ('a','ab')",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE3),
                      expressionVirtualColumn("j0.unnest", "array(\"dim4\",\"dim5\")", ColumnType.STRING_ARRAY),
                      in("j0.unnest", ImmutableSet.of("a", "ab"))
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"a"},
            new Object[]{"ab"},
            new Object[]{"a"},
            new Object[]{"ab"}
        )
    );
  }

  @Test
  public void testUnnestWithMultipleAndFiltersOnSelectedColumns()
  {
    testQuery(
        "SELECT d3 FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3) where d3='b' and m1 < 10 and m2 < 10",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      FilteredDataSource.create(
                          new TableDataSource(CalciteTests.DATASOURCE3),
                          and(
                              range("m1", ColumnType.LONG, null, 10L, false, true),
                              range("m2", ColumnType.LONG, null, 10L, false, true)
                          )
                      ),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      equality("j0.unnest", "b", ColumnType.STRING)
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"b"},
            new Object[]{"b"}
        )
    );
  }

  @Test
  public void testUnnestWithMultipleOrFiltersOnSelectedColumns()
  {
    testQuery(
        "SELECT d3 FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3) where d3='b' or m1 < 2 ",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE3),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .filters(
                      or(
                          equality("j0.unnest", "b", ColumnType.STRING),
                          range("m1", ColumnType.LONG, null, 2L, false, true)
                      )
                  )
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{"b"}
        )
    );
  }

  @Test
  public void testUnnestWithMultipleAndFiltersOnSelectedUnnestedColumns()
  {
    testQuery(
        "SELECT d3 FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3) where d3 IN ('a','b') and d3 < 'e' ",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE3),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      in("j0.unnest", ImmutableSet.of("a", "b"))
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{"b"}
        )
    );
  }

  @Test
  public void testUnnestWithMultipleOrFiltersOnUnnestedColumns()
  {
    testQuery(
        "SELECT d3 FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3) where d3='b' or d3='d' ",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE3),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      in("j0.unnest", ImmutableSet.of("b", "d"))
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"b"},
            new Object[]{"b"},
            new Object[]{"d"}
        )
    );
  }

  @DecoupledTestConfig(quidemReason = QuidemTestCaseReason.UNNEST_DIFFERENT_RESULTSET)
  @Test
  public void testUnnestWithMultipleOrFiltersOnVariationsOfUnnestedColumns()
  {
    testQuery(
        "SELECT d3 FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3) where strlen(d3) < 2 or d3='d' ",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE3),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      or(
                          expressionFilter("(strlen(\"j0.unnest\") < 2)"),
                          equality("j0.unnest", "d", ColumnType.STRING)
                      )
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{"b"},
            new Object[]{"c"},
            new Object[]{"d"},
            new Object[]{""}
        )
    );
  }

  @Test
  public void testUnnestWithMultipleOrFiltersOnSelectedNonUnnestedColumns()
  {
    testQuery(
        "SELECT d3 FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3) where m1 < 2 or m2 < 2 ",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      FilteredDataSource.create(
                          new TableDataSource(CalciteTests.DATASOURCE3),
                          or(
                              range("m1", ColumnType.LONG, null, 2L, false, true),
                              range("m2", ColumnType.LONG, null, 2L, false, true)
                          )
                      ),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"b"}
        )
    );
  }

  @Test
  public void testUnnestWithMultipleOrFiltersOnSelectedVirtualColumns()
  {
    testQuery(
        "SELECT d45 FROM druid.numfoo, UNNEST(ARRAY[dim4,dim5]) as unnested (d45) where d45 IN ('a','aa') or m1 < 2 ",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE3),
                      expressionVirtualColumn("j0.unnest", "array(\"dim4\",\"dim5\")", ColumnType.STRING_ARRAY),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .filters(
                      or(
                          in("j0.unnest", ImmutableSet.of("a", "aa")),
                          range("m1", ColumnType.LONG, null, 2L, false, true)
                      )
                  )
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"aa"},
            new Object[]{"a"},
            new Object[]{"a"},
            new Object[]{"aa"}
        )
    );
  }

  @Test
  public void testUnnestWithMultipleOrFiltersOnUnnestedColumnsAndOnOriginalColumn()
  {
    testQuery(
        "SELECT d3 FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3) where d3='b' or dim3='d' ",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE3),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .filters(
                      or(
                          equality("j0.unnest", "b", ColumnType.STRING),
                          equality("dim3", "d", ColumnType.STRING)
                      )
                  )
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"b"},
            new Object[]{"b"},
            new Object[]{"d"}
        )
    );
  }

  @Test
  public void testUnnestWithMultipleOrFiltersOnUnnestedColumnsAndOnOriginalColumnDiffOrdering()
  {
    testQuery(
        "SELECT dim3, d3 FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3) where dim3='b' or d3='a' ",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE3),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .filters(
                      or(
                          equality("dim3", "b", ColumnType.STRING),
                          equality("j0.unnest", "a", ColumnType.STRING)
                      )
                  )
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("dim3", "j0.unnest")
                  .columnTypes(ColumnType.STRING, ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"a\",\"b\"]", "a"},
            new Object[]{"[\"a\",\"b\"]", "b"},
            new Object[]{"[\"b\",\"c\"]", "b"},
            new Object[]{"[\"b\",\"c\"]", "c"}
        )
    );
  }

  @NotYetSupported(Modes.UNNEST_RESULT_MISMATCH)
  @Test
  public void testUnnestWithCountOnColumn()
  {
    cannotVectorize();
    testQuery(
        "SELECT count(*) d3 FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE3),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .context(QUERY_CONTEXT_UNNEST)
                  .aggregators(aggregators(new CountAggregatorFactory("a0")))
                  .build()
        ),
        ImmutableList.of(
            new Object[]{8L}
        )
    );
  }

  @DecoupledTestConfig(quidemReason = QuidemTestCaseReason.IMPROVED_PLAN)
  @Test
  public void testUnnestWithGroupByHavingSelector()
  {
    cannotVectorize();
    testQuery(
        "SELECT d3, COUNT(*) FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) AS unnested(d3) GROUP BY d3 HAVING d3='b'",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(UnnestDataSource.create(
                            new TableDataSource(CalciteTests.DATASOURCE3),
                            nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                            null
                        ))
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setContext(QUERY_CONTEXT_UNNEST)
                        .setDimensions(new DefaultDimensionSpec("j0.unnest", "d0", ColumnType.STRING))
                        .setGranularity(Granularities.ALL)
                        .setDimFilter(equality("j0.unnest", "b", ColumnType.STRING))
                        .setAggregatorSpecs(new CountAggregatorFactory("a0"))
                        .setContext(QUERY_CONTEXT_UNNEST)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"b", 2L}
        )
    );
  }

  @Test
  public void testUnnestWithSumOnUnnestedVirtualColumn()
  {
    cannotVectorize();
    testQuery(
        "select sum(c) col from druid.numfoo, unnest(ARRAY[m1,m2]) as u(c)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE3),
                      expressionVirtualColumn("j0.unnest", "array(\"m1\",\"m2\")", ColumnType.FLOAT_ARRAY),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .context(QUERY_CONTEXT_UNNEST)
                  .aggregators(aggregators(new DoubleSumAggregatorFactory("a0", "j0.unnest")))
                  .build()
        ),
        ImmutableList.of(
            new Object[]{42.0}
        )
    );
  }

  @Test
  public void testUnnestWithSumOnUnnestedColumn()
  {
    cannotVectorize();
    testQuery(
        "select sum(c) col from druid.numfoo, unnest(mv_to_array(dim3)) as u(c)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE3),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .virtualColumns(expressionVirtualColumn("v0", "CAST(\"j0.unnest\", 'DOUBLE')", ColumnType.DOUBLE))
                  .context(QUERY_CONTEXT_UNNEST)
                  .aggregators(aggregators(new DoubleSumAggregatorFactory("a0", "v0")))
                  .build()
        ),
        ImmutableList.of(
            new Object[]{null}
        )
    );
  }

  @Test
  public void testUnnestWithSumOnUnnestedArrayColumn()
  {
    cannotVectorize();
    testQuery(
        "select sum(c) col from druid.arrays, unnest(arrayDoubleNulls) as u(c)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newTimeseriesQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.ARRAYS_DATASOURCE),
                      expressionVirtualColumn("j0.unnest", "\"arrayDoubleNulls\"", ColumnType.DOUBLE_ARRAY),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .context(QUERY_CONTEXT_UNNEST)
                  .aggregators(aggregators(new DoubleSumAggregatorFactory("a0", "j0.unnest")))
                  .build()
        ),
        ImmutableList.of(
            new Object[]{4030.0999999999995}
        )
    );
  }

  @Test
  public void testUnnestWithGroupByHavingWithWhereOnAggCol()
  {
    cannotVectorize();
    testQuery(
        "SELECT d3, COUNT(*) FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) AS unnested(d3) WHERE d3 IN ('a','c') GROUP BY d3 HAVING COUNT(*) = 1",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(UnnestDataSource.create(
                            new TableDataSource(CalciteTests.DATASOURCE3),
                            nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                            in("j0.unnest", ImmutableSet.of("a", "c"))
                        ))
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setContext(QUERY_CONTEXT_UNNEST)
                        .setDimensions(new DefaultDimensionSpec("j0.unnest", "d0", ColumnType.STRING))
                        .setGranularity(Granularities.ALL)
                        .setAggregatorSpecs(new CountAggregatorFactory("a0"))
                        .setHavingSpec(new DimFilterHavingSpec(equality("a0", 1L, ColumnType.LONG), true))
                        .setContext(QUERY_CONTEXT_UNNEST)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"a", 1L},
            new Object[]{"c", 1L}
        )
    );
  }

  @DecoupledTestConfig(quidemReason = QuidemTestCaseReason.IMPROVED_PLAN)
  @Test
  public void testUnnestWithGroupByHavingWithWhereOnUnnestCol()
  {
    cannotVectorize();
    testQuery(
        "SELECT d3, COUNT(*) FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) AS unnested(d3) WHERE d3 IN ('a','c') GROUP BY d3 HAVING d3='a'",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(UnnestDataSource.create(
                            new TableDataSource(CalciteTests.DATASOURCE3),
                            nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                            in("j0.unnest", ImmutableSet.of("a", "c"))
                        ))
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setContext(QUERY_CONTEXT_UNNEST)
                        .setDimensions(new DefaultDimensionSpec("j0.unnest", "d0", ColumnType.STRING))
                        .setGranularity(Granularities.ALL)
                        .setAggregatorSpecs(new CountAggregatorFactory("a0"))
                        .setDimFilter(equality("j0.unnest", "a", ColumnType.STRING))
                        .setContext(QUERY_CONTEXT_UNNEST)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"a", 1L}
        )
    );
  }

  @Test
  public void testUnnestWithGroupByWithWhereOnUnnestArrayCol()
  {
    cannotVectorize();
    testQuery(
        "SELECT uln, COUNT(*) FROM druid.arrays, UNNEST(arrayLongNulls) AS unnested(uln) WHERE uln IN (1, 2, 3) GROUP BY uln",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(UnnestDataSource.create(
                            new TableDataSource(CalciteTests.ARRAYS_DATASOURCE),
                            expressionVirtualColumn("j0.unnest", "\"arrayLongNulls\"", ColumnType.LONG_ARRAY),
                            in("j0.unnest", ColumnType.LONG, ImmutableList.of(1L, 2L, 3L))
                        ))
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setContext(QUERY_CONTEXT_UNNEST)
                        .setDimensions(new DefaultDimensionSpec("j0.unnest", "d0", ColumnType.LONG))
                        .setGranularity(Granularities.ALL)
                        .setAggregatorSpecs(new CountAggregatorFactory("a0"))
                        .setContext(QUERY_CONTEXT_UNNEST)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{1L, 5L},
            new Object[]{2L, 6L},
            new Object[]{3L, 6L}
        )
    );
  }

  @DecoupledTestConfig(quidemReason = QuidemTestCaseReason.IMPROVED_PLAN)
  @Test
  public void testUnnestWithGroupByHavingWithWhereOnUnnestArrayCol()
  {
    cannotVectorize();
    testQuery(
        "SELECT uln, COUNT(*) FROM druid.arrays, UNNEST(arrayLongNulls) AS unnested(uln) WHERE uln IN (1, 2, 3) GROUP BY uln HAVING uln=1",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(UnnestDataSource.create(
                            new TableDataSource(CalciteTests.ARRAYS_DATASOURCE),
                            expressionVirtualColumn("j0.unnest", "\"arrayLongNulls\"", ColumnType.LONG_ARRAY),
                            in("j0.unnest", ColumnType.LONG, ImmutableList.of(1L, 2L, 3L))
                        ))
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setContext(QUERY_CONTEXT_UNNEST)
                        .setDimensions(new DefaultDimensionSpec("j0.unnest", "d0", ColumnType.LONG))
                        .setGranularity(Granularities.ALL)
                        .setAggregatorSpecs(new CountAggregatorFactory("a0"))
                        .setDimFilter(equality("j0.unnest", 1L, ColumnType.LONG))
                        .setContext(QUERY_CONTEXT_UNNEST)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{1L, 5L}
        )
    );
  }

  @Test
  public void testUnnestVirtualWithColumnsAndNullIf()
  {
    testQuery(
        "select c,m2 from druid.foo, unnest(ARRAY[\"m1\", \"m2\"]) as u(c) where NULLIF(c,m2) IS NULL",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE1),
                      expressionVirtualColumn("j0.unnest", "array(\"m1\",\"m2\")", ColumnType.FLOAT_ARRAY),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .filters(
                      or(
                          expressionFilter("(\"j0.unnest\" == \"m2\")"),
                          and(
                              isNull("j0.unnest"),
                              not(istrue(expressionFilter("(\"j0.unnest\" == \"m2\")")))
                          )
                      )
                  )
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest", "m2")
                  .columnTypes(ColumnType.FLOAT, ColumnType.DOUBLE)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{1.0f, 1.0D},
            new Object[]{1.0f, 1.0D},
            new Object[]{2.0f, 2.0D},
            new Object[]{2.0f, 2.0D},
            new Object[]{3.0f, 3.0D},
            new Object[]{3.0f, 3.0D},
            new Object[]{4.0f, 4.0D},
            new Object[]{4.0f, 4.0D},
            new Object[]{5.0f, 5.0D},
            new Object[]{5.0f, 5.0D},
            new Object[]{6.0f, 6.0D},
            new Object[]{6.0f, 6.0D}
        )
    );
  }

  @Test
  public void testUnnestWithTimeFilterOnly()
  {
    testQuery(
        "select c from foo, unnest(MV_TO_ARRAY(dim3)) as u(c)"
        + " where __time >= TIMESTAMP '2000-01-02 00:00:00' and __time <= TIMESTAMP '2000-01-03 00:10:00'",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      FilteredDataSource.create(
                          new TableDataSource(CalciteTests.DATASOURCE1),
                          range("__time", ColumnType.LONG, 946771200000L, 946858200000L, false, false)
                      ),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      null
                  ))
                  .intervals(querySegmentSpec(Intervals.of("2000-01-02T00:00:00.000Z/2000-01-03T00:10:00.001Z")))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"b"},
            new Object[]{"c"},
            new Object[]{"d"}
        )
    );
  }

  @Test
  public void testUnnestWithTimeFilterOnlyArrayColumn()
  {
    testQuery(
        "select c from arrays, unnest(arrayStringNulls) as u(c)"
        + " where __time >= TIMESTAMP '2023-01-02 00:00:00' and __time <= TIMESTAMP '2023-01-03 00:10:00'",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      FilteredDataSource.create(
                          new TableDataSource(CalciteTests.ARRAYS_DATASOURCE),
                          range("__time", ColumnType.LONG, 1672617600000L, 1672704600000L, false, false)
                      ),
                      expressionVirtualColumn("j0.unnest", "\"arrayStringNulls\"", ColumnType.STRING_ARRAY),
                      null
                  ))
                  .intervals(querySegmentSpec(Intervals.of("2023-01-02T00:00:00.000Z/2023-01-03T00:10:00.001Z")))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{"b"},
            new Object[]{"b"},
            new Object[]{null},
            new Object[]{"d"},
            new Object[]{null},
            new Object[]{"b"},
            new Object[]{null},
            new Object[]{"b"}
        )
    );
  }

  @Test
  public void testUnnestWithTimeFilterAndAnotherFilter()
  {
    testQuery(
        "select c from foo, unnest(MV_TO_ARRAY(dim3)) as u(c) "
        + " where m1=2 and __time >= TIMESTAMP '2000-01-02 00:00:00' and __time <= TIMESTAMP '2000-01-03 00:10:00'",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      FilteredDataSource.create(
                          new TableDataSource(CalciteTests.DATASOURCE1),
                          and(
                              equality("m1", 2.0, ColumnType.FLOAT),
                              range("__time", ColumnType.LONG, 946771200000L, 946858200000L, false, false)
                          )
                      ),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      null
                  ))
                  .intervals(querySegmentSpec(Intervals.of("2000-01-02T00:00:00.000Z/2000-01-03T00:10:00.001Z")))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"b"},
            new Object[]{"c"}
        )
    );
  }

  @Test
  public void testUnnestWithTimeFilterOrAnotherFilter()
  {
    testQuery(
        "select c from foo, unnest(MV_TO_ARRAY(dim3)) as u(c) "
        + " where m1=2 or __time >= TIMESTAMP '2000-01-02 00:00:00' and __time <= TIMESTAMP '2000-01-03 00:10:00'",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      FilteredDataSource.create(
                          new TableDataSource(CalciteTests.DATASOURCE1),
                          or(
                              equality("m1", 2.0, ColumnType.FLOAT),
                              range("__time", ColumnType.LONG, 946771200000L, 946858200000L, false, false)
                          )
                      ),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"b"},
            new Object[]{"c"},
            new Object[]{"d"}
        )
    );
  }

  @Test
  public void testUnnestWithTimeFilterOnlyNested()
  {
    testQuery(
        "select c from foo CROSS JOIN UNNEST(ARRAY[m1,m2]) as un(d) CROSS JOIN unnest(MV_TO_ARRAY(dim3)) as u(c)"
        + " where __time >= TIMESTAMP '2000-01-02 00:00:00' and __time <= TIMESTAMP '2000-01-03 00:10:00'",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      UnnestDataSource.create(
                          FilteredDataSource.create(
                              new TableDataSource(CalciteTests.DATASOURCE1),
                              range("__time", ColumnType.LONG, 946771200000L, 946858200000L, false, false)
                          ),
                          expressionVirtualColumn("j0.unnest", "array(\"m1\",\"m2\")", ColumnType.FLOAT_ARRAY),
                          null
                      ),
                      nestedExpressionVirtualColumn(ds("j0.unnest"), "\"dim3\"", ColumnType.STRING),
                      null
                  ))
                  .intervals(querySegmentSpec(Intervals.of("2000-01-02T00:00:00.000Z/2000-01-03T00:10:00.001Z")))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns(ds("j0.unnest"))
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"b"},
            new Object[]{"c"},
            new Object[]{"b"},
            new Object[]{"c"},
            new Object[]{"d"},
            new Object[]{"d"}
        )
    );
  }

  @Test
  public void testUnnestWithTimeFilterOnlyNestedAndNestedAgain()
  {
    testQuery(
        "select c from foo CROSS JOIN UNNEST(ARRAY[m1,m2]) as un(d) CROSS JOIN UNNEST(ARRAY[dim1,dim2]) as ud(a) "
        + " CROSS JOIN unnest(MV_TO_ARRAY(dim3)) as u(c)"
        + " where __time >= TIMESTAMP '2000-01-02 00:00:00' and __time <= TIMESTAMP '2000-01-03 00:10:00'",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      UnnestDataSource.create(
                          UnnestDataSource.create(
                              FilteredDataSource.create(
                                  new TableDataSource(CalciteTests.DATASOURCE1),
                                  range("__time", ColumnType.LONG, 946771200000L, 946858200000L, false, false)
                              ),
                              expressionVirtualColumn("j0.unnest", "array(\"m1\",\"m2\")", ColumnType.FLOAT_ARRAY),
                              null
                          ),
                          expressionVirtualColumn(ds("j0.unnest"), "array(\"dim1\",\"dim2\")", ColumnType.STRING_ARRAY),
                          null
                      ),
                      nestedExpressionVirtualColumn(ds(ds("j0.unnest")), "\"dim3\"", ColumnType.STRING),
                      null
                  ))
                  .intervals(querySegmentSpec(Intervals.of("2000-01-02T00:00:00.000Z/2000-01-03T00:10:00.001Z")))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns(ds(ds("j0.unnest")))
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"b"},
            new Object[]{"c"},
            new Object[]{"b"},
            new Object[]{"c"},
            new Object[]{"b"},
            new Object[]{"c"},
            new Object[]{"b"},
            new Object[]{"c"},
            new Object[]{"d"},
            new Object[]{"d"},
            new Object[]{"d"},
            new Object[]{"d"}
        )
    );
  }

  @DecoupledTestConfig(ignoreExpectedQueriesReason = IgnoreQueriesReason.UNNEST_EXTRA_SCANQUERY)
  @Test
  public void testUnnestWithTimeFilterInsideSubquery()
  {
    testQuery(
        "select d3 from (select * from foo, UNNEST(MV_TO_ARRAY(dim3)) as u(d3)"
        + " where __time >= TIMESTAMP '2000-01-02 00:00:00' and __time <= TIMESTAMP '2000-01-03 00:10:00' LIMIT 2) \n"
        + " where m1 IN (1,2)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(
                      new QueryDataSource(
                          newScanQueryBuilder()
                              .dataSource(
                                  UnnestDataSource.create(
                                      FilteredDataSource.create(
                                          new TableDataSource(CalciteTests.DATASOURCE1),
                                          range("__time", ColumnType.LONG, 946771200000L, 946858200000L, false, false)
                                      ),
                                      expressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                                      null
                                  )
                              )
                              .intervals(querySegmentSpec(Intervals.of(
                                  "2000-01-02T00:00:00.000Z/2000-01-03T00:10:00.001Z")))
                              .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                              .columns("m1", "j0.unnest")
                              .columnTypes(ColumnType.FLOAT, ColumnType.STRING)
                              .limit(2)
                              .context(QUERY_CONTEXT_UNNEST)
                              .build()
                      )
                  )
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .filters(
                      in("m1", ColumnType.FLOAT, ImmutableList.of(1.0f, 2.0f))
                  )
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"b"},
            new Object[]{"c"}
        )
    );
  }

  @DecoupledTestConfig(ignoreExpectedQueriesReason = IgnoreQueriesReason.UNNEST_EXTRA_SCANQUERY)
  @Test
  public void testUnnestWithTimeFilterInsideSubqueryArrayColumns()
  {
    testQuery(
        "select uln from (select * from arrays, UNNEST(arrayLongNulls) as u(uln)"
        + " where __time >= TIMESTAMP '2023-01-02 00:00:00' and __time <= TIMESTAMP '2023-01-03 00:10:00' LIMIT 2) \n"
        + " where ARRAY_CONTAINS(arrayLongNulls, ARRAY[2])",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(
                      new QueryDataSource(
                          newScanQueryBuilder()
                              .dataSource(
                                  UnnestDataSource.create(
                                      FilteredDataSource.create(
                                          new TableDataSource(CalciteTests.ARRAYS_DATASOURCE),
                                          range("__time", ColumnType.LONG, 1672617600000L, 1672704600000L, false, false)
                                      ),
                                      expressionVirtualColumn("j0.unnest", "\"arrayLongNulls\"", ColumnType.LONG_ARRAY),
                                      null
                                  )
                              )
                              .intervals(querySegmentSpec(Intervals.of(
                                  "2023-01-02T00:00:00.000Z/2023-01-03T00:10:00.001Z")))
                              .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                              .columns("arrayLongNulls", "j0.unnest")
                              .columnTypes(ColumnType.LONG_ARRAY, ColumnType.LONG)
                              .limit(2)
                              .context(QUERY_CONTEXT_UNNEST)
                              .build()
                      )
                  )
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .filters(
                      new ArrayContainsElementFilter("arrayLongNulls", ColumnType.LONG, 2L, null)
                  )
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("j0.unnest")
                  .columnTypes(ColumnType.LONG)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{2L},
            new Object[]{3L}
        )
    );
  }

  @DecoupledTestConfig(ignoreExpectedQueriesReason = IgnoreQueriesReason.UNNEST_EXTRA_SCANQUERY)
  @Test
  public void testUnnestWithFilterAndUnnestNestedBackToBack()
  {
    testQuery(
        "SELECT m1, dim3_unnest1, dim3_unnest2, dim3_unnest3 FROM \n"
        + "      ( SELECT * FROM \n"
        + "           ( SELECT * FROM foo, UNNEST(MV_TO_ARRAY(dim3)) as ut(dim3_unnest1) ), \n"
        + "             UNNEST(MV_TO_ARRAY(dim3)) as ut(dim3_unnest2) \n"
        + "      ), UNNEST(MV_TO_ARRAY(dim3)) as ut(dim3_unnest3) "
        + " WHERE m1=2  AND (dim3_unnest1='a' OR dim3_unnest2='b') AND dim3_unnest3='c' "
        + " AND __time >= TIMESTAMP '2000-01-02 00:00:00' and __time <= TIMESTAMP '2000-01-03 00:10:00'",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(
                      UnnestDataSource.create(
                          FilteredDataSource.create(
                              UnnestDataSource.create(
                                  FilteredDataSource.create(
                                      UnnestDataSource.create(
                                          new TableDataSource(CalciteTests.DATASOURCE1),
                                          expressionVirtualColumn(
                                              "j0.unnest",
                                              "\"dim3\"",
                                              ColumnType.STRING
                                          ),
                                          null
                                      ),
                                      and(
                                          equality("m1", 2.0f, ColumnType.FLOAT),
                                          range("__time", ColumnType.LONG, 946771200000L, 946858200000L, false, false)
                                      )
                                  ),
                                  expressionVirtualColumn(
                                      "_j0.unnest",
                                      "\"dim3\"",
                                      ColumnType.STRING
                                  ),
                                  null
                              ),
                              or(
                                  equality("j0.unnest", "a", ColumnType.STRING),
                                  equality("_j0.unnest", "b", ColumnType.STRING)
                              )
                          ),
                          expressionVirtualColumn(
                              "__j0.unnest",
                              "\"dim3\"",
                              ColumnType.STRING
                          ),
                          equality("__j0.unnest", "c", ColumnType.STRING)
                      )
                  )
                  .intervals(querySegmentSpec(Intervals.of("2000-01-02T00:00:00.000Z/2000-01-03T00:10:00.001Z")))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .virtualColumns(expressionVirtualColumn("v0", "2.0", ColumnType.FLOAT))
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("v0", "j0.unnest", "_j0.unnest", "__j0.unnest")
                  .columnTypes(ColumnType.FLOAT, ColumnType.STRING, ColumnType.STRING, ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{2.0f, "b", "b", "c"},
            new Object[]{2.0f, "c", "b", "c"}
        )
    );
  }

  @DecoupledTestConfig(ignoreExpectedQueriesReason = IgnoreQueriesReason.UNNEST_EXTRA_SCANQUERY)
  @Test
  public void testUnnestWithLookup()
  {
    testQuery(
        "SELECT * FROM lookup.lookyloo, unnest(mv_to_array(v)) as u(d) where k='a'",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      FilteredDataSource.create(
                          new LookupDataSource("lookyloo"),
                          equality("k", "a", ColumnType.STRING)
                      ),
                      expressionVirtualColumn("j0.unnest", "\"v\"", ColumnType.STRING),
                      null
                  ))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .virtualColumns(expressionVirtualColumn("v0", "'a'", ColumnType.STRING))
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("v0", "v", "j0.unnest")
                  .columnTypes(ColumnType.STRING, ColumnType.STRING, ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a", "xa", "xa"}
        )
    );
  }

  @DecoupledTestConfig(quidemReason = QuidemTestCaseReason.UNNEST_VC_USES_PROJECTED_CONSTANT)
  @Test
  public void testUnnestWithGroupByOnExpression()
  {
    cannotVectorize();
    testQuery(
        "WITH X as \n"
        + "(\n"
        + "SELECT\n"
        + "ARRAY[1,2,3] as allNums\n"
        + "FROM foo\n"
        + "GROUP BY 1\n"
        + ")\n"
        + "select * from X CROSS JOIN UNNEST(X.allNums) as ud(num)",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                                  new QueryDataSource(
                                      GroupByQuery.builder()
                                                  .setDataSource(CalciteTests.DATASOURCE1)
                                                  .setInterval(new MultipleIntervalSegmentSpec(ImmutableList.of(
                                                      Filtration.eternity())))
                                                  .setVirtualColumns(expressionVirtualColumn(
                                                      "v0",
                                                      "array(1,2,3)",
                                                      ColumnType.LONG_ARRAY
                                                  ))
                                                  .setDimensions(dimensions(
                                                      new DefaultDimensionSpec(
                                                          "v0",
                                                          "d0",
                                                          ColumnType.LONG_ARRAY
                                                      )
                                                  ))
                                                  .setGranularity(Granularities.ALL)
                                                  .setContext(QUERY_CONTEXT_DEFAULT)
                                                  .build()),
                                  expressionVirtualColumn(
                                      "j0.unnest",
                                      "array(1,2,3)",
                                      ColumnType.LONG_ARRAY
                                  ),
                                  null
                              )
                  )
                  .eternityInterval()
                  .columns("d0", "j0.unnest")
                  .columnTypes(ColumnType.LONG_ARRAY, ColumnType.LONG)
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_DEFAULT)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{ImmutableList.of(1L, 2L, 3L), 1},
            new Object[]{ImmutableList.of(1L, 2L, 3L), 2},
            new Object[]{ImmutableList.of(1L, 2L, 3L), 3}
        )
    );
  }

  @Test
  public void testArrayToMvPostaggInline()
  {
    cannotVectorize();
    testQuery(
        "WITH \"ext\" AS (\n"
        + "  SELECT\n"
        + "    CAST(\"c0\" AS TIMESTAMP) AS \"__time\",\n"
        + "    STRING_TO_ARRAY(\"c1\", '<#>') AS \"strings\",\n"
        + "    CAST(STRING_TO_ARRAY(\"c2\", '<#>') AS BIGINT ARRAY) AS \"longs\"\n"
        + "  FROM (\n"
        + "    VALUES\n"
        + "    (0, 'A<#>B', '1<#>2'),\n"
        + "    (0, 'C<#>D', '3<#>4')\n"
        + "  ) AS \"t\" (\"c0\", \"c1\", \"c2\")\n"
        + ")\n"
        + "SELECT\n"
        + "  ARRAY_TO_MV(\"strings\") AS \"strings\",\n"
        + "  ARRAY_TO_MV(\"longs\") AS \"longs\",\n"
        + "  COUNT(*) AS \"count\"\n"
        + "FROM \"ext\"\n"
        + "GROUP BY \"strings\", \"longs\"",
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(
                            InlineDataSource.fromIterable(
                                Arrays.asList(
                                    new Object[]{0L, "A<#>B", "1<#>2"},
                                    new Object[]{0L, "C<#>D", "3<#>4"}
                                ),
                                RowSignature.builder()
                                            .add("c0", ColumnType.LONG)
                                            .add("c1", ColumnType.STRING)
                                            .add("c2", ColumnType.STRING)
                                            .build()
                            )
                        )
                        .setQuerySegmentSpec(new MultipleIntervalSegmentSpec(ImmutableList.of(Intervals.ETERNITY)))
                        .setDimensions(
                            new DefaultDimensionSpec("v0", "d0", ColumnType.STRING_ARRAY),
                            new DefaultDimensionSpec("v1", "d1", ColumnType.LONG_ARRAY)
                        )
                        .setVirtualColumns(
                            new ExpressionVirtualColumn(
                                "v0",
                                "string_to_array(\"c1\",'<#>')",
                                ColumnType.STRING_ARRAY,
                                TestExprMacroTable.INSTANCE
                            ),
                            new ExpressionVirtualColumn(
                                "v1",
                                "CAST(string_to_array(\"c2\",'<#>'), 'ARRAY<LONG>')",
                                ColumnType.LONG_ARRAY,
                                TestExprMacroTable.INSTANCE
                            )
                        )
                        .setAggregatorSpecs(
                            new CountAggregatorFactory("a0")
                        )
                        .setPostAggregatorSpecs(
                            new ExpressionPostAggregator(
                                "p0",
                                "array_to_mv(\"d0\")",
                                null,
                                ColumnType.STRING,
                                TestExprMacroTable.INSTANCE
                            ),
                            new ExpressionPostAggregator(
                                "p1",
                                "array_to_mv(\"d1\")",
                                null,
                                ColumnType.STRING,
                                TestExprMacroTable.INSTANCE
                            )
                        )
                        .setGranularity(Granularities.ALL)
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{"[\"A\",\"B\"]", "[\"1\",\"2\"]", 1L},
            new Object[]{"[\"C\",\"D\"]", "[\"3\",\"4\"]", 1L}
        ),
        RowSignature.builder()
                    .add("strings", ColumnType.STRING)
                    .add("longs", ColumnType.STRING)
                    .add("count", ColumnType.LONG)
                    .build()
    );
  }

  @DecoupledTestConfig(quidemReason = QuidemTestCaseReason.UNNEST_SUBSTRING_EMPTY)
  @Test
  public void testUnnestExtractionFn()
  {
    testQuery(
        "SELECT substring(d3,1) FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3) WHERE substring(d3,1) <> 'b'",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE3),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      expressionFilter("(substring(\"j0.unnest\", 0, -1) != 'b')")
                  ))
                  .virtualColumns(expressionVirtualColumn("v0", "substring(\"j0.unnest\", 0, -1)", ColumnType.STRING))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("v0")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"c"},
            new Object[]{"d"}
        )
    );
  }

  @Test
  public void testUnnestExtractionFnNull()
  {
    testQuery(
        "SELECT substring(d3,1) FROM druid.numfoo, UNNEST(MV_TO_ARRAY(dim3)) as unnested (d3) WHERE substring(d3,1) is not null",
        QUERY_CONTEXT_UNNEST,
        ImmutableList.of(
            Druids.newScanQueryBuilder()
                  .dataSource(UnnestDataSource.create(
                      new TableDataSource(CalciteTests.DATASOURCE3),
                      nestedExpressionVirtualColumn("j0.unnest", "\"dim3\"", ColumnType.STRING),
                      expressionFilter("notnull(substring(\"j0.unnest\", 0, -1))")
                  ))
                  .virtualColumns(expressionVirtualColumn("v0", "substring(\"j0.unnest\", 0, -1)", ColumnType.STRING))
                  .intervals(querySegmentSpec(Filtration.eternity()))
                  .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                  .context(QUERY_CONTEXT_UNNEST)
                  .columns("v0")
                  .columnTypes(ColumnType.STRING)
                  .build()
        ),
        ImmutableList.of(
            new Object[]{"a"},
            new Object[]{"b"},
            new Object[]{"b"},
            new Object[]{"c"},
            new Object[]{"d"}
        )
    );
  }

  @Test
  public void testBooleanConstExprArray()
  {
    ExprEval exprEval = ExprEval.ofArray(ExpressionType.LONG_ARRAY, new Long[]{1L, 0L, null});
    testQuery(
        "SELECT ARRAY[true, false, null] FROM druid.numfoo LIMIT 1",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.DATASOURCE3)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(new ExpressionVirtualColumn(
                    "v0",
                    "array(1,0,null)",
                    exprEval.toExpr(),
                    ColumnType.LONG_ARRAY
                ))
                .columns("v0")
                .columnTypes(ColumnType.LONG_ARRAY)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(1)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{"[1,0,null]"}
        )
    );
  }

  @Test
  public void testGroupByNestedArrayInline()
  {
    cannotVectorize();
    // msq does not support nested arrays currently
    msqIncompatible();
    testQuery(
        "SELECT c1, ARRAY_PREPEND('1', ARRAY_AGG(ARRAY[1,c2], 100000)) c5 \n"
        + "FROM (VALUES (1,1),(2,2),(3,3)) t(c1,c2)\n"
        + "GROUP BY 1 \n"
        + "HAVING ARRAY_PREPEND('1', ARRAY_AGG(ARRAY[1,c2], 100000)) <> ARRAY_PREPEND('0', ARRAY_AGG(ARRAY[1,c2], 100000))",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(
                            InlineDataSource.fromIterable(
                                ImmutableList.of(
                                    new Object[]{1L, 1L},
                                    new Object[]{2L, 2L},
                                    new Object[]{3L, 3L}
                                ),
                                RowSignature.builder()
                                            .add("c1", ColumnType.LONG)
                                            .add("c2", ColumnType.LONG)
                                            .build()
                            )
                        )
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setVirtualColumns(
                            expressionVirtualColumn(
                            "v0",
                            "array(1,\"c2\")",
                            ColumnType.LONG_ARRAY
                            )
                        )
                        .setDimensions(new DefaultDimensionSpec("c1", "d0", ColumnType.LONG))
                        .setAggregatorSpecs(
                            new ExpressionLambdaAggregatorFactory(
                                "a0",
                                ImmutableSet.of("v0"),
                                "__acc",
                                "ARRAY<ARRAY<LONG>>[]",
                                "ARRAY<ARRAY<LONG>>[]",
                                true,
                                true,
                                false,
                                "array_append(\"__acc\", \"v0\")",
                                "array_concat(\"__acc\", \"a0\")",
                                null,
                                null,
                                HumanReadableBytes.valueOf(100000),
                                TestExprMacroTable.INSTANCE
                            )
                        )
                        .setPostAggregatorSpecs(
                            expressionPostAgg(
                                "p0",
                                "array_prepend('1',\"a0\")",
                                ColumnType.ofArray(ColumnType.LONG_ARRAY)
                            )
                        )
                        .setHavingSpec(
                            new DimFilterHavingSpec(
                                expressionFilter("(array_prepend('1',\"a0\") != array_prepend('0',\"a0\"))"),
                                true
                            )
                        )
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{1, ImmutableList.of(ImmutableList.of(1L), ImmutableList.of(1L, 1L))},
            new Object[]{2, ImmutableList.of(ImmutableList.of(1L), ImmutableList.of(1L, 2L))},
            new Object[]{3, ImmutableList.of(ImmutableList.of(1L), ImmutableList.of(1L, 3L))}
        )
    );
  }

  @Test
  public void testGroupByNestedArrayInlineCount()
  {
    cannotVectorize();
    // msq does not support nested arrays currently
    msqIncompatible();
    testQuery(
        "SELECT COUNT(*) c FROM (\n"
        + "SELECT c1, ARRAY_PREPEND('1', ARRAY_AGG(ARRAY[1,c2], 100000)) c5 \n"
        + "FROM (VALUES (1,1),(2,2),(3,3)) t(c1,c2)\n"
        + "GROUP BY 1 \n"
        + "HAVING ARRAY_PREPEND('1', ARRAY_AGG(ARRAY[1,c2], 100000)) <> ARRAY_PREPEND('0', ARRAY_AGG(ARRAY[1,c2], 100000))\n"
        + ")",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(
                            GroupByQuery.builder()
                                        .setDataSource(
                                            InlineDataSource.fromIterable(
                                                ImmutableList.of(
                                                    new Object[]{1L, 1L},
                                                    new Object[]{2L, 2L},
                                                    new Object[]{3L, 3L}
                                                ),
                                                RowSignature.builder()
                                                            .add("c1", ColumnType.LONG)
                                                            .add("c2", ColumnType.LONG)
                                                            .build()
                                            )
                                        )
                                        .setInterval(querySegmentSpec(Filtration.eternity()))
                                        .setGranularity(Granularities.ALL)
                                        .setVirtualColumns(
                                            expressionVirtualColumn(
                                                "v0",
                                                "array(1,\"c2\")",
                                                ColumnType.LONG_ARRAY
                                            )
                                        )
                                        .setDimensions(new DefaultDimensionSpec("c1", "d0", ColumnType.LONG))
                                        .setAggregatorSpecs(
                                            new ExpressionLambdaAggregatorFactory(
                                                "a0",
                                                ImmutableSet.of("v0"),
                                                "__acc",
                                                "ARRAY<ARRAY<LONG>>[]",
                                                "ARRAY<ARRAY<LONG>>[]",
                                                true,
                                                true,
                                                false,
                                                "array_append(\"__acc\", \"v0\")",
                                                "array_concat(\"__acc\", \"a0\")",
                                                null,
                                                null,
                                                HumanReadableBytes.valueOf(100000),
                                                TestExprMacroTable.INSTANCE
                                            )
                                        )
                                        .setHavingSpec(
                                            new DimFilterHavingSpec(
                                                expressionFilter(
                                                    "(array_prepend('1',\"a0\") != array_prepend('0',\"a0\"))"),
                                                true
                                            )
                                        )
                                        .setContext(QUERY_CONTEXT_DEFAULT)
                                        .build()
                        )
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setAggregatorSpecs(new CountAggregatorFactory("_a0"))
                        .setContext(QUERY_CONTEXT_DEFAULT)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{3L}
        )
    );
  }

  @Test
  public void testNullArray()
  {
    testQuery(
        "SELECT arrayLongNulls = ARRAY[null, null] FROM druid.arrays LIMIT 1",
        ImmutableList.of(
            newScanQueryBuilder()
                .dataSource(CalciteTests.ARRAYS_DATASOURCE)
                .intervals(querySegmentSpec(Filtration.eternity()))
                .virtualColumns(
                    expressionVirtualColumn(
                        "v0",
                        "(\"arrayLongNulls\" == CAST(array(null,null), 'ARRAY<LONG>'))",
                        ColumnType.LONG
                    )
                )
                .columns("v0")
                .columnTypes(ColumnType.LONG)
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .limit(1)
                .context(QUERY_CONTEXT_DEFAULT)
                .build()
        ),
        ImmutableList.of(
            new Object[]{false}
        )
    );
  }

  @Test
  public void testArrayGroupStringArrayColumnLimit()
  {
    cannotVectorize();
    testQuery(
        "SELECT arrayStringNulls, SUM(cnt) FROM druid.arrays GROUP BY 1 ORDER BY 1 DESC LIMIT 10",
        QUERY_CONTEXT_NO_STRINGIFY_ARRAY,
        ImmutableList.of(
            GroupByQuery.builder()
                        .setDataSource(CalciteTests.ARRAYS_DATASOURCE)
                        .setInterval(querySegmentSpec(Filtration.eternity()))
                        .setGranularity(Granularities.ALL)
                        .setDimensions(
                            dimensions(
                                new DefaultDimensionSpec("arrayStringNulls", "d0", ColumnType.STRING_ARRAY)
                            )
                        )
                        .setAggregatorSpecs(aggregators(new LongSumAggregatorFactory("a0", "cnt")))
                        .setLimitSpec(
                            new DefaultLimitSpec(
                                ImmutableList.of(
                                    new OrderByColumnSpec(
                                        "d0",
                                        OrderByColumnSpec.Direction.DESCENDING,
                                        StringComparators.NATURAL
                                    )
                                ),
                                10
                            )
                        )
                        .setContext(QUERY_CONTEXT_NO_STRINGIFY_ARRAY)
                        .build()
        ),
        ImmutableList.of(
            new Object[]{Arrays.asList("d", null, "b"), 2L},
            new Object[]{Arrays.asList("b", "b"), 2L},
            new Object[]{Arrays.asList("a", "b"), 3L},
            new Object[]{Arrays.asList(null, "b"), 2L},
            new Object[]{Collections.singletonList(null), 1L},
            new Object[]{Collections.emptyList(), 1L},
            new Object[]{null, 3L}
        )
    );
  }
}
