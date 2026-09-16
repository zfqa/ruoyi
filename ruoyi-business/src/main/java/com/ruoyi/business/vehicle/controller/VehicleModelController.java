package com.ruoyi.business.vehicle.controller;

import java.util.List;
import com.ruoyi.business.vehicle.domain.VehicleModel;
import com.ruoyi.business.vehicle.mapper.VehicleModelMapper;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/business/vehicle/model")
public class VehicleModelController extends BaseController
{
    private final VehicleModelMapper mapper;
    public VehicleModelController(VehicleModelMapper mapper) { this.mapper=mapper; }
    @PreAuthorize("@ss.hasPermi('business:vehicle:model:list')") @GetMapping("/list")
    public TableDataInfo list(VehicleModel query) { startPage(); List<VehicleModel> list=mapper.selectList(query); return getDataTable(list); }
    @PreAuthorize("@ss.hasPermi('business:vehicle:model:query')") @GetMapping("/{id}")
    public AjaxResult get(@PathVariable Long id) { return success(mapper.selectById(id)); }
}
