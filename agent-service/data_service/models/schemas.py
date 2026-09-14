from typing import Any, Literal

from pydantic import BaseModel, Field


class SourceRef(BaseModel):
    file: str
    page: int | None = None
    slide: int | None = None


class SourceAnchor(BaseModel):
    """A user-facing anchor into source text in a PDF page or PPTX slide."""

    file: str
    page: int | None = None
    slide: int | None = None
    text: str


class SemanticSource(BaseModel):
    file: str
    page_start: int | None = None
    page_end: int | None = None
    slide_start: int | None = None
    slide_end: int | None = None
    source_text: str


class SemanticChunk(BaseModel):
    """A context-preserving text unit built from one or more PDF pages."""

    title: str
    chunk_type: Literal["section", "event", "narrative", "table", "chart"] = "narrative"
    subject: str | None = None
    content: str
    source: SemanticSource
    metadata: dict[str, Any] = Field(default_factory=dict)


class DocumentChunk(BaseModel):
    content: str
    source: SourceRef
    page: int | None = None
    slide: int | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)


class Entity(BaseModel):
    name: str
    type: str
    original_name: str | None = None
    parent_company: str | None = None
    brand: str | None = None
    sources: list[SourceAnchor] = Field(default_factory=list)


class Metric(BaseModel):
    metric_type: Literal["financial", "shipment", "sales", "production", "export", "inventory", "market_share", "other"] = "other"
    name: str
    value: str
    unit: str | None = None
    time: str | None = None
    entity: str | None = None
    product: str | None = None
    change_type: Literal["yoy", "mom", "share", "target", "actual", "other"] | None = None
    sources: list[SourceAnchor] = Field(default_factory=list)


class KnowledgeEvent(BaseModel):
    """A source-grounded industry event, separate from named entities and metrics."""

    event_type: Literal[
        "acquisition", "product_launch", "capacity_expansion", "cooperation",
        "investment", "litigation", "personnel_change", "strategy_change",
        "financial_report", "sales_release", "policy", "technology_release",
        "market_change", "other",
    ] = "other"
    subject: str | None = None
    object: str | None = None
    time: str | None = None
    summary: str
    keywords: list[str] = Field(default_factory=list)
    sources: list[SourceAnchor] = Field(default_factory=list)


class StructuredKnowledge(BaseModel):
    """LLM-derived automotive knowledge with page-level provenance."""

    knowledge_type: str
    title: str
    time: str | None = None
    entities: list[Entity] = Field(default_factory=list)
    metrics: list[Metric] = Field(default_factory=list)
    events: list[KnowledgeEvent] = Field(default_factory=list)
    summary: str
    insights: list[str] = Field(default_factory=list)
    source: SemanticSource
    chunk_type: str | None = None
    subject: str | None = None


class StructuredEvent(BaseModel):
    company: str | None = None
    companies: list[str] = Field(default_factory=list)
    event: str | None = None
    time: str | None = None
    impact: str | None = None
    event_type: str = "其他行业动态"
    keywords: list[str] = Field(default_factory=list)
    sources: list[SourceAnchor] = Field(default_factory=list)


class TableData(BaseModel):
    type: str = "table"
    title: str = "未命名表格"
    columns: list[str]
    rows: list[dict[str, str]]
    source: SourceRef | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)


class ChartSeries(BaseModel):
    """One native PPTX chart series, kept without LLM interpretation."""

    name: str
    values: list[float | str | None] = Field(default_factory=list)
    points: list["ChartPoint"] = Field(default_factory=list)


class ChartPoint(BaseModel):
    """One native chart point; bubble charts require all three dimensions."""

    x: float | str | None = None
    y: float | str | None = None
    bubble_size: float | str | None = None


class ChartKnowledge(BaseModel):
    """Structured values extracted from a native PowerPoint chart object."""

    type: str = "chart"
    title: str = "未命名图表"
    chart_type: str
    categories: list[str] = Field(default_factory=list)
    series: list[ChartSeries] = Field(default_factory=list)
    source: SourceRef
    metadata: dict[str, Any] = Field(default_factory=dict)


