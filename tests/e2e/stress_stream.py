"""Stress test for TrustShield conversational SSE chat stream endpoint on production.

Endpoint: POST https://trustshield-ai-ecru.vercel.app/api/v1/chat/stream
Strict requirement: Python standard library urllib only (no requests, no httpx).
"""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import math
import sys
import time
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass
from typing import Any, List, Optional

URL = "https://trustshield-ai-ecru.vercel.app/api/v1/chat/stream"

MESSAGES: list[str] = [
    "what makes you different from any other ai like chatgpt?",
    "tell me about yourself",
    "what is phishing?",
    "who made you? tell me everything about the team",
    "Is this safe? URGENT your SBI account is blocked, share OTP at http://sbi-kyc-update.xyz/login",
    "write a poem about rain",
]


@dataclass
class RequestRecord:
    req_id: int
    message: str
    status: Optional[int]
    ttfe: Optional[float]
    total_time: float
    final_event_type: Optional[str]
    exception_type: Optional[str]
    exception_message: Optional[str]
    events_count: int
    is_failure: bool


def compute_percentile(data: list[float], p: float) -> float:
    if not data:
        return 0.0
    s = sorted(data)
    k = (len(s) - 1) * (p / 100.0)
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return s[int(k)]
    return s[int(f)] * (c - k) + s[int(c)] * (k - f)


def send_stream_request(req_id: int, message: str, timeout: float = 90.0) -> RequestRecord:
    start_time = time.perf_counter()
    first_event_time: Optional[float] = None
    final_event_type: Optional[str] = None
    events_count = 0
    status: Optional[int] = None
    exception_type: Optional[str] = None
    exception_message: Optional[str] = None

    payload = json.dumps({
        "message": message,
        "history": [],
        "context": None,
        "context_signature": "",
    }).encode("utf-8")

    req = urllib.request.Request(
        URL,
        data=payload,
        headers={
            "Content-Type": "application/json",
            "User-Agent": "TrustShield-StressTester/1.0",
        },
    )

    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            status = resp.status
            for raw_line in resp:
                line = raw_line.decode("utf-8", errors="replace").strip()
                if line.startswith("data: "):
                    if first_event_time is None:
                        first_event_time = time.perf_counter()
                    events_count += 1
                    data_str = line[6:].strip()
                    try:
                        event = json.loads(data_str)
                        if isinstance(event, dict) and "type" in event:
                            final_event_type = event["type"]
                    except Exception:
                        pass
    except urllib.error.HTTPError as e:
        status = e.code
        exception_type = f"HTTPError({e.code})"
        body_snippet = ""
        try:
            raw_body = e.read().decode("utf-8", errors="replace").strip()
            if raw_body:
                body_snippet = f" | body: {raw_body[:150]}"
        except Exception:
            pass
        exception_message = f"{e}{body_snippet}"
    except urllib.error.URLError as e:
        exception_type = "URLError"
        exception_message = str(e.reason) if hasattr(e, "reason") else str(e)
    except TimeoutError as e:
        exception_type = "TimeoutError"
        exception_message = str(e)
    except Exception as e:
        exception_type = type(e).__name__
        exception_message = str(e)

    total_time = time.perf_counter() - start_time
    ttfe = (first_event_time - start_time) if first_event_time is not None else None

    # Determine failure
    is_failure = False
    if exception_type is not None:
        is_failure = True
    elif status != 200:
        is_failure = True
    elif final_event_type == "error":
        is_failure = True
    elif final_event_type != "done":
        is_failure = True

    return RequestRecord(
        req_id=req_id,
        message=message,
        status=status,
        ttfe=ttfe,
        total_time=total_time,
        final_event_type=final_event_type,
        exception_type=exception_type,
        exception_message=exception_message,
        events_count=events_count,
        is_failure=is_failure,
    )


