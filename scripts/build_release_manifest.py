"""Write models/release/v3_release_manifest.json (promoted-v3 release evidence).

The RC manifest (models/release/v3_rc_manifest.json) is immutable historical evidence and is
only *referenced* here. Reads no holdout rows; hashes only.
"""
from __future__ import annotations

import hashlib
import json
import platform
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROMOTED_COMMIT = "d239ca3df0d524d7335ae16946820803fe984ea2"
RC_COMMIT = "5548b2f5463daea8804dc4f4076067ea0df24904"
HOLDOUT = "datasets/holdout/trustshield-text-v2-holdout/"


def sha(path: str) -> str:
    return hashlib.sha256((ROOT / path).read_bytes()).hexdigest()


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def main() -> None:
    import joblib
    import numpy
    import scipy
    import sklearn

    v3 = json.loads((ROOT / "models/metadata/trustshield_text_v3.json").read_text(encoding="utf-8"))
    v1 = json.loads((ROOT / "models/metadata/trustshield_text_v1.json").read_text(encoding="utf-8"))
    access = json.loads((ROOT / HOLDOUT / "holdout_access_log.json").read_text(encoding="utf-8"))
    v2_hashes = json.loads((ROOT / "models/evaluation/v2_final/hashes.json").read_text(encoding="utf-8"))
    manifest = {
        "name": "trustshield-text-v3-release",
        "promoted_at_commit": PROMOTED_COMMIT,
        "promoted_commit_verified": git("rev-parse", "d239ca3") == PROMOTED_COMMIT,
        "rc_reference": {"tag": "v3-rc1", "commit": RC_COMMIT, "manifest": "models/release/v3_rc_manifest.json",
                         "manifest_sha256": sha("models/release/v3_rc_manifest.json"),
                         "note": "immutable historical RC evidence; never edited"},
        "default_model": "trustshield_text_v3",
        "rollback_model": "trustshield_text_v1",
        "rollback_how": "TRUSTSHIELD_ML_MODEL=trustshield_text_v1",
        "models": {
            "trustshield_text_v3": {"artifact": "models/trained/trustshield_text_v3.joblib", "artifact_sha256": sha("models/trained/trustshield_text_v3.joblib"),
                                    "metadata": "models/metadata/trustshield_text_v3.json", "metadata_sha256": sha("models/metadata/trustshield_text_v3.json"),
                                    "threshold": v3["threshold"], "sklearn_version": v3["sklearn_version"], "model_version": v3["model_version"],
                                    "byte_reproducible": False,
                                    "reproducibility": "docs/REPRODUCIBILITY.md (functionally identical on regeneration; bytes differ by embedded timestamps/timings)"},
            "trustshield_text_v1": {"artifact": "models/trained/trustshield_text_v1.joblib", "artifact_sha256": sha("models/trained/trustshield_text_v1.joblib"),
                                    "metadata": "models/metadata/trustshield_text_v1.json", "metadata_sha256": sha("models/metadata/trustshield_text_v1.json"),
                                    "threshold": v1["threshold"], "sklearn_version": v1["sklearn_version"], "model_version": v1["model_version"]},
        },
        "fusion": {"default_strategy": "ml_aware_weighted", "strategy_version": "phase4-1",
                   "parameters": {"ml_weight": 0.5, "strong_ml_cut": 0.7, "min_tokens_for_moderate_ml_floor": 3, "operating_point": "risk score >= 25 (caution+)"},
                   "unchanged_by_promotion": True},
        "data": {"train": "datasets/processed/trustshield-text-v3/train.jsonl", "train_sha256": sha("datasets/processed/trustshield-text-v3/train.jsonl"),
                 "validation": "datasets/processed/trustshield-text-v3/validation.jsonl", "validation_sha256": sha("datasets/processed/trustshield-text-v3/validation.jsonl"),
                 "manifest_sha256": sha("datasets/processed/trustshield-text-v3/manifest.json")},
        "environment": {"python": platform.python_version(), "scikit_learn": sklearn.__version__, "numpy": numpy.__version__, "scipy": scipy.__version__,
                        "joblib": joblib.__version__, "lock_file": "requirements-lock.txt", "lock_file_sha256": sha("requirements-lock.txt")},
        "sealed_v2_evaluation": {
            "status": "spent - permanently; never use for v4 development, tuning or model selection",
            "holdout_sha256": sha(HOLDOUT + "holdout.jsonl"), "holdout_manifest_sha256": sha(HOLDOUT + "manifest.json"),
            "access_log": HOLDOUT + "holdout_access_log.json", "access_log_sha256": sha(HOLDOUT + "holdout_access_log.json"), "access_log_entries": len(access),
            "evaluated_rc_tag": "v3-rc1", "results_dir": "models/evaluation/v2_final/", "results_hashes_file": "models/evaluation/v2_final/hashes.json",
            "results_hashes_file_sha256": sha("models/evaluation/v2_final/hashes.json"),
            "output_sha256": v2_hashes["outputs"], "report": "docs/V2_EVALUATION_REPORT.md", "promotion_criteria": "docs/V2_PROMOTION_CRITERIA.md",
            "headline_v3": {"n": 979, "recall": 0.821, "specificity": 0.783, "precision": 0.821, "f1": 0.821, "roc_auc": 0.877},
            "headline_v1": {"n": 979, "recall": 0.812, "specificity": 0.738, "precision": 0.790, "f1": 0.801, "roc_auc": 0.852},
            "goal_met": "no - below the aspirational 90% recall/specificity goals",
            "spent_scripts": {
                "policy": "refuse to run without --archival-override-v2-spent; results were not altered or regenerated",
                "scripts_sha256_at_time_of_v2_run": {
                    "scripts/run_v2_promotion_eval.py": "dfd513ce743bd1da4d184c8cf580441d0016871f8831c8a5c2b185b7bbe2c329",
                    "scripts/v2_postrun_analysis.py": "6f5a106891465bb5eec8cfd68ebedf5c2e871819124cb05bc12971a3174a9e07",
                    "scripts/evaluate_holdout_v2.py": "9981589469db68e46da42b1fa91b6f12677adc70c3283e134d6fccc8de9ce007",
                    "scripts/prepare_holdout_v2.py": "0126cbcae26524f192e91c50ab80c8be2a351d18ea68243ee88445330d21b230"},
                "only_change_since": "an archival-override guard at the start of main(); see scripts/_v2_spent_guard.py"},
        },
        "sandbox_state": {"external_browsing": "disabled (gate: independent adversarial review, see docs/THREAT_MODEL.md)"},
    }
    out = ROOT / "models/release/v3_release_manifest.json"
    out.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="")
    print(f"wrote {out.relative_to(ROOT)} (python {sys.version.split()[0]})")


if __name__ == "__main__":
    main()
