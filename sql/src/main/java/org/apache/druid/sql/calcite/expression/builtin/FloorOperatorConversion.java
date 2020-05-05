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

import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.druid.segment.column.RowSignature;
import org.apache.druid.sql.calcite.expression.DruidExpression;
import org.apache.druid.sql.calcite.expression.OperatorConversions;
import org.apache.druid.sql.calcite.expression.SqlOperatorConversion;
import org.apache.druid.sql.calcite.planner.PlannerContext;

import javax.annotation.Nullable;

public class FloorOperatorConversion implements SqlOperatorConversion
{
  @Override
  public SqlOperator calciteOperator()
  {
    return SqlStdOperatorTable.FLOOR;
  }

  @Override
  @Nullable
  public DruidExpression toDruidExpression(
      final PlannerContext plannerContext,
      final RowSignature rowSignature,
      final RexNode rexNode
  )
  {
    final RexCall call = (RexCall) rexNode;

    if (call.getOperands().size() == 1) {
      // FLOOR(expr) -- numeric FLOOR
      return OperatorConversions.convertCall(plannerContext, rowSignature, call, "floor");
    } else if (call.getOperands().size() == 2) {
      // FLOOR(expr TO timeUnit) -- time FLOOR
      return DruidExpression.fromFunctionCall(
          "timestamp_floor",
          TimeFloorOperatorConversion.toTimestampFloorOrCeilArgs(plannerContext, rowSignature, call.getOperands())
      );
    } else {
      // WTF? FLOOR with the wrong number of arguments?
      return null;
    }
  }
}
