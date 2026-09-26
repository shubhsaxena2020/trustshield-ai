# v4 evaluation protocol (pre-registration)

**No holdout has been created, populated or evaluated by this document or by the conversational build.** It fixes the rules *before* any data exists.

1. **New sealed holdout only.** Built after the candidate is frozen, from sources not used in training/validation. Its SHA-256 manifest is
   recorded before sealing, with a remove-only near-duplicate check against train+validation. The v1 test split and v2 holdout are spent
   and excluded from every step.
2. **Freeze first.** Model file hash, feature pipeline, threshold and fusion are recorded in a release manifest before the holdout is opened.
3. **Pre-registered criteria (all required to promote over v3):** on the sealed holdout, recall >= 90%, specificity >= 90%, F1 >= 0.88; the
   real-source slice is reported separately and must not fall below v3's sealed-holdout recall/specificity; no worse than v3 on
   hard-negative categories; latency and the 2 GiB memory budget unchanged; zero security-control regressions.
4. **One evaluation, one decision.** A single scripted run with an access log. Results are published whether they pass or fail; there is no
   retuning, threshold change or re-run afterwards. A failed candidate is closed; a new attempt needs a new holdout.
5. **Reporting.** Raw predictions, independent recompute, slice tables, manual error analysis, and an honest statement of limits.
6. **Rollback.** v3 remains production and v1 remains the rollback until a candidate passes and is separately approved.
