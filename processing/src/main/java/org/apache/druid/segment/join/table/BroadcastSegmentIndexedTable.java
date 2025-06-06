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

package org.apache.druid.segment.join.table;

import com.google.common.base.Preconditions;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.io.Closer;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.query.cache.CacheKeyBuilder;
import org.apache.druid.segment.BaseObjectColumnValueSelector;
import org.apache.druid.segment.ColumnCache;
import org.apache.druid.segment.ColumnSelectorFactory;
import org.apache.druid.segment.Cursor;
import org.apache.druid.segment.CursorBuildSpec;
import org.apache.druid.segment.CursorFactory;
import org.apache.druid.segment.CursorHolder;
import org.apache.druid.segment.Cursors;
import org.apache.druid.segment.NilColumnValueSelector;
import org.apache.druid.segment.QueryableIndex;
import org.apache.druid.segment.QueryableIndexColumnSelectorFactory;
import org.apache.druid.segment.QueryableIndexSegment;
import org.apache.druid.segment.SimpleAscendingOffset;
import org.apache.druid.segment.VirtualColumns;
import org.apache.druid.segment.column.BaseColumn;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.RowSignature;
import org.apache.druid.segment.data.ReadableOffset;
import org.apache.druid.timeline.SegmentId;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class BroadcastSegmentIndexedTable implements IndexedTable
{
  private static final Logger LOG = new Logger(BroadcastSegmentIndexedTable.class);
  private static final byte CACHE_PREFIX = 0x01;

  private final QueryableIndexSegment segment;
  private final CursorFactory cursorFactory;
  private final QueryableIndex queryableIndex;
  private final Set<String> keyColumns;
  private final RowSignature rowSignature;
  private final String version;
  private final List<Index> keyColumnsIndexes;

  public BroadcastSegmentIndexedTable(
      final QueryableIndexSegment theSegment,
      final Set<String> keyColumns,
      final String version
  )
  {
    this.keyColumns = keyColumns;
    this.version = version;
    this.segment = Preconditions.checkNotNull(theSegment, "Segment must not be null");
    this.cursorFactory = Preconditions.checkNotNull(
        segment.as(CursorFactory.class),
        "Segment[%s] must have a cursor factory",
        segment.getId()
    );
    this.queryableIndex = Preconditions.checkNotNull(
        segment.as(QueryableIndex.class),
        "Segment[%s] must have a QueryableIndex",
        segment.getId()
    );

    this.rowSignature = cursorFactory.getRowSignature();

    // initialize keycolumn index builders
    final ArrayList<RowBasedIndexBuilder> indexBuilders = new ArrayList<>(rowSignature.size());
    final List<String> keyColumnNames = new ArrayList<>(keyColumns.size());
    for (int i = 0; i < rowSignature.size(); i++) {
      final RowBasedIndexBuilder m;
      final String columnName = rowSignature.getColumnName(i);
      if (keyColumns.contains(columnName)) {
        final ColumnType keyType =
            rowSignature.getColumnType(i).orElse(IndexedTableJoinMatcher.DEFAULT_KEY_TYPE);

        m = new RowBasedIndexBuilder(keyType);
        keyColumnNames.add(columnName);
      } else {
        m = null;
      }
      indexBuilders.add(m);
    }

    try (final CursorHolder cursorHolder = cursorFactory.makeCursorHolder(CursorBuildSpec.FULL_SCAN)) {
      final Cursor cursor = cursorHolder.asCursor();
      if (cursor == null) {
        this.keyColumnsIndexes = Collections.emptyList();
        return;
      }

      int rowNumber = 0;
      ColumnSelectorFactory columnSelectorFactory = cursor.getColumnSelectorFactory();

      // this should really be optimized to use dimension selectors where possible to populate indexes from bitmap
      // indexes, but, an optimization for another day
      final List<BaseObjectColumnValueSelector> selectors = keyColumnNames
          .stream()
          .map(columnName -> {
            // multi-value dimensions are not currently supported
            if (columnSelectorFactory.getColumnCapabilities(columnName).hasMultipleValues().isMaybeTrue()) {
              return NilColumnValueSelector.instance();
            }
            return columnSelectorFactory.makeColumnValueSelector(columnName);
          })
          .collect(Collectors.toList());

      while (!cursor.isDone()) {
        for (int keyColumnSelectorIndex = 0; keyColumnSelectorIndex < selectors.size(); keyColumnSelectorIndex++) {
          final String keyColumnName = keyColumnNames.get(keyColumnSelectorIndex);
          final int columnPosition = rowSignature.indexOf(keyColumnName);
          final RowBasedIndexBuilder keyColumnIndexBuilder = indexBuilders.get(columnPosition);
          keyColumnIndexBuilder.add(selectors.get(keyColumnSelectorIndex).getObject());
        }

        if (rowNumber % 100_000 == 0) {
          if (rowNumber == 0) {
            LOG.debug("Indexed first row for table %s", theSegment.getId());
          } else {
            LOG.debug("Indexed row %s for table %s", rowNumber, theSegment.getId());
          }
        }
        rowNumber++;
        cursor.advance();
      }

      this.keyColumnsIndexes = indexBuilders.stream()
                                            .map(builder -> builder != null ? builder.build() : null)
                                            .collect(Collectors.toList());

      LOG.info("Created BroadcastSegmentIndexedTable with %s rows.", rowNumber);
    }
  }

  @Override
  public String version()
  {
    return version;
  }

  @Override
  public Set<String> keyColumns()
  {
    return keyColumns;
  }

  @Override
  public RowSignature rowSignature()
  {
    return rowSignature;
  }

  @Override
  public int numRows()
  {
    return queryableIndex.getNumRows();
  }

  @Override
  public Index columnIndex(int column)
  {
    return RowBasedIndexedTable.getKeyColumnIndex(column, keyColumnsIndexes);
  }

  @Override
  public Reader columnReader(int column)
  {
    if (!rowSignature.contains(column)) {
      throw new IAE("Column[%d] is not a valid column for segment[%s]", column, segment.getId());
    }
    final SimpleAscendingOffset offset = new SimpleAscendingOffset(queryableIndex.getNumRows());
    final BaseColumn baseColumn = queryableIndex.getColumnHolder(rowSignature.getColumnName(column)).getColumn();
    final BaseObjectColumnValueSelector<?> selector = baseColumn.makeColumnValueSelector(offset);

    return new Reader()
    {
      @Nullable
      @Override
      public Object read(int row)
      {
        offset.setCurrentOffset(row);
        return selector.getObject();
      }

      @Override
      public void close() throws IOException
      {
        baseColumn.close();
      }
    };
  }

  @Nullable
  @Override
  public ColumnSelectorFactory makeColumnSelectorFactory(ReadableOffset offset, Closer closer)
  {
    return new QueryableIndexColumnSelectorFactory(
        VirtualColumns.EMPTY,
        Cursors.getTimeOrdering(queryableIndex.getOrdering()),
        offset,
        new ColumnCache(queryableIndex, closer)
    );
  }

  @Override
  public byte[] computeCacheKey()
  {
    SegmentId segmentId = segment.getId();
    CacheKeyBuilder keyBuilder = new CacheKeyBuilder(CACHE_PREFIX);
    return keyBuilder
        .appendLong(segmentId.getInterval().getStartMillis())
        .appendLong(segmentId.getInterval().getEndMillis())
        .appendString(segmentId.getVersion())
        .appendString(segmentId.getDataSource())
        .appendInt(segmentId.getPartitionNum())
        .build();
  }

  @Override
  public boolean isCacheable()
  {
    return true;
  }

  @Override
  public void close()
  {
    // the segment will close itself when it is dropped, no need to do it here
  }

  @Override
  public Optional<Closeable> acquireReference()
  {
    // this is wrong, we should have a reference to the reference counted segment instead of the direct segment so that
    // we can use reference counting correctly to ensure the segment doesn't get dropped. this probably means
    // segmentizer factory needs to change to spit out the reference instead of the raw segment...
    return Optional.of(() -> {});
  }
}
