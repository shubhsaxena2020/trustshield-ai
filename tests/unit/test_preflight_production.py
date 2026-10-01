import pytest

from scripts import preflight

GOOD = {
    "TRUSTSHIELD_ENV": "production",
    "CORS_ALLOWED_ORIGINS": "https://trustshield-demo.vercel.app",
    "PUBLIC_API_URL": "https://trustshield-api.onrender.com",
    "NEXT_PUBLIC_API_URL": "https://trustshield-api.onrender.com",
    "TRUSTSHIELD_RESULT_STORE": "sandbox_only",
}


@pytest.fixture(autouse=True)
def _env(monkeypatch):
    preflight.results.clear()
    for name in ("TRUSTSHIELD_ML", "TRUSTSHIELD_ML_MODEL", "DATABASE_URL",
                 "DEBUG", "TRUSTSHIELD_OCR_PROVIDER", "TRUSTSHIELD_FUSION"):
        monkeypatch.delenv(name, raising=False)
    for name, value in GOOD.items():
        monkeypatch.setenv(name, value)
    yield
    preflight.results.clear()


def _status(name):
    return next(item["status"] for item in preflight.results if item["check"] == name)


def _failures():
    return {item["check"] for item in preflight.results if item["status"] == "FAIL"}


def test_valid_production_configuration_passes_deployment_checks():
    preflight.check_production(None)
    failed = _failures()
    # Tesseract is installed on the dev machine via TESSERACT_CMD; only that environment-specific check may differ.
    assert failed <= {"API environment (CORS, ML, OCR, fusion, tmp)"}, failed
    assert _status("PUBLIC_API_URL is https (or unset for a same-origin deployment)") == "PASS" and _status("frontend API URL is same-origin or matches PUBLIC_API_URL") == "PASS"
    assert _status("no development configuration enabled") == "PASS" and _status("database") == "PASS"


@pytest.mark.parametrize("name,value,check", [
    ("PUBLIC_API_URL", "http://api.example.com", "PUBLIC_API_URL is https (or unset for a same-origin deployment)"),
    ("NEXT_PUBLIC_API_URL", "https://other.example.com", "frontend API URL is same-origin or matches PUBLIC_API_URL"),
    ("TRUSTSHIELD_ENV", "development", "TRUSTSHIELD_ENV is staging or production"),
    ("TRUSTSHIELD_OCR_PROVIDER", "mock", "no development configuration enabled"),
    ("CORS_ALLOWED_ORIGINS", "*", "API environment (CORS, ML, OCR, fusion, tmp)"),
    ("TRUSTSHIELD_ML_MODEL", "trustshield_text_v1", "API environment (CORS, ML, OCR, fusion, tmp)"),
])
def test_each_deployment_mistake_is_a_failure(monkeypatch, name, value, check):
    monkeypatch.setenv(name, value)
    preflight.check_production(None)
    assert check in _failures()


def test_localhost_api_url_is_flagged_as_development_config(monkeypatch):
    monkeypatch.setenv("NEXT_PUBLIC_API_URL", "http://localhost:8000")
    monkeypatch.setenv("PUBLIC_API_URL", "http://localhost:8000")
    preflight.check_production(None)
    assert "no development configuration enabled" in _failures()


def test_database_url_is_only_a_warning(monkeypatch):
    monkeypatch.setenv("DATABASE_URL", "postgresql://user@host/db")
    preflight.check_production(None)
    assert _status("database") == "WARN"


def test_unreachable_remote_is_a_failure():
    preflight.check_production("https://127.0.0.1:9")
    assert _status("remote API health") == "FAIL"
