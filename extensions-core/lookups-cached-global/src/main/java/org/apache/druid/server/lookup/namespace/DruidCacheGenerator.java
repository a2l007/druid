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

package org.apache.druid.server.lookup.namespace;

import com.fasterxml.jackson.annotation.JacksonInject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.jaxrs.smile.SmileMediaTypes;
import com.google.inject.Inject;
import org.apache.druid.data.input.MapPopulator;
import org.apache.druid.guice.annotations.EscalatedClient;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.JodaUtils;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.java.util.http.client.HttpClient;
import org.apache.druid.java.util.http.client.Request;
import org.apache.druid.java.util.http.client.response.StatusResponseHandler;
import org.apache.druid.java.util.http.client.response.StatusResponseHolder;
import org.apache.druid.query.lookup.namespace.CacheGenerator;
import org.apache.druid.query.lookup.namespace.DruidExtractionNamespace;
import org.apache.druid.server.lookup.namespace.cache.CacheHandler;
import org.apache.druid.server.lookup.namespace.cache.CacheScheduler;
import org.apache.druid.sql.http.ResultFormat;
import org.apache.druid.sql.http.SqlQuery;
import org.apache.druid.utils.JvmUtils;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.joda.time.DateTime;

import javax.annotation.Nullable;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class DruidCacheGenerator implements CacheGenerator<DruidExtractionNamespace>
{
  private static final Logger LOG = new Logger(DruidCacheGenerator.class);

  private static final long MAX_MEMORY = JvmUtils.getRuntimeInfo().getMaxHeapSizeBytes();

  private final HttpClient httpClient;

  private final ObjectMapper objectMapper;

  @Inject
  public DruidCacheGenerator(
      @EscalatedClient HttpClient httpClient,
      @JacksonInject ObjectMapper objectMapper
  )
  {
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
  }

  @Nullable
  @Override
  public String generateCache(
      DruidExtractionNamespace namespace,
      CacheScheduler.EntryImpl<DruidExtractionNamespace> id,
      String lastVersion,
      CacheHandler cache
  ) throws Exception
  {
    final long lastCheck = lastVersion == null ? JodaUtils.MIN_INSTANT : Long.parseLong(lastVersion);
    final Long lastDruidUpdate;

    try {
      lastDruidUpdate = lastUpdates(namespace);
      if (lastDruidUpdate == null || lastDruidUpdate <= lastCheck) {
        return null;
      }
    }
    catch (Exception e) {
      throw new ISE(e, "Error while updating druid lookups");
    }

    LOG.debug("Updating %s", id);

    final String newVersion = lastDruidUpdate.toString();
    final long startNs = System.nanoTime();

    Iterator<Pair<String, String>> pairs = getLookupPairs(namespace);

    final MapPopulator.PopulateResult populateResult = MapPopulator.populateAndWarnAtByteLimit(
        pairs,
        cache.getCache(),
        (long) (MAX_MEMORY * namespace.getMaxHeapPercentage() / 100.0),
        null == id ? null : id.toString()
    );
    final long duration = System.nanoTime() - startNs;
    LOG.info(
        "Finished loading %d values (%d bytes) for [%s] in %d ns",
        populateResult.getEntries(),
        populateResult.getBytes(),
        id,
        duration
    );
    return newVersion;
  }

  @Nullable
  private Long lastUpdates(DruidExtractionNamespace namespace)
      throws JsonProcessingException, MalformedURLException, ExecutionException, InterruptedException
  {
    final String datasourceName = namespace.getDatasourceName();
    // Retrieve the latest segment versions of available and unavailable segments from the sys schema tables.
    // The lookup should consider updates only if there are no unavailable segments.
    SqlQuery sqlQuery = new SqlQuery(
        "SELECT \n"
        + "  \"is_available\", MAX(CAST (LEFT(\"version\",10) || ' ' || RIGHT(LEFT(\"version\",19),8) AS TIMESTAMP))\n"
        + "FROM sys.segments\n"
        + "WHERE \"datasource\"= '" + datasourceName + "'\n"
        + "GROUP BY 1",
        ResultFormat.ARRAY,
        false,
        false,
        false,
        null,
        null
    );
    StatusResponseHolder responseHolder = httpClient.go(new Request(HttpMethod.POST, new URL(namespace.getDruidUrl()))
                                                            .addHeader(
                                                                HttpHeaders.Names.CONTENT_TYPE,
                                                                SmileMediaTypes.APPLICATION_JACKSON_SMILE
                                                            )
                                                            .setContent(
                                                                "application/json",
                                                                objectMapper.writeValueAsBytes(sqlQuery)
                                                            ), StatusResponseHandler.getInstance()).get();

    if (responseHolder.getStatus().equals(HttpResponseStatus.OK)) {
      List<List<String>> result = objectMapper.readValue(
          responseHolder.getContent(),
          new TypeReference<List<List<String>>>()
          {
          }
      );
      // The response contains the latest timestamps of available and unavailable segments and
      // is of format: [[0,"2023-08-10T01:04:06"],[1, ""2023-08-10T02:04:06"]]
      // The lookup should be updated only if all the segments are available which means our response should
      // only contain [[1, ""2023-08-10T02:04:06"]]
      //
      if (result.size() == 1 && result.get(0).get(0).equals("1")) {
        DateTime datetime = DateTimes.of(result.get(0).get(1));
        return datetime.getMillis();
      }
      LOG.error("Segments for lookup cache are not fully available yet!");
    } else {
      throw new ISE(
          "Error while fetching latest segment versions with code [%s] and content[%s]",
          responseHolder.getStatus(),
          responseHolder.getContent()
      );
    }

    return null;
  }

  private Iterator<Pair<String, String>> getLookupPairs(DruidExtractionNamespace namespace)
      throws MalformedURLException, JsonProcessingException, ExecutionException, InterruptedException
  {
    SqlQuery sqlQuery = new SqlQuery(
        namespace.getLookupQuery(),
        ResultFormat.ARRAY,
        false,
        false,
        false,
        null,
        null
    );
    StatusResponseHolder responseHolder = httpClient.go(new Request(HttpMethod.POST, new URL(namespace.getDruidUrl()))
                                                            .addHeader(
                                                                HttpHeaders.Names.CONTENT_TYPE,
                                                                SmileMediaTypes.APPLICATION_JACKSON_SMILE
                                                            )
                                                            .setContent(
                                                                "application/json",
                                                                objectMapper.writeValueAsBytes(sqlQuery)
                                                            ), StatusResponseHandler.getInstance()).get();

    if (responseHolder.getStatus().equals(HttpResponseStatus.OK)) {
      List<List<String>> result = objectMapper.readValue(
          responseHolder.getContent(),
          new TypeReference<List<List<String>>>()
          {
          }
      );
      if (result.size() > 0) {
        return result.stream().map(val -> new Pair<String, String>(val.get(0), val.get(1))).iterator();
      }
    } else {
      throw new ISE(
          "Error while querying druid with code [%s] and content[%s]",
          responseHolder.getStatus(),
          responseHolder.getContent()
      );
    }
    LOG.info("Druid lookup query yielded empty results");
    return Collections.emptyIterator();
  }
}
