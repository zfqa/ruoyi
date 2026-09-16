from __future__ import annotations
from typing import Any, Literal
from pydantic import BaseModel, Field


STANDARD_FIELDS = [
    "time_period", "region", "market", "vehicle_type", "oem", "brand", "model",
    "power_type", "size_class", "tech_route", "production", "sales", "retail_sales", "wholesale",
    "domestic_sales", "domestic_wholesale", "export", "inventory", "yoy_production", "yoy_sales",
    "yoy_retail_sales", "yoy_wholesale", "yoy_domestic_sales", "yoy_domestic_wholesale", "yoy_export", "source_file", "source_sheet", "source_row",
    "source_column", "source_column_index", "source_cell",
    "source_metric_type", "source_metric_scope", "source_metric_label", "source_dimension",
    "source_table_type", "source_parser_id", "source_semantic_confidence", "source_row_kind",
    "manufacturer", "origin_type",
]


class ParseIssue(BaseModel):
    level: Literal["info", "warning", "error"]
    code: str
    message: str
    sheet: str | None = None
    row: int | None = None
    column: str | None = None
    source_file: str | None = None
    # 原始Excel位置。宽表转长表后，一条原表数据可能产生多条标准化记录，
    # 因此位置必须以源文件/工作表/行/单元格为准，而不能使用DataFrame行号。
    locations: list[dict[str, Any]] = Field(default_factory=list)
    source_record_count: int | None = None
    standardized_record_count: int | None = None
    location_count: int = 0
    locations_truncated: bool = False


class SheetMeta(BaseModel):
    sheet_name: str
    sheet_type: str
    source_file: str = ""
    detected_metric: str | None = None
    detected_dimension: str | None = None
    semantic_confidence: float = 0.0
    dimension_confidence: float = 0.0
    # v9：兼容字段仍保留，但只允许确定性0/1，不再使用0.92/0.96/0.99概率权重。
    field_mapping_confidence: float = 0.0
    field_confidences: dict[str, float] = Field(default_factory=dict)
    metric_validation_score: float = 0.0
    dimension_validation_score: float = 0.0
    period_validation_score: float = 0.0
    value_validation_score: float = 0.0
    template_validation_score: float = 0.0
    template_validation_status: str = "REVIEW"
    validation_details: dict[str, Any] = Field(default_factory=dict)
    unmapped_named_columns: list[str] = Field(default_factory=list)
    unlabeled_data_columns: list[str] = Field(default_factory=list)
    dimension_integrity: dict[str, Any] = Field(default_factory=dict)
    semantic_evidence: list[str] = Field(default_factory=list)
    value_column: str | None = None
    transform_mode: str = "standard"
    header_row: int | None = None
    original_headers: list[str] = Field(default_factory=list)
    field_mapping: dict[str, str] = Field(default_factory=dict)
    # rows_detected 保留兼容：表示标准化后的记录数，不等同于Excel物理行数。
    rows_detected: int = 0
    physical_rows: int = 0
    physical_columns: int = 0
    source_data_rows: int = 0
    period_column_count: int = 0
    period_start: str | None = None
    period_end: str | None = None
    period_cells_total: int = 0
    period_valid_cells: int = 0
    period_placeholder_cells: int = 0
    period_placeholder_breakdown: dict[str, int] = Field(default_factory=dict)
    merged_ranges: list[str] = Field(default_factory=list)
    parser_id: str | None = None
    metric_scope: str | None = None
    metric_label: str | None = None
    structure_type: str | None = None
    block_count: int = 0


class FileParseResult(BaseModel):
    """单个上传文件的独立展示结果。"""

    file_name: str
    rows: int
    columns: list[str]
    parse_summary: dict[str, Any] = Field(default_factory=dict)
    sheet_meta: list[SheetMeta] = Field(default_factory=list)
    issues: list[ParseIssue] = Field(default_factory=list)
    preview: list[dict[str, Any]] = Field(default_factory=list)


class ParseResult(BaseModel):
    dataset_id: str
    file_name: str
    source_files: list[str] = Field(default_factory=list)
    # rows 保留向后兼容：表示标准化后的可分析记录数。
    rows: int
    columns: list[str]
    parse_summary: dict[str, Any] = Field(default_factory=dict)
    sheet_meta: list[SheetMeta]
    issues: list[ParseIssue]
    preview: list[dict[str, Any]]
    # 顶层字段继续表示所有文件合并后的数据集，保证市场分析接口兼容；
    # Excel导入页面使用file_results按文件切换展示。
    file_results: list[FileParseResult] = Field(default_factory=list)


