package org.apache.druid.iceberg.common;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.inject.Binder;
import org.apache.druid.guice.JsonConfigProvider;
import org.apache.druid.iceberg.guice.HiveConf;
import org.apache.druid.iceberg.input.CatalogConfig;
import org.apache.druid.iceberg.input.HiveIcebergCatalog;
import org.apache.druid.initialization.DruidModule;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class IcebergDruidModule implements DruidModule
{
  @Override
  public List<? extends Module> getJacksonModules()
  {
    return Collections.singletonList(
        new SimpleModule("IcebergDruidModule")
            .registerSubtypes(
                new NamedType(HiveIcebergCatalog.class, "hive")
            )
    );
  }

  @Override
  public void configure(Binder binder)
  {
    JsonConfigProvider.bind(binder, "druid.iceberg.catalog", CatalogConfig.class);
    final Configuration conf = new Configuration();
    conf.setClassLoader(getClass().getClassLoader());

    // Ensure that FileSystem class level initialization happens with correct CL
    ClassLoader currCtxCl = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
      FileSystem.get(conf);
    }
    catch (IOException ex) {
      throw new RuntimeException(ex);
    }
    finally {
      Thread.currentThread().setContextClassLoader(currCtxCl);
    }

    binder.bind(Configuration.class).annotatedWith(HiveConf.class).toInstance(conf);

  }

}

