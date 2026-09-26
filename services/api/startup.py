"""Release-identity and environment checks. Used by API startup (staging/production) and scripts/preflight.py.

Every failure is loud: the API refuses to start rather than serving a silently degraded system.
"""
from __future__ import annotations

import hashlib
import json
import os
import tempfile
from pathlib import Path

from .settings import Settings

ROOT = Path(__file__).resolve().parents[2]
RELEASE_MANIFEST = ROOT / "models" / "release" / "v3_release_manifest.json"
EXPECTED_MODEL = "trustshield_text_v3"
EXPECTED_THRESHOLD = 0.16


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def model_problems(root: Path = ROOT, model: str | None = None) -> list[str]:
    """Absent artifact, hash mismatch, load failure, sklearn incompatibility, wrong identity or threshold."""
    from services.analysis.ml.classifier import TextClassifier

    model = model or (os.getenv("TRUSTSHIELD_ML_MODEL", "").strip() or EXPECTED_MODEL)
    problems: list[str] = []
    manifest_path = root / "models" / "release" / "v3_release_manifest.json"
    if not manifest_path.is_file():
        return [f"release manifest missing: {manifest_path.name}"]
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if model not in manifest["models"]:
        return [f"model {model!r} is not in the release manifest"]
    expected = manifest["models"][model]
    artifact = root / expected["artifact"]
    if not artifact.is_file():
        return [f"MODEL ARTIFACT MISSING: {expected['artifact']} (ML would be unavailable)"]
    actual = _sha256(artifact)
    if actual != expected["artifact_sha256"]:
        problems.append(f"model artifact SHA-256 {actual[:12]}... differs from the promoted release {expected['artifact_sha256'][:12]}...")
    classifier = TextClassifier(model, project_root=root)
    if not classifier.available:
        problems.append(f"model failed to load: {classifier.reason}")
        return problems
    if classifier.model_name != model:
        problems.append(f"model identity {classifier.model_name!r} != {model!r}")
    threshold = float(classifier.metadata.get("threshold", -1))
    pinned = EXPECTED_THRESHOLD if model == EXPECTED_MODEL else float(expected["threshold"])
    if threshold != pinned or threshold != float(expected["threshold"]):
        problems.append(f"model threshold {threshold} != expected {pinned}")
    if classifier.metadata.get("sklearn_version") != expected["sklearn_version"]:
        problems.append("model metadata sklearn version differs from the release manifest")
    probe = classifier.predict("URGENT: your account is blocked, share the OTP at http://kyc-update.xyz")
    if probe.error is not None or not 0.0 <= probe.p_malicious <= 1.0:
        problems.append("model smoke prediction failed")
    return problems


def environment_problems(settings: Settings) -> list[str]:
    problems = list(settings.problems())
    if settings.strict:
        from services.analysis.extractors.ocr import get_ocr_provider

        provider = get_ocr_provider()
        if not provider.is_available():
            problems.append("Tesseract OCR is unavailable (set TESSERACT_CMD or install tesseract-ocr)")
        if os.getenv("TRUSTSHIELD_ENV", "").lower() == "test":
            problems.append("TRUSTSHIELD_ENV=test enables loopback allowances")
        if os.getenv("TRUSTSHIELD_FUSION", "ml_aware_weighted").strip() != "ml_aware_weighted":
            problems.append("TRUSTSHIELD_FUSION must be the default ml_aware_weighted")
        try:
            with tempfile.NamedTemporaryFile(delete=True):
                pass
        except OSError as exc:
            problems.append(f"temporary directory is not writable: {type(exc).__name__}")
    return problems


def startup_problems(settings: Settings) -> list[str]:
    return environment_problems(settings) + (model_problems() if settings.strict else [])
