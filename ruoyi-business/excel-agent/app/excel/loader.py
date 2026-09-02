"""Excel/CSV workbook 加载器."""
import csv
import os
import re
import openpyxl
from typing import Tuple


def load_workbooks(file_path: str) -> Tuple[openpyxl.Workbook, openpyxl.Workbook]:
    """同时加载公式版与缓存值版 workbook.

    Returns:
        (wb_formula, wb_value)
    """
    if not os.path.exists(file_path):
        raise FileNotFoundError(f"文件不存在: {file_path}")

    ext = os.path.splitext(file_path)[1].lower()
    if ext == ".csv":
        return _load_csv(file_path)
    if ext not in {".xlsx", ".xlsm", ".xltx", ".xltm"}:
        raise ValueError(f"不支持的文件格式: {ext}，仅支持 .xlsx/.xlsm/.csv")

    wb_formula = openpyxl.load_workbook(file_path, data_only=False, read_only=False)
    wb_value = openpyxl.load_workbook(file_path, data_only=True, read_only=False)
    return wb_formula, wb_value


def _load_csv(file_path: str) -> Tuple[openpyxl.Workbook, openpyxl.Workbook]:
    """将 CSV 映射为单 Sheet 工作簿，以复用同一套表格识别和血缘逻辑。"""
    rows = _read_csv_rows(file_path)
    wb_formula = _rows_to_workbook(rows)
    wb_value = _rows_to_workbook(rows)
    return wb_formula, wb_value


def _read_csv_rows(file_path: str) -> list[list[object]]:
    last_error = None
    for encoding in ("utf-8-sig", "gb18030"):
        try:
            with open(file_path, "r", encoding=encoding, newline="") as handle:
                sample = handle.read(8192)
                handle.seek(0)
                try:
                    dialect = csv.Sniffer().sniff(sample, delimiters=",;\t|")
                except csv.Error:
                    dialect = csv.excel
                return [[_parse_csv_value(value) for value in row] for row in csv.reader(handle, dialect)]
        except UnicodeDecodeError as exc:
            last_error = exc
    raise ValueError("CSV编码无法识别，仅支持 UTF-8 或 GB18030") from last_error


def _rows_to_workbook(rows: list[list[object]]) -> openpyxl.Workbook:
    workbook = openpyxl.Workbook()
    worksheet = workbook.active
    worksheet.title = "CSV"
    for row in rows:
        worksheet.append(row)
    return workbook


def _parse_csv_value(value: str):
    text = value.strip()
    if text == "":
        return None
    if re.fullmatch(r"[-+]?(0|[1-9]\d*)", text) and not re.fullmatch(r"[-+]?0\d+", text):
        try:
            return int(text)
        except ValueError:
            pass
    if re.fullmatch(r"[-+]?(?:\d+\.\d*|\.\d+)(?:[eE][-+]?\d+)?", text):
        try:
            return float(text)
        except ValueError:
            pass
    return value
