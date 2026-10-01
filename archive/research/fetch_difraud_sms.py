"""Fetch and format the DIFrauD SMS subset (MIT license) into datasets/raw/difraud_sms/all.jsonl.

Mirrors the existing convention: datasets/raw/* is not committed (see .gitignore); this script regenerates it from the
original public source, using huggingface_hub against the real repo `redasers/difraud`. Provenance, license text and the
overlap analysis against TrustShield's existing corpora are recorded in datasets/raw/difraud_sms/SOURCE.md (which IS
committed). Run this before `scripts/v4_dataset_build.py` on a fresh checkout.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

from huggingface_hub import hf_hub_download

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "datasets" / "raw" / "difraud_sms" / "all.jsonl"
REPO = "redasers/difraud"


def main() -> int:
    rows = []
    for split in ("train", "validation", "test"):
        path = hf_hub_download(REPO, f"sms/{split}.jsonl", repo_type="dataset")
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line:
                continue
            r = json.loads(line)
            rows.append({"text": r["text"], "label": r["label"], "difraud_split": split})

    seen: set[str] = set()
    deduped = []
    for r in rows:
        key = r["text"].strip().lower()
        if key in seen:
            continue
        seen.add(key)
        deduped.append(r)
    print(f"raw rows: {len(rows)}, after exact-text dedup: {len(deduped)}")

    out_rows = []
    for r in deduped:
        rid = "difraud-" + hashlib.sha256(r["text"].encode("utf-8")).hexdigest()[:12]
        out_rows.append({
            "id": rid, "text": r["text"],
            "binary_label": "malicious_or_fraudulent" if r["label"] == 1 else "legitimate",
            "category_label": "phishing" if r["label"] == 1 else "other_legitimate",
            "source_name": "difraud_sms", "source_type": "public", "synthetic": False,
            "language": "en", "date_added": "2026-09-27",
            "notes": (f"difraud_own_split={r['difraud_split']};license=MIT;origin=University of Houston DIFrauD benchmark, "
                     "SMS subset (UCI SMS Spam Collection + SMS Phishing Dataset for Machine Learning and Pattern "
                     "Recognition, de-duplicated)"),
        })

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", encoding="utf-8") as f:
        for r in out_rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"wrote {len(out_rows)} rows to {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
