"""Post-run companion for the spent v2 holdout (no tuning, no criteria changes).

Re-scores the holdout through the same production path as
run_v2_promotion_eval.py (deterministic), preserves raw per-row predictions,
recomputes every metric independently, compares with results.json, and writes
slice metrics, error-analysis files and a criterion table.
"""
from __future__ import annotations

import csv
import json
import os
import random
import statistics
import sys
import time
from pathlib import Path

import numpy as np
from sklearn.metrics import average_precision_score, brier_score_loss, roc_auc_score

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
OUT = ROOT / "models/evaluation/v2_final"
HOLDOUT = ROOT / "datasets/holdout/trustshield-text-v2-holdout/holdout.jsonl"
REAL = {"mendeley_financial_scams", "reportsmishing_imc25"}
CAUTION, SEED, B = 25, 42, 2000
MODELS = {"v1": "trustshield_text_v1", "v3": "trustshield_text_v3"}


def load():
    rows = []
    for line in HOLDOUT.read_text(encoding="utf-8").splitlines():
        if line.strip():
            r = json.loads(line)
            notes = r.get("notes", "")
            rows.append({"id": r["id"], "text": r["text"], "y": int(r["binary_label"] != "legitimate"),
                         "source": r["source_name"], "category": r["category_label"], "synthetic": bool(r["synthetic"]),
                         "subtype": notes.split(";")[0] if "hard_negative" in notes else ""})
    return rows


def score(model, rows):
    os.environ["TRUSTSHIELD_ML_MODEL"] = "" if model == "trustshield_text_v1" else model
    os.environ["TRUSTSHIELD_ML"] = "on"
    from services.analysis import pipeline
    from services.analysis.ml.classifier import TextClassifier
    pipeline._text_classifier.cache_clear()
    clf = TextClassifier(model)
    assert clf.available
    out = []
    for r in rows:
        t = time.perf_counter()
        res = pipeline.analyze_text(r["text"])
        ms = (time.perf_counter() - t) * 1000
        out.append({"score": res.risk.score, "flag": int(res.risk.score >= CAUTION), "p": float(clf.predict(r["text"]).p_malicious), "ms": ms})
    return out


def conf(y, f):
    tp = sum(a and b for a, b in zip(y, f)); tn = sum((not a) and (not b) for a, b in zip(y, f))
    fp = sum((not a) and b for a, b in zip(y, f)); fn = sum(a and (not b) for a, b in zip(y, f))
    return tp, tn, fp, fn


def metrics(y, f, p, s):
    tp, tn, fp, fn = conf(y, f)
    n = len(y)
    prec = tp / (tp + fp) if tp + fp else None
    rec = tp / (tp + fn) if tp + fn else None
    spec = tn / (tn + fp) if tn + fp else None
    f1 = 2 * prec * rec / (prec + rec) if prec and rec else (0.0 if prec is not None and rec is not None else None)
    both = len(set(y)) == 2
    return {"n": n, "pos": tp + fn, "neg": tn + fp, "TP": tp, "TN": tn, "FP": fp, "FN": fn, "accuracy": (tp + tn) / n,
            "precision": prec, "recall": rec, "specificity": spec, "F1": f1,
            "FPR": None if spec is None else 1 - spec, "FNR": None if rec is None else 1 - rec,
            "roc_auc_ml_p": float(roc_auc_score(y, p)) if both else None,
            "pr_auc_ml_p": float(average_precision_score(y, p)) if both else None,
            "roc_auc_fused": float(roc_auc_score(y, s)) if both else None,
            "brier_ml_p": float(brier_score_loss(y, p)) if both else None}


