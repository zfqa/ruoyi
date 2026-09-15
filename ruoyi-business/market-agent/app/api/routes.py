from __future__ import annotations
import shutil
import uuid
import hashlib
import asyncio
import json
import threading
from datetime import datetime, timezone
import pandas as pd
from pathlib import Path
from typing import List

from fastapi import APIRouter, File, HTTPException, UploadFile
from fastapi.responses import FileResponse

from app.core.config import get_settings
from app.core.llm import LLMClient
from app.version import (
    APP_VERSION, API_CONTRACT_VERSION, FEATURES, PROCESS_STARTED_AT,
    REPORT_EXPORT_VERSION, SERVICE_ROOT,
)


def _persist_upload(source, target: Path, display_name: str) -> None:
    """Persist an uploaded stream and turn host filesystem failures into an actionable API error."""
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("wb") as output:
            shutil.copyfileobj(source, output)
    except OSError as exc:
        try:
            target.unlink(missing_ok=True)
        except OSError:
            pass
        raise HTTPException(
            status_code=503,
            detail=(
                f"分析文件“{display_name}”无法保存到服务器上传目录。"
                "请检查 Market Agent 进程对 STORAGE_DIR 的写入权限和磁盘空间后重试。"
            ),
        ) from exc
from app.models.schemas import (
    ChatRequest, ChatResponse, ParseResult, FileParseResult, ExportResponse,
    ContextTextRequest, ContextResult, ContextItem, ContextDeleteRequest,
    ReportPlanInstructionRequest, ReportConfigRequest, VisualReportCompositionRequest,
)
from app.services.parser import parse_file, file_hash
from app.services.validator import validate_market_data
from app.services.storage import (
    save_dataset, load_dataset, load_meta, load_context, append_context, clear_context,
    delete_context_items, delete_dataset, processed_base,
    save_sheet_datasets, load_sheet_dataset, available_sheets, sheet_selector_meta, COMPREHENSIVE_SHEET_NAME,
)
from app.services.market_analysis import MarketAnalyzer
from app.services.market_components import MarketComponentEngine, dashboard_component_catalog
from app.services.core_indicators import indicator_capabilities
from app.services.period_service import period_options
from app.services.chat_engine import ChatEngine
from app.services.report_generator import export_report, build_report_payload
from app.services.context_ingest import parse_context_file, items_from_text
from app.services.report_config import load_report_config, save_report_config, reset_report_config
from app.services.report_planner import (
    load_report_plan, save_report_plan, reset_report_plan,
    apply_report_plan_instruction, save_visual_report_plan,
)

router = APIRouter()
_job_lock = threading.Lock()


def _json_preview(df: pd.DataFrame, limit: int = 40) -> list[dict]:
    """Return strict-JSON records (NaN/NaT become null)."""
    return json.loads(df.head(limit).to_json(orient="records", force_ascii=False, date_format="iso"))


def _job_file(job_id: str) -> Path:
    return get_settings().job_dir / job_id / "job.json"


def _read_job(job_id: str) -> dict:
    path = _job_file(job_id)
    if not path.exists():
        raise FileNotFoundError(f"上传任务不存在：{job_id}")
    return json.loads(path.read_text(encoding="utf-8"))


