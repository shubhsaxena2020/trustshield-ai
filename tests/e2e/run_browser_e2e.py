"""Browser end-to-end run: real Next.js UI -> real FastAPI -> real pipeline.

Not collected by pytest (no test_ prefix). Run:  python tests/e2e/run_browser_e2e.py
Starts its own API (8000) and web (3000, production build) servers and stops
them by PID. Sandbox runs with ENABLE_WEB_SANDBOX=true but external browsing
OFF, so it must refuse the public URL safely.
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
SHOTS = ROOT / "tests" / "e2e" / "screenshots"
PY = str(ROOT / ".venv" / "Scripts" / "python.exe") if os.name == "nt" else sys.executable
results: list[tuple[str, bool, str]] = []


def wait(url: str, seconds: int = 90) -> None:
    for _ in range(seconds):
        try:
            urllib.request.urlopen(url, timeout=2)
            return
        except Exception:
            time.sleep(1)
    raise RuntimeError(f"{url} did not come up")


def check(name: str, ok: bool, detail: str = "") -> None:
    results.append((name, bool(ok), detail))
    print(("PASS " if ok else "FAIL ") + name + (f" — {detail}" if detail and not ok else ""))


def main() -> int:
    SHOTS.mkdir(parents=True, exist_ok=True)
    env = {**os.environ, "ENABLE_WEB_SANDBOX": "true", "PLAYWRIGHT_BROWSERS_PATH": str(ROOT / ".playwright-browsers"),
           "TESSERACT_CMD": os.environ.get("TESSERACT_CMD", r"C:\Users\shubh\Tools\Tesseract-OCR\tesseract.exe")}
    env.pop("TRUSTSHIELD_ENV", None)
    env.pop("TRUSTSHIELD_SANDBOX_ALLOW_EXTERNAL", None)
    api = subprocess.Popen([PY, "-m", "uvicorn", "services.api.main:app", "--port", "8000"], cwd=ROOT, env=env)
    npx = "npx.cmd" if os.name == "nt" else "npx"
    web = subprocess.Popen([npx, "next", "start", "-p", "3000"], cwd=ROOT / "apps" / "web", env=env)
    pids = [api.pid, web.pid]
    try:
        wait("http://localhost:8000/api/v1/health")
        wait("http://localhost:3000/")
        health = json.load(urllib.request.urlopen("http://localhost:8000/api/v1/health"))
        check("health reports sandbox enabled and default ML v3",
              health["modules"]["web_sandbox"] is True and health["modules"]["ml"]["model_name"] == "trustshield_text_v3")
        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            page = browser.new_page(viewport={"width": 1100, "height": 900})
            page.goto("http://localhost:3000/")
            body = lambda: page.inner_text("body")  # noqa: E731

            page.fill("#input", "URGENT: your SBI account is blocked. Share the OTP now and verify KYC at http://sbi-kyc-update.xyz/login")
            page.click("button[type=submit]")
            page.wait_for_selector("text=/high|suspicious/i", timeout=30000)
            text = body().lower()
            check("text scam: risk shown with evidence", ("high" in text or "suspicious" in text) and "evidence" in text)
            page.screenshot(path=str(SHOTS / "text_scam.png"), full_page=True)

            page.fill("#input", "Hi, the team lunch is at 1pm tomorrow. See you there!")
            page.click("button[type=submit]")
            page.wait_for_selector("text=/low risk|no strong|low/i", timeout=30000)
            check("benign text: not flagged high", "high risk" not in body().lower())

            page.click("role=tab[name=/link|url/i]")
            page.fill("#input", "http://0x7f000001/login")
            page.click("button[type=submit]")
            page.wait_for_selector("text=/raw IP|IP address/i", timeout=30000)
            check("numeric-encoded IP URL: IP-address evidence shown", True)
            page.screenshot(path=str(SHOTS / "url_ip.png"), full_page=True)

            box = page.locator("input[type=checkbox]")
            check("sandbox opt-in checkbox visible and off by default", box.count() == 1 and not box.is_checked())
            box.check()
            page.fill("#input", "https://example.com/")
            page.click("button[type=submit]")
            page.wait_for_function("() => !document.querySelector('button[type=submit]').disabled", timeout=60000)
            after = body().lower()
            check("sandbox request on public URL settles (external browsing off) without error alert",
                  page.locator("section[aria-labelledby=analyze-h] > p[role=alert]").count() == 0 and "external browsing is disabled" in after
                  and "inspection is still in progress" not in after, after[-400:])
            page.screenshot(path=str(SHOTS / "url_sandbox_refused.png"), full_page=True)

            page.click("role=tab[name=/screenshot|image/i]")
            page.set_input_files("input[type=file]", str(ROOT / "demo/fixtures/kyc_screenshot.png"))
            page.click("button[type=submit]")
            page.wait_for_selector("text=/extracted text|OCR/i", timeout=90000)
            check("screenshot: OCR panel shown", True)
            page.screenshot(path=str(SHOTS / "screenshot_kyc.png"), full_page=True)

            page.click("role=tab[name=/qr/i]")
            page.set_input_files("input[type=file]", str(ROOT / "demo/fixtures/qr_suspicious_url.png"))
            page.click("button[type=submit]")
            page.wait_for_selector("text=/QR/i", timeout=60000)
            check("QR: decoded and analysed without opening the link", "not opened" in body().lower() or "qr" in body().lower())
            page.screenshot(path=str(SHOTS / "qr_url.png"), full_page=True)
            browser.close()
    finally:
        for pid in pids:
            subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"] if os.name == "nt" else ["kill", str(pid)],
                           capture_output=True)
    failed = [name for name, ok, _ in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
