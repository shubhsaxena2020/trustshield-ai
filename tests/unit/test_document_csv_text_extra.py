"""Extra unit tests for CSV/text extraction edge cases."""
from pathlib import Path

import pytest

from services.analysis.extractors.document_text import (
    extract_txt,
    extract_csv,
    _detect_delimiter,
    max_document_bytes,
)
from services.analysis.extractors.document_types import (
    DocumentRejected,
    MAX_DOCUMENT_BYTES,
    MAX_EXTRACTED_TEXT_CHARS,
    MAX_ROWS,
    MAX_COLUMNS,
    MAX_CELLS,
    MAX_CELL_CHARS,
)

FIX = Path("tests/fixtures/uploads")


# ---------- delimiter detection ----------

def test_detect_delimiter_comma():
    assert _detect_delimiter("a,b,c\n1,2,3\n") == ","


def test_detect_delimiter_tab():
    assert _detect_delimiter("a\tb\tc\n1\t2\t3\n") == "\t"


def test_detect_delimiter_semicolon():
    assert _detect_delimiter("a;b;c\n1;2;3\n") == ";"


def test_detect_delimiter_pipe():
    assert _detect_delimiter("a|b|c\n1|2|3\n") == "|"


def test_detect_delimiter_empty_defaults_comma():
    assert _detect_delimiter("") == ","


def test_detect_delimiter_no_delimiter_defaults_comma():
    assert _detect_delimiter("just a line\n") == ","


def test_csv_delimiter_metadata_tab():
    r = extract_csv(b"name\tvalue\nfoo\tbar\n")
    assert r.metadata["delimiter"] == "\t"


def test_csv_delimiter_metadata_semicolon():
    r = extract_csv(b"name;value\nfoo;bar\n")
    assert r.metadata["delimiter"] == ";"


def test_csv_delimiter_metadata_pipe():
    r = extract_csv(b"name|value\nfoo|bar\n")
    assert r.metadata["delimiter"] == "|"


# ---------- empty ----------

def test_txt_empty_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_txt(b"")
    assert exc.value.code == "empty_document"


def test_csv_empty_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_csv(b"")
    assert exc.value.code == "empty_document"


# ---------- invalid UTF-8 ----------

def test_txt_invalid_utf8_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_txt(b"\x80\x81\x82")
    assert exc.value.code == "undecodable_text"


def test_csv_invalid_utf8_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_csv(b"\x80\x81\x82")
    assert exc.value.code == "undecodable_text"


# ---------- oversized upload ----------

def test_txt_oversized_rejected(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "10")
    with pytest.raises(DocumentRejected) as exc:
        extract_txt(b"a" * 100)
    assert exc.value.code == "upload_too_large"


def test_csv_oversized_rejected(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "10")
    with pytest.raises(DocumentRejected) as exc:
        extract_csv(b"a,b\n1,2\n" * 5)
    assert exc.value.code == "upload_too_large"


# ---------- MIME mismatch ----------

def test_txt_mime_mismatch_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_txt(b"hello", declared_mime="application/pdf")
    assert exc.value.code == "mime_mismatch"


def test_csv_mime_mismatch_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_csv(b"a,b\n1,2\n", declared_mime="application/pdf")
    assert exc.value.code == "mime_mismatch"


# ---------- truncation ----------

def test_txt_truncation():
    data = ("a " * 20000).encode()
    r = extract_txt(data)
    assert r.metadata["truncated"] is True
    assert len(r.text) <= MAX_EXTRACTED_TEXT_CHARS


def test_csv_truncation():
    # enough rows to exceed the extracted text char cap
    rows = ["x,y\n"] * 7000
    data = "".join(rows).encode()
    r = extract_csv(data)
    assert r.metadata["truncated"] is True
    assert len(r.text) <= MAX_EXTRACTED_TEXT_CHARS


# ---------- bounds ----------

def test_csv_too_many_columns():
    row = ",".join(["x"] * (MAX_COLUMNS + 1))
    with pytest.raises(DocumentRejected) as exc:
        extract_csv((row + "\n").encode())
    assert exc.value.code == "too_many_columns"


def test_csv_too_many_rows():
    data = ("a\n" * (MAX_ROWS + 1)).encode()
    with pytest.raises(DocumentRejected) as exc:
        extract_csv(data)
    assert exc.value.code == "too_many_rows"


def test_csv_too_many_cells():
    # 400 rows * 256 cols = 102400 cells > MAX_CELLS
    row = ",".join(["x"] * 256)
    data = (row + "\n") * 400
    with pytest.raises(DocumentRejected) as exc:
        extract_csv(data.encode())
    assert exc.value.code == "too_many_cells"


def test_csv_cell_too_large():
    data = ("a" * (MAX_CELL_CHARS + 1) + ",b\n").encode()
    with pytest.raises(DocumentRejected) as exc:
        extract_csv(data)
    assert exc.value.code == "cell_too_large"


# ---------- oversized CSV field ----------

def test_csv_oversized_field_rejected():
    field = "a" * 200000
    with pytest.raises(DocumentRejected) as exc:
        extract_csv((field + "\n").encode())
    assert exc.value.code == "invalid_csv"


# ---------- accepted sanity ----------

def test_txt_accepted_fixture():
    r = extract_txt((FIX / "clean.txt").read_bytes())
    assert r.format == "txt"
    assert r.metadata["size_bytes"] == 85


def test_csv_accepted_fixture():
    r = extract_csv((FIX / "clean.csv").read_bytes())
    assert r.format == "csv"
    assert r.metadata["row_count"] == 3
    assert r.metadata["column_count"] == 3


def test_max_document_bytes_clamps():
    assert max_document_bytes() <= MAX_DOCUMENT_BYTES