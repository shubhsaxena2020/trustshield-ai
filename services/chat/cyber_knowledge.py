"""Deterministic general-cybersecurity education content — always-available fallback answers, matched by keyword.

These are safe, reviewed, general knowledge (not tied to any specific analysis) and are the DeterministicFallbackProvider's
answer for GENERAL_CYBER_QA. An LLM provider may elaborate on the same topic, but must stay within general knowledge and
must never claim a live lookup happened (services.chat.ai_provider's output validation checks this for education replies too).
"""
from __future__ import annotations

import re

TOPICS: dict[str, tuple[tuple[str, ...], str]] = {
    "phishing": (("phishing",),
        "Phishing is a fraud message (email, text or chat) that impersonates a trusted sender to trick you into giving up "
        "credentials, OTPs or money, or into clicking a malicious link. Warning signs: urgency, a request for credentials "
        "or payment, and a link or sender that doesn't quite match the organization it claims to be."),
    "smishing": (("smishing",),
        "Smishing is phishing delivered over SMS/text message — the same tactics (urgency, fake links, credential requests) "
        "but sent as a text rather than an email."),
    "vishing": (("vishing",),
        "Vishing is phishing over a phone call — a scammer impersonates a bank, government office or company by voice, "
        "often creating urgency to get you to share OTPs, card details or make a payment."),
    "digital_arrest": (("digital arrest",),
        "'Digital arrest' scams impersonate police, customs or investigation agencies over video or phone calls, claiming "
        "you're under investigation or 'digital arrest', to pressure you into paying money or sharing personal/banking "
        "details. Real law-enforcement agencies do not arrest or collect fines over a video call."),
    "sim_swap": (("sim swap", "sim-swap", "simswap"),
        "SIM swapping is when a fraudster convinces your mobile carrier to move your phone number to a SIM card they "
        "control — usually via social engineering or a fraudulent request — so they can intercept your OTPs and take "
        "over accounts protected by SMS-based verification."),
    "qr_fraud": (("qr fraud", "qr code fraud", "qr scam", "scanning a qr"),
        "QR fraud usually works one of two ways: the QR code opens a phishing link (styled like a bank or payment page) "
        "that asks for credentials, or — for UPI — a malicious QR is disguised as a 'receive money' code but is actually "
        "a payment REQUEST, so scanning and approving it sends money out of your account rather than into it. Scanning a "
        "QR code by itself, without approving anything afterward, does not directly withdraw money."),
    "upi_fraud": (("upi fraud", "how does upi fraud"),
        "Common UPI fraud patterns: a fake 'collect request' disguised as a refund or cashback (approving it PAYS the "
        "scammer, it doesn't receive money); QR codes swapped at a shop or shared as fake 'receive payment' codes; and "
        "screen-sharing apps used to walk a victim through approving a payment while pretending to help them."),
    "sender_spoofing": (("sender id", "spoofed", "spoof"),
        "Sender IDs and caller IDs can be spoofed or bought commercially by scammers, so a message or call appearing to "
        "come from a bank's name is not proof of authenticity by itself — check the actual link/number and verify "
        "independently through the organization's official app or a number you already trust."),
    "urgency": (("why do scammers create urgency", "why urgency"),
        "Urgency and fear (an account about to be blocked, a legal threat, a narrow deadline) are used deliberately to "
        "stop you from pausing to verify the message — that pause is exactly what catches most fraud, so treating urgency "
        "itself as a warning sign is a reasonable default."),
    "verify_bank_sms": (("verify a bank sms", "verify a bank message"),
        "To verify a bank SMS: don't use any link or number inside the message itself — instead open your bank's official "
        "app, or a number/website you already had saved before receiving the message, and check there directly."),
    "clicked_phishing_link": (("clicked a phishing", "clicked on a phishing", "i clicked a link"),
        "If you clicked a phishing link: don't enter any credentials if you haven't already; if you did enter a password "
        "or OTP, change that password immediately from the official app/site and enable multi-factor authentication; "
        "watch your account activity and report the incident to your bank/service and to India's cybercrime portal "
        "(cybercrime.gov.in) or helpline 1930."),
    "report_fraud_india": (("report fraud in india", "report cyber fraud"),
        "In India, cyber fraud can be reported at the National Cyber Crime Reporting Portal (cybercrime.gov.in) or via the "
        "helpline 1930, alongside informing your bank directly."),
    "phishing_vs_spam": (("difference between phishing and spam",),
        "Spam is unwanted bulk messaging — annoying, but not necessarily deceptive. Phishing is specifically deceptive: it "
        "impersonates a trusted party to steal credentials, money or personal information."),
    "kyc_scam": (("kyc scam",),
        "Fake KYC scams impersonate a bank or telecom provider claiming your KYC will expire, pressuring you to click a "
        "link or share OTP/documents to 're-verify' — legitimate KYC updates are done in person, via the official app, or "
        "through verified in-branch/telecom processes, never by an urgent SMS link."),
}


def match(text: str) -> str | None:
    t = text.lower()
    for _key, (keywords, answer) in TOPICS.items():
        if any(re.search(re.escape(kw), t) for kw in keywords):
            return answer
    return None
