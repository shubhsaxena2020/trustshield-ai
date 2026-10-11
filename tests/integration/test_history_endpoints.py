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
