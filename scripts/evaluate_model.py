"""Evaluate rules, the ML classifier, or the available combined pipeline."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

_PROJECT_ROOT = str(Path(__file__).resolve().parents[1])
if _PROJECT_ROOT not in sys.path:
    sys.path.insert(0, _PROJECT_ROOT)

from services.analysis.ml.classifier import TextClassifier
from services.analysis.ml.metrics import (
    binary_metrics, category_scope_metrics, curve_points, multiclass_metrics, per_class_metrics, pr_auc, roc_auc, threshold_sweep,
)
from services.analysis.pipeline import analyze_text

RULE_CATEGORY_MAP = {
    "phishing": "phishing", "credential_phishing": "credential_theft", "credential_theft": "credential_theft",
    "payment_fraud": "payment_fraud", "fake_job": "fake_job", "investment_fraud": "investment_scam",
    "delivery_scam": "delivery_scam", "prize_scam": "prize_lottery_scam", "digital_arrest": "digital_arrest",
    "impersonation": "impersonation", "other_fraud": "other_fraud", "none_detected": "legitimate",
}


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _read_rows(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def _rule_result(text: str):
    previous = os.environ.get("TRUSTSHIELD_ML")
    os.environ["TRUSTSHIELD_ML"] = "off"
    try:
        return analyze_text(text)
    finally:
        if previous is None:
            os.environ.pop("TRUSTSHIELD_ML", None)
        else:
            os.environ["TRUSTSHIELD_ML"] = previous


def _combined_result(text: str):
    previous = os.environ.get("TRUSTSHIELD_ML")
    os.environ["TRUSTSHIELD_ML"] = "on"
    try:
        result = analyze_text(text)
    finally:
        if previous is None:
            os.environ.pop("TRUSTSHIELD_ML", None)
        else:
            os.environ["TRUSTSHIELD_ML"] = previous
    module = result.modules.get("ml")
    if module is None or module.status != "completed":
        return result, "combined unavailable: pipeline does not expose a completed ML module; rule result reported"
    return result, "combined pipeline ML integration active"


def _system_metrics(labels: list[int], scores: list[float], threshold: float) -> dict:
    values = binary_metrics(labels, scores, threshold)
    values.update({"roc_auc": roc_auc(labels, scores), "pr_auc": pr_auc(labels, scores)})
    return values


def evaluate(split: str, systems: list[str], dataset_dir: str | Path, project_root: str | Path | None = None,
             model_name: str = "trustshield_text_v1", confirm_final_test: bool = False) -> dict:
    if split not in {"validation", "test"}:
        raise ValueError("split must be validation or test")
    dataset_dir = Path(dataset_dir).resolve()
    root = Path(project_root or Path(__file__).resolve().parents[1]).resolve()
    if split == "test" and not confirm_final_test:
        raise PermissionError("test evaluation requires --confirm-final-test")
    rows = _read_rows(dataset_dir / f"{split}.jsonl")
    classifier = TextClassifier(model_name, project_root=root) if "ml" in systems or "combined" in systems else None
    metadata_path = root / "models" / "metadata" / f"{model_name}.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8")) if metadata_path.exists() else {}
    if split == "test":
        log_path = root / "models" / "evaluation" / "test_access_log.json"
        log = json.loads(log_path.read_text(encoding="utf-8")) if log_path.exists() else []
        log.append({"timestamp": datetime.now(timezone.utc).isoformat(), "model_artifact_sha256": metadata.get("artifact_sha256", ""),
                    "dataset_manifest_sha256": _sha256(dataset_dir / "manifest.json"), "command_line": " ".join(sys.argv)})
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_path.write_text(json.dumps(log, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        access_count = len(log)
    else:
        access_count = 0
    output_dir = root / "models" / "evaluation" / split
    output_dir.mkdir(parents=True, exist_ok=True)
    labels = [int(row["binary_label"] == "malicious_or_fraudulent") for row in rows]
    predictions = []
    latencies = {system: [] for system in systems}
    raw = {system: {"scores": [], "alerts": [], "categories": []} for system in systems}
    messages = []
    for row in rows:
        rule_start = time.perf_counter(); rule = _rule_result(row["text"]); rule_latency = (time.perf_counter() - rule_start) * 1000
        rule_score, rule_label = float(rule.risk.score) / 100.0, rule.risk.score >= 25
        rule_categories = [rule.classification.primary, *rule.classification.secondary]
        ml_prediction = classifier.predict(row["text"]) if classifier else None
        combined = None
        combined_detail = ""
        if "combined" in systems:
            combined, combined_detail = _combined_result(row["text"])
            if combined_detail not in messages:
                messages.append(combined_detail)
        if "rules" in systems:
            raw["rules"]["scores"].append(rule_score); raw["rules"]["alerts"].append(rule.risk.score >= 25); raw["rules"]["categories"].append(RULE_CATEGORY_MAP.get(rule.classification.primary, "other_fraud")); latencies["rules"].append(rule_latency)
        if "ml" in systems:
            raw["ml"]["scores"].append(ml_prediction.p_malicious); raw["ml"]["alerts"].append(ml_prediction.predicted_binary); raw["ml"]["categories"].append(ml_prediction.category or ("other_fraud" if ml_prediction.predicted_binary else "legitimate")); latencies["ml"].append(ml_prediction.latency_ms)
        if "combined" in systems:
            combined_score = float(combined.risk.score) / 100.0
            raw["combined"]["scores"].append(combined_score); raw["combined"]["alerts"].append(combined.risk.score >= 25); raw["combined"]["categories"].append(RULE_CATEGORY_MAP.get(combined.classification.primary, "other_fraud")); latencies["combined"].append(combined_latency := (time.perf_counter() - rule_start) * 1000)
        predictions.append({"id": row["id"], "text_hash": hashlib.sha256(row["text"].encode()).hexdigest(), "text": row["text"],
                            "binary_label": row["binary_label"], "category_label": row["category_label"], "source_type": row["source_type"], "synthetic": row.get("synthetic", False),
                            "source_name": row["source_name"], "rule_score": rule.risk.score, "rule_label": rule_label, "rule_categories": "|".join(rule_categories),
                            "ml_p": ml_prediction.p_malicious if ml_prediction else "", "ml_category": ml_prediction.category if ml_prediction else "",
                            "combined_score": combined.risk.score if combined else "", "combined_label": combined.risk.score >= 25 if combined else ""})
    _write_predictions(output_dir / f"predictions_{split}.csv", predictions)
    report = {"split": split, "systems": {}, "messages": messages, "access_count": access_count,
              "rule_category_mapping": RULE_CATEGORY_MAP, "latency_ms": {}}
    multiclass_rows = []
    for system in systems:
        threshold = float(metadata.get("threshold", 0.5)) if system == "ml" else 0.25
        metrics = _system_metrics(labels, raw[system]["scores"], threshold)
        secondary = _system_metrics(labels, raw[system]["scores"], float(metadata.get("threshold", 0.5)) if system == "ml" else 0.5)
        metrics["secondary_operating_point"] = secondary
        metrics["breakdown"] = {}
        for key, predicate in (("source_name", lambda row, value: row["source_name"] == value),
                               ("source_type", lambda row, value: row["source_type"] == value),
                               ("synthetic", lambda row, value: row.get("synthetic") == value),
                               ("holdout_cross_author", lambda row, value: row["source_name"].startswith("holdout-"))):
            values = sorted({row[key] for row in rows}) if key != "holdout_cross_author" else [True, False]
            metrics["breakdown"][key] = {}
            for value in values:
                indexes = [index for index, row in enumerate(rows) if predicate(row, value)]
                if indexes:
                    metrics["breakdown"][key][str(value)] = _system_metrics([labels[i] for i in indexes], [raw[system]["scores"][i] for i in indexes], threshold)
        labelled_indexes = [i for i, row in enumerate(rows) if row["category_label"] != "unlabeled"]
        expected = [row["category_label"] for row in rows]
        actual = raw[system]["categories"]
        merge_map = metadata.get("category_merge_map", {}) if system == "ml" else {}
        metrics["multiclass"] = category_scope_metrics(expected, actual, labels, merge_map)
        report["systems"][system] = metrics
        lat = latencies[system]
        report["latency_ms"][system] = {"mean": float(np.mean(lat)) if lat else 0.0, "median": float(np.median(lat)) if lat else 0.0, "p95": float(np.percentile(lat, 95)) if lat else 0.0}
        _write_matrix(output_dir / f"confusion_matrix_{system}.csv", metrics)
        for view, scopes in metrics["multiclass"].items():
            for scope, scope_metrics in scopes.items():
                multiclass_rows.extend({"system": system, "view": view, "scope": scope, "class": label, **values}
                                       for label, values in scope_metrics["per_class"].items())
    _write_csv(output_dir / "per_class_metrics.csv", multiclass_rows)
    if split == "validation" and "ml" in systems:
        _write_csv(output_dir / "threshold_sweep.csv", threshold_sweep(labels, raw["ml"]["scores"]))
    if systems:
        roc_data, pr_data = curve_points(labels, raw[systems[0]]["scores"])
        _write_curve_plot(output_dir / "roc_curve.png", roc_data, "fpr", "tpr", "ROC")
        _write_curve_plot(output_dir / "pr_curve.png", pr_data, "recall", "precision", "PR")
    _write_csv(output_dir / "system_comparison.csv", [{"system": system, **{key: value for key, value in report["systems"][system].items() if not isinstance(value, (dict, list))}} for system in systems])
    (output_dir / "evaluation.json").write_text(json.dumps(report, indent=2, sort_keys=True, default=str) + "\n", encoding="utf-8")
    return report


def _write_predictions(path: Path, rows: list[dict]) -> None:
    keys = list(rows[0]) if rows else ["id"]
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=keys); writer.writeheader(); writer.writerows(rows)


def _write_csv(path: Path, rows: list[dict]) -> None:
    keys = list(rows[0]) if rows else ["empty"]
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=keys, extrasaction="ignore"); writer.writeheader(); writer.writerows(rows)


def _write_matrix(path: Path, metrics: dict) -> None:
    counts = {key: metrics.get(key, 0) for key in ("TN", "FP", "FN", "TP")}
    _write_csv(path, [{"actual": "legitimate", "predicted_legitimate": counts["TN"], "predicted_malicious": counts["FP"]}, {"actual": "malicious_or_fraudulent", "predicted_legitimate": counts["FN"], "predicted_malicious": counts["TP"]}])


def _write_curve_plot(path: Path, curve: dict, x: str, y: str, title: str) -> None:
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        figure, axis = plt.subplots(figsize=(6, 4)); axis.plot(curve[x], curve[y]); axis.set(title=title, xlabel=x, ylabel=y); figure.tight_layout(); figure.savefig(path); plt.close(figure)
    except ImportError:
        path.write_bytes(b"\x89PNG\r\n\x1a\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--split", choices=("validation", "test"), required=True)
    parser.add_argument("--systems", default="rules,ml")
    parser.add_argument("--dataset-dir", type=Path, required=True)
    parser.add_argument("--model-name", default="trustshield_text_v1")
    parser.add_argument("--project-root", type=Path)
    parser.add_argument("--confirm-final-test", action="store_true")
    args = parser.parse_args()
    report = evaluate(args.split, [value for value in args.systems.split(",") if value], args.dataset_dir, project_root=args.project_root, model_name=args.model_name, confirm_final_test=args.confirm_final_test)
    print(json.dumps({"split": args.split, "access_count": report["access_count"], "systems": list(report["systems"])}, indent=2))


if __name__ == "__main__":
    main()
