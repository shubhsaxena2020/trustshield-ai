"""v4 research benchmark — DEVELOPMENT DATA ONLY (train.jsonl fit, validation.jsonl report).

Compares candidate text classifiers with a fixed, pre-declared protocol and reports real-source and synthetic-source
performance SEPARATELY. It reads only the two whitelisted files below; every other path is refused. Nothing here selects a
production model: any replacement for v3 needs a new sealed holdout and pre-registered criteria (docs/V4_EVALUATION_PROTOCOL.md).

Protocol constraints (so validation numbers are not tuned into):
  * hyper-parameters are fixed constants below, chosen before running; there is no search and no threshold tuning;
  * the decision rule is the classifier's natural one (SVC margin > 0, calibrated probability >= 0.5);
  * every candidate is fitted on the same train split and scored once on the validation split.

Usage: python scripts/v4_benchmark.py [--out artifacts/v4_dev/results.json] [--mine]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
from sklearn.calibration import CalibratedClassifierCV
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.metrics import roc_auc_score
from sklearn.pipeline import FeatureUnion, Pipeline
from sklearn.svm import LinearSVC

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "datasets" / "processed" / "trustshield-text-v3"
ALLOWED = {"train.jsonl", "validation.jsonl"}
POSITIVE = "malicious_or_fraudulent"
SEED = 20260927


def load(name: str) -> list[dict]:
    if name not in ALLOWED:
        raise SystemExit(f"REFUSED: v4 development reads only {sorted(ALLOWED)}, not {name!r}")
    return [json.loads(line) for line in (DATA / name).read_text(encoding="utf-8").splitlines() if line.strip()]


def word():
    return TfidfVectorizer(ngram_range=(1, 2), min_df=2, sublinear_tf=True, lowercase=True)


def char():
    return TfidfVectorizer(analyzer="char_wb", ngram_range=(2, 5), min_df=2, sublinear_tf=True, lowercase=True)


def svc():
    return LinearSVC(C=1.0, class_weight="balanced", random_state=SEED)


CANDIDATES = {
    "A_word_tfidf_svc": lambda: Pipeline([("f", word()), ("m", svc())]),
    "B_char_tfidf_svc": lambda: Pipeline([("f", char()), ("m", svc())]),
    "C_word_char_svc": lambda: Pipeline([("f", FeatureUnion([("w", word()), ("c", char())])), ("m", svc())]),
    "F_word_char_svc_calibrated": lambda: Pipeline([("f", FeatureUnion([("w", word()), ("c", char())])),
                                                    ("m", CalibratedClassifierCV(svc(), method="sigmoid", cv=5))]),
}
NOT_RUN = {
    "D_minilm_embeddings_classifier": "sentence-transformers / torch are not installed in this environment; planned, see docs/V4_RESEARCH_PLAN.md",
    "E_compact_transformer_finetune": "needs a GPU-class fine-tune and torch/transformers; planned, see docs/V4_RESEARCH_PLAN.md",
}


def metrics(y: np.ndarray, pred: np.ndarray, score: np.ndarray | None) -> dict:
    tp = int(((y == 1) & (pred == 1)).sum()); fn = int(((y == 1) & (pred == 0)).sum())
    tn = int(((y == 0) & (pred == 0)).sum()); fp = int(((y == 0) & (pred == 1)).sum())
    rec = tp / (tp + fn) if tp + fn else None
    spec = tn / (tn + fp) if tn + fp else None
    prec = tp / (tp + fp) if tp + fp else None
    f1 = 2 * prec * rec / (prec + rec) if prec and rec else None
    auc = float(roc_auc_score(y, score)) if score is not None and len(set(y.tolist())) == 2 else None
    r = lambda v: None if v is None else round(v, 4)  # noqa: E731
    return {"n": int(len(y)), "positives": int(y.sum()), "tp": tp, "fn": fn, "tn": tn, "fp": fp,
            "recall": r(rec), "specificity": r(spec), "precision": r(prec), "f1": r(f1), "roc_auc": r(auc)}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(ROOT / "artifacts" / "v4_dev" / "results.json"))
    ap.add_argument("--mine", action="store_true", help="also list validation false positives/negatives (hard-negative mining)")
    args = ap.parse_args()
    train, val = load("train.jsonl"), load("validation.jsonl")
    xt, yt = [r["text"] for r in train], np.array([r["binary_label"] == POSITIVE for r in train], dtype=int)
    xv, yv = [r["text"] for r in val], np.array([r["binary_label"] == POSITIVE for r in val], dtype=int)
    synthetic = np.array([bool(r.get("synthetic")) for r in val])
    report: dict = {"protocol": "fixed hyper-parameters, one fit on train, one score on validation; development data only",
                    "data": {"train": len(train), "validation": len(val), "validation_real": int((~synthetic).sum()),
                             "validation_synthetic": int(synthetic.sum())},
                    "candidates": {}, "not_run": NOT_RUN,
                    "uses_spent_data": False}
    mined: dict = {}
    for name, build in CANDIDATES.items():
        model = build().fit(xt, yt)
        if hasattr(model, "predict_proba"):
            score = model.predict_proba(xv)[:, 1]
            pred = (score >= 0.5).astype(int)
        else:
            score = model.decision_function(xv)
            pred = (score > 0).astype(int)
        report["candidates"][name] = {"all": metrics(yv, pred, score), "real": metrics(yv[~synthetic], pred[~synthetic], score[~synthetic]),
                                      "synthetic": metrics(yv[synthetic], pred[synthetic], score[synthetic])}
        print(f"{name}: all F1={report['candidates'][name]['all']['f1']} real F1={report['candidates'][name]['real']['f1']} "
              f"synthetic F1={report['candidates'][name]['synthetic']['f1']}")
        if args.mine:
            wrong = np.where(pred != yv)[0]
            mined[name] = [{"id": val[i]["id"], "true": int(yv[i]), "score": round(float(score[i]), 4), "synthetic": bool(synthetic[i]),
                            "category": val[i].get("category_label"), "text": val[i]["text"][:200]} for i in wrong]
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2), encoding="utf-8")
    if args.mine:
        (out.parent / "hard_negatives_validation.json").write_text(json.dumps(mined, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
