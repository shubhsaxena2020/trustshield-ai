"""Browser end-to-end run against the current chat UI: real Next.js page -> real FastAPI -> real pipeline.

Not collected by pytest (no test_ prefix). Run:  .venv\\Scripts\\python.exe tests\\e2e\\run_browser_e2e.py
Uses ports 8100 (API) and 3100 (web) so it never collides with a developer's running stack. Builds the
frontend with API_PROXY_TARGET pointing at its own API, so the browser only ever talks to the web origin.
Stops both servers by PID in a finally block.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[2]
WEB = ROOT / "apps" / "web"
SHOTS = ROOT / "tests" / "e2e" / "screenshots"
PY = str(ROOT / ".venv" / "Scripts" / "python.exe") if os.name == "nt" else sys.executable
API_PORT, WEB_PORT = 8100, 3100
API = f"http://127.0.0.1:{API_PORT}"
WEB_URL = f"http://127.0.0.1:{WEB_PORT}/"
NPX = "npx.cmd" if os.name == "nt" else "npx"
results: list[tuple[str, bool, str]] = []


def wait(url: str, seconds: int = 120) -> None:
    for _ in range(seconds):
        try:
            urllib.request.urlopen(url, timeout=2)
            return
        except Exception:
            time.sleep(1)
    raise RuntimeError(f"{url} did not come up")


def check(name: str, ok: bool, detail: str = "") -> None:
    results.append((name, bool(ok), detail))
    print(("PASS " if ok else "FAIL ") + name + (f" -- {detail}" if detail and not ok else ""))


def kill(procs: list[subprocess.Popen]) -> None:
    for p in procs:
        subprocess.run(["taskkill", "/PID", str(p.pid), "/T", "/F"] if os.name == "nt" else ["kill", str(p.pid)],
                       capture_output=True)


def send(page, text: str) -> None:
    page.get_by_role("textbox", name="Message").fill(text)
    page.get_by_role("button", name="Send").click()


def main() -> int:
    SHOTS.mkdir(parents=True, exist_ok=True)
    env = {**os.environ, "API_PROXY_TARGET": API, "PLAYWRIGHT_BROWSERS_PATH": str(ROOT / ".playwright-browsers"),
           "TESSERACT_CMD": os.environ.get("TESSERACT_CMD", r"C:\Users\shubh\Tools\Tesseract-OCR\tesseract.exe")}
    env.pop("TRUSTSHIELD_ENV", None)
    build = subprocess.run([NPX, "next", "build"], cwd=WEB, env=env, capture_output=True, text=True)
    if build.returncode != 0:
        print(build.stdout[-1500:], build.stderr[-1500:])
        check("frontend production build", False, "next build failed")
        return 1
    api = subprocess.Popen([PY, "-m", "uvicorn", "services.api.main:app", "--host", "127.0.0.1", "--port", str(API_PORT)],
                           cwd=ROOT, env=env)
    web = subprocess.Popen([NPX, "next", "start", "-H", "127.0.0.1", "-p", str(WEB_PORT)], cwd=WEB, env=env)
    try:
        wait(f"{API}/api/v1/health")
        wait(WEB_URL)
        health = json.load(urllib.request.urlopen(f"{API}/api/v1/health"))
        check("health: default ML v3 available", health["modules"]["ml"]["model_name"] == "trustshield_text_v3")
        check("health: web sandbox no longer reported", "web_sandbox" not in health["modules"])
        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            page = browser.new_page(viewport={"width": 1100, "height": 900})
            page.goto(WEB_URL)
            page.get_by_role("heading", name="What should I check for you?").wait_for(timeout=15000)
            check("empty state shows", True)
            check("no advanced-analyzer link", page.get_by_text("Advanced analyzer").count() == 0)

            send(page, "URGENT: your SBI account is blocked. Share the OTP now and verify KYC at http://sbi-kyc-update.xyz/login")
            page.get_by_text("High Risk", exact=False).first.wait_for(timeout=60000)
            body = page.inner_text("body")
            check("text scam: High Risk with evidence card", "Risk score" in body and "Evidence (" in body, body[-300:])
            page.screenshot(path=str(SHOTS / "text_scam.png"), full_page=True)

            send(page, "Hi, the team lunch is at 1pm tomorrow. See you there!")
            page.wait_for_function("() => document.querySelectorAll('[aria-label=\"Message\"]').length > 0")
            page.wait_for_timeout(4000)
            check("benign message: not flagged High Risk after the scam turn",
                  page.inner_text("main").count("High Risk") == 1, "a second High Risk appeared for a benign message")

            send(page, "http://paypa1-secure-login.xyz/verify")
            page.wait_for_timeout(6000)
            check("lookalike URL: flagged, not Low Risk", "Low Risk" not in page.inner_text("main").split("paypa1")[-1])
            page.screenshot(path=str(SHOTS / "url_lookalike.png"), full_page=True)

            page.get_by_role("button", name="Attach", exact=True).click()
            with page.expect_file_chooser() as chooser:
                page.get_by_role("menuitem", name="Upload a screenshot or QR code").click()
            chooser.value.set_files(str(ROOT / "demo" / "fixtures" / "kyc_screenshot.png"))
            page.get_by_role("button", name="Send").click()
            page.get_by_text("Risk score", exact=False).nth(2).wait_for(timeout=120000)
            check("screenshot: OCR-backed analysis card shown", "Risk score" in page.inner_text("main"))
            page.screenshot(path=str(SHOTS / "screenshot_kyc.png"), full_page=True)
            browser.close()
    finally:
        kill([api, web])
    failed = [name for name, ok, _ in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
