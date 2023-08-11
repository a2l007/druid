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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import org.apache.druid.jackson.DefaultObjectMapper;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.lifecycle.Lifecycle;
import org.apache.druid.java.util.http.client.HttpClient;
import org.apache.druid.java.util.http.client.response.StatusResponseHolder;
import org.apache.druid.query.lookup.namespace.DruidExtractionNamespace;
import org.apache.druid.server.lookup.namespace.cache.CacheHandler;
import org.apache.druid.server.lookup.namespace.cache.CacheScheduler;
import org.apache.druid.server.lookup.namespace.cache.NamespaceExtractionCacheManager;
import org.apache.druid.server.lookup.namespace.cache.OnHeapNamespaceExtractionCacheManager;
import org.apache.druid.server.metrics.NoopServiceEmitter;
import org.easymock.EasyMock;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.joda.time.Period;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class DruidCacheGeneratorTest
{
  private HttpClient mockClient;
  private ObjectMapper objectMapper;

  private Lifecycle lifecycle;
  private CacheScheduler scheduler;
  private NamespaceExtractionCacheManager cacheManager;

  @Before
  public void setup() throws Exception
  {
    lifecycle = new Lifecycle();
    lifecycle.start();
    NoopServiceEmitter noopServiceEmitter = new NoopServiceEmitter();
    cacheManager = new OnHeapNamespaceExtractionCacheManager(
        lifecycle,
        noopServiceEmitter,
        new NamespaceExtractionConfig()
    );
    scheduler = new CacheScheduler(
        noopServiceEmitter,
        Collections.emptyMap(),
        cacheManager
    );
    mockClient = EasyMock.createMock(HttpClient.class);
    objectMapper = new DefaultObjectMapper();
  }

  @Test
  public void testFullyAvailableLookups() throws Exception
  {
    final String segmentVersion = "2023-08-09T23:03:04";
    List<List<String>> segmentVersions = Collections.singletonList(ImmutableList.of("1", segmentVersion));
    DruidExtractionNamespace extractionNamespace = new DruidExtractionNamespace(
        new Period("PT5S"),
        "testds",
        "SELECT key, value from testds",
        "http://testdruid:9999",
        null
    );
    List<List<String>> expectedLookups = Collections.singletonList(ImmutableList.of("stan", "blue", "kyle", "green"));
    CacheHandler cache = cacheManager.allocateCache();

    EasyMock.expect(mockClient.go(EasyMock.anyObject(), EasyMock.anyObject())).andReturn(
                Futures.immediateFuture(
                    new StatusResponseHolder(
                        HttpResponseStatus.OK,
                        new StringBuilder(objectMapper.writeValueAsString(segmentVersions))
                    )
                ))
            .once();

    EasyMock.expect(mockClient.go(EasyMock.anyObject(), EasyMock.anyObject())).andReturn(
                Futures.immediateFuture(
                    new StatusResponseHolder(
                        HttpResponseStatus.OK,
                        new StringBuilder(objectMapper.writeValueAsString(expectedLookups))
                    )
                ))
            .once();
    EasyMock.replay(mockClient);
    DruidCacheGenerator cacheGenerator = new DruidCacheGenerator(mockClient, objectMapper);
    String actualVersion = cacheGenerator.generateCache(
        extractionNamespace,
        EasyMock.mock(CacheScheduler.EntryImpl.class),
        "1",
        cache
    );
    Assert.assertEquals(DateTimes.of(segmentVersion).getMillis(), Long.parseLong(actualVersion));
    assertLookups(expectedLookups, cache.getCache());
  }

  @Test
  public void testLookupsWithUnavailableSegments() throws Exception
  {
    final String segmentVersion = "2023-08-09T23:03:04";
    List<List<String>> segmentVersions = ImmutableList.of(
        ImmutableList.of("0", segmentVersion),
        ImmutableList.of("1", segmentVersion)
    );
    DruidExtractionNamespace extractionNamespace = new DruidExtractionNamespace(
        new Period("PT5S"),
        "testds",
        "SELECT key, value from testds",
        "http://testdruid:9999",
        null
    );
    List<List<String>> expectedLookups = Collections.singletonList(ImmutableList.of("stan", "blue", "kyle", "green"));
    CacheHandler cache = cacheManager.allocateCache();

    EasyMock.expect(mockClient.go(EasyMock.anyObject(), EasyMock.anyObject())).andReturn(
                Futures.immediateFuture(
                    new StatusResponseHolder(
                        HttpResponseStatus.OK,
                        new StringBuilder(objectMapper.writeValueAsString(segmentVersions))
                    )
                ))
            .once();

    EasyMock.expect(mockClient.go(EasyMock.anyObject(), EasyMock.anyObject())).andReturn(
                Futures.immediateFuture(
                    new StatusResponseHolder(
                        HttpResponseStatus.OK,
                        new StringBuilder(objectMapper.writeValueAsString(expectedLookups))
                    )
                ))
            .once();
    EasyMock.replay(mockClient);
    DruidCacheGenerator cacheGenerator = new DruidCacheGenerator(mockClient, objectMapper);
    String actualVersion = cacheGenerator.generateCache(
        extractionNamespace,
        EasyMock.mock(CacheScheduler.EntryImpl.class),
        "1",
        cache
    );
    // Update fails since we have unavailable segments
    Assert.assertEquals(null, actualVersion);
    assertLookups(Collections.emptyList(), cache.getCache());
  }

  @After
  public void tearDown()
  {
    lifecycle.stop();
  }

  private void assertLookups(List<List<String>> expected, Map<String, String> actual)
  {
    Assert.assertEquals(expected.size(), actual.size());
    for (List<String> expectedList : expected) {
      Assert.assertTrue(actual.containsKey(expectedList.get(0)));
      Assert.assertEquals(expectedList.get(1), actual.get(expectedList.get(0)));
    }

  }

}
