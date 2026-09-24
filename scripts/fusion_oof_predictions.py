"""Create leakage-safe train out-of-fold ML probabilities for fusion tuning.

This script intentionally reads only ``train.jsonl``.  It never loads the
validation or test split and never accepts ``--split test``.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
from sklearn.calibration import CalibratedClassifierCV
from sklearn.model_selection import GroupKFold
from sklearn.pipeline import Pipeline
from sklearn.svm import LinearSVC

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from services.analysis.ml.features import build_feature_pipeline

SEED = 42
FOLDS = 5


def validate_split(value: str) -> str:
    if value != "train_oof":
        raise ValueError("fusion OOF generation only accepts --split train_oof; test is sealed")
    return value


def _safe_dataset_dir(path: Path) -> Path:
    resolved = path.resolve()
    parts = {part.lower() for part in resolved.parts}
    if "holdout" in parts or "test" in parts:
        raise ValueError("sealed test/holdout paths are not allowed")
    return resolved


def _read_train(path: Path) -> list[dict]:
    path = path.resolve()
    if path.name != "train.jsonl":
        raise ValueError("OOF generation may open only train.jsonl")
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def _make_model():
    base = Pipeline([
        ("features", build_feature_pipeline("combined")),
        ("classifier", LinearSVC(C=1.0, class_weight=None, random_state=SEED)),
    ])
    return CalibratedClassifierCV(estimator=base, method="sigmoid", cv=5)


def _scores(model, texts: list[str]) -> np.ndarray:
    probabilities = model.predict_proba(texts)
    classes = [str(value) for value in getattr(model, "classes_", range(probabilities.shape[1]))]
    if "malicious_or_fraudulent" in classes:
        return probabilities[:, classes.index("malicious_or_fraudulent")]
    if "1" in classes:
        return probabilities[:, classes.index("1")]
    return probabilities[:, -1]


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def generate(dataset_dir: str | Path, output_dir: str | Path | None = None, split: str = "train_oof") -> Path:
    validate_split(split)
    dataset_dir = _safe_dataset_dir(Path(dataset_dir))
    train_path = dataset_dir / "train.jsonl"
    rows = _read_train(train_path)
    if len(rows) < FOLDS:
        raise ValueError("train split is too small for five grouped folds")
    groups = [row.get("duplicate_group") or row["id"] for row in rows]
    texts = [row["text"] for row in rows]
    labels = [row["binary_label"] for row in rows]
    probabilities = np.zeros(len(rows), dtype=float)
    fold_numbers = np.zeros(len(rows), dtype=int)
    splitter = GroupKFold(n_splits=FOLDS)
    for fold, (train_indexes, holdout_indexes) in enumerate(splitter.split(texts, labels, groups), start=0):
        model = _make_model()
        model.fit([texts[index] for index in train_indexes], [labels[index] for index in train_indexes])
        probabilities[holdout_indexes] = _scores(model, [texts[index] for index in holdout_indexes])
        fold_numbers[holdout_indexes] = fold

    root = Path(output_dir or ROOT / "models" / "evaluation" / "fusion").resolve()
    root.mkdir(parents=True, exist_ok=True)
    prediction_path = root / "train_oof_predictions.csv"
    fields = ["id", "text", "binary_label", "category_label", "source_type", "synthetic", "source_name",
              "duplicate_group", "ml_p", "fold"]
    with prediction_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for index, row in enumerate(rows):
            writer.writerow({
                "id": row["id"], "text": row["text"], "binary_label": row["binary_label"],
                "category_label": row.get("category_label", ""), "source_type": row.get("source_type", ""),
                "synthetic": row.get("synthetic", False), "source_name": row.get("source_name", ""),
                "duplicate_group": groups[index], "ml_p": float(probabilities[index]), "fold": int(fold_numbers[index]),
            })
    metadata = {
        "split": "train_oof", "seed": SEED, "folds": FOLDS, "group_field": "duplicate_group",
        "architecture": "combined-feature LinearSVC(C=1.0) with sigmoid calibration cv=5",
        "dataset_dir": str(dataset_dir), "train_row_count": len(rows),
        "input_files": [str(train_path)], "prediction_file": str(prediction_path),
        "prediction_sha256": _sha256(prediction_path),
    }
    (root / "train_oof_metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    return prediction_path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--split", default="train_oof")
    parser.add_argument("--dataset-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args()
    try:
        path = generate(args.dataset_dir, args.output_dir, args.split)
    except ValueError as exc:
        parser.error(str(exc))
    print(json.dumps({"split": "train_oof", "predictions": str(path)}, indent=2))


if __name__ == "__main__":
    main()