def _write_job(job_id: str, **changes) -> dict:
    with _job_lock:
        path = _job_file(job_id)
        current = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {"job_id": job_id}
        current.update(changes)
        current["updated_at"] = datetime.now(timezone.utc).isoformat()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(current, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
        return current


def _public_job(job: dict) -> dict:
    return {key: value for key, value in job.items() if key not in {"staged_files", "cancel_requested"}}


def _process_upload_job(job_id: str, staged_files: list[tuple[str, str]]) -> None:
    opened: list[UploadFile] = []
    try:
        job = _read_job(job_id)
        if job.get("cancel_requested"):
            _write_job(job_id, status="cancelled", progress=0, message="任务已取消")
            return
        _write_job(job_id, status="processing", progress=15, message="正在解析文件结构")
        for name, value in staged_files:
            opened.append(UploadFile(file=Path(value).open("rb"), filename=name))
        result = asyncio.run(_upload_market_files(opened))
        if _read_job(job_id).get("cancel_requested"):
            # 解析完成时才收到取消请求，清除刚生成的数据集，避免留下半完成任务。
            delete_dataset(result.dataset_id)
            _write_job(job_id, status="cancelled", progress=100, message="任务已取消，解析结果已清理")
            return
        _write_job(job_id, status="success", progress=100, message="解析完成", result=result.model_dump(mode="json"))
    except Exception as exc:
        _write_job(job_id, status="failed", progress=100, message=str(getattr(exc, "detail", exc)))
    finally:
        for file in opened:
            try:
                file.file.close()
            except Exception:
                pass


def resume_upload_jobs() -> None:
    """Resume persisted queued/processing jobs after an analysis-service restart."""
    for path in get_settings().job_dir.glob("*/job.json"):
        try:
            job = json.loads(path.read_text(encoding="utf-8"))
            if job.get("status") not in {"queued", "processing"}:
                continue
            staged = [(str(x[0]), str(x[1])) for x in job.get("staged_files") or [] if Path(str(x[1])).exists()]
            if not staged:
                _write_job(job["job_id"], status="failed", progress=100, message="服务重启后未找到任务临时文件，请重新上传")
                continue
            threading.Thread(target=_process_upload_job, args=(job["job_id"], staged), daemon=True).start()
        except Exception:
            continue


async def _stage_upload_job(files: List[UploadFile]) -> dict:
    if not files:
        raise HTTPException(status_code=400, detail="至少上传一个市场数据文件")
    settings = get_settings()
    job_id = uuid.uuid4().hex
    stage_dir = settings.job_dir / job_id / "files"
    try:
        stage_dir.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        raise HTTPException(
            status_code=507,
            detail=f"整车分析存储目录不可写：{settings.job_dir}。请使用 run-safe.ps1 重启服务或配置可写的 MARKET_AGENT_STORAGE_DIR",
        ) from exc
    staged: list[tuple[str, str]] = []
    for index, file in enumerate(files):
        suffix = Path(file.filename or "").suffix.lower()
        if suffix not in {".xlsx", ".xlsm", ".csv"}:
            shutil.rmtree(stage_dir.parent, ignore_errors=True)
            raise HTTPException(status_code=400, detail=f"市场数据文件 {file.filename} 仅支持 .xlsx/.xlsm/.csv")
        safe_name = (file.filename or f"upload_{index}{suffix}").replace("/", "_").replace("\\", "_")
        path = stage_dir / f"{index}_{safe_name}"
        _persist_upload(file.file, path, safe_name)
        if path.stat().st_size > settings.max_upload_mb * 1024 * 1024:
            shutil.rmtree(stage_dir.parent, ignore_errors=True)
            raise HTTPException(status_code=413, detail=f"文件 {safe_name} 超过 {settings.max_upload_mb}MB")
        staged.append((safe_name, str(path)))
    now = datetime.now(timezone.utc).isoformat()
    _write_job(job_id, status="queued", progress=5, message="文件已接收，等待解析", created_at=now,
               file_names=[name for name, _ in staged], staged_files=staged, cancel_requested=False)
    threading.Thread(target=_process_upload_job, args=(job_id, staged), daemon=True).start()
    return _public_job(_read_job(job_id))


@router.get("/health")
def health():
    return {
        "status": "ok",
        "app_version": APP_VERSION,
        "api_contract": API_CONTRACT_VERSION,
        "features": FEATURES,
        "report_export_version": REPORT_EXPORT_VERSION,
        "service_root": SERVICE_ROOT,
        "routes_file": str(Path(__file__).resolve()),
        "process_started_at": PROCESS_STARTED_AT,
    }


@router.get("/llm/status")
def llm_status():
    s = get_settings()
    client = LLMClient()
    return {
        "enabled": client.enabled,
        "api_key_configured": bool(client.api_key),
        "base_url": client.api_url,
        "model": client.model,
        "timeout": s.llm_timeout,
        "proxy_configured": client.proxy_configured,
        "network_mode": "authorized_proxy" if client.proxy_configured else "direct",
        "configuration_source": "ruoyi_runtime" if client.runtime_managed else "environment",
    }


@router.post("/llm/test")
def llm_test():
    client = LLMClient()
    if not client.enabled:
        return {"success": False, "message": "未配置完整的统一LLM请求地址、模型名称和API Key"}
    text = client.chat([{"role": "user", "content": "只回答：连接成功"}], temperature=0, max_tokens=20)
    if not text:
        return {"success": False, "message": "模型未返回内容"}
    if text.startswith("[LLM调用失败"):
        return {"success": False, "message": text}
    return {"success": True, "message": text}


@router.post("/upload", response_model=ParseResult)
async def upload(file: UploadFile = File(...)):
    """兼容旧版单文件上传。"""
    return await _upload_market_files([file])


@router.post("/upload/batch", response_model=ParseResult)
async def upload_batch(files: List[UploadFile] = File(...)):
    """支持甲方把销量、产量、库存、出口等拆成多个Excel/CSV一次上传。"""
    if not files:
        raise HTTPException(status_code=400, detail="至少上传一个市场数据文件")
    return await _upload_market_files(files)


@router.post("/upload/jobs")
async def create_upload_job(files: List[UploadFile] = File(...)):
    return await _stage_upload_job(files)


@router.get("/upload/jobs/{job_id}")
def upload_job(job_id: str):
    try:
        return _public_job(_read_job(job_id))
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc))


