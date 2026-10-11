from services.history import store


def test_summarize_truncates_and_collapses_whitespace():
    raw = "line one\n\n   line two " + "x" * 200
    out = store.summarize(raw)
    assert len(out) <= 121  # 120 chars + the ellipsis character
    assert "\n" not in out
    assert out.endswith("…")


def test_summarize_short_text_is_unchanged():
    assert store.summarize("hello world") == "hello world"


def test_save_posts_expected_body(monkeypatch):
    captured = {}

    def fake_request(method, url, key, **kwargs):
        captured["method"] = method
        captured["url"] = url
        captured["body"] = kwargs.get("body")
        return [kwargs.get("body")]

    monkeypatch.setattr(store, "_request", fake_request)
    store.save("https://x.supabase.co", "key", user_id="u1", input_type="text",
               input_summary="hi", risk_score=10, risk_label="low_risk", result_json={"a": 1})
    assert captured["method"] == "POST"
    assert captured["url"] == "https://x.supabase.co/rest/v1/scan_history"
    assert captured["body"]["user_id"] == "u1"
    assert captured["body"]["result_json"] == {"a": 1}


def test_save_swallows_errors(monkeypatch):
    def fake_request(*a, **k):
        raise RuntimeError("network down")

    monkeypatch.setattr(store, "_request", fake_request)
    store.save("https://x.supabase.co", "key", user_id="u1", input_type="text",
               input_summary="hi", risk_score=10, risk_label="low_risk", result_json={})
    # must not raise


def test_list_for_user_filters_by_user_and_orders(monkeypatch):
    captured = {}

    def fake_request(method, url, key, **kwargs):
        captured["params"] = kwargs.get("params")
        return [{"id": "1"}]

    monkeypatch.setattr(store, "_request", fake_request)
    rows = store.list_for_user("https://x.supabase.co", "key", user_id="u1")
    assert rows == [{"id": "1"}]
    assert "user_id=eq.u1" in captured["params"]
    assert "order=created_at.desc" in captured["params"]


def test_list_for_user_returns_empty_on_unexpected_shape(monkeypatch):
    monkeypatch.setattr(store, "_request", lambda *a, **k: {"unexpected": "shape"})
    assert store.list_for_user("https://x.supabase.co", "key", user_id="u1") == []


def test_delete_for_user_scopes_query_to_both_id_and_user(monkeypatch):
    captured = {}

    def fake_request(method, url, key, **kwargs):
        captured["method"] = method
        captured["params"] = kwargs.get("params")
        return [{"id": "row1"}]

    monkeypatch.setattr(store, "_request", fake_request)
    deleted = store.delete_for_user("https://x.supabase.co", "key", user_id="u1", row_id="row1")
    assert deleted is True
    assert captured["method"] == "DELETE"
    assert "id=eq.row1" in captured["params"]
    assert "user_id=eq.u1" in captured["params"]


def test_delete_for_user_returns_false_when_nothing_matched(monkeypatch):
    monkeypatch.setattr(store, "_request", lambda *a, **k: [])
    assert store.delete_for_user("https://x.supabase.co", "key", user_id="u1", row_id="missing") is False
