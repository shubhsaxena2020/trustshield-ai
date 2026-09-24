from services.analysis.evidence.models import Evidence, Severity
from services.analysis.fusion.engine import fuse, risk_score
from services.analysis.fusion.strategies import evidence_strength, ml_aware_weighted, strength_gated


def ev(category: str, severity: Severity, confidence: float = 0.8, source: str = "text_rules") -> Evidence:
    return Evidence(source=source, category=category, severity=severity, confidence=confidence,
                    title=category, description=category)


def statuses():
    return {"text_rules": "completed", "ml": "completed"}


def test_strength_classes_keep_weak_indicators_below_strong_deterministic_evidence():
    assert evidence_strength(ev("urgency", Severity.low)) == "weak"
    assert evidence_strength(ev("credential_request", Severity.high)) == "moderate"
    assert evidence_strength(ev("digital_arrest", Severity.critical)) == "strong"
    result = strength_gated([ev("digital_arrest", Severity.critical), ev("urgency", Severity.low)], statuses())
    assert result.label in {"suspicious", "high_risk"}


def test_strong_ml_alone_is_at_least_suspicious_and_disagreement_lowers_confidence():
    result = strength_gated([ev("ml_malicious_content", Severity.high, 0.95, "ml_classifier")], statuses())
    assert result.label in {"suspicious", "high_risk"}
    assert result.score >= 50
    assert result.confidence == "low"


def test_moderate_ml_alone_is_at_least_caution():
    result = strength_gated([ev("ml_malicious_content", Severity.medium, 0.40, "ml_classifier")], statuses())
    assert result.label in {"caution", "suspicious", "high_risk"}
    assert result.score >= 25


def test_weighted_strategy_keeps_the_required_ml_floor():
    result = ml_aware_weighted([ev("ml_malicious_content", Severity.medium, 0.40, "ml_classifier")],
                               statuses(), ml_weight=0.5)
    assert result.score >= 25


def test_strong_ml_and_strong_deterministic_evidence_are_high_risk():
    result = strength_gated([
        ev("ml_malicious_content", Severity.high, 0.95, "ml_classifier"),
        ev("credential_pressure", Severity.high, 0.85),
    ], statuses())
    assert result.label == "high_risk"
    assert result.score >= 70


def test_rules_strong_with_ml_negative_keeps_risk_and_reduces_confidence():
    evidence = [ev("digital_arrest", Severity.critical)]
    negative = strength_gated(evidence, statuses())
    positive = strength_gated(evidence + [ev("ml_malicious_content", Severity.medium, 0.4, "ml_classifier")], statuses())
    assert negative.score >= 50
    assert negative.label in {"suspicious", "high_risk"}
    assert negative.confidence == "low"
    assert positive.score >= negative.score


def test_insufficient_coverage_is_unable_to_verify():
    result = strength_gated([], {"text_rules": "failed", "ml": "unavailable"}, input_ok=False)
    assert result.label == "unable_to_verify"


def test_legacy_fusion_remains_the_existing_noisy_or():
    evidence = [ev("ml_malicious_content", Severity.medium, 0.8, "ml_classifier")]
    legacy = fuse(evidence, statuses(), strategy_name="legacy")
    assert legacy.score == risk_score(evidence) == 13
    assert legacy.label == "low_risk"


def test_risk_score_is_not_lowered_by_confidence_disagreement():
    evidence = [ev("digital_arrest", Severity.critical)]
    good = strength_gated(evidence, statuses())
    bad = strength_gated(evidence, {"text_rules": "completed", "ml": "completed"}, module_quality={"ml": 0.1})
    assert bad.score == good.score
    assert bad.confidence <= good.confidence
