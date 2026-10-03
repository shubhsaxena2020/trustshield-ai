# Testing

Written for: anyone changing code, and anyone deciding whether a change is safe to ship.

## Run the suite

```bash
.venv\Scripts\python.exe -m pytest -q
```

Current state (2026-10-03): **523 passed, 7 skipped, 0 failed**, about 65 to 100 seconds. The skipped tests need optional
external components and are reported, not hidden.

## Layout

| Directory | Covers |
|---|---|
| `tests/unit/` | detectors, fusion, normalisation, intent routing, provider logic, configuration checks |
| `tests/integration/` | the API end to end through the test client: analysis, chat, production hardening |
| `tests/security/` | SSRF guard, redaction, secret scanning, boundary checks |
| `tests/e2e/` | browser-driven scripts against a running stack; run manually, not in the default suite |
| `tests/fixtures/` | synthetic inputs only |

## Rules that the suite enforces

- **Spent data.** No script or service code may reference the sealed holdout or the v1 test split outside the archival
  guard. `test_spent_data_boundary.py` checks this statically.
- **Runtime boundary.** Services and frontend code never reference spent data paths (`test_runtime_and_frontend_never_reference_spent_data`).
- **Deployment profile.** Staging and production configuration rules are tested against the real settings code.
- **Confidence cap.** A test asserts the current `medium` cap. It will fail when the cap is intentionally lifted; that is
  the point, so the change is deliberate.

## Before shipping a change

1. Full suite passes.
2. `scripts/preflight.py` passes for the target profile.
3. For UI changes: run the app and check the golden path in a browser. Type checks and tests do not verify the interface.
4. For detection changes: re-run the evaluation on the validation split. Never on a spent split.

## Known gaps

- End-to-end browser tests are not in the default run.
- Concurrency behaviour under real load was measured once (see DEPLOYMENT.md), not continuously.
