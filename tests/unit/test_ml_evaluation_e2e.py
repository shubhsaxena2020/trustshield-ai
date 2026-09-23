from pathlib import Path

from scripts.evaluate_model import evaluate
from scripts.prepare_dataset import prepare_dataset
from scripts.train_classifier import train
from tests.fixtures.make_toy_dataset import write_toy_dataset


def test_validation_evaluation_writes_validation_outputs_without_test_access(tmp_path: Path):
    input_dir = tmp_path / "input"
    dataset_dir = tmp_path / "dataset"
    write_toy_dataset(input_dir)
    prepare_dataset(input_dir=input_dir, output_dir=dataset_dir)
    train(dataset_dir=dataset_dir, project_root=tmp_path, model_name="toy-model")
    result = evaluate(split="validation", systems=["ml"], dataset_dir=dataset_dir, project_root=tmp_path, model_name="toy-model")
    assert (tmp_path / "models" / "evaluation" / "validation" / "evaluation.json").exists()
    assert not (tmp_path / "models" / "evaluation" / "test_access_log.json").exists()
    assert result["split"] == "validation"
    scopes = result["systems"]["ml"]["multiclass"]
    assert set(scopes) == {"unmerged", "merged"}
    assert set(scopes["merged"]) == {"fraud_only", "all_labelled_incl_legitimate"}