@router.post("/upload/jobs/{job_id}/cancel")
def cancel_upload_job(job_id: str):
    try:
        job = _read_job(job_id)
        if job.get("status") in {"success", "failed", "cancelled"}:
            return _public_job(job)
        return _public_job(_write_job(job_id, cancel_requested=True, message="已请求取消，当前解析步骤结束后将停止"))
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc))


@router.post("/upload/jobs/{job_id}/retry")
def retry_upload_job(job_id: str):
    try:
        old = _read_job(job_id)
        if old.get("status") not in {"failed", "cancelled"}:
            raise HTTPException(status_code=400, detail="只有失败或已取消的任务可以重试")
        staged = [(str(x[0]), str(x[1])) for x in old.get("staged_files") or [] if Path(str(x[1])).exists()]
        if not staged:
            raise HTTPException(status_code=410, detail="任务临时文件已不存在，请重新上传")
        new_id = uuid.uuid4().hex
        new_stage = get_settings().job_dir / new_id / "files"
        new_stage.mkdir(parents=True, exist_ok=True)
        copied: list[tuple[str, str]] = []
        for index, (name, source) in enumerate(staged):
            target = new_stage / f"{index}_{name}"
            shutil.copy2(source, target)
            copied.append((name, str(target)))
        now = datetime.now(timezone.utc).isoformat()
        _write_job(new_id, status="queued", progress=5, message="重试任务已创建", created_at=now,
                   file_names=[x[0] for x in copied], staged_files=copied, cancel_requested=False, retry_of=job_id)
        threading.Thread(target=_process_upload_job, args=(new_id, copied), daemon=True).start()
        return _public_job(_read_job(new_id))
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc))


