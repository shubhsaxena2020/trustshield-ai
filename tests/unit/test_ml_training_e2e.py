from pathlib import Path
import json

from scripts.prepare_dataset import prepare_dataset
from scripts.train_classifier import train
from tests.fixtures.make_toy_dataset import write_toy_dataset


def test_train_classifier_end_to_end_on_toy_dataset(tmp_path: Path):
    input_dir = tmp_path / "input"
    dataset_dir = tmp_path / "dataset"
    write_toy_dataset(input_dir)
    prepare_dataset(input_dir=input_dir, output_dir=dataset_dir)
    result = train(dataset_dir=dataset_dir, project_root=tmp_path, model_name="toy-model")
    assert result["model_name"] == "toy-model"
    assert (tmp_path / "models" / "trained" / "toy-model.joblib").exists()
    assert (tmp_path / "models" / "metadata" / "toy-model.json").exists()
    assert (tmp_path / "models" / "evaluation" / "validation" / "model_comparison.csv").exists()
    metadata = json.loads((tmp_path / "models" / "metadata" / "toy-model.json").read_text(encoding="utf-8"))
    scopes = metadata["validation_metrics"]["category_f1_scopes"]
    assert set(scopes) == {"unmerged", "merged"}
    assert set(scopes["merged"]) == {"fraud_only", "all_labelled_incl_legitimate"}
    assert metadata["validation_metrics"]["category_macro_f1"] == scopes["merged"]["fraud_only"]["macro_f1"]
