from pathlib import Path
import shutil

from openpyxl import Workbook

from app.services.parser import parse_file
from app.services.market_analysis import MarketAnalyzer
from app.services.storage import save_dataset, load_dataset, processed_base


def _make_client_like_workbook(path: Path):
    wb = Workbook()
    ws = wb.active
    ws.title = "总零售销量"
    ws.append(["说明：测试固定模板"])
    ws.append([])
    ws.append(["集团", "整车厂/品牌", "国家/地区", "车种", "级别", "车型", "动力总成", 202312, 202412, None, None])
    rows = [
        ["丰田集团", "丰田", "中国", "Cars", "C", "Corolla", "HV", 100, 120, "一汽丰田", "-"],
        ["丰田集团", "丰田", "中国", "Small Cars", "B", "亚洲狮 (Allion)", "ICE", 50, 60, "一汽丰田", "-"],
        ["丰田集团", "丰田", "日本", "Include all models (Imported Cars)", "N/A", "N/A", "N/A", 30, 40, "海外", "-"],
        ["丰田集团", "雷克萨斯", "美国", "SUV", "D", "RAV4", "PHV", 20, 25, "海外", "-"],
    ]
    for row in rows:
        ws.append(row)
    wb.save(path)


def test_v8_fixed_template_named_fields_reach_95_and_multidimension(tmp_path: Path):
    path = tmp_path / "海外整车厂--丰田2023、2024年月度汽车销量.xlsx"
    _make_client_like_workbook(path)
    df, metas, issues = parse_file(path, source_name=path.name)
    meta = metas[0]
    assert meta.semantic_confidence >= 0.95
    assert meta.field_mapping_confidence >= 0.95
    assert meta.dimension_confidence >= 0.95
    assert meta.detected_dimension == "multi_dimension"
    assert meta.sheet_type == "multi_dimension_detail"
    assert meta.dimension_integrity["vehicle_type"]["status"] == "完整"
    assert meta.dimension_integrity["model"]["status"] == "完整"
    assert meta.dimension_integrity["power_type"]["status"] == "完整"
    assert any(x.code == "UNNAMED_DATA_COLUMNS" for x in issues)


def test_v8_market_observation_and_rankings_do_not_truncate_or_misfilter(tmp_path: Path):
    path = tmp_path / "海外整车厂--丰田2023、2024年月度汽车销量.xlsx"
    _make_client_like_workbook(path)
    df, _, _ = parse_file(path, source_name=path.name)
    result = MarketAnalyzer(df).run_all()

    indicators = [x["指标"] for x in result.overview_table]
    assert set(indicators) == {"Cars", "Small Cars", "Include all models (Imported Cars)", "SUV"}

    market_objects = [x["对象"] for x in result.rankings["market"]]
    assert len(market_objects) == 4
    assert "Small Cars" in market_objects
    assert "Include all models (Imported Cars)" in market_objects

    model_objects = [x["对象"] for x in result.rankings["model"]]
    assert "亚洲狮 (Allion)" in model_objects
    assert "N/A" in model_objects


def test_v8_na_category_survives_storage_roundtrip_and_power_share(tmp_path: Path):
    path = tmp_path / "海外整车厂--丰田2023、2024年月度汽车销量.xlsx"
    _make_client_like_workbook(path)
    df, _, _ = parse_file(path, source_name=path.name)
    dataset_id = "pytest_v8_na_roundtrip"
    try:
        save_dataset(dataset_id, df, {"file_name": path.name})
        loaded = load_dataset(dataset_id)
        assert "N/A" in set(loaded["power_type"].astype(str))
        assert "N/A" in set(loaded["model"].astype(str))
        result = MarketAnalyzer(loaded).run_all()
        power_types = [x["动力类型"] for x in result.power_share]
        assert set(power_types) == {"HV", "ICE", "N/A", "PHV"}
        assert abs(sum(float(x["占比"]) for x in result.power_share) - 1.0) < 1e-9
    finally:
        shutil.rmtree(processed_base(dataset_id), ignore_errors=True)


def test_v8_dimension_integrity_explains_latest_period_coverage(tmp_path: Path):
    path = tmp_path / "海外整车厂--丰田2023、2024年月度汽车销量.xlsx"
    _make_client_like_workbook(path)
    df, _, _ = parse_file(path, source_name=path.name)
    result = MarketAnalyzer(df).run_all()
    for dim in ["market", "vehicle_type", "oem", "brand", "model", "power_type"]:
        assert result.dimension_integrity[dim]["状态"] == "完整"
        assert result.dimension_integrity[dim]["最新周期未进入输出"] == []
