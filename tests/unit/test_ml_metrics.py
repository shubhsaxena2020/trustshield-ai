import pytest

from services.analysis.ml.metrics import (
    binary_metrics, category_scope_metrics, expected_calibration_error, select_threshold, threshold_sweep,
)


def test_binary_metrics_hand_computed_counts_and_rates():
    labels = [1, 1, 1, 1, 1, 0, 0, 0, 0, 0]
    scores = [0.9, 0.8, 0.1, 0.2, 0.3, 0.7, 0.1, 0.2, 0.4, 0.3]
    result = binary_metrics(labels, scores, threshold=0.5)
    assert {key: result[key] for key in ("TP", "TN", "FP", "FN")} == {"TP": 2, "TN": 4, "FP": 1, "FN": 3}
    assert result["precision"] == pytest.approx(2 / 3)
    assert result["recall"] == pytest.approx(2 / 5)
    assert result["specificity"] == pytest.approx(4 / 5)
    assert result["F1"] == pytest.approx(0.5)


def test_equal_width_ece_is_hand_computed():
    value = expected_calibration_error([0, 0, 1, 1], [0.1, 0.2, 0.8, 0.9], n_bins=2)
    assert value == pytest.approx(0.15)


def test_expected_calibration_error_rejects_non_binary_labels():
    with pytest.raises(ValueError, match="labels must be binary"):
        expected_calibration_error([0, 2], [0.1, 0.9])


def test_category_metrics_report_unmerged_and_merged_scopes():
    actual = ["phishing", "rare", "legitimate", "unlabeled"]
    predicted = ["phishing", "other_fraud", "other_fraud", "other_fraud"]
    result = category_scope_metrics(actual, predicted, [1, 1, 0, 1], {"rare": "other_fraud"})
    assert set(result) == {"unmerged", "merged"}
    assert result["merged"]["fraud_only"]["support"] == 2
    assert result["merged"]["all_labelled_incl_legitimate"]["support"] == 3
    assert result["merged"]["fraud_only"]["macro_f1"] == pytest.approx(1.0)
    assert result["unmerged"]["fraud_only"]["macro_f1"] < result["merged"]["fraud_only"]["macro_f1"]


def test_threshold_selection_prefers_recall_under_specificity_target():
    sweep = threshold_sweep([0, 1, 1, 1], [0.6, 0.7, 0.8, 0.95], thresholds=[0.5, 0.75])
    chosen = select_threshold(sweep)
    assert chosen["threshold"] == 0.75
    assert chosen["spec_target_met"] is True
