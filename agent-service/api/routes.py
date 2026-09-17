from pathlib import Path
from tempfile import NamedTemporaryFile
from io import BytesIO
import logging
from contextlib import asynccontextmanager
import os
import secrets
import uuid
from datetime import datetime, timezone

from fastapi import FastAPI, File, Form, Header, HTTPException, Query, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import StreamingResponse
from news_service.config import load_sources
from news_service.crawler.browser_manager import BrowserManager
from news_service.models import NewsBatchDeleteRequest, NewsCrawlRequest, NewsDeleteBySourceRequest, NewsDeleteByUrlsRequest
from news_service.scheduler import get_news_scheduler
from news_service.service import NewsService
from data_service.service.ingestion_service import ingest_document
from llm.client import LlmRuntimeConfig
from api.schemas import VehicleExportFromDataRequest
from dongchedi_service.vehicle.service import VehicleSelectionService, VehicleServiceError
from dongchedi_service.vehicle.excel_export_service import VehicleExcelExportService
from dongchedi_service.vehicle.result_repository import SavedVehicleResultRepository
from api.vehicle_export import build_vehicle_export_response, require_export_records

logger = logging.getLogger(__name__)

OPENAPI_TAGS = [
    {"name": "系统", "description": "服务健康状态与运行基础信息。"},
    {"name": "懂车帝车辆", "description": "懂车帝车辆品牌、车系、详情与已保存结果导出。"},
    {"name": "新闻资讯", "description": "新闻采集、查询、任务报告和新闻源管理。"},
    {"name": "文档解析", "description": "PDF、TXT、PPTX 文件上传及结构化解析。"},
]


@asynccontextmanager
async def lifespan(_: FastAPI):
    """Start and safely stop the per-worker whitelist news scheduler."""
    scheduler = get_news_scheduler()
    # RuoYi is the production scheduler owner.  Keep this opt-in so an
    # omitted deployment variable never starts a second scheduler worker.
    scheduler_enabled = os.getenv("NEWS_SCHEDULER_ENABLED", "false").strip().lower() in {"1", "true", "yes", "on"}
    if scheduler_enabled:
        scheduler.start()
    else:
        logger.info("News scheduler disabled by NEWS_SCHEDULER_ENABLED")
    try:
        yield
    finally:
        if scheduler_enabled:
            scheduler.shutdown()
        BrowserManager.shutdown_shared()


app = FastAPI(
    title="汽车行业市场洞察 AI Agent",
    version="0.1.0",
    lifespan=lifespan,
    openapi_tags=OPENAPI_TAGS,
)
vehicle_selection_service = VehicleSelectionService()
saved_vehicle_result_repository = SavedVehicleResultRepository()
vehicle_excel_export_service = VehicleExcelExportService()


@app.get("/health", summary="健康检查", description="检查后端服务是否正常运行，供部署、运维和前端联调使用。", tags=["系统"])
async def health_check() -> dict[str, str]:
    return {"status": "ok"}


def _vehicle_error_response(exc: VehicleServiceError) -> HTTPException:
    status_code = {
        "unsupported_brand": 400,
        "series_not_found": 404,
        "auth_required": 401,
        "page_load_failed": 502,
        "parse_failed": 422,
        "upstream_error": 502,
    }.get(exc.code, 502)
    detail = {"code": exc.code, "message": str(exc)}
    if exc.diagnostic_id:
        detail["diagnostic_id"] = exc.diagnostic_id
    return HTTPException(status_code=status_code, detail=detail)


@app.get(
    "/api/vehicles/brands",
    summary="获取车辆品牌列表",
    description="返回当前支持查询的懂车帝车辆品牌白名单，用于后续按品牌查询车系。该接口不访问懂车帝上游服务。",
    tags=["懂车帝车辆"],
)
async def list_vehicle_brands(authorization: str | None = Header(default=None)) -> dict:
    """Return the fixed business whitelist; this action never accesses Dongchedi."""
    _require_internal_token(authorization)
    return {"items": vehicle_selection_service.list_brands()}


