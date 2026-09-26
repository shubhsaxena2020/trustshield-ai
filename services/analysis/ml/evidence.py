"""Canonical Evidence emission for the ML signal."""
from __future__ import annotations

from services.analysis.evidence.models import Evidence, Severity
from services.analysis.fusion.result import ModuleResult
from .classifier import MLPrediction

ML_HIGH_THRESHOLD = 0.90
ML_EVIDENCE_SOURCE = "ml_classifier"


def ml_evidence(prediction: MLPrediction) -> list[Evidence]:
    if prediction.error or prediction.p_malicious < prediction.threshold:
        return []
    category = prediction.category or "malicious_content"
    evidence_category = f"ml_{category}_pattern" if prediction.category else "ml_malicious_content"
    title = (f"Machine-learning classifier detected {category.replace('_', ' ')} content"
             if prediction.category else "Machine-learning classifier detected malicious content")
    return [Evidence(
        source=ML_EVIDENCE_SOURCE, category=evidence_category,
        severity=Severity.high if prediction.p_malicious >= ML_HIGH_THRESHOLD else Severity.medium,
        confidence=prediction.p_malicious,
        title=title,
        description="This is a statistical pattern signal from a calibrated classifier, not proof that the message is fraudulent.",
        metadata={"model_name": prediction.model_name, "model_version": prediction.model_version,
                  "dataset_version": prediction.dataset_version, "threshold": prediction.threshold,
                  "feature_pipeline_version": prediction.feature_pipeline_version, "category": prediction.category},
    )]


def ml_module_result(prediction_or_error: MLPrediction | Exception | None) -> ModuleResult:
    if isinstance(prediction_or_error, MLPrediction):
        if prediction_or_error.error:
            return ModuleResult(status="unavailable", detail=prediction_or_error.error)
        return ModuleResult(status="completed", data={
            "model_name": prediction_or_error.model_name, "model_version": prediction_or_error.model_version,
            "dataset_version": prediction_or_error.dataset_version, "dataset_manifest_sha256": prediction_or_error.dataset_manifest_sha256,
            "feature_pipeline_version": prediction_or_error.feature_pipeline_version, "calibration_version": prediction_or_error.calibration_version,
            "p_malicious": prediction_or_error.p_malicious, "threshold": prediction_or_error.threshold,
            "predicted_binary": prediction_or_error.predicted_binary, "category": prediction_or_error.category,
            "category_probs": prediction_or_error.category_probs, "latency_ms": prediction_or_error.latency_ms,
        })
    detail = str(prediction_or_error) if prediction_or_error else "Classifier unavailable"
    return ModuleResult(status="unavailable", detail=detail)
