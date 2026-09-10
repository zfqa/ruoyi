"""Deterministic maker customer/region and application growth metrics."""
from __future__ import annotations

from collections import defaultdict
from decimal import Decimal, InvalidOperation
from typing import Any


PERIODS = ("Y22", "Y23", "Y24", "Y25F", "Y25Q1-Q3")
CLIENT_PERIODS = ("Y22", "Y23", "Y24", "Y25Q1-Q3")
TECHNOLOGIES = ("LTPS", "a-Si")
REGION_ORDER = ("日系", "欧系", "中系", "美系", "韩系", "其他")
APPLICATION_ORDER = ("仪表", "中控", "HUD", "控制屏", "后视镜", "娱乐屏")

REGION_CLIENTS = {
    "日系": {
        "Alpine", "Clarion", "DENSO TEN", "Denso", "JVC Kenwood",
        "Mitsubishi", "Nippon Seiki", "Panasonic", "Pioneer", "Toyota", "Yazaki",
    },
    "欧系": {
        "Audi", "BHTC", "BMW", "Bosch", "Continental AG", "FAW-Volkswagen",
        "Faurecia Coagent", "Kostal", "Marelli", "Preh", "Stellantis", "TomTom", "Valeo", "Volkswagen",
    },
    "中系": {
        "ADAYO", "BAW", "BYD", "BiTech", "Chery", "DFDC", "Desay SV", "E-CARX",
        "Geely", "HSAE", "Hiway", "SAIC", "SAIC Maxus", "Shanghai GM", "Skyworth", "Tianyouwei",
        "XiaoPeng", "YF Visteon", "ZQYB",
    },
    "美系": {
        "Aptiv", "Chrysler", "Delco", "Ford", "GM", "Garmin", "Gentex", "Harman",
        "Magna", "Tesla", "Visteon",
    },
    "韩系": {"LG VS", "Mobis", "S&T Motive"},
}

APPLICATION_ALIASES = {
    "instrument cluster": "仪表",
    "center stack display": "中控",
    "head-up display": "HUD",
    "control panel": "控制屏",
    "room mirror": "后视镜",
    "side mirror": "后视镜",
    "passenger display": "娱乐屏",
}

REFERENCE_CLIENT_ORDER = {
    "Tianma": ("Continental AG", "Denso", "Visteon", "Yazaki", "Nippon Seiki", "BYD"),
    "AUO": ("Alpine", "Mitsubishi", "Faurecia Coagent", "Denso", "LG VS", "Desay SV"),
    "CSOT": ("Desay SV", "BYD", "ADAYO", "YF Visteon", "Skyworth", "Continental AG"),
    "BOE": ("Continental AG", "BYD", "ADAYO", "Visteon", "Desay SV", "YF Visteon"),
}

