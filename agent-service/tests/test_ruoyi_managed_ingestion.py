from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from data_service.service.ingestion_service import ingest_document


class ManagedIngestionTest(unittest.TestCase):
    def test_external_managed_parse_never_writes_python_knowledge_stores(self) -> None:
        with TemporaryDirectory() as directory:
            source = Path(directory) / "sample.txt"
            source.write_text("Tianma 2025年车载显示出货增长20%，LTPS为主要增长来源。", encoding="utf-8")

            with patch("data_service.service.ingestion_service._extract_knowledge", return_value=[]), \
                 patch("data_service.service.ingestion_service._save_review_records", side_effect=AssertionError("review write")), \
                 patch("data_service.service.ingestion_service._index_knowledge", side_effect=AssertionError("chroma write")):
                result = ingest_document(source, index_to_kb=False, persist_review=False)

            self.assertEqual("success", result["status"])
            self.assertFalse(result["indexed"])
            self.assertEqual(0, result["review_count"])
            self.assertFalse(result["persist_review"])

    def test_uploaded_original_name_is_used_by_all_parsed_sources(self) -> None:
        with TemporaryDirectory() as directory:
            source = Path(directory) / "temporary-upload-name.txt"
            source.write_text("Tianma 2025年车载显示出货增长20%。", encoding="utf-8")

            with patch("data_service.service.ingestion_service._extract_knowledge", return_value=[]):
                result = ingest_document(
                    source,
                    source_file_name="市场报告原文件.pdf",
                    index_to_kb=False,
                    persist_review=False,
                )

            self.assertEqual("success", result["status"])
            self.assertTrue(result["chunk_data"])
            self.assertTrue(result["semantic_chunks"])
            self.assertTrue(all(item["source"]["file"] == "市场报告原文件.pdf"
                                for item in result["chunk_data"]))
            self.assertTrue(all(item["source"]["file"] == "市场报告原文件.pdf"
                                for item in result["semantic_chunks"]))


if __name__ == "__main__":
    unittest.main()
