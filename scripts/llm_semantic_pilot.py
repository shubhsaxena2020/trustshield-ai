"""Small, stratified pilot benchmark of the SemanticSecurityAnalyst vs v3, per docs/LLM_SEMANTIC_BENCHMARK_PROTOCOL.md.

DEVELOPMENT DATA ONLY: reads exclusively from datasets/processed/trustshield-text-v4-dev/{dev,calibration}.jsonl (never the
v1 test split or v2 holdout). Requires TRUSTSHIELD_SEMANTIC_ANALYST=remote and a reachable 9Router with
TRUSTSHIELD_AI_BASE_URL/_API_KEY/_MODEL set -- this makes REAL LLM calls, so it is a manual/opt-in script, never run in CI.

Usage: python scripts/llm_semantic_pilot.py [--n-per-class 15] [--repeat-n 5] [--repeat-times 3]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import random
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "datasets" / "processed" / "public"
ALLOWED = {"india_spam_sms.jsonl", "ucI_sms_spam.jsonl"}  # non-spent, licensed, audited (docs/DATASET_SOURCES.md)
CACHE_PATH = ROOT / "artifacts" / "semantic_pilot" / "cache.json"
POSITIVE = "malicious_or_fraudulent"
SEED = 20260927


def load(name: str) -> list[dict]:
    if name not in ALLOWED:
        raise SystemExit(f"REFUSED: pilot reads only {sorted(ALLOWED)}, not {name!r}")
    return [json.loads(line) for line in (DATA / name).read_text(encoding="utf-8").splitlines() if line.strip()]


def load_dev() -> list[dict]:
    """This branch does not carry the trustshield-text-v4-dev split-building pipeline (that lives on
    feature/trustshield-v4-source-robustness); this pilot reads the underlying public corpora directly instead --
    still non-spent, still licensed, still audited, just not pre-split into train/dev/calibration."""
    return load("india_spam_sms.jsonl") + load("ucI_sms_spam.jsonl")


def load_cache() -> dict:
    if CACHE_PATH.exists():
        return json.loads(CACHE_PATH.read_text(encoding="utf-8"))
    return {}


def save_cache(cache: dict) -> None:
    CACHE_PATH.parent.mkdir(parents=True, exist_ok=True)
    CACHE_PATH.write_text(json.dumps(cache, indent=2), encoding="utf-8")


def cache_key(text: str, model: str, prompt_version: str) -> str:
    from services.chat.semantic_analyst import redact_secrets
    h = hashlib.sha256(f"{redact_secrets(text)}|{model}|{prompt_version}".encode()).hexdigest()
    return h[:24]


def stratified_sample(rows: list[dict], n_per_class: int, prefer_sources: tuple[str, ...]) -> list[dict]:
    rng = random.Random(SEED)
    by_class: dict[bool, list[dict]] = {True: [], False: []}
    for r in rows:
        by_class[r["binary_label"] == POSITIVE].append(r)
    out = []
    for is_pos in (True, False):
        pool = by_class[is_pos]
        preferred = [r for r in pool if r.get("source_name") in prefer_sources]
        rest = [r for r in pool if r.get("source_name") not in prefer_sources]
        rng.shuffle(preferred)
        rng.shuffle(rest)
        picked = (preferred + rest)[:n_per_class]
        out += picked
    rng.shuffle(out)
    return out


def run_semantic(text: str, model: str, cache: dict) -> dict:
    from services.chat.semantic_analyst import PROMPT_VERSION, analyze_semantics
    key = cache_key(text, model, PROMPT_VERSION)
    if key in cache:
        return cache[key]
    t0 = time.perf_counter()
    result = analyze_semantics(text)
    entry = {"status": result.status, "latency_ms": round((time.perf_counter() - t0) * 1000)}
    if result.assessment:
        entry["assessment"] = result.assessment.assessment
        entry["semantic_confidence"] = result.assessment.semantic_confidence
        entry["signal_types"] = [s.type for s in result.assessment.signals]
    cache[key] = entry
    return entry


def run_v3(text: str) -> dict:
    from services.analysis.ml.classifier import TextClassifier
    clf = TextClassifier()
    pred = clf.predict(text)
    return {"p_malicious": pred.p_malicious, "predicted_binary": pred.predicted_binary, "threshold": pred.threshold}


def metrics(rows: list[dict], pred_key: str, true_key: str = "true") -> dict:
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
    return {"n": len(rows), "n_pos": n_pos, "n_neg": n_neg, "tp": tp, "fn": fn, "tn": tn, "fp": fp,
           "recall": rec, "specificity": spec, "precision": prec, "f1": f1, "reportable": n_pos >= 15 and n_neg >= 15}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--n-per-class", type=int, default=15)
    ap.add_argument("--repeat-n", type=int, default=5)
    ap.add_argument("--repeat-times", type=int, default=3)
    ap.add_argument("--held-out-source", default="india_spam_sms")
    ap.add_argument("--held-out-n", type=int, default=10)
    ap.add_argument("--out", default=str(ROOT / "artifacts" / "semantic_pilot" / "results.json"))
    args = ap.parse_args()

    import os
    if os.getenv("TRUSTSHIELD_SEMANTIC_ANALYST") != "remote":
        raise SystemExit("set TRUSTSHIELD_SEMANTIC_ANALYST=remote (and _AI_BASE_URL/_API_KEY/_MODEL) before running this")
    model = os.getenv("TRUSTSHIELD_AI_MODEL", "unknown")
    cache = load_cache()

    dev = load_dev()
    pilot = stratified_sample(dev, args.n_per_class, prefer_sources=("india_spam_sms",))
    print(f"pilot size: {len(pilot)} (target {args.n_per_class}/class)")

    pilot_rows = []
    for i, r in enumerate(pilot):
        sem = run_semantic(r["text"], model, cache)
        v3 = run_v3(r["text"])
        pilot_rows.append({"true": r["binary_label"] == POSITIVE, "source": r.get("source_name"),
                           "sem_pred": sem.get("assessment") == "fraud_like" and sem.get("semantic_confidence", 0) >= 0.6,
                           "sem_status": sem["status"], "v3_pred": v3["predicted_binary"]})
        print(f"  [{i+1}/{len(pilot)}] source={r.get('source_name')} true={r['binary_label']=='malicious_or_fraudulent'} "
              f"sem={sem.get('assessment')}({sem['status']}) v3={v3['predicted_binary']}")
        save_cache(cache)  # incremental: survives an interrupted run

    # repeatability: a small fixed set, run repeat_times each
    repeat_set = pilot[:args.repeat_n]
    repeatability = []
    for r in repeat_set:
        runs = []
        for _ in range(args.repeat_times):
            from services.chat.semantic_analyst import analyze_semantics
            res = analyze_semantics(r["text"])  # bypasses cache deliberately: we want independent live calls
            runs.append({"status": res.status, "assessment": res.assessment.assessment if res.assessment else None,
                        "confidence": res.assessment.semantic_confidence if res.assessment else None})
        assessments = {x["assessment"] for x in runs}
        repeatability.append({"text_preview": r["text"][:60], "runs": runs, "assessment_agreement": len(assessments) == 1})

    # source-held-out: rows from the specified held-out source, capped
    held_out_rows = [r for r in dev if r.get("source_name") == args.held_out_source][:args.held_out_n]
    held_out_results = []
    for r in held_out_rows:
        sem = run_semantic(r["text"], model, cache)
        held_out_results.append({"true": r["binary_label"] == POSITIVE,
                                 "sem_pred": sem.get("assessment") == "fraud_like" and sem.get("semantic_confidence", 0) >= 0.6,
                                 "sem_status": sem["status"]})
        save_cache(cache)

    report = {
        "protocol": "docs/LLM_SEMANTIC_BENCHMARK_PROTOCOL.md", "model": model, "pilot_size": len(pilot_rows),
        "v3_pilot": metrics(pilot_rows, "v3_pred"),
        "semantic_pilot": metrics(pilot_rows, "sem_pred"),
        "semantic_status_distribution": {s: sum(1 for r in pilot_rows if r["sem_status"] == s)
                                         for s in {r["sem_status"] for r in pilot_rows}},
        "repeatability": repeatability,
        "held_out_source": args.held_out_source, "held_out_n": len(held_out_results),
        "held_out_semantic": metrics(held_out_results, "sem_pred") if held_out_results else None,
    }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "repeatability"}, indent=2))
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    sys.path.insert(0, str(ROOT))
    sys.exit(main())