@app.get(
    "/api/vehicles/auth-status",
    summary="获取懂车帝登录状态文件",
    description="检查本地 Playwright 登录态文件是否存在且结构可用；不访问懂车帝上游，也不证明会话一定仍有效。",
    tags=["懂车帝车辆"],
)
async def vehicle_auth_status(authorization: str | None = Header(default=None)) -> dict:
    _require_internal_token(authorization)
    from dongchedi_service.auth import DongchediAuthManager

    manager = DongchediAuthManager()
    result = manager.state_file_status()
    return {
        "ready": manager.local_state_ready(),
        "status": result.status.value,
        "message": result.message,
        "state_path": str(manager.state_path),
    }


@app.get(
    "/api/vehicles/series",
    summary="获取车系列表",
    description="根据品牌查询可选择的懂车帝车系列表，返回车系标识、名称和状态。该接口会访问懂车帝上游服务，访问失败时可能返回 502。",
    tags=["懂车帝车辆"],
)
async def list_vehicle_series(
    brand: str = Query(..., min_length=1, max_length=50),
    authorization: str | None = Header(default=None),
) -> dict:
    """Dynamically discover selectable series for one user-selected brand."""
    _require_internal_token(authorization)
    try:
        result = await run_in_threadpool(vehicle_selection_service.list_series, brand)
    except VehicleServiceError as exc:
        raise _vehicle_error_response(exc) from exc
    return {
        "brand": result.brand,
        "items": [
            {
                "series_id": item.series_id,
                "series_name": item.series_name,
                "series_status": item.series_status,
            }
            for item in result.series
        ],
    }


@app.get(
    "/api/vehicles/series/{series_id}/details",
    summary="获取车系详细信息",
    description="根据车系标识获取车型与详细配置数据。可结合品牌或车系名称进行校验；该接口会访问懂车帝上游服务。",
    tags=["懂车帝车辆"],
)
async def get_vehicle_series_details(
    series_id: str,
    brand: str | None = Query(default=None, min_length=1, max_length=50),
    series_name: str | None = Query(default=None, min_length=1, max_length=100),
    authorization: str | None = Header(default=None),
) -> dict:
    """Parse only the series explicitly selected by the user."""
    _require_internal_token(authorization)
    try:
        return await run_in_threadpool(
            vehicle_selection_service.get_series_details,
            series_id,
            brand=brand,
            series_name=series_name,
        )
    except VehicleServiceError as exc:
        raise _vehicle_error_response(exc) from exc


@app.get(
    "/api/vehicles/export",
    summary="导出车辆数据",
    description="将已保存的懂车帝车辆结果导出为 Excel 文件，可按品牌或车系标识筛选。不触发新的上游车辆采集。",
    tags=["懂车帝车辆"],
)
async def export_saved_vehicles(
    brand: str | None = Query(default=None, min_length=1, max_length=100),
    series_id: str | None = Query(default=None, min_length=1, max_length=100),
    authorization: str | None = Header(default=None),
) -> StreamingResponse:
    """Download an xlsx built only from previously saved Dongchedi JSON results."""
    _require_internal_token(authorization)
    query_result = saved_vehicle_result_repository.list_saved_vehicles(
        brand=brand,
        series_id=series_id,
    )
    return build_vehicle_export_response(
        require_export_records(query_result, brand=brand, series_id=series_id),
        exporter=vehicle_excel_export_service,
    )


@app.post(
    "/api/vehicles/export/from-data",
    summary="按任务车型数据导出 Excel",
    description="仅接收 RuoYi 已保存的车型字段并生成 Excel；不读取本地 staging、不访问上游、不调用模型。",
    tags=["懂车帝车辆"],
)
async def export_vehicles_from_data(
    request: VehicleExportFromDataRequest,
    authorization: str | None = Header(default=None),
) -> StreamingResponse:
    """Export the exact task models supplied by the trusted Java bridge."""
    _require_internal_token(authorization)
    content = vehicle_excel_export_service.build_task_workbook_bytes(request.models)
    filename = f"懂车帝_{request.brand}_{request.series_name}.xlsx"
    from urllib.parse import quote

    return StreamingResponse(
        BytesIO(content),
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        headers={"Content-Disposition": f"attachment; filename*=UTF-8''{quote(filename)}"},
    )


