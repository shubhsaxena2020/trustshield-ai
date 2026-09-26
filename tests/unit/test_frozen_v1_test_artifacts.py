"""Integrity checks for the immutable v1 held-out evaluation record."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[2]
FROZEN_DIR = ROOT / "models" / "evaluation" / "test_v1_frozen"
ORIGINAL_TEST_DIR = ROOT / "models" / "evaluation" / "test"
MODEL_PATH = ROOT / "models" / "trained" / "trustshield_text_v1.joblib"
METADATA_PATH = ROOT / "models" / "metadata" / "trustshield_text_v1.json"
DATASET_DIR = ROOT / "datasets" / "processed" / "trustshield-text-v1"
ACCESS_LOG_PATH = ROOT / "models" / "evaluation" / "test_access_log.json"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_manifest() -> dict:
    return json.loads((FROZEN_DIR / "FROZEN_MANIFEST.json").read_text(encoding="utf-8"))


def test_original_v1_test_artifacts_and_provenance_are_frozen() -> None:
    if not MODEL_PATH.exists():
        pytest.skip("v1 model artifact is absent; skipping frozen-artifact check on a fresh clone")

    if not (DATASET_DIR / "test.jsonl").exists() or not ORIGINAL_TEST_DIR.exists():
        pytest.skip("v1 test split / evaluation record is not part of the published tree")
    manifest = load_manifest()
    files = manifest["files"]
    expected_originals = (
        "evaluation.json",
        "predictions_test.csv",
        "system_comparison.csv",
        "confusion_matrix_rules.csv",
        "confusion_matrix_ml.csv",
        "confusion_matrix_combined.csv",
        "per_class_metrics.csv",
    )

    for name in expected_originals:
        original = ORIGINAL_TEST_DIR / name
        assert original.exists(), f"missing original frozen evaluation artifact: {original}"
        assert files[name]["sha256"] == sha256(original)
        assert sha256(FROZEN_DIR / name) == files[name]["sha256"]

    assert manifest["model_artifact_sha256"] == sha256(MODEL_PATH)
    assert manifest["metadata_sha256"] == sha256(METADATA_PATH)
    assert manifest["dataset_manifest_sha256"] == sha256(DATASET_DIR / "manifest.json")
    assert manifest["test_jsonl_sha256"] == sha256(DATASET_DIR / "test.jsonl")

    access_log = json.loads(ACCESS_LOG_PATH.read_text(encoding="utf-8"))
    assert len(access_log) == 1
    entry = access_log[0]
    assert entry["model_artifact_sha256"] == manifest["model_artifact_sha256"]
    assert entry["dataset_manifest_sha256"] == manifest["dataset_manifest_sha256"]

