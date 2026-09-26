"""Black-box smoke test of a deployed TrustShield API (local container, staging or production).

    python scripts/smoke_remote.py --base https://api.example.com --origin https://frontend.example.app

Uses only the public HTTP surface and the synthetic demo fixtures. Exits non-zero if any check fails. It sends a small, bounded
number of requests (well under the rate limits) and never enables or exercises the sandbox.
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIX = ROOT / "demo" / "fixtures"
results: list[tuple[str, bool, str]] = []


def call(base, path, *, method="GET", body=None, headers=None, raw=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    request = urllib.request.Request(base + path, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            return response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), error.read()
    except (urllib.error.URLError, ConnectionError, TimeoutError) as error:
        # A server that rejects an oversize body early may close the socket while the client is still sending.
        return 0, {}, json.dumps({"connection_error": type(getattr(error, "reason", error)).__name__}).encode()


def multipart(name, filename, content, mime="image/png"):
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\nContent-Type: {mime}\r\n\r\n").encode() \
        + content + f"\r\n--{boundary}--\r\n".encode()
    return body, {"Content-Type": f"multipart/form-data; boundary={boundary}"}


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("PASS " if ok else "FAIL ") + name + ("" if ok or not detail else f"  -- {detail}"))


def js(payload):
    return json.loads(payload.decode())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--origin", default="")
    parser.add_argument("--expect-sandbox", default="false")
    parser.add_argument("--skip-limits", action="store_true", help="skip the oversize/rate-limit probes")
    args = parser.parse_args()
    base = args.base.rstrip("/")
    origin_headers = {"Origin": args.origin} if args.origin else {}

    status, headers, body = call(base, "/api/v1/health")
    health = js(body) if status == 200 else {}
    modules = health.get("modules", {})
    check("health reachable", status == 200, str(status))
    check("ML is trustshield_text_v3 and available", modules.get("ml", {}).get("model_name") == "trustshield_text_v3" and modules.get("ml", {}).get("available") is True)
    check("Tesseract OCR available", modules.get("ocr", {}).get("available") is True and modules["ocr"].get("provider") == "tesseract")
    check("web sandbox disabled", modules.get("web_sandbox") is False)
    status, _, body = call(base, "/readyz")
    check("readyz ready", status == 200 and js(body).get("ready") is True)
    status, headers, _ = call(base, "/api/v1/health")
    low = {k.lower(): v for k, v in headers.items()}
    check("security headers present", low.get("x-content-type-options") == "nosniff" and "x-request-id" in low and low.get("cache-control") == "no-store", str(list(low)))
    if base.startswith("https://"):
        check("HSTS on HTTPS", "max-age" in low.get("strict-transport-security", ""))
    for path in ("/docs", "/openapi.json", "/redoc"):
        check(f"{path} not exposed", call(base, path)[0] == 404)

    def analyze(kind, path, **kw):
        status, headers, body = call(base, path, method="POST", headers={**origin_headers, **kw.pop("headers", {})}, **kw)
        return status, {k.lower(): v for k, v in headers.items()}, (js(body) if body[:1] in (b"{", b"[") else {})

    status, _, r = analyze("text", "/api/v1/analyze/text", body={"text": "URGENT: your SBI account will be blocked today. Share the OTP now and verify KYC at http://sbi-kyc-update.xyz/login"}, headers={"Content-Type": "application/json"})
    check("malicious text flagged (not low risk)", status == 200 and r["risk"]["label"] in {"caution", "suspicious", "high_risk"}, f"{status} {r.get('risk')}")
    check("ml module completed on text", r.get("modules", {}).get("ml", {}).get("status") == "completed")
    status, _, r = analyze("text", "/api/v1/analyze/text", body={"text": "Hi, the team lunch is at 1pm tomorrow. See you there!"}, headers={"Content-Type": "application/json"})
    check("legitimate text stays low risk", status == 200 and r["risk"]["label"] in {"low_risk", "unable_to_verify"}, f"{status} {r.get('risk')}")
    status, _, r = analyze("url", "/api/v1/analyze/url", body={"url": "http://sbi-kyc-update.xyz/login"}, headers={"Content-Type": "application/json"})
    check("static URL analysis returns evidence", status == 200 and len(r.get("evidence", [])) > 0)
    status, _, r = analyze("url", "/api/v1/analyze/url", body={"url": "http://0x7f000001/login"}, headers={"Content-Type": "application/json"})
    check("numeric-IP URL flagged", status == 200 and any(e["category"] == "ip_address_host" for e in r.get("evidence", [])), f"{status}")
    check("URL analysis did not start a sandbox", r.get("modules", {}).get("web_sandbox", {}).get("status") in {"not_run", "unavailable"})

    body, hdr = multipart("file", "kyc_screenshot.png", (FIX / "kyc_screenshot.png").read_bytes())
    status, _, r = analyze("image", "/api/v1/analyze/image", raw=body, headers=hdr)
    check("screenshot OCR analysis", status == 200 and r.get("modules", {}).get("ocr", {}).get("status") == "completed", f"{status}")
    body, hdr = multipart("file", "qr_suspicious_url.png", (FIX / "qr_suspicious_url.png").read_bytes())
    status, _, r = analyze("qr", "/api/v1/analyze/qr", raw=body, headers=hdr)
    check("QR (URL) analysis", status == 200 and r.get("modules", {}).get("qr", {}).get("status") == "completed", f"{status}")
    body, hdr = multipart("file", "qr_upi.png", (FIX / "qr_upi.png").read_bytes())
    status, _, r = analyze("qr", "/api/v1/analyze/qr", raw=body, headers=hdr)
    upi = json.dumps(r).lower()
    check("UPI QR analysis", status == 200 and "upi" in upi, f"{status}")

    body, hdr = multipart("file", "evil.png", b"this is not an image at all")
    status, _, r = analyze("bad", "/api/v1/analyze/image", raw=body, headers=hdr)
    check("malformed upload fails safely (4xx, no traceback)", 400 <= status < 500 and "Traceback" not in json.dumps(r))
    if not args.skip_limits:
        body, hdr = multipart("file", "big.png", b"\x89PNG\r\n\x1a\n" + b"0" * (6 * 1024 * 1024))
        status, _, r = analyze("big", "/api/v1/analyze/image", raw=body, headers=hdr)
        check("oversized upload rejected (413, or the connection is cut while sending)", status in {413, 0}, str(status))
    status, _, r = analyze("bad", "/api/v1/analyze/text", body={"text": ["x"]}, headers={"Content-Type": "application/json"})
    check("validation error does not echo input", status == 422 and "input" not in json.dumps(r))

    if args.origin:
        status, headers, _ = call(base, "/api/v1/analyze/text", method="OPTIONS",
                                  headers={"Origin": args.origin, "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "content-type"})
        low = {k.lower(): v for k, v in headers.items()}
        check("CORS allows the frontend origin", low.get("access-control-allow-origin") == args.origin, str(status))
        status, headers, _ = call(base, "/api/v1/analyze/text", method="OPTIONS",
                                  headers={"Origin": "https://evil.example", "Access-Control-Request-Method": "POST"})
        check("CORS refuses another origin", "access-control-allow-origin" not in {k.lower() for k in headers})

    failed = [name for name, ok, _ in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
