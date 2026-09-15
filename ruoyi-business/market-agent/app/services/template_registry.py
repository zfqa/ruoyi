from __future__ import annotations

from dataclasses import dataclass
import re
from typing import Iterable


def clean_token(value: object) -> str:
    text = "" if value is None else str(value)
    text = text.replace("\n", " ").replace("（", "(").replace("）", ")")
    return re.sub(r"\s+", "", text.strip().lower())


@dataclass(frozen=True)
class MetricSpec:
    key: str
    label: str
    aliases: tuple[str, ...]


# 具体口径必须优先于“销量”泛称。固定模板命中后是确定性规则，不使用0.92/0.96/0.99概率权重。
METRIC_SPECS: tuple[MetricSpec, ...] = (
    MetricSpec("domestic_wholesale", "国内批发销量", ("国内批发销量", "国内批销量", "国内批发量", "domesticwholesale", "domestic wholesale")),
    MetricSpec("retail_sales", "零售销量", ("总零售销量", "零售销量", "零售量", "零售销售", "retailsales", "retail sales")),
    MetricSpec("wholesale", "批发销量", ("总批发销量", "批发销量", "批销量", "批发量", "wholesale", "wholesalesales")),
    MetricSpec("domestic_sales", "国内销量", ("国内销量", "国内销售", "内销量", "内销", "domesticsales", "domestic sales")),
    MetricSpec("production", "产量", ("汽车产量", "整车产量", "总产量", "生产量", "产量", "production", "output")),
    MetricSpec("inventory", "库存", ("期末库存", "总库存", "库存量", "库存", "inventory", "stock")),
    MetricSpec("export", "出口", ("出口销量", "出口销售", "出口量", "出口", "exportsales", "export sales", "exports", "export")),
    MetricSpec("sales", "销量", ("汽车销量", "整车销量", "总销量", "销售量", "销量", "销售", "sales")),
)

METRIC_LABELS = {x.key: x.label for x in METRIC_SPECS}
SALES_METRICS = ("sales", "retail_sales", "wholesale")
DOMESTIC_SALES_METRICS = ("domestic_sales", "domestic_wholesale")

DIMENSION_ALIASES: dict[str, tuple[str, ...]] = {
    "region": ("国家/地区", "国家地区", "国家", "地区", "区域", "region", "country"),
    "market": ("指标", "市场", "细分市场", "market", "segment"),
    "vehicle_type": ("车种", "车型类别", "车辆类型", "vehicle_type", "vehicletype"),
    "oem": ("集团", "汽车集团", "车企", "主机厂", "整车厂", "厂商", "oem", "manufacturer"),
    "brand": ("整车厂/品牌", "整车厂品牌", "品牌", "brand"),
    "model": ("车型", "车系", "model"),
    "power_type": ("动力总成", "动力类型", "能源类型", "燃料类型", "动力", "能源", "power_type", "powertype", "power"),
    "size_class": ("级别", "车级", "尺寸", "class", "size"),
    "tech_route": ("技术路线", "技术", "路线", "technology", "tech"),
}

# Sheet/文件名用于判断主分析维度。多个明确表头维度并存时，最终维度会改成multi_dimension。
DIMENSION_NAME_PATTERNS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("model", ("车型销量", "车型产量", "车型库存", "车型", "车系", "model")),
    ("brand", ("品牌销量", "品牌产量", "品牌库存", "品牌", "brand")),
    ("oem", ("oem销量", "主机厂销量", "整车厂销量", "车企销量", "oem", "主机厂", "整车厂", "车企", "厂商")),
    ("power_type", ("动力类型销量", "动力总成销量", "能源类型销量", "动力结构", "动力类型", "动力总成", "能源类型")),
    ("vehicle_type", ("车种销量", "车种产量", "车种", "车辆类型")),
    ("region", ("国家销量", "地区销量", "国家/地区", "国家地区", "区域")),
    ("size_class", ("级别销量", "级别产量", "级别", "尺寸")),
    ("market", ("市场销量", "市场产量", "市场库存", "市场观察", "总体市场", "市场")),
)

GENERIC_DIMENSION_HEADERS = {
    clean_token(x) for x in ("名称", "对象", "项目", "规格", "类别", "分类", "明细", "项目名称", "对象名称", "name", "item", "entity")
}
GENERIC_VALUE_HEADERS = {
    clean_token(x) for x in ("数值", "值", "数量", "本期", "当期", "当月", "本月", "数据", "合计值", "总计值", "value", "amount", "volume", "qty", "quantity")
}
IGNORE_HEADERS = {
    clean_token(x) for x in ("序号", "备注", "说明", "单位", "数据源", "来源", "source", "note", "remark")
}

