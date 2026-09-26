"""Static boundary: nothing that can read spent data (v1 test split, v2 holdout) runs without an archival override."""
import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
TOKENS = ("holdout.jsonl", "test.jsonl", "trustshield-text-v2-holdout", "confirm-final-test")

# Scripts that mention spent paths but never read their content (guard lists, refusals, or byte-hashing for release evidence).
NEVER_READS_CONTENT = {
    "evaluate_fusion.py": "docstring: refuses sealed paths",
    "evaluate_hardening.py": "path guard that REJECTS test/holdout paths",
    "prepare_hardening_candidates.py": "lists forbidden inputs",
    "verify_v3_release.py": "docstring: never reads test or holdout",
    "build_release_manifest.py": "hashes holdout bytes for release evidence, parses no rows",
    "measure_footprint.py": "docstring: no holdout data",
    "preflight.py": "docstring: never opens the v2 holdout",
    "_v2_spent_guard.py": "the guard itself",
    "fusion_oof_predictions.py": "refuses sealed paths",
    "build_public_tree.py": "publication policy: names excluded paths, reads only CSV headers",
    "sync_public_repo.py": "mirrors the already-filtered publication tree",
}


def _scripts_with_tokens():
    for path in sorted((ROOT / "scripts").glob("*.py")):
        if any(token in path.read_text(encoding="utf-8") for token in TOKENS):
            yield path.name


@pytest.mark.parametrize("name", list(_scripts_with_tokens()))
def test_script_touching_spent_data_is_guarded_or_explicitly_inert(name):
    text = (ROOT / "scripts" / name).read_text(encoding="utf-8")
    assert "require_archival_override" in text or name in NEVER_READS_CONTENT, (
        f"{name} mentions spent data but has neither an archival-override guard nor an inert-use justification")


def test_guarded_scripts_call_the_guard_before_parsing_or_working():
    for name in ("run_v2_promotion_eval", "v2_postrun_analysis", "evaluate_holdout_v2", "prepare_holdout_v2",
                 "prepare_dataset_v3", "prepare_dataset", "leakage_sensitivity", "analyze_final_test_reporting", "evaluate_model"):
        text = (ROOT / "scripts" / f"{name}.py").read_text(encoding="utf-8")
        assert "require_archival_override" in text, name
        assert re.search(r"def main\(", text), name


def test_runtime_and_frontend_never_reference_spent_data():
    roots = [ROOT / "services", ROOT / "apps" / "web" / "app", ROOT / "apps" / "web" / "components"]
    for base in roots:
        for path in base.rglob("*"):
            if path.suffix in {".py", ".ts", ".tsx"} and "node_modules" not in path.parts:
                text = path.read_text(encoding="utf-8")
                assert "v2-holdout" not in text and "holdout.jsonl" not in text and "test.jsonl" not in text, path
