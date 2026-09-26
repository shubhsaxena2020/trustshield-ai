"""Measure real memory/time footprint of the API stack (RSS incl. child processes such as Tesseract).

Usage: python scripts/measure_footprint.py [--json out.json]
Each scenario runs in a fresh subprocess so peaks do not bleed into each other.
Requires psutil (dev-only). Reads only demo fixtures and synthetic data; no holdout data.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MB = 1024 * 1024

WORKER = r'''
import io, json, sys, threading, time
from pathlib import Path
import psutil
ROOT = Path(sys.argv[2]); sys.path.insert(0, str(ROOT))
scenario = sys.argv[1]
proc = psutil.Process()
peak = [0]; stop = threading.Event()
def tree_rss():
    total = proc.memory_info().rss
    for child in proc.children(recursive=True):
        try: total += child.memory_info().rss
        except psutil.Error: pass
    return total
def sample():
    while not stop.is_set():
        peak[0] = max(peak[0], tree_rss()); time.sleep(0.01)
threading.Thread(target=sample, daemon=True).start()
t0 = time.perf_counter()
out = {"scenario": scenario}
import services.api.main as api
out["import_s"] = round(time.perf_counter() - t0, 2)
out["rss_after_import_mb"] = round(proc.memory_info().rss / 1048576, 1)
if scenario != "idle":
    from fastapi.testclient import TestClient
    client = TestClient(api.app)
    t1 = time.perf_counter()
    health = client.get("/api/v1/health").json()   # loads the classifier
    out["ml_load_s"] = round(time.perf_counter() - t1, 2)
    out["ml_model"] = health["modules"]["ml"]["model_name"]
    out["rss_with_model_mb"] = round(proc.memory_info().rss / 1048576, 1)
    peak[0] = 0
    fx = ROOT / "demo" / "fixtures"
    if scenario == "text":
        for i in range(30):
            client.post("/api/v1/analyze/text", json={"text": f"URGENT {i}: your SBI account is blocked, share the OTP at http://sbi-kyc{i}.xyz " * 20})
    elif scenario == "ocr":
        for name in ("kyc_screenshot.png", "job_screenshot.png", "legit_screenshot.png"):
            r = client.post("/api/v1/analyze/image", files={"file": (name, (fx / name).read_bytes(), "image/png")}); assert r.status_code == 200, r.text
    elif scenario == "ocr_max":
        from PIL import Image, ImageDraw
        img = Image.new("RGB", (4900, 4900), "white"); d = ImageDraw.Draw(img)
        for row in range(0, 4800, 120): d.text((60, row + 20), "Your KYC expires today. Verify OTP at http://kyc-update.xyz now " * 3, fill="black")
        buf = io.BytesIO(); img.save(buf, "PNG"); data = buf.getvalue(); out["max_image_bytes"] = len(data)
        r = client.post("/api/v1/analyze/image", files={"file": ("max.png", data, "image/png")}); out["status"] = r.status_code
    elif scenario == "qr":
        for name in ("qr_upi.png", "qr_suspicious_url.png", "qr_benign.png", "qr_text.png"):
            r = client.post("/api/v1/analyze/qr", files={"file": (name, (fx / name).read_bytes(), "image/png")}); assert r.status_code == 200, r.text
out["peak_tree_rss_mb"] = round(max(peak[0], tree_rss()) / 1048576, 1)
stop.set()
print("RESULT " + json.dumps(out))
'''


def run(scenario: str) -> dict:
    started = time.perf_counter()
    proc = subprocess.run([sys.executable, "-c", WORKER, scenario, str(ROOT)], capture_output=True, text=True, cwd=ROOT, timeout=600)
    for line in proc.stdout.splitlines():
        if line.startswith("RESULT "):
            data = json.loads(line[7:])
            data["wall_s"] = round(time.perf_counter() - started, 1)
            return data
    return {"scenario": scenario, "error": (proc.stderr or proc.stdout)[-800:]}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()
    results = [run(name) for name in ("idle", "loaded", "text", "ocr", "ocr_max", "qr")]
    model = ROOT / "models" / "trained" / "trustshield_text_v3.joblib"
    report = {"python": sys.version.split()[0], "platform": sys.platform, "v3_artifact_mb": round(model.stat().st_size / MB, 2), "scenarios": results}
    print(json.dumps(report, indent=2))
    if args.json:
        args.json.write_text(json.dumps(report, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
