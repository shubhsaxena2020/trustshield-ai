import io
import json
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


class _FakeResponse(io.BytesIO):
    """Minimal urlopen response: context manager that records the read size it was given."""

    def __init__(self, body: bytes):
        super().__init__(body)
        self.read_sizes: list[int] = []

    def read(self, size: int = -1) -> bytes:
        self.read_sizes.append(size)
        return super().read(size)

    def __enter__(self):
        return self

    def __exit__(self, *exc_info):
        return False


class _ClosableHTTPError(urllib.error.HTTPError):
    def __init__(self, code: int):
        super().__init__("https://x.supabase.co/auth/v1/user", code, "upstream error", {}, None)
        self.closed = False

    def close(self) -> None:
        self.closed = True
        super().close()


def _serve(monkeypatch, response: _FakeResponse) -> _FakeResponse:
    monkeypatch.setattr(auth.urllib.request, "urlopen", lambda req, timeout: response)
    return response


def _fail_with(monkeypatch, exc: BaseException) -> None:
    def _raise(req, timeout):
        raise exc

    monkeypatch.setattr(auth.urllib.request, "urlopen", _raise)


def _http_error(monkeypatch, code: int) -> _ClosableHTTPError:
    exc = _ClosableHTTPError(code)
    _fail_with(monkeypatch, exc)
    return exc


def _lookup_payload(monkeypatch, payload: bytes):
    _serve(monkeypatch, _FakeResponse(payload))
    return auth._lookup_user("https://x.supabase.co", "service-key", "good-token")


def _resolve(monkeypatch, authorization: str = "Bearer good-token"):
    return auth.get_current_user(_request(configured=True), authorization=authorization)


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


def test_empty_bearer_token_is_anonymous():
    assert auth.get_current_user(_request(), authorization="Bearer ") is None
    assert auth.get_current_user(_request(), authorization="Bearer    ") is None


def test_bearer_scheme_is_case_insensitive_and_token_is_stripped(monkeypatch):
    seen = {}

    def fake_lookup(url, key, token):
        seen["token"] = token
        return auth.CurrentUser(id="u1", email="a@b.com")

    monkeypatch.setattr(auth, "_lookup_user", fake_lookup)
    user = auth.get_current_user(_request(configured=True), authorization="bEaReR padded-token ")
    assert user == auth.CurrentUser(id="u1", email="a@b.com")
    assert seen["token"] == "padded-token"


def test_token_with_internal_whitespace_is_401_without_network(monkeypatch):
    called = {"count": 0}

    def fake_lookup(*args, **kwargs):
        called["count"] += 1
        return None

    monkeypatch.setattr(auth, "_lookup_user", fake_lookup)
    with pytest.raises(HTTPException) as exc:
        auth.get_current_user(_request(configured=True), authorization="Bearer two words")
    assert exc.value.status_code == 401
    assert called["count"] == 0


def test_unconfigured_server_without_token_is_anonymous():
    assert auth.get_current_user(_request(configured=False), authorization=None) is None
    assert auth.get_current_user(_request(configured=False), authorization="Bearer ") is None


def test_lookup_user_sends_service_key_and_bearer_token(monkeypatch):
    seen = {}

    def fake_urlopen(req, timeout):
        seen["url"] = req.full_url
        seen["apikey"] = req.get_header("Apikey")
        seen["authorization"] = req.get_header("Authorization")
        seen["timeout"] = timeout
        return _FakeResponse(b'{"id": "u1", "email": "a@b.com"}')

    monkeypatch.setattr(auth.urllib.request, "urlopen", fake_urlopen)
    auth._lookup_user("https://x.supabase.co", "service-key", "good-token")
    assert seen == {
        "url": "https://x.supabase.co/auth/v1/user",
        "apikey": "service-key",
        "authorization": "Bearer good-token",
        "timeout": auth._TIMEOUT_SECONDS,
    }


def test_lookup_user_returns_none_on_403(monkeypatch):
    _http_error(monkeypatch, 403)
    assert auth._lookup_user("https://x.supabase.co", "service-key", "bad-token") is None


