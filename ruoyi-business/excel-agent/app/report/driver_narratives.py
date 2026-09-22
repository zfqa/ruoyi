"""终稿风格「驱动力一/二/三」长叙述：规则模板 + 已算指标，不重新算账。"""
from __future__ import annotations

from typing import Any


def build_driver_narratives(
    maker: str,
    *,
    full_year: bool,
    size_rows: list[dict[str, Any]],
    product: dict[str, Any],
    customer: dict[str, Any],
    application: dict[str, Any],
    boe_customer: dict[str, Any] | None = None,
    boe_application: dict[str, Any] | None = None,
    boe_product: dict[str, Any] | None = None,
) -> dict[str, str]:
    """Return product/customer/application essays keyed like the delivered deck."""
    period = "全年" if full_year else "前三季度"
    year_label = "25年全年" if full_year else "25年前三季度"
    return {
        "product": _product_essay(
            maker, year_label, period, full_year, size_rows, product, boe_product,
        ),
        "customer": _customer_essay(
            maker, year_label, period, full_year, customer, boe_customer,
        ),
        "application": _application_essay(
            maker, year_label, period, full_year, application, boe_application,
        ),
    }


def _product_essay(
    maker: str,
    year_label: str,
    period: str,
    full_year: bool,
    size_rows: list[dict[str, Any]],
    product: dict[str, Any],
    boe_product: dict[str, Any] | None = None,
) -> str:
    segments = _maker_segments(maker, size_rows)
    if not segments:
        return ""

    positives = sorted(
        (item for item in segments if (item["contribution"] or 0) > 0),
        key=lambda item: item["contribution"],
        reverse=True,
    )
    negatives = sorted(
        (item for item in segments if (item["contribution"] or 0) < 0),
        key=lambda item: item["contribution"],
    )
    if not positives:
        return ""

    first = positives[0]
    parts = [
        f"{maker} {year_label}增长主要于{_seg_label(first)}产品增长所驱动，"
        f"同比{_pct(first['yoy'])}，增长贡献{_pct(first['contribution'], signed=False)}"
    ]
    if len(positives) > 1:
        second = positives[1]
        parts.append(
            f"；其次是{_seg_label(second)}产品，同比{_pct(second['yoy'])}，"
            f"增长贡献{_pct(second['contribution'], signed=False)}"
        )
    if negatives:
        drag = negatives[0]
        parts.append(
            f"；{_seg_label(drag)}产品表现不佳，同比{_pct(drag['yoy'])}，"
            f"贡献{_pct(drag['contribution'])}，拖累整体增速"
        )
    parts.append("。")

    by_volume = sorted(segments, key=lambda item: item["qty"] or 0, reverse=True)
    base = by_volume[0] if by_volume else None
    engine = first
    if base and engine:
        parts.append(
            f"从产品结构看，{maker}{period}{_seg_label(base)}作为基本盘稳固地位，"
            f"{_seg_label(engine)}发力作为核心引擎提拉增长。"
        )

    boe_bits = _product_vs_boe(maker, size_rows, product, boe_product)
    if boe_bits:
        parts.append(boe_bits)
    return "".join(parts)


def _customer_essay(
    maker: str,
    year_label: str,
    period: str,
    full_year: bool,
    customer: dict[str, Any],
    boe_customer: dict[str, Any] | None,
) -> str:
    clients = ((customer.get("top_clients") or {}).get("clients") or [])
    if not clients:
        return ""

    def contribution(item: dict[str, Any]) -> float | None:
        value = item.get("growth_contribution_primary")
        if value is None:
            value = item.get("growth_contribution_y25_q1_q3")
        return value

    def share(item: dict[str, Any]) -> float | None:
        value = item.get("share_primary")
        if value is None:
            value = item.get("share_y25_q1_q3")
        return value

    def yoy(item: dict[str, Any]) -> float | None:
        value = item.get("yoy_primary")
        if value is None:
            value = item.get("yoy_2025_q1_q3_vs_2024_q1_q3")
        return value

    def qty(item: dict[str, Any]) -> float:
        periods = item.get("periods") or {}
        if full_year and periods.get("Y25F") is not None:
            return float(periods["Y25F"])
        return float(periods.get("Y25Q1-Q3") or 0)

    scored = [item for item in clients if contribution(item) is not None]
    if not scored:
        return ""

    engine = max(scored, key=lambda item: contribution(item) or float("-inf"))
    top_qty = max(clients, key=qty)
    parts = [
        f"{maker} {year_label}核心增长引擎来自于{engine.get('client')}，"
        f"出货同比{_pct(yoy(engine))}，增长贡献{_pct(contribution(engine), signed=False)}"
    ]

    if top_qty.get("client") != engine.get("client"):
        prior_share = top_qty.get("share_y24_q1_q3")
        current_share = share(top_qty)
        share_text = ""
        if prior_share is not None and current_share is not None:
            share_text = (
                f"，但在{maker}内部出货份额从{_pct(prior_share, digits=0, signed=False)}"
                f"下降至{_pct(current_share, digits=0, signed=False)}"
                if current_share < prior_share
                else (
                    f"，在{maker}内部出货份额从{_pct(prior_share, digits=0, signed=False)}"
                    f"提升至{_pct(current_share, digits=0, signed=False)}"
                )
            )
        elif top_qty.get("share_change_points") is not None and current_share is not None:
            delta = top_qty.get("share_change_points")
            share_text = (
                f"，内部出货份额{_pct(current_share, digits=0, signed=False)}"
                f"（变化{_points(delta)}个百分点）"
            )
        parts.append(
            f"；其次出货量第一为{top_qty.get('client')}{share_text}，"
            f"出货量同比{_pct(yoy(top_qty))}，增长贡献{_pct(contribution(top_qty))}"
        )
    parts.append("；")

    regions = ((customer.get("regions") or {}).get("rows") or [])
    region_line = _region_structure_line(maker, period, regions, full_year)
    if region_line:
        parts.append(region_line)

    boe_line = _customer_vs_boe(maker, regions, boe_customer, full_year)
    if boe_line:
        parts.append(boe_line)
    return "".join(parts)


