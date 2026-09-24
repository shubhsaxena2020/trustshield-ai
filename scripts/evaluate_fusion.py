"""Evaluate Phase 4 fusion strategies on train OOF or validation data only.

The train OOF split tunes strategy parameters.  Validation is evaluated once
per candidate and is used for candidate selection.  This script refuses the
sealed test split and never opens ``test.jsonl`` or a holdout directory.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import sys
from collections import defaultdict
from pathlib import Path
import numpy as np
from sklearn.metrics import average_precision_score, roc_auc_score

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from services.analysis.evidence.models import Evidence, Severity
from services.analysis.fusion.engine import fuse, risk_score
from services.analysis.pipeline import analyze_text
from services.analysis.fusion.strategies import DEFAULT_STRONG_ML_CUT, MODERATE_ML_CUT

SEED = 42
ML_THRESHOLD = 0.12
CANDIDATES = ("legacy", "ml_aware_weighted", "strength_gated")
COMPLEXITY = {"legacy": 0, "ml_aware_weighted": 1, "strength_gated": 2}


def validate_split(value: str) -> str:
    if value not in {"train_oof", "validation"}:
        raise ValueError("only --split train_oof or --split validation is allowed; test is sealed")
    return value


def _safe_dataset_dir(path: Path) -> Path:
    resolved = path.resolve()
    parts = {part.lower() for part in resolved.parts}
    if "holdout" in parts or "test" in parts:
        raise ValueError("sealed test/holdout paths are not allowed")
    return resolved


def _read_csv(path: Path) -> list[dict]:
    resolved = path.resolve()
    if resolved.name.startswith("predictions_test") or "test" in {part.lower() for part in resolved.parts}:
        raise ValueError("test predictions are sealed")
    with resolved.open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def _read_jsonl(path: Path, expected_name: str) -> list[dict]:
    if path.name != expected_name:
        raise ValueError(f"may open only {expected_name}")
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def _write_csv(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    keys = list(rows[0]) if rows else ["empty"]
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=keys, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def _binary_metrics(labels: list[int], scores: list[float], threshold: float) -> dict:
    predictions = [int(score >= threshold) for score in scores]
    tp = sum(y == 1 and p == 1 for y, p in zip(labels, predictions))
    tn = sum(y == 0 and p == 0 for y, p in zip(labels, predictions))
    fp = sum(y == 0 and p == 1 for y, p in zip(labels, predictions))
    fn = sum(y == 1 and p == 0 for y, p in zip(labels, predictions))
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    specificity = tn / (tn + fp) if tn + fp else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {"TP": tp, "TN": tn, "FP": fp, "FN": fn, "precision": precision, "recall": recall,
            "specificity": specificity, "F1": f1, "FPR": 1.0 - specificity, "FNR": 1.0 - recall,
            "accuracy": (tp + tn) / len(labels) if labels else 0.0, "threshold": threshold}


def _curve_metrics(labels: list[int], scores: list[float]) -> dict:
    return {"roc_auc": float(roc_auc_score(labels, scores)) if len(set(labels)) > 1 else None,
            "pr_auc": float(average_precision_score(labels, scores)) if len(set(labels)) > 1 else None}


def _deterministic_evidence(text: str) -> list[Evidence]:
    # Disable ML while replaying only the deterministic evidence path.  The
    # saved p_malicious column is then attached below, avoiding a second model
    # inference and keeping evaluation tied to the saved validation prediction.
    previous = os.environ.get("TRUSTSHIELD_ML")
    os.environ["TRUSTSHIELD_ML"] = "off"
    try:
        result = analyze_text(text)
    finally:
        if previous is None:
            os.environ.pop("TRUSTSHIELD_ML", None)
        else:
            os.environ["TRUSTSHIELD_ML"] = previous
    return [item for item in result.evidence if item.source != "ml_classifier"]


def _evidence_for(row: dict) -> tuple[list[Evidence], dict[str, str]]:
    evidence = _deterministic_evidence(row["text"])
    probability = float(row["ml_p"])
    if probability >= ML_THRESHOLD:
        category = row.get("ml_category") or None
        evidence.append(Evidence(
            source="ml_classifier", category=f"ml_{category}_pattern" if category else "ml_malicious_content",
            # Preserve the frozen ML evidence emission tier for the legacy
            # candidate. New strategies classify p with their tuned strength
            # cut independently of this unchanged evidence severity.
            severity=Severity.high if probability >= 0.90 else Severity.medium,
            confidence=probability,
            title="Machine-learning classifier detected malicious content",
            description="Statistical classifier signal used for fusion evaluation.",
            metadata={"category": category, "threshold": ML_THRESHOLD},
        ))
    return evidence, {"text_rules": "completed", "ml": "completed"}


def _load_rows(split: str, dataset_dir: Path, project_root: Path) -> tuple[list[dict], list[str]]:
    if split == "train_oof":
        path = project_root / "models" / "evaluation" / "fusion" / "train_oof_predictions.csv"
        rows = _read_csv(path)
        input_files = [str(path)]
    else:
        path = project_root / "models" / "evaluation" / "validation" / "predictions_validation.csv"
        rows = _read_csv(path)
        validation_path = dataset_dir / "validation.jsonl"
        validation_rows = _read_jsonl(validation_path, "validation.jsonl")
        groups = {row["id"]: row.get("duplicate_group") or row["id"] for row in validation_rows}
        for row in rows:
            row["duplicate_group"] = groups.get(row["id"], row["id"])
        input_files = [str(path), str(validation_path)]
    for row in rows:
        row["ml_p"] = float(row["ml_p"])
        row["binary"] = int(row["binary_label"] == "malicious_or_fraudulent")
        row["source_name"] = row.get("source_name", "")
    return rows, input_files


def _make_evidence(rows: list[dict]) -> list[tuple[list[Evidence], dict[str, str]]]:
    return [_evidence_for(row) for row in rows]


def _evaluate_candidate(name: str, rows: list[dict], evidence_rows: list[tuple[list[Evidence], dict[str, str]]],
                        *, ml_weight: float = 1.0, strong_ml_cut: float = DEFAULT_STRONG_ML_CUT,
                        tier_minimum_scores: dict[str, int] | None = None) -> list[dict]:
    output = []
    for row, (evidence, statuses) in zip(rows, evidence_rows):
        result = fuse(evidence, statuses, strategy_name=name, module_quality=None,
                      strategy_params={"ml_weight": ml_weight, "strong_ml_cut": strong_ml_cut,
                                       "tier_minimum_scores": tier_minimum_scores} if name != "legacy" else None)
        output.append({"id": row["id"], "score": result.score, "label": result.label,
                       "ml_p": row["ml_p"], "binary": row["binary"],
                       "rule_score": risk_score([item for item in evidence if item.source != "ml_classifier"]),
                       "duplicate_group": row.get("duplicate_group", row["id"]),
                       "source_name": row.get("source_name", ""), "fusion_data": result.data or {}})
    return output


def _tune_parameters(rows: list[dict], evidence_rows: list[tuple[list[Evidence], dict[str, str]]]) -> dict:
    labels = [row["binary"] for row in rows]
    candidates = []
    for weight in (0.50, 0.75, 1.00, 1.25, 1.50, 2.00):
        values = _evaluate_candidate("ml_aware_weighted", rows, evidence_rows, ml_weight=weight)
        scores = [item["score"] for item in values]
        metrics = _binary_metrics(labels, scores, 25)
        candidates.append((metrics["specificity"] >= 0.85, metrics["F1"], -weight, "ml_aware_weighted", {"ml_weight": weight}))
    b = max(candidates, key=lambda item: (item[0], item[1], item[2]))
    gated = []
    for cut in (0.70, 0.75, 0.80, 0.85, 0.90):
        for moderate_floor in (25, 30):
            for strong_floor in (50, 60):
                params = {"strong_ml_cut": cut, "tier_minimum_scores": {"weak": 0, "moderate": moderate_floor, "strong": strong_floor}}
                values = _evaluate_candidate("strength_gated", rows, evidence_rows, **params)
                metrics = _binary_metrics(labels, [item["score"] for item in values], 25)
                gated.append((metrics["specificity"] >= 0.85, metrics["F1"], -cut, -moderate_floor, -strong_floor, params))
    c = max(gated, key=lambda item: item[:-1])
    return {"seed": SEED, "train_oof_rows": len(rows), "moderate_ml_cut": MODERATE_ML_CUT,
            "ml_aware_weighted": b[4], "strength_gated": c[-1],
            "selection_rule": "highest train-OOf caution F1 subject to specificity >= 0.85; ties prefer simpler/lower parameter values",
            "learned_meta": {"status": "skipped", "reason": "No new trained production artifact is permitted in Phase 4; a runtime-loaded meta model would violate the frozen-artifact boundary."}}


def _agreement_rows(values: list[dict], candidate: str, malicious_total: int) -> list[dict]:
    cells = {"agree_malicious": lambda item: item["ml_p"] >= ML_THRESHOLD and item["rule_score"] >= 25,
             "ml_only": lambda item: item["ml_p"] >= ML_THRESHOLD and item["rule_score"] < 25,
             "rules_only": lambda item: item["ml_p"] < ML_THRESHOLD and item["rule_score"] >= 25,
             "both_negative": lambda item: item["ml_p"] < ML_THRESHOLD and item["rule_score"] < 25}
    output = []
    for name, predicate in cells.items():
        subset = [item for item in values if predicate(item)]
        tp = sum(item["binary"] for item in subset)
        output.append({"candidate": candidate, "cell": name, "rows": len(subset), "malicious": tp,
                       "legitimate": len(subset) - tp, "precision": tp / len(subset) if subset else 0.0,
                       "recall": tp / malicious_total if malicious_total else 0.0})
    return output


def _cluster_bootstrap(values: list[dict], seed: int = SEED, samples: int = 2000) -> dict:
    groups = defaultdict(list)
    for index, item in enumerate(values):
        groups[item["duplicate_group"]].append(index)
    group_values = list(groups.values())
    rng = np.random.default_rng(seed)
    recalls, specificities = [], []
    for _ in range(samples):
        indexes = np.concatenate([group_values[index] for index in rng.integers(0, len(group_values), len(group_values))])
        labels = [values[index]["binary"] for index in indexes]
        predictions = [values[index]["prediction"] for index in indexes]
        tp = sum(y and p for y, p in zip(labels, predictions)); fn = sum(y and not p for y, p in zip(labels, predictions))
        tn = sum(not y and not p for y, p in zip(labels, predictions)); fp = sum(not y and p for y, p in zip(labels, predictions))
        recalls.append(tp / (tp + fn) if tp + fn else 0.0)
        specificities.append(tn / (tn + fp) if tn + fp else 0.0)
    return {"recall": [float(np.percentile(recalls, 2.5)), float(np.percentile(recalls, 97.5))],
            "specificity": [float(np.percentile(specificities, 2.5)), float(np.percentile(specificities, 97.5))],
            "groups": len(group_values), "samples": samples, "seed": seed}


def _selection(comparison: list[dict]) -> str:
    eligible = [row for row in comparison if row.get("operating_point") == "caution" and row.get("status") == "evaluated"
                and float(row["specificity"]) >= 0.85]
    if not eligible:
        return "legacy"
    selected = max(eligible, key=lambda row: (float(row["F1"]), -COMPLEXITY[row["candidate"]]))
    return selected["candidate"]


def evaluate(split: str, dataset_dir: str | Path, project_root: str | Path | None = None) -> dict:
    split = validate_split(split)
    project_root = Path(project_root or ROOT).resolve()
    dataset_dir = _safe_dataset_dir(Path(dataset_dir))
    rows, input_files = _load_rows(split, dataset_dir, project_root)
    evidence_rows = _make_evidence(rows)
    tuning = None
    if split == "train_oof":
        tuning = _tune_parameters(rows, evidence_rows)
        tuning_path = project_root / "models" / "evaluation" / "fusion" / "tuning.json"
        tuning_path.parent.mkdir(parents=True, exist_ok=True)
        tuning_path.write_text(json.dumps(tuning, indent=2) + "\n", encoding="utf-8")
    else:
        tuning_path = project_root / "models" / "evaluation" / "fusion" / "tuning.json"
        if not tuning_path.is_file():
            raise FileNotFoundError("run fusion_oof_predictions.py and evaluate_fusion.py --split train_oof first")
        tuning = json.loads(tuning_path.read_text(encoding="utf-8"))

    parameter_map = {"legacy": {}, "ml_aware_weighted": tuning["ml_aware_weighted"], "strength_gated": tuning["strength_gated"]}
    candidate_values = {}
    for candidate in CANDIDATES:
        candidate_values[candidate] = _evaluate_candidate(candidate, rows, evidence_rows, **parameter_map[candidate])
    labels = [row["binary"] for row in rows]
    malicious_total = sum(labels)
    comparison = []
    source_rows = []
    agreement = []
    suppression = []
    distributions = []
    for candidate, values in candidate_values.items():
        scores = [item["score"] for item in values]
        for operating_point, threshold in (("caution", 25), ("suspicious", 50)):
            metrics = _binary_metrics(labels, scores, threshold)
            metrics.update(_curve_metrics(labels, scores))
            comparison.append({"split": split, "candidate": candidate, "status": "evaluated",
                               "operating_point": operating_point, **metrics})
            for source in sorted({row.get("source_name", "") for row in rows}):
                indexes = [index for index, row in enumerate(rows) if row.get("source_name", "") == source]
                source_metrics = _binary_metrics([labels[index] for index in indexes], [scores[index] for index in indexes], threshold)
                source_rows.append({"candidate": candidate, "operating_point": operating_point, "source_name": source, **source_metrics})
        agreement.extend(_agreement_rows(values, candidate, malicious_total))
        for threshold_name, threshold in (("caution", 25), ("suspicious", 50)):
            ml_positive = [item for item in values if item["ml_p"] >= ML_THRESHOLD]
            suppressed = [item for item in ml_positive if item["score"] < threshold]
            malicious_ml = [item for item in ml_positive if item["binary"]]
            malicious_suppressed = [item for item in suppressed if item["binary"]]
            suppression.append({"candidate": candidate, "operating_point": threshold_name,
                               "ml_positive": len(ml_positive), "suppressed": len(suppressed),
                               "suppression_pct": len(suppressed) / len(ml_positive) * 100 if ml_positive else 0.0,
                               "malicious_ml_positive": len(malicious_ml), "malicious_suppressed": len(malicious_suppressed),
                               "malicious_suppression_pct": len(malicious_suppressed) / len(malicious_ml) * 100 if malicious_ml else 0.0})
        counts = defaultdict(int)
        for item in values:
            counts[item["label"]] += 1
        distributions.extend({"candidate": candidate, "label": label, "count": count} for label, count in sorted(counts.items()))

    comparison.append({"split": split, "candidate": "learned_meta", "status": "skipped",
                       "operating_point": "not_evaluated", "reason": tuning["learned_meta"]["reason"]})
    selected = _selection(comparison) if split == "validation" else "strength_gated"
    selected_values = candidate_values[selected]
    selected_pred = [{**item, "prediction": int(item["score"] >= 25)} for item in selected_values]
    legacy_pred = [{**item, "prediction": int(item["score"] >= 25)} for item in candidate_values["legacy"]]
    selected_ci = _cluster_bootstrap(selected_pred)
    legacy_ci = _cluster_bootstrap(legacy_pred)
    selected_rec = _binary_metrics(labels, [item["score"] for item in selected_values], 25)["recall"]
    legacy_rec = _binary_metrics(labels, [item["score"] for item in candidate_values["legacy"]], 25)["recall"]
    selected_spec = _binary_metrics(labels, [item["score"] for item in selected_values], 25)["specificity"]
    legacy_spec = _binary_metrics(labels, [item["score"] for item in candidate_values["legacy"]], 25)["specificity"]
    bootstrap = {"selected_candidate": selected, "legacy": legacy_ci, "selected": selected_ci,
                 "point_difference": {"recall": selected_rec - legacy_rec, "specificity": selected_spec - legacy_spec},
                 "difference_exceeds_noise": {
                     "recall": selected_ci["recall"][0] > legacy_ci["recall"][1] or legacy_ci["recall"][0] > selected_ci["recall"][1],
                     "specificity": selected_ci["specificity"][0] > legacy_ci["specificity"][1] or legacy_ci["specificity"][0] > selected_ci["specificity"][1],
                 }}

    out_dir = project_root / "models" / "evaluation" / "fusion" / split
    out_dir.mkdir(parents=True, exist_ok=True)
    pred_rows = []
    for index, row in enumerate(rows):
        item = {"id": row["id"], "binary_label": row["binary_label"], "source_name": row.get("source_name", ""), "ml_p": row["ml_p"]}
        for candidate in CANDIDATES:
            value = candidate_values[candidate][index]
            item[f"{candidate}_score"] = value["score"]
            item[f"{candidate}_label"] = value["label"]
        pred_rows.append(item)
    _write_csv(out_dir / ("predictions_validation_fusion.csv" if split == "validation" else "predictions_train_oof_fusion.csv"), pred_rows)
    _write_csv(out_dir / "candidate_comparison.csv", comparison)
    _write_csv(out_dir / "per_source_breakdown.csv", source_rows)
    _write_csv(out_dir / "agreement_table.csv", agreement)
    _write_csv(out_dir / "suppression.csv", suppression)
    _write_csv(out_dir / "decision_state_distribution.csv", distributions)
    for candidate, values in candidate_values.items():
        for name, threshold in (("caution", 25), ("suspicious", 50)):
            metrics = _binary_metrics(labels, [item["score"] for item in values], threshold)
            _write_csv(out_dir / f"confusion_matrix_{candidate}_{name}.csv", [
                {"actual": "legitimate", "predicted_legitimate": metrics["TN"], "predicted_malicious": metrics["FP"]},
                {"actual": "malicious_or_fraudulent", "predicted_legitimate": metrics["FN"], "predicted_malicious": metrics["TP"]},
            ])
    (out_dir / "bootstrap_confidence_intervals.json").write_text(json.dumps(bootstrap, indent=2) + "\n", encoding="utf-8")
    summary = {"split": split, "candidate_count": len(CANDIDATES), "selected_candidate": selected,
               "selection_rule": "highest validation caution F1 subject to specificity >= 0.85; ties prefer simpler strategy",
               "input_files": input_files, "tuning_file": str(tuning_path), "bootstrap": bootstrap,
               "learned_meta": tuning["learned_meta"]}
    (out_dir / "evaluation.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    markdown = [f"# Fusion evaluation: {split}", "", f"Selected candidate: `{selected}`", "",
                "Selection: highest caution+ F1 subject to specificity >= 0.85; ties prefer simpler strategy.", "",
                "Learned meta: skipped because Phase 4 does not add a trained production artifact.", "",
                "Input files:"]
    markdown.extend(f"- `{path}`" for path in input_files)
    (out_dir / "evaluation.md").write_text("\n".join(markdown) + "\n", encoding="utf-8")
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--split", required=True)
    parser.add_argument("--dataset-dir", type=Path, required=True)
    parser.add_argument("--project-root", type=Path)
    args = parser.parse_args()
    try:
        summary = evaluate(args.split, args.dataset_dir, args.project_root)
    except (ValueError, FileNotFoundError) as exc:
        parser.error(str(exc))
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
