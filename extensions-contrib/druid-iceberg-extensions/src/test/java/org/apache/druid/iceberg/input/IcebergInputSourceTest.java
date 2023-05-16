package org.apache.druid.iceberg.input;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.druid.data.input.InputSplit;
import org.apache.druid.data.input.MaxSizeSplitHintSpec;
import org.apache.druid.data.input.impl.LocalInputSource;
import org.apache.druid.data.input.impl.LocalInputSourceAdapter;
import org.apache.druid.java.util.common.FileUtils;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.Files;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetReaders;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.types.Types;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class IcebergInputSourceTest
{
  IcebergCatalog testCatalog;

  Schema tableSchema = new Schema(
      Types.NestedField.required(1, "id", Types.StringType.get()),
      Types.NestedField.required(2, "name", Types.StringType.get())
  );
  Map<String, Object> tableData = ImmutableMap.of("id", "123988", "name", "Foo");

  @Test
  public void testInputSource() throws IOException
  {
    final File warehouseDir = FileUtils.createTempDir();
    testCatalog = new LocalCatalog(warehouseDir.getPath(), new HashMap<>());
    String namespace = "default";
    String tableName = "foosTable";
    TableIdentifier tableIdentifier = TableIdentifier.of(Namespace.of(namespace), tableName);

    createAndLoadTable(tableIdentifier);

    IcebergInputSource inputSource = new IcebergInputSource(tableName, namespace, null, testCatalog, new LocalInputSourceAdapter());
    Stream<InputSplit<List<String>>> splits = inputSource.createSplits(null, new MaxSizeSplitHintSpec(null, null));
    List<File> localInputSourceList = splits.map(inputSource::withSplit).map(inpSource -> (LocalInputSource)inpSource).map(LocalInputSource::getFiles).flatMap(List::stream).collect(Collectors.toList());

    Assert.assertEquals(1, localInputSourceList.size());
    CloseableIterable<Record> datafileReader = Parquet.read(Files.localInput(localInputSourceList.get(0))).project(tableSchema).createReaderFunc(fileSchema -> GenericParquetReaders.buildReader(tableSchema, fileSchema)).build();

    //List<List<String>> lsit = splits.stream().map(InputSplit::get).collect(Collectors.toList());
    //InputFile inputFile = icebergTable.io().newInputFile(lsit.get(0).get(0));
   // System.out.println(lsit.get(0).get(0));
    //CloseableIterable<Record> readerz = Parquet.read(Files.localInput(new File(lsit.get(0).get(0)))).createReaderFunc(fileSchema -> GenericParquetReaders.buildReader(schema, fileSchema)).build();
    for (Record record : datafileReader) {
      Assert.assertEquals(tableData.get("id"), record.get(0));
      Assert.assertEquals(tableData.get("name"), record.get(1));
    }
    dropTableFromCatalog(tableIdentifier);
  }

  private void createAndLoadTable(TableIdentifier tableIdentifier) throws IOException
  {
    //Setup iceberg table and schema
    Table icebergTableFromSchema = testCatalog.retrieveCatalog().createTable(tableIdentifier, tableSchema);

    //Generate an iceberg record and write it to a file
    GenericRecord record = GenericRecord.create(tableSchema);
    ImmutableList.Builder<GenericRecord> builder = ImmutableList.builder();

    builder.add(record.copy(tableData));
    String filepath = icebergTableFromSchema.location() + "/" + UUID.randomUUID();
    OutputFile file = icebergTableFromSchema.io().newOutputFile(filepath);
    DataWriter<GenericRecord> dataWriter =
        Parquet.writeData(file)
               .schema(tableSchema)
               .createWriterFunc(GenericParquetWriter::buildWriter)
               .overwrite()
               .withSpec(PartitionSpec.unpartitioned())
               .build();

    try {
      for (GenericRecord genRecord : builder.build()) {
        dataWriter.write(genRecord);
      }
    }
    finally {
      dataWriter.close();
    }
    DataFile dataFile = dataWriter.toDataFile();

    //Add the data file to the iceberg table
    icebergTableFromSchema.newAppend().appendFile(dataFile).commit();

//    CloseableIterable<Record> result = IcebergGenerics.read(icebergTableFromSchema).build();
//    for (Record r : result) {
//      System.out.println(r);
//    }
  }
  
  private void dropTableFromCatalog (TableIdentifier tableIdentifier)
  {
    testCatalog.retrieveCatalog().dropTable(tableIdentifier);
  }

}
