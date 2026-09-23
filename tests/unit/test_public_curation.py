from __future__ import annotations

import csv
import json
from pathlib import Path

import scripts.curate_public_datasets as curate


def _audit(path: Path, rows: list[dict]) -> None:
    path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")


def _fixture(root: Path) -> None:
    raw = root / "datasets" / "raw"
    audit = root / "datasets" / "processed" / "public" / "audit"
    (raw / "ucI_sms_spam").mkdir(parents=True)
    (raw / "india_spam_sms").mkdir(parents=True)
    (raw / "ealvaradob_phishing-dataset").mkdir(parents=True)
    (root / "docs").mkdir()
    (raw / "ucI_sms_spam" / "SMSSpamCollection").write_text(
        "spam\tFake prize claim now\nspam\tLegitimate promotion offer\nham\tHello friend\n", encoding="utf-8")
    with (raw / "india_spam_sms" / "spam_ham_india.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=["Label", "Msg"])
        writer.writeheader(); writer.writerows([
            {"Label": "spam", "Msg": "Fake wallet credit pay now"},
            {"Label": "spam", "Msg": "Named retailer discount offer"},
            {"Label": "ham", "Msg": "Hello from work"},
        ])
    audit.mkdir(parents=True)
    for prefix in ("uci", "india"):
        spam_one, spam_two, ham_one = (("uci-000001", "uci-000002", "uci-000003")
                                       if prefix == "uci" else ("india-000002", "india-000003", "india-000004"))
        _audit(audit / f"{prefix}_spam_llm_audit.jsonl", [
            {"source_row_id": spam_one, "verdict": "fraud", "category": "payment_fraud", "confidence": "high", "rationale": "toy fraud"},
            {"source_row_id": spam_two, "verdict": "legit_promo", "category": "legitimate", "confidence": "medium", "rationale": "toy promo"},
        ])
        _audit(audit / f"{prefix}_ham150_llm_audit.jsonl", [
            {"source_row_id": ham_one, "verdict": "legit", "category": "legitimate", "confidence": "high", "rationale": "toy ham"},
        ])


def test_public_curation_is_deterministic_on_toy_data(tmp_path: Path, monkeypatch):
    _fixture(tmp_path)
    monkeypatch.setattr(curate, "ROOT", tmp_path)
    monkeypatch.setattr(curate, "RAW", tmp_path / "datasets" / "raw")
    monkeypatch.setattr(curate, "OUT", tmp_path / "datasets" / "processed" / "public")
    monkeypatch.setattr(curate, "AUDIT", tmp_path / "datasets" / "processed" / "public" / "audit")
    assert curate.main() == 0
    first = {path.relative_to(tmp_path).as_posix(): path.read_bytes() for path in (tmp_path / "datasets" / "processed" / "public").rglob("*.jsonl")}
    assert curate.main() == 0
    second = {path.relative_to(tmp_path).as_posix(): path.read_bytes() for path in (tmp_path / "datasets" / "processed" / "public").rglob("*.jsonl")}
    assert first == second
    rows = [json.loads(line) for line in (tmp_path / "datasets" / "processed" / "public" / "ucI_sms_spam.jsonl").read_text().splitlines()]
    assert rows[0]["annotation_method"] == "llm-audit:antigravity-agy"