def _application_essay(
    maker: str,
    year_label: str,
    period: str,
    full_year: bool,
    application: dict[str, Any],
    boe_application: dict[str, Any] | None,
) -> str:
    series = ((application.get("application_history") or {}).get("series") or [])
    if not series:
        return ""

    period_key = "Y25F" if full_year else "Y25Q1-Q3"
    yoy_key = period_key

    def app_qty(item: dict[str, Any]) -> float:
        periods = item.get("periods") or {}
        if full_year and periods.get("Y25F") is not None:
            return float(periods["Y25F"])
        return float(periods.get("Y25Q1-Q3") or 0)

    def app_share(item: dict[str, Any]) -> float | None:
        value = item.get("share_primary")
        if value is None:
            value = item.get("share_y25_q1_q3")
        return value

    def app_growth(item: dict[str, Any]) -> float | None:
        value = item.get("growth_contribution_primary")
        if value is None:
            value = item.get("growth_contribution_y25_q1_q3")
        return value

    ranked = sorted(series, key=app_qty, reverse=True)
    first = ranked[0]
    parts = [
        f"{maker} {year_label}核心出货应用类为{first.get('application')}，"
        f"占总出货量{_pct(app_share(first), digits=0, signed=False)}，"
        f"占总增长贡献{_pct(app_growth(first), digits=0, signed=False)}，"
        f"同比{_pct((first.get('yoy_periods') or {}).get(yoy_key))}"
    ]
    if len(ranked) > 1:
        second = ranked[1]
        parts.append(
            f"；其次是{second.get('application')}，"
            f"占总出货量{_pct(app_share(second), digits=0, signed=False)}，"
            f"增长贡献{_pct(app_growth(second), digits=0, signed=False)}，"
            f"同比{_pct((second.get('yoy_periods') or {}).get(yoy_key))}"
        )
        area = second.get("display_area") or {}
        area_growth = (
            area.get("growth_contribution_primary")
            if area.get("growth_contribution_primary") is not None
            else area.get("growth_contribution_y25_q1_q3")
        )
        if area_growth is not None:
            parts.append(
                f"，但在面积上{second.get('application')}占增长贡献"
                f"{_pct(area_growth, digits=0, signed=False)}"
            )
    parts.append("。")

    boe_line = _application_vs_boe(maker, series, boe_application, full_year)
    if boe_line:
        parts.append(boe_line)
    return "".join(parts)


