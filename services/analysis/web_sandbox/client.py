"""API-side subprocess client. This module deliberately never imports Playwright."""
from __future__ import annotations

import asyncio
import json
import os
import shutil
import subprocess
import threading
import sys
import tempfile
from pathlib import Path

from .jobobject import Containment, ContainmentError, start_contained
from .policy import SandboxPolicy
from .policy import external_allowed
from .telemetry import bounded_telemetry, clean_text


class SandboxClient:
    # Jobs run on separate threads with separate event loops, so the gate must be thread-safe.
    _semaphore: threading.BoundedSemaphore | None = None

    def __init__(self, policy: SandboxPolicy | None = None):
        self.policy = policy or SandboxPolicy.from_env()
        if SandboxClient._semaphore is None:
            SandboxClient._semaphore = threading.BoundedSemaphore(self.policy.max_concurrency)

    async def run(self, url: str, *, test_allowlist: list[str] | None = None,
                  resolver_map: dict[str, list[str]] | None = None,
                  production: bool = True) -> dict:
        semaphore = SandboxClient._semaphore
        assert semaphore is not None

        def guarded() -> dict:
            with semaphore:
                return self._run_process(url, test_allowlist, resolver_map, production)

        return await asyncio.to_thread(guarded)

    def _run_process(self, url: str, test_allowlist, resolver_map, production) -> dict:
        root = Path(__file__).resolve().parents[3]
        temp_root = Path(tempfile.mkdtemp(prefix="trustshield-worker-"))
        env = {
            "PATH": os.environ.get("PATH", ""),
            "PYTHONPATH": os.environ.get("PYTHONPATH", str(root)),
            "TEMP": str(temp_root), "TMP": str(temp_root),
        }
        local_browsers = root / ".playwright-browsers"
        browsers = os.environ.get("PLAYWRIGHT_BROWSERS_PATH") or (str(local_browsers) if local_browsers.is_dir() else "")
        if browsers:
            env["PLAYWRIGHT_BROWSERS_PATH"] = browsers
        for key in ("SystemRoot", "WINDIR"):
            if os.environ.get(key):
                env[key] = os.environ[key]
        # Only bounded sandbox knobs are copied. Test mode is not accepted from
        # an API request; direct test harnesses can opt into it via their process.
        for key in os.environ:
            if key.startswith("TRUSTSHIELD_SANDBOX_"):
                env[key] = os.environ[key]
        if os.environ.get("TRUSTSHIELD_ENV", "").lower() == "test":
            env["TRUSTSHIELD_ENV"] = "test"
        payload = {"url": url, "test_allowlist": test_allowlist or [],
                   "resolver_map": resolver_map or {}, "production": production,
                   "allow_external": external_allowed()}
        process = None
        containment = Containment(False, "not started")
        try:
            process, containment = start_contained(
                [sys.executable, "-m", "services.worker.sandbox_worker"],
                cwd=str(root), env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                stderr=subprocess.PIPE, text=True,
            )
            stdout, stderr = process.communicate(json.dumps(payload), timeout=self.policy.total_timeout_s)
            lines = [line for line in stdout.splitlines() if line.strip()]
            data = json.loads(lines[-1]) if lines else {"status": "failed", "detail": "worker returned no JSON"}
            if process.returncode != 0 and data.get("status") == "completed":
                data = {"status": "failed", "detail": "worker exited non-zero"}
            data["isolation"] = {"job_object": containment.applied, "detail": containment.detail}
            return data
        except subprocess.TimeoutExpired:
            if process is not None:
                process.kill()
                process.communicate()
            return {"status": "timeout", "detail": "sandbox wall-clock limit exceeded"}
        except ContainmentError as exc:
            return {"status": "failed", "detail": "sandbox containment unavailable: " + clean_text(exc, 300)}
        except (OSError, json.JSONDecodeError) as exc:
            return {"status": "failed", "detail": clean_text(exc, 500)}
        finally:
            containment.close()
            shutil.rmtree(temp_root, ignore_errors=True)


async def run_sandbox(url: str, **kwargs) -> dict:
    return await SandboxClient().run(url, **kwargs)
