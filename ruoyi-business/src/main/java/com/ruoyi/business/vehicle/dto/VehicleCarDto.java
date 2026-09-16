package com.ruoyi.business.vehicle.dto;

import java.util.Map;
import com.alibaba.fastjson2.annotation.JSONField;

/** Contract for one car record returned by Python /api/vehicles details. */
public class VehicleCarDto
{
    @JSONField(name = "car_id") private String carId;
    @JSONField(name = "model_name") private String modelName;
    private String manufacturer;
    @JSONField(name = "official_guide_price") private String officialGuidePrice;
    private String level;
    @JSONField(name = "energy_type") private String energyType;
    @JSONField(name = "instrument_screen_size_inch") private String instrumentScreenSizeInch;
    @JSONField(name = "instrument_screen_style") private String instrumentScreenStyle;
    @JSONField(name = "center_screen_size_inch") private String centerScreenSizeInch;
    @JSONField(name = "center_screen_material") private String centerScreenMaterial;
    @JSONField(name = "passenger_screen_size_inch") private String passengerScreenSizeInch;
    @JSONField(name = "rear_screen_size_inch") private String rearScreenSizeInch;
    private Map<String, String> sources;
    @JSONField(name = "field_status") private Map<String, String> fieldStatus;
    public String getCarId() { return carId; } public void setCarId(String v) { carId=v; }
    public String getModelName() { return modelName; } public void setModelName(String v) { modelName=v; }
    public String getManufacturer() { return manufacturer; } public void setManufacturer(String v) { manufacturer=v; }
    public String getOfficialGuidePrice() { return officialGuidePrice; } public void setOfficialGuidePrice(String v) { officialGuidePrice=v; }
    public String getLevel() { return level; } public void setLevel(String v) { level=v; }
    public String getEnergyType() { return energyType; } public void setEnergyType(String v) { energyType=v; }
    public String getInstrumentScreenSizeInch() { return instrumentScreenSizeInch; } public void setInstrumentScreenSizeInch(String v) { instrumentScreenSizeInch=v; }
    public String getInstrumentScreenStyle() { return instrumentScreenStyle; } public void setInstrumentScreenStyle(String v) { instrumentScreenStyle=v; }
    public String getCenterScreenSizeInch() { return centerScreenSizeInch; } public void setCenterScreenSizeInch(String v) { centerScreenSizeInch=v; }
    public String getCenterScreenMaterial() { return centerScreenMaterial; } public void setCenterScreenMaterial(String v) { centerScreenMaterial=v; }
    public String getPassengerScreenSizeInch() { return passengerScreenSizeInch; } public void setPassengerScreenSizeInch(String v) { passengerScreenSizeInch=v; }
    public String getRearScreenSizeInch() { return rearScreenSizeInch; } public void setRearScreenSizeInch(String v) { rearScreenSizeInch=v; }
    public Map<String, String> getSources() { return sources; } public void setSources(Map<String, String> v) { sources=v; }
    public Map<String, String> getFieldStatus() { return fieldStatus; } public void setFieldStatus(Map<String, String> v) { fieldStatus=v; }
}