def _maker_segments(maker: str, size_rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    result = []
    for row in size_rows:
        metric = ((row.get("makers") or {}).get(maker) or {})
        contribution = metric.get("growth_contribution_2025_vs_2024")
        yoy = metric.get("yoy_2025_vs_2024")
        qty = (metric.get("values") or {}).get("2025")
        if contribution is None or yoy is None:
            continue
        result.append({
            "technology": row.get("technology"),
            "label": row.get("label"),
            "row_key": row.get("row_key"),
            "contribution": float(contribution),
            "yoy": float(yoy),
            "qty": float(qty) if qty is not None else 0.0,
            "metric": metric,
        })
    return result


def _seg_label(segment: dict[str, Any]) -> str:
    return f"{segment.get('technology')} {segment.get('label')}"


def _product_vs_boe(
    maker: str,
    size_rows: list[dict[str, Any]],
    product: dict[str, Any],
    boe_product: dict[str, Any] | None = None,
) -> str:
    if maker == "BOE":
        return _boe_product_self(size_rows, product)

    def tech_qty(name: str, technology: str) -> float:
        total = 0.0
        for row in size_rows:
            if row.get("technology") != technology:
                continue
            value = (((row.get("makers") or {}).get(name) or {}).get("values") or {}).get("2025")
            if value is not None:
                total += float(value)
        return total

    def bucket_qty(name: str, row_key: str) -> float:
        row = next((item for item in size_rows if item.get("row_key") == row_key), None)
        if not row:
            return 0.0
        value = (((row.get("makers") or {}).get(name) or {}).get("values") or {}).get("2025")
        return float(value or 0)

    maker_ltps = tech_qty(maker, "LTPS")
    boe_ltps = tech_qty("BOE", "LTPS")
    maker_asi_small = bucket_qty(maker, "a_si.<8")
    boe_asi_small = bucket_qty("BOE", "a_si.<8")
    maker_asi_mid = bucket_qty(maker, "a_si.[12,15)")
    boe_asi_mid = bucket_qty("BOE", "a_si.[12,15)")

    bits = ["相较于BOE，"]
    if boe_ltps > 0 and maker_ltps > 0:
        bits.append(f"{maker} LTPS全尺寸{_ratio_phrase(maker_ltps, boe_ltps)}")
    if maker_asi_small or boe_asi_small:
        gap = abs(maker_asi_small - boe_asi_small)
        if maker_asi_small >= boe_asi_small:
            bits.append(f"，但a-Si上{maker}仅守住8”以下基本盘（{_num(gap)}K差距）")
        else:
            bits.append(f"，a-Si 8”以下由BOE领先（{_num(gap)}K差距）")
    if boe_asi_mid > maker_asi_mid and boe_asi_mid > 0:
        bits.append("，12”-15”由BOE规模优势主导")

    tech = product.get("technology_history") or {}
    boe_tech = ((boe_product or {}).get("technology_history") or {})
    yoy_key = "Y25Q1-Q3"
    maker_asi_yoy = ((tech.get("a-Si") or {}).get("yoy_periods") or {}).get(yoy_key)
    maker_ltps_yoy = ((tech.get("LTPS") or {}).get("yoy_periods") or {}).get(yoy_key)
    boe_asi_yoy = ((boe_tech.get("a-Si") or {}).get("yoy_periods") or {}).get(yoy_key)
    boe_ltps_yoy = ((boe_tech.get("LTPS") or {}).get("yoy_periods") or {}).get(yoy_key)
    if any(value is not None for value in (maker_asi_yoy, maker_ltps_yoy, boe_asi_yoy, boe_ltps_yoy)):
        bits.append("；")
        chunks = []
        if maker_asi_yoy is not None:
            chunks.append(f"{maker} a-Si同比{_pct(maker_asi_yoy)}")
        if boe_asi_yoy is not None:
            chunks.append(f"BOE同比{_pct(boe_asi_yoy)}")
        if maker_ltps_yoy is not None:
            chunks.append(f"LTPS同比{_pct(maker_ltps_yoy)}")
        if boe_ltps_yoy is not None:
            chunks.append(f"BOE同比{_pct(boe_ltps_yoy)}")
        bits.append("，".join(chunks))
    return "".join(bits) if len(bits) > 1 else ""


def _boe_product_self(size_rows: list[dict[str, Any]], product: dict[str, Any]) -> str:
    segments = _maker_segments("BOE", size_rows)
    positives = sorted(
        (item for item in segments if item["contribution"] > 0),
        key=lambda item: item["contribution"],
        reverse=True,
    )
    if not positives:
        return ""
    top = positives[:2]
    share = sum(item["contribution"] for item in top)
    labels = "、".join(_seg_label(item) for item in top)
    return f"从产品结构看，BOE {labels}为核心增长引擎，累计贡献{_pct(share, digits=0)}。"


def _region_primary(item: dict[str, Any], full_year: bool) -> tuple[float | None, float | None]:
    if full_year and (item.get("full_year") or {}).get("Y25") is not None:
        fy = item.get("full_year") or {}
        return fy.get("Y25"), fy.get("yoy_2025_vs_2024")
    q = item.get("q1_q3") or {}
    return q.get("Y25Q1-Q3"), q.get("yoy_2025_vs_2024")


def _region_structure_line(
    maker: str,
    period: str,
    regions: list[dict[str, Any]],
    full_year: bool,
) -> str:
    usable = []
    for item in regions:
        if item.get("region") in {None, "其他"}:
            continue
        qty, yoy = _region_primary(item, full_year)
        if qty is None:
            continue
        usable.append({
            "region": item.get("region"),
            "qty": float(qty),
            "yoy": yoy,
        })
    if not usable:
        return ""
    top = max(usable, key=lambda item: item["qty"])
    growing = [item for item in usable if (item["yoy"] or 0) > 0]
    if growing:
        best_growth = max(growing, key=lambda item: item["yoy"] or 0)
        if best_growth["region"] == "日系":
            return (
                f"从客户结构看，{maker}增长主要受益于日系客户放量；"
                f"{top['region']}客户凭规模托底，保障基本盘。"
            )
    return (
        f"从客户结构看，{maker}以{top['region']}客户为出货基本盘"
        f"（{_num(top['qty'])}K，同比{_pct(top['yoy'])}）。"
    )


def _customer_vs_boe(
    maker: str,
    regions: list[dict[str, Any]],
    boe_customer: dict[str, Any] | None,
    full_year: bool,
) -> str:
    if maker == "BOE" or not boe_customer:
        return ""
    boe_regions = ((boe_customer.get("regions") or {}).get("rows") or [])
    boe_by_name = {item.get("region"): item for item in boe_regions}
    comparisons = []
    for item in regions:
        region = item.get("region")
        if region in {None, "其他"} or region not in boe_by_name:
            continue
        qty, _ = _region_primary(item, full_year)
        boe_qty, _ = _region_primary(boe_by_name[region], full_year)
        if qty is None or boe_qty in {None, 0}:
            continue
        comparisons.append((region, float(qty), float(boe_qty), float(qty) / float(boe_qty)))
    if not comparisons:
        return ""
    leading = [item for item in comparisons if item[3] >= 1]
    trailing = [item for item in comparisons if item[3] < 1]
    bits = ["相较于BOE，"]
    if leading:
        lead_text = "、".join(
            f"{region}（{_ratio_phrase(qty, boe_qty)}）"
            for region, qty, boe_qty, _ in sorted(leading, key=lambda item: item[3], reverse=True)[:3]
        )
        bits.append(f"{maker}在{lead_text}上高于BOE")
    if trailing:
        trail = min(trailing, key=lambda item: abs(item[1] - item[2]))
        region, qty, boe_qty, _ = trail
        bits.append(f"，{region}相差较少（{_num(abs(qty - boe_qty))}K差距）")
        dominated = [item for item in trailing if item[3] < 0.5]
        if dominated:
            names = "、".join(item[0] for item in dominated[:2])
            bits.append(f"，{names}上BOE占据主导")
    return "".join(bits) if len(bits) > 1 else ""


def _application_vs_boe(
    maker: str,
    series: list[dict[str, Any]],
    boe_application: dict[str, Any] | None,
    full_year: bool,
) -> str:
    if maker == "BOE" or not boe_application:
        return ""
    boe_series = ((boe_application.get("application_history") or {}).get("series") or [])
    boe_by_name = {item.get("application"): item for item in boe_series}

    def qty(item: dict[str, Any]) -> float:
        periods = item.get("periods") or {}
        if full_year and periods.get("Y25F") is not None:
            return float(periods["Y25F"])
        return float(periods.get("Y25Q1-Q3") or 0)

    comparisons = []
    for item in series:
        name = item.get("application")
        if name not in boe_by_name:
            continue
        mine = qty(item)
        theirs = qty(boe_by_name[name])
        if mine <= 0 or theirs <= 0:
            continue
        comparisons.append((name, mine, theirs, mine / theirs))
    if not comparisons:
        return ""
    best = max(comparisons, key=lambda item: item[3])
    worst = min(comparisons, key=lambda item: item[3])
    bits = [
        f"相较于BOE，{maker}在{best[0]}应用上占据主导（{_ratio_phrase(best[1], best[2])}）"
    ]
    if worst[0] != best[0] and worst[3] < 1:
        bits.append(f"，但在{worst[0]}应用上BOE占据主导地位")
    return "".join(bits)


def _ratio_phrase(left: float, right: float) -> str:
    if right <= 0:
        return "领先"
    ratio = left / right
    if ratio >= 1:
        if abs(ratio - round(ratio)) < 0.15:
            return f"近{int(round(ratio))}倍差距"
        return f"近{ratio:.1f}倍差距"
    gap = abs(left - right)
    return f"{_num(gap)}K差距"


def _pct(value: Any, digits: int = 0, signed: bool = True) -> str:
    if value is None:
        return "-"
    number = float(value) * 100
    if signed:
        return f"{number:+.{digits}f}%"
    return f"{number:.{digits}f}%"


def _points(value: Any) -> str:
    if value is None:
        return "-"
    return f"{float(value) * 100:+.1f}"


def _num(value: Any) -> str:
    if value is None:
        return "-"
    number = float(value)
    if abs(number - round(number)) < 1e-6:
        return f"{int(round(number)):,}"
    return f"{number:,.1f}"
