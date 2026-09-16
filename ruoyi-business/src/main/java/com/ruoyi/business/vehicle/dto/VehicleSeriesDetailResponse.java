package com.ruoyi.business.vehicle.dto;

import java.util.List;
import com.alibaba.fastjson2.annotation.JSONField;

public class VehicleSeriesDetailResponse
{
    @JSONField(name = "series_id") private String seriesId;
    @JSONField(name = "series_name") private String seriesName;
    @JSONField(name = "source_url") private String sourceUrl;
    @JSONField(name = "price_source_status") private String priceSourceStatus;
    @JSONField(name = "parser_warnings") private List<String> parserWarnings;
    private List<VehicleCarDto> cars;
    public String getSeriesId() { return seriesId; } public void setSeriesId(String v) { seriesId=v; }
    public String getSeriesName() { return seriesName; } public void setSeriesName(String v) { seriesName=v; }
    public String getSourceUrl() { return sourceUrl; } public void setSourceUrl(String v) { sourceUrl=v; }
    public String getPriceSourceStatus() { return priceSourceStatus; } public void setPriceSourceStatus(String v) { priceSourceStatus=v; }
    public List<String> getParserWarnings() { return parserWarnings; } public void setParserWarnings(List<String> v) { parserWarnings=v; }
    public List<VehicleCarDto> getCars() { return cars; } public void setCars(List<VehicleCarDto> v) { cars=v; }
}
