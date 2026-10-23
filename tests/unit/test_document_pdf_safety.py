import zlib

import pytest

from services.analysis.extractors.document_types import (
    MAX_DOCUMENT_BYTES,
    DocumentRejected,
)
from services.analysis.extractors.pdf_text import extract_pdf, max_document_bytes

FIX = __import__("pathlib").Path("tests/fixtures/uploads")


def _pdf_with_flate(payload: bytes) -> bytes:
    compressed = zlib.compress(payload)
    header = b"%PDF-1.4\n1 0 obj\n<< /Type /Page /Filter /FlateDecode /Length " + str(len(compressed)).encode() + b" >>\nstream\n"
    return header + compressed + b"\nendstream\nendobj\n%%EOF\n"


def test_pdf_clean_fixture_accepted():
    r = extract_pdf((FIX / "clean.pdf").read_bytes())
    assert r.format == "pdf"
    assert "blocked" in r.text


def test_pdf_clean_flate_stream_extracts_text():
    content = b"BT (Hello Flate) Tj ET"
    pdf = _pdf_with_flate(content)
    r = extract_pdf(pdf)
    assert r.format == "pdf"
    assert "Hello Flate" in r.text


def test_pdf_empty_rejected():
    with pytest.raises(DocumentRejected):
        extract_pdf(b"")


def test_pdf_invalid_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_pdf(b"not a pdf")
    # header missing -> invalid_pdf
    assert exc.value.code == "invalid_pdf"


def test_pdf_encrypted_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_pdf((FIX / "password_protected.pdf").read_bytes())
    assert exc.value.code == "encrypted_pdf"


def test_pdf_mime_mismatch_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_pdf(b"%PDF-1.4\n", declared_mime="text/plain")
    assert exc.value.code == "mime_mismatch"


def test_pdf_decompression_bomb_rejected():
    # ~60MB of zeros compresses tiny but expands beyond MAX_DECOMPRESSED_BYTES.
    payload = b"\x00" * (60 * 1024 * 1024)
    pdf = _pdf_with_flate(payload)
    with pytest.raises(DocumentRejected) as exc:
        extract_pdf(pdf)
    assert exc.value.code == "decompression_bomb"


def test_pdf_corrupt_flate_stream_rejected():
    # FlateDecode declared but payload is not valid zlib.
    pdf = (
        b"%PDF-1.4\n"
        b"1 0 obj\n<< /Filter /FlateDecode /Length 4 >>\n"
        b"stream\nXXXX\nendstream\nendobj\n%%EOF\n"
    )
    with pytest.raises(DocumentRejected) as exc:
        extract_pdf(pdf)
    assert exc.value.code == "invalid_pdf"


def test_max_document_bytes_clamps_large_env(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", str(100 * 1024 * 1024))
    assert max_document_bytes() == MAX_DOCUMENT_BYTES


def test_max_document_bytes_honors_smaller_env(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "12345")
    assert max_document_bytes() == 12345


def test_max_document_bytes_invalid_env():
    monkeypatch = pytest.MonkeyPatch()
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "not-an-int")
    try:
        assert max_document_bytes() == MAX_DOCUMENT_BYTES
    finally:
        monkeypatch.undo()


def test_pdf_oversized_upload_rejected(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "10")
    with pytest.raises(DocumentRejected) as exc:
        extract_pdf(b"%PDF-1.4\n" + b"a" * 100)
    assert exc.value.code == "upload_too_large"