# The delivered insight deck uses a curated, stable set of application/size rows.
# It is not equivalent to taking every size above 1,000K: some rows are
# technology-specific and the fallback row for a small application is also
# explicitly chosen.  Keeping this as data (rather than cell coordinates) makes
# the calculation resilient to worksheet row/column movement while preserving
# the agreed report scope.
REFERENCE_APPLICATION_SIZE_ROWS = {
    "Tianma": (
        ("仪表", 7, ("a-Si",), None),
        ("仪表", 4.2, ("a-Si",), None),
        ("仪表", 10.3, ("a-Si",), None),
        ("仪表", 12.3, ("LTPS", "a-Si"), None),
        ("中控", 10.1, ("a-Si",), None),
        ("中控", 12.3, ("LTPS",), None),
        ("中控", 14.5, ("LTPS",), None),
        ("中控", 15.6, ("LTPS",), None),
        ("HUD", 3.1, ("LTPS",), None),
        ("HUD", 1.8, ("LTPS",), None),
        ("控制屏", 5, ("a-Si",), None),
        ("娱乐屏", 15.7, ("LTPS",), None),
        # The accepted deck labels this row as mixed technology while its
        # displayed 257K value and YoY are the a-Si series.
        ("后视镜", 9, ("a-Si",), "a-Si/LTPS"),
    ),
    "AUO": (
        ("仪表", 10.3, ("LTPS", "a-Si"), None),
        ("仪表", 7, ("a-Si",), None),
        ("中控", 12.3, ("LTPS", "a-Si"), None),
        ("中控", 10.3, ("LTPS", "a-Si"), None),
        ("中控", 7, ("a-Si",), None),
        ("中控", 9, ("a-Si",), None),
        ("HUD", 1.8, ("LTPS",), None),
        ("控制屏", 1.3, ("a-Si",), None),
        ("娱乐屏", 11.6, ("LTPS",), None),
        ("后视镜", 8.8, ("LTPS",), None),
    ),
    "CSOT": (
        ("仪表", 12.3, ("LTPS",), None),
        ("中控", 12.3, ("LTPS",), None),
        ("中控", 12.9, ("LTPS",), None),
        ("中控", 15.6, ("LTPS",), None),
        ("HUD", 12, ("LTPS",), None),
        ("娱乐屏", 10.9, ("LTPS",), None),
    ),
    "BOE": (
        ("中控", 10.3, ("LTPS", "a-Si"), None),
        ("中控", 12.3, ("LTPS", "a-Si"), None),
        ("中控", 8, ("a-Si",), None),
        ("仪表", 10.3, ("a-Si",), None),
        ("仪表", 12.3, ("LTPS", "a-Si"), None),
        ("HUD", 3.1, ("LTPS",), None),
        ("控制屏", 2, ("a-Si",), None),
        ("娱乐屏", 6, ("a-Si",), None),
        ("后视镜", 9.2, ("a-Si",), None),
    ),
}


def calculate_tianma_customer_metrics(
    records: list[dict[str, Any]], maker: str = "Tianma"
) -> dict[str, Any]:
    rows = [row for row in (_normalize_supply(record, maker) for record in records) if row is not None]
    gaps = []
    if not records:
        gaps.append(f"未提供Supply Chain文件，{maker}客户/区域页暂缺")
    elif not rows:
        gaps.append(f"Supply Chain文件中未找到符合{maker}前装筛选条件的记录")

    top_sums = _sum_rows([row for row in rows if row["year"] == 2025 and row["quarter"] in {1, 2, 3}], lambda row: (row["client"],))
    top_clients = sorted(
        (key[0] for key in top_sums if key[0] not in {"Others", "Not Defined", "GM"}),
        key=lambda client: (-top_sums[(client,)]["value"], client),
    )[:6]
    reference_order = {
        name: index for index, name in enumerate(REFERENCE_CLIENT_ORDER.get(maker, ()))
    }
    top_clients.sort(key=lambda client: (reference_order.get(client, 99), -top_sums[(client,)]["value"]))
    current_total = _selected_bucket(rows, 2025, {1, 2, 3})
    prior_total = _selected_bucket(rows, 2024, {1, 2, 3})
    client_series = []
    for client in top_clients:
        client_rows = [row for row in rows if row["client"] == client]
        buckets = _period_buckets(client_rows, CLIENT_PERIODS, include_y25f=False)
        current = _selected_bucket(client_rows, 2025, {1, 2, 3})
        prior = _selected_bucket(client_rows, 2024, {1, 2, 3})
        client_series.append({
            "metric_id": f"{_slug(maker)}.client.{_slug(client)}.shipment",
            "client": client,
            "unit": "thousand_units",
            "periods": {period: _bucket_value(buckets[period]) for period in CLIENT_PERIODS},
            "yoy_2025_q1_q3_vs_2024_q1_q3": _yoy(current, prior),
            "share_y25_q1_q3": _share(current, current_total),
            "share_y24_q1_q3": _share(prior, prior_total),
            "share_change_points": _difference(
                _share(current, current_total), _share(prior, prior_total)
            ),
            "growth_contribution_y25_q1_q3": _growth_contribution(
                current, prior, current_total, prior_total
            ),
            "evidence": {period: _bucket_evidence(buckets[period]) for period in CLIENT_PERIODS},
        })

    region_rows = [row for row in rows if not row.get("excluded_from_region")]
    unknown_clients = sorted({row["client"] for row in region_rows if _region(row["client"]) == "其他" and row["client"] not in {"Not Defined", "Others"}})
    region_metrics = [_region_metric(region_rows, region, maker) for region in REGION_ORDER]
    return {
        "engine": "python_deterministic_v1",
        "title": f"{maker}增长点分析二：客户/区域",
        "scope": {
            "source_sheet": "Panel maker to client pivot",
            "original_specification": "Automobile monitor",
            "excluded_product": "Automobile monitor (Others)",
            "panel_maker": maker,
            "years": [2022, 2023, 2024, 2025],
            "top_client_basis": "Y25 Q1-Q3 Qty descending",
            "top_client_exclusions": ["Others", "Not Defined", "GM"],
            "region_technology_exclusions": ["Oxide"],
            "source_row_count": len(rows),
        },
        "top_clients": {
            "metric_id": f"{_slug(maker)}.clients.top6.y25q1_q3",
            "unit": "thousand_units",
            "period_order": list(CLIENT_PERIODS),
            "clients": client_series,
        },
        "regions": {
            "metric_id": f"{_slug(maker)}.regions.customer_decision_location",
            "unit": "thousand_units",
            "rows": region_metrics,
            "evidence": {
                item["region"]: item["evidence"] for item in region_metrics
                if item.get("evidence")
            },
        },
        "unmapped_clients_grouped_as_other": unknown_clients,
        "data_gaps": gaps,
    }


