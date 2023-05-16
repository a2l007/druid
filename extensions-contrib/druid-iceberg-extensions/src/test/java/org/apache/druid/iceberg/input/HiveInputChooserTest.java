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
package org.apache.druid.iceberg.input;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import com.fasterxml.jackson.databind.introspect.AnnotatedClassResolver;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.druid.data.input.ColumnsFilter;
import org.apache.druid.data.input.InputFormat;
import org.apache.druid.data.input.InputRowSchema;
import org.apache.druid.data.input.impl.CsvInputFormat;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.data.input.impl.TimestampSpec;
import org.apache.druid.guice.DruidGuiceExtensions;
import org.apache.druid.iceberg.common.IcebergDruidModule;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class HiveInputChooserTest
{
  ObjectMapper mapper = new ObjectMapper();
  private static final InputRowSchema INPUT_ROW_SCHEMA = new InputRowSchema(
      new TimestampSpec(null, null, null),
      DimensionsSpec.EMPTY,
      ColumnsFilter.all()
  );
  private static final String COLUMN = "value";

  private static final InputFormat INPUT_FORMAT = new CsvInputFormat(
      Arrays.asList(TimestampSpec.DEFAULT_COLUMN, COLUMN),
      null,
      false,
      null,
      0
  );

  @Before
  public void setUp()
  {
    for (Module jacksonModule : new IcebergDruidModule().getJacksonModules()) {
      mapper.registerModule(jacksonModule);
    }
  }

  /*@Test
  public void testSubTypeRegistration()
  {
    MapperConfig config = mapper.getDeserializationConfig();
    //AnnotatedClass annotatedClass = AnnotatedClassResolver.resolveWithoutSuperTypes(config, HiveInputChooser.class);
    AnnotatedClass annotatedClass = null;
    List<String> subtypes = mapper.getSubtypeResolver()
                                  .collectAndResolveSubtypesByClass(config, annotatedClass)
                                  .stream()
                                  .map(NamedType::getName)
                                  .collect(Collectors.toList());
    Assert.assertNotNull(subtypes);
    Assert.assertEquals("hive", Iterables.getOnlyElement(subtypes));
  }

   */

  /*@Test
  public void testHiveInputSource() throws JsonProcessingException
  {
    HiveInputChooser hiveInputSource = new HiveInputChooser("test", "testNamespace");
    //InputEntityIteratingReader reader = (InputEntityIteratingReader) hiveInputSource.formattableReader(INPUT_ROW_SCHEMA, INPUT_FORMAT, Files.createTempDir());

    System.out.println(mapper.writeValueAsString(hiveInputSource));
    String str = "{\"type\":\"hive\",\"tableName\":\"test\",\"namespace\":\"testNamespace\"}";
    HiveInputChooser hv = mapper.readValue(str, HiveInputChooser.class);
    System.out.println(hv.getNamespace());
  }*/

  /*@Test
  public void testModule()
  {
    Injector injector = createInjector();
    HiveInputChooser inputSource = injector.getInstance(HiveInputChooser.class);
    System.out.println(inputSource.getNamespace());
  }
  private static Injector createInjector()
  {
    return Guice.createInjector(
        ImmutableList.of(
            new DruidGuiceExtensions(),
            new IcebergDruidModule()
        )
    );
  }


   */
  @Test
  public void testMap() throws JsonProcessingException
  {
    ObjectMapper mapper = new ObjectMapper();
    Map<String,String> mapStr = ImmutableMap.of("test", "1", "test2", "2", "test3", "3");
    System.out.println(mapper.writeValueAsString(mapStr));
  }
}
