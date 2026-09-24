"""Playwright observer. It never fills, clicks, submits, or executes downloads."""
from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import os
import shutil
import tempfile
from pathlib import Path
from urllib.parse import urljoin

from .egress_proxy import ValidatingEgressProxy
from .network_guard import SandboxGuard
from .policy import SandboxPolicy
from .telemetry import Telemetry, bounded_telemetry, clean_text


def _resolver_from_map(mapping: dict[str, list[str]] | None):
    mapping = {str(k).lower().rstrip("."): list(v) for k, v in (mapping or {}).items()}

    def resolve(host: str) -> list[str]:
        if host.lower().rstrip(".") in mapping:
            return mapping[host.lower().rstrip(".")]
        from services.analysis.url_analysis.ssrf import _default_resolver
        return _default_resolver(host)
    return resolve


async def run_browser(job: dict) -> dict:
    try:
        from playwright.async_api import TimeoutError as PlaywrightTimeoutError, async_playwright
    except ImportError as exc:
        return {"status": "unavailable", "detail": f"Playwright is not installed: {exc}"}

    policy = SandboxPolicy.from_env()
    allowlist = job.get("test_allowlist") or []
    guard = SandboxGuard(_resolver_from_map(job.get("resolver_map")),
                         test_allowlist=allowlist,
                         production=bool(job.get("production", True)),
                         allow_external=bool(job.get("allow_external", False)),
                         allowed_ports=policy.allowed_ports)
    initial = str(job.get("url", ""))
    telemetry = Telemetry(initial_url=initial)
    temp_root = Path(tempfile.mkdtemp(prefix="trustshield-sandbox-"))
    proxy = ValidatingEgressProxy(guard, policy)
    browser = context = page = None
    try:
        target = guard.validate_request(initial, "GET")
        proxy_host, proxy_port = await proxy.start()
        async with async_playwright() as pw:
            args = [
                "--proxy-bypass-list=<-loopback>",
                "--disable-webrtc", "--force-webrtc-ip-handling-policy=disable_non_proxied_udp",
                "--block-new-web-contents", "--disable-notifications", "--mute-audio",
                "--disable-quic", "--disable-http3", "--disable-features=PreconnectToSearch,InterestFeedContentSuggestions",
                "--disable-background-networking", "--disable-component-update", "--no-pings",
                "--disable-sync", "--disable-extensions", "--disable-default-apps",
            ]
            if guard.test_mode:
                maps = []
                for host_port in allowlist:
                    host, _, _ = str(host_port).rpartition(":")
                    addresses = (job.get("resolver_map") or {}).get(host, ["127.0.0.1"])
                    maps.append(f"MAP {host} {addresses[0]}")
                if maps:
                    args.append("--host-resolver-rules=" + ",".join(maps))
            browser = await pw.chromium.launch(headless=True, proxy={"server": f"http://{proxy_host}:{proxy_port}", "username": proxy.username, "password": proxy.password}, args=args)
            context = await browser.new_context(
                service_workers="block", accept_downloads=True,
                viewport={"width": 1280, "height": 800},
                java_script_enabled=True,
            )
            page = await context.new_page()

            async def block_and_record(route, reason: str):
                telemetry.blocked.append({"url": clean_text(route.request.url, 2048), "reason": reason})
                await route.abort("blockedbyclient")

            async def route_handler(route):
                request = route.request
                if request.url.startswith("about:blank"):
                    await route.continue_()
                    return
                if request.url.startswith("data:image/") and len(request.url) <= 500_000:
                    await route.continue_()
                    return
                if request.url.startswith("data:"):
                    await block_and_record(route, "inline_scheme_policy")
                    return
                if len(telemetry.redirects) > policy.max_redirects:
                    await block_and_record(route, "redirect_limit")
                    return
                if len(telemetry.requests) < policy.max_requests:
                    try:
                        guard.validate_request(request.url, request.method)
                        telemetry.requests.append({"url": clean_text(request.url, 2048), "method": request.method,
                                                   "resource_type": request.resource_type})
                    except Exception as exc:
                        if request.method.upper() not in {"GET", "HEAD"}:
                            telemetry.post_attempts += 1
                        await block_and_record(route, str(exc))
                        return
                else:
                    await block_and_record(route, "request_record_cap")
                    return
                await route.continue_()

            await page.route("**/*", route_handler)

            async def on_response(response):
                status = response.status
                if 300 <= status < 400:
                    location = response.headers.get("location")
                    if location:
                        destination = urljoin(response.url, location)
                        telemetry.redirects.append({"url": clean_text(response.url, 2048), "status": status,
                                                    "location": clean_text(destination, 2048)})
                        if len(telemetry.redirects) > policy.max_redirects:
                            telemetry.blocked.append({"url": destination, "reason": "redirect_limit"})
                        else:
                            try:
                                guard.validate_request(destination, "GET")
                            except Exception as exc:
                                telemetry.blocked.append({"url": destination, "reason": str(exc)})

            page.on("response", on_response)

            async def on_dialog(dialog):
                telemetry.dialogs += 1
                telemetry.dialog_types.append(clean_text(dialog.type, 40))
                await dialog.dismiss()

            page.on("dialog", on_dialog)

            async def on_popup(popup):
                telemetry.popups += 1
                await popup.close()

            context.on("page", on_popup)

            async def on_download(download):
                item = {"filename": clean_text(download.suggested_filename, 255), "mime": "", "sha256": "", "size": 0,
                        "deleted": False, "trigger": "javascript"}
                quarantine = temp_root / "downloads"
                quarantine.mkdir(exist_ok=True)
                path = quarantine / (hashlib.sha256(item["filename"].encode()).hexdigest() + ".bin")
                try:
                    await download.save_as(str(path))
                    size = path.stat().st_size
                    item["size"] = min(size, policy.download_bytes)
                    with path.open("rb") as stream:
                        remaining = policy.download_bytes
                        digest = hashlib.sha256()
                        while remaining > 0:
                            chunk = stream.read(min(64 * 1024, remaining))
                            if not chunk:
                                break
                            digest.update(chunk)
                            remaining -= len(chunk)
                        item["sha256"] = digest.hexdigest()
                    item["capped"] = size > policy.download_bytes
                except Exception as exc:
                    item["error"] = clean_text(exc, 300)
                finally:
                    try:
                        path.unlink(missing_ok=True)
                        item["deleted"] = not path.exists()
                    except OSError:
                        item["deleted"] = False
                telemetry.downloads.append(item)

            pending_downloads: list[asyncio.Task] = []
            page.on("download", lambda dl: pending_downloads.append(asyncio.ensure_future(on_download(dl))))
            await page.add_init_script("window.open = function(){ return null; };")
            try:
                await page.goto(target.url, wait_until="domcontentloaded", timeout=policy.navigation_timeout_ms)
            except PlaywrightTimeoutError:
                telemetry.load_timeout = True
            except Exception as exc:
                if "TLS" in str(exc) or "CERT" in str(exc):
                    telemetry.tls_error = True
                telemetry.blocked.append({"url": target.url, "reason": clean_text(exc, 300)})
            await page.wait_for_timeout(250)
            if pending_downloads:
                await asyncio.wait(pending_downloads, timeout=policy.idle_timeout_s)
            telemetry.final_url = page.url
            telemetry.title = await page.title()
            body_text = await page.locator("body").inner_text(timeout=1_000) if await page.locator("body").count() else ""
            telemetry.visible_text = body_text[:policy.visible_text_chars]
            telemetry.visible_text_truncated = len(body_text) > policy.visible_text_chars
            lowered = body_text.lower()
            telemetry.page_blocks_automation = any(word in lowered for word in ("captcha", "automated access", "verify you are human"))
            forms = await page.locator("form").evaluate_all("""forms => forms.map(form => ({
                action: form.action, action_scheme: new URL(form.action || location.href).protocol.replace(':',''),
                method: (form.method || 'get').toUpperCase(), hidden: getComputedStyle(form).display === 'none' || form.offsetParent === null,
                fields: Array.from(form.elements).filter(e => e.tagName === 'INPUT' || e.tagName === 'TEXTAREA').map(e => ({
                    field_type: (e.type || '').toLowerCase() === 'password' ? 'password' :
                      /otp|one-time|verification|pin/i.test([e.name,e.id,e.autocomplete,e.placeholder].join(' ')) ? 'otp' :
                      /cvv|cvc|security.?code/i.test([e.name,e.id,e.autocomplete,e.placeholder].join(' ')) ? 'cvv' :
                      /cc-number|card.?number|credit.?card/i.test([e.name,e.id,e.autocomplete,e.placeholder].join(' ')) ? 'card' :
                      /upi|vpa/i.test([e.name,e.id,e.autocomplete,e.placeholder].join(' ')) ? 'upi' :
                      (e.type || '').toLowerCase()
                }))
            }))""")
            telemetry.forms = forms
            screenshot = await page.screenshot(type="jpeg", quality=45, timeout=2_000)
            if len(screenshot) <= policy.screenshot_bytes:
                telemetry.screenshot_jpeg_b64 = base64.b64encode(screenshot).decode("ascii")
            telemetry.total_bytes = proxy.budget.used
            return {"status": "completed", "telemetry": bounded_telemetry(telemetry.as_dict())}
    except Exception as exc:
        return {"status": "failed", "detail": clean_text(exc, 500), "telemetry": bounded_telemetry(telemetry.as_dict())}
    finally:
        if context is not None:
            try:
                await context.close()
            except Exception:
                pass
        if browser is not None:
            try:
                await browser.close()
            except Exception:
                pass
        await proxy.close()
        shutil.rmtree(temp_root, ignore_errors=True)


def main() -> int:
    try:
        job = json.loads(os.read(0, 10 * 1024 * 1024).decode("utf-8"))
        result = asyncio.run(asyncio.wait_for(run_browser(job), SandboxPolicy.from_env().total_timeout_s))
    except asyncio.TimeoutError:
        result = {"status": "timeout", "detail": "sandbox wall-clock limit exceeded"}
    except Exception as exc:
        result = {"status": "failed", "detail": clean_text(exc, 500)}
    print(json.dumps(result, separators=(",", ":")), flush=True)
    return 0 if result.get("status") == "completed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
