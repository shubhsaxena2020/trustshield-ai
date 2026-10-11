"""Supabase-backed scan history: direct PostgREST calls with the service-role key (no SDK,
matching services/analysis/reputation/safe_browsing.py's urllib style).

RLS on scan_history restricts select/delete to the owning user as a second line of defense;
every query here additionally filters by user_id explicitly, so a server-side bug in the RLS
policy is not the only thing standing between one user's history and another's."""
from __future__ import annotations

import json
import logging
import urllib.error
import urllib.request
from typing import Any

_log = logging.getLogger("trustshield.history")
_TIMEOUT_SECONDS = 5
_SUMMARY_MAX_CHARS = 120


def summarize(raw: str) -> str:
    text = " ".join(raw.split())
    if len(text) <= _SUMMARY_MAX_CHARS:
        return text
    return text[:_SUMMARY_MAX_CHARS] + "…"


def _headers(service_role_key: str, *, want_representation: bool) -> dict[str, str]:
    headers = {
        "apikey": service_role_key,
        "Authorization": f"Bearer {service_role_key}",
        "Content-Type": "application/json",
    }
    if want_representation:
        headers["Prefer"] = "return=representation"
    return headers


def _request(method: str, url: str, service_role_key: str, *, body: dict | None = None,
             params: str = "") -> Any:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        url + params, data=data, method=method,
        headers=_headers(service_role_key, want_representation=method in ("POST", "DELETE")),
    )
    with urllib.request.urlopen(req, timeout=_TIMEOUT_SECONDS) as r:  # noqa: S310 (fixed https host from settings)
        raw = r.read()
        return json.loads(raw) if raw else None


def save(supabase_url: str, service_role_key: str, *, user_id: str, input_type: str,
         input_summary: str, risk_score: int, risk_label: str, result_json: dict) -> None:
    """Best-effort write; failures are logged and never raised (history must not break analysis)."""
    try:
        _request("POST", f"{supabase_url}/rest/v1/scan_history", service_role_key, body={
            "user_id": user_id, "input_type": input_type, "input_summary": input_summary,
            "risk_score": risk_score, "risk_label": risk_label, "result_json": result_json,
        })
    except Exception as exc:
        _log.warning(json.dumps({"event": "history_write_failed", "error_type": type(exc).__name__}))


def list_for_user(supabase_url: str, service_role_key: str, *, user_id: str, limit: int = 50) -> list[dict]:
    params = f"?user_id=eq.{user_id}&order=created_at.desc&limit={limit}"
    result = _request("GET", f"{supabase_url}/rest/v1/scan_history", service_role_key, params=params)
    return result if isinstance(result, list) else []


def delete_for_user(supabase_url: str, service_role_key: str, *, user_id: str, row_id: str) -> bool:
    """Deletes only if row_id belongs to user_id (filtered in the query, not just by RLS)."""
    params = f"?id=eq.{row_id}&user_id=eq.{user_id}"
    result = _request("DELETE", f"{supabase_url}/rest/v1/scan_history", service_role_key, params=params)
    return bool(result)


def delete_all_for_user(supabase_url: str, service_role_key: str, *, user_id: str) -> None:
    params = f"?user_id=eq.{user_id}"
    _request("DELETE", f"{supabase_url}/rest/v1/scan_history", service_role_key, params=params)
