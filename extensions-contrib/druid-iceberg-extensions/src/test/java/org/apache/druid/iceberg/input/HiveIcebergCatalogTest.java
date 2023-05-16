package org.apache.druid.iceberg.input;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.druid.java.util.common.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;

public class HiveIcebergCatalogTest
{
  @Test
  public void testCatalogSerDe() throws JsonProcessingException
  {
    final File warehouseDir = FileUtils.createTempDir();
    HiveIcebergCatalog hiveCatalog = new HiveIcebergCatalog(warehouseDir.getPath(), "hdfs://testuri", new HashMap<>(), new Configuration());
    Assert.assertEquals("hive", hiveCatalog.retrieveCatalog().name());
  }
}
