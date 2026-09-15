from __future__ import annotations
import json
import pandas as pd

from app.core.llm import LLMClient, SYSTEM_PROMPT
from app.models.schemas import ChatResponse
from app.services.market_analysis import MarketAnalyzer
from app.services.report_generator import build_report_payload
from app.services.validator import missing_required_for
from app.services.report_config import apply_report_instruction, config_summary, load_report_config
from app.services.report_planner import apply_report_plan_instruction, load_report_plan, report_plan_summary


class ChatEngine:
    def __init__(
        self,
        df: pd.DataFrame,
        context_items: list[dict] | None = None,
        dataset_id: str | None = None,
        analysis_period: dict | None = None,
    ):
        self.df = df
        self.dataset_id = dataset_id
        self.context_items = context_items or []
        self.analysis_period = analysis_period or {}
        self.analyzer = MarketAnalyzer(df, **self.analysis_period)
        self.llm = LLMClient()

    def _context_by_category(self, category: str) -> list[dict]:
        return [x for x in self.context_items if x.get("category") == category]

    def answer(
        self,
        question: str,
        use_llm: bool = True,
        history: list[dict[str, str]] | None = None,
        selected_chart_title: str | None = None,
        selected_component_id: str | None = None,
        selected_component_title: str | None = None,
    ) -> ChatResponse:
        q = question.strip().lower()
        history = history or []
        evidence = [
            "读取已解析的标准化DataFrame；说明型表优先按Sheet名、其次按文件名识别销量/产量/库存/出口等指标",
            "来自不同指标表的同粒度数据会按时间/市场/OEM/品牌/车型/动力等标准键对齐，不把不同层级盲目相加",
            f"当前统一分析周期：{self.analyzer.period.display_label}；同比基期：{self.analyzer.period.comparison_label}",
            "区间模式下销量/产量/出口等流量指标累计求和，库存等时点指标采用期末值；趋势只展示所选区间",
            "使用程序计算确定性统计结果，不让大模型猜测数字",
            "字段或资料缺失时返回缺失提示而非自动推断",
        ]
        warnings: list[str] = []
        table: list[dict] = []
        charts: list[dict] = []
        base_answer = ""
        intent = "general"
        report_updated = False
        report_config: dict = {}
        report_plan: dict = {}

        report_edit_words = ["加入", "添加", "增加", "放入", "插入", "移除", "删除", "不要", "取消", "去掉", "只保留", "重点", "强调", "突出", "标题改", "题目改", "章节标题", "报告名", "修改报告", "调整报告", "加回来", "恢复", "也加", "也放", "生成", "制作"]
        history_text = " ".join(str(x.get("content", "")) for x in history[-6:]).lower()
        recent_report_context = any(k in history_text for k in ["报告", "周报", "report_config", "报告编排", "市场观察", "1.1"])
        component_words = ["市场观察", "1.1", "总体市场", "总市场", "乘用车", "商用车", "新能源", "燃油", "oem", "品牌排名", "车型排名", "排名", "核心指标", "动力结构", "动力类型结构", "折线图", "趋势图", "图表组件"]
        selected_component_add = bool(selected_component_id or selected_chart_title) and any(k in q for k in ["这张图", "当前图", "该图", "选中的图", "这个图表", "当前组件", "加入到周报", "加入周报", "放到周报", "加入报告"])
        is_dynamic_report_plan = bool(self.dataset_id) and (any(k in q for k in component_words) or selected_component_add) and (
            any(k in q for k in report_edit_words) or any(k in q for k in ["报告", "周报", "ppt", "页面", "观察"])
        )
        is_report_edit = self.dataset_id and any(k in q for k in report_edit_words) and (any(k in q for k in ["报告", "周报", "ppt", "word"]) or recent_report_context)
        if is_dynamic_report_plan:
            intent = "report_plan_edit"
            report_plan, changes = apply_report_plan_instruction(
                self.dataset_id,
                question,
                self.df,
                self.analysis_period,
                use_llm=bool(use_llm),
                selected_chart_title=selected_chart_title,
                selected_component_id=selected_component_id,
            )
            report_config = load_report_config(self.dataset_id)
            report_updated = True
            base_answer = "已把你的自然语言要求转换为可执行的动态报告计划。\n" + "\n".join("- " + x for x in changes)
            base_answer += "\n\n" + "\n".join("- " + x for x in report_plan_summary(report_plan))
            base_answer += "\n\n后续周报预览和PPT/Word/Excel导出会按这个计划从整车分析结果中确定性取数；政策、人事、战略、产业链和竞争追踪仍只使用你上传的行业资料。"
            evidence.append("自然语言负责创建市场观察并选择看板组件；销量、同比、环比、占比、排名和图表数据全部由Python执行")
            evidence.append("报告计划已写入当前数据集 report_plan.json，预览与导出共享同一计划")
            if selected_component_id:
                evidence.append(f"当前看板组件：{selected_component_title or selected_component_id}（{selected_component_id}）")

        elif is_report_edit:
            intent = "report_edit"
            available = [c.get("title", "") for c in self.analyzer.line_charts() if c.get("title")]
            report_config, changes = apply_report_instruction(self.dataset_id, question, available)
            report_updated = True
            base_answer = "已更新报告编排。"
            if changes:
                base_answer += "\n" + "\n".join("- " + x for x in changes)
            base_answer += "\n\n" + "\n".join("- " + x for x in config_summary(report_config, available))
            evidence.append("报告修改要求已写入当前数据集的 report_config.json，后续预览和 Excel/Word/PPT 导出都会应用")

        elif any(k in q for k in ["折线图", "趋势图", "曲线", "画图", "绘图", "line chart", "line"]):
            intent = "line_charts"
            charts = self.analyzer.line_charts()
            if charts:
                table = [{"图表": c.get("title"), "系列数": len(c.get("series", [])), "来源字段": c.get("source")} for c in charts]
                base_answer = f"已根据 time_period 与可用字段生成 {len(charts)} 个折线图。只有字段明确支持的图表才会生成。"
            else:
                warnings.append("缺少 time_period 或可用数值/同比/动力/车型字段，无法生成折线图。")
                base_answer = "当前数据无法生成折线图；请补充时间字段和至少一个可统计数值字段。"

        elif any(k in q for k in ["报告", "周报", "洞察", "ppt", "内容形式"]):
            intent = "report"
            report_config = load_report_config(self.dataset_id) if self.dataset_id else {}
            report_plan = load_report_plan(self.dataset_id) if self.dataset_id else {}
            payload = build_report_payload(self.df, context_items=self.context_items, use_llm=use_llm, report_config=report_config, analysis_period=self.analysis_period, report_plan=report_plan)
            table = payload.get("overview_table", [])[:8]
            charts = payload.get("line_charts", [])[:2]
            stage_note = "已先让大模型独立分析本次上传表格，再将该分析按参考周报结构组织成报告。" if payload.get("ai_market_analysis") else "当前未启用大模型，已使用程序事实规则生成报告初稿。"
            base_answer = (
                stage_note + "\n\n"
                f"报告结构：1.行业全景（市场观察、宏观政策动态）；"
                f"2.企业行动（人事调整、战略调整与布局）；3.产业链观察；4.竞争追踪。\n\n"
                f"市场观察标题：{payload.get('market_observation_title')}\n\n"
                + "\n".join("- " + str(x) for x in payload.get("market_key_insights", [])[:5])
            )
            if not self.context_items:
                warnings.append("尚未补充行业资讯资料；政策、人事、战略、产业链和竞争追踪章节将显示‘本期暂无有效数据源’，不会自动编造。")

        elif any(k in q for k in ["宏观政策", "政策动态", "政策"]):
            intent = "context_macro_policy"
            items = self._context_by_category("macro_policy")
            if items:
                table = [{"标题": x.get("title"), "来源": f"{x.get('source_name','')} {x.get('locator','')}", "内容": str(x.get("content", ""))[:300]} for x in items[:20]]
                base_answer = f"已从补充资料中检索到 {len(items)} 条宏观政策相关内容。"
            else:
                base_answer = "当前没有可支持宏观政策章节的已上传资料。"
                warnings.append("不使用模型常识补写政策新闻，请上传事实性行业资料。")

        elif any(k in q for k in ["人事", "任命", "离职", "高管"]):
            intent = "context_personnel"
            items = self._context_by_category("personnel")
            table = [{"标题": x.get("title"), "来源": f"{x.get('source_name','')} {x.get('locator','')}", "内容": str(x.get("content", ""))[:300]} for x in items[:20]]
            base_answer = f"已从补充资料中检索到 {len(items)} 条人事相关内容。" if items else "当前没有已上传的人事动态资料。"

        elif any(k in q for k in ["战略", "布局", "企业行动"]):
            intent = "context_strategy"
            items = self._context_by_category("strategy")
            table = [{"标题": x.get("title"), "来源": f"{x.get('source_name','')} {x.get('locator','')}", "内容": str(x.get("content", ""))[:300]} for x in items[:20]]
            base_answer = f"已从补充资料中检索到 {len(items)} 条战略/布局相关内容。" if items else "当前没有已上传的企业战略资料。"

        elif any(k in q for k in ["产业链", "智能驾驶", "robotaxi", "电池", "芯片", "座舱", "显示"]):
            intent = "context_industry_chain"
            items = self._context_by_category("industry_chain")
            table = [{"标题": x.get("title"), "来源": f"{x.get('source_name','')} {x.get('locator','')}", "内容": str(x.get("content", ""))[:300]} for x in items[:20]]
            base_answer = f"已从补充资料中检索到 {len(items)} 条产业链相关内容。" if items else "当前没有已上传的产业链资料。"

        elif any(k in q for k in ["竞争追踪", "竞争事件", "专利", "收购", "股权"]):
            intent = "context_competition"
            items = self._context_by_category("competition")
            table = [{"标题": x.get("title"), "来源": f"{x.get('source_name','')} {x.get('locator','')}", "内容": str(x.get("content", ""))[:300]} for x in items[:20]]
            base_answer = f"已从补充资料中检索到 {len(items)} 条竞争追踪相关内容。" if items else "当前没有已上传的竞争追踪资料。"

        elif any(k in q for k in ["总销量", "批发销量", "总产量", "总库存", "大盘", "整体"]):
            intent = "totals"
            missing = missing_required_for("totals", self.df)
            warnings += [f"缺少字段：{m}，不自动推断" for m in missing]
            totals = self.analyzer.totals(latest=True)
            table = [{"指标": k, "数值": v} for k, v in totals.items()]
            base_answer = f"已按{self.analyzer.period.display_label}汇总当前可用核心指标；销量口径为{self.analyzer.sales_metric_label()}。同比基期为{self.analyzer.period.comparison_label}，不会把零售销量改名成批发销量。"

        elif "库存" in q:
            intent = "inventory"
            missing = missing_required_for("inventory", self.df)
            warnings += [f"缺少字段：{m}，不自动推断" for m in missing]
            table = [{"指标": "inventory", "数值": self.analyzer.totals(latest=True).get("inventory")}]
            base_answer = f"库存字段按{self.analyzer.period.display_label}计算；区间模式采用期末库存，不对库存月度值求和。若字段缺失则不做推断。"

        elif any(k in q for k in ["排名", "top", "前十", "top10"]):
            dim = "oem" if any(k in q for k in ["oem", "主机厂", "车企", "厂商"]) else "brand" if "品牌" in q else "model" if "车型" in q else "market"
            intent = f"ranking_{dim}"
            missing = missing_required_for(intent, self.df)
            warnings += [f"缺少字段：{m}，无法计算该排名" for m in missing]
            table = self.analyzer.ranking(dim)
            name = {"oem": "主机厂/OEM", "brand": "品牌", "model": "车型", "market": "市场"}.get(dim, dim)
            base_answer = f"已按{self.analyzer.period.display_label}的{self.analyzer.sales_metric_label()}计算{name}排名；区间模式为期间累计排名，占比使用同口径期间总量作为分母。"

        elif any(k in q for k in ["动力", "能源", "新能源", "结构", "占比", "渗透率"]):
            intent = "power_share"
            missing = missing_required_for("power_share", self.df)
            warnings += [f"缺少字段：{m}，无法计算动力结构" for m in missing]
            table = self.analyzer.power_structure()
            charts = [c for c in self.analyzer.line_charts() if "动力" in c.get("title", "") or "新能源" in c.get("title", "")]
            base_answer = f"已按{self.analyzer.period.display_label}统计动力类型{self.analyzer.sales_metric_label()}结构；区间模式按期间累计构成计算，趋势图展示所选月份。"

        elif any(k in q for k in ["趋势", "月度", "环比", "top车型趋势"]):
            intent = "trend"
            missing = missing_required_for("trend", self.df)
            warnings += [f"缺少字段：{m}，无法计算月度趋势" for m in missing]
            table = self.analyzer.trend()
            charts = self.analyzer.line_charts()[:3]
            base_answer = f"已按 time_period 生成{self.analyzer.period.display_label}对应的月度趋势；区间模式只展示所选月份，并保持每月原始统计口径。"

        elif any(k in q for k in ["异常", "阈值", "预警", "风险"]):
            intent = "anomaly"
            table = self.analyzer.detect_anomalies()
            base_answer = f"已按{self.analyzer.period.display_label}与{self.analyzer.period.comparison_label}的可比口径，以同比绝对值≥15%的基础阈值识别异常波动。"

        elif any(k in q for k in ["表格", "总览", "类似", "图片"]):
            intent = "overview_table"
            table = self.analyzer.overview()
            base_answer = "已生成参考周报市场观察页的整车市场核心指标表。"

        else:
            result = self.analyzer.run_all()
            table = result.overview_table[:10]
            charts = result.line_charts[:1]
            warnings += result.warnings
            base_answer = f"已返回{self.analyzer.period.display_label}整车市场总览。你可以继续追问排名、趋势、折线图、异常、动力结构、报告章节或已上传行业资料。"

        if use_llm and self.llm.enabled and intent not in {"report", "report_edit", "report_plan_edit"}:
            sample = table[:20]
            chart_summary = [{"title": c.get("title"), "series": [s.get("name") for s in c.get("series", [])]} for c in charts[:5]]
            # 为自由问答提供更完整但仍受控的结构化上下文；这样用户不需要只能问固定问题。
            full = self.analyzer.run_all()
            latest = self.analyzer.latest_frame()
            keep_cols = [c for c in [
                "time_period", "region", "market", "vehicle_type", "oem", "brand", "model", "power_type",
                "production", "sales", "retail_sales", "wholesale", "domestic_sales", "domestic_wholesale", "export", "inventory",
                "yoy_production", "yoy_sales", "yoy_retail_sales", "yoy_wholesale", "yoy_domestic_sales", "yoy_domestic_wholesale", "yoy_export",
            ] if c in latest.columns]
            latest_rows = latest[keep_cols].head(40).where(latest[keep_cols].notna(), None).to_dict("records") if keep_cols else []
            structured_context = {
                "latest_period": full.latest_period,
                "analysis_period": full.analysis_period,
                "period_label": (full.analysis_period or {}).get("display_label"),
                "comparison_period_label": (full.analysis_period or {}).get("comparison_label"),
                "overview": full.overview_table[:10],
                "rankings": {k: v[:10] for k, v in full.rankings.items()},
                "power_share": full.power_share[:12],
                "monthly_trend": full.monthly_trend[-24:],
                "anomalies": full.anomalies[:20],
                "available_line_charts": [{"title": c.get("title"), "series": [x.get("name") for x in c.get("series", [])]} for c in full.line_charts],
                "latest_source_rows": latest_rows,
                "uploaded_context": [{"category": x.get("category"), "title": x.get("title"), "content": str(x.get("content", ""))[:500], "source": x.get("source_name")} for x in self.context_items[:20]],
            }
            conversation = [{"role": str(x.get("role", "user")), "content": str(x.get("content", ""))[:1200]} for x in history[-8:] if x.get("content")]
            messages = [{"role": "system", "content": SYSTEM_PROMPT}] + conversation + [
                {"role": "user", "content": f"当前用户问题：{question}\n当前程序识别意图：{intent}\n固定程序结论：{base_answer}\n当前直接结果表：{json.dumps(sample, ensure_ascii=False, default=str)}\n当前图表：{json.dumps(chart_summary, ensure_ascii=False, default=str)}\n完整受控数据上下文：{json.dumps(structured_context, ensure_ascii=False, default=str)}\n警告：{json.dumps(warnings, ensure_ascii=False)}\n请结合对话上下文回答当前问题。只能使用这些数据与已上传事实资料；若数据不足必须明确说不足，不要引入未提供的外部事实。"},
            ]
            llm_text = self.llm.chat(messages, max_tokens=2000)
            if llm_text and not llm_text.startswith("[LLM调用失败"):
                base_answer = llm_text

        return ChatResponse(answer=base_answer, table=table, charts=charts, evidence_chain=evidence, warnings=warnings, report_updated=report_updated, report_config=report_config, report_plan=report_plan)
