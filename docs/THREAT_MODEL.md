# Threat model

TrustShield accepts attacker-controlled text, URLs, QR payloads, and image bytes. The current design favors local static analysis and explicit uncertainty. It does not claim to prove that content is safe.

## IMPLEMENTED controls

| Threat | Mitigation | Status |
|---|---|---|
| Image decompression bomb | Pillow bomb warnings/errors are converted into a structured rejection; decoded dimensions and pixel count are checked before extraction. | Implemented and covered by tests |
| Polyglot image or wrong declared MIME | The filename is not used to select a decoder. The server decodes the bytes, checks the actual format, and rejects a declared MIME mismatch. | Implemented and covered by tests |
| Oversized upload | The API reads only up to the configured upload limit plus a detection byte; the validator also checks decoded dimensions. | Implemented and covered by tests |
| Corrupt or undecodable image | Pillow decoding errors become HTTP 422 with an explicit error code; OCR/QR extraction is not reached. | Implemented and covered by tests |
| Malicious URL in a QR code | OpenCV decodes locally. URL payloads go to string-only static URL analysis; destinations are never fetched. | Implemented and covered by tests |
| `javascript:` or `data:` QR payload | The payload is classified as a URL-like payload and routed to the static analyzer as an unsupported scheme. It is never launched. | Implemented and covered by tests |
| UPI QR as a payment attack vector | UPI values are parsed structurally and surfaced as evidence and fields. No payment app, payment intent, or payment action is launched. | Implemented and covered by tests |
| OCR text containing instructions or prompt injection | OCR output is treated as untrusted data. It is normalized and passed to deterministic rules/static URL analysis; it is not executed as instructions. | Implemented |
| Mock-provider misuse | The provider name is exposed, the UI displays the demo warning, and the provider reads only the explicit demo metadata field. It is documented as test/demo-only. | Implemented; operational discipline still required |
| URL SSRF if a future fetch is added | `validate_public_url` blocks disallowed schemes, internal names, private/link-local/metadata destinations, numeric-IP tricks, credentials, and private DNS results. | Guard implemented and tested; no current fetch path |
| Raw content leakage through result storage | Raw image bytes are never stored. Structured results, including extracted OCR text when present, remain in a bounded in-memory store of 200 entries and are lost on restart. | Implemented |
| Model-artifact loading trust | The loader confines metadata/artifact paths below the project model directories, rejects non-`.joblib` paths, verifies the recorded SHA-256 before loading, checks the recorded sklearn version, and converts load/inference failures into `ml=unavailable`. | Implemented |
| Dataset poisoning or misleading public labels | v1 keeps only the two retained SMS sources, uses supplied per-row audits, records provenance/confidence, caps legitimate rows before grouping, and excludes the unaudited Hugging Face bulk labels. Remaining public ham outside the reviewed samples is explicitly marked not individually audited. | Implemented with documented residual limitation |
| Prompt/text injection in input | Not applicable to the model path: input text is feature data for deterministic rules/TF-IDF inference, not instructions to an LLM or tool executor. | Not applicable |
| ML evasion / style shortcut | Validation includes source-name breakdown, hard legitimate controls, synthetic holdout assignment, calibrated threshold selection, manual FP/FN review, and saved-prediction leakage sensitivity analysis. Obfuscation, near-duplicate template overlap, and unseen scam patterns remain residual risks. | Partially mitigated; leakage-aware test interpretation documented |

## PLANNED or not enabled

- A web sandbox for inspecting page content is not enabled. The current application does not fetch QR or user-supplied URLs.
- External reputation providers are not configured.
- The final test-set ML evaluation is frozen and was accessed once by the coordinator. Further reporting reads saved predictions only; the primary numbers remain affected by cross-split near-duplicate templates.
- Ollama explanations, SQLite persistence, PaddleOCR, and Hindi OCR are not implemented.

## Phase 5 web-sandbox controls

The sandbox is disabled unless `ENABLE_WEB_SANDBOX=true`, and external browsing
also requires `TRUSTSHIELD_SANDBOX_ALLOW_EXTERNAL=true`. The API never imports
Playwright; a scrubbed child process owns the ephemeral browser, proxy, temp
directory, and bounded telemetry. The guard rejects non-HTTP(S), credentials,
numeric-IP tricks, private/reserved/mapped/link-local/multicast/metadata
addresses, and non-80/443 ports in production. Every DNS answer is validated
before the proxy connects directly to the selected validated address, and
Playwright revalidates requests and redirect destinations.

