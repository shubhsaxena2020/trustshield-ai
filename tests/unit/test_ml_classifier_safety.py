import hashlib
import json
from pathlib import Path

import joblib

from services.analysis.ml.classifier import TextClassifier


def _artifact(tmp_path: Path) -> tuple[Path, Path]:
    trained = tmp_path / "models" / "trained"
    metadata = tmp_path / "models" / "metadata"
    trained.mkdir(parents=True)
    metadata.mkdir(parents=True)
    artifact = trained / "toy.joblib"
    joblib.dump({"binary_model": None}, artifact)
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    (metadata / "toy.json").write_text(json.dumps({"artifact_sha256": digest, "artifact_filename": "toy.joblib"}))
    return trained, metadata


def test_tampered_artifact_is_unavailable_without_loading(tmp_path: Path):
    trained, metadata = _artifact(tmp_path)
    artifact = trained / "toy.joblib"
    artifact.write_bytes(artifact.read_bytes() + b"tampered")
    loaded = TextClassifier("toy", project_root=tmp_path)
    assert loaded.available is False
    assert "sha256" in loaded.reason.lower()


def test_artifact_path_escape_is_refused(tmp_path: Path):
    trained = tmp_path / "models" / "trained"
    metadata = tmp_path / "models" / "metadata"
    trained.mkdir(parents=True)
    metadata.mkdir(parents=True)
    (metadata / "toy.json").write_text(json.dumps({"artifact_filename": "..\\outside.joblib", "artifact_sha256": "0" * 64}))
    loaded = TextClassifier("toy", project_root=tmp_path)
    assert loaded.available is False
    assert "outside" in loaded.reason.lower() or "path" in loaded.reason.lower()


def test_missing_artifact_is_unavailable(tmp_path: Path):
    (tmp_path / "models" / "metadata").mkdir(parents=True)
    (tmp_path / "models" / "metadata" / "toy.json").write_text(json.dumps({"artifact_filename": "toy.joblib"}))
    loaded = TextClassifier("toy", project_root=tmp_path)
    assert loaded.available is False
    assert "artifact" in loaded.reason.lower()


def test_corrupt_artifact_is_unavailable(tmp_path: Path):
    trained = tmp_path / "models" / "trained"
    metadata = tmp_path / "models" / "metadata"
    trained.mkdir(parents=True); metadata.mkdir(parents=True)
    artifact = trained / "toy.joblib"
    artifact.write_bytes(b"not a joblib")
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    (metadata / "toy.json").write_text(json.dumps({"artifact_sha256": digest, "artifact_filename": "toy.joblib"}))
    loaded = TextClassifier("toy", project_root=tmp_path)
    assert loaded.available is False
    assert loaded.reason


def test_sklearn_version_mismatch_is_unavailable(tmp_path: Path):
    trained, metadata = _artifact(tmp_path)
    artifact = trained / "toy.joblib"
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    (metadata / "toy.json").write_text(json.dumps({"artifact_sha256": digest, "artifact_filename": "toy.joblib", "sklearn_version": "0.0.invalid"}))
    loaded = TextClassifier("toy", project_root=tmp_path)
    assert loaded.available is False
    assert "sklearn version" in loaded.reason.lower()
