package com.ruoyi.business.data.excel.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.business.data.excel.domain.ExcelImport;
import com.ruoyi.business.data.excel.mapper.ExcelImportMapper;
import com.ruoyi.business.data.excel.service.ExcelFileStorage;
import com.ruoyi.business.data.excel.service.IExcelImportService;

/**
 * Excel/CSV导入与字段映射 服务实现
 * 
 * @author ruoyi
 */
@Service
public class ExcelImportServiceImpl implements IExcelImportService
{
    @Autowired
    private ExcelImportMapper excelImportMapper;

    @Autowired
    private ExcelFileStorage excelFileStorage;

    @Override
    public List<ExcelImport> selectExcelImportList(ExcelImport excelImport)
    {
        return excelImportMapper.selectExcelImportList(excelImport);
    }

    @Override
    public ExcelImport selectExcelImportById(Long id)
    {
        return excelImportMapper.selectExcelImportById(id);
    }

    @Override
    public ExcelImport selectExcelImportStatusById(Long id)
    {
        return excelImportMapper.selectExcelImportStatusById(id);
    }

    @Override
    public int insertExcelImport(ExcelImport excelImport)
    {
        return excelImportMapper.insertExcelImport(excelImport);
    }

    @Override
    public int updateExcelImport(ExcelImport excelImport)
    {
        return excelImportMapper.updateExcelImport(excelImport);
    }

    @Override
    public int deleteExcelImportById(Long id)
    {
        ExcelImport task = excelImportMapper.selectExcelImportById(id);
        int rows = excelImportMapper.deleteExcelImportById(id);
        if (rows > 0)
        {
            excelFileStorage.deleteTaskFiles(task);
        }
        return rows;
    }

    @Override
    public int deleteExcelImportByIds(Long[] ids)
    {
        List<ExcelImport> tasks = new java.util.ArrayList<>();
        for (Long id : ids)
        {
            ExcelImport task = excelImportMapper.selectExcelImportById(id);
            if (task != null)
            {
                tasks.add(task);
            }
        }
        int rows = excelImportMapper.deleteExcelImportByIds(ids);
        if (rows > 0)
        {
            tasks.forEach(excelFileStorage::deleteTaskFiles);
        }
        return rows;
    }
}
