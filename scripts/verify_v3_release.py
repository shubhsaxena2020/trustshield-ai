"""Independent v3 release verification: leakage/similarity + hard-negative evaluation.

Reads train/validation only (never test, never holdout). Writes
models/evaluation/hardening_v3/release_verification.json.
"""
from __future__ import annotations

import csv
import json
import random
import re
import sys
from pathlib import Path

import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from scripts.prepare_hardening_candidates import bucket_names  # noqa: E402

V1 = ROOT / "datasets/processed/trustshield-text-v1"
V3 = ROOT / "datasets/processed/trustshield-text-v3"
OUT = ROOT / "models/evaluation/hardening_v3"
FORBIDDEN = ("holdout", "codex", "mendeley", "reportsmishing")
THRESHOLDS = (0.6, 0.7, 0.8, 0.85, 0.9)


def rows(path):
    return [json.loads(line) for line in path.open(encoding="utf-8") if line.strip()]


def norm(text):
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9]+", " ", text.lower())).strip()


def cos_counts(a, b):
    vec = TfidfVectorizer(analyzer="char_wb", ngram_range=(3, 5), sublinear_tf=True).fit(a + b)
    A, B = vec.transform(a), vec.transform(b)
    counts = {t: 0 for t in THRESHOLDS}
    max_per_a = np.zeros(A.shape[0])
    for start in range(0, A.shape[0], 500):
        sim = (A[start:start + 500] @ B.T).toarray()
        max_per_a[start:start + 500] = sim.max(axis=1)
        for t in THRESHOLDS:
            counts[t] += int((sim >= t).sum())
    return {str(t): c for t, c in counts.items()}, max_per_a


def load_scores(path, col):
    with path.open(encoding="utf-8") as handle:
        return {r["id"]: float(r[col]) for r in csv.DictReader(handle)}


def rate(flags):
    flags = list(flags)
    return sum(flags) / len(flags) if flags else None


