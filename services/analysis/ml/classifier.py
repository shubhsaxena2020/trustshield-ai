"""Safe, provenance-checked loading and inference for trained text models."""
from __future__ import annotations

import hashlib
import json
import math
import os
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import joblib


@dataclass(frozen=True)
class MLPrediction:
    model_name: str
    model_version: str
    dataset_version: str
    dataset_manifest_sha256: str
    feature_pipeline_version: str
    calibration_version: str
    p_malicious: float
    threshold: float
    predicted_binary: bool
    category: str | None
    category_probs: dict[str, float] | None
    latency_ms: float
    error: str | None = field(default=None, compare=False)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


class TextClassifier:
    def __init__(self, model_name: str | None = None, project_root: str | Path | None = None):
        self.project_root = Path(project_root or Path(__file__).resolve().parents[3]).resolve()
        configured_name = os.getenv("TRUSTSHIELD_ML_MODEL", "").strip()
        self.model_name = model_name or configured_name or "trustshield_text_v1"
        self.available = False
        self.reason = ""
        self.metadata: dict[str, Any] = {}
        self.artifact: dict[str, Any] | None = None
        self._load()

    def _unavailable(self, reason: str) -> None:
        self.available = False
        self.reason = reason

    def _load(self) -> None:
        try:
            if not self.model_name or Path(self.model_name).name != self.model_name or self.model_name in {".", ".."}:
                raise ValueError("model name must be a single safe filename stem")
            metadata_root = (self.project_root / "models" / "metadata").resolve()
            trained_root = (self.project_root / "models" / "trained").resolve()
            metadata_path = (metadata_root / f"{self.model_name}.json").resolve()
            if metadata_root not in metadata_path.parents:
                raise ValueError("metadata path escapes project metadata directory")
            if not metadata_path.is_file():
                raise FileNotFoundError(f"metadata not found: {metadata_path}")
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            expected_sklearn = str(metadata.get("sklearn_version", ""))
            if expected_sklearn and expected_sklearn != __import__("sklearn").__version__:
                raise RuntimeError(f"sklearn version mismatch: artifact={expected_sklearn}, runtime={__import__('sklearn').__version__}")
            artifact_name = str(metadata.get("artifact_filename") or f"{self.model_name}.joblib")
            artifact_path = (trained_root / artifact_name).resolve()
            if trained_root not in artifact_path.parents or artifact_path.suffix != ".joblib":
                raise ValueError("artifact path escapes project trained directory")
            if not artifact_path.is_file():
                raise FileNotFoundError(f"artifact not found: {artifact_path}")
            expected = str(metadata.get("artifact_sha256", ""))
            actual = _sha256(artifact_path)
            if not expected or expected != actual:
                raise ValueError("artifact sha256 verification failed")
            artifact = joblib.load(artifact_path)
            if not isinstance(artifact, dict):
                raise ValueError("artifact must be a dictionary payload")
            if not any(artifact.get(key) is not None for key in ("binary_model", "multiclass_model")):
                raise ValueError("artifact contains no supported classifier")
            self.metadata, self.artifact, self.available = metadata, artifact, True
        except Exception as exc:  # Loading is a boundary: no model failure may reach the analysis pipeline.
            self._unavailable(str(exc))

    @staticmethod
    def _probability(model: Any, text: str) -> tuple[float, dict[str, float] | None]:
        if hasattr(model, "predict_proba"):
            probabilities = model.predict_proba([text])[0]
            classes = list(getattr(model, "classes_", range(len(probabilities))))
            pairs = {str(label): float(value) for label, value in zip(classes, probabilities)}
            malicious = pairs.get("malicious_or_fraudulent", pairs.get("1", float(probabilities[-1])))
            return max(0.0, min(1.0, malicious)), pairs
        if hasattr(model, "decision_function"):
            score = float(model.decision_function([text])[0])
            probability = 1.0 / (1.0 + math.exp(-max(-60.0, min(60.0, score))))
            return probability, None
        prediction = model.predict([text])[0]
        return float(prediction in {1, "1", "malicious_or_fraudulent"}), None

    def _safe_prediction(self, text: str, started: float) -> MLPrediction:
        return MLPrediction(
            self.model_name, str(self.metadata.get("model_version", "unavailable")),
            str(self.metadata.get("dataset_version", "unknown")), str(self.metadata.get("dataset_manifest_sha256", "")),
            str(self.metadata.get("feature_pipeline_version", "unknown")), str(self.metadata.get("calibration_version", "unknown")),
            0.0, float(self.metadata.get("threshold", 0.5)), False, None, None,
            (time.perf_counter() - started) * 1000.0, self.reason,
        )

    def predict(self, text: str) -> MLPrediction:
        started = time.perf_counter()
        if not self.available or self.artifact is None:
            return self._safe_prediction(text, started)
        try:
            binary_model = self.artifact.get("binary_model")
            multiclass_model = self.artifact.get("multiclass_model")
            category_model = self.artifact.get("category_model")
            category_probs = None
            if multiclass_model is not None:
                probabilities = multiclass_model.predict_proba([text])[0]
                classes = [str(label) for label in getattr(multiclass_model, "classes_", range(len(probabilities)))]
                pairs = {label: float(value) for label, value in zip(classes, probabilities)}
                p_malicious = 1.0 - pairs.get("legitimate", 0.0)
                category_probs = pairs
                category = max(((label, value) for label, value in pairs.items() if label != "legitimate"), key=lambda item: item[1], default=(None, 0.0))[0] if p_malicious >= float(self.metadata.get("threshold", 0.5)) else None
            else:
                p_malicious, _ = self._probability(binary_model, text)
                category = None
                if category_model is not None and p_malicious >= float(self.metadata.get("threshold", 0.5)):
                    category = str(category_model.predict([text])[0])
                    if hasattr(category_model, "predict_proba"):
                        probs = category_model.predict_proba([text])[0]
                        category_probs = {str(label): float(value) for label, value in zip(category_model.classes_, probs)}
            threshold = float(self.metadata.get("threshold", 0.5))
            return MLPrediction(
                self.model_name, str(self.metadata.get("model_version", "1")), str(self.metadata.get("dataset_version", "unknown")),
                str(self.metadata.get("dataset_manifest_sha256", "")), str(self.metadata.get("feature_pipeline_version", "unknown")),
                str(self.metadata.get("calibration_version", "unknown")), max(0.0, min(1.0, float(p_malicious))), threshold,
                float(p_malicious) >= threshold, category, category_probs, (time.perf_counter() - started) * 1000.0,
            )
        except Exception as exc:
            self.reason = f"inference failed: {exc}"
            return self._safe_prediction(text, started)
