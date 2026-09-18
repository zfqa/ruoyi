import math

from app.api.routes import _json_safe


def test_json_safe_converts_nan_and_inf_to_null():
    payload = {
        "yoy": float("nan"),
        "growth": float("inf"),
        "drop": float("-inf"),
        "ok": 1.5,
        "nested": [{"value": float("nan")}, math.nan],
    }
    cleaned = _json_safe(payload)
    assert cleaned == {
        "yoy": None,
        "growth": None,
        "drop": None,
        "ok": 1.5,
        "nested": [{"value": None}, None],
    }
