package com.ruoyi.business.analysis.vehicle.controller;

import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.business.analysis.vehicle.domain.VehicleAnalysis;
import com.ruoyi.business.analysis.vehicle.service.IVehicleAnalysisService;

/**
 * 整车市场分析-基础统计、排名与趋势 控制器
 * 
 * @author ruoyi
 */
@RestController
@RequestMapping("/business/analysis/vehicle")
public class VehicleAnalysisController extends BaseController
{
    @Autowired
    private IVehicleAnalysisService vehicleAnalysisService;

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:list')")
    @GetMapping("/list")
    public TableDataInfo list(VehicleAnalysis vehicleAnalysis)
    {
        if (!getLoginUser().getUser().isAdmin())
        {
            vehicleAnalysis.setCreateBy(getUsername());
        }
        startPage();
        List<VehicleAnalysis> list = vehicleAnalysisService.selectVehicleAnalysisList(vehicleAnalysis);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:export')")
    @Log(title = "整车市场分析-基础统计、排名与趋势", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, VehicleAnalysis vehicleAnalysis)
    {
        if (!getLoginUser().getUser().isAdmin()) vehicleAnalysis.setCreateBy(getUsername());
        List<VehicleAnalysis> list = vehicleAnalysisService.selectVehicleAnalysisList(vehicleAnalysis);
        ExcelUtil<VehicleAnalysis> util = new ExcelUtil<VehicleAnalysis>(VehicleAnalysis.class);
        util.exportExcel(response, list, "整车市场分析-基础统计、排名与趋势数据");
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        VehicleAnalysis record = vehicleAnalysisService.selectVehicleAnalysisById(id);
        if (!canAccess(record)) return error("任务不存在或无权访问");
        return success(record);
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:add')")
    @Log(title = "整车市场分析-基础统计、排名与趋势", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody VehicleAnalysis vehicleAnalysis)
    {
        return toAjax(vehicleAnalysisService.insertVehicleAnalysis(vehicleAnalysis));
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:edit')")
    @Log(title = "整车市场分析-基础统计、排名与趋势", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody VehicleAnalysis vehicleAnalysis)
    {
        if (!canAccess(vehicleAnalysisService.selectVehicleAnalysisById(vehicleAnalysis.getId())))
        {
            return error("任务不存在或无权修改");
        }
        vehicleAnalysis.setUpdateBy(getUsername());
        return toAjax(vehicleAnalysisService.updateVehicleAnalysis(vehicleAnalysis));
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:remove')")
    @Log(title = "整车市场分析-基础统计、排名与趋势", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        for (Long id : ids)
        {
            if (!canAccess(vehicleAnalysisService.selectVehicleAnalysisById(id)))
            {
                return error("任务不存在或无权删除");
            }
        }
        return toAjax(vehicleAnalysisService.deleteVehicleAnalysisByIds(ids));
    }

    private boolean canAccess(VehicleAnalysis record)
    {
        return record != null && (getLoginUser().getUser().isAdmin() || getUsername().equals(record.getCreateBy()));
    }
}
