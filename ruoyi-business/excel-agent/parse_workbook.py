"""Command line entry point for Excel/CSV extraction."""
from __future__ import annotations

import argparse
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.pipeline import parse_workbook, to_json


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    parser = argparse.ArgumentParser(description="Parse an Excel or CSV file into source-grounded JSON.")
    parser.add_argument("file", help="Path to .xlsx/.xlsm/.csv file")
    parser.add_argument("--include-raw-cells", action="store_true", help="Include non-empty raw cell layer")
    parser.add_argument("--raw-cell-mode", choices=("non-empty", "all"), default="non-empty", help="Raw cell output scope when --include-raw-cells is enabled")
    parser.add_argument("--max-records-per-table", type=int, default=None, help="Limit records per table for previews")
    parser.add_argument("--use-llm", action="store_true", help="Use Ark LLM for table structure interpretation with rule fallback")
    parser.add_argument("--max-llm-tables", type=int, default=None, help="Maximum table candidates sent to the LLM")
    parser.add_argument("--baseline-file", default=None, help="Older tracker workbook used only for Y22 history")
    parser.add_argument("--supply-chain-file", default=None, help="Supply Chain workbook used for customer and region analysis")
    parser.add_argument(
        "--extra-history-file", action="append", default=[],
        help="Additional History workbook(s); Year/Quarter overlap is overridden by later files and --file",
    )
    parser.add_argument(
        "--extra-supply-chain-file", action="append", default=[],
        help="Additional Supply Chain workbook(s); Year/Quarter overlap is overridden by later files and --supply-chain-file",
    )
    parser.add_argument("--file-label", default=None, help="Original Omdia file name for --file (publication lag)")
    parser.add_argument("--baseline-file-label", default=None, help="Original Omdia file name for baseline")
    parser.add_argument("--supply-chain-file-label", default=None, help="Original Omdia file name for supply chain")
    parser.add_argument(
        "--extra-history-file-label", action="append", default=[],
        help="Original Omdia names aligned with --extra-history-file order",
    )
    parser.add_argument(
        "--extra-supply-chain-file-label", action="append", default=[],
        help="Original Omdia names aligned with --extra-supply-chain-file order",
    )
    args = parser.parse_args()

    result = parse_workbook(
        args.file,
        include_raw_cells=args.include_raw_cells,
        max_records_per_table=args.max_records_per_table,
        raw_cell_mode=args.raw_cell_mode,
        use_llm=args.use_llm,
        max_llm_tables=args.max_llm_tables,
        baseline_file_path=args.baseline_file,
        supply_chain_file_path=args.supply_chain_file,
        extra_history_file_paths=args.extra_history_file,
        extra_supply_chain_file_paths=args.extra_supply_chain_file,
        file_label=args.file_label,
        baseline_file_label=args.baseline_file_label,
        supply_chain_file_label=args.supply_chain_file_label,
        extra_history_file_labels=args.extra_history_file_label,
        extra_supply_chain_file_labels=args.extra_supply_chain_file_label,
    )
    print(to_json(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
