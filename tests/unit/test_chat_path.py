"""When the scanner may run. Every message reaches the model first; the scanner tool is allowed only for content the user shared.
Regression for 2026-10-06: "what is this techexpo thing?" and "but this is a related question" were scored as content."""
import pytest

from services.api.main import _may_scan


@pytest.mark.parametrize("message", [
    "what is this techexpo thing?",
    "but this is a related question",
    "tell me about yourself",
    "yo",
    "tell me the ways i can get scammed online?",
    "is it safe to share my OTP with someone?",
    "what is phishing?",
    "write a poem about rain",
])
def test_questions_and_remarks_are_never_scanned(message):
    assert _may_scan(message) is False


@pytest.mark.parametrize("message", [
    "Is this safe? URGENT your SBI account is blocked, share OTP at http://sbi-kyc-update.xyz/login",
    "your SBI account is blocked, share the OTP now",
    "Pay 500 rupees to release your parcel",
    "http://sbi-kyc-update.xyz/login",
    "Dear customer your KYC expires today, update now",
    "hey are we still meeting tomorrow at five",
])
def test_shared_content_may_be_scanned(message):
    assert _may_scan(message) is True


def test_refusal_that_names_a_certainty_claim_is_not_flagged():
    from services.chat.explain import chat_grounding_violations
    text = "It reports risk evidence and uncertainty rather than claiming that content is definitely safe or fraudulent."
    assert "claims certainty or verification" not in chat_grounding_violations(text)


def test_scam_described_by_its_promise_is_not_flagged():
    from services.chat.explain import chat_grounding_violations
    text = "Fake investment offers promising guaranteed or unusually high returns are a common scam."
    assert "claims certainty or verification" not in chat_grounding_violations(text)


def test_trustshield_guarantee_is_still_flagged():
    from services.chat.explain import chat_grounding_violations
    assert "claims certainty or verification" in chat_grounding_violations("TrustShield offers guaranteed safety for every link.")


def test_real_certainty_claim_is_still_flagged():
    from services.chat.explain import chat_grounding_violations
    assert "claims certainty or verification" in chat_grounding_violations("This message is definitely safe.")
