"""API integration tests for document uploads (W3).

All payloads are synthetic and local. Covers clean, corrupt, oversized and
unsupported cases through both document endpoints.
"""
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from services.api.main import app

client = TestClient(app)
FIXTURES = Path("tests/fixtures/uploads")

_DOC_MIME = {
    "pdf": "application/pdf",
    "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
}

_CLEAN = {"pdf": "clean.pdf", "docx": "clean.docx"}
_CORRUPT = {"pdf": "corrupt.pdf", "docx": "corrupt.docx"}


@pytest.mark.parametrize("fmt", ["pdf", "docx"])
def test_document_analyze_clean(fmt):
    name = _CLEAN[fmt]
    data = (FIXTURES / name).read_bytes()
    response = client.post(
        "/api/v1/analyze/document",
        files={"file": (name, data, _DOC_MIME[fmt])},
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["input_type"] == "document"
    assert body["status"] == "completed"
    assert 0 <= body["risk"]["score"] <= 100


@pytest.mark.parametrize("fmt", ["pdf", "docx"])
def test_chat_upload_clean(fmt):
    name = _CLEAN[fmt]
    data = (FIXTURES / name).read_bytes()
    response = client.post(
        "/api/v1/chat/upload",
        files={"file": (name, data, _DOC_MIME[fmt])},
        data={"message": "please check this document"},
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["kind"] == "analysis"
    assert body["analysis"] is not None
    assert "document" in body["analysis"]["input_types"]


@pytest.mark.parametrize("fmt", ["pdf", "docx"])
def test_document_analyze_corrupt(fmt):
    name = _CORRUPT[fmt]
    data = (FIXTURES / name).read_bytes()
    response = client.post(
        "/api/v1/analyze/document",
        files={"file": (name, data, _DOC_MIME[fmt])},
    )
    assert response.status_code == 422, response.text
    assert response.json().get("detail")


@pytest.mark.parametrize("fmt", ["pdf", "docx"])
def test_chat_upload_corrupt(fmt):
    name = _CORRUPT[fmt]
    data = (FIXTURES / name).read_bytes()
    response = client.post(
        "/api/v1/chat/upload",
        files={"file": (name, data, _DOC_MIME[fmt])},
        data={"message": "please check this document"},
    )
    assert response.status_code == 422, response.text
    assert response.json().get("detail")


@pytest.mark.parametrize("fmt", ["pdf", "docx"])
def test_document_analyze_oversized(monkeypatch, fmt):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "10")
    name = _CLEAN[fmt]
    data = b"A" * 11
    response = client.post(
        "/api/v1/analyze/document",
        files={"file": (name, data, _DOC_MIME[fmt])},
    )
    assert response.status_code == 413, response.text
    assert "maximum upload size" in str(response.json().get("detail", "")).lower()


@pytest.mark.parametrize("fmt", ["pdf", "docx"])
def test_chat_upload_oversized(monkeypatch, fmt):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "10")
    name = _CLEAN[fmt]
    data = b"A" * 11
    response = client.post(
        "/api/v1/chat/upload",
        files={"file": (name, data, _DOC_MIME[fmt])},
        data={"message": "please check this document"},
    )
    assert response.status_code == 413, response.text
    assert "maximum upload size" in str(response.json().get("detail", "")).lower()


def test_document_analyze_unsupported():
    data = b"\x80\x81\x82\x83"
    response = client.post(
        "/api/v1/analyze/document",
        files={"file": ("unsupported.bin", data, "application/octet-stream")},
    )
    assert response.status_code == 415, response.text
    assert "not supported" in str(response.json().get("detail", "")).lower()


def test_chat_upload_unsupported():
    data = b"\x80\x81\x82\x83"
    response = client.post(
        "/api/v1/chat/upload",
        files={"file": ("unsupported.bin", data, "application/octet-stream")},
        data={"message": "please check this document"},
    )
    assert response.status_code == 422, response.text
    assert response.json().get("detail")
