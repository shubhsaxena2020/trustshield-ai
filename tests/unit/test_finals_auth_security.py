"""Security regressions for the W1 auth + scan-history slice (child 9).

Every test drives the real FastAPI app through ``TestClient`` with the network boundary
monkeypatched (no live Supabase, no outbound request), so what is asserted is runtime
behaviour a reviewer cannot read off the source: the status each failure mode produces, the
PostgREST query that is actually built, and the identity that actually reaches the store.

Invariant -> test map
  1 token expiry .......... expired/rejected token -> 401 on every history endpoint, upstream
                            outage -> 503, and a stale token on /analyze/text is never served
                            anonymously
  2 cross-account identity  a client-supplied user_id (query, JSON body, header, path injection)
                            never reaches the store; exactly one user_id filter per query
  3 service-role key ...... never echoed in a response body/headers, never sent off the
                            configured Supabase host
  4 duplicate submit ...... identical resubmits get distinct analysis ids and distinct writes
  5 history edge cases .... empty history, foreign-row delete, limit forwarding, unconfigured server
  6 response shape ........ rows pass through unchanged and in order (result_json included)
"""
from __future__ import annotations

import io
import json
import urllib.error
import urllib.parse
import urllib.request

import pytest
from fastapi.testclient import TestClient

from services.api import auth as auth_module
from services.api.main import create_app
from services.api.settings import Settings
from services.history import store as history_store

SUPABASE_URL = "https://supabase.test"
SERVICE_KEY = "srk_test_service_role_key_do_not_echo_0123456789"
USER_A = "11111111-1111-4111-8111-111111111111"
USER_B = "22222222-2222-4222-8222-222222222222"
TOKEN_A = "access-token-for-user-a"
TOKEN_B = "access-token-for-user-b"

HISTORY_ENDPOINTS = [
    ("get", "/api/v1/history"),
    ("delete", "/api/v1/history/row-1"),
    ("delete", "/api/v1/history"),
]


# ---- helpers -------------------------------------------------------------------------------------------------------


def _client(*, configured: bool = True, raise_server_exceptions: bool = False) -> TestClient:
    settings = Settings(
        supabase_url=SUPABASE_URL if configured else "",
        supabase_service_role_key=SERVICE_KEY if configured else "",
    )
    return TestClient(create_app(settings), raise_server_exceptions=raise_server_exceptions)


def _as(user_id: str):
    def lookup(supabase_url, service_role_key, access_token):
        return auth_module.CurrentUser(id=user_id, email=f"{user_id}@example.test")
    return lookup


def _record_store(monkeypatch, *, rows=None, deleted=True) -> list[tuple[str, tuple, dict]]:
    """Replace every history store entry point with a recorder; returns the call log."""
    calls: list[tuple[str, tuple, dict]] = []

    def recorder(name, value):
        def call(*args, **kwargs):
            calls.append((name, args, kwargs))
            return value
        return call

    monkeypatch.setattr(history_store, "list_for_user", recorder("list_for_user", [] if rows is None else rows))
    monkeypatch.setattr(history_store, "delete_for_user", recorder("delete_for_user", deleted))
    monkeypatch.setattr(history_store, "delete_all_for_user", recorder("delete_all_for_user", None))
    monkeypatch.setattr(history_store, "save", recorder("save", None))
    return calls


def _capture_requests(monkeypatch, *, rows=None, deleted_rows=None) -> list[dict]:
    """Let the real store build its query, then capture it instead of calling PostgREST."""
    calls: list[dict] = []

    def fake_request(method, url, service_role_key, *, body=None, params=""):
        calls.append({"method": method, "url": url, "key": service_role_key, "body": body, "params": params})
        if method == "GET":
            return [] if rows is None else rows
        return [] if deleted_rows is None else deleted_rows

    monkeypatch.setattr(history_store, "_request", fake_request)
    return calls


class _FakeResponse(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *exc_info):
        return False


def _header(request: urllib.request.Request, name: str) -> str | None:
    for key, value in request.header_items():
        if key.lower() == name:
            return value
    return None


def _http_error(code: int):
    def fake_urlopen(request, timeout):
        raise urllib.error.HTTPError(request.full_url, code, "upstream refused", {}, None)
    return fake_urlopen


def _query_segments(params: str) -> list[str]:
    """The '&'-separated filters a PostgREST request will actually send."""
    return params.lstrip("?").split("&")