def print_table(records: list[RequestRecord]) -> None:
    headers = ["#", "Message (snippet)", "HTTP", "TTFE (s)", "Total (s)", "Final Evt", "Exception Type", "Exception Message"]
    col_widths = [4, 30, 6, 10, 10, 10, 18, 45]

    row_fmt = " | ".join(f"{{:<{w}}}" for w in col_widths)
    sep_line = "-+-".join("-" * w for w in col_widths)

    print(row_fmt.format(*headers))
    print(sep_line)

    for r in records:
        msg_snip = (r.message[:27] + "...") if len(r.message) > 30 else r.message
        http_str = str(r.status) if r.status is not None else "None"
        ttfe_str = f"{r.ttfe:.3f}" if r.ttfe is not None else "None"
        tot_str = f"{r.total_time:.3f}"
        evt_str = str(r.final_event_type) if r.final_event_type is not None else "None"
        exc_type = r.exception_type if r.exception_type is not None else "None"
        exc_msg = (r.exception_message[:42] + "...") if r.exception_message and len(r.exception_message) > 45 else (r.exception_message or "None")

        print(row_fmt.format(r.req_id, msg_snip, http_str, ttfe_str, tot_str, evt_str, exc_type, exc_msg))


def print_summary(concurrency: int, records: list[RequestRecord]) -> dict[str, Any]:
    total_count = len(records)
    failed_records = [r for r in records if r.is_failure]
    failure_count = len(failed_records)
    success_count = total_count - failure_count

    # Failure categorization by exception type
    failure_by_exc: dict[str, int] = {}
    for r in failed_records:
        if r.exception_type:
            cat = r.exception_type
        elif r.final_event_type == "error":
            cat = "No exception (event type: error)"
        elif r.final_event_type != "done":
            cat = f"No exception (stream truncated, last={r.final_event_type})"
        else:
            cat = f"No exception (status={r.status})"
        failure_by_exc[cat] = failure_by_exc.get(cat, 0) + 1

    total_times = [r.total_time for r in records]
    p50_total = compute_percentile(total_times, 50.0)
    p95_total = compute_percentile(total_times, 95.0)

    ttfe_vals = [r.ttfe for r in records if r.ttfe is not None]
    p50_ttfe = compute_percentile(ttfe_vals, 50.0) if ttfe_vals else 0.0
    p95_ttfe = compute_percentile(ttfe_vals, 95.0) if ttfe_vals else 0.0

    print("\n" + "=" * 60)
    print(f"SUMMARY FOR CONCURRENCY {concurrency}")
    print("=" * 60)
    print(f"Total Requests:       {total_count}")
    print(f"Successful Requests:  {success_count}")
    print(f"Failed Requests:      {failure_count}")
    print("\nCount of Failures by Exception Type:")
    if failure_by_exc:
        for exc, count in sorted(failure_by_exc.items(), key=lambda x: x[1], reverse=True):
            print(f"  - {exc}: {count}")
    else:
        print("  None (0 failures)")

    print("\nTiming Metrics (Total Time):")
    print(f"  - Min Total Time:   {min(total_times):.3f}s" if total_times else "  - Min Total Time:   N/A")
    print(f"  - Max Total Time:   {max(total_times):.3f}s" if total_times else "  - Max Total Time:   N/A")
    print(f"  - p50 Total Time:   {p50_total:.3f}s")
    print(f"  - p95 Total Time:   {p95_total:.3f}s")

    print("\nTiming Metrics (TTFE - Time To First Event):")
    print(f"  - Valid TTFE Count: {len(ttfe_vals)}/{total_count}")
    print(f"  - p50 TTFE:         {p50_ttfe:.3f}s")
    print(f"  - p95 TTFE:         {p95_ttfe:.3f}s")
    print("=" * 60 + "\n")

    return {
        "concurrency": concurrency,
        "total_count": total_count,
        "success_count": success_count,
        "failure_count": failure_count,
        "failures_by_exception": failure_by_exc,
        "p50_total_time": p50_total,
        "p95_total_time": p95_total,
        "p50_ttfe": p50_ttfe,
        "p95_ttfe": p95_ttfe,
        "records": [asdict(r) for r in records],
    }


