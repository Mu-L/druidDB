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

package org.apache.druid.sql.calcite.expression.builtin;

import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.math.expr.BuiltInExprMacros;
import org.apache.druid.sql.calcite.expression.DirectOperatorConversion;
import org.apache.druid.sql.calcite.expression.OperatorConversions;

public class DecodeBase64UTFOperatorConversion extends DirectOperatorConversion
{

  private static final SqlFunction SQL_FUNCTION = OperatorConversions
      .operatorBuilder(StringUtils.toUpperCase(BuiltInExprMacros.StringDecodeBase64UTFExprMacro.NAME))
      .operandTypes(SqlTypeFamily.CHARACTER)
      .returnTypeNullable(SqlTypeName.VARCHAR)
      .functionCategory(SqlFunctionCategory.STRING)
      .build();

  public DecodeBase64UTFOperatorConversion()
  {
    super(SQL_FUNCTION, SQL_FUNCTION.getName());
  }
}
