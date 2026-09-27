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
  8. Anything else — including short benign text and, deliberately, anything ambiguous — is ANALYZE_TEXT: the pipeline always
     produces an honest result (including "Low Risk" for genuinely benign text), so defaulting to analysis is the safe choice.
Empty input is EMPTY.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Literal

from services.analysis.normalization.text import extract_urls

Intent = Literal["greeting", "general_cyber_qa", "product_qa", "off_topic", "analyze_url", "analyze_text",
                 "analyze_attachment", "follow_up_analysis", "empty"]

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
    r"^what is\b", r"^what's\b", r"^how does\b", r"^how do\b", r"^explain\b", r"^tell me about\b",
]

_OFF_TOPIC_PATTERNS = [
    r"\bwrite (me |us )?a (poem|song|story|essay)\b", r"\btell me a joke\b", r"\bwhat's the weather\b",
    r"\bwho won\b", r"\brecipe for\b", r"\btranslate this\b", r"\bcode (a|me|for)\b.*\b(game|app|website)\b",
]

FOLLOWUP_MAX_CHARS = 240
FOLLOWUP_MAX_WORDS = 30


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

    # Greeting/off-topic/product/general-knowledge patterns are checked BEFORE the loose follow-up matcher: the follow-up
    # matcher's "can_trust" pattern contains words like "phishing"/"scam"/"fraud", so "What is phishing?" would otherwise be
    # swallowed as a follow-up merely because an analysis happens to be in context. A genuine definitional/product/greeting
    # question should win; a specific reference to "this"/"it" (handled by the patterns below not matching) still falls
    # through to the follow-up check.
    if is_greeting(text):
        return IntentResult("greeting", "matches a greeting/small-talk phrase with no other content")
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

    return IntentResult("analyze_text", "no chat/education/product pattern matched: analysed as content (safe default)")
