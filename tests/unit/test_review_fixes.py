"""Regression tests for defects found by the independent review of the cap lift and provider refactor."""
import pytest

from services.analysis.evidence.models import Evidence, Severity
from services.analysis.fusion import strategies
from services.chat import explain
from services.chat.contract import ChatAnalysis


def _analysis():
    return ChatAnalysis.model_validate({
        "analysis_id": "t", "risk_level": "suspicious", "risk_score": 60, "confidence": "medium",
        "evidence": [], "capabilities": [{"name": "reputation", "used": False, "description": "rep", "reason": "none"}],
        "uncertainty": [], "warnings": [], "safe_actions": [], "urls": [], "contract_version": "2.0",
        "created_at": "2026-01-01T00:00:00Z", "timestamp": "2026-01-01T00:00:00Z", "input_types": ["text"],
    })


def test_analysis_followup_validator_blocks_a_request_for_a_secret():
    reply = "To finish checking this, please send me your OTP so I can verify the account."
    assert explain.grounding_violations(reply, _analysis()), "follow-up validator must reject secret requests"


def test_negated_secret_warning_is_not_a_violation():
    reply = "Never share your OTP with anyone who contacts you."
    assert not explain.grounding_violations(reply, _analysis())


@pytest.fixture
def clean_budget(monkeypatch):
    monkeypatch.setattr(explain, "_REMOTE_CALL_TIMES", [])
    monkeypatch.setattr(explain, "_REMOTE_CALLS_PER_MINUTE", 2)
    yield


def test_budget_exhaustion_does_not_open_the_circuit(clean_budget, monkeypatch):
    explain._circuit_state.clear()
    chain = explain.ProviderChain([("primary", lambda p: (_ for _ in ()).throw(
        explain.RemoteProviderBudgetExceeded("cap")))])
    for _ in range(5):
        chain.reply_cyber_qa("What is phishing?")
    assert explain._circuit_state.get("primary") is None, "budget exhaustion must not count as a route failure"


def test_retry_attempt_also_consumes_budget(clean_budget, monkeypatch):
    import urllib.error
    calls = {"n": 0}

    def fake_call(*args, **kwargs):
        calls["n"] += 1
        raise urllib.error.HTTPError("u", 503, "down", {}, None)

    monkeypatch.setenv("TRUSTSHIELD_AI_BASE_URL", "https://api.example.com/v1")
    monkeypatch.setenv("TRUSTSHIELD_AI_API_KEY", "k")
    monkeypatch.setattr(explain, "_remote_call_once", fake_call)
    with pytest.raises(Exception):
        explain._remote_transport("hi")
    assert calls["n"] == 2
    assert len(explain._REMOTE_CALL_TIMES) == 2, "each HTTP attempt must take a budget token"


def _ev(source, severity, category="x"):
    return Evidence(source=source, category=category, severity=severity, confidence=0.9, title="t", description="d")


FULL_COVERAGE = {"text_rules": "completed", "ml": "completed", "url_static": "completed"}


def test_large_severity_gap_between_ml_and_rules_steps_confidence_down():
    evidence = [_ev("ml_classifier", Severity.low), _ev("text_rules", Severity.critical, "digital_arrest")]
    assert strategies._confidence(FULL_COVERAGE, evidence, True, None) == "medium"


def test_agreeing_severities_keep_high_when_coverage_is_full():
    evidence = [_ev("ml_classifier", Severity.high), _ev("text_rules", Severity.high, "credential_request")]
    assert strategies._confidence(FULL_COVERAGE, evidence, True, None) == "high"
