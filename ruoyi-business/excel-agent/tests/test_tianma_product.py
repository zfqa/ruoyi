import os
import sys
import unittest


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.report.tianma_product import calculate_tianma_product_metrics


def record(year, quarter, technology, size, shipment, maker="Tianma", application="Center stack display"):
    values = {
        "year": year,
        "quarter": quarter,
        "maker": maker,
        "quantity_000": shipment,
        "technology": technology,
        "size": size,
        "original_specification": "Automobile monitor",
        "application": application,
    }
    return {
        name: {
            "value": value,
            "source_sheet": "Shipment",
            "source_cell": f"pivot-cache:{year}-{quarter}-{technology}-{size}-{name}",
        }
        for name, value in values.items()
    }


class TianmaProductMetricsTest(unittest.TestCase):
    def test_technology_history_exact_sizes_and_boundary_buckets(self):
        baseline = [
            record(2022, "Q1", "LTPS TFT LCD", 7.9, 10),
            record(2022, "Q1", "a-Si TFT LCD", 8, 20),
        ]
        current = [
            record(2023, "Q1", "LTPS TFT LCD", 7.9, 20),
            record(2024, "Q1", "LTPS TFT LCD", 7.9, 100),
            record(2024, "Q1", "a-Si TFT LCD", 12, 50),
            record(2025, "Q1", "LTPS TFT LCD", 7.9, 60),
            record(2025, "Q2", "LTPS TFT LCD", 8, 40),
            record(2025, "Q3", "LTPS TFT LCD", 12, 20),
            record(2025, "Q4", "LTPS TFT LCD", 15, 30),
            record(2025, "Q1", "a-Si TFT LCD", 12, 75),
            record(2025, "Q1", "LTPS TFT LCD", 7.9, 999, application="Automobile monitor (Others)"),
            record(2025, "Q1", "LTPS TFT LCD", 7.9, 999, maker="AUO"),
        ]

        metrics = calculate_tianma_product_metrics(current, baseline)

        ltps = metrics["technology_history"]["LTPS"]
        self.assertEqual(150, ltps["periods"]["Y25F"])
        self.assertEqual(120, ltps["periods"]["Y25Q1-Q3"])
        self.assertEqual(0.5, ltps["standard_y25f_yoy"])
        self.assertEqual(0.2, ltps["yoy_periods"]["Y25Q1-Q3"])

        points = metrics["y25q1_q3_size_distribution"]["points"]
        self.assertEqual([7.9, 8, 12], [point["size"] for point in points])
        self.assertEqual([60, 40, 95], [point["shipment"] for point in points])

        growth = metrics["technology_size_growth"]["LTPS"]["series"]
        by_bucket = {item["size_bucket"]: item for item in growth}
        self.assertEqual(60, by_bucket["<8"]["periods"]["Y25F"])
        self.assertEqual(40, by_bucket["[8,12)"]["periods"]["Y25F"])
        self.assertEqual(20, by_bucket["[12,15)"]["periods"]["Y25F"])
        self.assertEqual(30, by_bucket[">=15"]["periods"]["Y25F"])
        self.assertEqual([], metrics["data_gaps"])

    def test_missing_baseline_is_reported(self):
        metrics = calculate_tianma_product_metrics([record(2025, "Q1", "LTPS", 10, 10)])
        self.assertIsNone(metrics["technology_history"]["LTPS"]["periods"]["Y22"])
        self.assertTrue(any("Y22" in gap for gap in metrics["data_gaps"]))

    def test_other_maker_is_selected_by_field_value_not_position(self):
        current = [
            record(2025, "Q1", "LTPS", 12.3, 25, maker="Tianma"),
            record(2025, "Q1", "LTPS", 15.6, 75, maker="BOE"),
        ]
        metrics = calculate_tianma_product_metrics(current, [], "BOE")
        self.assertEqual(75, metrics["technology_history"]["LTPS"]["periods"]["Y25Q1-Q3"])
        self.assertEqual([15.6], [item["size"] for item in metrics["y25q1_q3_size_distribution"]["points"]])


if __name__ == "__main__":
    unittest.main()
