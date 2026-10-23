import json
import logging
import urllib.error

import pytest
from fastapi.testclient import TestClient

from services.api.main import create_app
from services.api.settings import Settings
from services.api import auth as auth_module
from services.history import store as history_store


def _client(*, configured=True):
    settings = Settings(supabase_url="https://x.supabase.co" if configured else "",
                         supabase_service_role_key="key" if configured else "")
    app = create_app(settings)
    return TestClient(app)


USER = auth_module.CurrentUser(id="u1", email="a@b.com")
AUTH = {"Authorization": "Bearer good"}

# Every history route, with the store function it is allowed to call.
ENDPOINTS = [
    ("get", "/api/v1/history", "list"),
    ("delete", "/api/v1/history/row1", "delete"),
    ("delete", "/api/v1/history", "delete_all"),
]


def test_history_requires_login():
    client = _client()
    res = client.get("/api/v1/history")
    assert res.status_code == 401


def test_history_lists_rows_for_logged_in_user(monkeypatch):
    monkeypatch.setattr(auth_module, "_lookup_user",
                         lambda *a, **k: auth_module.CurrentUser(id="u1", email="a@b.com"))
    monkeypatch.setattr(history_store, "list_for_user", lambda *a, **k: [{"id": "1"}])
    client = _client()
    res = client.get("/api/v1/history", headers={"Authorization": "Bearer good"})
    assert res.status_code == 200
    assert res.json() == [{"id": "1"}]


def test_delete_row_requires_login():
    client = _client()
    res = client.delete("/api/v1/history/row1")
    assert res.status_code == 401


def test_delete_missing_row_is_404(monkeypatch):
    monkeypatch.setattr(auth_module, "_lookup_user",
                         lambda *a, **k: auth_module.CurrentUser(id="u1", email="a@b.com"))
    monkeypatch.setattr(history_store, "delete_for_user", lambda *a, **k: False)
    client = _client()
    res = client.delete("/api/v1/history/row1", headers={"Authorization": "Bearer good"})
    assert res.status_code == 404


def test_analyze_text_writes_history_when_logged_in(monkeypatch):
    monkeypatch.setattr(auth_module, "_lookup_user",
                         lambda *a, **k: auth_module.CurrentUser(id="u1", email="a@b.com"))
    saved = {}
    monkeypatch.setattr(history_store, "save", lambda *a, **k: saved.update(k))
    client = _client()
    res = client.post("/api/v1/analyze/text", json={"text": "hello there, free gift card now"},
                       headers={"Authorization": "Bearer good"})
    assert res.status_code == 200
    assert saved["user_id"] == "u1"
    assert saved["input_type"] == "text"


def test_analyze_text_writes_nothing_when_anonymous(monkeypatch):
    called = {"count": 0}
    monkeypatch.setattr(history_store, "save", lambda *a, **k: called.__setitem__("count", called["count"] + 1))
    client = _client()
    res = client.post("/api/v1/analyze/text", json={"text": "hello there"})
    assert res.status_code == 200
    assert called["count"] == 0


# ---- helpers for the expanded coverage ----------------------------------------------------------------------------

def _verified(monkeypatch, user=USER):
    """A caller whose access token resolves to `user`; no network is involved."""
    monkeypatch.setattr(auth_module, "_lookup_user", lambda *a, **k: user)
    return user


def _capture_store(monkeypatch, *, deleted=True):
    """Record every store call (args, kwargs) instead of reaching PostgREST."""
    calls = {"list": [], "delete": [], "delete_all": []}

    def list_for_user(*args, **kwargs):
        calls["list"].append((args, kwargs))
        return [{"id": "1"}]

    def delete_for_user(*args, **kwargs):
        calls["delete"].append((args, kwargs))
        return deleted

    def delete_all_for_user(*args, **kwargs):
        calls["delete_all"].append((args, kwargs))
        return None

    monkeypatch.setattr(history_store, "list_for_user", list_for_user)
    monkeypatch.setattr(history_store, "delete_for_user", delete_for_user)
    monkeypatch.setattr(history_store, "delete_all_for_user", delete_all_for_user)
    return calls