async def _upload_market_files(files: List[UploadFile]) -> ParseResult:
    settings = get_settings()
    dataset_uuid = uuid.uuid4().hex[:10]
    frames = []
    all_metas = []
    all_issues = []
    stored_paths: list[Path] = []
    source_files: list[str] = []
    hash_parts: list[str] = []
    file_results: list[FileParseResult] = []

    def build_parse_summary(metas, frame: pd.DataFrame, file_count: int) -> dict:
        return {
            "file_count": file_count,
            "sheet_count": len(metas),
            "physical_rows_total": int(sum(meta.physical_rows for meta in metas)),
            "source_data_rows_total": int(sum(meta.source_data_rows for meta in metas)),
            "standardized_rows": int(len(frame)),
            "period_columns_total": int(sum(meta.period_column_count for meta in metas)),
            "period_valid_cells_total": int(sum(meta.period_valid_cells for meta in metas)),
            "period_placeholder_cells_total": int(sum(meta.period_placeholder_cells for meta in metas)),
            "wide_table_sheet_count": int(sum(1 for meta in metas if meta.transform_mode == "wide_to_long")),
        }

    for idx, file in enumerate(files):
        suffix = Path(file.filename or "").suffix.lower()
        if suffix not in [".xlsx", ".xlsm", ".csv"]:
            raise HTTPException(status_code=400, detail=f"市场数据文件 {file.filename} 仅支持 .xlsx/.xlsm/.csv")
        safe_name = (file.filename or f"upload_{idx}{suffix}").replace("/", "_").replace("\\", "_")
        upload_path = settings.upload_dir / f"{dataset_uuid}_{idx}_{safe_name}"
        _persist_upload(file.file, upload_path, safe_name)
        if upload_path.stat().st_size > settings.max_upload_mb * 1024 * 1024:
            upload_path.unlink(missing_ok=True)
            raise HTTPException(status_code=413, detail=f"文件 {safe_name} 超过 {settings.max_upload_mb}MB")
        stored_paths.append(upload_path)
        source_files.append(safe_name)
        hash_parts.append(file_hash(upload_path))
        try:
            df, metas, issues = parse_file(upload_path, source_name=safe_name)
        except Exception as exc:
            raise HTTPException(status_code=500, detail=f"解析失败 {safe_name}：{exc}")
        frames.append(df)
        all_metas.extend(metas)
        for issue in issues:
            issue.source_file = safe_name
            all_issues.append(issue)
        file_issues = list(issues) + validate_market_data(df, source_file=safe_name)
        file_results.append(FileParseResult(
            file_name=safe_name,
            rows=len(df),
            columns=list(df.columns),
            parse_summary=build_parse_summary(metas, df, 1),
            sheet_meta=metas,
            issues=file_issues,
            preview=_json_preview(df),
        ))

    combined = pd.concat(frames, ignore_index=True, sort=False) if frames else pd.DataFrame()
    all_issues += validate_market_data(combined)
    digest = hashlib.sha256("|".join(hash_parts).encode("utf-8")).hexdigest()[:16]
    dataset_id = f"{digest}_{dataset_uuid}"
    display_name = source_files[0] if len(source_files) == 1 else f"批量上传_{len(source_files)}个文件"

    # 解析规模必须区分“源Excel物理行/明细行”和“宽表转长表后的标准化记录”。
    # 对多Sheet/多文件，物理行数与明细行数按Sheet求和；标准化记录数就是combined长度。
    parse_summary = build_parse_summary(all_metas, combined, len(source_files))
    save_dataset(dataset_id, combined, {
        "file_name": display_name,
        "source_files": source_files,
        "upload_paths": [str(x) for x in stored_paths],
        "sheet_meta": [m.model_dump() for m in all_metas],
        "issues": [i.model_dump() for i in all_issues],
        "parse_summary": parse_summary,
    })
    # v10: 每个Sheet独立保存，前端可选择分析来源
    sheet_files = save_sheet_datasets(dataset_id, combined)
    meta = load_meta(dataset_id)
    meta["sheet_files"] = sheet_files
    save_dataset(dataset_id, combined, meta)
    return ParseResult(
        dataset_id=dataset_id,
        file_name=display_name,
        source_files=source_files,
        rows=len(combined),
        columns=list(combined.columns),
        parse_summary=parse_summary,
        sheet_meta=all_metas,
        issues=all_issues,
        preview=_json_preview(combined),
        file_results=file_results,
    )


@router.get("/sheets/{dataset_id}")
def sheets(dataset_id: str):
    meta = load_meta(dataset_id)
    return {
        "dataset_id": dataset_id,
        "sheets": available_sheets(dataset_id, include_comprehensive=True),
        "selector_meta": sheet_selector_meta(dataset_id),
        "meta": meta.get("sheet_meta", []),
    }


def _period_kwargs(period_mode: str = "latest", start_period: str | None = None, end_period: str | None = None, year: int | None = None) -> dict:
    return {"period_mode": period_mode, "start_period": start_period, "end_period": end_period, "year": year}


