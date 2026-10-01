# Salvage inventory — what survives the rebuild

Written before any rearchitecture work, so nothing genuinely good gets thrown out along with the sprawl. Everything
below is verified against the actual repo state, not recalled from memory.

## Keep as-is (load-bearing, proven)

- **`trustshield_text_v3`** (`models/trained/trustshield_text_v3.joblib` + `models/metadata/trustshield_text_v3.json`).
  LinearSVC, two-stage (binary → category), isotonic-calibrated, threshold 0.16, SHA-256-verified on load. Sealed
  holdout metrics: recall 82.1%, specificity 78.3%, precision 82.1%, F1 82.1%. **This is the one component in the
  entire project with a real, measured, honest result — and the one piece confirmed to beat an LLM at the actual
  job** (80% vs. 45–56% recall on identical held-out messages, `artifacts/difraud_challenge/results.json` and
  `artifacts/gemini_semantic_benchmark/`). Any redesign keeps this as the detection authority.
- **The evidence-fusion shape**: normalize → rules → ML → (OCR/QR/URL for non-text input) → weighted fusion → one
  score/label/confidence → explanation. `services/analysis/pipeline.py`'s own docstring says it in one line:
  "Detection is rule/ML based; explanation only restates evidence." Keep this separation even if the plumbing
  around it gets rebuilt.
- **The redaction + grounding-validator pattern**: secrets never reach an LLM; LLM output is checked against real
  evidence before the user sees it. Two real bugs were found in the grounding validator by an independent review
  and fixed with regression tests (`tests/integration/test_ai_assistant.py`) — don't rebuild this from scratch,
  the hardening is already paid for.
- **The ProviderChain pattern** (`services/chat/explain.py`): primary model → secondary → deterministic fallback,
  with a circuit breaker. Reusable verbatim for a 9Router/Antigravity-only setup — it already is one.
- **Static-only analysis discipline**: no live URL fetch, no QR auto-open, no payment initiation. A design choice,
  not a gap — keep it.
- **The test suite**: 656 passing, 7 skipped, covering SSRF, redaction, prompt injection, grounding, the fusion
  engine, and the provider chain. Rebuilding the surrounding architecture should extend this suite, not discard it.

## Genuinely messy — this is the real complaint, and it's fair

- **5+ unmerged branches** (`feature/trustshield-hybrid-intelligence-v4`, `feature/trustshield-v4-source-robustness`,
  `feature/trustshield-ai-assistant`, `feature/trustshield-9router-llm`, `feature/trustshield-final-expo-rc`,
  `feature/trustshield-gemini-architecture`) — each did real work, none of it reached `main`. Nobody could open this
  repo today and tell what's actually running in production versus what's experimental.
- **~50 docs** spread across phases with overlapping names (`AI_ASSISTANT_ARCHITECTURE.md`,
  `AI_PROVIDER_CHAIN.md`, `HYBRID_INTELLIGENCE_ARCHITECTURE.md`, `GEMINI_MIGRATION_RC_REVIEW.md`...). Most are
  individually accurate but there's no single current source of truth.
- **Production (`trustshield-ai-ecru.vercel.app`) only ever shows the v3 classifier.** None of the conversational
  AI, ProviderChain, or Gemini work has ever shipped — it all exists on research branches. This is the "stuck on
  Vercel, moving here and there" complaint, and it's accurate: there's a real system here that was never deployed.

## Discard or deliberately not carried forward

- The semantic-security-analyst research path (`services/chat/semantic_analyst.py` and its benchmarks) — it did its
  job (answered the question honestly: no, an LLM doesn't beat the classifier) and doesn't need to be a live
  component. Its artifacts stay as evidence, not as code to maintain.
- Branch-specific one-off scripts (`scripts/gemini_conversation_benchmark.py`, `scripts/difraud_challenge.py`,
  `scripts/probe_9router_models.py`) — valuable as a record of how the LLM-vs-classifier question was actually
  answered, not as ongoing infrastructure.
