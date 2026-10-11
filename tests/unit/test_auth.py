import urllib.error

import pytest
from fastapi import HTTPException

from services.api import auth


class _FakeState:
    def __init__(self, configured: bool):
        from services.api.settings import Settings
        self.settings = Settings(
            supabase_url="https://x.supabase.co" if configured else "",
            supabase_service_role_key="service-key" if configured else "",
        )


class _FakeApp:
    def __init__(self, configured: bool):
        self.state = _FakeState(configured)


class _FakeRequest:
    def __init__(self, configured: bool):
        self.app = _FakeApp(configured)


def _request(configured: bool = True):
    return _FakeRequest(configured)


def test_no_header_is_anonymous():
    assert auth.get_current_user(_request(), authorization=None) is None


def test_non_bearer_header_is_anonymous():
    assert auth.get_current_user(_request(), authorization="Basic abc123") is None


def test_valid_token_resolves_user(monkeypatch):
    monkeypatch.setattr(auth, "_lookup_user", lambda *a, **k: auth.CurrentUser(id="u1", email="a@b.com"))
    user = auth.get_current_user(_request(configured=True), authorization="Bearer good-token")
    assert user == auth.CurrentUser(id="u1", email="a@b.com")


def test_invalid_token_raises_401(monkeypatch):
    monkeypatch.setattr(auth, "_lookup_user", lambda *a, **k: None)
    with pytest.raises(HTTPException) as exc:
        auth.get_current_user(_request(configured=True), authorization="Bearer bad-token")
    assert exc.value.status_code == 401


def test_unconfigured_server_raises_503_when_token_present():
    with pytest.raises(HTTPException) as exc:
        auth.get_current_user(_request(configured=False), authorization="Bearer some-token")
    assert exc.value.status_code == 503


def test_lookup_user_returns_none_on_401(monkeypatch):
    def fake_urlopen(req, timeout):
        raise urllib.error.HTTPError(req.full_url, 401, "unauthorized", {}, None)
    monkeypatch.setattr(auth.urllib.request, "urlopen", fake_urlopen)
    assert auth._lookup_user("https://x.supabase.co", "service-key", "bad-token") is None


def test_lookup_user_parses_successful_response(monkeypatch):
    import io
    import json

    class FakeResponse(io.BytesIO):
        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

    def fake_urlopen(req, timeout):
        return FakeResponse(json.dumps({"id": "u1", "email": "a@b.com"}).encode())

    monkeypatch.setattr(auth.urllib.request, "urlopen", fake_urlopen)
    user = auth._lookup_user("https://x.supabase.co", "service-key", "good-token")
    assert user == auth.CurrentUser(id="u1", email="a@b.com")
