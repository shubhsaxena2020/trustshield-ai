#!/usr/bin/env python3
"""Check normal-chat versus scam-content routing on the local TrustShield API."""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE_URL = os.getenv("TRUSTSHIELD_TEST_URL", "http://localhost:8000").rstrip("/")
RAW_PATH = ROOT / "docs/security-runs/model_routing_battery_35_raw.json"
REPORT_PATH = ROOT / "docs/security-runs/model_routing_battery_35_report.md"

PROMPTS = [
    ("normal_chat", "Hi there!", "chat", []),
    ("normal_chat", "Good morning, how are you today?", "chat", []),
    ("normal_chat", "Thanks for helping me with this demo.", "chat", []),
    ("normal_chat", "What is phishing, in simple terms?", "chat", []),
    ("normal_chat", "How does two-factor authentication protect an account?", "chat", []),
    ("normal_chat", "What is the difference between smishing and vishing?", "chat", []),
    ("normal_chat", "What model do you use?", "chat", []),
    ("normal_chat", "Who built TrustShield AI?", "chat", []),
    ("normal_chat", "Can you write me a short poem about rain?", "chat", []),
    ("normal_chat", "What's the weather like in Mumbai?", "chat", []),
    ("normal_chat", "Help me think of a name for a cybersecurity club.", "chat", []),
    ("normal_chat", "Can you explain password managers to a beginner?", "chat", []),
    ("normal_chat", "Why did you flag that message as suspicious?", "followup", [
        {"role": "user", "text": "Your account will be closed today; verify at http://bank-alert.example/login and share the code."},
        {"role": "assistant", "text": "That message has been checked and shows warning signs."},
    ]),
    ("normal_chat", "What should I do next about the result you just gave me?", "followup", [
        {"role": "user", "text": "Parcel held. Pay a fee at http://parcel.example/claim now."},
        {"role": "assistant", "text": "The message was analyzed and looks suspicious."},
    ]),
    ("normal_chat", "Did you check the sender's reputation in the scan we just discussed?", "followup", [
        {"role": "user", "text": "Your bank card is locked. Call the number in this text to unlock it."},
        {"role": "assistant", "text": "I checked the message for scam indicators."},
    ]),
    ("normal_chat", "Can you explain what a risk score means generally?", "chat", []),
    ("normal_chat", "I am not sure what to ask. Can you tell me what you can help with?", "chat", []),
    ("normal_chat", "Tell me a fun fact about the internet.", "chat", []),
    ("scan_content", "Is this real? Your bank account is blocked. Sign in at http://secure-bank-check.example/login and confirm your OTP.", "analysis", []),
    ("scan_content", "Can you check this link: http://account-verify.example/reset", "analysis", []),
    ("scan_content", "Someone called pretending to be my bank and said I must read them the code that just arrived by text. Is this suspicious?", "analysis", []),
    ("scan_content", "PhonePe refund pending. Approve this collect request and tell me the OTP to receive ₹4,500.", "analysis", []),
    ("scan_content", "Congratulations, you are selected for a remote job. Pay a refundable onboarding fee at http://fast-hire.example/apply.", "analysis", []),
    ("scan_content", "dear custmer ur kyc expiring today click http://kyc-fast.example n send pan + bank passwrd", "analysis", []),
    ("scan_content", "Click http://parcel-fee.example/pay to release your package; the courier needs your card CVV.", "analysis", []),
    ("scan_content", "Got this sms: electricity disconnect 6pm pay bill now on http://power-payment.example or call 9876543210", "analysis", []),
    ("scan_content", "Your crypto wallet has a security hold. Restore access at http://wallet-rescue.example with your recovery phrase.", "analysis", []),
    ("scan_content", "I received this: 'Mom, new number. I'm stranded. Send ₹8,000 to this UPI ID right away.'", "analysis", []),
    ("scan_content", "HR says my salary account will close unless I log in here: http://payroll-fix.example and enter my password.", "analysis", []),
    ("scan_content", "Your tax refund was approved. Submit your Aadhaar and bank details at http://tax-return.example today.", "analysis", []),
    ("scan_content", "The caller said they are from police cyber cell and I need to transfer my savings to a safe account now.", "analysis", []),
    ("scan_content", "hi pls chk this msg: ur parcel stuck pay 12rs here http://ship-track.example fast", "analysis", []),
    ("scan_content", "Your Microsoft account storage is full; verify your password at http://onedrive-renew.example to avoid deletion.", "analysis", []),
    ("scan_content", "Pay ₹2,000 to this UPI ID for the job interview slot, then send the transaction screenshot to the recruiter.", "analysis", []),
    ("scan_content", "Boss texted: buy five gift cards now and send me all the codes before the meeting starts.", "analysis", []),
]

