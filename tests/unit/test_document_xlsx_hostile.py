"""Hostile XLSX input tests for the bounded XLSX text extractor."""

import io
import zipfile

import pytest

from services.analysis.extractors.document_types import (
    MAX_DOCUMENT_BYTES,
    DocumentRejected,
)
from services.analysis.extractors.xlsx_text import extract_xlsx, max_document_bytes

FIX = __import__("pathlib").Path("tests/fixtures/uploads")

SS_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"


def _archive(entries: dict[str, str | bytes]) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, content in entries.items():
            archive.writestr(name, content)
    return buffer.getvalue()


def _minimal_workbook() -> str:
    return (
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        '<sheets><sheet name="Sheet1" sheetId="1"/></sheets></workbook>'
    )


def _minimal_sheet() -> str:
    return (
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        "<sheetData/></worksheet>"
    )


def test_xlsx_macro_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_xlsx((FIX / "macro_enabled.xlsx").read_bytes())
    assert exc.value.code == "macro_enabled"


def test_xlsx_macro_synthetic_rejected():
    data = _archive(
        {
            "xl/workbook.xml": _minimal_workbook(),
            "xl/worksheets/sheet1.xml": _minimal_sheet(),
            "xl/vbaProject.bin": b"fake vba",
        }
    )
    with pytest.raises(DocumentRejected) as exc:
        extract_xlsx(data)
    assert exc.value.code == "macro_enabled"


def test_xlsx_encrypted_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_xlsx((FIX / "password_protected.xlsx").read_bytes())
    assert exc.value.code == "encrypted_xlsx"


def test_xlsx_encrypted_ole_magic_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_xlsx(b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1" + b"\x00" * 16)
    assert exc.value.code == "encrypted_xlsx"


def test_xlsx_zipbomb_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_xlsx((FIX / "zipbomb.xlsx").read_bytes())
    assert exc.value.code == "zip_bomb"


def test_xlsx_path_traversal_rejected():
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("../evil.txt", "x")
        z.writestr(
            "xl/worksheets/sheet1.xml",
            "<worksheet xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><sheetData/></worksheet>",
        )
    with pytest.raises(DocumentRejected) as exc:
        extract_xlsx(buf.getvalue())
    assert exc.value.code == "path_traversal"


def test_xlsx_malformed_xml_rejected():
    data = _archive(
        {
            "xl/workbook.xml": _minimal_workbook(),
            "xl/worksheets/sheet1.xml": "<worksheet><sheetData>",
        }
    )
    with pytest.raises(DocumentRejected) as exc:
        extract_xlsx(data)
    assert exc.value.code == "invalid_xlsx"


def test_xlsx_malformed_shared_strings_rejected():
    data = _archive(
        {
            "xl/workbook.xml": _minimal_workbook(),
            "xl/worksheets/sheet1.xml": _minimal_sheet(),
            "xl/sharedStrings.xml": "<sst><si><t>unterminated",
        }
    )
    with pytest.raises(DocumentRejected) as exc:
        extract_xlsx(data)
    assert exc.value.code == "invalid_xlsx"


def test_xlsx_no_worksheets_rejected():
    data = _archive({"xl/workbook.xml": _minimal_workbook()})
    with pytest.raises(DocumentRejected) as exc:
        extract_xlsx(data)
    assert exc.value.code == "invalid_xlsx"


def test_xlsx_missing_workbook_rejected():
    data = _archive({"xl/worksheets/sheet1.xml": _minimal_sheet()})
    with pytest.raises(DocumentRejected) as exc:
        extract_xlsx(data)
    assert exc.value.code == "invalid_xlsx"


def test_xlsx_too_many_sheets_rejected():
    entries = {"xl/workbook.xml": _minimal_workbook()}
    for i in range(1, 22):
        entries[f"xl/worksheets/sheet{i}.xml"] = _minimal_sheet()
    data = _archive(entries)
    with pytest.raises(DocumentRejected) as exc:
        extract_xlsx(data)
    assert exc.value.code == "too_many_sheets"


def test_xlsx_too_many_rows_rejected():
    rows = "".join(
        f'<row r="{i}"><c t="inlineStr"><is><t>x</t></is></c></row>'
        for i in range(1, 10002)
    )
    sheet = (
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        f"<sheetData>{rows}</sheetData></worksheet>"
    )
    data = _archive({"xl/workbook.xml": _minimal_workbook(), "xl/worksheets/sheet1.xml": sheet})
    with pytest.raises(DocumentRejected) as exc:
        extract_xlsx(data)
    assert exc.value.code == "too_many_rows"


def test_xlsx_too_many_cells_rejected():
    rows = []
    for r in range(1, 393):
        cells = "".join("<c><v>1</v></c>" for _ in range(256))
        rows.append(f'<row r="{r}">{cells}</row>')
    sheet = (
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        f"<sheetData>{''.join(rows)}</sheetData></worksheet>"
    )
    data = _archive({"xl/workbook.xml": _minimal_workbook(), "xl/worksheets/sheet1.xml": sheet})
    with pytest.raises(DocumentRejected) as exc:
        extract_xlsx(data)
    assert exc.value.code == "too_many_cells"


def test_xlsx_oversized_rejected(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "10")
    with pytest.raises(DocumentRejected) as exc:
        extract_xlsx(b"PK" + b"a" * 100)
    assert exc.value.code == "upload_too_large"


def test_xlsx_empty_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_xlsx(b"")
    assert exc.value.code == "empty_document"


def test_xlsx_mime_mismatch_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_xlsx(b"PK", declared_mime="application/pdf")
    assert exc.value.code == "mime_mismatch"


def test_xlsx_clean_fixture_accepted():
    r = extract_xlsx((FIX / "clean.xlsx").read_bytes())
    assert r.format == "xlsx"
    assert "Hello" in r.text


def test_max_document_bytes_clamps_large_env(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", str(100 * 1024 * 1024))
    assert max_document_bytes() == MAX_DOCUMENT_BYTES


def test_max_document_bytes_honors_smaller_env(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "12345")
    assert max_document_bytes() == 12345


def test_max_document_bytes_invalid_env(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "not-an-int")
    assert max_document_bytes() == MAX_DOCUMENT_BYTES
