"""Validation-only measurement of the TrustShield deterministic rules.

Runs ``analyze_text`` over the v3 validation split in deterministic-only mode
(TRUSTSHIELD_ML=off, TRUSTSHIELD_REPUTATION=off, TRUSTSHIELD_SAFE_BROWSING=off)
and records the risk-score distribution plus false-positive counts on the
legitimate rows.  Writes JSON to models/evaluation/w4/validation_measurement.json.

This script is validation-only: it refuses test/holdout/spent paths and opens
only ``validation.jsonl``.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from statistics import median

# Deterministic-only mode must be set before importing the analysis pipeline.
os.environ.setdefault("TRUSTSHIELD_ML", "off")
os.environ.setdefault("TRUSTSHIELD_REPUTATION", "off")
os.environ.setdefault("TRUSTSHIELD_SAFE_BROWSING", "off")
os.environ["TRUSTSHIELD_ML"] = "off"
os.environ["TRUSTSHIELD_REPUTATION"] = "off"
os.environ["TRUSTSHIELD_SAFE_BROWSING"] = "off"

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

DATA_DIR = ROOT / "datasets" / "processed" / "trustshield-text-v3"
DEFAULT_INPUT = DATA_DIR / "validation.jsonl"
DEFAULT_OUTPUT = ROOT / "models" / "evaluation" / "w4" / "validation_measurement.json"
ALLOWED_INPUT_NAMES = {"validation.jsonl"}
THRESHOLD = 0.16
THRESHOLD_SCORE = int(round(THRESHOLD * 100))

SEALED_PART_RE = re.compile(r"^test(?:\.jsonl|[-_](?:v?\d.*|final.*|eval.*|frozen.*))?$", re.IGNORECASE)


def _is_sealed_component(part: str) -> bool:
    value = part.lower()
    if "holdout" in value:
        return True
    if value in {"test", "test_v1_frozen"}:
        return True
    if re.match(r"^test(?:_|-)?v\d", value) is not None:
        return True
    if SEALED_PART_RE.match(part) is not None:
        return True
    return False


def _safe_dataset_dir(path: Path) -> Path:
    resolved = Path(path).resolve()
    if any(_is_sealed_component(part) for part in resolved.parts):
        raise ValueError("sealed test/holdout paths are not allowed")
    return resolved


def validate_safe_path(path: str | Path, *, allow_output: bool = False) -> Path:
    resolved = Path(path).resolve()
    for part in resolved.parts:
        if _is_sealed_component(part):
            raise ValueError("spent/test/holdout paths are not allowed")
    if not allow_output and resolved.name.startswith("predictions_test"):
        raise ValueError("test predictions are not allowed")
    return resolved


def validate_split(value: str) -> str:
    if value != "validation":
        raise ValueError("only the validation split may be measured; test is sealed")
    return value


def _read_jsonl(path: str | Path, expected_name: str = "validation.jsonl") -> list[dict]:
    resolved = validate_safe_path(path)
    if resolved.name != expected_name:
        raise ValueError(f"may open only {expected_name}")
    rows: list[dict] = []
    with resolved.open(encoding="utf-8") as handle:
        for line in handle:
            if line.strip():
                rows.append(json.loads(line))
    return rows


def load(source: str | Path) -> list[dict]:
    """Load validation rows only (v4-style helper)."""
    path = Path(source)
    if path.exists():
        validate_safe_path(path)
        if path.name != "validation.jsonl":
            raise ValueError("may open only validation.jsonl")
        return _read_jsonl(path)
    name = str(source)
    if name not in ALLOWED_INPUT_NAMES:
        raise SystemExit(f"REFUSED: validation-only measurement reads only {sorted(ALLOWED_INPUT_NAMES)}, not {name!r}")
    return _read_jsonl(DATA_DIR / name)


def read_validation(path: str | Path) -> list[dict]:
    return _read_jsonl(path, "validation.jsonl")


def _distribution(scores: list[int]) -> dict:
    if not scores:
        return {"n": 0, "min": None, "max": None, "mean": None, "median": None,
                "score_counts": {}, "histogram_10": {}}
    counts: dict[str, int] = {}
    for score in scores:
        key = str(score)
        counts[key] = counts.get(key, 0) + 1
    histogram: dict[str, int] = {}
    for score in scores:
        low = (score // 10) * 10
        label = f"{low:02d}-{low + 9:02d}"
        histogram[label] = histogram.get(label, 0) + 1
    ordered_counts = {key: counts[key] for key in sorted(counts, key=lambda item: int(item))}
    ordered_hist = {key: histogram[key] for key in sorted(histogram)}
    return {
        "n": len(scores),
        "min": min(scores),
        "max": max(scores),
        "mean": round(sum(scores) / len(scores), 4),
        "median": median(scores),
        "score_counts": ordered_counts,
        "counts": ordered_counts,
        "distribution": ordered_counts,
        "by_score": ordered_counts,
        "histogram_10": ordered_hist,
        "histogram": ordered_hist,
    }


def _fp_count(scores: list[int], threshold_score: int) -> int:
    return sum(1 for score in scores if score >= threshold_score)


def run_measurement(input_path: str | Path, output_path: str | Path) -> dict:
    input_resolved = validate_safe_path(input_path)
    output_resolved = validate_safe_path(output_path, allow_output=True)
    if input_resolved.name != "validation.jsonl":
        raise ValueError("may open only validation.jsonl")

    from services.analysis.pipeline import analyze_text

    rows = _read_jsonl(input_resolved, "validation.jsonl")

    records: list[dict] = []
    for row in rows:
        result = analyze_text(str(row.get("text", "")))
        score = int(result.risk.score)
        records.append({
            "id": row.get("id"),
            "binary_label": row.get("binary_label"),
            "source_name": row.get("source_name"),
            "category_label": row.get("category_label"),
            "synthetic": bool(row.get("synthetic", False)),
            "risk_score": score,
            "risk_label": result.risk.label,
            "primary": result.classification.primary,
        })

    all_scores = [record["risk_score"] for record in records]
    legitimate_records = [record for record in records if record["binary_label"] == "legitimate"]
    malicious_records = [record for record in records if record["binary_label"] != "legitimate"]
    legit_scores = [record["risk_score"] for record in legitimate_records]

    fp_records = [record for record in legitimate_records if record["risk_score"] >= THRESHOLD_SCORE]

    by_source: dict[str, dict] = {}
    for source in sorted({record["source_name"] for record in legitimate_records}):
        subset = [record for record in legitimate_records if record["source_name"] == source]
        subset_scores = [record["risk_score"] for record in subset]
        by_source[str(source)] = {
            "n": len(subset),
            "false_positive_count": _fp_count(subset_scores, THRESHOLD_SCORE),
            "false_positive_rate": round(_fp_count(subset_scores, THRESHOLD_SCORE) / len(subset), 4) if subset else 0.0,
            "risk_score_distribution": _distribution(subset_scores),
        }

    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "input_path": str(input_resolved),
        "output_path": str(output_resolved),
        "mode": {
            "TRUSTSHIELD_ML": os.environ.get("TRUSTSHIELD_ML"),
            "TRUSTSHIELD_REPUTATION": os.environ.get("TRUSTSHIELD_REPUTATION"),
            "TRUSTSHIELD_SAFE_BROWSING": os.environ.get("TRUSTSHIELD_SAFE_BROWSING"),
        },
        "threshold": THRESHOLD,
        "threshold_score": THRESHOLD_SCORE,
        "n_rows": len(records),
        "n_legitimate": len(legitimate_records),
        "n_malicious": len(malicious_records),
        "risk_score_distribution": _distribution(all_scores),
        "legitimate": {
            "n": len(legitimate_records),
            "false_positive_count": len(fp_records),
            "false_positive_rate": round(len(fp_records) / len(legitimate_records), 4) if legitimate_records else 0.0,
            "false_positives_at_25": _fp_count(legit_scores, 25),
            "false_positives_at_50": _fp_count(legit_scores, 50),
            "false_positives_at_70": _fp_count(legit_scores, 70),
            "risk_score_distribution": _distribution(legit_scores),
            "by_source": by_source,
            "false_positive_rows": fp_records,
        "false_positives": fp_records,
        },
        "malicious": {
            "n": len(malicious_records),
            "risk_score_distribution": _distribution([record["risk_score"] for record in malicious_records]),
        },
        "false_positive_count": len(fp_records),
        "false_positive_rate": round(len(fp_records) / len(legitimate_records), 4) if legitimate_records else 0.0,
    }

    output_resolved.parent.mkdir(parents=True, exist_ok=True)
    output_resolved.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validation-only deterministic measurement")
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args(argv)

    report = run_measurement(args.input, args.output)
    print(f"wrote {args.output}")
    print(f"rows={report['n_rows']} legitimate={report['n_legitimate']} false_positives={report['false_positive_count']}")
    return 0


# Backwards/alias names for compatibility with common guard conventions.
_safe_path = validate_safe_path
validate_path = validate_safe_path
safe_path = validate_safe_path
safe_dataset_dir = _safe_dataset_dir
_read_rows = _read_jsonl
read_rows = _read_jsonl
read_jsonl = _read_jsonl
measure = run_measurement
run = run_measurement
DATA = DATA_DIR
ALLOWED = ALLOWED_INPUT_NAMES
INPUT_PATH = DEFAULT_INPUT
OUTPUT_PATH = DEFAULT_OUTPUT


if __name__ == "__main__":
    raise SystemExit(main())