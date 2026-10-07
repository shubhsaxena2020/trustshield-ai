"""Risk fusion + confidence. Legacy behavior is preserved for compatibility."""
from __future__ import annotations

from dataclasses import dataclass
import os
from typing import Any

from services.analysis.evidence.models import Evidence, Severity
from services.analysis.fusion.config import RISK_BANDS as BANDS

# Probability-like weight a single piece of evidence contributes at confidence 1.0.
SEVERITY_WEIGHT = {
    Severity.informational: 0.0,
    Severity.low: 0.06,
    Severity.medium: 0.16,
    Severity.high: 0.38,
    Severity.critical: 0.55,
}
CATEGORY_PRIORITY = [
    ("digital_arrest", "digital_arrest"),
    ("credential_pressure", "credential_phishing"),
    ("credential_request", "credential_theft"),
    ("domain_mismatch", "phishing"),
    ("lookalike_domain", "phishing"),
    ("homograph_domain", "phishing"),
    ("job_fee_scam", "fake_job"),
    ("fake_job", "fake_job"),
    ("investment_pressure", "investment_fraud"),
    ("investment_scam", "investment_fraud"),
    ("payment_request", "payment_fraud"),
    ("delivery_scam", "delivery_scam"),
    ("lottery_prize", "prize_scam"),
    ("account_threat", "account_threat_phishing"),
    ("impersonation_authority", "impersonation"),
    ("executable_download", "malware_delivery"),
]

# ML availability must not be treated as calibration or detector agreement.
# This cap is lifted only after a later evaluation of calibration and agreement.
CONFIDENCE_CAP = "high"
DEFAULT_FUSION_STRATEGY = "ml_aware_weighted"
_CONFIDENCE_RANK = {"low": 0, "medium": 1, "high": 2}
ML_CATEGORY_MAP = {
    "phishing": "phishing", "credential_theft": "credential_theft", "payment_fraud": "payment_fraud",
    "fake_job": "fake_job", "investment_scam": "investment_fraud", "impersonation": "impersonation",
    "digital_arrest": "digital_arrest", "delivery_scam": "delivery_scam",
    "prize_lottery_scam": "prize_scam", "other_fraud": "other_fraud",
}


@dataclass
class Fusion:
    score: int
    label: str
    confidence: str
    primary: str
    secondary: list[str]
    data: dict[str, Any] | None = None


def risk_score(evidence: list[Evidence]) -> int:
    """Noisy-OR over weighted evidence: many independent signals compound, none can exceed 100."""
    safe = 1.0
    for e in evidence:
        safe *= 1.0 - SEVERITY_WEIGHT[e.severity] * e.confidence
    return _with_brand_floor(evidence, round((1.0 - safe) * 100))


def _with_brand_floor(evidence: list[Evidence], score: int) -> int:
    """A link that claims a known brand but is not its domain, together with a second URL risk (suspicious top-level domain,
    credential path, no HTTPS, lookalike), is at least "suspicious". Without this floor a bare fake bank link scored 36 (caution)."""
    categories = {e.category for e in evidence}
    if "url_shortener" in categories:
        score = max(score, 34)  # a shortened link hides its destination: at least "caution" (2026-10-07)
    lure = "location_media_lure" in categories
    if "disposable_tunnel_host" in categories:
        score = max(score, 75 if lure else 50)  # tunnel address: suspicious; with a location/camera lure: high risk (2026-10-07)
    if "free_hosting_subdomain" in categories and lure:
        score = max(score, 50)
    if "domain_mismatch" in categories:
        mismatch_hosts = " ".join(str(e.metadata.get("registered_domain", "")) for e in evidence if e.category == "domain_mismatch")
        credential_host = any(word in mismatch_hosts for word in ("login", "verify", "secure", "kyc", "netbank", "refund", "update"))
        if categories & {"suspicious_tld", "credential_path_terms", "no_https", "lookalike_domain"} or credential_host:
            return max(score, 60)
    # Established institutional domain (certificates for a year or more, under .ac.in / .gov.in / .edu) with no medium or high risk
    # evidence other than the classifier: low risk. Verified 2026-10-07 on psitche.ac.in (the college's official domain).
    if "established_institution" in categories and "disposable_tunnel_host" not in categories and "domain_mismatch" not in categories:
        other_risk = [e for e in evidence if e.category not in {"ml_phishing_pattern", "established_institution"}
                      and e.severity in (Severity.medium, Severity.high)]
        if not other_risk:
            score = min(score, 15)
    return score