def _user_filters(params: str) -> list[str]:
    return [segment for segment in _query_segments(params) if segment.startswith("user_id=")]


# ---- 1. token expiry: 401, 503, and never a silent anonymous fallback ------------------------------------------------


@pytest.mark.parametrize("method,path", HISTORY_ENDPOINTS)
def test_expired_token_is_401_on_every_history_endpoint(monkeypatch, method, path):
    monkeypatch.setattr(auth_module, "_lookup_user", lambda *a, **k: None)
    calls = _record_store(monkeypatch)
    res = getattr(_client(), method)(path, headers={"Authorization": f"Bearer {TOKEN_A}"})
    assert res.status_code == 401
    # The 401 must come from token validation, not from an endpoint's "log in" guard for anonymous callers:
    # that distinction is what proves an expired session is surfaced instead of degraded to anonymous.
    assert any(tok in res.json()["detail"].lower() for tok in ("expired", "session"))
    assert calls == [], "an expired token must never reach the history store"


@pytest.mark.parametrize("upstream_status", [401, 403])
def test_token_rejected_by_upstream_is_401_for_403_as_well(monkeypatch, upstream_status):
    monkeypatch.setattr(urllib.request, "urlopen", _http_error(upstream_status))
    calls = _record_store(monkeypatch)
    res = _client().get("/api/v1/history", headers={"Authorization": f"Bearer {TOKEN_A}"})
    assert res.status_code == 401, f"upstream {upstream_status} must surface as 401, not as an anonymous 200"
    assert isinstance(res.json(), dict) and "detail" in res.json()
    assert calls == []


@pytest.mark.parametrize("failure", ["http_500", "http_503", "timeout", "unreachable"])
def test_upstream_outage_is_503_never_401_and_never_anonymous(monkeypatch, failure):
    def timeout(request, timeout):
        raise TimeoutError("upstream timed out")

    def unreachable(request, timeout):
        raise urllib.error.URLError("name resolution failed")

    fake = {"http_500": _http_error(500), "http_503": _http_error(503),
            "timeout": timeout, "unreachable": unreachable}[failure]
    monkeypatch.setattr(urllib.request, "urlopen", fake)
    calls = _record_store(monkeypatch)
    client = _client()
    res = client.get("/api/v1/history", headers={"Authorization": f"Bearer {TOKEN_A}"})
    assert res.status_code == 503, "a verification outage must be retryable, never a 401 and never an anonymous list"
    assert any(tok in res.json()["detail"].lower() for tok in ("verify", "session"))
    assert calls == []
    # A failed verification must not be papered over by serving the scan anonymously either:
    post = client.post("/api/v1/analyze/text", json={"text": "pay the release fee today"},
                       headers={"Authorization": f"Bearer {TOKEN_A}"})
    assert post.status_code == 503
    assert len(client.app.state.store) == 0


def test_stale_token_is_not_served_anonymously_on_analyze(monkeypatch):
    monkeypatch.setattr(auth_module, "_lookup_user", lambda *a, **k: None)
    calls = _record_store(monkeypatch)
    client = _client()
    res = client.post("/api/v1/analyze/text",
                      json={"text": "URGENT: your account is blocked, pay the reactivation fee now"},
                      headers={"Authorization": f"Bearer {TOKEN_A}"})
    assert res.status_code == 401
    assert "x-analysis-id" not in {key.lower() for key in res.headers}
    assert len(client.app.state.store) == 0, "an expired session must not produce a stored analysis"
    assert [name for name, _, _ in calls] == [], "history_store.save must never run for an expired session"


@pytest.mark.parametrize("header", [None, "Bearer", "Bearer   ", "Basic abc123"])
def test_a_header_that_carries_no_token_is_still_anonymous(monkeypatch, header):
    """The contrast case: only a real bearer token can fail verification, so an absent/different
    scheme stays anonymous (unchanged anonymous scanning) and still writes no history."""
    calls = _record_store(monkeypatch)
    headers = {} if header is None else {"Authorization": header}
    res = _client().post("/api/v1/analyze/text", json={"text": "your parcel is waiting, pay 2 dollars"}, headers=headers)
    assert res.status_code == 200
    assert res.headers["X-Analysis-ID"]
    assert [name for name, _, _ in calls] == []


# ---- 2. cross-account boundaries -------------------------------------------------------------------------------------


