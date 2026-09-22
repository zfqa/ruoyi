# -*- coding: utf-8 -*-
from __future__ import annotations

import unittest

from app.report.driver_narratives import build_driver_narratives


def _size_row(row_key, technology, label, makers):
    return {
        "row_key": row_key,
        "technology": technology,
        "label": label,
        "is_total": False,
        "makers": {
            name: {
                "values": {"2025": values[0]},
                "yoy_2025_vs_2024": values[1],
                "growth_contribution_2025_vs_2024": values[2],
            }
            for name, values in makers.items()
        },
    }


class DriverNarrativesTest(unittest.TestCase):
    def test_tianma_style_essays_cover_three_drivers(self):
        size_rows = [
            _size_row("ltps.[12,15)", "LTPS", '12”-15”', {
                "Tianma": (4000, 1.27, 0.45),
                "BOE": (2000, 0.21, 0.10),
            }),
            _size_row("a_si.<8", "a-Si", '8”以下', {
                "Tianma": (8000, 0.11, 0.21),
                "BOE": (7934, -0.17, -0.32),
            }),
            _size_row("a_si.[8,12)", "a-Si", '8”-12”', {
                "Tianma": (5000, -0.08, -0.13),
                "BOE": (7000, 0.06, 0.10),
            }),
            _size_row("a_si.[12,15)", "a-Si", '12”-15”', {
                "Tianma": (1000, 0.29, 0.05),
                "BOE": (2000, 0.05, 0.04),
            }),
            _size_row("ltps.<8", "LTPS", '8”以下', {
                "Tianma": (1200, 0.3, 0.05),
                "BOE": (500, 1.52, 0.35),
            }),
        ]
        product = {
            "technology_history": {
                "LTPS": {"yoy_periods": {"Y25Q1-Q3": 0.68}},
                "a-Si": {"yoy_periods": {"Y25Q1-Q3": 0.05}},
            }
        }
        boe_product = {
            "technology_history": {
                "LTPS": {"yoy_periods": {"Y25Q1-Q3": 0.70}},
                "a-Si": {"yoy_periods": {"Y25Q1-Q3": 0.01}},
            }
        }
        customer = {
            "top_clients": {
                "clients": [
                    {
                        "client": "Nippon Seiki",
                        "periods": {"Y25Q1-Q3": 2600},
                        "yoy_primary": 1.09,
                        "growth_contribution_primary": 0.27,
                        "share_primary": 0.083,
                        "share_y24_q1_q3": 0.047,
                        "share_change_points": 0.036,
                    },
                    {
                        "client": "Continental AG",
                        "periods": {"Y25Q1-Q3": 3406},
                        "yoy_primary": -0.16,
                        "growth_contribution_primary": -0.12,
                        "share_primary": 0.11,
                        "share_y24_q1_q3": 0.15,
                        "share_change_points": -0.04,
                    },
                ]
            },
            "regions": {
                "rows": [
                    {"region": "日系", "q1_q3": {"Y25Q1-Q3": 7413, "yoy_2025_vs_2024": 0.011}},
                    {"region": "欧系", "q1_q3": {"Y25Q1-Q3": 6303, "yoy_2025_vs_2024": -0.05}},
                    {"region": "美系", "q1_q3": {"Y25Q1-Q3": 4708, "yoy_2025_vs_2024": 0.02}},
                    {"region": "中系", "q1_q3": {"Y25Q1-Q3": 4068, "yoy_2025_vs_2024": 0.1}},
                ]
            },
        }
        boe_customer = {
            "regions": {
                "rows": [
                    {"region": "日系", "q1_q3": {"Y25Q1-Q3": 1000, "yoy_2025_vs_2024": 0.1}},
                    {"region": "欧系", "q1_q3": {"Y25Q1-Q3": 6750, "yoy_2025_vs_2024": 0.2}},
                    {"region": "美系", "q1_q3": {"Y25Q1-Q3": 1200, "yoy_2025_vs_2024": 0.1}},
                    {"region": "中系", "q1_q3": {"Y25Q1-Q3": 9000, "yoy_2025_vs_2024": 0.3}},
                ]
            }
        }
        application = {
            "application_history": {
                "series": [
                    {
                        "application": "仪表",
                        "periods": {"Y25Q1-Q3": 17277},
                        "share_primary": 0.54,
                        "growth_contribution_primary": 0.66,
                        "yoy_periods": {"Y25Q1-Q3": 0.18},
                    },
                    {
                        "application": "中控",
                        "periods": {"Y25Q1-Q3": 10400},
                        "share_primary": 0.33,
                        "growth_contribution_primary": 0.07,
                        "yoy_periods": {"Y25Q1-Q3": 0.03},
                        "display_area": {"growth_contribution_primary": 0.52},
                    },
                ]
            }
        }
        boe_application = {
            "application_history": {
                "series": [
                    {"application": "仪表", "periods": {"Y25Q1-Q3": 5000}},
                    {"application": "中控", "periods": {"Y25Q1-Q3": 14000}},
                ]
            }
        }

        essays = build_driver_narratives(
            "Tianma",
            full_year=False,
            size_rows=size_rows,
            product=product,
            customer=customer,
            application=application,
            boe_customer=boe_customer,
            boe_application=boe_application,
            boe_product=boe_product,
        )

        self.assertIn("12”-15”", essays["product"])
        self.assertIn("+127%", essays["product"])
        self.assertIn("45%", essays["product"])
        self.assertIn("Nippon Seiki", essays["customer"])
        self.assertIn("Continental AG", essays["customer"])
        self.assertIn("仪表", essays["application"])
        self.assertIn("54%", essays["application"])
        self.assertIn("相较于BOE", essays["product"])
        self.assertIn("相较于BOE", essays["customer"])
        self.assertIn("相较于BOE", essays["application"])


if __name__ == "__main__":
    unittest.main()
