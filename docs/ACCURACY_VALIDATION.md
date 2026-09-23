# Accuracy validation

**No accuracy figures exist yet.** Nothing in this repo may claim one until produced by an evaluation script on the held-out test set.

Plan: labelled dataset (fraud categories + legitimate controls) split train/validation/test with fixed seeds and no leakage; report accuracy, precision, recall, F1, specificity, FPR, FNR, confusion matrix, macro-F1; compare TF-IDF+LogReg vs Linear SVM on validation only; test set used once.
Targets (not results): recall ≥90%, specificity ≥90%, macro-F1 ≥85%.

The 44 unit/integration/security tests verify behaviour on hand-written cases; they are **not** an accuracy measurement.
