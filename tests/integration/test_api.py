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


def test_oversized_rejected():
    assert client.post("/api/v1/analyze/text", json={"text": "a" * 20_001}).status_code == 422


def test_unknown_analysis_404():
    assert client.get("/api/v1/analysis/nope").status_code == 404