def call_api(prompt: str, history: list) -> tuple[int, dict | None, str | None]:
    payload = {"message": prompt, "context": None, "context_signature": "", "history": history}
    request = urllib.request.Request(BASE_URL + "/api/v1/chat", data=json.dumps(payload).encode("utf-8"),
                                     headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            return response.status, json.loads(response.read().decode("utf-8")), None
    except urllib.error.HTTPError as exc:
        return exc.code, None, exc.read().decode("utf-8", "replace")
    except Exception as exc:
        return 0, None, f"{type(exc).__name__}: {exc}"

def run() -> int:
    RAW_PATH.parent.mkdir(parents=True, exist_ok=True)
    rows = []
    started = time.monotonic()
    for index, (category, prompt, expected, history) in enumerate(PROMPTS, 1):
        status, response, error = call_api(prompt, history)
        actual = (response or {}).get("kind")
        is_normal_chat = expected in {"chat", "followup"}
        mismatch = status != 200 or (actual not in {"chat", "followup"} if is_normal_chat else actual != "analysis")
        label = "FALSE_POSITIVE" if mismatch and is_normal_chat and actual == "analysis" else "FALSE_NEGATIVE" if mismatch and not is_normal_chat and actual != "analysis" else "HTTP_OR_SCHEMA_ERROR" if mismatch else None
        rows.append({"id": f"MR-{index:02d}", "category": category, "prompt": prompt, "history": history,
                     "expected_kind": expected, "actual_kind": actual, "http_status": status, "mismatch": mismatch,
                     "finding": label, "reply_text": (((response or {}).get("reply") or {}).get("text")) or "",
                     "response_type": (response or {}).get("response_type"), "intent": (response or {}).get("intent"),
                     "route": (response or {}).get("route"), "analysis": (response or {}).get("analysis"), "error": error})
        if index % 5 == 0 or index == len(PROMPTS):
            print(f"{index}/{len(PROMPTS)} completed (elapsed {time.monotonic()-started:.1f}s)", flush=True)
            RAW_PATH.write_text(json.dumps({"base_url": BASE_URL, "total": len(rows), "results": rows}, ensure_ascii=False, indent=2), encoding="utf-8")
    RAW_PATH.write_text(json.dumps({"base_url": BASE_URL, "total": len(rows), "results": rows}, ensure_ascii=False, indent=2), encoding="utf-8")
    write_report(rows)
    return 0

def write_report(rows: list[dict]) -> None:
    false_positive = sum(x["finding"] == "FALSE_POSITIVE" for x in rows)
    false_negative = sum(x["finding"] == "FALSE_NEGATIVE" for x in rows)
    lines = ["# Model v3 scanner-routing battery", "", f"Total prompts: {len(rows)}",
             f"Routing matches: {len(rows)-sum(x['mismatch'] for x in rows)}/{len(rows)}",
             f"False positives: {false_positive}; false negatives: {false_negative}", "", "## Every prompt", ""]
    for row in rows:
        lines += [f"### {row['id']} — {row['category']} — {row['finding'] or 'PASS'}", "",
                  f"Expected: `{row['expected_kind']}`; actual: `{row['actual_kind']}`; HTTP: `{row['http_status']}`", "",
                  "**Prompt**", "", row["prompt"], ""]
        if row["history"]:
            lines += ["**Prior turns**", "", "```json", json.dumps(row["history"], ensure_ascii=False, indent=2), "```", ""]
        if row["mismatch"]:
            lines += ["**Full response text**", "", row["reply_text"] or "(empty)", ""]
            if row.get("analysis"):
                lines += ["**Analysis object**", "", "```json", json.dumps(row["analysis"], ensure_ascii=False, indent=2), "```", ""]
            if row.get("error"):
                lines += ["**Error**", "", row["error"], ""]
    REPORT_PATH.write_text("\n".join(lines), encoding="utf-8")

if __name__ == "__main__":
    assert len(PROMPTS) == 35 and len({p[1] for p in PROMPTS}) == 35
    raise SystemExit(run())
