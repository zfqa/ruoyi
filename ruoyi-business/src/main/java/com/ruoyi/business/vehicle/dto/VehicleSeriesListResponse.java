package com.ruoyi.business.vehicle.dto;

import java.util.List;

public class VehicleSeriesListResponse
{
    private String brand;
    private List<VehicleSeriesDto> items;
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public List<VehicleSeriesDto> getItems() { return items; }
    public void setItems(List<VehicleSeriesDto> items) { this.items = items; }
}
