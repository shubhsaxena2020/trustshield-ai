"""general_chat intent: general questions/requests to the assistant on any topic, outside the product/cyber scope.

Priority contract (from docs/INTENT_ROUTING.md and the goal spec): URL and attachment first, then fraud-action signals,
then greeting/assistant/cyber/product scopes, then general_chat, then analyze_text as the default for plain content.
A scam message must always be analysed — never swallowed by general_chat.
"""
from services.chat.explain import followup_intent
from services.chat.intent import classify_intent, has_suspicious_signal


def go(message, has_attachment=False, has_context=False):
    return classify_intent(message, has_attachment, has_context, followup_intent).intent


def test_general_knowledge_questions_are_general_chat():
    assert go("what's the capital of France?") == "general_chat"
    assert go("how do I make pasta?") == "general_chat"


def test_creative_requests_are_general_chat():
    assert go("write a haiku about rain") == "general_chat"


def test_personal_data_requests_are_not_general_chat():
    assert go("Can you confirm your bank details by replying?") == "analyze_text"


def test_otp_sharing_questions_are_not_general_chat():
    assert go("Is it safe to share my OTP?") == "analyze_text"


def test_url_message_is_never_general_chat():
    assert go("Why did my account get blocked? verify at http://x.xyz") != "general_chat"


def test_attachment_beats_general_chat():
    assert go("can you summarise this for me please?", has_attachment=True) == "analyze_attachment"


def test_suspicious_signal_beats_general_chat():
    assert not has_suspicious_signal("how do I make pasta?")
    assert go("Give me your card number now") == "analyze_text"


def test_statements_still_default_to_analysis():
    assert go("check this") == "analyze_text"
    assert go("see you tomorrow at the office") == "analyze_text"
    assert go("Hi, the team lunch is at 1pm tomorrow") == "analyze_text"


def test_scope_questions_still_win_over_general_chat():
    assert go("what is phishing?") == "general_cyber_qa"
    assert go("what is TrustShield?") == "product_qa"
    assert go("what's the weather") == "off_topic"
    assert go("who are you?") == "greeting"


def test_general_chat_holds_even_with_analysis_context():
    assert go("what's the capital of France?", has_context=True) == "general_chat"
