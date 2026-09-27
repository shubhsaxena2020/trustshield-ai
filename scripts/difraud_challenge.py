"""Stage-2 source-held-out challenge: v3 vs. the SemanticSecurityAnalyst on the 1,679 DIFrauD SMS rows that do NOT
overlap with any corpus already in datasets/processed/public/ -- i.e. text v3 has genuinely never seen in any split.

Pre-registered in docs/LLM_SEMANTIC_BENCHMARK_PROTOCOL.md's "Protocol v2 addendum", written and committed BEFORE this
script was run. DEVELOPMENT/GENERALIZATION data, not a new sealed holdout -- see that addendum for why. Requires
TRUSTSHIELD_SEMANTIC_ANALYST=remote and a reachable 9Router (real LLM calls; never run in CI).

Run scripts/fetch_difraud_sms.py first to populate datasets/raw/difraud_sms/all.jsonl (gitignored, regenerated from the
public MIT-licensed redasers/difraud HF dataset).

Usage: python scripts/difraud_challenge.py [--n-per-class 100]
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PUBLIC = ROOT / "datasets" / "processed" / "public"
DIFRAUD_RAW = ROOT / "datasets" / "raw" / "difraud_sms" / "all.jsonl"
SEED = 20260927
POSITIVE = "malicious_or_fraudulent"
MIN_COUNT_FOR_RATE = 20


def _load_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def load_non_overlapping_difraud() -> list[dict]:
    if not DIFRAUD_RAW.exists():
        raise SystemExit(f"missing {DIFRAUD_RAW} -- run scripts/fetch_difraud_sms.py first")
    existing_texts = set()
    for name in ("india_spam_sms.jsonl", "ucI_sms_spam.jsonl"):
        for r in _load_jsonl(PUBLIC / name):
            existing_texts.add(r["text"].strip().lower())
    difraud = _load_jsonl(DIFRAUD_RAW)
    return [r for r in difraud if r["text"].strip().lower() not in existing_texts]


def stratified_sample(rows: list[dict], n_per_class: int) -> list[dict]:
    import random
    rng = random.Random(SEED)
    fraud = [r for r in rows if r["binary_label"] == POSITIVE]
    legit = [r for r in rows if r["binary_label"] != POSITIVE]
    rng.shuffle(fraud)
    rng.shuffle(legit)
    out = fraud[:n_per_class] + legit[:n_per_class]
    rng.shuffle(out)
    return out


def wilson_interval(k: int, n: int, z: float = 1.96) -> tuple[float | None, float | None]:
    if n == 0:
        return (None, None)
    phat = k / n
    denom = 1 + z * z / n
    center = phat + z * z / (2 * n)
    margin = z * math.sqrt(phat * (1 - phat) / n + z * z / (4 * n * n))
    lo = max(0.0, (center - margin) / denom)
    hi = min(1.0, (center + margin) / denom)
    return (round(lo, 4), round(hi, 4))


def metrics_with_ci(rows: list[dict], pred_key: str, true_key: str = "true") -> dict:
    tp = sum(1 for r in rows if r[true_key] and r[pred_key])
    fn = sum(1 for r in rows if r[true_key] and not r[pred_key])
    tn = sum(1 for r in rows if not r[true_key] and not r[pred_key])
    fp = sum(1 for r in rows if not r[true_key] and r[pred_key])
    n_pos, n_neg = tp + fn, tn + fp
    rec = round(tp / n_pos, 4) if n_pos else None
    spec = round(tn / n_neg, 4) if n_neg else None
    prec_n = tp + fp
    prec = round(tp / prec_n, 4) if prec_n else None
    f1 = round(2 * prec * rec / (prec + rec), 4) if prec and rec else None
    bal_acc = round((rec + spec) / 2, 4) if rec is not None and spec is not None else None
    return {
        "n": len(rows), "n_pos": n_pos, "n_neg": n_neg, "tp": tp, "fn": fn, "tn": tn, "fp": fp,
        "recall": rec, "recall_wilson_95": wilson_interval(tp, n_pos) if n_pos else (None, None),
        "specificity": spec, "specificity_wilson_95": wilson_interval(tn, n_neg) if n_neg else (None, None),
        "challenge_sample_precision": prec,
        "challenge_sample_precision_note": "computed on a 50/50 stratified sample -- NOT real-world PPV",
        "f1": f1, "balanced_accuracy": bal_acc,
        "reportable": n_pos >= MIN_COUNT_FOR_RATE and n_neg >= MIN_COUNT_FOR_RATE,
    }


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    s = sorted(values)
    k = (len(s) - 1) * p
    f, c = math.floor(k), math.ceil(k)
    if f == c:
        return s[int(k)]
    return s[f] + (s[c] - s[f]) * (k - f)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--n-per-class", type=int, default=100)
    ap.add_argument("--out", default=str(ROOT / "artifacts" / "difraud_challenge" / "results.json"))
    args = ap.parse_args()

    if os.getenv("TRUSTSHIELD_SEMANTIC_ANALYST") != "remote":
        raise SystemExit("set TRUSTSHIELD_SEMANTIC_ANALYST=remote (and _AI_BASE_URL/_API_KEY/_MODEL) before running this")

    sys.path.insert(0, str(ROOT))
    from scripts.llm_semantic_pilot import cache_key, load_cache, save_cache  # reuse the existing, already-safe cache
    from services.analysis.ml.classifier import TextClassifier
    from services.chat.semantic_analyst import DEFAULT_MODEL, PROMPT_VERSION, analyze_semantics

    model = os.getenv("TRUSTSHIELD_AI_MODEL", DEFAULT_MODEL)
    cache = load_cache()
    clf = TextClassifier()

    non_overlapping = load_non_overlapping_difraud()
    fraud_avail = sum(1 for r in non_overlapping if r["binary_label"] == POSITIVE)
    legit_avail = len(non_overlapping) - fraud_avail
    print(f"non-overlapping DIFrauD rows: {len(non_overlapping)} (fraud={fraud_avail}, legit={legit_avail})")

    sample = stratified_sample(non_overlapping, args.n_per_class)
    print(f"challenge sample: {len(sample)} ({args.n_per_class}/class target)")

    rows = []
    latencies_ms = []
    for i, r in enumerate(sample):
        key = cache_key(r["text"], model, PROMPT_VERSION)
        if key in cache:
            sem = cache[key]
        else:
            t0 = time.perf_counter()
            result = analyze_semantics(r["text"])
            sem = {"status": result.status, "latency_ms": round((time.perf_counter() - t0) * 1000)}
            if result.assessment:
                sem["assessment"] = result.assessment.assessment
                sem["semantic_confidence"] = result.assessment.semantic_confidence
            cache[key] = sem
            save_cache(cache)
        if "latency_ms" in sem:
            latencies_ms.append(sem["latency_ms"])
        v3_pred = clf.predict(r["text"])
        rows.append({
            "true": r["binary_label"] == POSITIVE,
            "sem_pred": sem.get("assessment") == "fraud_like" and sem.get("semantic_confidence", 0) >= 0.6,
            "sem_status": sem["status"],
            "v3_pred": v3_pred.predicted_binary,
        })
        if (i + 1) % 20 == 0 or i + 1 == len(sample):
            print(f"  [{i+1}/{len(sample)}]")

    status_dist = {s: sum(1 for r in rows if r["sem_status"] == s) for s in {r["sem_status"] for r in rows}}
    report = {
        "protocol": "docs/LLM_SEMANTIC_BENCHMARK_PROTOCOL.md#protocol-v2-addendum", "model": model,
        "source": "difraud_sms_non_overlapping", "source_available_fraud": fraud_avail,
        "source_available_legit": legit_avail, "challenge_size": len(rows),
        "v3": metrics_with_ci(rows, "v3_pred"),
        "semantic_analyst": metrics_with_ci(rows, "sem_pred"),
        "semantic_status_distribution": status_dist,
        "semantic_latency_ms": {
            "median": percentile(latencies_ms, 0.5), "p90": percentile(latencies_ms, 0.9),
            "n_live_calls_this_run": len(latencies_ms),
        },
    }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
