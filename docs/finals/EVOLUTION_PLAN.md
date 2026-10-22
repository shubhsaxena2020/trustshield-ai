# Evolution beyond 48 hours

## During this run

Follow `ROADMAP.md` by priority. W1–W4 and W7 are final gates. W5 follows W1–W3; W6 follows functional W5. Prefer deeper verification and regression hardening over adding scope if the team finishes early.

## After the run / before 30 October

1. Head reviews `STATE.md`, all reports, evidence, final diff, and `FINAL-REPORT.md`; Shubh reviews `NEEDS_SHUBH.md` items before any approval-dependent holdout evaluation, secret setup, or external action.
2. Keep changes local until Shubh chooses the release candidate. Re-run the final local demo and regression suite from a clean checkout.
3. Continue only unblocked acceptance gaps: real auth email/expiry paths, device testing, and new-source/model validation under the spent-data policy.
4. If a new model is proposed, freeze the candidate and criteria before creating a separately sourced, hashed, unopened holdout; Shubh decides whether to open it.
5. Keep production and current v3 untouched unless separately authorized.

## Future maintenance

Update these documents when an observed tool workflow, package dependency, quality gate, or recovery path changes. Keep reports append-only where specified. Do not convert unmeasured validation evidence into performance claims.
