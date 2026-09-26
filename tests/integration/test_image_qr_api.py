from io import BytesIO

import pytest
from fastapi.testclient import TestClient
from PIL import Image, PngImagePlugin
from starlette.datastructures import UploadFile

from services.api.main import app


client = TestClient(app)


def make_png(text="Your account will be blocked today. Share OTP now"):
    image = Image.new("RGB", (400, 120), "white")
    out = BytesIO()
    info = PngImagePlugin.PngInfo()
    info.add_text("trustshield_demo_text", text)
    image.save(out, format="PNG", pnginfo=info)
    return out.getvalue()


def test_image_multipart_roundtrip(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_OCR_PROVIDER", "mock")
    response = client.post(
        "/api/v1/analyze/image",
        files={"file": ("upload.jpg", make_png(), "image/png")},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["input_type"] == "image"
    assert body["status"] == "completed"
    assert body["risk"]["label"] != "unable_to_verify"
    assert body["modules"]["ocr"]["status"] == "completed"
    assert body["modules"]["ocr"]["data"]["text"] == "Your account will be blocked today. Share OTP now"
    assert body["evidence"]


def test_image_api_truncates_oversized_mock_ocr(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_OCR_PROVIDER", "mock")
    response = client.post(
        "/api/v1/analyze/image",
        files={"file": ("large.png", make_png("A" * 20_005), "image/png")},
    )
    assert response.status_code == 200
    assert any("OCR text was truncated" in item for item in response.json()["limitations"])


def test_upload_files_are_closed_in_finally(monkeypatch):
    closed = []
    original_close = UploadFile.close

    async def close(upload):
        closed.append(True)
        await original_close(upload)

    monkeypatch.setattr(UploadFile, "close", close)
    assert client.post("/api/v1/analyze/image", files={"file": ("x.png", make_png(), "image/png")}).status_code == 200
    assert client.post("/api/v1/analyze/qr", files={"file": ("x.png", make_png(), "image/png")}).status_code == 200
    assert len(closed) >= 2


def test_health_reports_capabilities(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_OCR_PROVIDER", "mock")
    body = client.get("/api/v1/health").json()
    assert body["modules"]["ocr"]["provider"] == "mock"
    assert body["modules"]["ocr"]["available"] is True
    assert body["modules"]["qr"] is True


def test_image_api_error_codes(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "10")
    assert client.post("/api/v1/analyze/image", files={"file": ("x.png", b"12345678901", "image/png")}).status_code == 413
    monkeypatch.delenv("MAX_UPLOAD_SIZE")
    assert client.post("/api/v1/analyze/image", files={"file": ("x.gif", b"not-gif", "image/gif")}).status_code == 415
    assert client.post("/api/v1/analyze/image", files={"file": ("x.png", b"not-image", "image/png")}).status_code == 422


def test_qr_api_returns_unable_to_verify_for_non_qr_image():
    response = client.post("/api/v1/analyze/qr", files={"file": ("x.png", make_png(), "image/png")})
    assert response.status_code == 200
    assert response.json()["input_type"] == "qr"
    assert response.json()["modules"]["qr"]["status"] == "failed"
