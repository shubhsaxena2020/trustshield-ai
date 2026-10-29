"""Browser coverage for the TrustShield web UI. External services are isolated with deterministic test doubles."""
from __future__ import annotations

import base64
import json
import os
from pathlib import Path

import pytest
from playwright.sync_api import Browser, Page, Route, TimeoutError as PlaywrightTimeoutError, sync_playwright

WEB_URL = os.environ.get("TRUSTSHIELD_WEB_URL", "http://localhost:3100").rstrip("/")
CHROMIUM = os.environ.get("TRUSTSHIELD_CHROMIUM_EXECUTABLE")
CHECK_DIR = Path(os.environ.get("TRUSTSHIELD_WEB_CHECK_DIR", r"C:\Users\shubh\Documents\TrustShield_WebFinal_Check"))
TEST_EMAIL = "throwaway-web-final@example.com"
TEST_PASSWORD = "Expo-only-test-password-47"


@pytest.fixture(scope="module")
def browser() -> Browser:
    with sync_playwright() as playwright:
        launch = {"headless": True}
        if CHROMIUM:
            launch["executable_path"] = CHROMIUM
        instance = playwright.chromium.launch(**launch)
        yield instance
        instance.close()


def dismiss_gate(page: Page) -> None:
    gate = page.get_by_role("dialog")
    try:
        gate.wait_for(state="visible", timeout=5000)
    except PlaywrightTimeoutError:
        return
    gate.get_by_text("Skip for now", exact=True).click()
    gate.wait_for(state="hidden")


def open_anonymous_chat(page: Page) -> None:
    response = page.goto(f"{WEB_URL}/", wait_until="domcontentloaded")
    assert response and response.status == 200
    dismiss_gate(page)
    page.locator(".ts-col:not([inert]) textarea[aria-label='Message']").wait_for(state="visible")


def capture(page: Page, name: str) -> None:
    CHECK_DIR.mkdir(parents=True, exist_ok=True)
    page.screenshot(path=str(CHECK_DIR / name), full_page=True)


def test_routes_and_mobile_widths(browser: Browser) -> None:
    metrics = {}
    for width, height in ((1440, 900), (390, 844), (360, 740)):
        page = browser.new_page(viewport={"width": width, "height": height})
        page.add_init_script("""
          window.__webFinalVitals = { lcpMs: null, cls: 0 };
          new PerformanceObserver(entries => {
            for (const entry of entries.getEntries()) window.__webFinalVitals.lcpMs = entry.startTime;
          }).observe({ type: 'largest-contentful-paint', buffered: true });
          new PerformanceObserver(entries => {
            for (const entry of entries.getEntries()) if (!entry.hadRecentInput) window.__webFinalVitals.cls += entry.value;
          }).observe({ type: 'layout-shift', buffered: true });
        """)
        for route, heading in (("/", None), ("/login", "Log in"), ("/signup", "Sign up")):
            response = page.goto(f"{WEB_URL}{route}", wait_until="domcontentloaded")
            assert response and response.status == 200, f"{route} failed at {width}px"
            if heading:
                assert page.get_by_role("heading", name=heading).is_visible()
            if route == "/":
                if width == 1440:
                    capture(page, "desktop-home-gate-final.png")
                elif width == 390:
                    capture(page, "mobile-home-gate-final.png")
                dismiss_gate(page)
                page.get_by_role("textbox", name="Message").wait_for(state="visible")
                page.wait_for_timeout(1500)
                capture(page, {1440: "desktop-home-final.png", 390: "mobile-home-final.png", 360: "mobile-home-360-final.png"}[width])
                if width == 1440:
                    metrics = page.evaluate("""() => ({
                      lcpMs: window.__webFinalVitals.lcpMs,
                      cls: window.__webFinalVitals.cls,
                      firstLoadJsBytes: performance.getEntriesByType('resource').filter(e => /\\/_next\\/static\\/.*\\.js(?:\\?|$)/.test(e.name)).reduce((s, e) => s + e.transferSize, 0)
                    })""")
            else:
                capture(page, f"{'desktop' if width == 1440 else 'mobile' if width == 390 else 'mobile-360'}-{route[1:]}-final.png")
            widths = page.evaluate("() => ({scroll: document.documentElement.scrollWidth, client: innerWidth})")
            assert widths["scroll"] <= widths["client"] + 1, f"horizontal overflow at {width}px on {route}: {widths}"
        page.close()
    (CHECK_DIR / "local-performance.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")


def analysis_payload(*, qr: str | None = None) -> dict:
    return {
        "contract_version": "2.0",
        "analysis_id": "browser-test-analysis",
        "timestamp": "2026-10-29T00:00:00Z",
        "input_types": ["qr"] if qr else ["text"],
        "risk_score": 78 if qr else 68,
        "risk_level": "suspicious",
        "confidence": "medium",
        "primary_category": "qr_payment_request" if qr else "credential_theft",
        "modules_used": ["rules", "qr"] if qr else ["rules"],
        "evidence": [],
        "extracted_content": qr or "",
        "urls": [],
        "qr_payload": qr,
        "qr_payload_type": "upi" if qr else None,
        "warnings": [],
        "uncertainty": [],
        "safe_actions": ["Do not share an OTP."],
        "capabilities": [],
        "ocr_confidence": None,
    }