def boot(y, f1_, f3_, idx):
    rng = random.Random(SEED)
    res = {k: [] for k in ("v1_recall", "v3_recall", "v1_fpr", "v3_fpr", "v1_f1", "v3_f1", "d_recall", "d_fpr", "d_f1")}
    def st(f, ii):
        tp, tn, fp, fn = conf([y[i] for i in ii], [f[i] for i in ii])
        rec = tp / (tp + fn) if tp + fn else None
        fpr = fp / (fp + tn) if fp + tn else None
        pr = tp / (tp + fp) if tp + fp else 0.0
        f1 = 2 * pr * rec / (pr + rec) if rec is not None and pr + rec else 0.0
        return rec, fpr, f1
    for _ in range(B):
        ii = [rng.choice(idx) for _ in idx]
        a, b = st(f1_, ii), st(f3_, ii)
        for k, (x, z) in zip(("recall", "fpr", "f1"), zip(a, b)):
            if x is None or z is None:
                continue
            res["v1_" + k].append(x); res["v3_" + k].append(z); res["d_" + k].append(z - x)
    ci = lambda v: [float(np.percentile(v, 2.5)), float(np.percentile(v, 97.5))] if v else None  # noqa: E731
    return {k: {"mean": float(np.mean(v)) if v else None, "ci95": ci(v)} for k, v in res.items()}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    rows = load()
    pred = {tag: score(name, rows) for tag, name in MODELS.items()}
    with (OUT / "raw_predictions.csv").open("w", newline="", encoding="utf-8") as h:
        w = csv.writer(h)
        w.writerow(["id", "source", "category", "synthetic", "hard_negative_subtype", "y_true", "v1_score", "v1_flag", "v1_p", "v1_ms", "v3_score", "v3_flag", "v3_p", "v3_ms"])
        for r, a, b in zip(rows, pred["v1"], pred["v3"]):
            w.writerow([r["id"], r["source"], r["category"], r["synthetic"], r["subtype"], r["y"], a["score"], a["flag"], f"{a['p']:.6f}", f"{a['ms']:.3f}", b["score"], b["flag"], f"{b['p']:.6f}", f"{b['ms']:.3f}"])
    y = [r["y"] for r in rows]
    idxs = {"ALL": list(range(len(rows))),
            "REAL": [i for i, r in enumerate(rows) if r["source"] in REAL],
            "SYNTHETIC": [i for i, r in enumerate(rows) if r["source"] not in REAL],
            "REAL_LEGIT": [i for i, r in enumerate(rows) if r["source"] in REAL and not r["y"]],
            "REAL_FRAUD": [i for i, r in enumerate(rows) if r["source"] in REAL and r["y"]]}
    for s in sorted({r["source"] for r in rows}):
        idxs[f"source={s}"] = [i for i, r in enumerate(rows) if r["source"] == s]
    for s in sorted({r["source"] for r in rows}):
        for lab, tag in ((1, "fraud"), (0, "legit")):
            ii = [i for i, r in enumerate(rows) if r["source"] == s and r["y"] == lab]
            if ii:
                idxs[f"source={s}/{tag}"] = ii
    for c in sorted({r["category"] for r in rows if r["y"]}):
        ii = [i for i, r in enumerate(rows) if r["category"] == c]
        idxs[f"fraud_category={c}"] = ii  # includes all sources; support shown via n
    for st_ in sorted({r["subtype"] for r in rows if r["subtype"]}):
        idxs[f"hard_negative_legit={st_}"] = [i for i, r in enumerate(rows) if r["subtype"] == st_]
    per = {}
    for tag in pred:
        per[tag] = {}
        for name, ii in idxs.items():
            per[tag][name] = metrics([y[i] for i in ii], [pred[tag][i]["flag"] for i in ii],
                                     [pred[tag][i]["p"] for i in ii], [pred[tag][i]["score"] for i in ii])
    lat = {t: {"median_ms": statistics.median(p["ms"] for p in pred[t]), "mean_ms": statistics.fmean(p["ms"] for p in pred[t]),
               "p95_ms": float(np.percentile([p["ms"] for p in pred[t]], 95))} for t in pred}
    f1_, f3_ = [p["flag"] for p in pred["v1"]], [p["flag"] for p in pred["v3"]]
    ci = {name: boot(y, f1_, f3_, idxs[name]) for name in ("ALL", "REAL", "REAL_LEGIT", "REAL_FRAUD", "SYNTHETIC")}
    real_legit_boot = boot(y, f1_, f3_, idxs["REAL_LEGIT"])["d_fpr"]

    m1, m3 = per["v1"], per["v3"]
    a1, a3 = m1["ALL"]["roc_auc_ml_p"], m3["ALL"]["roc_auc_ml_p"]
    b1, b3 = m1["ALL"]["brier_ml_p"], m3["ALL"]["brier_ml_p"]
    table = [
        ("C1a recall (ALL) drop", "<= 0.02", f"v1 {m1['ALL']['recall']:.4f} -> v3 {m3['ALL']['recall']:.4f} (drop {m1['ALL']['recall'] - m3['ALL']['recall']:+.4f})", m3["ALL"]["recall"] >= m1["ALL"]["recall"] - 0.02),
        ("C1b recall (REAL_FRAUD) drop", "<= 0.02", f"v1 {m1['REAL_FRAUD']['recall']:.4f} -> v3 {m3['REAL_FRAUD']['recall']:.4f} (drop {m1['REAL_FRAUD']['recall'] - m3['REAL_FRAUD']['recall']:+.4f})", m3["REAL_FRAUD"]["recall"] >= m1["REAL_FRAUD"]["recall"] - 0.02),
        ("C2 REAL_LEGIT FPR not higher and CI upper <= +0.03", "diff <= 0 and upper <= 0.03", f"v1 {m1['REAL_LEGIT']['FPR']:.4f} -> v3 {m3['REAL_LEGIT']['FPR']:.4f}; diff CI {real_legit_boot['ci95']}", m3["REAL_LEGIT"]["FPR"] <= m1["REAL_LEGIT"]["FPR"] and real_legit_boot["ci95"][1] <= 0.03),
        ("C3 overall FPR increase", "<= 0.01", f"v1 {m1['ALL']['FPR']:.4f} -> v3 {m3['ALL']['FPR']:.4f}", m3["ALL"]["FPR"] <= m1["ALL"]["FPR"] + 0.01),
        ("C4 real gain (REAL_LEGIT FPR fall >= 0.02 OR REAL F1 gain >= 0.02)", ">= 0.02", f"REAL_LEGIT FPR fall {m1['REAL_LEGIT']['FPR'] - m3['REAL_LEGIT']['FPR']:+.4f}; REAL F1 gain {m3['REAL']['F1'] - m1['REAL']['F1']:+.4f}",
         (m1["REAL_LEGIT"]["FPR"] - m3["REAL_LEGIT"]["FPR"] >= 0.02) or (m3["REAL"]["F1"] - m1["REAL"]["F1"] >= 0.02)),
        ("C5a ROC-AUC (ML p, ALL) drop", "<= 0.01", f"v1 {a1:.4f} -> v3 {a3:.4f}", a3 >= a1 - 0.01),
        ("C5b Brier (ML p, ALL) increase", "<= 0.01", f"v1 {b1:.4f} -> v3 {b3:.4f}", b3 <= b1 + 0.01),
        ("C6 median latency", "<= 2x v1", f"v1 {lat['v1']['median_ms']:.1f} ms -> v3 {lat['v3']['median_ms']:.1f} ms", lat["v3"]["median_ms"] <= 2 * lat["v1"]["median_ms"]),
        ("size REAL/REAL_LEGIT/REAL_FRAUD n >= 30", ">= 30", f"{m1['REAL']['n']} / {m1['REAL_LEGIT']['n']} / {m1['REAL_FRAUD']['n']}", min(m1["REAL"]["n"], m1["REAL_LEGIT"]["n"], m1["REAL_FRAUD"]["n"]) >= 30),
    ]
    script = json.loads((OUT / "results.json").read_text(encoding="utf-8"))
    diffs = []
    for tag in ("v1", "v3"):
        for name in ("ALL", "REAL", "REAL_LEGIT", "REAL_FRAUD", "SYNTHETIC"):
            for k, kk in (("tp", "TP"), ("tn", "TN"), ("fp", "FP"), ("fn", "FN")):
                if script["metrics"][tag][name][k] != per[tag][name][kk]:
                    diffs.append((tag, name, k, script["metrics"][tag][name][k], per[tag][name][kk]))
    for tag in ("v1", "v3"):
        if abs(script["aux"][tag]["roc_auc"] - per[tag]["ALL"]["roc_auc_ml_p"]) > 1e-9 or abs(script["aux"][tag]["brier"] - per[tag]["ALL"]["brier_ml_p"]) > 1e-9:
            diffs.append((tag, "AUC/Brier", "", script["aux"][tag], per[tag]["ALL"]))
    result = {"n": len(rows), "slice_metrics": per, "latency": lat, "bootstrap_95ci": ci, "criteria": [
        {"criterion": c, "required": r, "observed": o, "pass": bool(p)} for c, r, o, p in table],
        "mechanical_verdict": "PROMOTE" if all(p for *_, p in table) else "DO_NOT_PROMOTE",
        "script_verdict": script["verdict"], "independent_vs_script_confusion_and_auc_mismatches": diffs}
    (OUT / "slice_metrics_and_decision.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    with (OUT / "slice_metrics.csv").open("w", newline="", encoding="utf-8") as h:
        w = csv.writer(h)
        keys = list(next(iter(per["v1"].values())))
        w.writerow(["model", "slice"] + keys)
        for tag in per:
            for name, m in per[tag].items():
                w.writerow([tag, name] + [m[k] for k in keys])
    # error analysis
    text = {r["id"]: r["text"] for r in rows}
    def dump(name, pick):
        with (OUT / name).open("w", newline="", encoding="utf-8") as h:
            w = csv.writer(h)
            w.writerow(["id", "source", "category", "y_true", "v1_score", "v1_p", "v3_score", "v3_p", "text"])
            for i, r in enumerate(rows):
                if pick(i, r):
                    w.writerow([r["id"], r["source"], r["category"], r["y"], pred["v1"][i]["score"], f"{pred['v1'][i]['p']:.3f}", pred["v3"][i]["score"], f"{pred['v3'][i]['p']:.3f}", text[r["id"]]])
    real = lambda r: r["source"] in REAL  # noqa: E731
    dump("errors_v3_real_false_negatives.csv", lambda i, r: real(r) and r["y"] and not pred["v3"][i]["flag"])
    dump("errors_v3_real_legit_false_positives.csv", lambda i, r: real(r) and not r["y"] and pred["v3"][i]["flag"])
    dump("errors_v1_right_v3_wrong.csv", lambda i, r: pred["v1"][i]["flag"] == r["y"] and pred["v3"][i]["flag"] != r["y"])
    dump("errors_v3_right_v1_wrong.csv", lambda i, r: pred["v3"][i]["flag"] == r["y"] and pred["v1"][i]["flag"] != r["y"])
    print(json.dumps({"mismatches_vs_script": diffs, "verdict": result["mechanical_verdict"]}, indent=1))
    for c, r, o, p in table:
        print(("PASS " if p else "FAIL ") + c + " | " + r + " | " + o)


if __name__ == "__main__":
    main()