@app.post(
    "/news/crawl",
    summary="手动抓取新闻",
    description="按当前新闻源配置手动触发新闻发现、详情解析、去重和保存。可指定来源与发布时间范围；可能访问外部网站并耗时较长。",
    tags=["新闻资讯"],
)
async def crawl_news(request: NewsCrawlRequest | None = None, authorization: str | None = Header(default=None)) -> dict:
    """Manually run configured static or Playwright-backed news crawling."""
    _require_news_internal_token(authorization)
    payload = request or NewsCrawlRequest()
    crawl_run_id = uuid.uuid4().hex
    started_at = datetime.now(timezone.utc).isoformat()
    run_articles: list[dict] = []
    def capture(article, operation: str) -> None:
        payload = article.model_dump() if hasattr(article, "model_dump") else dict(article)
        run_articles.append({**payload, "operation": {
            "success": "INSERTED", "updated": "UPDATED", "duplicate_url": "DUPLICATE_URL", "duplicate_content": "DUPLICATE_CONTENT"
        }.get(operation, operation.upper())})
    try:
        # ``DynamicCrawler`` uses Playwright's synchronous API.  Calling it in
        # this async route's event-loop thread makes Playwright reject the
        # operation before Chromium can start.  The crawl itself is blocking
        # (HTTP, SQLite and browser I/O), so run the complete existing service
        # flow in FastAPI's worker threadpool instead.
        results = await run_in_threadpool(
            NewsService().crawl,
            source_name=payload.source_name,
            force=payload.force,
            publish_time_start=payload.publish_time_start,
            publish_time_end=payload.publish_time_end,
            on_article=capture,
        )
        counters = {key: sum(getattr(result, key) for result in results) for key in ("pages_visited", "discovered", "requested", "success", "duplicate_url", "duplicate_content", "keyword_filtered", "publish_time_filtered", "listing_publish_time_filtered", "detail_publish_time_filtered", "publish_time_unknown", "fetch_failed", "parse_failed", "skipped_frequency")}
        page_stop_reason = results[0].page_stop_reason if len(results) == 1 else None
        result_complete = all(result.result_complete for result in results)
        detail_limit_reached_sources = sum(1 for result in results if result.detail_limit_reached)
        failed = counters["fetch_failed"] + counters["parse_failed"]
        duplicates = counters["duplicate_url"] + counters["duplicate_content"]
        # A URL duplicate is a completed candidate before a detail request,
        # therefore it belongs in the coverage denominator as well.
        coverage_total = counters["requested"] + duplicates
        success_rate = round((counters["success"] + duplicates) / coverage_total, 6) if coverage_total else 0.0
        return {
            "crawl_run_id": crawl_run_id,
            "source_name": payload.source_name,
            "started_at": started_at,
            "completed_at": datetime.now(timezone.utc).isoformat(),
            "stats": {"fetched": counters["requested"], "inserted": counters["success"], "updated": 0, "duplicate": duplicates, "filtered": counters["keyword_filtered"] + counters["publish_time_filtered"], "failed": failed},
            "articles": run_articles,
            "sources": len(results),
            "failed": failed,
            "success_rate": success_rate,
            "visited_pages": counters["pages_visited"],
            "discovered_articles": counters["discovered"],
            "requested_articles": counters["requested"],
            "success_count": counters["success"],
            "duplicate_url_count": counters["duplicate_url"],
            "duplicate_content_count": counters["duplicate_content"],
            "keyword_filtered_count": counters["keyword_filtered"],
            "publish_time_filtered_count": counters["publish_time_filtered"],
            "publish_time_unknown_count": counters["publish_time_unknown"],
            "fetch_failed_count": counters["fetch_failed"],
            "parse_failed_count": counters["parse_failed"],
            "page_stop_reason": page_stop_reason,
            "result_complete": result_complete,
            "detail_limit_reached_sources": detail_limit_reached_sources,
            **counters,
            "results": [result.model_dump() for result in results],
        }
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc


def _require_internal_token(authorization: str | None) -> None:
    """Guard Java-only internal APIs with the shared service token."""
    configured = os.getenv("AGENT_INTERNAL_TOKEN", "").strip()
    if not configured:
        raise HTTPException(status_code=503, detail="内部接口未配置")
    expected = f"Bearer {configured}"
    if not authorization or not secrets.compare_digest(authorization, expected):
        raise HTTPException(status_code=401, detail="内部接口未授权")


# Kept as an alias so the already validated news boundary keeps its existing
# call sites and behavior. Vehicle APIs use the same underlying guard.
_require_news_internal_token = _require_internal_token


