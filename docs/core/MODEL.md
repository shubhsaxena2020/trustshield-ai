# Model

Written for: anyone retraining, validating or releasing the fraud classifier.

## What it is

`trustshield_text_v3`: a LinearSVC, two stages (binary fraud or legitimate, then category), with isotonic calibration fitted
by 5-fold cross-validation. It scores text only. Its decision threshold is 0.16. The artifact is loaded at startup and its
SHA-256 is checked against the release manifest; a mismatch aborts startup in staging and production.

It is the sole authority for the ML part of a verdict. A language model is never used to decide fraud.

## Validation

Measured on the sealed holdout, reported once and not used for tuning:

| Metric | Value |
|---|---|
| Recall | 82.1% |
| Specificity | 78.3% |
| Precision | 82.1% |
| F1 | 82.1% |

These are the figures to quote. They describe this model on this holdout and are not a general accuracy claim.

## Data discipline

Two splits are spent and must not be used for any further decision:

- The v1 test split was evaluated once.
- The v2 holdout is sealed and was evaluated once.

Scripts that could read either are guarded by `scripts/_v2_spent_guard.py`, which refuses to run without an explicit
archival override. `tests/unit/test_spent_data_boundary.py` enforces that every script touching spent data calls the
guard. Do not bypass either. See `docs/SPENT_DATA_POLICY.md`.

## Release

1. Train with `scripts/train_classifier.py` on the training and validation splits only.
2. Evaluate on the validation split with `scripts/evaluate_model.py`.
3. Produce the artifact and its manifest, then verify with `scripts/verify_v3_release.py`.
4. The manifest hashes the artifact; the API refuses to start if the hash differs.

A new model is a new release with a new manifest and a new validation record. Changing the threshold is a model change,
not a configuration change.

## Rollback

`trustshield_text_v1` is retained as the rollback. Selecting it outside development requires
`TRUSTSHIELD_ROLLBACK_CONFIRM=v1`.
