from pathlib import Path
import json
import os
import re
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from itertools import repeat

from data_service.extractor.entity_extractor import extract_entities_with_sources
from data_service.models.schemas import DocumentChunk, Entity, KnowledgeEvent, Metric, SemanticSource, SourceAnchor, StructuredEvent, StructuredKnowledge
from data_service.loader.pdf_loader import load_pdf_text
from data_service.loader.pptx_loader import load_pptx_text
from data_service.loader.text_loader import load_text
from data_service.parser.pdf_parser import parse_pdf_pages
from data_service.parser.table_parser import extract_pdf_tables
from data_service.parser.pptx_table_parser import extract_pptx_tables
from data_service.parser.pptx_chart_parser import extract_pptx_charts
from data_service.parser.text_structurer import structure_automotive_text
from data_service.chunker.semantic_chunker import SemanticChunker
from data_service.semantic.semantic_extractor import SemanticExtractor
from data_service.validator.document_validator import validate_document
from llm.client import LlmRuntimeConfig

import logging

logger = logging.getLogger(__name__)


DEFAULT_LLM_MAX_CONCURRENCY = 3
MAX_LLM_MAX_CONCURRENCY = 3
LLM_SLOW_SECONDS = 30
LLM_VERY_SLOW_SECONDS = 120


def _unsupported_response(file_path: Path, validation: object, *, index_to_kb: bool) -> dict:
    """Use one API-safe shape for every preflight rejection."""
    validation_data = validation.model_dump()
    return {
        "status": "unsupported",
        "file_name": file_path.name,
        "filename": file_path.name,
        "file_type": validation_data.get("document_type", file_path.suffix.lstrip(".")),
        "supported": False,
        "warning": validation_data.get("warnings", []),
        "reason": validation_data.get("reason"),
        "message": validation_data.get("message"),
        "suggestion": validation_data.get("suggestion"),
        "detail": validation_data.get("detail"),
        "validation": validation_data,
        "chunks": 0,
        "chunk_data": [],
        "entities": [],
        "tables": [],
        "charts": [],
        "structured_events": [],
        "semantic_chunks": [],
        "knowledge": [],
        "index_to_kb": index_to_kb,
        "indexed": False,
        "indexed_count": 0,
    }


def _loader_error_response(file_path: Path, exc: Exception, *, index_to_kb: bool) -> dict:
    logger.exception("[DOCUMENT_CHECK] file=%s type=%s result=unsupported reason=parse_error", file_path.name, file_path.suffix.lstrip("."))
    return {
        "status": "unsupported",
        "file_name": file_path.name,
        "filename": file_path.name,
        "file_type": file_path.suffix.lstrip(".") or "unknown",
        "supported": False,
        "warning": [],
        "reason": "parse_error",
        "message": "文档解析失败",
        "suggestion": "请确认文件未损坏且格式正确后重新上传",
        "detail": str(exc),
        "validation": {"supported": False, "document_type": file_path.suffix.lstrip(".") or "unknown", "reason": "parse_error", "message": "文档解析失败", "detail": str(exc), "warnings": [], "metadata": {}},
        "chunks": 0,
        "chunk_data": [],
        "entities": [],
        "tables": [],
        "charts": [],
        "structured_events": [],
        "semantic_chunks": [],
        "knowledge": [],
        "index_to_kb": index_to_kb,
        "indexed": False,
        "indexed_count": 0,
    }


def _event_key(event: StructuredEvent) -> tuple[str, str, tuple[str, ...], str | None]:
    return (
        event.event_type,
        re.sub(r"\s+", "", event.event or ""),
        tuple(event.companies or ([event.company] if event.company else [])),
        event.time,
    )


def _extract_events_with_sources(chunks: list[DocumentChunk]) -> list[StructuredEvent]:
    """Extract events per chunk and retain only their original text anchors."""
    aggregated: dict[tuple[str, str, tuple[str, ...], str | None], StructuredEvent] = {}
    source_keys: dict[tuple[str, str, tuple[str, ...], str | None], set[tuple[str, int | None, int | None, str]]] = {}
    for chunk in chunks:
        for event in structure_automotive_text(chunk.content):
            if not event.event or event.event not in chunk.content:
                continue
            key = _event_key(event)
            aggregate = aggregated.setdefault(key, event)
            seen = source_keys.setdefault(key, set())
            anchor = SourceAnchor(file=chunk.source.file, page=chunk.source.page, slide=chunk.source.slide, text=event.event)
            anchor_key = (anchor.file, anchor.page, anchor.slide, anchor.text)
            if anchor_key not in seen:
                aggregate.sources.append(anchor)
                seen.add(anchor_key)
    return list(aggregated.values())


