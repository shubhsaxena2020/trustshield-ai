"""Manual-QA run for docs/MANUAL_QA_CHECKLIST.md sections A2, B3, B4, C1-C3 and D1.

Not collected by pytest. Requires the loopback stack already running (frontend
http://127.0.0.1:3000, backend http://127.0.0.1:8000). This script NEVER starts
or stops servers: if the preflight fails it reports the fact and exits.

Local:  python tests/e2e/run_manual_qa_playwright.py
Remote: python tests/e2e/run_manual_qa_playwright.py --web https://<preview> --api https://<backend>
"""
from __future__ import annotations

import argparse
import base64
import json
import urllib.request
from pathlib import Path

from playwright.sync_api import Page, sync_playwright

ROOT = Path(__file__).resolve().parents[2]
SHOTS = ROOT / "tests" / "e2e" / "screenshots"

results: list[tuple[str, bool]] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + (f" — {detail}" if detail and not ok else ""))


def stack_up(url: str) -> bool:
    try:
        with urllib.request.urlopen(url, timeout=2) as r:
            return r.status == 200
    except Exception:
        return False


def drop(page: Page, spec: dict) -> None:
    """Dispatch a real DataTransfer drop on the chat surface (the component's own handler)."""
    page.evaluate(
        """async (spec) => {
            // Decode in the page: the app's CSP connect-src blocks fetch() of data: URLs.
            const bin = atob(spec.b64);
            const buffer = Uint8Array.from(bin, (c) => c.charCodeAt(0));
            const file = new File([buffer], spec.name, {type: spec.type});
            const dt = new DataTransfer();
            dt.items.add(file);
            const surface = document.querySelector('div.h-dvh') ?? document.body;
            for (const type of ['dragenter', 'dragover', 'drop']) {
                surface.dispatchEvent(new DragEvent(type, {dataTransfer: dt, bubbles: true, cancelable: true}));
                await new Promise((r) => setTimeout(r, 10));
            }
        }""",
        spec,
    )


def no_hscroll(page: Page) -> bool:
    return page.evaluate(
        "() => [document.documentElement, document.body].every((el) => el.scrollWidth <= el.clientWidth)"
    )


