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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.druid.error.DruidException;
import org.apache.druid.error.DruidException.Category;
import org.apache.druid.error.DruidException.Persona;
import org.apache.druid.error.DruidExceptionMatcher;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.RE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.granularity.Granularity;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.math.expr.ExprEval;
import org.apache.druid.math.expr.ExpressionProcessing;
import org.apache.druid.query.DataSource;
import org.apache.druid.query.Druids;
import org.apache.druid.query.JoinAlgorithm;
import org.apache.druid.query.JoinDataSource;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryContexts;
import org.apache.druid.query.QueryDataSource;
import org.apache.druid.query.TableDataSource;
import org.apache.druid.query.UnionDataSource;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.post.ExpressionPostAggregator;
import org.apache.druid.query.dimension.DimensionSpec;
import org.apache.druid.query.extraction.CascadeExtractionFn;
import org.apache.druid.query.extraction.ExtractionFn;
import org.apache.druid.query.filter.AndDimFilter;
import org.apache.druid.query.filter.DimFilter;
import org.apache.druid.query.filter.EqualityFilter;
import org.apache.druid.query.filter.ExpressionDimFilter;
import org.apache.druid.query.filter.InDimFilter;
import org.apache.druid.query.filter.IsTrueDimFilter;
import org.apache.druid.query.filter.NotDimFilter;
import org.apache.druid.query.filter.NullFilter;
import org.apache.druid.query.filter.OrDimFilter;
import org.apache.druid.query.filter.RangeFilter;
import org.apache.druid.query.filter.TypedInFilter;
import org.apache.druid.query.groupby.GroupByQuery;
import org.apache.druid.query.groupby.having.DimFilterHavingSpec;
import org.apache.druid.query.scan.ScanQuery;
import org.apache.druid.query.spec.MultipleIntervalSegmentSpec;
import org.apache.druid.query.spec.QuerySegmentSpec;
import org.apache.druid.query.timeseries.TimeseriesQuery;
import org.apache.druid.query.union.UnionQuery;
import org.apache.druid.segment.column.ColumnHolder;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.RowSignature;
import org.apache.druid.segment.column.ValueType;
import org.apache.druid.segment.join.JoinType;
import org.apache.druid.segment.virtual.ExpressionVirtualColumn;
import org.apache.druid.server.security.AuthConfig;
import org.apache.druid.server.security.AuthenticationResult;
import org.apache.druid.server.security.ForbiddenException;
import org.apache.druid.server.security.ResourceAction;
import org.apache.druid.sql.SqlStatementFactory;
import org.apache.druid.sql.calcite.QueryTestRunner.QueryResults;
import org.apache.druid.sql.calcite.expression.DruidExpression;
import org.apache.druid.sql.calcite.planner.Calcites;
import org.apache.druid.sql.calcite.planner.PlannerConfig;
import org.apache.druid.sql.calcite.planner.PlannerContext;
import org.apache.druid.sql.calcite.run.EngineFeature;
import org.apache.druid.sql.calcite.util.CalciteTestBase;
import org.apache.druid.sql.calcite.util.CalciteTests;
import org.apache.druid.sql.calcite.util.SqlTestFramework;
import org.apache.druid.sql.calcite.util.SqlTestFramework.PlannerFixture;
import org.apache.druid.sql.http.SqlParameter;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Matcher;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Interval;
import org.joda.time.chrono.ISOChronology;
import org.junit.Assert;
import org.junit.internal.matchers.ThrowableMessageMatcher;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A base class for SQL query testing. It sets up query execution environment, provides useful helper methods,
 * and populates data using {@link CalciteTests#createMockWalker}.
 */
public class BaseCalciteQueryTest extends CalciteTestBase
{
  public static final double ASSERTION_EPSILON = 1e-5;

  public static final Logger log = new Logger(BaseCalciteQueryTest.class);

  public static final PlannerConfig PLANNER_CONFIG_DEFAULT = new PlannerConfig();

  public static final PlannerConfig PLANNER_CONFIG_REQUIRE_TIME_CONDITION =
      PlannerConfig.builder().requireTimeCondition(true).build();

  public static final PlannerConfig PLANNER_CONFIG_NO_TOPN =
      PlannerConfig.builder().maxTopNLimit(0).build();

  public static final PlannerConfig PLANNER_CONFIG_NO_HLL =
      PlannerConfig.builder().useApproximateCountDistinct(false).build();

  public static final String LOS_ANGELES = "America/Los_Angeles";
  public static final PlannerConfig PLANNER_CONFIG_LOS_ANGELES =
      PlannerConfig
          .builder()
          .sqlTimeZone(DateTimes.inferTzFromString(LOS_ANGELES))
          .build();

  public static final PlannerConfig PLANNER_CONFIG_AUTHORIZE_SYS_TABLES =
      PlannerConfig.builder().authorizeSystemTablesDirectly(true).build();

  public static final PlannerConfig PLANNER_CONFIG_LEGACY_QUERY_EXPLAIN =
      PlannerConfig.builder().useNativeQueryExplain(false).build();

  public static final PlannerConfig PLANNER_CONFIG_NATIVE_QUERY_EXPLAIN =
      PlannerConfig.builder().useNativeQueryExplain(true).build();

  public static final int MAX_NUM_IN_FILTERS = 100;
  public static final PlannerConfig PLANNER_CONFIG_MAX_NUMERIC_IN_FILTER =
      PlannerConfig.builder().maxNumericInFilters(MAX_NUM_IN_FILTERS).build();

  public static final String DUMMY_SQL_ID = "dummy";

  public static final String PRETEND_CURRENT_TIME = "2000-01-01T00:00:00Z";

  public static final Map<String, Object> QUERY_CONTEXT_DEFAULT =
      ImmutableMap.<String, Object>builder()
                  .put(QueryContexts.CTX_SQL_QUERY_ID, DUMMY_SQL_ID)
                  .put(PlannerContext.CTX_SQL_CURRENT_TIMESTAMP, "2000-01-01T00:00:00Z")
                  .put(QueryContexts.DEFAULT_TIMEOUT_KEY, QueryContexts.DEFAULT_TIMEOUT_MILLIS)
                  .put(QueryContexts.MAX_SCATTER_GATHER_BYTES_KEY, Long.MAX_VALUE)
                  .build();

  public static final Map<String, Object> QUERY_CONTEXT_NO_STRINGIFY_ARRAY =
      ImmutableMap.<String, Object>builder()
                  .putAll(QUERY_CONTEXT_DEFAULT)
                  .put(QueryContexts.CTX_SQL_STRINGIFY_ARRAYS, false)
                  .build();

  public static final Map<String, Object> QUERY_CONTEXT_NO_STRINGIFY_ARRAY_USE_EQUALITY =
      ImmutableMap.<String, Object>builder()
                  .putAll(QUERY_CONTEXT_NO_STRINGIFY_ARRAY)
                  .build();

  public static final Map<String, Object> QUERY_CONTEXT_DONT_SKIP_EMPTY_BUCKETS = ImmutableMap.of(
      QueryContexts.CTX_SQL_QUERY_ID, DUMMY_SQL_ID,
      PlannerContext.CTX_SQL_CURRENT_TIMESTAMP, PRETEND_CURRENT_TIME,
      TimeseriesQuery.SKIP_EMPTY_BUCKETS, false,
      QueryContexts.DEFAULT_TIMEOUT_KEY, QueryContexts.DEFAULT_TIMEOUT_MILLIS,
      QueryContexts.MAX_SCATTER_GATHER_BYTES_KEY, Long.MAX_VALUE
  );

  public static final Map<String, Object> QUERY_CONTEXT_DO_SKIP_EMPTY_BUCKETS = ImmutableMap.of(
      QueryContexts.CTX_SQL_QUERY_ID, DUMMY_SQL_ID,
      PlannerContext.CTX_SQL_CURRENT_TIMESTAMP, PRETEND_CURRENT_TIME,
      TimeseriesQuery.SKIP_EMPTY_BUCKETS, true,
      QueryContexts.DEFAULT_TIMEOUT_KEY, QueryContexts.DEFAULT_TIMEOUT_MILLIS,
      QueryContexts.MAX_SCATTER_GATHER_BYTES_KEY, Long.MAX_VALUE
  );

  public static final Map<String, Object> QUERY_CONTEXT_LEXICOGRAPHIC_TOPN =
      QueryContexts.override(QUERY_CONTEXT_DEFAULT, PlannerConfig.CTX_KEY_USE_LEXICOGRAPHIC_TOPN, true);

  public static final Map<String, Object> QUERY_CONTEXT_NO_TOPN = ImmutableMap.of(
      QueryContexts.CTX_SQL_QUERY_ID, DUMMY_SQL_ID,
      PlannerContext.CTX_SQL_CURRENT_TIMESTAMP, PRETEND_CURRENT_TIME,
      PlannerConfig.CTX_KEY_USE_APPROXIMATE_TOPN, "false",
      QueryContexts.DEFAULT_TIMEOUT_KEY, QueryContexts.DEFAULT_TIMEOUT_MILLIS,
      QueryContexts.MAX_SCATTER_GATHER_BYTES_KEY, Long.MAX_VALUE
  );

  public static final Map<String, Object> QUERY_CONTEXT_LOS_ANGELES = ImmutableMap.of(
      QueryContexts.CTX_SQL_QUERY_ID, DUMMY_SQL_ID,
      PlannerContext.CTX_SQL_CURRENT_TIMESTAMP, PRETEND_CURRENT_TIME,
      PlannerContext.CTX_SQL_TIME_ZONE, LOS_ANGELES,
      QueryContexts.DEFAULT_TIMEOUT_KEY, QueryContexts.DEFAULT_TIMEOUT_MILLIS,
      QueryContexts.MAX_SCATTER_GATHER_BYTES_KEY, Long.MAX_VALUE
  );

  // Matches QUERY_CONTEXT_DEFAULT
  public static final Map<String, Object> TIMESERIES_CONTEXT_BY_GRAN = ImmutableMap.of(
      QueryContexts.CTX_SQL_QUERY_ID, DUMMY_SQL_ID,
      PlannerContext.CTX_SQL_CURRENT_TIMESTAMP, PRETEND_CURRENT_TIME,
      TimeseriesQuery.SKIP_EMPTY_BUCKETS, true,
      QueryContexts.DEFAULT_TIMEOUT_KEY, QueryContexts.DEFAULT_TIMEOUT_MILLIS,
      QueryContexts.MAX_SCATTER_GATHER_BYTES_KEY, Long.MAX_VALUE
  );

  public static final Map<String, Object> QUERY_CONTEXT_WITH_SUBQUERY_MEMORY_LIMIT =
      ImmutableMap.<String, Object>builder()
                  .putAll(QUERY_CONTEXT_DEFAULT)
                  .put(QueryContexts.MAX_SUBQUERY_BYTES_KEY, "100000")
                  // Disallows the fallback to row based limiting
                  .put(QueryContexts.MAX_SUBQUERY_ROWS_KEY, "1")
                  .build();

  // Add additional context to the given context map for when the
  // timeseries query has timestamp_floor expression on the timestamp dimension
  public static Map<String, Object> getTimeseriesContextWithFloorTime(
      Map<String, Object> context,
      String timestampResultField
  )
  {
    return ImmutableMap.<String, Object>builder()
                       .putAll(context)
                       .put(TimeseriesQuery.CTX_TIMESTAMP_RESULT_FIELD, timestampResultField)
                       .build();
  }

  // Matches QUERY_CONTEXT_LOS_ANGELES
  public static final Map<String, Object> TIMESERIES_CONTEXT_LOS_ANGELES = new HashMap<>();

  public static final Map<String, Object> OUTER_LIMIT_CONTEXT = new HashMap<>(QUERY_CONTEXT_DEFAULT);

  public boolean cannotVectorize = false;
  public boolean cannotVectorizeUnlessFallback = false;
  public boolean skipVectorize = false;

  static {
    TIMESERIES_CONTEXT_LOS_ANGELES.put(QueryContexts.CTX_SQL_QUERY_ID, DUMMY_SQL_ID);
    TIMESERIES_CONTEXT_LOS_ANGELES.put(PlannerContext.CTX_SQL_CURRENT_TIMESTAMP, "2000-01-01T00:00:00Z");
    TIMESERIES_CONTEXT_LOS_ANGELES.put(PlannerContext.CTX_SQL_TIME_ZONE, LOS_ANGELES);
    TIMESERIES_CONTEXT_LOS_ANGELES.put(TimeseriesQuery.SKIP_EMPTY_BUCKETS, true);
    TIMESERIES_CONTEXT_LOS_ANGELES.put(QueryContexts.DEFAULT_TIMEOUT_KEY, QueryContexts.DEFAULT_TIMEOUT_MILLIS);
    TIMESERIES_CONTEXT_LOS_ANGELES.put(QueryContexts.MAX_SCATTER_GATHER_BYTES_KEY, Long.MAX_VALUE);

    OUTER_LIMIT_CONTEXT.put(PlannerContext.CTX_SQL_OUTER_LIMIT, 2);
  }

  public static boolean developerIDEdetected()
  {
    String javaCmd = System.getProperties().getProperty("sun.java.command", "");
    boolean isEclipse = javaCmd.contains("org.eclipse.jdt.internal.junit.runner.RemoteTestRunner");
    return isEclipse;
  }

  // Generate timestamps for expected results
  public static long timestamp(final String timeString)
  {
    return Calcites.jodaToCalciteTimestamp(DateTimes.of(timeString), DateTimeZone.UTC);
  }

  // Generate timestamps for expected results
  public static long timestamp(final String timeString, final String timeZoneString)
  {
    final DateTimeZone timeZone = DateTimes.inferTzFromString(timeZoneString);
    return Calcites.jodaToCalciteTimestamp(new DateTime(timeString, timeZone), timeZone);
  }

  // Generate day numbers for expected results
  public static int day(final String dayString)
  {
    return (int) (Intervals.utc(timestamp("1970"), timestamp(dayString)).toDurationMillis() / (86400L * 1000L));
  }

  public static QuerySegmentSpec querySegmentSpec(final Interval... intervals)
  {
    return new MultipleIntervalSegmentSpec(Arrays.asList(intervals));
  }

  public static AndDimFilter and(DimFilter... filters)
  {
    return new AndDimFilter(Arrays.asList(filters));
  }

  public static OrDimFilter or(DimFilter... filters)
  {
    return new OrDimFilter(Arrays.asList(filters));
  }

  public static NotDimFilter not(DimFilter filter)
  {
    return new NotDimFilter(filter);
  }

  public static IsTrueDimFilter istrue(DimFilter filter)
  {
    return new IsTrueDimFilter(filter);
  }

  public static DimFilter in(String dimension, Collection<String> values)
  {
    return in(dimension, ColumnType.STRING, new ArrayList<>(values));
  }

  public static DimFilter in(String dimension, Collection<String> values, ExtractionFn extractionFn)
  {
    if (extractionFn == null) {
      return in(dimension, ColumnType.STRING, new ArrayList<>(values));
    }
    return new InDimFilter(dimension, values, extractionFn);
  }

  public static DimFilter in(String dimension, ColumnType matchValueType, List<?> values)
  {
    return new TypedInFilter(dimension, matchValueType, values, null, null);
  }

  public static DimFilter isNull(final String fieldName)
  {
    return isNull(fieldName, null);
  }

  public static DimFilter isNull(final String fieldName, final ExtractionFn extractionFn)
  {
    return new NullFilter(fieldName, null);
  }

  public static DimFilter notNull(final String fieldName)
  {
    return not(isNull(fieldName));
  }

  public static DimFilter equality(final String fieldName, final Object matchValue, final ColumnType matchValueType)
  {
    return new EqualityFilter(fieldName, matchValueType, matchValue, null);
  }

  public static ExpressionDimFilter expressionFilter(final String expression)
  {
    return new ExpressionDimFilter(expression, CalciteTests.createExprMacroTable());
  }

  public static DimFilter range(
      final String fieldName,
      final ColumnType matchValueType,
      final Object lower,
      final Object upper,
      final boolean lowerStrict,
      final boolean upperStrict
  )
  {
    return new RangeFilter(fieldName, matchValueType, lower, upper, lowerStrict, upperStrict, null);
  }

  public static DimFilter timeRange(final Object intervalObj)
  {
    final Interval interval = new Interval(intervalObj, ISOChronology.getInstanceUTC());
    return range(
        ColumnHolder.TIME_COLUMN_NAME,
        ColumnType.LONG,
        interval.getStartMillis(),
        interval.getEndMillis(),
        false,
        true
    );
  }

  public static CascadeExtractionFn cascade(final ExtractionFn... fns)
  {
    return new CascadeExtractionFn(fns);
  }

  public static List<DimensionSpec> dimensions(final DimensionSpec... dimensionSpecs)
  {
    return Arrays.asList(dimensionSpecs);
  }

  public static List<AggregatorFactory> aggregators(final AggregatorFactory... aggregators)
  {
    return Arrays.asList(aggregators);
  }

  public static DimFilterHavingSpec having(final DimFilter filter)
  {
    return new DimFilterHavingSpec(filter, true);
  }

  public static ExpressionVirtualColumn expressionVirtualColumn(
      final String name,
      final String expression,
      final ColumnType outputType
  )
  {
    return new ExpressionVirtualColumn(name, expression, outputType, CalciteTests.createExprMacroTable());
  }

  /**
   * Optionally updates the VC defintion for the one planned by the decoupled planner.
   *
   * Compared to original plans; decoupled planner:
   *  * moves the mv_to_array into the VC
   *  * the type is an ARRAY
   */
  public ExpressionVirtualColumn nestedExpressionVirtualColumn(
      String name,
      String expression,
      ColumnType outputType)
  {
    if (testBuilder().isDecoupledMode()) {
      expression = StringUtils.format("mv_to_array(%s)", expression);
      outputType = ColumnType.ofArray(outputType);
    }
    return expressionVirtualColumn(name, expression, outputType);
  }

  public static JoinDataSource join(
      DataSource left,
      DataSource right,
      String rightPrefix,
      String condition,
      JoinType joinType,
      DimFilter filter,
      JoinAlgorithm joinAlgorithm
  )
  {
    return JoinDataSource.create(
        left,
        right,
        rightPrefix,
        condition,
        joinType,
        filter,
        CalciteTests.createExprMacroTable(),
        CalciteTests.createJoinableFactoryWrapper(),
        joinAlgorithm
    );
  }

  public static JoinDataSource join(
      DataSource left,
      DataSource right,
      String rightPrefix,
      String condition,
      JoinType joinType,
      DimFilter filter
  )
  {
    return join(
        left,
        right,
        rightPrefix,
        condition,
        joinType,
        filter,
        JoinAlgorithm.BROADCAST
    );
  }

  public static JoinDataSource join(
      DataSource left,
      DataSource right,
      String rightPrefix,
      String condition,
      JoinType joinType
  )
  {
    return join(left, right, rightPrefix, condition, joinType, null);
  }

  public static UnionDataSource unionDataSource(String... datasources)
  {
    List<DataSource> sources = Stream.of(datasources).map(TableDataSource::new).collect(Collectors.toList());
    return new UnionDataSource(sources);
  }

  public static String equalsCondition(DruidExpression left, DruidExpression right)
  {
    return StringUtils.format("(%s == %s)", left.getExpression(), right.getExpression());
  }

  public static ExpressionPostAggregator expressionPostAgg(final String name, final String expression, ColumnType outputType)
  {
    return new ExpressionPostAggregator(name, expression, null, outputType, CalciteTests.createExprMacroTable());
  }

  public static Druids.ScanQueryBuilder newScanQueryBuilder()
  {
    return new Druids.ScanQueryBuilder().resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST);
  }

  protected static DruidExceptionMatcher invalidSqlIs(String s)
  {
    return DruidExceptionMatcher.invalidSqlInput().expectMessageIs(s);
  }

  protected static DruidExceptionMatcher invalidSqlContains(String s)
  {
    return DruidExceptionMatcher.invalidSqlInput().expectMessageContains(s);
  }

  @RegisterExtension
  protected static SqlTestFrameworkConfig.Rule queryFrameworkRule = new SqlTestFrameworkConfig.Rule();

  public SqlTestFramework queryFramework()
  {
    try {
      return queryFrameworkRule.get();
    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public void assumeFeatureAvailable(EngineFeature feature)
  {
    boolean featureAvailable = queryFramework().engine().featureAvailable(feature);
    assumeTrue(featureAvailable, StringUtils.format("test disabled; feature [%s] is not available!", feature));
  }

  public void assertQueryIsUnplannable(final String sql, String expectedError)
  {
    assertQueryIsUnplannable(PLANNER_CONFIG_DEFAULT, sql, expectedError);
  }

  public void assertQueryIsUnplannable(final PlannerConfig plannerConfig, final String sql, String expectedError)
  {
    try {
      testQuery(plannerConfig, sql, CalciteTests.REGULAR_USER_AUTH_RESULT, ImmutableList.of(), ImmutableList.of());
    }
    catch (DruidException e) {
      assertThat(
          e,
          buildUnplannableExceptionMatcher().expectMessageContains(expectedError)
      );
    }
    catch (Exception e) {
      log.error(e, "Expected DruidException for query: %s", sql);
      throw e;
    }
  }

  private DruidExceptionMatcher buildUnplannableExceptionMatcher()
  {
    if (testBuilder().isDecoupledMode()) {
      return new DruidExceptionMatcher(Persona.USER, Category.INVALID_INPUT, "invalidInput");
    } else {
      return new DruidExceptionMatcher(Persona.USER, Category.INVALID_INPUT, "general");
    }
  }

  /**
   * Provided for tests that wish to check multiple queries instead of relying on ExpectedException.
   */
  public void assertQueryIsForbidden(final String sql, final AuthenticationResult authenticationResult)
  {
    assertQueryIsForbidden(PLANNER_CONFIG_DEFAULT, sql, authenticationResult);
  }

  public void assertQueryIsForbidden(
      final PlannerConfig plannerConfig,
      final String sql,
      final AuthenticationResult authenticationResult
  )
  {
    Exception e = null;
    try {
      testQuery(plannerConfig, sql, authenticationResult, ImmutableList.of(), ImmutableList.of());
    }
    catch (Exception e1) {
      e = e1;
    }

    if (!(e instanceof ForbiddenException)) {
      log.error(e, "Expected ForbiddenException for query: %s with authResult: %s", sql, authenticationResult);
      Assert.fail(sql);
    }
  }

  public void testQuery(
      final String sql,
      final List<Query<?>> expectedQueries,
      final List<Object[]> expectedResults
  )
  {
    testBuilder()
        .sql(sql)
        .expectedQueries(expectedQueries)
        .expectedResults(expectedResults)
        .run();
  }

  public void testQuery(
      final String sql,
      final List<Query<?>> expectedQueries,
      final ResultMatchMode resultsMatchMode,
      final List<Object[]> expectedResults
  )
  {
    testBuilder()
        .sql(sql)
        .expectedQueries(expectedQueries)
        .expectedResults(resultsMatchMode, expectedResults)
        .run();
  }

  public void testQuery(
      final String sql,
      final List<Query<?>> expectedQueries,
      final List<Object[]> expectedResults,
      final RowSignature expectedResultSignature
  )
  {
    testBuilder()
        .sql(sql)
        .expectedQueries(expectedQueries)
        .expectedResults(expectedResults)
        .expectedSignature(expectedResultSignature)
        .run();
  }

  public void testQuery(
      final String sql,
      final Map<String, Object> queryContext,
      final List<Query<?>> expectedQueries,
      final List<Object[]> expectedResults
  )
  {
    testBuilder()
        .queryContext(queryContext)
        .sql(sql)
        .expectedQueries(expectedQueries)
        .expectedResults(expectedResults)
        .run();
  }

  public void testQuery(
      final String sql,
      final List<Query<?>> expectedQueries,
      final List<Object[]> expectedResults,
      final List<SqlParameter> parameters
  )
  {
    testBuilder()
        .sql(sql)
        .parameters(parameters)
        .expectedQueries(expectedQueries)
        .expectedResults(expectedResults)
        .run();
  }

  public void testQuery(
      final PlannerConfig plannerConfig,
      final String sql,
      final AuthenticationResult authenticationResult,
      final List<Query<?>> expectedQueries,
      final List<Object[]> expectedResults
  )
  {
    testBuilder()
        .plannerConfig(plannerConfig)
        .sql(sql)
        .authResult(authenticationResult)
        .expectedQueries(expectedQueries)
        .expectedResults(expectedResults)
        .run();
  }

  public void testQuery(
      final String sql,
      final Map<String, Object> queryContext,
      final List<Query<?>> expectedQueries,
      final ResultsVerifier expectedResultsVerifier
  )
  {
    testBuilder()
        .sql(sql)
        .queryContext(queryContext)
        .expectedQueries(expectedQueries)
        .expectedResults(expectedResultsVerifier)
        .run();
  }

  public void testQuery(
      final PlannerConfig plannerConfig,
      final Map<String, Object> queryContext,
      final String sql,
      final AuthenticationResult authenticationResult,
      final List<Query<?>> expectedQueries,
      final List<Object[]> expectedResults
  )
  {
    testBuilder()
        .plannerConfig(plannerConfig)
        .queryContext(queryContext)
        .sql(sql)
        .authResult(authenticationResult)
        .expectedQueries(expectedQueries)
        .expectedResults(expectedResults)
        .run();
  }

  public void testQuery(
      final PlannerConfig plannerConfig,
      final Map<String, Object> queryContext,
      final List<SqlParameter> parameters,
      final String sql,
      final AuthenticationResult authenticationResult,
      final List<Query<?>> expectedQueries,
      final List<Object[]> expectedResults
  )
  {
    testBuilder()
        .plannerConfig(plannerConfig)
        .queryContext(queryContext)
        .parameters(parameters)
        .sql(sql)
        .authResult(authenticationResult)
        .expectedQueries(expectedQueries)
        .expectedResults(expectedResults)
        .run();
  }

  public void testQuery(
      final PlannerConfig plannerConfig,
      final Map<String, Object> queryContext,
      final List<SqlParameter> parameters,
      final String sql,
      final AuthenticationResult authenticationResult,
      final List<Query<?>> expectedQueries,
      final ResultsVerifier expectedResultsVerifier
  )
  {
    testBuilder()
        .plannerConfig(plannerConfig)
        .queryContext(queryContext)
        .parameters(parameters)
        .sql(sql)
        .authResult(authenticationResult)
        .expectedQueries(expectedQueries)
        .expectedResults(expectedResultsVerifier)
        .run();
  }

  protected QueryTestBuilder testBuilder()
  {
    return new QueryTestBuilder(new CalciteTestConfig())
        .cannotVectorize(
            cannotVectorize || (!ExpressionProcessing.allowVectorizeFallback() && cannotVectorizeUnlessFallback)
        )
        .skipVectorize(skipVectorize);
  }

  public CalciteTestConfig createCalciteTestConfig()
  {
    return new CalciteTestConfig();
  }

  public class CalciteTestConfig implements QueryTestBuilder.QueryTestConfig
  {
    private boolean isRunningMSQ = false;
    private Map<String, Object> baseQueryContext;

    public CalciteTestConfig()
    {
      this(BaseCalciteQueryTest.QUERY_CONTEXT_DEFAULT);
    }

    public CalciteTestConfig(boolean isRunningMSQ)
    {
      this();
      this.isRunningMSQ = isRunningMSQ;
    }

    public CalciteTestConfig(Map<String, Object> baseQueryContext, boolean isRunningMSQ)
    {
      this(baseQueryContext);
      this.isRunningMSQ = isRunningMSQ;
    }

    public CalciteTestConfig(Map<String, Object> baseQueryContext)
    {
      Preconditions.checkNotNull(baseQueryContext, "baseQueryContext is null");
      this.baseQueryContext = baseQueryContext;
      Preconditions.checkState(
          baseQueryContext.containsKey(PlannerContext.CTX_SQL_CURRENT_TIMESTAMP),
          "context must contain CTX_SQL_CURRENT_TIMESTAMP to ensure consistent behaviour!"
      );
    }

    @Override
    public PlannerFixture plannerFixture(PlannerConfig plannerConfig, AuthConfig authConfig)
    {
      return queryFramework().plannerFixture(plannerConfig, authConfig);
    }

    @Override
    public ObjectMapper jsonMapper()
    {
      return queryFramework().queryJsonMapper();
    }

    @Override
    public ResultsVerifier defaultResultsVerifier(
        List<Object[]> expectedResults,
        ResultMatchMode expectedResultMatchMode,
        RowSignature expectedResultSignature
    )
    {
      return BaseCalciteQueryTest.this.defaultResultsVerifier(
          expectedResults,
          expectedResultMatchMode,
          expectedResultSignature
      );
    }

    @Override
    public boolean isRunningMSQ()
    {
      return isRunningMSQ;
    }

    @Override
    public Map<String, Object> baseQueryContext()
    {
      return baseQueryContext;
    }

    @Override
    public SqlTestFramework queryFramework()
    {
      return BaseCalciteQueryTest.this.queryFramework();
    }
  }

  public enum ResultMatchMode
  {
    EQUALS {
      @Override
      void validate(int row, int column, ValueType type, Object expectedCell, Object resultCell)
      {
        assertEquals(
            mismatchMessage(row, column),
            expectedCell,
            resultCell);
      }
    },
    RELAX_NULLS {
      @Override
      void validate(int row, int column, ValueType type, Object expectedCell, Object resultCell)
      {
        if (expectedCell == null) {
          if (resultCell == null) {
            return;
          }
        }
        EQUALS.validate(row, column, type, expectedCell, resultCell);
      }
    },
    EQUALS_EPS {
      @Override
      void validate(int row, int column, ValueType type, Object expectedCell, Object resultCell)
      {
        if (expectedCell instanceof Float) {
          assertEquals(
              mismatchMessage(row, column),
              (Float) expectedCell,
              (Float) resultCell,
              ASSERTION_EPSILON
          );
        } else if (expectedCell instanceof Double) {
          assertEquals(
              mismatchMessage(row, column),
              (Double) expectedCell,
              (Double) resultCell,
              ASSERTION_EPSILON
          );
        } else if (expectedCell instanceof Object[] || expectedCell instanceof List) {
          final Object[] expectedCellCasted = homogenizeArray(expectedCell);
          final Object[] resultCellCasted = homogenizeArray(resultCell);
          if (expectedCellCasted.length != resultCellCasted.length) {
            throw new RE(
                "Mismatched array lengths: expected[%s] with length[%d], actual[%s] with length[%d]",
                Arrays.toString(expectedCellCasted),
                expectedCellCasted.length,
                Arrays.toString(resultCellCasted),
                resultCellCasted.length
            );
          }
          for (int i = 0; i < expectedCellCasted.length; ++i) {
            validate(row, column, type, expectedCellCasted[i], resultCellCasted[i]);
          }
        } else {
          EQUALS.validate(row, column, type, expectedCell, resultCell);
        }
      }
    },

    RELAX_NULLS_EPS {
      @Override
      void validate(int row, int column, ValueType type, Object expectedCell, Object resultCell)
      {
        if (expectedCell == null) {
          if (resultCell == null) {
            return;
          }
        }
        EQUALS_EPS.validate(row, column, type, expectedCell, resultCell);
      }
    },

    /**
     * Comparision which accepts 1000 units of least precision.
     */
    EQUALS_RELATIVE_1000_ULPS {
      private static final int ASSERTION_ERROR_ULPS = 1000;

      @Override
      void validate(int row, int column, ValueType type, Object expectedCell, Object resultCell)
      {
        if (expectedCell instanceof Float) {
          float eps = ASSERTION_ERROR_ULPS * Math.ulp((Float) expectedCell);
          assertEquals(
              mismatchMessage(row, column),
              (Float) expectedCell,
              (Float) resultCell,
              eps
          );
        } else if (expectedCell instanceof Double) {
          double eps = ASSERTION_ERROR_ULPS * Math.ulp((Double) expectedCell);
          assertEquals(
              mismatchMessage(row, column),
              (Double) expectedCell,
              (Double) resultCell,
              eps
          );
        } else if (expectedCell instanceof Object[] || expectedCell instanceof List) {
          final Object[] expectedCellCasted = homogenizeArray(expectedCell);
          final Object[] resultCellCasted = homogenizeArray(resultCell);

          if (expectedCellCasted.length != resultCellCasted.length) {
            throw new RE(
                "Mismatched array lengths: expected[%s] with length[%d], actual[%s] with length[%d]",
                Arrays.toString(expectedCellCasted),
                expectedCellCasted.length,
                Arrays.toString(resultCellCasted),
                resultCellCasted.length
            );
          }
          for (int i = 0; i < expectedCellCasted.length; ++i) {
            validate(row, column, type, expectedCellCasted[i], resultCellCasted[i]);
          }
        } else {
          EQUALS.validate(row, column, type, expectedCell, resultCell);
        }
      }
    },

    /**
     * Relax nulls which accepts 1000 units of least precision.
     */
    RELAX_NULLS_RELATIVE_1000_ULPS {
      @Override
      void validate(int row, int column, ValueType type, Object expectedCell, Object resultCell)
      {
        if (expectedCell == null) {
          if (resultCell == null) {
            return;
          }
        }
        EQUALS_RELATIVE_1000_ULPS.validate(row, column, type, expectedCell, resultCell);
      }
    };

    abstract void validate(int row, int column, ValueType type, Object expectedCell, Object resultCell);

    private static String mismatchMessage(int row, int column)
    {
      return StringUtils.format("column content mismatch at %d,%d", row, column);
    }

    private static Object[] homogenizeArray(Object array)
    {
      if (array instanceof Object[]) {
        return (Object[]) array;
      } else if (array instanceof List) {
        return ExprEval.coerceListToArray((List) array, true).rhs;
      }
      throw new ISE("Found array[%s] of type[%s] which is not handled", array.toString(), array.getClass().getName());
    }
  }

  public void assertResultsValid(final ResultMatchMode matchMode, final List<Object[]> expected, final QueryResults queryResults)
  {
    final List<Object[]> results = queryResults.results;
    Assert.assertEquals("Result count mismatch", expected.size(), results.size());

    final List<ValueType> types = new ArrayList<>();

    for (int i = 0; i < queryResults.signature.getColumnNames().size(); i++) {
      Optional<ColumnType> columnType = queryResults.signature.getColumnType(i);
      if (columnType.isPresent()) {
        types.add(columnType.get().getType());
      } else {
        types.add(null);
      }
    }

    final int numRows = results.size();
    for (int row = 0; row < numRows; row++) {
      final Object[] expectedRow = expected.get(row);
      final Object[] resultRow = results.get(row);
      assertEquals("column count mismatch; at row#" + row, expectedRow.length, resultRow.length);

      for (int i = 0; i < resultRow.length; i++) {
        final Object resultCell = resultRow[i];
        final Object expectedCell = expectedRow[i];

        matchMode.validate(
            row,
            i,
            types.get(i),
            expectedCell,
            resultCell
        );
      }
    }
  }

  public static void assertResultsEquals(String sql, List<Object[]> expectedResults, List<Object[]> results)
  {
    int minSize = Math.min(results.size(), expectedResults.size());
    for (int i = 0; i < minSize; i++) {
      Assert.assertArrayEquals(
          StringUtils.format("result #%d: %s", i + 1, sql),
          expectedResults.get(i),
          results.get(i)
      );
    }
    Assert.assertEquals(expectedResults.size(), results.size());
  }

  public <T extends Throwable> void testQueryThrows(
      final String sql,
      final DruidExceptionMatcher exceptionMatcher
  )
  {
    testQueryThrows(sql, null, DruidException.class, exceptionMatcher);
  }

  public <T extends Exception> void testQueryThrows(
      final String sql,
      final Class<T> exceptionType,
      final String exceptionMessage
  )
  {
    testQueryThrows(
        sql,
        null,
        exceptionType,
        ThrowableMessageMatcher.hasMessage(CoreMatchers.equalTo(exceptionMessage))
    );
  }

  public <T extends Exception> void testQueryThrows(
      final String sql,
      final Class<T> exceptionType,
      final Matcher<Throwable> exceptionMatcher
  )
  {
    testQueryThrows(sql, null, exceptionType, exceptionMatcher);
  }

  public <T extends Exception> void testQueryThrows(
      final String sql,
      final Map<String, Object> queryContext,
      final Class<T> exceptionType,
      final Matcher<Throwable> exceptionMatcher
  )
  {
    T e = assertThrows(
        exceptionType,
        () -> testBuilder()
            .sql(sql)
            .queryContext(queryContext)
            .build()
            .run()
    );
    assertThat(e, exceptionMatcher);
  }

  public void analyzeResources(
      String sql,
      List<ResourceAction> expectedActions
  )
  {
    testBuilder()
        .sql(sql)
        .expectedResources(expectedActions)
        .run();
  }

  public void analyzeResources(
      PlannerConfig plannerConfig,
      String sql,
      AuthenticationResult authenticationResult,
      List<ResourceAction> expectedActions
  )
  {
    testBuilder()
        .plannerConfig(plannerConfig)
        .sql(sql)
        .authResult(authenticationResult)
        .expectedResources(expectedActions)
        .run();
  }

  public void analyzeResources(
      PlannerConfig plannerConfig,
      AuthConfig authConfig,
      String sql,
      Map<String, Object> contexts,
      AuthenticationResult authenticationResult,
      List<ResourceAction> expectedActions
  )
  {
    testBuilder()
        .plannerConfig(plannerConfig)
        .authConfig(authConfig)
        .sql(sql)
        .queryContext(contexts)
        .authResult(authenticationResult)
        .expectedResources(expectedActions)
        .run();
  }

  public SqlStatementFactory getSqlStatementFactory(
      PlannerConfig plannerConfig
  )
  {
    return getSqlStatementFactory(
        plannerConfig,
        new AuthConfig()
    );
  }

  /**
   * Build the statement factory, which also builds all the infrastructure
   * behind the factory by calling methods on this test class. As a result, each
   * factory is specific to one test and one planner config. This method can be
   * overridden to control the objects passed to the factory.
   */
  SqlStatementFactory getSqlStatementFactory(
      PlannerConfig plannerConfig,
      AuthConfig authConfig
  )
  {
    return queryFramework().plannerFixture(plannerConfig, authConfig).statementFactory();
  }

  protected void cannotVectorize()
  {
    cannotVectorize = true;
  }

  protected void cannotVectorizeUnlessFallback()
  {
    cannotVectorizeUnlessFallback = true;
  }

  protected void skipVectorize()
  {
    skipVectorize = true;
  }

  protected void msqIncompatible()
  {
    assumeFalse(testBuilder().config.isRunningMSQ(), "test case is not MSQ compatible");
  }

  protected boolean isRunningMSQ()
  {
    return testBuilder().config.isRunningMSQ();
  }

  protected static boolean isRewriteJoinToFilter(final Map<String, Object> queryContext)
  {
    return (boolean) queryContext.getOrDefault(
        QueryContexts.REWRITE_JOIN_TO_FILTER_ENABLE_KEY,
        QueryContexts.DEFAULT_ENABLE_REWRITE_JOIN_TO_FILTER
    );
  }

  /**
   * Override not just the outer query context, but also the contexts of all subqueries.
   *
   * @return
   */
  public static <T> Query<?> recursivelyClearContext(final Query<T> query, ObjectMapper queryJsonMapper)
  {
    try {
      Query<T> newQuery;
      if (query instanceof UnionQuery) {
        UnionQuery unionQuery = (UnionQuery) query;
        newQuery = (Query<T>) unionQuery
            .withDataSources(recursivelyClearDatasource(unionQuery.getDataSources(), queryJsonMapper));
      } else {
        newQuery = query.withDataSource(recursivelyClearContext(query.getDataSource(), queryJsonMapper));
      }
      final JsonNode newQueryNode = queryJsonMapper.valueToTree(newQuery);
      ((ObjectNode) newQueryNode).remove("context");
      JsonNode fc = ((ObjectNode) newQueryNode).get("searchFilterContext");
      if (fc != null) {
        ((ObjectNode) fc).remove("nowMs");
      }

      return queryJsonMapper.treeToValue(newQueryNode, Query.class);
    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static List<DataSource> recursivelyClearDatasource(final List<DataSource> dataSources,
      ObjectMapper queryJsonMapper)
  {
    List<DataSource> ret = new ArrayList<>();
    for (DataSource dataSource : dataSources) {
      ret.add(recursivelyClearContext(dataSource, queryJsonMapper));
    }
    return ret;
  }

  /**
   * Override the contexts of all subqueries of a particular datasource.
   */
  private static DataSource recursivelyClearContext(final DataSource dataSource, ObjectMapper queryJsonMapper)
  {
    if (dataSource instanceof QueryDataSource) {
      final Query<?> subquery = ((QueryDataSource) dataSource).getQuery();
      Query<?> newSubQuery = recursivelyClearContext(subquery, queryJsonMapper);
      return new QueryDataSource(newSubQuery);
    } else {
      return dataSource.withChildren(
          dataSource.getChildren()
                    .stream()
                    .map(ds -> recursivelyClearContext(ds, queryJsonMapper))
                    .collect(Collectors.toList())
      );
    }
  }

  /**
   * This is a provider of query contexts that should be used by join tests.
   * It tests various configs that can be passed to join queries. All the configs provided by this provider should
   * have the join query engine return the same results.
   */
  public static Object[] provideQueryContexts()
  {
    return new Object[] {
        // default behavior
        Named.of("default", QUERY_CONTEXT_DEFAULT),
        // all rewrites enabled
        Named.of("all_enabled", new ImmutableMap.Builder<String, Object>()
            .putAll(QUERY_CONTEXT_DEFAULT)
            .put(QueryContexts.JOIN_FILTER_REWRITE_VALUE_COLUMN_FILTERS_ENABLE_KEY, true)
            .put(QueryContexts.JOIN_FILTER_REWRITE_ENABLE_KEY, true)
            .put(QueryContexts.REWRITE_JOIN_TO_FILTER_ENABLE_KEY, true)
            .build()),
        // filter-on-value-column rewrites disabled, everything else enabled
        Named.of("filter-on-value-column_disabled", new ImmutableMap.Builder<String, Object>()
            .putAll(QUERY_CONTEXT_DEFAULT)
            .put(QueryContexts.JOIN_FILTER_REWRITE_VALUE_COLUMN_FILTERS_ENABLE_KEY, false)
            .put(QueryContexts.JOIN_FILTER_REWRITE_ENABLE_KEY, true)
            .put(QueryContexts.REWRITE_JOIN_TO_FILTER_ENABLE_KEY, true)
            .build()),
        // filter rewrites fully disabled, join-to-filter enabled
        Named.of("join-to-filter", new ImmutableMap.Builder<String, Object>()
            .putAll(QUERY_CONTEXT_DEFAULT)
            .put(QueryContexts.JOIN_FILTER_REWRITE_VALUE_COLUMN_FILTERS_ENABLE_KEY, false)
            .put(QueryContexts.JOIN_FILTER_REWRITE_ENABLE_KEY, false)
            .put(QueryContexts.REWRITE_JOIN_TO_FILTER_ENABLE_KEY, true)
            .build()),
        // filter rewrites disabled, but value column filters still set to true
        // (it should be ignored and this should
        // behave the same as the previous context)
        Named.of("filter-rewrites-disabled", new ImmutableMap.Builder<String, Object>()
            .putAll(QUERY_CONTEXT_DEFAULT)
            .put(QueryContexts.JOIN_FILTER_REWRITE_VALUE_COLUMN_FILTERS_ENABLE_KEY, true)
            .put(QueryContexts.JOIN_FILTER_REWRITE_ENABLE_KEY, false)
            .put(QueryContexts.REWRITE_JOIN_TO_FILTER_ENABLE_KEY, true)
            .build()),
        // filter rewrites fully enabled, join-to-filter disabled
        Named.of("filter-rewrites", new ImmutableMap.Builder<String, Object>()
            .putAll(QUERY_CONTEXT_DEFAULT)
            .put(QueryContexts.JOIN_FILTER_REWRITE_VALUE_COLUMN_FILTERS_ENABLE_KEY, true)
            .put(QueryContexts.JOIN_FILTER_REWRITE_ENABLE_KEY, true)
            .put(QueryContexts.REWRITE_JOIN_TO_FILTER_ENABLE_KEY, false)
            .build()),
        // all rewrites disabled
        Named.of("all_disabled", new ImmutableMap.Builder<String, Object>()
            .putAll(QUERY_CONTEXT_DEFAULT)
            .put(QueryContexts.JOIN_FILTER_REWRITE_VALUE_COLUMN_FILTERS_ENABLE_KEY, false)
            .put(QueryContexts.JOIN_FILTER_REWRITE_ENABLE_KEY, false)
            .put(QueryContexts.REWRITE_JOIN_TO_FILTER_ENABLE_KEY, false)
            .build()),
    };
  }

  protected Map<String, Object> withLeftDirectAccessEnabled(Map<String, Object> context)
  {
    // since context is usually immutable in tests, make a copy
    HashMap<String, Object> newContext = new HashMap<>(context);
    newContext.put(QueryContexts.SQL_JOIN_LEFT_SCAN_DIRECT, true);
    return newContext;
  }

  protected Map<String, Object> withTimestampResultContext(
      Map<String, Object> input,
      String timestampResultField,
      int timestampResultFieldIndex,
      Granularity granularity
  )
  {
    Map<String, Object> output = new HashMap<>(input);
    output.put(GroupByQuery.CTX_TIMESTAMP_RESULT_FIELD, timestampResultField);

    try {
      output.put(
          GroupByQuery.CTX_TIMESTAMP_RESULT_FIELD_GRANULARITY,
          queryFramework().queryJsonMapper().writeValueAsString(granularity)
      );
    }
    catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }

    output.put(GroupByQuery.CTX_TIMESTAMP_RESULT_FIELD_INDEX, timestampResultFieldIndex);
    return output;
  }

  @FunctionalInterface
  public interface ResultsVerifier
  {
    default void verifyRowSignature(RowSignature rowSignature)
    {
      // do nothing
    }

    void verify(String sql, QueryResults queryResults);
  }

  private ResultsVerifier defaultResultsVerifier(
      final List<Object[]> expectedResults,
      ResultMatchMode expectedResultMatchMode,
      final RowSignature expectedSignature
  )
  {
    return new DefaultResultsVerifier(expectedResults, expectedResultMatchMode, expectedSignature);
  }

  public class DefaultResultsVerifier implements ResultsVerifier
  {
    protected final List<Object[]> expectedResults;
    @Nullable
    protected final RowSignature expectedResultRowSignature;
    protected final ResultMatchMode expectedResultMatchMode;

    public DefaultResultsVerifier(List<Object[]> expectedResults, ResultMatchMode expectedResultMatchMode, RowSignature expectedSignature)
    {
      this.expectedResults = expectedResults;
      this.expectedResultMatchMode = expectedResultMatchMode;
      this.expectedResultRowSignature = expectedSignature;
    }

    public DefaultResultsVerifier(List<Object[]> expectedResults, RowSignature expectedSignature)
    {
      this(expectedResults, ResultMatchMode.EQUALS, expectedSignature);
    }

    @Override
    public void verifyRowSignature(RowSignature rowSignature)
    {
      if (expectedResultRowSignature != null) {
        Assert.assertEquals(expectedResultRowSignature, rowSignature);
      }
    }

    @Override
    public void verify(String sql, QueryResults queryResults)
    {
      try {
        assertResultsValid(expectedResultMatchMode, expectedResults, queryResults);
      }
      catch (AssertionError e) {
        log.info("sql: %s", sql);
        log.info(resultsToString("Expected", expectedResults));
        log.info(resultsToString("Actual", queryResults.results));
        throw e;
      }
    }

  }

  /**
   * Dump the expected results in the form of the elements of a Java array which
   * can be used to validate the results. This is a convenient way to create the
   * expected results: let the test fail with empty results. The actual results
   * are printed to the console. Copy them into the test.
   */
  public static String resultsToString(String name, List<Object[]> results)
  {
    return new ResultsPrinter(name, results).getResult();
  }

  static class ResultsPrinter
  {
    private StringBuilder sb;

    private ResultsPrinter(String name, List<Object[]> results)
    {
      sb = new StringBuilder();
      sb.append("-- ");
      sb.append(name);
      sb.append(" results --\n");

      for (int rowIndex = 0; rowIndex < results.size(); rowIndex++) {
        printArray(results.get(rowIndex));
        if (rowIndex < results.size() - 1) {
          outprint(",");
        }
        sb.append('\n');
      }
      sb.append("----");
    }

    private String getResult()
    {
      return sb.toString();
    }

    private void printArray(final Object[] array)
    {
      printArrayImpl(array, "new Object[]{", "}");
    }

    private void printList(final List<?> list)
    {
      printArrayImpl(list.toArray(new Object[0]), "ImmutableList.of(", ")");
    }

    private void printArrayImpl(final Object[] array, final String pre, final String post)
    {
      sb.append(pre);
      for (int colIndex = 0; colIndex < array.length; colIndex++) {
        Object col = array[colIndex];
        if (colIndex > 0) {
          sb.append(", ");
        }
        if (col == null) {
          sb.append("null");
        } else if (col instanceof String) {
          outprint("\"");
          outprint(StringEscapeUtils.escapeJava((String) col));
          outprint("\"");
        } else if (col instanceof Long) {
          outprint(col);
          outprint("L");
        } else if (col instanceof Double) {
          outprint(col);
          outprint("D");
        } else if (col instanceof Float) {
          outprint(col);
          outprint("F");
        } else if (col instanceof Object[]) {
          printArray((Object[]) col);
        } else if (col instanceof List) {
          printList((List<?>) col);
        } else {
          outprint(col);
        }
      }
      outprint(post);
    }

    private void outprint(Object post)
    {
      sb.append(post);
    }
  }

  /**
   * Helper method that copies a resource to a temporary file, then returns it.
   */
  public File getResourceAsTemporaryFile(final String resource)
  {
    final File file = newTempFile("resourceAsTempFile");
    final InputStream stream = getClass().getResourceAsStream(resource);

    if (stream == null) {
      throw new RE(StringUtils.format("No such resource [%s]", resource));
    }

    try {
      ByteStreams.copy(stream, Files.newOutputStream(file.toPath()));
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
    return file;
  }

  /**
   * Adds shadowing in non-decoupled mode planning.
   *
   * Due to some circumstances - DruidUnnestRel have exposed all columns during planning;
   * which made the VC registry to see some columns which are not selected ; and as a result
   * it renamed some columns with underscores.
   */
  public String ds(String colName)
  {
    if (testBuilder().isDecoupledMode()) {
      return colName;
    } else {
      return "_" + colName;
    }
  }
}
