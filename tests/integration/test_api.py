import pytest
import logging
from fastapi.testclient import TestClient

from services.api.main import app

client = TestClient(app)


def test_health():
    assert client.get("/api/v1/health").json()["status"] == "ok"


def test_text_roundtrip_and_fetch():
    r = client.post("/api/v1/analyze/text", json={"text": "Your KYC expires today. Share OTP now at http://hdfc-kyc.top/verify"})
    assert r.status_code == 200
    body = r.json()
    assert body["risk"]["label"] in {"suspicious", "high_risk"}
    assert body["evidence"] and body["explanation"] and body["recommended_actions"]
    assert client.get(f"/api/v1/analysis/{body['analysis_id']}").json()["analysis_id"] == body["analysis_id"]


def test_url_endpoint():
    r = client.post("/api/v1/analyze/url", json={"url": "http://192.168.0.1/login.exe"})
    assert r.status_code == 200 and r.json()["risk"]["score"] > 25


@pytest.mark.parametrize("path,payload,detail", [
    ("/api/v1/analyze/text", {"text": ""}, "Text cannot be blank. Kripya message likhein."),
    ("/api/v1/analyze/text", {"text": " \t\n "}, "Text cannot be blank. Kripya message likhein."),
    ("/api/v1/analyze/url", {"url": ""}, "URL cannot be blank. Kripya URL enter karein."),
    ("/api/v1/analyze/url", {"url": " \t\n "}, "URL cannot be blank. Kripya URL enter karein."),
])
def test_blank_analysis_input_is_rejected_without_publishing(path, payload, detail):
    response = client.post(path, json=payload)
    assert response.status_code == 422
    assert response.json() == {"detail": detail}
    assert "X-Analysis-ID" not in response.headers


@pytest.mark.parametrize("sample", [
    "Yeh bank se bol raha hoon, OTP abhi bhejo warna account band ho jayega",
    "बैंक से बोल रहा हूँ, खाता बंद होने से पहले OTP अभी भेजें",
])
def test_text_analysis_accepts_hinglish_and_devanagari_without_logging_input(sample, caplog):
    caplog.set_level(logging.INFO)
    response = client.post("/api/v1/analyze/text", json={"text": sample})
    assert response.status_code == 200
    assert response.json()["risk"]["label"]
    assert sample not in "\n".join(record.getMessage() for record in caplog.records)


def test_analysis_limits_reject_oversized_text_and_url():
    text = client.post("/api/v1/analyze/text", json={"text": "x" * 20_001})
    url = client.post("/api/v1/analyze/url", json={"url": "https://example.com/" + "x" * 2040})
    assert text.status_code == 422
    assert url.status_code == 422


def test_oversized_rejected():
    assert client.post("/api/v1/analyze/text", json={"text": "a" * 20_001}).status_code == 422


def test_unknown_analysis_404():
    assert client.get("/api/v1/analysis/nope").status_code == 404
