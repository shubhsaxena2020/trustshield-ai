# Privacy and retention audit (A07 / #30)

Read-only audit of what the TrustShield backend actually stores, logs and returns for a scan, and
what the Android app must therefore never do. Verified against commit `f4fbd1f` on `android/A07`
(the audit itself is documentation only: **no file under `services/` was modified by this
package**).

Requirements this answers: **N01** (no raw audio/image/message persistence; mask OTP/PIN/card/
phone/UPI before logging; exclude input from analytics) and **F09** (keep Android API enrichment
disabled until signed-in history *and* server-side retention pass this audit).

---

## 0. Bottom line

Three claims in the current documentation are **not true of the code today**:

1. *"No raw message is stored."* — False when a user is signed in. `services/history/store.py:21-25`
   collapses whitespace and keeps **up to 120 characters of the raw message**, and
   `services/api/main.py:110` writes that string into the `scan_history.input_summary` column.
2. *"No image content is stored."* — False for image/QR/document scans by a signed-in user.
   `services/api/main.py:112` stores the **entire result model**, and for images that model contains
   the OCR `raw_text`, `normalized_text` and `text` fields (`services/analysis/pipeline.py:197-205`).
3. *"A blanket zero-storage claim holds."* — There is no such thing today. There are two durable
   channels (1 and 2) plus a process-memory result store (§2, F4), and neither durable channel has a
   retention job, a redaction step, or an expiry.

What **is** already true: the backend does not write user content to its logs (§F6), and anonymous
(not signed in) scans never touch history (§F7). The conflict is specific to authenticated use —
which is exactly what F09 gates.

---

## 1. Method and its limits

* Traced every storage and logging site reachable from `/api/v1/analyze/{text,url,image,qr,document}`
  by reading: `services/api/main.py`, `services/api/security.py`, `services/api/settings.py`,
  `services/analysis/pipeline.py`, `services/analysis/fusion/result.py`,
  `services/analysis/evidence/models.py`, `services/analysis/normalization/text.py`,
  `services/history/store.py`, `services/chat/explain.py` (the existing `redact_secrets` helper).
* **No runtime verification.** No Supabase project was contacted and no URL was opened from this
  package. Everything below is code reading; the gate in §4 therefore requires a *test*, not a
  promise, before the conflict is called fixed.
* Line numbers are from the commit above. If they drift, re-read the cited function rather than
  trusting the number.

---

## 2. Field inventory — every value that is stored, held or logged

| # | Data | Where it lands | Code | Lifetime | Verdict |
|---|---|---|---|---|---|
| 1 | First ~120 chars of the raw message / URL text | `scan_history.input_summary` (Supabase, service-role write) | `services/history/store.py:18`, `:21-25`, `:55-58`; `services/api/main.py:110` | Until the user deletes the row. No TTL, no purge job | **Violation of N01** (F1) |
| 2 | Whole result document | `scan_history.result_json` | `services/api/main.py:112`; `services/history/store.py:55-58` | Same as above | **Violation** (F2) |
| 3 | OCR `raw_text`, `normalized_text`, `text` (up to 20 000 chars) | inside #2 for image/QR/document scans | `services/analysis/pipeline.py:197-205`; `MAX_TEXT_CHARS` at `services/analysis/normalization/text.py:7` | Same as #2 | **Violation of N01** (F2) |
| 4 | Domains extracted from the submitted URL | `result.modules.url_static.data.domains` → inside #2 | `services/analysis/pipeline.py:164` | Same as #2 | Partial — URL host, not message text (F8) |
| 5 | Whole result object, in process memory | `ResultStore` (`OrderedDict`) | `services/api/security.py:179-214`; `services/api/main.py:96` | Bounded by `store_max=200` / `store_ttl_s=900` **only when** the store policy is `all` | Conditional (F4) |
| 6 | The same result served back | `GET /api/v1/analysis/{analysis_id}` — **unauthenticated** | `services/api/main.py:247-252` | While #5 holds it | Capability-URL exposure (F5) |
| 7 | `analysis_id` (and request id, method, path, status, duration) | access log | `services/api/security.py:81-84`; header set at `main.py:88` | Log retention policy | Metadata only (F6) — but see F5 |
| 8 | Module failure events (module name, status) | event log | `services/api/main.py:94-95` | Log retention | No content (F6) |
| 9 | History-write failures | event log | `services/api/main.py:115`; `services/history/store.py:60` | Log retention | Error type only (F6) |
| 10 | Uploaded image / document bytes | request memory only; `file.close()` at end of request | `services/api/main.py:206`, `:217`, `:235`, `:244` | Request lifetime | Not durable, but see §5 residual risk R5 |
| 11 | Android on-device content | nothing yet — no code in `apps/android` writes scan content to disk | — | — | The rule in §3 is pre-emptive |