def main():
    v1_train, v3_train = rows(V1 / "train.jsonl"), rows(V3 / "train.jsonl")
    v3_val = rows(V3 / "validation.jsonl")
    res = {"validation_identical_to_v1": (V1 / "validation.jsonl").read_bytes() == (V3 / "validation.jsonl").read_bytes()}

    tt, vt = [r["text"] for r in v3_train], [r["text"] for r in v3_val]
    res["exact_overlap_train_val"] = len(set(tt) & set(vt))
    res["normalized_overlap_train_val"] = len({norm(t) for t in tt} & {norm(t) for t in vt})
    res["duplicate_group_overlap_train_val"] = len({r["duplicate_group"] for r in v3_train} & {r["duplicate_group"] for r in v3_val})
    res["exact_dups_within_train"] = len(tt) - len(set(tt))
    res["normalized_dups_within_train"] = len(tt) - len({norm(t) for t in tt})
    seen, conflicts = {}, 0
    for r in v3_train:
        key = norm(r["text"])
        if key in seen and seen[key] != r["binary_label"]:
            conflicts += 1
        seen[key] = r["binary_label"]
    res["label_conflicts_within_train"] = conflicts
    res["forbidden_source_rows_in_train"] = sum(
        any(f in (r["source_name"] + " " + r.get("annotation_method", "")).lower() for f in FORBIDDEN) for r in v3_train)
    res["cosine_train_val_pairs"], _ = cos_counts(tt, vt)
    v1_ids = {r["id"] for r in v1_train}
    new_rows = [r for r in v3_train if r["id"] not in v1_ids]
    res["new_rows_vs_v1_train"] = len(new_rows)
    by_source = {}
    for r in new_rows:
        by_source[r["source_name"]] = by_source.get(r["source_name"], 0) + 1
    res["new_rows_by_source"] = by_source
    _, mx = cos_counts([r["text"] for r in new_rows], vt)
    res["new_rows_max_cosine_to_validation"] = float(mx.max())
    res["new_rows_ge_0.60_to_validation"] = int((mx >= 0.60).sum())
    res["real_vs_synthetic_train"] = {"real": sum(not r["synthetic"] for r in v3_train),
                                      "synthetic": sum(bool(r["synthetic"]) for r in v3_train)}
    res["train_rows_with_spaced_phone_or_listinfo_or_enron"] = sum(
        bool(re.search(r"\d{3}\s+-\s+\d{3}\s+-\s+\d{4}|listinfo|\benron\b", r["text"], re.I)) for r in v3_train)

    old = load_scores(ROOT / "models/evaluation/fusion/validation/predictions_validation_fusion.csv", "ml_aware_weighted_score")
    new = load_scores(OUT / "fusion/validation/predictions_validation_fusion.csv", "ml_aware_weighted_score")
    old_ml = load_scores(ROOT / "models/evaluation/validation/predictions_validation.csv", "ml_p")
    new_ml = load_scores(OUT / "validation/predictions_validation.csv", "ml_p")
    t_old = json.loads((ROOT / "models/metadata/trustshield_text_v1.json").read_text())["threshold"]
    t_new = json.loads((ROOT / "models/metadata/trustshield_text_v3.json").read_text())["threshold"]
    res["thresholds"] = {"v1": t_old, "v3": t_new}

    legit = [r for r in v3_val if r["binary_label"] == "legitimate"]
    mal = [r for r in v3_val if r["binary_label"] != "legitimate"]
    ev = {}
    for name, pred in (("all_legit", lambda r: True), ("real_legit", lambda r: not r["synthetic"]),
                       ("synthetic_legit", lambda r: r["synthetic"])):
        sel = [r for r in legit if pred(r)]
        ev[name] = {"n": len(sel),
                    "fusion_fpr_v1": rate(old[r["id"]] >= 25 for r in sel), "fusion_fpr_v3": rate(new[r["id"]] >= 25 for r in sel),
                    "ml_fpr_v1": rate(old_ml[r["id"]] >= t_old for r in sel), "ml_fpr_v3": rate(new_ml[r["id"]] >= t_new for r in sel)}
    buckets = {}
    for b in ("bank_account", "otp_pin", "payment", "urgent_service", "job", "delivery", "telecom"):
        for scope, pred in (("all", lambda r: True), ("real", lambda r: not r["synthetic"])):
            sel = [r for r in legit if b in bucket_names(r["text"]) and pred(r)]
            buckets[f"{b}/{scope}"] = {"n": len(sel), "fpr_v1": rate(old[r["id"]] >= 25 for r in sel),
                                       "fpr_v3": rate(new[r["id"]] >= 25 for r in sel)}
    hard = [r for r in legit if bucket_names(r["text"])]
    for name, sel in (("union/all", hard), ("union/real", [r for r in hard if not r["synthetic"]])):
        buckets[name] = {"n": len(sel), "fpr_v1": rate(old[r["id"]] >= 25 for r in sel), "fpr_v3": rate(new[r["id"]] >= 25 for r in sel)}
    ev["buckets"] = buckets

    real_legit = [r for r in legit if not r["synthetic"]]
    rng, diffs = random.Random(42), []
    for _ in range(2000):
        s = [rng.choice(real_legit) for _ in real_legit]
        diffs.append(rate(new[r["id"]] >= 25 for r in s) - rate(old[r["id"]] >= 25 for r in s))
    diffs.sort()
    ev["real_legit_fusion_fpr_diff_v3_minus_v1"] = {"mean": float(np.mean(diffs)), "ci95": [diffs[49], diffs[1949]]}
    ev["malicious_recall_fusion"] = {
        s: {"n": len([r for r in mal if p(r)]), "v1": rate(old[r["id"]] >= 25 for r in mal if p(r)),
            "v3": rate(new[r["id"]] >= 25 for r in mal if p(r))}
        for s, p in (("real", lambda r: not r["synthetic"]), ("synthetic", lambda r: r["synthetic"]))}
    res["hard_negative_evaluation"] = ev
    (OUT / "release_verification.json").write_text(json.dumps(res, indent=2), encoding="utf-8")
    print(json.dumps(res, indent=2))


if __name__ == "__main__":
    main()
