"""Deterministic text fraud rules. Each rule yields structured Evidence; no single keyword decides the verdict."""
from __future__ import annotations

import re

from services.analysis.evidence.models import Evidence, Severity
from services.analysis.url_analysis.brands import BRANDS, TEXT_CLAIMS

_NEGATION = re.compile(r"(do not|don't|dont|never|not to|beware|avoid|nobody will ask|will never ask|do n't)[^.!?\n]{0,50}$")
_SENSITIVE = r"(?:otp|one[- ]time password|pin|cvv|password|passcode|card number|card details|net ?banking|login (?:id|details|credentials)|upi pin|aadhaar number)"

RULES: dict[str, dict] = {
    # Location or media capture lures: "track", "GPS", "see who opened", "allow camera". Scams use these with a link that asks the
    # reader to open it (a tracking page captured GPS coordinates in a 2026-10-07 test). Alone it is weak; with a tunnel link it is decisive.
    "location_media_lure": dict(
        pattern=r"\b(gps|live location|share (?:your )?location|track (?:my|your|this|the) (?:location|phone|device|parcel)|tracking link|track (?:me|you|him|her)|location (?:access|tracking)|allow (?:the )?camera|camera access|see who (?:viewed|opened)|view (?:the )?(?:photo|video|document|file) (?:here|now)|verify (?:you are|you're) (?:human|real))\b",
        severity=Severity.medium, confidence=0.6, title="Location or media capture lure",
        desc="The message asks you to share a location, grant camera access, or open a page to track or view something."),
    "urgency": dict(
        pattern=r"\b(immediately|urgent(?:ly)?|right now|asap|within \d+ ?(?:hours?|hrs?|minutes?|mins?)|today only|expires? (?:today|soon|tonight)|final (?:warning|notice|reminder)|last chance|act now|verify now|limited time|before midnight)\b",
        severity=Severity.low, confidence=0.5, title="Urgency / time pressure",
        desc="The message pressures the reader to act quickly, a common social-engineering tactic."),
    "account_threat": dict(
        pattern=r"\b(?:(?:account|card|sim|kyc|pan|aadhaar|aadhar|wallet)\b[^.\n]{0,40}\b(?:blocked|suspended|deactivated|closed|expires?|expired|freez\w*|frozen|terminated|restricted)|kyc (?:update|expir\w*|pending|verification)|(?:will|shall) be (?:blocked|suspended|deactivated|closed|terminated))\b",
        severity=Severity.medium, confidence=0.65, title="Account suspension / KYC threat",
        desc="The message threatens loss of access to an account or service unless you act."),
    "payment_request": dict(
        pattern=r"\b(?:registration fee|processing fee|security deposit|advance payment|refundable deposit|clearance fee|customs fee|pay (?:rs\.?|₹|inr)\s?\d+|transfer (?:rs\.?|₹|inr)?\s?\d+|(?:send|pay)[^.\n]{0,20}(?:via|through|using) (?:upi|gpay|google pay|phonepe|paytm)|scan (?:the |this )?qr)",
        severity=Severity.medium, confidence=0.65, title="Payment or fee requested",
        desc="The message asks for money, a fee or a deposit, which legitimate offers rarely require up front."),
    "impersonation_authority": dict(
        pattern=r"\b(?:cbi|police|cyber ?crime|customs|narcotics|enforcement directorate|income tax (?:dept|department|officer)|trai|rbi|supreme court|high court|bank officer|customer (?:care|support) executive|senior officer|dcp|ips officer)\b",
        severity=Severity.low, confidence=0.5, title="Authority or official impersonation cues",
        desc="The message invokes a government agency, bank or officer; scammers use this to intimidate."),
    "fake_job": dict(
        pattern=r"\b(?:work from home|part[- ]time (?:job|work)|earn (?:up to )?(?:rs\.?|₹|inr)?\s?\d[\d,]*\s?(?:/-)?\s?(?:per|a|/) ?(?:day|daily|week|hour)|no interview|instant(?:ly)? (?:selected|hired|joining)|selected for (?:the )?(?:job|position)|(?:like|subscribe) (?:and|&) ?(?:earn|share)|typing job|data entry job|apply (?:on|via|through) whatsapp|hr (?:manager )?whatsapp)\b",
        severity=Severity.medium, confidence=0.55, title="Fake-job style offer",
        desc="The offer matches patterns of recruitment scams (easy money, instant selection, no interview)."),
    "investment_scam": dict(
        pattern=r"\b(?:guaranteed (?:returns?|profit|income)|zero risk|risk[- ]free|double your money|100% (?:profit|returns?|safe)|assured (?:returns?|profit)|daily (?:profit|returns?)|vip (?:group|tips)|stock tips|join (?:our )?(?:telegram|whatsapp) group|crypto (?:trading|investment)|high returns?)\b",
        severity=Severity.medium, confidence=0.6, title="Investment-scam style promises",
        desc="Promises of guaranteed or unrealistic returns are a hallmark of investment fraud."),
    "lottery_prize": dict(
        pattern=r"\b(?:you (?:have )?won|lucky draw|lottery|claim your (?:prize|reward|gift)|congratulations[^.\n]{0,30}(?:won|selected|winner)|cashback (?:of|worth)|reward points? (?:will )?expire)\b",
        severity=Severity.medium, confidence=0.55, title="Prize / reward lure",
        desc="Unsolicited prizes and rewards are used to lure victims into sharing details or paying fees."),
    "delivery_scam": dict(
        pattern=r"\b(?:(?:package|parcel|shipment|courier)[^.\n]{0,50}(?:held|on hold|undelivered|could not be delivered|address (?:is )?(?:incomplete|invalid)|customs)|redelivery fee|update (?:your )?delivery address)\b",
        severity=Severity.medium, confidence=0.5, title="Delivery-problem lure",
        desc="A vague delivery problem with a link or fee is a frequent smishing pattern."),
}

