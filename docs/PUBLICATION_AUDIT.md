# Repository publication audit

Before the first push the tracked tree was audited and a **clean publication tree without git history** was built with
`scripts/build_public_tree.py` (the local development repository, whose history contains earlier data commits, is not published).
The GitHub repository is **private**.

## Policy

Published: source code, documentation, CI, deployment files, manifests/hashes/provenance, small synthetic or test fixtures needed by
automated tests, demo assets, aggregate evaluation outputs (metrics, confusion matrices, plots, prediction files that carry only
ids and scores).

Not published: raw spent v1 test / v2 holdout content; corpora of real messages and every corpus whose redistribution rights are not
explicitly verified; any file with a `text`/`message` column; manual error analyses that quote real messages; local logs, temp files,
installers, `.env` and other secrets; model artifacts (delivered only through the private release mechanism, `docs/MODEL_RELEASE.md`).
Documentation quotes of spent-holdout messages were replaced by descriptions before publication.

## Result for this tree (49 tracked files excluded)

| excluded | files | reason |
|---|---:|---|
| `datasets/processed/**` (v1/v3 train, validation, test, all; public corpora and their audits) | 24 | real/public SMS text; redistribution rights unclear or spent test text |
| files with a `text`/`message` column (prediction and error CSVs incl. `v2_final/errors_*`) | 12 | raw message content |
| manual error analyses (`models/evaluation/**/error_analysis*.md`) | 4 | quote spent test/validation messages |
| v2 holdout audit files | 6 | contain holdout text |
| `datasets/holdout/*/holdout.jsonl`, `datasets/raw/holdout_v2/**`, `datasets/raw/synthetic/codex_holdout.jsonl` | 3 | sealed/spent v2 rows |

Kept from `datasets/`: `datasets/raw/synthetic/agy_*.jsonl` (own AI-generated synthetic rows, no third-party text), the v2 holdout
manifest, access log and `SEALED.md`, and a `datasets/README.md` explaining the omissions. `PUBLICATION_MANIFEST.json` in the
published tree lists every included file with its SHA-256 and every excluded path.

## Reproducibility without protected data

`models/release/v3_release_manifest.json`, `models/metadata/*.json`, dataset manifests (row counts, hashes, provenance, dedup and
leakage reports) and the release/RC manifests allow the owner to verify that unpublished data matches what the models were built
from. Tests that need unpublished data (`test_frozen_v1_test_artifacts`, raw-holdout construction tests, v2 output hashes of
text-bearing files) skip on the published tree; everything else (393 tests) runs. Model retraining from the published tree is not
possible by design; the exact artifacts come from the private release.

## Checks performed

Secret scan of every published file (0 findings); size cap 2 MiB per file; leak check that no excluded path is present; full test
suite executed inside the built publication tree (see CI). The token used to fetch model artifacts is a build/CI secret and appears
nowhere in the tree.
