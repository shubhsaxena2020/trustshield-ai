"""Isolation and failure-mode tests for ``services/history/store.py``.

Every assertion is made at the urllib boundary: ``urlopen`` is replaced, so these tests see the
exact request the module builds (method, full URL, headers, body, timeout) instead of trusting
an internal helper. That distinction matters because this client authenticates with the
service-role key, which bypasses RLS -- per ``docs/AUTH_AND_HISTORY_ARCHITECTURE.md`` the
explicit, AND-ed ``user_id`` filter in these URLs *is* the isolation boundary, so it is the
URLs that have to be proven, not the Python that builds them.
"""
from __future__ import annotations

import io
import json
import logging
import urllib.error
import urllib.parse

import pytest

from services.history import store

BASE = "https://x.supabase.co"
TABLE_URL = f"{BASE}/rest/v1/scan_history"
KEY = "service-role-key-should-never-be-logged"
LOGGER = "trustshield.history"
SUMMARY_MARKER = "SUMMARY_MARKER_9c1"


class _Response:
    """Minimal stand-in for ``http.client.HTTPResponse`` (the module uses it as a context)."""

    def __init__(self, payload: bytes | None):
        self._payload = payload

    def read(self) -> bytes | None:
        return self._payload

    def __enter__(self) -> _Response:
        return self

    def __exit__(self, *exc_info) -> bool:
        return False


class _Call:
    """One captured ``urllib.request.Request`` plus the timeout it was opened with."""

    def __init__(self, request, timeout):
        self.method = request.method
        self.url = request.full_url
        self.headers = dict(request.headers)
        self.data = request.data
        self.timeout = timeout

    def header(self, name: str) -> str | None:
        for key, value in self.headers.items():
            if key.lower() == name.lower():
                return value
        return None

    def param_map(self) -> dict[str, list[str]]:
        query = urllib.parse.urlsplit(self.url).query
        out: dict[str, list[str]] = {}
        for name, value in urllib.parse.parse_qsl(query, keep_blank_values=True):
            out.setdefault(name, []).append(value)
        return out


class _FakeHTTP:
    """Replaces ``urllib.request.urlopen``; no socket is ever opened."""

    def __init__(self):
        self.calls: list[_Call] = []
        self._responder = lambda call: b"[]"

    def reply(self, payload: bytes | None) -> None:
        self._responder = lambda call: payload

    def fail(self, error: BaseException) -> None:
        def boom(call):
            raise error

        self._responder = boom

    def __call__(self, request, timeout=None):
        call = _Call(request, timeout)
        self.calls.append(call)
        return _Response(self._responder(call))


@pytest.fixture
def http(monkeypatch) -> _FakeHTTP:
    fake = _FakeHTTP()
    # store did `import urllib.request`, so this patches the module attribute it resolves at
    # call time; monkeypatch restores it afterwards.
    monkeypatch.setattr(store.urllib.request, "urlopen", fake)
    return fake


def _save(**overrides):
    kwargs = dict(user_id="user-a", input_type="text", input_summary=SUMMARY_MARKER,
                  risk_score=42, risk_label="high_risk", result_json={"verdict": "scam"})
    kwargs.update(overrides)
    return store.save(BASE, KEY, **kwargs)


def _warning_messages(caplog) -> list[str]:
    return [r.getMessage() for r in caplog.records if r.name == LOGGER]


# ---- summarize (unchanged behaviour) -------------------------------------------------------

def test_summarize_truncates_and_collapses_whitespace():
    raw = "line one\n\n   line two " + "x" * 200
    out = store.summarize(raw)
    assert len(out) <= 121  # 120 chars + the ellipsis character
    assert "\n" not in out
    assert out.endswith("…")


def test_summarize_short_text_is_unchanged():
    assert store.summarize("hello world") == "hello world"


# ---- every SELECT carries exactly one user_id filter ---------------------------------------

def test_list_for_user_sends_exactly_one_user_id_filter(http):
    http.reply(b'[{"id": "row-1"}]')

    rows = store.list_for_user(BASE, KEY, user_id="user-a")

    assert rows == [{"id": "row-1"}]
    call = http.calls[0]
    assert call.method == "GET"
    assert call.url.startswith(TABLE_URL + "?")
    assert call.data is None  # a read must not smuggle a body
    assert call.param_map() == {
        "user_id": ["eq.user-a"],
        "order": ["created_at.desc"],
        "limit": ["50"],
    }