def label_for(score: int) -> str:
    return next(lbl for floor, lbl in BANDS if score >= floor)


def classify(evidence: list[Evidence]) -> tuple[str, list[str]]:
    cats = {e.category for e in evidence if e.severity != Severity.informational}
    matched: list[str] = []
    for cat, name in CATEGORY_PRIORITY:
        if cat in cats and name not in matched:
            matched.append(name)
    if matched:
        return matched[0], matched[1:]
    for item in evidence:
        if item.source == "ml_classifier" and item.severity != Severity.informational:
            category = ML_CATEGORY_MAP.get(str(item.metadata.get("category", "")))
            if category:
                return category, []
    return "none_detected", []


def assess_confidence(module_status: dict[str, str], input_ok: bool = True,
                      module_quality: dict[str, float] | None = None) -> str:
    """High/medium/low from module coverage, optionally discounted by modality quality."""
    if not input_ok:
        return "low"
    if not module_status:
        return "low"
    weights = {"completed": 1.0, "not_run": 0.5, "unavailable": 0.4, "failed": 0.0, "timeout": 0.0}
    quality = module_quality or {}
    cov = sum(weights.get(s, 0.0) * max(0.0, min(1.0, quality.get(name, 1.0)))
               for name, s in module_status.items()) / len(module_status)
    if quality:
        # A modality that reports poor recognition quality must lower the aggregate
        # confidence even when the downstream deterministic rules completed.
        quality_avg = sum(max(0.0, min(1.0, value)) for value in quality.values()) / len(quality)
        cov *= 0.5 + 0.5 * quality_avg
    result = "high" if cov >= 0.85 else "medium" if cov >= 0.55 else "low"
    return result if _CONFIDENCE_RANK[result] <= _CONFIDENCE_RANK[CONFIDENCE_CAP] else CONFIDENCE_CAP


def _limited_context_is_only_low_confidence_cause(
    module_status: dict[str, str], input_ok: bool, module_quality: dict[str, float] | None,
) -> bool:
    """Allow analyzed short text to keep its score-based label when context alone lowers confidence."""
    quality = module_quality or {}
    text_quality = quality.get("text_rules")
    if module_status.get("text_rules") != "completed" or text_quality is None or text_quality >= 1.0:
        return False
    without_text_context = {name: value for name, value in quality.items() if name != "text_rules"}
    return assess_confidence(module_status, input_ok, without_text_context) != "low"


def coverage_requires_unable_to_verify(score: int, confidence: str, module_status: dict[str, str],
                                      input_ok: bool, module_quality: dict[str, float] | None) -> bool:
    """Apply the shared insufficient-coverage rule used by every strategy."""
    completed = any(status == "completed" for status in module_status.values())
    # Agreement is an output-confidence adjustment, not module coverage.  Use
    # the coverage-only confidence here so ML/rules disagreement does not turn
    # an otherwise completed analysis into unable_to_verify.
    coverage_confidence = assess_confidence(module_status, input_ok, module_quality)
    context_is_only_low_cause = _limited_context_is_only_low_confidence_cause(
        module_status, input_ok, module_quality,
    )
    return not input_ok or not completed or (coverage_confidence == "low" and score < 50 and not context_is_only_low_cause)


def fuse(evidence: list[Evidence], module_status: dict[str, str], input_ok: bool = True,
         module_quality: dict[str, float] | None = None, strategy_name: str | None = None,
         strategy_params: dict[str, Any] | None = None, input_token_count: int | None = None) -> Fusion:
    strategy_name = strategy_name or os.getenv("TRUSTSHIELD_FUSION", DEFAULT_FUSION_STRATEGY).strip().lower()
    if strategy_name != "legacy":
        from services.analysis.fusion.strategies import apply_strategy
        result = apply_strategy(strategy_name, evidence, module_status, input_ok, module_quality,
                                severity_weight=SEVERITY_WEIGHT, input_token_count=input_token_count,
                                **(strategy_params or {}))
        score = _with_brand_floor(evidence, result.score)
        label = result.label if score == result.score else label_for(score)
        return Fusion(score, label, result.confidence, result.primary, result.secondary, result.data)
    score = risk_score(evidence)
    confidence = assess_confidence(module_status, input_ok, module_quality)
    label = label_for(score)
    if coverage_requires_unable_to_verify(score, confidence, module_status, input_ok, module_quality):
        label = "unable_to_verify"
    primary, secondary = classify(evidence)
    return Fusion(score, label, confidence, primary, secondary)
