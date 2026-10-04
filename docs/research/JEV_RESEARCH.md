# JEV research — what it is and whether it fits TrustShield AI

Research date: 2026-10-04. Read-only research; nothing was implemented.

## 1. What JEV means

Repo and Second Brain searches found no internal definition of "JEV" — the only
matches for "jev" were a background-task ID and the goal file itself. The repo's
`docs/PLAN_CONVERSATIONAL_V2.md` and `docs/DAY_PLAN.md` track a related but
distinct deferred item, JEPA (e.g. "JEPA is deferred"), which the goal says is
NOT what JEV means. So the term came from outside the repo.

Web research resolves it clearly. **JEV = Jev, the "System One model" released
by TypeSafe AI on 2026-09-15** (Diogo Almeida / typesafe.ai). Sources:

- Launch summary and interface: state in, typed probabilistic decisions out,
  trained with RLCD (Reinforcement Learning for Calibrated Decisions), 70–500 ms
  latency, $0.042 per MTok (listed in the launch post).
  https://github.com/omnijev/awesome-jev (accessed 2026-10-04)
- Explanation of the model class and its agentic-security use:
  https://rogue.security/blog/system-one-models-jev-agentic-security
  (accessed 2026-10-04)
- Independent peer-style evaluations:
  - "JEV as a Judge for Agent Trace Security", arXiv 2609.34862 (Sept 2026).
    JEV F1 77.8 vs 74.1 for the best generative judge across 5,219 agent
    trajectories; median latency 0.99 s; ~$0.000195 per judgment.
  - "Jev-IDS: A System One Model for Network Intrusion Detection",
    arXiv 2610.01079 — Jev as a network-IDS classifier, 4.8x faster and 3.8x
    cheaper than a frontier LLM baseline.
  - "JevVibe" (CWE classification for secure code generation), arXiv 2609.34963.
    All accessed 2026-10-04.

Other meanings of the letters JEV found on the web, none of which fit
TrustShield:

1. V-JEPA / JEPA (Meta FAIR) — video representation learning, different family
   from what was asked. https://github.com/facebookresearch/vjepa2
2. JeVois — an embedded smart camera for hobbyist robotics.
3. JEV (Japanese Encephalitis Virus) — virology.
4. JEV as a regional electric-vehicle scheme name (India EV policy press).
   All four were encountered in search results dated 2026-09/10; none describe
   a fraud-detection technology.

The fitting meaning is #0, Jev the decision model. It is a text-only, typed
decision interface: a caller supplies a serialized state plus predefined
questions with declared candidate sets, and the model returns probabilities
over those candidates without generating free-form text.

## 2. How JEV works (the interface that matters here)

- Inputs: a structured state (JSON the caller builds) and typed questions.
- Three primitives: Choice (pick among declared candidates, probability per
  candidate), Score (rated on a defined scale), Noul (probability of truth for
  a yes/no claim).
- Output is schema-shaped probabilities — no prose, no parsing, no
  output-validation failures.
- Trained with RLCD so probabilities are calibrated rather than just fluent.
- Small and fast enough (70–500 ms latency; ~$0.0002 per judgment in the
  arXiv trace-judging study) to call per-item in a live pipeline.

Documented failure modes ("Jev 1.13 jaggedness", listed in the awesome-jev
index): literal reading, counting, dates, indirection, distractor state,
adversarial content — security teams treat it as a calibrated decision head,
not a reasoning engine.

## 3. Fit for TrustShield AI

TrustShield (per README and docs) is a privacy-first fraud/scam risk analyzer
for text, URLs, screenshots and QR codes, with a frozen Phase 3 text model
(`trustshield_text_v3`, threshold 0.16) and Phase 4 risk fusion with an
`ml_aware_weighted` strategy. The interesting question: can JEV replace or
augment any of that?

Fit for **fraud text detection**: strong fit as a supplementary decision layer,
not as a replacement.

- Fraud text classification is exactly a bounded typing job: scam / phishing /
  legitimate / not_sure, or finer buckets (KYC, UPI, delivery, urgent-service,
  OTP, lottery). JEV's Choice primitive maps one-to-one onto this.
