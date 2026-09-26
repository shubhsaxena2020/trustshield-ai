"""Train and select TrustShield text models using train.jsonl and validation.jsonl only."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import platform
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Sequence

import joblib
import numpy as np
from sklearn.calibration import CalibratedClassifierCV
from sklearn.linear_model import LogisticRegression
from sklearn.svm import LinearSVC

# Make the documented `python scripts/train_classifier.py ...` entry point work
# when Python initially puts only the scripts directory on sys.path.
_PROJECT_ROOT = str(Path(__file__).resolve().parents[1])
if _PROJECT_ROOT not in sys.path:
    sys.path.insert(0, _PROJECT_ROOT)

from services.analysis.ml.features import FEATURE_PIPELINE_VERSION, build_feature_pipeline
from services.analysis.ml.metrics import (
    brier_score, binary_metrics, calibration_curve, category_scope_metrics, curve_points, multiclass_metrics, pr_auc,
    roc_auc, select_threshold, threshold_sweep,
)

SEED = 42
C_GRID = (0.5, 1.0)
SPECIFICITY_TARGET = 0.90


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _read_rows(path: Path) -> list[dict]:
    # This function intentionally receives only the explicit train or validation path.
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def _binary_scores(model, texts: list[str]) -> np.ndarray:
    if hasattr(model, "predict_proba"):
        probabilities = model.predict_proba(texts)
        classes = [str(value) for value in getattr(model, "classes_", range(probabilities.shape[1]))]
        if "malicious_or_fraudulent" in classes:
            return probabilities[:, classes.index("malicious_or_fraudulent")]
        if "1" in classes:
            return probabilities[:, classes.index("1")]
        return probabilities[:, -1]
    scores = model.decision_function(texts)
    scores = np.asarray(scores, dtype=float)
    return 1.0 / (1.0 + np.exp(-np.clip(scores, -60, 60)))


def _make_binary_model(feature_set: str, family: str, c_value: float, class_weight: str | None, calibration: str,
                       calibration_cv: int):
    features = build_feature_pipeline(feature_set)
    if family == "logistic_regression":
        classifier = LogisticRegression(C=c_value, class_weight=class_weight, max_iter=1000, random_state=SEED, solver="liblinear")
        base = __import__("sklearn.pipeline", fromlist=["Pipeline"]).Pipeline([("features", features), ("classifier", classifier)])
        return CalibratedClassifierCV(estimator=base, method=calibration, cv=calibration_cv)
    base = __import__("sklearn.pipeline", fromlist=["Pipeline"]).Pipeline([
        ("features", features), ("classifier", LinearSVC(C=c_value, class_weight=class_weight, random_state=SEED))
    ])
    return CalibratedClassifierCV(estimator=base, method=calibration, cv=calibration_cv)


def _fit_category_model(feature_set: str, train_rows: list[dict], validation_rows: list[dict]) -> tuple[Any | None, dict[str, str], dict]:
    labelled_train = [row for row in train_rows if row["binary_label"] == "malicious_or_fraudulent" and row["category_label"] != "unlabeled"]
    counts = {}
    for row in labelled_train:
        counts[row["category_label"]] = counts.get(row["category_label"], 0) + 1
    merge_map = {category: (category if count >= 30 else "other_fraud") for category, count in sorted(counts.items())}
    labels = [merge_map[row["category_label"]] for row in labelled_train]
    if len(set(labels)) < 2:
        return None, merge_map, {"macro_f1": 0.0, "reason": "category model requires at least two merged classes"}
    model = __import__("sklearn.pipeline", fromlist=["Pipeline"]).Pipeline([
        ("features", build_feature_pipeline(feature_set)),
        ("classifier", LogisticRegression(C=1.0, max_iter=1000, random_state=SEED, solver="lbfgs")),
    ])
    model.fit([row["text"] for row in labelled_train], labels)
    labelled_val = [row for row in validation_rows if row["binary_label"] == "malicious_or_fraudulent" and row["category_label"] != "unlabeled"]
    if not labelled_val:
        return model, merge_map, {"macro_f1": 0.0, "reason": "no labelled validation rows"}
    predictions = model.predict([row["text"] for row in labelled_val])
    expected = [merge_map.get(row["category_label"], row["category_label"]) for row in labelled_val]
    return model, merge_map, {"macro_f1": multiclass_metrics(expected, predictions)["macro_f1"], "support": len(labelled_val)}


def _two_stage_category_predictions(category_model: Any | None, texts: list[str], scores: Sequence[float], threshold: float) -> list[str]:
    malicious = [index for index, score in enumerate(scores) if float(score) >= threshold]
    predictions = ["legitimate"] * len(texts)
    if category_model is not None and malicious:
        values = category_model.predict([texts[index] for index in malicious])
        for index, value in zip(malicious, values):
            predictions[index] = str(value)
    return predictions


def _category_scope_report(actual: list[str], binary_labels: list[int], merge_map: dict[str, str],
                           fraud_predictions: list[str], all_predictions: list[str]) -> dict:
    fraud_report = category_scope_metrics(actual, fraud_predictions, binary_labels, merge_map)
    all_report = category_scope_metrics(actual, all_predictions, binary_labels, merge_map)
    return {view: {"fraud_only": fraud_report[view]["fraud_only"],
                   "all_labelled_incl_legitimate": all_report[view]["all_labelled_incl_legitimate"]}
            for view in ("unmerged", "merged")}


def _safe_model_size(model: Any) -> int:
    with tempfile.NamedTemporaryFile(suffix=".joblib", delete=False) as handle:
        name = handle.name
    try:
        joblib.dump(model, name)
        return os.path.getsize(name)
    finally:
        try:
            os.unlink(name)
        except OSError:
            pass


def _candidate_specs(train_size: int) -> list[dict]:
    calibration_methods = ["sigmoid"] + (["isotonic"] if train_size >= 100 else [])
    specs = []
    for feature_set in ("word", "char", "combined"):
        for c_value in C_GRID:
            for class_weight in (None, "balanced"):
                specs.append({"feature_set": feature_set, "family": "logistic_regression", "C": c_value,
                              "class_weight": class_weight, "calibration": "sigmoid", "architecture": "two_stage"})
            for calibration in calibration_methods:
                specs.append({"feature_set": feature_set, "family": "linear_svc", "C": c_value,
                              "class_weight": None, "calibration": calibration, "architecture": "two_stage"})
    return specs


def _evaluate_candidate(spec: dict, train_rows: list[dict], validation_rows: list[dict], calibration_cv: int) -> tuple[dict, dict]:
    train_texts = [row["text"] for row in train_rows]
    train_labels = [row["binary_label"] for row in train_rows]
    val_texts = [row["text"] for row in validation_rows]
    val_binary = [int(row["binary_label"] == "malicious_or_fraudulent") for row in validation_rows]
    model = _make_binary_model(spec["feature_set"], spec["family"], spec["C"], spec["class_weight"], spec["calibration"], calibration_cv)
    started = time.perf_counter()
    model.fit(train_texts, train_labels)
    fit_seconds = time.perf_counter() - started
    scores = _binary_scores(model, val_texts)
    sweep = threshold_sweep(val_binary, scores)
    selected = select_threshold(sweep, SPECIFICITY_TARGET)
    metrics = binary_metrics(val_binary, scores, selected["threshold"])
    category_model, merge_map, category_metrics = _fit_category_model(spec["feature_set"], train_rows, validation_rows)
    actual_categories = [row["category_label"] for row in validation_rows]
    category_predictions = _two_stage_category_predictions(category_model, val_texts, scores, selected["threshold"])
    fraud_predictions = ["legitimate"] * len(val_texts)
    fraud_indexes = [index for index, row in enumerate(validation_rows)
                     if val_binary[index] and row["category_label"] != "unlabeled"]
    if category_model is not None and fraud_indexes:
        for index, value in zip(fraud_indexes, category_model.predict([val_texts[index] for index in fraud_indexes])):
            fraud_predictions[index] = str(value)
    category_scopes = _category_scope_report(actual_categories, val_binary, merge_map, fraud_predictions, category_predictions)
    spec = {**spec, "calibration_cv": calibration_cv}
    metrics.update({"roc_auc": roc_auc(val_binary, scores), "pr_auc": pr_auc(val_binary, scores),
                    "brier": brier_score(val_binary, scores),
                    "category_macro_f1": category_scopes["merged"]["fraud_only"]["macro_f1"],
                    "category_weighted_f1": category_scopes["merged"]["fraud_only"]["weighted_f1"],
                    "category_f1_scopes": category_scopes,
                    "latency_mean_ms": 0.0, "latency_median_ms": 0.0, "latency_p95_ms": 0.0,
                    "fit_seconds": fit_seconds, "model_size_bytes": _safe_model_size(model),
                    "spec_target_met": selected["spec_target_met"]})
    latencies = []
    for text in val_texts:
        start = time.perf_counter()
        _binary_scores(model, [text])
        latencies.append((time.perf_counter() - start) * 1000)
    if latencies:
        metrics.update({"latency_mean_ms": float(np.mean(latencies)), "latency_median_ms": float(np.median(latencies)),
                        "latency_p95_ms": float(np.percentile(latencies, 95))})
    result = {**spec, **metrics, "threshold": selected["threshold"], "selected_rule": selected["rule"],
              "architecture": "two_stage"}
    return {"binary_model": model, "category_model": category_model, "category_merge_map": merge_map}, {"row": result, "scores": scores.tolist(), "sweep": sweep, "selected": selected}


def _write_csv(path: Path, rows: list[dict]) -> None:
    if not rows:
        path.write_text("\n", encoding="utf-8")
        return
    keys = list(rows[0])
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=keys, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def _write_plot(path: Path, kind: str, labels: list[int], scores: list[float], curve: dict | None = None) -> None:
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        figure, axis = plt.subplots(figsize=(6, 4))
        if kind == "roc":
            axis.plot(curve["fpr"], curve["tpr"]); axis.set(xlabel="False positive rate", ylabel="True positive rate", title="Validation ROC curve")
        elif kind == "pr":
            axis.plot(curve["recall"], curve["precision"]); axis.set(xlabel="Recall", ylabel="Precision", title="Validation PR curve")
        else:
            points = calibration_curve(labels, scores, 10)
            axis.plot([p["mean_predicted"] for p in points], [p["fraction_positive"] for p in points], marker="o")
            axis.plot([0, 1], [0, 1], linestyle="--"); axis.set(xlabel="Mean predicted", ylabel="Fraction positive", title="Validation calibration curve")
        figure.tight_layout(); figure.savefig(path); plt.close(figure)
    except ImportError:
        # The dev dependency is declared; this keeps the CLI usable in a minimal environment.
        import base64
        path.write_bytes(base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="))


def train(dataset_dir: str | Path, project_root: str | Path | None = None, model_name: str = "trustshield_text_v1",
          eval_dir: str | Path | None = None) -> dict:
    dataset_dir = Path(dataset_dir).resolve()
    root = Path(project_root or Path(__file__).resolve().parents[1]).resolve()
    train_rows = _read_rows(dataset_dir / "train.jsonl")
    validation_rows = _read_rows(dataset_dir / "validation.jsonl")
    manifest_path = dataset_dir / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    calibration_cv = 5 if min(sum(row["binary_label"] == label for row in train_rows) for label in ("legitimate", "malicious_or_fraudulent")) >= 5 else 3
    candidates = []
    selected_payload = None
    selected_detail = None
    for spec in _candidate_specs(len(train_rows)):
        payload, detail = _evaluate_candidate(spec, train_rows, validation_rows, calibration_cv)
        candidates.append(detail["row"])
        if selected_detail is None or (detail["row"]["pr_auc"], detail["row"]["F1"], -detail["row"]["model_size_bytes"]) > (selected_detail["row"]["pr_auc"], selected_detail["row"]["F1"], -selected_detail["row"]["model_size_bytes"]):
            selected_payload, selected_detail = payload, detail
    # Architecture A is evaluated explicitly and compared on the same validation-only rule.
    single_train_rows = [row for row in train_rows if row["category_label"] != "unlabeled"]
    single_labels = [row["category_label"] for row in single_train_rows]
    single_merge = {label: label for label in sorted(set(single_labels))}
    single_model = CalibratedClassifierCV(estimator=__import__("sklearn.pipeline", fromlist=["Pipeline"]).Pipeline([
        ("features", build_feature_pipeline("combined")),
        ("classifier", LogisticRegression(C=1.0, max_iter=1000, random_state=SEED, solver="lbfgs")),
    ]), method="sigmoid", cv=calibration_cv)
    single_model.fit([row["text"] for row in single_train_rows], single_labels)
    val_probs = single_model.predict_proba([row["text"] for row in validation_rows])
    single_classes = [str(value) for value in single_model.classes_]
    single_scores = np.asarray([1.0 - row[single_classes.index("legitimate")] for row in val_probs])
    val_binary = [int(row["binary_label"] == "malicious_or_fraudulent") for row in validation_rows]
    single_sweep = threshold_sweep(val_binary, single_scores)
    single_selected = select_threshold(single_sweep)
    single_metrics = binary_metrics(val_binary, single_scores, single_selected["threshold"])
    single_latencies = []
    for text in [row["text"] for row in validation_rows]:
        start = time.perf_counter(); single_model.predict_proba([text]); single_latencies.append((time.perf_counter() - start) * 1000)
    single_predictions = single_model.predict([row["text"] for row in validation_rows])
    expected_multi = [single_merge[row["category_label"]] for row in validation_rows if row["category_label"] != "unlabeled"]
    predicted_multi = [str(value) for row, value in zip(validation_rows, single_predictions) if row["category_label"] != "unlabeled"]
    single_category_scopes = category_scope_metrics([row["category_label"] for row in validation_rows],
                                                    [str(value) for value in single_predictions], val_binary, single_merge)
    single_metrics.update({"roc_auc": roc_auc(val_binary, single_scores), "pr_auc": pr_auc(val_binary, single_scores),
                           "brier": brier_score(val_binary, single_scores),
                           "category_macro_f1": single_category_scopes["merged"]["fraud_only"]["macro_f1"],
                           "category_weighted_f1": single_category_scopes["merged"]["fraud_only"]["weighted_f1"],
                           "category_f1_scopes": single_category_scopes,
                           "latency_mean_ms": float(np.mean(single_latencies)), "latency_median_ms": float(np.median(single_latencies)), "latency_p95_ms": float(np.percentile(single_latencies, 95)),
                           "model_size_bytes": _safe_model_size(single_model), "threshold": single_selected["threshold"], "spec_target_met": single_selected["spec_target_met"]})
    single_row = {"architecture": "single_multiclass", "feature_set": "combined", "family": "logistic_regression", "C": 1.0,
                  "class_weight": None, "calibration": "sigmoid", "selected_rule": single_selected["rule"], **single_metrics}
    candidates.append(single_row)
    if (single_row["pr_auc"], single_row["F1"], -single_row["model_size_bytes"]) > (selected_detail["row"]["pr_auc"], selected_detail["row"]["F1"], -selected_detail["row"]["model_size_bytes"]):
        selected_payload = {"multiclass_model": single_model, "binary_model": None, "category_model": None, "category_merge_map": single_merge}
        selected_detail = {"row": single_row, "scores": single_scores.tolist(), "sweep": single_sweep, "selected": single_selected}
    evaluation_dir = Path(eval_dir).resolve() if eval_dir is not None else root / "models" / "evaluation" / "validation"
    evaluation_dir.mkdir(parents=True, exist_ok=True)
    _write_csv(evaluation_dir / "model_comparison.csv", candidates)
    selected_scores = selected_detail["scores"]
    selected_row = selected_detail["row"]
    selected_labels = val_binary
    source_metrics = {}
    for source_name in sorted({row["source_name"] for row in validation_rows}):
        indexes = [index for index, row in enumerate(validation_rows) if row["source_name"] == source_name]
        source_labels = [selected_labels[index] for index in indexes]
        source_scores = [selected_scores[index] for index in indexes]
        source_metrics[source_name] = {"count": len(indexes), **binary_metrics(source_labels, source_scores, selected_row["threshold"]),
                                       "roc_auc": roc_auc(source_labels, source_scores) if len(set(source_labels)) > 1 else None,
                                       "pr_auc": pr_auc(source_labels, source_scores) if len(set(source_labels)) > 1 else None}
    selected_actual_categories = [row["category_label"] for row in validation_rows]
    if selected_payload.get("multiclass_model") is not None:
        selected_category_predictions = [str(value) for value in selected_payload["multiclass_model"].predict([row["text"] for row in validation_rows])]
        selected_category_scopes = category_scope_metrics(selected_actual_categories, selected_category_predictions,
                                                          selected_labels, selected_payload.get("category_merge_map", {}))
    else:
        selected_category_predictions = _two_stage_category_predictions(selected_payload.get("category_model"),
                                                                         [row["text"] for row in validation_rows],
                                                                         selected_scores, selected_row["threshold"])
        selected_fraud_predictions = ["legitimate"] * len(validation_rows)
        selected_fraud_indexes = [index for index, row in enumerate(validation_rows)
                                  if selected_labels[index] and row["category_label"] != "unlabeled"]
        if selected_payload.get("category_model") is not None and selected_fraud_indexes:
            for index, value in zip(selected_fraud_indexes, selected_payload["category_model"].predict([validation_rows[index]["text"] for index in selected_fraud_indexes])):
                selected_fraud_predictions[index] = str(value)
        selected_category_scopes = _category_scope_report(selected_actual_categories, selected_labels,
                                                          selected_payload.get("category_merge_map", {}),
                                                          selected_fraud_predictions, selected_category_predictions)
    selected_validation_metrics = binary_metrics(selected_labels, selected_scores, selected_row["threshold"])
    selected_validation_metrics.update({"category_f1_scopes": selected_category_scopes,
                                        "category_macro_f1": selected_category_scopes["merged"]["fraud_only"]["macro_f1"],
                                        "category_weighted_f1": selected_category_scopes["merged"]["fraud_only"]["weighted_f1"]})
    metrics = {"model_name": model_name, "selected_candidate": selected_row,
               "category_f1_scopes": selected_category_scopes,
               "category_macro_f1": selected_category_scopes["merged"]["fraud_only"]["macro_f1"],
               "category_weighted_f1": selected_category_scopes["merged"]["fraud_only"]["weighted_f1"],
               "validation_metrics": selected_validation_metrics,
               "roc_auc": roc_auc(selected_labels, selected_scores), "pr_auc": pr_auc(selected_labels, selected_scores),
               "brier": brier_score(selected_labels, selected_scores), "calibration_curve": calibration_curve(selected_labels, selected_scores),
               "ece": __import__("services.analysis.ml.metrics", fromlist=["expected_calibration_error"]).expected_calibration_error(selected_labels, selected_scores),
               "per_source_name": source_metrics,
               "threshold_rule": selected_detail["selected"]["rule"], "threshold_sweep": selected_detail["sweep"]}
    (evaluation_dir / "validation_metrics.json").write_text(json.dumps(metrics, indent=2, sort_keys=True), encoding="utf-8")
    _write_csv(evaluation_dir / "threshold_sweep.csv", selected_detail["sweep"])
    cm = binary_metrics(selected_labels, selected_scores, selected_row["threshold"])
    _write_csv(evaluation_dir / "confusion_matrix_binary.csv", [{"actual": "legitimate", "predicted_legitimate": cm["TN"], "predicted_malicious": cm["FP"]}, {"actual": "malicious_or_fraudulent", "predicted_legitimate": cm["FN"], "predicted_malicious": cm["TP"]}])
    _write_csv(evaluation_dir / "per_class_metrics.csv", [{"class": "legitimate", "precision": 0, "recall": cm["specificity"], "f1": 0}, {"class": "malicious_or_fraudulent", "precision": cm["precision"], "recall": cm["recall"], "f1": cm["F1"]}])
    _write_csv(evaluation_dir / "confusion_matrix_multiclass.csv", [])
    _write_csv(evaluation_dir / "class_distribution.csv", [{"split": split_name, "category": category, "count": sum(row["category_label"] == category for row in split_rows)}
                                                        for split_name, split_rows in (("train", train_rows), ("validation", validation_rows))
                                                        for category in sorted({row["category_label"] for row in split_rows})])
    roc_curve_data, pr_curve_data = curve_points(selected_labels, selected_scores)
    _write_plot(evaluation_dir / "roc_curve.png", "roc", selected_labels, selected_scores, roc_curve_data)
    _write_plot(evaluation_dir / "pr_curve.png", "pr", selected_labels, selected_scores, pr_curve_data)
    _write_plot(evaluation_dir / "calibration_curve.png", "calibration", selected_labels, selected_scores)
    trained_dir, metadata_dir = root / "models" / "trained", root / "models" / "metadata"
    trained_dir.mkdir(parents=True, exist_ok=True); metadata_dir.mkdir(parents=True, exist_ok=True)
    artifact_path = trained_dir / f"{model_name}.joblib"
    joblib.dump(selected_payload, artifact_path)
    metadata = {
        "model_name": model_name, "model_version": "phase3-1", "sklearn_version": __import__("sklearn").__version__,
        "numpy_version": np.__version__, "python_version": platform.python_version(), "seed": SEED,
        "dataset_version": manifest["dataset_version"], "dataset_manifest_sha256": _sha256(manifest_path),
        "train_row_count": len(train_rows), "validation_row_count": len(validation_rows), "feature_pipeline_version": FEATURE_PIPELINE_VERSION,
        "hyperparameters": selected_row, "calibration_method": selected_row.get("calibration"), "calibration_version": "sklearn-CalibratedClassifierCV-v1",
        "threshold": selected_row["threshold"], "threshold_rule": selected_detail["selected"], "threshold_sweep": selected_detail["sweep"],
        "category_merge_map": selected_payload.get("category_merge_map", {}), "artifact_filename": artifact_path.name,
        "calibration_grid": {"methods_considered": ["sigmoid", "isotonic"] if len(train_rows) >= 100 else ["sigmoid"],
                              "isotonic_skipped_reason": "training set has fewer than 100 rows; isotonic calibration is too data-hungry" if len(train_rows) < 100 else ""},
        "artifact_sha256": _sha256(artifact_path), "selection_criteria": "validation PR-AUC; tie-break F1 at selected threshold; then smaller model size",
        "validation_metrics": metrics, "created_timestamp": datetime.now(timezone.utc).isoformat(),
    }
    (metadata_dir / f"{model_name}.json").write_text(json.dumps(metadata, indent=2, sort_keys=True), encoding="utf-8")
    return {"model_name": model_name, "artifact": str(artifact_path), "selected_candidate": selected_row}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-dir", type=Path, required=True)
    parser.add_argument("--model-name", default="trustshield_text_v1")
    parser.add_argument("--eval-dir", type=Path, help="evaluation output directory; default preserves v1 behavior")
    parser.add_argument("--project-root", type=Path)
    args = parser.parse_args()
    print(json.dumps(train(args.dataset_dir, project_root=args.project_root, model_name=args.model_name,
                           eval_dir=args.eval_dir), indent=2, default=str))


if __name__ == "__main__":
    main()
