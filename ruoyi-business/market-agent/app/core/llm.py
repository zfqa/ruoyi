from __future__ import annotations
import json
import threading
import requests
from app.core.config import get_settings


_runtime_lock = threading.RLock()
_runtime_llm: dict[str, str | bool] = {"active": False}


def configure_runtime_llm(api_url: str, model: str, api_key: str, configured: bool) -> None:
    """接收若依统一LLM配置；仅保存在进程内，不写入market-agent磁盘。"""
    with _runtime_lock:
        _runtime_llm.update({
            "active": True,
            "api_url": (api_url or "").strip(),
            "model": (model or "").strip(),
            "api_key": (api_key or "").strip() if configured else "",
        })


def reset_runtime_llm() -> None:
    """仅供测试恢复环境变量模式。"""
    with _runtime_lock:
        _runtime_llm.clear()
        _runtime_llm["active"] = False


def _runtime_snapshot() -> dict[str, str | bool]:
    with _runtime_lock:
        return dict(_runtime_llm)


def _llm_error_message(exc: Exception) -> str:
    """Return a concise, actionable message without leaking credentials."""
    if isinstance(exc, requests.Timeout):
        return "大模型请求超时，请检查网络后重试"
    if isinstance(exc, requests.HTTPError):
        status = exc.response.status_code if exc.response is not None else None
        if status in {401, 403}:
            return "大模型鉴权失败，请检查 API Key 和模型权限"
        if status == 429:
            return "大模型请求过于频繁或额度不足，请稍后重试并检查账户额度"
        if status is not None:
            return f"大模型接口返回 HTTP {status}，请检查服务地址和模型配置"
    if isinstance(exc, requests.ConnectionError):
        detail = str(exc)
        if "WinError 10013" in detail or "权限不允许" in detail:
            return "大模型网络连接被 Windows/企业网络策略拦截；请配置授权代理 LLM_PROXY_URL，或请网络管理员放行 api.deepseek.com:443"
        return "无法连接大模型服务，请检查网络、代理、防火墙和服务地址"
    return f"大模型调用异常：{type(exc).__name__}"


class LLMClient:
    """兼容 OpenAI Chat Completions 协议的轻量客户端。"""

    def __init__(self):
        self.settings = get_settings()
        runtime = _runtime_snapshot()
        self.runtime_managed = bool(runtime.get("active"))
        self.api_url = str(runtime.get("api_url", "")) if self.runtime_managed else self.settings.llm_base_url
        self.model = str(runtime.get("model", "")) if self.runtime_managed else self.settings.llm_model
        self.api_key = str(runtime.get("api_key", "")) if self.runtime_managed else (self.settings.llm_api_key or "")

    @property
    def enabled(self) -> bool:
        return bool(self.api_key and self.api_url and self.model)

    @property
    def proxy_configured(self) -> bool:
        return bool(str(self.settings.llm_proxy_url or "").strip())

    @property
    def request_proxies(self) -> dict[str, str] | None:
        proxy = str(self.settings.llm_proxy_url or "").strip()
        return {"http": proxy, "https": proxy} if proxy else None

    def chat(self, messages: list[dict[str, str]], temperature: float = 0.2, max_tokens: int = 1600) -> str | None:
        if not self.enabled:
            return None
        url = self.api_url.rstrip("/")
        if not url.endswith("/chat/completions"):
            url += "/chat/completions"
        headers = {"Authorization": f"Bearer {self.api_key}", "Content-Type": "application/json"}
        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
        }
        try:
            resp = requests.post(
                url,
                headers=headers,
                data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                timeout=self.settings.llm_timeout,
                proxies=self.request_proxies,
            )
            resp.raise_for_status()
            data = resp.json()
            return data["choices"][0]["message"]["content"].strip()
        except Exception as exc:
            return f"[LLM调用失败，已降级为规则结果] {_llm_error_message(exc)}"


SYSTEM_PROMPT = """你是汽车行业市场洞察分析师。回答必须严格基于传入的结构化数据、程序计算结果和证据链。关键数值不得自行重算或猜测；字段缺失时必须明确提示缺失，禁止自动推断。没有外部事实资料时，不得编造政策、新闻、企业动作或原因解释。输出中文，先给结论，再给关键数据和风险提示。"""

MARKET_ANALYSIS_SYSTEM_PROMPT = """你负责第一阶段：只分析当前上传的汽车市场表格事实。
必须遵守：
1. 输入中的所有数字均由Python程序计算，你只能引用，不能自行重算、改写或补值。
2. 只能分析当前fact_pack中的规模、同比、结构、排名、集中度、趋势、异常及数据完整性。
3. 不能使用参考周报中的任何旧日期、旧数字、旧结论、旧企业名称或旧原因；参考周报仅代表最终报告的章节结构和表达密度，不是事实来源。
4. 如果没有政策/新闻证据，不允许解释“为什么”，只能写数据表现，并标记“原因需结合外部资料验证”。
5. 缺失字段不得推断；数据冲突必须提示人工复核。
6. 输出必须是严格JSON，不要Markdown。
"""

REPORT_SYSTEM_PROMPT = """你负责第二阶段：把“第一阶段对当前表格的分析结果”组织成汽车行业市场洞察周报初稿。
必须遵守：
1. 报告中的市场结论必须来自本次current_market_analysis和current_fact_pack，不能照抄或复刻参考周报的事实内容。
2. 参考周报只控制结构、版式逻辑和写作风格：行业全景→市场观察/宏观政策→企业行动→产业链观察→竞争追踪。
3. 所有销量、产量、库存、占比、同比等数字只能使用程序提供的数据，不得自行猜测或修改。
4. 市场变化原因没有可靠证据时只能写“原因需结合外部资料进一步验证”。
5. 宏观政策、人事调整、战略布局、产业链观察、竞争追踪由程序完整保留用户原文，不属于你的输出范围，禁止摘要、删减或改写。
6. 市场观察的标题、关键洞察、逐行分析、竞争格局分析必须针对当前数据重新生成，不得使用示例句式中的具体事实。
7. 输出中文、事实密集、结论先行；判断性内容属于AI初稿，需人工确认。
8. 输出必须是严格JSON，不要Markdown。
"""
