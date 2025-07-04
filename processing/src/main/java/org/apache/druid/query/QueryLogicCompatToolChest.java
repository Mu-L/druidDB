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

package org.apache.druid.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import org.apache.druid.frame.allocation.MemoryAllocatorFactory;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.query.aggregation.MetricManipulationFn;
import org.apache.druid.segment.column.RowSignature;

import java.util.Optional;

public class QueryLogicCompatToolChest extends QueryToolChest<Object, Query<Object>>
{
  private final RowSignature resultRowSignature;
  private final GenericQueryMetricsFactory queryMetricsFactory;

  public QueryLogicCompatToolChest(RowSignature resultRowSignature, GenericQueryMetricsFactory queryMetricsFactory)
  {
    this.resultRowSignature = resultRowSignature;
    this.queryMetricsFactory = queryMetricsFactory;
  }

  @Override
  public RowSignature resultArraySignature(Query<Object> query)
  {
    return resultRowSignature;
  }

  @Override
  public QueryMetrics<? super Query<Object>> makeMetrics(Query<Object> query)
  {
    return queryMetricsFactory.makeMetrics(query);
  }

  @Override
  public Function<Object, Object> makePreComputeManipulatorFn(Query<Object> query, MetricManipulationFn fn)
  {
    return Functions.identity();
  }

  @Override
  public TypeReference<Object> getResultTypeReference()
  {
    return null;
  }

  @Override
  public Sequence<Object[]> resultsAsArrays(Query<Object> query, Sequence<Object> resultSequence)
  {
    Sequence<?> res = resultSequence;
    return (Sequence<Object[]>) res;
  }

  @Override
  public Optional<Sequence<FrameSignaturePair>> resultsAsFrames(Query<Object> query, Sequence<Object> resultSequence,
      MemoryAllocatorFactory memoryAllocatorFactory, boolean useNestedForUnknownTypes)
  {
    Sequence<?> res = resultSequence;
    return Optional.of((Sequence<FrameSignaturePair>) res);
  }
}
