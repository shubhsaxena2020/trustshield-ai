"""Safety regression tests for CSV extraction."""
from pathlib import Path

import pytest

from services.analysis.extractors.document_text import extract_csv, max_document_bytes
from services.analysis.extractors.document_types import (
    MAX_DOCUMENT_BYTES,
    DocumentRejected,
)

FIX = Path("tests/fixtures/uploads")


def test_csv_oversized_field_rejected():
    # A single field larger than csv's default field-size limit.
    field = "a" * 200000
    with pytest.raises(DocumentRejected) as exc:
        extract_csv((field + "\n").encode())
    assert exc.value.code == "invalid_csv"


def test_csv_normal_extraction_preserved():
    r = extract_csv((FIX / "clean.csv").read_bytes())
    assert r.format == "csv"
    assert r.metadata["row_count"] == 3
    assert r.metadata["column_count"] == 3
    assert "Alice | 100" in r.text


def test_max_document_bytes_clamps_high_env(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", str(100 * 1024 * 1024))
    assert max_document_bytes() == MAX_DOCUMENT_BYTES


def test_max_document_bytes_honors_lower_env(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "1024")
    assert max_document_bytes() == 1024


def test_csv_oversized_upload_rejected(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "10")
    with pytest.raises(DocumentRejected) as exc:
        extract_csv(b"a,b\n1,2\n" * 5)
    assert exc.value.code == "upload_too_large"