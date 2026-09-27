# Tech Expo final brief

## The story: two kinds of AI
1. **Security ML** — the fraud classifier and evidence generation (`trustshield_text_v3`, deterministic rules, OCR, QR,
   static URL analysis, risk fusion). This decides the verdict.
2. **Conversational AI** — understands questions, explains the evidence, answers cybersecurity questions, helps with next
   steps. This never decides the verdict.

Recommended one-line explanation for judges: *"The language model is not trusted to decide whether a message is
fraudulent. TrustShield's fraud-detection pipeline produces the security evidence and risk assessment. The conversational
AI then helps the user understand that evidence and interact with the result naturally."*

## Demo sequence (real pipeline, no fabricated scores)
1. **`yo`** → a natural greeting, not a risk score. This is the headline fix of this phase.
2. **`What can you do?`** → product self-explanation from fixed metadata.
3. Paste a phishing message → natural-language explanation + compact risk card.
4. **`Why?`** → grounded follow-up citing the actual evidence.
5. **`Did you open the website?`** → truthful "No" (capability disclosure).
6. **`What is UPI fraud?`** → educational answer, no risk card.
7. Upload a QR/screenshot fixture → multimodal analysis.
8. Open `/analyzer` → the underlying modules, for judges who want the technical detail.

## Fallback demo (no internet at the venue)
Run locally: `uvicorn services.api.main:app --port 8000` + `npm run dev` in `apps/web` (`NEXT_PUBLIC_API_URL=http://localhost:8000`).
No cloud dependency is required for core analysis — v3, OCR, QR and static URL analysis all run locally.

## Likely judge questions (concise factual answers)
- **Why isn't 82% enough?** The 90% recall/specificity targets weren't met on the sealed holdout; we say so rather than
  round up, and v4 research (a separate branch) is actively working the gap.
- **Does the AI browse websites?** No — live webpage inspection and reputation lookups are both disabled; the assistant
  says so when asked, and refuses to claim otherwise even if a future LLM provider tried to.
- **Why is risk different from confidence?** Risk is how suspicious the content looks; confidence is how much reliable
  evidence was available to judge it. A message can look very risky with only medium confidence if some checks (like
  reputation) simply aren't available.
- **Why not just use ChatGPT?** A general LLM would be the sole judge of fraud with no evidence pipeline behind it — the
  point of TrustShield is that the verdict comes from an evidence-fused pipeline, and the LLM only explains it.
- **What happens if the AI explanation service is unavailable?** The deterministic provider — not a placeholder, a fully
  grounded fallback — answers instead; the security analysis itself is unaffected either way.
