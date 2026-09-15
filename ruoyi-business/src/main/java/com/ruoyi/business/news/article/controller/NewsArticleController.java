package com.ruoyi.business.news.article.controller;
import java.util.List; import com.ruoyi.business.news.article.NewsArticle; import com.ruoyi.business.news.article.mapper.NewsArticleMapper; import com.ruoyi.common.core.controller.BaseController; import com.ruoyi.common.core.domain.AjaxResult; import com.ruoyi.common.core.page.TableDataInfo; import org.springframework.security.access.prepost.PreAuthorize; import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/business/news/article") public class NewsArticleController extends BaseController {
 private final NewsArticleMapper mapper; public NewsArticleController(NewsArticleMapper mapper){this.mapper=mapper;}
 @PreAuthorize("@ss.hasPermi('business:news:collect:list')") @GetMapping("/list") public TableDataInfo list(NewsArticle query){startPage(); List<NewsArticle> list=mapper.selectList(query);return getDataTable(list);}
 @PreAuthorize("@ss.hasPermi('business:news:collect:query')") @GetMapping("/{id}") public AjaxResult get(@PathVariable Long id){return success(mapper.selectById(id));}
}

