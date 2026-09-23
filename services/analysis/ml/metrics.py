"""Pure evaluation metrics and the fixed validation threshold rule."""
from __future__ import annotations

from typing import Iterable, Sequence

import numpy as np
from sklearn.metrics import average_precision_score, f1_score, precision_recall_curve, roc_auc_score, roc_curve


def _safe(numerator: float, denominator: float) -> float:
    return float(numerator / denominator) if denominator else 0.0


def confusion_counts(labels: Sequence, scores: Sequence[float], threshold: float = 0.5) -> dict[str, int]:
    y_true = np.asarray(labels).astype(int)
    y_pred = np.asarray(scores) >= threshold
    return {
        "TP": int(np.sum((y_true == 1) & y_pred)), "TN": int(np.sum((y_true == 0) & ~y_pred)),
        "FP": int(np.sum((y_true == 0) & y_pred)), "FN": int(np.sum((y_true == 1) & ~y_pred)),
    }


def binary_metrics(labels: Sequence, scores: Sequence[float], threshold: float = 0.5) -> dict[str, float | int]:
    counts = confusion_counts(labels, scores, threshold)
    tp, tn, fp, fn = (counts[key] for key in ("TP", "TN", "FP", "FN"))
    total = tp + tn + fp + fn
    precision = _safe(tp, tp + fp)
    recall = _safe(tp, tp + fn)
    specificity = _safe(tn, tn + fp)
    f1 = _safe(2 * precision * recall, precision + recall)
    return {**counts, "accuracy": _safe(tp + tn, total), "precision": precision, "recall": recall,
            "specificity": specificity, "F1": f1, "FPR": 1.0 - specificity, "FNR": 1.0 - recall,
            "threshold": float(threshold)}


def multiclass_confusion_matrix(labels: Sequence, predictions: Sequence, classes: Sequence[str] | None = None) -> tuple[list[str], np.ndarray]:
    names = list(classes or sorted({str(value) for value in list(labels) + list(predictions)}))
    indexes = {name: index for index, name in enumerate(names)}
    matrix = np.zeros((len(names), len(names)), dtype=int)
    for actual, predicted in zip(labels, predictions):
        if str(actual) in indexes and str(predicted) in indexes:
            matrix[indexes[str(actual)], indexes[str(predicted)]] += 1
    return names, matrix


def per_class_metrics(labels: Sequence, predictions: Sequence, classes: Sequence[str] | None = None) -> dict[str, dict[str, float | int]]:
    names, matrix = multiclass_confusion_matrix(labels, predictions, classes)
    result = {}
    for index, name in enumerate(names):
        tp = int(matrix[index, index])
        fp = int(matrix[:, index].sum() - tp)
        fn = int(matrix[index, :].sum() - tp)
        support = int(matrix[index, :].sum())
        precision = _safe(tp, tp + fp)
        recall = _safe(tp, tp + fn)
        result[name] = {"precision": precision, "recall": recall, "f1": _safe(2 * precision * recall, precision + recall), "support": support}
    return result


def multiclass_metrics(labels: Sequence, predictions: Sequence, classes: Sequence[str] | None = None) -> dict:
    per_class = per_class_metrics(labels, predictions, classes)
    supports = np.asarray([value["support"] for value in per_class.values()], dtype=float)
    f1s = np.asarray([value["f1"] for value in per_class.values()], dtype=float)
    return {"per_class": per_class, "macro_f1": float(f1s.mean()) if len(f1s) else 0.0,
            "weighted_f1": _safe(float(np.dot(f1s, supports)), float(supports.sum()))}


def category_scope_metrics(actual: Sequence[str], predictions: Sequence[str], binary_labels: Sequence[int],
                           merge_map: dict[str, str] | None = None) -> dict[str, dict[str, dict]]:
    """Report raw and metadata-merged category metrics in the two supported scopes."""
    merge_map = merge_map or {}
    labelled = [index for index, value in enumerate(actual) if str(value) != "unlabeled"]
    scopes = {
        "fraud_only": [index for index in labelled if int(binary_labels[index]) == 1],
        "all_labelled_incl_legitimate": labelled,
    }
    views = {"unmerged": lambda value: str(value), "merged": lambda value: merge_map.get(str(value), str(value))}
    result: dict[str, dict[str, dict]] = {}
    for view, normalize in views.items():
        result[view] = {}
        for scope, indexes in scopes.items():
            expected = [normalize(actual[index]) for index in indexes]
            predicted = [normalize(predictions[index]) for index in indexes]
            metrics = multiclass_metrics(expected, predicted)
            metrics.update({"support": len(indexes), "malicious": sum(int(binary_labels[index]) for index in indexes),
                            "legitimate": len(indexes) - sum(int(binary_labels[index]) for index in indexes)})
            result[view][scope] = metrics
    return result


