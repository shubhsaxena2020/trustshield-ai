# Frozen TrustShield Text Model v1 test record

TrustShield Text Model v1 — original single held-out evaluation (access count 1). Historical measurement of the v1 pipeline; not a benchmark to optimise against.

This directory is an immutable historical record. It contains byte-for-byte copies of the artifacts in `models/evaluation/test/`, the reporting-only analyses derived from the saved predictions, and a copy of `models/evaluation/test_access_log.json`. `FROZEN_MANIFEST.json` records the SHA-256 of every copied artifact and the model, metadata, dataset manifest, and test JSONL hashes.

May be done:

- Read, hash, cite, and audit these files.
- Recompute descriptive summaries from the frozen predictions without loading or running the model.
- Add a new, separately named analysis that cannot overwrite this directory or its manifest.

May not be done:

- Tune a threshold, weights, bands, rules, features, or model against these results.
- Re-run the test evaluator to improve, select, or replace any number.
- Overwrite, regenerate, or delete any frozen file or `FROZEN_MANIFEST.json`.

The leakage and prevalence caveats apply: exact/normalized/group overlap was controlled, but template variants below the grouping threshold and paraphrase overlap remain possible; the forced synthetic holdout also makes test prevalence non-natural. See [the canonical Phase 3 closure report](../../../docs/PHASE3_CLOSURE_REPORT.md#18-known-limitations) and its leakage section.

Future evaluations must use a new holdout, `trustshield-text-v2-holdout`, and append to a new access log. They must not reuse or extend the v1 test log or mutate this record.
