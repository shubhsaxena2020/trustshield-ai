import asyncio
import time

from fastapi.testclient import TestClient

from services.api import main as api


client = TestClient(api.app)


def _telemetry():
    return {"initial_url": "https://example.com/", "final_url": "https://example.com/", "title": "Welcome", "visible_text": "Hello", "forms": [], "requests": []}


def test_flag_off_keeps_static_only_path(monkeypatch):
    monkeypatch.delenv("ENABLE_WEB_SANDBOX", raising=False)
    body = client.post("/api/v1/analyze/url", json={"url": "https://example.com/"}).json()
    assert body["status"] == "completed"
    assert body["modules"]["web_sandbox"]["status"] == "not_run"


def test_enabled_url_returns_202_then_polls_to_completed(monkeypatch):
    monkeypatch.setenv("ENABLE_WEB_SANDBOX", "true")
    api._SANDBOX_BUCKETS.clear()

    async def fake_run(url, **kwargs):
        return {"status": "completed", "telemetry": _telemetry()}

    monkeypatch.setattr(api, "run_sandbox", fake_run)
    first = client.post("/api/v1/analyze/url", json={"url": "https://example.com/", "sandbox": True})
    assert first.status_code == 202
    analysis_id = first.json()["analysis_id"]
    for _ in range(20):
        current = client.get(f"/api/v1/analysis/{analysis_id}").json()
        if current["status"] == "completed":
            break
        time.sleep(0.02)
    assert current["status"] == "completed"
    assert current["modules"]["web_sandbox"]["status"] == "completed"


def test_worker_failure_degrades_to_partial(monkeypatch):
    monkeypatch.setenv("ENABLE_WEB_SANDBOX", "true")
    api._SANDBOX_BUCKETS.clear()

    async def failed(url, **kwargs):
        return {"status": "failed", "detail": "synthetic worker failure"}

    monkeypatch.setattr(api, "run_sandbox", failed)
    first = client.post("/api/v1/analyze/url", json={"url": "https://example.com/", "sandbox": True})
    analysis_id = first.json()["analysis_id"]
    for _ in range(20):
        current = client.get(f"/api/v1/analysis/{analysis_id}").json()
        if current["status"] == "partial":
            break
        time.sleep(0.02)
    assert current["status"] == "partial"
    assert current["modules"]["web_sandbox"]["status"] == "failed"
    assert "confidence" in current["risk"]


def test_sandbox_rate_limit_is_structured(monkeypatch):
    monkeypatch.setenv("ENABLE_WEB_SANDBOX", "true")
    api._SANDBOX_BUCKETS["testclient"] = (time.monotonic(), 0.0)
    response = client.post("/api/v1/analyze/url", json={"url": "https://example.com/", "sandbox": True})
    assert response.status_code == 429


def test_malformed_url_never_becomes_500(monkeypatch):
    monkeypatch.setenv("ENABLE_WEB_SANDBOX", "true")
    api._SANDBOX_BUCKETS.clear()
    response = client.post("/api/v1/analyze/url", json={"url": "file:///etc/passwd", "sandbox": True})
    assert response.status_code == 200
    assert response.json()["modules"]["web_sandbox"]["status"] == "unavailable"


def test_request_cannot_supply_fixture_allowlist():
    assert "test_allowlist" not in api.UrlIn.model_fields
    assert "resolver_map" not in api.UrlIn.model_fields


def test_finished_job_no_longer_claims_inspection_in_progress(monkeypatch):
    monkeypatch.setenv("ENABLE_WEB_SANDBOX", "true")
    api._SANDBOX_BUCKETS.clear()

    async def fake_run(url, **kwargs):
        return {"status": "failed", "detail": "external browsing is disabled by policy"}

    monkeypatch.setattr(api, "run_sandbox", fake_run)
    first = client.post("/api/v1/analyze/url", json={"url": "https://example.com/", "sandbox": True}).json()
    assert any("still in progress" in item for item in first["limitations"])
    for _ in range(50):
        current = client.get(f"/api/v1/analysis/{first['analysis_id']}").json()
        if current["status"] != "sandboxing":
            break
        time.sleep(0.02)
    assert not any("still in progress" in item for item in current["limitations"])
    assert any("failed" in item for item in current["limitations"])