def mock_stream(page: Page, answer: str) -> None:
    def reply(route: Route) -> None:
        message = route.request.post_data_json["message"]
        risk = analysis_payload()
        events = [
            {"type": "meta", "kind": "chat", "response_type": "analysis", "intent": {"intent": "analyze_text", "reason": "test", "urls": [], "followup_intent": None}},
            {"type": "analysis", "analysis": risk, "signature": "test-signature", "route": {"kind": "text", "reason": "test", "urls": [], "intent": "analyze_text", "has_attachment": False, "modules": ["rules"]}},
            {"type": "token", "text": answer.format(message=message)},
            {"type": "done"},
        ]
        body = "\n\n".join("data: " + json.dumps(event, ensure_ascii=False) for event in events) + "\n\n"
        route.fulfill(status=200, content_type="text/event-stream", body=body)

    page.route("**/api/v1/chat/stream", reply)


@pytest.mark.parametrize(
    ("message", "answer", "quoted"),
    [
        ("URGENT: bank account suspended. Verify at secure-bank-login.top", "Risk indicators found in “{message}”. This is not a guarantee of fraud.", True),
        ("The library closes at 6 pm today.", "No obvious scam indicators were found. This is not a guarantee that the message is safe.", False),
        ("आपका बैंक खाता बंद हो जाएगा। अभी OTP भेजें।", "हिंदी संदेश की जाँच पूरी हुई। OTP साझा न करें।", False),
        ("KYC update nahi kiya toh account band hoga, OTP bhejo abhi", "Hinglish message checked: OTP साझा न करें and verify the sender.", False),
    ],
)
def test_text_checks_render_quotes_and_language(browser: Browser, message: str, answer: str, quoted: bool) -> None:
    page = browser.new_page(viewport={"width": 390, "height": 844})
    open_anonymous_chat(page)
    mock_stream(page, answer)
    page.get_by_role("textbox", name="Message").fill(message)
    send = page.get_by_role("button", name="Send")
    send.wait_for(state="visible")
    page.wait_for_function("() => !document.querySelector('button[aria-label=Send]')?.disabled")
    send.click()
    page.get_by_text(answer.format(message=message), exact=False).wait_for(timeout=15000)
    if quoted:
        assert page.get_by_text(f'“{message}”', exact=False).is_visible()
    page.close()


def mock_upload(page: Page, *, qr: str | None = None) -> list[int]:
    counts: list[int] = []

    def reply(route: Route) -> None:
        body = route.request.post_data_buffer or b""
        count = body.count(b"filename=")
        counts.append(count)
        result = analysis_payload(qr=qr)
        text = "The QR requests INR 125.50 for Coffee House. Verify both before approving." if qr else "Received the uploaded image for review."
        payload = {
            "kind": "analysis", "response_type": "analysis", "intent": {"intent": "analyze_image", "reason": "test", "urls": [], "followup_intent": None},
            "route": {"kind": "image", "reason": "test", "urls": [], "intent": "image", "has_attachment": True, "modules": ["qr"] if qr else ["ocr"]},
            "reply": {"text": text, "cited_evidence": [], "provider": "test", "grounded": True},
            "analysis": result, "context_signature": "test-signature", "attachments": [],
        }
        route.fulfill(status=200, content_type="application/json", body=json.dumps(payload, ensure_ascii=False))

    page.route("**/api/v1/chat/upload", reply)
    return counts


def attach_files(page: Page, files: list[dict]) -> None:
    page.get_by_role("button", name="Attach", exact=True).click()
    page.locator('input[type="file"]').set_input_files(files)


def test_image_upload_and_four_file_message(browser: Browser) -> None:
    page = browser.new_page(viewport={"width": 390, "height": 844})
    open_anonymous_chat(page)
    counts = mock_upload(page)
    one_pixel_png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/XioAAAAASUVORK5CYII=")
    attach_files(page, [{"name": "screenshot.png", "mimeType": "image/png", "buffer": one_pixel_png}])
    page.wait_for_function("() => !document.querySelector('button[aria-label=Send]')?.disabled")
    page.get_by_role("button", name="Send").click()
    page.get_by_text("Received the uploaded image for review.", exact=False).wait_for(timeout=15000)
    assert counts == [1]
    page.close()

    page = browser.new_page(viewport={"width": 390, "height": 844})
    open_anonymous_chat(page)
    counts = mock_upload(page)
    files = [{"name": f"fixture-{index}.txt", "mimeType": "text/plain", "buffer": f"fixture {index}".encode()} for index in range(1, 5)]
    attach_files(page, files)
    for index in range(1, 5):
        assert page.get_by_text(f"fixture-{index}.txt", exact=True).is_visible()
    page.wait_for_function("() => !document.querySelector('button[aria-label=Send]')?.disabled")
    page.get_by_role("button", name="Send").click()
    page.get_by_text("Received the uploaded image for review.", exact=False).wait_for(timeout=15000)
    assert counts == [4]
    page.close()


