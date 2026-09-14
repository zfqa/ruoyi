from pathlib import Path

from pypdf import PdfReader

from data_service.models.schemas import DocumentChunk


def load_pdf_text(path: str | Path) -> list[DocumentChunk]:
    """Extract text page by page with durable file/page provenance."""
    pdf_path = Path(path)
    reader = PdfReader(str(pdf_path))
    chunks = []
    for page_number, page in enumerate(reader.pages, start=1):
        content = (page.extract_text() or "").strip()
        if content:
            chunks.append(DocumentChunk(content=content, source={"file": pdf_path.name, "page": page_number}, page=page_number, metadata={"file_type": "pdf", "extractor": "pypdf"}))
    return chunks


