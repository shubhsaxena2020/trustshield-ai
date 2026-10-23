"""Supabase-backed scan history: direct PostgREST calls with the service-role key (no SDK,
matching services/analysis/reputation/safe_browsing.py's urllib style).

RLS on scan_history restricts select/delete to the owning user as a second line of defense;
every query here additionally filters by user_id explicitly, so a server-side bug in the RLS
policy is not the only thing standing between one user's history and another's."""
from __future__ import annotations

import json
import logging
import urllib.error
import urllib.parse
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


def _eq(value: str) -> str:
    """A PostgREST 'eq.<value>' filter value, URL-encoded so a crafted id (e.g. a path param an
    attacker controls) can't inject extra '&'-separated filters into the query string. The
    backend's own user_id is the real isolation boundary here, not RLS (this client uses the
    service-role key, which bypasses RLS) -- see docs/SUPABASE_SETUP.md."""
    return "eq." + urllib.parse.quote(value, safe="")


def _scoped_user_id(user_id: Any) -> str:
    """Fail closed: refuse to build a query unless a non-empty user_id was supplied.

    This is the one filter that isolates users, so a missing identity must never turn into a
    missing filter. Previously ``None`` reached ``urllib.parse.quote`` and raised TypeError
    (a 500 from the endpoint), while ``""`` produced ``user_id=eq.`` -- a filter that looks
    like tenant scope but cannot match a real uuid, so PostgREST rejects it instead of
    returning nothing. Raise a clear ValueError before any request is made.
    """
    if not isinstance(user_id, str) or not user_id.strip():
        raise ValueError("user_id is required for history queries")
    return user_id


def list_for_user(supabase_url: str, service_role_key: str, *, user_id: str, limit: int = 50) -> list[dict]:
    user_id = _scoped_user_id(user_id)
    params = f"?user_id={_eq(user_id)}&order=created_at.desc&limit={int(limit)}"
    result = _request("GET", f"{supabase_url}/rest/v1/scan_history", service_role_key, params=params)
    return result if isinstance(result, list) else []


def delete_for_user(supabase_url: str, service_role_key: str, *, user_id: str, row_id: str) -> bool:
    """Deletes only if row_id belongs to user_id (filtered in the query, not just by RLS)."""
    user_id = _scoped_user_id(user_id)
    params = f"?id={_eq(row_id)}&user_id={_eq(user_id)}"
    result = _request("DELETE", f"{supabase_url}/rest/v1/scan_history", service_role_key, params=params)
    return bool(result)


def delete_all_for_user(supabase_url: str, service_role_key: str, *, user_id: str) -> None:
    user_id = _scoped_user_id(user_id)
    params = f"?user_id={_eq(user_id)}"
    _request("DELETE", f"{supabase_url}/rest/v1/scan_history", service_role_key, params=params)
