# Context and data-access model

## Shared authority

`MISSION.md` is the hard contract. `STATE.md` is the current checkpoint. `ROADMAP.md` gives ownership/dependencies. Each package prompt supplies only needed context. `ORCHESTRATOR-LOG.md` is append-only. Reports and evidence live under `work/finals/<pkg>/` and `docs/finals/evidence/<workstream>/`.

## Read/write boundaries

| Lane/package | May read | May write |
|---|---|---|
| A-W1 | auth/history architecture, auth/API/UI code, relevant tests | auth/signup/login/history paths named in prompt; W1 evidence/report |
| B-W2 discovery | public ChatGPT web and local REA/browser guidance; current UI read-only | `UI_SPEC.md`, captures and report only |
| B-W2 implementation | measured spec and `apps/web` UI after W1 releases overlapping paths | named UI paths only |
| W3 | upload/API/extractor/UI code, safe synthetic fixtures | named parser/upload/UI paths, synthetic fixtures and evidence |
| W4 | train/validation only after guards checked; rules and fixed regression fixtures | named rules/tests/docs; never v3 artifact/defaults or spent data |
| W5 | extension APIs/docs, local fixtures | `extension/` only |
| W6 | API contract, Android docs, local SDK | `android/` only |
| W7 | regression/security/demo/presentation materials | named harness/docs/evidence only |

All workers may read repository docs and source needed for assigned work, but must not print secrets or inspect spent dataset contents. No worker may write `STATE.md`, the main log, commit, push, deploy, alter machine-wide settings, or modify pre-existing untracked paths unless explicitly listed.

## Sensitive boundaries

Never read `.env` values, personal Chrome profile data, account secrets, v1 test rows, or v2 holdout contents. Do not open submitted URLs. External provider calls require the existing configured Nilabh route only and remain subject to backoff. New holdout data must be separately sourced/licensed, hashed, sealed, and left unopened.
