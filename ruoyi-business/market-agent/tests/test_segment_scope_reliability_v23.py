import math

import pandas as pd

from app.services.market_components import MarketComponentEngine
from app.services.report_planner import _normalize_component_options


def _us_scope_frame() -> pd.DataFrame:
    rows = []
    values = [
        ("Cars", "EV", 10),
        ("Light Trucks", "ICE", 20),
        ("Medium Trucks", "HV", 30),
        ("Heavy Trucks", "FCV", 40),
    ]
    for index, (market, power_type, sales) in enumerate(values, 2):
        rows.append({
            "time_period": "2025-01", "region": "美国", "market": market,
            "vehicle_type": market, "power_type": power_type, "sales": sales,
            "source_file": "美国市场.xlsx", "source_sheet": "Sheet1", "source_row": index,
            "source_row_kind": "detail", "source_metric_type": "sales",
        })
    return pd.DataFrame(rows)


def test_segment_capabilities_are_grounded_in_excel_values_and_sources():
    engine = MarketComponentEngine(_us_scope_frame(), {"period_mode": "single", "end_period": "2025-01"})
    options = engine.capabilities()["indicator_capabilities"]
    scopes = {item["id"]: item for item in options["segment_capabilities"]}

    assert scopes["total_market"]["source_files"] == ["美国市场.xlsx"]
    assert scopes["passenger_vehicle"]["record_count"] == 2
    assert scopes["commercial_vehicle"]["record_count"] == 2
    assert scopes["passenger_vehicle"]["status"] == "reliable"
    assert scopes["passenger_vehicle"]["record_coverage"] == 1.0
    assert scopes["passenger_vehicle"]["value_coverage"] == 1.0
    assert scopes["new_energy_vehicle"]["record_count"] == 2
    assert scopes["ice_vehicle"]["record_count"] == 2
    assert scopes["new_energy_vehicle"]["unclassified_values"] == []
    assert scopes["total_market"]["source_metric"] == "sales"
    assert scopes["total_market"]["source_row_start"] == 2
    assert scopes["total_market"]["source_row_end"] == 5


def test_unavailable_scope_is_not_added_to_default_report_rows():
    frame = pd.DataFrame([{
        "time_period": "2025-01", "sales": 100,
        "source_file": "仅销量.xlsx", "source_sheet": "销量", "source_row_kind": "detail",
    }])
    options = MarketComponentEngine(frame, {"period_mode": "single", "end_period": "2025-01"}).capabilities()["indicator_capabilities"]
    scopes = {item["id"]: item for item in options["segment_capabilities"]}
    template_segments = {item["segment"] for item in options["matrix_row_templates"]}

    assert scopes["passenger_vehicle"]["status"] == "unavailable"
    assert scopes["new_energy_vehicle"]["status"] == "unavailable"
    assert template_segments == {"total_market"}


def test_review_scope_is_not_silently_added_to_default_template():
    frame = _us_scope_frame()
    frame.loc[len(frame)] = {
        "time_period": "2025-01", "region": "美国", "market": "Cars",
        "vehicle_type": "Cars", "power_type": "MHV/PHV", "sales": 5,
        "source_file": "美国市场.xlsx", "source_sheet": "Sheet1", "source_row": 6,
        "source_row_kind": "detail", "source_metric_type": "sales",
    }
    options = MarketComponentEngine(frame, {"period_mode": "single", "end_period": "2025-01"}).capabilities()["indicator_capabilities"]
    scopes = {item["id"]: item for item in options["segment_capabilities"]}
    template_segments = {item["segment"] for item in options["matrix_row_templates"]}

    assert scopes["new_energy_vehicle"]["status"] == "review"
    assert "new_energy_vehicle" not in template_segments


def test_blank_source_value_is_reported_as_blank_but_not_offered_as_mapping_key():
    frame = _us_scope_frame()
    frame.loc[0, "power_type"] = "nan"
    engine = MarketComponentEngine(frame, {"period_mode": "single", "end_period": "2025-01"})
    scopes, mappings = engine.segment_capabilities()
    power_scope = next(item for item in scopes if item["id"] == "new_energy_vehicle")

    assert "（空白）" in power_scope["unclassified_values"]
    assert all(item["source_value"] != "nan" for item in mappings)


def test_manual_mapping_changes_scope_calculation_and_is_audited():
    frame = pd.DataFrame([
        {"time_period": "2025-01", "power_type": "EV", "sales": 10, "source_file": "动力.xlsx", "source_sheet": "销量", "source_row_kind": "detail"},
        {"time_period": "2025-01", "power_type": "ICE", "sales": 20, "source_file": "动力.xlsx", "source_sheet": "销量", "source_row_kind": "detail"},
        {"time_period": "2025-01", "power_type": "MHV/PHV", "sales": 5, "source_file": "动力.xlsx", "source_sheet": "销量", "source_row_kind": "detail"},
    ])
    engine = MarketComponentEngine(frame, {"period_mode": "single", "end_period": "2025-01"})
    scopes, mappings = engine.segment_capabilities()
    assert next(item for item in scopes if item["id"] == "new_energy_vehicle")["status"] == "review"
    assert next(item for item in mappings if item["source_value"] == "MHV/PHV")["requires_review"] is True

    overrides = {"power_type": {"MHV/PHV": "new_energy_vehicle"}}
    updated_scopes, _ = engine.segment_capabilities(overrides)
    updated_nev = next(item for item in updated_scopes if item["id"] == "new_energy_vehicle")
    assert updated_nev["status"] == "reliable"
    assert updated_nev["unclassified_values"] == []

    component = engine.custom_indicator_matrix(
        [{"id": "nev", "display_name": "新能源车", "segment": "new_energy_vehicle", "filters": []}],
        [{"id": "sales", "display_name": "销量", "metric": "sales", "aggregation": "sum"}],
        segment_mappings=overrides,
    )
    assert math.isclose(component["facts"][0]["cells"]["sales"]["value"], 15)
    assert component["facts"][0]["scope_audit"]["status"] == "reliable"
    assert component["segment_mappings"] == overrides


def test_report_plan_only_persists_valid_segment_mapping_targets():
    normalized = _normalize_component_options({
        "market_summary": {
            "segment_mappings": {
                "power_type": {"MHV/PHV": "new_energy_vehicle", "EV": "passenger_vehicle"},
                "market": {"Cars": "passenger_vehicle"},
                "unknown": {"x": "commercial_vehicle"},
            }
        }
    })

    assert normalized == {
        "market_summary": {
            "segment_mappings": {
                "power_type": {"MHV/PHV": "new_energy_vehicle"},
                "market": {"Cars": "passenger_vehicle"},
            }
        }
    }
