"""Deterministic explanation provider. Explains only evidence that exists; never invents findings."""
from __future__ import annotations

from services.analysis.evidence.models import Evidence, Severity

LABEL_TEXT = {
    "low_risk": "No significant suspicious indicators were identified in the available analysis.",
    "caution": "Some indicators require independent verification.",
    "suspicious": "Multiple suspicious indicators were identified.",
    "high_risk": "Strong fraud indicators were identified.",
    "unable_to_verify": "The available evidence was insufficient to assess this content reliably.",
}

ACTIONS = {
    "digital_arrest": ["No agency arrests people or demands money over a call or video call. Disconnect and call 1930 (India cyber-crime helpline)."],
    "credential_phishing": ["Do not share OTP, PIN, CVV or passwords with anyone who contacts you."],
    "credential_theft": ["Do not share OTP, PIN, CVV or passwords with anyone who contacts you."],
    "phishing": ["Do not click the link. Open the organization's official website or app by typing its address yourself."],
    "fake_job": ["Do not pay any registration, training or security fee for a job. Verify the employer on its official website."],
    "investment_fraud": ["Ignore guaranteed-return offers. Check whether the entity is SEBI-registered before investing."],
    "payment_fraud": ["Do not send money or scan payment QR codes until the request is verified through an official channel."],
    "delivery_scam": ["Track the parcel only through the courier's official app or website."],
    "prize_scam": ["Genuine prizes do not require fees or personal details. Ignore the message."],
    "account_threat_phishing": ["Contact your bank using the number printed on your card or official app, not the one in the message."],
    "impersonation": ["Verify the sender through an official contact channel before responding."],
    "malware_delivery": ["Do not download or install files from this link."],
}
GENERIC = ["Verify the sender independently through an official contact channel.", "If you already interacted, contact your bank and report at cybercrime.gov.in or call 1930."]


def explain(label: str, evidence: list[Evidence], primary: str, limitations: list[str]) -> tuple[str, list[str]]:
    material = [e for e in evidence if e.severity != Severity.informational]
    parts = [LABEL_TEXT[label]]
    if material and label != "unable_to_verify":
        top = sorted(material, key=lambda e: (list(Severity).index(e.severity), e.confidence), reverse=True)[:4]
        parts.append("Main findings: " + "; ".join(e.title.rstrip(".") for e in top) + ".")
    if limitations:
        parts.append("Limitations: " + " ".join(limitations))
    actions = list(ACTIONS.get(primary, []))
    if label in {"suspicious", "high_risk", "caution"}:
        actions += GENERIC
    elif label == "unable_to_verify":
        actions = ["Treat the content with caution and verify it through an official channel before acting."]
    else:
        actions = ["No action needed based on this analysis, but never share OTPs or passwords and verify unexpected requests."]
    return " ".join(parts), actions
