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

package org.apache.druid.query.aggregation.exact.count.bitmap64;

import org.apache.druid.data.input.InputRow;
import org.apache.druid.segment.serde.ComplexMetricExtractor;

import javax.annotation.Nullable;

public class Bitmap64ExactCountBuildComplexMetricSerde extends Bitmap64ExactCountMergeComplexMetricSerde
{
  private static final ComplexMetricExtractor EXTRACTOR = new ComplexMetricExtractor()
  {

    @Override
    public Class extractedClass()
    {
      return Object.class;
    }

    @Nullable
    @Override
    public Object extractValue(InputRow inputRow, String metricName)
    {
      return inputRow.getRaw(metricName);
    }
  };

  @Override
  public ComplexMetricExtractor getExtractor()
  {
    return EXTRACTOR;
  }

}
