"""Production hardening: headers, request IDs, CORS, rate limits, body limits, safe errors, stores, readiness, startup checks."""
import io
import json
import logging
import threading
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from services.api import main as api
from services.api.security import RateLimiter, ResultStore, client_key
from services.api.settings import Settings
from services.api.startup import model_problems

ROOT = Path(__file__).resolve().parents[2]
FRONTEND = "https://trustshield-demo.vercel.app"
PNG = (ROOT / "demo" / "fixtures" / "legit_screenshot.png").read_bytes()


def _settings(**overrides) -> Settings:
    base = dict(env="production", cors_origins=(FRONTEND,), store_policy="sandbox_only",
                rate_light_per_min=100000, rate_heavy_per_min=100000)
    base.update(overrides)
    return Settings(**base)


def _client(**overrides) -> TestClient:
    return TestClient(api.create_app(_settings(**overrides)))


# ---- headers, request id, CORS -------------------------------------------------------------------------------------

def test_security_headers_and_request_id_on_every_response():
    client = _client()
    for response in (client.get("/healthz"), client.get("/api/v1/health"), client.get("/nope")):
        assert response.headers["X-Content-Type-Options"] == "nosniff"
        assert response.headers["X-Frame-Options"] == "DENY"
        assert response.headers["Cache-Control"] == "no-store"
        assert "max-age" in response.headers["Strict-Transport-Security"]
        assert len(response.headers["X-Request-ID"]) >= 8


def test_request_id_is_echoed_only_when_well_formed():
    client = _client()
    assert client.get("/healthz", headers={"X-Request-ID": "abc-12345678"}).headers["X-Request-ID"] == "abc-12345678"
    weird = client.get("/healthz", headers={"X-Request-ID": "a b\tc<script>"}).headers["X-Request-ID"]
    assert "<" not in weird and weird != "a b\tc<script>"


def test_cors_allows_only_the_configured_frontend_origin():
    client = _client()
    ok = client.options("/api/v1/analyze/text", headers={"Origin": FRONTEND, "Access-Control-Request-Method": "POST"})
    assert ok.headers.get("access-control-allow-origin") == FRONTEND
    bad = client.options("/api/v1/analyze/text", headers={"Origin": "https://evil.example", "Access-Control-Request-Method": "POST"})
    assert "access-control-allow-origin" not in bad.headers
    simple = client.get("/healthz", headers={"Origin": "http://localhost:3000"})
    assert "access-control-allow-origin" not in simple.headers


def test_docs_and_openapi_are_disabled_in_production():
    client = _client()
    assert client.get("/docs").status_code == 404 and client.get("/openapi.json").status_code == 404
    dev = TestClient(api.create_app(Settings(env="development")))
    assert dev.get("/openapi.json").status_code == 200
    test_env = TestClient(api.create_app(Settings(env="test")))
    assert test_env.get("/docs").status_code == 404 and test_env.get("/openapi.json").status_code == 404


# ---- errors never leak ---------------------------------------------------------------------------------------------

def test_unhandled_exception_returns_generic_500_and_logs_no_content(monkeypatch, caplog):
    def boom(text):
        raise RuntimeError("SECRET-USER-TEXT-4242 OTP 991823")

    monkeypatch.setattr(api, "analyze_text", boom)
    client = TestClient(api.create_app(_settings()), raise_server_exceptions=False)
    with caplog.at_level(logging.INFO):
        response = client.post("/api/v1/analyze/text", json={"text": "SECRET-USER-TEXT-4242 OTP 991823"})
    assert response.status_code == 500
    body = response.json()
    assert body["detail"] == "Internal error" and body["request_id"]
    assert "SECRET" not in response.text and "Traceback" not in response.text and "RuntimeError" not in response.text
    assert "SECRET-USER-TEXT-4242" not in caplog.text and "991823" not in caplog.text
    assert "unhandled_error" in caplog.text and "RuntimeError" in caplog.text  # the class name only


def test_validation_errors_do_not_echo_submitted_input():
    response = _client().post("/api/v1/analyze/text", json={"text": ["sensitive-otp-778899"]})
    assert response.status_code == 422
    assert "sensitive-otp-778899" not in response.text and "input" not in json.dumps(response.json())


def test_access_log_contains_no_body_or_query(caplog):
    client = _client()
    with caplog.at_level(logging.INFO):
        client.post("/api/v1/analyze/text?token=QUERYSECRET", json={"text": "Your OTP is 445566, do not share"})
    ours = " ".join(r.getMessage() for r in caplog.records if r.name.startswith("trustshield"))  # httpx logs its own URLs
    assert "445566" not in ours and "QUERYSECRET" not in ours
    assert '"event": "request"' in ours


