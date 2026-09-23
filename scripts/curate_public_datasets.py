#!/usr/bin/env python3
"""Curate retained public SMS sources from supplied per-row audits.

This script intentionally does not infer labels. Every spam row and every row
in the supplied 150-row ham samples is mapped from the corresponding audit
JSONL. Remaining ham rows retain the source ham label and are marked as not
individually audited.
"""
from __future__ import annotations

import csv
import html
import json
import re
import statistics
import sys
from collections import Counter
from hashlib import sha1, sha256
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "datasets" / "raw"
OUT = ROOT / "datasets" / "processed" / "public"
AUDIT = OUT / "audit"
TODAY = "2026-09-24"
ANNOTATORS = {"ucI_sms_spam": "llm-audit:antigravity-agy", "india_spam_sms": "llm-audit:codex-gpt-5.6-luna"}


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows), encoding="utf-8")


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def file_sha256(path: Path) -> str:
    digest = sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def words(text: str) -> int:
    return len(text.split())


def hygiene(text: str) -> tuple[str, list[str]]:
    notes: list[str] = []
    text = html.unescape(text)
    if "ï¿½" in text:
        text = text.replace("ï¿½", "<MISSING_CHAR>")
        notes.append("replacement_char_neutralized")
    if re.search(r"(?:Ãƒ|Ã‚|Ã¢|Ã°Å¸)", text):
        return "", ["unrecoverable_mojibake_dropped"]
    text = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]", " ", text)
    return re.sub(r"\s+", " ", text).strip(), notes


def english(text: str) -> bool:
    if not text or re.search(r"[\u0370-\u052f\u0590-\u08ff\u0900-\u0dff\u3040-\u30ff\u4e00-\u9fff]", text):
        return False
    letters = [char for char in text if char.isalpha()]
    return bool(letters) and sum(char.isascii() for char in letters) / len(letters) >= 0.80


