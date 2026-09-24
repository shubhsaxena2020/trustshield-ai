"""Build and seal the TrustShield v2 final holdout without model evaluation."""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
import statistics
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer

from scripts.curate_public_datasets import hygiene, pii
from scripts.prepare_dataset import (
    SEED,
    VALID_CATEGORIES,
    normalize_for_dedup,
    template_signature,
    validate_record,
    _near_groups,
)

DATASET_VERSION = "trustshield-text-v2-holdout"
BUILDER_VERSION = "holdout-builder-2026-09-24-v2"
NEAR_DUPLICATE_THRESHOLD = 0.60
GROUP_THRESHOLD = 0.80
PREVIOUS_HOLDOUT_SHA256 = "394ce5e18afcdb14fba8e3a7172e1d3308aeea5c75d047dec460385efbaeb5d4"
SUPERSESSION_REASON = (
    "previous seal used templated (gamed) audits and trusted source labels of reported-smishing rows; "
    "it was never accessed (access log empty)"
)
AUDIT_ANNOTATION_METHOD = "llm-audit-blind:antigravity-agy"
AUDIT_DISTINCT_THRESHOLD = 0.90
AUDIT_CONTENT_THRESHOLD = 0.75
AUDIT_SENTENCE_REUSE_LIMIT = 3
HARD_NEGATIVE_RE = re.compile(r"\b(?:otp|bank|banking|verify|verification|urgent|payment|debit|credit|account|security|transaction|refund)\b", re.I)
AUDIT_TRAILING_ID_RE = re.compile(r"\s*\([^()]*\)\s*[.!?]?\s*$")
AUDIT_WORD_RE = re.compile(r"[a-z]{4,}")
AUDIT_STOP_WORDS = {
    "about", "after", "also", "been", "being", "from", "have", "into", "that", "their",
    "there", "these", "they", "this", "with", "your", "message", "text", "row", "shows",
    "contains", "indicates", "suggests", "appears", "likely", "signals", "because", "asks",
}


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_jsonl(path: Path) -> list[dict]:
    rows = []
    with path.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise ValueError(f"invalid JSON in {path}:{line_number}: {exc.msg}") from exc
    return rows


def _write_jsonl(path: Path, rows: Iterable[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows), encoding="utf-8")


def _counts(rows: list[dict]) -> dict[str, dict[str, int]]:
    dimensions = ("binary_label", "category_label", "source_name", "synthetic")
    return {dimension: dict(sorted(Counter(str(row[dimension]) for row in rows).items())) for dimension in dimensions}


def _source_stage(rows: list[dict]) -> dict[str, dict[str, int]]:
    result: dict[str, dict[str, int]] = defaultdict(lambda: Counter())
    for row in rows:
        result[row["source_name"]]["accepted_schema"] += 1
    return {source: dict(sorted(counts.items())) for source, counts in sorted(result.items())}


def _prepare_candidate(raw: dict, line_number: int, input_path: Path) -> tuple[dict | None, list[str]]:
    if not isinstance(raw, dict) or not isinstance(raw.get("text"), str):
        return None, [f"line {line_number}: text must be a string"]
    text = html.unescape(raw["text"])
    clean, hygiene_notes = hygiene(text)
    if not clean:
        return None, hygiene_notes or [f"line {line_number}: empty after hygiene"]
    clean, pii_count = pii(clean)
    item = dict(raw)
    item["text"] = clean
    notes = [str(item.get("notes", "")), f"candidate_input={input_path.name}"]
    notes.extend(hygiene_notes)
    if pii_count:
        notes.append(f"pii_replacements={pii_count}")
    item["notes"] = ";".join(value for value in notes if value)
    row, reasons = validate_record(item, line_number)
    if row is not None:
        row["_input_file"] = str(input_path.resolve())
    return row, reasons


def _load_candidates(input_files: list[Path]) -> tuple[list[dict], list[dict]]:
    accepted: list[dict] = []
    rejected: list[dict] = []
    for path in sorted({p.resolve() for p in input_files}):
        for line_number, raw in enumerate(read_jsonl(path), 1):
            row, reasons = _prepare_candidate(raw, line_number, path)
            if row is None:
                rejected.append({"file": str(path), "line": line_number, "reasons": reasons})
            else:
                accepted.append(row)
    return accepted, rejected


def _v1_rows(v1_dir: Path) -> tuple[list[dict], list[dict], list[dict]]:
    result = []
    for split in ("train", "validation", "test"):
        path = v1_dir / f"{split}.jsonl"
        result.append(read_jsonl(path) if path.exists() else [])
    return tuple(result)  # type: ignore[return-value]