The worker observes forms but never fills or submits them; non-GET/HEAD traffic
is aborted. Dialogs and popups are dismissed/closed, downloads are quarantined,
hashed, capped, and deleted, visible text is bounded and marked untrusted, and
worker failures reduce confidence through `partial`/`timeout` module status.
The explicit loopback fixture allowance is accepted only in test mode and only
through the test harness constructor; it is not an API field.

Residual risks: a Chromium exploit can still escape an application-level
worker; this Windows development setup has no Docker/microVM profile, kernel
egress firewall, reputation service, malware detonation, or persistent
screenshot policy. `--no-sandbox` is deliberately not used. Production must
add OS/container isolation and resource controls before enabling external
browsing.

## Residual gaps

- There is no rate limiting. An exposed deployment needs an edge/API rate limiter and request-level abuse controls.
- Analysis results are held in memory only. A process restart loses them, and the store is not a multi-instance persistence layer.
- Tesseract parses untrusted image data in the API process. The current validation limits exposure, but production hardening should isolate OCR work and enforce process/resource limits.
- OCR can misread text, and QR decoding can fail or select the first payload when multiple codes are present. The result therefore keeps provider/decoder warnings and separate confidence rather than claiming certainty.
- OCR text shorter than the shared 15-character minimum follows the pasted-text path and can yield `unable_to_verify` rather than a completed analysis.
- The mock provider can make a fixture look successfully extracted without recognising its pixels. It must never be used to assess real-world OCR quality.

## PLANNED: isolated OCR worker boundary

The following security boundary is planned and is not implemented in this phase:

`API -> isolated OCR worker (separate process/container, no network, resource limits, temp-dir cleanup, timeouts) -> Tesseract`

Arbitrary user images should not be parsed inside the main API process. Image decoding and OCR are complex parsers of untrusted bytes; a separate process or container can enforce resource and timeout limits, deny network access, clean temporary files, and contain a parser compromise without taking down the API process.

## Sandbox containment and known limitations (Phase 5)

Implemented:

- The loopback egress proxy requires a per-job random Basic credential (`Proxy-Authorization`, constant-time compare, 407 challenge). Chromium is given the credential through Playwright; unauthenticated or wrong-credential requests never trigger DNS resolution or an upstream connection (tested).
- On Windows the worker process tree runs inside a Job Object: kill-on-close (timeout, crash or API exit leaves no orphan Chromium), 3 GiB job memory cap, 64-process cap. Containment is reported in the result (`isolation.job_object`). Verified by a test that closes the job and checks a grandchild process dies.
- Chromium runs with WebRTC disabled and non-proxied UDP blocked, a scrubbed environment, a temp directory removed afterwards, and a project-local browser.
- Job dispatch uses a thread-safe concurrency gate and a bounded per-client rate limiter.

Known limitations:

- The sandbox is process- and Job-Object-isolated, not container/VM/AppContainer isolated. On non-Windows hosts there is no Job Object; only the wall-clock kill applies. Run the worker in a container or VM before exposing external browsing to untrusted users.
- The Job Object is assigned just after the worker starts, so a very brief window precedes containment.
- `ENABLE_WEB_SANDBOX` and `TRUSTSHIELD_SANDBOX_ALLOW_EXTERNAL` default to off; live external browsing has not been exercised. The browser E2E confirms the UI opt-in is refused safely with external browsing off.
- Job dispatch is one thread per accepted job; there is no persistent queue.
- **Gate:** external live browsing (`TRUSTSHIELD_SANDBOX_ALLOW_EXTERNAL`) stays disabled until the sandbox and UI changes receive a fresh adversarial review; this is independent of the ML model promotion.

## Phase 5 security gate: second adversarial review (2026-09-26)

An independent review (Codex; the Antigravity attempt was blocked by a provider content filter and produced nothing) attacked the
proxy authentication, Job Object containment, redirects, downloads, concurrency and UI opt-in against HEAD `d239ca3`. All eight
verified findings were fixed with regression tests (mutation-checked):