# ---- rate limits, bounded concurrency, timeouts, body size -----------------------------------------------------------

def test_light_and_heavy_rate_limits_return_429_with_retry_after():
    client = _client(rate_light_per_min=3, rate_heavy_per_min=2)
    codes = [client.post("/api/v1/analyze/text", json={"text": f"hello there {i}"}).status_code for i in range(6)]
    assert codes[:1] == [200] and 429 in codes
    limited = next(r for r in [client.post("/api/v1/analyze/text", json={"text": "again please"}) for _ in range(3)] if r.status_code == 429)
    assert int(limited.headers["Retry-After"]) >= 1
    heavy = [client.post("/api/v1/analyze/qr", files={"file": ("q.png", PNG, "image/png")}).status_code for _ in range(5)]
    assert 429 in heavy


def test_rate_limiter_is_memory_bounded_and_refills(monkeypatch):
    limiter = RateLimiter(60, burst=2, max_keys=50)
    assert limiter.allow("a")[0] and limiter.allow("a")[0] and not limiter.allow("a")[0]
    for i in range(500):
        limiter.allow(f"k{i}")
    assert len(limiter._buckets) <= 50


def test_client_identity_is_spoof_resistant():
    assert client_key(("10.0.0.9", 1), "1.2.3.4, 5.6.7.8", 0) == "10.0.0.9"  # header ignored unless a proxy is trusted
    assert client_key(("10.0.0.9", 1), "6.6.6.6, 1.2.3.4", 1) == "1.2.3.4"  # rightmost = the trusted proxy's view
    assert client_key(("10.0.0.9", 1), "1.2.3.4", 2) == "10.0.0.9"  # fewer entries than trusted hops: do not trust


def test_body_limit_rejects_declared_and_streamed_oversize_before_parsing():
    client = _client(max_body_bytes=200_000)
    declared = client.post("/api/v1/analyze/image", files={"file": ("big.png", b"x" * 300_000, "image/png")})
    assert declared.status_code == 413
    def chunks():
        for _ in range(40):
            yield b"y" * 10_000
    streamed = client.post("/api/v1/analyze/image", content=chunks(), headers={"Content-Type": "multipart/form-data; boundary=x"})
    assert streamed.status_code == 413
    ok = client.post("/api/v1/analyze/image", files={"file": ("ok.png", PNG, "image/png")})
    assert ok.status_code == 200


def test_upload_size_and_type_limits_fail_safely():
    client = _client()
    assert client.post("/api/v1/analyze/image", files={"file": ("x.png", b"not an image", "image/png")}).status_code in {400, 415, 422}
    exe = client.post("/api/v1/analyze/qr", files={"file": ("a.exe", b"MZ\x90\x00" + b"0" * 100, "application/octet-stream")})
    assert exe.status_code in {400, 415, 422} and "Traceback" not in exe.text


def test_heavy_work_is_bounded_and_times_out(monkeypatch):
    real = api.analyze_qr

    def slow(data, mime, name):
        time.sleep(1.5)
        return real(PNG, "image/png", "q.png")

    monkeypatch.setattr(api, "analyze_qr", slow)
    client = _client(heavy_concurrency=1, heavy_queue_timeout_s=0.3, request_timeout_s=5.0)
    codes = []
    def call():
        codes.append(client.post("/api/v1/analyze/qr", files={"file": ("q.png", PNG, "image/png")}).status_code)
    threads = [threading.Thread(target=call) for _ in range(2)]
    [t.start() for t in threads]; [t.join() for t in threads]
    assert sorted(codes) == [200, 503]
    timed = _client(request_timeout_s=0.2)
    assert timed.post("/api/v1/analyze/qr", files={"file": ("q.png", PNG, "image/png")}).status_code == 504


# ---- stateless bounded result store ---------------------------------------------------------------------------------

def test_production_store_retains_no_ordinary_results_but_dev_does():
    prod = _client()
    response = prod.post("/api/v1/analyze/text", json={"text": "Your parcel is delayed, pay 49 rupees at http://x.example"})
    assert response.status_code == 200 and response.headers["X-Analysis-ID"]
    assert prod.get(f"/api/v1/analysis/{response.headers['X-Analysis-ID']}").status_code == 404
    dev = TestClient(api.create_app(Settings(env="development")))
    r = dev.post("/api/v1/analyze/text", json={"text": "Your parcel is delayed, pay 49 rupees at http://x.example"})
    assert dev.get(f"/api/v1/analysis/{r.headers['X-Analysis-ID']}").status_code == 200