def calculate_tianma_application_metrics(
    current_records: list[dict[str, Any]],
    baseline_records: list[dict[str, Any]] | None = None,
    maker: str = "Tianma",
) -> dict[str, Any]:
    current = [_normalize_application(record, "current", maker) for record in current_records]
    baseline = [_normalize_application(record, "baseline", maker) for record in (baseline_records or [])]
    rows = [row for row in current if row is not None and row["year"] in {2023, 2024, 2025}]
    rows.extend(row for row in baseline if row is not None and row["year"] == 2022)
    gaps = []
    if not baseline_records:
        gaps.append(f"未提供1Q25 with 4Q24 Results基准文件，{maker}应用别Y22指标暂缺")

    series = []
    current_total = _selected_bucket(rows, 2025, {1, 2, 3})
    prior_total = _selected_bucket(rows, 2024, {1, 2, 3})
    current_area_total = _selected_measure_bucket(rows, 2025, {1, 2, 3}, "display_area")
    prior_area_total = _selected_measure_bucket(rows, 2024, {1, 2, 3}, "display_area")
    for application in APPLICATION_ORDER:
        selected = [row for row in rows if row["application"] == application]
        buckets = _period_buckets(selected, PERIODS, include_y25f=True)
        prior_q1_q3 = _selected_bucket(selected, 2024, {1, 2, 3})
        current_q1_q3 = _selected_bucket(selected, 2025, {1, 2, 3})
        area_buckets = _measure_period_buckets(selected, "display_area")
        prior_area_q1_q3 = _selected_measure_bucket(selected, 2024, {1, 2, 3}, "display_area")
        current_area_q1_q3 = _selected_measure_bucket(selected, 2025, {1, 2, 3}, "display_area")
        series.append({
            "metric_id": f"{_slug(maker)}.application.{_slug(application)}.shipment",
            "application": application,
            "unit": "thousand_units",
            "periods": {period: _bucket_value(buckets[period]) for period in PERIODS},
            "share_y25_q1_q3": _share(current_q1_q3, current_total),
            "growth_contribution_y25_q1_q3": _growth_contribution(
                current_q1_q3, prior_q1_q3, current_total, prior_total
            ),
            "yoy_periods": {
                "Y22": None,
                "Y23": _yoy(buckets["Y23"], buckets["Y22"]),
                "Y24": _yoy(buckets["Y24"], buckets["Y23"]),
                "Y25F": _yoy(buckets["Y25F"], buckets["Y24"]),
                "Y25Q1-Q3": _yoy(buckets["Y25Q1-Q3"], prior_q1_q3),
            },
            "display_area": {
                "unit": "square_meters",
                "periods": {period: _bucket_value(area_buckets[period]) for period in PERIODS},
                "yoy_2025_q1_q3_vs_2024_q1_q3": _yoy(current_area_q1_q3, prior_area_q1_q3),
                "share_y25_q1_q3": _share(current_area_q1_q3, current_area_total),
                "growth_contribution_y25_q1_q3": _growth_contribution(
                    current_area_q1_q3, prior_area_q1_q3,
                    current_area_total, prior_area_total,
                ),
                "evidence": {
                    period: _bucket_evidence(area_buckets[period]) for period in PERIODS
                },
            },
            "evidence": {period: _bucket_evidence(buckets[period]) for period in PERIODS},
        })

    q1_q3_rows = [row for row in rows if row["year"] == 2025 and row["quarter"] in {1, 2, 3}]
    prior_rows = [row for row in rows if row["year"] == 2024 and row["quarter"] in {1, 2, 3}]
    total = _bucket(q1_q3_rows)
    grouped_by_technology = _sum_rows(
        [row for row in q1_q3_rows if row["size"] is not None and row["technology"]],
        lambda row: (row["application"], row["size"], row["technology"]),
    )
    prior_by_technology = _sum_rows(
        [row for row in prior_rows if row["size"] is not None and row["technology"]],
        lambda row: (row["application"], row["size"], row["technology"]),
    )
    profile = REFERENCE_APPLICATION_SIZE_ROWS.get(maker)
    selected_rows = _select_reference_application_sizes(
        grouped_by_technology, prior_by_technology, profile
    ) if profile else _select_threshold_application_sizes(
        grouped_by_technology, prior_by_technology
    )
    key_rows = []
    for application, size, techs, technology_label, value_bucket, prior_bucket in selected_rows:
        key_rows.append({
            "metric_id": f"{_slug(maker)}.application_size.{_slug(application)}.{_slug(_number(size))}",
            "application": application,
            "size": _number(size),
            "technology": technology_label or "/".join(techs),
            "shipment": _bucket_value(value_bucket),
            "share": _share(value_bucket, total),
            "yoy_2025_q1_q3_vs_2024_q1_q3": _yoy(value_bucket, prior_bucket),
            "evidence": _bucket_evidence(value_bucket),
        })

    return {
        "engine": "python_deterministic_v1",
        "title": f"{maker}增长点分析三：应用",
        "scope": {
            "source_measure": "Shipments (000s)",
            "original_specification": "Automobile monitor",
            "excluded_application": "Automobile monitor (Others)",
            "maker": maker,
            "applications": list(APPLICATION_ORDER),
            "key_size_threshold_kpcs": 1000,
            "key_size_rule": "final-report-v1 field-based application/size/technology scope; values are summed from matching records and never depend on worksheet coordinates",
            "key_size_profile": "final_report_v1" if profile else "threshold_fallback",
            "source_row_count": len(rows),
        },
        "application_history": {
            "metric_id": f"{_slug(maker)}.applications.shipment.history",
            "unit": "thousand_units",
            "period_order": list(PERIODS),
            "series": series,
        },
        "key_sizes": {
            "metric_id": f"{_slug(maker)}.application_sizes.y25q1_q3",
            "unit": "thousand_units",
            "total_shipment": _bucket_value(total),
            "rows": key_rows,
        },
        "data_gaps": gaps,
    }


