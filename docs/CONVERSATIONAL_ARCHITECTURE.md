# Conversational architecture (branch `feature/trustshield-conversational-next`)

**Status: CURRENT (implemented, tested locally). Not promoted to production.**

The four-tab scanner became a chat-style assistant at `/`. The security backend was not rewritten: the assistant is a routing and
presentation layer over the same pipeline, model (v3), fusion and limits. **A language model never decides the verdict.**

```
browser --> POST /api/v1/chat (JSON)  |  POST /api/v1/chat/upload (multipart image + message + previous analysis)
   services/chat/router.py        deterministic: followup | url | text | image | empty          (no LLM)
   services/chat/orchestrator.py  runs existing analyze_text / analyze_url / QR decode / OCR; merges evidence; re-fuses via pipeline._finish
   services/chat/contract.py      AnalysisResult -> ChatAnalysis (contract 2.0): evidence[], capabilities used/unavailable, uncertainty ...
   services/chat/explain.py       text for the user, generated FROM the contract (deterministic provider by default)
```

## Routing rules (deterministic, unit-tested)
1. Attachment present: image. QR decoding **and** OCR both run; the message is the user's question (URLs in it are still checked).
2. Empty: 422.
3. Short (<=240 chars, <=30 words) question with **no URL**, an existing analysis in context, and a matching follow-up intent: answered
   from the stored analysis. Without context the same words are analysed as ordinary text.
4. A message that is only a URL: URL analysis.
5. Anything else: text analysis; URLs inside are extracted and checked structurally.

## Statelessness and bounds
The server stores no conversation. The browser sends back the previous `ChatAnalysis` as context (validated by pydantic: evidence <= 40,
extracted content <= 4000 chars, bounded fields). Message <= 5000 chars, history <= 6 turns / 12 000 chars, upload <= 4 MB with the
existing image validation, existing rate limits and the heavy-work concurrency gate. Old `/api/v1/analyze/*` endpoints are unchanged.

## Honest coverage
Every analysis carries a capability list. **Not available in this deployment (always reported as not used):** reputation/threat
intelligence and isolated-browser page inspection. The UI shows "Analysis coverage: used / not used" on every result, and follow-ups such
as "Did you open the website?" answer from that list ("No, the link was not opened").

## Risk vs confidence
Risk score/level (how suspicious) and confidence (how much independent evidence supports it) are separate fields and separate UI
elements. Levels: Low Risk, Caution, Suspicious, High Risk, Unable to Verify, rendered with icon + text, never colour alone. The product
never says "safe", "100% safe" or "definitely fraud" (tests assert this).

## Frontend
`apps/web/components/chat/{Chat,RiskCard,types}`: timeline, composer (Enter sends, Shift+Enter newline), "+" attach, drag/drop, clipboard
image paste, preview/remove, cancel, retry, copy, new chat, starter prompts (fill the composer only), privacy note, ML/API banners.
The previous tabbed analyzer is preserved unchanged at `/analyzer`.

## Verification (local)
Backend suite green; frontend typecheck/lint/build clean; chat browser E2E 25/25 (including 360/390/430/820 px widths, image paste,
blank image -> Unable to Verify, follow-ups, coverage); old analyzer E2E 8/8.
