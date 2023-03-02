package org.apache.druid.iceberg.input;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public class CatalogConfig
{
  @JsonProperty
  private String catalogType;

  @JsonProperty
  private String warehouseType;

  @JsonProperty
  private String warehousePath;

  @JsonProperty
  private String catalogUri;

  @JsonProperty
  private Map<String,String> catalogProperties;

  @JsonCreator
  public CatalogConfig(
      @JsonProperty("catalogType") String catalogType,
      @JsonProperty("warehouseType") String warehouseType,
      @JsonProperty("warehousePath") String warehousePath,
      @JsonProperty("catalogUri") String catalogUri,
      @JsonProperty("catalogProperties")
          Map<String, String> catalogProperties
  )
  {
    this.catalogType = catalogType;
    this.warehouseType = warehouseType;
    this.warehousePath = warehousePath;
    this.catalogUri = catalogUri;
    this.catalogProperties = catalogProperties;
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

  public Map<String, String> getCatalogProperties()
  {
    return catalogProperties;
  }

}