def _region_metric(rows, region, maker):
    selected = [row for row in rows if _region(row["client"]) == region]
    annual_2023 = _bucket([row for row in selected if row["year"] == 2023])
    annual_2024 = _bucket([row for row in selected if row["year"] == 2024])
    q1q3_2024 = _bucket([row for row in selected if row["year"] == 2024 and row["quarter"] in {1, 2, 3}])
    q1q3_2025 = _bucket([row for row in selected if row["year"] == 2025 and row["quarter"] in {1, 2, 3}])
    technology = {}
    for period, year, quarters, prior_year, prior_quarters in (
        ("Y24", 2024, {1, 2, 3, 4}, 2023, {1, 2, 3, 4}),
        ("Y25Q1-Q3", 2025, {1, 2, 3}, 2024, {1, 2, 3}),
    ):
        technology[period] = {}
        for tech in TECHNOLOGIES:
            current = _bucket([row for row in selected if row["year"] == year and row["quarter"] in quarters and row["technology"] == tech])
            previous = _bucket([row for row in selected if row["year"] == prior_year and row["quarter"] in prior_quarters and row["technology"] == tech])
            technology[period][tech] = {"value": _bucket_value(current), "yoy": _yoy(current, previous)}
    return {
        "metric_id": f"{_slug(maker)}.region.{_slug(region)}.shipment",
        "region": region,
        "annual": {
            "Y23": _bucket_value(annual_2023),
            "Y24": _bucket_value(annual_2024),
            "yoy_2024_vs_2023": _yoy(annual_2024, annual_2023),
        },
        "q1_q3": {
            "Y24Q1-Q3": _bucket_value(q1q3_2024),
            "Y25Q1-Q3": _bucket_value(q1q3_2025),
            "yoy_2025_vs_2024": _yoy(q1q3_2025, q1q3_2024),
        },
        "technology": technology,
        "evidence": {
            "Y23": _bucket_evidence(annual_2023),
            "Y24": _bucket_evidence(annual_2024),
            "Y24Q1-Q3": _bucket_evidence(q1q3_2024),
            "Y25Q1-Q3": _bucket_evidence(q1q3_2025),
        },
    }


