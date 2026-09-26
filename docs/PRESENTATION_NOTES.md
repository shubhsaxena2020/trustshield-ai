# Judge Q&A notes
- **Just ChatGPT?** No. Rules, URL/domain analysis and (soon) ML detect; the explanation layer only restates evidence.
- **Accuracy?** We will report held-out metrics (confusion matrix, precision/recall/FPR/FNR). None published yet.
- **Missed scam?** Confidence is separate from score, "Unable to Verify" exists, and the false-negative rate is measured.
- **Site blocks the scanner?** Reduced confidence or Unable to Verify (sandbox pending).
- **Why not antivirus?** Our focus is fraud, phishing and social engineering, not malware files.
- **Current accuracy claim?** Sealed-holdout v3: recall 82.1%, specificity 78.3%, precision 82.1%, F1 82.1%. It did not reach our aspirational 90% recall/specificity goals; real-data performance is source-limited and domain-sensitive, so we report risk evidence and uncertainty, not guarantees of safety.
