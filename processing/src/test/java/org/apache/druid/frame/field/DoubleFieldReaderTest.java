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

package org.apache.druid.frame.field;

import org.apache.datasketches.memory.WritableMemory;
import org.apache.druid.frame.FrameType;
import org.apache.druid.frame.key.KeyOrder;
import org.apache.druid.frame.write.FrameWriterTestData;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.query.extraction.SubstringDimExtractionFn;
import org.apache.druid.query.filter.DruidObjectPredicate;
import org.apache.druid.query.filter.StringPredicateDruidPredicateFactory;
import org.apache.druid.query.rowsandcols.MapOfColumnsRowsAndColumns;
import org.apache.druid.query.rowsandcols.column.ColumnAccessor;
import org.apache.druid.query.rowsandcols.concrete.RowBasedFrameRowsAndColumnsTest;
import org.apache.druid.segment.BaseDoubleColumnValueSelector;
import org.apache.druid.segment.ColumnValueSelector;
import org.apache.druid.segment.DimensionDictionarySelector;
import org.apache.druid.segment.DimensionSelector;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.data.IndexedInts;
import org.apache.druid.testing.InitializedNullHandlingTest;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@RunWith(Parameterized.class)
public class DoubleFieldReaderTest extends InitializedNullHandlingTest
{
  private static final long MEMORY_POSITION = 1;

