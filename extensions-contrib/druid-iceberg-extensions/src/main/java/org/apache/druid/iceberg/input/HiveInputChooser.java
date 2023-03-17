package org.apache.druid.iceberg.input;

import com.fasterxml.jackson.annotation.JacksonInject;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterators;
import org.apache.druid.data.input.InputChooser;
import org.apache.druid.iceberg.common.UnifiedCatalogManager;
import org.apache.druid.iceberg.guice.HiveConf;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.expressions.Literal;
import org.apache.iceberg.hive.HiveCatalog;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.types.Types;
import org.joda.time.Interval;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class HiveInputChooser implements InputChooser
{
  @JsonProperty
  private final String tableName;

  @JsonProperty
  private final String namespace;

  @JsonProperty
  private final String partitionColumn;

  @JsonProperty
  private final List<String> intervals;

  private final CatalogConfig catalogConfig;

  private final Configuration configuration;

  private static final Logger log = new Logger(HiveInputChooser.class);

  private List<String> snapshotDataFiles = new ArrayList<>();

  @JsonCreator
  public HiveInputChooser(
      @JsonProperty("tableName") String tableName,
      @JsonProperty("namespace") String namespace,
      @JsonProperty("partitionColumn") String partitionColumn,
      @JsonProperty("intervals") List<String> intervals,
      @JacksonInject CatalogConfig catalogConfig,
      @JacksonInject @HiveConf Configuration configuration
  )
  {
    this.tableName = Preconditions.checkNotNull(tableName, "tableName cannot be null");
    this.namespace = Preconditions.checkNotNull(namespace, "namespace cannot be null");
    this.partitionColumn = partitionColumn;
    if (partitionColumn != null) {
      Preconditions.checkNotNull(intervals, "Intervals cannot be null when partitionColumn is specified");
    }
    this.intervals = intervals;
    this.catalogConfig = catalogConfig;
    this.configuration = configuration;
    catalogConfig.getCatalogProperties()
                 .forEach((key, value) -> this.configuration.set(key, value));
  }

  protected void setupHdfsInputSource()
  {
    if (snapshotDataFiles.isEmpty()) {
      UnifiedCatalogManager ucm = new UnifiedCatalogManager(
          catalogConfig.getCatalogType(),
          catalogConfig.getWarehouseType(),
          catalogConfig.getWarehousePath(),
          catalogConfig.getCatalogUri(),
          configuration
      );

      Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
      HiveCatalog catalog = ucm.setupCatalog();
      Namespace namespace = Namespace.of(getNamespace());

      List<TableIdentifier> tables = catalog.listTables(namespace);
      TableIdentifier tableIdentifier = tables.stream()
                                              .filter(tableId -> tableId.toString()
                                                                        .equals(getNamespace() + "." + getTableName()))
                                              .findFirst()
                                              .orElse(null);
      if (tableIdentifier == null) {
        throw new IAE(" Couldn't retrieve table identifier for '%s'", getTableName());
      }
      snapshotDataFiles = extractSnapshotDataFiles(
          catalog,
          tableIdentifier,
          getPartitionColumn(),
          getIntervals()
      );
    }
  }

  protected List<String> extractSnapshotDataFiles(
      HiveCatalog catalog,
      TableIdentifier tableIdentifier,
      String partitionColumn,
      List<String> intervals
  )
  {
    TableScan tableScan = catalog.loadTable(tableIdentifier).newScan();
    List<DataFile> filteredDataFiles = new ArrayList<>();

    for (String interval : intervals) {
      Interval filterInterval = Interval.parse(interval);
      Long dateStart = (long) Literal.of(filterInterval.getStart().toString())
                                     .to(Types.TimestampType.withZone())
                                     .value();
      Long dateEnd = (long) Literal.of(filterInterval.getEnd().toString())
                                   .to(Types.TimestampType.withZone())
                                   .value();

      log.info("Adding expression for interval " + dateStart + " to " + dateEnd);
      CloseableIterable<FileScanTask> tasks = tableScan.filter(Expressions.and(
          Expressions.greaterThanOrEqual(
              partitionColumn,
              dateStart
          ),
          Expressions.lessThanOrEqual(
              partitionColumn,
              dateEnd
          )
      )).planFiles();

      Iterators.addAll(filteredDataFiles, CloseableIterable.transform(tasks, FileScanTask::file).iterator());
    }

    List<String> dataFilePaths = filteredDataFiles.stream()
                                                  .map(df -> df.path().toString())
                                                  .collect(Collectors.toList());

    return dataFilePaths;
  }

  @Override
  public String toString()
  {
    return "HiveInputSource{" +
           "tableName='" + tableName + '\'' +
           ", namespace='" + namespace + '\'' +
           ", partitionColumn='" + partitionColumn + '\'' +
           ", intervals='" + intervals + '\'' +
           '}';
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
  public String getPartitionColumn()
  {
    return partitionColumn;
  }

  @JsonProperty
  public List<String> getIntervals()
  {
    return intervals;
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    HiveInputChooser that = (HiveInputChooser) o;
    return Objects.equals(tableName, that.tableName) && Objects.equals(namespace, that.namespace) && Objects.equals(
        partitionColumn,
        that.partitionColumn
    ) && Objects.equals(intervals, that.intervals);
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(tableName, namespace, partitionColumn, intervals);
  }

  @Override
  public List<String> chooseFiles()
  {
    //TODO what should choosefiles do
    setupHdfsInputSource();
    return snapshotDataFiles;
  }
}