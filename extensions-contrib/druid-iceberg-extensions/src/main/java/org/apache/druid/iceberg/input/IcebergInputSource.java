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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import org.apache.druid.data.input.AbstractInputSourceAdapter;
import org.apache.druid.data.input.InputFormat;
import org.apache.druid.data.input.InputRowSchema;
import org.apache.druid.data.input.InputSource;
import org.apache.druid.data.input.InputSourceAdapter;
import org.apache.druid.data.input.InputSourceReader;
import org.apache.druid.data.input.InputSplit;
import org.apache.druid.data.input.SplitHintSpec;
import org.apache.druid.data.input.impl.SplittableInputSource;
import org.apache.druid.iceberg.filter.IcebergFilter;
import org.apache.druid.java.util.common.logger.Logger;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

public class IcebergInputSource implements SplittableInputSource<List<String>>
{
  @JsonProperty
  private final String tableName;

  @JsonProperty
  private final String namespace;

  @JsonProperty
  private IcebergCatalog icebergCatalog;

  @JsonProperty
  private IcebergFilter icebergFilter;

  @JsonProperty
  private AbstractInputSourceAdapter warehouseSource;

  private static final Logger log = new Logger(IcebergInputSource.class);

  private boolean isLoaded = false;

  @JsonCreator
  public IcebergInputSource(
      @JsonProperty("tableName") String tableName,
      @JsonProperty("namespace") String namespace,
      @JsonProperty("icebergFilter") IcebergFilter icebergFilter,
      @JsonProperty("catalogType") IcebergCatalog icebergCatalog,
      @JsonProperty("warehouseSource") AbstractInputSourceAdapter warehouseSource
  )
  {
    this.tableName = Preconditions.checkNotNull(tableName, "tableName cannot be null");
    this.namespace = Preconditions.checkNotNull(namespace, "namespace cannot be null");
    this.icebergCatalog = icebergCatalog;
    this.icebergFilter = icebergFilter;
    this.warehouseSource = warehouseSource;
  }

  @Override
  public boolean needsFormat()
  {
    return true;
  }

  @Override
  public InputSourceReader reader(
      InputRowSchema inputRowSchema, @Nullable InputFormat inputFormat, File temporaryDirectory
  )
  {
    if (!isLoaded) {
      retrieveIcebergDatafiles();
    }
    return warehouseSource.getInputSource().reader(inputRowSchema, inputFormat, temporaryDirectory);
  }

  @Override
  public Stream<InputSplit<List<String>>> createSplits(
      InputFormat inputFormat, @Nullable SplitHintSpec splitHintSpec
  ) throws IOException
  {
    log.error("A2L Creating Splits");
    if (!isLoaded) {
      retrieveIcebergDatafiles();
    }
    return warehouseSource.getInputSource().createSplits(inputFormat, splitHintSpec);
  }

  @Override
  public int estimateNumSplits(InputFormat inputFormat, @Nullable SplitHintSpec splitHintSpec) throws IOException
  {
    log.error("A2L Estimating Splits");
    if (!isLoaded) {
      retrieveIcebergDatafiles();
    }
    return warehouseSource.getInputSource().estimateNumSplits(inputFormat, splitHintSpec);
  }

  @Override
  public InputSource withSplit(InputSplit<List<String>> inputSplit)
  {
    log.error("A2L Using splits");
    return warehouseSource.getInputSource().withSplit(inputSplit);
  }

  @Override
  public SplitHintSpec getSplitHintSpecOrDefault(@Nullable SplitHintSpec splitHintSpec)
  {
    return warehouseSource.getInputSource().getSplitHintSpecOrDefault(splitHintSpec);
  }

  @JsonProperty
  public String getTableName()
  {
    return tableName;
  }

  @JsonProperty
  public String getNamespace()
  {
    return namespace;
  }

  @JsonProperty
  public IcebergCatalog getIcebergCatalog()
  {
    return icebergCatalog;
  }

  @JsonProperty
  public IcebergFilter getIcebergFilter()
  {
    return icebergFilter;
  }

  protected void retrieveIcebergDatafiles()
  {
    List<String> snapshotDataFiles = icebergCatalog.extractSnapshotDataFiles(
        getNamespace(),
        getTableName(),
        getIcebergFilter()
    );
    log.error("Snap shot data files are :");
    for (String s : snapshotDataFiles) {
      log.error(s);
    }
    warehouseSource.setupInputSource(snapshotDataFiles);
    isLoaded = true;
  }
}
