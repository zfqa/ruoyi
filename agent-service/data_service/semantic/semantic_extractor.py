from __future__ import annotations

import json
import logging
import re
from typing import Any

from langchain_core.messages import HumanMessage, SystemMessage
from pydantic import ValidationError

from data_service.extractor.entity_extractor import extract_entities, find_entity_context
from data_service.models.schemas import Entity, KnowledgeEvent, Metric, SemanticChunk, SourceAnchor, StructuredKnowledge
from data_service.normalizer.company_aliases import aliases_for_company, normalize_company_name
from data_service.parser.text_structurer import structure_automotive_text
from data_service.semantic.prompts import (
    CHART_SEMANTIC_SYSTEM_PROMPT,
    CHART_SEMANTIC_USER_PROMPT,
    SEMANTIC_SYSTEM_PROMPT,
    SEMANTIC_USER_PROMPT,
    TABLE_SEMANTIC_SYSTEM_PROMPT,
    TABLE_SEMANTIC_USER_PROMPT,
)
from llm.client import LlmRuntimeConfig, get_chat_model

logger = logging.getLogger(__name__)

_COMPANY_EXCLUSIONS = {"汽车市场", "新能源汽车", "智能驾驶", "十五五", "行业政策", "市场监管总局", "欧盟", "中国", "美国", "南科5厂", "mini led", "h200"}
_EVENT_TYPE_MAP = {
    "投资并购": "acquisition", "产品发布": "product_launch", "产能/生产": "capacity_expansion",
    "战略合作": "cooperation", "诉讼/知识产权": "litigation", "人事任命": "personnel_change",
    "政策动态": "policy", "技术动态": "technology_release", "市场动态": "market_change",
    "销量动态": "sales_release",
}


