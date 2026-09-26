import pytest

from services.analysis.ml.classifier import DEFAULT_MODEL, ROLLBACK_MODEL, TextClassifier


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch):
    monkeypatch.delenv("TRUSTSHIELD_ML_MODEL", raising=False)
    monkeypatch.delenv("TRUSTSHIELD_ML", raising=False)


def test_v3_is_the_default_and_loads():
    classifier = TextClassifier()
    assert DEFAULT_MODEL == "trustshield_text_v3" and ROLLBACK_MODEL == "trustshield_text_v1"
    assert classifier.model_name == "trustshield_text_v3"
    assert classifier.available, classifier.reason
    assert classifier.metadata["threshold"] == 0.16


def test_v1_remains_explicitly_selectable_by_argument_and_env(monkeypatch):
    by_arg = TextClassifier("trustshield_text_v1")
    assert by_arg.model_name == "trustshield_text_v1" and by_arg.available and by_arg.metadata["threshold"] == 0.12
    monkeypatch.setenv("TRUSTSHIELD_ML_MODEL", "trustshield_text_v1")
    by_env = TextClassifier()
    assert by_env.model_name == "trustshield_text_v1" and by_env.available


def test_health_reports_v3_as_default():
    from fastapi.testclient import TestClient
    from services.analysis import pipeline
    from services.api.main import app

    pipeline._text_classifier.cache_clear()
    ml = TestClient(app).get("/api/v1/health").json()["modules"]["ml"]
    assert ml["model_name"] == "trustshield_text_v3" and ml["available"] is True
    assert ml["model_version"]


def test_health_and_analysis_use_v1_when_rolled_back(monkeypatch):
    from fastapi.testclient import TestClient
    from services.analysis import pipeline
    from services.api.main import app

    monkeypatch.setenv("TRUSTSHIELD_ML_MODEL", "trustshield_text_v1")
    pipeline._text_classifier.cache_clear()
    try:
        client = TestClient(app)
        assert client.get("/api/v1/health").json()["modules"]["ml"]["model_name"] == "trustshield_text_v1"
        body = client.post("/api/v1/analyze/text", json={"text": "URGENT share your OTP to keep your SBI account"}).json()
        assert body["modules"]["ml"]["status"] == "completed"
    finally:
        monkeypatch.delenv("TRUSTSHIELD_ML_MODEL")
        pipeline._text_classifier.cache_clear()
