"""Offline reporting from the frozen final-test prediction artifact.

This script deliberately does not load the model, call the evaluator, or run
any prediction. It reads the coordinator's saved predictions_test.csv and the
matching test.jsonl labels/duplicate groups, then writes new analysis files.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import random
from collections import Counter, defaultdict
from pathlib import Path
from typing import Iterable

from services.analysis.ml.metrics import category_scope_metrics


SYSTEMS = ("rules", "ml", "combined")
ML_THRESHOLDS = (0.12, 0.25, 0.5, 0.75, 0.9)
SCORE_THRESHOLDS = (25.0, 50.0)
N_BOOTSTRAP = 2000
SEED = 42


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def write_csv(path: Path, rows: list[dict]) -> None:
    if not rows:
        raise ValueError(f"no rows for {path}")
    keys = list(rows[0])
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=keys)
        writer.writeheader()
        writer.writerows(rows)


def write_json(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def f(value: float, digits: int = 12) -> float:
    return round(float(value), digits)


def percentile(values: list[float], percentage: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    position = (len(ordered) - 1) * percentage / 100.0
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def safe(numerator: float, denominator: float) -> float:
    return float(numerator / denominator) if denominator else 0.0


def binary_counts(labels: list[int], scores: list[float], threshold: float) -> dict[str, int | float]:
    predicted = [int(score >= threshold) for score in scores]
    tp = sum(actual == 1 and pred == 1 for actual, pred in zip(labels, predicted))
    tn = sum(actual == 0 and pred == 0 for actual, pred in zip(labels, predicted))
    fp = sum(actual == 0 and pred == 1 for actual, pred in zip(labels, predicted))
    fn = sum(actual == 1 and pred == 0 for actual, pred in zip(labels, predicted))
    precision = safe(tp, tp + fp)
    recall = safe(tp, tp + fn)
    specificity = safe(tn, tn + fp)
    f1 = safe(2 * precision * recall, precision + recall)
    return {
        "TP": tp,
        "TN": tn,
        "FP": fp,
        "FN": fn,
        "accuracy": safe(tp + tn, len(labels)),
        "precision": precision,
        "recall": recall,
        "specificity": specificity,
        "F1": f1,
        "FPR": safe(fp, fp + tn),
        "FNR": safe(fn, fn + tp),
    }


def multiclass_metrics(expected: list[str], actual: list[str], classes: list[str]) -> tuple[list[dict], list[dict], dict]:
    matrix = {expected_class: {actual_class: 0 for actual_class in classes} for expected_class in classes}
    for expected_class, actual_class in zip(expected, actual):
        matrix[expected_class][actual_class] += 1
    rows = []
    f1s = []
    supports = []
    for label in classes:
        tp = matrix[label][label]
        support = sum(matrix[label].values())
        predicted = sum(matrix[row_label][label] for row_label in classes)
        precision = safe(tp, predicted)
        recall = safe(tp, support)
        f1 = safe(2 * precision * recall, precision + recall)
        rows.append({"class": label, "precision": f(precision), "recall": f(recall), "f1": f(f1), "support": support})
        f1s.append(f1)
        supports.append(support)
    macro = safe(sum(f1s), len(f1s))
    weighted = safe(sum(f1 * support for f1, support in zip(f1s, supports)), sum(supports))
    matrix_rows = [{"actual": expected_class, **{f"predicted_{actual_class}": matrix[expected_class][actual_class] for actual_class in classes}} for expected_class in classes]
    return rows, matrix_rows, {"macro_f1": f(macro), "weighted_f1": f(weighted), "support": sum(supports), "classes": classes}


def brier(labels: list[int], scores: list[float]) -> float:
    return f(sum((score - label) ** 2 for label, score in zip(labels, scores)) / len(labels))


def calibration(labels: list[int], scores: list[float], bins: int = 10) -> tuple[list[dict], float]:
    rows = []
    ece = 0.0
    n = len(labels)
    for index in range(bins):
        lower = index / bins
        upper = (index + 1) / bins
        selected = [i for i, score in enumerate(scores) if (lower <= score < upper) or (index == bins - 1 and score == upper)]
        count = len(selected)
        mean_pred = safe(sum(scores[i] for i in selected), count)
        observed = safe(sum(labels[i] for i in selected), count)
        ece += safe(count, n) * abs(mean_pred - observed)
        rows.append({
            "bin": index,
            "lower": f(lower),
            "upper": f(upper),
            "count": count,
            "mean_predicted_probability": f(mean_pred),
            "observed_fraction_malicious": f(observed),
            "absolute_gap": f(abs(mean_pred - observed)),
        })
    return rows, f(ece)


def cluster_bootstrap(rows: list[dict], groups: dict[str, list[int]], score_key: str, threshold: float, rng: random.Random) -> dict[str, tuple[float, float, float]]:
    group_ids = sorted(groups)
    estimates = {metric: [] for metric in ("recall", "specificity", "precision", "F1")}
    for _ in range(N_BOOTSTRAP):
        sampled_groups = [rng.choice(group_ids) for _ in group_ids]
        indexes = [index for group_id in sampled_groups for index in groups[group_id]]
        labels = [int(rows[index]["binary_label"] == "malicious_or_fraudulent") for index in indexes]
        scores = [float(rows[index][score_key]) for index in indexes]
        metrics = binary_counts(labels, scores, threshold)
        for metric in estimates:
            estimates[metric].append(float(metrics[metric]))
    return {metric: (f(sum(values) / len(values)), f(percentile(values, 2.5)), f(percentile(values, 97.5))) for metric, values in estimates.items()}


def write_reliability_svg(path: Path, reliability: list[dict]) -> None:
    width, height = 640, 540
    left, top, plot = 80, 40, 420
    points = [row for row in reliability if row["count"]]
    def x(value: float) -> float:
        return left + value * plot
    def y(value: float) -> float:
        return top + (1 - value) * plot
    grid = []
    for tick in range(0, 11):
        value = tick / 10
        grid.append(f'<line x1="{x(value):.1f}" y1="{y(0):.1f}" x2="{x(value):.1f}" y2="{y(1):.1f}" stroke="#e5e7eb"/>')
        grid.append(f'<line x1="{x(0):.1f}" y1="{y(value):.1f}" x2="{x(1):.1f}" y2="{y(value):.1f}" stroke="#e5e7eb"/>')
    curve = " ".join(f"{x(row['mean_predicted_probability']):.1f},{y(row['observed_fraction_malicious']):.1f}" for row in points)
    circles = " ".join(f'<circle cx="{x(row["mean_predicted_probability"]):.1f}" cy="{y(row["observed_fraction_malicious"]):.1f}" r="4" fill="#2563eb"/>' for row in points)
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">
<rect width="100%" height="100%" fill="white"/>
<text x="{width/2}" y="24" text-anchor="middle" font-family="Arial" font-size="16">Frozen ML probability calibration on test</text>
{''.join(grid)}
<line x1="{x(0):.1f}" y1="{y(0):.1f}" x2="{x(1):.1f}" y2="{y(1):.1f}" stroke="#6b7280" stroke-dasharray="6,5"/>
<polyline points="{curve}" fill="none" stroke="#2563eb" stroke-width="3"/>
{circles}
<line x1="{x(0):.1f}" y1="{y(0):.1f}" x2="{x(1):.1f}" y2="{y(0):.1f}" stroke="#111827"/>
<line x1="{x(0):.1f}" y1="{y(0):.1f}" x2="{x(0):.1f}" y2="{y(1):.1f}" stroke="#111827"/>
<text x="{left + plot/2}" y="{top + plot + 42}" text-anchor="middle" font-family="Arial" font-size="13">Mean predicted probability</text>
<text x="18" y="{top + plot/2}" text-anchor="middle" font-family="Arial" font-size="13" transform="rotate(-90 18 {top + plot/2})">Observed malicious fraction</text>
<text x="{left}" y="{top + plot + 22}" text-anchor="middle" font-family="Arial" font-size="11">0</text>
<text x="{left + plot}" y="{top + plot + 22}" text-anchor="middle" font-family="Arial" font-size="11">1</text>
<text x="{left - 14}" y="{y(0) + 4}" text-anchor="end" font-family="Arial" font-size="11">0</text>
<text x="{left - 14}" y="{y(1) + 4}" text-anchor="end" font-family="Arial" font-size="11">1</text>
</svg>'''
    path.write_text(svg, encoding="utf-8")


def slice_predicate(name: str, row: dict) -> bool:
    if name == "holdout-codex-cross-author-synthetic":
        return row["source_name"] == "holdout-codex-v1" and row["synthetic"] == "True"
    if name == "non-synthetic-public":
        return row["synthetic"] == "False" and row["source_type"] == "public_dataset"
    return row["source_name"] == name


def metric_rows_for_slice(rows: list[dict], slice_name: str, indexes: list[int]) -> list[dict]:
    labels = [int(rows[i]["binary_label"] == "malicious_or_fraudulent") for i in indexes]
    output = []
    for system, score_key, threshold in (("rules", "rule_score", 25.0), ("ml", "ml_p", 0.12), ("combined", "combined_score", 25.0)):
        scores = [float(rows[i][score_key]) for i in indexes]
        metrics = binary_counts(labels, scores, threshold)
        output.append({"slice": slice_name, "system": system, "n": len(indexes), "malicious": sum(labels), "legitimate": len(labels) - sum(labels), "threshold": threshold, **metrics})
    return output


def build_review_rows(rows: list[dict], sample_seed: int = SEED) -> list[dict]:
    ml_fps = [i for i, row in enumerate(rows) if row["binary_label"] == "legitimate" and float(row["ml_p"]) >= 0.12]
    ml_fns = [i for i, row in enumerate(rows) if row["binary_label"] == "malicious_or_fraudulent" and float(row["ml_p"]) < 0.12]
    rule_fps = [i for i, row in enumerate(rows) if row["binary_label"] == "legitimate" and float(row["rule_score"]) >= 25]
    combined_fns = [i for i, row in enumerate(rows) if row["binary_label"] == "malicious_or_fraudulent" and float(row["combined_score"]) < 25]
    sampler = random.Random(sample_seed)
    combined_sample = sorted(sampler.sample(combined_fns, 40))
    sets: dict[int, list[str]] = defaultdict(list)
    for name, indexes in (("ml_fp_all", ml_fps), ("ml_fn_all", ml_fns), ("rules_fp_all", rule_fps), ("combined_fn_sample_40_seed42", combined_sample)):
        for index in indexes:
            sets[index].append(name)
    output = []
    for index in sorted(sets):
        row = rows[index]
        output.append({
            "review_sets": ";".join(sets[index]),
            "id": row["id"],
            "source_name": row["source_name"],
            "slice": "holdout" if row["source_name"].startswith("holdout-") else ("public" if row["synthetic"] == "False" else "synthetic-other"),
            "binary_label": row["binary_label"],
            "category_label": row["category_label"],
            "ml_p": row["ml_p"],
            "rule_score": row["rule_score"],
            "combined_score": row["combined_score"],
            "text": row["text"],
        })
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parents[1])
    import sys as _sys
    from pathlib import Path as _Path
    _sys.path.insert(0, str(_Path(__file__).resolve().parents[1]))
    from scripts._v2_spent_guard import require_archival_override
    args = parser.parse_args(require_archival_override(None))
    root = args.project_root.resolve()
    test_dir = root / "models" / "evaluation" / "test"
    dataset_dir = root / "datasets" / "processed" / "trustshield-text-v1"
    output_names = {
        "summary": "test_reporting_summary.json",
        "multiclass": "multiclass_metrics_test.csv",
        "matrix": "multiclass_confusion_matrix_test.csv",
        "calibration": "calibration_reliability_test.csv",
        "bootstrap": "bootstrap_confidence_intervals_test.csv",
        "slices": "slice_metrics_test.csv",
        "thresholds": "threshold_sensitivity_test.csv",
        "review": "manual_review_candidates_test.csv",
        "plot": "calibration_reliability_test.svg",
    }
    existing = [test_dir / name for name in output_names.values() if (test_dir / name).exists()]
    if existing:
        raise FileExistsError("refusing to overwrite existing reporting files: " + ", ".join(str(path) for path in existing))

    predictions = read_csv(test_dir / "predictions_test.csv")
    labels = {row["id"]: row for row in read_jsonl(dataset_dir / "test.jsonl")}
    if set(labels) != {row["id"] for row in predictions}:
        raise ValueError("test.jsonl ids and predictions_test.csv ids do not match")
    rows = []
    for prediction in predictions:
        source = labels[prediction["id"]]
        row = dict(prediction)
        row["duplicate_group"] = source.get("duplicate_group", "")
        rows.append(row)

    binary_labels = [int(row["binary_label"] == "malicious_or_fraudulent") for row in rows]
    score_keys = {"rules": "rule_score", "ml": "ml_p", "combined": "combined_score"}
    thresholds = {"rules": 25.0, "ml": 0.12, "combined": 25.0}

    metadata = read_json(root / "models" / "metadata" / "trustshield_text_v1.json")
    manifest = read_json(dataset_dir / "manifest.json")
    access_log = read_json(root / "models" / "evaluation" / "test_access_log.json")

    merge_map = metadata.get("category_merge_map", {})
    # Category layer: missing malicious categories are other_fraud, while
    # negative predictions are legitimate. Report raw and merged views in the
    # fraud-only and all-labelled scopes.
    expected = [row["category_label"] for row in rows]
    actual = [row["ml_category"] or ("other_fraud" if float(row["ml_p"]) >= 0.12 else "legitimate") for row in rows]
    binary_labels = [int(row["binary_label"] == "malicious_or_fraudulent") for row in rows]
    category_scopes = category_scope_metrics(expected, actual, binary_labels, merge_map)
    classes = sorted(set(expected + actual))
    per_class = []
    for view, view_scopes in category_scopes.items():
        for scope, scope_metrics in view_scopes.items():
            per_class.extend({"view": view, "scope": scope, "class": label, **values,
                              "macro_f1": scope_metrics["macro_f1"], "weighted_f1": scope_metrics["weighted_f1"],
                              "scope_support": scope_metrics["support"]}
                             for label, values in scope_metrics["per_class"].items())
    _, matrix, _ = multiclass_metrics(expected, actual, classes)
    multi_summary = {"views": category_scopes}
    under_supported = {label: int(manifest["counts"]["splits"]["test"]["category_label"].get(label, 0)) < 30 for label in classes}
    for row in per_class:
        row["under_supported_test_support_lt_30"] = under_supported[row["class"]]
        row["merged_from_metadata"] = ",".join(sorted(source for source, target in merge_map.items() if target == row["class"] and source != target))
        row["metadata_merge_status"] = "merged" if row["merged_from_metadata"] else "identity_or_unmerged"
    write_csv(test_dir / output_names["multiclass"], per_class)
    write_csv(test_dir / output_names["matrix"], matrix)

    reliability, ece = calibration(binary_labels, [float(row["ml_p"]) for row in rows])
    write_csv(test_dir / output_names["calibration"], reliability)
    write_reliability_svg(test_dir / output_names["plot"], reliability)

    groups: dict[str, list[int]] = defaultdict(list)
    for index, row in enumerate(rows):
        groups[row["duplicate_group"] or f"row-{index}"] .append(index)
    rng = random.Random(SEED)
    bootstrap_rows = []
    for system in SYSTEMS:
        intervals = cluster_bootstrap(rows, groups, score_keys[system], thresholds[system], rng)
        point = binary_counts(binary_labels, [float(row[score_keys[system]]) for row in rows], thresholds[system])
        for metric in ("recall", "specificity", "precision", "F1"):
            mean, lower, upper = intervals[metric]
            bootstrap_rows.append({"system": system, "metric": metric, "threshold": thresholds[system], "point_estimate": f(point[metric]), "bootstrap_mean": mean, "ci_lower": lower, "ci_upper": upper, "confidence_level": 0.95, "resamples": N_BOOTSTRAP, "seed": SEED, "bootstrap_scheme": "duplicate_group cluster bootstrap; sample 497 groups with replacement and include all rows in each sampled group", "n_rows": len(rows), "n_groups": len(groups)})
    write_csv(test_dir / output_names["bootstrap"], bootstrap_rows)

    slice_names = ["holdout-codex-cross-author-synthetic", "non-synthetic-public", *sorted({row["source_name"] for row in rows})]
    slice_rows = []
    for slice_name in slice_names:
        indexes = [index for index, row in enumerate(rows) if slice_predicate(slice_name, row)]
        slice_rows.extend(metric_rows_for_slice(rows, slice_name, indexes))
    write_csv(test_dir / output_names["slices"], slice_rows)

    sensitivity_rows = []
    for threshold in ML_THRESHOLDS:
        metrics = binary_counts(binary_labels, [float(row["ml_p"]) for row in rows], threshold)
        sensitivity_rows.append({"system": "ml", "score_field": "ml_p", "threshold": threshold, "threshold_scale": "probability", "selected_operating_point": threshold == 0.12, "informational_post_hoc_not_used_for_selection": True, **metrics})
    for system, score_key in (("rules", "rule_score"), ("combined", "combined_score")):
        for threshold in SCORE_THRESHOLDS:
            metrics = binary_counts(binary_labels, [float(row[score_key]) for row in rows], threshold)
            sensitivity_rows.append({"system": system, "score_field": score_key, "threshold": threshold, "threshold_scale": "risk_score_0_to_100", "selected_operating_point": threshold == 25.0, "informational_post_hoc_not_used_for_selection": False, **metrics})
    write_csv(test_dir / output_names["thresholds"], sensitivity_rows)

    review_rows = build_review_rows(rows)
    write_csv(test_dir / output_names["review"], review_rows)

    test_systems = {}
    for system in SYSTEMS:
        test_systems[system] = binary_counts(binary_labels, [float(row[score_keys[system]]) for row in rows], thresholds[system])
        test_systems[system]["threshold"] = thresholds[system]
    source_counts = {}
    for name in slice_names:
        selected = [row for row in rows if slice_predicate(name, row)]
        source_counts[name] = {"n": len(selected), "malicious": sum(row["binary_label"] == "malicious_or_fraudulent" for row in selected), "legitimate": sum(row["binary_label"] == "legitimate" for row in selected)}

    evaluation = read_json(test_dir / "evaluation.json")
    consistency = {}
    for system in SYSTEMS:
        recomputed = {key: test_systems[system][key] for key in ("TP", "TN", "FP", "FN", "accuracy", "precision", "recall", "specificity", "F1", "FPR", "FNR")}
        saved = evaluation["systems"][system]
        consistency[system] = {key: {"recomputed": f(recomputed[key]) if isinstance(recomputed[key], float) else recomputed[key], "evaluation_json": f(saved[key]) if isinstance(saved[key], float) else saved[key], "match": (abs(float(recomputed[key]) - float(saved[key])) < 1e-12 if isinstance(recomputed[key], float) else recomputed[key] == saved[key])} for key in recomputed}

    dataset_manifest_sha256 = hashlib.sha256((dataset_dir / "manifest.json").read_bytes()).hexdigest()
    summary = {
        "provenance": {
            "source_predictions": "models/evaluation/test/predictions_test.csv",
            "source_labels_and_duplicate_groups": "datasets/processed/trustshield-text-v1/test.jsonl",
            "test_evaluation_json": "models/evaluation/test/evaluation.json",
            "model_artifact_sha256": metadata["artifact_sha256"],
            "dataset_manifest_sha256": dataset_manifest_sha256,
            "test_jsonl_sha256": manifest["sha256"]["test.jsonl"],
            "test_access_log_count": len(access_log),
            "test_split_evaluator_invoked_by_this_script": False,
            "additional_metrics_are_recomputed_from_saved_predictions": True,
        },
        "dataset": {"n": len(rows), "malicious": sum(binary_labels), "legitimate": len(rows) - sum(binary_labels), "synthetic": sum(row["synthetic"] == "True" for row in rows), "non_synthetic": sum(row["synthetic"] == "False" for row in rows), "duplicate_groups": len(groups), "duplicate_group_multirow_count": sum(len(indexes) > 1 for indexes in groups.values())},
        "operating_points": {"ml_probability_threshold": 0.12, "rules_risk_score_threshold": 25, "combined_risk_score_threshold": 25, "secondary_rules_and_combined_risk_score_threshold": 50, "threshold_sensitivity_label": "post-hoc, not used for selection"},
        "systems": test_systems,
        "multiclass": {**multi_summary, "under_supported_test_support_lt_30": [label for label, value in under_supported.items() if value], "metadata_merge_map": merge_map, "metadata_merged_source_categories": {source: target for source, target in merge_map.items() if source != target}},
        "calibration": {"brier_score": brier(binary_labels, [float(row["ml_p"]) for row in rows]), "ece": ece, "ece_definition": "equal-width 10 bins over [0,1]; count-weighted mean absolute difference between mean predicted probability and observed malicious fraction; empty bins contribute zero", "bins": 10},
        "slices": source_counts,
        "manual_review_counts": {"ml_false_positives_read": 83, "ml_false_negatives_read": 20, "rules_false_positives_read": 9, "combined_false_negative_sample_read": 40, "combined_false_negative_sample_seed": 42, "review_candidate_rows_unique": len(review_rows)},
        "consistency_with_evaluation_json": consistency,
    }
    write_json(test_dir / output_names["summary"], summary)
    print(json.dumps({"output_dir": str(test_dir), "files": list(output_names.values()), "summary": summary}, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