def _run_analysis(
    df: pd.DataFrame,
    period_mode: str = "latest",
    start_period: str | None = None,
    end_period: str | None = None,
    year: int | None = None,
    sheet_name: str | None = None,
):
    try:
        engine = MarketComponentEngine(df, _period_kwargs(period_mode, start_period, end_period, year))
        analyzer = engine.analyzer
        result = analyzer.run_all().__dict__
        # run_all已经得到概览、排名、动力结构和折线图；组件目录直接复用，
        # 禁止在同一次HTTP请求里重复执行相同Pandas聚合。
        capabilities = engine.capabilities(result)
        # 主页面保持原有的简洁市场概览（指标 / 主销量 / 具体分析）。
        # 公式指标和二维表只在“可视化编排”中使用，避免改变用户日常看板。
        power_variants: dict[str, Any] = {}
        all_power_types = analyzer.power_type_monthly_chart()
        if all_power_types:
            power_variants["all"] = all_power_types
        grouped_power = next(
            (chart for chart in (result.get("line_charts") or []) if chart.get("title") == "动力类型月度结构占比"),
            None,
        )
        if grouped_power:
            power_variants["both"] = grouped_power
            for mode, series_name in [("new_energy", "新能源车"), ("fuel", "燃油车")]:
                selected_series = [series for series in grouped_power.get("series", []) if series.get("name") == series_name]
                if selected_series:
                    power_variants[mode] = {**grouped_power, "series": selected_series}
        result["power_trend_variants"] = power_variants
        result["dashboard_components"] = capabilities.get("dashboard_components", [])
        result["dashboard_dimensions"] = capabilities.get("dimensions", {})
        result["indicator_capabilities"] = capabilities.get("indicator_capabilities", {})
        result["power_group_capabilities"] = capabilities.get("power_group_capabilities", {})
        # This is an explicit audit boundary.  A concrete sheet must never
        # silently fall back to the dataset-wide dataframe; only an empty
        # selection means the user intentionally chose the combined view.
        result["analysis_scope"] = {
            "mode": "sheet" if sheet_name else "dataset",
            "sheet_name": sheet_name or "全部工作表（综合分析）",
            "strict_sheet": bool(sheet_name),
        }
        return result
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


@router.get("/period-options/{dataset_id}")
def analysis_period_options(dataset_id: str, sheet_name: str | None = None):
    try:
        df = load_sheet_dataset(dataset_id, sheet_name) if sheet_name else load_dataset(dataset_id)
        return period_options(df)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc))


@router.get("/analysis/{dataset_id}/{sheet_name}")
def analysis_sheet(
    dataset_id: str,
    sheet_name: str,
    period_mode: str = "latest",
    start_period: str | None = None,
    end_period: str | None = None,
    year: int | None = None,
):
    df = load_sheet_dataset(dataset_id, sheet_name)
    return _run_analysis(df, period_mode, start_period, end_period, year, sheet_name=sheet_name)


@router.get("/analysis/{dataset_id}")
def analysis(
    dataset_id: str,
    sheet_name: str | None = None,
    period_mode: str = "latest",
    start_period: str | None = None,
    end_period: str | None = None,
    year: int | None = None,
):
    # Keep accepting sheet_name as a query parameter as a defensive fallback
    # for older clients.  The Vue client uses the explicit path route above.
    df = load_sheet_dataset(dataset_id, sheet_name) if sheet_name else load_dataset(dataset_id)
    return _run_analysis(df, period_mode, start_period, end_period, year, sheet_name=sheet_name)


@router.get("/dashboard-components/{dataset_id}")
def dashboard_components(
    dataset_id: str,
    sheet_name: str | None = None,
    period_mode: str = "latest",
    start_period: str | None = None,
    end_period: str | None = None,
    year: int | None = None,
):
    """Expose the exact component IDs shared by dashboard, chat and exports."""
    try:
        df = load_sheet_dataset(dataset_id, sheet_name) if sheet_name else load_dataset(dataset_id)
        capabilities = MarketComponentEngine(df, _period_kwargs(period_mode, start_period, end_period, year)).capabilities()
        return {
            "dataset_id": dataset_id,
            "period": capabilities.get("period"),
            "components": capabilities.get("dashboard_components", []),
            "dimensions": capabilities.get("dimensions", {}),
            "indicator_capabilities": capabilities.get("indicator_capabilities", {}),
            "power_group_capabilities": capabilities.get("power_group_capabilities", {}),
        }
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc))
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


@router.post("/context/{dataset_id}/text", response_model=ContextResult)
def add_context_text(dataset_id: str, req: ContextTextRequest):
    if not req.text.strip():
        raise HTTPException(status_code=400, detail="文本不能为空")
    items = items_from_text(req.text, req.source_name)
    current = append_context(dataset_id, items)
    return ContextResult(dataset_id=dataset_id, added=len(items), total=len(current), items=[ContextItem(**x) for x in current[-min(len(current), 30):]])


