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
