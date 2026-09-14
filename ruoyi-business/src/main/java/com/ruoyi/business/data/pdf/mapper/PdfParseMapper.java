package com.ruoyi.business.data.pdf.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.business.data.pdf.domain.PdfParse;

/**
 * 文本型PDF正文与规则表格解析 数据层
 * 
 * @author ruoyi
 */
public interface PdfParseMapper
{
    public List<PdfParse> selectPdfParseList(PdfParse pdfParse);

    public PdfParse selectPdfParseById(Long id);

    public int insertPdfParse(PdfParse pdfParse);

    public int updatePdfParse(PdfParse pdfParse);

    /** 更新文档解析工作流的结果字段，保留普通 CRUD 更新入口。 */
    public int updatePdfParseResult(PdfParse pdfParse);

    /** 仅将失败任务原子地重新置为处理中，防止重复点击提交多个后台任务。 */
    public int retryPdfParse(@Param("id") Long id, @Param("updateBy") String updateBy);

    /** 启动恢复：将上次进程遗留的处理中任务标记为失败，不自动重跑。 */
    public int markInterruptedProcessingTasks(@Param("errorMessage") String errorMessage, @Param("updateBy") String updateBy);

    /** 历史公开文档迁移至私有目录后更新受控逻辑引用。 */
    public int updateStoredFilePath(@Param("id") Long id, @Param("storedFilePath") String storedFilePath, @Param("updateBy") String updateBy);

    public List<PdfParse> selectLegacyProfileDocuments();

    /** 删除前必须以真实 KB 版本关联判断，不能依赖前端投影状态。 */
    public int countKnowledgeVersionsByPdfTaskId(Long taskId);

    public int deletePdfParseById(Long id);

    public int deletePdfParseByIds(Long[] ids);
}
