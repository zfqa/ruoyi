package com.ruoyi.business.data.excel.mapper;

import java.util.List;
import com.ruoyi.business.data.excel.domain.ExcelImport;

/**
 * Excel/CSV导入与字段映射 数据层
 * 
 * @author ruoyi
 */
public interface ExcelImportMapper
{
    public List<ExcelImport> selectExcelImportList(ExcelImport excelImport);

    public ExcelImport selectExcelImportById(Long id);

    public ExcelImport selectExcelImportStatusById(Long id);

    public int insertExcelImport(ExcelImport excelImport);

    public int updateExcelImport(ExcelImport excelImport);

    public int deleteExcelImportById(Long id);

    public int deleteExcelImportByIds(Long[] ids);
}
