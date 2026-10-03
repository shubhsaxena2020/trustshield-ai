# Security

Written for: anyone changing the code, configuring a deployment, or deciding what to expose.

## What the system defends against

Scam and phishing messages, malicious links, and manipulated screenshots or QR codes, presented to a person who may act on
the result. The stakes are a user being misled in either direction: a dangerous message reported as safe, or a safe one
reported as dangerous.

## Trust boundaries

| Boundary | Rule |
|---|---|
| User input → analysis | Treated as untrusted: size-capped, validated, and never echoed in error messages |
| Images and QR | Decoded in memory, validated for type, dimensions and pixel count; not stored |
| Links | Analysed statically (`url_analysis/static.py` makes no network calls). Nothing on the live path fetches a destination. `url_analysis/ssrf.py` contains a fetch-safety validator (resolves hostnames, refuses private and metadata addresses) that is **not called by the live path**; it is kept, with `tests/security/test_ssrf.py`, as a guard for any future fetch |
| Chat context | Signed with an HMAC. Unsigned or altered context is discarded |
| LLM output | Untrusted. Every reply is checked for forbidden claims and replaced by the deterministic answer on failure |
| Provider URL | Validated: https only, or http to loopback in development. Private, link-local and metadata addresses refused |
| Provider key | Read from the environment only; scrubbed from any provider output; never logged |

## Why the sandboxed browser was removed

An earlier design opened suspect links in an isolated browser. It was disabled in production, incompatible with the
serverless and container targets, and the source of the repository's only failing test. Its removal also removes the
risk of the service fetching attacker-chosen content. See DECISIONS.md.

## Known risks

**9Router must never be reachable from outside the machine running it.** Two published vulnerabilities apply:

- CVE-2026-55641 (GHSA-86m2-fcxq-5q7c): a Host-header spoof bypasses its auth, making it an open relay and enabling SSRF
  through its search endpoint.
- CVE-2026-49353 (GHSA-6g2f-w7g3-77vf): its "local-only" check reads the Host and Origin headers rather than the socket
  peer address, so any tunnel (Cloudflare, Tailscale, ngrok) bypasses it.

The expo setup exposes only the frontend. The backend and 9Router stay on loopback. Do not change this to tunnel the
relay's port, even for convenience.

**Rate limits are per process.** Multiple workers or instances each keep their own counters.

**The remote-call budget is a cost control.** It is 20 calls per minute per process. Raising it changes the cost exposure
and should be a deliberate decision.

**Fusion confidence is capped.** This is a calibration safeguard, not a security control; see DETECTION.md.

## Data handling

Ordinary analyses are not retained in staging or production (`TRUSTSHIELD_RESULT_STORE=sandbox_only`). Results in
development are held in memory for the TTL. Access logs record method, path, status, duration and analysis ID, never the
body or the content.

## Reporting

Security issues should be reported to the project owner directly, not in a public issue.