def _semantic_chunk_stats(chunks: list) -> dict[str, int]:
    stats = {"total": len(chunks), "indexable": 0, "section": 0, "event": 0, "narrative": 0, "table": 0, "chart": 0}
    for chunk in chunks:
        stats[chunk.chunk_type] += 1
        stats["indexable"] += int(bool(chunk.metadata.get("indexable")))
    return stats


def _llm_max_concurrency() -> int:
    """Read the bounded per-document LLM concurrency without adding a settings system."""
    try:
        configured = int(os.getenv("LLM_MAX_CONCURRENCY", str(DEFAULT_LLM_MAX_CONCURRENCY)))
    except ValueError:
        configured = DEFAULT_LLM_MAX_CONCURRENCY
    return min(max(configured, 1), MAX_LLM_MAX_CONCURRENCY)


def _log_llm_slow(*, chunk_index: int, semantic_chunk: object, elapsed: float) -> None:
    source = semantic_chunk.source
    fields = {
        "index": chunk_index,
        "page": source.page_start,
        "slide": source.slide_start,
        "type": semantic_chunk.chunk_type,
        "chars": len(semantic_chunk.content),
        "elapsed": f"{elapsed:.2f}s",
    }
    if elapsed > LLM_VERY_SLOW_SECONDS:
        logger.warning("[LLM-VERY-SLOW] %s", " ".join(f"{key}={value}" for key, value in fields.items()))
    elif elapsed > LLM_SLOW_SECONDS:
        logger.warning("[LLM-SLOW] %s", " ".join(f"{key}={value}" for key, value in fields.items()))


def _extract_one_knowledge_chunk(indexed_chunk: tuple[int, object], file_path: Path,
                                 llm_runtime: LlmRuntimeConfig | None = None) -> dict:
    """Extract one chunk independently so a worker never shares a model client."""
    chunk_index, semantic_chunk = indexed_chunk
    source = semantic_chunk.source
    started_at = time.perf_counter()
    logger.info(
        "[LLM-START] index=%s type=%s source=%s page=%s slide=%s chars=%s start_time=%s",
        chunk_index,
        semantic_chunk.chunk_type,
        source.file,
        source.page_start,
        source.slide_start,
        len(semantic_chunk.content),
        datetime.now(timezone.utc).isoformat(),
    )
    try:
        result = SemanticExtractor(llm_runtime).extract(semantic_chunk).model_dump()
        elapsed = time.perf_counter() - started_at
        logger.info(
            "[LLM-END] index=%s type=%s source=%s page=%s slide=%s elapsed=%.2fs status=success",
            chunk_index,
            semantic_chunk.chunk_type,
            source.file,
            source.page_start,
            source.slide_start,
            elapsed,
        )
        _log_llm_slow(chunk_index=chunk_index, semantic_chunk=semantic_chunk, elapsed=elapsed)
        return result
    except Exception as exc:
        elapsed = time.perf_counter() - started_at
        logger.warning(
            "[LLM-END] index=%s type=%s source=%s page=%s slide=%s elapsed=%.2fs status=fallback error_type=%s",
            chunk_index,
            semantic_chunk.chunk_type,
            source.file,
            source.page_start,
            source.slide_start,
            elapsed,
            type(exc).__name__,
        )
        logger.warning(
            "LLM semantic extraction failed for %s; using source-grounded fallback: %s",
            file_path.name,
            exc,
        )
        _log_llm_slow(chunk_index=chunk_index, semantic_chunk=semantic_chunk, elapsed=elapsed)
        return SemanticExtractor.fallback_extract(semantic_chunk).model_dump()


