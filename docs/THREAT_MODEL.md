# Threat model (initial)
| Threat | Mitigation | Status |
|---|---|---|
| SSRF / internal network via URL fetch | `validate_public_url`: http/https only, blocks localhost, private/link-local/metadata, numeric-IP tricks, credentials in URL, DNS-resolved private IPs | Implemented + tested (`tests/security`) |
| Redirect to private IP | Re-validate every hop | Planned with sandbox |
| Oversized / malformed input | 20k char text limit, 2048 char URL limit, Pydantic validation | Implemented |
| Prompt injection from webpage text | LLM receives structured evidence only; raw page text marked untrusted | Planned |
| Malicious downloads | Hash + static analysis only; no execution on host | Planned |
| Malware detonation | Only in a dedicated disposable VM; Docker alone is not isolation | Out of scope for MVP |
| Secret leakage | `.env` ignored, `.env.example` only; no raw content persisted | Implemented |
| Wrong verdict | Confidence separate from score; Unable to Verify state; measured FN/FP rates | Partial (metrics pending) |

Known gaps: CORS limited to http://localhost:3000; no rate limiting yet.
