# AI assistant — manual review package

## A. Branch / commits
`feature/trustshield-ai-assistant`, off production commit `672ebbb`, HEAD `1ebb317`. Ten commits: intent router, provider
abstraction, endpoint wiring, hallucination/injection test suite, natural-language reply rewrite, UX separation + human-
likeness E2E, documentation set, security-finding fixes (FIND-001/002/003), UX-finding fixes.

## B. Preview URL
`https://trustshield-pt54x9lh2-shubh-saxena-s-projects.vercel.app` (temporary bypass token used for QA, then revoked — request
a fresh one to browse it).

## C. Intent router
`services/chat/intent.py`. Deterministic; ~45-case unit matrix plus a second independent-review-driven safety net
(`has_suspicious_signal`) that forces analysis on OTP/KYC/account-block/payment wording even if the message also matches a
chat/education/product pattern. Verified against all four of the independent reviewer's concrete bypass messages.

## D. AI provider architecture
`services/chat/explain.py`: `DeterministicExplanationProvider` (always available), `OllamaExplanationProvider` (dev-only),
`RemoteLLMProvider` (OpenAI-compatible, production-eligible only with explicit credentials). All three implement the same
five methods (`reply_for_analysis`, `reply_for_followup`, `reply_greeting`, `reply_cyber_qa`, `reply_product_qa`,
`reply_off_topic`); the optional two fall back to the deterministic answer on any error, empty output, or grounding
violation.

## E. Which provider is active in Preview
**Deterministic only.** `TRUSTSHIELD_AI_PROVIDER` is unset on the Preview; no LLM credential is configured or used anywhere
in this deployment. See `docs/PROVIDER_CONFIGURATION.md` for exact activation steps (a stop condition: needs a paid key).

## F. Greeting test result
Verified live on the Preview: `"yo"` → `response_type: "chat"`, reply "Hey! I'm TrustShield AI...", no risk score, no risk
card. This is the core bug fix of this phase (chat E2E: "greeting gets a natural reply, not a score" — PASS).

## G. Cybersecurity Q&A examples
`"What is phishing?"` → educational answer about impersonation and credential theft, no risk card.
`"How does UPI fraud work?"` → explains fake collect-requests and QR swaps, no risk card.
Twelve topics covered in `services/chat/cyber_knowledge.py`; unmatched questions get an honest "ask me about X, Y, Z" reply
rather than a fabricated answer.

## H. Product Q&A examples
`"What can you do?"`, `"Are you ChatGPT?"`, `"How accurate are you?"` (states the sealed 82.1% recall/specificity/precision/F1
and explicitly that the 90% goal was not met — never implies 90% production accuracy), `"Do you browse websites?"` (states
the actual disabled capability by name). All sourced from `services/chat/product_metadata.py`, not invented.

## I. Analysis examples
A pasted phishing message now gets a natural lead sentence ("This looks suspicious. The combination of X and Y is
consistent with fraud. <safe action>.") followed by the structured risk card — not a bullet-list report as the primary
text.

## J. Follow-up examples
`"Why?"` → cites the actual evidence. `"Did you open the website?"` → truthful "No — live webpage inspection is disabled."
`"What should I do?"` → the analysis's own recommended actions. All grounded in the signed `ChatAnalysis`, none from an LLM
in this deployment.

## K. Prompt-injection results
26 regression tests (`tests/integration/test_ai_assistant.py`) covering injection via the analysed message and via the chat
message itself. All pass against the deterministic provider (the only one active). The independent reviewer's FIND-001 note
— that an *optional* LLM path is inherently more injectable than a template — is accurate but not currently exploitable
since no such path is live; documented as a condition for any future provider enablement in `docs/LLM_SECURITY_POLICY.md`.

## L. Hallucination-validation results
15 self-discovered bypasses (before the independent review) plus 9 independent-review-found bypasses (FIND-002) were all
concrete, reproducible, and are now all caught by `grounding_violations`/`chat_grounding_violations`, with negative tests
confirming legitimate safety advice ("never share your OTP") is not falsely flagged. Known residual limitation, stated
honestly in `docs/LLM_SECURITY_POLICY.md`: this is regex-based semantic detection, not a structured-output schema — a
sufficiently novel phrasing could still theoretically slip through if a live LLM provider is ever enabled.

## M. Provider-failure fallback
Simulated via a fake transport in tests (`test_ollama_chat_reply_falls_back_on_capability_hallucination`,
`test_remote_provider_falls_back_on_transport_error`) and via an environment simulation of Ollama selected with no Ollama
installed (`test_deterministic_fallback_keeps_analysis_working_when_llm_unavailable`) — analysis and replies both continue
working normally.

## N. Multimodal behaviour
Unchanged from the prior phase: attachment always analysed (QR + OCR both run); mixed text+URL handled; verified in the E2E
suite (QR upload, screenshot upload, oversize/malformed rejection).

## O. Mobile/accessibility
360/390/430/820px verified with no horizontal overflow (chat E2E). Accessibility (live regions, focus management, 44px
touch targets, non-color risk signalling) was hardened in the prior conversational phase and inherited unchanged here — not
freshly re-audited in this pass.

## P. Security review
Independent review (Codex, `gpt-5.6-luna`) attacked seven specific surfaces named in the brief. Three real findings
(FIND-001 conditional prompt injection, FIND-002 grounding-regex bypasses, FIND-003 intent-router misrouting on scam-shaped
questions) — all fixed and verified against the reviewer's own concrete adversarial inputs. Four surfaces confirmed clean:
signed-context trust boundary, API-key handling, response-field leakage, and Markdown/XSS (no `dangerouslySetInnerHTML`
anywhere in the chat UI).

## Q. Test counts
Backend: 537 passed (`pytest tests -q`). Frontend: typecheck/lint/build all clean. E2E: conversational 29/29 (local and
remote), analyzer 15/15 (remote) / 8/8 (local).

## R. Performance
Not separately re-measured in this pass (the intent router adds one deterministic regex pass before the existing pipeline;
negligible compared to OCR/ML latency measured in the prior phase). Flagged as a gap, not fabricated.

## S. Production unchanged confirmation
Verified at the start and the end of this phase: `https://trustshield-ai-ecru.vercel.app` serves `/`, `/analyzer`, and
`/api/v1/health` at 200 throughout, still on v3, no env var read or written.

## T. Exact manual actions needed next
1. Manually browse the Preview (request a fresh bypass token; it has expired).
2. Decide whether to enable a remote LLM provider — this needs a paid API key and is a stop condition; see
   `docs/PROVIDER_CONFIGURATION.md` for the exact steps and the provider comparison/recommendation.
3. Decide whether to commission a follow-up review once/if a remote provider is actually enabled (FIND-001/002 become
   directly exploitable only then).
4. Decide whether to merge this branch's UX/architecture work back toward the production lineage, and on what timeline
   relative to the separate v4 source-robustness work (which this phase deliberately did not touch, per the brief).
5. No production-promotion decision is pending from this pass.