| # | severity | finding | fix |
|---|---|---|---|
| 1 | high | proxy credential forwarded upstream when sent obs-folded (leading whitespace) | obs-fold and duplicate credential headers are refused (407); `Proxy-*` headers stripped after whitespace normalisation |
| 2 | high | Job Object assigned after process start (child escape window, 1/10 runs) | worker launched `CREATE_SUSPENDED`, contained, then resumed; failure to contain kills the worker and fails closed |
| 3 | medium | JS/meta navigation chains bypassed the redirect limit (200 requests) | every main-frame navigation request counts; cap = `max_redirects + 1`, reason `navigation_limit` |
| 4 | medium | GET form auto-submit reached the target | in-page submit/`requestSubmit` blocked in every frame; POST remains blocked at the network layer |
| 5 | medium | API admission did not enforce the port allow-list | same 80/443 rule at admission (422) |
| 6 | medium | unbounded accepted jobs/threads | at most 8 accepted-but-unfinished jobs, then HTTP 429; slot released when the job ends |
| 7 | low/med | oversized headers / disconnects raised unhandled exceptions | explicit stream limit, `LimitOverrunError` handled, cleanup errors suppressed |
| 8 | low | design doc named a nonexistent env var and a container/queue architecture | `WEB_SANDBOX_DESIGN.md` carries an authoritative implementation note (`ENABLE_WEB_SANDBOX`, subprocess + Job Object) |

Residual limitations after the fixes: GET navigations to validated public hosts are inherently possible (loading a page is a GET);
form-submit blocking is in-page best effort layered over the network-level POST block; no container/VM isolation; no non-Windows
containment beyond the wall-clock kill; live external browsing has still never been exercised and stays disabled.


## Conversational layer (branch `feature/trustshield-conversational-next`)

| Threat | Control |
|--------|---------|
| Prompt injection via message, OCR text, QR payload | All such text is data: bounded, control-char-cleaned, never in instructions, rendered as text; verdict is computed by rules/ML/fusion, not by a language model; regression tests |
| Model/explanation claims certainty or an action not taken | Deterministic explanations from contract fields; `grounding_violations` blocks certainty, "opened the page", reputation claims and a different level/score; production is always deterministic |
| Oversized/forged conversation context | pydantic-validated `ChatAnalysis` (bounded lists/strings), message <= 5000 chars, history <= 6 turns; server stores nothing |
| Third-party disclosure of user URLs | External providers are interface-only; refused without ENHANCED mode + explicit opt-in (tested); none enabled |
| Upload abuse | Same image validation, 4 MB app limit, rate limits and heavy-work gate as the v1 endpoints |
| Hostile browsing from the API | Unchanged: sandbox disabled; the chat path performs no network fetch of user URLs |

