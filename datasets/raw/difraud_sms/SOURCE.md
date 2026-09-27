# DIFrauD SMS subset (`redasers/difraud`, HF dataset repo, `sms/` split)

- **Source:** `redasers/difraud` on Hugging Face — the DIFrauD benchmark (University of Houston), SMS subset.
- **License:** MIT (`LICENSE.txt` in the repo: "Copyright 2023 University of Houston", full MIT terms; verified by fetching
  the actual file, not inferred from a dataset-card summary).
- **Redistribution rights:** Explicit — MIT permits use, copy, modify, merge, publish, distribute, sublicense.
- **Acquisition date:** 2026-09-27, via `huggingface_hub.hf_hub_download` (files: `sms/{train,validation,test}.jsonl`).
- **Original label semantics:** `label`: 0 = legitimate ("ham"), 1 = deceptive/phishing. Mapped here to
  `binary_label`: legitimate / malicious_or_fraudulent; `category_label`: other_legitimate / phishing.
- **Geographic/domain scope:** Real messages from a private UK-based wireless operator, per the DIFrauD paper.
- **Language:** English.
- **Composition:** The DIFrauD authors built this SMS subset by merging the UCI SMS Spam Collection (5,574 messages) with a
  second, independently collected academic corpus, "SMS Phishing Dataset for Machine Learning and Pattern Recognition"
  (5,971 messages), then de-duplicating the union to 6,574 total.
- **Overlap check against TrustShield's existing corpora (exact normalized-text match):** of 6,573 rows after our own
  exact-text dedup, **4,924 already exist** in `ucI_sms_spam.jsonl` / `india_spam_sms.jsonl` / v3 train/validation (expected,
  since DIFrauD's SMS subset includes the same UCI corpus TrustShield already uses) — **1,649 rows are genuinely new text**,
  originating from the second, previously-absent academic corpus.
- **Independence assessment:** Partial. The 4,924 overlapping rows are NOT a new source (already counted). The 1,649
  non-overlapping rows are a genuinely new, independently collected real-world source not previously in TrustShield.
- **Approximate size added:** 1,649 net-new real rows (used in `v4_dataset_build.py`; the 4,924 overlapping rows are also
  included so the leakage-safe template grouping can correctly recognize and co-locate the duplicates — this adds no new
  information but is not a leakage risk, since duplicate copies always land in the same split by construction).
- **Note on file naming:** the upstream repo names its own internal splits `train`/`validation`/`test.jsonl`. Those are
  DIFrauD's own train/test split from its own paper, unrelated to TrustShield's spent v1 test split or v2 holdout; we merge
  all three into this single `all.jsonl` specifically so no filename here could ever be confused with, or trigger a refusal
  meant for, TrustShield's own spent-data paths.
