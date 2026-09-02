"""CLI for generating the first competitive-insight report from parsed JSON."""
from __future__ import annotations

import argparse
import json
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.pipeline import to_json
from app.report import generate_competitive_insight_report


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="Generate the first competitive-insight report.")
    parser.add_argument("parsed_json", help="Path to the full parsed workbook JSON")
    parser.add_argument("--no-llm", action="store_true", help="Generate only the traceable report template")
    args = parser.parse_args()
    with open(args.parsed_json, "r", encoding="utf-8") as handle:
        parsed = json.load(handle)
    report = generate_competitive_insight_report(parsed, use_llm=not args.no_llm)
    print(to_json(report))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
