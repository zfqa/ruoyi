import os
import sys
import tempfile
import unittest

from openpyxl import Workbook

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.llm.client import LlmError, _parse_json_object
from app.llm.interpreter import interpret_table
from app.llm.prompts import build_table_prompt
from app.pipeline import parse_workbook
from app.table.detector import detect_tables


class FakeClient:
    available = True
    model = "test-model"

    def __init__(self, response=None, error=None):
        self.response = response
        self.error = error

    def complete_json(self, system_prompt, user_prompt):
        if self.error:
            raise self.error
        return self.response


def structure_response(**overrides):
    result = {
        "candidate_id": None,
        "is_table": True,
        "table_name": "年度面板出货量",
        "title_cells": ["A1"],
        "header": {"start_row": 2, "end_row": 2},
        "data": {"start_row": 3, "end_row": 4},
        "columns": [
            {"column": "A", "source_header": ["A2"], "header_path": ["年份"], "original_name": "年份", "standard_name": "year"},
            {"column": "B", "source_header": ["B2"], "header_path": ["厂商"], "original_name": "厂商", "standard_name": "manufacturer"},
            {"column": "C", "source_header": ["C2"], "header_path": ["出货量"], "original_name": "出货量", "standard_name": "shipment"},
        ],
        "footer_rows": [],
        "confidence": 0.96,
        "records": [{"shipment": 999999}],
    }
    result.update(overrides)
    return result


class LlmInterpreterTest(unittest.TestCase):
    def setUp(self):
        self.workbook = Workbook()
        self.sheet = self.workbook.active
        self.sheet.title = "Market"
        self.sheet.append(["年度面板出货量", None, None])
        self.sheet.append(["年份", "厂商", "出货量"])
        self.sheet.append([2025, "BOE", 10])
        self.sheet.append([2026, "TCL CSOT", 12])
        self.table = detect_tables(self.sheet, self.sheet.title)[0]

    def test_valid_structure_is_applied_without_accepting_values(self):
        interpreted = interpret_table(FakeClient(structure_response()), self.sheet, self.table)

        self.assertEqual("年度面板出货量", interpreted["llm_table_name"])
        self.assertEqual(["year", "manufacturer", "shipment"], [field["name"] for field in interpreted["fields"]])
        self.assertNotIn("records", interpreted)

    def test_out_of_range_structure_is_rejected(self):
        response = structure_response(data={"start_row": 3, "end_row": 999})
        with self.assertRaises(LlmError):
            interpret_table(FakeClient(response), self.sheet, self.table)

    def test_pipeline_falls_back_to_rules_when_llm_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "market.xlsx")
            self.workbook.save(path)
            result = parse_workbook(path, use_llm=True, llm_client=FakeClient(error=LlmError("mock failure")))

        self.assertFalse(result["quality"]["llm_used"])
        self.assertEqual(1, result["quality"]["llm_fallback_count"])
        self.assertTrue(result["sheets"][0]["tables"][0]["records"])

    def test_pipeline_limits_llm_calls_and_uses_rule_fallback(self):
        class CountingClient(FakeClient):
            def __init__(self):
                super().__init__(response=structure_response())
                self.calls = 0

            def complete_json(self, system_prompt, user_prompt):
                self.calls += 1
                return self.response

        client = CountingClient()
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "market.xlsx")
            self.workbook.save(path)
            result = parse_workbook(path, use_llm=True, llm_client=client, max_llm_tables=0)

        self.assertEqual(0, client.calls)
        self.assertEqual(0, result["quality"]["llm_attempt_count"])
        self.assertEqual(1, result["quality"]["llm_skipped_count"])
        self.assertTrue(result["sheets"][0]["tables"][0]["records"])

    def test_json_code_fence_is_supported(self):
        self.assertEqual({"is_table": True}, _parse_json_object("```json\n{\"is_table\": true}\n```"))

    def test_table_prompt_has_a_hard_size_limit(self):
        for row in range(1, 50):
            for column in range(1, 31):
                self.sheet.cell(row, column).value = "x" * 200
        table = detect_tables(self.sheet, self.sheet.title)[0]
        self.assertLessEqual(len(build_table_prompt(self.sheet, table)), 20000)


if __name__ == "__main__":
    unittest.main()
