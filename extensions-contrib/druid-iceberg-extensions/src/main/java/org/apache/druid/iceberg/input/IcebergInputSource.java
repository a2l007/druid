package org.apache.druid.iceberg.input;

import com.fasterxml.jackson.annotation.JacksonInject;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterators;
import org.apache.druid.data.input.AbstractInputSource;
import org.apache.druid.data.input.InputFormat;
import org.apache.druid.data.input.InputSource;
import org.apache.druid.data.input.InputSplit;
import org.apache.druid.data.input.SplitHintSpec;
import org.apache.druid.data.input.impl.SplittableInputSource;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.expressions.Literal;
import org.apache.iceberg.hive.HiveCatalog;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.types.Types;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class IcebergInputSource extends AbstractInputSource implements SplittableInputSource<List<String>>
{
  @JsonProperty
  private final String tableName;

  @JsonProperty
  private final String namespace;

  @JsonProperty
  private final String partitionColumn;

  @JsonProperty
  private final List<String> intervals;

  @JsonProperty
  private IcebergCatalog icebergCatalog;

  @JsonProperty
  private SplittableInputSource inputSource;

  private static final Logger log = new Logger(IcebergInputSource.class);

  @JsonCreator
  public IcebergInputSource(
      @JsonProperty("tableName") String tableName,
      @JsonProperty("namespace") String namespace,
      @JsonProperty("partitionColumn") String partitionColumn,
      @JsonProperty("intervals") List<String> intervals,
      @JsonProperty("catalogType") IcebergCatalog icebergCatalog,
      @JsonProperty("inputSource") SplittableInputSource inputSource
  ) {
    this.tableName = Preconditions.checkNotNull(tableName, "tableName cannot be null");
    this.namespace = Preconditions.checkNotNull(namespace, "namespace cannot be null");
    this.partitionColumn = partitionColumn;
    if (partitionColumn != null) {
      Preconditions.checkNotNull(intervals, "Intervals cannot be null when partitionColumn is specified");
    }
    this.intervals = intervals;
    this.icebergCatalog = icebergCatalog;
    this.inputSource = inputSource;
    retrieveIcebergDatafiles();

  }

  @Override
  public boolean needsFormat()
  {
    return false;
  }

  @Override
  public Stream<InputSplit<List<String>>> createSplits(
      InputFormat inputFormat, @Nullable SplitHintSpec splitHintSpec
  ) throws IOException
  {
    return inputSource.createSplits(inputFormat, splitHintSpec);
  }

  @Override
  public int estimateNumSplits(InputFormat inputFormat, @Nullable SplitHintSpec splitHintSpec) throws IOException
  {
    return inputSource.estimateNumSplits(inputFormat, splitHintSpec);
  }

  @Override
  public InputSource withSplit(InputSplit<List<String>> inputSplit)
  {
    return inputSource.withSplit(inputSplit);
  }

  @Override
  public SplitHintSpec getSplitHintSpecOrDefault(@Nullable SplitHintSpec splitHintSpec)
  {
    return inputSource.getSplitHintSpecOrDefault(splitHintSpec);
  }

  public InputSource getInputSource()
  {
    return inputSource;
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

  protected void retrieveIcebergDatafiles()
  {
     /* UnifiedCatalogManager ucm = new UnifiedCatalogManager(
          catalogType,
          warehouseType,
          warehousePath,
          catalogUri,
          configuration
      );
      */

      Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
     // HiveCatalog catalog = ucm.setupCatalog();
     /* Namespace namespace = Namespace.of(getNamespace());

      List<TableIdentifier> tables = catalog.listTables(namespace);
      TableIdentifier tableIdentifier = tables.stream()
                                              .filter(tableId -> tableId.toString()
                                                                        .equals(getNamespace() + "." + getTableName()))
                                              .findFirst()
                                              .orElse(null);
      if (tableIdentifier == null) {
        throw new IAE(" Couldn't retrieve table identifier for '%s'", getTableName());
      }

      */
      List<String> snapshotDataFiles = icebergCatalog.extractSnapshotDataFiles(
          getNamespace(),
          getTableName(),
          getPartitionColumn(),
          getIntervals()
      );
    inputSource.appendChosenPaths(snapshotDataFiles);
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
    if (partitionColumn == null || partitionColumn.isEmpty()) {
      CloseableIterable<FileScanTask> tasks = tableScan.planFiles();
      Iterators.addAll(filteredDataFiles, CloseableIterable.transform(tasks, FileScanTask::file).iterator());
    } else {
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
    }

    List<String> dataFilePaths = filteredDataFiles.stream()
                                                  .map(df -> df.path().toString())
                                                  .collect(Collectors.toList());

    return dataFilePaths;
  }
}
