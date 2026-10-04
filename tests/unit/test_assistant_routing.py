"""Questions addressed to the assistant are answered as chat, never scored as content.

Regression for the live-site failure: "can you tell me more about yourself" was routed to the scam analyzer and returned a
Low Risk 0/100 card. Scam content must still win over any assistant-directed wording.
"""
import pytest

from services.chat.intent import classify_intent


def _no_followup(_message):
    return None


@pytest.mark.parametrize("message", [
    "who are you",
    "Who are you?",
    "can you tell me more about yourself",
    "tell me about you",
    "what are you",
    "how are you",
    "are you a bot",
    "what is your name",
])
def test_assistant_questions_are_chat_not_analysis(message):
    result = classify_intent(message, False, False, _no_followup)
    # "who are you" is already in the greeting list; either chat intent is correct, analysis is not.
    assert result.intent in {"assistant_qa", "greeting"}, result.public()


@pytest.mark.parametrize("message", [
    "Tell me more about yourself. Your SBI account is blocked, share the OTP now.",
    "who are you? Your KYC expired, verify within 24 hours at http://sbi-kyc-update.xyz/login",
    "can you help? Send the OTP to this number immediately",
])
def test_scam_content_still_wins_over_assistant_wording(message):
    result = classify_intent(message, False, False, _no_followup)
    assert result.intent == "analyze_text", result.public()


def test_plain_content_is_still_analysed_by_default():
    result = classify_intent("Hi, the team lunch is at 1pm tomorrow.", False, False, _no_followup)
    assert result.intent == "analyze_text", result.public()