def test_lookup_user_closes_rejected_token_response(monkeypatch):
    exc = _http_error(monkeypatch, 401)
    assert auth._lookup_user("https://x.supabase.co", "service-key", "bad-token") is None
    assert exc.closed is True


def test_lookup_user_raises_on_5xx(monkeypatch):
    exc = _http_error(monkeypatch, 503)
    with pytest.raises(urllib.error.HTTPError):
        auth._lookup_user("https://x.supabase.co", "service-key", "token")
    assert exc.closed is True


def test_lookup_user_reads_a_bounded_body(monkeypatch):
    response = _serve(monkeypatch, _FakeResponse(b"x" * (auth._MAX_RESPONSE_BYTES + 1)))
    with pytest.raises(auth._UpstreamError):
        auth._lookup_user("https://x.supabase.co", "service-key", "token")
    assert response.read_sizes == [auth._MAX_RESPONSE_BYTES + 1]


def test_lookup_user_raises_on_non_json_body(monkeypatch):
    with pytest.raises(auth._UpstreamError):
        _lookup_payload(monkeypatch, b"<html>not json</html>")


def test_lookup_user_raises_on_unusable_success_body(monkeypatch):
    with pytest.raises(auth._UpstreamError):
        _lookup_payload(monkeypatch, b'{"email": "a@b.com"}')
    with pytest.raises(auth._UpstreamError):
        _lookup_payload(monkeypatch, b"[]")
    with pytest.raises(auth._UpstreamError):
        _lookup_payload(monkeypatch, b'{"id": "", "email": "a@b.com"}')


def test_lookup_user_missing_or_null_email_becomes_empty(monkeypatch):
    assert _lookup_payload(monkeypatch, b'{"id": "u1"}').email == ""
    assert _lookup_payload(monkeypatch, b'{"id": "u1", "email": null}').email == ""


def test_upstream_401_is_401_with_expired_message(monkeypatch):
    _http_error(monkeypatch, 401)
    with pytest.raises(HTTPException) as exc:
        _resolve(monkeypatch)
    assert exc.value.status_code == 401
    assert "expired" in exc.value.detail.lower()


def test_upstream_403_is_401(monkeypatch):
    _http_error(monkeypatch, 403)
    with pytest.raises(HTTPException) as exc:
        _resolve(monkeypatch)
    assert exc.value.status_code == 401


def test_upstream_500_is_503_with_generic_message(monkeypatch):
    _http_error(monkeypatch, 500)
    with pytest.raises(HTTPException) as exc:
        _resolve(monkeypatch)
    assert exc.value.status_code == 503
    assert "verify" in exc.value.detail.lower()


def test_network_failure_is_503_and_does_not_leak_detail(monkeypatch):
    _fail_with(monkeypatch, urllib.error.URLError("secret upstream host detail"))
    with pytest.raises(HTTPException) as exc:
        _resolve(monkeypatch)
    assert exc.value.status_code == 503
    assert "secret" not in exc.value.detail


def test_timeout_is_503(monkeypatch):
    _fail_with(monkeypatch, TimeoutError("timed out"))
    with pytest.raises(HTTPException) as exc:
        _resolve(monkeypatch)
    assert exc.value.status_code == 503


def test_non_json_upstream_body_is_503(monkeypatch):
    _serve(monkeypatch, _FakeResponse(b"<html>not json</html>"))
    with pytest.raises(HTTPException) as exc:
        _resolve(monkeypatch)
    assert exc.value.status_code == 503
    assert "<html>" not in exc.value.detail


def test_upstream_body_without_id_is_503_not_401(monkeypatch):
    _serve(monkeypatch, _FakeResponse(b'{"email": "a@b.com"}'))
    with pytest.raises(HTTPException) as exc:
        _resolve(monkeypatch)
    assert exc.value.status_code == 503


def test_upstream_list_body_is_503(monkeypatch):
    _serve(monkeypatch, _FakeResponse(b"[]"))
    with pytest.raises(HTTPException) as exc:
        _resolve(monkeypatch)
    assert exc.value.status_code == 503
