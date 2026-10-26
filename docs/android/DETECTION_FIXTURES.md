# Android detection fixtures

These deterministic Hinglish fixtures cover utility disconnection threats. Each scam fixture must
score at least 60 and expose verbatim evidence for the disconnection wording and bill update action.
The ordinary bank credit notice must remain at 0.

| Case | Text | Minimum score |
|---|---|---:|
| Original | Aapka bijli connection aaj raat 9:30 baje kaat diya jayega. Turant is number par call karein 9876543210 aur bill update karein. | 60 |
| Katega | Aapka bijli connection aaj raat katega. Turant 9876543210 par call karein aur bill update kar dein. | 60 |
| Band | Aaj raat aapka bijli connection band kar diya jayega. Bill update karne ke liye call karein 9876543210. | 60 |
| Cut | Aapka electricity connection aaj raat cut ho jayega. Bill update karein aur 9876543210 par call karein. | 60 |
| Last date | Bijli bill ki last date aaj hai, warna connection kaat diya jayega. Bill update karne ko call karein 9876543210. | 60 |
| Last date paraphrase | Aaj last date hai: aapka power connection cut ho jayega. Bill update karein, call karein 9876543210. | 60 |
| Legitimate bank credit | Rs 5,000 credited to A/c XX1234. Avl bal Rs 12,340. Never share OTP. | 0 |

The JVM fixtures live in `LegitScamPairsTest`. They assert exact-quote evidence against the input;
the score remains a deterministic local rules result.
