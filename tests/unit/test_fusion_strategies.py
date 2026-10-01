from services.analysis.evidence.models import Evidence, Severity
from services.analysis.fusion.engine import CONFIDENCE_CAP, fuse, risk_score
from services.analysis.explanation.template import explain
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


def test_ml_weighted_contribution_is_monotone_and_below_threshold_has_no_floor():
    lower = ml_aware_weighted([ev("ml_malicious_content", Severity.medium, 0.05, "ml_classifier")], statuses())
    higher = ml_aware_weighted([ev("ml_malicious_content", Severity.medium, 0.10, "ml_classifier")], statuses())
    assert higher.score > lower.score
    assert higher.score < 25


def test_short_moderate_ml_does_not_establish_floor_but_long_text_does():
    short = ml_aware_weighted([ev("ml_malicious_content", Severity.medium, 0.31, "ml_classifier")], statuses(), input_token_count=2)
    long = ml_aware_weighted([ev("ml_malicious_content", Severity.medium, 0.31, "ml_classifier")], statuses(), input_token_count=8)
    assert short.score < 25
    assert long.score >= 25


def test_strong_ml_floor_and_strong_evidence_are_not_cancelled_by_weak_evidence():
    strong_ml = ml_aware_weighted([ev("ml_malicious_content", Severity.high, 0.95, "ml_classifier")], statuses(), input_token_count=2)
    strong_rules = ml_aware_weighted([ev("digital_arrest", Severity.critical)], statuses())
    with_weak = ml_aware_weighted([ev("digital_arrest", Severity.critical), ev("urgency", Severity.low, 0.9)], statuses())
    assert strong_ml.score >= 50
    assert with_weak.score >= strong_rules.score >= 50


def test_agreement_is_not_applicable_when_ml_did_not_complete():
    result = ml_aware_weighted([ev("urgency", Severity.low)], {"text_rules": "completed", "ml": "not_run"})
    assert result.data["agreement"] == "not_applicable"
    assert result.data["disagreement"] is False


def test_completed_ml_disagreement_lowers_confidence():
    result = ml_aware_weighted([ev("ml_malicious_content", Severity.medium, 0.4, "ml_classifier")], statuses())
    assert result.data["agreement"] == "ml_only"
    assert result.data["disagreement"] is True
    assert result.confidence == "low"


def test_drivers_are_rendered_in_explanation():
    result = ml_aware_weighted([ev("ml_malicious_content", Severity.medium, 0.4, "ml_classifier")], statuses())
    explanation, _ = explain(result.label, [ev("ml_malicious_content", Severity.medium, 0.4, "ml_classifier")], result.primary, [], result.data)
    assert "moderate_ml" in explanation


def test_confidence_remains_capped():
    modules = {name: "completed" for name in ("text_rules", "ml", "url_static", "reputation", "ocr", "qr")}
    result = ml_aware_weighted([], modules)
    assert result.confidence == CONFIDENCE_CAP == "medium"


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


def test_low_coverage_from_failed_modules_is_unable_to_verify_even_when_input_is_valid():
    result = ml_aware_weighted([], {
        "text_rules": "completed", "ml": "failed", "url_static": "failed", "reputation": "unavailable",
    }, input_ok=True)
    assert result.label == "unable_to_verify"


def test_limited_context_can_keep_score_based_label():
    result = ml_aware_weighted([ev("urgency", Severity.low)], {
        "text_rules": "completed", "ml": "not_run",
    }, module_quality={"text_rules": 0.25})
    assert result.label == "low_risk"


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