def run_test_suite(concurrency: int, total_requests: int = 60, timeout: float = 90.0) -> dict[str, Any]:
    print(f"\n>>> Starting stress run: {total_requests} requests across {len(MESSAGES)} messages with CONCURRENCY = {concurrency} ...")

    # Generate requests: 60 requests rotating across the 6 messages
    items = [(i + 1, MESSAGES[i % len(MESSAGES)]) for i in range(total_requests)]
    records: list[RequestRecord] = []

    if concurrency == 1:
        for idx, msg in items:
            rec = send_stream_request(idx, msg, timeout=timeout)
            records.append(rec)
            status_str = f"status={rec.status}" if rec.status is not None else "NO_STATUS"
            err_str = f" [ERR: {rec.exception_type}]" if rec.is_failure else ""
            print(f"[{idx:02d}/{total_requests:02d}] {status_str} | TTFE={rec.ttfe if rec.ttfe else 0.0:.2f}s | Tot={rec.total_time:.2f}s | last={rec.final_event_type}{err_str}")
    else:
        with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
            future_to_id = {
                executor.submit(send_stream_request, idx, msg, timeout): idx
                for idx, msg in items
            }
            done_count = 0
            for future in concurrent.futures.as_completed(future_to_id):
                done_count += 1
                rec = future.result()
                records.append(rec)
                status_str = f"status={rec.status}" if rec.status is not None else "NO_STATUS"
                err_str = f" [ERR: {rec.exception_type}]" if rec.is_failure else ""
                print(f"[{done_count:02d}/{total_requests:02d} done] (Req #{rec.req_id:02d}) {status_str} | TTFE={rec.ttfe if rec.ttfe else 0.0:.2f}s | Tot={rec.total_time:.2f}s | last={rec.final_event_type}{err_str}")

    records.sort(key=lambda r: r.req_id)
    print("\nDetailed Request Table:")
    print_table(records)
    summary_data = print_summary(concurrency, records)
    return summary_data


def main() -> int:
    parser = argparse.ArgumentParser(description="Stress test TrustShield chat stream endpoint.")
    parser.add_argument("--concurrency", type=int, choices=[1, 4], default=None, help="Run specific concurrency (1 or 4). Default runs both sequentially.")
    parser.add_argument("--requests", type=int, default=60, help="Number of requests to send (default: 60).")
    parser.add_argument("--timeout", type=float, default=90.0, help="Per-request timeout in seconds (default: 90.0).")
    parser.add_argument("--save-json", type=str, default=None, help="Optional path to save full results as JSON.")
    args = parser.parse_args()

    results: list[dict[str, Any]] = []

    if args.concurrency is not None:
        concurrency_levels = [args.concurrency]
    else:
        # Instruction: "Run them with concurrency 1, then with concurrency 4."
        concurrency_levels = [1, 4]

    for c in concurrency_levels:
        res = run_test_suite(concurrency=c, total_requests=args.requests, timeout=args.timeout)
        results.append(res)

    if len(results) > 1:
        print("\n" + "#" * 60)
        print("OVERALL COMPARISON SUMMARY")
        print("#" * 60)
        for res in results:
            print(f"Concurrency {res['concurrency']}: "
                  f"Total={res['total_count']}, Success={res['success_count']}, Failures={res['failure_count']}, "
                  f"p50={res['p50_total_time']:.2f}s, p95={res['p95_total_time']:.2f}s, "
                  f"p50_TTFE={res['p50_ttfe']:.2f}s, p95_TTFE={res['p95_ttfe']:.2f}s")
        print("#" * 60 + "\n")

    if args.save_json:
        with open(args.save_json, "w", encoding="utf-8") as f:
            json.dump(results, f, indent=2)
        print(f"Results saved to {args.save_json}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
