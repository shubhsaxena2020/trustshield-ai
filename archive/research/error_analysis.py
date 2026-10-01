"""Write first-pass, human-reviewable FP/FN cause tags for an evaluation CSV."""
from __future__ import annotations

import argparse
import csv
from pathlib import Path


HEURISTICS = {
    "security-warning language": ("never share", "security alert", "do not disclose"),
    "banking vocabulary": ("bank", "account", "kyc", "otp", "pin", "cvv"),
    "urgency": ("now", "urgent", "immediately", "today", "within"),
    "payment terminology": ("pay", "fee", "refund", "invoice", "transfer", "upi"),
    "short text": (),
    "no link": (),
    "obfuscation": ("hxxp", "[.]", "0tp", "p@y", "free-gift"),
}


def tags(text: str) -> list[str]:
    lowered = text.lower()
    result = [name for name, terms in HEURISTICS.items() if terms and any(term in lowered for term in terms)]
    if len(text.split()) < 8:
        result.append("short text")
    if "http://" not in lowered and "https://" not in lowered and "www." not in lowered:
        result.append("no link")
    return result or ["other"]


def write_error_analysis(predictions_csv: str | Path, output_md: str | Path, threshold: float = 0.5) -> None:
    with Path(predictions_csv).open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    false_positive = [row for row in rows if row.get("binary_label") == "legitimate" and str(row.get("ml_p", "")).lower() not in {"", "nan"} and float(row["ml_p"]) >= threshold]
    false_negative = [row for row in rows if row.get("binary_label") == "malicious_or_fraudulent" and str(row.get("ml_p", "")).lower() not in {"", "nan"} and float(row["ml_p"]) < threshold]
    lines = ["# Error analysis", "", "Heuristics are a first pass only; a human/agent review must follow.", "", "Tags: security-warning language, banking vocabulary, urgency, payment terminology, short text, no link, obfuscation, other.", "", "## False positives", ""]
    for row in false_positive:
        lines.append(f"- `{row['id']}` ({', '.join(tags(row.get('text', '')))})")
    lines += ["", "## False negatives", ""]
    for row in false_negative:
        lines.append(f"- `{row['id']}` ({', '.join(tags(row.get('text', '')))})")
    Path(output_md).write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--predictions", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--threshold", type=float, default=0.5)
    args = parser.parse_args()
    write_error_analysis(args.predictions, args.output, args.threshold)


if __name__ == "__main__":
    main()