def test_result_store_is_bounded_and_expires():
    class R:
        def __init__(self, i): self.analysis_id = f"id{i}"
    store = ResultStore(Settings(store_max=10, store_ttl_s=0.2))
    for i in range(50):
        store.put(R(i))
    assert len(store) <= 10 and store.get("id0") is None and store.get("id49") is not None
    time.sleep(0.3)
    assert store.get("id49") is None and len(store) == 0
    sandbox_only = ResultStore(Settings(store_policy="sandbox_only"))
    sandbox_only.put(R(1), retain=False)
    assert len(sandbox_only) == 0
    sandbox_only.put(R(2), retain=True)
    assert len(sandbox_only) == 1


# ---- readiness and startup checks ------------------------------------------------------------------------------------

def test_liveness_and_readiness():
    client = _client()
    assert client.get("/healthz").json() == {"status": "alive"}
    ready = client.get("/readyz")
    assert ready.status_code == 200 and ready.json()["ready"] is True


def test_readiness_fails_when_ml_is_unavailable(monkeypatch):
    from services.analysis import pipeline
    from services.analysis.ml.classifier import TextClassifier
    missing = TextClassifier("does-not-exist", project_root=ROOT / "tests")
    monkeypatch.setattr(pipeline, "_text_classifier", lambda: missing)
    response = _client().get("/readyz")
    assert response.status_code == 503 and response.json() == {"ready": False, "reasons": ["ml_unavailable"]}


@pytest.mark.parametrize("overrides,fragment", [
    ({"cors_origins": ("*",)}, "wildcards"),
    ({"cors_origins": ("http://app.example",)}, "https"),
    ({"cors_origins": ("https://localhost",)}, "localhost"),
    ({"store_policy": "all"}, "sandbox_only"),
    ({"env": "prod"}, "TRUSTSHIELD_ENV"),
])
def test_bad_production_settings_are_reported(overrides, fragment):
    assert any(fragment in problem for problem in _settings(**overrides).problems())


@pytest.mark.parametrize("name,value,fragment", [
    ("TRUSTSHIELD_ML", "off", "TRUSTSHIELD_ML=off"),
    ("TRUSTSHIELD_ML_MODEL", "trustshield_text_v1", "TRUSTSHIELD_ML_MODEL"),
    ("TRUSTSHIELD_OCR_PROVIDER", "mock", "mock"),
    ("ENABLE_EXTERNAL_REPUTATION", "true", "REPUTATION"),
])
def test_dangerous_environment_flags_block_production(monkeypatch, name, value, fragment):
    monkeypatch.setenv(name, value)
    assert any(fragment in problem for problem in _settings().problems())


def test_production_app_refuses_to_start_on_bad_config():
    app = api.create_app(_settings(cors_origins=("*",)))
    with pytest.raises(RuntimeError, match="startup checks failed"):
        with TestClient(app):
            pass


def test_model_identity_passes_for_the_promoted_release():
    problems = model_problems()
    if any("MISSING" in p for p in problems):
        pytest.skip("model artifact not present in this checkout")
    assert problems == []


def test_model_absent_or_tampered_is_a_loud_failure(tmp_path):
    (tmp_path / "models" / "release").mkdir(parents=True)
    (tmp_path / "models" / "trained").mkdir(parents=True)
    (tmp_path / "models" / "metadata").mkdir(parents=True)
    for name in ("release/v3_release_manifest.json",):
        (tmp_path / "models" / name).write_bytes((ROOT / "models" / name).read_bytes())
    assert any("MODEL ARTIFACT MISSING" in p for p in model_problems(tmp_path))
    (tmp_path / "models" / "trained" / "trustshield_text_v3.joblib").write_bytes(b"tampered")
    problems = model_problems(tmp_path)
    assert any("differs from the promoted release" in p for p in problems)
    assert any("failed to load" in p for p in problems)


def test_same_origin_deployment_needs_no_cors_origins_and_allows_none():
    assert _settings(cors_origins=()).problems() == []
    client = _client(cors_origins=())
    response = client.get("/api/v1/healthz", headers={"Origin": "https://anything.example"})
    assert response.status_code == 200 and "access-control-allow-origin" not in response.headers


def test_probe_paths_exist_under_api_prefix():
    client = _client(cors_origins=())
    assert client.get("/api/v1/healthz").json() == {"status": "alive"}
    assert client.get("/api/v1/readyz").json()["ready"] is True


def test_rollback_to_v1_needs_explicit_confirmation_and_is_verified_against_the_manifest(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_ML_MODEL", "trustshield_text_v1")
    assert any("TRUSTSHIELD_ROLLBACK_CONFIRM" in p for p in _settings().problems())
    monkeypatch.setenv("TRUSTSHIELD_ROLLBACK_CONFIRM", "v1")
    assert _settings().problems() == []
    problems = model_problems()
    if any("MISSING" in p for p in problems):
        pytest.skip("model artifact not present in this checkout")
    assert problems == []