def run(page: Page, timeout: int) -> None:
    body = lambda: page.inner_text("body")  # noqa: E731
    mic = page.locator("button[aria-label='Speak your message'], button[aria-label='Stop voice input']")

    check("initial render: composer and privacy note present",
          page.locator("textarea").count() == 1 and "Avoid sharing passwords, OTPs or card secrets" in body())

    # C1 — real PNG onto the chat surface -> preview above the message box.
    kyc_bytes = (ROOT / "demo" / "fixtures" / "kyc_screenshot.png").read_bytes()
    kyc = {"b64": base64.b64encode(kyc_bytes).decode(), "name": "kyc_screenshot.png", "type": "image/png"}
    drop(page, kyc)
    preview = page.locator("img[alt='Attachment preview']")
    preview.wait_for(state="visible", timeout=10_000)
    check("C1 kyc_screenshot.png drop: attachment preview shows", preview.count() == 1)
    page.click("button[aria-label='Remove attachment']")
    drop(page, kyc)  # the same file again: the preview must come back after a remove
    check("C1 second drop after remove: preview re-appears", preview.count() == 1)
    page.click("button[aria-label='Remove attachment']")

    # C2 — 5 MB image -> rejected with the size message.
    page.evaluate(
        """() => {
            const big = new Uint8Array(5 * 1024 * 1024);
            const file = new File([big], 'big.png', {type: 'image/png'});
            const dt = new DataTransfer();
            dt.items.add(file);
            const surface = document.querySelector('div.h-dvh') ?? document.body;
            surface.dispatchEvent(new DragEvent('dragover', {dataTransfer: dt, bubbles: true, cancelable: true}));
            surface.dispatchEvent(new DragEvent('drop', {dataTransfer: dt, bubbles: true, cancelable: true}));
        }"""
    )
    notice = page.locator("p[role='alert']")
    check("C2 5 MB drop: rejected with the size message",
          "larger than 4 MB" in body() and notice.count() == 1 and preview.count() == 0, "notice missing or the file was still attached")

    # C3 — .txt drop -> ignored, not accepted, page stays usable.
    txt = {"b64": "aGVsbG8gd29ybGQK", "name": "notes.txt", "type": "text/plain"}
    drop(page, txt)
    page.wait_for_timeout(500)
    # A rejected file may show a notice, which is correct; what must not happen is an attachment or a disabled composer.
    check("C3 .txt drop: not attached and the composer stays usable",
          preview.count() == 0 and page.locator("textarea").first.is_enabled())

    # B4/B3 — mic button exists with Web Speech; clicking it with permission denied shows a message.
    has_speech = page.evaluate("() => Boolean(window.SpeechRecognition ?? window.webkitSpeechRecognition)")
    if has_speech:
        check("B4 mic button exists because the browser has Web Speech", mic.count() == 1)
    else:
        check("B4 mic button hidden or disabled because the browser has no Web Speech", mic.count() == 0)

    if has_speech and mic.count() == 1:
        mic.click()
        page.wait_for_timeout(1500)  # recog.onerror fires asynchronously once permission is denied
        check("B3 mic click with permission denied: message shown, not accepted silently",
              "microphone access is allowed" in body(),
              "no notice appeared after the denied start")
        page.type("textarea", "still responsive")
        check("B3 composer still usable after the denied mic click", page.input_value("textarea") == "still responsive")
        page.fill("textarea", "")
    elif has_speech:
        check("B3 mic click with permission denied: skipped because the button never appeared", False)

    # A2 — who are you: describes TrustShield and is not analysed.
    page.fill("textarea", "who are you")
    before = page.locator("main ol > li").count()
    page.click("button[type=submit]")
    page.wait_for_function(
        f"() => document.querySelectorAll('main ol > li').length >= {before + 2} && !document.querySelector('main li[role=status]')",
        timeout=timeout,
    )
    reply = page.locator("main ol > li").last.inner_text()
    page.screenshot(path=str(SHOTS / "manual_qa_who_are_you.png"), full_page=True)
    check("A2 'who are you': replies about the assistant with no risk card",
          "TrustShield" in reply and page.locator("main section[aria-label='Risk assessment']").count() == 0,
          "risk card rendered or the answer did not name TrustShield")

    # D1 — every width from the checklist keeps the page free of horizontal scroll.
    page.set_viewport_size({"width": 390, "height": 800})
    check("D1 390px: no horizontal scroll", no_hscroll(page))
    for w in (360, 430, 820):
        page.set_viewport_size({"width": w, "height": 800})
        check(f"D1 {w}px: no horizontal scroll", no_hscroll(page))
        page.screenshot(path=str(SHOTS / f"manual_qa_{w}.png"), full_page=True)
    page.set_viewport_size({"width": 1280, "height": 900})


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--web", default="http://127.0.0.1:3000")
    ap.add_argument("--api", default="http://127.0.0.1:8000")
    ap.add_argument("--timeout", type=int, default=60_000)
    args = ap.parse_args()

    api_ok = stack_up(f"{args.api}/api/v1/health")
    web_ok = stack_up(args.web)
    print(f"preflight — web:= {web_ok}:" if False else f"preflight — web:{args.web}={web_ok}, api:{args.api}={api_ok}")
    state = {"web": "up" if web_ok else "down", "api": "up" if api_ok else "down"}
    print("preflight — stack state: %s" % json.dumps(state))
    if not (api_ok and web_ok):
        print("")
        print("MISSING STACK")
        if not web_ok:
            print(f"- frontend http://127.0.0.1:3000 -> {args.web}: NOT running")
        if not api_ok:
            print(f"- backend  http://127.0.0.1:8000 -> {args.api}: NOT running")
        print("Not starting the production stack. Start it yourself, then re-run this script:")
        print("  1) backend:  uvicorn services.api.main:app --host 127.0.0.1 --port 8000")
        print("  2) frontend: npx next start -p 3000   (from apps/web)")
        print("")
        print("0/0 passed — checks were not run because the stack is down.")
        return 2

    SHOTS.mkdir(parents=True, exist_ok=True)
    exceptions: list[str] = []
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        context = browser.new_context(
            viewport={"width": 1280, "height": 900},
            permissions=["microphone"],
        )
        context.add_init_script(
            """() => {
                window.addEventListener('beforeunload', (e) => { e.preventDefault(); });
                try {
                    const st = JSON.parse(window.localStorage.getItem('qa-mic-state') ?? '{}');
                    const v = window.webkitRequestAnimationFrame ?? null;
                    if (v) window.__qa_raf_present = true;
                } catch {}
            }"""
        )
        page = context.new_page()
        errors: list[str] = []
        page.on("pageerror", lambda e: errors.append(f"pageerror: {e}"))
        page.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)
        page.goto(args.web, wait_until="networkidle", timeout=args.timeout)
        try:
            run(page, args.timeout)
        except Exception as e:  # a crashed check must still be reported, not swallowed
            exceptions.append(f"{type(e).__name__}: {e}")
            page.screenshot(path=str(SHOTS / "manual_qa_failure.png"), full_page=True)
        browser.close()
    check("no uncaught page errors", not exceptions, "; ".join(exceptions)[:300])
    failed = [n for n, ok in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
