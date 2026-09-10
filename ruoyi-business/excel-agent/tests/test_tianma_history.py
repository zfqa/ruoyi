import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.report.tianma_history import calculate_tianma_history_metrics


def record(year, quarter, maker, shipment, area, application="Center stack display", technology="a-Si"):
    values = {
        "year": year,
        "quarter": quarter,
        "maker": maker,
        "quantity_000": shipment,
        "display_area": area,
        "original_specification": "Automobile monitor",
        "application": application,
        "technology": technology,
    }
    return {
        name: {
            "value": value,
            "source_sheet": "Shipment share",
            "source_cell": f"pivotCacheDefinition1.xml#{year}-{quarter}-{maker}-{name}",
        }
        for name, value in values.items()
    }


class TianmaHistoryMetricsTest(unittest.TestCase):
    def test_combines_baseline_y22_with_current_y23_y25_and_calculates_shares(self):
        baseline = [
            record(2022, "1Q", "Tianma", 50, 5),
            record(2022, "1Q", "AUO", 50, 5),
        ]
        current = [
            record(2023, "1Q", "Tianma", 80, 8),
            record(2023, "1Q", "AUO", 120, 12),
            record(2024, "1Q", "Tianma", 100, 10),
            record(2024, "1Q", "AUO", 100, 10),
            record(2025, "1Q", "Tianma", 30, 3),
            record(2025, "1Q", "AUO", 70, 7),
            record(2025, "2Q", "Tianma", 40, 4),
            record(2025, "2Q", "AUO", 60, 6),
            record(2025, "4Q", "Tianma", 50, 5),
            record(2025, "4Q", "AUO", 50, 5),
            record(2025, "1Q", "Tianma", 999, 99, "Automobile monitor (Others)"),
        ]

        metrics = calculate_tianma_history_metrics(current, baseline)

        self.assertEqual(50, metrics["shipment"]["periods"]["Y22"])
        self.assertEqual(120, metrics["shipment"]["periods"]["Y25F"])
        self.assertEqual(70, metrics["shipment"]["periods"]["Y25Q1-Q3"])
        self.assertEqual(0.2, metrics["shipment"]["standard_y25f_yoy"])
        self.assertEqual(0.2, metrics["shipment"]["yoy_periods"]["Y25F"])
        self.assertEqual(-0.3, metrics["shipment"]["yoy_periods"]["Y25Q1-Q3"])
        self.assertEqual(0.583333, metrics["shipment"]["forecast_completion_y25_q1_q3"])
        self.assertEqual(0.4, metrics["shipment_share"]["periods"]["Y25F"])
        self.assertEqual(0.35, metrics["shipment_share"]["periods"]["Y25Q1-Q3"])
        self.assertEqual(100, metrics["shipment"]["comparison_periods"]["Y24Q1-Q3"])
        self.assertEqual(70, metrics["shipment"]["comparison_periods"]["Y25Q1-Q3"])
        self.assertEqual(-0.3, metrics["shipment"]["yoy_2025_q1_q3_vs_2024_q1_q3"])
        self.assertEqual(12, metrics["display_area"]["periods"]["Y25F"])
        self.assertEqual([], metrics["data_gaps"])

    def test_missing_baseline_is_an_explicit_gap_and_not_imputed(self):
        metrics = calculate_tianma_history_metrics([record(2025, "1Q", "Tianma", 30, 3)])

        self.assertIsNone(metrics["shipment"]["periods"]["Y22"])
        self.assertTrue(any("Y22" in gap for gap in metrics["data_gaps"]))

    def test_csot_uses_china_star_alias_and_market_denominator(self):
        current = [
            record(2024, "1Q", "China Star", 40, 8),
            record(2024, "1Q", "BOE", 60, 12),
            record(2025, "1Q", "China Star", 80, 20),
            record(2025, "1Q", "BOE", 120, 30),
        ]
        metrics = calculate_tianma_history_metrics(current, [], "CSOT")
        self.assertEqual(80, metrics["shipment"]["periods"]["Y25Q1-Q3"])
        self.assertEqual(0.4, metrics["shipment_share"]["periods"]["Y25Q1-Q3"])
        self.assertEqual("CSOT", metrics["scope"]["maker"])

    def test_market_share_keeps_all_technologies_in_denominator_only(self):
        current = [
            record(2025, "Q1", "BOE", 100, 10, technology="a-Si"),
            record(2025, "Q1", "BOE", 25, 5, technology="Oxide"),
            record(2025, "Q1", "AUO", 100, 10, technology="LTPS"),
        ]
        metrics = calculate_tianma_history_metrics(current, [], "BOE")
        self.assertEqual(100, metrics["shipment"]["periods"]["Y25Q1-Q3"])
        self.assertEqual(10, metrics["display_area"]["periods"]["Y25Q1-Q3"])
        self.assertEqual(0.444444, metrics["shipment_share"]["periods"]["Y25Q1-Q3"])
        self.assertEqual(0.4, metrics["display_area_share"]["periods"]["Y25Q1-Q3"])
        self.assertEqual(
            "all technologies", metrics["scope"]["share_market_denominator_technology_scope"]
        )


if __name__ == "__main__":
    unittest.main()
