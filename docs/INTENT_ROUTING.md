# Intent routing

**Status: CURRENT.** `services/chat/intent.py`. No language model decides intent — every rule below is a regex/keyword match,
unit-tested in `tests/unit/test_intent_router.py` (matrix of ~45 cases).

## The bug this fixes
Before this phase, every message went straight into the fraud-analysis pipeline. `"yo"` came back "Low Risk — 0/100". That
is the product failure this module exists to fix.

## Priority order (highest first — this is the "misrouting safety" rule)
1. **Attachment present** → `analyze_attachment`. Always, regardless of any accompanying text.
2. **Message contains a URL** → `analyze_url` / `analyze_text`. Always — a scam wrapped in "hey bro" still gets analysed.
3. **Short question, existing signed analysis in context, matches a follow-up pattern** → `follow_up_analysis`.
4. **Bare greeting/small-talk phrase** ("yo", "hi", "thanks", "who are you?") → `greeting`.
5. **Off-topic request** ("write me a poem") → `off_topic`.
6. **Question about TrustShield itself** ("what can you do", "are you ChatGPT") → `product_qa`.
7. **General cybersecurity question** ("what is phishing", "how does UPI fraud work") → `general_cyber_qa`.
8. **Anything else** → `analyze_text` (the safe default: an honest "Low Risk" result costs nothing; silently skipping a real
   scam would).

Greeting/off-topic/product/cyber patterns are checked **before** the follow-up matcher, not after: the follow-up matcher's
own patterns contain words like "phishing"/"fraud"/"scam" (for questions like "can I trust this?"), so without this ordering
"What is phishing?" would be swallowed as a follow-up merely because an analysis happened to be in context.

## What the router does NOT do
It never asks an LLM to classify intent for these deterministic cases. There is no ambiguous-case LLM fallback in this
phase — every case in the test matrix is resolved by a rule. If a genuinely ambiguous message is misclassified as
`analyze_text`, that is the safe direction to fail in (see priority rule 8).
