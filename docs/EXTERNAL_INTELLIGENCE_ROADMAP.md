# External intelligence roadmap

**CURRENT:** interfaces only (`services/chat/external.py`); no network requests. **PLANNED:** everything below.

## Interface
`ExternalIntelligenceProvider.lookup(url) -> ProviderResult{source, status, timestamp, evidence[], confidence, privacy_implications, error}`.
Results are ordinary `Evidence` and pass through the same fusion; nothing external is trusted automatically. A provider that did not run
is listed under "Not used" in the analysis coverage.

## Privacy modes
- **LOCAL ONLY (default; the only mode deployed):** no URL or content leaves TrustShield.
- **ENHANCED (planned):** requires an explicit per-request opt-in that discloses what is sent to which third party. `run_providers`
  refuses any third-party provider without ENHANCED **and** opt-in (tested).

## Candidate sources (none integrated; each needs a terms/privacy review and explicit approval before use)
Google Safe Browsing, PhishTank/OpenPhish feeds, URLhaus, VirusTotal, domain-age/WHOIS. Free-tier limits, licensing and privacy must be
checked per source. No paid provider is enabled.

## Future isolated URL inspection (NOT built; the sandbox stays disabled)
Vercel API -> authenticated job queue -> **separate isolated Linux VM/container** (no shared network, no credentials, no host mounts,
short-lived) -> egress restricted to public DNS/HTTP(S) with SSRF and private-range blocking plus redirect/size/time caps -> headless
Playwright with downloads disabled -> structured, sanitised findings returned as Evidence. It needs a fresh adversarial review before any
enablement. The Vercel API container itself never browses hostile pages.
