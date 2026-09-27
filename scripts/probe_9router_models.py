"""Functional probe for every 9Router model (item 11 of the Gemini migration brief): a model appearing in
/v1/models is NOT proof it works. Sends 3 minimal harmless prompts per model and flags deprecation/provider-notice
responses instead of trusting apparent success. Never logs the API key or full response bodies to disk beyond this
report; only short previews are recorded for evidence.

Usage: python scripts/probe_9router_models.py [--models id1,id2,...]
"""
from __future__ import annotations

import argparse
import json
import os
import re
import time
import urllib.error
import urllib.request

BASE_URL = os.environ["TRUSTSHIELD_AI_BASE_URL"].rstrip("/")
API_KEY = os.environ["TRUSTSHIELD_AI_API_KEY"]

PROMPTS = [
    ("route_ok", "Reply with exactly: ROUTE_OK"),
    ("cyber_qa", "What is phishing? Answer in one sentence."),
    ("strict_json", 'Return strict JSON only, no markdown fences, no prose: {"ok": true}'),
]

_DEPRECATION_RE = re.compile(
    r"(deprecat|please use|model unavailable|route retired|has been retired|no longer (available|supported)|"
    r"upstream (auth|error)|quota exceeded|rate limit|service unavailable|invalid api key|not found: model)", re.I)


def call(model: str, prompt: str, timeout: float = 30.0) -> dict:
    body = json.dumps({"model": model, "messages": [{"role": "user", "content": prompt}],
                       "temperature": 0.1, "max_tokens": 200, "stream": False}).encode()
    req = urllib.request.Request(f"{BASE_URL}/chat/completions", data=body,
                                 headers={"Content-Type": "application/json", "Authorization": f"Bearer {API_KEY}"})
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310
            payload = json.loads(resp.read())
            text = payload["choices"][0]["message"]["content"]
            latency_ms = round((time.perf_counter() - t0) * 1000)
            return {"ok": True, "text": text, "latency_ms": latency_ms,
                    "looks_like_provider_notice": bool(_DEPRECATION_RE.search(text))}
    except urllib.error.HTTPError as exc:
        return {"ok": False, "error": f"HTTPError:{exc.code}", "latency_ms": round((time.perf_counter() - t0) * 1000)}
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": f"{type(exc).__name__}", "latency_ms": round((time.perf_counter() - t0) * 1000)}


def probe_model(model: str) -> dict:
    results = {}
    any_notice = False
    for key, prompt in PROMPTS:
        r = call(model, prompt)
        results[key] = r
        if r.get("looks_like_provider_notice"):
            any_notice = True

    route_ok_text = results["route_ok"].get("text", "") if results["route_ok"]["ok"] else ""
    json_text = results["strict_json"].get("text", "") if results["strict_json"]["ok"] else ""
    json_valid = False
    if json_text:
        stripped = re.sub(r"^```(?:json)?\s*|\s*```$", "", json_text.strip())
        try:
            json_valid = json.loads(stripped) == {"ok": True}
        except Exception:  # noqa: BLE001
            json_valid = False

    all_ok = all(r["ok"] for r in results.values())
    verdict = "CHAT_CAPABLE" if (all_ok and not any_notice and "ROUTE_OK" in route_ok_text) else "REJECTED"
    return {
        "model": model, "verdict": verdict, "all_calls_ok": all_ok,
        "provider_notice_detected": any_notice,
        "route_ok_response_preview": route_ok_text[:80],
        "cyber_qa_response_preview": results["cyber_qa"].get("text", "")[:150],
        "strict_json_valid": json_valid, "strict_json_response_preview": json_text[:120],
        "latencies_ms": {k: v.get("latency_ms") for k, v in results.items()},
        "errors": {k: v.get("error") for k, v in results.items() if not v["ok"]},
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default="")
    ap.add_argument("--out", default="artifacts/9router_probe/results.json")
    args = ap.parse_args()

    if args.models:
        models = args.models.split(",")
    else:
        req = urllib.request.Request(f"{BASE_URL}/models", headers={"Authorization": f"Bearer {API_KEY}"})
        with urllib.request.urlopen(req, timeout=15) as resp:  # noqa: S310
            models = [m["id"] for m in json.loads(resp.read())["data"]]

    reports = []
    for m in models:
        print(f"probing {m} ...")
        r = probe_model(m)
        reports.append(r)
        print(f"  -> {r['verdict']} (notice={r['provider_notice_detected']}, json_valid={r['strict_json_valid']})")

    from pathlib import Path
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(reports, indent=2), encoding="utf-8")
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
