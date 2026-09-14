"""Conservative company and vehicle-model extraction with source anchors."""
from __future__ import annotations

import re
from collections.abc import Iterable

from data_service.models.schemas import DocumentChunk, Entity, SourceAnchor
from data_service.normalizer.company_aliases import normalize_company_name

# A deliberately small POC allow-list. Policies, technologies, factories and
# regions must not be promoted into business entities.
COMPANY_LEXICON = (
    "比亚迪", "比亚迪汽车", "特斯拉", "理想汽车", "理想", "蔚来", "小鹏",
    "吉利汽车", "吉利", "上汽集团", "上汽", "广汽埃安", "长安汽车", "长安",
    "TCL华星", "华星光电", "京东方", "京东方科技", "BOE", "天马微电子",
    "深天马", "天马", "群创光电", "群创", "英伟达", "博世", "宁德时代",
)
VEHICLE_MODELS: dict[str, str | None] = {
    "Model Y": None, "Model 3": None, "极氪001": "极氪", "极氪009": "极氪",
    "理想MEGA": "理想", "秦PLUS": None, "宋PLUS": None, "海豹06": None,
    "L6": None, "银河E5": None, "AION Y": "AION", "AION V": "AION",
}


def extract_entities(text: str) -> list[Entity]:
    """Extract only the two business entity classes used by StructuredKnowledge."""
    entities: list[Entity] = []
    seen: set[tuple[str, str]] = set()
    lowered = text.lower()
    for original_name in sorted(COMPANY_LEXICON, key=len, reverse=True):
        if original_name.lower() not in lowered:
            continue
        name, original = normalize_company_name(original_name)
        if name and (name, "company") not in seen:
            entities.append(Entity(name=name, original_name=original, type="company"))
            seen.add((name, "company"))
    for model_name, brand in VEHICLE_MODELS.items():
        if model_name.lower() in lowered and (model_name, "vehicle_model") not in seen:
            entities.append(Entity(name=model_name, original_name=model_name, type="vehicle_model", brand=brand))
            seen.add((model_name, "vehicle_model"))
    return entities


def find_entity_context(content: str, entity_name: str, original_name: str | None = None) -> list[str]:
    """Return original source sentences/lines containing a configured spelling."""
    names = tuple(dict.fromkeys(item for item in (entity_name, original_name) if item))
    pieces = re.findall(r"[^。！？；\n]+[。！？；]?", content)
    return [piece.strip() for piece in pieces if piece.strip() and any(name.lower() in piece.lower() for name in names)]


def extract_entities_with_sources(chunks: Iterable[DocumentChunk]) -> list[Entity]:
    """Aggregate canonical entities while retaining every source occurrence."""
    aggregated: dict[tuple[str, str], Entity] = {}
    seen_sources: dict[tuple[str, str], set[tuple[str, int | None, int | None, str]]] = {}
    for chunk in chunks:
        for entity in extract_entities(chunk.content):
            key = (entity.name, entity.type)
            aggregate = aggregated.setdefault(key, entity.model_copy(deep=True))
            source_keys = seen_sources.setdefault(key, set())
            for text in find_entity_context(chunk.content, entity.name, entity.original_name):
                anchor = SourceAnchor(file=chunk.source.file, page=chunk.source.page, slide=chunk.source.slide, text=text)
                source_key = (anchor.file, anchor.page, anchor.slide, anchor.text)
                if source_key not in source_keys:
                    aggregate.sources.append(anchor)
                    source_keys.add(source_key)
    return [aggregated[key] for key in sorted(aggregated)]


