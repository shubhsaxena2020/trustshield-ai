# Decisions

Written for: anyone asking "why is it like this", and anyone tempted to undo something.

Format: one entry per decision, with the date, what was decided, why, and what was rejected. Newest first.

---

### D-011: Keep the expo deployment local, tunnel only the frontend (2026-10-02)

**Decided.** Run backend, frontend and 9Router on the presenter's laptop. Expose only the Next.js server through a
Cloudflare quick tunnel. Next.js forwards `/api/*` to the backend server-side, so the backend port is never exposed.

**Why.** 9Router has two published vulnerabilities that make any exposure unsafe (see SECURITY.md). A cloud deployment
depends on free tiers and cold starts that cannot be controlled during a slot. A single laptop under direct control is
the most reliable setup for one booth.

**Rejected.** Hosting the API on a cloud platform for the expo (cold starts, sleeping free tiers). Tunnelling 9Router.
Tunnelling the backend as a second public origin (more surface, and unnecessary once the proxy exists).

---

### D-010: Remove the sandboxed browser entirely (2026-09 to 2026-10)

**Decided.** Delete `services/analysis/web_sandbox/`, its worker, and its tests.

**Why.** It was disabled in production, could not run on the serverless or container targets, and was the cause of the
repository's only failing test. Keeping a disabled subsystem adds complexity and an attack surface with no current user.

**Rejected.** Leaving it disabled behind a flag. A flag is still code that must be maintained and reasoned about.

**Consequence.** URL analysis is static only. If live inspection is ever wanted, it needs a new design with its own
threat model, not a revival of this code.

---

### D-009: Keep the duplicate-looking fusion modules separate (2026-10-02)

**Decided.** `engine.py` (legacy fusion path) and `strategies.py` (active `ml_aware_weighted` strategy) stay as separate
files. Only their identical data tables were deduplicated.

**Why.** `strategies.py` documents that the legacy path stays unchanged on purpose, and the active path is the one that
runs. Merging them would erase a deliberate boundary for no real code saved.

---

### D-008: Keep router and intent as separate passes (2026-10-02)

**Decided.** `router.py` (which detectors run) and `intent.py` (conversational intent) remain separate. Only the shared
follow-up constants were deduplicated.

**Why.** They answer different questions for different call sites. Their decision logic carries hard-won safety fixes
(the greeting-scored-as-low-risk bug, the contextual-rephrase bug). The duplication left is small.

---

### D-007: Keep `trustshield_text_v3` as the sole fraud authority (2026-09)

**Decided.** The v3 classifier decides the ML part of every verdict. No language model decides fraud.

**Why.** It has a sealed, once-evaluated holdout and a recorded validation. A language model's judgement is neither
reproducible nor validated for this task, and its output is untrusted by design.

**Rejected.** Replacing it with an LLM classifier. Training or hosting a local model (out of scope; the LLM is accessed
through the existing relay).

---

### D-006: Drop the legacy `TRUSTSHIELD_EXPLANATION_PROVIDER` alias (2026-10-02)

**Decided.** Only `TRUSTSHIELD_AI_PROVIDER` is read.

**Why.** The project has not been deployed under the old name, so there is no existing configuration to keep working.

---

### D-005: Raise the per-minute remote-call budget to 100 (2026-10-03)

**Decided.** The budget is 100 calls per minute per process, by owner decision.

**Why.** It bounds cost exposure on a remote provider. Raising it for a live demo is a cost decision the owner must make.

**Consequence.** Cost exposure at the limit is up to five times higher. Re-check the budget if the endpoint is billed
per call.

---

### D-004: Keep the confidence cap at `medium` (open)

**Decided (for now).** Fusion confidence is clamped to `medium` by `CONFIDENCE_CAP` in `engine.py` and by a second
clamp in `strategies.py`.

**Why.** Calibration and detector agreement have not been evaluated well enough to justify reporting `high`. The cap is a
safeguard until they are.

**Open.** Lifting it requires (a) an evaluation of calibration and agreement on the validation split, and (b) a decision
to remove both clamps together. An attempt to lift it was blocked by a security classifier and was not worked around.

---

### D-003: Do not archive scripts on the basis of a list alone (2026-10-02)

**Decided.** Scripts move to `archive/research/` only after verifying they have no live imports, no test coverage, and
are not pinned by a safety test or by release hashes.

**Why.** An initial list of 25 would have broken the release provenance hashes, a safety test, and several passing
tests. The verified set was 11.

---

### D-002: Stateless, in-memory results with a TTL (earlier)

**Decided.** Results are held in a bounded in-memory store for 15 minutes. Uploads are not stored.

**Why.** Simpler to operate, nothing persistent to protect, and sufficient for the use case.

**Rejected.** A database. No current requirement needs one.

---

### D-001: Two systems, kept apart (earlier)

**Decided.** The security core produces the verdict. The conversational layer explains it and cannot alter it.

**Why.** A verdict that a language model could rewrite is not a verdict. Keeping them apart is what makes the explanations
safe to show.
