# Manual validation error analysis

This review covers the selected ML system at its validation-selected threshold
`p_malicious >= 0.12`. I read all 48 ML false positives and all 4 ML false
negatives in `predictions_validation.csv`. A 40-FN sample was impossible at
this operating point because only four ML FNs exist, so all four were read.
I also read the full 61-message combined-system FN list; those combined FNs
are discussed separately because they are risk-score/fusion decisions, not
the same classifier decision.

The cause labels below are human inferences from the displayed message text
and the recorded dataset label. They are not additional ground-truth
annotations. Counts are primary-cause counts and sum to the relevant error
set. Example IDs are real validation IDs.

## False positives: 48 total

| Primary cause | Count | Examples |
|---|---:|---|
| security-warning language | 9 | `agy-gen-legit-v1-083a7ecb`, `agy-gen-legit-v1-3f62680a`, `agy-gen-legit-v1-46450a90` |
| banking vocabulary | 5 | `agy-gen-legit-v1-21ac24c6`, `agy-gen-legit-v1-29fb7c61`, `agy-gen-legit-v1-8cd94163` |
| urgent legitimate notification | 8 | `agy-gen-legit-v1-146a8376`, `agy-gen-legit-v1-7603bba5`, `indiaspam-09f404d0` |
| payment terminology | 8 | `agy-gen-legit-v1-207e7d1d`, `agy-gen-legit-v1-a205fc6e`, `ucisms-c50115de` |
| other | 18 | `agy-gen-legit-v1-29eb82c1`, `ucisms-14f98515`, `ucisms-93579e6b` |

Observed pattern: synthetic legitimate controls deliberately contain OTP,
bank, payment, parcel, warning, and identity-document language. Public UCI
and India legitimate rows also contain ordinary promotions, casual chat, and
subscription terminology. These are genuine style and vocabulary controls,
not evidence that the labels are wrong. The threshold is low by design under
the fixed recall/specificity selection rule, so weak positive probabilities
become alerts.

## False negatives: 4 total

| Primary cause | Count | Examples |
|---|---:|---|
| unusual wording | 1 | `agy-gen-fraud-v1-e683ee16` |
| insufficient context | 1 | `agy-gen-fraud-v1-807159cc` |
| obfuscation | 0 | No selected-ML FN was primarily an obfuscation case. |
| new scam pattern | 1 | `ucisms-c479165f` |
| classifier weakness | 1 | `agy-gen-fraud-v1-28df6cf1` |
| rule gap | 0 | The QR/UPI case was caught by rules even though ML missed it. |

The four observed FN messages were: a QR/UPI-PIN cashback lure just below
the threshold (classifier weakness; rules caught it), a vague “Sarah from
accounting” opener with insufficient context, a family-impersonation wire
request with wording unlike the training fraud templates, and an older
dating/horoscope premium-message lure that does not resemble the dominant
bank/job/payment fraud language. Because each non-empty primary-cause bucket
contains only one ML FN, providing two or three distinct IDs for every bucket
would be inventing examples; the table reports the complete set honestly.

## Supplemental combined-system review

The combined system has 61 validation FNs at the fixed `risk.score >= 25`
primary point. I read the complete generated list. Many have high ML
probability but only medium ML evidence and therefore remain below the rule
score threshold; examples include `agy-gen-fraud-v1-13d9f926`,
`agy-gen-fraud-v1-36042a94`, `agy-gen-fraud-v1-dda5788f`, and
`agy-gen-fraud-v1-fac1c2c7`. This is an observed rule/fusion coverage gap,
not a claim that the text model failed on those rows. The existing rule
weights and severities were not changed to improve it, per the phase
constraint.

