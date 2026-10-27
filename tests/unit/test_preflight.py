import socket

from pathlib import Path

import pytest

from scripts import preflight

V3_ARTIFACT = Path(__file__).resolve().parents[2] / "models" / "trained" / "trustshield_text_v3.joblib"
MODEL_REQUIRED = pytest.mark.skipif(
    not V3_ARTIFACT.is_file(),
    reason="MODEL_ARTIFACT_MISSING: trustshield_text_v3.joblib is required for the real-project preflight; no model was downloaded.",
)


@pytest.fixture(autouse=True)
def _reset(monkeypatch):
    preflight.results.clear()
    for name in ("TRUSTSHIELD_ML", "TRUSTSHIELD_ML_MODEL", "TRUSTSHIELD_ENV"):
        monkeypatch.delenv(name, raising=False)
    yield
    preflight.results.clear()


def _status(name):
    return next(item["status"] for item in preflight.results if item["check"] == name)


@MODEL_REQUIRED
def test_healthy_model_checks_pass_on_the_real_project():
    preflight.check_model()
    assert _status("default model constant") == "PASS"
    assert _status("v3 artifact loads") == "PASS"
    assert _status("v3 threshold") == "PASS"
    assert _status("v3 smoke prediction") == "PASS"


def test_missing_v3_artifact_is_a_loud_failure(monkeypatch, tmp_path):
    monkeypatch.setattr(preflight, "ROOT", tmp_path)
    preflight.check_model()
    assert _status("v3 artifact present") == "FAIL"
    detail = next(item["detail"] for item in preflight.results if item["check"] == "v3 artifact present")
    assert "UNAVAILABLE" in detail and "rules-only" in detail


def test_ml_off_is_a_failure(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_ML", "off")
    preflight.check_model()
    assert _status("ML enabled") == "FAIL"


def test_model_override_to_v1_warns(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_ML_MODEL", "trustshield_text_v1")
    preflight.check_model()
    assert _status("model override") == "WARN"


def test_busy_port_fails():
    with socket.socket() as holder:
        holder.bind(("127.0.0.1", 0))
        holder.listen(1)
        port = holder.getsockname()[1]
        preflight.check_ports([port])
    assert _status(f"port {port} free") == "FAIL"


def test_crashing_check_is_reported_as_failure(monkeypatch):
    monkeypatch.setattr(preflight, "check_ocr", lambda: (_ for _ in ()).throw(RuntimeError("boom")))
    monkeypatch.setattr(preflight, "check_model", lambda: None)
    monkeypatch.setattr(preflight, "check_api", lambda: None)
    monkeypatch.setattr(preflight, "check_frontend", lambda: None)
    assert preflight.main(["--ports"]) == 1
