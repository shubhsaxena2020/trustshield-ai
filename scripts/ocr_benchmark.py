"""Run the real-Tesseract OCR validation benchmark over the synthetic corpus."""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from services.analysis.extractors.ocr import TesseractProvider, resolve_tesseract_cmd
from services.analysis.extractors.ocr_metrics import (
    aggregate_scores,
    check_critical_tokens,
    score_text,
    word_diff,
)
from services.analysis.pipeline import analyze_image
from scripts.make_demo_fixtures import visible_transcription


CORPUS = ROOT / "benchmarks" / "ocr" / "corpus"
RESULTS = ROOT / "benchmarks" / "ocr" / "results.json"
MODES = ["none", "current", "gray", "gray_autocontrast", "gray_resize2x"]
DEMO_IDS = ["kyc_screenshot", "job_screenshot", "legit_screenshot"]
DIAGNOSTIC_IDS = ["small_text", "hard_small_compressed_lowres", "low_contrast"]


@contextmanager
def temporary_env(**changes: str | None) -> Iterator[None]:
    previous = {key: os.environ.get(key) for key in changes}
    try:
        for key, value in changes.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        yield
    finally:
        for key, value in previous.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


def _mime(path: Path) -> str:
    return {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg"}[path.suffix.lower()]


def _scoped_word_diff(diff: dict, reference: str) -> dict[str, dict[str, list]]:
    reference_lines = [line for line in reference.splitlines() if line.strip()]
    header_words = reference_lines[0].split() if reference_lines else []
    header_word_count = len(header_words)
    scopes = {"ui_chrome": {"missed": [], "corrupted": [], "inserted": []},
              "message": {"missed": [], "corrupted": [], "inserted": []}}
    reference_index = 0
    for item in diff["alignment"]:
        operation = item["operation"]
        scope = "ui_chrome" if reference_index < header_word_count else "message"
        if operation == "delete":
            scopes[scope]["missed"].append(item["expected"])
        elif operation == "substitute":
            scopes[scope]["corrupted"].append({"expected": item["expected"], "actual": item["actual"]})
        elif operation == "insert":
            scopes[scope]["inserted"].append(item["actual"])
        if item["expected"] is not None:
            reference_index += 1
    return scopes


def _fixture_records(manifest: dict, mode: str) -> list[dict]:
    records = []
    with temporary_env(TRUSTSHIELD_OCR_PROVIDER=None, TRUSTSHIELD_OCR_PREPROCESS=mode):
        for fixture in sorted(manifest["fixtures"], key=lambda item: item["id"]):
            image_path = CORPUS / fixture["file"]
            reference = (CORPUS / f"{fixture['id']}.txt").read_text(encoding="utf-8")
            payload = image_path.read_bytes()
            started = time.perf_counter()
            result = analyze_image(payload, _mime(image_path), image_path.name)
            runtime_ms = (time.perf_counter() - started) * 1000
            ocr = result.modules["ocr"]
            data = ocr.data
            raw_text = data.get("raw_text", "")
            metrics = score_text(reference, raw_text)
            token_results = check_critical_tokens(raw_text, fixture.get("critical_tokens", []))
            diff = word_diff(reference, raw_text)
            records.append({
                "id": fixture["id"],
                "file": fixture["file"],
                "reference_text": reference,
                "raw_text": raw_text,
                "normalized_text": data.get("normalized_text", ""),
                "confidence": data.get("confidence"),
                "provider": data.get("provider"),
                "provider_version": data.get("provider_version"),
                "tesseract_cmd_source": data.get("tesseract_cmd_source"),
                "status": ocr.status,
                "detail": ocr.detail,
                "preprocessing": data.get("preprocessing"),
                "runtime_ms": runtime_ms,
                **metrics,
                "word_diff": diff,
                "word_diff_by_scope": _scoped_word_diff(diff, reference),
                "token_results": token_results,
                "analysis": {
                    "status": result.status,
                    "label": result.risk.label,
                    "score": result.risk.score,
                    "confidence": result.risk.confidence,
                    "evidence_categories": sorted({item.category for item in result.evidence}),
                },
            })
    if any(record["status"] != "completed" for record in records):
        raise RuntimeError(f"Real Tesseract did not complete every fixture in mode {mode}.")
    return records


def _token_aggregate(records: list[dict]) -> dict:
    grouped: dict[str, list[dict]] = {}
    for record in records:
        for item in record["token_results"]["tokens"]:
            grouped.setdefault(item["category"], []).append(item)

    def rates(items: list[dict]) -> dict[str, float | int]:
        return {
            "total": len(items),
            "exact": sum(item["exact"] for item in items) / len(items) if items else 0.0,
            "lenient": sum(item["lenient"] for item in items) / len(items) if items else 0.0,
        }

    all_items = [item for items in grouped.values() for item in items]
    return {**{category: rates(items) for category, items in sorted(grouped.items())}, "overall": rates(all_items)}


def _tesseract_metadata() -> dict:
    configured = os.getenv("TESSERACT_CMD", "").strip()
    if not configured:
        raise SystemExit("OCR benchmark requires TESSERACT_CMD to be set; refusing PATH/default fallback.")
    resolved, source = resolve_tesseract_cmd()
    if not resolved or source != "env:TESSERACT_CMD":
        raise SystemExit("OCR benchmark could not resolve the configured TESSERACT_CMD binary.")
    try:
        version_run = subprocess.run([resolved, "--version"], capture_output=True, text=True, timeout=10, check=True)
        languages_run = subprocess.run([resolved, "--list-langs"], capture_output=True, text=True, timeout=10, check=True)
    except (OSError, subprocess.SubprocessError) as exc:
        raise SystemExit(f"OCR benchmark could not execute TESSERACT_CMD: {exc}") from exc
    version = next((line.strip() for line in (version_run.stdout + "\n" + version_run.stderr).splitlines() if line.strip()), "unknown")
    languages = sorted(line.strip() for line in languages_run.stdout.splitlines()[1:] if line.strip())
    provider = TesseractProvider()
    if not provider.is_available():
        raise SystemExit(f"OCR benchmark could not use Tesseract at {resolved}.")
    return {
        "tesseract_cmd": resolved,
        "tesseract_cmd_source": source,
        "tesseract_version": version,
        "language_data": languages,
        "preprocessing_modes": MODES,
        "default_preprocessing": "current",
        "timeout_seconds": float(os.getenv("TRUSTSHIELD_OCR_TIMEOUT", "30")),
    }


def _demo_result(path: Path, provider: str) -> dict:
    with Image.open(path) as image:
        ground_truth = str(image.info.get("trustshield_demo_text", ""))
    selected_provider = None if provider == "real" else provider
    with temporary_env(TRUSTSHIELD_OCR_PROVIDER=selected_provider, TRUSTSHIELD_OCR_PREPROCESS="current"):
        result = analyze_image(path.read_bytes(), "image/png", path.name)
    ocr = result.modules["ocr"]
    raw_text = ocr.data.get("raw_text", "")
    full_ground_truth = visible_transcription(ground_truth)
    diff = word_diff(full_ground_truth, raw_text)
    return {
        "ground_truth": full_ground_truth,
        "message_ground_truth": ground_truth,
        "raw_text": raw_text,
        "normalized_text": ocr.data.get("normalized_text", ""),
        "confidence": ocr.data.get("confidence"),
        "status": ocr.status,
        "word_diff": diff,
        "word_diff_by_scope": _scoped_word_diff(diff, full_ground_truth),
        "label": result.risk.label,
        "score": result.risk.score,
        "risk_confidence": result.risk.confidence,
        "evidence_categories": sorted({item.category for item in result.evidence}),
    }


def _demo_records() -> list[dict]:
    records = []
    for demo_id in DEMO_IDS:
        path = ROOT / "demo" / "fixtures" / f"{demo_id}.png"
        if not path.exists():
            raise SystemExit(f"Missing required demo fixture: {path}")
        real = _demo_result(path, "real")
        mock = _demo_result(path, "mock")
        real_categories = set(real["evidence_categories"])
        mock_categories = set(mock["evidence_categories"])
        records.append({
            "id": demo_id,
            "real": real,
            "mock": mock,
            "real_vs_mock": {
                "categories_added_in_real": sorted(real_categories - mock_categories),
                "categories_missing_in_real": sorted(mock_categories - real_categories),
                "score_delta_real_minus_mock": real["score"] - mock["score"],
            },
        })
    return records


def _print_summary(results: dict) -> None:
    print("# OCR benchmark summary")
    print("\n| mode | WER micro | WER macro | CER micro | CER macro |")
    print("|---|---:|---:|---:|---:|")
    for mode in MODES:
        aggregate = results["modes"][mode]["aggregate"]
        print(f"| {mode} | {aggregate['wer']['micro']:.6f} | {aggregate['wer']['macro']:.6f} | {aggregate['cer']['micro']:.6f} | {aggregate['cer']['macro']:.6f} |")
    print("\n| mode | token category | exact | lenient | n |")
    print("|---|---|---:|---:|---:|")
    for mode in MODES:
        for category, values in results["modes"][mode]["token_preservation"].items():
            print(f"| {mode} | {category} | {values['exact']:.6f} | {values['lenient']:.6f} | {values['total']} |")
    print("\n| demo | real label | mock label | real score | mock score | delta |")
    print("|---|---|---|---:|---:|---:|")
    for demo in results["demos"]:
        print(f"| {demo['id']} | {demo['real']['label']} | {demo['mock']['label']} | {demo['real']['score']} | {demo['mock']['score']} | {demo['real_vs_mock']['score_delta_real_minus_mock']} |")
    print("\n## Raw OCR diagnostics")
    for mode in MODES:
        for fixture_id in DIAGNOSTIC_IDS:
            record = next(record for record in results["modes"][mode]["fixtures"] if record["id"] == fixture_id)
            print(f"[{mode}/{fixture_id}] {record['raw_text']!r}")


def main() -> None:
    metadata = _tesseract_metadata()
    manifest_path = CORPUS / "manifest.json"
    if not manifest_path.exists():
        raise SystemExit("OCR corpus is missing; run scripts/make_ocr_corpus.py first.")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    modes = {}
    for mode in MODES:
        records = _fixture_records(manifest, mode)
        modes[mode] = {
            "fixtures": records,
            "aggregate": aggregate_scores(records),
            "token_preservation": _token_aggregate(records),
        }
    results = {
        "synthetic_corpus": True,
        "corpus_fixture_count": len(manifest["fixtures"]),
        "environment": metadata,
        "modes": modes,
        "demos": _demo_records(),
    }
    RESULTS.parent.mkdir(parents=True, exist_ok=True)
    RESULTS.write_text(json.dumps(results, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    _print_summary(results)
    print(f"\nWrote {RESULTS}")


if __name__ == "__main__":
    main()
