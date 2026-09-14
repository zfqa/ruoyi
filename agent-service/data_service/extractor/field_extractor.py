import re

from data_service.extractor.entity_extractor import extract_entities
from data_service.models.schemas import StructuredEvent


def extract_event_fields(text: str) -> StructuredEvent:
    entities = extract_entities(text)
    company = next((entity.name for entity in entities if entity.type == "company"), None)
    time = (re.search(r"20\d{2}(?:年)?", text) or [None])[0]
    event = next((phrase for phrase in ("推出新能源车型", "推出多款新能源车型", "发布新车型", "扩大产能") if phrase in text), None)
    impact = next((phrase for phrase in ("销量增长", "销量增长明显", "市场份额提升", "销量下滑") if phrase in text), None)
    keywords = [keyword for keyword in ("新能源", "插混", "纯电", "800V", "固态电池", "智能驾驶") if keyword in text]
    return StructuredEvent(company=company, companies=[company] if company else [], event=event, time=time, impact=impact, event_type="企业动态" if company else "其他行业动态", keywords=keywords)


