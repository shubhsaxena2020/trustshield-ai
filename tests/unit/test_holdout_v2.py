from __future__ import annotations

import hashlib
import json
import subprocess
import sys
from pathlib import Path

import pytest

from scripts.prepare_holdout_v2 import PREVIOUS_HOLDOUT_SHA256, audit_quality_check, prepare_holdout_v2


RAW_HOLDOUT = Path(__file__).resolve().parents[2] / "datasets" / "raw" / "holdout_v2"
RAW_HOLDOUT_READY = (
    (RAW_HOLDOUT / "codex_holdout2.jsonl").is_file()
    and any((RAW_HOLDOUT / "mendeley_financial_scams" / "extracted").rglob("*.csv"))
    and (RAW_HOLDOUT / "reportsmishing" / "final_dataset_output.csv").is_file()
    and (RAW_HOLDOUT / "reportsmishing" / "LICENSE.txt").is_file()
)
needs_raw_holdout = pytest.mark.skipif(
    not RAW_HOLDOUT_READY,
    reason="RAW_HOLDOUT_ARTIFACTS_MISSING: required private raw source files are absent; no dataset was downloaded.")


def _row(text: str, *, row_id: str, source: str = "toy-synthetic", binary: str = "legitimate", category: str = "legitimate") -> dict:
    synthetic = source == "toy-synthetic"
    return {
        "id": row_id, "text": text, "binary_label": binary, "category_label": category,
        "source_type": "synthetic" if synthetic else "public_dataset", "source_name": source,
        "synthetic": synthetic, "language": "en", "date_added": "2026-09-24",
    }


