"""Validate, deduplicate, group and split TrustShield text records.

The implementation deliberately keeps the test split out of every model-training
script.  This script is the only place that assigns split values.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import logging
import re
import unicodedata
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.neighbors import NearestNeighbors

SEED = 42
NEAR_DUPLICATE_THRESHOLD = 0.85
NEAR_THRESHOLDS = (0.70, 0.80, 0.85, 0.90)
SCHEMA_VERSION = "trustshield-text-v1"
VALID_BINARY = {"legitimate", "malicious_or_fraudulent"}
VALID_CATEGORIES = {
    "legitimate", "phishing", "credential_theft", "payment_fraud", "fake_job",
    "investment_scam", "impersonation", "digital_arrest", "delivery_scam",
    "prize_lottery_scam", "other_fraud", "unlabeled",
}
VALID_SOURCE_TYPES = {"public_dataset", "synthetic", "manually_curated", "legitimate_control"}
URL_RE = re.compile(r"(?i)\b(?:https?://|www\.)[^\s<>\"']+|\b[a-z0-9-]+(?:\.[a-z0-9-]+)+(?:/[^\s<>\"']*)?")
AMOUNT_RE = re.compile(r"(?i)(?:[$€£₹]|\b(?:rs|inr|usd|eur|gbp)\.?\s*)\s*\d[\d,]*(?:\.\d+)?")
_logger = logging.getLogger(__name__)


def normalize_for_dedup(text: str) -> str:
    """Schema normalization: lowercase, URL placeholder, digits to zero, whitespace collapse."""
    value = unicodedata.normalize("NFKC", text).lower()
    value = URL_RE.sub(" <url> ", value)
    value = re.sub(r"\d", "0", value)
    return re.sub(r"\s+", " ", value).strip()


def template_signature(text: str, mode: str = "legacy") -> str:
    """Return a legacy or strict template signature for future dataset grouping."""
    return _template_signature(text, mode)


def _template_signature(text: str, mode: str) -> str:
    if mode not in {"legacy", "strict"}:
        raise ValueError("template signature mode must be legacy or strict")
    value = unicodedata.normalize("NFKC", text)
    value = URL_RE.sub(" <url> ", value)
    value = AMOUNT_RE.sub(" <amount> ", value)
    value = re.sub(r"\b\d+(?:[.,]\d+)?\b", "<number>", value)
    if mode == "strict":
        value = re.sub(r"\b[A-Za-z]+\s+\d+[A-Za-z]+\b", "<reference>", value)
        value = re.sub(r"\b[A-Za-z]\d+\b|\b\d+[A-Za-z]+\b", "<reference>", value)
        value = re.sub(r"\d{6,}|[xX]{6,}", "<reference>", value)
        value = re.sub(r"\b(?=[A-Za-z0-9]*[A-Za-z])(?=[A-Za-z0-9]*\d)[A-Za-z0-9]+\b", "<reference>", value)
    value = re.sub(r"\b[A-Z][a-z]{2,}\b", "<entity>", value)
    if mode == "strict":
        value = re.sub(r"[^\w\s<>]", " ", value)
    return re.sub(r"\s+", " ", value.lower()).strip()


def _source_short(source_name: str) -> str:
    value = re.sub(r"[^a-zA-Z0-9]+", "-", source_name).strip("-").lower()
    return (value or "source")[:24]


def _record_id(row: dict) -> str:
    digest = hashlib.sha1(normalize_for_dedup(row["text"]).encode("utf-8")).hexdigest()[:8]
    return f"{_source_short(row['source_name'])}-{digest}"


def validate_record(raw: dict, line_number: int = 0) -> tuple[dict | None, list[str]]:
    """Return a normalized record or reasons for rejection."""
    if not isinstance(raw, dict):
        return None, [f"line {line_number}: row is not an object"]
    reasons: list[str] = []
    required = ("text", "binary_label", "category_label", "source_type", "source_name", "synthetic")
    for key in required:
        if key not in raw:
            reasons.append(f"missing {key}")
    if reasons:
        return None, reasons
    if not isinstance(raw["text"], str) or not raw["text"].strip():
        reasons.append("text must be a non-empty string")
    if raw["binary_label"] not in VALID_BINARY:
        reasons.append("invalid binary_label")
    if raw["category_label"] not in VALID_CATEGORIES:
        reasons.append("invalid category_label")
    if raw["source_type"] not in VALID_SOURCE_TYPES:
        reasons.append("invalid source_type")
    if not isinstance(raw["source_name"], str) or not raw["source_name"].strip():
        reasons.append("source_name must be a non-empty string")
    if not isinstance(raw["synthetic"], bool):
        reasons.append("synthetic must be boolean")
    if raw.get("language", "en") != "en":
        reasons.append("language must be en")
    if raw["binary_label"] == "legitimate" and raw["category_label"] != "legitimate":
        reasons.append("legitimate binary label requires legitimate category")
    if raw["category_label"] == "legitimate" and raw["binary_label"] != "legitimate":
        reasons.append("legitimate category requires legitimate binary label")
    if raw["category_label"] == "unlabeled" and not (
        raw["source_type"] == "public_dataset" and raw["binary_label"] == "malicious_or_fraudulent"
    ):
        reasons.append("unlabeled is only allowed for malicious public_dataset rows")
    if raw["source_type"] == "synthetic" and raw["synthetic"] is not True:
        reasons.append("synthetic source_type requires synthetic=true")
    if raw["source_type"] in {"public_dataset", "manually_curated"} and raw["synthetic"] is not False:
        reasons.append(f"{raw['source_type']} requires synthetic=false")
    if raw["source_type"] == "legitimate_control" and raw["binary_label"] != "legitimate":
        reasons.append("legitimate_control requires legitimate binary label")
    if reasons:
        return None, reasons
    row = dict(raw)
    row["text"] = raw["text"].strip()
    row["language"] = "en"
    row["notes"] = str(raw.get("notes", ""))
    row["date_added"] = str(raw.get("date_added") or datetime.now(timezone.utc).date().isoformat())
    row["id"] = str(raw.get("id") or _record_id(row))
    row["split"] = ""
    row["duplicate_group"] = ""
    return row, []


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


class _UnionFind:
    def __init__(self, size: int):
        self.parent = list(range(size))

    def find(self, value: int) -> int:
        while self.parent[value] != value:
            self.parent[value] = self.parent[self.parent[value]]
            value = self.parent[value]
        return value

    def union(self, first: int, second: int) -> None:
        left, right = self.find(first), self.find(second)
        if left != right:
            self.parent[right] = left


def _pairwise_cosines(matrix, threshold: float) -> Iterable[tuple[int, int, float]]:
    """Yield upper-triangle neighbours in sparse blocks, avoiding a dense n*n matrix."""
    block_size = 512
    for start in range(0, matrix.shape[0], block_size):
        block = matrix[start:start + block_size]
        neighbours = (block @ matrix.T).tocoo()
        for row, col, value in zip(neighbours.row, neighbours.col, neighbours.data):
            left, right = start + int(row), int(col)
            if right > left and value >= threshold:
                yield left, right, float(value)


def _near_groups(rows: list[dict], near_dup_threshold: float = NEAR_DUPLICATE_THRESHOLD,
                 template_signature_mode: str = "legacy") -> tuple[dict[str, list[str]], dict]:
    if not rows:
        return {}, {"pair_counts": {str(t): 0 for t in NEAR_THRESHOLDS}, "groups": 0, "cross_source_pairs": 0}
    texts = [row["text"] for row in rows]
    vectorizer = TfidfVectorizer(analyzer="char", ngram_range=(3, 5), min_df=1, sublinear_tf=True)
    matrix = vectorizer.fit_transform(texts)
    uf = _UnionFind(len(rows))
    pair_counts = {}
    pair_values: list[tuple[int, int, float]] = []
    reported_thresholds = sorted(set(NEAR_THRESHOLDS + (near_dup_threshold,)))
    for threshold in reported_thresholds:
        pairs = list(_pairwise_cosines(matrix, threshold))
        pair_counts[str(threshold)] = len(pairs)
        if threshold == near_dup_threshold:
            pair_values = pairs
    for left, right, _ in pair_values:
        uf.union(left, right)
    by_template: dict[str, list[int]] = defaultdict(list)
    for index, row in enumerate(rows):
        by_template[_template_signature(row["text"], template_signature_mode)].append(index)
    for indexes in by_template.values():
        for index in indexes[1:]:
            uf.union(indexes[0], index)
    roots: dict[int, list[int]] = defaultdict(list)
    for index in range(len(rows)):
        roots[uf.find(index)].append(index)
    ordered = sorted(roots.values(), key=lambda indexes: min(rows[i]["id"] for i in indexes))
    groups: dict[str, list[str]] = {}
    group_by_index: dict[int, str] = {}
    for number, indexes in enumerate(ordered, 1):
        group_id = f"dg-{number:06d}"
        groups[group_id] = sorted(rows[i]["id"] for i in indexes)
        for index in indexes:
            group_by_index[index] = group_id
    cross_source = sum(
        1 for left, right, _ in pair_values if rows[left]["source_type"] != rows[right]["source_type"]
    )
    return groups, {
        "pair_counts": pair_counts,
        "groups": len(groups),
        "cross_source_pairs": cross_source,
        "template_signatures": len(by_template), "template_signature_mode": template_signature_mode,
        "near_duplicate_threshold": near_dup_threshold,
        "group_by_index": group_by_index,
    }


def _choose_splits(rows: list[dict], groups: dict[str, list[str]]) -> dict[str, str]:
    row_by_id = {row["id"]: row for row in rows}
    group_items = [(group_id, ids) for group_id, ids in groups.items()]
    rng = np.random.default_rng(SEED)
    random_key = {group_id: float(rng.random()) for group_id, _ in group_items}
    holdout = {group_id for group_id, ids in group_items if any(row_by_id[i]["source_name"].startswith("holdout-") for i in ids)}
    total = len(rows)
    targets = {"test": round(total * 0.15), "validation": round(total * 0.15), "train": total - round(total * 0.15) * 2}
    assigned = {"train": 0, "validation": 0, "test": 0}
    strata = sorted({(row["binary_label"], row["category_label"]) for row in rows})
    assigned_strata = {split: Counter() for split in ("train", "validation", "test")}
    fractions = {"train": 0.70, "validation": 0.15, "test": 0.15}
    total_strata = Counter((row["binary_label"], row["category_label"]) for row in rows)
    target_strata = {split: {stratum: total_strata[stratum] * fractions[split] for stratum in strata}
                     for split in fractions}
    selected: dict[str, str] = {}
    for group_id in sorted(holdout):
        selected[group_id] = "test"
        assigned["test"] += len(groups[group_id])
        assigned_strata["test"].update((row_by_id[row_id]["binary_label"], row_by_id[row_id]["category_label"]) for row_id in groups[group_id])
    remaining = [(group_id, ids) for group_id, ids in group_items if group_id not in holdout]
    remaining.sort(key=lambda item: (-len(item[1]), random_key[item[0]], item[0]))
    for group_id, ids in remaining:
        size = len(ids)
        group_strata = Counter((row_by_id[row_id]["binary_label"], row_by_id[row_id]["category_label"]) for row_id in ids)
        choices = []
        for split in ("test", "validation", "train"):
            new_total = assigned[split] + size
            overflow = max(0, new_total - targets[split])
            new_strata = assigned_strata[split].copy()
            new_strata.update(group_strata)
            # Greedy minimum deficit keeps each (binary, category) stratum close to its target.
            deficit = sum(max(0.0, target_strata[split][stratum] - new_strata[stratum]) ** 2
                          for stratum in strata)
            over_strata = sum(max(0.0, new_strata[stratum] - target_strata[split][stratum]) ** 2
                              for stratum in strata)
            priority = (overflow > 0, deficit + 0.05 * over_strata, abs(new_total - targets[split]), 0 if split != "train" else 1)
            choices.append((priority, split))
        chosen = min(choices)[1]
        selected[group_id] = chosen
        assigned[chosen] += size
        assigned_strata[chosen].update(group_strata)
    return {row_id: selected[group_id] for group_id, ids in groups.items() for row_id in ids}


def _counts(rows: list[dict]) -> dict:
    dimensions = ("binary_label", "category_label", "source_type", "source_name", "synthetic")
    return {dimension: dict(Counter(str(row[dimension]) for row in rows)) for dimension in dimensions}


def assert_zero_leakage(split_rows: dict[str, list[dict]]) -> None:
    """Assert normalized text, duplicate groups, and holdout authors do not leak."""
    for left, right in (("train", "validation"), ("train", "test"), ("validation", "test")):
        left_rows, right_rows = split_rows[left], split_rows[right]
        assert not ({row["duplicate_group"] for row in left_rows} & {row["duplicate_group"] for row in right_rows}), f"duplicate-group leakage: {left}/{right}"
        assert not ({normalize_for_dedup(row["text"]) for row in left_rows} & {normalize_for_dedup(row["text"]) for row in right_rows}), f"normalized-text leakage: {left}/{right}"
    assert all(not row["source_name"].startswith("holdout-") or row["split"] == "test" for row in split_rows["test"])
    assert all(not row["source_name"].startswith("holdout-") for row in split_rows["train"] + split_rows["validation"])


def _write_jsonl(path: Path, rows: list[dict]) -> None:
    path.write_text("".join(json.dumps(row, sort_keys=True, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")


def _input_files(input_dir: Path | None, extra: list[Path] | None) -> list[Path]:
    if input_dir is None:
        roots = [Path("datasets/processed/public"), Path("datasets/raw/synthetic")]
        files = [path for root in roots if root.exists() for path in root.glob("*.jsonl")]
    else:
        files = sorted(input_dir.rglob("*.jsonl")) if input_dir.exists() else []
    files.extend(path for path in (extra or []) if path.exists())
    return sorted(set(path.resolve() for path in files))


def parse_caps(values: list[str] | None) -> dict[tuple[str, str], int]:
    """Parse ``source_name:category=limit`` caps before deduplication/grouping."""
    caps: dict[tuple[str, str], int] = {}
    for value in values or []:
        try:
            key, raw_limit = value.split("=", 1)
            source_name, category = key.split(":", 1)
            limit = int(raw_limit)
        except ValueError as exc:
            raise ValueError(f"invalid --cap {value!r}; expected source_name:category=non-negative_integer") from exc
        if not source_name or not category or limit < 0:
            raise ValueError(f"invalid --cap {value!r}; expected source_name:category=non-negative_integer")
        if (source_name, category) in caps:
            raise ValueError(f"duplicate --cap for {source_name}:{category}")
        caps[(source_name, category)] = limit
    return caps


def apply_caps(rows: list[dict], caps: dict[tuple[str, str], int], seed: int = SEED) -> tuple[list[dict], dict[str, int], dict[str, int]]:
    """Uniformly subsample capped non-fraud rows before deduplication/grouping."""
    pre_counts = Counter(f"{row['source_name']}:{row['category_label']}" for row in rows)
    keep_ids = {row["id"] for row in rows}
    rng = np.random.default_rng(seed)
    post_counts = Counter(pre_counts)
    ordered = sorted(rows, key=lambda row: row["id"])
    for (source_name, category), limit in sorted(caps.items()):
        key = f"{source_name}:{category}"
        matching = [row for row in ordered if row["source_name"] == source_name and row["category_label"] == category]
        candidates = [row for row in matching if row["binary_label"] != "malicious_or_fraudulent"]
        if len(candidates) <= limit:
            post_counts[key] = len(matching)
            continue
        selected_indexes = set(rng.choice(len(candidates), size=limit, replace=False).tolist())
        for index, row in enumerate(candidates):
            if index not in selected_indexes:
                keep_ids.discard(row["id"])
        post_counts[key] = limit + len(matching) - len(candidates)
    return [row for row in rows if row["id"] in keep_ids], dict(sorted(pre_counts.items())), dict(sorted(post_counts.items()))


def prepare_dataset(input_dir: str | Path | None = None, output_dir: str | Path | None = None,
                    extra: list[str | Path] | None = None, caps: list[str] | None = None,
                    near_dup_threshold: float = NEAR_DUPLICATE_THRESHOLD,
                    template_signature_mode: str = "legacy") -> dict:
    output = Path(output_dir or "datasets/processed/trustshield-text-v1")
    output.mkdir(parents=True, exist_ok=True)
    files = _input_files(Path(input_dir) if input_dir is not None else None, [Path(p) for p in (extra or [])])
    rejected: list[dict] = []
    accepted: list[dict] = []
    for path in files:
        with path.open(encoding="utf-8") as handle:
            for line_number, line in enumerate(handle, 1):
                try:
                    raw = json.loads(line)
                except json.JSONDecodeError as exc:
                    rejected.append({"file": str(path), "line": line_number, "reasons": [f"invalid JSON: {exc.msg}"]})
                    continue
                row, reasons = validate_record(raw, line_number)
                if row is None:
                    rejected.append({"file": str(path), "line": line_number, "reasons": reasons})
                else:
                    row["_input_file"] = str(path)
                    accepted.append(row)
    parsed_caps = parse_caps(caps)
    accepted_before_caps = len(accepted)
    accepted, pre_cap_counts, post_cap_counts = apply_caps(accepted, parsed_caps)
    by_normalized: dict[str, list[dict]] = defaultdict(list)
    for row in accepted:
        by_normalized[normalize_for_dedup(row["text"])].append(row)
    removed_ids: list[str] = []
    conflicts: list[dict] = []
    survivors: list[dict] = []
    for normalized, records in sorted(by_normalized.items()):
        labels = {(row["binary_label"], row["category_label"]) for row in records}
        if len(labels) > 1:
            conflicts.append({"normalized_text": normalized, "ids": sorted(row["id"] for row in records), "labels": sorted(labels)})
            removed_ids.extend(row["id"] for row in records)
            continue
        ranked = sorted(records, key=lambda row: (-sum(bool(row.get(key)) for key in ("notes", "date_added", "id", "source_name")), row["id"]))
        survivors.append(ranked[0])
        removed_ids.extend(row["id"] for row in ranked[1:])
    survivors.sort(key=lambda row: row["id"])
    groups, near_report = _near_groups(survivors, near_dup_threshold, template_signature_mode)
    split_by_id = _choose_splits(survivors, groups)
    for row in survivors:
        row["duplicate_group"] = next(group_id for group_id, ids in groups.items() if row["id"] in ids)
        row["split"] = split_by_id[row["id"]]
        row.pop("_input_file", None)
    all_rows = sorted(survivors, key=lambda row: row["id"])
    split_rows = {split: sorted([row for row in all_rows if row["split"] == split], key=lambda row: row["id"])
                  for split in ("train", "validation", "test")}
    assert_zero_leakage(split_rows)
    _write_jsonl(output / "all.jsonl", all_rows)
    for split, rows in split_rows.items():
        _write_jsonl(output / f"{split}.jsonl", rows)
    (output / "duplicate_groups.json").write_text(json.dumps(groups, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    under_supported = {}
    for category in sorted(VALID_CATEGORIES - {"unlabeled"}):
        under_supported[category] = {
            "train": sum(row["category_label"] == category for row in split_rows["train"]),
            "validation": sum(row["category_label"] == category for row in split_rows["validation"]),
            "test": sum(row["category_label"] == category for row in split_rows["test"]),
        }
    report_lines = [
        "# TrustShield dataset preparation report", "", f"Near-duplicate threshold selected: {near_dup_threshold:.2f}",
        "TF-IDF character n-grams: analyzer=char, range=3-5, sublinear_tf=true; sparse block neighbour search.",
        f"Template signature mode: {template_signature_mode}.",
        "Legacy masks URLs, amounts, numbers and capitalised entity-like tokens. Strict additionally strips punctuation and masks letter-digit/digit-letter references, mixed alphanumeric reference codes, and runs of digits or x/X of length >=6.", "",
        "## Near-duplicate transparency", "", "| cosine threshold | pairs |", "|---:|---:|",
    ]
    report_lines += [f"| {threshold:.2f} | {near_report['pair_counts'].get(str(threshold), 0)} |" for threshold in sorted(set(NEAR_THRESHOLDS + (near_dup_threshold,)))]
    report_lines += ["", f"Union-find groups: {near_report['groups']}", f"Cross-source near-duplicate pairs at selected threshold: {near_report['cross_source_pairs']}",
                     f"Template signatures: {near_report['template_signatures']}", "", "## Exact/normalized dedup", "",
                     f"Accepted after schema validation: {accepted_before_caps}", f"Accepted after caps: {len(accepted)}", f"Survivors: {len(survivors)}", f"Removed ids: {len(removed_ids)}", f"Cross-label conflicts dropped: {len(conflicts)}", "",
                     "## Under-supported categories", "", "Counts are before category-model merging.", ""]
    for category, counts in under_supported.items():
        if counts["train"] < 30 or counts["validation"] < 8 or counts["test"] < 8:
            report_lines.append(f"- `{category}`: train={counts['train']}, validation={counts['validation']}, test={counts['test']} (under-supported)")
    report_lines += ["", "## Removed ids", ""] + [f"- `{value}`" for value in sorted(removed_ids)]
    report_lines += ["", "## Cross-label conflicts", ""] + [f"- `{item['normalized_text']}`: {', '.join(item['ids'])}" for item in conflicts]
    (output / "dedup_report.md").write_text("\n".join(report_lines) + "\n", encoding="utf-8")
    files_written = [output / name for name in ("all.jsonl", "train.jsonl", "validation.jsonl", "test.jsonl", "duplicate_groups.json", "dedup_report.md")]
    manifest = {
        "dataset_version": SCHEMA_VERSION, "created": datetime.now(timezone.utc).date().isoformat(), "seed": SEED,
        "thresholds": {"near_duplicate_cosine": near_dup_threshold, "reported": sorted(set(NEAR_THRESHOLDS + (near_dup_threshold,)))},
        "counts": {"overall": _counts(all_rows), "splits": {split: _counts(rows) for split, rows in split_rows.items()}},
        "input_files": [{"path": str(path), "sha256": _sha256(path)} for path in files],
        "rejected_rows": len(rejected), "rejected_row_details": rejected,
        "removed_ids": sorted(removed_ids), "cross_label_conflicts": conflicts,
        "caps": {f"{source}:{category}": limit for (source, category), limit in sorted(parsed_caps.items())},
        "pre_cap_counts": pre_cap_counts, "post_cap_counts": post_cap_counts,
        "accepted_before_caps": accepted_before_caps, "accepted_after_caps": len(accepted),
        "under_supported_categories": under_supported,
        "sha256": {path.name: _sha256(path) for path in files_written},
        "ordered_concatenation_sha256": hashlib.sha256((output / "all.jsonl").read_bytes()).hexdigest(),
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return {"dataset_version": SCHEMA_VERSION, "rejected_rows": len(rejected), "rows": len(all_rows), "output_dir": str(output)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--extra", type=Path, action="append", default=[])
    parser.add_argument("--cap", action="append", default=[], metavar="SOURCE:CATEGORY=LIMIT",
                        help="Uniformly subsample this non-fraud category before dedup/grouping; seed is 42 and all fraud rows are kept.")
    parser.add_argument("--near-dup-threshold", type=float, default=NEAR_DUPLICATE_THRESHOLD)
    parser.add_argument("--template-signature", choices=("legacy", "strict"), default="legacy")
    import sys as _sys
    from pathlib import Path as _Path
    _sys.path.insert(0, str(_Path(__file__).resolve().parents[1]))
    from scripts._v2_spent_guard import require_archival_override
    args = parser.parse_args(require_archival_override(None))
    print(json.dumps(prepare_dataset(args.input_dir, args.output_dir, args.extra, args.cap, args.near_dup_threshold, args.template_signature), indent=2))


if __name__ == "__main__":
    main()
