# TrustShield AI: deep technical notes for the team

Audience: every team member who may be asked a technical question by judges. Read sections 1 to 4 fully, and skim the rest. Every
claim points to the file that implements it, so you can open the code and check it. Where a number is weak, the section says so.

Last updated: 2026-10-06, against branch `conversational-safety-fixes`.

---

## 0. The one-paragraph version

TrustShield AI takes something a user received (a pasted message, a screenshot, a link, or a QR code), turns it into plain text
or a URL string, and runs it through **deterministic detectors** (regex rules for scam language, static analysis of URL structure,
brand-impersonation checks) plus a **calibrated text classifier** (`trustshield_text_v3`). All detector outputs become structured
**Evidence** objects. A **fusion** step combines the evidence into a risk score (0 to 100) and a label (low risk, caution,
suspicious, high risk, or unable to verify). A separate **chat layer** lets a language model explain that result in plain words, and
answer general cybersecurity questions. The language model never sets the score. The scanner never browses the web.

---

## 1. The problem and why our design looks the way it does

- Scams reach people through WhatsApp, SMS, email, screenshots, QR codes, shortened links, fake payment requests and impersonation.
- Our constraints, which shape every design choice below:
  1. **Privacy:** the analysis is local. Screenshots are OCR'd on the server with Tesseract; QR codes are decoded without the
     destination being fetched; URLs are analysed as strings with no network request (`services/analysis/url_analysis/static.py`:
     "Never makes a network request").
  2. **Explainability:** every result is a list of evidence items, each with a severity, a confidence and a human-readable title.
     A judge can see *why* a message scored what it did.
  3. **Safety of the assistant:** the chat model can explain but cannot change the verdict. Its replies are checked against the
     scanner score before they are shown.

---

## 2. System map (what runs where)

```
Browser (Next.js 16, React)                      apps/web/components/chat/Chat.tsx
   │  POST /api/v1/chat/stream  (SSE)            services/api/main.py
   │  POST /api/v1/analyze/image|qr|text|url      (same API process)
   ▼
FastAPI  (services/api/main.py)
   ├─ Chat route:  intent classify → model-first → (tool call) scanner → explanation stream
   └─ Analysis route:  normalise → text rules + URL analysis + ML → evidence → fusion → explanation
        services/analysis/pipeline.py  →  services/analysis/fusion/*  →  services/analysis/explanation/template.py
        OCR: services/analysis/ocr/*  (Tesseract)      QR: services/analysis/qr/*  (OpenCV decoder)
   Model host (external): NVIDIA-hosted OpenAI-compatible endpoint, configured by NILABH_LLM_* variables.
Deployment: Vercel "Services" (one project): `web` (Next.js) + `api` (container, Dockerfile.vercel).
```

File map (key files only):

| Concern | File |
|---|---|
| Entry point, all HTTP routes, SSE chat stream | `services/api/main.py` |
| Chat intent routing (rules, used to *decide* scanner eligibility) | `services/chat/intent.py` |
| Model-led chat agent, tool definition, guards, streaming | `services/chat/agent.py` |
| Chat explanation and grounding checks (reply acceptance) | `services/chat/explain.py` |
| Product facts the model may state | `services/chat/product_metadata.py` |
| Analysis pipeline orchestration | `services/analysis/pipeline.py` |
| Text normalisation (zero-width removal, URL extraction) | `services/analysis/normalization/text.py` |
| Deterministic text rules | `services/analysis/rules/text_rules.py` |
| Static URL analysis | `services/analysis/url_analysis/static.py` |
| Brand → official-domain table | `services/analysis/url_analysis/brands.py` |
| ML classifier loading and inference (provenance-checked) | `services/analysis/ml/classifier.py` |
| ML evidence and features | `services/analysis/ml/evidence.py`, `services/analysis/ml/features.py` |
| Fusion engine (noisy-OR, label bands, confidence) | `services/analysis/fusion/engine.py` |
| Fusion strategies (default: `ml_aware_weighted`) | `services/analysis/fusion/strategies.py` |
| Evidence data model (Pydantic) | `services/analysis/evidence/models.py` |
| Template explanations and safe actions | `services/analysis/explanation/template.py` |
| OCR | `services/analysis/ocr/` |
| QR decoding | `services/analysis/qr/` |
| Model metadata (hashes, thresholds, validation metrics) | `models/metadata/trustshield_text_v3.json` |
| Release manifest | `models/release/v3_release_manifest.json` |
| Frontend chat | `apps/web/components/chat/Chat.tsx` |
| Frontend risk card | `apps/web/components/chat/RiskCard.tsx` |

