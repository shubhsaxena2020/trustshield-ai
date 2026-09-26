"""Regression tests for the second (fresh, independent) sandbox adversarial review."""
import asyncio
import subprocess
import sys
import threading
import time

import pytest

from services.analysis.web_sandbox import jobobject
from services.analysis.web_sandbox.browser_worker import run_browser
from services.analysis.web_sandbox.egress_proxy import ValidatingEgressProxy
from services.analysis.web_sandbox.network_guard import SandboxGuard
from services.analysis.web_sandbox.policy import SandboxPolicy
from tests.fixtures.sandbox_site.site import GETS, start_site


def _browser_or_skip():
    try:
        from playwright.async_api import async_playwright

        async def check():
            async with async_playwright() as pw:
                browser = await pw.chromium.launch(headless=True)
                await browser.close()
        asyncio.run(check())
    except Exception as exc:
        pytest.skip(f"Chromium is not installed or cannot launch: {exc}")


def _run(path, monkeypatch):
    _browser_or_skip()
    server = start_site()
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    port = server.server_address[1]
    monkeypatch.setenv("TRUSTSHIELD_ENV", "test")
    try:
        job = {"url": f"http://bank-login.test:{port}{path}", "production": False,
               "test_allowlist": [f"bank-login.test:{port}", f"other.test:{port}"],
               "resolver_map": {"bank-login.test": ["127.0.0.1"], "other.test": ["127.0.0.1"]}}
        return asyncio.run(run_browser(job))
    finally:
        server.shutdown(); server.server_close(); thread.join(timeout=1)


# ---- worker: JS navigation cap, GET form submit, redirect to a private target ------------------------------------

def test_javascript_navigation_chain_is_capped_like_redirects(monkeypatch):
    GETS.clear()
    result = _run("/js-chain/0", monkeypatch)
    assert result["status"] == "completed"
    policy = SandboxPolicy()
    chain = [path for path in GETS if path.startswith("/js-chain/")]
    assert len(chain) <= policy.max_redirects + 2, chain
    assert any(item["reason"] == "navigation_limit" for item in result["telemetry"]["blocked"])
    assert result["telemetry"]["navigations"] >= policy.max_redirects + 1


def test_get_form_auto_submit_never_reaches_the_target(monkeypatch):
    GETS.clear()
    result = _run("/get-form-submit", monkeypatch)
    assert result["status"] == "completed"
    assert not any(path.startswith("/record") for path in GETS), GETS


def test_redirect_to_unlisted_private_target_is_blocked(monkeypatch):
    result = _run("/private-redirect", monkeypatch)
    assert result["status"] == "completed"
    telemetry = result["telemetry"]
    assert telemetry["redirects"], "the 302 to 127.0.0.1 must be observed"
    assert telemetry["blocked"], "the private destination must be blocked, not fetched"


# ---- Job Object: no assign-after-start window, fail closed --------------------------------------------------------

CHILD = (
    "import subprocess, sys, time, pathlib\n"
    "kid = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(120)'])\n"
    "pathlib.Path(sys.argv[1]).write_text(str(kid.pid))\n"
    "time.sleep(120)\n"
)
windows_only = pytest.mark.skipif(sys.platform != "win32", reason="Job Objects are Windows-only")


def _alive(pid: int) -> bool:
    out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/NH"], capture_output=True, text=True).stdout
    return str(pid) in out


def _wait_pid(path):
    for _ in range(150):
        if path.exists() and path.read_text():
            return int(path.read_text())
        time.sleep(0.1)
    raise AssertionError("child pid file never appeared")


@windows_only
def test_start_contained_leaves_no_escape_window(tmp_path):
    for round_ in range(10):
        pidfile = tmp_path / f"kid{round_}.pid"
        process, containment = jobobject.start_contained([sys.executable, "-c", CHILD, str(pidfile)])
        assert containment.applied, containment.detail
        kid = _wait_pid(pidfile)
        assert _alive(kid)
        containment.close()
        for _ in range(50):
            if not _alive(kid) and process.poll() is not None:
                break
            time.sleep(0.1)
        assert process.poll() is not None and not _alive(kid), f"round {round_}"


