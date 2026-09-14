from pathlib import Path

import pdfplumber

from data_service.models.schemas import DocumentChunk


def parse_pdf_pages(path: str | Path) -> list[DocumentChunk]:
    """Use pdfplumber as a layout-aware fallback/companion to pypdf."""
    pdf_path = Path(path)
    chunks: list[DocumentChunk] = []
    with pdfplumber.open(pdf_path) as pdf:
        for page_number, page in enumerate(pdf.pages, start=1):
            content = (page.extract_text() or "").strip()
            if content:
                chunks.append(DocumentChunk(content=content, source={"file": pdf_path.name, "page": page_number}, page=page_number, metadata={"file_type": "pdf", "extractor": "pdfplumber"}))
    return chunks