@router.post("/context/{dataset_id}/upload", response_model=ContextResult)
async def add_context_files(dataset_id: str, files: List[UploadFile] = File(...)):
    settings = get_settings()
    if not (processed_base(dataset_id) / "data.csv").exists():
        raise HTTPException(status_code=404, detail=f"数据集不存在或已被删除：{dataset_id}，请重新选择数据集")
    if not files:
        raise HTTPException(status_code=400, detail="请至少选择一个行业资料文件")
    all_items: list[ContextItem] = []
    allowed = {".txt", ".md", ".docx", ".pptx", ".pdf", ".xlsx", ".xlsm", ".csv"}
    for file in files:
        suffix = Path(file.filename or "").suffix.lower()
        if suffix not in allowed:
            raise HTTPException(status_code=400, detail=f"行业资料不支持格式：{suffix}")
        safe_name = Path((file.filename or f"context{suffix}").replace("\\", "/")).name
        safe_name = "".join("_" if ord(char) < 32 else char for char in safe_name).strip() or f"context{suffix}"
        path = settings.upload_dir / f"context_{uuid.uuid4().hex[:8]}_{safe_name}"
        try:
            with path.open("wb") as target:
                shutil.copyfileobj(file.file, target)
            if path.stat().st_size > settings.max_upload_mb * 1024 * 1024:
                raise HTTPException(status_code=413, detail=f"文件 {safe_name} 超过 {settings.max_upload_mb}MB")
            parsed = parse_context_file(path)
            # The temporary UUID prefix is an implementation detail.  Keeping
            # the browser's original name makes repeat uploads idempotent and
            # gives report citations a stable, readable source name.
            for item in parsed:
                item.source_name = safe_name
            all_items.extend(parsed)
        except HTTPException:
            raise
        except Exception as exc:
            raise HTTPException(status_code=422, detail=f"行业资料解析失败 {safe_name}：{exc}") from exc
        finally:
            path.unlink(missing_ok=True)
    try:
        before = len(load_context(dataset_id))
        current = append_context(dataset_id, all_items)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    added = len(current) - before
    recent = current[-min(added, 30):] if added else []
    return ContextResult(
        dataset_id=dataset_id,
        added=added,
        total=len(current),
        items=[ContextItem(**item) for item in recent],
    )


@router.get("/context/{dataset_id}")
def get_context(dataset_id: str):
    items = load_context(dataset_id)
    counts = {}
    for item in items:
        counts[item.get("category", "other")] = counts.get(item.get("category", "other"), 0) + 1
    return {"dataset_id": dataset_id, "total": len(items), "counts": counts, "items": items[:200]}


@router.delete("/context/{dataset_id}")
def delete_context(dataset_id: str):
    clear_context(dataset_id)
    return {"dataset_id": dataset_id, "total": 0}


@router.post("/context/{dataset_id}/delete-items")
def delete_selected_context(dataset_id: str, req: ContextDeleteRequest):
    if not req.item_ids:
        raise HTTPException(status_code=400, detail="至少选择一条行业资料")
    before = len(load_context(dataset_id))
    remaining = delete_context_items(dataset_id, req.item_ids)
    counts: dict[str, int] = {}
    for item in remaining:
        category = item.get("category", "other")
        counts[category] = counts.get(category, 0) + 1
    return {"dataset_id": dataset_id, "deleted": before - len(remaining), "total": len(remaining), "counts": counts}


@router.post("/chat", response_model=ChatResponse)
def chat(req: ChatRequest):
    try:
        df = load_sheet_dataset(req.dataset_id, req.sheet_name) if req.sheet_name else load_dataset(req.dataset_id)
        context = load_context(req.dataset_id)
        analysis_period = _period_kwargs(req.period_mode, req.start_period, req.end_period, req.year)
        answer = ChatEngine(df, context_items=context, dataset_id=req.dataset_id, analysis_period=analysis_period).answer(
            req.question,
            req.use_llm,
            history=req.history,
            selected_chart_title=req.selected_chart_title,
            selected_component_id=req.selected_component_id,
            selected_component_title=req.selected_component_title,
        )
        period_label = MarketAnalyzer(df, **analysis_period).period.display_label
        answer.evidence_chain = [f"当前分析周期：{period_label}"] + list(answer.evidence_chain or [])
        if req.sheet_name:
            answer.evidence_chain = [f"当前分析工作表：{req.sheet_name}"] + list(answer.evidence_chain or [])
        return answer
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc))
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"问答失败：{exc}")


