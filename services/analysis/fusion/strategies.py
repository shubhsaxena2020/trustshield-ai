"""Pluggable Phase 4 risk-fusion strategies.

The legacy implementation remains in ``engine.py`` unchanged.  New strategies
use this module so evaluation and the online pipeline share the same contract.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from services.analysis.evidence.models import Evidence, Severity
from services.analysis.fusion.config import RISK_BANDS
from services.analysis.fusion.engine import CATEGORY_PRIORITY, ML_CATEGORY_MAP

STRATEGY_VERSION = "phase4-1"
MODERATE_ML_CUT = 0.12
DEFAULT_STRONG_ML_CUT = 0.70
DEFAULT_ML_WEIGHT = 0.50
DEFAULT_MIN_TOKENS_FOR_MODERATE_ML_FLOOR = 6
TIER_MINIMUM_SCORES = {"weak": 0, "moderate": 25, "strong": 50}

# This is deliberately a data table.  Adding a detector category requires one
# explicit fusion decision instead of silently inheriting a severity weight.
EVIDENCE_STRENGTH_CLASSES = {
    "weak": frozenset({
        "urgency", "no_https", "url_shortener", "very_long_url", "long_hostname",
        "suspicious_tld", "excessive_hyphens", "encoded_characters", "excessive_subdomains",
        "punycode_host", "impersonation_authority", "authority_impersonation",
        "page_blocks_automation", "load_timeout", "tls_error", "blocked_by_policy",
    }),
    "moderate": frozenset({
        "payment_request", "domain_mismatch", "credential_request", "account_threat",
        "delivery_scam", "fake_job", "investment_scam", "lottery_prize", "job_fee_scam",
        "investment_pressure", "archive_download", "unusual_port", "qr_payment",
        "redirect_chain_unexpected", "cross_domain_redirect", "dialog_abuse", "popup_storm",
        "non_https_login_form",
    }),
    "strong": frozenset({
        "credential_pressure", "digital_arrest", "homograph_domain", "lookalike_domain",
        "executable_download", "ip_address_host", "userinfo_in_url", "unsupported_scheme",
        "known_malicious_reputation", "credential_harvesting_sandbox",
        "credential_form_detected", "password_field", "otp_field", "card_field",
        "brand_domain_mismatch_page", "js_auto_download", "hidden_iframe_form", "download_attempt",
    }),
}



@dataclass
class StrategyResult:
    score: int
    label: str
    confidence: str
    primary: str
    secondary: list[str] = field(default_factory=list)
    data: dict[str, Any] = field(default_factory=dict)


def _label(score: int) -> str:
    return next(label for floor, label in RISK_BANDS if score >= floor)


def evidence_strength(evidence: Evidence, strong_ml_cut: float = DEFAULT_STRONG_ML_CUT) -> str:
    """Return a fusion strength class, independent of detector confidence."""
    if evidence.source == "ml_classifier":
        if evidence.confidence >= strong_ml_cut:
            return "strong"
        if evidence.confidence >= MODERATE_ML_CUT:
            return "moderate"
        return "weak"
    for name in ("strong", "moderate", "weak"):
        if evidence.category in EVIDENCE_STRENGTH_CLASSES[name]:
            return name
    if evidence.severity == Severity.critical:
        return "strong"
    if evidence.severity in {Severity.high, Severity.medium}:
        return "moderate"
    return "weak"


def _legacy_risk(evidence: list[Evidence], severity_weight: dict[Severity, float]) -> int:
    safe = 1.0
    for item in evidence:
        safe *= 1.0 - severity_weight[item.severity] * item.confidence
    return round((1.0 - safe) * 100)


def _classes(evidence: list[Evidence], strong_ml_cut: float) -> dict[str, list[Evidence]]:
    output = {name: [] for name in ("weak", "moderate", "strong")}
    for item in evidence:
        if item.severity != Severity.informational:
            output[evidence_strength(item, strong_ml_cut)].append(item)
    return output


def _classification(evidence: list[Evidence]) -> tuple[str, list[str]]:
    cats = {item.category for item in evidence if item.severity != Severity.informational}
    matched: list[str] = []
    for category, name in CATEGORY_PRIORITY:
        if category in cats and name not in matched:
            matched.append(name)
    if matched:
        return matched[0], matched[1:]
    for item in evidence:
        if item.source == "ml_classifier" and item.severity != Severity.informational:
            category = ML_CATEGORY_MAP.get(str(item.metadata.get("category", "")))
            if category:
                return category, []
    return "none_detected", []


def _confidence(module_status: dict[str, str], evidence: list[Evidence], input_ok: bool,
                module_quality: dict[str, float] | None) -> str:
    if not input_ok or not module_status:
        return "low"
    weights = {"completed": 1.0, "not_run": 0.5, "unavailable": 0.4, "failed": 0.0, "timeout": 0.0}
    quality = module_quality or {}
    coverage = sum(weights.get(status, 0.0) * max(0.0, min(1.0, quality.get(name, 1.0)))
                  for name, status in module_status.items()) / len(module_status)
    if quality:
        quality_avg = sum(max(0.0, min(1.0, value)) for value in quality.values()) / len(quality)
        coverage *= 0.5 + 0.5 * quality_avg
    base = "high" if coverage >= 0.85 else "medium" if coverage >= 0.55 else "low"
    ml_positive = any(item.source == "ml_classifier" and item.severity != Severity.informational for item in evidence)
    deterministic_positive = any(item.source != "ml_classifier" and item.severity != Severity.informational for item in evidence)
    if module_status.get("ml") == "completed" and ml_positive != deterministic_positive:
        base = {"high": "medium", "medium": "low", "low": "low"}[base]
    elif module_status.get("ml") == "completed" and ml_positive and deterministic_positive:
        ranks = list(Severity)
        ml_top = max(ranks.index(i.severity) for i in evidence if i.source == "ml_classifier" and i.severity != Severity.informational)
        det_top = max(ranks.index(i.severity) for i in evidence if i.source != "ml_classifier" and i.severity != Severity.informational)
        if abs(ml_top - det_top) >= 2:
            base = {"high": "medium", "medium": "low", "low": "low"}[base]
    return base


def _result(score: int, evidence: list[Evidence], module_status: dict[str, str], input_ok: bool,
            module_quality: dict[str, float] | None, *, parameters: dict[str, Any],
            drivers: list[str], agreement: str, extra: dict[str, Any] | None = None) -> StrategyResult:
    confidence = _confidence(module_status, evidence, input_ok, module_quality)
    from services.analysis.fusion.engine import coverage_requires_unable_to_verify
    if coverage_requires_unable_to_verify(score, confidence, module_status, input_ok, module_quality):
        label = "unable_to_verify"
    else:
        label = _label(score)
    primary, secondary = _classification(evidence)
    counts = {key: len(value) for key, value in _classes(evidence, float(parameters.get("strong_ml_cut", DEFAULT_STRONG_ML_CUT))).items()}
    data = {
        "strategy": parameters.get("strategy"), "version": STRATEGY_VERSION,
        "strength_class_counts": counts, "agreement": agreement,
        "disagreement": agreement in {"ml_only", "rules_only"}, "drivers": drivers,
        "parameters": {key: value for key, value in parameters.items() if key != "strategy"},
    }
    if extra:
        data.update(extra)
    return StrategyResult(score=int(max(0, min(100, score))), label=label, confidence=confidence,
                          primary=primary, secondary=secondary, data=data)


def _agreement(evidence: list[Evidence], module_status: dict[str, str]) -> tuple[str, bool, bool]:
    if module_status.get("ml") != "completed":
        return "not_applicable", False, False
    ml = any(item.source == "ml_classifier" and item.severity != Severity.informational for item in evidence)
    rules = any(item.source != "ml_classifier" and item.severity != Severity.informational for item in evidence)
    if ml and rules:
        return "agree", ml, rules
    if ml:
        return "ml_only", ml, rules
    if rules:
        return "rules_only", ml, rules
    return "both_negative", ml, rules


def ml_aware_weighted(evidence: list[Evidence], module_status: dict[str, str], input_ok: bool = True,
                      module_quality: dict[str, float] | None = None, *, ml_weight: float = DEFAULT_ML_WEIGHT,
                      strong_ml_cut: float = DEFAULT_STRONG_ML_CUT,
                      min_tokens_for_moderate_ml_floor: int = DEFAULT_MIN_TOKENS_FOR_MODERATE_ML_FLOOR,
                      input_token_count: int | None = None,
                      severity_weight: dict[Severity, float] | None = None) -> StrategyResult:
    """Legacy rule noisy-OR plus one explicitly tunable ML weight."""
    from services.analysis.fusion.engine import SEVERITY_WEIGHT
    weights = severity_weight or SEVERITY_WEIGHT
    rules = [item for item in evidence if item.source != "ml_classifier"]
    ml = [item for item in evidence if item.source == "ml_classifier" and item.severity != Severity.informational]
    rule_probability = _legacy_risk(rules, weights) / 100.0
    p = max((item.confidence for item in ml), default=0.0)
    ml_probability = min(1.0, max(0.0, float(ml_weight) * p)) if ml else 0.0
    score = round((1.0 - (1.0 - rule_probability) * (1.0 - ml_probability)) * 100)
    classes = _classes(evidence, strong_ml_cut)
    strong_ml = any(item.source == "ml_classifier" for item in classes["strong"])
    moderate_ml = any(item.source == "ml_classifier" for item in classes["moderate"])
    strong_deterministic = any(item.source != "ml_classifier" for item in classes["strong"])
    if strong_ml:
        score = max(score, TIER_MINIMUM_SCORES["strong"])
    elif moderate_ml and (input_token_count is None or input_token_count >= min_tokens_for_moderate_ml_floor):
        score = max(score, TIER_MINIMUM_SCORES["moderate"])
    if strong_deterministic:
        score = max(score, TIER_MINIMUM_SCORES["strong"])
    if strong_deterministic and ml:
        score = max(score, 70)
    # Raw IP host + credential/login path -> high risk (deterministic floor).
    _cats = {e.category for e in evidence}
    if "ip_address_host" in _cats and "credential_path_terms" in _cats:
        score = max(score, 70)
    agreement, _, _ = _agreement(evidence, module_status)
    drivers = ["deterministic_evidence"] if rules else []
    if strong_ml:
        drivers.append("strong_ml")
    elif moderate_ml:
        drivers.append("moderate_ml")
    if strong_deterministic:
        drivers.append("strong_deterministic")
    return _result(score, evidence, module_status, input_ok, module_quality,
                   parameters={"strategy": "ml_aware_weighted", "ml_weight": ml_weight, "strong_ml_cut": strong_ml_cut,
                               "min_tokens_for_moderate_ml_floor": min_tokens_for_moderate_ml_floor},
                   drivers=drivers, agreement=agreement)


def strength_gated(evidence: list[Evidence], module_status: dict[str, str], input_ok: bool = True,
                   module_quality: dict[str, float] | None = None, *, strong_ml_cut: float = DEFAULT_STRONG_ML_CUT,
                   min_tokens_for_moderate_ml_floor: int = DEFAULT_MIN_TOKENS_FOR_MODERATE_ML_FLOOR,
                   input_token_count: int | None = None,
                   tier_minimum_scores: dict[str, int] | None = None,
                   severity_weight: dict[Severity, float] | None = None) -> StrategyResult:
    """Fuse evidence with explicit strength floors and agreement semantics."""
    from services.analysis.fusion.engine import SEVERITY_WEIGHT
    weights = severity_weight or SEVERITY_WEIGHT
    minimums = {**TIER_MINIMUM_SCORES, **(tier_minimum_scores or {})}
    classes = _classes(evidence, strong_ml_cut)
    score = _legacy_risk(evidence, weights)
    has_ml = bool([item for item in evidence if item.source == "ml_classifier" and item.severity != Severity.informational])
    strong_ml = bool([item for item in classes["strong"] if item.source == "ml_classifier"])
    moderate_ml = bool([item for item in classes["moderate"] if item.source == "ml_classifier"])
    strong_deterministic = bool([item for item in classes["strong"] if item.source != "ml_classifier"])
    if strong_ml:
        score = max(score, minimums["strong"])
    elif moderate_ml and (input_token_count is None or input_token_count >= min_tokens_for_moderate_ml_floor):
        score = max(score, minimums["moderate"])
    if strong_deterministic:
        score = max(score, minimums["strong"])
    if strong_deterministic and has_ml:
        score = max(score, 70)
    # Raw IP host + credential/login path -> high risk (deterministic floor).
    _cats = {e.category for e in evidence}
    if "ip_address_host" in _cats and "credential_path_terms" in _cats:
        score = max(score, 70)
    agreement, _, _ = _agreement(evidence, module_status)
    drivers = []
    if strong_ml:
        drivers.append("strong_ml")
    elif moderate_ml:
        drivers.append("moderate_ml")
    if strong_deterministic:
        drivers.append("strong_deterministic")
    if not drivers and classes["weak"]:
        drivers.append("weak_indicators")
    return _result(score, evidence, module_status, input_ok, module_quality,
                   parameters={"strategy": "strength_gated", "moderate_ml_cut": MODERATE_ML_CUT,
                               "strong_ml_cut": strong_ml_cut,
                               "min_tokens_for_moderate_ml_floor": min_tokens_for_moderate_ml_floor,
                               "tier_minimum_scores": minimums},
                   drivers=drivers, agreement=agreement)


def apply_strategy(name: str, evidence: list[Evidence], module_status: dict[str, str], input_ok: bool = True,
                   module_quality: dict[str, float] | None = None, *, ml_weight: float = DEFAULT_ML_WEIGHT,
                   strong_ml_cut: float = DEFAULT_STRONG_ML_CUT,
                   min_tokens_for_moderate_ml_floor: int = DEFAULT_MIN_TOKENS_FOR_MODERATE_ML_FLOOR,
                   input_token_count: int | None = None,
                   tier_minimum_scores: dict[str, int] | None = None,
                   severity_weight: dict[Severity, float] | None = None) -> StrategyResult:
    if name == "ml_aware_weighted":
        return ml_aware_weighted(evidence, module_status, input_ok, module_quality, ml_weight=ml_weight,
                                 strong_ml_cut=strong_ml_cut,
                                 min_tokens_for_moderate_ml_floor=min_tokens_for_moderate_ml_floor,
                                 input_token_count=input_token_count, severity_weight=severity_weight)
    if name == "strength_gated":
        return strength_gated(evidence, module_status, input_ok, module_quality, strong_ml_cut=strong_ml_cut,
                              min_tokens_for_moderate_ml_floor=min_tokens_for_moderate_ml_floor,
                              input_token_count=input_token_count,
                              tier_minimum_scores=tier_minimum_scores, severity_weight=severity_weight)
    raise ValueError(f"unknown fusion strategy: {name}")
