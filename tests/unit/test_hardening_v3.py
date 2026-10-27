import inspect
import json
from pathlib import Path

import pytest

V3_ARTIFACT = Path(__file__).resolve().parents[2] / "models" / "trained" / "trustshield_text_v3.joblib"
MODEL_REQUIRED = pytest.mark.skipif(
    not V3_ARTIFACT.is_file(),
    reason="MODEL_ARTIFACT_MISSING: trustshield_text_v3.joblib is required; no model was downloaded.",
)


def test_message_likeness_filter_only_targets_news_like_legitimate_rows():
    from scripts.prepare_dataset_v3 import looks_like_non_message_hf_legitimate

    assert looks_like_non_message_hf_legitimate("URL: https://example.invalid", "")
    assert looks_like_non_message_hf_legitimate("A short item", "news report")
    assert not looks_like_non_message_hf_legitimate("Your bank OTP is 1234", "transaction alert")


def test_blind_audit_agreement_filter():
    from scripts.prepare_dataset_v3 import blind_audit_agrees

    assert blind_audit_agrees(1, "fraud")
    assert blind_audit_agrees(0, "legit")
    assert not blind_audit_agrees(1, "legit")
    assert not blind_audit_agrees(0, "fraud")
    assert not blind_audit_agrees(1, "exclude")


def test_templated_audit_is_rejected():
    from scripts.prepare_dataset_v3 import audit_quality_check

    inputs = [{"source_row_id": f"x-{index}", "text": f"Message {index}"} for index in range(3)]
    audits = [{"source_row_id": f"x-{index}", "rationale": "same rationale", "confidence": "high"} for index in range(3)]
    with pytest.raises(ValueError, match="templated"):
        audit_quality_check(inputs, audits)


def test_v3_drop_rules_remove_validation_duplicates():
    from scripts.prepare_dataset_v3 import _dedup_new_candidates

    base = {"id": "train-1", "text": "Bank alert 123", "binary_label": "legitimate", "category_label": "legitimate"}
    validation = [{"id": "val-1", "text": "Validation message", "duplicate_group": "v1", "binary_label": "legitimate", "category_label": "legitimate"}]
    candidates = [dict(base, id="new-1"), dict(base, id="new-2", text="validation MESSAGE")]
    kept, reasons = _dedup_new_candidates([base], validation, candidates)
    assert kept == []
    assert reasons["duplicate_v1_train_exact"] == 1
    assert reasons["duplicate_validation_normalized"] == 1


def test_holdout_filter_returns_counts_without_holdout_text(tmp_path):
    from scripts.prepare_dataset_v3 import filter_candidates_against_holdout_texts

    sealed_text = "sealed holdout phrase never returned"
    path = tmp_path / "holdout.jsonl"
    path.write_text(json.dumps({"text": sealed_text, "binary_label": "malicious_or_fraudulent"}) + "\n", encoding="utf-8")
    candidates = [{"id": "c1", "text": "ordinary candidate message", "binary_label": "legitimate", "category_label": "legitimate"}]
    kept, stats = filter_candidates_against_holdout_texts(candidates, path)
    assert kept == candidates
    assert stats["holdout_rows_read"] == 1
    assert sealed_text not in json.dumps(stats)


def test_validation_copy_is_byte_identical(tmp_path):
    from scripts.prepare_dataset_v3 import validation_copy_is_byte_identical

    source, output = tmp_path / "validation.jsonl", tmp_path / "v3-validation.jsonl"
    source.write_bytes(b'{"id":"a"}\n')
    output.write_bytes(source.read_bytes())
    assert validation_copy_is_byte_identical(source, output)
    output.write_bytes(b'{"id":"a"}\r\n')
    assert not validation_copy_is_byte_identical(source, output)


def test_eval_dir_options_are_isolated_and_explicit():
    from scripts import evaluate_model, evaluate_fusion, fusion_oof_predictions, train_classifier

    assert "eval_dir" in inspect.signature(train_classifier.train).parameters
    assert "eval_dir" in inspect.signature(evaluate_model.evaluate).parameters
    assert "eval_dir" in inspect.signature(evaluate_fusion.evaluate).parameters
    assert "output_dir" in inspect.signature(fusion_oof_predictions.generate).parameters


def test_classifier_env_override(monkeypatch, tmp_path):
    from services.analysis.ml.classifier import TextClassifier

    monkeypatch.setenv("TRUSTSHIELD_ML_MODEL", "trustshield_text_v3")
    classifier = TextClassifier(project_root=tmp_path)
    assert classifier.model_name == "trustshield_text_v3"


def test_hardening_guards_refuse_test_and_holdout_paths(tmp_path):
    from scripts.evaluate_hardening import validate_safe_path

    with pytest.raises(ValueError):
        validate_safe_path(tmp_path / "test_v1_frozen" / "validation.jsonl")
    with pytest.raises(ValueError):
        validate_safe_path(tmp_path / "holdout-sealed" / "validation.jsonl")


@pytest.mark.parametrize("bad", [
    "datasets/processed/trustshield-text-v1/test.jsonl",
    "models/evaluation/test_final/predictions_validation.csv",
    "models/evaluation/test-eval/x.csv",
])
def test_hardening_guard_refuses_test_file_and_directory_variants(bad):
    from scripts.evaluate_hardening import validate_safe_path

    with pytest.raises(ValueError):
        validate_safe_path(bad)


def test_holdout_filter_actually_removes_matching_candidate(tmp_path):
    from scripts.prepare_dataset_v3 import filter_candidates_against_holdout_texts

    text = "your account will be blocked verify your kyc immediately at this link"
    path = tmp_path / "holdout.jsonl"
    path.write_text(json.dumps({"text": text}) + "\n", encoding="utf-8")
    candidates = [{"id": "c1", "text": text, "binary_label": "legitimate", "category_label": "legitimate"},
                  {"id": "c2", "text": "lunch at noon tomorrow?", "binary_label": "legitimate", "category_label": "legitimate"}]
    kept, _ = filter_candidates_against_holdout_texts(candidates, path)
    assert [row["id"] for row in kept] == ["c2"]


def test_v3_hf_filter_drops_mailing_list_and_corporate_email_and_scrubs_spaced_phone():
    from scripts.prepare_dataset_v3 import SPACED_PHONE_RE, looks_like_non_message_hf_legitimate

    assert looks_like_non_message_hf_legitimate("Razor-users mailing list https://lists.sourceforge.net/lists/listinfo/razor-users")
    assert looks_like_non_message_hf_legitimate("rooming list for enron offsite")
    assert SPACED_PHONE_RE.sub("<PHONE>", "call 713 - 853 - 5426 now") == "call <PHONE> now"


@MODEL_REQUIRED
def test_v3_model_loads_in_current_runtime():
    from services.analysis.ml.classifier import TextClassifier

    classifier = TextClassifier("trustshield_text_v3")
    assert classifier.available, classifier.reason
