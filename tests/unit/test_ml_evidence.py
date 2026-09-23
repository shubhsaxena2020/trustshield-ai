from services.analysis.ml.evidence import ml_evidence, ml_module_result
from services.analysis.ml.classifier import MLPrediction


def prediction(probability: float, threshold: float = 0.5) -> MLPrediction:
    return MLPrediction("toy", "1", "toy-v1", "manifest", "features-v1", "sigmoid", probability,
                        threshold, probability >= threshold, "phishing", {"phishing": probability}, 1.0)


def test_ml_evidence_emits_only_at_threshold_and_uses_provisional_severity():
    assert ml_evidence(prediction(0.49)) == []
    assert ml_evidence(prediction(0.80))[0].severity.value == "medium"
    assert ml_evidence(prediction(0.90))[0].severity.value == "high"
    assert ml_evidence(prediction(0.80))[0].source == "ml_classifier"


def test_ml_module_result_has_structured_provenance():
    result = ml_module_result(prediction(0.8))
    assert result.status == "completed"
    assert result.data["model_name"] == "toy"


def test_ml_evidence_uses_content_category_when_category_is_missing():
    missing = prediction(0.8)
    missing = MLPrediction(missing.model_name, missing.model_version, missing.dataset_version, missing.dataset_manifest_sha256,
                           missing.feature_pipeline_version, missing.calibration_version, missing.p_malicious, missing.threshold,
                           missing.predicted_binary, None, None, missing.latency_ms)
    evidence = ml_evidence(missing)[0]
    assert evidence.category == "ml_malicious_content"
    assert evidence.title == "Machine-learning classifier detected malicious content"
