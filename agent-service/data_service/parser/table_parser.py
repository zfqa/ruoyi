from pathlib import Path
import logging
from typing import Any

import pdfplumber

from data_service.models.schemas import SourceRef, TableData


logger = logging.getLogger(__name__)


def _clean(value: object) -> str:
    return " ".join(str(value or "").split())


def extract_pdf_tables(
    path: str | Path,
    *,
    warnings: list[dict[str, Any]] | None = None,
) -> list[TableData]:
    pdf_path = Path(path)
    output: list[TableData] = []
    with pdfplumber.open(pdf_path) as pdf:
        for page_number, page in enumerate(pdf.pages, start=1):
            try:
                located_tables = page.find_tables()
                page_tables = [(table.extract(), table.bbox) for table in located_tables]
                if not page_tables:
                    page_tables = [(table, None) for table in page.extract_tables()]
            except Exception as exc:
                _record_page_warning(warnings, page_number, exc)
                continue
            for index, (table, bbox) in enumerate(page_tables, start=1):
                try:
                    clean_rows = [[_clean(cell) for cell in row] for row in table if row and any(cell for cell in row)]
                    if len(clean_rows) < 2:
                        continue
                    columns = [value or f"column_{i + 1}" for i, value in enumerate(clean_rows[0])]
                    rows = [{columns[i]: row[i] if i < len(row) else "" for i in range(len(columns))} for row in clean_rows[1:]]
                    preceding = (page.extract_text() or "").splitlines()
                    title = next((line.strip() for line in preceding if "销量预测" in line or "表" in line), f"第{page_number}页表格{index}")
                    source_text = ""
                    if bbox is not None:
                        source_text = (page.within_bbox(bbox).extract_text() or "").strip()
                    output.append(TableData(
                        title=title,
                        columns=columns,
                        rows=rows,
                        source=SourceRef(file=pdf_path.name, page=page_number),
                        metadata={
                            "extractor": "pdfplumber",
                            "bbox": list(bbox) if bbox is not None else None,
                            "source_text": source_text,
                        },
                    ))
                except Exception as exc:
                    _record_page_warning(warnings, page_number, exc, table_index=index)
    return output


def _record_page_warning(
    warnings: list[dict[str, Any]] | None,
    page_number: int,
    exc: Exception,
    *,
    table_index: int | None = None,
) -> None:
    detail = f"第{page_number}页表格提取失败"
    if table_index is not None:
        detail += f"（表格{table_index}）"
    detail += f"：{type(exc).__name__}"
    logger.warning("[PDF_TABLE] %s", detail, exc_info=True)
    if warnings is not None:
        warnings.append({"code": "TABLE_EXTRACTION_ERROR", "page": page_number, "message": detail})