Notes that matter for the gate:

* #1 is **not** redacted. `services/history/store.py` imports only `json`, `logging`,
  `urllib.*` (`:9-14`) and never calls the repo's own `redact_secrets`
  (`services/chat/explain.py:892`). A 120-character window is enough to contain a full OTP
  ("Your OTP is 482913, please share…"), a UPI handle, or half a card number.
* #2 is a full `model_dump(mode="json")` of `AnalysisResult` (`services/analysis/fusion/result.py:35-47`),
  so it grows with every future module field. Today it already carries #3 and #4.
* #3 is the OCR module's own payload, not a derived summary: `"raw_text": raw_text` at
  `pipeline.py:203`, `"text": raw_text` at `:205`.

---

## 3. Findings

### F1 — `input_summary` persists up to 120 characters of raw user text (blocker)

```python
# services/history/store.py:18, 21-25
_SUMMARY_MAX_CHARS = 120

def summarize(raw: str) -> str:
    text = " ".join(raw.split())
    if len(text) <= _SUMMARY_MAX_CHARS:
        return text
    return text[:_SUMMARY_MAX_CHARS] + "…"
```

`services/api/main.py:110` passes `input_summary=history_store.summarize(raw_text)` straight into the
row. Whitespace collapsing is formatting, not privacy: OTPs, amounts, names, UPI handles and the
scammer's message all survive verbatim. This is the direct conflict with N01's "no raw message
persistence" and with `docs/android/PLAN_SUMMARY.md:9`.

### F2 — `result_json` persists the full result, including OCR text (blocker)

`services/api/main.py:112` stores `result_json=result.model_dump(mode="json")`. For an image scan
that dump contains `modules.ocr.data.raw_text`, `.normalized_text` and `.text`
(`services/analysis/pipeline.py:197-205`), i.e. the readable text of the user's screenshot —
bank balances, names, UPI handles, sometimes the OTP itself. A "no image bytes stored" claim is
technically true and materially misleading: the *content* of the image is stored as text.

### F3 — therefore a blanket zero-storage claim is false today

State plainly, for any future release note or Play listing: **the current signed-in path stores a
raw 120-character message window and the full analysis result (including OCR text) in Supabase.**
Do not ship a "we store nothing" line until F1 and F2 are fixed and re-audited.

### F4 — the documented in-memory bound (200 entries / 15 min) is correct but conditional (medium)

The docs (`docs/THREAT_MODEL.md:19`, `docs/core/DECISIONS.md:113`) say results live in a bounded
in-memory store of 200 entries with a 15-minute TTL. Code reading gives a more precise picture:

* The bounds exist: `store_max: int = 200` and `store_ttl_s: float = 900.0`
  (`services/api/settings.py:41-42`), overridable by `TRUSTSHIELD_RESULT_MAX` / `TRUSTSHIELD_RESULT_TTL`
  (`:80-81`), and `ResultStore._evict` enforces both (`services/api/security.py:187-194`).
* The policy gate decides whether anything is stored at all:
  `TRUSTSHIELD_RESULT_STORE` defaults to `sandbox_only` when `TRUSTSHIELD_ENV` is `staging`/`production`,
  and to `all` only in development (`services/api/settings.py:79`; defaults documented at `:40`).
* **No call site ever passes `retain=True`.** The only writer is
  `_state(request).store.put(result, retain=retain)` (`services/api/main.py:96`) and `_publish`'s
  `retain` parameter defaults to `False` (`main.py:86`); `ResultStore.put` returns early when
  `not retain and store_policy == "sandbox_only"` (`security.py:196-199`).

Net effect: in staging/production with defaults, **no analysis is retained at all**, so
`GET /api/v1/analysis/{id}` returns 404 unconditionally (`main.py:250-251`). The "200 entries /
15 minutes" figure describes a development-mode (or explicitly `TRUSTSHIELD_RESULT_STORE=all`)
deployment. It should be stated that way.

Also: the TTL is measured from insertion and is never refreshed on read — `ResultStore.get`
(`security.py:206-211`) does not update the stored timestamp — so a result lives exactly 15 minutes
from creation, not 15 minutes from last access. That is the safer behaviour; the docs just should
not imply a sliding window.

### F5 — `/api/v1/analysis/{id}` is unauthenticated and can return OCR text (medium)

`services/api/main.py:247-252` has no `get_current_user` dependency, unlike the history endpoints
(`main.py:273-276`, `:284-287`, `:298-301`). Anyone who knows the id gets the full result, including
`modules.ocr.data.raw_text` for image scans, for as long as #5 holds it.

