"""Generate the deterministic, clearly synthetic dataset used by ML tooling tests."""
from __future__ import annotations

import json
from pathlib import Path


def _alpha_code(value: int) -> str:
    result = ""
    while True:
        result = chr(ord("a") + value % 26) + result
        value = value // 26 - 1
        if value < 0:
            return result


def _variant(value: int) -> str:
    return " ".join(_alpha_code(value * factor + factor) for factor in (1, 7, 13, 29, 47))


def make_rows() -> list[dict]:
    rows: list[dict] = []
    markers = [
        "alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel", "india", "juliet",
        "kilo", "lima", "mike", "november", "oscar", "papa", "quebec", "romeo", "sierra", "tango",
        "uniform", "victor", "whiskey", "xray", "yankee", "zulu",
    ]
    legitimate = [
        "ToyBank alert: your demo account balance is 120 credits. Never share your OTP.",
        "Demo College reminder: the synthetic orientation starts at 10:00 in room A.",
        "ToyShop shipping notice: your fictional parcel is scheduled for delivery tomorrow.",
        "Demo Utility reminder: your practice bill is due on the twentieth of this month.",
    ]
    malicious = [
        ("phishing", "ToyBank KYC notice: verify your demo account at https://example.invalid/fake/{i} now."),
        ("payment_fraud", "Synthetic invoice alert: pay the fictional processing fee of 20 credits at once."),
        ("fake_job", "Practice work offer: pay a demo registration fee to unlock your remote job."),
        ("other_fraud", "Fictional reward claim: send the demo code to receive an invented prize."),
    ]
    for i in range(200):
        if i < 100:
            text = legitimate[i % len(legitimate)].replace("demo", f"demo-{markers[i % len(markers)]}-{i // len(markers)}")
            text += f" Marker {markers[i % len(markers)]} fixture-{_alpha_code(i)} variant {_variant(i)}."
            rows.append({
                "text": text,
                "binary_label": "legitimate",
                "category_label": "legitimate",
                "source_type": "legitimate_control",
                "source_name": "toy-controls",
                "synthetic": True,
                "language": "en",
                "notes": "clearly synthetic test control",
            })
        else:
            category, template = malicious[(i - 100) % len(malicious)]
            rows.append({
                "text": template.format(i=f"{markers[i % len(markers)]}-{_alpha_code(i)}") + f" Synthetic marker {_alpha_code(i)} variant {_variant(i)}.",
                "binary_label": "malicious_or_fraudulent",
                "category_label": category,
                "source_type": "synthetic",
                "source_name": "toy-generator-set-a",
                "synthetic": True,
                "language": "en",
                "notes": "clearly synthetic test example",
            })
    for i in range(12):
        rows[188 + i]["source_name"] = "holdout-toy-author"
        rows[188 + i]["notes"] = "clearly synthetic independent holdout"
    return rows


def write_toy_dataset(directory: str | Path) -> Path:
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "toy.jsonl"
    path.write_text("".join(json.dumps(row, sort_keys=True) + "\n" for row in make_rows()), encoding="utf-8")
    return path


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("output_dir", type=Path)
    write_toy_dataset(parser.parse_args().output_dir)
