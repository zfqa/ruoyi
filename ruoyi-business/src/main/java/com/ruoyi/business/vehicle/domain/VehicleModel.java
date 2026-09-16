package com.ruoyi.business.vehicle.domain;

import java.util.Date;
import com.ruoyi.common.core.domain.BaseEntity;

public class VehicleModel extends BaseEntity
{
    private Long id, firstSeenTaskId, lastSeenTaskId;
    private String sourceCode, dongchediCarId, dongchediSeriesId, brandName, seriesName, modelName, manufacturer, officialGuidePrice, level, energyType;
    private String instrumentScreenSizeInch, instrumentScreenStyle, centerScreenSizeInch, centerScreenMaterial, passengerScreenSizeInch, rearScreenSizeInch;
    private String sourceUrl, fieldSourcesJson, fieldStatusJson, rawJson;
    private Date firstSeenTime, lastCrawledTime;
    public Long getId(){return id;} public void setId(Long v){id=v;} public Long getFirstSeenTaskId(){return firstSeenTaskId;} public void setFirstSeenTaskId(Long v){firstSeenTaskId=v;} public Long getLastSeenTaskId(){return lastSeenTaskId;} public void setLastSeenTaskId(Long v){lastSeenTaskId=v;}
    public String getSourceCode(){return sourceCode;} public void setSourceCode(String v){sourceCode=v;} public String getDongchediCarId(){return dongchediCarId;} public void setDongchediCarId(String v){dongchediCarId=v;} public String getDongchediSeriesId(){return dongchediSeriesId;} public void setDongchediSeriesId(String v){dongchediSeriesId=v;}
    public String getBrandName(){return brandName;} public void setBrandName(String v){brandName=v;} public String getSeriesName(){return seriesName;} public void setSeriesName(String v){seriesName=v;} public String getModelName(){return modelName;} public void setModelName(String v){modelName=v;} public String getManufacturer(){return manufacturer;} public void setManufacturer(String v){manufacturer=v;} public String getOfficialGuidePrice(){return officialGuidePrice;} public void setOfficialGuidePrice(String v){officialGuidePrice=v;} public String getLevel(){return level;} public void setLevel(String v){level=v;} public String getEnergyType(){return energyType;} public void setEnergyType(String v){energyType=v;}
    public String getInstrumentScreenSizeInch(){return instrumentScreenSizeInch;} public void setInstrumentScreenSizeInch(String v){instrumentScreenSizeInch=v;} public String getInstrumentScreenStyle(){return instrumentScreenStyle;} public void setInstrumentScreenStyle(String v){instrumentScreenStyle=v;} public String getCenterScreenSizeInch(){return centerScreenSizeInch;} public void setCenterScreenSizeInch(String v){centerScreenSizeInch=v;} public String getCenterScreenMaterial(){return centerScreenMaterial;} public void setCenterScreenMaterial(String v){centerScreenMaterial=v;} public String getPassengerScreenSizeInch(){return passengerScreenSizeInch;} public void setPassengerScreenSizeInch(String v){passengerScreenSizeInch=v;} public String getRearScreenSizeInch(){return rearScreenSizeInch;} public void setRearScreenSizeInch(String v){rearScreenSizeInch=v;}
    public String getSourceUrl(){return sourceUrl;} public void setSourceUrl(String v){sourceUrl=v;} public String getFieldSourcesJson(){return fieldSourcesJson;} public void setFieldSourcesJson(String v){fieldSourcesJson=v;} public String getFieldStatusJson(){return fieldStatusJson;} public void setFieldStatusJson(String v){fieldStatusJson=v;} public String getRawJson(){return rawJson;} public void setRawJson(String v){rawJson=v;}
    public Date getFirstSeenTime(){return firstSeenTime;} public void setFirstSeenTime(Date v){firstSeenTime=v;} public Date getLastCrawledTime(){return lastCrawledTime;} public void setLastCrawledTime(Date v){lastCrawledTime=v;}
}
