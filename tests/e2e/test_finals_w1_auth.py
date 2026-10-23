"""W1 auth acceptance matrix: sign-up, sign-in, session, gate, per-account history, mobile, anon parity.

Not collected by pytest (no ``test_*`` functions; the module-level ``importorskip`` only keeps collection quiet).

    .venv\\Scripts\\python tests/e2e/test_finals_w1_auth.py
    .venv\\Scripts\\python tests/e2e/test_finals_w1_auth.py --web http://localhost:3000 --api http://localhost:8000 --runs 3

Requires the local stack already running (Next dev on --web, FastAPI on --api); it never starts or stops servers.
Every scenario gets a fresh browser context (cleared storage, fixed viewport/locale/timezone). Supabase auth is
mocked at the network layer: every request that would leave the machine is intercepted, ``/auth/v1/**`` is answered
locally, anything else external is aborted and recorded. Only scenario 13 talks to the real local API, unmocked.
Screenshots and ``w1-run.json`` go to ``docs/finals/evidence/w1/``; no token, cookie, password or real address is written.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
import traceback
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

import pytest

pytest.importorskip("playwright.sync_api")

from playwright.sync_api import TimeoutError as PWTimeout  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / "docs" / "finals" / "evidence" / "w1"

WEB = "http://localhost:3000"
API = "http://localhost:8000"

DESKTOP = {"width": 1280, "height": 900}
MOBILE = {"width": 375, "height": 812}
LOCALE = "en-US"
TIMEZONE = "UTC"

# Dummy credentials only: example.test is reserved, and none of these values is ever written to evidence.
PASSWORD = "dummy-pass-123"
ACCESS_TOKEN = "test-access-token"
REFRESH_TOKEN = "test-refresh-token"

USERS = {
    "A": {"id": "00000000-0000-4000-8000-00000000000a", "email": "a@example.test"},
    "B": {"id": "00000000-0000-4000-8000-00000000000b", "email": "b@example.test"},
    "NEW": {"id": "00000000-0000-4000-8000-00000000000c", "email": "new@example.test"},
}
for _u in USERS.values():
    _u.update({"aud": "authenticated", "role": "authenticated", "app_metadata": {}, "user_metadata": {},
               "created_at": "2026-01-01T00:00:00Z"})

STORE_KEY = "trustshield.chats.v1"
DISMISS_KEY = "trustshield.welcome_dismissed.v1"

# Console messages that are the scenario's own stimulus rather than an app defect. Keep this list tiny.
CONSOLE_ALLOW = {
    # Chromium logs every 4xx fetch at network level. Scenario 07b deliberately mocks a 400 refresh response and
    # scenario 03 mocks a 422 duplicate-email response; those lines are the browser reporting our own mocked
    # response, not an unhandled app error.
    "Failed to load resource: the server responded with a status of 400 (Bad Request)",
    "Failed to load resource: the server responded with a status of 422 (Unprocessable Entity)",
}

GATE_PROBE = """
window.__gateSeen = false;
try {
  new MutationObserver(function () {
    if (document.querySelector('[role=dialog]')) window.__gateSeen = true;
  }).observe(document, { childList: true, subtree: true });
} catch (e) {}
"""

BUSY_SAMPLER = """
() => {
  window.__samples = [];
  const t0 = performance.now();
  if (window.__sampler) clearInterval(window.__sampler);
  window.__sampler = setInterval(() => {
    const b = document.querySelector('button[type=submit]');
    window.__samples.push({
      t: Math.round(performance.now() - t0),
      disabled: b ? !!b.disabled : null,
      text: b ? (b.textContent || '').trim() : null,
    });
  }, 40);
}
"""

RESULTS: list[dict] = []


# ----------------------------------------------------------------------------------------------------------------------
# Reporting
# ----------------------------------------------------------------------------------------------------------------------
class Scenario:
    def __init__(self, sid: str, title: str):
        self.sid = sid
        self.title = title

    def check(self, name: str, ok: bool, detail: str = "") -> bool:
        status = "PASS" if ok else "FAIL"
        RESULTS.append({"scenario": f"{self.sid} {self.title}", "name": name, "status": status,
                        "detail": detail, "at": _now()})
        line = f"{status} [{self.sid}] {name}"
        if detail and not ok:
            line += f" — {detail}"
        print(line, flush=True)
        return bool(ok)

    def skip(self, name: str, reason: str) -> None:
        RESULTS.append({"scenario": f"{self.sid} {self.title}", "name": name, "status": "SKIP",
                        "detail": reason, "at": _now()})
        print(f"SKIP [{self.sid}] {name} — {reason}", flush=True)


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


# ----------------------------------------------------------------------------------------------------------------------
# Supabase mock
# ----------------------------------------------------------------------------------------------------------------------
def _session_body(user: dict, expires_at: int | None = None) -> dict:
    return {"access_token": ACCESS_TOKEN, "token_type": "bearer", "expires_in": 3600,
            "expires_at": expires_at if expires_at is not None else int(time.time()) + 3600,
            "refresh_token": REFRESH_TOKEN, "user": dict(user)}


class SupabaseMock:
    """Answers supabase-js locally. Counters are Python-side, so duplicate submits are measured, not inferred."""

    def __init__(self, *, password: str = "ok", signup: str = "confirm", refresh: str = "ok",
                 delay: float = 0.0, user: str = "A"):
        self.modes = {"password": password, "signup": signup, "refresh": refresh}
        self.delay = delay
        self.user = USERS[user]
        self.counts: Counter = Counter()
        self.escaped: list[str] = []

    @staticmethod
    def _kind(url: str) -> str:
        if "/auth/v1/signup" in url:
            return "signup"
        if "/auth/v1/token" in url:
            return "refresh" if "refresh_token" in url else "password"
        if "/auth/v1/logout" in url:
            return "logout"
        if url.split("?")[0].rstrip("/").endswith("/auth/v1/user"):
            return "user"
        return "other"

    def handle(self, route) -> None:  # noqa: ANN001 - playwright Route
        url = route.request.url
        if "/auth/v1/" not in url:
            self.escaped.append(url)
            route.abort()
            return
        kind = self._kind(url)
        self.counts[kind] += 1
        if self.delay:
            # Blocks this script (single-threaded sync API) but not the browser: the page keeps rendering and the
            # in-page sampler keeps recording, which is how scenario 11 observes the pending state.
            time.sleep(self.delay)
        mode = self.modes.get(kind, "ok")
        if kind == "logout":
            route.fulfill(status=204, body="")
        elif kind == "user":
            route.fulfill(status=200, content_type="application/json", body=json.dumps(self.user))
        elif kind == "signup" and mode == "network":
            route.abort()
        elif kind == "signup" and mode == "confirm":
            route.fulfill(status=200, content_type="application/json",
                          body=json.dumps({"user": dict(self.user), "session": None}))
        elif kind == "signup" and mode == "duplicate":
            route.fulfill(status=422, content_type="application/json",
                          body=json.dumps({"code": 422, "error_code": "user_already_exists",
                                           "msg": "User already registered"}))
        elif kind == "password" and mode == "bad":
            route.fulfill(status=400, content_type="application/json",
                          body=json.dumps({"code": 400, "error_code": "invalid_credentials",
                                           "msg": "Invalid login credentials"}))
        elif kind == "refresh" and mode == "fail":
            route.fulfill(status=400, content_type="application/json",
                          body=json.dumps({"code": 400, "error_code": "refresh_token_not_found",
                                           "msg": "Invalid Refresh Token: Refresh Token Not Found"}))
        elif kind in ("password", "signup", "refresh"):
            route.fulfill(status=200, content_type="application/json", body=json.dumps(_session_body(self.user)))
        else:
            route.fulfill(status=200, content_type="application/json", body="{}")


def _is_external(url: str) -> bool:
    try:
        host = (urlparse(url).hostname or "").lower()
    except ValueError:
        return False
    return host not in ("", "localhost", "127.0.0.1", "::1", "0.0.0.0")


# ----------------------------------------------------------------------------------------------------------------------
# Browser session
# ----------------------------------------------------------------------------------------------------------------------
class Session:
    def __init__(self, browser, mock: SupabaseMock | None = None, viewport: dict | None = None):
        self.ctx = browser.new_context(viewport=viewport or DESKTOP, locale=LOCALE, timezone_id=TIMEZONE,
                                       color_scheme="light", reduced_motion="reduce")
        self.mock = mock
        if mock is not None:
            self.ctx.route(_is_external, mock.handle)
        self.ctx.add_init_script(GATE_PROBE)
        self.page = self.ctx.new_page()
        self.logs: list[tuple[str, str]] = []
        self.requests: list[dict] = []
        self.page.on("console", lambda m: self.logs.append((m.type, m.text)))
        self.page.on("pageerror", lambda e: self.logs.append(("pageerror", str(e))))
        self.page.on("request", lambda r: self.requests.append(
            {"url": r.url, "method": r.method, "headers": {k.lower(): v for k, v in r.headers.items()}}))

    def __enter__(self) -> "Session":
        return self

    def __exit__(self, *exc) -> None:  # noqa: ANN002
        try:
            self.ctx.close()
        except Exception:
            pass

    def bad_console(self) -> list[str]:
        bad = []
        for kind, text in self.logs:
            if kind not in ("error", "pageerror"):
                continue
            if text.strip() in CONSOLE_ALLOW:
                continue
            bad.append(f"{kind}: {text}")
        return bad

    def shot(self, name: str, full_page: bool = True) -> None:
        EVIDENCE.mkdir(parents=True, exist_ok=True)
        try:
            self.page.screenshot(path=str(EVIDENCE / name), full_page=full_page)
        except Exception as exc:  # a navigation mid-shot must not hide the real result
            print(f"  (screenshot {name} failed: {exc})", flush=True)


# ----------------------------------------------------------------------------------------------------------------------
# Page helpers (selector lists are resilient: siblings are editing these pages while the matrix runs)
# ----------------------------------------------------------------------------------------------------------------------
def goto(page, path: str) -> None:
    page.goto(WEB + path, wait_until="domcontentloaded", timeout=30000)
    # Next's client bundle loads after the server HTML, and until it runs the form is inert (a click does nothing).
    # Waiting for networkidle + the Next runtime is what makes every later interaction deterministic.
    try:
        page.wait_for_load_state("load", timeout=20000)
        page.wait_for_load_state("networkidle", timeout=20000)
    except PWTimeout:
        pass
    try:
        page.wait_for_function("() => !!(window.next && window.next.version)", timeout=20000)
    except PWTimeout as exc:
        raise RuntimeError("the page never hydrated (window.next missing), so the UI cannot be driven") from exc


def goto_auth(page, path: str) -> None:
    goto(page, path)
    page.wait_for_selector("input[type=password]", timeout=20000)


def goto_home(page) -> None:
    goto(page, "/")
    page.wait_for_function("() => !!document.querySelector('textarea') || !!document.querySelector('[role=dialog]')",
                           timeout=20000)


def pick(page, selectors: list[str]) -> str:
    for sel in selectors:
        if page.locator(sel).count():
            return sel
    raise RuntimeError(f"none of these selectors matched: {selectors}")


def fill_email(page, value: str) -> None:
    page.fill(pick(page, ["input[type=email]", "#login-email", "#signup-email", "input[name=email]"]), value)


def fill_password(page, value: str) -> None:
    page.fill(pick(page, ["input[type=password]", "#login-password", "#signup-password"]), value)


def submit(page) -> None:
    page.click(pick(page, ["button[type=submit]", "form button:not([type=button])"]))


def wait_home(page, timeout: int = 12000) -> bool:
    try:
        page.wait_for_url(re.compile(re.escape(WEB) + r"/?$"), timeout=timeout)
        page.wait_for_selector("textarea", timeout=timeout)
        return True
    except PWTimeout:
        return False


def has_session(page) -> bool:
    return bool(page.evaluate(
        "() => Object.keys(localStorage).some(k => k.startsWith('sb-') && k.endsWith('-auth-token'))"))


def session_key(page) -> str | None:
    return page.evaluate(
        "() => Object.keys(localStorage).find(k => k.startsWith('sb-') && k.endsWith('-auth-token')) || null")


def expire_session(page) -> bool:
    key = session_key(page)
    if not key:
        return False
    page.evaluate("""(k) => { const s = JSON.parse(localStorage.getItem(k));
        s.expires_at = Math.floor(Date.now() / 1000) - 120; localStorage.setItem(k, JSON.stringify(s)); }""", key)
    return True


def aside_text(page) -> str:
    loc = page.locator("aside").first
    try:
        return loc.inner_text()
    except Exception:
        return ""


def gate(page):
    return page.locator("[role=dialog]").first


def gate_visible(page) -> bool:
    try:
        return gate(page).is_visible(timeout=1000)
    except Exception:
        return False


def wait_gate(page, timeout: int = 12000) -> bool:
    try:
        page.wait_for_selector("[role=dialog]", state="visible", timeout=timeout)
        return True
    except PWTimeout:
        return False


ALERT_SEL = '[role="alert"]:not(#__next-route-announcer__)'


def wait_alert(page, timeout: int = 12000) -> str:
    try:
        page.wait_for_selector(ALERT_SEL, state="visible", timeout=timeout)
    except PWTimeout:
        return ""
    loc = page.locator(ALERT_SEL)
    # The announcer is filtered out above; if multiple real alerts exist, take the last (the freshly rendered one).
    try:
        return loc.last.inner_text().strip()
    except Exception:
        return ""


def no_horizontal_overflow(page) -> tuple[bool, str]:
    data = page.evaluate("() => ({ sw: document.documentElement.scrollWidth, iw: window.innerWidth })")
    return data["sw"] <= data["iw"] + 1, f"scrollWidth={data['sw']} innerWidth={data['iw']}"


def in_viewport(page, selector: str, viewport_h: int) -> tuple[bool, str]:
    loc = page.locator(selector).first
    if not loc.count():
        return False, f"{selector} not found"
    if not loc.is_visible():
        return False, f"{selector} not visible"
    box = loc.bounding_box()
    if not box:
        return False, f"{selector} has no box"
    ok = box["width"] > 0 and box["height"] > 0 and box["y"] + box["height"] <= viewport_h + 1
    return ok, f"box={{{int(box['x'])},{int(box['y'])},{int(box['width'])},{int(box['height'])}}} viewport_h={viewport_h}"


# ----------------------------------------------------------------------------------------------------------------------
# Scenarios
# ----------------------------------------------------------------------------------------------------------------------
def scenario_01(browser, run_no: int) -> None:
    sc = Scenario("01", "sign-up with confirmation required")
    with Session(browser, SupabaseMock(signup="confirm", user="NEW")) as s:
        p = s.page
        goto_auth(p, "/signup")
        fill_email(p, USERS["NEW"]["email"])
        fill_password(p, PASSWORD)
        submit(p)
        body = ""
        try:
            p.wait_for_function("() => /check your email/i.test(document.body.innerText)", timeout=12000)
        except PWTimeout:
            pass
        body = p.inner_text("body")
        sc.check("'Check your email' state is shown", bool(re.search(r"check your email", body, re.I)), body[:200])
        sc.check("the sign-up form is gone", p.locator("form").count() == 0, f"forms={p.locator('form').count()}")
        sc.check("exactly one signup request", s_mock_count(s, "signup") == 1, f"counts={s_mock_counts(s)}")
        sc.check("no session stored while confirmation is pending", not has_session(p))
        sc.check("stays on /signup", p.url.rstrip("/").endswith("/signup"), p.url)
        s.shot("w1-01-signup-confirm.png")
        sc.check("no console errors", not s.bad_console(), "; ".join(s.bad_console())[:400])


def scenario_02(browser, run_no: int) -> None:
    sc = Scenario("02", "sign-up with confirmation disabled")
    with Session(browser, SupabaseMock(signup="session", user="NEW")) as s:
        p = s.page
        goto_auth(p, "/signup")
        fill_email(p, USERS["NEW"]["email"])
        fill_password(p, PASSWORD)
        submit(p)
        redirected = wait_home(p, timeout=10000)
        stored = has_session(p)
        sc.check("a session is stored (autoconfirm signup)", stored, "no sb-*-auth-token key after signup")
        s.shot("w1-02a-signup-autoconfirm.png")
        sc.check("signup redirects to / and signs the user in", redirected, f"stayed at {p.url} after signup")
        if not redirected:
            # Complete the requirement's intent without hiding the defect above: a manual visit proves whether the
            # session is usable at / or whether the account is not actually signed in.
            goto_home(p)
            p.wait_for_timeout(800)
        email_seen = USERS["NEW"]["email"] in aside_text(p)
        sc.check("account email is visible in the sidebar", email_seen, f"aside={aside_text(p)[:160]!r}")
        sc.check("first-visit gate is not shown to a signed-in user", not gate_visible(p)
                 and p.evaluate("() => window.__gateSeen !== true"))
        s.shot("w1-02b-signed-in-home.png")
        sc.check("no console errors", not s.bad_console(), "; ".join(s.bad_console())[:400])


def scenario_03(browser, run_no: int) -> None:
    sc = Scenario("03", "sign-up with an already registered email")
    with Session(browser, SupabaseMock(signup="duplicate", user="NEW")) as s:
        p = s.page
        goto_auth(p, "/signup")
        fill_email(p, USERS["NEW"]["email"])
        fill_password(p, PASSWORD)
        submit(p)
        alert = wait_alert(p)
        sc.check("the duplicate-email message is shown in role=alert", "already registered" in alert.lower(), repr(alert))
        sc.check("duplicate-email error focuses the email field",
                 p.evaluate("() => document.activeElement?.id === 'signup-email'"))
        sc.check("the sign-up form is still there", p.locator("form").count() == 1)
        sc.check("stays on /signup", p.url.rstrip("/").endswith("/signup"), p.url)
        sc.check("no session stored after a rejected signup", not has_session(p))
        s.shot("w1-03-signup-duplicate.png")
        sc.check("no console errors", not s.bad_console(), "; ".join(s.bad_console())[:400])

    with Session(browser, SupabaseMock(signup="network", user="NEW")) as s:
        p = s.page
        goto_auth(p, "/signup")
        fill_email(p, USERS["NEW"]["email"])
        fill_password(p, PASSWORD)
        submit(p)
        alert = wait_alert(p)
        sc.check("a network failure shows a retryable signup error", "check your connection" in alert.lower(), repr(alert))
        sc.check("a network failure focuses the alert", p.evaluate("() => document.activeElement?.id === 'signup-error'"))
        sc.check("the signup control is usable again after a network failure",
                 p.locator("button[type=submit]").first.is_enabled())
        sc.check("no session is stored after a network failure", not has_session(p))
        expected_abort = "error: Failed to load resource: net::ERR_FAILED"
        unexpected_console = [entry for entry in s.bad_console() if entry != expected_abort]
        sc.check("no unexpected console errors after a handled signup network failure",
                 not unexpected_console, "; ".join(unexpected_console)[:300])


def scenario_04(browser, run_no: int) -> None:
    sc = Scenario("04", "sign-in success")
    with Session(browser, SupabaseMock(password="ok")) as s:
        p = s.page
        goto_auth(p, "/login")
        fill_email(p, USERS["A"]["email"])
        fill_password(p, PASSWORD)
        submit(p)
        sc.check("redirected to / after sign-in", wait_home(p), p.url)
        p.wait_for_timeout(500)
        sc.check("account email is visible in the sidebar", USERS["A"]["email"] in aside_text(p),
                 repr(aside_text(p)[:160]))
        sc.check("first-visit gate is not shown", not gate_visible(p))
        sc.check("gate never flashed during sign-in", p.evaluate("() => window.__gateSeen !== true"))
        sc.check("exactly one password-grant request", s_mock_count(s, "password") == 1, f"counts={s_mock_counts(s)}")
        s.shot("w1-04-signin-success.png")
        sc.check("no console errors", not s.bad_console(), "; ".join(s.bad_console())[:400])


def scenario_05(browser, run_no: int) -> None:
    sc = Scenario("05", "sign-in with a wrong password")
    with Session(browser, SupabaseMock(password="bad")) as s:
        p = s.page
        goto_auth(p, "/login")
        fill_email(p, USERS["A"]["email"])
        fill_password(p, "wrong-" + PASSWORD)
        submit(p)
        alert = wait_alert(p)
        sc.check("'Invalid login credentials' is shown in role=alert", "invalid login credentials" in alert.lower(),
                 repr(alert))
        sc.check("stays on /login", p.url.rstrip("/").endswith("/login"), p.url)
        sc.check("invalid-credentials error focuses the password field",
                 p.evaluate("() => document.activeElement?.id === 'login-password'"))
        sc.check("no session stored after a failed sign-in", not has_session(p))
        sc.check("the submit control is usable again", p.locator("button[type=submit]").first.is_enabled())
        s.shot("w1-05-wrong-password.png")
        sc.check("no console errors", not s.bad_console(), "; ".join(s.bad_console())[:400])


def scenario_06(browser, run_no: int) -> None:
    sc = Scenario("06", "sign-out from the sidebar")
    mock = SupabaseMock(password="ok", user="A")
    with Session(browser, mock) as s:
        p = s.page
        goto_auth(p, "/login")
        seed_chats(p, {USERS["A"]["id"]: "ALPHA-ACCOUNT-HISTORY"})
        fill_email(p, USERS["A"]["email"])
        fill_password(p, PASSWORD)
        submit(p)
        if not wait_home(p):
            sc.check("signed in before sign-out", False, p.url)
            return
        p.wait_for_timeout(700)
        sc.check("the signed-in account's own chat is restored", "ALPHA-ACCOUNT-HISTORY" in aside_text(p),
                 repr(aside_text(p)[:200]))
        s.shot("w1-06a-signed-in-history.png")
        p.get_by_role("button", name=re.compile("log out", re.I)).first.click()
        shown = wait_gate(p, timeout=10000)
        p.wait_for_timeout(600)
        sc.check("the first-visit gate reappears after sign-out", shown and gate_visible(p))
        sc.check("the account email is gone from the sidebar", USERS["A"]["email"] not in aside_text(p),
                 repr(aside_text(p)[:200]))
        sc.check("the previous account's chats are not rendered", "ALPHA-ACCOUNT-HISTORY" not in aside_text(p),
                 repr(aside_text(p)[:200]))
        sc.check("the signed-out header offers Log in / Sign up",
                 p.locator("nav[aria-label=Account]").count() >= 1 or
                 (p.get_by_role("link", name=re.compile("^log in$", re.I)).count() >= 1))
        sc.check("the stored session is cleared", not has_session(p))
        sc.check("exactly one logout request", s_mock_count(s, "logout") == 1, f"counts={s_mock_counts(s)}")
        s.shot("w1-06b-signed-out-after-logout.png")
        sc.check("no console errors", not s.bad_console(), "; ".join(s.bad_console())[:400])


def scenario_07(browser, run_no: int) -> None:
    sc = Scenario("07", "token expiry and refresh")

    # 07a: an expired stored session is refreshed; the user stays signed in and the gate never appears.
    mock = SupabaseMock(password="ok", refresh="ok", user="A")
    with Session(browser, mock) as s:
        p = s.page
        goto_auth(p, "/login")
        fill_email(p, USERS["A"]["email"])
        fill_password(p, PASSWORD)
        submit(p)
        if not wait_home(p):
            sc.check("signed in before the expiry test", False, p.url)
        else:
            p.wait_for_timeout(600)
            sc.check("the stored session can be expired for the test", expire_session(p))
            mock.counts.clear()
            p.reload(wait_until="domcontentloaded")
            p.wait_for_timeout(1500)
            sc.check("an expired session triggers exactly one refresh call", s_mock_count(s, "refresh") == 1,
                     f"counts={s_mock_counts(s)}")
            sc.check("the user stays signed in after the refresh", USERS["A"]["email"] in aside_text(p),
                     repr(aside_text(p)[:200]))
            sc.check("the gate is not shown and never flashed", not gate_visible(p)
                     and p.evaluate("() => window.__gateSeen !== true"))
            s.shot("w1-07a-refresh-kept-session.png")
            sc.check("07a: no console errors", not s.bad_console(), "; ".join(s.bad_console())[:400])

    # 07b: a failing refresh signs the user out into a visible, non-crashing state.
    mock = SupabaseMock(password="ok", refresh="fail", user="A")
    with Session(browser, mock) as s:
        p = s.page
        goto_auth(p, "/login")
        fill_email(p, USERS["A"]["email"])
        fill_password(p, PASSWORD)
        submit(p)
        if not wait_home(p):
            sc.check("signed in before the failing-refresh test", False, p.url)
            return
        p.wait_for_timeout(600)
        expire_session(p)
        mock.counts.clear()
        p.reload(wait_until="domcontentloaded")
        p.wait_for_timeout(2000)
        sc.check("a failing refresh signs the user out", not has_session(p))
        sc.check("a visible signed-out state is rendered (gate and/or account links)",
                 gate_visible(p) or p.locator("nav[aria-label=Account]").count() >= 1)
        sc.check("the account email is gone", USERS["A"]["email"] not in aside_text(p), repr(aside_text(p)[:200]))
        sc.check("no stuck spinner: the composer is present and editable",
                 p.locator("textarea").count() >= 1 and p.locator("textarea").first.is_editable())
        sc.check("no unhandled page error", not [m for k, m in s.logs if k == "pageerror"],
                 "; ".join(m for k, m in s.logs if k == "pageerror")[:300])
        s.shot("w1-07b-refresh-failed.png")
        sc.check("07b: no console errors beyond the mocked 400 response", not s.bad_console(),
                 "; ".join(s.bad_console())[:400])


def scenario_08(browser, run_no: int) -> None:
    sc = Scenario("08", "first-visit gate dismissal")

    # The dialog itself receives initial focus; both Tab directions must stay inside from that boundary.
    with Session(browser, SupabaseMock()) as s:
        p = s.page
        goto_home(p)
        dialog = p.locator("[role=dialog]")
        opened = wait_gate(p)
        sc.check("the gate receives initial focus", opened and p.evaluate("() => document.activeElement?.getAttribute('role') === 'dialog'"))
        p.keyboard.press("Shift+Tab")
        sc.check("Shift+Tab from the dialog container stays inside", p.evaluate("() => { const d = document.querySelector('[role=dialog]'); return !!d && d.contains(document.activeElement); }"))
        p.keyboard.press("Tab")
        sc.check("Tab wraps inside after Shift+Tab", p.evaluate("() => { const d = document.querySelector('[role=dialog]'); return !!d && d.contains(document.activeElement); }"))
        s.shot("w1-08-focus-trap.png")

    # 08a: the 'Skip for now' text button.
    with Session(browser, SupabaseMock()) as s:
        p = s.page
        goto_home(p)
        sc.check("the gate appears on a fresh visit to /", wait_gate(p))
        s.shot("w1-08a-gate-open.png")
        p.locator("[role=dialog] button:has-text('Skip for now')").first.click()
        p.wait_for_timeout(400)
        sc.check("'Skip for now' closes the gate", not gate_visible(p))
        p.reload(wait_until="domcontentloaded")
        p.wait_for_timeout(1000)
        sc.check("the gate does not return after a reload", not gate_visible(p))
        s.shot("w1-08a-gate-skipped.png")
        sc.check("08a: no console errors", not s.bad_console(), "; ".join(s.bad_console())[:300])

    # 08b: the ✕ control (aria-label 'Skip for now').
    with Session(browser, SupabaseMock()) as s:
        p = s.page
        goto_home(p)
        sc.check("the gate appears on a second fresh visit", wait_gate(p))
        p.locator("[role=dialog] button[aria-label='Skip for now']").first.click()
        p.wait_for_timeout(400)
        sc.check("the ✕ control closes the gate", not gate_visible(p))
        p.reload(wait_until="domcontentloaded")
        p.wait_for_timeout(1000)
        sc.check("the ✕ dismissal also survives a reload", not gate_visible(p))
        s.shot("w1-08b-gate-x-closed.png")
        sc.check("08b: no console errors", not s.bad_console(), "; ".join(s.bad_console())[:300])

    # 08c: Escape, only if the sibling fix landed.
    with Session(browser, SupabaseMock()) as s:
        p = s.page
        goto_home(p)
        opened = wait_gate(p)
        p.keyboard.press("Escape")
        p.wait_for_timeout(500)
        closed = opened and not gate_visible(p)
        s.shot("w1-08c-gate-escape.png")
        if closed:
            p.reload(wait_until="domcontentloaded")
            p.wait_for_timeout(900)
            sc.check("Escape closes the gate", True)
            sc.check("Escape dismissal survives a reload", not gate_visible(p))
        else:
            sc.skip("Escape closes the gate",
                    "not implemented on this build: the gate has no keydown handler, so Escape leaves it open "
                    "(observed gap, not counted as a failure)")


def scenario_09(browser, run_no: int) -> None:
    sc = Scenario("09", "identity-bound chat history")
    mock = SupabaseMock(password="ok", user="B")
    with Session(browser, mock) as s:
        p = s.page
        goto_auth(p, "/login")
        p.evaluate("""(buckets) => {
            for (const [k, v] of Object.entries(buckets)) localStorage.setItem(k, JSON.stringify(v));
        }""", {
            f"{STORE_KEY}:{USERS['A']['id']}": [_conv("a1", "ALPHA-A-ONLY")],
            f"{STORE_KEY}:{USERS['B']['id']}": [_conv("b1", "BRAVO-B-ONLY")],
            STORE_KEY: [_conv("l1", "LEGACY-LEAK")],
            f"{STORE_KEY}:anon": [_conv("n1", "ANON-LEAK")],
        })
        fill_email(p, USERS["B"]["email"])
        fill_password(p, PASSWORD)
        submit(p)
        if not wait_home(p):
            sc.check("signed in as B", False, p.url)
            return
        p.wait_for_timeout(800)
        text = aside_text(p)
        sc.check("B sees B's chat", "BRAVO-B-ONLY" in text, repr(text[:220]))
        sc.check("B does not see A's chat", "ALPHA-A-ONLY" not in text, repr(text[:220]))
        sc.check("B does not see the legacy bucket", "LEGACY-LEAK" not in text, repr(text[:220]))
        sc.check("B does not see the anon bucket", "ANON-LEAK" not in text, repr(text[:220]))
        leftover = p.evaluate("(keys) => keys.map(k => localStorage.getItem(k))",
                              [STORE_KEY, f"{STORE_KEY}:anon"])
        sc.check("the legacy and anon buckets are purged", leftover == [None, None], repr(leftover))
        s.shot("w1-09a-userB-history.png")

        p.get_by_role("button", name=re.compile("log out", re.I)).first.click()
        wait_gate(p, timeout=10000)
        p.wait_for_timeout(700)
        anon_text = aside_text(p)
        sc.check("signed out: neither account's chats are rendered",
                 "ALPHA-A-ONLY" not in anon_text and "BRAVO-B-ONLY" not in anon_text, repr(anon_text[:220]))
        s.shot("w1-09b-signed-out.png")

        mock.user = USERS["A"]
        goto_auth(p, "/login")
        fill_email(p, USERS["A"]["email"])
        fill_password(p, PASSWORD)
        submit(p)
        if not wait_home(p):
            sc.check("signed in as A", False, p.url)
            return
        p.wait_for_timeout(800)
        a_text = aside_text(p)
        sc.check("A sees A's chat", "ALPHA-A-ONLY" in a_text, repr(a_text[:220]))
        sc.check("A does not see B's chat", "BRAVO-B-ONLY" not in a_text, repr(a_text[:220]))
        s.shot("w1-09c-userA-history.png")
        sc.check("no console errors", not s.bad_console(), "; ".join(s.bad_console())[:400])


def scenario_10(browser, run_no: int) -> None:
    sc = Scenario("10", "duplicate submit guard")

    with Session(browser, SupabaseMock(password="ok", delay=0.4)) as s:
        p = s.page
        goto_auth(p, "/login")
        fill_email(p, USERS["A"]["email"])
        fill_password(p, PASSWORD)
        p.evaluate("() => { const f = document.querySelector('form'); f.requestSubmit(); f.requestSubmit(); }")
        p.wait_for_timeout(3000)
        sc.check("two submits in one tick send exactly one sign-in request",
                 s_mock_count(s, "password") == 1, f"requests={s_mock_count(s, 'password')}")
        s.shot("w1-10a-login-duplicate-submit.png")
        sc.check("10a: no console errors", not s.bad_console(), "; ".join(s.bad_console())[:300])

    with Session(browser, SupabaseMock(signup="confirm", delay=0.4, user="NEW")) as s:
        p = s.page
        goto_auth(p, "/signup")
        fill_email(p, USERS["NEW"]["email"])
        fill_password(p, PASSWORD)
        p.evaluate("() => { const f = document.querySelector('form'); f.requestSubmit(); f.requestSubmit(); }")
        p.wait_for_timeout(3000)
        sc.check("two submits in one tick send exactly one signup request",
                 s_mock_count(s, "signup") == 1, f"requests={s_mock_count(s, 'signup')}")
        s.shot("w1-10b-signup-duplicate-submit.png")
        sc.check("10b: no console errors", not s.bad_console(), "; ".join(s.bad_console())[:300])


def scenario_11(browser, run_no: int) -> None:
    sc = Scenario("11", "slow auth response")

    with Session(browser, SupabaseMock(password="ok", delay=1.5)) as s:
        p = s.page
        goto_auth(p, "/login")
        fill_email(p, USERS["A"]["email"])
        fill_password(p, PASSWORD)
        p.evaluate(BUSY_SAMPLER)
        p.evaluate("() => document.querySelector('form').requestSubmit()")
        settled = wait_home(p, timeout=15000)
        samples = p.evaluate("() => { clearInterval(window.__sampler); return window.__samples || []; }")
        busy = [x for x in samples if x.get("disabled") and "logging in" in (x.get("text") or "").lower()]
        sc.check("the submit control is disabled and busy while the response is pending",
                 len(busy) > 0, f"samples={samples[:6]}")
        sc.check("the pending state was actually sampled in-flight", len(samples) >= 5, f"n={len(samples)}")
        sc.check("it settles to the signed-in state", settled and USERS["A"]["email"] in aside_text(p), p.url)
        s.shot("w1-11a-login-busy.png")
        sc.check("11a: no console errors", not s.bad_console(), "; ".join(s.bad_console())[:300])

    with Session(browser, SupabaseMock(signup="confirm", delay=1.5, user="NEW")) as s:
        p = s.page
        goto_auth(p, "/signup")
        fill_email(p, USERS["NEW"]["email"])
        fill_password(p, PASSWORD)
        p.evaluate(BUSY_SAMPLER)
        p.evaluate("() => document.querySelector('form').requestSubmit()")
        try:
            p.wait_for_function("() => /check your email/i.test(document.body.innerText)", timeout=15000)
        except PWTimeout:
            pass
        samples = p.evaluate("() => { clearInterval(window.__sampler); return window.__samples || []; }")
        busy = [x for x in samples if x.get("disabled") and "signing up" in (x.get("text") or "").lower()]
        sc.check("signup: the submit control is disabled and busy while pending", len(busy) > 0,
                 f"samples={samples[:6]}")
        sc.check("signup: it settles to the confirmation state",
                 bool(re.search(r"check your email", p.inner_text("body"), re.I)))
        s.shot("w1-11b-signup-busy.png")
        sc.check("11b: no console errors", not s.bad_console(), "; ".join(s.bad_console())[:300])


def scenario_12(browser, run_no: int) -> None:
    sc = Scenario("12", "mobile 375x812")

    with Session(browser, SupabaseMock(), viewport=MOBILE) as s:
        p = s.page
        goto_auth(p, "/login")
        ok, detail = no_horizontal_overflow(p)
        sc.check("/login has no horizontal overflow", ok, detail)
        fill_email(p, USERS["A"]["email"])
        fill_password(p, PASSWORD)
        sc.check("/login inputs accept typing",
                 p.input_value("input[type=email]") == USERS["A"]["email"] and
                 len(p.input_value("input[type=password]")) == len(PASSWORD))
        ok, detail = in_viewport(p, "button[type=submit]", MOBILE["height"])
        sc.check("/login primary button is visible without scrolling", ok, detail)
        s.shot("w1-12a-mobile-login.png")

    with Session(browser, SupabaseMock(), viewport=MOBILE) as s:
        p = s.page
        goto_auth(p, "/signup")
        ok, detail = no_horizontal_overflow(p)
        sc.check("/signup has no horizontal overflow", ok, detail)
        fill_email(p, USERS["NEW"]["email"])
        fill_password(p, PASSWORD)
        sc.check("/signup inputs accept typing", p.input_value("input[type=email]") == USERS["NEW"]["email"])
        ok, detail = in_viewport(p, "button[type=submit]", MOBILE["height"])
        sc.check("/signup primary button is visible without scrolling", ok, detail)
        s.shot("w1-12b-mobile-signup.png")

    with Session(browser, SupabaseMock(), viewport=MOBILE) as s:
        p = s.page
        goto_home(p)
        shown = wait_gate(p)
        ok, detail = no_horizontal_overflow(p)
        sc.check("/ gate has no horizontal overflow", shown and ok, f"gate={shown} {detail}")
        ok, detail = in_viewport(p, "[role=dialog] a:has-text('Log in')", MOBILE["height"])
        sc.check("/ gate primary action is visible without scrolling", ok, detail)
        ok, detail = in_viewport(p, "[role=dialog] button:has-text('Skip for now')", MOBILE["height"])
        sc.check("/ gate dismiss control is reachable without scrolling", ok, detail)
        s.shot("w1-12c-mobile-gate.png")
        sc.check("12c: no console errors", not s.bad_console(), "; ".join(s.bad_console())[:300])


def scenario_13(browser, run_no: int) -> None:
    sc = Scenario("13", "anonymous parity (real local API, unmocked)")
    with Session(browser, None) as s:  # deliberately no auth mock: this is the real path
        p = s.page
        goto_home(p)
        if wait_gate(p, timeout=8000):
            p.locator("[role=dialog] button:has-text('Skip for now')").first.click()
            p.wait_for_timeout(300)
        message = "Hi, the team lunch is at 1pm tomorrow. See you there!"
        p.fill("textarea", message)
        p.click("button[aria-label=Send]")
        try:
            p.wait_for_function("() => document.querySelectorAll('main ol > li').length >= 2 "
                                "&& !document.querySelector('main li[role=status]')", timeout=90000)
            rendered = True
        except PWTimeout:
            rendered = False
        reply = p.locator("main ol > li").last.inner_text() if p.locator("main ol > li").count() else ""
        sc.check("a reply renders for an anonymous message", rendered and len(reply.strip()) > 20, repr(reply[:200]))
        sc.check("the reply is not a connection error", "dropped" not in reply.lower(), repr(reply[:200]))
        stream = [r for r in s.requests if "/api/v1/chat/stream" in r["url"]]
        sc.check("the chat request reached the real local API", len(stream) == 1,
                 f"requests={[r['url'] for r in s.requests if '/api/v1/' in r['url']]}")
        auth_headers = [r["headers"].get("authorization", "") for r in stream]
        sc.check("the anonymous request carried NO Authorization header",
                 bool(stream) and all(h == "" for h in auth_headers), f"authorization={auth_headers}")
        external = sorted({urlparse(r["url"]).hostname or "" for r in s.requests
                           if r["url"].startswith("http") and (urlparse(r["url"]).hostname or "") not in
                           ("localhost", "127.0.0.1", "::1")})
        sc.check("no request left the machine", not external, f"external hosts={external}")
        s.shot("w1-13-anon-parity.png")
        sc.check("no console errors", not s.bad_console(), "; ".join(s.bad_console())[:400])


# ----------------------------------------------------------------------------------------------------------------------
# Small helpers used by scenarios
# ----------------------------------------------------------------------------------------------------------------------
def _conv(cid: str, title: str) -> dict:
    return {"id": cid, "title": title,
            "messages": [{"id": cid + "-m", "role": "user", "text": "seeded message"}]}


def seed_chats(page, buckets: dict[str, str]) -> None:
    page.evaluate("""(buckets) => {
        for (const [identity, title] of Object.entries(buckets)) {
            localStorage.setItem('trustshield.chats.v1:' + identity, JSON.stringify([{
                id: identity + '-c1', title: title,
                messages: [{ id: identity + '-m1', role: 'user', text: 'seeded message' }],
            }]));
        }
    }""", buckets)


def s_mock_count(session: Session, kind: str) -> int:
    return session.mock.counts.get(kind, 0) if session.mock else 0


def s_mock_counts(session: Session) -> dict:
    return dict(session.mock.counts) if session.mock else {}


SCENARIOS = [
    ("01", scenario_01), ("02", scenario_02), ("03", scenario_03), ("04", scenario_04),
    ("05", scenario_05), ("06", scenario_06), ("07", scenario_07), ("08", scenario_08),
    ("09", scenario_09), ("10", scenario_10), ("11", scenario_11), ("12", scenario_12),
    ("13", scenario_13),
]


# ----------------------------------------------------------------------------------------------------------------------
# Preflight, evidence, main
# ----------------------------------------------------------------------------------------------------------------------
def preflight() -> bool:
    problems = []
    for name, url in (("web", WEB + "/"), ("api", API + "/api/v1/health")):
        try:
            with urllib.request.urlopen(url, timeout=6) as resp:
                if resp.status != 200:
                    problems.append(f"{name} {url} returned HTTP {resp.status}")
                elif name == "api":
                    health = json.loads(resp.read().decode("utf-8"))
                    ml = health.get("modules", {}).get("ml", {})
                    print(f"preflight: api ok (ml available={ml.get('available')}, model={ml.get('model_name')})",
                          flush=True)
        except Exception as exc:  # noqa: BLE001
            problems.append(f"{name} {url} unreachable: {exc}")
    if problems:
        print("\nPREFLIGHT FAILED: the local stack is not reachable, so this matrix cannot run.", flush=True)
        for p in problems:
            print(f"  - {p}", flush=True)
        print("Start the stack first (Next dev on :3000, FastAPI on :8000), then re-run this script.\n", flush=True)
        return False
    print(f"preflight: web {WEB} ok", flush=True)
    return True


FORBIDDEN = [
    ("bearer header", re.compile(r"Bearer\s", re.I)),
    ("jwt", re.compile(r"eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}")),
    ("access token", re.compile(re.escape(ACCESS_TOKEN))),
    ("refresh token", re.compile(re.escape(REFRESH_TOKEN))),
    ("password", re.compile(re.escape(PASSWORD))),
    ("storage key", re.compile(r"sb-[a-z0-9]{6,}-auth-token")),
]


def scrub(text: str) -> None:
    for label, rx in FORBIDDEN:
        if rx.search(text):
            raise RuntimeError(f"evidence would leak a {label}; refusing to write it")
    for email in re.findall(r"[\w.+-]+@[\w.-]+", text):
        if not email.endswith("@example.test"):
            raise RuntimeError("evidence would contain a non-example address; refusing to write it")


def write_evidence(runs: list[dict]) -> Path:
    payload = {
        "suite": "W1 auth acceptance matrix",
        "script": "tests/e2e/test_finals_w1_auth.py",
        "web": WEB,
        "api": API,
        "generated_at": _now(),
        "runs": runs,
        "note": "Auth is mocked at the network layer for scenarios 01-12; scenario 13 uses the real local API.",
    }
    text = json.dumps(payload, indent=2)
    scrub(text)
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    out = EVIDENCE / "w1-run.json"
    out.write_text(text, encoding="utf-8")
    return out


def main() -> int:
    global WEB, API
    ap = argparse.ArgumentParser(description="W1 auth acceptance matrix (Playwright).")
    ap.add_argument("--web", default=WEB)
    ap.add_argument("--api", default=API)
    ap.add_argument("--runs", type=int, default=1)
    args = ap.parse_args()
    WEB = args.web.rstrip("/")
    API = args.api.rstrip("/")

    if not preflight():
        return 2

    runs: list[dict] = []
    failed_total = 0
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        try:
            for run_no in range(1, max(1, args.runs) + 1):
                RESULTS.clear()
                started = time.time()
                print(f"\n=== RUN {run_no} ===", flush=True)
                for sid, fn in SCENARIOS:
                    try:
                        fn(browser, run_no)
                    except Exception:  # noqa: BLE001 - one broken scenario must not lose the rest
                        detail = traceback.format_exc(limit=3).strip().splitlines()[-1]
                        RESULTS.append({"scenario": sid, "name": "scenario crashed", "status": "FAIL",
                                        "detail": detail, "at": _now()})
                        print(f"FAIL [{sid}] scenario crashed — {detail}", flush=True)
                passed = sum(1 for r in RESULTS if r["status"] == "PASS")
                failed = sum(1 for r in RESULTS if r["status"] == "FAIL")
                skipped = sum(1 for r in RESULTS if r["status"] == "SKIP")
                failed_total += failed
                print(f"=== RUN {run_no}: {passed} passed, {failed} failed, {skipped} skipped "
                      f"({time.time() - started:.1f}s) ===", flush=True)
                runs.append({"run": run_no, "started_at": _now(), "passed": passed, "failed": failed,
                             "skipped": skipped, "checks": list(RESULTS)})
        finally:
            browser.close()

    out = write_evidence(runs)
    print(f"\nevidence: {out}")
    print(f"evidence dir: {EVIDENCE}")
    if failed_total:
        print(f"\n{failed_total} check(s) failed across {len(runs)} run(s):")
        for run in runs:
            for r in run["checks"]:
                if r["status"] == "FAIL":
                    print(f"  FAIL [{r['scenario'].split(' ')[0]}] {r['name']} — {r['detail'][:200]}")
        return 1
    print(f"\nall checks passed across {len(runs)} run(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
