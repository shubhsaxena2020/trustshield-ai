"""Browser end-to-end run of the conversational UI at `/`: real Next.js -> real FastAPI -> real pipeline.

Not collected by pytest. Local:   python tests/e2e/run_chat_e2e.py
Remote (Preview): python tests/e2e/run_chat_e2e.py --web https://<preview> --bypass-token <secret>
Local mode needs a production build made with NEXT_PUBLIC_API_URL=http://localhost:8000; it starts and stops (by PID) its own servers.
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

from playwright.sync_api import Page, sync_playwright

ROOT = Path(__file__).resolve().parents[2]
SHOTS = ROOT / "tests" / "e2e" / "screenshots"
FIX = ROOT / "demo" / "fixtures"
PY = str(ROOT / ".venv" / "Scripts" / "python.exe") if os.name == "nt" else sys.executable
SCAM = "URGENT: your SBI account is blocked. Share the OTP now and verify KYC at http://sbi-kyc-update.xyz/login"
results: list[tuple[str, bool]] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + (f" — {detail}" if detail and not ok else ""))


def wait(url: str, seconds: int = 90) -> None:
    for _ in range(seconds):
        try:
            urllib.request.urlopen(url, timeout=2)
            return
        except Exception:
            time.sleep(1)
    raise RuntimeError(f"{url} did not come up")


def ask(page: Page, text: str, timeout: int, files: str | None = None) -> None:
    """Sends one turn and waits for the assistant's reply to be added."""
    before = page.locator("main ol > li").count()
    if files:
        page.set_input_files("input[type=file]", files)
    if text:
        page.fill("textarea", text)
    page.click("button[type=submit]")
    page.wait_for_function(f"() => document.querySelectorAll('main ol > li').length >= {before + 2} && !document.querySelector('main li[role=status]')",
                           timeout=timeout)


def last_reply(page: Page) -> str:
    return page.locator("main ol > li").last.inner_text()


