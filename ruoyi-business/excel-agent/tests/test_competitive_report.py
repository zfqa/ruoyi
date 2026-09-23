import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.report.competitive_insight import generate_competitive_insight_report
from app.report.metrics import calculate_competitive_metrics
from app.llm.client import LlmError
from app.pipeline import _report_maker_names


class CompetitiveInsightReportTest(unittest.TestCase):
    def test_report_detail_scope_does_not_expand_to_every_market_maker(self):
        records = [
            {"maker": {"value": "Tianma"}},
            {"maker": {"value": "Stanley"}},
            {"maker": {"value": "Hosiden"}},
        ]
        self.assertEqual(
            ["Tianma", "AUO", "CSOT", "BOE"],
            _report_maker_names(records, []),
        )

    def test_fallback_preserves_template_logic_and_maker_order(self):
        parsed = {
            "workbook_id": "sha256:test",
            "file_name": "tracker.xlsx",
            "sheets": [{"sheet_name": "Shipment", "tables": []}],
        }
        report = generate_competitive_insight_report(parsed, use_llm=False)
        self.assertEqual("competitive_insight_v1", report["report_type"])
        self.assertEqual(["Tianma", "AUO", "CSOT", "BOE"], [item["maker"] for item in report["makers"]])
        self.assertEqual("2025 / 2024 - 1", report["methodology"]["formulas"]["yoy"])
        self.assertEqual(["<8", "[8,12)", "[12,15)", ">=15"], report["methodology"]["size_buckets"])
        self.assertGreaterEqual(len(report["methodology"]["notes"]), 8)
        self.assertIn("growth_contribution", report["methodology"]["formulas"])

    def test_llm_result_is_normalized_to_all_four_makers(self):
        class FakeClient:
            available = True
            model = "fake"
            user_prompt = None

            def complete_json(self, system_prompt, user_prompt, max_tokens=3000):
                self.user_prompt = user_prompt
                return {
                    "executive_summary": ["有证据的摘要"],
                    "maker_narratives": [{"maker": "BOE", "overview": ["BOE摘要"]}],
                    "data_gaps": ["缺少面积数据"],
                }

        client = FakeClient()
        parsed = {"workbook_id": "sha256:test", "file_name": "tracker.xlsx", "sheets": []}
        report = generate_competitive_insight_report(parsed, llm_client=client)
        self.assertEqual(4, len(report["makers"]))
        self.assertEqual(["BOE摘要"], report["makers"][3]["overview"])
        self.assertEqual("python_metrics_llm_narrative", report["quality"]["generation_mode"])
        self.assertIn('"computed_metrics"', client.user_prompt)
        self.assertNotIn('"source_facts"', client.user_prompt)
        self.assertFalse(report["quality"]["llm_performed_arithmetic"])

    def test_metric_ids_are_hidden_from_narrative_but_retained_for_traceability(self):
        class FakeClient:
            available = True
            model = "fake"

            def complete_json(self, system_prompt, user_prompt, max_tokens=3000):
                return {
                    "executive_summary": ["市场同比增长15.98% [market.shipment.y25q1_q3]"],
                    "tianma_history_insights": {
                        "shipment": ["Tianma Y25F出货增长 [tianma.front_install.shipment]"]
                    },
                }

        parsed = {"workbook_id": "sha256:test", "file_name": "tracker.xlsx", "sheets": []}
        report = generate_competitive_insight_report(parsed, llm_client=FakeClient())

        self.assertEqual("市场同比增长15.98%", report["executive_summary"][0])
        self.assertEqual(
            ["market.shipment.y25q1_q3", "tianma.front_install.shipment"],
            report["quality"]["narrative_metric_refs"],
        )

    def test_each_narrative_keeps_a_separate_displayable_evidence_mapping(self):
        class FakeClient:
            available = True
            model = "fake"

            def complete_json(self, system_prompt, user_prompt, max_tokens=3000):
                return {"executive_summary": ["市场同比增长15.98% [market.shipment.y25q1_q3]"]}

        parsed = {
            "workbook_id": "sha256:test",
            "file_name": "tracker.xlsx",
            "computed_metrics": {
                "engine": "test",
                "market": {
                    "metric_id": "market.shipment.y25q1_q3",
                    "values": {"2024": 100, "2025": 115.98},
                    "evidence": {"2025": {"sheet": "Shipment", "cells": ["A10", "A11"]}},
                },
            },
        }
        report = generate_competitive_insight_report(parsed, llm_client=FakeClient())

        self.assertEqual("市场同比增长15.98%", report["executive_summary"][0])
        self.assertEqual("R1", report["narrative_sources"][0]["citation_label"])
        self.assertEqual("tracker.xlsx", report["narrative_sources"][0]["source_file"])
        self.assertEqual(["market.shipment.y25q1_q3"], report["narrative_sources"][0]["metric_ids"])
        evidence = report["narrative_sources"][0]["metrics"][0]["evidence"]
        self.assertEqual("Shipment", evidence["2025"]["sheet"])

    def test_llm_metric_reference_variants_resolve_to_canonical_evidence_ids(self):
        class FakeClient:
            available = True
            model = "fake"

            def complete_json(self, system_prompt, user_prompt, max_tokens=3000):
                return {
                    "executive_summary": [
                        "AUO客户增长 [auo.client.faurecia_coagent]",
                        "LTPS大尺寸增长 [maker.auo.technology_size.ltps.gte15]",
                    ]
                }

        parsed = {
            "workbook_id": "sha256:test",
            "file_name": "tracker.xlsx",
            "computed_metrics": {
                "engine": "test",
                "client": {
                    "metric_id": "auo.client.faurecia_coagent.shipment",
                    "evidence": {"Y25Q1-Q3": {"sheet": "Supply Chain", "cells": ["A1"]}},
                },
                "technology_size": {
                    "metric_id": "auo.technology_size.ltps.gte15",
                    "evidence": {"Y25Q1-Q3": {"sheet": "Shipment", "cells": ["B2"]}},
                },
            },
        }

        report = generate_competitive_insight_report(parsed, llm_client=FakeClient())

        self.assertEqual(
            [
                "auo.client.faurecia_coagent.shipment",
                "auo.technology_size.ltps.gte15",
            ],
            report["quality"]["narrative_metric_refs"],
        )
        self.assertEqual(
            [
                "auo.client.faurecia_coagent.shipment",
                "auo.technology_size.ltps.gte15",
            ],
            [item["metric_ids"][0] for item in report["narrative_sources"]],
        )
        self.assertFalse(any(
            "无法解析的指标引用" in item
            for item in report["quality"]["data_gaps"]
        ))

    def test_client_metric_typo_adaayo_resolves_to_adayo(self):
        class FakeClient:
            available = True
            model = "fake"

            def complete_json(self, system_prompt, user_prompt, max_tokens=3000):
                return {
                    "executive_summary": [
                        "BOE客户Adayo增长 [boe.client.adaayo.shipment]",
                    ]
                }

        parsed = {
            "workbook_id": "sha256:test",
            "file_name": "tracker.xlsx",
            "computed_metrics": {
                "engine": "test",
                "client": {
                    "metric_id": "boe.client.adayo.shipment",
                    "evidence": {"Y25Q1-Q3": {"sheet": "Supply Chain", "cells": ["A1"]}},
                },
            },
        }
        report = generate_competitive_insight_report(parsed, llm_client=FakeClient())
        self.assertIn("boe.client.adayo.shipment", report["quality"]["narrative_metric_refs"])
        self.assertEqual(
            ["boe.client.adayo.shipment"],
            report["narrative_sources"][0]["metric_ids"],
        )
        self.assertFalse(any(
            "无法解析的指标引用" in item
            for item in report["quality"]["data_gaps"]
        ))

    def test_llm_failure_returns_report_template_instead_of_failing_task(self):
        class FailingClient:
            available = True
            model = "fake"

            def complete_json(self, system_prompt, user_prompt, max_tokens=3000):
                raise LlmError("timeout")

        parsed = {"workbook_id": "sha256:test", "file_name": "tracker.xlsx", "sheets": []}
        report = generate_competitive_insight_report(parsed, llm_client=FailingClient())
        self.assertEqual("python_metrics_rule_narrative", report["quality"]["generation_mode"])
        self.assertTrue(any("timeout" in warning for warning in report["quality"]["warnings"]))

    def test_llm_quota_error_is_a_generation_warning_not_a_data_gap(self):
        class QuotaClient:
            available = True
            model = "glm-test"

            def complete_json(self, system_prompt, user_prompt, max_tokens=3000):
                raise LlmError('Ark API HTTP 429: {"error":{"code":"SetLimitExceeded"}}')

        parsed = {"workbook_id": "sha256:test", "file_name": "tracker.xlsx", "sheets": []}
        report = generate_competitive_insight_report(parsed, llm_client=QuotaClient())
        self.assertFalse(any("429" in gap for gap in report["quality"]["data_gaps"]))
        self.assertIn("指标计算不受影响", report["quality"]["warnings"][0])

    def test_rule_fallback_populates_narrative_from_existing_metrics(self):
        class FailingClient:
            available = True
            model = "fake"

            def complete_json(self, system_prompt, user_prompt, max_tokens=3000):
                raise LlmError("timeout")

        history = {
            "shipment": {"periods": {"Y24": 100, "Y25Q1-Q3": 120}, "yoy_periods": {"Y25Q1-Q3": 0.2}},
            "display_area": {"periods": {"Y25Q1-Q3": 300}, "yoy_periods": {"Y25Q1-Q3": 0.25}},
            "shipment_share": {"periods": {"Y24": 0.1, "Y25Q1-Q3": 0.12}},
            "display_area_share": {"periods": {"Y25Q1-Q3": 0.11}},
        }
        parsed = {
            "workbook_id": "sha256:test", "file_name": "tracker.xlsx", "sheets": [],
            "computed_metrics": {
                "engine": "python_deterministic_v1",
                "market": {"values": {"2025": 1000}, "yoy_2025_vs_2024": 0.1},
                "summary_matrix": {"rows": []},
                "makers": {"Tianma": {}},
                "maker_details": {"Tianma": {"history": history, "product": {}, "customer": {}, "application": {}}},
            },
        }
        report = generate_competitive_insight_report(parsed, llm_client=FailingClient())
        tianma = next(item for item in report["makers"] if item["maker"] == "Tianma")
        self.assertIn("120K", tianma["overview"][0])
        self.assertIn("12.0%", tianma["overview"][1])
        self.assertTrue(report["executive_summary"])

    def test_metrics_are_calculated_by_python_from_full_cell_facts(self):
        def record(year, maker, quantity, technology="LTPS TFT LCD", application="Control panel"):
            values = {
                "year": year, "quarter": "1Q", "panel_maker": maker,
                "quantity_000": quantity, "technology": technology,
                "original_specification": "Automobile monitor", "product": application,
                "client": "Client A",
            }
            return {name: {"value": value, "source_cell": f"A{index + 1}", "source_sheet": "Shipment"}
                    for index, (name, value) in enumerate(values.items())}

        records = [
            record(2024, "Tianma", 100), record(2025, "Tianma", 120),
            record(2024, "AUO", 100), record(2025, "AUO", 80),
            record(2025, "China Star", 50, technology="a-Si TFT LCD"),
            record(2025, "BOE", 999, technology="AMOLED"),
            record(2025, "BOE", 999, application="Automobile monitor (Others)"),
        ]
        metrics = calculate_competitive_metrics([{"sheet": "Shipment", "table": "Raw", "records": records}])

        tianma = metrics["makers"]["Tianma"]["shipment"]
        self.assertEqual({"2024": 100, "2025": 120}, tianma["values"])
        self.assertEqual(0.2, tianma["yoy_2025_vs_2024"])
        self.assertEqual(250, metrics["market"]["values"]["2025"])
        self.assertEqual(50, metrics["makers"]["CSOT"]["shipment"]["values"]["2025"])
        self.assertEqual("python_deterministic_v1", metrics["engine"])

    def test_market_denominator_keeps_non_target_makers(self):
        def record(year, maker, quantity):
            values = {
                "year": year, "quarter": "Q1", "maker": maker,
                "quantity_000": quantity, "technology": "LTPS", "size": 10,
                "original_specification": "Automobile monitor", "application": "Control panel",
            }
            return {name: {"value": value, "source_cell": f"A{index + 1}", "source_sheet": "Shipment"}
                    for index, (name, value) in enumerate(values.items())}

        metrics = calculate_competitive_metrics([{
            "sheet": "Shipment",
            "records": [record(2024, "Tianma", 100), record(2024, "Other Maker", 300),
                        record(2025, "Tianma", 120), record(2025, "Other Maker", 480)],
        }])

        self.assertEqual(600, metrics["market"]["values"]["2025"])
        self.assertEqual(0.2, metrics["makers"]["Tianma"]["shipment"]["market_share"]["2025"])

    def test_market_total_includes_oxide_while_maker_shipment_excludes_it(self):
        def record(year, maker, quantity, technology):
            values = {
                "year": year, "quarter": "Q1", "maker": maker,
                "quantity_000": quantity, "technology": technology,
                "original_specification": "Automobile monitor", "application": "Control panel",
            }
            return {name: {"value": value, "source_cell": f"A{index + 1}", "source_sheet": "Shipment"}
                    for index, (name, value) in enumerate(values.items())}

        metrics = calculate_competitive_metrics([{
            "sheet": "Shipment",
            "records": [
                record(2024, "Tianma", 100, "LTPS"),
                record(2024, "BOE", 10, "Oxide TFT LCD"),
                record(2025, "Tianma", 120, "a-Si"),
                record(2025, "Tianma", 30, "Oxide"),
                record(2025, "BOE", 999, "AMOLED"),
            ],
        }])

        self.assertEqual(150, metrics["market"]["values"]["2025"])
        self.assertEqual(120, metrics["makers"]["Tianma"]["shipment"]["values"]["2025"])
        self.assertEqual(0.8, metrics["makers"]["Tianma"]["shipment"]["market_share"]["2025"])

    def test_summary_matrix_uses_the_report_size_buckets_and_denominators(self):
        def record(year, maker, quantity, technology, size):
            values = {
                "year": year, "quarter": "Q1", "maker": maker,
                "quantity_000": quantity, "technology": technology, "size": size,
                "original_specification": "Automobile monitor", "application": "Control panel",
            }
            return {name: {"value": value, "source_cell": f"A{index + 1}", "source_sheet": "Shipment"}
                    for index, (name, value) in enumerate(values.items())}

        records = [
            record(2024, "Tianma", 100, "LTPS", 7.9),
            record(2024, "Tianma", 100, "LTPS", 10),
            record(2024, "AUO", 200, "a-Si", 7),
            record(2025, "Tianma", 150, "LTPS", 7.9),
            record(2025, "Tianma", 50, "LTPS", 10),
            record(2025, "AUO", 300, "a-Si", 7),
        ]
        metrics = calculate_competitive_metrics([{"sheet": "Shipment", "records": records}])
        rows = {row["row_key"]: row for row in metrics["summary_matrix"]["rows"]}
        ltps_under_8 = rows["ltps.<8"]

        self.assertEqual(11, len(rows))
        self.assertEqual(150, ltps_under_8["market"]["values"]["2025"])
        self.assertEqual(0.5, ltps_under_8["market"]["yoy_2025_vs_2024"])
        self.assertEqual(0.3, ltps_under_8["market"]["segment_share"]["2025"])
        self.assertEqual(0.3, ltps_under_8["market"]["total_market_share"]["2025"])
        self.assertEqual(0.75, ltps_under_8["makers"]["Tianma"]["internal_share"]["2025"])
        self.assertEqual(1, ltps_under_8["makers"]["Tianma"]["segment_market_share"]["2025"])
        self.assertEqual(1, ltps_under_8["makers"]["Tianma"]["same_size_market_share"]["2025"])

    def test_pdf_summary_share_denominators(self):
        def record(maker, quantity, technology, size):
            values = {
                "year": 2025, "quarter": "Q1", "maker": maker,
                "quantity_000": quantity, "technology": technology, "size": size,
                "original_specification": "Automobile monitor", "application": "Control panel",
            }
            return {name: {"value": value, "source_cell": f"A{index + 1}", "source_sheet": "Shipment"}
                    for index, (name, value) in enumerate(values.items())}

        metrics = calculate_competitive_metrics([{"sheet": "Shipment", "records": [
            record("Tianma", 100, "LTPS", 7),
            record("Tianma", 300, "a-Si", 7),
            record("Other Maker", 100, "LTPS", 7),
            record("Other Maker", 300, "LTPS", 10),
        ]}])
        row = {item["row_key"]: item for item in metrics["summary_matrix"]["rows"]}["ltps.<8"]

        self.assertEqual(0.25, row["market"]["segment_share"]["2025"])
        self.assertEqual(0.25, row["makers"]["Tianma"]["internal_share"]["2025"])
        self.assertEqual(0.5, row["makers"]["Tianma"]["segment_market_share"]["2025"])
        self.assertEqual(0.2, row["makers"]["Tianma"]["technology_market_share"]["2025"])

    def test_summary_all_share_excludes_oxide_from_denominator(self):
        def record(year, maker, quantity, technology, size=10):
            values = {
                "year": year, "quarter": "Q1", "maker": maker,
                "quantity_000": quantity, "technology": technology, "size": size,
                "original_specification": "Automobile monitor", "application": "Control panel",
            }
            return {name: {"value": value, "source_cell": f"A{index + 1}", "source_sheet": "Shipment"}
                    for index, (name, value) in enumerate(values.items())}

        metrics = calculate_competitive_metrics([{"sheet": "Shipment", "records": [
            record(2024, "Tianma", 100, "LTPS"),
            record(2024, "Other Maker", 100, "a-Si"),
            record(2024, "BOE", 50, "Oxide"),
            record(2025, "Tianma", 100, "LTPS"),
            record(2025, "Other Maker", 100, "a-Si"),
            record(2025, "BOE", 50, "Oxide"),
        ]}])
        # Absolute market still includes Oxide.
        self.assertEqual(250, metrics["market"]["values"]["2025"])
        rows = {row["row_key"]: row for row in metrics["summary_matrix"]["rows"]}
        all_row = rows["all.all"]
        # All YoY uses Oxide-inclusive totals; share uses LTPS+a-Si only → 100/200.
        self.assertEqual(250, all_row["market"]["values"]["2025"])
        self.assertEqual(1.0, all_row["market"]["total_market_share"]["2025"])
        self.assertEqual(0.5, all_row["makers"]["Tianma"]["same_size_market_share"]["2025"])
        self.assertEqual(0.5, rows["ltps.total"]["market"]["total_market_share"]["2025"])

    def test_summary_all_row_keeps_values_for_ltps_only_csot(self):
        """CSOT 仅有 LTPS 时，All 仍按 LTPS+a-Si 总量口径正常出数，不强制留空。"""
        def record(year, maker, quantity, technology, size=10):
            values = {
                "year": year, "quarter": "Q1", "maker": maker,
                "quantity_000": quantity, "technology": technology, "size": size,
                "original_specification": "Automobile monitor", "application": "Control panel",
            }
            return {name: {"value": value, "source_cell": f"A{index + 1}", "source_sheet": "Shipment"}
                    for index, (name, value) in enumerate(values.items())}

        metrics = calculate_competitive_metrics([{"sheet": "Shipment", "records": [
            record(2024, "CSOT", 100, "LTPS"),
            record(2024, "Tianma", 50, "LTPS"),
            record(2024, "Tianma", 50, "a-Si"),
            record(2025, "CSOT", 150, "LTPS"),
            record(2025, "Tianma", 60, "LTPS"),
            record(2025, "Tianma", 40, "a-Si"),
        ]}])
        rows = {row["row_key"]: row for row in metrics["summary_matrix"]["rows"]}
        all_csot = rows["all.all"]["makers"]["CSOT"]
        self.assertIsNone(all_csot.get("display_suppressed"))
        self.assertAlmostEqual(0.5, all_csot["yoy_2025_vs_2024"])
        self.assertEqual(1.0, all_csot["internal_share"]["2025"])
        # 150 / (150+100) share of LTPS+a-Si market
        self.assertAlmostEqual(0.6, all_csot["same_size_market_share"]["2025"])


if __name__ == "__main__":
    unittest.main()