def test_list_for_user_filter_tracks_the_argument(http):
    store.list_for_user(BASE, KEY, user_id="user-a")
    store.list_for_user(BASE, KEY, user_id="user-b")

    assert http.calls[0].param_map()["user_id"] == ["eq.user-a"]
    assert http.calls[1].param_map()["user_id"] == ["eq.user-b"]
    assert "user-b" not in http.calls[0].url


def test_list_for_user_query_is_built_only_from_the_argument(http):
    store.list_for_user(BASE, KEY, user_id="user-a")

    # Literal proof of shape: one user filter, then the fixed ordering/paging tail.
    assert urllib.parse.urlsplit(http.calls[0].url).query == (
        "user_id=eq.user-a&order=created_at.desc&limit=50"
    )


# ---- every DELETE is AND-ed with user_id ---------------------------------------------------

def test_delete_for_user_ands_row_id_with_user_id(http):
    http.reply(b'[{"id": "row-1"}]')

    assert store.delete_for_user(BASE, KEY, user_id="user-a", row_id="row-1") is True

    call = http.calls[0]
    assert call.method == "DELETE"
    assert call.data is None
    assert call.param_map() == {"id": ["eq.row-1"], "user_id": ["eq.user-a"]}


def test_delete_for_user_never_takes_the_user_filter_from_the_row_id(http):
    http.reply(b"[]")

    store.delete_for_user(BASE, KEY, user_id="user-a", row_id="user-b")

    params = http.calls[0].param_map()
    assert params["user_id"] == ["eq.user-a"]
    assert params["id"] == ["eq.user-b"]


def test_delete_for_user_user_filter_survives_a_crafted_row_id(http):
    crafted = "x&user_id=eq.other"
    http.reply(b"[]")

    store.delete_for_user(BASE, KEY, user_id="user-a", row_id=crafted)

    params = http.calls[0].param_map()
    assert params["user_id"] == ["eq.user-a"]  # exactly one, not eq.other
    assert list(params) == ["id", "user_id"]  # no injected parameter names
    assert urllib.parse.unquote(params["id"][0]) == "eq." + crafted


def test_delete_for_user_returns_false_when_the_api_matches_no_rows(http):
    for payload in (b"[]", b"null", b""):
        http.reply(payload)
        assert store.delete_for_user(BASE, KEY, user_id="user-a", row_id="row-1") is False


def test_delete_all_for_user_sends_only_the_user_filter(http):
    http.reply(b'[{"id": "row-1"}]')

    assert store.delete_all_for_user(BASE, KEY, user_id="user-a") is None

    call = http.calls[0]
    assert call.method == "DELETE"
    assert call.url.startswith(TABLE_URL + "?")
    assert call.data is None
    assert call.param_map() == {"user_id": ["eq.user-a"]}


def test_delete_all_for_user_needs_no_response_body(http):
    for payload in (b"", b"null", b"[]"):
        http.reply(payload)
        assert store.delete_all_for_user(BASE, KEY, user_id="user-a") is None


def test_delete_all_for_user_user_filter_survives_a_crafted_user_id(http):
    crafted = "user-a&id=eq.other&or=(1.eq.1)"
    http.reply(b"[]")

    store.delete_all_for_user(BASE, KEY, user_id=crafted)

    params = http.calls[0].param_map()
    assert list(params) == ["user_id"]
    assert urllib.parse.unquote(params["user_id"][0]) == "eq." + crafted


# ---- _eq encoding (unit level, same values as the crafted-id tests above) -------------------

CRAFTED_IDS = [
    "user-a",
    "x&user_id=eq.other",
    "1)or(1",
    "a%b",
    "id=eq.",
    "üñï&x=1",
    "a b+c/d?e#f",
    "'; drop table scan_history; --",
    "100%&limit=99999",
]