def test_dark_qr_upload_renders_payee_and_amount(browser: Browser) -> None:
    page = browser.new_page(viewport={"width": 390, "height": 844})
    open_anonymous_chat(page)
    dark_qr = Path(r"C:\Users\shubh\AppData\Local\Temp\claude\c--Users-shubh-c-language\a1acd1ec-a1aa-4a9e-a1e8-1b774c6a9f54\scratchpad\multi")
    fixture = dark_qr / "phonepe_dark.png" if dark_qr.is_dir() and (dark_qr / "phonepe_dark.png").is_file() else None
    png = fixture.read_bytes() if fixture else base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/XioAAAAASUVORK5CYII=")
    counts = mock_upload(page, qr="upi://pay?pa=coffee@upi&pn=Coffee%20House&am=125.50&cu=INR")
    attach_files(page, [{"name": fixture.name if fixture else "dark-inverted-qr.png", "mimeType": "image/png", "buffer": png}])
    page.wait_for_function("() => !document.querySelector('button[aria-label=Send]')?.disabled")
    page.get_by_role("button", name="Send").click()
    page.get_by_text("Coffee House", exact=False).wait_for(timeout=15000)
    page.get_by_text("The QR requests INR 125.50 for Coffee House. Verify both before approving.", exact=True).wait_for(timeout=5000)
    assert counts == [1]
    page.close()


def auth_routes(page: Page) -> dict[str, int]:
    counts = {"signup": 0, "password": 0, "logout": 0}
    user = {
        "id": "throwaway-user", "aud": "authenticated", "role": "authenticated", "email": TEST_EMAIL,
        "email_confirmed_at": "2026-10-29T00:00:00Z", "app_metadata": {"provider": "email", "providers": ["email"]},
        "user_metadata": {}, "identities": [], "created_at": "2026-10-29T00:00:00Z",
    }
    session = {"access_token": "test-access-token", "token_type": "bearer", "expires_in": 3600,
               "expires_at": 1893456000, "refresh_token": "test-refresh-token", "user": user}

    def handle(route: Route) -> None:
        url = route.request.url
        if url.endswith("/auth/v1/signup"):
            counts["signup"] += 1
            route.fulfill(status=200, content_type="application/json", body=json.dumps(session))
        elif "/auth/v1/token" in url:
            counts["password"] += 1
            route.fulfill(status=200, content_type="application/json", body=json.dumps(session))
        elif "/auth/v1/logout" in url:
            counts["logout"] += 1
            route.fulfill(status=204, body="")
        elif url.endswith("/auth/v1/user"):
            route.fulfill(status=200, content_type="application/json", body=json.dumps(user))
        else:
            route.abort()

    page.route("**/auth/v1/**", handle)
    page.route("**/api/v1/history", lambda route: route.fulfill(status=200, content_type="application/json", body=json.dumps([
        {"id": "scan-1", "input_type": "text", "input_summary": "Text scan", "risk_score": 68, "risk_label": "suspicious", "created_at": "2026-10-29T10:00:00Z"}
    ])))
    return counts


def test_throwaway_signup_login_history_and_logout(browser: Browser) -> None:
    page = browser.new_page(viewport={"width": 1440, "height": 900})
    counts = auth_routes(page)
    page.goto(f"{WEB_URL}/signup", wait_until="domcontentloaded")
    page.get_by_label("Email").fill(TEST_EMAIL)
    page.get_by_label("Password").fill(TEST_PASSWORD)
    page.get_by_role("button", name="Sign up").click()
    try:
        page.wait_for_url(f"{WEB_URL}/", timeout=10000)
    except PlaywrightTimeoutError as error:
        alert = page.get_by_role("alert").all_text_contents()
        capture(page, "signup-test-failure.png")
        raise AssertionError(f"throwaway signup did not redirect: mocked_auth={counts}; alerts={alert}; path={page.url}") from error
    page.get_by_text(TEST_EMAIL).first.wait_for(timeout=10000)
    page.get_by_text("Text scan", exact=False).wait_for(timeout=10000)
    capture(page, "desktop-signed-in-history-final.png")
    with page.expect_response("**/auth/v1/logout**"):
        page.get_by_role("button", name="Log out").click()
    page.wait_for_function("() => !Object.keys(localStorage).some(key => key.startsWith('sb-') && key.endsWith('-auth-token'))")
    page.goto(f"{WEB_URL}/login", wait_until="domcontentloaded")
    page.get_by_label("Email").fill(TEST_EMAIL)
    page.get_by_label("Password").fill(TEST_PASSWORD)
    page.get_by_role("button", name="Log in").click()
    try:
        page.wait_for_url(f"{WEB_URL}/", timeout=10000)
    except PlaywrightTimeoutError as error:
        capture(page, "login-test-failure.png")
        raise AssertionError(f"throwaway login did not redirect: mocked_auth={counts}; alerts={page.get_by_role('alert').all_text_contents()}; path={page.url}") from error
    page.get_by_text(TEST_EMAIL).first.wait_for(timeout=10000)
    assert counts == {"signup": 1, "password": 1, "logout": 1}
    page.close()
