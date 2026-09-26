"""Compare the frozen v1 system with experimental v3 on the exact v1 validation.

The script is validation-only.  It rejects test and holdout paths before any
file is opened and uses the existing v1 validation predictions/tuning as the
old-system inputs.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import sys
import time
from collections import defaultdict
from pathlib import Path
from typing import Any

import numpy as np
import joblib

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.evaluate_fusion import (  # noqa: E402
    _binary_metrics,
    _curve_metrics,
    _evaluate_candidate,
    _make_evidence,
)
from scripts.prepare_hardening_candidates import BUCKET_PATTERNS, bucket_names  # noqa: E402

SEED = 42
BOOTSTRAP_SAMPLES = 2000
SEALED_PART_RE = re.compile(r"^test(?:\.jsonl|[-_](?:v?\d.*|final.*|eval.*|frozen.*))?$", re.IGNORECASE)
BUCKETS = tuple(BUCKET_PATTERNS)


def validate_safe_path(path: str | Path, *, allow_output: bool = False) -> Path:
    """Reject test/holdout paths before opening them."""
    resolved = Path(path).resolve()
    for part in resolved.parts:
        lower = part.lower()
        if "holdout" in lower or lower in {"test", "test_v1_frozen"} or SEALED_PART_RE.match(part):
            raise ValueError("evaluate_hardening refuses test/holdout paths")
    if not allow_output and resolved.name.startswith("predictions_test"):
        raise ValueError("evaluate_hardening refuses test predictions")
    return resolved


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    path = validate_safe_path(path)
    if path.name != "validation.jsonl":
        raise ValueError("evaluate_hardening may open validation.jsonl only")
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def _read_csv(path: Path) -> list[dict[str, str]]:
    path = validate_safe_path(path)
    if path.name.startswith("predictions_test"):
        raise ValueError("evaluate_hardening refuses test predictions")
    with path.open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def _metrics(labels: list[int], scores: list[float], threshold: float) -> dict[str, Any]:
    result = _binary_metrics(labels, scores, threshold)
    result.update(_curve_metrics(labels, scores))
    result["Brier"] = float(np.mean([(score - label) ** 2 for label, score in zip(labels, scores)])) if labels else 0.0
    return result


def _model_predictions(rows: list[dict[str, Any]], prediction_path: Path, threshold: float) -> list[dict[str, Any]]:
    predictions = _read_csv(prediction_path)
    by_id = {row["id"]: row for row in predictions}
    if set(by_id) != {row["id"] for row in rows}:
        raise ValueError(f"prediction IDs do not exactly match validation: {prediction_path}")
    result = []
    for row in rows:
        saved = by_id[row["id"]]
        result.append({**row, "ml_p": float(saved["ml_p"]), "binary": int(row["binary_label"] == "malicious_or_fraudulent"),
                       "duplicate_group": row.get("duplicate_group") or row["id"],
                       "source_name": row.get("source_name", ""), "ml_category": saved.get("ml_category", "")})
    return result


def _bootstrap_differences(old: list[dict[str, Any]], new: list[dict[str, Any]]) -> dict[str, Any]:
    old_by_id = {row["id"]: row for row in old}
    new_by_id = {row["id"]: row for row in new}
    groups: dict[str, list[str]] = defaultdict(list)
    for row in old:
        groups[row["duplicate_group"]].append(row["id"])
    group_values = list(groups.values())
    rng = np.random.default_rng(SEED)
    samples = {key: [] for key in ("recall", "specificity", "FPR", "F1")}

    def values(ids: list[str]) -> dict[str, float]:
        labels = [old_by_id[item]["binary"] for item in ids]
        old_scores = [old_by_id[item]["score"] for item in ids]
        new_scores = [new_by_id[item]["score"] for item in ids]
        old_metrics = _binary_metrics(labels, old_scores, 25)
        new_metrics = _binary_metrics(labels, new_scores, 25)
        return {"recall": new_metrics["recall"] - old_metrics["recall"],
                "specificity": new_metrics["specificity"] - old_metrics["specificity"],
                "FPR": new_metrics["FPR"] - old_metrics["FPR"],
                "F1": new_metrics["F1"] - old_metrics["F1"]}

    for _ in range(BOOTSTRAP_SAMPLES):
        selected = np.concatenate([group_values[index] for index in rng.integers(0, len(group_values), len(group_values))])
        sample = values([str(item) for item in selected])
        for key in samples:
            samples[key].append(sample[key])
    result = {}
    for key, distribution in samples.items():
        result[key] = {"point_difference": values(list(old_by_id))[key],
                       "ci95": [float(np.percentile(distribution, 2.5)), float(np.percentile(distribution, 97.5))],
                       "beyond_noise": not (float(np.percentile(distribution, 2.5)) <= 0.0 <= float(np.percentile(distribution, 97.5)))}
    result["groups"] = len(group_values)
    result["samples"] = BOOTSTRAP_SAMPLES
    result["seed"] = SEED
    return result


def _source_breakdown(rows: list[dict[str, Any]], systems: dict[str, dict[str, Any]]) -> dict[str, Any]:
    output: dict[str, Any] = {}
    for name, values in systems.items():
        output[name] = {}
        for source in sorted({row["source_name"] for row in rows}):
            indexes = [index for index, row in enumerate(rows) if row["source_name"] == source]
            labels = [rows[index]["binary"] for index in indexes]
            output[name][source] = {str(point): _metrics(labels, [values[index]["score"] / 100.0 for index in indexes], point / 100.0)
                                    for point in (25, 50)}
    return output


def _hard_negative_subset(rows: list[dict[str, Any]], old: list[dict[str, Any]], new: list[dict[str, Any]]) -> dict[str, Any]:
    output: dict[str, Any] = {}
    old_by_id, new_by_id = {row["id"]: row for row in old}, {row["id"]: row for row in new}
    for bucket in BUCKETS:
        selected = [row for row in rows if row["binary"] == 0 and bucket in bucket_names(row["text"])]
        ids = [row["id"] for row in selected]
        output[bucket] = {"n": len(ids), "old_fpr": _metrics([0] * len(ids), [old_by_id[item]["score"] for item in ids], 25)["FPR"] if ids else 0.0,
                          "new_fpr": _metrics([0] * len(ids), [new_by_id[item]["score"] for item in ids], 25)["FPR"] if ids else 0.0}
    all_ids = [row["id"] for row in rows if row["binary"] == 0 and bucket_names(row["text"])]
    output["union"] = {"n": len(all_ids), "old_fpr": _metrics([0] * len(all_ids), [old_by_id[item]["score"] for item in all_ids], 25)["FPR"] if all_ids else 0.0,
                       "new_fpr": _metrics([0] * len(all_ids), [new_by_id[item]["score"] for item in all_ids], 25)["FPR"] if all_ids else 0.0}
    return output


def _latency(model_name: str, rows: list[dict[str, Any]], project_root: Path) -> dict[str, float]:
    metadata = json.loads((project_root / "models" / "metadata" / f"{model_name}.json").read_text(encoding="utf-8"))
    artifact = joblib.load(project_root / "models" / "trained" / str(metadata["artifact_filename"]))
    binary_model = artifact.get("binary_model")
    multiclass_model = artifact.get("multiclass_model")
    category_model = artifact.get("category_model")
    threshold = float(metadata.get("threshold", 0.5))
    values = []
    for row in rows:
        started = time.perf_counter()
        if multiclass_model is not None:
            probabilities = multiclass_model.predict_proba([row["text"]])[0]
            classes = [str(value) for value in getattr(multiclass_model, "classes_", range(len(probabilities)))]
            probability = 1.0 - float(probabilities[classes.index("legitimate")]) if "legitimate" in classes else float(max(probabilities))
        else:
            probabilities = binary_model.predict_proba([row["text"]])[0]
            classes = [str(value) for value in getattr(binary_model, "classes_", range(len(probabilities)))]
            probability = float(probabilities[classes.index("malicious_or_fraudulent")]) if "malicious_or_fraudulent" in classes else float(probabilities[-1])
            if category_model is not None and probability >= threshold:
                category_model.predict([row["text"]])
        values.append((time.perf_counter() - started) * 1000.0)
    return {"mean_ms": float(np.mean(values)), "median_ms": float(np.median(values)),
            "p95_ms": float(np.percentile(values, 95))}


def evaluate(project_root: str | Path | None = None, out_dir: str | Path | None = None,
             v1_dataset_dir: str | Path | None = None, v3_dataset_dir: str | Path | None = None,
             old_predictions: str | Path | None = None, new_predictions: str | Path | None = None) -> dict[str, Any]:
    root = Path(project_root or ROOT).resolve()
    v1_dir = validate_safe_path(v1_dataset_dir or root / "datasets" / "processed" / "trustshield-text-v1")
    v3_dir = validate_safe_path(v3_dataset_dir or root / "datasets" / "processed" / "trustshield-text-v3")
    output = validate_safe_path(out_dir or root / "models" / "evaluation" / "hardening_v3", allow_output=True)
    old_pred_path = validate_safe_path(old_predictions or root / "models" / "evaluation" / "validation" / "predictions_validation.csv")
    new_pred_path = validate_safe_path(new_predictions or root / "models" / "evaluation" / "hardening_v3" / "validation" / "predictions_validation.csv")
    v1_rows = _read_jsonl(v1_dir / "validation.jsonl")
    v3_rows = _read_jsonl(v3_dir / "validation.jsonl")
    if (v1_dir / "validation.jsonl").read_bytes() != (v3_dir / "validation.jsonl").read_bytes():
        raise ValueError("v3 validation is not byte-identical to v1 validation")
    if [row["id"] for row in v1_rows] != [row["id"] for row in v3_rows]:
        raise ValueError("v1/v3 validation order differs")
    old_meta_path = root / "models" / "metadata" / "trustshield_text_v1.json"
    new_meta_path = root / "models" / "metadata" / "trustshield_text_v3.json"
    old_meta, new_meta = json.loads(old_meta_path.read_text(encoding="utf-8")), json.loads(new_meta_path.read_text(encoding="utf-8"))
    old_rows = _model_predictions(v1_rows, old_pred_path, float(old_meta["threshold"]))
    new_rows = _model_predictions(v3_rows, new_pred_path, float(new_meta["threshold"]))
    old_tuning_path = root / "models" / "evaluation" / "fusion" / "tuning.json"
    new_tuning_path = root / "models" / "evaluation" / "hardening_v3" / "fusion" / "tuning_v3.json"
    old_tuning = json.loads(old_tuning_path.read_text(encoding="utf-8"))
    new_tuning = json.loads(new_tuning_path.read_text(encoding="utf-8"))
    old_evidence = _make_evidence(old_rows, float(old_meta["threshold"]))
    new_evidence = _make_evidence(new_rows, float(new_meta["threshold"]))
    old_fused = _evaluate_candidate("ml_aware_weighted", old_rows, old_evidence, moderate_ml_cut=float(old_meta["threshold"]), **old_tuning["ml_aware_weighted"])
    new_fused = _evaluate_candidate("ml_aware_weighted", new_rows, new_evidence, moderate_ml_cut=float(new_meta["threshold"]), **new_tuning["ml_aware_weighted"])
    old_legacy = _evaluate_candidate("legacy", old_rows, old_evidence, moderate_ml_cut=float(old_meta["threshold"]))
    new_legacy = _evaluate_candidate("legacy", new_rows, new_evidence, moderate_ml_cut=float(new_meta["threshold"]))
    labels = [row["binary"] for row in old_rows]
    systems = {
        "old_ml_only": {"scores": [row["ml_p"] for row in old_rows], "threshold": float(old_meta["threshold"])},
        "new_ml_only": {"scores": [row["ml_p"] for row in new_rows], "threshold": float(new_meta["threshold"])},
        "old_ml_aware_weighted": {"scores": [row["score"] / 100.0 for row in old_fused], "threshold": 0.25},
        "new_ml_aware_weighted": {"scores": [row["score"] / 100.0 for row in new_fused], "threshold": 0.25},
        "old_legacy": {"scores": [row["score"] / 100.0 for row in old_legacy], "threshold": 0.25},
        "new_legacy": {"scores": [row["score"] / 100.0 for row in new_legacy], "threshold": 0.25},
    }
    comparison: dict[str, Any] = {}
    for name, value in systems.items():
        comparison[name] = {"caution+": _metrics(labels, value["scores"], value["threshold"]),
                            "suspicious+": _metrics(labels, value["scores"], 0.50 if "ml_only" not in name else value["threshold"])}
    old_caution = [{**row, "score": value["score"]} for row, value in zip(old_rows, old_fused)]
    new_caution = [{**row, "score": value["score"]} for row, value in zip(new_rows, new_fused)]
    old_metrics = comparison["old_ml_aware_weighted"]["caution+"]
    new_metrics = comparison["new_ml_aware_weighted"]["caution+"]
    hardneg = _hard_negative_subset(old_rows, old_caution, new_caution)
    criteria = {
        "recall_drop_le_0.02": {"value": old_metrics["recall"] - new_metrics["recall"], "pass": old_metrics["recall"] - new_metrics["recall"] <= 0.02},
        "fpr_reduction_ge_0.02_or_f1_gain_ge_0.02": {"fpr_reduction": old_metrics["FPR"] - new_metrics["FPR"], "f1_gain": new_metrics["F1"] - old_metrics["F1"],
                                                       "pass": old_metrics["FPR"] - new_metrics["FPR"] >= 0.02 or new_metrics["F1"] - old_metrics["F1"] >= 0.02},
        "hard_negative_subset_fpr_not_increased": {"old_fpr": hardneg["union"]["old_fpr"], "new_fpr": hardneg["union"]["new_fpr"],
                                                    "pass": hardneg["union"]["new_fpr"] <= hardneg["union"]["old_fpr"]},
    }
    result = {
        "validation_only": True, "validation_sha256": _sha256(v3_dir / "validation.jsonl"), "seed": SEED,
        "models": {"old": {"name": "trustshield_text_v1", "threshold": old_meta["threshold"], "size_bytes": (root / "models" / "trained" / "trustshield_text_v1.joblib").stat().st_size},
                    "new": {"name": "trustshield_text_v3", "threshold": new_meta["threshold"], "size_bytes": (root / "models" / "trained" / "trustshield_text_v3.joblib").stat().st_size}},
        "comparison": comparison,
        "per_source": _source_breakdown(old_rows, {"old_ml_aware_weighted": old_caution, "new_ml_aware_weighted": new_caution}),
        "hard_negative_subset": hardneg,
        "cluster_bootstrap_old_vs_new_fused_caution": _bootstrap_differences(old_caution, new_caution),
        "latency_ms": {"old": _latency("trustshield_text_v1", old_rows, root), "new": _latency("trustshield_text_v3", new_rows, root)},
        "criteria": criteria,
        "criteria_pass_all": all(value["pass"] for value in criteria.values()),
        "inputs": {"old_predictions": str(old_pred_path), "new_predictions": str(new_pred_path), "old_tuning": str(old_tuning_path), "new_tuning": str(new_tuning_path)},
    }
    output.mkdir(parents=True, exist_ok=True)
    (output / "hardening_evaluation.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    lines = ["# Data hardening v3 evaluation", "", "Validation only; no test or holdout path was opened.", "",
             "| system | operating point | TP | TN | FP | FN | recall | specificity | F1 | FPR | ROC-AUC | PR-AUC | Brier |",
             "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for name, points in comparison.items():
        for point, metrics in points.items():
            lines.append(f"| {name} | {point} | {metrics['TP']} | {metrics['TN']} | {metrics['FP']} | {metrics['FN']} | {metrics['recall']:.4f} | {metrics['specificity']:.4f} | {metrics['F1']:.4f} | {metrics['FPR']:.4f} | {metrics['roc_auc'] if metrics['roc_auc'] is not None else 'NA'} | {metrics['pr_auc'] if metrics['pr_auc'] is not None else 'NA'} | {metrics['Brier']:.4f} |")
    lines.extend(["", "## Switch criteria", "", "| criterion | pass |", "|---|---|"])
    lines.extend(f"| {name} | {'PASS' if value['pass'] else 'FAIL'} |" for name, value in criteria.items())
    lines.append(f"| all criteria | {'PASS' if result['criteria_pass_all'] else 'FAIL'} |")
    (output / "hardening_evaluation.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path)
    parser.add_argument("--out-dir", type=Path)
    parser.add_argument("--v1-dataset-dir", type=Path)
    parser.add_argument("--v3-dataset-dir", type=Path)
    parser.add_argument("--old-predictions", type=Path)
    parser.add_argument("--new-predictions", type=Path)
    args = parser.parse_args()
    try:
        result = evaluate(args.project_root, args.out_dir, args.v1_dataset_dir, args.v3_dataset_dir,
                           args.old_predictions, args.new_predictions)
    except (ValueError, FileNotFoundError, KeyError) as exc:
        parser.error(str(exc))
    print(json.dumps({"validation_only": result["validation_only"], "criteria_pass_all": result["criteria_pass_all"]}, indent=2))


if __name__ == "__main__":
    main()