def _max_similarities(candidates: list[dict], v1_train: list[dict], v1_all: list[dict]) -> tuple[np.ndarray, np.ndarray]:
    if not candidates or not v1_train:
        return np.zeros(len(candidates)), np.zeros(len(candidates))
    vectorizer = TfidfVectorizer(analyzer="char", ngram_range=(3, 5), sublinear_tf=True)
    vectorizer.fit([row["text"] for row in v1_train])
    hold_matrix = vectorizer.transform([row["text"] for row in candidates])
    train_matrix = vectorizer.transform([row["text"] for row in v1_train])
    all_matrix = vectorizer.transform([row["text"] for row in v1_all])
    train_sim = (hold_matrix @ train_matrix.T).toarray().max(axis=1) if train_matrix.shape[0] else np.zeros(len(candidates))
    all_sim = (hold_matrix @ all_matrix.T).toarray().max(axis=1) if all_matrix.shape[0] else np.zeros(len(candidates))
    return train_sim, all_sim


def _quantiles(values: np.ndarray) -> dict[str, float]:
    if len(values) == 0:
        return {key: 0.0 for key in ("min", "p25", "median", "p75", "p95", "max")}
    return {key: round(float(value), 6) for key, value in zip(
        ("min", "p25", "median", "p75", "p95", "max"),
        np.quantile(values, (0, .25, .5, .75, .95, 1)),
    )}


def _length_stats(rows: list[dict]) -> dict[str, dict[str, dict[str, float | int]]]:
    result: dict[str, dict[str, dict[str, float | int]]] = defaultdict(dict)
    for source in sorted({row["source_name"] for row in rows}):
        for label in ("legitimate", "malicious_or_fraudulent"):
            values = sorted(len(row["text"].split()) for row in rows if row["source_name"] == source and row["binary_label"] == label)
            result[source][label] = {
                "n": len(values),
                "mean_words": round(statistics.mean(values), 2) if values else 0,
                "median_words": statistics.median(values) if values else 0,
                "p95_words": values[max(0, int(.95 * len(values)) - 1)] if values else 0,
            }
    return {source: dict(labels) for source, labels in sorted(result.items())}


def _composition(rows: list[dict]) -> dict:
    binary = Counter(row["binary_label"] for row in rows)
    hard_negative = sum(
        "hard_negative" in row.get("notes", "") or bool(HARD_NEGATIVE_RE.search(row["text"]))
        for row in rows if row["binary_label"] == "legitimate"
    )
    source_label_counts = {
        source: dict(sorted(Counter(row["binary_label"] for row in rows if row["source_name"] == source).items()))
        for source in sorted({row["source_name"] for row in rows})
    }
    legitimate_by_source = Counter(row["source_name"] for row in rows if row["binary_label"] == "legitimate")
    largest_legitimate_source, largest_legitimate_count = (legitimate_by_source.most_common(1)[0] if legitimate_by_source else ("", 0))
    diagnostics = _composition_diagnostics(rows, source_label_counts)
    matrix_counts = Counter(
        (row["binary_label"], row["category_label"], row["source_name"], bool(row["synthetic"]))
        for row in rows
    )
    final_composition = [
        {
            "binary_label": binary_label,
            "category_label": category_label,
            "source_name": source_name,
            "synthetic": synthetic,
            "count": count,
        }
        for (binary_label, category_label, source_name, synthetic), count in sorted(
            matrix_counts.items(), key=lambda item: (item[0][0], item[0][1], item[0][2], item[0][3])
        )
    ]
    return {
        "counts": _counts(rows),
        "final_composition": final_composition,
        "total": len(rows),
        "prevalence_malicious": round(binary["malicious_or_fraudulent"] / len(rows), 6) if rows else 0.0,
        "hard_negative_legitimate_count": hard_negative,
        "hard_negative_legitimate_share": round(hard_negative / binary["legitimate"], 6) if binary["legitimate"] else 0.0,
        "source_label_counts": source_label_counts,
        "largest_legitimate_source": largest_legitimate_source,
        "largest_legitimate_source_count": largest_legitimate_count,
        "largest_legitimate_source_share": round(largest_legitimate_count / binary["legitimate"], 6) if binary["legitimate"] else 0.0,
        "lengths_by_source_and_binary": _length_stats(rows),
        "diagnostics": diagnostics,
    }


def _rationale_without_prefix_and_id(rationale: str) -> str:
    value = AUDIT_TRAILING_ID_RE.sub("", rationale.strip())
    return " ".join(value.split()[3:])


