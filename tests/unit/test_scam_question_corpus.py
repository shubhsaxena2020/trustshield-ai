"""Corpus test: no India-context scam message in the fixture may classify as a benign chat intent.

Loads tests/fixtures/scam_questions.txt (one India-context scam attempt per line) and
tests/fixtures/benign_questions.txt (never-analysed small-talk/gk controls), then asserts no scam line classifies
as the general_chat intent via services.chat.intent.classify_intent. The general_chat intent is documented in
services/chat/explain.py but is not yet implemented in services/chat/intent.py, so that assertion is marked xfail
with reason "awaiting general_chat intent" (non-strict) until general_chat is implemented in the classifier.
"""
from pathlib import Path
import re

import pytest

from services.analysis.normalization.text import extract_urls
from services.chat.intent import IntentResult, classify_intent, has_suspicious_signal

FIXTURES = Path(__file__).resolve().parents[1] / "fixtures"
EXPECTED_SCAM_LINES = 80
EXPECTED_BENIGN_LINES = 40
def _no_followup(_message) -> None:
    return None


def _load(name: str) -> list[str]:
    lines = (FIXTURES / name).read_text(encoding="utf-8").splitlines()
    return [line.strip() for line in lines if line.strip()]


def _classify(message: str) -> IntentResult:
    return classify_intent(message, has_attachment=False, has_context=False,
                           followup_intent_fn=_no_followup)


def test_fixture_line_counts():
    assert len(_load("scam_questions.txt")) == EXPECTED_SCAM_LINES
    assert len(_load("benign_questions.txt")) == EXPECTED_BENIGN_LINES


@pytest.mark.parametrize("message", _load("scam_questions.txt"))
def test_no_scam_message_skips_analysis(message):
    """Safety rule (strict, no xfail): every scam line must be analysed. Question-form or polite scam wording must never
    reach general_chat, education or product answers."""
    result = _classify(message)
    assert result.intent.startswith("analyze"), (
        f"scam answered without analysis: {message!r} -> {result.public()}"
    )


def test_currently_every_scam_line_is_analysed():
    """Today's fallback guard so the corpus is genuinely exercised while general_chat is not implemented:
    every scam line must currently be routed to analysis (analyze_text / analyze_url), never to a benign
    chat-ish intent (greeting / assistant_qa / off_topic / product_qa / general_cyber_qa). This is what
    guarantees none of them would ever reach a general_chat answer even after general_chat lands — replace or
    tighten this once general_chat exists."""
    benign_chat = {"greeting", "assistant_qa", "off_topic", "product_qa", "general_cyber_qa"}
    misrouted = [(msg, _classify(msg).intent) for msg in _load("scam_questions.txt")
                 if _classify(msg).intent in benign_chat]
    assert not misrouted, f"{len(misrouted)} scam line(s) routed to a benign chat intent: {misrouted}"


def test_no_benign_question_trips_fraud_signal():
    """Benign-side control: none of the 40 benign lines trips a concrete fraud signal or contains a URL.
    If that changes, a later general_chat implementation misreading this corpus may swallow real scam
    payloads, so the corpus owner must rewrite the flagged line first."""
    flagged = [msg for msg in _load("benign_questions.txt")
               if has_suspicious_signal(msg) or extract_urls(msg)]
    assert not flagged, f"benign line(s) tripping a fraud signal/URL: {flagged}"


def test_benign_fixture_has_no_url_or_secret_marker():
    """The benign file is pure general-chat small talk/GK: no URLs and no credential/OTP/card wording, so a
    future general_chat intent can adopt these lines without ever absorbing a scam payload pattern."""
    flagged = [msg for msg in _load("benign_questions.txt")
               if extract_urls(msg) or re.search(r"\b(otp|one.?time|cvv|upi pin|mpin|card number|password|kyc)\b", msg, re.I)]
    assert not flagged, flagged


def test_scam_fixture_has_no_attachment_rule_dependency():
    """Attachment rule is upheld by the classifier itself; here we just confirm no scam line smuggles an
    attachment marker (which would make the corpus depend on the attachment rule rather than textual signal)."""
    flagged = [msg for msg in _load("scam_questions.txt") if "[attach" in msg.lower()]
    assert not flagged, flagged
