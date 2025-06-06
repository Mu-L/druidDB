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

package org.apache.druid.sql.calcite.planner;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.druid.error.DruidException;
import org.apache.druid.java.util.common.UOE;
import org.apache.druid.query.QueryContexts;
import org.joda.time.DateTimeZone;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class PlannerConfig
{
  public static final String CTX_KEY_USE_APPROXIMATE_COUNT_DISTINCT = "useApproximateCountDistinct";
  public static final String CTX_KEY_USE_GROUPING_SET_FOR_EXACT_DISTINCT = "useGroupingSetForExactDistinct";
  public static final String CTX_KEY_USE_APPROXIMATE_TOPN = "useApproximateTopN";
  public static final String CTX_KEY_USE_LEXICOGRAPHIC_TOPN = "useLexicographicTopN";
  public static final String CTX_COMPUTE_INNER_JOIN_COST_AS_FILTER = "computeInnerJoinCostAsFilter";
  public static final String CTX_KEY_USE_NATIVE_QUERY_EXPLAIN = "useNativeQueryExplain";
  public static final String CTX_KEY_FORCE_EXPRESSION_VIRTUAL_COLUMNS = "forceExpressionVirtualColumns";
  public static final String CTX_MAX_NUMERIC_IN_FILTERS = "maxNumericInFilters";
  public static final String CTX_REQUIRE_TIME_CONDITION = "requireTimeCondition";
  public static final int NUM_FILTER_NOT_USED = -1;
  @JsonProperty
  private int maxTopNLimit = 100_000;

  @JsonProperty
  private boolean useApproximateCountDistinct = true;

  @JsonProperty
  private boolean useApproximateTopN = true;

  @JsonProperty
  private boolean useLexicographicTopN = false;

  @JsonProperty
  private boolean requireTimeCondition = false;

  @JsonProperty
  private DateTimeZone sqlTimeZone = DateTimeZone.UTC;

  @JsonProperty
  private boolean useGroupingSetForExactDistinct = false;

  @JsonProperty
  private boolean computeInnerJoinCostAsFilter = true;

  @JsonProperty
  private boolean authorizeSystemTablesDirectly = false;

  @JsonProperty
  private boolean useNativeQueryExplain = true;

  @JsonProperty
  private boolean forceExpressionVirtualColumns = false;

  @JsonProperty
  private int maxNumericInFilters = NUM_FILTER_NOT_USED;

  @JsonProperty
  private String nativeQuerySqlPlanningMode = QueryContexts.NATIVE_QUERY_SQL_PLANNING_MODE_COUPLED; // can be COUPLED or DECOUPLED

  public int getMaxNumericInFilters()
  {
    return maxNumericInFilters;
  }

  public int getMaxTopNLimit()
  {
    return maxTopNLimit;
  }

  public boolean isUseApproximateCountDistinct()
  {
    return useApproximateCountDistinct;
  }

  public boolean isUseGroupingSetForExactDistinct()
  {
    return useGroupingSetForExactDistinct;
  }

  public boolean isUseApproximateTopN()
  {
    return useApproximateTopN;
  }

  public boolean isUseLexicographicTopN()
  {
    return useLexicographicTopN;
  }

  public boolean isRequireTimeCondition()
  {
    return requireTimeCondition;
  }

  public DateTimeZone getSqlTimeZone()
  {
    return sqlTimeZone;
  }

  public boolean isComputeInnerJoinCostAsFilter()
  {
    return computeInnerJoinCostAsFilter;
  }

  public boolean isAuthorizeSystemTablesDirectly()
  {
    return authorizeSystemTablesDirectly;
  }

  public boolean isUseNativeQueryExplain()
  {
    return useNativeQueryExplain;
  }

  /**
   * @return true if special virtual columns should not be optimized and should
   * always be of type "expressions", false otherwise.
   */
  public boolean isForceExpressionVirtualColumns()
  {
    return forceExpressionVirtualColumns;
  }

  public String getNativeQuerySqlPlanningMode()
  {
    return nativeQuerySqlPlanningMode;
  }

  public PlannerConfig withOverrides(final Map<String, Object> queryContext)
  {
    if (queryContext.isEmpty()) {
      return this;
    }
    return toBuilder()
        .withOverrides(queryContext)
        .build();
  }

  @Override
  public boolean equals(Object o)
  {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    PlannerConfig that = (PlannerConfig) o;
    return maxTopNLimit == that.maxTopNLimit
           && useApproximateCountDistinct == that.useApproximateCountDistinct
           && useApproximateTopN == that.useApproximateTopN
           && useLexicographicTopN == that.useLexicographicTopN
           && requireTimeCondition == that.requireTimeCondition
           && useGroupingSetForExactDistinct == that.useGroupingSetForExactDistinct
           && computeInnerJoinCostAsFilter == that.computeInnerJoinCostAsFilter
           && authorizeSystemTablesDirectly == that.authorizeSystemTablesDirectly
           && useNativeQueryExplain == that.useNativeQueryExplain
           && forceExpressionVirtualColumns == that.forceExpressionVirtualColumns
           && maxNumericInFilters == that.maxNumericInFilters
           && Objects.equals(sqlTimeZone, that.sqlTimeZone)
           && Objects.equals(nativeQuerySqlPlanningMode, that.nativeQuerySqlPlanningMode);
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(
        maxTopNLimit,
        useApproximateCountDistinct,
        useApproximateTopN,
        useLexicographicTopN,
        requireTimeCondition,
        sqlTimeZone,
        useGroupingSetForExactDistinct,
        computeInnerJoinCostAsFilter,
        authorizeSystemTablesDirectly,
        useNativeQueryExplain,
        forceExpressionVirtualColumns,
        maxNumericInFilters,
        nativeQuerySqlPlanningMode
    );
  }

  @Override
  public String toString()
  {
    return "PlannerConfig{" +
           "maxTopNLimit=" + maxTopNLimit +
           ", useApproximateCountDistinct=" + useApproximateCountDistinct +
           ", useApproximateTopN=" + useApproximateTopN +
           ", useLexicographicTopN=" + useLexicographicTopN +
           ", requireTimeCondition=" + requireTimeCondition +
           ", sqlTimeZone=" + sqlTimeZone +
           ", useNativeQueryExplain=" + useNativeQueryExplain +
           ", nativeQuerySqlPlanningMode=" + nativeQuerySqlPlanningMode +
           '}';
  }

  public static Builder builder()
  {
    return new PlannerConfig().toBuilder();
  }

  public Builder toBuilder()
  {
    return new Builder(this);
  }

  /**
   * Builder for {@link PlannerConfig}, primarily for use in tests to
   * allow setting options programmatically rather than from the command
   * line or a properties file. Starts with values from an existing
   * (typically default) config.
   */
  public static class Builder
  {
    private int maxTopNLimit;
    private boolean useApproximateCountDistinct;
    private boolean useApproximateTopN;
    private boolean useLexicographicTopN;
    private boolean requireTimeCondition;
    private DateTimeZone sqlTimeZone;
    private boolean useGroupingSetForExactDistinct;
    private boolean computeInnerJoinCostAsFilter;
    private boolean authorizeSystemTablesDirectly;
    private boolean useNativeQueryExplain;
    private boolean forceExpressionVirtualColumns;
    private int maxNumericInFilters;
    private String nativeQuerySqlPlanningMode;

    public Builder(PlannerConfig base)
    {
      // Note: use accessors, not fields, since some tests change the
      // config by defining a subclass.

      maxTopNLimit = base.getMaxTopNLimit();
      useApproximateCountDistinct = base.isUseApproximateCountDistinct();
      useApproximateTopN = base.isUseApproximateTopN();
      useLexicographicTopN = base.isUseLexicographicTopN();
      requireTimeCondition = base.isRequireTimeCondition();
      sqlTimeZone = base.getSqlTimeZone();
      useGroupingSetForExactDistinct = base.isUseGroupingSetForExactDistinct();
      computeInnerJoinCostAsFilter = base.computeInnerJoinCostAsFilter;
      authorizeSystemTablesDirectly = base.isAuthorizeSystemTablesDirectly();
      useNativeQueryExplain = base.isUseNativeQueryExplain();
      forceExpressionVirtualColumns = base.isForceExpressionVirtualColumns();
      maxNumericInFilters = base.getMaxNumericInFilters();
      nativeQuerySqlPlanningMode = base.getNativeQuerySqlPlanningMode();
    }

    public Builder requireTimeCondition(boolean option)
    {
      this.requireTimeCondition = option;
      return this;
    }

    public Builder maxTopNLimit(int value)
    {
      this.maxTopNLimit = value;
      return this;
    }

    public Builder maxNumericInFilters(int value)
    {
      this.maxNumericInFilters = value;
      return this;
    }

    public Builder useApproximateCountDistinct(boolean option)
    {
      this.useApproximateCountDistinct = option;
      return this;
    }

    public Builder useApproximateTopN(boolean option)
    {
      this.useApproximateTopN = option;
      return this;
    }

    public Builder useLexicographicTopN(boolean option)
    {
      this.useLexicographicTopN = option;
      return this;
    }

    public Builder useGroupingSetForExactDistinct(boolean option)
    {
      this.useGroupingSetForExactDistinct = option;
      return this;
    }

    public Builder computeInnerJoinCostAsFilter(boolean option)
    {
      this.computeInnerJoinCostAsFilter = option;
      return this;
    }

    public Builder sqlTimeZone(DateTimeZone value)
    {
      this.sqlTimeZone = value;
      return this;
    }

    public Builder authorizeSystemTablesDirectly(boolean option)
    {
      this.authorizeSystemTablesDirectly = option;
      return this;
    }

    public Builder useNativeQueryExplain(boolean option)
    {
      this.useNativeQueryExplain = option;
      return this;
    }

    public Builder nativeQuerySqlPlanningMode(String mode)
    {
      this.nativeQuerySqlPlanningMode = mode;
      return this;
    }

    public Builder withOverrides(final Map<String, Object> queryContext)
    {
      useApproximateCountDistinct = QueryContexts.parseBoolean(
          queryContext,
          CTX_KEY_USE_APPROXIMATE_COUNT_DISTINCT,
          useApproximateCountDistinct
      );
      useGroupingSetForExactDistinct = QueryContexts.parseBoolean(
          queryContext,
          CTX_KEY_USE_GROUPING_SET_FOR_EXACT_DISTINCT,
          useGroupingSetForExactDistinct
      );
      useApproximateTopN = QueryContexts.parseBoolean(
          queryContext,
          CTX_KEY_USE_APPROXIMATE_TOPN,
          useApproximateTopN
      );
      useLexicographicTopN = QueryContexts.parseBoolean(
          queryContext,
          CTX_KEY_USE_LEXICOGRAPHIC_TOPN,
          useLexicographicTopN
      );
      computeInnerJoinCostAsFilter = QueryContexts.parseBoolean(
          queryContext,
          CTX_COMPUTE_INNER_JOIN_COST_AS_FILTER,
          computeInnerJoinCostAsFilter
      );
      useNativeQueryExplain = QueryContexts.parseBoolean(
          queryContext,
          CTX_KEY_USE_NATIVE_QUERY_EXPLAIN,
          useNativeQueryExplain
      );
      forceExpressionVirtualColumns = QueryContexts.parseBoolean(
          queryContext,
          CTX_KEY_FORCE_EXPRESSION_VIRTUAL_COLUMNS,
          forceExpressionVirtualColumns
      );
      final int queryContextMaxNumericInFilters = QueryContexts.parseInt(
          queryContext,
          CTX_MAX_NUMERIC_IN_FILTERS,
          maxNumericInFilters
      );
      maxNumericInFilters = validateMaxNumericInFilters(
          queryContextMaxNumericInFilters,
          maxNumericInFilters);
      nativeQuerySqlPlanningMode = QueryContexts.parseString(
          queryContext,
          QueryContexts.CTX_NATIVE_QUERY_SQL_PLANNING_MODE,
          nativeQuerySqlPlanningMode
      );
      requireTimeCondition = QueryContexts.parseBoolean(
          queryContext,
          CTX_REQUIRE_TIME_CONDITION,
          requireTimeCondition
      );
      return this;
    }

    private static int validateMaxNumericInFilters(int queryContextMaxNumericInFilters, int systemConfigMaxNumericInFilters)
    {
      // if maxNumericInFIlters through context == 0 catch exception
      // else if query context exceeds system set value throw error
      if (queryContextMaxNumericInFilters == 0) {
        throw new UOE("[%s] must be greater than 0", CTX_MAX_NUMERIC_IN_FILTERS);
      } else if (queryContextMaxNumericInFilters > systemConfigMaxNumericInFilters
                 && systemConfigMaxNumericInFilters != NUM_FILTER_NOT_USED) {
        throw new UOE(
            "Expected parameter[%s] cannot exceed system set value of [%d]",
            CTX_MAX_NUMERIC_IN_FILTERS,
            systemConfigMaxNumericInFilters
        );
      }
      // if system set value is not present, thereby inferring default of -1
      if (systemConfigMaxNumericInFilters == NUM_FILTER_NOT_USED) {
        return systemConfigMaxNumericInFilters;
      }
      // all other cases return the valid query context value
      return queryContextMaxNumericInFilters;
    }

    public PlannerConfig build()
    {
      PlannerConfig config = new PlannerConfig();
      config.maxTopNLimit = maxTopNLimit;
      config.useApproximateCountDistinct = useApproximateCountDistinct;
      config.useApproximateTopN = useApproximateTopN;
      config.useLexicographicTopN = useLexicographicTopN;
      config.requireTimeCondition = requireTimeCondition;
      config.sqlTimeZone = sqlTimeZone;
      config.useGroupingSetForExactDistinct = useGroupingSetForExactDistinct;
      config.computeInnerJoinCostAsFilter = computeInnerJoinCostAsFilter;
      config.authorizeSystemTablesDirectly = authorizeSystemTablesDirectly;
      config.useNativeQueryExplain = useNativeQueryExplain;
      config.maxNumericInFilters = maxNumericInFilters;
      config.forceExpressionVirtualColumns = forceExpressionVirtualColumns;
      config.nativeQuerySqlPlanningMode = nativeQuerySqlPlanningMode;
      return config;
    }
  }

  /**
   * Translates {@link PlannerConfig} settings into its equivalent QueryContext map.
   *
   * @throws DruidException if the translation is not possible.
   */
  public Map<String, Object> getNonDefaultAsQueryContext()
  {
    Map<String, Object> overrides = new HashMap<>();
    PlannerConfig def = new PlannerConfig();
    if (def.useApproximateCountDistinct != useApproximateCountDistinct) {
      overrides.put(
          CTX_KEY_USE_APPROXIMATE_COUNT_DISTINCT,
          String.valueOf(useApproximateCountDistinct)
      );
    }
    if (def.useGroupingSetForExactDistinct != useGroupingSetForExactDistinct) {
      overrides.put(
          CTX_KEY_USE_GROUPING_SET_FOR_EXACT_DISTINCT,
          String.valueOf(useGroupingSetForExactDistinct)
      );
    }
    if (def.requireTimeCondition != requireTimeCondition) {
      overrides.put(
          CTX_REQUIRE_TIME_CONDITION,
          String.valueOf(requireTimeCondition)
      );
    }

    PlannerConfig newConfig = PlannerConfig.builder().withOverrides(overrides).build();
    if (!equals(newConfig)) {
      throw DruidException.defensive(
          "Not all PlannerConfig options are not persistable as QueryContext keys!\nold: %s\nnew: %s",
          this,
          newConfig
      );
    }
    return overrides;
  }
}
