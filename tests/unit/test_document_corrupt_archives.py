"""Regression tests for CRC-corrupt DOCX/XLSX archives (synthetic, in-memory)."""

import io
import zipfile

import pytest

from services.analysis.extractors.docx_text import extract_docx
from services.analysis.extractors.xlsx_text import extract_xlsx
from services.analysis.extractors.document_types import DocumentRejected


def _make_zip(entries: dict[str, str], *, compression: int = zipfile.ZIP_STORED) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", compression=compression) as zf:
        for name, content in entries.items():
            zf.writestr(name, content)
    return buf.getvalue()


def _corrupt_central_crc(data: bytes, target: str) -> bytes:
    """Flip a bit in the central-directory CRC-32 field for ``target``."""
    sig = b"PK\x01\x02"
    pos = 0
    while True:
        idx = data.find(sig, pos)
        if idx < 0:
            raise AssertionError(f"central directory entry not found: {target}")
        name_len = int.from_bytes(data[idx + 28 : idx + 30], "little")
        name = data[idx + 46 : idx + 46 + name_len].decode("utf-8", "replace")
        if name == target:
            out = bytearray(data)
            out[idx + 16] ^= 0xFF
            return bytes(out)
        pos = idx + 4


def _corrupt_entry_payload(data: bytes, target: str) -> bytes:
    """Corrupt one byte of entry data while leaving its central CRC intact."""
    # locate the entry in the central directory to find its local header offset
    sig = b"PK\x01\x02"
    pos = 0
    while True:
        idx = data.find(sig, pos)
        if idx < 0:
            raise AssertionError(f"central directory entry not found: {target}")
        name_len = int.from_bytes(data[idx + 28 : idx + 30], "little")
        name = data[idx + 46 : idx + 46 + name_len].decode("utf-8", "replace")
        if name == target:
            local_offset = int.from_bytes(data[idx + 42 : idx + 46], "little")
            assert data[local_offset : local_offset + 4] == b"PK\x03\x04"
            name_len = int.from_bytes(data[local_offset + 26 : local_offset + 28], "little")
            extra_len = int.from_bytes(data[local_offset + 28 : local_offset + 30], "little")
            payload_offset = local_offset + 30 + name_len + extra_len
            out = bytearray(data)
            out[payload_offset] ^= 0x01
            return bytes(out)
        pos = idx + 4


DOCX_ENTRIES = {
    "[Content_Types].xml": (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
        "</Types>"
    ),
    "word/_rels/document.xml.rels": (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>'
    ),
    "word/document.xml": (
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        "<w:body><w:p><w:r><w:t>TrustShield synthetic</w:t></w:r></w:p></w:body>"
        "</w:document>"
    ),
}

XLSX_ENTRIES = {
    "[Content_Types].xml": (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
        '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
        "</Types>"
    ),
    "xl/workbook.xml": (
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        '<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"/></sheets>'
        "</workbook>"
    ),
    "xl/worksheets/sheet1.xml": (
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        "<sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Hello</t></is></c></row></sheetData>"
        "</worksheet>"
    ),
}


def test_docx_crc_corrupt_central_directory_rejected():
    data = _corrupt_central_crc(_make_zip(DOCX_ENTRIES), "word/document.xml")
    with pytest.raises(DocumentRejected):
        extract_docx(data)


def test_docx_crc_corrupt_entry_payload_rejected():
    data = _corrupt_entry_payload(_make_zip(DOCX_ENTRIES, compression=zipfile.ZIP_STORED), "word/document.xml")
    with pytest.raises(DocumentRejected):
        extract_docx(data)


def test_xlsx_crc_corrupt_central_directory_rejected():
    data = _corrupt_central_crc(_make_zip(XLSX_ENTRIES), "xl/worksheets/sheet1.xml")
    with pytest.raises(DocumentRejected):
        extract_xlsx(data)


def test_xlsx_crc_corrupt_entry_payload_rejected():
    data = _corrupt_entry_payload(_make_zip(XLSX_ENTRIES, compression=zipfile.ZIP_STORED), "xl/worksheets/sheet1.xml")
    with pytest.raises(DocumentRejected):
        extract_xlsx(data)
