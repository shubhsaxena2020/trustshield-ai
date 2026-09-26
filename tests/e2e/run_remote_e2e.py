"""Browser E2E against a DEPLOYED frontend + API (staging or production). Not collected by pytest.

    python tests/e2e/run_remote_e2e.py --web https://<frontend>.vercel.app --api https://<api>.onrender.com

Drives the real UI in Chromium (Playwright): the seven Milestone A journeys, footer/prototype label, no console CORS errors, no
localhost calls, and safe failure for a malformed upload. Uses only the synthetic demo fixtures. Sandbox is never touched.
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[2]
FIX = ROOT / "demo" / "fixtures"
SHOTS = ROOT / "tests" / "e2e" / "screenshots"
results: list[tuple[str, bool]] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + ("" if ok or not detail else f"  -- {detail[:300]}"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--web", required=True)
    parser.add_argument("--api", default="")
    parser.add_argument("--bypass-token", default=os.environ.get("VERCEL_AUTOMATION_BYPASS_SECRET", ""), help="Vercel Deployment Protection bypass secret")
    parser.add_argument("--same-origin", action="store_true", help="the API is served from the same origin under /api (Vercel Services)")
    parser.add_argument("--timeout", type=int, default=90000)
    parser.add_argument("--local-rehearsal", action="store_true", help="rehearse against http://localhost servers (skips the HTTPS and no-localhost checks)")
    args = parser.parse_args()
    SHOTS.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        context = browser.new_context(viewport={"width": 1100, "height": 900}, extra_http_headers=(
            {"x-vercel-protection-bypass": args.bypass_token, "x-vercel-set-bypass-cookie": "true"} if args.bypass_token else {}))
        page = context.new_page()
        console_errors: list[str] = []
        requests: list[str] = []
        page.on("console", lambda m: console_errors.append(m.text) if m.type == "error" else None)
        page.on("request", lambda r: requests.append(r.url))
        page.goto(args.web, wait_until="networkidle", timeout=args.timeout)
        body = lambda: page.inner_text("body")  # noqa: E731
        check("site opens over HTTPS", (args.local_rehearsal or args.web.startswith("https://")) and "TrustShield" in body())
        check("prototype footer shown", "Research / Tech Expo Prototype" in body())
        check("no ML/API-unavailable banner", page.locator("[data-testid=ml-unavailable-banner]").count() == 0
              and page.locator("[data-testid=api-unreachable-banner]").count() == 0)
        check("sandbox opt-in not offered (sandbox disabled)", page.locator("input[type=checkbox]").count() == 0)

        def submit_text(text, expect):
            page.fill("#input", text)
            page.click("button[type=submit]")
            page.wait_for_selector(expect, timeout=args.timeout)

        submit_text("URGENT: your SBI account is blocked. Share the OTP now and verify KYC at http://sbi-kyc-update.xyz/login", "text=/high|suspicious|caution/i")
        page.screenshot(path=str(SHOTS / "remote_text_scam.png"), full_page=True)
        check("malicious text: risk + evidence shown", "evidence" in body().lower() and "high risk" in body().lower() or "suspicious" in body().lower())
        submit_text("Hi, the team lunch is at 1pm tomorrow. See you there!", "text=/low risk|no strong|low/i")
        check("legitimate text: not flagged high", "high risk" not in body().lower())

        page.click("role=tab[name=/link|url/i]")
        submit_text("http://sbi-kyc-update.xyz/login", "text=/evidence/i")
        check("static URL analysis", "evidence" in body().lower())
        submit_text("http://0x7f000001/login", "text=/raw IP|IP address/i")
        page.screenshot(path=str(SHOTS / "remote_url_ip.png"), full_page=True)
        check("numeric-IP URL analysis", True)

        page.click("role=tab[name=/screenshot|image/i]")
        page.set_input_files("input[type=file]", str(FIX / "kyc_screenshot.png"))
        page.click("button[type=submit]")
        page.wait_for_selector("text=/extracted text|OCR/i", timeout=args.timeout)
        page.screenshot(path=str(SHOTS / "remote_screenshot.png"), full_page=True)
        check("screenshot OCR analysis", True)

        page.click("role=tab[name=/qr/i]")
        page.set_input_files("input[type=file]", str(FIX / "qr_suspicious_url.png"))
        page.click("button[type=submit]")
        page.wait_for_selector("text=/QR/i", timeout=args.timeout)
        check("QR analysis", "qr" in body().lower())
        page.set_input_files("input[type=file]", str(FIX / "qr_upi.png"))
        page.click("button[type=submit]")
        page.wait_for_selector("text=/UPI/i", timeout=args.timeout)
        page.screenshot(path=str(SHOTS / "remote_qr_upi.png"), full_page=True)
        check("UPI QR analysis", "upi" in body().lower())

        bad = SHOTS / "not_an_image.png"
        bad.write_bytes(b"this is not an image")
        page.set_input_files("input[type=file]", str(bad))
        page.click("button[type=submit]")
        page.wait_for_selector("[role=alert]", timeout=args.timeout)
        check("malformed upload shows a safe error (no traceback)", "Traceback" not in body())

        page.wait_for_timeout(500)
        api_origin = (args.api or args.web).rstrip("/")
        check("browser called the production API directly", any(u.startswith(api_origin + "/api/v1/analyze/") for u in requests))
        check("no localhost requests in production", args.local_rehearsal or not any("localhost" in u or "127.0.0.1" in u for u in requests))
        check("no CORS/console errors", not any("CORS" in e or "blocked" in e.lower() for e in console_errors), "; ".join(console_errors)[:300])
        browser.close()
    bad.unlink(missing_ok=True)  # released only after the browser closes (Windows keeps the upload handle open)
    failed = [name for name, ok in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
