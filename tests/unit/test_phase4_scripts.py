import json
from pathlib import Path

import pytest


def test_oof_script_rejects_test_split():
    from scripts.fusion_oof_predictions import validate_split

    with pytest.raises(ValueError):
        validate_split("test")


def test_fusion_evaluator_accepts_only_train_oof_or_validation():
    from scripts.evaluate_fusion import validate_split

    assert validate_split("train_oof") == "train_oof"
    assert validate_split("validation") == "validation"
    with pytest.raises(ValueError):
        validate_split("test")


@pytest.mark.parametrize("relative", [
    "datasets/raw/holdout_v2",
    "datasets/raw/holdout-sealed",
    "datasets/raw/../holdout_v2",
    "datasets/processed/test_v1_frozen",
])
def test_phase4_dataset_guards_reject_sealed_path_components(relative):
    from scripts.evaluate_fusion import _safe_dataset_dir
    from scripts.fusion_oof_predictions import _safe_dataset_dir as oof_safe_dataset_dir

    for guard in (_safe_dataset_dir, oof_safe_dataset_dir):
        with pytest.raises(ValueError):
            guard(Path(relative))


def test_phase4_dataset_guards_allow_pytest_tmp_dataset(tmp_path):
    from scripts.evaluate_fusion import _read_jsonl, _safe_dataset_dir
    from scripts.fusion_oof_predictions import _read_train, _safe_dataset_dir as oof_safe_dataset_dir

    dataset_dir = tmp_path / "test_something0" / "dataset"
    dataset_dir.mkdir(parents=True)
    (dataset_dir / "validation.jsonl").write_text(json.dumps({"id": "v1"}) + "\n", encoding="utf-8")
    (dataset_dir / "train.jsonl").write_text(json.dumps({"id": "t1"}) + "\n", encoding="utf-8")

    assert _safe_dataset_dir(dataset_dir) == dataset_dir.resolve()
    assert oof_safe_dataset_dir(dataset_dir) == dataset_dir.resolve()
    assert _read_jsonl(dataset_dir / "validation.jsonl", "validation.jsonl")[0]["id"] == "v1"
    assert _read_train(dataset_dir / "train.jsonl")[0]["id"] == "t1"


@pytest.mark.parametrize("filename, reader, expected_name", [
    ("validation.jsonl", "jsonl", "validation.jsonl"),
    ("train.jsonl", "train", None),
])
def test_phase4_readers_reject_sealed_paths(tmp_path, filename, reader, expected_name):
    sealed = tmp_path / "holdout-sealed"
    sealed.mkdir()
    path = sealed / filename
    path.write_text("{}\n", encoding="utf-8")
    if reader == "jsonl":
        from scripts.evaluate_fusion import _read_jsonl
        target = lambda: _read_jsonl(path, expected_name)
    else:
        from scripts.fusion_oof_predictions import _read_train
        target = lambda: _read_train(path)
    with pytest.raises(ValueError):
        target()


def test_fusion_csv_reader_rejects_sealed_path(tmp_path):
    from scripts.evaluate_fusion import _read_csv

    path = tmp_path / "test_v1_frozen" / "predictions.csv"
    path.parent.mkdir()
    path.write_text("id\n1\n", encoding="utf-8")
    with pytest.raises(ValueError):
        _read_csv(path)


def test_selection_rule_is_applied_to_both_splits():
    from scripts.evaluate_fusion import _selection, _selection_rule

    comparison = [
        {"candidate": "legacy", "operating_point": "caution", "status": "evaluated", "specificity": 0.90, "F1": 0.70},
        {"candidate": "ml_aware_weighted", "operating_point": "caution", "status": "evaluated", "specificity": 0.90, "F1": 0.80},
        {"candidate": "strength_gated", "operating_point": "caution", "status": "evaluated", "specificity": 0.90, "F1": 0.79},
    ]
    assert _selection(comparison) == "ml_aware_weighted"
    assert "train OOF" in _selection_rule("train_oof")
    assert "validation" in _selection_rule("validation")
