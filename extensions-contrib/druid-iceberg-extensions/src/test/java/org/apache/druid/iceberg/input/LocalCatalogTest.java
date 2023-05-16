package org.apache.druid.iceberg.input;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableMap;
import org.apache.druid.jackson.DefaultObjectMapper;
import org.apache.druid.java.util.common.FileUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;

public class LocalCatalogTest
{
  @Test
  public void testCatalogSerDe() throws JsonProcessingException
  {
    final File warehouseDir = FileUtils.createTempDir();
    DefaultObjectMapper mapper = new DefaultObjectMapper();
    LocalCatalog before = new LocalCatalog(warehouseDir.getPath(), new HashMap<>());
    LocalCatalog after = mapper.readValue(
        mapper.writeValueAsString(before), LocalCatalog.class);
    Assert.assertEquals(before, after);
    Assert.assertEquals("hadoop", before.retrieveCatalog().name());
    Assert.assertEquals("hadoop", after.retrieveCatalog().name());
  }
}
