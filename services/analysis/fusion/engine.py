"""Risk fusion + confidence. Provisional weights/bands: to be tuned on validation data (docs/ACCURACY_VALIDATION.md)."""
from __future__ import annotations

from dataclasses import dataclass

from services.analysis.evidence.models import Evidence, Severity

# Probability-like weight a single piece of evidence contributes at confidence 1.0.
SEVERITY_WEIGHT = {
    Severity.informational: 0.0,
    Severity.low: 0.06,
    Severity.medium: 0.16,
    Severity.high: 0.38,
    Severity.critical: 0.55,
}
BANDS = [(70, "high_risk"), (50, "suspicious"), (25, "caution"), (0, "low_risk")]  # provisional

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
CONFIDENCE_CAP = "medium"
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


def risk_score(evidence: list[Evidence]) -> int:
    """Noisy-OR over weighted evidence: many independent signals compound, none can exceed 100."""
    safe = 1.0
    for e in evidence:
        safe *= 1.0 - SEVERITY_WEIGHT[e.severity] * e.confidence
    return round((1.0 - safe) * 100)


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


def fuse(evidence: list[Evidence], module_status: dict[str, str], input_ok: bool = True,
         module_quality: dict[str, float] | None = None) -> Fusion:
    score = risk_score(evidence)
    confidence = assess_confidence(module_status, input_ok, module_quality)
    label = label_for(score)
    completed = any(s == "completed" for s in module_status.values())
    context_is_only_low_cause = _limited_context_is_only_low_confidence_cause(module_status, input_ok, module_quality)
    if not input_ok or not completed or (confidence == "low" and score < 50 and not context_is_only_low_cause):
        label = "unable_to_verify"
    primary, secondary = classify(evidence)
    return Fusion(score, label, confidence, primary, secondary)