Residual: rate limiting is per instance, not global; the client-held context can be edited by the client (it only affects that client's own follow-up answers).

## Conversational AI assistant (branch `feature/trustshield-ai-assistant`)

| Threat | Control |
|--------|---------|
| Intent misrouting suppresses a real analysis | Deterministic priority rule: a URL or attachment always forces analysis regardless of greeting/question wording (`docs/INTENT_ROUTING.md`); tested against adversarial greeting-shaped scam text |
| Chat/education/product replies hallucinate a capability, certainty, or request a secret | `chat_grounding_violations` checked on every non-deterministic reply; any violation falls back to the deterministic answer |
| A future remote LLM provider is enabled without cost/turn limits | `docs/AI_FAILURE_MODES.md` flags this as required before any remote provider goes live; none is active today |
| Off-topic requests are used to pivot the assistant into general-purpose use | `off_topic` intent declines and redirects to the product's cybersecurity scope |

Residual: output validation is regex-based, not a structured-output schema (flagged for the independent review of this phase).

## Hybrid intelligence / semantic analyst (branch `feature/trustshield-hybrid-intelligence-v4`, local research only)

| Threat | Control |
|--------|---------|
| Semantic analyst output treated as authoritative | Schema has no score/verdict field; disabled by default; not wired into the live analysis pipeline this pass |
| Invented network-derived facts (domain age, reputation, live browsing) | `validate_no_invented_facts` rejects the whole assessment, not just the offending field |
| Fabricated evidence quotes | `evidence_spans_exist_in` checked against the redacted text the model actually saw; a non-existent quote discards the assessment |
| Prompt injection via analysed content | Content lives inside a delimited `<message_to_analyse>` block after the system instructions; tested with the brief's exact injection strings |
| Label/source/dataset leakage into the prompt | `build_semantic_prompt(message: str)` — the signature itself is the guardrail; explicit leakage tests for every known source/dataset identifier string |
| Research cache holding raw secrets | Cache key and value are built from `redact_secrets()` output only, never raw text or the API key |
| Provider/SSRF/key-leak surface | Reuses the already-hardened `_remote_transport` (SSRF validation, key scrubbing, call budget) rather than a separate transport |

Residual: this is regex/schema-based validation, not a formal proof; a sufficiently novel adversarial phrasing could still
theoretically produce a schema-valid but subtly misleading assessment — mitigated by the analyst's output never being
authoritative on its own (see Hybrid intelligence architecture doc).

## Gemini migration / ProviderChain (branch `feature/trustshield-gemini-architecture`)

| Threat | Control |
|--------|---------|
| A model-route deprecation notice displayed as though it were a real answer | `scripts/probe_9router_models.py`'s functional probe rejected every model returning provider-notice text; a live regex-based deprecation-notice guard is a documented gap, not yet added to the runtime path itself (see below) |
| Runtime code accepting a loopback provider endpoint in staging/production | `_validate_base_url` now checks `TRUSTSHIELD_ENV` at the code level, not only at `scripts/preflight.py`'s deploy-time layer (defense-in-depth found and fixed this pass) |
| A broken remote tier hammered on every request | `ProviderChain`'s in-process circuit breaker (3 consecutive failures → skip for 60s per tier) |
| A later chain tier's request confused with an earlier tier's model/credentials | Each tier's transport is a closure bound to one specific model string; `_remote_transport`'s `model=` override never falls back to a different tier's value |
| Certainty-refusal ("I cannot guarantee X is safe") misclassified as a certainty violation, silently discarding a good, safe response | Found via 3-model comparative benchmarking; fixed in `_claims_unsupported_certainty`/`_NEGATION_CUE` with regression tests using the exact real responses that exposed it |

**Update: this gap was found and closed within this same pass, not deferred.** `_remote_call_once` now raises
`ProviderNoticeDetected` (never retried, same treatment as a 4xx) when a response matches the exact structure of a
real observed deprecation notice — deliberately narrower than the discovery probe's own regex, since a generic
"deprecat|no longer supported" pattern would false-positive on legitimate answers like "SSL 3.0 is no longer
supported due to known vulnerabilities." Verified against 3 such legitimate phrasings plus the real notice text.

## Independent review findings (Gemini migration pass) — fixed vs. documented residual

**Fixed this pass** (full report: `docs/INDEPENDENT_REVIEW_GEMINI_2026-09-28.md`):
- **HIGH — circuit breaker penalized grounding violations, not just transport failures.** 3 adversarial prompts
  designed to provoke a certainty/capability claim would trip the breaker and take the primary tier offline for
  every concurrent user for 60 seconds — a real, attacker-triggerable denial-of-service on the whole tier. Fixed:
  the circuit breaker now only tracks transport/exception failures; a grounding rejection falls through to the next
  tier without being recorded as a breaker failure. A validator exception is also now caught and treated the same way
  (previously it would have escaped as an unhandled error).
- **HIGH — the certainty-negation fix (`3a87ed2`) introduced real false negatives.** Its generic 60-char negation
  window treated ANY nearby negation cue as suppressing a certainty claim, regardless of what it actually negated —
  "Don't worry because this link is safe.", "I won't lie this is guaranteed.", and similar phrasings escaped
  detection entirely. Fixed with `_CERTAINTY_NEGATION_CUE`, which only suppresses when the negation directly
  modifies a verb of assertion, and by dropping trailing negation for certainty claims (it was only ever valid for
  passive capability phrasing).
- **MEDIUM — a benchmark script could permanently cache a rate-limit rejection as if it were the model's real
  answer.** Fixed: an exhausted retry budget is reported for that run but never written to the shared cache.

**Documented residual, not changed this pass:**
- **LOW — `_validate_base_url`'s `TRUSTSHIELD_ENV` check fails open if the variable is simply unset**, matching this
  codebase's existing convention everywhere else (`get_provider`, `get_semantic_analyst_mode` do the same). Changing
  just this one function's default to fail-closed would make it inconsistent with every other strictness check in
  the codebase; changing the convention everywhere is a bigger architecture decision than this pass's scope. The
  actual deployed defense remains `scripts/preflight.py --profile production`, which explicitly requires
  `TRUSTSHIELD_ENV` to be staging/production and fails if a loopback URL is configured.
- **INFORMATIONAL — the circuit breaker has no half-open probe state**, so many concurrent requests can all retry a
  tier at once right when its cooldown expires (a "thundering herd" against an upstream that's still down). Left as
  a simple, single-process breaker per the brief's own "keep design simple" instruction (item 22); a half-open probe
  would add real complexity for a low-traffic local-research deployment.