def test_list_history_ignores_client_supplied_user_id_and_header(monkeypatch):
    monkeypatch.setattr(auth_module, "_lookup_user", _as(USER_A))
    calls = _record_store(monkeypatch, rows=[{"id": "row-a"}])
    res = _client().get(f"/api/v1/history?user_id={USER_B}",
                        headers={"Authorization": f"Bearer {TOKEN_A}", "X-User-Id": USER_B})
    assert res.status_code == 200
    (call,) = calls
    name, args, kwargs = call
    assert name == "list_for_user"
    assert args[0] == SUPABASE_URL and args[1] == SERVICE_KEY
    assert kwargs == {"user_id": USER_A}, "the only identity in the store call must be the verified one"


def test_list_history_query_has_exactly_one_user_id_filter(monkeypatch):
    monkeypatch.setattr(auth_module, "_lookup_user", _as(USER_A))
    calls = _capture_requests(monkeypatch, rows=[])
    res = _client().get(f"/api/v1/history?user_id={USER_B}&or=(user_id.eq.{USER_B})",
                        headers={"Authorization": f"Bearer {TOKEN_A}", "X-User-Id": USER_B})
    assert res.status_code == 200
    (call,) = calls
    assert _user_filters(call["params"]) == [f"user_id=eq.{USER_A}"]
    assert len(_query_segments(call["params"])) == 3, "user_id + order + limit, with nothing appended by the client"


def test_analyze_text_ignores_user_id_in_the_json_body(monkeypatch):
    monkeypatch.setattr(auth_module, "_lookup_user", _as(USER_A))
    calls = _capture_requests(monkeypatch)
    res = _client().post("/api/v1/analyze/text",
                         json={"text": "your KYC is expiring, pay the verification fee", "user_id": USER_B,
                               "UserId": USER_B},
                         headers={"Authorization": f"Bearer {TOKEN_A}"})
    assert res.status_code == 200
    writes = [call for call in calls if call["method"] == "POST"]
    assert len(writes) == 1
    body = writes[0]["body"]
    assert body["user_id"] == USER_A
    assert [key for key in body if "user_id" in key.lower()] == ["user_id"], "a body field must not add a second identity"


def test_delete_row_with_injected_user_id_stays_scoped_to_the_token_identity(monkeypatch):
    monkeypatch.setattr(auth_module, "_lookup_user", _as(USER_A))
    calls = _capture_requests(monkeypatch, deleted_rows=[{"id": "row-1"}])
    hostile_row_id = f"row-1&user_id=eq.{USER_B}"
    res = _client().delete(f"/api/v1/history/{urllib.parse.quote(hostile_row_id, safe='')}",
                           headers={"Authorization": f"Bearer {TOKEN_A}", "X-User-Id": USER_B})
    assert res.status_code == 200
    (call,) = calls
    segments = _query_segments(call["params"])
    assert len(segments) == 2, f"id + user_id only, got {segments}"
    assert _user_filters(call["params"]) == [f"user_id=eq.{USER_A}"]
    # The attacker-controlled path segment is data inside the id filter, not a second filter:
    assert urllib.parse.unquote(segments[0].split("=", 1)[1]) == f"eq.{hostile_row_id}"


def test_delete_all_history_is_scoped_to_the_token_identity(monkeypatch):
    monkeypatch.setattr(auth_module, "_lookup_user", _as(USER_A))
    calls = _capture_requests(monkeypatch)
    res = _client().delete(f"/api/v1/history?user_id={USER_B}",
                           headers={"Authorization": f"Bearer {TOKEN_A}", "X-User-Id": USER_B})
    assert res.status_code == 200
    (call,) = calls
    assert call["method"] == "DELETE"
    assert _query_segments(call["params"]) == [f"user_id=eq.{USER_A}"]


HOSTILE_FILTER_VALUES = [
    f"row&user_id=eq.{USER_B}",
    f"row?user_id=eq.{USER_B}",
    f"row%26user_id%3Deq.{USER_B}",
    "row=1&or=(id.gt.0)",
    "row#fragment",
    "row\n&user_id=eq.9",
    "row 1",
    "' or 1=1 --",
    "row\x00null",
]


@pytest.mark.parametrize("hostile", HOSTILE_FILTER_VALUES)
def test_eq_cannot_emit_a_query_delimiter(monkeypatch, hostile):
    encoded = history_store._eq(hostile)
    assert encoded.startswith("eq.")
    for delimiter in ("&", "?", "#", "="):
        assert delimiter not in encoded, f"{delimiter!r} in {encoded!r} would split or retarget the filter"
    assert urllib.parse.unquote(encoded[3:]) == hostile, "the value must round-trip as data"