# 直接字段别名：只允许确定性精确别名，不做包含关系的“95%/96%”猜测。
EXACT_FIELD_ALIASES: dict[str, tuple[str, ...]] = {
    "time_period": ("时间", "月份", "年月", "周期", "日期", "month", "date", "period", "yearmonth"),
    **DIMENSION_ALIASES,
    "production": ("产量", "生产", "生产量", "汽车产量", "整车产量", "总产量", "production", "output"),
    "sales": ("销量", "总销量", "汽车销量", "整车销量", "销售量", "销售", "sales"),
    "retail_sales": ("零售销量", "总零售销量", "零售量", "零售销售", "retail sales", "retailsales"),
    "wholesale": ("批发销量", "总批发销量", "批销量", "批发量", "wholesale"),
    "domestic_sales": ("国内销量", "国内销售", "内销", "内销量", "domestic sales", "domesticsales"),
    "domestic_wholesale": ("国内批发销量", "国内批销量", "国内批发量", "domestic wholesale", "domesticwholesale"),
    "export": ("出口", "出口量", "出口销量", "出口销售", "export", "exports"),
    "inventory": ("库存", "库存量", "期末库存", "inventory", "stock"),
    "yoy_production": ("产量同比", "同比产量", "production yoy"),
    "yoy_sales": ("销量同比", "销售同比", "sales yoy"),
    "yoy_retail_sales": ("零售销量同比", "零售同比", "retail sales yoy"),
    "yoy_wholesale": ("批发销量同比", "批发同比", "wholesale yoy"),
    "yoy_domestic_sales": ("国内销量同比", "内销同比", "domestic sales yoy"),
    "yoy_domestic_wholesale": ("国内批发销量同比", "国内批发同比", "domestic wholesale yoy"),
    "yoy_export": ("出口同比", "出口销量同比", "export yoy"),
}

ALIAS_TO_FIELD: dict[str, str] = {}
for field, aliases in EXACT_FIELD_ALIASES.items():
    for alias in aliases:
        token = clean_token(alias)
        # 若注册表本身出现冲突，启动时直接失败，防止“看起来100%”但实际语义冲突。
        if token in ALIAS_TO_FIELD and ALIAS_TO_FIELD[token] != field:
            raise RuntimeError(f"模板注册表字段别名冲突: {alias} -> {ALIAS_TO_FIELD[token]} / {field}")
        ALIAS_TO_FIELD[token] = field


def exact_field_for_header(header: object) -> str | None:
    return ALIAS_TO_FIELD.get(clean_token(header))


def _specific_metric_hits(text: object) -> list[tuple[str, str]]:
    token = clean_token(text)
    if not token:
        return []
    hits: list[tuple[str, str]] = []
    for spec in METRIC_SPECS:
        for alias in sorted(spec.aliases, key=lambda x: len(clean_token(x)), reverse=True):
            a = clean_token(alias)
            if a and a in token:
                hits.append((spec.key, alias))
                break
    # 若命中具体销量口径，同时命中泛化sales，则丢弃泛化sales。
    keys = {k for k, _ in hits}
    if keys & {"retail_sales", "wholesale", "domestic_sales", "domestic_wholesale"}:
        hits = [(k, a) for k, a in hits if k != "sales"]
    # “国内批发销量”会同时命中国内销量，保留最具体的国内批发。
    if "domestic_wholesale" in {k for k, _ in hits}:
        hits = [(k, a) for k, a in hits if k not in {"domestic_sales", "wholesale", "sales"}]
    # 去重
    out: list[tuple[str, str]] = []
    seen = set()
    for item in hits:
        if item[0] not in seen:
            out.append(item)
            seen.add(item[0])
    return out


def match_metric_from_text(text: object) -> tuple[str | None, str | None, bool]:
    """返回(metric, evidence_alias, ambiguous)。只有唯一确定命中才算成功。"""
    hits = _specific_metric_hits(text)
    if not hits:
        return None, None, False
    if len({x[0] for x in hits}) == 1:
        return hits[0][0], hits[0][1], False
    return None, "/".join(x[1] for x in hits), True


def match_dimension_from_text(text: object) -> tuple[str | None, str | None, bool]:
    token = clean_token(text)
    if not token:
        return None, None, False
    hits: list[tuple[str, str]] = []
    for dim, aliases in DIMENSION_NAME_PATTERNS:
        for alias in sorted(aliases, key=lambda x: len(clean_token(x)), reverse=True):
            a = clean_token(alias)
            if a and a in token:
                hits.append((dim, alias))
                break
    # 去重；多个维度词同时出现时不猜单一维度。
    unique = []
    seen = set()
    for x in hits:
        if x[0] not in seen:
            unique.append(x)
            seen.add(x[0])
    if not unique:
        return None, None, False
    if len(unique) == 1:
        return unique[0][0], unique[0][1], False
    return None, "/".join(x[1] for x in unique), True


def registered_metric_keys() -> tuple[str, ...]:
    return tuple(x.key for x in METRIC_SPECS)


def registered_dimension_keys() -> tuple[str, ...]:
    return tuple(DIMENSION_ALIASES.keys())
