package com.ruoyi.business.data.text.controller;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.business.data.text.domain.TextStruct;
import com.ruoyi.business.data.text.service.ITextStructService;
import com.ruoyi.business.data.text.service.TextEntityExtractionService;

/**
 * 自由文本结构化 控制器
 * 
 * @author ruoyi
 */
@RestController
@RequestMapping("/business/data/text")
public class TextStructController extends BaseController
{
    @Autowired
    private ITextStructService textStructService;

    @Autowired
    private TextEntityExtractionService extractionService;

    @Autowired
    @Qualifier("textStructTaskExecutor")
    private ThreadPoolTaskExecutor textStructTaskExecutor;

    @PreAuthorize("@ss.hasPermi('business:data:text:add')")
    @Log(title = "自由文本LLM实体抽取", businessType = BusinessType.INSERT)
    @PostMapping("/extract")
    public AjaxResult extract(@RequestBody Map<String, Object> payload)
    {
        String sourceText = string(payload.get("sourceText"));
        if (sourceText.length() < 2) return AjaxResult.error("待解析文本至少2个字符");
        if (sourceText.length() > 30000) return AjaxResult.error("单次文本不能超过30000个字符");
        TextStruct task = new TextStruct();
        task.setTaskName(StringUtils.isEmpty(string(payload.get("taskName")))
            ? "文本实体抽取-" + System.currentTimeMillis() : StringUtils.substring(string(payload.get("taskName")), 0, 200));
        task.setSourceText(sourceText);
        task.setStatus("1");
        task.setCreateBy(getUsername());
        task.setRemark("LLM实体抽取任务排队中");
        textStructService.insertTextStruct(task);
        try
        {
            textStructTaskExecutor.execute(() -> executeExtraction(task.getId()));
        }
        catch (RuntimeException e)
        {
            task.setStatus("3"); task.setRemark("文本抽取队列已满，请稍后重试");
            textStructService.updateTextStruct(task);
            return AjaxResult.error(task.getRemark());
        }
        return success(textStructService.selectTextStructById(task.getId()));
    }

    private void executeExtraction(Long taskId)
    {
        TextStruct task = textStructService.selectTextStructById(taskId);
        if (task == null) return;
        try
        {
            task.setRemark("LLM正在识别企业、车型、销量、尺寸和技术路线（接口不提供实时进度）");
            task.setLlmModel(extractionService.currentModel());
            textStructService.updateTextStruct(task);
            JSONObject result = extractionService.extract(task.getSourceText());
            task.setResultJson(result.toJSONString());
            task.setEntityCount(result.getIntValue("entityCount"));
            task.setLlmModel(result.getString("model"));
            task.setStatus("2");
            task.setCompletedTime(LocalDateTime.now());
            task.setRemark("实体抽取与单位归一完成");
        }
        catch (Exception e)
        {
            task.setStatus("3");
            task.setCompletedTime(LocalDateTime.now());
            task.setRemark(StringUtils.substring(e.getMessage() == null ? "LLM实体抽取失败" : e.getMessage(), 0, 500));
        }
        task.setUpdateBy("system");
        textStructService.updateTextStruct(task);
    }

    private String string(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    @PreAuthorize("@ss.hasPermi('business:data:text:list')")
    @GetMapping("/list")
    public TableDataInfo list(TextStruct textStruct)
    {
        startPage();
        List<TextStruct> list = textStructService.selectTextStructList(textStruct);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('business:data:text:export')")
    @Log(title = "自由文本结构化", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, TextStruct textStruct)
    {
        List<TextStruct> list = textStructService.selectTextStructList(textStruct);
        ExcelUtil<TextStruct> util = new ExcelUtil<TextStruct>(TextStruct.class);
        util.exportExcel(response, list, "自由文本结构化数据");
    }

    @PreAuthorize("@ss.hasPermi('business:data:text:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(textStructService.selectTextStructById(id));
    }

    @PreAuthorize("@ss.hasPermi('business:data:text:add')")
    @Log(title = "自由文本结构化", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody TextStruct textStruct)
    {
        return toAjax(textStructService.insertTextStruct(textStruct));
    }

    @PreAuthorize("@ss.hasPermi('business:data:text:edit')")
    @Log(title = "自由文本结构化", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody TextStruct textStruct)
    {
        return toAjax(textStructService.updateTextStruct(textStruct));
    }

    @PreAuthorize("@ss.hasPermi('business:data:text:remove')")
    @Log(title = "自由文本结构化", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(textStructService.deleteTextStructByIds(ids));
    }
}