@pytest.mark.parametrize("hostile", HOSTILE_FILTER_VALUES)
def test_delete_for_user_sends_exactly_one_user_id_segment_for_hostile_row_ids(monkeypatch, hostile):
    calls = _capture_requests(monkeypatch, deleted_rows=[{"id": "x"}])
    history_store.delete_for_user(SUPABASE_URL, SERVICE_KEY, user_id=USER_A, row_id=hostile)
    (call,) = calls
    segments = _query_segments(call["params"])
    assert len(segments) == 2, f"a hostile row id must not add query segments: {segments}"
    assert segments[0].startswith("id=eq.")
    assert segments[1] == f"user_id=eq.{USER_A}"


# ---- 3. service-role key confinement ---------------------------------------------------------------------------------


def test_auth_failure_never_echoes_the_service_role_key(monkeypatch):
    def boom(*args, **kwargs):
        raise RuntimeError(f"connect failed with apikey={SERVICE_KEY}")

    monkeypatch.setattr(auth_module, "_lookup_user", boom)
    res = _client().get("/api/v1/history", headers={"Authorization": f"Bearer {TOKEN_A}"})
    assert res.status_code == 503
    assert SERVICE_KEY not in res.text
    assert SERVICE_KEY not in json.dumps(dict(res.headers))


def test_history_store_failure_never_echoes_the_service_role_key(monkeypatch):
    def boom(*args, **kwargs):
        raise RuntimeError(f"upstream rejected apikey={SERVICE_KEY}")

    monkeypatch.setattr(auth_module, "_lookup_user", _as(USER_A))
    monkeypatch.setattr(history_store, "_request", boom)
    res = _client().get("/api/v1/history", headers={"Authorization": f"Bearer {TOKEN_A}"})
    assert 500 <= res.status_code < 600, "an upstream outage must never look like a successful list"
    assert res.status_code != 401, "an outage must not be reported as an expired session"
    assert SERVICE_KEY not in res.text
    assert SERVICE_KEY not in json.dumps(dict(res.headers))


def test_service_role_key_is_only_sent_to_the_configured_supabase_host(monkeypatch):
    sent: list[urllib.request.Request] = []

    def fake_urlopen(request, timeout):
        sent.append(request)
        if request.full_url.endswith("/auth/v1/user"):
            return _FakeResponse(json.dumps({"id": USER_A, "email": "a@example.test"}).encode())
        if request.get_method() == "DELETE":
            return _FakeResponse(b'[{"id": "row-1"}]')
        return _FakeResponse(b"[]")

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    client = _client(raise_server_exceptions=True)
    headers = {"Authorization": f"Bearer {TOKEN_A}", "X-User-Id": USER_B,
               "X-Forwarded-Host": "evil.example.com"}
    assert client.get("/api/v1/history", headers=headers).status_code == 200
    hostile_row_id = f"row-1&user_id=eq.{USER_B}@evil.example.com"
    assert client.delete(f"/api/v1/history/{urllib.parse.quote(hostile_row_id, safe='')}",
                         headers=headers).status_code == 200
    assert len(sent) == 4, "two verifications and two PostgREST calls"
    for request in sent:
        parts = urllib.parse.urlsplit(request.full_url)
        assert parts.scheme == "https"
        assert parts.netloc == "supabase.test", f"{parts.netloc} is not the configured Supabase host"
        assert "evil.example.com" not in parts.netloc
        assert _header(request, "apikey") == SERVICE_KEY
        if parts.path.startswith("/auth/v1/"):
            # The user's token is the identity being verified; the service key is only the apikey.
            assert _header(request, "authorization") == f"Bearer {TOKEN_A}"
        else:
            assert _header(request, "authorization") == f"Bearer {SERVICE_KEY}"
            assert TOKEN_A not in json.dumps(request.header_items())


def test_history_request_host_comes_from_settings_not_from_the_row_id(monkeypatch):
    calls = _capture_requests(monkeypatch, deleted_rows=[{"id": "x"}])
    history_store.delete_for_user(SUPABASE_URL, SERVICE_KEY, user_id=USER_A, row_id="//evil.example.com/steal")
    (call,) = calls
    final_url = call["url"] + call["params"]
    parts = urllib.parse.urlsplit(final_url)
    assert call["url"] == f"{SUPABASE_URL}/rest/v1/scan_history"
    assert parts.netloc == "supabase.test"
    assert "evil.example.com" not in parts.netloc


