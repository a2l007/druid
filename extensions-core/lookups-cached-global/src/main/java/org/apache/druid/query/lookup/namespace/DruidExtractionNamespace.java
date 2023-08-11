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

package org.apache.druid.query.lookup.namespace;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.google.common.base.Preconditions;
import org.apache.druid.java.util.common.logger.Logger;
import org.joda.time.Period;

import javax.annotation.Nullable;
import javax.validation.constraints.Min;

@JsonTypeName("druid")
public class DruidExtractionNamespace implements ExtractionNamespace
{
  private static final Logger LOG = new Logger(DruidExtractionNamespace.class);

  long DEFAULT_MAX_HEAP_PERCENTAGE = 10L;

  @JsonProperty
  private final String druidUrl;

  @JsonProperty
  private final Period pollPeriod;

  @JsonProperty
  private final String datasourceName;

  @JsonProperty
  private final String lookupQuery;

  @JsonProperty
  private final long maxHeapPercentage;

  @JsonCreator
  public DruidExtractionNamespace(
      @Min(0) @JsonProperty("pollPeriod") @Nullable final Period pollPeriod,
      @Nullable @JsonProperty("datasourceName") final String datasourceName,
      @Nullable @JsonProperty("lookupQuery") final String lookupQuery,
      @Nullable @JsonProperty("druidUrl") final String druidUrl,
      @Nullable @JsonProperty("maxHeapPercentage") final Long maxHeapPercentage
  )
  {
    if (pollPeriod == null) {
      // Warning because if JdbcExtractionNamespace is being used for lookups, any updates to the database will not
      // be picked up after the node starts. So for use cases where nodes start at different times (like streaming
      // ingestion with peons) there can be data inconsistencies across the cluster.
      LOG.warn("No pollPeriod configured for DruidExtractionNamespace - entries will be loaded only once at startup");
      this.pollPeriod = new Period(0L);
    } else {
      this.pollPeriod = pollPeriod;
    }
    this.datasourceName = Preconditions.checkNotNull(datasourceName, "Datasource cannot be null");
    this.lookupQuery = Preconditions.checkNotNull(lookupQuery, "Lookup query cannot be null");
    this.druidUrl = Preconditions.checkNotNull(druidUrl, "Druid URL cannot be null");
    this.maxHeapPercentage = maxHeapPercentage == null ? DEFAULT_MAX_HEAP_PERCENTAGE : maxHeapPercentage;
  }

  @Override
  public long getPollMs()
  {
    return pollPeriod.toStandardDuration().getMillis();
  }

  @Override
  public long getMaxHeapPercentage()
  {
    return maxHeapPercentage;
  }


  public String getDatasourceName()
  {
    return datasourceName;
  }

  public String getLookupQuery()
  {
    return lookupQuery;
  }

  public String getDruidUrl()
  {
    return druidUrl;
  }
}
