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

package org.apache.druid.msq.indexing;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.apache.druid.segment.IndexSpec;
import org.apache.druid.segment.data.CompressionStrategy;
import org.junit.Test;

public class MSQSpecTest
{

  @Test
  public void testLegacyEquals()
  {
    EqualsVerifier.forClass(LegacyMSQSpec.class)
                  .withNonnullFields("destination", "tuningConfig")
                  .withPrefabValues(
                      IndexSpec.class,
                      IndexSpec.DEFAULT,
                      IndexSpec.builder().withDimensionCompression(CompressionStrategy.ZSTD).build()
                  )
                  .usingGetClass()
                  .verify();
  }

  @Test
  public void testQueryDefEquals()
  {
    EqualsVerifier.forClass(QueryDefMSQSpec.class)
                  .withNonnullFields("destination", "tuningConfig")
                  .withPrefabValues(
                      IndexSpec.class,
                      IndexSpec.DEFAULT,
                      IndexSpec.builder().withDimensionCompression(CompressionStrategy.ZSTD).build()
                  )
                  .usingGetClass()
                  .verify();
  }

  @Test
  public void testEquals()
  {
    EqualsVerifier.forClass(MSQSpec.class)
                  .withNonnullFields("destination", "tuningConfig")
                  .withPrefabValues(
                      IndexSpec.class,
                      IndexSpec.DEFAULT,
                      IndexSpec.builder().withDimensionCompression(CompressionStrategy.ZSTD).build()
                  )
                  .usingGetClass()
                  .verify();
  }
}
