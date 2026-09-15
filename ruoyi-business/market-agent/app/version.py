from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path


APP_VERSION = "21.0"
API_CONTRACT_VERSION = "dynamic-indicator-content-fidelity-v1"
REPORT_EXPORT_VERSION = "21.1"
PROCESS_STARTED_AT = datetime.now(timezone.utc).isoformat()
SERVICE_ROOT = str(Path(__file__).resolve().parents[1])
FEATURES = [
    "multi_sheet_analysis",
    "dynamic_period_latest",
    "dynamic_period_single",
    "dynamic_period_range",
    "dynamic_period_year",
    "period_yoy",
    "period_scoped_chat",
    "period_scoped_report",
    "period_scoped_export",
    "natural_language_report_planner",
    "multiple_market_observations",
    "individual_dashboard_chart_components",
    "selected_chart_conversation_context",
    "observation_scoped_period_and_filters",
    "atomic_component_operations",
    "dashboard_component_registry",
    "plan_revisioned_exports",
    "title_scope_period_validation",
    "empty_dialogue_composed_observation",
    "data_driven_component_catalog",
    "explicit_component_selection",
    "dynamic_market_observation",
    "deterministic_market_components",
    "material_grounded_weekly_sections",
    "period_consistent_trend_windows",
    "dashboard_excel_lineage",
    "dashboard_reconciliation_audit",
    "visual_market_observation_composer",
    "ordered_report_components",
    "manual_observation_content",
    "validated_observation_export",
    "data_driven_core_indicators",
    "context_content_hashes",
    "export_content_completeness_audit",
    "non_overlapping_power_donut",
    "formula_grounded_china_market_metrics",
    "editable_market_summary_matrix",
    "configurable_power_group_trend",
    "power_group_mapping_audit",
    "runtime_build_identity",
]