def _extract_knowledge(semantic_chunks: list, file_path: Path,
                       llm_runtime: LlmRuntimeConfig | None = None) -> list[dict]:
    """Extract independent chunks concurrently while preserving their input order."""
    indexable_chunks = [chunk for chunk in semantic_chunks if chunk.metadata.get("indexable")]
    if not indexable_chunks:
        return []
    max_workers = min(_llm_max_concurrency(), len(indexable_chunks))
    batch_started_at = time.perf_counter()
    logger.info("[LLM-BATCH-START] chunks=%s max_concurrency=%s", len(indexable_chunks), max_workers)
    # executor.map returns results in input order, even when workers finish
    # out of order. That preserves source alignment for Review and RAG.
    try:
        with ThreadPoolExecutor(max_workers=max_workers, thread_name_prefix="semantic-llm") as executor:
            return list(executor.map(_extract_one_knowledge_chunk, enumerate(indexable_chunks),
                                     repeat(file_path), repeat(llm_runtime)))
    finally:
        logger.info(
            "[LLM-BATCH-END] chunks=%s elapsed=%.2fs",
            len(indexable_chunks),
            time.perf_counter() - batch_started_at,
        )


def _index_knowledge(knowledge: list[dict]) -> tuple[bool, int]:
    """Local vector persistence is intentionally disabled; RuoYi MySQL is the only knowledge store."""
    if knowledge:
        logger.warning("Ignoring index_to_kb request: unified RuoYi MySQL ingestion is required")
    return False, 0


def _page_source_text(chunk: DocumentChunk) -> str:
    """Keep the complete original page/slide text in a single review task."""
    parts = [chunk.content.strip()]
    # Native chart values are not text-box content. They are still source data
    # belonging to this slide, so expose them in the page-level review source.
    native_charts = chunk.metadata.get("native_charts")
    if native_charts:
        parts.append("原生图表数据:\n" + json.dumps(native_charts, ensure_ascii=False, indent=2))
    return "\n\n".join(part for part in parts if part).strip()


def _page_review_knowledge(chunks: list[DocumentChunk], knowledge: list[dict]) -> list[StructuredKnowledge]:
    """Aggregate fine-grained extraction results into one review task per page/slide.

    Semantic chunks remain the RAG unit. Only the human-review queue is
    page-based so a 21-slide PPTX produces 21 review tasks with full context.
    """
    parsed = [StructuredKnowledge.model_validate(item) for item in knowledge]
    by_location: dict[tuple[str, int | None, int | None], list[StructuredKnowledge]] = {}
    for item in parsed:
        source = item.source
        by_location.setdefault((source.file, source.page_start, source.slide_start), []).append(item)

    output: list[StructuredKnowledge] = []
    for chunk in chunks:
        location = (chunk.source.file, chunk.source.page, chunk.source.slide)
        related = by_location.get(location, [])
        source_text = _page_source_text(chunk)
        first_line = next((line.strip() for line in chunk.content.splitlines() if line.strip()), "")
        location_title = f"第{chunk.source.slide}页" if chunk.source.slide is not None else f"第{chunk.source.page}页"
        title = str(chunk.metadata.get("title") or first_line or location_title).strip()

        entity_map: dict[tuple[str, str], Entity] = {}
        metric_map: dict[tuple[str, str, str | None, str | None, str | None, str], Metric] = {}
        event_map: dict[tuple[str, str | None, str | None, str | None, str], KnowledgeEvent] = {}
        summaries: list[str] = []
        insights: list[str] = []
        knowledge_types: list[str] = []
        times: list[str] = []
        for item in related:
            knowledge_types.append(item.knowledge_type)
            if item.time:
                times.append(item.time)
            if item.summary and item.summary not in summaries:
                summaries.append(item.summary)
            for insight in item.insights:
                if insight not in insights:
                    insights.append(insight)
            for entity in item.entities:
                key = (entity.name, entity.type)
                existing = entity_map.get(key)
                if existing is None:
                    entity_map[key] = entity.model_copy(deep=True)
                    continue
                seen_sources = {(anchor.file, anchor.page, anchor.slide, anchor.text) for anchor in existing.sources}
                for anchor in entity.sources:
                    anchor_key = (anchor.file, anchor.page, anchor.slide, anchor.text)
                    if anchor_key not in seen_sources:
                        existing.sources.append(anchor)
                        seen_sources.add(anchor_key)
            for metric in item.metrics:
                key = (metric.name, metric.value, metric.unit, metric.time, metric.entity, metric.metric_type)
                existing = metric_map.get(key)
                if existing is None:
                    metric_map[key] = metric.model_copy(deep=True)
                    continue
                seen_sources = {(anchor.file, anchor.page, anchor.slide, anchor.text) for anchor in existing.sources}
                for anchor in metric.sources:
                    anchor_key = (anchor.file, anchor.page, anchor.slide, anchor.text)
                    if anchor_key not in seen_sources:
                        existing.sources.append(anchor)
                        seen_sources.add(anchor_key)
            for event in item.events:
                key = (event.event_type, event.subject, event.object, event.time, event.summary)
                existing_event = event_map.get(key)
                if existing_event is None:
                    event_map[key] = event.model_copy(deep=True)
                    continue
                seen_sources = {(anchor.file, anchor.page, anchor.slide, anchor.text) for anchor in existing_event.sources}
                for anchor in event.sources:
                    anchor_key = (anchor.file, anchor.page, anchor.slide, anchor.text)
                    if anchor_key not in seen_sources:
                        existing_event.sources.append(anchor)
                        seen_sources.add(anchor_key)

        distinct_types = list(dict.fromkeys(knowledge_types))
        distinct_times = list(dict.fromkeys(times))
        output.append(
            StructuredKnowledge(
                knowledge_type=distinct_types[0] if len(distinct_types) == 1 else "market_trend",
                title=title,
                time=distinct_times[0] if len(distinct_times) == 1 else None,
                entities=list(entity_map.values()),
                metrics=list(metric_map.values()),
                events=list(event_map.values()),
                summary="；".join(summaries) or title,
                insights=insights,
                source=SemanticSource(
                    file=chunk.source.file,
                    page_start=chunk.source.page,
                    page_end=chunk.source.page,
                    slide_start=chunk.source.slide,
                    slide_end=chunk.source.slide,
                    source_text=source_text or title,
                ),
                chunk_type="page",
                subject=None,
            )
        )
    return output


