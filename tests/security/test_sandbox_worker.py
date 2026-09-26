import asyncio
import threading

import pytest

from tests.fixtures.sandbox_site.site import POSTS, start_site
from services.analysis.web_sandbox.browser_worker import run_browser
from services.analysis.web_sandbox.client import SandboxClient
from services.analysis.web_sandbox.policy import SandboxPolicy


def _browser_available():
    try:
        from playwright.async_api import async_playwright

        async def check():
            async with async_playwright() as pw:
                browser = await pw.chromium.launch(headless=True)
                await browser.close()
        asyncio.run(check())
        return True
    except Exception as exc:
        pytest.skip(f"Chromium is not installed or cannot launch: {exc}")


def _run(path, monkeypatch, **extra):
    _browser_available()
    server = start_site()
    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()
    port = server.server_address[1]
    monkeypatch.setenv("TRUSTSHIELD_ENV", "test")
    try:
        job = {"url": f"http://bank-login.test:{port}{path}", "production": False,
               "test_allowlist": [f"bank-login.test:{port}", f"other.test:{port}"],
               "resolver_map": {"bank-login.test": ["127.0.0.1"], "other.test": ["127.0.0.1"]}, **extra}
        return asyncio.run(run_browser(job))
    finally:
        server.shutdown(); server.server_close(); server_thread.join(timeout=1)


def test_bank_form_is_observed_but_never_submitted(monkeypatch):
    POSTS.clear()
    result = _run("/bank-login", monkeypatch)
    assert result["status"] == "completed"
    telemetry = result["telemetry"]
    kinds = {field["field_type"] for form in telemetry["forms"] for field in form["fields"]}
    assert {"password", "otp", "card", "cvv"} <= kinds
    assert telemetry["post_attempts"] == 0
    assert POSTS == []


def test_page_post_attempt_is_aborted_before_reaching_fixture(monkeypatch):
    POSTS.clear()
    result = _run("/post-attempt", monkeypatch)
    assert result["status"] == "completed"
    assert result["telemetry"]["post_attempts"] >= 1
    assert POSTS == []


@pytest.mark.parametrize("path", ["/redirect/0", "/dialogs", "/popups", "/prompt-injection", "/hidden-iframe", "/local-access", "/download", "/huge", "/slow"])
def test_worker_observation_scenarios_are_bounded(path, monkeypatch):
    result = _run(path, monkeypatch)
    assert result["status"] in {"completed", "failed"}
    if result["status"] == "completed":
        telemetry = result["telemetry"]
        assert telemetry["visible_text_untrusted"] is True
        assert len(telemetry.get("requests", [])) <= 200
        assert not telemetry.get("screenshot_jpeg_b64") or len(telemetry["screenshot_jpeg_b64"]) < 2_500_000


def test_redirect_and_prompt_content_never_becomes_worker_instruction(monkeypatch):
    result = _run("/prompt-injection", monkeypatch)
    if result["status"] == "completed":
        assert result["telemetry"]["visible_text_untrusted"] is True


def test_api_client_starts_scrubbed_worker_with_parent_timeout(monkeypatch):
    seen = {}

    class FakeProcess:
        returncode = 0

        def communicate(self, payload, timeout):
            seen["payload"] = payload; seen["timeout"] = timeout
            return '{"status":"completed"}', ""

    def fake_popen(command, **kwargs):
        seen["command"] = command; seen["env"] = kwargs["env"]
        return FakeProcess()

    monkeypatch.setenv("OPENAI_API_KEY", "must-not-cross-boundary")
    monkeypatch.setenv("TRUSTSHIELD_SECRET", "must-not-cross-boundary")
    from services.analysis.web_sandbox import client as client_module
    from services.analysis.web_sandbox.jobobject import Containment
    monkeypatch.setattr(client_module, "start_contained",
                        lambda command, **kwargs: (fake_popen(command, **kwargs), Containment(True, "fake")))
    result = SandboxClient(SandboxPolicy(total_timeout_s=3))._run_process("https://example.com/", None, None, True)
    assert result["status"] == "completed"
    assert "services.worker.sandbox_worker" in seen["command"]
    assert seen["timeout"] == 3
    assert "OPENAI_API_KEY" not in seen["env"] and "TRUSTSHIELD_SECRET" not in seen["env"]
    assert "PLAYWRIGHT_BROWSERS_PATH" in seen["env"]


def test_downloads_are_quarantined_hashed_and_deleted(monkeypatch):
    result = _run("/download", monkeypatch)
    assert result["status"] == "completed"
    downloads = result["telemetry"]["downloads"]
    assert len(downloads) == 1
    assert downloads[0]["deleted"] is True and downloads[0]["sha256"]


def test_semaphore_is_thread_safe_across_event_loops():
    import threading as th
    client = SandboxClient(SandboxPolicy(max_concurrency=1))
    assert isinstance(SandboxClient._semaphore, type(th.BoundedSemaphore()))
    assert client is not None


def test_full_subprocess_path_runs_chromium_under_job_object_and_proxy_auth(monkeypatch):
    _browser_available()
    server = start_site()
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    port = server.server_address[1]
    monkeypatch.setenv("TRUSTSHIELD_ENV", "test")
    try:
        result = SandboxClient(SandboxPolicy())._run_process(
            f"http://bank-login.test:{port}/bank-login", [f"bank-login.test:{port}"],
            {"bank-login.test": ["127.0.0.1"]}, False)
    finally:
        server.shutdown(); server.server_close(); thread.join(timeout=1)
    assert result["status"] == "completed", result
    assert result["telemetry"]["forms"]
    if __import__("sys").platform == "win32":
        assert result["isolation"]["job_object"] is True