- Noul maps onto the yes/no question "is this message a scam attempt?" — the
  same shape TrustShield's text classifier answers, but with calibrated
  probability instead of a logistic-regression score, and zero-shot rather
  than needing labeled training rows.
- TrustShield's current Phase 3 text model is small, local, and fine-tuned on
  its own curated dataset (`datasets/processed/trustshield-text-v3`). JEV is
  hosted and remote. Privacy boundary (docs/LLM_PRIVACY_BOUNDARY.md,
  docs/LLM_SECURITY_POLICY.md) would need enforcement: message text would
  leave the machine if JEV is called.
- TrustShield already has a deterministic rule layer and fusion gates, so the
  Rogue-published warning applies: the decision model is not the security
  boundary; fusion code acting on its output is (as with Nexus guardrails).

Fit for **URL detection**: weaker fit. TrustShield's URL analysis is largely
static/deterministic (structural parsing, javascript:/data: routing, UPI),
which is cheap and audit local-only. A JEV call could still add a semantic
judgment ("does the URL's text linguistically imitate a bank brand?") that the
static analyzer can't, but the added value is smaller.

## 4. Cost to run

- Launch-post listed price: $0.042 per MTok
  (source: awesome-jev index, access 2026-10-04).
- Independent estimate in arXiv 2609.34862: ~$0.000195 per judgment, median
  0.99 s per call. At 1,000 analyses/day that is ~$0.20/day text-if-text-only,
  i.e. roughly $6/month — cheap compared to a frontier LLM judge.
- JevVibe measured Jev at 6.27x lower latency and 55.9x lower API cost versus
  GPT-5.6-Sol on a CWE classification task.
- Catch: costs scale with state size because the state is serialized text;
  a screenshot-derived OCR blob or a long chat history inflates tokens. Also,
  TrustShield's current stack is deliberately local-only. Hosting externally
  conflicts with the privacy-first stance unless the state is redacted/minimized
  first.

## 5. Recommendation: PILOT, do not adopt outright

Reasons to pilot rather than adopt:

1. Proven fit, but only as a supplementary layer. Independent papers show JEV
   beating generative LLM judges and doing well on security-type classification
   (agent traces, CWE, network IDS). Fraud-text triage is the same shape.
2. TrustShield already has a working scored classifier it spent effort
   calibrating (v3, threshold 0.16, sealed-holdout protocol). Replacing it with
   a hosted 6-week-old third-party model without the same sealed-holdout
   evaluation would discard a governed pipeline.
3. Privacy: the stack is local-only by design. JEV is remote. A pilot should
   run only on questions over redacted metadata (message length, URL token
   shapes, keyword classes serialized as a state), not raw user text, unless
   the user explicitly permits a cloud call.
4. Fusion integration risk: the Rogue/pentest analysis documents real cases
   where downstream code mis-read a decision model's output fields. In
   TrustShield that failure mode lands in the Phase 4 fusion evidence gates,
   which must be re-evaluated (V4 protocol, documented comments) against a new
   signal source with a fresh sealed holdout.

Concrete pilot sketch (not implemented): serialize a minimal state (rule
verdicts, URL tokens, structural flags) — no raw message body — ask JEV a
Choice question over the same label buckets as `trustshield-text-v3` and a Noul
question for the scam/no-scam call, log the calibration agreement against the
existing classifier's scores on a held-out set, and only then consider
promoting it into fusion. New evaluation, new holdout — no seed reuse.

## Sources

- https://github.com/omnijev/awesome-jev — index of launch post, primitives,
  failure modes, independent evaluations (accessed 2026-10-04)
- https://rogue.security/blog/system-one-models-jev-agentic-security
  (published 2026-09-21, accessed 2026-10-04)
- https://arxiv.org/pdf/2609.34862 (accessed 2026-10-04)
- https://arxiv.org/pdf/2609.34963 (accessed 2026-10-04)
- https://arxiv.org/html/2610.01079 (accessed 2026-10-04)
- https://immersivelabs.com/resources/c7-blog/ai-in-your-soc-without-breaking-the-bank-testing-jev-on-blue-team-workflows (accessed 2026-10-04)
- https://penligent.ai/hackinglabs/tr/jev-ai (accessed 2026-10-04)
- https://unknownobserver.com/posts/jev-in-25-lines-python-latent-space-representation (accessed 2026-10-04)