@windows_only
def test_worker_executes_nothing_until_it_is_inside_the_job(monkeypatch, tmp_path):
    """Deterministic proof of the suspended launch: while contain() runs, the child must not have run yet."""
    marker = tmp_path / "ran.txt"
    real_contain = jobobject.contain
    ran_before_containment = []

    def slow_contain(process):
        time.sleep(1.5)  # far longer than interpreter start-up; an unsuspended child would have written the marker
        ran_before_containment.append(marker.exists())
        return real_contain(process)

    monkeypatch.setattr(jobobject, "contain", slow_contain)
    code = "import sys, time; open(sys.argv[1], 'w').write('x'); time.sleep(60)"
    process, containment = jobobject.start_contained([sys.executable, "-c", code, str(marker)])
    try:
        assert ran_before_containment == [False]
        for _ in range(100):
            if marker.exists():
                break
            time.sleep(0.1)
        assert marker.exists(), "the worker must actually be resumed after containment"
    finally:
        containment.close()
        process.wait(timeout=10)


@windows_only
def test_containment_failure_fails_closed(monkeypatch):
    monkeypatch.setattr(jobobject, "contain", lambda process: jobobject.Containment(False, "forced failure"))
    started = []
    real_popen = subprocess.Popen

    def spy(*args, **kwargs):
        process = real_popen(*args, **kwargs)
        started.append(process)
        return process

    monkeypatch.setattr(subprocess, "Popen", spy)
    with pytest.raises(jobobject.ContainmentError):
        jobobject.start_contained([sys.executable, "-c", "import time; time.sleep(60)"])
    assert started and started[0].poll() is not None  # killed, never left running


# ---- proxy: obs-fold / duplicate credentials, oversize headers, abrupt clients -----------------------------------

def _probe(monkeypatch, build_request, expect):
    async def scenario():
        seen, errors = [], []

        async def upstream(reader, writer):
            seen.append(await reader.read(8192))
            writer.write(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
            await writer.drain()
            writer.close()

        server = await asyncio.start_server(upstream, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        asyncio.get_running_loop().set_exception_handler(lambda loop, ctx: errors.append(ctx))
        monkeypatch.setenv("TRUSTSHIELD_ENV", "test")
        guard = SandboxGuard(resolver=lambda host: ["127.0.0.1"], test_allowlist=[f"public.test:{port}"])
        proxy = ValidatingEgressProxy(guard, SandboxPolicy(idle_timeout_s=1))
        host, proxy_port = await proxy.start()
        reader, writer = await asyncio.open_connection(host, proxy_port)
        writer.write(build_request(proxy, port))
        await writer.drain()
        try:
            reply = await asyncio.wait_for(reader.read(4096), 2)
        except ConnectionError:
            reply = b""
        writer.close()
        try:
            await writer.wait_closed()
        except ConnectionError:
            pass
        await proxy.close()
        server.close()
        await server.wait_closed()
        await asyncio.sleep(0.05)
        expect(reply, seen, errors)

    asyncio.run(scenario())


def _get(port, extra=b""):
    return f"GET http://public.test:{port}/ HTTP/1.1\r\nHost: public.test\r\n".encode() + extra


def test_credential_is_not_forwarded_upstream(monkeypatch):
    def expect(reply, seen, errors):
        assert b"200 OK" in reply and seen
        assert b"proxy-authorization" not in seen[0].lower() and not errors

    _probe(monkeypatch, lambda proxy, port: _get(port, proxy.auth_header + b"\r\n\r\n"), expect)


def test_obs_folded_credential_is_refused_and_never_forwarded(monkeypatch):
    def expect(reply, seen, errors):
        assert b"407" in reply and not seen

    _probe(monkeypatch, lambda proxy, port: _get(port, b" " + proxy.auth_header + b"\r\n\r\n"), expect)


def test_duplicate_credential_headers_are_refused(monkeypatch):
    def expect(reply, seen, errors):
        assert b"407" in reply and not seen

    _probe(monkeypatch, lambda proxy, port: _get(port, proxy.auth_header + b"\r\n" + proxy.auth_header + b"\r\n\r\n"), expect)


def test_oversized_headers_do_not_raise_unhandled_errors(monkeypatch):
    def expect(reply, seen, errors):
        assert not seen and not errors, errors

    _probe(monkeypatch, lambda proxy, port: b"GET http://public.test/ HTTP/1.1\r\nX-Pad: " + b"a" * 70_000 + b"\r\n\r\n", expect)
