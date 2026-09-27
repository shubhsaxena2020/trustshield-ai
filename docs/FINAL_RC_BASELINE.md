# Final Expo RC — start-of-pass checkpoint

## Timestamp
2026-09-28 (session start of the "FINAL OVERNIGHT MASTER COMPLETION PASS").

## Branch
`feature/trustshield-final-expo-rc`, created from `de69e19` on
`feature/trustshield-hybrid-intelligence-v4` (verified: `git log -1 de69e19` resolves to
`de69e19bf8c371688928f21831477240d49e63a7`; working tree was clean at branch-creation time).

## Start commit
`de69e19` (branch head at creation; identical to this branch's own first commit before any new work in this pass).

## Production reference
`https://trustshield-ai-ecru.vercel.app` — verified live at branch-creation time:
- `/` → 200
- `/api/v1/health` → 200
Production commit lineage traces to the MASTER OVERNIGHT BUILD promotion (`672ebbb`); nothing in any subsequent
research branch (including this one) has been promoted since.

## v3 model identity
- Artifact: `models/trained/trustshield_text_v3.joblib`
- SHA-256: `d69aadbb758d186c8b33946895b456588cf8f5ce5eca504647d9e7a009c1189a` (matches
  `models/metadata/trustshield_text_v3.json`'s recorded `artifact_sha256` exactly — verified by direct hash, not
  assumed from the metadata file alone)
- Threshold: `0.16`
- Sealed holdout metrics (never to be altered): recall 82.1%, specificity 78.3%, precision 82.1%, F1 82.1%

## Test counts at start of this pass
617 passed, 7 skipped (carried over from the end of the hybrid-intelligence-v4 pass; a fresh full run was launched at
the start of this pass to reconfirm — see the final report for the actual re-run number, since this file is a
point-in-time checkpoint, not re-edited after the fact).

## Active experiment flags (all default OFF; none flipped in production)
- `TRUSTSHIELD_AI_PROVIDER` (conversational LLM: deterministic / ollama / remote)
- `TRUSTSHIELD_SEMANTIC_ANALYST` (disabled / remote — refused outright when `TRUSTSHIELD_ENV` is staging/production)
- `TRUSTSHIELD_FUSION_EXPERIMENT` — **not yet defined in code**; no fusion implementation exists on this branch as of
  this checkpoint. Will only be introduced if the Phase 29 entry gate (to be pre-registered this pass) is passed.

## LLM model ID
`ag/claude-sonnet-4-6`, reached via the user's local 9Router.

## 9Router endpoint category
Local-only (`http://127.0.0.1:20128/v1`) — loopback exception in the SSRF validator; never exposed publicly; tied to
the user's personal Antigravity/IDE subscription, approved for local research use only, not for public/production
traffic (standing rule, carried over from the prior phase).

## Production health state
200 / 200 (`/`, `/api/v1/health`) at checkpoint time, as recorded above.

## Not recorded here
No secrets, API keys, or credential material of any kind.
