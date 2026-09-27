"""Deterministic intent classification: the fix for "yo" -> "Low Risk 0/100". No LLM decides intent."""
import pytest

from services.chat.explain import followup_intent
from services.chat.intent import classify_intent


def go(message, has_attachment=False, has_context=False):
    return classify_intent(message, has_attachment, has_context, followup_intent).intent


@pytest.mark.parametrize("message,expected", [
    ("yo", "greeting"), ("hi", "greeting"), ("hello", "greeting"), ("hey", "greeting"), ("thanks", "greeting"),
    ("thanks!", "greeting"), ("cool", "greeting"), ("good morning", "greeting"), ("who are you?", "greeting"),
    ("what is phishing?", "general_cyber_qa"), ("what is smishing", "general_cyber_qa"), ("what is vishing", "general_cyber_qa"),
    ("how does QR fraud work?", "general_cyber_qa"), ("how does upi fraud work", "general_cyber_qa"),
    ("what is a digital arrest scam", "general_cyber_qa"), ("what is sim swapping", "general_cyber_qa"),
    ("can scanning a qr code steal money", "general_cyber_qa"), ("why do scammers create urgency", "general_cyber_qa"),
    ("how do i verify a bank sms", "general_cyber_qa"), ("what should i do if i clicked a phishing link", "general_cyber_qa"),
    ("how do i report fraud in india", "general_cyber_qa"), ("difference between phishing and spam", "general_cyber_qa"),
    ("what is TrustShield?", "product_qa"), ("how does trustshield work", "product_qa"), ("are you chatgpt", "product_qa"),
    ("do you browse websites", "product_qa"), ("do you store my messages", "product_qa"), ("how accurate are you", "product_qa"),
    ("what model do you use", "product_qa"), ("do you guarantee something is safe", "product_qa"), ("what can you do", "product_qa"),
    ("write me a poem", "off_topic"), ("tell me a joke", "off_topic"), ("what's the weather", "off_topic"),
    ("Your account has been blocked, verify now", "analyze_text"),
    ("Hi, the team lunch is at 1pm tomorrow", "analyze_text"),
    ("check this", "analyze_text"),
])
def test_intent_matrix(message, expected):
    assert go(message) == expected


def test_url_always_wins_over_greeting_language():
    assert go("Hey bro your SBI account is blocked click http://sbi-kyc-update.xyz/login") == "analyze_text"


def test_url_always_wins_over_question_language():
    assert go("Can you check https://example.com/login?") == "analyze_text"


def test_attachment_always_wins_over_everything():
    assert go("what is phishing?", has_attachment=True) == "analyze_attachment"
    assert go("yo", has_attachment=True) == "analyze_attachment"


def test_followup_wins_over_general_question_only_when_it_actually_matches_followup_pattern():
    assert go("why is this suspicious?", has_context=True) == "follow_up_analysis"
    # a genuine general-knowledge question is not swallowed by follow-up just because a context exists
    assert go("what is phishing?", has_context=True) == "general_cyber_qa"


def test_empty_message():
    assert go("") == "empty"
    assert go("   ") == "empty"


def test_ambiguous_short_benign_text_defaults_to_analysis_not_chat():
    # deliberately ambiguous: not a greeting phrase, not a question pattern -> safe default is still analysis
    assert go("see you tomorrow at the office") == "analyze_text"
