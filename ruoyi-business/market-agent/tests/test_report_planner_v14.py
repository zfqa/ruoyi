from __future__ import annotations
import pandas as pd

from app.services.market_components import MarketComponentEngine
from app.services.report_planner import _rule_patch, normalize_report_plan
from app.services.report_generator import build_report_payload


def _df():
    rows=[]
    def add(period, market, value, power="ICE", oem="OEM-A"):
        rows.append({"time_period":period,"region":"美国","market":market,"vehicle_type":market,"oem":oem,"brand":"B","model":market+power,"power_type":power,"sales":value,"source_file":"x.xlsx","source_sheet":"Sheet1","source_metric_type":"sales","source_metric_label":"销量","source_row_kind":"detail"})
    # 2024-11 comparison
    add("2024-11","Cars",200,"EV"); add("2024-11","Cars",50,"PHV"); add("2024-11","Cars",1,"FCV"); add("2024-11","Cars",749,"ICE")
    add("2024-11","Light Trucks",4000); add("2024-11","Medium Trucks",100); add("2024-11","Heavy Trucks",100)
    # 2025-10 mom
    add("2025-10","Cars",150,"EV"); add("2025-10","Cars",50,"PHV"); add("2025-10","Cars",800,"ICE")
    add("2025-10","Light Trucks",4000); add("2025-10","Medium Trucks",100); add("2025-10","Heavy Trucks",100)
    # 2025-11 current: total 6000, passenger 5800, commercial 200, NEV 300
    add("2025-11","Cars",200,"EV"); add("2025-11","Cars",99,"PHV"); add("2025-11","Cars",1,"FCV"); add("2025-11","Cars",700,"ICE")
    add("2025-11","Light Trucks",4800); add("2025-11","Medium Trucks",100); add("2025-11","Heavy Trucks",100)
    return pd.DataFrame(rows)


def _period():
    return {"period_mode":"single","start_period":"2025-11","end_period":"2025-11"}


def test_us_market_summary_segments_are_deterministic():
    e=MarketComponentEngine(_df(),_period())
    rows={x["指标"]:x for x in e.market_summary(["total_market","passenger_vehicle","commercial_vehicle","new_energy_vehicle"])["rows"]}
    assert rows["总体市场"]["销量"] == 6000
    assert rows["乘用车"]["销量"] == 5800
    assert rows["商用车"]["销量"] == 200
    assert rows["新能源车"]["销量"] == 300
    assert abs(rows["新能源车"]["占比"] - 0.05) < 1e-9


def test_rule_planner_understands_market_observation_prompt():
    patch=_rule_patch("生成美国市场11月观察，放总体市场、乘用车、商用车和新能源，加入新能源占有率和新能源/燃油同比变化图，重点分析新能源")
    assert patch["replace"] is False
    assert patch["custom_title"] == "美国市场11月观察"
    assert patch["summary_segments"] == ["total_market","passenger_vehicle","commercial_vehicle","new_energy_vehicle"]
    assert "nev_share_trend" in patch["components"]
    assert "nev_vs_ice_yoy" in patch["components"]


def test_dynamic_payload_only_projects_selected_components():
    plan=normalize_report_plan({"mode":"custom","market_observation":{"custom_title":"美国市场11月观察","components":["market_summary","nev_share_trend","nev_vs_ice_yoy"],"summary_segments":["total_market","passenger_vehicle","commercial_vehicle","new_energy_vehicle"]}})
    payload=build_report_payload(_df(),use_llm=False,analysis_period=_period(),report_plan=plan)
    assert payload["dynamic_market_observation"] is True
    assert payload["market_observation_title"] == "美国市场11月观察"
    assert [x["id"] for x in payload["market_observation_components"]] == ["market_summary","nev_share_trend","nev_vs_ice_yoy"]
    assert payload["top_oem"] == []
    assert [x["title"] for x in payload["line_charts"]] == ["新能源汽车销量占有率","新能源车与燃油车销量同比变化"]
