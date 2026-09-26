from __future__ import annotations

import json
from pathlib import Path

from scripts.prepare_dataset import normalize_for_dedup, prepare_dataset, template_signature
from tests.fixtures.make_toy_dataset import write_toy_dataset


def test_schema_normalization_masks_digits_urls_and_collapses_whitespace():
    assert normalize_for_dedup("  Demo 42 https://fake.invalid/abc  ") == "demo 00 <url>"


def test_prepare_rejects_invalid_rows_and_deduplicates_with_holdout_and_no_leakage(tmp_path: Path):
    input_dir = tmp_path / "input"
    output_dir = tmp_path / "output"
    write_toy_dataset(input_dir)
    with (input_dir / "invalid.jsonl").open("a", encoding="utf-8") as handle:
        handle.write(json.dumps({"text": "bad", "binary_label": "unknown"}) + "\n")
    result = prepare_dataset(input_dir=input_dir, output_dir=output_dir)
    assert result["rejected_rows"] >= 1
    assigned = [json.loads(line) for line in (output_dir / "all.jsonl").read_text().splitlines()]
    splits = {name: [row for row in assigned if row["split"] == name] for name in ("train", "validation", "test")}
    assert all(row["source_name"].startswith("holdout-") is False for row in splits["train"] + splits["validation"])
    assert all(row["split"] == name for name, rows in splits.items() for row in rows)
    assert not ({row["duplicate_group"] for row in splits["train"]} & {row["duplicate_group"] for row in splits["validation"]})
    assert not ({row["duplicate_group"] for row in splits["train"]} & {row["duplicate_group"] for row in splits["test"]})
    assert not ({row["duplicate_group"] for row in splits["validation"]} & {row["duplicate_group"] for row in splits["test"]})
    normalized = {name: {normalize_for_dedup(row["text"]) for row in rows} for name, rows in splits.items()}
    assert not (normalized["train"] & normalized["validation"])
    assert not (normalized["train"] & normalized["test"])
    assert not (normalized["validation"] & normalized["test"])


def test_prepare_is_byte_deterministic(tmp_path: Path):
    input_dir = tmp_path / "input"
    write_toy_dataset(input_dir)
    first = tmp_path / "first"
    second = tmp_path / "second"
    prepare_dataset(input_dir=input_dir, output_dir=first)
    prepare_dataset(input_dir=input_dir, output_dir=second)
    for path in first.iterdir():
        assert path.read_bytes() == (second / path.name).read_bytes(), path.name


def test_near_duplicate_template_variants_share_duplicate_group(tmp_path: Path):
    input_dir = tmp_path / "input"
    input_dir.mkdir()
    rows = [
        {"text": "DemoBank says verify your synthetic account alpha at https://fake.invalid/a now. This message asks you to verify your synthetic account immediately and keep the account secure.", "binary_label": "malicious_or_fraudulent", "category_label": "phishing", "source_type": "synthetic", "source_name": "toy-a", "synthetic": True, "language": "en"},
        {"text": "DemoBank says verify your synthetic account bravo at https://fake.invalid/b now. This message asks you to verify your synthetic account immediately and keep the account secure.", "binary_label": "malicious_or_fraudulent", "category_label": "phishing", "source_type": "synthetic", "source_name": "toy-b", "synthetic": True, "language": "en"},
    ]
    (input_dir / "variants.jsonl").write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
    output_dir = tmp_path / "output"
    prepare_dataset(input_dir=input_dir, output_dir=output_dir)
    prepared = [json.loads(line) for line in (output_dir / "all.jsonl").read_text().splitlines()]
    assert len(prepared) == 2
    assert len({row["duplicate_group"] for row in prepared}) == 1
    group_id = prepared[0]["duplicate_group"]
    assert group_id == "dg-000001"
    groups = json.loads((output_dir / "duplicate_groups.json").read_text())
    assert groups[group_id] == sorted(row["id"] for row in prepared)
    report = (output_dir / "dedup_report.md").read_text(encoding="utf-8")
    assert "| 0.85 | 1 |" in report