# ---- 4. duplicate-submit invariants (server side) --------------------------------------------------------------------


def test_identical_resubmits_get_distinct_ids_and_two_history_writes(monkeypatch):
    monkeypatch.setattr(auth_module, "_lookup_user", _as(USER_A))
    calls = _record_store(monkeypatch)
    client = _client()
    body = {"text": "URGENT: your KYC is expiring, pay the verification fee to keep the account open"}
    first = client.post("/api/v1/analyze/text", json=body, headers={"Authorization": f"Bearer {TOKEN_A}"})
    second = client.post("/api/v1/analyze/text", json=body, headers={"Authorization": f"Bearer {TOKEN_A}"})
    assert first.status_code == second.status_code == 200
    ids = [first.headers["X-Analysis-ID"], second.headers["X-Analysis-ID"]]
    assert first.json()["analysis_id"] == ids[0] and second.json()["analysis_id"] == ids[1]
    assert len(set(ids)) == 2, "an identical resubmit must not be served from a previous analysis"
    saves = [call for call in calls if call[0] == "save"]
    assert len(saves) == 2, "both submissions must be written to history"
    assert {call[2]["user_id"] for call in saves} == {USER_A}
    for analysis_id in ids:
        assert client.get(f"/api/v1/analysis/{analysis_id}").status_code == 200


def test_two_users_submitting_identical_text_never_share_an_analysis_id(monkeypatch):
    by_token = {TOKEN_A: USER_A, TOKEN_B: USER_B}

    def lookup(supabase_url, service_role_key, access_token):
        if access_token not in by_token:
            return None
        return auth_module.CurrentUser(id=by_token[access_token], email=f"{by_token[access_token]}@example.test")

    monkeypatch.setattr(auth_module, "_lookup_user", lookup)
    calls = _record_store(monkeypatch)
    client = _client()
    body = {"text": "Your parcel is held at customs; confirm the address and pay the release fee"}
    a = client.post("/api/v1/analyze/text", json=body, headers={"Authorization": f"Bearer {TOKEN_A}"})
    b = client.post("/api/v1/analyze/text", json=body, headers={"Authorization": f"Bearer {TOKEN_B}"})
    assert a.status_code == b.status_code == 200
    assert a.headers["X-Analysis-ID"] != b.headers["X-Analysis-ID"]
    assert a.json()["analysis_id"] != b.json()["analysis_id"]
    saves = [call for call in calls if call[0] == "save"]
    assert [call[2]["user_id"] for call in saves] == [USER_A, USER_B]
    assert USER_B not in json.dumps(saves[0][2], default=str), "A's write must not carry B's identity"
    assert USER_A not in json.dumps(saves[1][2], default=str)


def test_anonymous_scan_is_not_attributed_to_a_later_logged_in_scan(monkeypatch):
    monkeypatch.setattr(auth_module, "_lookup_user", _as(USER_A))
    calls = _record_store(monkeypatch)
    client = _client()
    body = {"text": "Send the OTP to unlock your refund; this offer expires in 10 minutes"}
    anon = client.post("/api/v1/analyze/text", json=body)
    authed = client.post("/api/v1/analyze/text", json=body, headers={"Authorization": f"Bearer {TOKEN_A}"})
    assert anon.status_code == authed.status_code == 200
    saves = [call for call in calls if call[0] == "save"]
    assert len(saves) == 1, "only the authenticated submission may be written to a user's history"
    assert saves[0][2]["user_id"] == USER_A
    assert anon.headers["X-Analysis-ID"] != authed.headers["X-Analysis-ID"]


# ---- 5. history edge cases -------------------------------------------------------------------------------------------


def test_empty_history_is_200_with_an_empty_list(monkeypatch):
    monkeypatch.setattr(auth_module, "_lookup_user", _as(USER_A))
    monkeypatch.setattr(history_store, "list_for_user", lambda *a, **k: [])
    res = _client().get("/api/v1/history", headers={"Authorization": f"Bearer {TOKEN_A}"})
    assert res.status_code == 200
    assert res.json() == []


def test_deleting_a_row_you_do_not_own_is_404_and_scoped_to_the_caller(monkeypatch):
    monkeypatch.setattr(auth_module, "_lookup_user", _as(USER_A))
    calls = _capture_requests(monkeypatch, deleted_rows=[])  # PostgREST matched no row
    res = _client().delete(f"/api/v1/history/{USER_B}-row",
                           headers={"Authorization": f"Bearer {TOKEN_A}", "X-User-Id": USER_B})
    assert res.status_code == 404
    (call,) = calls
    segments = _query_segments(call["params"])
    assert segments == [f"id=eq.{USER_B}-row", f"user_id=eq.{USER_A}"], "id AND user_id: a foreign row cannot match"