Mitigating fact (verified): `analysis_id` is `uuid.uuid4().hex`
(`services/analysis/fusion/result.py:37`) — 128 random bits, so it cannot be enumerated. It is a
bearer capability, not a guessable key. But it is also returned in the `X-Analysis-ID` header
(`main.py:88`) and written to the access log (`security.py:81-84`), so anyone with log access can
replay it for the retention window. With `store_policy=sandbox_only` (the production default) this
endpoint is unreachable and the risk is latent — it becomes real the moment retention is enabled.

### F6 — logs themselves are clean today (positive finding)

Verified, not assumed:

* Access log emits only `request_id`, `method`, `path`, `status`, `duration_ms`, `analysis_id`
  (`services/api/security.py:81-84`). `scope["path"]` excludes the query string, so a
  `?text=`-style leak is not possible on these routes.
* Unhandled errors log the exception **class** only — the comment at `security.py:74` says why
  ("messages can contain user text").
* Module failures log `module` and `status` (`main.py:94-95`); history failures log `error_type`
  (`main.py:115`, `store.py:60`).
* `services/analysis/pipeline.py` contains no logger calls at all.

So the N01 log requirement is met on the server side today. The un-met half of N01 is *storage*.

### F7 — history is written only for signed-in users with Supabase configured (verified)

`_record_history` (`services/api/main.py:100-115`) returns early:

```python
if user is None:                                              # main.py:101-102
    return
settings = _state(request).settings
if not settings.supabase_url or not settings.supabase_service_role_key:   # main.py:104-105
    return
```

The env flags are `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` (`services/api/settings.py:82-83`).
Call sites: text `main.py:183`, URL `:191`, image/QR `:208`, document `:237`. Consequence: anonymous
API scanning writes nothing to Supabase — the F1/F2 conflict is strictly about authenticated
sessions. Deletion is user-initiated only (`main.py:284-307`); there is no scheduled purge, so a row
lives until the user deletes it.

### F8 — what a result fetch actually returns

`GET /api/v1/analysis/{id}` returns the full `AnalysisResult`
(`services/analysis/fusion/result.py:35-47`): `risk`, `classification`, `evidence`, `modules`,
`explanation`, `recommended_actions`, `limitations`. Backend evidence
(`services/analysis/evidence/models.py:20-29`) has **no** `quote` field, so it does not echo the
user's sentence — but its `metadata` is free-form and already carries the registered domain and the
claimed brand list (`pipeline.py:162-164`). Treat module `data` and evidence `metadata` as
carrying user-derived values when deciding what to store.

---

## 4. Remediation gate

Both blocks are **hard gates**: every condition must hold, with a test or a command that a reviewer
can re-run, before the dependent feature is switched on. Conditions marked *(backend)* are out of
scope for this package — they are proposed, not implemented here.

### (a) Gate to enable authenticated scan history

| ID | Condition | How it is checked |
|---|---|---|
| H1 | `summarize()` output is redacted, not merely truncated: `summarize("Your OTP is 482913 card 4111 1111 1111 1111")` contains no 4+ digit run | new unit test in `tests/unit/` asserting the output against a digit-run regex |
| H2 | `result_json` no longer carries OCR text: `"raw_text" not in json.dumps(row["result_json"])` and no `normalized_text` / `"text"` blob for an image scan | integration test that scans an image with a fixture, then inspects the payload handed to `history_store.save` |
| H3 | `input_summary` is capped (≤120 chars) **and** redacted **and** never the full message; the cap is asserted, not assumed | property test over synthetic long inputs |
| H4 | A retention policy exists for `scan_history` (TTL column/trigger or a documented purge job) and is demonstrated | SQL/config diff plus one end-to-end check |
| H5 | History is opt-in in the Android UI, visible per scan, and always deletable | manual gate item (§5) plus the existing DELETE endpoints `main.py:284-307` |
| H6 | This document is re-issued with F1/F2 marked fixed and new file:line citations | `docs/android/PRIVACY_AUDIT.md` diff |

