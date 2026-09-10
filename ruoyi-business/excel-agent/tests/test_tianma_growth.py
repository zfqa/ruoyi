import os
import sys
import unittest


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.report.tianma_growth import (
    calculate_tianma_application_metrics,
    calculate_tianma_customer_metrics,
)


def facts(values, sheet="Shipment"):
    return {
        name: {"value": value, "source_sheet": sheet, "source_cell": f"cache:{name}:{value}"}
        for name, value in values.items()
    }


def supply(year, quarter, client, qty, technology="a-Si", maker="Tianma"):
    return facts({
        "year": year, "quarter": quarter, "panel_maker": maker, "client": client,
        "original_specification": "Automobile monitor", "product": "Instrument cluster",
        "technology": technology, "quantity_000": qty,
    }, "Panel maker to client pivot")


def shipment(year, quarter, application, size, qty, technology="a-Si", maker="Tianma"):
    return facts({
        "year": year, "quarter": quarter, "maker": maker,
        "original_specification": "Automobile monitor", "application": application,
        "technology": technology, "size": size, "quantity_000": qty,
    })


class TianmaGrowthMetricsTest(unittest.TestCase):
    def test_customer_top6_and_region_assignment(self):
        records = []
        for index, client in enumerate(("Continental AG", "Denso", "Visteon", "Yazaki", "Nippon Seiki", "BYD"), start=1):
            records.append(supply(2025, "Q1", client, 1000 - index))
        records.extend([
            supply(2025, "Q1", "Others", 9000),
            supply(2025, "Q1", "GM", 8000),
            supply(2024, "Q1", "Marelli", 100, "LTPS"),
            supply(2023, "Q1", "Marelli", 50, "LTPS"),
        ])

        metrics = calculate_tianma_customer_metrics(records)
        names = [item["client"] for item in metrics["top_clients"]["clients"]]
        self.assertEqual(["Continental AG", "Denso", "Visteon", "Yazaki", "Nippon Seiki", "BYD"], names)
        europe = next(row for row in metrics["regions"]["rows"] if row["region"] == "欧系")
        self.assertEqual(100, europe["annual"]["Y24"])
        self.assertEqual(1, europe["annual"]["yoy_2024_vs_2023"])
        first = metrics["top_clients"]["clients"][0]
        self.assertIsNotNone(first["share_y25_q1_q3"])

    def test_application_history_and_key_size_merges_technologies_before_threshold(self):
        baseline = [shipment(2022, "Q1", "Room mirror", 9, 100, maker="Demo")]
        current = [
            shipment(2024, "Q1", "Instrument cluster", 10.3, 500, "a-Si", "Demo"),
            shipment(2024, "Q1", "Instrument cluster", 10.3, 100, "LTPS", "Demo"),
            shipment(2025, "Q1", "Instrument cluster", 10.3, 900, "a-Si", "Demo"),
            shipment(2025, "Q2", "Instrument cluster", 10.3, 300, "LTPS", "Demo"),
            shipment(2025, "Q1", "Side mirror", 9, 50, "a-Si", "Demo"),
            shipment(2025, "Q1", "Room mirror", 9, 70, "a-Si", "Demo"),
        ]

        metrics = calculate_tianma_application_metrics(current, baseline, "Demo")
        mirror = next(item for item in metrics["application_history"]["series"] if item["application"] == "后视镜")
        self.assertEqual(120, mirror["periods"]["Y25Q1-Q3"])
        self.assertAlmostEqual(120 / 1320, mirror["share_y25_q1_q3"], places=6)
        self.assertAlmostEqual(5 / 6, next(item for item in metrics["application_history"]["series"] if item["application"] == "仪表")["growth_contribution_y25_q1_q3"], places=6)
        key = metrics["key_sizes"]["rows"][0]
        self.assertEqual("仪表", key["application"])
        self.assertEqual(1200, key["shipment"])
        self.assertEqual("LTPS/a-Si", key["technology"])
        self.assertEqual(1, key["yoy_2025_q1_q3_vs_2024_q1_q3"])
        mirror_key = next(item for item in metrics["key_sizes"]["rows"] if item["application"] == "后视镜")
        self.assertEqual(120, mirror_key["shipment"])

    def test_final_report_profile_uses_configured_technology_scope(self):
        current = [
            shipment(2024, "Q1", "Instrument cluster", 10.3, 1000, "a-Si"),
            shipment(2024, "Q1", "Instrument cluster", 10.3, 100, "LTPS"),
            shipment(2025, "Q1", "Instrument cluster", 10.3, 1200, "a-Si"),
            shipment(2025, "Q1", "Instrument cluster", 10.3, 500, "LTPS"),
            shipment(2025, "Q1", "Center stack display", 12.3, 200, "a-Si"),
            shipment(2025, "Q1", "Center stack display", 12.3, 1400, "LTPS"),
        ]

        metrics = calculate_tianma_application_metrics(current, [], "Tianma")
        rows = metrics["key_sizes"]["rows"]
        instrument = next(row for row in rows if row["application"] == "仪表")
        center = next(row for row in rows if row["application"] == "中控")
        self.assertEqual(1200, instrument["shipment"])
        self.assertEqual("a-Si", instrument["technology"])
        self.assertAlmostEqual(0.2, instrument["yoy_2025_q1_q3_vs_2024_q1_q3"])
        self.assertEqual(1400, center["shipment"])
        self.assertEqual("LTPS", center["technology"])
        self.assertEqual("final_report_v1", metrics["scope"]["key_size_profile"])

    def test_customer_region_uses_decision_location_and_excludes_oxide(self):
        records = [
            supply(2024, "Q1", "Shanghai GM", 190, "LTPS TFT LCD", "AUO"),
            supply(2025, "Q1", "Shanghai GM", 251, "LTPS TFT LCD", "AUO"),
            supply(2025, "Q1", "Visteon", 100, "Oxide TFT LCD", "AUO"),
        ]
        metrics = calculate_tianma_customer_metrics(records, "AUO")
        china = next(row for row in metrics["regions"]["rows"] if row["region"] == "中系")
        america = next(row for row in metrics["regions"]["rows"] if row["region"] == "美系")
        self.assertEqual(190, china["annual"]["Y24"])
        self.assertEqual(251, china["q1_q3"]["Y25Q1-Q3"])
        self.assertIsNone(america["q1_q3"]["Y25Q1-Q3"])
        visteon = next(item for item in metrics["top_clients"]["clients"] if item["client"] == "Visteon")
        self.assertEqual(100, visteon["periods"]["Y25Q1-Q3"])

    def test_application_history_excludes_oxide(self):
        current = [
            shipment(2025, "Q1", "Center stack display", 10.3, 100, "a-Si", "BOE"),
            shipment(2025, "Q1", "Center stack display", 12.3, 25, "Oxide", "BOE"),
        ]
        metrics = calculate_tianma_application_metrics(current, [], "BOE")
        center = next(item for item in metrics["application_history"]["series"] if item["application"] == "中控")
        self.assertEqual(100, center["periods"]["Y25Q1-Q3"])


if __name__ == "__main__":
    unittest.main()