def test_strict_template_signature_groups_reviewed_uci_variants(tmp_path: Path):
    variants = [
        "Congratulations! Thanks to a good friend U have WON the £2,000 Xmas prize. 2 claim is easy, just call 08718726971 NOW! Only 10p per minute. BT-national-rate.",
        "Congratulations - Thanks to a good friend U have WON the £2,000 Xmas prize. 2 claim is easy, just call 08712103738 NOW! Only 10p per minute. BT-national-rate",
        "URGENT! We are trying to contact U. Todays draw shows that you have won a £800 prize GUARANTEED. Call 09050001295 from land line. Claim A21. Valid 12hrs only",
        "URGENT! We are trying to contact U. Todays draw shows that you have won a £800 prize GUARANTEED. Call 09050003091 from land line. Claim C52. Valid12hrs only",
    ]
    assert template_signature(variants[0], "legacy") != template_signature(variants[1], "legacy")
    assert template_signature(variants[2], "legacy") != template_signature(variants[3], "legacy")
    assert template_signature(variants[0], "strict") == template_signature(variants[1], "strict")
    assert template_signature(variants[2], "strict") == template_signature(variants[3], "strict")
    assert "<reference>" in template_signature(variants[2], "strict")

    input_dir = tmp_path / "input"
    input_dir.mkdir()
    rows = [{"text": text, "binary_label": "malicious_or_fraudulent", "category_label": "prize_lottery_scam", "source_type": "public_dataset", "source_name": f"review-{index}", "synthetic": False, "language": "en"} for index, text in enumerate(variants)]
    (input_dir / "variants.jsonl").write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
    legacy_dir, strict_dir = tmp_path / "legacy", tmp_path / "strict"
    prepare_dataset(input_dir=input_dir, output_dir=legacy_dir)
    prepare_dataset(input_dir=input_dir, output_dir=strict_dir, near_dup_threshold=0.80, template_signature_mode="strict")
    legacy_rows = [json.loads(line) for line in (legacy_dir / "all.jsonl").read_text().splitlines()]
    strict_rows = [json.loads(line) for line in (strict_dir / "all.jsonl").read_text().splitlines()]
    assert len({row["duplicate_group"] for row in legacy_rows}) == 4
    assert len({row["duplicate_group"] for row in strict_rows}) == 2


def test_cap_is_seeded_pre_dedup_and_keeps_fraud(tmp_path: Path):
    input_dir = tmp_path / "input"
    input_dir.mkdir()
    rows = []
    words = ["alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel", "india", "juliet"]
    for index in range(10):
        rows.append({"text": f"Legitimate control message variant {words[index]} with enough context.", "binary_label": "legitimate", "category_label": "legitimate", "source_type": "public_dataset", "source_name": "cap-source", "synthetic": False, "language": "en"})
    for index in range(3):
        rows.append({"text": f"Fraud message asks for a payment fee variant {words[index]}.", "binary_label": "malicious_or_fraudulent", "category_label": "payment_fraud", "source_type": "public_dataset", "source_name": "cap-source", "synthetic": False, "language": "en"})
    (input_dir / "rows.jsonl").write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
    first, second = tmp_path / "first", tmp_path / "second"
    prepare_dataset(input_dir=input_dir, output_dir=first, caps=["cap-source:legitimate=4"])
    prepare_dataset(input_dir=input_dir, output_dir=second, caps=["cap-source:legitimate=4"])
    assert (first / "all.jsonl").read_bytes() == (second / "all.jsonl").read_bytes()
    manifest = json.loads((first / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["pre_cap_counts"]["cap-source:legitimate"] == 10
    assert manifest["post_cap_counts"]["cap-source:legitimate"] == 4
    output_rows = [json.loads(line) for line in (first / "all.jsonl").read_text().splitlines()]
    assert sum(row["binary_label"] == "legitimate" for row in output_rows) == 4
    assert sum(row["binary_label"] == "malicious_or_fraudulent" for row in output_rows) == 3