def _calls(calls):
    return {name: entries for name, entries in calls.items() if entries}


_UNSTABLE_KEYS = {"analysis_id", "created_at", "timestamp", "id", "latency_ms"}


def _stable(value):
    """Drop per-request ids, clock readings and timings so two analyses of the same input can be compared."""
    if isinstance(value, dict):
        return {key: _stable(item) for key, item in value.items() if key not in _UNSTABLE_KEYS}
    if isinstance(value, list):
        return [_stable(item) for item in value]
    return value


# ---- authentication and configuration ------------------------------------------------------------------------------

@pytest.mark.parametrize("method,path,store_key", ENDPOINTS)
def test_anonymous_request_is_401_and_never_touches_the_store(method, path, store_key, monkeypatch):
    calls = _capture_store(monkeypatch)
    res = getattr(_client(), method)(path)
    assert res.status_code == 401
    assert _calls(calls) == {}
    assert "Log in" in res.json()["detail"]


@pytest.mark.parametrize("method,path,store_key", ENDPOINTS)
def test_invalid_token_is_401_not_silently_anonymous(method, path, store_key, monkeypatch):
    calls = _capture_store(monkeypatch)
    monkeypatch.setattr(auth_module, "_lookup_user", lambda *a, **k: None)  # Supabase says the token is not valid
    res = getattr(_client(), method)(path, headers=AUTH)
    assert res.status_code == 401
    assert res.json()["detail"] == "Your session has expired; please log in again"
    assert _calls(calls) == {}


@pytest.mark.parametrize("method,path,store_key", ENDPOINTS)
def test_unconfigured_server_with_a_token_is_503(method, path, store_key, monkeypatch):
    calls = _capture_store(monkeypatch)
    res = getattr(_client(configured=False), method)(path, headers=AUTH)
    assert res.status_code == 503
    assert res.json()["detail"] == "Accounts are not configured on this server"
    assert _calls(calls) == {}


def test_non_bearer_authorization_counts_as_anonymous(monkeypatch):
    calls = _capture_store(monkeypatch)
    res = _client().get("/api/v1/history", headers={"Authorization": "Basic abc123"})
    assert res.status_code == 401
    assert _calls(calls) == {}


# ---- identity: the user id always comes from the verified token -----------------------------------------------------

def test_list_uses_the_verified_user_and_the_configured_store(monkeypatch):
    calls = _capture_store(monkeypatch)
    _verified(monkeypatch)
    res = _client().get("/api/v1/history", headers=AUTH)
    assert res.status_code == 200
    assert res.json() == [{"id": "1"}]
    assert calls["list"] == [(("https://x.supabase.co", "key"), {"user_id": "u1"})]


def test_delete_scopes_the_row_to_the_verified_user(monkeypatch):
    calls = _capture_store(monkeypatch)
    _verified(monkeypatch)
    res = _client().delete("/api/v1/history/row1", headers=AUTH)
    assert res.status_code == 200
    assert res.json() == {"deleted": "row1"}
    assert calls["delete"] == [(("https://x.supabase.co", "key"), {"user_id": "u1", "row_id": "row1"})]


def test_delete_all_scopes_to_the_verified_user(monkeypatch):
    calls = _capture_store(monkeypatch)
    _verified(monkeypatch)
    res = _client().delete("/api/v1/history", headers=AUTH)
    assert res.status_code == 200
    assert res.json() == {"deleted": "all"}
    assert calls["delete_all"] == [(("https://x.supabase.co", "key"), {"user_id": "u1"})]


