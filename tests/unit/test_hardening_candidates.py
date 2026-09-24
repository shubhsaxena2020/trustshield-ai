import json

from scripts import prepare_hardening_candidates as hardening


def test_bucket_assignment_is_multi_bucket_and_ordered():
    text = "Your bank OTP expires immediately; delivery tracking and payment refund are due."
    assert hardening.bucket_names(text) == (
        "otp_pin",
        "bank_account",
        "payment",
        "urgent_service",
        "delivery",
    )


def test_hardneg_dedup_against_toy_v1(tmp_path, monkeypatch):
    public_dir = tmp_path / "public"
    public_dir.mkdir()
    row = {
        "id": "uci-new",
        "text": "Bank account payment due",
        "binary_label": "legitimate",
        "category_label": "legitimate",
        "source_type": "public_dataset",
        "source_name": "ucI_sms_spam",
        "synthetic": False,
        "annotation_method": "source-label-unreviewed-ham",
        "notes": "source_row_id=uci-1",
    }
    duplicate = dict(row, id="uci-v1", text="Bank ACCOUNT payment due")
    (public_dir / "ucI_sms_spam.jsonl").write_text(
        json.dumps(row) + "\n" + json.dumps(duplicate) + "\n", encoding="utf-8"
    )
    (public_dir / "india_spam_sms.jsonl").write_text("", encoding="utf-8")
    monkeypatch.setattr(hardening, "PUBLIC_DIR", public_dir)
    keep = {hardening.normalize_for_dedup(duplicate["text"])}
    candidates, stats = hardening.build_hardneg_candidates(keep)
    assert candidates == []
    assert stats["rows"] == 0


def test_blind_rows_have_no_labels_and_mapping_is_consistent():
    sample = [
        {"source_row_id": "hf-texts-000001", "text": "A message", "source_label": 0, "original_index": 1, "source_config": "texts"},
        {"source_row_id": "hf-texts-000002", "text": "A warning", "source_label": 1, "original_index": 2, "source_config": "texts"},
    ]
    blind = [{"source_row_id": row["source_row_id"], "text": row["text"]} for row in sample]
    mapping = [{key: row[key] for key in ("source_row_id", "source_label", "original_index", "source_config")} for row in sample]
    assert all(set(row) == {"source_row_id", "text"} for row in blind)
    assert [row["source_row_id"] for row in blind] == [row["source_row_id"] for row in mapping]
    assert {row["source_row_id"] for row in blind} == {row["source_row_id"] for row in mapping}


def test_hygiene_and_sms_proxy():
    text = hardening.clean_hf_text("  Confirm&nbsp;payment to 123-456-7890  ")
    assert text == "Confirm payment to <PHONE>"
    assert hardening.is_sms_like_english(text)
