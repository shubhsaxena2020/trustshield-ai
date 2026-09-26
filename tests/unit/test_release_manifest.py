import hashlib
import json
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
RC_MANIFEST_SHA256 = "081f8818f633f7605bf45d2158f4791285d064f4598168c0dd32506ddb258b86"


def _sha(path: str) -> str:
    return hashlib.sha256((ROOT / path).read_bytes()).hexdigest()


def _same(path: str, digest: str) -> bool:
    """Recorded text-file hashes must survive Git's line-ending normalisation (Windows CRLF working copies vs LF checkouts)."""
    raw = (ROOT / path).read_bytes()
    lf = raw.replace(b"\r\n", b"\n")
    return digest in {hashlib.sha256(v).hexdigest() for v in (raw, lf, lf.replace(b"\n", b"\r\n"))}


@pytest.fixture(scope="module")
def manifest():
    return json.loads((ROOT / "models/release/v3_release_manifest.json").read_text(encoding="utf-8"))


def test_rc_manifest_is_immutable_historical_evidence(manifest):
    assert _same("models/release/v3_rc_manifest.json", RC_MANIFEST_SHA256)
    assert manifest["rc_reference"]["manifest_sha256"] == RC_MANIFEST_SHA256
    assert manifest["rc_reference"]["commit"] == "5548b2f5463daea8804dc4f4076067ea0df24904"


def test_release_manifest_identity(manifest):
    assert manifest["promoted_at_commit"].startswith("d239ca3")
    assert manifest["default_model"] == "trustshield_text_v3" and manifest["rollback_model"] == "trustshield_text_v1"
    v3 = manifest["models"]["trustshield_text_v3"]
    assert v3["threshold"] == 0.16 and v3["sklearn_version"] == "1.9.1"
    assert manifest["fusion"]["default_strategy"] == "ml_aware_weighted" and manifest["fusion"]["unchanged_by_promotion"] is True


def test_recorded_hashes_match_files(manifest):
    checked = 0

    def verify(path, digest):
        nonlocal checked
        if (ROOT / path).exists():
            assert _same(path, digest), path
            checked += 1

    for model in manifest["models"].values():
        verify(model["metadata"], model["metadata_sha256"])
    verify(manifest["data"]["train"], manifest["data"]["train_sha256"])
    verify(manifest["data"]["validation"], manifest["data"]["validation_sha256"])
    verify("requirements-lock.txt", manifest["environment"]["lock_file_sha256"])
    v2 = manifest["sealed_v2_evaluation"]
    verify("datasets/holdout/trustshield-text-v2-holdout/manifest.json", v2["holdout_manifest_sha256"])
    verify(v2["results_hashes_file"], v2["results_hashes_file_sha256"])
    for name, digest in v2["output_sha256"].items():
        verify(f"models/evaluation/v2_final/{name}", digest)  # text-bearing outputs are not published
    assert checked >= 5  # metadata x2, lock file, holdout manifest, results hashes file are always published


def test_model_artifacts_match_when_present(manifest):
    for model in manifest["models"].values():
        path = ROOT / model["artifact"]
        if not path.exists():
            pytest.skip("model artifacts are git-ignored; regenerate per docs/REPRODUCIBILITY.md")
        assert _sha(model["artifact"]) == model["artifact_sha256"]


def test_v2_is_recorded_as_spent(manifest):
    v2 = manifest["sealed_v2_evaluation"]
    assert v2["access_log_entries"] == 1 and "spent" in v2["status"]
    log = json.loads((ROOT / v2["access_log"]).read_text(encoding="utf-8"))
    assert len(log) == 1 and _same(v2["access_log"], v2["access_log_sha256"])