def test_identity_follows_the_token_not_a_constant(monkeypatch):
    """Two callers get two identities, so the id is derived from the token rather than hard-coded."""
    calls = _capture_store(monkeypatch)
    users = {"token-a": auth_module.CurrentUser(id="user-a", email="a@b.com"),
             "token-b": auth_module.CurrentUser(id="user-b", email="b@b.com")}
    monkeypatch.setattr(auth_module, "_lookup_user", lambda url, key, token: users.get(token))
    client = _client()
    assert client.get("/api/v1/history", headers={"Authorization": "Bearer token-a"}).status_code == 200
    assert client.get("/api/v1/history", headers={"Authorization": "Bearer token-b"}).status_code == 200
    assert [kwargs["user_id"] for _, kwargs in calls["list"]] == ["user-a", "user-b"]


@pytest.mark.parametrize("method,path,store_key", ENDPOINTS)
def test_caller_controlled_identity_input_is_ignored(method, path, store_key, monkeypatch):
    """Query string, headers and body cannot move the filter away from the verified user."""
    calls = _capture_store(monkeypatch)
    _verified(monkeypatch)
    res = _client().request(
        method.upper(), path,
        headers={**AUTH, "X-User-Id": "attacker", "X-Forwarded-User": "attacker"},
        params={"user_id": "eq.attacker", "id": "eq.attacker"}, json={"user_id": "attacker"},
    )
    assert res.status_code == 200
    (_, kwargs), = calls[store_key]
    assert kwargs["user_id"] == "u1"
    assert "eq.attacker" not in json.dumps(kwargs)


@pytest.mark.parametrize("row_id", ["row1&user_id=eq.someone", "a b", "x" * 300])
def test_crafted_row_id_is_passed_through_verbatim_with_the_callers_own_scope(row_id, monkeypatch):
    """main.py never interpolates row_id into a URL and never widens the filter; escaping is the store's job."""
    calls = _capture_store(monkeypatch)
    _verified(monkeypatch)
    res = _client().delete(f"/api/v1/history/{row_id}", headers=AUTH)
    assert res.status_code == 200
    assert res.json() == {"deleted": row_id}
    (args, kwargs), = calls["delete"]
    assert args == ("https://x.supabase.co", "key")  # no URL was built from row_id
    assert kwargs == {"user_id": "u1", "row_id": row_id}


def test_encoded_slash_in_the_row_id_never_reaches_the_store(monkeypatch):
    """Observed: Starlette decodes %2F before routing, so the path has two segments and no route matches."""
    calls = _capture_store(monkeypatch)
    _verified(monkeypatch)
    res = _client().delete("/api/v1/history/row1%2Fevil", headers=AUTH)
    assert res.status_code == 404
    assert _calls(calls) == {}


# ---- history-store failures are 503, never an unhandled 500 --------------------------------------------------------

def _raise_urlerror(*args, **kwargs):
    raise urllib.error.URLError("connection refused")


def _raise_http_500(*args, **kwargs):
    raise urllib.error.HTTPError("https://x.supabase.co/rest/v1/scan_history?user_id=eq.u1", 500, "boom", {}, None)


def _raise_timeout(*args, **kwargs):
    raise TimeoutError("timed out")


@pytest.mark.parametrize("error", [_raise_urlerror, _raise_http_500, _raise_timeout],
                         ids=["URLError", "HTTPError500", "TimeoutError"])
@pytest.mark.parametrize("method,path,store_key", ENDPOINTS)
def test_store_failure_is_503_with_a_retryable_message(method, path, store_key, error, monkeypatch):
    _verified(monkeypatch)
    monkeypatch.setattr(history_store, store_key + "_for_user", error)
    res = getattr(_client(), method)(path, headers=AUTH)
    assert res.status_code == 503
    assert res.json()["detail"] == "Scan history is temporarily unavailable; please retry shortly"
    assert res.headers["Retry-After"] == "5"


