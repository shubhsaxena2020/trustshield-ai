"""API admission controls added after the second sandbox review: port allow-list and bounded pending jobs."""
import asyncio
import threading
import time

from fastapi.testclient import TestClient

from services.api import main as api

client = TestClient(api.app)


def _telemetry():
    return {"initial_url": "https://example.com/", "final_url": "https://example.com/", "title": "Welcome",
            "visible_text": "Hello", "forms": [], "requests": []}


def test_preflight_rejects_disallowed_ports_before_spawning_anything(monkeypatch):
    monkeypatch.setenv("ENABLE_WEB_SANDBOX", "true")
    api._SANDBOX_BUCKETS.clear()
    started = []
    monkeypatch.setattr(api, "_start_sandbox_job", lambda *a, **k: started.append(a))
    for url in ("http://93.184.216.34:22/", "https://93.184.216.34:8443/"):
        assert client.post("/api/v1/analyze/url", json={"url": url, "sandbox": True}).status_code == 422, url
    assert started == []


def test_pending_jobs_are_bounded_and_slots_are_released(monkeypatch):
    monkeypatch.setenv("ENABLE_WEB_SANDBOX", "true")
    api._SANDBOX_BUCKETS.clear()
    monkeypatch.setattr(api, "_SANDBOX_PENDING", threading.BoundedSemaphore(1))
    gate = threading.Event()

    async def slow_run(url, **kwargs):
        while not gate.is_set():
            await asyncio.sleep(0.01)
        return {"status": "completed", "telemetry": _telemetry()}

    monkeypatch.setattr(api, "run_sandbox", slow_run)
    first = client.post("/api/v1/analyze/url", json={"url": "https://example.com/", "sandbox": True})
    second = client.post("/api/v1/analyze/url", json={"url": "https://example.org/", "sandbox": True})
    assert first.status_code == 202 and second.status_code == 429
    gate.set()
    for _ in range(200):
        if client.get(f"/api/v1/analysis/{first.json()['analysis_id']}").json()["status"] != "sandboxing":
            break
        time.sleep(0.02)
    time.sleep(0.2)
    third = client.post("/api/v1/analyze/url", json={"url": "https://example.net/", "sandbox": True})
    assert third.status_code == 202  # the slot was released after the first job finished
    time.sleep(0.3)
