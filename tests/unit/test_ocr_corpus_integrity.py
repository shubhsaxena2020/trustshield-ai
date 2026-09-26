from pathlib import Path
import json

from scripts.make_demo_fixtures import visible_transcription
from scripts.make_ocr_corpus import build_fixture_specs


ROOT = Path(__file__).resolve().parents[2]
CORPUS = ROOT / "benchmarks" / "ocr" / "corpus"


def test_corpus_ground_truth_matches_every_drawn_string_in_reading_order():
    specs = build_fixture_specs()
    manifest = json.loads((CORPUS / "manifest.json").read_text(encoding="utf-8"))
    manifest_by_id = {item["id"]: item for item in manifest["fixtures"]}

    for spec in specs:
        ground_truth = (CORPUS / f"{spec['id']}.txt").read_text(encoding="utf-8").splitlines()
        assert ground_truth == spec["visible_lines"]
        assert set(spec["visible_lines"]) == set(ground_truth)
        assert manifest_by_id[spec["id"]]["critical_tokens"] == spec["critical_tokens"]
        visible_text = "\n".join(ground_truth)
        for token in spec["critical_tokens"]:
            expected = token["value"]
            haystack = visible_text.casefold() if token["category"] == "org" else visible_text
            needle = expected.casefold() if token["category"] == "org" else expected
            assert needle in haystack


def test_demo_transcription_includes_the_rendered_header():
    message = "SBI KYC ALERT\nYour account will be blocked today."

    assert visible_transcription(message).splitlines() == [
        "TrustShield demo chat",
        "SBI KYC ALERT",
        "Your account will be blocked today.",
    ]
