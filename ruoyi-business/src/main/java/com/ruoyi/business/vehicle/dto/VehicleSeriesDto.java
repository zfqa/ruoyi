package com.ruoyi.business.vehicle.dto;
import com.alibaba.fastjson2.annotation.JSONField;

public class VehicleSeriesDto
{
    @JSONField(name = "series_id") private String seriesId;
    @JSONField(name = "series_name") private String seriesName;
    @JSONField(name = "series_status") private String seriesStatus;
    public String getSeriesId() { return seriesId; }
    public void setSeriesId(String seriesId) { this.seriesId = seriesId; }
    public String getSeriesName() { return seriesName; }
    public void setSeriesName(String seriesName) { this.seriesName = seriesName; }
    public String getSeriesStatus() { return seriesStatus; }
    public void setSeriesStatus(String seriesStatus) { this.seriesStatus = seriesStatus; }
}
