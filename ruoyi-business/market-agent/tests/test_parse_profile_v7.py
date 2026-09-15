from pathlib import Path

from openpyxl import Workbook

from app.services.parser import parse_excel


def _make_wide_template(path: Path):
    wb = Workbook()
    ws = wb.active
    ws.title = "Sheet1"
    ws.append([])
    ws.append(["国家/地区", "集团", "整车厂/品牌", "车种", "车型", "动力总成", 202401, 202402, 202403])
    ws.append(["美国", "丰田集团", "丰田", "Cars", "Corolla", "HV", 100, 110, "-"])
    ws.append(["美国", "福特集团", "福特", "Light Trucks", "F-Series", "ICE", 200, "N/A", 250])
    wb.save(path)


def test_v7_distinguishes_excel_rows_from_normalized_records(tmp_path: Path):
    path = tmp_path / "美国市场月度汽车销量明细.xlsx"
    _make_wide_template(path)
    df, metas, issues = parse_excel(path, source_name=path.name)
    assert len(metas) == 1
    meta = metas[0]
    assert meta.physical_rows == 4
    assert meta.source_data_rows == 2
    assert meta.period_column_count == 3
    assert meta.period_cells_total == 6
    assert meta.period_valid_cells == 4
    assert meta.period_placeholder_cells == 2
    assert meta.period_placeholder_breakdown == {"-": 1, "N/A": 1}
    assert meta.rows_detected == 4
    assert len(df) == 4
    # 宽转长后仍必须指向原Excel物理行和月份单元格，不能使用展开后的记录序号。
    assert set(df["source_row"].astype(int)) == {3, 4}
    assert set(df["source_cell"].dropna()) == {"G3", "H3", "G4", "I4"}
    assert int(df["source_row"].max()) <= meta.physical_rows


def test_v7_fixed_template_maps_group_to_oem_and_factory_brand_to_brand(tmp_path: Path):
    path = tmp_path / "美国市场月度汽车销量明细.xlsx"
    _make_wide_template(path)
    df, metas, _ = parse_excel(path, source_name=path.name)
    mapping = metas[0].field_mapping
    assert mapping["集团"] == "oem"
    assert mapping["整车厂/品牌"] == "brand"
    assert set(df["oem"].dropna()) == {"丰田集团", "福特集团"}
    assert set(df["brand"].dropna()) == {"丰田", "福特"}
