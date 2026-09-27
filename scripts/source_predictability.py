"""Source-predictability experiment (item 13 of the hybrid-intelligence brief): how strongly is SOURCE (not fraud) encoded
in the non-spent public corpora? A classifier trained to predict source_name instead of binary_label. High source
accuracy indicates dataset-style artifacts that a fraud classifier could be secretly exploiting instead of learning
transferable fraud semantics -- directly relevant to the LOSO source-generalization findings on the v4-source-robustness
branch.

DEVELOPMENT DATA ONLY: datasets/processed/public/{india_spam_sms,ucI_sms_spam}.jsonl. Never the v1 test split or v2 holdout.
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, confusion_matrix
from sklearn.model_selection import train_test_split

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "datasets" / "processed" / "public"
ALLOWED = {"india_spam_sms.jsonl", "ucI_sms_spam.jsonl"}
SEED = 20260927


def load(name: str) -> list[dict]:
    if name not in ALLOWED:
        raise SystemExit(f"REFUSED: reads only {sorted(ALLOWED)}, not {name!r}")
    return [json.loads(line) for line in (DATA / name).read_text(encoding="utf-8").splitlines() if line.strip()]


def digits_normalized(text: str) -> str:
    import re
    return re.sub(r"\d", "#", text.lower())


def run(feature_name: str, vectorizer, texts: list[str], labels: list[str]) -> dict:
    x = vectorizer.fit_transform(texts)
    xtr, xte, ytr, yte = train_test_split(x, labels, test_size=0.25, random_state=SEED, stratify=labels)
    clf = LogisticRegression(max_iter=1000, random_state=SEED)
    clf.fit(xtr, ytr)
    pred = clf.predict(xte)
    acc = accuracy_score(yte, pred)
    classes = sorted(set(labels))
    cm = confusion_matrix(yte, pred, labels=classes).tolist()
    return {"feature": feature_name, "n_test": len(yte), "accuracy": round(float(acc), 4), "classes": classes, "confusion_matrix": cm}


def main() -> int:
    rows = load("india_spam_sms.jsonl") + load("ucI_sms_spam.jsonl")
    texts_raw = [r["text"] for r in rows]
    texts_norm = [digits_normalized(t) for t in texts_raw]
    sources = [r["source_name"] for r in rows]

    results = [
        run("raw_word", TfidfVectorizer(ngram_range=(1, 2), min_df=2), texts_raw, sources),
        run("normalized_word", TfidfVectorizer(ngram_range=(1, 2), min_df=2), texts_norm, sources),
        run("char", TfidfVectorizer(analyzer="char_wb", ngram_range=(3, 5), min_df=2), texts_raw, sources),
    ]
    baseline = max(sources.count(s) for s in set(sources)) / len(sources)  # majority-class baseline
    report = {"n_rows": len(rows), "majority_class_baseline_accuracy": round(baseline, 4), "results": results,
              "note": "source-name accuracy, NOT fraud accuracy -- high accuracy here means the corpora are stylistically "
                      "distinguishable, which is exactly the kind of dataset artifact a fraud classifier could exploit "
                      "instead of learning transferable semantics (see the LOSO findings on the v4-source-robustness branch)"}
    out = ROOT / "artifacts" / "source_predictability" / "results.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2), encoding="utf-8")
    for r in results:
        print(f"{r['feature']:16} accuracy={r['accuracy']} (majority baseline {report['majority_class_baseline_accuracy']}) n_test={r['n_test']}")
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
