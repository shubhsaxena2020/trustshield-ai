"""Bounded rate-limit verification: confirms the existing light/heavy limiters actually return
429s under pressure. NEVER point this at the live production URL -- local/staging only.

Requests are fired concurrently (a thread pool), not one at a time -- a sequential loop is too
slow to generate real burst pressure against a per-minute limiter and will falsely read as "no
429s" just because it couldn't send requests fast enough.

Usage: python scripts/security/rate_limit_check.py --base-url http://localhost:8000 --requests 150
"""
from __future__ import annotations

import argparse
import threading
import time
import urllib.error
import urllib.request


def _one(base_url: str, path: str) -> int:
    req = urllib.request.Request(f"{base_url}{path}", method="GET")
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status
    except urllib.error.HTTPError as exc:
        return exc.code
    except Exception:
        return -1


def burst(base_url: str, path: str, count: int, concurrency: int = 20) -> dict[int, int]:
    codes: dict[int, int] = {}
    lock = threading.Lock()

    def worker():
        status = _one(base_url, path)
        with lock:
            codes[status] = codes.get(status, 0) + 1

    threads = []
    for i in range(count):
        t = threading.Thread(target=worker)
        t.start()
        threads.append(t)
        if len(threads) >= concurrency:
            threads.pop(0).join()
    for t in threads:
        t.join()
    return codes


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8000")
    parser.add_argument("--requests", type=int, default=150)
    args = parser.parse_args()
    if "localhost" not in args.base_url and "127.0.0.1" not in args.base_url:
        raise SystemExit("refusing: this script only targets local/staging, never a production URL")
    # /api/v1/history carries the rate limiter (limit_light); /api/v1/health intentionally does
    # not, so it isn't useful for this check.
    start = time.monotonic()
    codes = burst(args.base_url, "/api/v1/history", args.requests)
    print(f"{args.requests} requests in {time.monotonic() - start:.1f}s -> {codes}")