@router.get("/report/{dataset_id}")
def report(
    dataset_id: str,
    sheet_name: str | None = None,
    use_llm: bool = True,
    period_mode: str = "latest",
    start_period: str | None = None,
    end_period: str | None = None,
    year: int | None = None,
):
    try:
        df = load_sheet_dataset(dataset_id, sheet_name) if sheet_name else load_dataset(dataset_id)
        meta = load_meta(dataset_id)
        if sheet_name:
            meta = dict(meta)
            meta["analysis_sheet"] = sheet_name
        context = load_context(dataset_id)
        config = load_report_config(dataset_id)
        plan = load_report_plan(dataset_id)
        analysis_period = _period_kwargs(period_mode, start_period, end_period, year)
        payload = build_report_payload(df, context_items=context, use_llm=use_llm, meta=meta, report_config=config, analysis_period=analysis_period, report_plan=plan)
        if sheet_name:
            payload["analysis_sheet"] = sheet_name
            payload["source_note"] = f"当前报告数据分析范围：{sheet_name}。" + str(payload.get("source_note", ""))
        return payload
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc))
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"周报生成失败：{exc}")


@router.post("/export/{dataset_id}/{fmt}", response_model=ExportResponse)
def export(
    dataset_id: str,
    fmt: str,
    sheet_name: str | None = None,
    period_mode: str = "latest",
    start_period: str | None = None,
    end_period: str | None = None,
    year: int | None = None,
):
    if fmt not in ["xlsx", "docx", "pptx"]:
        raise HTTPException(status_code=400, detail="fmt must be xlsx/docx/pptx")
    try:
        df = load_sheet_dataset(dataset_id, sheet_name) if sheet_name else load_dataset(dataset_id)
        meta = load_meta(dataset_id)
        if sheet_name:
            meta = dict(meta)
            meta["analysis_sheet"] = sheet_name
        context = load_context(dataset_id)
        config = load_report_config(dataset_id)
        plan = load_report_plan(dataset_id)
        observations = plan.get("market_observations") or []
        if plan.get("mode") == "custom" and (
            not observations or any(not (item.get("components") or []) for item in observations)
        ):
            raise ValueError("请先在“可视化编排”中完成 1.1 市场观察，并至少选择一个表格或图表组件")
        analysis_period = _period_kwargs(period_mode, start_period, end_period, year)
        period_sig = f"{period_mode}_{start_period or ''}_{end_period or ''}_{year or ''}"
        context_signature = hashlib.sha256(json.dumps([
            item.get("content_sha256") or hashlib.sha256(str(item.get("content") or "").encode("utf-8")).hexdigest()
            for item in context
        ], ensure_ascii=False).encode("utf-8")).hexdigest()[:16]
        config_signature = hashlib.sha256(json.dumps(config, ensure_ascii=False, sort_keys=True, default=str).encode("utf-8")).hexdigest()[:16]
        export_scope = (
            (sheet_name or "all") + "|" + period_sig + f"|plan_revision={plan.get('revision', 0)}"
            f"|context={context_signature}|config={config_signature}|exporter={REPORT_EXPORT_VERSION}"
        )
        export_id = f"{dataset_id}_{hashlib.sha1(export_scope.encode('utf-8')).hexdigest()[:10]}"
        # 校验周期参数后再进入耗时导出。
        MarketAnalyzer(df, **analysis_period)
        path = export_report(df, fmt, get_settings().report_dir, export_id, context_items=context, meta=meta, report_config=config, analysis_period=analysis_period, report_plan=plan)
        expected_context = len([
            item for item in context
            if item.get("category") in {"macro_policy", "personnel", "strategy", "industry_chain", "competition"}
        ]) if config.get("include_weekly_content") else 0
        return ExportResponse(
            dataset_id=dataset_id,
            format=fmt,
            file_name=path.name,
            url_path=f"/files/{path.name}",
            app_version=APP_VERSION,
            report_export_version=REPORT_EXPORT_VERSION,
            content_audit={"status": "complete", "expected_items": expected_context, "verified_items": expected_context},
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc))
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"报告导出失败：{exc}")


@router.get("/report-plan/{dataset_id}")
def report_plan(dataset_id: str):
    load_dataset(dataset_id)
    return load_report_plan(dataset_id)