@app.get("/news/scheduler/status", summary="获取新闻调度状态", description="返回新闻定时调度器的运行状态和已登记任务信息，不触发新闻采集。", tags=["新闻资讯"])
async def news_scheduler_status(authorization: str | None = Header(default=None)) -> dict:
    """Return only scheduler metadata; it never performs a crawl."""
    _require_news_internal_token(authorization)
    return get_news_scheduler().status()


@app.post("/news/scheduler/reload", summary="重新加载新闻调度", description="重新读取新闻源配置并同步定时任务，无需重启服务。", tags=["新闻资讯"])
async def reload_news_scheduler(authorization: str | None = Header(default=None)) -> dict:
    """Re-read YAML and reconcile per-source scheduled jobs without restart."""
    _require_news_internal_token(authorization)
    return get_news_scheduler().reload()


@app.get(
    "/news/list",
    summary="获取新闻列表",
    description="分页查询已保存新闻，可按来源、关键词、标题、发布时间和采集时间筛选。返回总数、分页信息和新闻记录。",
    tags=["新闻资讯"],
)
async def list_news(
    source_name: str | None = Query(default=None, max_length=100),
    source_site: str | None = Query(default=None, max_length=255),
    keyword: str | None = Query(default=None, max_length=100),
    publish_time_start: str | None = Query(default=None, max_length=100),
    publish_time_end: str | None = Query(default=None, max_length=100),
    crawl_time_start: str | None = Query(default=None, max_length=100),
    crawl_time_end: str | None = Query(default=None, max_length=100),
    title: str | None = Query(default=None, max_length=300),
    limit: int = Query(default=50, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
    authorization: str | None = Header(default=None),
) -> dict:
    _require_news_internal_token(authorization)
    total, records = NewsService().list_articles(
        source_name=source_name,
        source_site=source_site,
        keyword=keyword,
        publish_time_start=publish_time_start,
        publish_time_end=publish_time_end,
        crawl_time_start=crawl_time_start,
        crawl_time_end=crawl_time_end,
        title=title,
        limit=limit,
        offset=offset,
    )
    return {"total": total, "limit": limit, "offset": offset, "items": [record.model_dump() for record in records]}


@app.get(
    "/news/logs",
    summary="获取新闻采集日志",
    description="分页查询新闻采集日志，可按来源、状态、文章地址、栏目地址和采集时间筛选，用于排查采集过程。",
    tags=["新闻资讯"],
)
async def list_news_logs(
    source_name: str | None = Query(default=None, max_length=100),
    status: str | None = Query(default=None, max_length=100),
    article_url: str | None = Query(default=None, max_length=2000),
    page_url: str | None = Query(default=None, max_length=2000),
    crawl_time_start: str | None = Query(default=None, max_length=100),
    crawl_time_end: str | None = Query(default=None, max_length=100),
    limit: int = Query(default=50, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
    authorization: str | None = Header(default=None),
) -> dict:
    _require_news_internal_token(authorization)
    total, records = NewsService().list_logs(
        source_name=source_name,
        status=status,
        article_url=article_url,
        page_url=page_url,
        crawl_time_start=crawl_time_start,
        crawl_time_end=crawl_time_end,
        limit=limit,
        offset=offset,
    )
    return {"total": total, "limit": limit, "offset": offset, "items": [record.model_dump() for record in records]}


@app.get(
    "/news/report",
    summary="获取最新采集报告",
    description="返回最近一次新闻采集任务的统计结果及关联失败记录；可限定某个新闻来源。",
    tags=["新闻资讯"],
)
async def latest_news_report(
    source_name: str | None = Query(default=None, max_length=100),
    authorization: str | None = Header(default=None),
) -> dict:
    """Return the latest per-source crawl reconciliation report and its exceptions."""
    _require_news_internal_token(authorization)
    service = NewsService()
    report = service.latest_report(source_name=source_name)
    if report is None:
        raise HTTPException(status_code=404, detail="暂无采集任务统计")
    _, failures = service.list_failures(source_name=None, status=None, report_id=report.id, limit=500, offset=0)
    return {**report.model_dump(), "source": report.source_name, "failures": [item.model_dump() for item in failures]}


@app.get(
    "/news/report/history",
    summary="获取采集报告历史",
    description="分页查询历史新闻采集报告，可按来源和报告时间范围筛选。",
    tags=["新闻资讯"],
)
async def news_report_history(
    source_name: str | None = Query(default=None, max_length=100),
    start_time: str | None = Query(default=None, max_length=100),
    end_time: str | None = Query(default=None, max_length=100),
    limit: int = Query(default=50, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
    authorization: str | None = Header(default=None),
) -> dict:
    _require_news_internal_token(authorization)
    total, reports = NewsService().list_report_history(source_name=source_name, start_time=start_time, end_time=end_time, limit=limit, offset=offset)
    return {"total": total, "limit": limit, "offset": offset, "items": [item.model_dump() for item in reports]}


@app.get(
    "/news/failures",
    summary="获取新闻失败记录",
    description="分页查询新闻发现、请求或解析失败记录，可按来源、状态和采集报告筛选。",
    tags=["新闻资讯"],
)
async def news_failures(
    source_name: str | None = Query(default=None, max_length=100),
    status: str | None = Query(default=None, max_length=100),
    report_id: int | None = Query(default=None, ge=1),
    limit: int = Query(default=50, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
    authorization: str | None = Header(default=None),
) -> dict:
    _require_news_internal_token(authorization)
    total, failures = NewsService().list_failures(source_name=source_name, status=status, report_id=report_id, limit=limit, offset=offset)
    return {"total": total, "limit": limit, "offset": offset, "items": [item.model_dump() for item in failures]}


@app.get(
    "/news/sources",
    summary="获取新闻源配置",
    description="返回当前新闻源的启用状态、域名、栏目地址、关键词、采集频率和分页容量配置。",
    tags=["新闻资讯"],
)
async def list_news_sources(authorization: str | None = Header(default=None)) -> dict:
    _require_news_internal_token(authorization)
    # This catalogue is used by RuoYi to create collection tasks. Disabled
    # sources remain in YAML for configuration/history, but are not collectable.
    sources = [source for source in load_sources() if source.enabled]
    return {
        "sources": [
            {
                "name": source.name,
                "enabled": source.enabled,
                "domain": source.domain,
                "column_urls": [str(url) for url in source.column_urls],
                "keywords": source.keywords,
                "keyword_mode": source.keyword_mode,
                "crawl_frequency": source.crawl_frequency,
                "max_articles_per_run": source.max_articles_per_run,
                "max_pages": source.max_pages,
            }
            for source in sources
        ]
    }


@app.post(
    "/news/batch-delete",
    summary="批量删除新闻",
    description="根据请求中的新闻标识批量删除已保存新闻，返回请求数量、删除数量和未找到数量。",
    tags=["新闻资讯"],
)
async def batch_delete_news(request: NewsBatchDeleteRequest, authorization: str | None = Header(default=None)) -> dict:
    _require_news_internal_token(authorization)
    requested_ids = list(dict.fromkeys(request.ids))
    deleted = NewsService().delete_articles(requested_ids)
    return {"requested": len(request.ids), "deleted": deleted, "not_found": len(requested_ids) - deleted}


@app.post(
    "/news/delete-by-urls",
    summary="按规范化URL删除新闻",
    description="按 canonical_url 批量删除采集暂存库中的新闻，并清理对应抓取日志。用于任务列表清空后的 SQLite 同步。",
    tags=["新闻资讯"],
)
async def delete_news_by_urls(request: NewsDeleteByUrlsRequest, authorization: str | None = Header(default=None)) -> dict:
    _require_news_internal_token(authorization)
    requested = list(dict.fromkeys(url.strip() for url in request.canonical_urls if url and url.strip()))
    deleted = NewsService().delete_by_canonical_urls(requested)
    return {"requested": len(request.canonical_urls), "deleted": deleted, "not_found": max(0, len(requested) - deleted)}


@app.post(
    "/news/delete-by-source",
    summary="按来源删除新闻",
    description="按新闻来源站点删除已保存新闻，可结合发布时间范围限定删除范围。",
    tags=["新闻资讯"],
)
async def delete_news_by_source(request: NewsDeleteBySourceRequest, authorization: str | None = Header(default=None)) -> dict:
    _require_news_internal_token(authorization)
    deleted = NewsService().delete_by_source_site(
        source_site=request.source_site,
        publish_time_start=request.publish_time_start,
        publish_time_end=request.publish_time_end,
    )
    return {"source_site": request.source_site, "deleted": deleted}


@app.get("/news/{article_id}", summary="获取新闻详情", description="根据新闻标识返回一条已保存新闻的完整内容和来源信息。", tags=["新闻资讯"])
async def get_news(article_id: int, authorization: str | None = Header(default=None)) -> dict:
    _require_news_internal_token(authorization)
    article = NewsService().get_article(article_id)
    if article is None:
        raise HTTPException(status_code=404, detail="新闻不存在")
    return article.model_dump()


@app.delete("/news/{article_id}", summary="删除单条新闻", description="根据新闻标识删除一条已保存新闻；不存在时返回 404。", tags=["新闻资讯"])
async def delete_news(article_id: int, authorization: str | None = Header(default=None)) -> dict:
    _require_news_internal_token(authorization)
    if not NewsService().delete_article(article_id):
        raise HTTPException(status_code=404, detail="新闻不存在")
    return {"deleted": True, "id": article_id}


@app.post(
    "/data/upload",
    summary="上传并解析文档",
    description="上传 PDF、TXT 或 PPTX 文档并返回文本块、实体、表格、事件和结构化知识。PPTX 支持原生图表解析；PDF 图表不解析。解析和可选知识库索引可能耗时。",
    tags=["文档解析"],
)
async def upload_data_document(file: UploadFile = File(...), index_to_kb: bool = Form(False),
                               persist_review: bool = Form(False), llm_managed: bool = Form(False),
                               llm_api_url: str = Form(""), llm_model: str = Form(""),
                               llm_api_key: str = Form(""),
                               authorization: str | None = Header(default=None)) -> dict:
    """Upload a PDF/TXT/PPTX document and return provenance-aware structured data."""
    _require_internal_token(authorization)
    suffix = Path(file.filename or "").suffix.lower()
    if suffix not in {".pdf", ".txt", ".pptx"}:
        raise HTTPException(status_code=415, detail="仅支持 PDF、TXT 或 PPTX 文件")
    try:
        with NamedTemporaryFile(delete=False, suffix=suffix) as temporary:
            temporary.write(await file.read())
            temporary_path = Path(temporary.name)
        # Document semantics must use the RuoYi-owned runtime configuration.
        # Missing credentials deliberately trigger the source-grounded fallback;
        # this endpoint never falls back to a colleague machine's .env values.
        runtime = LlmRuntimeConfig(api_url=llm_api_url, model=llm_model, api_key=llm_api_key)
        result = ingest_document(temporary_path, index_to_kb=index_to_kb,
                                 persist_review=persist_review, source_file_name=file.filename,
                                 llm_runtime=runtime)
        result["file_name"] = file.filename
        result["filename"] = file.filename
        for chunk in result.get("chunk_data", []):
            chunk["source"]["file"] = file.filename
        for semantic_chunk in result.get("semantic_chunks", []):
            semantic_chunk["source"]["file"] = file.filename
        for knowledge in result.get("knowledge", []):
            knowledge["source"]["file"] = file.filename
        for entity in result.get("entities", []):
            for source in entity.get("sources", []):
                source["file"] = file.filename
        for event in result.get("structured_events", []):
            for source in event.get("sources", []):
                source["file"] = file.filename
        for table in result["tables"]:
            if table.get("source"):
                table["source"]["file"] = file.filename
        for chart in result.get("charts", []):
            if chart.get("source"):
                chart["source"]["file"] = file.filename
        return result
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except Exception as exc:
        logger.exception("[DOCUMENT_CHECK] upload parse error file=%s", file.filename)
        return {
            "status": "unsupported",
            "file_name": file.filename,
            "filename": file.filename,
            "file_type": suffix.lstrip(".") or "unknown",
            "supported": False,
            "warning": [],
            "reason": "parse_error",
            "message": "文档解析失败",
            "suggestion": "请确认文件未损坏且格式正确后重新上传",
            "detail": str(exc),
            "chunks": 0,
            "chunk_data": [],
            "entities": [],
            "tables": [],
            "structured_events": [],
            "semantic_chunks": [],
            "knowledge": [],
            "index_to_kb": index_to_kb,
            "persist_review": persist_review,
            "indexed": False,
            "indexed_count": 0,
        }
    finally:
        if "temporary_path" in locals() and temporary_path.exists():
            temporary_path.unlink()