  @Rule
  public MockitoRule mockitoRule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS);

  @Mock
  public BaseDoubleColumnValueSelector writeSelector;

  private final FrameType frameType;

  private WritableMemory memory;
  private FieldWriter fieldWriter;

  public DoubleFieldReaderTest(FrameType frameType)
  {
    this.frameType = frameType;
  }

  @Parameterized.Parameters(name = "frameType = {0}")
  public static Iterable<Object[]> constructorFeeder()
  {
    final List<Object[]> constructors = new ArrayList<>();
    for (FrameType frameType : FrameType.values()) {
      if (frameType.isRowBased()) {
        constructors.add(new Object[]{frameType});
      }
    }
    return constructors;
  }

  @Before
  public void setUp()
  {
    memory = WritableMemory.allocate(1000);
    fieldWriter = DoubleFieldWriter.forPrimitive(writeSelector, frameType);
  }

  @After
  public void tearDown()
  {
    fieldWriter.close();
  }

  @Test
  public void test_isNull_defaultOrNull()
  {
    writeToMemory(null);
    Assert.assertTrue(DoubleFieldReader.forPrimitive(frameType).isNull(memory, MEMORY_POSITION));
  }

  @Test
  public void test_isNull_aValue()
  {
    writeToMemory(5.1d);
    Assert.assertFalse(DoubleFieldReader.forPrimitive(frameType).isNull(memory, MEMORY_POSITION));
  }

  @Test
  public void test_makeColumnValueSelector_defaultOrNull()
  {
    writeToMemory(null);

    final ColumnValueSelector<?> readSelector =
        DoubleFieldReader.forPrimitive(frameType)
                         .makeColumnValueSelector(memory, new ConstantFieldPointer(MEMORY_POSITION, -1));

    Assert.assertTrue(readSelector.isNull());
  }

  @Test
  public void test_makeColumnValueSelector_aValue()
  {
    writeToMemory(5.1d);

    final ColumnValueSelector<?> readSelector =
        DoubleFieldReader.forPrimitive(frameType)
                         .makeColumnValueSelector(memory, new ConstantFieldPointer(MEMORY_POSITION, -1));

    Assert.assertEquals(5.1d, readSelector.getObject());
  }

  @Test
  public void test_makeDimensionSelector_defaultOrNull()
  {
    writeToMemory(null);

    final DimensionSelector readSelector =
        DoubleFieldReader.forPrimitive(frameType)
                         .makeDimensionSelector(memory, new ConstantFieldPointer(MEMORY_POSITION, -1), null);

    // Data retrieval tests.
    final IndexedInts row = readSelector.getRow();
    Assert.assertEquals(1, row.size());
    Assert.assertNull(readSelector.lookupName(0));

    // Informational method tests.
    Assert.assertFalse(readSelector.supportsLookupNameUtf8());
    Assert.assertFalse(readSelector.nameLookupPossibleInAdvance());
    Assert.assertEquals(DimensionDictionarySelector.CARDINALITY_UNKNOWN, readSelector.getValueCardinality());
    Assert.assertEquals(String.class, readSelector.classOfObject());
    Assert.assertNull(readSelector.idLookup());

    // Value matcher tests.
    Assert.assertFalse(readSelector.makeValueMatcher("0.0").matches(false));
    Assert.assertTrue(readSelector.makeValueMatcher((String) null).matches(false));
    Assert.assertFalse(readSelector.makeValueMatcher(StringPredicateDruidPredicateFactory.equalTo("0.0")).matches(false));
    Assert.assertTrue(readSelector.makeValueMatcher(StringPredicateDruidPredicateFactory.of(DruidObjectPredicate.isNull())).matches(false));
  }

  @Test
  public void testCompareRows()
  {
    final List<Double> rows = FrameWriterTestData.TEST_DOUBLES.getData(KeyOrder.ASCENDING);

    final ColumnAccessor accessor =
        RowBasedFrameRowsAndColumnsTest.MAKER.apply(
            MapOfColumnsRowsAndColumns.builder()
                                      .add("dim1", rows.toArray(), ColumnType.DOUBLE)
                                      .build()
        ).findColumn("dim1").toAccessor();

    for (int i = 1; i < rows.size(); i++) {
      if (Objects.equals(accessor.getObject(i - 1), accessor.getObject(i))) {
        Assert.assertEquals(0, accessor.compareRows(i - 1, i));
      } else {
        Assert.assertTrue(accessor.compareRows(i - 1, i) < 0);
      }
    }
  }

  @Test
  public void test_makeDimensionSelector_aValue()
  {
    writeToMemory(5.1d);

    final DimensionSelector readSelector =
        DoubleFieldReader.forPrimitive(frameType)
                         .makeDimensionSelector(memory, new ConstantFieldPointer(MEMORY_POSITION, -1), null);

    // Data retrieval tests.
    final IndexedInts row = readSelector.getRow();
    Assert.assertEquals(1, row.size());
    Assert.assertEquals("5.1", readSelector.lookupName(0));

    // Informational method tests.
    Assert.assertFalse(readSelector.supportsLookupNameUtf8());
    Assert.assertFalse(readSelector.nameLookupPossibleInAdvance());
    Assert.assertEquals(DimensionDictionarySelector.CARDINALITY_UNKNOWN, readSelector.getValueCardinality());
    Assert.assertEquals(String.class, readSelector.classOfObject());
    Assert.assertNull(readSelector.idLookup());

    // Value matcher tests.
    Assert.assertTrue(readSelector.makeValueMatcher("5.1").matches(false));
    Assert.assertFalse(readSelector.makeValueMatcher("5").matches(false));
    Assert.assertTrue(readSelector.makeValueMatcher(StringPredicateDruidPredicateFactory.equalTo("5.1"))
                                  .matches(false));
    Assert.assertFalse(readSelector.makeValueMatcher(StringPredicateDruidPredicateFactory.equalTo("5")).matches(false));
  }

  @Test
  public void test_makeDimensionSelector_aValue_extractionFn()
  {
    writeToMemory(20.5d);

    final DimensionSelector readSelector =
        DoubleFieldReader.forPrimitive(frameType).makeDimensionSelector(
            memory,
            new ConstantFieldPointer(MEMORY_POSITION, -1),
            new SubstringDimExtractionFn(1, null)
        );

    // Data retrieval tests.
    final IndexedInts row = readSelector.getRow();
    Assert.assertEquals(1, row.size());
    Assert.assertEquals("0.5", readSelector.lookupName(0));

    // Informational method tests.
    Assert.assertFalse(readSelector.supportsLookupNameUtf8());
    Assert.assertFalse(readSelector.nameLookupPossibleInAdvance());
    Assert.assertEquals(DimensionDictionarySelector.CARDINALITY_UNKNOWN, readSelector.getValueCardinality());
    Assert.assertEquals(String.class, readSelector.classOfObject());
    Assert.assertNull(readSelector.idLookup());

    // Value matcher tests.
    Assert.assertTrue(readSelector.makeValueMatcher("0.5").matches(false));
    Assert.assertFalse(readSelector.makeValueMatcher("2").matches(false));
    Assert.assertTrue(readSelector.makeValueMatcher(StringPredicateDruidPredicateFactory.equalTo("0.5"))
                                  .matches(false));
    Assert.assertFalse(readSelector.makeValueMatcher(StringPredicateDruidPredicateFactory.equalTo("2")).matches(false));
  }

  private void writeToMemory(final Double value)
  {
    Mockito.when(writeSelector.isNull()).thenReturn(value == null);

    if (value != null) {
      Mockito.when(writeSelector.getDouble()).thenReturn(value);
    }

    if (fieldWriter.writeTo(memory, MEMORY_POSITION, memory.getCapacity() - MEMORY_POSITION) < 0) {
      throw new ISE("Could not write");
    }
  }
}