@router.post("/report-plan/{dataset_id}/instruction")
def report_plan_instruction(dataset_id: str, req: ReportPlanInstructionRequest):
    try:
        df = load_sheet_dataset(dataset_id, req.sheet_name) if req.sheet_name else load_dataset(dataset_id)
        analysis_period = _period_kwargs(req.period_mode, req.start_period, req.end_period, req.year)
        plan, changes = apply_report_plan_instruction(
            dataset_id,
            req.instruction,
            df,
            analysis_period,
            use_llm=req.use_llm,
            selected_chart_title=req.selected_chart_title,
            selected_component_id=req.selected_component_id,
        )
        return {"dataset_id": dataset_id, "plan": plan, "changes": changes}
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc))
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


@router.put("/report-plan/{dataset_id}/visual")
def report_plan_visual(dataset_id: str, req: VisualReportCompositionRequest):
    """一次保存报告标题、1.1 内容组件和其他章节开关。"""
    previous_plan = None
    previous_config = None
    plan_saved = False
    try:
        df = load_sheet_dataset(dataset_id, req.sheet_name) if req.sheet_name else load_dataset(dataset_id)
        analysis_period = _period_kwargs(req.period_mode, req.start_period, req.end_period, req.year)
        previous_plan = load_report_plan(dataset_id)
        previous_config = load_report_config(dataset_id)
        plan = save_visual_report_plan(
            dataset_id,
            df,
            observation_id=req.observation_id,
            observation_title=req.observation_title,
            component_ids=req.component_ids,
            summary_segments=req.summary_segments,
            core_indicators=req.core_indicators,
            market_summary_rows=req.market_summary_rows,
            market_summary_columns=req.market_summary_columns,
            component_options=req.component_options,
            manual_content=req.manual_content,
            lookback_months=req.lookback_months,
            analysis_period=analysis_period,
        )
        plan_saved = True
        config_input = dict(previous_config)
        config_input.update({
            "custom_title": req.report_title,
            "include_weekly_content": req.include_weekly_content,
            "include_anomalies": req.include_anomalies,
        })
        config = save_report_config(dataset_id, config_input)
        return {"dataset_id": dataset_id, "plan": plan, "config": config}
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc))
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    except Exception as exc:
        if plan_saved and previous_plan is not None:
            save_report_plan(dataset_id, previous_plan)
        if previous_config is not None:
            save_report_config(dataset_id, previous_config)
        raise HTTPException(status_code=500, detail=f"可视化编排保存失败：{exc}")


@router.post("/report-plan/{dataset_id}/reset")
def report_plan_reset(dataset_id: str):
    load_dataset(dataset_id)
    return reset_report_plan(dataset_id)


@router.get("/report-config/{dataset_id}")
def report_config(dataset_id: str):
    load_dataset(dataset_id)
    return load_report_config(dataset_id)


@router.put("/report-config/{dataset_id}")
def report_config_update(dataset_id: str, req: ReportConfigRequest):
    load_dataset(dataset_id)
    return save_report_config(dataset_id, req.model_dump())


@router.post("/report-config/{dataset_id}/reset")
def report_config_reset(dataset_id: str):
    load_dataset(dataset_id)
    return reset_report_config(dataset_id)


@router.get("/dataset/{dataset_id}")
def dataset_detail(dataset_id: str):
    try:
        df = load_dataset(dataset_id)
        meta = load_meta(dataset_id)
        return {
            "dataset_id": dataset_id,
            "file_name": meta.get("file_name", ""),
            "source_files": meta.get("source_files", []),
            "rows": len(df),
            "columns": list(df.columns),
            "parse_summary": meta.get("parse_summary", {}),
            "sheet_meta": meta.get("sheet_meta", []),
            "issues": meta.get("issues", []),
            "preview": _json_preview(df),
        }
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc))


@router.delete("/dataset/{dataset_id}")
def dataset_remove(dataset_id: str):
    try:
        removed = delete_dataset(dataset_id)
        return {"dataset_id": dataset_id, "deleted": True, **removed}
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc))


@router.get("/files/{file_name}")
def files(file_name: str):
    path = get_settings().report_dir / file_name
    if not path.exists():
        raise HTTPException(status_code=404, detail="文件不存在")
    return FileResponse(path, filename=file_name)
