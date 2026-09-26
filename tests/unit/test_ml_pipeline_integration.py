from __future__ import annotations

from fastapi.testclient import TestClient
import pytest

import services.analysis.fusion.engine as fusion_engine
from services.analysis.fusion.engine import CONFIDENCE_CAP
from services.analysis.ml.classifier import MLPrediction
from services.analysis.pipeline import analyze_text, analyze_url
import services.analysis.pipeline as pipeline
from services.api.main import app


class FakeClassifier:
    available = True
    reason = ""

    def __init__(self, prediction: MLPrediction):
        self.prediction = prediction

    def predict(self, text: str) -> MLPrediction:
        return self.prediction


def _prediction(probability: float = 0.95, category: str | None = "phishing") -> MLPrediction:
    return MLPrediction("toy", "v1", "toy-v1", "manifest-sha", "features-v1", "cal-v1", probability,
                        0.12, probability >= 0.12, category, {category: probability} if category else None, 1.25)


def test_ml_provenance_and_category_fill_ruleless_classification(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_ML", "on")
    monkeypatch.setattr(pipeline, "_text_classifier", lambda: FakeClassifier(_prediction()))
    result = analyze_text("ordinary words with no deterministic rule trigger")
    assert result.modules["ml"].status == "completed"
    assert result.modules["ml"].data["dataset_manifest_sha256"] == "manifest-sha"
    assert result.modules["ml"].data["latency_ms"] == 1.25
    assert result.classification.primary == "phishing"
    assert result.evidence[0].source == "ml_classifier"


def test_nonlegacy_fusion_is_exposed_as_optional_module_data(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_ML", "on")
    monkeypatch.setenv("TRUSTSHIELD_FUSION", "strength_gated")
    monkeypatch.setattr(pipeline, "_text_classifier", lambda: FakeClassifier(_prediction(0.95)))
    result = analyze_text("ordinary words with no deterministic rule trigger")
    assert result.modules["fusion"].status == "completed"
    assert result.modules["fusion"].data["strategy"] == "strength_gated"
    assert result.modules["fusion"].data["agreement"] == "ml_only"
    assert result.risk.score >= 50


def test_selected_nonlegacy_fusion_is_the_default(monkeypatch):
    monkeypatch.delenv("TRUSTSHIELD_FUSION", raising=False)
    monkeypatch.setenv("TRUSTSHIELD_ML", "on")
    monkeypatch.setattr(pipeline, "_text_classifier", lambda: FakeClassifier(_prediction(0.40)))
    result = analyze_text("ordinary words with no deterministic rule trigger")
    assert result.modules["fusion"].data["strategy"] == "ml_aware_weighted"
    assert result.risk.label in {"caution", "suspicious", "high_risk"}


def test_ml_off_is_rule_only_and_url_does_not_run_text_model(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_ML", "off")
    result = analyze_text("verify account now")
    assert result.modules["ml"].status == "not_run"
    url_result = analyze_url("https://example.invalid/login")
    assert "ml" not in url_result.modules


def test_url_without_ml_reports_non_applicable_agreement(monkeypatch):
    monkeypatch.delenv("TRUSTSHIELD_FUSION", raising=False)
    result = analyze_url("https://user:pass@evil.example.com")
    assert result.modules["fusion"].data["agreement"] == "not_applicable"
    assert result.modules["fusion"].data["disagreement"] is False


def test_short_benign_text_does_not_get_moderate_ml_floor(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_ML", "on")
    monkeypatch.delenv("TRUSTSHIELD_FUSION", raising=False)
    monkeypatch.setattr(pipeline, "_text_classifier", lambda: FakeClassifier(_prediction(0.31, None)))
    short = analyze_text("hello there")
    long = analyze_text("this is a longer ordinary message with enough context for analysis")
    assert short.risk.label == "low_risk"
    assert short.risk.score < 25
    assert long.risk.label == "caution"
    assert long.risk.score >= 25


@pytest.mark.parametrize("probability", [float("nan"), float("inf"), -0.1, 1.5])
def test_nonfinite_or_out_of_range_ml_probability_fails_closed(monkeypatch, probability):
    monkeypatch.setenv("TRUSTSHIELD_ML", "on")
    monkeypatch.setattr(pipeline, "_text_classifier", lambda: FakeClassifier(_prediction(probability, None)))
    result = analyze_text("ordinary words with no deterministic rule trigger")
    assert result.modules["ml"].status == "failed"
    assert "p_malicious" in result.modules["ml"].detail
    assert result.risk.label == "unable_to_verify"


def test_missing_model_degrades_to_unavailable_without_raising(monkeypatch, tmp_path):
    from services.analysis.ml.classifier import TextClassifier

    monkeypatch.setenv("TRUSTSHIELD_ML", "on")
    missing = TextClassifier("does-not-exist", project_root=tmp_path)
    monkeypatch.setattr(pipeline, "_text_classifier", lambda: missing)
    result = analyze_text("verify account now")
    assert result.status in {"completed", "partial"}
    assert result.modules["ml"].status == "unavailable"
    assert result.modules["ml"].detail


def test_confidence_cap_is_single_documented_medium_cap(monkeypatch):
    modules = {name: "completed" for name in ("text_rules", "ml", "url_static", "reputation", "web_sandbox", "ocr", "qr")}
    assert CONFIDENCE_CAP == "medium"
    assert fusion_engine.assess_confidence(modules) == "medium"
    monkeypatch.setattr(fusion_engine, "CONFIDENCE_CAP", "high")
    assert fusion_engine.assess_confidence(modules) == "high"


def test_health_reports_ml_shape():
    body = TestClient(app).get("/api/v1/health").json()
    assert set(body["modules"]["ml"]) == {"available", "model_name", "model_version"}


def test_health_honors_ml_off_without_loading_classifier(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_ML", "off")
    def fail_loader(*args, **kwargs):
        raise AssertionError("classifier must not load while ML is disabled")
    monkeypatch.setattr(pipeline, "TextClassifier", fail_loader)
    body = TestClient(app).get("/api/v1/health").json()
    assert body["modules"]["ml"] == {"available": False, "reason": "disabled_by_env", "model_name": None, "model_version": None}


def test_health_reuses_pipeline_classifier_cache(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_ML", "on")
    pipeline._text_classifier.cache_clear()
    calls = []

    class FakeClassifier:
        available = True
        model_name = "fake"
        metadata = {"model_version": "fake-v1"}

    def loader(*args, **kwargs):
        calls.append(True)
        return FakeClassifier()

    monkeypatch.setattr(pipeline, "TextClassifier", loader)
    client = TestClient(app)
    assert client.get("/api/v1/health").json()["modules"]["ml"]["model_name"] == "fake"
    assert client.get("/api/v1/health").json()["modules"]["ml"]["model_name"] == "fake"
    assert len(calls) == 1
    pipeline._text_classifier.cache_clear()
