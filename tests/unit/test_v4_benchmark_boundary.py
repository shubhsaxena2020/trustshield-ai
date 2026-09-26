"""The v4 research benchmark may read only the development splits."""
import importlib.util
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "v4_benchmark.py"
spec = importlib.util.spec_from_file_location("v4_benchmark", SCRIPT)
v4 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v4)


@pytest.mark.parametrize("name", ["test.jsonl", "all.jsonl", "../x/holdout.jsonl", "manifest.json"])
def test_non_development_files_are_refused(name):
    with pytest.raises(SystemExit):
        v4.load(name)


def test_only_train_and_validation_allowed():
    assert v4.ALLOWED == {"train.jsonl", "validation.jsonl"}
    assert v4.DATA.name == "trustshield-text-v3"


def test_metrics_shape():
    import numpy as np
    m = v4.metrics(np.array([1, 1, 0, 0]), np.array([1, 0, 0, 1]), np.array([0.9, 0.2, 0.1, 0.8]))
    assert (m["tp"], m["fn"], m["tn"], m["fp"]) == (1, 1, 1, 1) and m["recall"] == 0.5 and m["specificity"] == 0.5
