import unittest

from data_service.chunker.semantic_chunker import SemanticChunker
from data_service.models.schemas import DocumentChunk, SourceRef, TableData


class PdfTableProvenanceTest(unittest.TestCase):
    def test_bbox_source_text_makes_pdf_table_traceable_and_indexable(self):
        page = DocumentChunk(
            content="销量表\n厂商 2024 2025\nTianma 100 120",
            source=SourceRef(file="market.pdf", page=7),
            page=7,
            metadata={"file_type": "pdf"},
        )
        table = TableData(
            title="销量表",
            columns=["厂商", "2024", "2025"],
            rows=[{"厂商": "Tianma", "2024": "100", "2025": "120"}],
            source=SourceRef(file="market.pdf", page=7),
            metadata={
                "extractor": "pdfplumber",
                "bbox": [10, 20, 300, 180],
                "source_text": "厂商 2024 2025\nTianma 100 120",
            },
        )

        chunks = SemanticChunker().chunk([page], [table], [])
        table_chunk = next(item for item in chunks if item.chunk_type == "table")

        self.assertTrue(table_chunk.metadata["indexable"])
        self.assertTrue(table_chunk.metadata["source_available"])
        self.assertEqual(7, table_chunk.source.page_start)
        self.assertEqual("厂商 2024 2025\nTianma 100 120", table_chunk.source.source_text)
        self.assertIn("Tianma", table_chunk.content)
        self.assertIn("行: Tianma | 列: 2025 | 值: 120", table_chunk.content)
        self.assertNotIn("column_1", table_chunk.content)


if __name__ == "__main__":
    unittest.main()
