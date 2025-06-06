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

package org.apache.druid.query.scan;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.druid.error.DruidException;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.UOE;
import org.apache.druid.java.util.common.guava.BaseSequence;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.java.util.common.guava.Sequences;
import org.apache.druid.query.Order;
import org.apache.druid.query.QueryMetrics;
import org.apache.druid.query.QueryTimeoutException;
import org.apache.druid.query.context.ResponseContext;
import org.apache.druid.segment.BaseObjectColumnValueSelector;
import org.apache.druid.segment.ColumnSelectorFactory;
import org.apache.druid.segment.Cursor;
import org.apache.druid.segment.CursorBuildSpec;
import org.apache.druid.segment.CursorFactory;
import org.apache.druid.segment.CursorHolder;
import org.apache.druid.segment.Cursors;
import org.apache.druid.segment.Segment;
import org.apache.druid.segment.VirtualColumn;
import org.apache.druid.segment.column.ColumnCapabilities;
import org.apache.druid.segment.column.ColumnHolder;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.RowSignature;
import org.apache.druid.segment.filter.Filters;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

public class ScanQueryEngine
{
  public Sequence<ScanResultValue> process(
      final ScanQuery query,
      final Segment segment,
      final ResponseContext responseContext,
      @Nullable final QueryMetrics<?> queryMetrics
  )
  {
    final Long numScannedRows = responseContext.getRowScanCount();
    if (numScannedRows != null && numScannedRows >= query.getScanRowsLimit() && query.getTimeOrder().equals(Order.NONE)) {
      return Sequences.empty();
    }
    if (segment.isTombstone()) {
      return Sequences.empty();
    }

    final boolean hasTimeout = query.context().hasTimeout();
    final Long timeoutAt = responseContext.getTimeoutTime();
    final CursorFactory cursorFactory = segment.as(CursorFactory.class);

    if (cursorFactory == null) {
      throw new ISE(
          "Null cursor factory found. Probably trying to issue a query against a segment being memory unmapped."
      );
    }

    final List<String> allColumns = new ArrayList<>();

    if (query.getColumns() != null && !query.getColumns().isEmpty()) {

      // Unless we're in legacy mode, allColumns equals query.getColumns() exactly. This is nice since it makes
      // the compactedList form easier to use.
      allColumns.addAll(query.getColumns());
    } else {
      final Set<String> availableColumns = Sets.newLinkedHashSet(
          Iterables.concat(
              cursorFactory.getRowSignature().getColumnNames(),
              Iterables.transform(
                  Arrays.asList(query.getVirtualColumns().getVirtualColumns()),
                  VirtualColumn::getOutputName
              )
          )
      );

      allColumns.addAll(availableColumns);
    }

    final List<Interval> intervals = query.getQuerySegmentSpec().getIntervals();
    Preconditions.checkArgument(intervals.size() == 1, "Can only handle a single interval, got[%s]", intervals);

    // If the row count is not set, set it to 0, else do nothing.
    responseContext.addRowScanCount(0);
    final long limit = calculateRemainingScanRowsLimit(query, responseContext);
    final CursorHolder cursorHolder = cursorFactory.makeCursorHolder(makeCursorBuildSpec(query, queryMetrics));
    if (Order.NONE != query.getTimeOrder()
        && Cursors.getTimeOrdering(cursorHolder.getOrdering()) != query.getTimeOrder()) {
      final String failureReason = StringUtils.format(
          "Cannot order by[%s] with direction[%s] on cursor with order[%s].",
          ColumnHolder.TIME_COLUMN_NAME,
          query.getTimeOrder(),
          cursorHolder.getOrdering()
      );

      cursorHolder.close();

      throw DruidException.forPersona(DruidException.Persona.USER)
                          .ofCategory(DruidException.Category.UNSUPPORTED)
                          .build("%s", failureReason);
    }
    return new BaseSequence<>(
        new BaseSequence.IteratorMaker<ScanResultValue, Iterator<ScanResultValue>>()
        {
          @Override
          public Iterator<ScanResultValue> make()
          {
            final Cursor cursor = cursorHolder.asCursor();
            if (cursor == null) {
              return Collections.emptyIterator();
            }
            final List<BaseObjectColumnValueSelector> columnSelectors = new ArrayList<>(allColumns.size());
            final RowSignature.Builder rowSignatureBuilder = RowSignature.builder();
            final ColumnSelectorFactory factory = cursor.getColumnSelectorFactory();

            for (String column : allColumns) {
              final BaseObjectColumnValueSelector selector = factory.makeColumnValueSelector(column);
              ColumnCapabilities columnCapabilities = factory.getColumnCapabilities(column);
              rowSignatureBuilder.add(column, ColumnType.fromCapabilities(columnCapabilities));
              columnSelectors.add(selector);
            }

            final int batchSize = query.getBatchSize();
            return new Iterator<>()
            {
              private long offset = 0;

              @Override
              public boolean hasNext()
              {
                return !cursor.isDone() && offset < limit;
              }

              @Override
              public ScanResultValue next()
              {
                if (!hasNext()) {
                  throw new NoSuchElementException();
                }
                if (hasTimeout && System.currentTimeMillis() >= timeoutAt) {
                  throw new QueryTimeoutException(StringUtils.nonStrictFormat("Query [%s] timed out", query.getId()));
                }
                final long lastOffset = offset;
                final Object events;
                final ScanQuery.ResultFormat resultFormat = query.getResultFormat();
                if (ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST.equals(resultFormat)) {
                  events = rowsToCompactedList();
                } else if (ScanQuery.ResultFormat.RESULT_FORMAT_LIST.equals(resultFormat)) {
                  events = rowsToList();
                } else {
                  throw new UOE("resultFormat[%s] is not supported", resultFormat.toString());
                }
                responseContext.addRowScanCount(offset - lastOffset);
                return new ScanResultValue(
                    segment.getId() == null ? null : segment.getId().toString(),
                    allColumns,
                    events,
                    rowSignatureBuilder.build()
                );
              }

              @Override
              public void remove()
              {
                throw new UnsupportedOperationException();
              }

              private List<List<Object>> rowsToCompactedList()
              {
                final List<List<Object>> events = new ArrayList<>(batchSize);
                final long iterLimit = Math.min(limit, offset + batchSize);
                for (; !cursor.isDone() && offset < iterLimit; cursor.advance(), offset++) {
                  final List<Object> theEvent = new ArrayList<>(allColumns.size());
                  for (int j = 0; j < allColumns.size(); j++) {
                    theEvent.add(getColumnValue(j));
                  }
                  events.add(theEvent);
                }
                return events;
              }

              private List<Map<String, Object>> rowsToList()
              {
                List<Map<String, Object>> events = Lists.newArrayListWithCapacity(batchSize);
                final long iterLimit = Math.min(limit, offset + batchSize);
                for (; !cursor.isDone() && offset < iterLimit; cursor.advance(), offset++) {
                  final Map<String, Object> theEvent = new LinkedHashMap<>();
                  for (int j = 0; j < allColumns.size(); j++) {
                    theEvent.put(allColumns.get(j), getColumnValue(j));
                  }
                  events.add(theEvent);
                }
                return events;
              }

              private Object getColumnValue(int i)
              {
                final BaseObjectColumnValueSelector selector = columnSelectors.get(i);
                final Object value = selector == null ? null : selector.getObject();
                return value;
              }
            };
          }

          @Override
          public void cleanup(Iterator<ScanResultValue> iterFromMake)
          {
          }
        }
    ).withBaggage(cursorHolder);
  }

  /**
   * If we're performing time-ordering, we want to scan through the first `limit` rows in each segment ignoring the number
   * of rows already counted on other segments.
   */
  private long calculateRemainingScanRowsLimit(ScanQuery query, ResponseContext responseContext)
  {
    if (query.getTimeOrder().equals(Order.NONE)) {
      return query.getScanRowsLimit() - (Long) responseContext.getRowScanCount();
    }
    return query.getScanRowsLimit();
  }

  public static CursorBuildSpec makeCursorBuildSpec(ScanQuery query, @Nullable QueryMetrics<?> queryMetrics)
  {
    return CursorBuildSpec.builder()
                          .setInterval(query.getSingleInterval())
                          .setFilter(Filters.convertToCNFFromQueryContext(query, Filters.toFilter(query.getFilter())))
                          .setVirtualColumns(query.getVirtualColumns())
                          .setPhysicalColumns(query.getRequiredColumns())
                          .setPreferredOrdering(query.getOrderBys())
                          .setQueryContext(query.context())
                          .setQueryMetrics(queryMetrics)
                          .build();
  }
}