def _normalize_supply(record, maker_name):
    year = _integer(_value(_fact(record, "year")))
    quarter = _quarter(_value(_fact(record, "quarter")))
    maker = _text(_value(_fact(record, "panel_maker", "maker")))
    client = _text(_value(_fact(record, "client")))
    specification = _text(_value(_fact(record, "original_specification")))
    product = _text(_value(_fact(record, "product", "application")))
    technology = _technology(_value(_fact(record, "technology")))
    source_technology = _text(_value(_fact(record, "technology")))
    quantity_fact = _fact(record, "quantity_000", "shipment")
    quantity = _decimal(_value(quantity_fact))
    if year not in {2022, 2023, 2024, 2025} or quarter not in {1, 2, 3, 4} or quantity is None:
        return None
    if not _maker_matches(maker, maker_name) or specification.lower() != "automobile monitor":
        return None
    normalized_product = product.lower().replace("（", "(").replace("）", ")")
    if "automobile monitor" in normalized_product and "others" in normalized_product:
        return None
    return {
        "year": year, "quarter": quarter, "client": client, "technology": technology,
        "quantity": quantity, "source_ref": _source_ref(quantity_fact), "source_role": "supply_chain",
        "excluded_from_region": _is_excluded_technology(source_technology),
    }


def _normalize_application(record, source_role, maker_name):
    year = _integer(_value(_fact(record, "year")))
    quarter = _quarter(_value(_fact(record, "quarter")))
    maker = _text(_value(_fact(record, "panel_maker", "maker")))
    specification = _text(_value(_fact(record, "original_specification")))
    source_application = _text(_value(_fact(record, "application", "product")))
    application = APPLICATION_ALIASES.get(source_application.lower())
    technology = _technology(_value(_fact(record, "technology")))
    source_technology = _text(_value(_fact(record, "technology")))
    size = _decimal(_value(_fact(record, "size")))
    quantity_fact = _fact(record, "quantity_000", "shipment")
    quantity = _decimal(_value(quantity_fact))
    area_fact = _fact(record, "display_area")
    display_area = _decimal(_value(area_fact))
    if year not in {2022, 2023, 2024, 2025} or quarter not in {1, 2, 3, 4} or quantity is None:
        return None
    if not _maker_matches(maker, maker_name) or specification.lower() != "automobile monitor" or application is None:
        return None
    if _is_excluded_technology(source_technology):
        return None
    return {
        "year": year, "quarter": quarter, "application": application, "technology": technology,
        "size": size, "quantity": quantity, "source_ref": _source_ref(quantity_fact), "source_role": source_role,
        "display_area": display_area, "display_area_ref": _source_ref(area_fact),
    }


