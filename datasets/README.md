# datasets/

This published tree deliberately omits corpora with real message text, the spent v1 test split and the sealed v2
holdout rows. Kept: synthetic training rows under `raw/synthetic/`, and the v2 holdout **manifest, access log and SEALED.md**
(hashes/provenance only). Manifests under `models/release/` and `datasets/holdout/*/manifest.json` let the owner verify that the
unpublished data matches what the models were built from. See `docs/PUBLICATION_AUDIT.md` and `docs/SPENT_DATA_POLICY.md`.
