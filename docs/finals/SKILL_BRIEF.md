# Skill brief — TrustShield AI finals

## Goal

Deliver the strongest locally verifiable TrustShield AI branch for the PSIT Tech Expo finals on 30 October 2026. This 48-hour run starts at the launch time in `STATE.md`. Work stays on `finals-48h`; production stays untouched.

## Mental model

Claude Code is the head and re-verifier. Codex is the orchestrator: plan, scope, dispatch, inspect diffs, rerun checks, and commit. Two Hermes lane slots perform scoped work packages; each package may delegate to no more than nine children with disjoint files. A worker report is a claim until independently reproduced.

## Product invariants

- The deterministic scanner is the verdict path; conversational AI explains and chats, it does not decide the verdict.
- Anonymous scans remain available. History is identity-scoped; never leak one account's local or server history to another.
- No user link is opened. No production deployment/configuration change, paid service, new account, secret exposure, or provider fallback.
- v3, threshold `0.16`, and spent v1/v2 evaluation data remain unchanged and unused for development.
- TrustShield keeps its own white theme, logo, copy, and assets. Reference products supply interaction patterns only.

## Definition of done

W1–W4 and W7 meet their written gates with evidence. W5/W6 reach the best verified state allowed by their dependencies and local tooling. `FINAL-REPORT.md` records exact commands, evidence, gaps, and risks. The branch is pushed only if a remote is configured and the milestone is green.