---

## 3. The analysis pipeline, step by step

Entry: `analyze_text`, `analyze_url`, `analyze_image`, `analyze_qr` in `services/analysis/pipeline.py`. All of them end in `_finish`.

1. **Normalise** (`services/analysis/normalization/text.py`). Strip zero-width characters and a BOM, Unicode-normalise, collapse whitespace.
   Text is capped at `MAX_TEXT_CHARS = 20_000`. The URL extractor is a regex covering `http(s)://`, `www.`, and bare domains on a fixed list
   of TLDs (com, in, co.in, xyz, top, click, link, and others).
2. **Text rules** (`run_text_rules`). About a dozen regex rules, each producing `Evidence` with a category and severity. Examples:
   urgency, sensitive-data requests (OTP, PIN, CVV, card details), payment demands, account-suspension threats, prize or lottery bait,
   impersonation of authority, brand claims (`TEXT_CLAIMS`, `BRANDS`). A negation guard (`_NEGATION`) stops "never share your OTP" from
   counting as a request for an OTP.
3. **Context quality.** `text_context_quality` estimates how much text there is. Very short input lowers confidence (a "short context"
   limitation is added).
4. **URL analysis** (only if URLs were extracted). `analyze_url_static` returns evidence for: unsupported scheme, IP-address host,
   userinfo in the URL, URL shorteners, punycode and homograph hosts, a *domain mismatch* (the URL mentions a known brand but its
   registered domain is not one of that brand's official domains), a *lookalike* domain (edit distance ≤ 2 from an official domain),
   suspicious TLDs (xyz, top, and similar), excessive subdomains, long host names, excessive hyphens, unusual ports, no HTTPS, encoded
   characters, executable or archive downloads, and credential-related paths (login, verify, KYC).
   Registered domains are parsed with `tldextract` (public-suffix aware), never by substring.
5. **Brand claim check.** If the text claims a brand (`claimed_brands`) and the link's registered domain is not official for that
   brand, a *brand mismatch* item is added.
6. **ML classifier** (`_run_ml`). `TextClassifier.predict(text)` returns `p_malicious`. It is checked to be finite and within [0, 1].
   The model is only loaded after provenance checks (the SHA-256 of the artifact must match the metadata and manifest). If
   `TRUSTSHIELD_ML=off`, the step is skipped and reported as "not run".
7. **Guard (added 2026-10-06).** If the message's links are all official brand domains and no text rule fired, the classifier's
   evidence is dropped. Reason: an official SBI link inside "check my account" was scored as phishing. The guard only applies when there
   are links. A screenshot of an official notice with no link is still scored by the classifier (see section 10, open issue).
8. **Fusion** (`fuse` in `services/analysis/fusion/engine.py`). See section 4.
9. **Explanation** (`explain` in `services/analysis/explanation/template.py`). A template produces the summary, the list of
   "what we found", and safe next steps (verify through the official channel, never share an OTP, report to 1930 or cybercrime.gov.in).
   It restates evidence only. It does not invent facts.
10. **Result.** `AnalysisResult` with `risk` (score, label, confidence), `classification` (primary and secondary categories),
    `evidence`, `modules` (status of each detector), `explanation`, `recommended_actions`, `limitations`.

Limitations are first-class: if OCR or the classifier did not run, the result says so (`LIMIT_ML`, `LIMIT_SHORT_CONTEXT`).

---

## 4. Fusion: how evidence becomes a score

File: `services/analysis/fusion/engine.py`.

### 4.1 The noisy-OR formula (legacy and building block)

`risk_score(evidence) = round((1 − Π (1 − w_severity × confidence_i)) × 100)`, where `w_severity` comes from `SEVERITY_WEIGHT`
(high = 0.38, and lower for medium, low and informational). Many independent weak signals compound; the score can never exceed 100.

### 4.2 The default strategy

`DEFAULT_FUSION_STRATEGY = ml_aware_weighted` (`services/analysis/fusion/strategies.py`). It is the strategy selected on the
**validation** set (`docs/HYBRID_V4_SELECTION_POLICY.md` and the fusion evaluation under `models/evaluation/fusion/`). It uses:
- category-aware weighting (`CATEGORY_PRIORITY`, `ML_CATEGORY_MAP`), so a payment-fraud rule and an ML "payment_fraud" prediction agree,
- explicit **evidence-strength gates**, so one weak signal cannot produce a high-risk label,
- a separate **confidence** value (low, medium or high), based on which modules completed and how much context there was.
Switch back to the Phase 3 behaviour with `TRUSTSHIELD_FUSION=legacy`.

### 4.3 The brand floor (added 2026-10-06)

`_with_brand_floor`: if a *domain mismatch* is present **and** a second URL risk is present (suspicious TLD, credential path, no
HTTPS, or lookalike), the score is at least 60, which is the "suspicious" band. Reason: a fake bank link with a lookalike
domain and no other text scored 36 (caution). Applied in both the legacy path and the default strategy path.

### 4.4 Bands and labels

`BANDS` / `RISK_BANDS` (`services/analysis/fusion/config.py`) map score ranges to labels: low risk, caution, suspicious,
high risk. `unable_to_verify` is used when coverage is too low to be honest (`coverage_requires_unable_to_verify`).

### 4.5 The verdict rule (say this clearly)

The score and label come **only** from evidence and the classifier. The chat model receives the score and may explain it. Its reply
is rejected if it quotes a different score (`reply_is_acceptable`).

---

## 5. The text classifier: `trustshield_text_v3`

Files: `services/analysis/ml/classifier.py` (loading, provenance, inference), `services/analysis/ml/features.py`,
`services/analysis/ml/evidence.py`, `services/analysis/ml/metrics.py`. Metadata: `models/metadata/trustshield_text_v3.json`.

| Item | Value (from the metadata file) |
|---|---|
| Architecture | `two_stage` |
| Calibration | isotonic (`CalibratedClassifierCV`, version `sklearn-CalibratedClassifierCV-v1`); sigmoid was also considered |
| Decision threshold | 0.16 (`threshold_rule` in the metadata) |
| Feature pipeline | `trustshield-text-features-v1` |
| Dataset | `trustshield-text-v3`, manifest SHA-256 `5226121d…` |
| Training rows | 4,004 |
| Validation rows | 625 (124 malicious, 501 legitimate) |
| Seed | recorded in the metadata |
| Artifact SHA-256 | `d69aadbb…` (checked at load time against the manifest) |

**Validation metrics (from the metadata, not the sealed holdout):** accuracy 0.9392, F1 0.865, precision and recall at the chosen
threshold: 122 true positives, 2 false negatives, 36 false positives, 465 true negatives (so FNR 1.6%, FPR 7.2%). Brier score 0.023.
The calibration curve is in the metadata; the top probability bins are close to observed rates.

**Sealed holdout (one-time, product metadata, `services/chat/product_metadata.py`):** recall 82.1%, specificity 78.3%, precision 82.1%,
F1 82.1%. The 90% targets were not met. Treat this as the honest number for the expo. The validation numbers are optimistic
because the threshold was chosen on that same validation set.

**Why the two numbers disagree:** the sealed holdout is a different, one-time evaluation with different data. We do not have a
single source that reconciles them. Say so if asked, and point to the holdout report (`docs/V2_EVALUATION_REPORT.md`).

**Known weaknesses (be upfront):**
- It flags ordinary official text sometimes. In our test, a screenshot of an official SBI notice with no link scored 25 (caution),
  because the classifier alone pushed it up. This is the open issue in section 10.
- The dataset is small: 124 malicious validation examples. Confidence intervals are wide.
- It is a text model. It does not see layout, logos or the rendered page.

---

## 6. URL analysis in detail

File: `services/analysis/url_analysis/static.py`. Brand table: `services/analysis/url_analysis/brands.py`.

- **Parsing:** `urlsplit`, then `tldextract` for the registered domain (public-suffix aware). Hostnames are decoded (punycode,
  percent-encoding) before checks.
- **Brand table:** each brand key maps to its *official* registered domains. SBI currently lists `sbi.co.in`, `onlinesbi.sbi`,
  `onlinesbi.com`, `bank.sbi`, `sbi.bank.in`, `sbicard.com`, `sbilife.co.in`. The `bank.sbi`, `onlinesbi.com` and `sbi.bank.in`
  entries were added on 2026-10-06 from a public search; **verify them against sbi.co.in before the expo.**
- **Domain mismatch** fires when the URL or text names a brand but the registered domain is not in that brand's list.
- **Lookalike** fires when a registered label is within edit distance 2 of an official label (typosquatting).
- **Why the URL check never fetches the page:** privacy and safety. The product answers "is the link structured like a scam"
  and says clearly that it did not open the page (`capabilities_not_used` in the metadata).

---

## 7. The chat layer (model-led, with a scanner tool)

Files: `services/chat/agent.py`, `services/chat/explain.py`, `services/chat/intent.py`, `services/api/main.py` (`_stream_chat_events`).

### 7.1 Routing (decided in this order)

1. **Every message goes to the model first** (since 2026-10-06). The model receives the system prompt, the last few turns, and a
   scanner tool (`SCAN_TOOL`, function name `analyze_content`).
2. **The scanner runs only through a guard.** `_may_scan(message)` in `services/api/main.py` allows the tool when the message has a
   link, a fraud or payment signal, or is a passage of text that is not a question or request. It refuses questions ("what is
   phishing?"), requests, and remarks about earlier replies ("but this is a related question").
3. **`is_content_to_check`** (`agent.py`) is a second guard: the text the model passes to the scanner must actually appear in the
   user's message (substring or ≥90% word overlap). This stops the model from inventing content to scan.
4. **Intent rules** (`intent.py`) still classify messages (greeting, product question, cyber question, analysis) and are used by the
   guard, not as the first decision.

### 7.2 Streaming

`stream_turn` (agent.py) yields `token`, `reset`, `scanned` and `final` events. When the model calls the tool, the earlier text is
cleared (`reset`), the scanner runs, the analysis event is sent to the browser, and the model writes the explanation. The browser
(`Chat.tsx`, `streamChat`) paints tokens at a steady pace and handles `replace` (a corrected reply).

### 7.3 Reply acceptance (the grounding checks)

`chat_grounding_violations` (`explain.py`) rejects replies that: claim a builder or owner not in the product facts, claim certainty
("definitely safe", "guaranteed"), claim to have opened a link or looked up a reputation service, ask the user for a secret, or
state a score different from the scanner's. Exemptions exist for refusals ("rather than claiming…") and for describing scams
("fake offers promising guaranteed returns"). A rejected reply is retried once, then replaced by a neutral message.

### 7.4 Model host and resilience

- Configuration: `NILABH_LLM_BASE_URL`, `NILABH_LLM_API_KEY`, `NILABH_LLM_MODEL` (environment only; never committed).
- Current model: `nvidia/nemotron-3-ultra-550b-a55b` via the NVIDIA OpenAI-compatible endpoint.
- **Reasoning is turned off** for chat (`chat_template_kwargs.enable_thinking = false`). Measured: about 7.7 s instead of 15.9 s for the
  same answer length.
- **Retries:** up to three attempts with a short pause (1.5 s, then 3 s), for connection failures, empty replies and HTTP 503 overload.
- **Silence timeout:** a stream silent for 25 s is abandoned.
- If the model is down, shared content with scam signals is still scanned directly, and the user is told the assistant is unavailable.
  Canned answers are not shown.

---

## 8. OCR and QR

- **OCR:** Tesseract 5.4 via `services/analysis/ocr/`. The result includes the extracted text and a confidence value. Confidence
  feeds fusion (`_ocr_quality`); a provider without confidence gets a conservative factor (0.75).
- **QR:** OpenCV decoder (`services/analysis/qr/`). The payload is classified (URL, UPI string, plain text, `javascript:`/`data:`
  routed to the static analyser). The destination is parsed, never opened.
- **Image validation:** PNG, JPEG and WebP only. The declared type must match the decoded image; size and dimension limits apply.

---

## 9. API

Defined in `services/api/main.py` (see also `docs/API.md`):

| Route | Purpose |
|---|---|
| `GET /api/v1/health` | Module status (ML, OCR, QR) |
| `POST /api/v1/analyze/text` | Text analysis (JSON) |
| `POST /api/v1/analyze/url` | URL analysis |
| `POST /api/v1/analyze/image` | Screenshot (multipart) → OCR → analysis |
| `POST /api/v1/analyze/qr` | QR image → decode → analysis |
| `GET /api/v1/analysis/{id}` | Bounded in-memory result lookup (lost on restart) |
| `POST /api/v1/chat` | Non-streamed chat turn |
| `POST /api/v1/chat/upload` | Chat with an attachment |
| `POST /api/v1/chat/stream` | Server-sent events for the chat UI |

Rate limits apply per client (`limit_light`, `limit_heavy`). Production refuses to keep ordinary results in memory.

---

## 10. Honest status: open issues (say these before a judge finds them)

1. **Official notice in a screenshot can still score caution (25).** A real screenshot of the official SBI homepage was scored 25 by
   the classifier alone. A narrow fix (drop classifier-only evidence when no rule fires) broke four existing tests that encode the
   classifier's intended behaviour, so it was reverted. It needs a deliberate design decision.
2. **Bare lookalike link:** now scores at least 60 (suspicious), but a pure link with nothing else has little context, so confidence
   stays medium.
3. **Classifier false positives** on ordinary official text. Measured FPR on validation: 7.2%.
4. **Chat latency:** 6 to 34 seconds per reply in production, dominated by model queueing and 503 retries.
5. **Model host availability:** the NVIDIA endpoint returns intermittent 503 "overloaded" errors.
6. **Real browsing is not part of the product.** For testing only, we used Playwright to open official sites and take screenshots.
7. **Second opinion on key dates:** the official SBI domain list was verified only through a public search. Confirm on sbi.co.in.
8. **Keys:** the NVIDIA keys pasted during testing are not rotated (the team decided this; the key lives only on the laptop).

---

## 11. Testing: what exists and how to run it

- **Unit and integration:** `tests/unit`, `tests/integration`. Run: `.venv\Scripts\python.exe -m pytest -q tests/unit tests/integration`.
  Result on 2026-10-06: **723 passed.**
- **Routing audit:** 180 messages (`tests/fixtures/routing_audit.jsonl`), report in `docs/reports/routing_audit.md`. Earlier result:
  96.1% correct, 0 of 79 scams missed, 7 false alarms.
- **Stress test:** `tests/e2e/stress_stream.py`. Result: 24 of 24 succeeded, first-reply p50 0.37 s, p95 7.1 s (small sample).
- **Browser end to end:** `tests/e2e/run_browser_e2e.py` (8 of 8 earlier), `tests/e2e/run_chat_e2e.py`.
- **Real screenshots (2026-10-06):** a generated SMS-style scam screenshot scored 85 (high risk); the official SBI homepage
  screenshot scored 25 (caution, see issue 1).

What is missing: a labelled set of real phishing screenshots and real links. Build it before claiming any accuracy in public.

---

## 12. Deployment

- One Vercel project (`trustshield-ai`), built with `scripts/build_vercel_bundle.py` (outside the repo, into a scratch folder). The
  bundle contains no credentials; secret scan with `scripts/scan_secrets.py` must report zero findings.
- `vercel deploy --prod` from the bundle folder, then the alias `trustshield-ai-ecru.vercel.app` moves to the new deployment.
- Vercel's container registry (`vcr.vercel.com`) holds the `api` image. **Hobby plan limit: 50 images per repository**, so old
  images must be deleted periodically (we deleted 49 on 2026-10-06). Source: <https://vercel.com/docs/container-registry/limits-and-pricing>.
- Model settings (`NILABH_LLM_*`) are set in the Vercel project as encrypted environment variables.

---

## 13. Likely technical questions, with answers

**Q: How does the score come out?**
A: Each detector emits evidence with a severity and a confidence. The default fusion strategy combines them (category-aware, with
strength gates) into 0 to 100, and the label comes from fixed bands. There is also a floor: a brand-mismatch link with a second
URL risk is at least 60. Show `services/analysis/fusion/strategies.py` and `engine.py`.

**Q: Where does the ML model come in?**
A: It is one detector among several. It gives a calibrated probability; it is one evidence item and it can be dropped when a
guard applies (official links). Show the metadata file.

**Q: Why not just use an LLM to judge the message?**
A: We want the verdict to be reproducible and explainable. The rules and the calibrated classifier produce the verdict; the LLM only
explains it. An LLM verdict would change between runs and could be talked into a different answer.

**Q: How do you stop prompt injection in the chat?**
A: Untrusted content (OCR text, QR payloads, URLs) is passed as labelled data, not instructions. The model's replies are checked
(grounding), and the tool refuses content that is not in the user's message. Honest limit: no prompt-injection defence is perfect.

**Q: Why does it not open the link?**
A: Opening a link is the most dangerous step in scam checking, and it leaks the user's IP and interest to the scammer. We analyse
the string. We say clearly that the page was not opened.

**Q: What happens with a QR code?**
A: The QR is decoded locally. The payload is classified. A UPI payment string is parsed structurally, and nothing is sent anywhere.

**Q: Is the classifier validated?**
A: On a validation set of 625 messages (124 malicious): 93.9% accuracy, 7.2% false-positive rate, 1.6% false-negative rate, at the
chosen threshold. A one-time sealed holdout gave 82.1% recall and 78.3% specificity. The holdout is the honest number to quote.

**Q: What are the limits?**
A: Section 10. Lead with them before a judge does.

**Q: What would you do next?**
A: A labelled set of real screenshots and links; a fix for the official-notice false alarm; sign-in and cross-device history;
Hindi OCR; a second model host so the chat never depends on one provider.

---

## 14. Glossary

- **Evidence:** one finding (for example "domain mismatch") with category, severity, confidence and title.
- **Noisy-OR:** combining independent probabilities: `1 − Π(1 − p_i)`.
- **Calibration (isotonic):** mapping raw classifier scores to probabilities that match observed rates.
- **Threshold:** the probability above which a message is marked malicious (0.16 here).
- **Sealed holdout:** a test set held back and used once, to avoid tuning on it.
- **Grounding:** checking that a model's reply is supported by the scanner's output.
- **SSE:** server-sent events, the streaming format for the chat.
- **Registered domain:** the part of a host you buy, for example `sbi.co.in`.
- **Lookalike (typosquat):** a domain a few characters away from an official one.
