"""Horizon / Omdia filename / merge correctness for automotive display reports."""
from __future__ import annotations

import unittest

from app.report.competitive_insight import _resolve_horizon_meta
from app.report.metrics import _detect_summary_mode
from app.report.periods import (
    latest_omdia_data_through,
    parse_omdia_tracker_meta,
    workbook_overlay_rank,
)
from app.report.record_merge import merge_records_by_year_quarter


class OmdiaMetaTests(unittest.TestCase):
    def test_with_3q25_results_is_not_full_year(self):
        meta = parse_omdia_tracker_meta(
            "Automotive Display Market Tracker - 4Q25 (with 3Q25 Results).xlsx"
        )
        self.assertEqual(meta["data_through_label"], "3Q25")
        self.assertEqual(meta["publication_label"], "4Q25")

    def test_1q26_through_4q25(self):
        meta = parse_omdia_tracker_meta("Automotive Display Market Tracker - 1Q26.xlsx")
        self.assertEqual(meta["data_through_label"], "4Q25")
        self.assertEqual(meta["publication_label"], "1Q26")

    def test_latest_picks_newer_through(self):
        latest = latest_omdia_data_through(
            [
                "Tracker 4Q25 (with 3Q25 Results).xlsx",
                "Tracker 1Q26.xlsx",
            ]
        )
        self.assertEqual(latest["data_through_label"], "4Q25")

    def test_overlay_rank_orders_oldest_first(self):
        older = workbook_overlay_rank("Tracker 4Q25 (with 3Q25 Results).xlsx")
        newer = workbook_overlay_rank("Tracker 1Q26.xlsx")
        self.assertLess(older, newer)


class SummaryModeTests(unittest.TestCase):
    def test_filename_3q25_beats_pivot_q4(self):
        rows = [
            {"year": 2025, "quarter": q}
            for q in (1, 2, 3, 4)
        ]
        qs, full_year, mode, target = _detect_summary_mode(rows, 2025, 3)
        self.assertFalse(full_year)
        self.assertEqual(mode, "y25_q1_q3")
        self.assertEqual(target, 2025)
        self.assertEqual(qs, {1, 2, 3})

    def test_filename_4q25_is_full_year(self):
        rows = [{"year": 2025, "quarter": 1}]
        qs, full_year, mode, target = _detect_summary_mode(rows, 2025, 4)
        self.assertTrue(full_year)
        self.assertEqual(mode, "y25_full_year")
        self.assertEqual(qs, {1, 2, 3, 4})


class HorizonMetaTests(unittest.TestCase):
    def test_metrics_data_through_drives_framing(self):
        metrics = {
            "scope": {
                "full_year": False,
                "summary_mode": "y25_q1_q3",
                "summary_target_year": 2025,
                "data_through_year": 2025,
                "data_through_quarter": 3,
            }
        }
        framing = _resolve_horizon_meta(
            {"file_name": "Tracker 4Q25 (with 3Q25 Results).xlsx"},
            metrics,
        )
        self.assertFalse(framing["full_year"])
        self.assertEqual(framing["data_through_quarter"], 3)
        self.assertEqual(framing["report_horizon"], "y25_q1_q3")

    def test_q1_q2_mode_not_forced_to_q3(self):
        metrics = {
            "scope": {
                "full_year": False,
                "summary_mode": "y25_q1_q2",
                "summary_target_year": 2025,
                "data_through_year": 2025,
                "data_through_quarter": 2,
            }
        }
        framing = _resolve_horizon_meta({"file_name": "ignored.xlsx"}, metrics)
        self.assertEqual(framing["data_through_quarter"], 2)
        self.assertFalse(framing["full_year"])

    def test_newer_omdia_file_refreshes_full_year(self):
        metrics = {
            "scope": {
                "full_year": False,
                "summary_mode": "y25_q1_q3",
                "summary_target_year": 2025,
                "data_through_year": 2025,
                "data_through_quarter": 3,
            }
        }
        framing = _resolve_horizon_meta(
            {
                "source_workbooks": [
                    {"file_name": "Tracker 4Q25 (with 3Q25 Results).xlsx"},
                    {"file_name": "Tracker 1Q26.xlsx"},
                ]
            },
            metrics,
        )
        self.assertTrue(framing["full_year"])
        self.assertEqual(framing["data_through_quarter"], 4)
        self.assertEqual(framing["report_horizon"], "y25_full_year")


class MergeTests(unittest.TestCase):
    def test_newer_group_replaces_same_year_quarter(self):
        older = [{"year": 2025, "quarter": "Q1", "value": 1, "id": "old"}]
        newer = [{"year": 2025, "quarter": "Q1", "value": 2, "id": "new"}]
        gap = [{"year": 2024, "quarter": "Q4", "value": 9, "id": "gap"}]
        merged = merge_records_by_year_quarter(
            [("older.xlsx", older + gap), ("newer.xlsx", newer)]
        )
        by_id = {row["id"]: row for row in merged}
        self.assertIn("new", by_id)
        self.assertNotIn("old", by_id)
        self.assertIn("gap", by_id)


if __name__ == "__main__":
    unittest.main()