class ChatRequest(BaseModel):
    dataset_id: str
    question: str
    use_llm: bool = True
    sheet_name: str | None = None
    period_mode: Literal["latest", "single", "range", "year"] = "latest"
    start_period: str | None = None
    end_period: str | None = None
    year: int | None = None
    selected_component_id: str | None = None
    selected_component_title: str | None = None
    selected_chart_title: str | None = None
    history: list[dict[str, str]] = Field(default_factory=list)


class ChatResponse(BaseModel):
    answer: str
    table: list[dict[str, Any]] = Field(default_factory=list)
    charts: list[dict[str, Any]] = Field(default_factory=list)
    evidence_chain: list[str] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
    report_updated: bool = False
    report_config: dict[str, Any] = Field(default_factory=dict)
    report_plan: dict[str, Any] = Field(default_factory=dict)


class ContextTextRequest(BaseModel):
    text: str
    category: Literal["macro_policy", "personnel", "strategy", "industry_chain", "competition", "other"]
    source_name: str = "手工补充文本"


class ContextItem(BaseModel):
    id: str = ""
    created_at: str = ""
    category: Literal["macro_policy", "personnel", "strategy", "industry_chain", "competition", "other"]
    title: str
    content: str
    source_name: str
    locator: str = ""
    content_sha256: str = ""


class ContextResult(BaseModel):
    dataset_id: str
    added: int
    total: int
    items: list[ContextItem] = Field(default_factory=list)


class ContextDeleteRequest(BaseModel):
    item_ids: list[str] = Field(default_factory=list)


class ContextCategoryUpdateRequest(BaseModel):
    category: Literal["macro_policy", "personnel", "strategy", "industry_chain", "competition", "other"]


class ReportConfigRequest(BaseModel):
    include_overview: bool = True
    include_rankings: bool = True
    ranking_dimensions: list[Literal["market", "oem", "brand", "model"]] = Field(default_factory=lambda: ["market", "oem", "brand", "model"])
    include_power_structure: bool = True
    include_monthly_trend: bool = True
    include_line_charts: bool = True
    selected_line_charts: list[str] = Field(default_factory=list)
    include_weekly_content: bool = True
    include_anomalies: bool = True
    custom_title: str = ""
    last_instruction: str = ""


class ReportPlanInstructionRequest(BaseModel):
    instruction: str
    sheet_name: str | None = None
    period_mode: Literal["latest", "single", "range", "year"] = "latest"
    start_period: str | None = None
    end_period: str | None = None
    year: int | None = None
    selected_component_id: str | None = None
    selected_component_title: str | None = None
    selected_chart_title: str | None = None
    use_llm: bool = True


class VisualReportCompositionRequest(BaseModel):
    """可视化编辑器提交的完整 1.1 市场观察配置。"""

    report_title: str = ""
    observation_id: str | None = None
    observation_title: str = ""
    component_ids: list[str] = Field(default_factory=list)
    summary_segments: list[Literal[
        "total_market", "passenger_vehicle", "commercial_vehicle",
        "new_energy_vehicle", "ice_vehicle",
    ]] = Field(default_factory=list)
    core_indicators: list[dict[str, Any]] = Field(default_factory=list)
    market_summary_rows: list[dict[str, Any]] = Field(default_factory=list)
    market_summary_columns: list[dict[str, Any]] = Field(default_factory=list)
    component_options: dict[str, Any] = Field(default_factory=dict)
    manual_content: str = ""
    lookback_months: int = Field(default=12, ge=2, le=36)
    filters: dict[str, list[str]] = Field(default_factory=dict)
    sheet_name: str | None = None
    period_mode: Literal["latest", "single", "range", "year"] = "latest"
    start_period: str | None = None
    end_period: str | None = None
    year: int | None = None
    include_weekly_content: bool = True
    include_anomalies: bool = True


class ExportResponse(BaseModel):
    dataset_id: str
    format: Literal["xlsx", "docx", "pptx"]
    file_name: str
    url_path: str
    app_version: str = ""
    report_export_version: str = ""
    content_audit: dict[str, Any] = Field(default_factory=dict)
