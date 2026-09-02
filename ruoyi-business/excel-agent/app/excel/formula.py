"""公式与缓存值处理."""
from typing import Optional


def get_formula_and_cached(cell_formula) -> tuple:
    """从 openpyxl cell 提取公式与缓存值.

    当 data_only=True 时 cell.value 为缓存值，formula 为 None；
    当 data_only=False 时 cell.value 为公式，缓存值需要从 data_only=True 的 wb 读取。
    本模块依赖外部同时传入两份 cell。
    """
    pass