def test_store_failure_is_logged_with_the_error_type_only(monkeypatch, caplog):
    caplog.set_level(logging.WARNING, logger="trustshield.api")
    _verified(monkeypatch)
    monkeypatch.setattr(history_store, "list_for_user", _raise_http_500)
    assert _client().get("/api/v1/history", headers=AUTH).status_code == 503
    events = [record.getMessage() for record in caplog.records if "history_store_unavailable" in record.getMessage()]
    assert len(events) == 1
    assert json.loads(events[0]) == {"event": "history_store_unavailable", "action": "list", "error_type": "HTTPError"}
    for leak in ("https://", "supabase", "eq.", "scan_history", "u1", "Bearer"):
        assert leak not in events[0]


def test_missing_row_stays_404_after_the_failure_handling(monkeypatch):
    calls = _capture_store(monkeypatch, deleted=False)
    _verified(monkeypatch)
    res = _client().delete("/api/v1/history/row1", headers=AUTH)
    assert res.status_code == 404
    assert res.json()["detail"] == "History entry not found"
    assert len(calls["delete"]) == 1


def test_delete_row_and_delete_all_return_the_same_body_shape(monkeypatch):
    """Decision: both answers are one `deleted` string key ("row1" / "all"), so the pair is already consistent."""
    _capture_store(monkeypatch)
    _verified(monkeypatch)
    client = _client()
    row = client.delete("/api/v1/history/row1", headers=AUTH)
    everything = client.delete("/api/v1/history", headers=AUTH)
    assert row.status_code == everything.status_code == 200
    assert row.json() == {"deleted": "row1"}
    assert everything.json() == {"deleted": "all"}
    assert list(row.json()) == list(everything.json()) == ["deleted"]
    assert all(isinstance(response.json()["deleted"], str) for response in (row, everything))


# ---- recording an analysis is best effort and never changes the response --------------------------------------------

def test_history_recording_never_changes_the_analyze_response(monkeypatch):
    """Anonymous, recorded, and failed-to-record analyses of the same text return the same body."""
    text = "Your parcel is held, pay the fee at http://parcel.top"
    monkeypatch.setattr(auth_module, "_lookup_user", lambda *a, **k: USER)
    monkeypatch.setattr(history_store, "save", lambda *a, **k: None)
    recorded = _client().post("/api/v1/analyze/text", json={"text": text}, headers=AUTH)

    monkeypatch.setattr(history_store, "save", _raise_urlerror)
    failed = _client().post("/api/v1/analyze/text", json={"text": text}, headers=AUTH)

    monkeypatch.setattr(history_store, "save", lambda *a, **k: pytest.fail("anonymous analysis must not write history"))
    anonymous = _client().post("/api/v1/analyze/text", json={"text": text})

    for response in (recorded, failed, anonymous):
        assert response.status_code == 200
        assert response.headers["X-Analysis-ID"] == response.json()["analysis_id"]
    assert _stable(recorded.json()) == _stable(failed.json()) == _stable(anonymous.json())


def test_history_write_failure_is_logged_without_content(monkeypatch, caplog):
    caplog.set_level(logging.WARNING, logger="trustshield.api")
    _verified(monkeypatch)
    monkeypatch.setattr(history_store, "save", _raise_urlerror)
    text = "your parcel is held, pay the redelivery fee"
    assert _client().post("/api/v1/analyze/text", json={"text": text}, headers=AUTH).status_code == 200
    events = [record.getMessage() for record in caplog.records if "history_write_failed" in record.getMessage()]
    assert len(events) == 1
    assert json.loads(events[0]) == {"event": "history_write_failed", "error_type": "URLError"}
    assert "parcel" not in events[0] and "https://" not in events[0] and "supabase" not in events[0]


def test_anonymous_analyze_with_an_unconfigured_server_never_writes(monkeypatch):
    monkeypatch.setattr(history_store, "save", lambda *a, **k: pytest.fail("anonymous analysis must not write history"))
    res = _client(configured=False).post("/api/v1/analyze/text", json={"text": "hello there"})
    assert res.status_code == 200

