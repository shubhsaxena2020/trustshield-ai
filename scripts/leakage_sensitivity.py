"""Quantify frozen test-set template similarity without running the model."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import random
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer

_PROJECT_ROOT = str(Path(__file__).resolve().parents[1])
if _PROJECT_ROOT not in sys.path:
    sys.path.insert(0, _PROJECT_ROOT)

from services.analysis.ml.metrics import binary_metrics, pr_auc, roc_auc
from services.analysis.ml.metrics import category_scope_metrics

SEED = 42
N_BOOTSTRAP = 2000
PREVALENCE = 0.20
SIMILARITY_THRESHOLDS = (0.60, 0.80)
SUBSET_LIMITS = (None, 0.80, 0.70, 0.60)


def _read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _read_csv(path: Path) -> list[dict]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _similarity_matrix(test_texts: list[str], train_texts: list[str], pool_texts: list[str]):
    vectorizer = TfidfVectorizer(analyzer="char", ngram_range=(3, 5), sublinear_tf=True)
    train_matrix = vectorizer.fit_transform(train_texts)
    test_matrix = vectorizer.transform(test_texts)
    pool_matrix = vectorizer.transform(pool_texts)
    return test_matrix @ pool_matrix.T


def max_char_tfidf_similarity(test_texts: list[str], train_texts: list[str], pool_texts: list[str]) -> list[float]:
    """Return test-to-pool maxima using a vectorizer fitted on train_texts only."""
    similarities = _similarity_matrix(test_texts, train_texts, pool_texts)
    return np.asarray(similarities.max(axis=1).toarray()).ravel().astype(float).tolist()


def prevalence_adjusted_precision(recall: float, fpr: float, prevalence: float = PREVALENCE) -> float | None:
    denominator = recall * prevalence + fpr * (1.0 - prevalence)
    return float(recall * prevalence / denominator) if denominator else None


def _similarity_values(test_texts: list[str], train_texts: list[str], pool_texts: list[str]) -> tuple[list[float], dict[str, int]]:
    similarities = _similarity_matrix(test_texts, train_texts, pool_texts)
    maxima = np.asarray(similarities.max(axis=1).toarray()).ravel().astype(float).tolist()
    pairs = {str(threshold): int((similarities >= threshold).sum()) for threshold in SIMILARITY_THRESHOLDS}
    return maxima, pairs


def _slice_name(row: dict) -> str | None:
    source = str(row["source_name"]).lower()
    if source.startswith("holdout-codex"):
        return "holdout-codex"
    if "uci" in source or "india" in source:
        return "public-uci-india"
    return None


def _cluster_bootstrap(rows: list[dict], indexes: list[int], score_key: str, threshold: float) -> dict[str, float | int | str | None]:
    groups: dict[str, list[int]] = defaultdict(list)
    for index in indexes:
        groups[str(rows[index].get("duplicate_group") or rows[index]["id"])].append(index)
    labels = [int(rows[index]["binary_label"] == "malicious_or_fraudulent") for index in indexes]
    if len(groups) < 2 or len(set(labels)) < 2:
        return {"recall_ci_mean": None, "recall_ci_lower": None, "recall_ci_upper": None,
                "specificity_ci_mean": None, "specificity_ci_lower": None, "specificity_ci_upper": None,
                "ci_status": "not_computable_small_or_single_class"}
    group_ids = sorted(groups)
    rng = random.Random(SEED)
    recall_values: list[float] = []
    specificity_values: list[float] = []
    for _ in range(N_BOOTSTRAP):
        selected = [rng.choice(group_ids) for _ in group_ids]
        sample_indexes = [index for group_id in selected for index in groups[group_id]]
        sample_labels = [int(rows[index]["binary_label"] == "malicious_or_fraudulent") for index in sample_indexes]
        sample_scores = [float(rows[index][score_key]) for index in sample_indexes]
        values = binary_metrics(sample_labels, sample_scores, threshold)
        recall_values.append(float(values["recall"]))
        specificity_values.append(float(values["specificity"]))
    return {
        "recall_ci_mean": float(np.mean(recall_values)), "recall_ci_lower": float(np.percentile(recall_values, 2.5)),
        "recall_ci_upper": float(np.percentile(recall_values, 97.5)),
        "specificity_ci_mean": float(np.mean(specificity_values)), "specificity_ci_lower": float(np.percentile(specificity_values, 2.5)),
        "specificity_ci_upper": float(np.percentile(specificity_values, 97.5)), "ci_status": "ok",
    }


def _metric_row(rows: list[dict], indexes: list[int], system: str, score_key: str, threshold: float,
               similarity_basis: str, subset: str, similarity_limit: float | None, slice_name: str) -> dict:
    labels = [int(rows[index]["binary_label"] == "malicious_or_fraudulent") for index in indexes]
    scores = [float(rows[index][score_key]) for index in indexes]
    values = binary_metrics(labels, scores, threshold)
    values.update({"roc_auc": roc_auc(labels, scores) if len(set(labels)) > 1 else None,
                   "pr_auc": pr_auc(labels, scores) if any(labels) else None})
    row = {"similarity_basis": similarity_basis, "subset": subset, "similarity_limit": similarity_limit,
           "slice": slice_name, "system": system, "score_field": score_key, "threshold": threshold,
           "n": len(indexes), "malicious": sum(labels), "legitimate": len(labels) - sum(labels), **values}
    if system in {"ml", "combined"}:
        row["prevalence_adjusted_precision_pi_0_20"] = prevalence_adjusted_precision(float(values["recall"]), float(values["FPR"]))
        row["prevalence_adjusted_precision_note"] = "derived from test recall/FPR; prevalence-dependent; pi=0.20"
    if system == "ml":
        row.update(_cluster_bootstrap(rows, indexes, score_key, threshold))
        row["bootstrap_seed"] = SEED
        row["bootstrap_resamples"] = N_BOOTSTRAP
        row["bootstrap_unit"] = "duplicate_group"
    else:
        row.update({"recall_ci_mean": None, "recall_ci_lower": None, "recall_ci_upper": None,
                    "specificity_ci_mean": None, "specificity_ci_lower": None, "specificity_ci_upper": None,
                    "ci_status": "not_requested"})
    return row


def _write_csv(path: Path, rows: list[dict]) -> None:
    fieldnames = []
    for row in rows:
        for key in row:
            if key not in fieldnames:
                fieldnames.append(key)
    fieldnames = fieldnames or ["empty"]
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def _write_markdown(path: Path, rows: list[dict], diagnostics: dict) -> None:
    overall = [row for row in rows if row["slice"] == "all" and row["system"] == "ml" and row["similarity_basis"] == "train_plus_validation"]
    by_subset = {row["subset"]: row for row in overall}
    lines = [
        "# Leakage sensitivity of frozen v1 test results", "",
        "This is a saved-prediction analysis only. It did not load the model and did not invoke `evaluate_model.py --split test`.",
        "The character TF-IDF vectorizer uses 3–5 character n-grams with sublinear TF, is fit on train text only, and is then applied to train and train+validation pools.", "",
        f"The frozen test set has {by_subset['all']['n']} rows ({by_subset['all']['malicious']} malicious, {by_subset['all']['legitimate']} legitimate; {by_subset['all']['malicious'] / by_subset['all']['n']:.3f} malicious prevalence). The 400-row holdout-codex block is why this is about 47.5%, not the validation/training prevalence.", "",
        "## Recomputed cross-split similarity counts", "",
        f"- train→test pairs at cosine ≥0.60: {diagnostics['train']['pair_counts']['0.6']}",
        f"- train→test pairs at cosine ≥0.80: {diagnostics['train']['pair_counts']['0.8']}",
        f"- train+validation→test pairs at cosine ≥0.60: {diagnostics['train_plus_validation']['pair_counts']['0.6']}",
        f"- train+validation→test pairs at cosine ≥0.80: {diagnostics['train_plus_validation']['pair_counts']['0.8']}", "",
        "## ML recall/specificity as similarity-prone rows are removed", "",
        "| subset | n | recall | specificity | recall 95% CI | specificity 95% CI |",
        "|---|---:|---:|---:|---|---|",
    ]
    for subset in ("all", "max_similarity_lt_0.80", "max_similarity_lt_0.70", "max_similarity_lt_0.60"):
        row = by_subset[subset]
        lines.append(f"| {subset} | {row['n']} | {row['recall']:.6f} | {row['specificity']:.6f} | [{row['recall_ci_lower']:.6f}, {row['recall_ci_upper']:.6f}] | [{row['specificity_ci_lower']:.6f}, {row['specificity_ci_upper']:.6f}] |" if row["ci_status"] == "ok" else f"| {subset} | {row['n']} | {row['recall']:.6f} | {row['specificity']:.6f} | {row['ci_status']} | {row['ci_status']} |")
    controlled = by_subset["max_similarity_lt_0.60"]
    lines += ["", f"Interpretation: removing rows with train+validation similarity at or above 0.60 leaves {controlled['n']} of {by_subset['all']['n']} test rows. ML recall changes from {by_subset['all']['recall']:.6f} to {controlled['recall']:.6f}, while specificity changes from {by_subset['all']['specificity']:.6f} to {controlled['specificity']:.6f}. These controlled subsets are smaller and exclude the most template-near test rows; they are the more defensible generalisation view, while the overall test result remains the complete frozen-set result.", "",
        "Prevalence-adjusted precision is derived from recall and FPR at π=0.20 and is therefore prevalence-dependent; it is not a replacement for the observed 47.5% test prevalence.", ""]
    path.write_text("\n".join(lines), encoding="utf-8")


def _write_frozen_multiclass_report(path: Path, rows: list[dict], merge_map: dict[str, str]) -> None:
    actual = [row["category_label"] for row in rows]
    predicted = [str(row["ml_category"]) if str(row["ml_category"]) else ("other_fraud" if float(row["ml_p"]) >= 0.12 else "legitimate") for row in rows]
    labels = [int(row["binary_label"] == "malicious_or_fraudulent") for row in rows]
    scopes = category_scope_metrics(actual, predicted, labels, merge_map)
    output = []
    for view, view_scopes in scopes.items():
        for scope, metrics in view_scopes.items():
            for category, values in metrics["per_class"].items():
                output.append({"system": "ml", "view": view, "scope": scope, "class": category, **values,
                               "macro_f1": metrics["macro_f1"], "weighted_f1": metrics["weighted_f1"],
                               "scope_support": metrics["support"], "malicious": metrics["malicious"], "legitimate": metrics["legitimate"]})
    _write_csv(path, output)
    return scopes


def run(project_root: str | Path | None = None) -> dict:
    root = Path(project_root or Path(__file__).resolve().parents[1]).resolve()
    dataset_dir = root / "datasets" / "processed" / "trustshield-text-v1"
    test_dir = root / "models" / "evaluation" / "test"
    train_rows = _read_jsonl(dataset_dir / "train.jsonl")
    validation_rows = _read_jsonl(dataset_dir / "validation.jsonl")
    test_labels = _read_jsonl(dataset_dir / "test.jsonl")
    predictions = _read_csv(test_dir / "predictions_test.csv")
    label_by_id = {row["id"]: row for row in test_labels}
    if set(label_by_id) != {row["id"] for row in predictions}:
        raise ValueError("test labels and saved predictions do not have the same ids")
    rows = []
    for prediction in predictions:
        label = label_by_id[prediction["id"]]
        rows.append({**prediction, "binary_label": label["binary_label"], "duplicate_group": label.get("duplicate_group", ""), "source_name": label["source_name"]})
    test_texts = [row["text"] for row in rows]
    train_texts = [row["text"] for row in train_rows]
    validation_texts = [row["text"] for row in validation_rows]
    train_max, train_pairs = _similarity_values(test_texts, train_texts, train_texts)
    combined_pool = train_texts + validation_texts
    combined_max, combined_pairs = _similarity_values(test_texts, train_texts, combined_pool)
    for row, value, combined_value in zip(rows, train_max, combined_max):
        row["max_similarity_to_train"] = value
        row["max_similarity_to_train_plus_validation"] = combined_value

    systems = (("rules", "rule_score", 25.0), ("ml", "ml_p", 0.12), ("combined", "combined_score", 25.0))
    output_rows = []
    for basis, key in (("train", "max_similarity_to_train"), ("train_plus_validation", "max_similarity_to_train_plus_validation")):
        for subset_limit in SUBSET_LIMITS:
            subset = "all" if subset_limit is None else f"max_similarity_lt_{subset_limit:.2f}"
            for slice_name in ("all", "holdout-codex", "public-uci-india"):
                indexes = [index for index, row in enumerate(rows)
                           if (subset_limit is None or float(row[key]) < subset_limit)
                           and (slice_name == "all" or _slice_name(row) == slice_name)]
                for system, score_key, threshold in systems:
                    output_rows.append(_metric_row(rows, indexes, system, score_key, threshold, basis, subset, subset_limit, slice_name))
    diagnostics = {"train": {"pair_counts": train_pairs, "test_rows": len(train_max)},
                   "train_plus_validation": {"pair_counts": combined_pairs, "test_rows": len(combined_max)}}
    csv_path = test_dir / "leakage_sensitivity.csv"
    json_path = test_dir / "leakage_sensitivity.json"
    md_path = test_dir / "leakage_sensitivity.md"
    metadata = json.loads((root / "models" / "metadata" / "trustshield_text_v1.json").read_text(encoding="utf-8"))
    multiclass_scopes = _write_frozen_multiclass_report(test_dir / "multiclass_metrics_test.csv", rows, metadata.get("category_merge_map", {}))
    _write_csv(csv_path, output_rows)
    summary = {"provenance": {"predictions": str((test_dir / "predictions_test.csv").relative_to(root)),
                               "train": str((dataset_dir / "train.jsonl").relative_to(root)),
                               "validation": str((dataset_dir / "validation.jsonl").relative_to(root)),
                               "test": str((dataset_dir / "test.jsonl").relative_to(root)),
                               "test_access_log_count": len(json.loads((root / "models" / "evaluation" / "test_access_log.json").read_text(encoding="utf-8"))),
                               "model_run": False},
              "vectorizer": {"analyzer": "char", "ngram_range": [3, 5], "sublinear_tf": True, "fit_on": "train_only"},
              "seed": SEED, "bootstrap_resamples": N_BOOTSTRAP, "prevalence": PREVALENCE,
              "diagnostics": diagnostics, "multiclass_scopes": multiclass_scopes, "rows": output_rows,
              "input_sha256": {name: _sha256(path) for name, path in (("predictions_test.csv", test_dir / "predictions_test.csv"), ("train.jsonl", dataset_dir / "train.jsonl"), ("validation.jsonl", dataset_dir / "validation.jsonl"), ("test.jsonl", dataset_dir / "test.jsonl"))}}
    json_path.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    _write_markdown(md_path, output_rows, diagnostics)
    return {"csv": str(csv_path), "json": str(json_path), "markdown": str(md_path), "rows": len(output_rows), "diagnostics": diagnostics}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parents[1])
    import sys as _sys
    from pathlib import Path as _Path
    _sys.path.insert(0, str(_Path(__file__).resolve().parents[1]))
    from scripts._v2_spent_guard import require_archival_override
    args = parser.parse_args(require_archival_override(None))
    print(json.dumps(run(args.project_root), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
