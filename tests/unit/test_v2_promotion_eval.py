import hashlib
import json
from pathlib import Path

import pytest

from scripts import run_v2_promotion_eval as ev

ROOT = Path(__file__).resolve().parents[2]


def _m(recall=0.9, fpr=0.1, f1=0.8, n=100):
    return {"n": n, "recall": recall, "fpr": fpr, "f1": f1}


def _all(**overrides):
    base = {name: _m() for name in ("ALL", "REAL", "REAL_LEGIT", "REAL_FRAUD", "SYNTHETIC")}
    base.update(overrides)
    return base


AUX_OK = {"v1": {"roc_auc": 0.95, "brier": 0.06, "median_ms": 5.0}, "v3": {"roc_auc": 0.96, "brier": 0.06, "median_ms": 5.0}}
CI_OK = {"mean": -0.03, "ci95": [-0.06, 0.0]}


def test_promotes_only_when_real_gain_and_no_regression():
    m1 = _all()
    m3 = _all(REAL_LEGIT=_m(fpr=0.07))
    assert ev.evaluate_criteria(m1, m3, CI_OK, AUX_OK)["verdict"] == "PROMOTE"


def test_synthetic_only_gain_is_not_enough():
    m1 = _all()
    m3 = _all(ALL=_m(fpr=0.05), SYNTHETIC=_m(fpr=0.01))
    out = ev.evaluate_criteria(m1, m3, {"mean": 0.0, "ci95": [-0.01, 0.01]}, AUX_OK)
    assert out["checks"]["C4_real_gain"] is False and out["verdict"] == "DO_NOT_PROMOTE"


@pytest.mark.parametrize("override,check", [
    ({"REAL_FRAUD": _m(recall=0.85)}, "C1_recall_real_fraud_drop_le_0.02"),
    ({"REAL_LEGIT": _m(fpr=0.12)}, "C2_real_legit_fpr_not_higher"),
    ({"ALL": _m(fpr=0.12)}, "C3_overall_fpr_increase_le_0.01"),
])
def test_each_regression_blocks_promotion(override, check):
    out = ev.evaluate_criteria(_all(), _all(**override), CI_OK, AUX_OK)
    assert out["checks"][check] is False and out["verdict"] == "DO_NOT_PROMOTE"


def test_small_slices_block_promotion():
    m = _all(REAL_LEGIT=_m(fpr=0.05, n=10))
    m1 = _all(REAL_LEGIT=_m(n=10))
    assert ev.evaluate_criteria(m1, m, CI_OK, AUX_OK)["verdict"] == "DO_NOT_PROMOTE"


def test_refuses_without_confirmation_and_on_second_run(tmp_path):
    holdout = tmp_path / "h.jsonl"
    holdout.write_text("{}\n", encoding="utf-8")
    rc = tmp_path / "rc.json"
    rc.write_text(json.dumps({"commit": "x", "frozen_sha256": {}}), encoding="utf-8")
    log = tmp_path / "log.json"
    common = ["--holdout", str(holdout), "--rc-manifest", str(rc), "--access-log", str(log)]
    assert ev.main(common) == 2
    log.write_text('[{"already": "accessed"}]', encoding="utf-8")
    assert ev.main(common + ["--confirm-final-holdout"]) == 2
    assert json.loads(log.read_text()) == [{"already": "accessed"}]


def test_refuses_on_frozen_hash_mismatch_without_logging_access(tmp_path):
    holdout = tmp_path / "h.jsonl"
    holdout.write_text("{}\n", encoding="utf-8")
    rc = tmp_path / "rc.json"
    rc.write_text(json.dumps({"commit": "x", "frozen_sha256": {"README.md": "0" * 64}}), encoding="utf-8")
    log = tmp_path / "log.json"
    code = ev.main(["--holdout", str(holdout), "--rc-manifest", str(rc), "--access-log", str(log), "--confirm-final-holdout"])
    assert code == 2 and not log.exists()


def test_end_to_end_on_toy_holdout_never_touches_real_holdout(tmp_path):
    for name in ("models/trained/trustshield_text_v1.joblib", "models/trained/trustshield_text_v3.joblib"):
        if not (ROOT / name).exists():
            pytest.skip("model artifacts not present")
    rows = []
    for i in range(6):
        rows.append({"text": f"Your SBI account is blocked, verify OTP now at http://sbi-kyc{i}.xyz", "binary_label": "malicious_or_fraudulent", "source_name": "reportsmishing_imc25"})
        rows.append({"text": f"See you at lunch tomorrow number {i}", "binary_label": "legitimate", "source_name": "mendeley_financial_scams"})
    holdout = tmp_path / "toy.jsonl"
    holdout.write_text("\n".join(json.dumps(r) for r in rows), encoding="utf-8")
    frozen = {n: hashlib.sha256((ROOT / n).read_bytes()).hexdigest() for n in ("models/trained/trustshield_text_v1.joblib", "models/trained/trustshield_text_v3.joblib")}
    rc = tmp_path / "rc.json"
    rc.write_text(json.dumps({"commit": "toy", "frozen_sha256": frozen}), encoding="utf-8")
    log = tmp_path / "log.json"
    code = ev.main(["--holdout", str(holdout), "--rc-manifest", str(rc), "--access-log", str(log),
                    "--out-dir", str(tmp_path / "out"), "--confirm-final-holdout"])
    assert code == 0 and len(json.loads(log.read_text())) == 1
    result = json.loads((tmp_path / "out/results.json").read_text())
    assert result["verdict"] == "DO_NOT_PROMOTE"  # toy slices are below the minimum size
    assert (ROOT / "datasets/holdout/trustshield-text-v2-holdout/holdout_access_log.json").read_text().strip() == "[]"