def _save_review_records(knowledge: list[dict], chunks: list[DocumentChunk]) -> tuple[list[str], int, int]:
    """Local SQLite review persistence is disabled; review and ACL state belong to RuoYi MySQL."""
    if chunks:
        logger.warning("Ignoring persist_review request: unified RuoYi MySQL ingestion is required")
    return [], 0, 0


def ingest_document(path: str | Path, *, index_to_kb: bool = False, persist_review: bool = False,
                    source_file_name: str | None = None,
                    llm_runtime: LlmRuntimeConfig | None = None) -> dict:
    file_path = Path(path)
    suffix = file_path.suffix.lower()
    validation = validate_document(file_path)
    logger.info(
        "[DOCUMENT_CHECK] file=%s type=%s result=%s reason=%s",
        file_path.name,
        validation.document_type,
        "supported" if validation.supported else "unsupported",
        validation.reason,
    )
    if not validation.supported:
        return _unsupported_response(file_path, validation, index_to_kb=index_to_kb)
    local_parse_started_at = time.perf_counter()
    try:
        if suffix == ".pdf":
            # pypdf is primary; pdfplumber fills pages pypdf cannot extract.
            primary = {chunk.page: chunk for chunk in load_pdf_text(file_path)}
            fallback = {chunk.page: chunk for chunk in parse_pdf_pages(file_path)}
            chunks = [primary.get(page) or fallback[page] for page in sorted(set(primary) | set(fallback))]
            table_warnings: list[dict] = []
            tables = extract_pdf_tables(file_path, warnings=table_warnings)
            charts = []
        elif suffix == ".pptx":
            chunks = load_pptx_text(file_path)
            tables = extract_pptx_tables(file_path)
            charts = extract_pptx_charts(file_path)
        elif suffix == ".txt":
            chunks, tables, charts = load_text(file_path), [], []
        else:
            raise ValueError("当前运行版本未找到该文件类型的解析器")
    except Exception as exc:
        return _loader_error_response(file_path, exc, index_to_kb=index_to_kb)
    local_parse_elapsed = time.perf_counter() - local_parse_started_at
    if suffix == ".pptx":
        logger.info(
            "[PPT-PARSE-END] elapsed=%.2fs document_chunks=%s tables=%s charts=%s",
            local_parse_elapsed,
            len(chunks),
            len(tables),
            len(charts),
        )
    else:
        logger.info(
            "[DOCUMENT-PARSE-END] type=%s elapsed=%.2fs document_chunks=%s tables=%s charts=%s",
            validation.document_type,
            local_parse_elapsed,
            len(chunks),
            len(tables),
            len(charts),
        )
    if suffix == ".pptx":
        logger.info("[PPT_TABLE_DEBUG] file=%s chunks=%s tables=%s charts=%s", file_path.name, len(chunks), len(tables), len(charts))
        for table_index, table in enumerate(tables, start=1):
            source = getattr(table, "source", None)
            logger.info(
                "[PPT_TABLE_DEBUG] table_index=%s slide=%s columns=%s rows=%s",
                table_index,
                getattr(source, "slide", None),
                getattr(table, "columns", []),
                len(getattr(table, "rows", []) or []),
            )
    else:
        charts = []
    if source_file_name:
        for chunk in chunks:
            chunk.source.file = source_file_name
        for table in tables:
            table.source.file = source_file_name
        for chart in charts:
            chart.source.file = source_file_name
    if charts:
        charts_by_slide: dict[int, list[dict]] = {}
        for chart in charts:
            if chart.source.slide is None:
                continue
            charts_by_slide.setdefault(chart.source.slide, []).append(chart.model_dump())
        for chunk in chunks:
            slide_number = chunk.source.slide or chunk.slide
            native_charts = charts_by_slide.get(slide_number or -1, [])
            if native_charts:
                # Keep the extracted chart structure visible beside ordinary
                # slide text. This is diagnostic/traceability metadata only;
                # SemanticChunker still consumes the typed `charts` list.
                chunk.metadata["native_charts"] = native_charts
                logger.info(
                    "[PPT_CHART_DEBUG] file=%s slide=%s charts=%s",
                    chunk.source.file,
                    slide_number,
                    len(native_charts),
                )
    entities = extract_entities_with_sources(chunks)
    events = _extract_events_with_sources(chunks)
    semantic_chunk_started_at = time.perf_counter()
    semantic_chunks = SemanticChunker().chunk(chunks, tables, charts)
    semantic_chunk_stats = _semantic_chunk_stats(semantic_chunks)
    logger.info(
        "[SEMANTIC-CHUNK-END] elapsed=%.2fs semantic_chunks=%s indexable_chunks=%s",
        time.perf_counter() - semantic_chunk_started_at,
        semantic_chunk_stats["total"],
        semantic_chunk_stats["indexable"],
    )
    knowledge = _extract_knowledge(semantic_chunks, file_path, llm_runtime)
    review_ids, new_review_count, duplicate_review_count = (
        _save_review_records(knowledge, chunks) if persist_review else ([], 0, 0)
    )
    indexed, indexed_count = _index_knowledge(knowledge) if index_to_kb else (False, 0)
    result_warnings = [warning.model_dump() for warning in validation.warnings]
    result_warnings.extend(table_warnings if suffix == ".pdf" else [])
    result = {"status": "success", "file_name": file_path.name, "filename": file_path.name, "file_type": validation.document_type, "supported": True, "warning": result_warnings, "reason": None, "chunks": len(chunks), "chunk_data": [chunk.model_dump() for chunk in chunks], "entities": [entity.model_dump() for entity in entities], "tables": [table.model_dump() for table in tables], "charts": [chart.model_dump() for chart in charts], "structured_events": [event.model_dump() for event in events], "semantic_chunks": [chunk.model_dump() for chunk in semantic_chunks], "semantic_chunk_stats": semantic_chunk_stats, "knowledge": knowledge, "review_scope": "page" if persist_review else "external", "review_ids": review_ids, "review_count": len(review_ids), "new_review_count": new_review_count, "duplicate_review_count": duplicate_review_count, "persist_review": persist_review, "index_to_kb": index_to_kb, "indexed": indexed, "indexed_count": indexed_count, "validation": validation.model_dump()}
    return result