@pytest.mark.parametrize("value", CRAFTED_IDS)
def test_eq_percent_encodes_so_the_value_stays_one_parameter(value):
    encoded = store._eq(value)

    assert encoded.startswith("eq.")
    assert "&" not in encoded
    assert "=" not in encoded[3:]
    assert not {" ", "#", "?"} & set(encoded)

    query = urllib.parse.urlsplit("/rest/v1/scan_history?user_id=" + encoded).query
    pairs = urllib.parse.parse_qsl(query, keep_blank_values=True)
    assert len(pairs) == 1
    assert pairs[0][0] == "user_id"
    assert urllib.parse.unquote(pairs[0][1]) == "eq." + value


# ---- response shapes -----------------------------------------------------------------------

@pytest.mark.parametrize("payload", [b'{"unexpected": "shape"}', b"null", b'"rows"', b"", b"17"])
def test_list_for_user_returns_empty_list_for_non_list_payloads(http, payload):
    http.reply(payload)

    assert store.list_for_user(BASE, KEY, user_id="user-a") == []
    assert len(http.calls) == 1  # shape handling never re-queries


def test_list_for_user_returns_rows_for_a_list_payload(http):
    http.reply(b'[{"id": "row-1"}, {"id": "row-2"}]')

    assert store.list_for_user(BASE, KEY, user_id="user-a") == [{"id": "row-1"}, {"id": "row-2"}]


# ---- limit ---------------------------------------------------------------------------------

def test_list_for_user_passes_limit_through(http):
    store.list_for_user(BASE, KEY, user_id="user-a", limit=7)
    store.list_for_user(BASE, KEY, user_id="user-a")

    assert [c.param_map()["limit"] for c in http.calls] == [["7"], ["50"]]


def test_non_integer_limit_cannot_carry_query_structure(http):
    with pytest.raises(ValueError):
        store.list_for_user(BASE, KEY, user_id="user-a", limit="5&user_id=eq.other")

    assert http.calls == []  # int() coercion rejects it before any request


def test_limit_is_passed_through_unclamped_today(http):
    """Decision, not an oversight.

    ``limit`` is never taken from request input -- the only caller (``services/api/main.py``)
    uses the default -- so an out-of-range value is unreachable over HTTP and is forwarded
    verbatim rather than silently rewritten. PostgREST rejects ``limit=-5`` with a 400, so if a
    caller-facing limit is ever added, clamp it here (and make this test assert the clamp).
    """
    store.list_for_user(BASE, KEY, user_id="user-a", limit=-5)
    store.list_for_user(BASE, KEY, user_id="user-a", limit=10**9)

    assert [c.param_map()["limit"] for c in http.calls] == [["-5"], ["1000000000"]]


# ---- fail closed when the verified identity is missing -------------------------------------

QUERY_OPERATIONS = {
    "list": lambda user_id: store.list_for_user(BASE, KEY, user_id=user_id),
    "delete_one": lambda user_id: store.delete_for_user(BASE, KEY, user_id=user_id, row_id="row-1"),
    "delete_all": lambda user_id: store.delete_all_for_user(BASE, KEY, user_id=user_id),
}


@pytest.mark.parametrize("bad_id", [None, "", "   ", "\t\n", b"user-a", 0], ids=repr)
@pytest.mark.parametrize("operation", list(QUERY_OPERATIONS))
def test_every_query_refuses_a_blank_user_id(http, operation, bad_id):
    with pytest.raises(ValueError, match="user_id"):
        QUERY_OPERATIONS[operation](bad_id)

    assert http.calls == []  # no unfiltered, empty, or TypeError-driven request is ever sent


def test_user_id_is_used_verbatim_when_present(http):
    store.list_for_user(BASE, KEY, user_id="User-A-01")

    assert http.calls[0].param_map()["user_id"] == ["eq.User-A-01"]


# ---- save() is best effort and logs one redacted warning -----------------------------------

def test_save_posts_the_row_scoped_to_the_given_user(http):
    assert _save() is None

    call = http.calls[0]
    assert call.method == "POST"
    assert call.url == TABLE_URL  # the write's scope is the body's user_id, not a filter
    assert json.loads(call.data) == {
        "user_id": "user-a",
        "input_type": "text",
        "input_summary": SUMMARY_MARKER,
        "risk_score": 42,
        "risk_label": "high_risk",
        "result_json": {"verdict": "scam"},
    }
    assert call.header("apikey") == KEY
    assert call.header("prefer") == "return=representation"


