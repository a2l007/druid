package org.apache.druid.iceberg.common;

import com.google.common.collect.Iterators;
import org.apache.druid.iceberg.input.HiveInputChooser;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.catalog.Catalog;
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
import java.util.stream.Collectors;

public abstract class IcebergDriver
{
  private static final Logger log = new Logger(IcebergDriver.class);

  public abstract Catalog setupCatalog ();

  public void extractDataFiles(Catalog icebergCatalog, String catalogNamespace, String tableName)
  {
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        Namespace namespace = Namespace.of(catalogNamespace);

        List<TableIdentifier> tables = icebergCatalog.listTables(namespace);
        TableIdentifier tableIdentifier = tables.stream()
                                                .filter(tableId -> tableId.toString()
                                                                          .equals(catalogNamespace + "." + tableName))
                                                .findFirst()
                                                .orElse(null);
        if (tableIdentifier == null) {
          throw new IAE(" Couldn't retrieve table identifier for '%s'", tableName);
        }
//        snapshotDataFiles = extractSnapshotDataFiles(
//            icebergCatalog,
//            tableIdentifier,
//            getPartitionColumn(),
//            getIntervals()
//        );
      }

  protected List<String> extractSnapshotDataFiles(
      Catalog catalog,
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
    }
