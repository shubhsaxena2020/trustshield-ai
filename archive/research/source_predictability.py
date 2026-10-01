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
from sklearn.metrics import accuracy_score, confusion_matrix, f1_score
from sklearn.model_selection import train_test_split

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "datasets" / "processed" / "public"
ALLOWED = {"india_spam_sms.jsonl", "ucI_sms_spam.jsonl"}
DIFRAUD_RAW = ROOT / "datasets" / "raw" / "difraud_sms" / "all.jsonl"
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
    macro_f1 = f1_score(yte, pred, average="macro")
    classes = sorted(set(labels))
    cm = confusion_matrix(yte, pred, labels=classes).tolist()
    return {"feature": feature_name, "n_test": len(yte), "accuracy": round(float(acc), 4),
           "macro_f1": round(float(macro_f1), 4), "classes": classes, "confusion_matrix": cm}


def load_non_overlapping_difraud() -> list[dict]:
    """Same overlap filter as scripts/difraud_challenge.py -- only the genuinely new DIFrauD rows count as a third,
    independent source; the overlapping rows are already counted inside ucI_sms_spam."""
    if not DIFRAUD_RAW.exists():
        return []
    existing = {r["text"].strip().lower() for r in load("india_spam_sms.jsonl") + load("ucI_sms_spam.jsonl")}
    rows = [json.loads(line) for line in DIFRAUD_RAW.read_text(encoding="utf-8").splitlines() if line.strip()]
    return [r for r in rows if r["text"].strip().lower() not in existing]


def _run_experiment(rows: list[dict]) -> dict:
    texts_raw = [r["text"] for r in rows]
    texts_norm = [digits_normalized(t) for t in texts_raw]
    sources = [r["source_name"] for r in rows]
    results = [
        run("raw_word", TfidfVectorizer(ngram_range=(1, 2), min_df=2), texts_raw, sources),
        run("normalized_word", TfidfVectorizer(ngram_range=(1, 2), min_df=2), texts_norm, sources),
        run("char", TfidfVectorizer(analyzer="char_wb", ngram_range=(3, 5), min_df=2), texts_raw, sources),
    ]
    baseline = max(sources.count(s) for s in set(sources)) / len(sources)
    class_counts = {s: sources.count(s) for s in sorted(set(sources))}
    return {"n_rows": len(rows), "n_sources": len(class_counts), "class_counts": class_counts,
           "majority_class_baseline_accuracy": round(baseline, 4), "results": results}


def main() -> int:
    two_source_rows = load("india_spam_sms.jsonl") + load("ucI_sms_spam.jsonl")
    two_source = _run_experiment(two_source_rows)

    difraud_new = load_non_overlapping_difraud()
    three_source = None
    if difraud_new:
        for r in difraud_new:
            r.setdefault("source_name", "difraud_sms_new")
        three_source = _run_experiment(two_source_rows + difraud_new)

    report = {
        "note": "source-name accuracy, NOT fraud accuracy -- high accuracy here means the corpora are stylistically "
                "distinguishable, which is exactly the kind of dataset artifact a fraud classifier could exploit "
                "instead of learning transferable semantics (see the LOSO findings on the v4-source-robustness branch)",
        "two_source": two_source,
        "three_source_with_difraud": three_source,
        "three_source_note": ("null if datasets/raw/difraud_sms/all.jsonl was not present when this ran (gitignored, "
                              "regenerated via scripts/fetch_difraud_sms.py) -- otherwise adds the 1,679 genuinely "
                              "non-overlapping DIFrauD rows as a third, independent source label" if three_source
                              else "datasets/raw/difraud_sms/all.jsonl not found; run scripts/fetch_difraud_sms.py first"),
    }
    out = ROOT / "artifacts" / "source_predictability" / "results.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print("-- two-source (original) --")
    for r in two_source["results"]:
        print(f"{r['feature']:16} accuracy={r['accuracy']} macro_f1={r['macro_f1']} "
             f"(baseline {two_source['majority_class_baseline_accuracy']}) n_test={r['n_test']}")
    if three_source:
        print("-- three-source (with non-overlapping DIFrauD) --")
        for r in three_source["results"]:
            print(f"{r['feature']:16} accuracy={r['accuracy']} macro_f1={r['macro_f1']} "
                 f"(baseline {three_source['majority_class_baseline_accuracy']}) n_test={r['n_test']}")
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
