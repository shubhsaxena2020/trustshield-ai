"""One-time, approval-gated v1-vs-v3 evaluation on the sealed v2 holdout.

Criteria are pre-registered in docs/V2_PROMOTION_CRITERIA.md and encoded in
`evaluate_criteria` below. This script must NOT be run without explicit project
lead approval (--confirm-final-holdout). It refuses to run twice, verifies the
frozen release-candidate hashes first, and logs access before reading any row.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import statistics
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

CAUTION = 25
REAL_SOURCES = {"mendeley_financial_scams", "reportsmishing_imc25"}
MIN_SLICE = 30
BOOTSTRAP = 2000
SEED = 42


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _metrics(y: list[int], s: list[int]) -> dict:
    tp = sum(a == 1 and b == 1 for a, b in zip(y, s))
    tn = sum(a == 0 and b == 0 for a, b in zip(y, s))
    fp = sum(a == 0 and b == 1 for a, b in zip(y, s))
    fn = sum(a == 1 and b == 0 for a, b in zip(y, s))
    p = tp / (tp + fp) if tp + fp else 0.0
    r = tp / (tp + fn) if tp + fn else 0.0
    return {"n": len(y), "tp": tp, "tn": tn, "fp": fp, "fn": fn, "recall": r if tp + fn else None,
            "fpr": fp / (fp + tn) if fp + tn else None, "precision": p,
            "f1": 2 * p * r / (p + r) if p + r else 0.0}


def _auc_brier(y: list[int], p: list[float]) -> dict:
    from sklearn.metrics import brier_score_loss, roc_auc_score
    return {"roc_auc": float(roc_auc_score(y, p)), "brier": float(brier_score_loss(y, p))}


def _slices(rows: list[dict]) -> dict[str, list[int]]:
    idx = range(len(rows))
    real = lambda i: rows[i]["source_name"] in REAL_SOURCES  # noqa: E731
    legit = lambda i: rows[i]["y"] == 0  # noqa: E731
    return {
        "ALL": list(idx),
        "REAL": [i for i in idx if real(i)],
        "REAL_LEGIT": [i for i in idx if real(i) and legit(i)],
        "REAL_FRAUD": [i for i in idx if real(i) and not legit(i)],
        "SYNTHETIC": [i for i in idx if not real(i)],
    }


def slice_metrics(rows: list[dict], flags: list[int], indexes: list[int]) -> dict:
    return _metrics([rows[i]["y"] for i in indexes], [flags[i] for i in indexes])


def paired_fpr_ci(rows, flags_v1, flags_v3, indexes) -> dict:
    legit = [i for i in indexes if rows[i]["y"] == 0]
    rng, diffs = random.Random(SEED), []
    for _ in range(BOOTSTRAP):
        sample = [rng.choice(legit) for _ in legit]
        diffs.append(sum(flags_v3[i] for i in sample) / len(sample) - sum(flags_v1[i] for i in sample) / len(sample))
    diffs.sort()
    return {"mean": statistics.fmean(diffs), "ci95": [diffs[int(0.025 * BOOTSTRAP)], diffs[int(0.975 * BOOTSTRAP) - 1]]}


def evaluate_criteria(m1: dict, m3: dict, real_fpr_ci: dict, aux: dict) -> dict:
    """m1/m3: slice -> metrics. aux: {'v1': {roc_auc, brier, median_ms}, 'v3': {...}}."""
    def small(name):
        return m1[name]["n"] < MIN_SLICE
    checks = {
        "C1_recall_all_drop_le_0.02": m3["ALL"]["recall"] >= m1["ALL"]["recall"] - 0.02,
        "C1_recall_real_fraud_drop_le_0.02": m3["REAL_FRAUD"]["recall"] >= m1["REAL_FRAUD"]["recall"] - 0.02,
        "C2_real_legit_fpr_not_higher": m3["REAL_LEGIT"]["fpr"] <= m1["REAL_LEGIT"]["fpr"] and real_fpr_ci["ci95"][1] <= 0.03,
        "C3_overall_fpr_increase_le_0.01": m3["ALL"]["fpr"] <= m1["ALL"]["fpr"] + 0.01,
        "C4_real_gain": (m1["REAL_LEGIT"]["fpr"] - m3["REAL_LEGIT"]["fpr"] >= 0.02) or (m3["REAL"]["f1"] - m1["REAL"]["f1"] >= 0.02),
        "C5_auc_drop_le_0.01": aux["v3"]["roc_auc"] >= aux["v1"]["roc_auc"] - 0.01,
        "C5_brier_increase_le_0.01": aux["v3"]["brier"] <= aux["v1"]["brier"] + 0.01,
        "C6_median_latency_le_2x": aux["v3"]["median_ms"] <= 2 * max(aux["v1"]["median_ms"], 1e-6),
    }
    if any(small(name) for name in ("REAL_LEGIT", "REAL_FRAUD", "REAL")):
        checks["slice_size_ok"] = False
    return {"checks": checks, "verdict": "PROMOTE" if all(checks.values()) else "DO_NOT_PROMOTE"}


def score_model(model_name: str, rows: list[dict]) -> tuple[list[int], list[float], list[float], int]:
    os.environ["TRUSTSHIELD_ML_MODEL"] = "" if model_name == "trustshield_text_v1" else model_name
    os.environ["TRUSTSHIELD_ML"] = "on"
    from services.analysis import pipeline
    from services.analysis.ml.classifier import TextClassifier
    pipeline._text_classifier.cache_clear()
    classifier = TextClassifier(model_name)
    if not classifier.available:
        raise RuntimeError(f"{model_name} unavailable: {getattr(classifier, 'reason', '')}")
    flags, probs, latencies, errors = [], [], [], 0
    for row in rows:
        started = time.perf_counter()
        try:
            result = pipeline.analyze_text(row["text"])
            flags.append(int(result.risk.score >= CAUTION))
        except Exception:
            flags.append(0)
            errors += 1
        latencies.append((time.perf_counter() - started) * 1000.0)
        probs.append(float(classifier.predict(row["text"]).p_malicious))
    return flags, probs, latencies, errors


def main(argv: list[str] | None = None) -> int:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from scripts._v2_spent_guard import require_archival_override
    argv = require_archival_override(argv)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--holdout", type=Path, required=True)
    parser.add_argument("--rc-manifest", type=Path, required=True)
    parser.add_argument("--access-log", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, default=ROOT / "models/evaluation/v2_final")
    parser.add_argument("--confirm-final-holdout", action="store_true")
    args = parser.parse_args(argv)
    if not args.confirm_final_holdout:
        print("Refusing: pass --confirm-final-holdout only after explicit project-lead approval.", file=sys.stderr)
        return 2
    log = json.loads(args.access_log.read_text(encoding="utf-8")) if args.access_log.exists() else []
    if log:
        print("Refusing: the v2 holdout has already been accessed; it is one-time.", file=sys.stderr)
        return 2
    rc = json.loads(args.rc_manifest.read_text(encoding="utf-8"))
    for name, expected in rc["frozen_sha256"].items():
        actual = sha256(ROOT / name)
        if actual != expected:
            print(f"Refusing: {name} hash {actual} != frozen {expected}", file=sys.stderr)
            return 2
    log.append({"timestamp": datetime.now(timezone.utc).isoformat(), "holdout_sha256": sha256(args.holdout),
                "rc_commit": rc["commit"], "command": " ".join([sys.executable, *sys.argv])})
    args.access_log.write_text(json.dumps(log, indent=2) + "\n", encoding="utf-8")

    rows = []
    for line in args.holdout.read_text(encoding="utf-8").splitlines():
        if line.strip():
            item = json.loads(line)
            rows.append({"text": item["text"], "y": int(item["binary_label"] != "legitimate"), "source_name": item["source_name"]})
    slices = _slices(rows)
    y = [r["y"] for r in rows]
    per, aux, flags = {}, {}, {}
    for tag, model in (("v1", "trustshield_text_v1"), ("v3", "trustshield_text_v3")):
        f, p, lat, errors = score_model(model, rows)
        flags[tag] = f
        per[tag] = {name: slice_metrics(rows, f, idx) for name, idx in slices.items()}
        aux[tag] = {**_auc_brier(y, p), "median_ms": statistics.median(lat), "row_errors": errors}
    ci = paired_fpr_ci(rows, flags["v1"], flags["v3"], slices["REAL_LEGIT"])
    outcome = evaluate_criteria(per["v1"], per["v3"], ci, aux)
    args.out_dir.mkdir(parents=True, exist_ok=True)
    report = {"rc": rc, "metrics": per, "aux": aux, "real_legit_fpr_paired_bootstrap": ci, **outcome}
    (args.out_dir / "results.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({"verdict": outcome["verdict"], "checks": outcome["checks"]}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