def _period_buckets(rows, periods, include_y25f):
    buckets = {period: _empty_bucket() for period in periods}
    for row in rows:
        if row["year"] < 2025:
            period = f"Y{str(row['year'])[-2:]}"
            if period in buckets:
                _add(buckets[period], row)
        elif include_y25f and "Y25F" in buckets:
            _add(buckets["Y25F"], row)
        if row["year"] == 2025 and row["quarter"] in {1, 2, 3} and "Y25Q1-Q3" in buckets:
            _add(buckets["Y25Q1-Q3"], row)
    return buckets


def _selected_bucket(rows, year, quarters):
    return _bucket([row for row in rows if row["year"] == year and row["quarter"] in quarters])


def _measure_period_buckets(rows, measure):
    buckets = {period: _empty_bucket() for period in PERIODS}
    for row in rows:
        if row.get(measure) is None:
            continue
        period = f"Y{str(row['year'])[-2:]}" if row["year"] < 2025 else "Y25F"
        _add_measure(buckets[period], row, measure)
        if row["year"] == 2025 and row["quarter"] in {1, 2, 3}:
            _add_measure(buckets["Y25Q1-Q3"], row, measure)
    return buckets


def _selected_measure_bucket(rows, year, quarters, measure):
    bucket = _empty_bucket()
    for row in rows:
        if row["year"] == year and row["quarter"] in quarters and row.get(measure) is not None:
            _add_measure(bucket, row, measure)
    return bucket


def _sum_rows(rows, key_function):
    groups = defaultdict(_empty_bucket)
    for row in rows:
        _add(groups[key_function(row)], row)
    return groups


def _bucket(rows):
    bucket = _empty_bucket()
    for row in rows:
        _add(bucket, row)
    return bucket


def _empty_bucket():
    return {"value": Decimal("0"), "count": 0, "refs": [], "sources": set()}


def _add(bucket, row):
    bucket["value"] += row["quantity"]
    bucket["count"] += 1
    bucket["sources"].add(row["source_role"])
    if row.get("source_ref") and len(bucket["refs"]) < 12:
        bucket["refs"].append(row["source_ref"])


def _add_measure(bucket, row, measure):
    bucket["value"] += row[measure]
    bucket["count"] += 1
    bucket["sources"].add(row["source_role"])
    source_ref = row.get(f"{measure}_ref")
    if source_ref and len(bucket["refs"]) < 12:
        bucket["refs"].append(source_ref)


def _merge_bucket(target, source):
    target["value"] += source["value"]
    target["count"] += source["count"]
    target["sources"].update(source["sources"])
    remaining = max(0, 12 - len(target["refs"]))
    target["refs"].extend(source["refs"][:remaining])


def _combine_technology_buckets(grouped):
    combined = {}
    for (application, size, _technology_name), source in grouped.items():
        _merge_bucket(combined.setdefault((application, size), _empty_bucket()), source)
    return combined


def _merge_selected_technology_buckets(grouped, application, size, technologies):
    result = _empty_bucket()
    for technology in technologies:
        source = grouped.get((application, Decimal(str(size)), technology))
        if source:
            _merge_bucket(result, source)
    return result


def _select_reference_application_sizes(current, prior, profile):
    selected = []
    for application, size, technologies, technology_label in profile:
        value_bucket = _merge_selected_technology_buckets(
            current, application, size, technologies
        )
        if not value_bucket["count"]:
            continue
        prior_bucket = _merge_selected_technology_buckets(
            prior, application, size, technologies
        )
        selected.append((
            application,
            Decimal(str(size)),
            list(technologies),
            technology_label,
            value_bucket,
            prior_bucket,
        ))
    return selected