_ARREST = r"\b(?:arrest\w*|warrant|case (?:has been )?registered|money laundering|narcotics|illegal parcel|fir\b|digital arrest|drugs? (?:found|seized|in (?:your )?parcel))\b"
_ISOLATION = r"\b(?:video call|skype|do not (?:tell|inform|disclose)|don't (?:tell|inform)|confidential|stay on (?:the )?(?:line|call)|safe account|verification account|rbi account|supervised account|do not disconnect)\b"


def _hits(pattern: str, text: str) -> list[str]:
    return [m.group(0) for m in re.finditer(pattern, text, re.I)][:5]


def _ev(category: str, sev: Severity, conf: float, title: str, desc: str, **meta) -> Evidence:
    return Evidence(source="text_rules", category=category, severity=sev, confidence=conf, title=title, description=desc, metadata=meta)


def _credential_requests(text: str) -> tuple[list[str], list[str]]:
    """Return (real requests, warnings). 'Do not share your OTP' is a warning, not a request."""
    requests, warnings = [], []
    patterns = [
        rf"\b(?:share|send|enter|provide|tell|submit|confirm|verify|update|give|reply with|forward)\b[^.\n]{{0,35}}\b{_SENSITIVE}\b",
        rf"\b{_SENSITIVE}\b[^.\n]{{0,25}}\b(?:share|send|enter|provide|required|needed|to verify)\b",
    ]
    for p in patterns:
        for m in re.finditer(p, text, re.I):
            before = text[max(0, m.start() - 60):m.start() + 12].lower()
            (warnings if _NEGATION.search(before) else requests).append(m.group(0))
    return requests[:5], warnings[:3]


def run_text_rules(text: str) -> list[Evidence]:
    t = text.lower()
    out: list[Evidence] = []
    found: set[str] = set()

    for cat, r in RULES.items():
        hits = _hits(r["pattern"], t)
        if hits:
            found.add(cat)
            out.append(_ev(cat, r["severity"], r["confidence"], r["title"], r["desc"], matches=hits))

    reqs, warns = _credential_requests(t)
    if reqs:
        found.add("credential_request")
        out.append(_ev("credential_request", Severity.high, 0.8, "Asks for OTP, PIN, password or card details",
                       "Banks and legitimate services never ask you to share these secrets by message or call.", matches=reqs))
    if warns and not reqs:
        out.append(_ev("security_warning_present", Severity.informational, 0.7, "Message warns against sharing secrets",
                       "Text tells the reader NOT to share OTP/PIN, which is typical of genuine notifications.", matches=warns))

    arrest, isolation = _hits(_ARREST, t), _hits(_ISOLATION, t)
    if arrest and isolation and ("impersonation_authority" in found or "payment_request" in found or len(arrest) + len(isolation) >= 3):
        found.add("digital_arrest")
        out.append(_ev("digital_arrest", Severity.critical, 0.85, "Digital-arrest scam pattern",
                       "Arrest/legal threats combined with secrecy, video-call or 'safe account' pressure match known digital-arrest scams.",
                       arrest=arrest, isolation=isolation))

    # Compound signals: context matters more than any keyword.
    if "credential_request" in found and found & {"urgency", "account_threat"}:
        out.append(_ev("credential_pressure", Severity.high, 0.85, "Secret requested under pressure",
                       "A request for OTP/PIN/password combined with urgency or an account threat is the core phishing pattern."))
    if "fake_job" in found and "payment_request" in found:
        out.append(_ev("job_fee_scam", Severity.high, 0.85, "Job offer that requires a payment",
                       "Genuine employers do not charge registration, training or security fees."))
    if "investment_scam" in found and found & {"urgency", "payment_request"}:
        out.append(_ev("investment_pressure", Severity.high, 0.75, "Investment promise with pressure or payment",
                       "Guaranteed returns combined with urgency or a payment request strongly suggests investment fraud."))
    return out


def claimed_brands(text: str) -> list[str]:
    t = text.lower()
    return [k for k, p in TEXT_CLAIMS.items() if re.search(p, t)]


def brand_name(key: str) -> str:
    return BRANDS[key][0]