class SemanticExtractor:
    """Converts a context-preserving semantic chunk into domain knowledge."""

    _SUBJECT_BLACKLIST = {
        "十五五", "战略", "调整", "布局", "合作", "增长", "市场", "政策",
        "robotaxi", "新能源", "价格", "技术", "行业政策",
    }

    def __init__(self, runtime: LlmRuntimeConfig | None = None) -> None:
        self._model = get_chat_model(runtime)

    @staticmethod
    def _json_object(content: Any) -> dict[str, Any]:
        text = str(content).strip()
        if text.startswith("```"):
            text = text.split("\n", 1)[-1].rsplit("```", 1)[0].strip()
        start, end = text.find("{"), text.rfind("}")
        if start < 0 or end < start:
            raise ValueError("模型未返回 JSON 对象")
        payload = json.loads(text[start : end + 1])
        if not isinstance(payload, dict):
            raise ValueError("模型返回内容不是 JSON 对象")
        return payload

    @staticmethod
    def _safe_table_metrics(metrics: Any, chunk: SemanticChunk) -> list[dict[str, Any]]:
        """Reject unbound/generic table metrics rather than storing ambiguous numbers."""
        if not isinstance(metrics, list):
            return []
        generic_names = {"同比", "同比增长率", "增长率", "占比", "市场份额", "渗透率", "环比", "指标"}
        row_subjects = set(chunk.metadata.get("row_subjects") or []) or {
            line.split("|", 1)[0].strip()
            for line in chunk.content.splitlines()[1:]
            if "|" in line and line.split("|", 1)[0].strip()
        }
        output: list[dict[str, str]] = []
        for item in metrics:
            if not isinstance(item, dict):
                continue
            name, value = str(item.get("name") or "").strip(), str(item.get("value") or "").strip()
            entity = str(item.get("entity") or "").strip()
            if not name or not value or name in generic_names or not entity:
                continue
            if row_subjects and not any(subject == entity or subject in entity or entity in subject for subject in row_subjects):
                continue
            output.append({
                "metric_type": str(item.get("metric_type") or "other").strip() or "other",
                "name": name,
                "value": value,
                "unit": str(item.get("unit") or "").strip() or None,
                "time": str(item.get("time") or "").strip() or None,
                "entity": entity,
                "product": str(item.get("product") or "").strip() or None,
                "change_type": str(item.get("change_type") or "").strip() or None,
            })
        return output

    @staticmethod
    def _subject_from_content(content: str) -> str | None:
        """Resolve explicit public actors before asking the model to infer one."""
        if re.search(r"市场监管总局|市监总局", content):
            return "市场监管总局"
        if re.search(r"(?:近半|多地|各地|多个|\d+个)?省市.*?(?:发布|出台)|重庆、山东、陕西、四川", content):
            return "地方政府"
        return None

    @classmethod
    def _clean_subject(cls, subject: Any) -> str | None:
        """Reject policy/strategy concepts; only a concrete actor may be a subject."""
        value = str(subject or "").strip()
        if not value or value.lower() in {"null", "none", "n/a", "未知"}:
            return None
        normalized = re.sub(r"\s+", "", value).lower()
        if normalized in cls._SUBJECT_BLACKLIST or normalized.startswith(("十五五", "robotaxi")):
            return None
        return value

    def _event_subject(self, chunk: SemanticChunk) -> str | None:
        """Keep a source-derived event subject without issuing another LLM request."""
        candidate = self._clean_subject(chunk.subject)
        if candidate:
            return candidate
        return self._subject_from_content(chunk.content)

    @staticmethod
    def _anchor(chunk: SemanticChunk, text: str) -> SourceAnchor:
        return SourceAnchor(file=chunk.source.file, page=chunk.source.page_start, slide=chunk.source.slide_start, text=text)

    @staticmethod
    def _ground_entities(entities: Any, chunk: SemanticChunk) -> list[Entity]:
        """Attach only source-derived anchors to every knowledge entity."""
        if not isinstance(entities, list):
            return []
        grounded: list[Entity] = []
        seen: set[tuple[str, str]] = set()
        source_content = chunk.source.source_text or chunk.content
        for item in entities:
            try:
                entity = Entity.model_validate(item)
            except ValidationError:
                continue
            if entity.type == "model":
                entity.type = "vehicle_model"
            if entity.type not in {"company", "vehicle_model"}:
                continue
            raw_name = entity.original_name or entity.name
            if entity.type == "company":
                if raw_name.strip().lower() in _COMPANY_EXCLUSIONS:
                    continue
                canonical, original = normalize_company_name(raw_name)
                if not canonical:
                    continue
                entity.name, entity.original_name = canonical, original
            elif not entity.original_name:
                entity.original_name = entity.name
            key = (entity.name, entity.type)
            if key in seen:
                continue
            contexts = find_entity_context(chunk.content, entity.name, entity.original_name)
            if entity.type == "company" and not contexts:
                for alias in aliases_for_company(entity.name):
                    alias_contexts = find_entity_context(chunk.content, alias, alias)
                    if alias_contexts:
                        contexts.extend(alias_contexts)
                        entity.original_name = alias
            if not contexts:
                contexts = find_entity_context(source_content, entity.name, entity.original_name)
            # Do not keep an LLM-only entity that cannot be found in the
            # current chunk or its source anchor.
            if not contexts:
                logger.warning("Dropping ungrounded entity name=%s type=%s source=%s", entity.name, entity.type, chunk.source.file)
                continue
            anchors = [SemanticExtractor._anchor(chunk, context) for context in dict.fromkeys(contexts)]
            grounded.append(entity.model_copy(update={"sources": anchors}))
            seen.add(key)
        return grounded

    @classmethod
    def _ground_metrics(cls, metrics: Any, chunk: SemanticChunk) -> list[Metric]:
        if not isinstance(metrics, list):
            return []
        output: list[Metric] = []
        for item in metrics:
            try:
                metric = Metric.model_validate(item)
            except ValidationError:
                continue
            metric = cls._normalize_metric(metric)
            if metric.metric_type != "other" and not metric.entity:
                logger.warning("Dropping unbound metric name=%s source=%s", metric.name, chunk.source.file)
                continue
            source_content = chunk.source.source_text or chunk.content
            terms = [term for term in (metric.entity, metric.value, metric.name) if term]
            contexts = [line.strip() for line in re.split(r"[\n。；;]", source_content) if line.strip() and any(term in line for term in terms)]
            if not contexts:
                contexts = [source_content.strip()] if source_content.strip() else []
            output.append(metric.model_copy(update={"sources": [cls._anchor(chunk, text) for text in dict.fromkeys(contexts[:3])] }))
        return output

    @staticmethod
    def _normalize_metric(metric: Metric) -> Metric:
        """Preserve source values while separating common business units."""
        text = f"{metric.name} {metric.product or ''}".lower()
        if metric.metric_type == "other":
            if any(term in text for term in ("营收", "营业收入", "净利润", "毛利", "现金流", "研发", "资产", "负债", "亏损")):
                metric.metric_type = "financial"
            elif any(term in text for term in ("出货", "发货")):
                metric.metric_type = "shipment"
            elif "销量" in text:
                metric.metric_type = "sales"
            elif "产量" in text:
                metric.metric_type = "production"
            elif "出口" in text:
                metric.metric_type = "export"
            elif "库存" in text:
                metric.metric_type = "inventory"
            elif any(term in text for term in ("市场份额", "占有率")):
                metric.metric_type = "market_share"
        if metric.change_type is None:
            if "同比" in metric.name:
                metric.change_type = "yoy"
            elif "环比" in metric.name:
                metric.change_type = "mom"
            elif any(term in metric.name for term in ("份额", "占比", "占有率")):
                metric.change_type = "share"
        if metric.unit is None:
            match = re.fullmatch(r"\s*([+＋\-−]?\d+(?:\.\d+)?)\s*(亿元|万元|元|万辆|万台|万片|百万片|辆|台|片|百分点|%)\s*", metric.value)
            if match:
                metric.value = match.group(1).replace("＋", "+").replace("−", "-")
                metric.unit = match.group(2)
        return metric

    @classmethod
    def _ground_events(cls, events: Any, chunk: SemanticChunk) -> list[KnowledgeEvent]:
        if not isinstance(events, list):
            return []
        output: list[KnowledgeEvent] = []
        source_content = chunk.source.source_text or chunk.content
        for item in events:
            try:
                event = KnowledgeEvent.model_validate(item)
            except ValidationError:
                continue
            if not event.summary.strip():
                continue
            source_subject = event.subject
            if event.subject:
                canonical, original = normalize_company_name(event.subject)
                if not original or (original not in source_content and (canonical or "") not in source_content):
                    continue
                event.subject = canonical or event.subject
            if event.object and event.object not in source_content:
                continue
            if event.time and event.time not in source_content:
                continue
            terms = [term for term in (source_subject, event.subject, event.object, event.time) if term]
            contexts = [line.strip() for line in re.split(r"[\n。；;]", source_content) if line.strip() and (not terms or any(term in line for term in terms))]
            output.append(event.model_copy(update={"sources": [cls._anchor(chunk, text) for text in dict.fromkeys(contexts[:3])] }))
        return output

    @classmethod
    def _fallback_events(cls, chunk: SemanticChunk) -> list[KnowledgeEvent]:
        """Keep rule-based source events available when the LLM is unavailable."""
        output: list[KnowledgeEvent] = []
        for raw_event in structure_automotive_text(chunk.content):
            if not raw_event.event:
                continue
            output.append(KnowledgeEvent(
                event_type=_EVENT_TYPE_MAP.get(raw_event.event_type, "other"),
                subject=raw_event.company,
                time=raw_event.time,
                summary=raw_event.event,
                keywords=raw_event.keywords,
                sources=[cls._anchor(chunk, raw_event.event)],
            ))
        return output

    def extract(self, chunk: SemanticChunk) -> StructuredKnowledge:
        if chunk.chunk_type == "table":
            prompt_template, system_prompt = TABLE_SEMANTIC_USER_PROMPT, TABLE_SEMANTIC_SYSTEM_PROMPT
        elif chunk.chunk_type == "chart":
            prompt_template, system_prompt = CHART_SEMANTIC_USER_PROMPT, CHART_SEMANTIC_SYSTEM_PROMPT
        else:
            prompt_template, system_prompt = SEMANTIC_USER_PROMPT, SEMANTIC_SYSTEM_PROMPT
        prompt = prompt_template.format(
            title=chunk.title,
            file_name=chunk.source.file,
            page_start=chunk.source.page_start or chunk.source.slide_start,
            page_end=chunk.source.page_end or chunk.source.slide_end,
            content=chunk.content,
        )
        try:
            if chunk.chunk_type == "table":
                logger.info("[TABLE_SEMANTIC_INPUT] title=%s\ncontent:\n%s", chunk.title, chunk.content)
            elif chunk.chunk_type == "chart":
                logger.info("[CHART_SEMANTIC_INPUT] title=%s\ncontent:\n%s", chunk.title, chunk.content)
            response = self._model.invoke(
                [SystemMessage(content=system_prompt), HumanMessage(content=prompt)],
                response_format={"type": "json_object"},
            )
            payload = self._json_object(response.content)
            if chunk.chunk_type == "table":
                payload["metrics"] = self._safe_table_metrics(payload.get("metrics"), chunk)
            payload["entities"] = [entity.model_dump() for entity in self._ground_entities(payload.get("entities"), chunk)]
            payload["metrics"] = [metric.model_dump() for metric in self._ground_metrics(payload.get("metrics"), chunk)]
            payload["events"] = [event.model_dump() for event in self._ground_events(payload.get("events"), chunk)]
            # These fields remain required by StructuredKnowledge for API and
            # review compatibility, but they are deliberately not LLM tasks.
            payload["summary"] = ""
            payload["insights"] = []
            payload["source"] = chunk.source.model_dump()
            payload["chunk_type"] = chunk.chunk_type
            payload["subject"] = self._event_subject(chunk) if chunk.chunk_type == "event" else self._clean_subject(chunk.subject)
            return StructuredKnowledge.model_validate(payload)
        except (json.JSONDecodeError, ValidationError, ValueError) as exc:
            logger.warning("Invalid semantic response for %s pages %s-%s: %s", chunk.source.file, chunk.source.page_start, chunk.source.page_end, exc)
            raise
        except Exception as exc:
            logger.exception("Semantic extraction failed for %s pages %s-%s", chunk.source.file, chunk.source.page_start, chunk.source.page_end)
            raise RuntimeError("语义结构化调用失败") from exc

    @staticmethod
    def fallback_extract(chunk: SemanticChunk) -> StructuredKnowledge:
        """Create a conservative record without adding facts not in the source."""
        content = chunk.content.strip()
        if chunk.chunk_type == "table":
            return StructuredKnowledge(
                knowledge_type="market_trend",
                title=chunk.title,
                entities=SemanticExtractor._ground_entities(extract_entities(content), chunk),
                metrics=[],
                events=SemanticExtractor._fallback_events(chunk),
                summary="",
                insights=[],
                source=chunk.source,
                chunk_type=chunk.chunk_type,
                subject=(
                    SemanticExtractor._clean_subject(chunk.subject)
                    or SemanticExtractor._subject_from_content(chunk.content)
                ),
            )
        if any(word in content for word in ("风险", "下滑", "压力", "不确定")):
            knowledge_type = "risk"
        elif any(word in content for word in ("政策", "购置税", "补贴", "法规")):
            knowledge_type = "policy"
        elif any(word in content for word in ("电池", "800V", "智能驾驶", "技术")):
            knowledge_type = "technology"
        elif any(word in content for word in ("推出", "发布", "产能", "合作")):
            knowledge_type = "company_event"
        elif any(word in content for word in ("销量", "同比", "增长率", "万辆")):
            knowledge_type = "sales_analysis"
        else:
            knowledge_type = "market_trend"

        metrics: list[Metric] = []
        first_sentence = re.split(r"[。！？]", content, maxsplit=1)[0].strip()
        time_match = re.search(r"20\d{2}(?:年)?", content)
        return StructuredKnowledge(
            knowledge_type=knowledge_type,
            title=chunk.title or first_sentence[:40] or "汽车行业文档知识",
            time=time_match.group(0) if time_match else None,
            entities=SemanticExtractor._ground_entities(extract_entities(content), chunk),
            metrics=metrics,
            events=SemanticExtractor._fallback_events(chunk),
            summary="",
            insights=[],
            source=chunk.source,
            chunk_type=chunk.chunk_type,
            subject=(
                SemanticExtractor._clean_subject(chunk.subject)
                or SemanticExtractor._subject_from_content(chunk.content)
            ),
        )