def roc_auc(labels: Sequence, scores: Sequence[float]) -> float:
    return float(roc_auc_score(labels, scores)) if len(set(labels)) > 1 else 0.0


def pr_auc(labels: Sequence, scores: Sequence[float]) -> float:
    return float(average_precision_score(labels, scores)) if any(labels) else 0.0


def brier_score(labels: Sequence, scores: Sequence[float]) -> float:
    y_true, y_score = np.asarray(labels, dtype=float), np.asarray(scores, dtype=float)
    return float(np.mean((y_score - y_true) ** 2)) if len(y_true) else 0.0


def calibration_curve(labels: Sequence, scores: Sequence[float], n_bins: int = 10) -> list[dict[str, float | int]]:
    """Equal-width bins on [0, 1]; empty bins are retained for reproducible reports."""
    y_true, y_score = np.asarray(labels, dtype=float), np.asarray(scores, dtype=float)
    result = []
    for index in range(n_bins):
        low, high = index / n_bins, (index + 1) / n_bins
        mask = (y_score >= low) & ((y_score < high) if index < n_bins - 1 else (y_score <= high))
        result.append({"bin": index, "lower": low, "upper": high,
                       "count": int(mask.sum()), "mean_predicted": float(y_score[mask].mean()) if mask.any() else 0.0,
                       "fraction_positive": float(y_true[mask].mean()) if mask.any() else 0.0})
    return result


def expected_calibration_error(labels: Sequence, scores: Sequence[float], n_bins: int = 10) -> float:
    """ECE using equal-width bins, weighted by the fraction of all observations."""
    if any(value not in (0, 1) for value in labels):
        raise ValueError("labels must be binary")
    total = len(labels)
    return float(sum((item["count"] / total) * abs(item["mean_predicted"] - item["fraction_positive"])
                     for item in calibration_curve(labels, scores, n_bins) if total))


def threshold_sweep(labels: Sequence, scores: Sequence[float], thresholds: Iterable[float] | None = None) -> list[dict]:
    values = sorted(set(float(value) for value in (thresholds if thresholds is not None else np.linspace(0.01, 0.99, 99))))
    return [{"threshold": threshold, **binary_metrics(labels, scores, threshold), "precision": binary_metrics(labels, scores, threshold)["precision"],
             "recall": binary_metrics(labels, scores, threshold)["recall"], "specificity": binary_metrics(labels, scores, threshold)["specificity"]}
            for threshold in values]


def select_threshold(sweep: Sequence[dict], specificity_target: float = 0.90) -> dict:
    eligible = [row for row in sweep if row["specificity"] >= specificity_target]
    if eligible:
        chosen = max(eligible, key=lambda row: (row["recall"], row["precision"], -row["threshold"]))
        return {**chosen, "spec_target_met": True, "rule": "highest malicious recall subject to specificity >= 0.90; tie-break higher precision"}
    chosen = max(sweep, key=lambda row: (row["F1"], row["precision"], row["recall"], -row["threshold"]))
    return {**chosen, "spec_target_met": False, "rule": "no threshold met specificity >= 0.90; highest F1 fallback"}


def curve_points(labels: Sequence, scores: Sequence[float]) -> tuple[dict, dict]:
    fpr, tpr, roc_thresholds = roc_curve(labels, scores) if len(set(labels)) > 1 else (np.array([0, 1]), np.array([0, 1]), np.array([np.inf, 0]))
    precision, recall, pr_thresholds = precision_recall_curve(labels, scores)
    return ({"fpr": fpr.tolist(), "tpr": tpr.tolist(), "thresholds": roc_thresholds.tolist()},
            {"precision": precision.tolist(), "recall": recall.tolist(), "thresholds": pr_thresholds.tolist()})
