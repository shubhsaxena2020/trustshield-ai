"""Deterministic conversational intent classifier. No language model decides intent.

Fixes the core product bug this phase exists to fix: every message used to be treated as an analysis request, so "yo" scored
"Low Risk — 0/100". This module decides FIRST whether a message is casual chat, a general or product question, or something
that should be analysed — and analysis always wins when there is real signal to analyse (docs/INTENT_ROUTING.md, priority rule).

Priority order (highest first) — this is the "misrouting safety" rule: a scam message that happens to start with "hey" must
still be analysed.
  1. An attachment is always ANALYZE_ATTACHMENT (unchanged from the existing router).
  2. A message containing a URL is always ANALYZE_URL, regardless of greeting/question wording around it.
  3. A short follow-up matching a known follow-up intent, with an existing signed analysis context, is FOLLOW_UP_ANALYSIS.
  4. A bare greeting/small-talk phrase with nothing else in the message is GREETING.
  5. A recognizable general cybersecurity question is GENERAL_CYBER_QA.
  6. A recognizable question about TrustShield itself is PRODUCT_QA.
  7. A message clearly off the product's cybersecurity scope (per docs/AI_ASSISTANT_ARCHITECTURE.md's domain-scope rule) is OFF_TOPIC.
  8. A question or request to the assistant on any other topic, with no URL, no attachment, no fraud-action signal and no
     request for personal data ("your bank details…"), is GENERAL_CHAT — answered as chat, never scored as content.
  9. Anything else — including short benign text and, deliberately, anything ambiguous — is ANALYZE_TEXT: the pipeline always
     produces an honest result (including "Low Risk" for genuinely benign text), so defaulting to analysis is the safe choice.
Empty input is EMPTY.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Literal

from services.analysis.normalization.text import extract_urls

Intent = Literal["greeting", "assistant_qa", "general_cyber_qa", "product_qa", "off_topic", "general_chat", "analyze_url",
                 "analyze_text", "analyze_attachment", "follow_up_analysis", "empty"]

# Questions addressed to the assistant itself ("who are you", "tell me more about yourself"). They are answered as chat, never
# scored as content: before this rule they fell through to analyze_text and produced a Low Risk 0/100 card.
_ASSISTANT_PATTERNS = [
    r"^(who|what) (are|r) (you|u)\b", r"\btell me (more )?about (yourself|you)\b", r"\bintroduce yourself\b",
    r"^(how are|how r) (you|u)\b", r"\bare you (a bot|a robot|real|human)\b", r"\bwhat('s| is) your (name|purpose|job)\b",
    r"^(hi|hey|hello|yo)[ ,!]+(who|what) (are|r) (you|u)\b",
]

_GREETINGS = {"yo", "hi", "hey", "hello", "sup", "howdy", "yo!", "hi!", "hey!", "hello!",
              "good morning", "good afternoon", "good evening", "morning", "evening",
              "thanks", "thank you", "thanks!", "thx", "cool", "ok", "okay", "lol", "haha",
              "who are you", "who are you?"}

_PRODUCT_PATTERNS = [
    r"\btrustshield\b", r"\bwhat (can|do) you do\b", r"\bhow (do|does) you work\b", r"\bhow (do|does) trustshield work\b",
    r"\bare you chatgpt\b", r"\bdo you (store|save|keep) my\b", r"\bdo you browse\b", r"\bdo you (visit|open) websites\b",
    r"\bhow accurate (are|is) you\b", r"\bwhat model (do you|does trustshield) use\b", r"\bwhat can you do\b",
    r"\bwho (made|built|created) you\b", r"\byour (model|accuracy|capabilities)\b", r"\bdo you guarantee\b",
]

_CYBER_TERMS = [
    r"\bphishing\b", r"\bsmishing\b", r"\bvishing\b", r"\bdigital arrest\b", r"\bsim.?swap(ping)?\b", r"\bqr fraud\b",
    r"\bqr code\b.*\b(steal|scam|fraud)\b", r"\bupi fraud\b", r"\bhow does upi fraud\b", r"\bsender id\b.*\bspoof",
    r"\bwhy do scammers\b", r"\bverify a bank\b", r"\bclicked a phishing\b", r"\breport fraud\b",
    r"\bdifference between phishing and spam\b", r"\bwhat is (a |an )?(scam|fraud|malware|ransomware|spyware)\b",
    r"\bkyc scam\b", r"\bcourier scam\b", r"\bjob scam\b", r"\bwhatsapp scam\b", r"\botp\b.*\b(scam|fraud|share)\b",
    # NOTE: the earlier generic openers (^what is, ^how do, ^explain, ^tell me about…) were removed when general_chat was
    # introduced: a bare "how do" is not a cybersecurity question — "how do I make pasta?" must not be routed to
    # general_cyber_qa. The topic-specific patterns above already cover every previously tested cyber question.
]

_OFF_TOPIC_PATTERNS = [
    r"\bwrite (me |us )?a (poem|song|story|essay)\b", r"\btell me a joke\b", r"\bwhat's the weather\b",
    r"\bwho won\b", r"\brecipe for\b", r"\btranslate this\b", r"\bcode (a|me|for)\b.*\b(game|app|website)\b",
]

# Independent review finding (FIND-003): the chat/education/product/off-topic patterns above are necessarily broad ("what
# is", "how do", "trustshield", any off-topic phrase anywhere), so a scam message can be built to also match one of them —
# e.g. "How do I verify my KYC? Send the OTP to this number immediately." matched general_cyber_qa and skipped analysis. This
# is a second, independent safety net: any of these concrete fraud-action signals forces analysis regardless of what else the
# message also matches, mirroring the URL/attachment priority rule above.
_SUSPICIOUS_SIGNAL_PATTERNS = [
    r"\botp\b", r"\bone.?time (password|code)\b", r"\bverification code\b", r"\bsecurity code\b",
    r"\baccount.{0,20}\b(blocked|suspended|frozen|locked|expir\w*)\b", r"\bkyc\b.{0,20}\b(expir\w*|updat\w*|verif\w*)\b",
    r"\bupi\b.{0,20}\b(pin|approve|collect request)\b", r"\bcvv\b", r"\bpin number\b", r"\bcard number\b",
    r"\bsend (the |your )?(otp|code|pin|cvv)\b", r"\breply with (your |the )?(otp|code|pin)\b",
    r"\brestore access\b", r"\bunlock (your|this) account\b", r"\bact (now|immediately)\b", r"\bwithin 24 hours\b",
    r"\bclaim your (prize|reward|refund)\b", r"\bpay\b.{0,15}\b(now|immediately|to (receive|release))\b",
]


def has_suspicious_signal(text: str) -> bool:
    t = _norm(text)
    return any(re.search(p, t) for p in _SUSPICIOUS_SIGNAL_PATTERNS)

FOLLOWUP_MAX_CHARS = 240
FOLLOWUP_MAX_WORDS = 30

# Found via a live-LLM test (item 59 of the 9Router integration brief): "Explain that in very simple words." matched the
# generic `^explain\b` cyber-education ancher above and lost its analysis context, so the assistant re-explained phishing
# in general instead of simplifying the CURRENT result. A message that reformulates/simplifies something already in
# context ("that"/"it"/"this") must be routed as a follow-up before the generic education patterns get a chance to match.
_CONTEXTUAL_REPHRASE_PATTERN = re.compile(
    r"\b(explain|simplify|rephrase|reformulate|shorten|summarize|say|make)\b.{0,20}\b(that|it|this)\b|"
    r"\b(in simple(r)? (words|terms)|more simply|differently|shorter|to a (non.?technical|beginner)|"
    r"(three|3|top)\b.{0,30}\b(reasons?|points?)|why does (that|it|this) matter)\b", re.I)


def _norm(text: str) -> str:
    return re.sub(r"\s+", " ", text.strip().lower())


def is_greeting(text: str) -> bool:
    t = _norm(text).rstrip("!.? ")
    return t in _GREETINGS or (len(t.split()) <= 3 and t in _GREETINGS)


def is_product_question(text: str) -> bool:
    t = _norm(text)
    return any(re.search(p, t) for p in _PRODUCT_PATTERNS)


def is_general_cyber_question(text: str) -> bool:
    t = _norm(text)
    return any(re.search(p, t) for p in _CYBER_TERMS)


def is_off_topic(text: str) -> bool:
    t = _norm(text)
    return any(re.search(p, t) for p in _OFF_TOPIC_PATTERNS)


# general_chat: questions or requests addressed to the assistant on any topic (maths, cooking, writing,…) that carry no
# URL, no attachment, no fraud-action signal and no request for personal data. Kept narrow so plain statements ("check
# this") keep the safe analyze_text default. Questions are off-topic subject matter, but a question form alone is not
# enough for general_cyber_qa, hence its own intent (checked after cyber/product so scope questions keep winning).
_GENERAL_CHAT_QUESTION_STARTERS = re.compile(
    r"^(what|whats|what's|who|whens|when is|when was|where|why|which|how|when;\s)*\b(what|who|when|where|why|which|how)\b")
_GENERAL_CHAT_AUX_STARTER = re.compile(
    r"^(is|are|can|could|do|does|did|will|would|should|shall)\b")
# A request is only chat when the message itself opens with the request ("Write me a haiku"), never when a lure contains
# "give me a ..." mid-message.
_GENERAL_CHAT_REQUEST_VERBS = re.compile(
    r"^(write|give me|give us|make me|make a|tell me|suggest|recommend|list|draft|create)\b.{0,20}\b(me|a|an|the|some|us)\b")
_GENERAL_CHAT_PERSONAL_DATA = re.compile(
    r"\b(your|my|mine)\b.{0,15}\b(bank|account|card|password|passcode|otp|upi|aadhaar|pan|cvv|pin|credentials|details?|information|bvn|ssn)\b")


# A question that points at content the assistant has not seen ("is this legit?", "why is it suspicious?", "what does the
# message say?") is not general chat: nothing is in view to answer from, so it keeps the analyse-by-default path.
_REFERS_TO_UNSEEN_CONTENT = re.compile(
    r"\b(this|that|these|those|it|the (message|link|sms|text|email|screenshot|attachment|url|qr|one|above|below|file))\b")


# Safety gate (independent review, critical): a message that asks the reader to pay, send, share, approve, verify or click, or
# that names a payment/credential/urgency object, is content to analyse, never chat, education or product Q&A. Plain
# education questions ("what is phishing?") contain none of these action or object words, so they still answer normally.
_ACTION_OR_OBJECT_RISK = re.compile(
    r"\b(pay|paid|payment|send|sent|share|sharing|transfer|approve|confirm|verify|click|enter|submit|provide|deposit|fee|fine|"
    r"refund|reconnect|claim|release|unlock|download|install|reply|call (the|this) number)\b|"
    r"\b(otp|mpin|pin|password|passcode|cvv|upi|netbanking|net banking|yono|gpay|phonepe|paytm|crypto\w*|bitcoin|kyc|blocked|"
    r"suspended|arrest\w*|warrant|parcel|prize|lottery|urgent\w*|netbank\w*|collect request|user id|credentials?|"
    r"bill|payable|rupees|dues?|tonight|courier|customs|clearance|duty|consignment|shipment|package|account|alert|"
    r"detected|suspension|suspended|flagged|"
    # job, loan, investment, utility and reward lures (independent review, round 3)
    r"job|jobs|task|tasks|hiring|salary|income|commission|telegram|whatsapp|loan|pre-?approved|sanction\w*|trading|forex|"
    r"returns|electricity|disconnect\w*|cutoff|cut off|reward|cashback|voucher|gift|lucky draw)\b")
# Narrow education exemption: a short definitional question ("what is a digital arrest scam?", "how does UPI fraud work?",
# "how do I spot a fake SMS?") carries no personal pronoun, no digits, and no payment, release or sending action. Such a
# question is education, not a message to check. Any question with an action verb is still analysed.
_DEFINITIONAL_QUESTION = re.compile(
    r"^(?!.*\b(my|your|you|me|we|us|our|this|that|it|these|those)\b)(?!.*\d)"
    r"(what (is|are|s|'s) (a |an |the )?|what does [\w\s'-]{1,30} mean|how does |how do (one|people) |"
    r"how do i (spot|identify|recognize|recognise|avoid|protect|verify|check|report) |explain |define )"
    r"[\w\s'-]{1,60}\??$")
_BLOCKING_ACTION = re.compile(
    r"\b(pay|paid|payment|send|sent|share|sharing|transfer|approve|enter|submit|provide|deposit|fee|fine|refund|reconnect|"
    r"claim|release|unlock|unblock|restore|retrieve|clear|collect|download|install|reply|click|confirm|update|"
    r"call (the|this) number)\b")


def is_definitional_question(text: str) -> bool:
    t = _norm(text)
    return bool(_DEFINITIONAL_QUESTION.search(t)) and not _BLOCKING_ACTION.search(t)


def has_action_or_object_risk(text: str) -> bool:
    return bool(_ACTION_OR_OBJECT_RISK.search(_norm(text)))


def is_general_chat(text: str) -> bool:
    t = _norm(text)
    if _REFERS_TO_UNSEEN_CONTENT.search(t):
        return False
    if not t.endswith("?") and not _GENERAL_CHAT_REQUEST_VERBS.search(t):
        return False
    # the question-word/auxiliary form only counts when it starts the sentence
    if t.endswith("?") and not (_GENERAL_CHAT_QUESTION_STARTERS.search(t) or _GENERAL_CHAT_AUX_STARTER.search(t)):
        return False
    return not _GENERAL_CHAT_PERSONAL_DATA.search(t)


@dataclass
class IntentResult:
    intent: Intent
    reason: str
    urls: list[str] = field(default_factory=list)
    followup_intent: str | None = None

    def public(self) -> dict:
        return {"intent": self.intent, "reason": self.reason, "urls": self.urls[:10], "followup_intent": self.followup_intent}


def classify_intent(message: str, has_attachment: bool, has_context: bool, followup_intent_fn) -> IntentResult:
    """`followup_intent_fn(message) -> str | None` is injected (services.chat.explain.followup_intent) to avoid a circular import."""
    text = (message or "").strip()
    if has_attachment:
        return IntentResult("analyze_attachment", "attachment present: always analysed")
    if not text:
        return IntentResult("empty", "nothing to analyse")

    urls = extract_urls(text)
    if urls:
        return IntentResult("analyze_url" if len(text) < 20 and len(urls) == 1 and text.strip() in (urls[0], f"<{urls[0]}>") else "analyze_text",
                            "message contains a URL: analysis always wins over any greeting/question wording", urls=urls)

    if has_suspicious_signal(text):
        return IntentResult("analyze_text", "message contains a concrete fraud-action signal (OTP/KYC/account-block/payment "
                            "wording): analysis wins even if the message also resembles a question or greeting")

    # A contextual rephrase/simplify request ("explain that simply", "shorter", "to a beginner") wins over the generic
    # education patterns below when an analysis is in context — see _CONTEXTUAL_REPHRASE_PATTERN's comment for the bug this
    # fixes. Without has_context this pattern is harmless: "explain differently" with nothing to explain falls through to
    # the ordinary analyze_text default a few lines down.
    if has_context and len(text) <= FOLLOWUP_MAX_CHARS and _CONTEXTUAL_REPHRASE_PATTERN.search(text):
        intent = followup_intent_fn(text)
        if intent:
            return IntentResult("follow_up_analysis", f"contextual rephrase/simplify request matching follow-up intent '{intent}'",
                                followup_intent=intent)

    # Greeting/off-topic/product/general-knowledge patterns are checked BEFORE the loose follow-up matcher: the follow-up
    # matcher's "can_trust" pattern contains words like "phishing"/"scam"/"fraud", so "What is phishing?" would otherwise be
    # swallowed as a follow-up merely because an analysis happens to be in context. A genuine definitional/product/greeting
    # question should win; a specific reference to "this"/"it" (handled by the patterns below not matching) still falls
    # through to the follow-up check.
    if is_greeting(text):
        return IntentResult("greeting", "matches a greeting/small-talk phrase with no other content")
    if any(re.search(p, _norm(text)) for p in _ASSISTANT_PATTERNS):
        return IntentResult("assistant_qa", "question addressed to the assistant itself: answered as chat, not scored as content")
    if has_action_or_object_risk(text) and not is_definitional_question(text):
        return IntentResult("analyze_text", "asks for a payment, credential or action, or names a payment/urgency object: "
                            "analysed as content, never answered as chat or education")
    if is_off_topic(text):
        return IntentResult("off_topic", "matches an off-scope request pattern (outside cybersecurity/product scope)")
    if is_product_question(text):
        return IntentResult("product_qa", "matches a question about TrustShield itself")
    if is_general_cyber_question(text):
        return IntentResult("general_cyber_qa", "matches a general cybersecurity education pattern")

    if (has_context and len(text) <= FOLLOWUP_MAX_CHARS and len(text.split()) <= FOLLOWUP_MAX_WORDS):
        intent = followup_intent_fn(text)
        if intent:
            return IntentResult("follow_up_analysis", f"short question matching follow-up intent '{intent}' with analysis in context",
                                followup_intent=intent)

    # general_chat is checked after the follow-up matchers (an in-context short question about the CURRENT analysis must
    # still win) and before the analyze_text default. It deliberately ignores has_context: "what's the capital of
    # France?" is general knowledge even when an analysis happens to be in context.
    if is_general_chat(text) and not has_suspicious_signal(text):
        return IntentResult("general_chat", "general question/request to the assistant with no URL, attachment, "
                            "fraud-action signal or personal-data request")

    return IntentResult("analyze_text", "no chat/education/product pattern matched: analysed as content (safe default)")
