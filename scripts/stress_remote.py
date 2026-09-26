"""Resource gate for a deployed API: concurrent worst-case OCR uploads, durations, OOM/5xx detection, container memory counters.

    python scripts/stress_remote.py --base https://<site>.vercel.app --concurrency 4 [--bypass-token ...]

Builds the largest supported test image (24 MP, well inside the 25 MP pixel limit, small on disk) and fires it concurrently at
/api/v1/analyze/image. Memory counters come from /api/v1/debug/proxy, which exists only on a deployment started with
TRUSTSHIELD_DEBUG_PROXY=1 (Preview). Uses synthetic data only.
"""
from __future__ import annotations

import argparse
import io
import json
import os
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid


def make_image() -> bytes:
    from PIL import Image, ImageDraw

    img = Image.new("RGB", (4900, 4900), "white")
    draw = ImageDraw.Draw(img)
    for row in range(0, 4800, 120):
        draw.text((60, row + 20), "Your KYC expires today. Verify OTP at http://kyc-update.xyz now " * 3, fill="black")
    buffer = io.BytesIO()
    img.save(buffer, "PNG")
    return buffer.getvalue()


def post_image(base: str, data: bytes, headers: dict) -> tuple[int, float, str]:
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"max.png\"\r\nContent-Type: image/png\r\n\r\n").encode() \
        + data + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(base + "/api/v1/analyze/image", data=body, method="POST",
                                     headers={**headers, "Content-Type": f"multipart/form-data; boundary={boundary}"})
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=330) as response:
            response.read()
            return response.status, time.perf_counter() - started, response.headers.get("X-Request-ID", "")
    except urllib.error.HTTPError as error:
        return error.code, time.perf_counter() - started, error.headers.get("X-Request-ID", "")
    except Exception as error:  # connection cut, timeout
        return 0, time.perf_counter() - started, type(error).__name__


def memory(base: str, headers: dict) -> dict | None:
    try:
        request = urllib.request.Request(base + "/api/v1/debug/proxy", headers=headers)
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read()).get("memory")
    except Exception:
        return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--bypass-token", default=os.environ.get("VERCEL_AUTOMATION_BYPASS_SECRET", ""))
    args = parser.parse_args()
    base = args.base.rstrip("/")
    headers = {"x-vercel-protection-bypass": args.bypass_token} if args.bypass_token else {}
    data = make_image()
    print(f"test image: {len(data) / 1024:.0f} KiB (24 MP)")
    print("memory before:", memory(base, headers))
    results: list[tuple[int, float, str]] = []
    threads = [threading.Thread(target=lambda: results.append(post_image(base, data, headers))) for _ in range(args.concurrency)]
    started = time.perf_counter()
    [t.start() for t in threads]
    [t.join() for t in threads]
    wall = time.perf_counter() - started
    for status, seconds, rid in sorted(results):
        print(f"  status={status} duration={seconds:.1f}s request_id={rid}")
    print(f"wall {wall:.1f}s; median {statistics.median(s for _, s, _ in results):.1f}s")
    after = memory(base, headers)
    print("memory after:", after)
    failures = [r for r in results if r[0] not in {200, 429, 503}]
    if after and after.get("peak"):
        peak_mib = int(after["peak"]) / 1048576
        limit = int(after["max"]) / 1048576 if after.get("max") and after["max"].isdigit() else None
        print(f"container peak memory: {peak_mib:.0f} MiB" + (f" of {limit:.0f} MiB" if limit else ""))
    print("RESULT:", "no 5xx/timeouts/OOM" if not failures else f"unexpected outcomes: {failures}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