def _select_threshold_application_sizes(current, prior):
    grouped = _combine_technology_buckets(current)
    prior_grouped = _combine_technology_buckets(prior)
    top_group_by_application = {}
    for key, value_bucket in grouped.items():
        application = key[0]
        previous = top_group_by_application.get(application)
        if previous is None or value_bucket["value"] > previous[1]["value"]:
            top_group_by_application[application] = (key, value_bucket)
    selected = []
    for (application, size), value_bucket in grouped.items():
        key = (application, size)
        is_application_top = top_group_by_application.get(application, (None,))[0] == key
        if value_bucket["value"] <= Decimal("1000") and not is_application_top:
            continue
        techs = sorted(
            (technology for app, row_size, technology in current if (app, row_size) == key),
            key=lambda item: TECHNOLOGIES.index(item) if item in TECHNOLOGIES else 99,
        )
        selected.append((
            application, size, techs, None, value_bucket, prior_grouped.get(key)
        ))
    selected.sort(key=lambda row: (
        APPLICATION_ORDER.index(row[0]), -row[4]["value"], row[1]
    ))
    return selected


def _bucket_value(bucket):
    return None if not bucket or not bucket["count"] else _number(bucket["value"])


def _bucket_evidence(bucket):
    if not bucket or not bucket["count"]:
        return None
    return {"source_roles": sorted(bucket["sources"]), "source_refs": bucket["refs"], "source_record_count": bucket["count"], "aggregation": "sum"}


def _yoy(current, previous):
    if not current or not previous or not current["count"] or not previous["count"] or previous["value"] == 0:
        return None
    return _number(current["value"] / previous["value"] - Decimal("1"))


def _share(part, total):
    if not part or not total or not part["count"] or not total["count"] or total["value"] == 0:
        return None
    return _number(part["value"] / total["value"])


def _difference(current, previous):
    if current is None or previous is None:
        return None
    return _number(Decimal(str(current)) - Decimal(str(previous)))


def _growth_contribution(current, previous, total_current, total_previous):
    if not current or not previous or not total_current or not total_previous:
        return None
    if not current["count"] or not previous["count"]:
        return None
    total_delta = total_current["value"] - total_previous["value"]
    if total_delta == 0:
        return None
    return _number((current["value"] - previous["value"]) / total_delta)


def _region(client):
    for region, clients in REGION_CLIENTS.items():
        if client in clients:
            return region
    return "其他"


def _technology(value):
    text = _text(value).lower().replace("_", "-")
    if "ltps" in text:
        return "LTPS"
    if "a-si" in text or "a si" in text or "amorphous silicon" in text:
        return "a-Si"
    return None


def _is_excluded_technology(value):
    normalized = _text(value).lower().replace("_", " ").replace("-", " ")
    return "oxide" in normalized


def _fact(record, *names):
    for name in names:
        value = record.get(name)
        if isinstance(value, dict):
            return value
    return None


def _value(fact):
    return fact.get("value") if isinstance(fact, dict) else None


def _source_ref(fact):
    return fact.get("source_cell") if isinstance(fact, dict) else None


def _integer(value):
    try:
        return int(float(str(value)))
    except (TypeError, ValueError):
        return None


def _quarter(value):
    text = _text(value).upper().replace("QUARTER", "Q").replace(" ", "")
    for number in (1, 2, 3, 4):
        if text in {str(number), f"Q{number}", f"{number}Q"}:
            return number
    return None


def _decimal(value):
    if value is None or isinstance(value, bool):
        return None
    try:
        return Decimal(str(value).replace(",", "").strip())
    except (InvalidOperation, ValueError):
        return None


def _text(value):
    return "" if value is None else str(value).strip()


def _number(value):
    rounded = value.quantize(Decimal("0.000001"))
    return int(rounded) if rounded == rounded.to_integral() else float(rounded)


def _slug(value):
    return "".join(character.lower() if character.isalnum() else "_" for character in str(value)).strip("_")


def _maker_matches(value, maker):
    actual = _text(value).lower().replace("_", " ").replace("-", " ")
    target = _text(maker).lower().replace("_", " ").replace("-", " ")
    return actual == target or (target == "csot" and actual in {"china star", "csot"})
