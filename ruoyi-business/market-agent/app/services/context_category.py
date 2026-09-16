"""行业资料分类：与 GitHub 原版周报章节一致，并兼容短暂使用过的知识库四类。"""
from __future__ import annotations

GITHUB_CONTEXT_CATEGORIES = {
    "macro_policy", "personnel", "strategy", "industry_chain", "competition", "other",
}

# 若本地曾用知识库四类写入 context.json，加载时归一回 GitHub 章节。
KB_TO_GITHUB_CATEGORY = {
    "POLICY": "macro_policy",
    "NEWS": "personnel",
    "REPORT": "strategy",
    "PDF": "industry_chain",
}


def normalize_context_category(category: str | None) -> str:
    value = (category or "").strip()
    if value in GITHUB_CONTEXT_CATEGORIES:
        return value
    return KB_TO_GITHUB_CATEGORY.get(value, "other")
