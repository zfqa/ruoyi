from pathlib import Path

from data_service.models.schemas import DocumentChunk


def load_text(path: str | Path) -> list[DocumentChunk]:
    text_path = Path(path)
    content = text_path.read_text(encoding="utf-8-sig").strip()
    return [DocumentChunk(content=content, source={"file": text_path.name}, metadata={"file_type": "txt"})] if content else []


