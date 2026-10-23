"""Unit tests for PDF extraction limits and hostile inputs."""

import zlib

import pytest

from services.analysis.extractors.document_types import (
    MAX_DOCUMENT_BYTES,
    DocumentRejected,
    MAX_PAGES,
)
from services.analysis.extractors.pdf_text import extract_pdf, max_document_bytes


def _pdf_with_flate(payload: bytes) -> bytes:
    compressed = zlib.compress(payload)
    header = (
        b"%PDF-1.4\n1 0 obj\n<< /Type /Page /Filter /FlateDecode /Length "
        + str(len(compressed)).encode()
        + b" >>\nstream\n"
    )
    return header + compressed + b"\nendstream\nendobj\n%%EOF\n"


def test_pdf_max_document_bytes_honors_smaller_env(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "12345")
    assert max_document_bytes() == 12345


def test_pdf_max_document_bytes_clamps_larger_env(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", str(100 * 1024 * 1024))
    assert max_document_bytes() == MAX_DOCUMENT_BYTES


def test_pdf_decompression_bomb_rejected():
    # Synthetic: highly compressible payload that expands beyond the cap.
    payload = b"\x00" * (51 * 1024 * 1024)
    pdf = _pdf_with_flate(payload)
    with pytest.raises(DocumentRejected) as exc:
        extract_pdf(pdf)
    assert exc.value.code == "decompression_bomb"


def test_pdf_empty_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_pdf(b"")
    assert exc.value.code == "empty_document"


def test_pdf_oversized_upload_rejected(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "10")
    with pytest.raises(DocumentRejected) as exc:
        extract_pdf(b"%PDF-1.4\n" + b"a" * 100)
    assert exc.value.code == "upload_too_large"


def test_pdf_encrypted_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_pdf(b"%PDF-1.4\n/Encrypt\n")
    assert exc.value.code == "encrypted_pdf"


def test_pdf_too_many_pages_rejected():
    pdf = b"%PDF-1.4\n" + (b"/Type /Page\n" * (MAX_PAGES + 1))
    with pytest.raises(DocumentRejected) as exc:
        extract_pdf(pdf)
    assert exc.value.code == "too_many_pages"


def test_pdf_invalid_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_pdf(b"not a pdf")
    assert exc.value.code == "invalid_pdf"
