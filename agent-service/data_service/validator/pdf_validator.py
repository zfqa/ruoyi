from __future__ import annotations

import logging
from pathlib import Path
from typing import Any

import pdfplumber
from pydantic import BaseModel, Field
from pypdf import PdfReader

logger = logging.getLogger(__name__)

MIN_TEXT_PAGE_RATIO = 0.20
MIN_TEXT_CHARS_PER_PAGE = 20
COMPLEX_TABLE_EMPTY_CELL_RATIO = 0.55
COMPLEX_TABLE_ROW_LENGTH_VARIANCE = 0.25


class PDFValidationWarning(BaseModel):
    code: str
    page: int
    message: str


class PDFValidationResult(BaseModel):
    supported: bool
    reason_code: str | None = None
    message: str | None = None
    warnings: list[PDFValidationWarning] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)


def _complex_table(table: list[list[Any] | None]) -> bool:
    rows = [row for row in table if row]
    if len(rows) < 2:
        return False
    lengths = [len(row) for row in rows]
    if max(lengths) != min(lengths) and (max(lengths) - min(lengths)) / max(lengths) > COMPLEX_TABLE_ROW_LENGTH_VARIANCE:
        return True
    cells = [cell for row in rows for cell in row]
    if cells and sum(1 for cell in cells if not str(cell or "").strip()) / len(cells) > COMPLEX_TABLE_EMPTY_CELL_RATIO:
        return True
    return False


def _unsupported(reason_code: str, message: str, metadata: dict[str, Any]) -> PDFValidationResult:
    logger.info("[PDF VALIDATION] supported=false reason=%s metadata=%s", reason_code, metadata)
    return PDFValidationResult(supported=False, reason_code=reason_code, message=message, metadata=metadata)


def validate_pdf(path: str | Path) -> PDFValidationResult:
    """Classify unsupported/low-reliability PDFs before the normal parser runs."""
    pdf_path = Path(path)
    metadata: dict[str, Any] = {"pages": 0, "text_pages": 0, "empty_pages": 0, "is_encrypted": False}
    warnings: list[PDFValidationWarning] = []
    try:
        reader = PdfReader(str(pdf_path))
        metadata["is_encrypted"] = bool(reader.is_encrypted)
        if reader.is_encrypted:
            try:
                decrypted = reader.decrypt("")
            except Exception:
                decrypted = 0
            if not decrypted:
                return _unsupported("ENCRYPTED_PDF", "检测到PDF已加密，当前系统无法解析受保护内容。", metadata)

        metadata["pages"] = len(reader.pages)
        if not metadata["pages"]:
            return _unsupported("INVALID_PDF", "PDF文件损坏或无法正常读取。", metadata)

        text_chars = 0
        image_pages = 0
        with pdfplumber.open(pdf_path) as pdf:
            for page_number, page in enumerate(pdf.pages, start=1):
                text = (page.extract_text() or "").strip()
                if text:
                    metadata["text_pages"] += 1
                    text_chars += len(text)
                else:
                    metadata["empty_pages"] += 1
                if page.images:
                    image_pages += 1
                try:
                    raw_tables = page.extract_tables()
                    if len(raw_tables) > 3 or any(_complex_table(table) for table in raw_tables):
                        warnings.append(PDFValidationWarning(code="COMPLEX_TABLE", page=page_number, message="检测到疑似复杂表格，当前POC无法保证表格结构解析准确性。"))
                except Exception:
                    warnings.append(PDFValidationWarning(code="COMPLEX_TABLE", page=page_number, message="表格结构提取异常，当前POC无法保证表格结构解析准确性。"))

        metadata["total_text_chars"] = text_chars
        metadata["text_page_ratio"] = round(metadata["text_pages"] / metadata["pages"], 3)
        metadata["average_text_chars_per_page"] = round(text_chars / metadata["pages"], 1)
        if metadata["text_page_ratio"] < MIN_TEXT_PAGE_RATIO or metadata["average_text_chars_per_page"] < MIN_TEXT_CHARS_PER_PAGE:
            if image_pages:
                return _unsupported("SCANNED_PDF", "检测到该PDF可能为扫描件，当前POC仅支持文本型PDF解析。", metadata)
            return _unsupported("NO_TEXT_CONTENT", "未检测到足够的可解析文本内容。", metadata)

        logger.info("[PDF VALIDATION] file=%s pages=%s text_pages=%s text_ratio=%s encrypted=%s supported=true", pdf_path.name, metadata["pages"], metadata["text_pages"], metadata["text_page_ratio"], metadata["is_encrypted"])
        for warning in warnings:
            logger.warning("[PDF VALIDATION][WARNING] page=%s code=%s", warning.page, warning.code)
        return PDFValidationResult(supported=True, warnings=warnings, metadata=metadata)
    except Exception:
        logger.exception("[PDF VALIDATION] validation error for file=%s", pdf_path.name)
        return PDFValidationResult(supported=False, reason_code="PDF_VALIDATION_ERROR", message="PDF预检查失败，当前无法确认该文件是否可解析。", metadata=metadata)


