#!/usr/bin/env python3
"""Build real hard-negative candidates and a label-free HF blind-audit sample.

This step intentionally reads only the two retained public sources, the v1
train/validation splits, and the Hugging Face ``texts`` source.  It never
discovers files recursively, and it never reads a test or holdout path.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import random
import re
import shutil
import statistics
import tempfile
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable

from sklearn.feature_extraction.text import TfidfVectorizer


ROOT = Path(__file__).resolve().parents[1]
PUBLIC_DIR = ROOT / "datasets" / "processed" / "public"
V1_DIR = ROOT / "datasets" / "processed" / "trustshield-text-v1"
HARDENING_DIR = ROOT / "datasets" / "raw" / "hardening"
DEFAULT_HF_PATH = ROOT / "datasets" / "raw" / "ealvaradob_phishing-dataset" / "texts.json"
SEED = 42
HF_CONFIG = "texts"
HF_REPO = "ealvaradob/phishing-dataset"
HF_URL = "https://huggingface.co/datasets/ealvaradob/phishing-dataset"
HF_SAMPLE_SIZE = 800
BLIND_SIZE = 400
NEAR_DUPLICATE_THRESHOLD = 0.60

BUCKET_PATTERNS: dict[str, tuple[str, ...]] = {
    "otp_pin": (r"\botp\b", r"\bpin\b", r"\bcvv\b", r"\bpassword\b"),
    "bank_account": (r"\bbank\b", r"a\s*/\s*c", r"\baccount\b", r"\bdebited\b", r"\bcredited\b", r"\bupi\b", r"\bkyc\b"),
    "payment": (r"\bpayment\b", r"\bpaid\b", r"\brefund\b", r"\bfee\b", r"\bemi\b", r"\bloan\b", r"\bbill\b"),
    "urgent_service": (r"\burgent\b", r"\bexpires?\b", r"\bblocked\b", r"\bsuspended\b", r"\bimmediately\b", r"\blast\s+date\b", r"\bdue\b"),
    "job": (r"\bjob\b", r"\binterview\b", r"\bhiring\b", r"\bsalary\b", r"\bvacancy\b", r"\boffer\s+letter\b"),
    "delivery": (r"\bparcel\b", r"\bcourier\b", r"\bdelivery\b", r"\bshipped\b", r"\border\b", r"\btracking\b"),
    "telecom": (r"\brecharge\b", r"\bdata\b", r"\bplan\b", r"\bairtel\b", r"\bjio\b", r"\bvi\b", r"\bvodafone\b", r"\bbalance\b", r"\bsim\b"),
}
COMPILED_BUCKETS = {
    bucket: tuple(re.compile(pattern, re.IGNORECASE) for pattern in patterns)
    for bucket, patterns in BUCKET_PATTERNS.items()
}
URL_RE = re.compile(r"(?i)\b(?:https?://|www\.)[^\s<>\"']+|\b[a-z0-9-]+(?:\.[a-z0-9-]+)+(?:/[^\s<>\"']*)?")


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


def normalize_for_dedup(text: str) -> str:
    value = unicodedata.normalize("NFKC", text).lower()
    value = URL_RE.sub(" <url> ", value)
    value = re.sub(r"\d", "0", value)
    return re.sub(r"\s+", " ", value).strip()


def bucket_names(text: str) -> tuple[str, ...]:
    """Return all matching vocabulary buckets in their registered order."""
    return tuple(
        bucket for bucket in BUCKET_PATTERNS
        if any(pattern.search(text) for pattern in COMPILED_BUCKETS[bucket])
    )


def _hygiene(text: str) -> str:
    text = html.unescape(text)
    if "Ã¯Â¿Â½" in text:
        text = text.replace("Ã¯Â¿Â½", "<MISSING_CHAR>")
    if re.search(r"(?:ÃƒÆ’|Ãƒâ€š|ÃƒÂ¢|ÃƒÂ°Ã…Â¸)", text):
        return ""
    text = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def _english(text: str) -> bool:
    if not text or re.search(r"[\u0370-\u052f\u0590-\u08ff\u0900-\u0dff\u3040-\u30ff\u4e00-\u9fff]", text):
        return False
    letters = [char for char in text if char.isalpha()]
    return bool(letters) and sum(char.isascii() for char in letters) / len(letters) >= 0.80


def _replace_pii(text: str) -> str:
    text = re.sub(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b", "<EMAIL>", text, flags=re.I)
    return re.sub(
        r"(?<!\w)(?:\+?\d{1,3}\s*[-. ]\s*)?(?:\(?\d{2,4}\)?\s*[-. ]\s*)?\d{3,4}\s*[-. ]\s*\d{3,4}(?!\w)",
        "<PHONE>",
        text,
    )


def clean_hf_text(text: str) -> str:
    """Apply the same HTML/whitespace/PII hygiene used by public curation."""
    return _replace_pii(_hygiene(text))


def is_sms_like_english(text: str) -> bool:
    """Use the available HF fields as an auditable SMS-like proxy.

    The source has no message-type field.  The pre-registered proxy is an
    English, non-empty row with at most 60 whitespace-delimited words after
    the shared hygiene pass.  This is intentionally conservative about the
    claim: short email/web fragments can still be present because provenance
    within the compiled source is unavailable.
    """
    return bool(text) and _english(text) and len(text.split()) <= 60


def _record_key(row: dict[str, Any]) -> str:
    return normalize_for_dedup(str(row.get("text", "")))


def load_v1_reference() -> tuple[list[dict[str, Any]], set[str]]:
    """Read exactly v1 train and validation, never test."""
    rows = read_jsonl(V1_DIR / "train.jsonl") + read_jsonl(V1_DIR / "validation.jsonl")
    return rows, {_record_key(row) for row in rows}


def build_hardneg_candidates(v1_normalized: set[str]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    candidates: list[dict[str, Any]] = []
    counts: Counter[str] = Counter()
    source_counts: Counter[str] = Counter()
    lengths: defaultdict[str, list[int]] = defaultdict(list)
    for source_name, filename in (("ucI_sms_spam", "ucI_sms_spam.jsonl"), ("india_spam_sms", "india_spam_sms.jsonl")):
        for row in read_jsonl(PUBLIC_DIR / filename):
            if row.get("binary_label") != "legitimate" or _record_key(row) in v1_normalized:
                continue
            buckets = bucket_names(row["text"])
            if not buckets:
                continue
            candidate = dict(row)
            candidate["notes"] = f"{candidate.get('notes', '').rstrip(';')};hardneg_bucket={','.join(buckets)};provenance=retained_public_dataset_row"
            candidates.append(candidate)
            source_counts[source_name] += 1
            lengths[source_name].append(len(row["text"].split()))
            for bucket in buckets:
                counts[f"{source_name}::{bucket}"] += 1
    candidates.sort(key=lambda row: (row["source_name"], row["id"], normalize_for_dedup(row["text"])))
    return candidates, {
        "rows": len(candidates),
        "source_counts": dict(sorted(source_counts.items())),
        "source_bucket_counts": dict(sorted(counts.items())),
        "lengths_words": length_stats(lengths),
    }


def length_stats(lengths: dict[str, list[int]] | defaultdict[str, list[int]]) -> dict[str, dict[str, float | int]]:
    result: dict[str, dict[str, float | int]] = {}
    for key, values in sorted(lengths.items()):
        ordered = sorted(values)
        result[key] = {
            "n": len(ordered),
            "min": ordered[0] if ordered else 0,
            "mean": round(statistics.mean(ordered), 2) if ordered else 0,
            "median": statistics.median(ordered) if ordered else 0,
            "p95": ordered[max(0, int(0.95 * len(ordered)) - 1)] if ordered else 0,
            "max": ordered[-1] if ordered else 0,
        }
    return result


def _max_reference_cosine(reference_texts: list[str], candidate_texts: list[str]) -> list[float]:
    if not reference_texts or not candidate_texts:
        return [0.0] * len(candidate_texts)
    vectorizer = TfidfVectorizer(analyzer="char", ngram_range=(3, 5), sublinear_tf=True)
    reference_matrix = vectorizer.fit_transform(reference_texts)
    candidate_matrix = vectorizer.transform(candidate_texts)
    return [
        float((candidate_matrix[index] @ reference_matrix.T).max())
        for index in range(candidate_matrix.shape[0])
    ]


def load_hf_rows(path: Path) -> tuple[list[dict[str, Any]], dict[str, int]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    rows: list[dict[str, Any]] = []
    rejection_counts: Counter[str] = Counter()
    for original_index, item in enumerate(data):
        text = clean_hf_text(str(item.get("text", "")))
        if not text:
            rejection_counts["empty_or_mojibake"] += 1
            continue
        if not _english(text):
            rejection_counts["non_english"] += 1
            continue
        if len(text.split()) > 60:
            rejection_counts["over_60_words"] += 1
            continue
        label = item.get("label")
        if label not in (0, 1):
            rejection_counts["missing_or_invalid_label"] += 1
            continue
        rows.append({
            "source_row_id": f"hf-texts-{original_index:06d}",
            "text": text,
            "source_label": int(label),
            "original_index": original_index,
            "source_config": HF_CONFIG,
        })
    return rows, dict(sorted(rejection_counts.items()))


def _fetch_hf_to_allowed_path(destination: Path) -> Path:
    """Fetch texts.json with HF Hub without writing outside hardening output."""
    try:
        from huggingface_hub import hf_hub_download
    except ImportError as exc:  # pragma: no cover - environment dependent
        raise RuntimeError("huggingface_hub is required when the HF source is absent") from exc
    with tempfile.TemporaryDirectory(prefix="trustshield-hf-") as temp_dir:
        downloaded = Path(hf_hub_download(HF_REPO, "texts.json", repo_type="dataset", local_dir=temp_dir))
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(downloaded, destination)
    return destination


def choose_hf_path(requested: Path | None) -> tuple[Path, str]:
    source = requested or DEFAULT_HF_PATH
    if source.exists():
        return source, "existing_fetched_hf_artifact"
    destination = HARDENING_DIR / "hf_texts.json"
    return _fetch_hf_to_allowed_path(destination), "hf_hub_download"


def build_hf_sample(v1_rows: list[dict[str, Any]], v1_normalized: set[str], hf_path: Path) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    eligible, rejection_counts = load_hf_rows(hf_path)
    exact_removed = 0
    exact_kept: list[dict[str, Any]] = []
    for row in eligible:
        if normalize_for_dedup(row["text"]) in v1_normalized:
            exact_removed += 1
        else:
            exact_kept.append(row)
    max_cosines = _max_reference_cosine([row["text"] for row in v1_rows], [row["text"] for row in exact_kept])
    near_removed = sum(value >= NEAR_DUPLICATE_THRESHOLD for value in max_cosines)
    near_kept = [row for row, value in zip(exact_kept, max_cosines) if value < NEAR_DUPLICATE_THRESHOLD]
    natural_balance = dict(sorted(Counter(row["source_label"] for row in near_kept).items()))
    if len(near_kept) < HF_SAMPLE_SIZE:
        raise RuntimeError(f"only {len(near_kept)} HF rows remain after filtering; need {HF_SAMPLE_SIZE}")
    by_label: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for row in near_kept:
        by_label[row["source_label"]].append(row)
    rng = random.Random(SEED)
    target_each = HF_SAMPLE_SIZE // 2
    if all(len(by_label[label]) >= target_each for label in (0, 1)):
        sampled = rng.sample(by_label[0], target_each) + rng.sample(by_label[1], target_each)
    else:
        sampled = rng.sample(near_kept, HF_SAMPLE_SIZE)
    rng.shuffle(sampled)
    return sampled, {
        "eligible_after_hygiene": len(eligible),
        "hygiene_rejections": rejection_counts,
        "exact_normalized_removed": exact_removed,
        "near_duplicate_removed": near_removed,
        "remaining_after_dedup": len(near_kept),
        "natural_balance_after_filtering": {str(key): value for key, value in natural_balance.items()},
        "sample_balance": {str(key): value for key, value in sorted(Counter(row["source_label"] for row in sampled).items())},
        "sample_size": len(sampled),
        "sampling_seed": SEED,
        "near_duplicate_threshold": NEAR_DUPLICATE_THRESHOLD,
    }


def write_outputs() -> dict[str, Any]:
    HARDENING_DIR.mkdir(parents=True, exist_ok=True)
    v1_rows, v1_normalized = load_v1_reference()
    hardneg_rows, hardneg_stats = build_hardneg_candidates(v1_normalized)
    hf_path, hf_source_mode = choose_hf_path(None)
    sample, hf_stats = build_hf_sample(v1_rows, v1_normalized, hf_path)
    blind_rows = [{"source_row_id": row["source_row_id"], "text": row["text"]} for row in sample]
    write_jsonl(HARDENING_DIR / "real_hardneg_candidates.jsonl", hardneg_rows)
    write_jsonl(HARDENING_DIR / "hf_blind_input_A.jsonl", blind_rows[:BLIND_SIZE])
    write_jsonl(HARDENING_DIR / "hf_blind_input_B.jsonl", blind_rows[BLIND_SIZE:])
    write_jsonl(HARDENING_DIR / "hf_sample_map.jsonl", [
        {key: row[key] for key in ("source_row_id", "source_label", "original_index", "source_config")}
        for row in sample
    ])
    metadata = {
        "source": HF_REPO,
        "source_url": HF_URL,
        "source_config": HF_CONFIG,
        "declared_license": "apache-2.0 (Apache License 2.0)",
        "license_url": "https://www.apache.org/licenses/LICENSE-2.0",
        "upstream_provenance": "The dataset card says texts combines multiple sources; provenance of individual rows is unclear.",
        "hf_source_mode": hf_source_mode,
        "raw_download_path": str(hf_path),
        "raw_download_sha256": sha256_file(hf_path),
        "hard_negative": hardneg_stats,
        "hf": hf_stats,
        "output_sha256": {
            name: sha256_file(HARDENING_DIR / name)
            for name in ("real_hardneg_candidates.jsonl", "hf_blind_input_A.jsonl", "hf_blind_input_B.jsonl", "hf_sample_map.jsonl")
        },
        "script_input_files": [
            str(PUBLIC_DIR / "ucI_sms_spam.jsonl"),
            str(PUBLIC_DIR / "india_spam_sms.jsonl"),
            str(V1_DIR / "train.jsonl"),
            str(V1_DIR / "validation.jsonl"),
            str(hf_path),
        ],
        "forbidden_inputs_not_read": [
            "datasets/processed/trustshield-text-v1/test.jsonl",
            "datasets/holdout/**",
            "datasets/raw/holdout_v2/**",
            "models/evaluation/test*/**",
        ],
    }
    (HARDENING_DIR / "hf_blind_metadata.json").write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return metadata


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hf-path", type=Path, help="explicit texts.json path, primarily for tests/reproduction")
    args = parser.parse_args()
    if args.hf_path is not None:
        global DEFAULT_HF_PATH
        DEFAULT_HF_PATH = args.hf_path
    metadata = write_outputs()
    print(json.dumps(metadata, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