def _transport_failures():
    return {
        "http_400": urllib.error.HTTPError(
            f"{TABLE_URL}?user_id=eq.user-a", 400, "Bad Request", {},
            io.BytesIO(b'{"message":"row-secret-marker for user-a"}'),
        ),
        "url_error": urllib.error.URLError("connection refused"),
        "timeout": TimeoutError("timed out"),
    }


@pytest.mark.parametrize("error", list(_transport_failures().values()), ids=list(_transport_failures()))
def test_save_never_raises_and_logs_one_redacted_warning(http, caplog, error):
    caplog.set_level(logging.DEBUG, logger=LOGGER)
    http.fail(error)

    assert _save() is None  # a history failure must never reach the analysis response

    messages = _warning_messages(caplog)
    assert len(messages) == 1
    assert caplog.records[-1].levelno == logging.WARNING
    # Exact payload: an error *type* and nothing else -- no URL, key, body or row content.
    assert json.loads(messages[0]) == {
        "event": "history_write_failed",
        "error_type": type(error).__name__,
    }
    for secret in (KEY, "supabase.co", SUMMARY_MARKER, "user-a", "row-secret-marker"):
        assert secret not in messages[0]
    assert len(http.calls) == 1  # one attempt, no retry


def test_save_never_raises_when_the_response_body_is_not_json(http, caplog):
    caplog.set_level(logging.DEBUG, logger=LOGGER)
    http.reply(b"<html>gateway error</html>")

    assert _save() is None

    messages = _warning_messages(caplog)
    assert len(messages) == 1
    assert json.loads(messages[0]) == {
        "event": "history_write_failed",
        "error_type": "JSONDecodeError",
    }


def test_save_never_raises_when_the_row_cannot_be_serialised(http, caplog):
    caplog.set_level(logging.DEBUG, logger=LOGGER)

    assert _save(result_json={"bad": {1, 2}}) is None

    assert http.calls == []  # failed while encoding, before any request
    messages = _warning_messages(caplog)
    assert len(messages) == 1
    assert json.loads(messages[0])["error_type"] == "TypeError"


def test_save_logs_nothing_when_the_write_succeeds(http, caplog):
    caplog.set_level(logging.DEBUG, logger=LOGGER)
    http.reply(b"")

    assert _save() is None

    assert _warning_messages(caplog) == []


# ---- timeout and no retry ------------------------------------------------------------------

def test_every_request_uses_the_five_second_timeout(http):
    store.list_for_user(BASE, KEY, user_id="user-a")
    store.delete_for_user(BASE, KEY, user_id="user-a", row_id="row-1")
    store.delete_all_for_user(BASE, KEY, user_id="user-a")
    _save()

    assert [c.method for c in http.calls] == ["GET", "DELETE", "DELETE", "POST"]
    assert [c.timeout for c in http.calls] == [5, 5, 5, 5]


def test_a_failing_read_is_not_retried(http):
    http.fail(urllib.error.URLError("dns failure"))

    with pytest.raises(urllib.error.URLError):
        store.list_for_user(BASE, KEY, user_id="user-a")

    assert len(http.calls) == 1


def test_a_failing_delete_is_not_retried(http):
    http.fail(TimeoutError("timed out"))

    with pytest.raises(TimeoutError):
        store.delete_for_user(BASE, KEY, user_id="user-a", row_id="row-1")

    assert len(http.calls) == 1


# ---- credentials ---------------------------------------------------------------------------

def test_reads_authenticate_with_the_key_and_never_put_it_in_the_url(http):
    store.list_for_user(BASE, KEY, user_id="user-a")

    call = http.calls[0]
    assert call.header("apikey") == KEY
    assert call.header("authorization") == f"Bearer {KEY}"
    assert KEY not in call.url
    assert call.header("prefer") is None  # a GET must not ask for a representation


def test_deletes_ask_for_a_representation_to_report_whether_a_row_matched(http):
    http.reply(b'[{"id": "row-1"}]')

    store.delete_for_user(BASE, KEY, user_id="user-a", row_id="row-1")
    store.delete_all_for_user(BASE, KEY, user_id="user-a")

    assert [c.header("prefer") for c in http.calls] == ["return=representation"] * 2