def run(page: Page, timeout: int) -> None:
    body = lambda: page.inner_text("body")  # noqa: E731
    check("home is the conversational assistant", page.locator("textarea").count() == 1 and "What should I check for you?" in body())
    check("privacy note visible", "Avoid sharing passwords, OTPs or card secrets" in body())

    page.click("button:has-text('URGENT: your bank account')")
    page.wait_for_selector("main ol > li", timeout=timeout)
    check("starter prompt sends immediately", "bank account" in body().lower() or page.locator("main ol > li").count() >= 1)
    page.fill("textarea", "line one")
    page.press("textarea", "Shift+Enter")
    check("Shift+Enter inserts a newline", "\n" in page.input_value("textarea"))
    page.fill("textarea", "")

    ask(page, SCAM, timeout)
    reply = last_reply(page)
    page.screenshot(path=str(SHOTS / "chat_text_scam.png"), full_page=True)
    check("scam text: risk card with score, confidence and evidence", "Risk score" in reply and "Confidence" in reply and "Evidence (" in reply)
    check("scam text: flagged suspicious or high risk", "Suspicious" in reply or "High Risk" in reply)
    check("never claims certainty", not any(x in body() for x in ("100% safe", "definitely fraud", "guaranteed safe")))

    page.locator("summary:has-text('Analysis coverage')").last.click()
    cov = last_reply(page)
    check("capability disclosure lists what was not used", "Not used" in cov and ("reputation" in cov.lower() or "Reputation" in cov))

    ask(page, "Why is this suspicious?", timeout)
    # Free-form (model-written) replies vary in wording, so check the rule: no new card, and the answer refers to the analysis.
    check("follow-up answered from the analysis (no new risk card)", page.locator("main ol > li").last.locator("section[aria-label='Risk assessment']").count() == 0
          and any(w in last_reply(page).lower() for w in ("evidence", "risk", "score", "suspicious", "signal")))
    ask(page, "Did you open the website?", timeout)
    check("follow-up: states the page was not opened", "not" in last_reply(page).lower())
    ask(page, "Is it definitely fraud?", timeout)
    # A refusal may say "definitely" as long as it is negated in the same sentence ("I can't say it's definitely fraud").
    refusal = re.search(r"(can.?t|cannot|can not|not|no|isn.?t|won.?t)\b[^.?!]{0,40}definitely|definitely[^.?!]{0,40}\b(not|can.?t|cannot)\b",
                        last_reply(page).lower())
    check("follow-up: refuses certainty", "definitely" not in last_reply(page).lower() or refusal is not None)

    page.click("button:has-text('New chat')")
    check("new chat clears the timeline", page.locator("main ol > li").count() == 0)

    # --- human-likeness matrix: the core bug this phase fixes ("yo" -> "Low Risk 0/100") -----------------------------------
    ask(page, "yo", timeout)
    yo_reply = last_reply(page)
    check("greeting gets a natural reply, not a score", "0/100" not in yo_reply and "Risk score" not in yo_reply and "TrustShield AI" in yo_reply)
    page.click("button:has-text('New chat')")

    ask(page, "What is phishing?", timeout)
    phishing_reply = last_reply(page)
    check("general cybersecurity question gets an educational answer, no risk card", "Risk score" not in phishing_reply and "phishing" in phishing_reply.lower())
    page.click("button:has-text('New chat')")

    ask(page, "What can you do?", timeout)
    product_reply = last_reply(page)
    check("product question gets a product answer, no risk card", "Risk score" not in product_reply)
    page.click("button:has-text('New chat')")

    ask(page, "Hey bro your SBI account is blocked click http://sbi-kyc-update.xyz/login", timeout)
    check("greeting language does not suppress a real scam analysis", "Risk score" in last_reply(page))
    page.screenshot(path=str(SHOTS / "chat_greeting_vs_scam.png"), full_page=True)
    page.click("button:has-text('New chat')")

    ask(page, "Hi, the team lunch is at 1pm tomorrow. See you there!", timeout)
    check("benign text: not flagged high risk", "High Risk" not in last_reply(page))

    ask(page, "http://0x7f000001/login", timeout)
    check("bare URL analysed with IP evidence", "IP" in last_reply(page) or "Evidence (" in last_reply(page))

    ask(page, "What is this?", timeout, str(FIX / "qr_suspicious_url.png"))
    page.screenshot(path=str(SHOTS / "chat_qr.png"), full_page=True)
    check("QR upload: payload shown as not opened", "not opened" in last_reply(page).lower())

    ask(page, "Is this legit?", timeout, str(FIX / "kyc_screenshot.png"))
    check("screenshot upload: OCR text extracted and analysed", "Extracted text" in last_reply(page) and "Risk score" in last_reply(page))

    blank = SHOTS / "blank.png"
    from PIL import Image
    Image.new("RGB", (80, 80), "white").save(blank)
    ask(page, "", timeout, str(blank))
    check("blank image: Unable to Verify", "Unable to Verify" in last_reply(page))
    blank.unlink(missing_ok=True)

    page.click("button:has-text('New chat')")
    page.evaluate("""() => { const dt = new DataTransfer(); dt.items.add(new File([new Uint8Array(70)], 'x.png', {type: 'image/png'}));
        document.querySelector('textarea').dispatchEvent(new ClipboardEvent('paste', {clipboardData: dt, bubbles: true, cancelable: true})); }""")
    check("clipboard image paste attaches a preview", page.locator("img[alt='Attachment preview']").count() == 1)
    page.click("button[aria-label='Remove attachment']")
    check("attachment can be removed", page.locator("img[alt='Attachment preview']").count() == 0)

    page.set_input_files("input[type=file]", {"name": "big.png", "mimeType": "image/png", "buffer": b"0" * (4 * 1024 * 1024 + 1)})
    check("oversize image rejected in the UI", "larger than 4 MB" in body() and page.locator("img[alt='Attachment preview']").count() == 0)

    page.set_viewport_size({"width": 390, "height": 800})
    ask(page, SCAM, timeout)
    page.screenshot(path=str(SHOTS / "chat_mobile_390.png"), full_page=True)
    check("mobile 390px: no horizontal scroll", page.evaluate("() => document.documentElement.scrollWidth <= window.innerWidth"))
    for w in (360, 430, 820):
        page.set_viewport_size({"width": w, "height": 800})
        check(f"{w}px: no horizontal scroll", page.evaluate("() => document.documentElement.scrollWidth <= window.innerWidth"))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--web", default="")
    ap.add_argument("--bypass-token", default=os.environ.get("VERCEL_AUTOMATION_BYPASS_SECRET", ""))
    ap.add_argument("--timeout", type=int, default=90000)
    args = ap.parse_args()
    SHOTS.mkdir(parents=True, exist_ok=True)
    pids: list[int] = []
    web_url = args.web or "http://localhost:3000/"
    try:
        if not args.web:
            env = {**os.environ, "PLAYWRIGHT_BROWSERS_PATH": str(ROOT / ".playwright-browsers"),
                   "TESSERACT_CMD": os.environ.get("TESSERACT_CMD", r"C:\Users\shubh\Tools\Tesseract-OCR\tesseract.exe")}
            env.pop("TRUSTSHIELD_ENV", None)
            env.pop("ENABLE_WEB_SANDBOX", None)
            api = subprocess.Popen([PY, "-m", "uvicorn", "services.api.main:app", "--port", "8000"], cwd=ROOT, env=env)
            web = subprocess.Popen(["npx.cmd" if os.name == "nt" else "npx", "next", "start", "-p", "3000"], cwd=ROOT / "apps" / "web", env=env)
            pids = [api.pid, web.pid]
            wait("http://localhost:8000/api/v1/health")
            wait("http://localhost:3000/")
        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            context = browser.new_context(viewport={"width": 1100, "height": 900}, extra_http_headers=(
                {"x-vercel-protection-bypass": args.bypass_token, "x-vercel-set-bypass-cookie": "true"} if args.bypass_token else {}))
            page = context.new_page()
            errors: list[str] = []
            page.on("console", lambda m: errors.append(m.text) if m.type == "error" and "vercel.live" not in m.text else None)
            page.goto(web_url, wait_until="networkidle", timeout=args.timeout)
            run(page, args.timeout)
            check("no console errors", not errors, "; ".join(errors)[:300])
            browser.close()
    finally:
        for pid in pids:
            subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"] if os.name == "nt" else ["kill", str(pid)], capture_output=True)
    failed = [n for n, ok in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
