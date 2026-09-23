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
    return (matched[0], matched[1:]) if matched else ("none_detected", [])


def assess_confidence(module_status: dict[str, str], input_ok: bool = True) -> str:
    """High/medium/low from module coverage. 'unavailable'/'not_run' modules reduce coverage; failures reduce it more."""
    if not input_ok:
        return "low"
    if not module_status:
        return "low"
    weights = {"completed": 1.0, "not_run": 0.5, "unavailable": 0.4, "failed": 0.0, "timeout": 0.0}
    cov = sum(weights.get(s, 0.0) for s in module_status.values()) / len(module_status)
    return "high" if cov >= 0.85 else "medium" if cov >= 0.55 else "low"


def fuse(evidence: list[Evidence], module_status: dict[str, str], input_ok: bool = True) -> Fusion:
    score = risk_score(evidence)
    confidence = assess_confidence(module_status, input_ok)
    label = label_for(score)
    completed = any(s == "completed" for s in module_status.values())
    if not input_ok or not completed or (confidence == "low" and score < 50):
        label = "unable_to_verify"
    primary, secondary = classify(evidence)
    return Fusion(score, label, confidence, primary, secondary)
