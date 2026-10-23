import os
from pathlib import Path

import pytest

from services.analysis.extractors.document_text import extract_txt, extract_csv
from services.analysis.extractors.document_types import DocumentRejected

FIX = Path("tests/fixtures/uploads")


def test_txt_accepted():
    r = extract_txt((FIX / "clean.txt").read_bytes())
    assert r.format == "txt"
    assert "SBI account" in r.text
    assert r.metadata["size_bytes"] == 85


def test_txt_empty_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_txt(b"")
    assert exc.value.code == "empty_document"


def test_txt_invalid_utf8_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_txt((FIX / "corrupt.txt").read_bytes())
    assert exc.value.code == "undecodable_text"


def test_txt_oversized_rejected(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "10")
    with pytest.raises(DocumentRejected) as exc:
        extract_txt(b"a" * 100)
    assert exc.value.code == "upload_too_large"


def test_txt_mime_mismatch_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_txt(b"hello", declared_mime="application/pdf")
    assert exc.value.code == "mime_mismatch"


def test_txt_truncation():
    data = ("a " * 20000).encode()
    r = extract_txt(data)
    assert r.metadata["truncated"] is True
    assert len(r.text) <= 20000


def test_csv_accepted():
    r = extract_csv((FIX / "clean.csv").read_bytes())
    assert r.format == "csv"
    assert r.metadata["row_count"] == 3
    assert r.metadata["column_count"] == 3
    assert "Alice | 100" in r.text


def test_csv_delimiter_detection():
    data = b"name\tvalue\nfoo\tbar\n"
    r = extract_csv(data)
    assert r.metadata["delimiter"] == "\t"


def test_csv_too_many_columns():
    row = ",".join(["x"] * 300)
    with pytest.raises(DocumentRejected) as exc:
        extract_csv((row + "\n").encode())
    assert exc.value.code == "too_many_columns"


def test_csv_cell_too_large():
    data = ("a" * 11000 + ",b\n").encode()
    with pytest.raises(DocumentRejected) as exc:
        extract_csv(data)
    assert exc.value.code == "cell_too_large"


def test_csv_empty_rejected():
    with pytest.raises(DocumentRejected):
        extract_csv(b"")


def test_csv_mime_mismatch_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_csv(b"a,b\n1,2\n", declared_mime="application/pdf")
    assert exc.value.code == "mime_mismatch"