def test_history_page_size_is_forwarded_and_cannot_be_widened_by_the_client(monkeypatch):
    monkeypatch.setattr(auth_module, "_lookup_user", _as(USER_A))
    calls = _capture_requests(monkeypatch, rows=[])
    client = _client()
    client.get("/api/v1/history?limit=100000", headers={"Authorization": f"Bearer {TOKEN_A}"})
    assert "limit=50" in calls[0]["params"], "a client limit parameter must not resize the query"
    calls.clear()
    history_store.list_for_user(SUPABASE_URL, SERVICE_KEY, user_id=USER_A, limit=7)
    assert "limit=7" in calls[0]["params"]
    with pytest.raises((TypeError, ValueError)):
        history_store.list_for_user(SUPABASE_URL, SERVICE_KEY, user_id=USER_A, limit=f"5&user_id=eq.{USER_B}")


def test_unconfigured_server_answers_503_for_a_token_and_401_without_one(monkeypatch):
    def must_not_look_up(*args, **kwargs):
        pytest.fail("an unconfigured server must not call the auth upstream")

    monkeypatch.setattr(auth_module, "_lookup_user", must_not_look_up)
    client = _client(configured=False)
    for method, path in HISTORY_ENDPOINTS:
        authed = getattr(client, method)(path, headers={"Authorization": f"Bearer {TOKEN_A}"})
        assert authed.status_code == 503, f"{method.upper()} {path} with a token must report missing configuration"
        assert any(tok in authed.json()["detail"].lower() for tok in ("configur", "configured"))
        anon = getattr(client, method)(path)
        assert anon.status_code == 401, f"{method.upper()} {path} without a token must stay 401"


# ---- 6. response shape stability -------------------------------------------------------------------------------------


def test_history_rows_pass_through_unfiltered_and_unreordered(monkeypatch):
    monkeypatch.setattr(auth_module, "_lookup_user", _as(USER_A))
    # Deliberately NOT in created_at-desc order: any re-sorting or re-shaping in the endpoint flips or drops this.
    rows = [
        {"id": "row-older", "user_id": USER_A, "input_type": "text", "risk_score": 5, "risk_label": "low_risk",
         "created_at": "2026-10-01T00:00:00Z", "result_json": None},
        {"id": "row-newer", "user_id": USER_A, "input_type": "url", "risk_score": 71, "risk_label": "high_risk",
         "created_at": "2026-10-02T00:00:00Z",
         "result_json": {"analysis_id": "abc", "modules": {"ml": {"status": "completed"}}},
         "server_side_field_a_reviewer_would_not_expect": "kept"},
    ]
    monkeypatch.setattr(history_store, "list_for_user", lambda *a, **k: rows)
    res = _client().get("/api/v1/history", headers={"Authorization": f"Bearer {TOKEN_A}"})
    assert res.status_code == 200
    assert res.json() == rows, "rows must pass through verbatim: no dropped keys, no re-sorting, no re-shaping"
    assert [row["id"] for row in res.json()] == ["row-older", "row-newer"]
    assert res.json()[1]["result_json"]["modules"]["ml"]["status"] == "completed"
    assert "result_json" in res.json()[0]


# ---- trailing-slash guard: a client that builds `/history/{id}` with an empty id must never wipe the account ----


def test_row_shaped_delete_with_empty_id_must_not_erase_all_history(monkeypatch):
    # FIXED: redirect_slashes=False in services/api/main.py create_app means `DELETE /api/v1/history/`
    # no longer 307-redirects to the bulk-delete route. It is an unmatched route (404) and no store
    # call is made, so an empty row id can never wipe the account.
    monkeypatch.setattr(auth_module, "_lookup_user", _as(USER_A))
    calls = _record_store(monkeypatch)
    res = _client().request("DELETE", "/api/v1/history/", headers={"Authorization": f"Bearer {TOKEN_A}"})
    assert [name for name, _, _ in calls] == [], (
        f"a row-shaped delete must never reach a bulk delete (got {res.status_code} {res.text})")
    assert res.status_code == 404, f"expected 404 for unmatched trailing-slash path, got {res.status_code} {res.text}"
