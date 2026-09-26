"""Build the deterministic TrustShield text v3 train plus the exact v1 validation.

This script is intentionally explicit about every input.  It never opens the
v1 test split or any sealed-holdout labels/rows.  The one permitted holdout
operation streams only each holdout row's ``text`` field through a remove-only
similarity filter; no holdout text is retained or emitted.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import random
import re
import sys
import unicodedata
from collections import Counter, defaultdict
from datetime import date
from pathlib import Path
from typing import Any, Iterable

import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.prepare_dataset import (  # noqa: E402
    VALID_CATEGORIES,
    _pairwise_cosines,
    normalize_for_dedup,
    template_signature,
    validate_record,
)
from scripts.prepare_hardening_candidates import BUCKET_PATTERNS, bucket_names  # noqa: E402

SEED = 42
VERSION = "trustshield-text-v3"
V1_DIR = ROOT / "datasets" / "processed" / "trustshield-text-v1"
HARDENING_DIR = ROOT / "datasets" / "raw" / "hardening"
SYNTHETIC_DIR = ROOT / "datasets" / "raw" / "synthetic"
HOLDOUT_TEXT_PATH = ROOT / "datasets" / "holdout" / "trustshield-text-v2-holdout" / "holdout.jsonl"
HF_LEGIT_CAP = 150
NEAR_THRESHOLDS = (0.60, 0.70, 0.80, 0.85, 0.90)
CONTROL_RE = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
NEWS_RE = re.compile(
    r"(?i)(?:^\s*url\s*:|\bdate\s*:|\bsubject\s*:|newsisfree|\brss\b|click here to be removed|\bunsubscribe\b"
    r"|listinfo|mailman|mailing list|\benron\b|spamassassin|sourceforge\.net/lists|\brooming list\b)"
)
SPACED_PHONE_RE = re.compile(r"(?<!\w)\+?\d{2,4}\s+[-.]\s+\d{2,4}\s+[-.]\s+\d{3,4}(?!\w)")
NEWS_RATIONALE_RE = re.compile(
    r"(?i)\b(?:news|news report|article|newsletter|bulk promotion|marketing|feed|wire service|press release)\b"
)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def write_jsonl(path: Path, rows: Iterable[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def _clean_text(text: str) -> str:
    value = unicodedata.normalize("NFKC", text)
    value = CONTROL_RE.sub(" ", value)
    return re.sub(r"\s+", " ", value).strip()


def looks_like_non_message_hf_legitimate(text: str, rationale: str = "") -> bool:
    """Return true for HF legitimate rows that look like articles or bulk email.

    This selection rule is applied only to source-label legitimate HF rows.  It
    is deliberately small and auditable: obvious URL/header/newsletter tokens
    in the text or a rationale describing an article/news/bulk promotion are
    enough to remove the row.
    """
    return bool(NEWS_RE.search(text) or NEWS_RATIONALE_RE.search(rationale))


def blind_audit_agrees(source_label: int, verdict: str) -> bool:
    """Return whether a blind verdict agrees with the hidden source label."""
    return (int(source_label) == 1 and str(verdict).lower() == "fraud") or (int(source_label) == 0 and str(verdict).lower() == "legit")


def validation_copy_is_byte_identical(source_path: Path, output_path: Path) -> bool:
    """Check the v3 validation copy without parsing or rewriting JSONL."""
    return source_path.read_bytes() == output_path.read_bytes()


def audit_quality_check(input_rows: list[dict[str, Any]], audit_rows: list[dict[str, Any]],
                       min_distinct_rationale_ratio: float = 0.80) -> dict[str, Any]:
    """Validate one blind-audit batch and reject templated audit output."""
    input_ids = [str(row.get("source_row_id", "")) for row in input_rows]
    audit_ids = [str(row.get("source_row_id", "")) for row in audit_rows]
    rationales = [re.sub(r"\s+", " ", str(row.get("rationale", "")).strip().lower()) for row in audit_rows]
    unique_ids = len(set(audit_ids))
    unique_rationales = len(set(rationales))
    nonempty = sum(bool(value) for value in rationales)
    stats = {
        "input_rows": len(input_rows), "audit_rows": len(audit_rows),
        "matched_ids": len(set(input_ids) & set(audit_ids)),
        "duplicate_audit_ids": len(audit_ids) - unique_ids,
        "nonempty_rationales": nonempty,
        "distinct_rationale_ratio": unique_rationales / max(1, len(rationales)),
        "confidence_values": dict(Counter(str(row.get("confidence", "")) for row in audit_rows)),
    }
    if set(input_ids) != set(audit_ids) or stats["duplicate_audit_ids"]:
        raise ValueError("blind audit does not cover the input batch exactly once")
    if rationales and stats["distinct_rationale_ratio"] < min_distinct_rationale_ratio:
        raise ValueError("blind audit quality check rejected templated rationales")
    if nonempty != len(rationales):
        raise ValueError("blind audit contains an empty rationale")
    return stats


def _length_stats(rows: list[dict[str, Any]]) -> dict[str, float | int]:
    lengths = sorted(len(str(row.get("text", "")).split()) for row in rows)
    if not lengths:
        return {"n": 0, "min": 0, "mean": 0.0, "median": 0.0, "p95": 0, "max": 0}
    return {"n": len(lengths), "min": lengths[0], "mean": round(float(np.mean(lengths)), 2),
            "median": float(np.median(lengths)), "p95": lengths[max(0, int(0.95 * len(lengths)) - 1)],
            "max": lengths[-1]}


def _read_hf_inputs_and_audits() -> tuple[dict[str, str], dict[str, dict[str, Any]], dict[str, Any]]:
    input_paths = [HARDENING_DIR / name for name in ("hf_blind_input_A.jsonl", "hf_blind_input_A_rest.jsonl", "hf_blind_input_B.jsonl")]
    audit_paths = [HARDENING_DIR / name for name in ("hf_blind_audit_A_agy.jsonl", "hf_blind_audit_A_rest_agy.jsonl", "hf_blind_audit_B_agy.jsonl")]
    input_rows = {path.name: read_jsonl(path) for path in input_paths}
    audit_rows = {path.name: read_jsonl(path) for path in audit_paths}
    # The residual A batch is independently checked here, including its exact
    # ID coverage and rationale diversity.
    a_rows = input_rows["hf_blind_input_A.jsonl"] + input_rows["hf_blind_input_A_rest.jsonl"]
    a_by_id = {str(row["source_row_id"]): row for row in a_rows}
    a_rest_audit = audit_rows["hf_blind_audit_A_rest_agy.jsonl"]
    a_rest_input = [a_by_id[str(row["source_row_id"])] for row in a_rest_audit if str(row["source_row_id"]) in a_by_id]
    a_360_audit = audit_rows["hf_blind_audit_A_agy.jsonl"]
    a_360_input = [a_by_id[str(row["source_row_id"])] for row in a_360_audit if str(row["source_row_id"]) in a_by_id]
    quality = {
        "A_rest": audit_quality_check(a_rest_input, a_rest_audit),
        "A_360": audit_quality_check(a_360_input, a_360_audit),
        "B": audit_quality_check(input_rows["hf_blind_input_B.jsonl"], audit_rows["hf_blind_audit_B_agy.jsonl"]),
    }
    text_by_id: dict[str, str] = {}
    for rows in input_rows.values():
        for row in rows:
            text_by_id.setdefault(str(row["source_row_id"]), str(row.get("text", "")))
    audits: dict[str, dict[str, Any]] = {}
    for rows in audit_rows.values():
        for row in rows:
            key = str(row["source_row_id"])
            if key in audits:
                raise ValueError(f"duplicate HF audit id: {key}")
            audits[key] = row
    return text_by_id, audits, {"quality": quality, "input_counts": {key: len(value) for key, value in input_rows.items()},
                               "audit_counts": {key: len(value) for key, value in audit_rows.items()}}


def _hf_candidates() -> tuple[list[dict[str, Any]], dict[str, Any]]:
    mapping = {str(row["source_row_id"]): row for row in read_jsonl(HARDENING_DIR / "hf_sample_map.jsonl")}
    text_by_id, audits, audit_stats = _read_hf_inputs_and_audits()
    counts = Counter()
    before_legit: list[dict[str, Any]] = []
    before_fraud: list[dict[str, Any]] = []
    discarded: Counter[str] = Counter()
    for source_id, mapping_row in sorted(mapping.items()):
        audit = audits.get(source_id)
        text = text_by_id.get(source_id, "")
        if audit is None or not text:
            discarded["no_verdict_or_missing_input"] += 1
            continue
        verdict = str(audit.get("verdict", "")).lower()
        source_label = int(mapping_row["source_label"])
        counts[f"source_label_{source_label}::{verdict}"] += 1
        if verdict == "exclude":
            discarded["exclude"] += 1
            continue
        agrees = blind_audit_agrees(source_label, verdict)
        if not agrees:
            discarded["disagreement"] += 1
            continue
        category = str(audit.get("category", ""))
        if category not in VALID_CATEGORIES:
            discarded["invalid_agreed_category"] += 1
            continue
        row = {
            "text": text, "binary_label": "malicious_or_fraudulent" if source_label else "legitimate",
            "category_label": category, "source_type": "public_dataset",
            "source_name": "ealvaradob_phishing-dataset_texts",
            "synthetic": False, "language": "en", "date_added": "2026-09-24",
            "notes": "declared license apache-2.0; upstream provenance unclear; blind audit agreement",
            "annotation_method": "llm-audit-blind:antigravity-agy",
            "audit_rationale": str(audit.get("rationale", "")), "audit_confidence": audit.get("confidence", ""),
            "source_row_id": source_id, "source_label": source_label,
        }
        if source_label == 0:
            before_legit.append(row)
        else:
            before_fraud.append(row)
    legit_before_filter = list(before_legit)
    message_removed = [row for row in before_legit if looks_like_non_message_hf_legitimate(row["text"], row["audit_rationale"])]
    before_legit = [row for row in before_legit if row not in message_removed]
    discarded["legitimate_message_likeness"] += len(message_removed)
    rng = random.Random(SEED)
    cap_removed = 0
    if len(before_legit) > HF_LEGIT_CAP:
        kept_indexes = set(rng.sample(range(len(before_legit)), HF_LEGIT_CAP))
        cap_removed = len(before_legit) - HF_LEGIT_CAP
        before_legit = [row for index, row in enumerate(before_legit) if index in kept_indexes]
        discarded["legitimate_cap"] += cap_removed
    selected = before_legit + before_fraud
    for row in selected:
        row["text"] = SPACED_PHONE_RE.sub("<PHONE>", row["text"])
    selected.sort(key=lambda row: (row["source_row_id"], row["text"]))
    stats = {
        "mapping_rows": len(mapping), "audited_rows": len(audits), "no_verdict_rows": discarded["no_verdict_or_missing_input"],
        "verdict_counts": dict(sorted(counts.items())), "discarded": dict(sorted(discarded.items())),
        "agreement_kept_before_message_filter": len(legit_before_filter) + len(before_fraud),
        "legitimate_message_filter_removed": len(message_removed), "legitimate_cap": HF_LEGIT_CAP,
        "legitimate_cap_removed": cap_removed, "kept_rows": len(selected),
        "composition_before_message_filter": {"legitimate": len(legit_before_filter), "malicious_or_fraudulent": len(before_fraud)},
        "composition_after_message_filter_before_cap": {"legitimate": len(legit_before_filter) - len(message_removed), "malicious_or_fraudulent": len(before_fraud)},
        "composition_after_cap": dict(Counter(row["binary_label"] for row in selected)),
        "lengths_before_message_filter": _length_stats(legit_before_filter + before_fraud),
        "lengths_after_message_filter_and_cap": _length_stats(selected),
        "per_source_before": {"ealvaradob_phishing-dataset_texts": _length_stats(legit_before_filter + before_fraud)},
        "per_source_after": {"ealvaradob_phishing-dataset_texts": _length_stats(selected)},
        "audit_quality": audit_stats,
    }
    return selected, stats


def _canonical_candidate(raw: dict[str, Any], source_path: Path, line_number: int) -> tuple[dict[str, Any] | None, list[str]]:
    candidate = dict(raw)
    candidate["text"] = _clean_text(str(candidate.get("text", "")))
    row, reasons = validate_record(candidate, line_number)
    if row is not None:
        row["_origin"] = "new"
        row["_input_file"] = str(source_path)
    return row, reasons


def _add_bucket_tags(row: dict[str, Any]) -> dict[str, Any]:
    if row["binary_label"] != "legitimate":
        return row
    buckets = bucket_names(row["text"])
    if buckets:
        note = str(row.get("notes", "")).rstrip(";")
        marker = f"hardneg_bucket={','.join(buckets)}"
        if marker not in note:
            row["notes"] = f"{note};{marker}" if note else marker
    return row


class _UF:
    def __init__(self, size: int):
        self.parent = list(range(size))

    def find(self, index: int) -> int:
        while self.parent[index] != index:
            self.parent[index] = self.parent[self.parent[index]]
            index = self.parent[index]
        return index

    def union(self, left: int, right: int) -> None:
        left, right = self.find(left), self.find(right)
        if left != right:
            self.parent[right] = left


def _cosine_vectorizer(texts: list[str]) -> tuple[TfidfVectorizer | None, Any | None]:
    if not texts:
        return None, None
    vectorizer = TfidfVectorizer(analyzer="char", ngram_range=(3, 5), sublinear_tf=True)
    return vectorizer, vectorizer.fit_transform(texts)


def _max_cosines(reference_texts: list[str], candidate_texts: list[str], fit_texts: list[str] | None = None) -> list[float]:
    if not candidate_texts or not reference_texts:
        return [0.0] * len(candidate_texts)
    vectorizer = TfidfVectorizer(analyzer="char", ngram_range=(3, 5), sublinear_tf=True)
    matrix = vectorizer.fit_transform(fit_texts or (reference_texts + candidate_texts))
    references = vectorizer.transform(reference_texts)
    candidates = vectorizer.transform(candidate_texts)
    return [float((candidates[index] @ references.T).max()) for index in range(candidates.shape[0])]


def filter_candidates_against_holdout_texts(candidates: list[dict[str, Any]], holdout_path: Path,
                                            threshold: float = 0.60) -> tuple[list[dict[str, Any]], dict[str, int]]:
    """Remove candidates similar to holdout text while retaining no holdout text.

    Only a transient ``text`` local and one sparse vector for the current row
    are used.  The result and report contain counts only, never holdout text or
    any non-text holdout field.
    """
    if not candidates:
        # The file is intentionally not needed when there is no candidate to test.
        return [], {"holdout_rows_read": 0, "exact_removed": 0, "normalized_removed": 0,
                    "cosine_removed": 0, "removed_total": 0}
    candidate_texts = [row["text"] for row in candidates]
    vectorizer, candidate_matrix = _cosine_vectorizer(candidate_texts)
    exact_keys = {text for text in candidate_texts}
    normalized_keys = {normalize_for_dedup(text) for text in candidate_texts}
    exact_hits: set[int] = set()
    normalized_hits: set[int] = set()
    cosine_hits: set[int] = set()
    holdout_rows = 0
    with holdout_path.open(encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            parsed = json.loads(line)
            text = parsed.get("text") if isinstance(parsed, dict) else None
            if not isinstance(text, str) or not text:
                continue
            holdout_rows += 1
            if text in exact_keys:
                exact_hits.update(index for index, value in enumerate(candidate_texts) if value == text)
            if normalize_for_dedup(text) in normalized_keys:
                normalized_hits.update(index for index, value in enumerate(candidate_texts)
                                       if normalize_for_dedup(value) == normalize_for_dedup(text))
            row_vector = vectorizer.transform([text])
            similarities = (candidate_matrix @ row_vector.T).toarray().ravel()
            cosine_hits.update(index for index, value in enumerate(similarities) if float(value) >= threshold)
            del parsed, text, row_vector, similarities
    removed = exact_hits | normalized_hits | cosine_hits
    kept = [row for index, row in enumerate(candidates) if index not in removed]
    return kept, {"holdout_rows_read": holdout_rows, "exact_removed": len(exact_hits),
                  "normalized_removed": len(normalized_hits), "cosine_removed": len(cosine_hits),
                  "removed_total": len(removed)}


def _dedup_new_candidates(v1_train: list[dict[str, Any]], validation: list[dict[str, Any]],
                          candidates: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], Counter[str]]:
    reasons: Counter[str] = Counter()
    exact_train = {row["text"] for row in v1_train}
    norm_train = {normalize_for_dedup(row["text"]) for row in v1_train}
    exact_val = {row["text"] for row in validation}
    norm_val = {normalize_for_dedup(row["text"]) for row in validation}
    accepted: list[dict[str, Any]] = []
    for row in sorted(candidates, key=lambda item: item["id"]):
        if row["text"] in exact_val:
            reasons["duplicate_validation_exact"] += 1; continue
        if normalize_for_dedup(row["text"]) in norm_val:
            reasons["duplicate_validation_normalized"] += 1; continue
        if row["text"] in exact_train:
            reasons["duplicate_v1_train_exact"] += 1; continue
        if normalize_for_dedup(row["text"]) in norm_train:
            reasons["duplicate_v1_train_normalized"] += 1; continue
        accepted.append(row)
    by_norm: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in accepted:
        by_norm[normalize_for_dedup(row["text"])].append(row)
    result: list[dict[str, Any]] = []
    for _, group in sorted(by_norm.items()):
        labels = {(row["binary_label"], row["category_label"]) for row in group}
        if len(labels) > 1:
            reasons["new_normalized_label_conflict"] += len(group)
            continue
        result.append(sorted(group, key=lambda item: item["id"])[0])
        reasons["new_normalized_duplicate"] += max(0, len(group) - 1)
    return sorted(result, key=lambda row: row["id"]), reasons


def _assign_groups(rows: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    if not rows:
        return [], {"pair_counts": {str(value): 0 for value in NEAR_THRESHOLDS}, "groups": 0}
    texts = [row["text"] for row in rows]
    vectorizer, matrix = _cosine_vectorizer(texts)
    assert vectorizer is not None and matrix is not None
    pair_counts = {str(value): len(list(_pairwise_cosines(matrix, value))) for value in NEAR_THRESHOLDS}
    uf = _UF(len(rows))
    for left, right, _ in _pairwise_cosines(matrix, 0.80):
        uf.union(left, right)
    by_signature: dict[str, list[int]] = defaultdict(list)
    for index, row in enumerate(rows):
        by_signature[template_signature(row["text"], "strict")].append(index)
    for indexes in by_signature.values():
        for index in indexes[1:]:
            uf.union(indexes[0], index)
    roots: dict[int, list[int]] = defaultdict(list)
    for index in range(len(rows)):
        roots[uf.find(index)].append(index)
    ordered = sorted(roots.values(), key=lambda group: min(rows[index]["id"] for index in group))
    group_by_index: dict[int, str] = {}
    groups: dict[str, list[str]] = {}
    for number, indexes in enumerate(ordered, 1):
        group_id = f"v3-dg-{number:06d}"
        groups[group_id] = sorted(rows[index]["id"] for index in indexes)
        for index in indexes:
            group_by_index[index] = group_id
            rows[index]["duplicate_group"] = group_id
            rows[index]["split"] = "train"
    return rows, {"pair_counts": pair_counts, "groups": len(groups), "template_signatures": len(by_signature),
                  "groups_map": groups}


def _cross_split_leakage(train_rows: list[dict[str, Any]], validation_rows: list[dict[str, Any]]) -> dict[str, Any]:
    train_texts = [row["text"] for row in train_rows]
    val_texts = [row["text"] for row in validation_rows]
    exact = len(set(train_texts) & set(val_texts))
    normalized = len({normalize_for_dedup(text) for text in train_texts} & {normalize_for_dedup(text) for text in val_texts})
    groups = len({row.get("duplicate_group", "") for row in train_rows} & {row.get("duplicate_group", "") for row in validation_rows})
    counts = {str(value): 0 for value in NEAR_THRESHOLDS}
    if train_texts and val_texts:
        vectorizer = TfidfVectorizer(analyzer="char", ngram_range=(3, 5), sublinear_tf=True)
        matrix = vectorizer.fit_transform(train_texts + val_texts)
        train_matrix, val_matrix = matrix[:len(train_texts)], matrix[len(train_texts):]
        similarities = train_matrix @ val_matrix.T
        for value in similarities.data:
            for threshold in NEAR_THRESHOLDS:
                if float(value) >= threshold:
                    counts[str(threshold)] += 1
    return {"exact_pairs": exact, "normalized_pairs": normalized, "duplicate_group_overlap": groups,
            "cosine_pair_counts": counts}


def _composition(rows: list[dict[str, Any]]) -> dict[str, Any]:
    dimensions = ("binary_label", "category_label", "source_type", "source_name", "synthetic")
    result: dict[str, Any] = {dimension: dict(sorted(Counter(str(row.get(dimension, "")) for row in rows).items())) for dimension in dimensions}
    bucket_counts: Counter[str] = Counter()
    bucket_source: Counter[str] = Counter()
    for row in rows:
        if row.get("binary_label") == "legitimate":
            for bucket in bucket_names(row["text"]):
                bucket_counts[bucket] += 1
                bucket_source[f"{bucket}::{row.get('synthetic', False)}"] += 1
    result["hardneg_bucket_counts"] = dict(sorted(bucket_counts.items()))
    result["hardneg_bucket_by_synthetic"] = dict(sorted(bucket_source.items()))
    result["under_supported_categories"] = sorted(category for category, count in result["category_label"].items()
                                                    if category not in {"legitimate", "unlabeled"} and count < 30)
    return result


def build(output_dir: str | Path | None = None) -> dict[str, Any]:
    output = Path(output_dir or ROOT / "datasets" / "processed" / VERSION).resolve()
    output.mkdir(parents=True, exist_ok=True)
    v1_train_path, v1_validation_path = V1_DIR / "train.jsonl", V1_DIR / "validation.jsonl"
    validation_bytes = v1_validation_path.read_bytes()
    v1_train = read_jsonl(v1_train_path)
    validation = read_jsonl(v1_validation_path)
    if any(str(row.get("source_name", "")).startswith("holdout-") for row in v1_train):
        raise ValueError("v1 train contains a holdout source")
    # Public rows may carry a Codex audit-method provenance note; that does not
    # make the message Codex-authored.  The prohibited authored class is the
    # synthetic/Codex source itself.
    codex_v1_train = [row["id"] for row in v1_train
                      if row.get("synthetic") is True and "codex" in str(row.get("source_name", "")).lower()]
    if codex_v1_train:
        raise ValueError("v1 train contains Codex-authored rows")
    if len(v1_train) != 2918 or len(validation) != 625:
        raise ValueError(f"unexpected v1 sizes: train={len(v1_train)} validation={len(validation)}")

    input_paths = [v1_train_path, v1_validation_path, HARDENING_DIR / "real_hardneg_candidates.jsonl",
                   SYNTHETIC_DIR / "agy_hardening_v2.jsonl", HARDENING_DIR / "hf_sample_map.jsonl",
                   HARDENING_DIR / "hf_blind_input_A.jsonl", HARDENING_DIR / "hf_blind_input_A_rest.jsonl",
                   HARDENING_DIR / "hf_blind_input_B.jsonl", HARDENING_DIR / "hf_blind_audit_A_agy.jsonl",
                   HARDENING_DIR / "hf_blind_audit_A_rest_agy.jsonl", HARDENING_DIR / "hf_blind_audit_B_agy.jsonl"]
    real_raw = read_jsonl(input_paths[2])
    synthetic_raw = read_jsonl(input_paths[3])
    hf_rows, hf_stats = _hf_candidates()
    raw_candidate_rows = real_raw + synthetic_raw + hf_rows
    candidates: list[dict[str, Any]] = []
    schema_rejections: Counter[str] = Counter()
    for source_path, rows in ((input_paths[2], real_raw), (input_paths[3], synthetic_raw),
                              (HARDENING_DIR / "hf_blind_audited_agreed.jsonl", hf_rows)):
        for line_number, raw in enumerate(rows, 1):
            row, reasons = _canonical_candidate(raw, source_path, line_number)
            if row is None:
                schema_rejections.update(reasons)
                continue
            row.pop("_input_file", None)
            row.pop("_origin", None)
            _add_bucket_tags(row)
            candidates.append(row)
    candidate_before_filters = len(candidates)
    holdout_candidates, holdout_stats = filter_candidates_against_holdout_texts(candidates, HOLDOUT_TEXT_PATH)
    holdout_removed_ids = {row["id"] for row in candidates} - {row["id"] for row in holdout_candidates}
    candidates, dedup_reasons = _dedup_new_candidates(v1_train, validation, holdout_candidates)
    dedup_after = len(candidates)
    validation_strict = {template_signature(row["text"], "strict") for row in validation}
    fit_texts = [row["text"] for row in v1_train] + [row["text"] for row in candidates]
    val_filter_removed: Counter[str] = Counter()
    val_cosines = _max_cosines([row["text"] for row in validation], [row["text"] for row in candidates], fit_texts=fit_texts)
    remaining: list[dict[str, Any]] = []
    for row, cosine in zip(candidates, val_cosines):
        if cosine >= 0.60:
            val_filter_removed["near_duplicate_validation_cosine_ge_0.60"] += 1
        elif template_signature(row["text"], "strict") in validation_strict:
            val_filter_removed["strict_template_validation_collision"] += 1
        else:
            remaining.append(row)
    candidates = remaining
    train_rows = [dict(row) for row in v1_train] + candidates
    train_rows, group_report = _assign_groups(train_rows)
    for row in train_rows:
        row["split"] = "train"
    v3_validation = [dict(row) for row in validation]
    v3_validation_bytes = validation_bytes
    group_overlap = set(row["duplicate_group"] for row in train_rows) & set(row["duplicate_group"] for row in v3_validation)
    if group_overlap:
        raise AssertionError(f"train/validation duplicate group overlap: {sorted(group_overlap)[:3]}")
    leakage = {
        "v1_train_vs_v1_validation": _cross_split_leakage(v1_train, validation),
        "v3_train_vs_v3_validation": _cross_split_leakage(train_rows, v3_validation),
        "group_overlap_asserted_zero": len(group_overlap) == 0,
        "holdout_text_filter": holdout_stats,
        "holdout_removed_candidate_ids_count_only": len(holdout_removed_ids),
    }

    write_jsonl(output / "train.jsonl", sorted(train_rows, key=lambda row: row["id"]))
    (output / "validation.jsonl").write_bytes(v3_validation_bytes)
    write_jsonl(output / "all.jsonl", sorted(train_rows + v3_validation, key=lambda row: row["id"]))
    composition = {"all": _composition(train_rows + v3_validation), "train": _composition(train_rows),
                   "validation": _composition(v3_validation), "new_candidates_kept": _composition(candidates),
                   "hf": hf_stats}
    (output / "composition.json").write_text(json.dumps(composition, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    dedup_report = ["# v3 deduplication and filtering report", "", f"- Initial new candidates: {candidate_before_filters}",
                    f"- Holdout text-only filter removed: {holdout_stats['removed_total']}",
                    f"- After exact/normalized deduplication: {dedup_after}",
                    f"- After v1-validation cosine/template filters: {len(candidates)}", "", "## Removal counts"]
    for key, value in sorted({**schema_rejections, **dedup_reasons, **val_filter_removed}.items()):
        dedup_report.append(f"- `{key}`: {value}")
    (output / "dedup_report.md").write_text("\n".join(dedup_report) + "\n", encoding="utf-8")
    leakage_report = ["# v3 leakage report", "", "Cosine uses character TF-IDF with 3-5 grams and sublinear TF.", ""]
    for name in ("v1_train_vs_v1_validation", "v3_train_vs_v3_validation"):
        value = leakage[name]
        leakage_report.extend([f"## {name}", "", f"- Exact pairs: {value['exact_pairs']}",
                               f"- Normalized pairs: {value['normalized_pairs']}",
                               f"- Duplicate-group overlap: {value['duplicate_group_overlap']}", "",
                               "| threshold | cosine pairs |", "|---:|---:|"])
        leakage_report.extend(f"| {threshold} | {count} |" for threshold, count in value["cosine_pair_counts"].items())
        leakage_report.append("")
    leakage_report.append(f"Holdout text-only filter removed {holdout_stats['removed_total']} new candidates; no holdout labels or non-text fields were used.")
    (output / "leakage_report.md").write_text("\n".join(leakage_report) + "\n", encoding="utf-8")
    output_files = ["train.jsonl", "validation.jsonl", "all.jsonl", "composition.json", "dedup_report.md", "leakage_report.md"]
    manifest = {
        "dataset_version": VERSION, "seed": SEED, "date_built": date.today().isoformat(),
        "validation_is_exact_v1_copy": validation_bytes == v3_validation_bytes,
        "validation_v1_sha256": sha256_file(v1_validation_path), "validation_v3_sha256": sha256_file(output / "validation.jsonl"),
        "no_test_split_created": True,
        "final_evaluation_reserved_for_sealed_v2_holdout_after_lead_approval": True,
        "input_files": [{"path": str(path), "sha256": sha256_file(path)} for path in input_paths],
        "holdout_text_filter_input": {"path": str(HOLDOUT_TEXT_PATH), "sha256": "not computed; text-only remove-only protocol"},
        "verified_v1_train_rows": len(v1_train), "verified_v1_train_holdout_rows": 0, "verified_v1_train_codex_rows": 0,
        "counts": {"v1_train": len(v1_train), "v1_validation": len(validation), "real_raw": len(real_raw),
                   "synthetic_raw": len(synthetic_raw), "hf_audited_agreed_candidates": len(hf_rows),
                   "new_candidates_before_filters": candidate_before_filters, "new_candidates_after_holdout": len(holdout_candidates),
                   "new_candidates_after_dedup": dedup_after, "new_candidates_after_validation_filters": len(candidates),
                   "v3_train": len(train_rows), "v3_validation": len(v3_validation), "v3_all": len(train_rows) + len(v3_validation)},
        "removal_counts": {"schema_rejections": dict(sorted(schema_rejections.items())),
                           "holdout_text_filter": holdout_stats, "exact_normalized_dedup": dict(sorted(dedup_reasons.items())),
                           "v1_validation_filters": dict(sorted(val_filter_removed.items()))},
        "hf_audit": hf_stats,
        "group_report": {key: value for key, value in group_report.items() if key != "groups_map"},
        "composition": composition,
        "leakage": leakage,
        "output_sha256": {name: sha256_file(output / name) for name in output_files},
        "under_supported_categories": composition["train"]["under_supported_categories"],
        "forbidden_inputs_not_read": [str(V1_DIR / "test.jsonl"), "datasets/raw/synthetic/codex_holdout.jsonl",
                                       "datasets/holdout/** labels/non-text fields", "datasets/raw/holdout_v2/**"],
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    manifest["output_sha256"]["manifest.json"] = sha256_file(output / "manifest.json")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", "--out-dir", dest="output_dir", type=Path,
                        default=ROOT / "datasets" / "processed" / VERSION)
    import sys as _sys
    from pathlib import Path as _Path
    _sys.path.insert(0, str(_Path(__file__).resolve().parents[1]))
    from scripts._v2_spent_guard import require_archival_override
    args = parser.parse_args(require_archival_override(None))
    print(json.dumps(build(args.output_dir), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