def pii(text: str) -> tuple[str, int]:
    count = 0

    def repl(token: str):
        def inner(_: re.Match[str]) -> str:
            nonlocal count
            count += 1
            return token
        return inner

    text = re.sub(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b", repl("<EMAIL>"), text, flags=re.I)
    text = re.sub(r"(?<!\w)(?:\+?\d{1,3}[ -.])?(?:\(?\d{2,4}\)?[ -.])?\d{3,4}[ -. ]\d{3,4}(?!\w)", repl("<PHONE>"), text)
    return text, count


def record_id(short: str, text: str) -> str:
    return f"{short}-{sha1(text.encode('utf-8')).hexdigest()[:8]}"


def raw_sms(source: str) -> tuple[list[dict[str, Any]], list[tuple[str, str, str]], list[tuple[str, str, str]], int]:
    records: list[dict[str, Any]] = []
    spam: list[tuple[str, str, str]] = []
    ham: list[tuple[str, str, str]] = []
    if source == "ucI_sms_spam":
        path = RAW / "ucI_sms_spam" / "SMSSpamCollection"
        iterator = ((i, *line.rstrip("\r\n").split("\t", 1)) for i, line in enumerate(path.open(encoding="utf-8"), 1))
    else:
        path = RAW / "india_spam_sms" / "spam_ham_india.csv"
        iterator = ((i, row.get("Label", "").strip().lower(), row.get("Msg", "")) for i, row in enumerate(csv.DictReader(path.open(encoding="utf-8", newline="")), 2))
    raw_count = 0
    for row_number, label, raw_text in iterator:
        if label not in {"spam", "ham"}:
            continue
        raw_count += 1
        source_row_id = f"{'uci' if source == 'ucI_sms_spam' else 'india'}-{row_number:06d}"
        text, hygiene_notes = hygiene(raw_text)
        (spam if label == "spam" else ham).append((source_row_id, label, text))
        if not text or not english(text):
            continue
        text, pii_count = pii(text)
        records.append({"source_row_id": source_row_id, "source_label": label, "text": text,
                        "hygiene": hygiene_notes, "pii_count": pii_count})
    return records, spam, ham, raw_count


def _audit_paths(source: str) -> tuple[Path, Path]:
    prefix = "uci" if source == "ucI_sms_spam" else "india"
    return AUDIT / f"{prefix}_spam_llm_audit.jsonl", AUDIT / f"{prefix}_ham150_llm_audit.jsonl"


def _audit_maps(source: str) -> tuple[dict[str, dict], dict[str, dict]]:
    spam_path, ham_path = _audit_paths(source)
    spam = {row["source_row_id"]: row for row in read_jsonl(spam_path)}
    ham = {row["source_row_id"]: row for row in read_jsonl(ham_path)}
    if not spam or not ham:
        raise FileNotFoundError(f"genuine audit files missing for {source}: {spam_path}, {ham_path}")
    return spam, ham


def _make_record(source: str, short: str, item: dict, audit: dict | None, *, unreviewed_ham: bool = False) -> dict:
    if unreviewed_ham:
        binary, category = "legitimate", "legitimate"
        method = "source-label-unreviewed-ham"
        confidence = "not_individually_audited"
        rationale = "Source ham label retained; this row was outside the supplied 150-row audit sample."
    else:
        verdict = audit["verdict"]
        if verdict in {"legit_promo", "legit"}:
            binary, category = "legitimate", "legitimate"
        elif verdict == "fraud":
            binary, category = "malicious_or_fraudulent", audit["category"]
        else:
            raise ValueError(f"unsupported non-excluded audit verdict: {verdict}")
        method = ANNOTATORS[source]
        confidence = audit.get("confidence", "unspecified")
        rationale = audit.get("rationale", "")
    notes = [f"source_row_id={item['source_row_id']}", f"original_label={item['source_label']}",
             f"source_annotation_method={ANNOTATORS[source]}", f"audit_scope={'unreviewed_ham' if unreviewed_ham else 'per_row'}"]
    if audit and audit.get("verdict") == "legit_promo":
        notes.append("public_spam_reclassified_promo")
    if audit and audit.get("verdict") == "fraud" and item["source_label"] == "spam":
        notes.append("public_spam_reclassified_fraud")
    if item.get("hygiene"):
        notes.extend(item["hygiene"])
    if item.get("pii_count"):
        notes.append(f"pii_replacements={item['pii_count']}")
    return {
        "id": record_id(short, item["text"]), "text": item["text"],
        "binary_label": binary, "category_label": category, "source_type": "public_dataset",
        "source_name": source, "synthetic": False, "language": "en", "date_added": TODAY,
        "split": "", "duplicate_group": "", "annotation_method": method,
        "audit_confidence": confidence, "audit_rationale": rationale, "notes": ";".join(notes),
    }


def curate_sms(source: str, short: str) -> tuple[list[dict], dict]:
    records, spam, ham, raw_count = raw_sms(source)
    spam_audit, ham_audit = _audit_maps(source)
    result: list[dict] = []
    dropped = Counter()
    applied = Counter()
    for item in records:
        audit = spam_audit.get(item["source_row_id"]) if item["source_label"] == "spam" else ham_audit.get(item["source_row_id"])
        unreviewed = item["source_label"] == "ham" and audit is None
        if audit and audit["verdict"] == "exclude":
            dropped[f"{item['source_label']}:{audit.get('category', 'unspecified')}"] += 1
            continue
        row = _make_record(source, short, item, audit, unreviewed_ham=unreviewed)
        result.append(row)
        applied[audit["verdict"] if audit else "unreviewed_ham"] += 1
    audited_ham = list(ham_audit.values())
    ham_noise = sum(row.get("verdict") == "fraud" for row in audited_ham)
    counts = {
        "raw": raw_count, "after_language_and_hygiene": len(records), "final": len(result),
        "audit_excluded": sum(dropped.values()), "audit_exclusions_by_reason": dict(sorted(dropped.items())),
        "applied_verdicts": dict(sorted(applied.items())), "audited_spam": len(spam_audit),
        "audited_ham_sample": len(audited_ham), "ham_label_noise": ham_noise,
        "ham_label_noise_rate": ham_noise / len(audited_ham) if audited_ham else 0.0,
        "final_label_counts": dict(Counter(row["binary_label"] for row in result)),
    }
    return result, counts


def stats(records: list[dict]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for label in ("legitimate", "malicious_or_fraudulent"):
        values = sorted(words(row["text"]) for row in records if row["binary_label"] == label)
        result[label] = {"n": len(values), "mean_words": round(statistics.mean(values), 2) if values else 0,
                         "median_words": statistics.median(values) if values else 0,
                         "p95_words": values[max(0, int(0.95 * len(values)) - 1)] if values else 0}
    return result


def write_source_docs(sources: dict[str, dict]) -> None:
    uci = sources["ucI_sms_spam"]; india = sources["india_spam_sms"]
    (RAW / "ucI_sms_spam" / "SOURCE.md").write_text(f"""# Source: UCI SMS Spam Collection

- URL: https://archive.ics.uci.edu/dataset/228/sms+spam+collection
- Licence: CC BY 4.0 as stated by UCI; cite Almeida & Hidalgo (2011), DOI https://doi.org/10.24432/C5CC84.
- Retrieval date: {TODAY}; raw SHA256: `{file_sha256(RAW / 'ucI_sms_spam' / 'SMSSpamCollection')}`.
- Mapping: all spam rows use the supplied per-row Antigravity/agy audit; ham rows use the supplied 150-row audit where present and retain the source ham label otherwise.
- Annotation method: audited rows `llm-audit:antigravity-agy`; remaining ham rows are `source-label-unreviewed-ham` and are not claimed to be individually audited.
- Counts: {json.dumps(uci['counts'], sort_keys=True)}.
- Lengths: {json.dumps(uci['lengths'], sort_keys=True)}.
- Bias/limitation: older UK premium-rate/prize material and casual Singaporean ham; this is a style shortcut risk.
- Correction: an earlier revision used regex rules and fabricated ham notes while labelling them `llm-audit:gpt-5`. Those files and outputs are discarded; this revision uses only the supplied genuine audits.
""", encoding="utf-8")
    (RAW / "india_spam_sms" / "SOURCE.md").write_text(f"""# Source: India Spam SMS Classification

- URL: https://github.com/junioralive/india-spam-sms-classification
- Licence claim: repository README says MIT; rights/provenance for every SMS are not independently verified.
- Retrieval date: {TODAY}; raw SHA256: `{file_sha256(RAW / 'india_spam_sms' / 'spam_ham_india.csv')}`.
- Mapping: all spam rows use the supplied per-row Codex gpt-5.6-luna audit; ham rows use the supplied 150-row audit where present and retain the source ham label otherwise.
- Annotation method: audited rows `llm-audit:codex-gpt-5.6-luna`; remaining ham rows are `source-label-unreviewed-ham` and are not claimed to be individually audited.
- Counts: {json.dumps(india['counts'], sort_keys=True)}.
- Lengths: {json.dumps(india['lengths'], sort_keys=True)}.
- Bias/limitation: Indian telecom, retail, and transactional promotions dominate; spam is not synonymous with fraud.
- Correction: an earlier revision used regex rules and fabricated ham notes while labelling them `llm-audit:gpt-5`. Those files and outputs are discarded; this revision uses only the supplied genuine audits.
""", encoding="utf-8")
    (RAW / "ealvaradob_phishing-dataset" / "SOURCE.md").write_text(f"""# Source: ealvaradob/phishing-dataset

- Fetch capability: retained in `scripts/fetch_public_datasets.py` for reproducibility.
- Decision for dataset v1: excluded entirely from `datasets/processed/public/`.
- Correction: the earlier processed output and its 43.5% sample figure came from an invalid rule-based/sample audit. The bulk labels were never audited per row, so no Hugging Face record is redistributed or used for v1 training/evaluation.
- URL: https://huggingface.co/datasets/ealvaradob/phishing-dataset
- Declared licence: apache-2.0 on the dataset card; component provenance remains a separate caveat.
""", encoding="utf-8")


def write_dataset_sources(sources: dict[str, dict]) -> None:
    rows = []
    for name in ("ucI_sms_spam", "india_spam_sms"):
        data = sources[name]
        c = data["counts"]
        rows.append(f"| {name} | {c['raw']} | {c['after_language_and_hygiene']} | {c['audit_excluded']} | {c['final']} | {data['lengths']['legitimate']['n']} | {data['lengths']['malicious_or_fraudulent']['n']} |")
    (ROOT / "docs" / "DATASET_SOURCES.md").write_text(f"""# Public Dataset Sources for TrustShield dataset v1

Run `python scripts/curate_public_datasets.py` offline from cached raw files and the four supplied genuine audit files. `scripts/fetch_public_datasets.py` still documents/fetches the optional Hugging Face source, but curation intentionally excludes it.

| Source | Raw | After language/hygiene | Audit exclusions | Final v1 | Legitimate | Malicious/fraudulent |
|---|---:|---:|---:|---:|---:|---:|
{chr(10).join(rows)}

## Genuine audit mapping

- UCI spam and the UCI 150-row ham sample: `llm-audit:antigravity-agy`.
- India spam and the India 150-row ham sample: `llm-audit:codex-gpt-5.6-luna`.
- `fraud` -> `malicious_or_fraudulent` plus the audited category.
- `legit_promo` -> `legitimate` with `public_spam_reclassified_promo` in notes.
- `exclude` -> dropped, with counts recorded by the supplied audit category/reason.
- Ham rows in each 150-row sample marked `fraud` or `exclude` are handled by that verdict. Remaining ham rows were **not individually audited** and retain the source ham label.

## Observed ham label-noise rates

These are observed only in the supplied 150-row samples, not estimates for every ham row:

- UCI: {sources['ucI_sms_spam']['counts']['ham_label_noise']}/{sources['ucI_sms_spam']['counts']['audited_ham_sample']} = {100 * sources['ucI_sms_spam']['counts']['ham_label_noise_rate']:.2f}%.
- India: {sources['india_spam_sms']['counts']['ham_label_noise']}/{sources['india_spam_sms']['counts']['audited_ham_sample']} = {100 * sources['india_spam_sms']['counts']['ham_label_noise_rate']:.2f}%.

## Correction

An earlier revision used regex rules for the spam audits, fabricated `reviewed; no obvious fraud signal` ham audits, labelled those rows `llm-audit:gpt-5`, and produced a 43.5% Hugging Face sample figure. That was invalid. The discarded rule-based audit files and Hugging Face processed output are removed; no Hugging Face bulk record is part of v1 because its labels were never individually audited.

## Source records

- UCI: `datasets/raw/ucI_sms_spam/SOURCE.md`
- India: `datasets/raw/india_spam_sms/SOURCE.md`
- Excluded Hugging Face source: `datasets/raw/ealvaradob_phishing-dataset/SOURCE.md`
""", encoding="utf-8")


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    uci, uci_counts = curate_sms("ucI_sms_spam", "ucisms")
    india, india_counts = curate_sms("india_spam_sms", "indiaspam")
    sources = {"ucI_sms_spam": {"counts": uci_counts, "lengths": stats(uci)},
               "india_spam_sms": {"counts": india_counts, "lengths": stats(india)}}
    write_jsonl(OUT / "ucI_sms_spam.jsonl", uci)
    write_jsonl(OUT / "india_spam_sms.jsonl", india)
    stale = [OUT / "ealvaradob_phishing_dataset.jsonl", *AUDIT.glob("*_spam_audit.jsonl"),
             *AUDIT.glob("*_ham_sample_audit.jsonl"), AUDIT / "ealvaradob_phishing_dataset_sample_audit.jsonl"]
    for path in stale:
        if path.exists():
            path.unlink()
    write_source_docs(sources)
    write_dataset_sources(sources)
    for name, data in sources.items():
        print(name, json.dumps({"counts": data["counts"], "lengths": data["lengths"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