def _normalise_audit_sentence(sentence: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", sentence.lower()).strip()


def _content_words(value: str) -> set[str]:
    return {word for word in AUDIT_WORD_RE.findall(value.lower()) if word not in AUDIT_STOP_WORDS}


def audit_quality_check(audit_rows: list[dict], message_by_id: dict[str, str] | None = None) -> dict[str, int | float]:
    """Validate audit quality using content-aware checks that prefixes cannot game."""
    rationales = [str(row.get("rationale", row.get("audit_rationale", ""))).strip() for row in audit_rows]
    if any(not rationale for rationale in rationales):
        raise ValueError("audit contains an empty rationale")
    normalized_rationales = [_normalise_audit_sentence(_rationale_without_prefix_and_id(rationale)) for rationale in rationales]
    distinct = len(set(normalized_rationales))
    distinct_share = distinct / len(audit_rows) if audit_rows else 0.0

    content_available = 0
    content_overlap = 0
    for row, rationale in zip(audit_rows, rationales):
        row_id = row.get("source_row_id")
        message = message_by_id.get(str(row_id)) if message_by_id is not None and row_id is not None else None
        if message is None:
            continue
        content_available += 1
        if _content_words(rationale) & _content_words(message):
            content_overlap += 1
    content_share = content_overlap / content_available if content_available else 0.0

    sentence_counts: Counter[str] = Counter()
    for rationale in rationales:
        for sentence in re.split(r"[.!?]+", AUDIT_TRAILING_ID_RE.sub("", rationale)):
            normalized_sentence = _normalise_audit_sentence(sentence)
            if normalized_sentence:
                sentence_counts[normalized_sentence] += 1
    max_sentence_reuse = max(sentence_counts.values(), default=0)
    errors = []
    if audit_rows and distinct_share < AUDIT_DISTINCT_THRESHOLD:
        errors.append(f"distinct rationale coverage {distinct}/{len(audit_rows)} after normalization")
    if content_available and content_share < AUDIT_CONTENT_THRESHOLD:
        errors.append(f"content-word overlap {content_overlap}/{content_available}")
    if max_sentence_reuse > AUDIT_SENTENCE_REUSE_LIMIT:
        errors.append(f"sentence reuse {max_sentence_reuse} exceeds {AUDIT_SENTENCE_REUSE_LIMIT}")
    if errors:
        raise ValueError("audit quality check failed: " + "; ".join(errors))
    return {
        "rows": len(audit_rows),
        "distinct_rationales_after_first3_parenthetical": distinct,
        "distinct_rationale_share_after_first3_parenthetical": round(distinct_share, 6),
        "content_word_overlap_count": content_overlap,
        "content_word_overlap_available": content_available,
        "content_word_overlap_share": round(content_share, 6),
        "max_sentence_reuse": max_sentence_reuse,
        "sentence_reuse_over_3": sum(count > AUDIT_SENTENCE_REUSE_LIMIT for count in sentence_counts.values()),
    }


def _active_audit_files(output: Path) -> list[Path]:
    legacy = sorted((output / "audit").glob("*.jsonl"))
    if legacy:
        names = ", ".join(path.name for path in legacy)
        raise ValueError(f"retired audit files remain in audit/: {names}")
    return sorted(path for path in (output / "audit_blind").glob("*.jsonl") if path.name != "blind_input.jsonl")


def _load_audits(output: Path, candidates: list[dict]) -> tuple[dict[str, dict], dict[str, dict[str, int | float]], dict[str, str]]:
    message_by_id = {str(row["id"]): row["text"] for row in candidates}
    active_files = _active_audit_files(output)
    real_ids = {str(row["id"]) for row in candidates if not row["synthetic"]}
    if real_ids and not active_files:
        raise ValueError("real-source candidates require an active audit in audit_blind/")
    audits: dict[str, dict] = {}
    summary: dict[str, dict[str, int | float]] = {}
    for path in active_files:
        rows = read_jsonl(path)
        quality = audit_quality_check(rows, message_by_id)
        summary[path.name] = quality
        for row in rows:
            source_row_id = str(row.get("source_row_id", ""))
            if not source_row_id:
                raise ValueError(f"audit file {path.name} has a row without source_row_id")
            if source_row_id in audits:
                raise ValueError(f"audit row {source_row_id} appears more than once")
            audits[source_row_id] = row
    missing = sorted(real_ids - audits.keys())
    extra = sorted(set(audits) - set(message_by_id))
    if missing:
        raise ValueError(f"active blind audit is missing candidate rows: {missing[:5]}")
    if extra:
        raise ValueError(f"active blind audit has unknown source_row_id values: {extra[:5]}")
    return audits, summary, message_by_id


def _apply_audits(candidates: list[dict], audits: dict[str, dict], source_counts: dict[str, Counter]) -> tuple[list[dict], list[str], dict[str, list[str]]]:
    kept: list[dict] = []
    disagreements: list[str] = []
    excluded: dict[str, list[str]] = defaultdict(list)
    for row in candidates:
        source_counts[row["source_name"]]["accepted_schema"] += 1
        if row["synthetic"]:
            source_counts[row["source_name"]]["audit_not_applicable"] += 1
            kept.append(row)
            continue
        audit = audits[str(row["id"])]
        verdict = str(audit.get("verdict", "")).lower()
        if verdict == "exclude":
            reason = str(audit.get("category") or "unspecified")
            excluded[reason].append(str(row["id"]))
            source_counts[row["source_name"]]["removed_audit_exclude"] += 1
            continue
        expected = "fraud" if row["binary_label"] == "malicious_or_fraudulent" else "legit"
        if verdict != expected:
            disagreements.append(str(row["id"]))
            source_counts[row["source_name"]]["removed_source_label_disagreement"] += 1
            continue
        updated = dict(row)
        updated["category_label"] = "legitimate" if verdict == "legit" else str(audit.get("category", ""))
        if updated["category_label"] not in VALID_CATEGORIES:
            raise ValueError(f"audit category is invalid for {row['id']}: {updated['category_label']}")
        updated["annotation_method"] = AUDIT_ANNOTATION_METHOD
        updated["audit_confidence"] = str(audit.get("confidence", ""))
        updated["audit_rationale"] = str(audit.get("rationale", audit.get("audit_rationale", ""))).strip()
        note_parts = [part for part in str(updated.get("notes", "")).split(";") if not part.startswith("audit_rationale=")]
        note_parts.append(f"blind_rationale_id={row['id']}")
        updated["notes"] = ";".join(part for part in note_parts if part)
        source_counts[row["source_name"]]["audit_kept"] += 1
        kept.append(updated)
    return kept, sorted(disagreements), {reason: sorted(ids) for reason, ids in sorted(excluded.items())}


def _within_holdout_dedupe(rows: list[dict], source_counts: dict[str, Counter]) -> tuple[list[dict], dict]:
    kept: list[dict] = []
    removed_ids: dict[str, list[str]] = {"exact_duplicate": [], "normalized_duplicate": []}
    seen_exact: set[str] = set()
    for row in rows:
        if row["text"] in seen_exact:
            removed_ids["exact_duplicate"].append(str(row["id"]))
            source_counts[row["source_name"]]["removed_within_holdout_exact_duplicate"] += 1
            continue
        seen_exact.add(row["text"])
        kept.append(row)
    normalized_kept: list[dict] = []
    seen_normalized: set[str] = set()
    for row in kept:
        normalized = normalize_for_dedup(row["text"])
        if normalized in seen_normalized:
            removed_ids["normalized_duplicate"].append(str(row["id"]))
            source_counts[row["source_name"]]["removed_within_holdout_normalized_duplicate"] += 1
            continue
        seen_normalized.add(normalized)
        normalized_kept.append(row)
    return normalized_kept, {
        "input_rows": len(rows),
        "output_rows": len(normalized_kept),
        "removal_counts": {reason: len(ids) for reason, ids in removed_ids.items() if ids},
        "removed_ids": {reason: ids for reason, ids in removed_ids.items() if ids},
    }


def _rejected_audit_records(output: Path) -> list[dict[str, str]]:
    records = []
    for path in sorted((output / "audit_rejected_templated").glob("*.jsonl")):
        records.append({
            "path": str(path.relative_to(output)).replace("\\", "/"),
            "sha256": file_sha256(path),
            "reason": "rejected templated audit: rationale uniqueness was gamed by first/last message words plus stock category text; not used",
        })
    return records


def _composition_diagnostics(rows: list[dict], source_label_counts: dict[str, dict[str, int]]) -> dict[str, float | int]:
    if not rows:
        return {"source_only_majority_accuracy": 0.0, "length_only_majority_accuracy": 0.0, "length_split_words": 0}
    global_majority = Counter(row["binary_label"] for row in rows).most_common(1)[0][0]
    source_predictions = {
        source: Counter(labels).most_common(1)[0][0]
        for source, labels in (
            (source, [row["binary_label"] for row in rows if row["source_name"] == source])
            for source in source_label_counts
        )
    }
    source_accuracy = sum(source_predictions[row["source_name"]] == row["binary_label"] for row in rows) / len(rows)
    split_words = int(statistics.median(len(row["text"].split()) for row in rows))
    buckets: dict[str, list[dict]] = {"short": [], "long": []}
    for row in rows:
        buckets["short" if len(row["text"].split()) <= split_words else "long"].append(row)
    length_predictions = {
        bucket: Counter(row["binary_label"] for row in bucket_rows).most_common(1)[0][0] if bucket_rows else global_majority
        for bucket, bucket_rows in buckets.items()
    }
    length_accuracy = sum(length_predictions["short" if len(row["text"].split()) <= split_words else "long"] == row["binary_label"] for row in rows) / len(rows)
    return {
        "source_only_majority_accuracy": round(source_accuracy, 6),
        "length_only_majority_accuracy": round(length_accuracy, 6),
        "length_split_words": split_words,
    }


def _default_source_metadata() -> dict:
    root = Path(__file__).resolve().parents[1]
    mendeley_csv = next((root / "datasets/raw/holdout_v2/mendeley_financial_scams/extracted").rglob("*.csv"), None)
    reports_csv = root / "datasets/raw/holdout_v2/reportsmishing/final_dataset_output.csv"
    reports_license = root / "datasets/raw/holdout_v2/reportsmishing/LICENSE.txt"
    return {
        "mendeley_financial_scams": {
            "kind": "real_public_dataset",
            "license": "CC BY 4.0",
            "license_text": "Creative Commons Attribution 4.0 International; the Mendeley record states CC BY 4.0.",
            "license_url": "https://creativecommons.org/licenses/by/4.0/",
            "source_url": "https://data.mendeley.com/datasets/znsk27yk3h/1",
            "citation": "Al Rafi Ahmed and Gazi Faizul Islam, Financial scams detection dataset, Mendeley Data, 2025, DOI: 10.17632/znsk27yk3h.1.",
            "retrieval_date": "2026-09-24",
            "raw_sha256": file_sha256(mendeley_csv) if mendeley_csv else "",
            "raw_row_counts": {"total": 523, "scam": 310, "ham": 213},
            "audit_row_counts": {"blind_audit_rows": 130, "source_label_agree_rows": 130},
            "candidate_stage_counts": {"schema_input": 130, "english_candidate_rows": 130},
            "mapping": "message -> text; scam -> audited fraud category; ham -> audited legitimate or observed label-noise verdict; English-only schema rows retained.",
            "known_biases": "Small consent-collected Bangladesh-focused sample; English/Bangla mix; templated financial and everyday messages; no sender metadata retained.",
        },
        "reportsmishing_imc25": {
            "kind": "real_public_dataset",
            "license": "CC BY 4.0",
            "license_text": "Creative Commons Attribution 4.0 International; repository LICENSE.txt is the full licence text.",
            "license_url": "https://creativecommons.org/licenses/by/4.0/",
            "source_url": "https://github.com/reportsmishing/Smishing-Dataset-IMC25",
            "citation": "Sharad Agarwal, Antonis Papasavva, Guillermo Suarez-Tangil, and Marie Vasek, Fishing for Smishing, ACM IMC 2025, DOI: 10.1145/3730567.3764431.",
            "retrieval_date": "2026-09-24",
            "raw_sha256": file_sha256(reports_csv),
            "license_file_sha256": file_sha256(reports_license),
            "raw_row_counts": {"total": 33869, "english": 22078},
            "audit_row_counts": {"blind_audit_rows": 298, "source_label_agree_fraud": 234, "source_label_disagreement": 54, "excluded_verdicts": 10},
            "candidate_stage_counts": {"schema_input": 298, "english_candidate_rows": 298},
            "mapping": "English translation/text -> text; binary label retained only where the blind verdict agrees with the reported-smishing source label; sampled rows selected with seed 42.",
            "known_biases": "Public-user-report selection bias; mostly malicious reports and no benign class; multilingual corpus reduced to English; placeholder normalization removes some context.",
        },
        "holdout2-codex-v1": {
            "kind": "independently authored synthetic controls and fraud hard negatives",
            "license": "project-authored synthetic data",
            "raw_sha256": file_sha256(root / "datasets/raw/holdout_v2/codex_holdout2.jsonl") if (root / "datasets/raw/holdout_v2/codex_holdout2.jsonl").exists() else "",
            "raw_row_counts": {"total": 630, "fraud": 300, "legitimate": 330},
            "mapping": "Each record was authored independently for this holdout; fraud has 30 rows per category and legitimate controls are hard-negative focused.",
            "known_biases": "Synthetic language is not a prevalence estimate; regional/channel tags are construction metadata, not observed sender metadata.",
        },
    }


def _write_collection_docs(
    output: Path,
    sources: dict,
    composition: dict,
    previous_manifest_sha256: str | None = None,
    source_stage_counts: dict | None = None,
    audit_summary: dict | None = None,
    excluded_source_label_disagreement: list[str] | None = None,
    excluded_audit_verdict: dict | None = None,
    within_holdout_deduplication: dict | None = None,
) -> None:
    counts = composition["counts"]
    source_label_lines = ["| Source | Legitimate | Malicious/fraudulent | Total |", "|---|---:|---:|---:|"]
    for source, labels in composition["source_label_counts"].items():
        source_label_lines.append(f"| `{source}` | {labels.get('legitimate', 0)} | {labels.get('malicious_or_fraudulent', 0)} | {sum(labels.values())} |")
    length_lines = ["| Source | Label | N | Mean words | Median words | P95 words |", "|---|---|---:|---:|---:|---:|"]
    for source, labels in composition["lengths_by_source_and_binary"].items():
        for label, stats in labels.items():
            length_lines.append(f"| `{source}` | {label} | {stats['n']} | {stats['mean_words']} | {stats['median_words']} | {stats['p95_words']} |")
    composition_lines = ["| Binary label | Category | Source | Synthetic | Count |", "|---|---|---|---:|---:|"]
    for item in composition["final_composition"]:
        composition_lines.append(f"| {item['binary_label']} | {item['category_label']} | `{item['source_name']}` | {str(item['synthetic']).lower()} | {item['count']} |")
    (output / "collection_plan.md").write_text(
        "# TrustShield v2 holdout collection plan\n\n"
        "This directory is a sealed final holdout. English real-source rows were individually audited; the independently authored synthetic input is included as a separate source. No model, fusion, or metric evaluation was run during construction.\n\n"
        "## Included\n\n"
        + "\n".join(f"- `{name}`: {json.dumps(value, sort_keys=True)}" for name, value in sorted(sources.items()))
        + "\n\n## Composition and confound diagnostics\n\n"
        + f"Total rows: {composition['total']}; legitimate: {counts['binary_label'].get('legitimate', 0)}; malicious/fraudulent: {counts['binary_label'].get('malicious_or_fraudulent', 0)}. The largest legitimate source is `{composition['largest_legitimate_source']}` with {composition['largest_legitimate_source_count']} rows ({composition['largest_legitimate_source_share']:.2%} of legitimate rows).\n\n"
        + "### Source by label\n\n"
        + "\n".join(source_label_lines)
        + "\n\n### Final composition by label, category, source, and synthetic flag\n\n"
        + "\n".join(composition_lines)
        + "\n\n### Length distributions\n\n"
        + "\n".join(length_lines)
        + "\n\nA source-only majority baseline has accuracy "
        + f"{composition['diagnostics']['source_only_majority_accuracy']:.2%}; a length-bucket-only majority baseline has accuracy "
        + f"{composition['diagnostics']['length_only_majority_accuracy']:.2%} (split at {composition['diagnostics']['length_split_words']} words). These are construction diagnostics, not TrustShield results, and show how much label information is available from source or length alone.\n\n"
        + "The limitation is material: source and message style remain partially confounded. Any later evaluation MUST report per-source slices, at minimum synthetic fraud by category, synthetic legitimate hard-negative subtype, Mendeley legitimate, Mendeley fraud, Report Smishing fraud, and source-by-label length bands; overall scores must not be presented without those slices.\n\n"
        + "## Blind audit and exclusions\n\n"
        + "Real-source rows use the single blind audit `audit_blind/blind_audit_agy.jsonl` with source labels hidden and rows shuffled. A row is retained only when its `fraud`/`legit` verdict agrees with the source binary label; `exclude` verdicts are dropped. Synthetic rows remain author-labelled by construction.\n\n"
        + f"Audit quality summary: {json.dumps(audit_summary or {}, sort_keys=True)}. Source-stage counts: {json.dumps(source_stage_counts or {}, sort_keys=True)}. Source-label disagreement ids are recorded in the manifest ({len(excluded_source_label_disagreement or [])} rows); exclude-verdict counts by audit reason are recorded there ({json.dumps({reason: len(ids) for reason, ids in (excluded_audit_verdict or {}).items()}, sort_keys=True)}).\n\n"
        + f"Within-holdout exact and normalized deduplication was applied after v1 isolation, keeping the first deterministic candidate occurrence: {json.dumps(within_holdout_deduplication or {}, sort_keys=True)}.\n\n"
        + "Real fraud categories are indicative only: they come from one blind LLM annotator. The real binary label is the intersection of source label and agreeing blind verdict, not an independently verified ground truth.\n\n"
        + "\n\n## Not obtained or rejected\n\n"
        "- No additional real Indian/UPI/KYC corpus with a clearly redistributable licence was retained after the search.\n"
        "- Mendeley SMS Phishing Dataset (`f45bkkt8pr/1`) was rejected because its archive returned HTTP 403 and the terms could not be independently checked.\n"
        "- `notd5a/malicious-benign-sms-mms-dataset` was rejected because its CC BY-NC 4.0 card describes a derivative with 68,569 AI-generated rows.\n"
        "- `redasers/difraud` and similar mirrors were rejected because they reuse v1 UCI/SMS phishing corpora; `ealvaradob/phishing-dataset` remains excluded because bulk labels were not audited per row.\n"
        "- `safealert-private` was rejected because access requires registration/request and it is not redistributable without approval.\n"
        "- `mdsajjadullah/bangla-phishing-detection-2026`, `DimensionV/KR-MOB-SMISHING-v2`, and `electricsheepafrica/africa-smishing*` were rejected as synthetic or non-real/unclear-source data; Bangla/Korean are also out of scope.\n"
        "- `vnovaai/INDIA_FRAUD_DETECTION_JSONL_V1` was rejected as a tiny scenario-style corpus without evidence of real collected messages or row-level audit provenance.\n"
        "- `UniqueData/spam-text-messages-dataset` was rejected because it is CC BY-NC-ND and its provenance overlaps established SMS corpora; `jngb-labs/sms-spam` was rejected for v1 overlap and a custom licence.\n"
        "- `haxrits/adaption-sms-fraud-classification` was rejected for no explicit redistributable dataset licence and derivative instruction-tuning content; `alusci/sms-otp-spam-dataset` was rejected as synthetic/noisy.\n"
        "- The Roman Urdu Zenodo sample and Vietnamese sample were rejected for the English-only scope and incomplete or unsuitable licence/provenance for this holdout.\n"
        "- No additional real Indian/UPI/KYC corpus with a clearly redistributable licence was retained after the search; synthetic Indian/global controls are explicitly marked synthetic and must not be described as real traffic.\n\n"
        "## Limitation and next collection\n\n"
        f"The achieved holdout has {composition['counts']['binary_label'].get('malicious_or_fraudulent', 0)} malicious/fraudulent rows and {composition['counts']['binary_label'].get('legitimate', 0)} legitimate rows. Real-source scarcity is not hidden: the real sources remain source-screened and unbalanced, while the synthetic portion is clearly separated. A stronger benchmark needs more licensed real Indian smishing (UPI, KYC, bank impersonation), more genuine bank/OTP/payment alerts, sender/time metadata with consent, and independently audited contemporary benign traffic.\n",
        encoding="utf-8",
    )
    (output / "SEALED.md").write_text(
        "# SEALED\n\n"
        f"Sealed on 2026-09-24. Dataset: `{DATASET_VERSION}`.\n"
        f"Holdout SHA-256: `{file_sha256(output / 'holdout.jsonl')}`.\n"
        f"Previous manifest SHA-256: `{previous_manifest_sha256 or 'not supplied'}`.\n\n"
        "This holdout MUST NOT be used for fusion or model tuning. Evaluation is permitted only after explicit approval by the project lead. Every access must be logged in `holdout_access_log.json`; use the guarded access script and do not bypass the confirmation flag.\n",
        encoding="utf-8",
    )
    log = output / "holdout_access_log.json"
    if not log.exists():
        log.write_text("[]\n", encoding="utf-8")


def prepare_holdout_v2(input_files: list[str | Path], v1_dir: str | Path, output_dir: str | Path,
                       source_metadata: dict | None = None, previous_manifest_sha256: str | None = None) -> dict:
    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    access_log = output / "holdout_access_log.json"
    if access_log.exists() and json.loads(access_log.read_text(encoding="utf-8")) != []:
        raise ValueError("refusing to reseal a holdout with a non-empty access log")
    previous_manifest = output / "manifest.json"
    previous_manifest_record = json.loads(previous_manifest.read_text(encoding="utf-8")) if previous_manifest.exists() else {}
    if previous_manifest_sha256 is None and previous_manifest.exists():
        previous_manifest_sha256 = file_sha256(previous_manifest)
    candidates, rejected = _load_candidates([Path(path) for path in input_files])
    audits, audit_summary, _ = _load_audits(output, candidates)
    source_counts: dict[str, Counter] = defaultdict(Counter)
    audited_candidates, excluded_source_label_disagreement, excluded_audit_verdict = _apply_audits(candidates, audits, source_counts)

    v1_train, v1_validation, v1_test = _v1_rows(Path(v1_dir))
    v1_all = v1_train + v1_validation + v1_test
    train_sim, all_sim = _max_similarities(audited_candidates, v1_train, v1_all)
    exact = {row["text"] for row in v1_all}
    normalized = {normalize_for_dedup(row["text"]) for row in v1_all}
    strict_templates = {template_signature(row["text"], "strict") for row in v1_all}
    removal_counts = Counter()
    removal_ids: dict[str, list[str]] = defaultdict(list)
    survivors = []
    survivor_indices: list[int] = []
    for index, row in enumerate(audited_candidates):
        reason = None
        if row["text"] in exact:
            reason = "exact_duplicate"
        elif normalize_for_dedup(row["text"]) in normalized:
            reason = "normalized_duplicate"
        elif template_signature(row["text"], "strict") in strict_templates:
            reason = "strict_template_collision"
        elif all_sim[index] >= NEAR_DUPLICATE_THRESHOLD:
            reason = "near_duplicate"
        if reason:
            removal_counts[reason] += 1
            removal_ids[reason].append(str(row["id"]))
            source_counts[row["source_name"]][f"removed_v1_{reason}"] += 1
            continue
        row["_max_similarity_v1_train"] = round(float(train_sim[index]), 6)
        row["_max_similarity_v1_any"] = round(float(all_sim[index]), 6)
        survivors.append(row)
        survivor_indices.append(index)
        source_counts[row["source_name"]]["survived_isolation"] += 1

    survivors, within_holdout_deduplication = _within_holdout_dedupe(survivors, source_counts)
    groups, group_report = _near_groups(survivors, GROUP_THRESHOLD, "strict")
    group_by_id = {record_id: group_id for group_id, ids in groups.items() for record_id in ids}
    for row in survivors:
        row["duplicate_group"] = group_by_id[row["id"]]
        row["split"] = "holdout"
        row.pop("_input_file", None)
        row.pop("_max_similarity_v1_train", None)
        row.pop("_max_similarity_v1_any", None)
    all_rows = sorted(survivors, key=lambda row: row["id"])
    for row in all_rows:
        source_counts[row["source_name"]]["final"] += 1
    _write_jsonl(output / "holdout.jsonl", all_rows)
    composition = _composition(all_rows)
    sources = source_metadata or _default_source_metadata()
    source_report = {source: dict(sorted(counts.items())) for source, counts in sorted(source_counts.items())}
    _write_collection_docs(
        output, sources, composition, previous_manifest_sha256, source_report, audit_summary,
        excluded_source_label_disagreement, excluded_audit_verdict, within_holdout_deduplication,
    )
    audit_hashes = {
        str(path.relative_to(output)).replace("\\", "/"): file_sha256(path)
        for path in sorted((output / "audit_blind").glob("*.jsonl"))
    }
    input_hashes = [{"path": str(path.resolve()), "sha256": file_sha256(path)} for path in sorted({Path(p).resolve() for p in input_files})]
    history = list(previous_manifest_record.get("supersession_history", []))
    if previous_manifest_record.get("supersedes") and not history:
        history.append(previous_manifest_record["supersedes"])
    manifest = {
        "dataset_version": DATASET_VERSION,
        "seed": SEED,
        "builder_version": BUILDER_VERSION,
        "created": datetime.now(timezone.utc).isoformat(),
        "sealed": True,
        "sources": sources,
        "source_stage_counts": source_report,
        "composition": composition,
        "isolation": {
            "v1_rows": {"train": len(v1_train), "validation": len(v1_validation), "test": len(v1_test)},
            "exact_and_normalized_against_all_v1": True,
            "near_duplicate": {"fit": "v1_train_only", "analyzer": "char", "ngram_range": [3, 5], "sublinear_tf": True, "threshold": NEAR_DUPLICATE_THRESHOLD},
            "strict_template_signature": "scripts.prepare_dataset.template_signature(text, 'strict')",
            "removal_counts": dict(sorted(removal_counts.items())),
            "removed_ids": {reason: sorted(ids) for reason, ids in sorted(removal_ids.items())},
            "residual_max_similarity_to_v1_train": _quantiles(train_sim[survivor_indices] if survivor_indices else np.zeros(0)),
            "within_holdout_deduplication": within_holdout_deduplication,
            "within_holdout_grouping": group_report,
        },
        "accepted_schema_rows": len(candidates),
        "rejected_rows": rejected,
        "excluded_source_label_disagreement": sorted(excluded_source_label_disagreement),
        "excluded_audit_verdict": {reason: sorted(ids) for reason, ids in sorted(excluded_audit_verdict.items())},
        "final_rows": len(all_rows),
        "audit_summary": audit_summary,
        "input_files": input_hashes,
        "audit_files": audit_hashes,
        "rejected_audits": _rejected_audit_records(output),
        "sha256": {"holdout.jsonl": file_sha256(output / "holdout.jsonl")},
    }
    if previous_manifest_sha256:
        manifest["supersedes"] = {
            "manifest_sha256_of_previous_seal": previous_manifest_sha256,
            "previous_holdout_sha256": PREVIOUS_HOLDOUT_SHA256,
            "reason": SUPERSESSION_REASON,
        }
        manifest["supersession_history"] = history
    elif history:
        manifest["supersession_history"] = history
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
    access_log.write_text("[]\n", encoding="utf-8")
    return {"dataset_version": DATASET_VERSION, "rows": len(all_rows), "rejected_rows": len(rejected), "output_dir": str(output)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, action="append", required=True, help="Candidate JSONL; repeat for each source")
    parser.add_argument("--v1-dir", type=Path, default=Path("datasets/processed/trustshield-text-v1"))
    parser.add_argument("--output-dir", type=Path, default=Path("datasets/holdout/trustshield-text-v2-holdout"))
    parser.add_argument("--previous-manifest-sha256", help="Override the prior manifest hash recorded in supersedes")
    args = parser.parse_args()
    print(json.dumps(prepare_holdout_v2(args.input, args.v1_dir, args.output_dir, previous_manifest_sha256=args.previous_manifest_sha256), indent=2))


if __name__ == "__main__":
    main()
