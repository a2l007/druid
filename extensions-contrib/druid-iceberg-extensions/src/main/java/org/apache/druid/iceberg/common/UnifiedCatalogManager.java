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

package org.apache.druid.iceberg.common;

import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.hive.HiveCatalog;

import java.util.HashMap;
import java.util.Map;

public class UnifiedCatalogManager
{
  private String catalogType;
  private String warehouseType;
  private String warehousePath;
  private String catalogUri;
  private Configuration configuration;

  public UnifiedCatalogManager(String catalogType, String warehouseType, String warehousePath, String catalogUri, Configuration configuration)
  {
    this.catalogType = catalogType;
    this.warehouseType = warehouseType;
    this.warehousePath = warehousePath;
    this.catalogUri = catalogUri;
    this.configuration = configuration;
  }

  public HiveCatalog setupCatalog()
  {
    HiveCatalog catalog = new HiveCatalog();
    catalog.setConf(configuration);

    Map<String, String> properties = new HashMap<>();
    properties.put("warehouse", getWarehousePath());
    properties.put("uri", getCatalogUri());

    catalog.initialize(getCatalogType(), properties);
    return catalog;
  }

  public String getCatalogType()
  {
    return catalogType;
  }

  public String getWarehouseType()
  {
    return warehouseType;
  }

  public String getWarehousePath()
  {
    return warehousePath;
  }

  public String getCatalogUri()
  {
    return catalogUri;
  }
}
