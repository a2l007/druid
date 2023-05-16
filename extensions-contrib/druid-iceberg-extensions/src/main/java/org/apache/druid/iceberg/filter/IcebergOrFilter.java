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

package org.apache.druid.iceberg.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

public class IcebergOrFilter implements IcebergFilter
{
  private final List<IcebergFilter> filters;

  private static final Logger log = new Logger(IcebergAndFilter.class);

  @JsonCreator
  public IcebergOrFilter(
      @JsonProperty("filters") List<IcebergFilter> filters
  )
  {
    this.filters = filters;
  }

  @JsonProperty
  public List<IcebergFilter> getFilters()
  {
    return filters;
  }

  @Override
  public TableScan filter(TableScan tableScan)
  {
    tableScan = tableScan.filter(getFilterExpression());
    return tableScan;
  }

  @Override
  public Expression getFilterExpression()
  {
    List<Expression> expressions = new ArrayList<>();
    LinkedHashSet<IcebergFilter> flatFilters = flattenOrChildren(filters);
    if (!flatFilters.isEmpty()) {
      for (IcebergFilter filter : flatFilters) {
        expressions.add(filter.getFilterExpression());
      }
    } else {
      log.error("Empty filter set, running iceberg table scan without filters");
    }
    Expression finalExpr = Expressions.alwaysFalse();
    for (Expression expr : expressions) {
      finalExpr = Expressions.or(finalExpr, expr);
    }
    return finalExpr;
  }

  private static LinkedHashSet<IcebergFilter> flattenOrChildren(final Collection<IcebergFilter> filters)
  {
    final LinkedHashSet<IcebergFilter> retVal = new LinkedHashSet<>();

    for (IcebergFilter child : filters) {
      if (child instanceof IcebergOrFilter) {
        retVal.addAll(flattenOrChildren(((IcebergOrFilter) child).getFilters()));
        //} else if (!(child instanceof TrueFilter)) {
      } else {
        retVal.add(child);
      }
    }

    return retVal;
  }
}