Until H1–H6 all hold, the Android app must not send a signed-in request to any `/api/v1/analyze/*`
endpoint (F09's "disabled" state).

### (b) Gate to enable Android backend enrichment

| ID | Condition | How it is checked |
|---|---|---|
| E1 | Enrichment stays off by default at the type level: `AnalysisResult.backend` defaults to `BackendStatus.DISABLED` (`apps/android/.../core/model/Analysis.kt:136`, comment at `:79-80`) and only an explicit user setting flips it | unit test asserting the default of a freshly built result |
| E2 | Nothing is logged before it is redacted: every `Log.*` call site that can see input passes through `Redactor.redact` | source-scan gate: `grep -rn "Log\.[dviwe]" app/src/main` reviewed line by line; no call site takes a raw `text`/`ocr`/`quote` variable |
| E3 | OCR text never leaves the device: the outbound enrichment body is built from masked fields only, never from `modules.ocr.data.raw_text` | unit test serialising the request body and asserting no 5+ digit run and no `@` localpart |
| E4 | The client treats `404` from `/api/v1/analysis/{id}` as normal (it is, see F4) and never stores or re-uses an `analysis_id` | unit test on the adapter's error mapping |
| E5 | Defence in depth: the adapter strips any OCR/module `data` from a response before it is rendered or cached | unit test on the response mapping |
| E6 | Either F1/F2 are fixed per gate (a), or enrichment runs anonymous-only (no `Authorization` header), proven by a test/assertion in the client | code review of the client's session state |

E2 is the one that needs discipline rather than a compiler: until a logging layer exists in the
Android app, "redaction before any log line" is a review rule enforced by that grep, not by code.

---

## 5. What the Android app must never do

1. **No raw audio on disk.** Call Shield keeps a bounded in-memory rolling transcript for the active
   session and clears it on stop (`docs/android/ARCHITECTURE.md:21`). No `MediaRecorder` output, no
   cached WAV, no `SpeechRecognizer` audio buffer written anywhere.
2. **No raw image bytes on disk.** A shared `content://` image is read into a bounded buffer, analysed,
   and dropped. Nothing goes to `cacheDir`, `filesDir`, `MediaStore` or an external dir.
3. **No raw message text on disk.** No `SharedPreferences`, DataStore, SQLite, file or `WorkManager`
   input carrying scan text. Even the analysis result, if cached at all, carries masked evidence
   quotes, not the input.
4. **No scan content in logs, crash reports or analytics.** Every diagnostic line goes through
   `Redactor.redact`; crash reporting must not attach the analysed input; analytics events carry
   counts and labels only (`low_risk`, `high_risk`, stage ids), never text.
5. **No scan content in an outbound enrichment request** beyond what gate (b) permits, and never
   while signed-in history is still in the state described in F1/F2.

The shipped piece for rules 4 and 5 is `Redactor`
(`apps/android/app/src/main/java/ai/trustshield/android/core/privacy/Redactor.kt`): `redact`,
`redactJson` (also masks values under `otp`/`pin`/`card`/`phone`/`upi`-style keys), `isSensitive`,
and `MASK = "[REDACTED]"`. It is standalone — no dependency on any other package — deterministic,
idempotent, bounded on a 20 000-character input, and fails closed rather than throwing. It is
covered by 29 JUnit cases in
`apps/android/app/src/test/java/ai/trustshield/android/core/privacy/RedactionTest.kt`.

---

## 6. Residual risks / not fixed by this package

* **Nothing in `services/` was changed.** F1 and F2 are still true after this PR, and an app built
  from `android-finals` today is only safe while it stays anonymous and local-only.
* **Already-stored rows are not remediated.** Every `scan_history` row written before the fix keeps
  its raw summary and OCR text. No migration or purge is proposed here; that is a separate,
  deliberate data operation.
* **The redactor is pattern-based, not a DLP engine.** It will not catch a secret spelled in words
  ("twelve thirty-four"), digits split across a line break or a hyphen mid-code, a number written in
  a script whose digits are not `\p{Nd}`, or an identifier shape nobody listed. It is "low risk to
  log", not "safe to publish".
* **Documented masking limits** (pinned by tests): an *ungrouped* 13–14 digit run that fails Luhn is
  left alone (label it `A/c …`), and any 4–8 digit run within 12–15 characters of a
  code/OTP/PIN/CVV/password keyword is masked — which will also hit a legitimate 4-digit year or a
  postal PIN in that window (over-masking, chosen deliberately over leaking).
* **Uploaded bytes may transiently hit disk.** FastAPI's `UploadFile` is a spooled temporary file;
  above the spool threshold the bytes exist in the OS temp directory for the life of the request and
  are released by `await file.close()` (`main.py:217`, `:244`). The exact spool size for the pinned
  `starlette==1.7.0` was **not** verified here (no runtime test, no network). Not durable storage,
  but not "never on disk" either.
* **`_log.exception("chat stream failed")` (`main.py:560`)** can carry a provider exception message.
  The chat path is outside F09's enrichment scope and was not traced field by field here.
* **No runtime proof of the Supabase behaviour.** No database was contacted; §2/F1/F2/F7 are code
  reading. Gate (a) requires tests precisely because reading is not proof.
* **The Android logging layer does not exist yet.** Gate (b) E2 is a rule for the packages that add
  it; this package can only supply the redactor and the rule.
