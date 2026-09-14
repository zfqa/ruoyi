"""Unified preflight validation for supported document types."""
from __future__ import annotations

import logging
import zipfile
from pathlib import Path
from typing import Any

from pptx import Presentation
from pydantic import BaseModel, Field

from data_service.validator.pdf_validator import validate_pdf

logger = logging.getLogger(__name__)

class DocumentValidationWarning(BaseModel):
    code: str
    message: str
    page: int | None = None
    slide: int | None = None


class DocumentValidationResult(BaseModel):
    supported: bool
    document_type: str
    reason_code: str | None = None
    reason: str | None = None
    message: str | None = None
    suggestion: str | None = None
    detail: str | None = None
    warnings: list[DocumentValidationWarning] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)


_REASON_DETAILS: dict[str, tuple[str, str]] = {
    "scan_pdf": ("该PDF为扫描件，当前版本未启用OCR能力，无法提取正文", "请上传文字版PDF或启用OCR解析"),
    "encrypted_pdf": ("该PDF已加密，无法读取内容", "请解除PDF密码后重新上传"),
    "complex_table": ("检测到复杂表格结构，当前版本无法稳定解析", "建议提供Excel原始文件或简化表格结构"),
    "parse_error": ("文档解析失败", "请确认文件未损坏且格式正确后重新上传"),
}


def _result(
    *,
    supported: bool,
    document_type: str,
    reason: str | None = None,
    detail: str | None = None,
    warnings: list[DocumentValidationWarning] | None = None,
    metadata: dict[str, Any] | None = None,
) -> DocumentValidationResult:
    message, suggestion = _REASON_DETAILS.get(reason or "", (None, None))
    return DocumentValidationResult(
        supported=supported,
        document_type=document_type,
        reason_code=reason.upper() if reason else None,
        reason=reason,
        message=message,
        suggestion=suggestion,
        detail=detail,
        warnings=warnings or [],
        metadata=metadata or {},
    )


def _pptx_unsupported(reason: str, detail: str, metadata: dict[str, Any]) -> DocumentValidationResult:
    logger.info("[PPTX VALIDATION] supported=false reason=%s metadata=%s", reason, metadata)
    return _result(supported=False, document_type="pptx", reason=reason, detail=detail, metadata=metadata)


def _is_complex_table(table: object) -> bool:
    """Warn only when the PPTX table cannot be read as a stable rectangle.

    Grouped data tables deliberately leave repeated category cells blank;
    empty cells and ordinary merged presentation cells must not trigger a
    false COMPLEX_TABLE warning when the complete grid remains readable.
    """
    try:
        rows = list(table.rows)
        column_count = len(table.columns)
        if len(rows) < 2 or column_count < 1:
            return True
        for row in rows:
            cells = list(row.cells)
            if len(cells) != column_count:
                return True
            # Force every cell to be read. An exception signals an unstable
            # merge/split structure that this POC should warn about.
            for cell in cells:
                _ = cell.text
        return False
    except Exception:
        return True


def validate_pptx(path: str | Path) -> DocumentValidationResult:
    """Reject protected/image-only decks and report, rather than fail on, complex tables."""
    pptx_path = Path(path)
    metadata: dict[str, Any] = {"slides": 0, "text_slides": 0, "empty_slides": 0}
    warnings: list[DocumentValidationWarning] = []
    try:
        prefix = pptx_path.read_bytes()[:8]
        if prefix.startswith(b"\xd0\xcf\x11\xe0"):
            return _pptx_unsupported("parse_error", "检测到PPTX受到保护，当前系统无法解析。", metadata)
        if not zipfile.is_zipfile(pptx_path):
            return _pptx_unsupported("parse_error", "PPTX文件损坏或不是有效的PPTX文件。", metadata)
        try:
            presentation = Presentation(str(pptx_path))
        except Exception:
            # A valid ZIP that cannot be opened as an OOXML presentation is commonly protected.
            logger.exception("[PPTX VALIDATION] unable to open presentation: %s", pptx_path.name)
            return _pptx_unsupported("parse_error", "检测到PPTX受到保护，当前系统无法解析。", metadata)

        metadata["slides"] = len(presentation.slides)
        for slide_number, slide in enumerate(presentation.slides, start=1):
            has_text = False
            for shape in slide.shapes:
                if shape.has_table:
                    if _is_complex_table(shape.table):
                        warnings.append(DocumentValidationWarning(code="COMPLEX_TABLE", slide=slide_number, message="检测到复杂表格，当前POC无法保证表格结构准确性。"))
                    if any(str(cell.text or "").strip() for row in shape.table.rows for cell in row.cells):
                        has_text = True
                elif shape.has_text_frame and str(shape.text or "").strip():
                    has_text = True
            if has_text:
                metadata["text_slides"] += 1
            else:
                metadata["empty_slides"] += 1
        if metadata["slides"] == 0:
            return _pptx_unsupported("parse_error", "PPTX文件损坏或不包含可读取的幻灯片。", metadata)
        if metadata["text_slides"] == 0:
            return _pptx_unsupported("parse_error", "未检测到可解析文本内容，可能为图片型PPT。", metadata)
        if warnings:
            return _result(
                supported=False,
                document_type="pptx",
                reason="complex_table",
                warnings=warnings,
                metadata=metadata,
            )
        logger.info("[PPTX VALIDATION] file=%s slides=%s text_slides=%s supported=true", pptx_path.name, metadata["slides"], metadata["text_slides"])
        for warning in warnings:
            logger.warning("[PPTX VALIDATION][WARNING] slide=%s code=%s", warning.slide, warning.code)
        return DocumentValidationResult(supported=True, document_type="pptx", warnings=warnings, metadata=metadata)
    except OSError:
        logger.exception("[PPTX VALIDATION] file read error: %s", pptx_path.name)
        return _pptx_unsupported("parse_error", "PPTX文件损坏或无法读取。", metadata)
    except Exception as exc:
        logger.exception("[PPTX VALIDATION] validation error: %s", pptx_path.name)
        return _pptx_unsupported("parse_error", str(exc), metadata)


def validate_document(path: str | Path) -> DocumentValidationResult:
    document_path = Path(path)
    suffix = document_path.suffix.lower()
    if suffix == ".pdf":
        result = validate_pdf(document_path)
        warnings = [DocumentValidationWarning(code=item.code, page=item.page, message=item.message) for item in result.warnings]
        reason_map = {
            "SCANNED_PDF": "scan_pdf",
            "ENCRYPTED_PDF": "encrypted_pdf",
        }
        reason = reason_map.get(result.reason_code or "", "parse_error" if not result.supported else None)
        if not result.supported:
            return _result(
                supported=False,
                document_type="pdf",
                reason=reason,
                detail=result.message,
                warnings=warnings,
                metadata=result.metadata,
            )
        return _result(
            supported=True,
            document_type="pdf",
            warnings=warnings,
            metadata=result.metadata,
        )
    if suffix == ".pptx":
        return validate_pptx(document_path)
    if suffix == ".txt":
        return _result(supported=True, document_type="txt")
    return _result(supported=False, document_type=suffix.lstrip(".") or "unknown", reason="parse_error", detail="当前运行版本未找到该文件类型的解析器。")