def _write_jsonl(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")


def _write_v1(path: Path, rows: list[dict] | None = None) -> None:
    path.mkdir(parents=True)
    for split in ("train", "validation", "test"):
        _write_jsonl(path / f"{split}.jsonl", (rows or []) if split == "train" else [])


def _toy_audit(rows: list[dict]) -> list[dict]:
    return [{"source_row_id": row["id"], "verdict": verdict, "category": category, "confidence": "high", "rationale": rationale}
            for row, verdict, category, rationale in rows]


def _write_blind_audit(out: Path, audit_rows: list[dict], candidates: list[dict]) -> None:
    _write_jsonl(out / "audit_blind" / "blind_audit_agy.jsonl", audit_rows)
    _write_jsonl(out / "audit_blind" / "blind_input.jsonl", [{"source_row_id": row["id"], "text": row["text"]} for row in candidates])


def test_templated_audit_fails_quality_checks() -> None:
    audit = [{"source_row_id": f"toy-{index}", "rationale": 'Quote "a ... b"; account lure signals phishing (id)'} for index in range(5)]
    messages = {f"toy-{index}": f"ordinary note number {index}" for index in range(5)}
    with pytest.raises(ValueError, match="distinct rationale"):
        audit_quality_check(audit, messages)


def test_genuine_toy_audit_passes_quality_checks() -> None:
    audit = [
        {"source_row_id": "toy-1", "rationale": "Bank alert describes an account review request (toy-1)"},
        {"source_row_id": "toy-2", "rationale": "Parcel notice requests a delivery fee (toy-2)"},
        {"source_row_id": "toy-3", "rationale": "Prize message promises a lottery reward (toy-3)"},
        {"source_row_id": "toy-4", "rationale": "Credential prompt requests an OTP code (toy-4)"},
    ]
    messages = {
        "toy-1": "Your bank account review is ready.", "toy-2": "The parcel delivery fee is due.",
        "toy-3": "You won a lottery prize reward.", "toy-4": "Provide the OTP code to continue.",
    }
    summary = audit_quality_check(audit, messages)
    assert summary["rows"] == 4
    assert summary["distinct_rationale_share_after_first3_parenthetical"] == 1.0
    assert summary["content_word_overlap_share"] == 1.0
    assert summary["max_sentence_reuse"] == 1


@needs_raw_holdout
def test_blind_audit_filters_disagreement_and_exclude_and_uses_audit_category(tmp_path: Path):
    v1 = tmp_path / "v1"
    _write_v1(v1)
    candidates = [
        _row("Bank account review requests an OTP code", row_id="real-fraud", source="real-source", binary="malicious_or_fraudulent", category="phishing"),
        _row("A normal family update arrives today", row_id="real-legit", source="real-source"),
        _row("Prize notice requests a payment", row_id="real-disagree", source="real-source", binary="malicious_or_fraudulent", category="phishing"),
        _row("Corrupted source message", row_id="real-exclude", source="real-source", binary="malicious_or_fraudulent", category="phishing"),
        _row("Synthetic account reminder", row_id="synthetic-1"),
    ]
    candidate_path = tmp_path / "candidates.jsonl"
    _write_jsonl(candidate_path, candidates)
    out = tmp_path / "out"
    audit = _toy_audit([
        (candidates[0], "fraud", "credential_theft", "Bank alert requests an OTP code from the account holder."),
        (candidates[1], "legit", "legitimate", "Family update describes a normal personal conversation."),
        (candidates[2], "legit", "legitimate", "Prize message asks for payment but is judged legitimate here."),
        (candidates[3], "exclude", "corrupted", "Corrupted message cannot be reliably interpreted."),
    ])
    _write_blind_audit(out, audit, candidates[:4])
    prepare_holdout_v2([candidate_path], v1, out)
    rows = [json.loads(line) for line in (out / "holdout.jsonl").read_text(encoding="utf-8").splitlines()]
    by_id = {row["id"]: row for row in rows}
    assert set(by_id) == {"real-fraud", "real-legit", "synthetic-1"}
    assert by_id["real-fraud"]["category_label"] == "credential_theft"
    assert by_id["real-fraud"]["annotation_method"] == "llm-audit-blind:antigravity-agy"
    assert by_id["real-fraud"]["audit_confidence"] == "high"
    assert "blind_rationale_id=real-fraud" in by_id["real-fraud"]["notes"]
    manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["excluded_source_label_disagreement"] == ["real-disagree"]
    assert manifest["excluded_audit_verdict"]["corrupted"] == ["real-exclude"]


@needs_raw_holdout
def test_within_holdout_exact_then_normalized_dedupe_records_ids(tmp_path: Path):
    v1 = tmp_path / "v1"
    _write_v1(v1)
    candidates = [
        _row("Payment account 123 notice", row_id="synthetic-1"),
        _row("Payment account 123 notice", row_id="synthetic-2"),
        _row("payment account 456 notice", row_id="synthetic-3"),
        _row("A separate message", row_id="synthetic-4"),
    ]
    candidate_path = tmp_path / "candidates.jsonl"
    _write_jsonl(candidate_path, candidates)
    out = tmp_path / "out"
    prepare_holdout_v2([candidate_path], v1, out)
    manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["isolation"]["within_holdout_deduplication"]["removal_counts"] == {"exact_duplicate": 1, "normalized_duplicate": 1}
    assert manifest["isolation"]["within_holdout_deduplication"]["removed_ids"] == {"exact_duplicate": ["synthetic-2"], "normalized_duplicate": ["synthetic-3"]}
    rows = [json.loads(line) for line in (out / "holdout.jsonl").read_text(encoding="utf-8").splitlines()]
    assert [row["id"] for row in rows] == ["synthetic-1", "synthetic-4"]


def test_builder_refuses_failing_active_audit(tmp_path: Path):
    v1 = tmp_path / "v1"
    _write_v1(v1)
    candidates = [_row(f"Account notice {index}", row_id=f"real-{index}", source="real-source", binary="malicious_or_fraudulent", category="phishing") for index in range(5)]
    candidate_path = tmp_path / "candidates.jsonl"
    _write_jsonl(candidate_path, candidates)
    out = tmp_path / "out"
    bad_audit = [{"source_row_id": row["id"], "verdict": "fraud", "category": "phishing", "confidence": "high", "rationale": 'Quote "a ... b"; account lure signals phishing (id)'} for row in candidates]
    _write_blind_audit(out, bad_audit, candidates)
    with pytest.raises(ValueError, match="distinct rationale"):
        prepare_holdout_v2([candidate_path], v1, out)


@needs_raw_holdout
def test_manifest_has_new_supersession_chain_and_rejected_audit_hashes(tmp_path: Path):
    v1 = tmp_path / "v1"
    _write_v1(v1)
    candidate = _row("A synthetic message", row_id="synthetic-1")
    candidate_path = tmp_path / "candidates.jsonl"
    _write_jsonl(candidate_path, [candidate])
    out = tmp_path / "out"
    old_supersedes = {"manifest_sha256_of_previous_seal": "older-manifest", "previous_holdout_sha256": "older-holdout", "reason": "older reason"}
    (out / "manifest.json").parent.mkdir(parents=True)
    (out / "manifest.json").write_text(json.dumps({"supersedes": old_supersedes}) + "\n", encoding="utf-8")
    rejected = out / "audit_rejected_templated" / "legacy.jsonl"
    rejected.parent.mkdir(parents=True)
    rejected.write_text('{"rationale":"rejected"}\n', encoding="utf-8")
    prepare_holdout_v2([candidate_path], v1, out)
    manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["supersedes"]["previous_holdout_sha256"] == PREVIOUS_HOLDOUT_SHA256
    assert manifest["supersedes"]["reason"] == "previous seal used templated (gamed) audits and trusted source labels of reported-smishing rows; it was never accessed (access log empty)"
    assert manifest["supersession_history"] == [old_supersedes]
    entry = manifest["rejected_audits"][0]
    assert entry["path"] == "audit_rejected_templated/legacy.jsonl"
    assert entry["sha256"] == hashlib.sha256(rejected.read_bytes()).hexdigest()


@needs_raw_holdout
def test_holdout_manifest_contains_audit_hashes_and_quality_summary(tmp_path: Path):
    v1 = tmp_path / "v1"
    _write_v1(v1)
    candidate = _row("Bank account review is ready", row_id="real-1", source="real-source", binary="malicious_or_fraudulent", category="phishing")
    candidate_path = tmp_path / "candidates.jsonl"
    _write_jsonl(candidate_path, [candidate])
    out = tmp_path / "out"
    audit = _toy_audit([(candidate, "fraud", "credential_theft", "Bank alert requests an account review code.")])
    _write_blind_audit(out, audit, [candidate])
    prepare_holdout_v2([candidate_path], v1, out)
    manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    assert set(manifest["audit_files"]) == {"audit_blind/blind_input.jsonl", "audit_blind/blind_audit_agy.jsonl"}
    summary = manifest["audit_summary"]["blind_audit_agy.jsonl"]
    assert summary["content_word_overlap_count"] == 1
    assert summary["distinct_rationale_share_after_first3_parenthetical"] == 1.0


def test_holdout_guard_requires_confirmation_and_logs_access(tmp_path: Path):
    holdout = tmp_path / "holdout.jsonl"
    model = tmp_path / "model.bin"
    log = tmp_path / "access.json"
    holdout.write_text('{"text":"toy"}\n', encoding="utf-8")
    model.write_bytes(b"toy-model")
    script = Path("scripts/evaluate_holdout_v2.py")
    plain = [sys.executable, str(script), "--holdout-v2", str(holdout), "--model-artifact", str(model), "--access-log", str(log)]
    spent = subprocess.run(plain + ["--confirm-final-holdout"], capture_output=True, text=True, check=False)
    assert spent.returncode == 3 and "v2 is spent" in spent.stderr and not log.exists()
    base = plain + ["--archival-override-v2-spent"]
    refused = subprocess.run(base, capture_output=True, text=True, check=False)
    assert refused.returncode != 0
    assert not log.exists()
    allowed = subprocess.run(base + ["--confirm-final-holdout"], capture_output=True, text=True, check=False)
    assert allowed.returncode == 0
    entries = json.loads(log.read_text(encoding="utf-8"))
    assert len(entries) == 1
    assert entries[0]["holdout_sha256"]
    assert entries[0]["model_artifact_sha256"]
    assert "access count: 1" in allowed.stdout.lower()
