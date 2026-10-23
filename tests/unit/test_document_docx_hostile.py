"""Hostile-input unit tests for DOCX extraction."""

from __future__ import annotations

import io
import zipfile

import pytest

from services.analysis.extractors.document_types import (
    DocumentRejected,
    MAX_DOCUMENT_BYTES,
)
from services.analysis.extractors.docx_text import extract_docx, max_document_bytes

FIX = __import__("pathlib").Path("tests/fixtures/uploads")


def _minimal_docx(document_xml: str, extra_entries: dict | None = None) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("word/document.xml", document_xml)
        z.writestr(
            "word/_rels/document.xml.rels",
            "<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'/>",
        )
        if extra_entries:
            for name, content in extra_entries.items():
                z.writestr(name, content)
    return buf.getvalue()


def _clean_document_xml(text: str = "TrustShield synthetic") -> str:
    return (
        "<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'>"
        "<w:body><w:p><w:r><w:t>" + text + "</w:t></w:r></w:p></w:body></w:document>"
    )


# --- empty ---


def test_hostile_empty_bytes():
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(b"")
    assert exc.value.code == "empty_document"
    assert exc.value.status_code == 422


# --- malformed zip ---


def test_hostile_malformed_zip_garbage():
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(b"this is not a zip file at all")
    assert exc.value.code == "invalid_docx"


def test_hostile_malformed_zip_truncated():
    data = (FIX / "clean.docx").read_bytes()[:40]
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(data)
    assert exc.value.code == "invalid_docx"


def test_hostile_malformed_zip_bad_crc():
    data = bytearray(_minimal_docx(_clean_document_xml()))
    sig = b"PK\x01\x02"
    idx = bytes(data).find(sig)
    assert idx != -1
    data[idx + 16] ^= 0xFF
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(bytes(data))
    assert exc.value.code == "invalid_docx"


# --- corrupt XML ---


def test_hostile_corrupt_xml_unclosed():
    data = _minimal_docx("<w:document><w:body><w:p>")
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(data)
    assert exc.value.code == "invalid_docx"


def test_hostile_corrupt_xml_xxe():
    xml = (
        '<?xml version="1.0"?>\n'
        '<!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>\n'
        + _clean_document_xml("&xxe;")
    )
    data = _minimal_docx(xml)
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(data)
    assert exc.value.code == "invalid_docx"


def test_hostile_corrupt_xml_missing_document_part():
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("word/_rels/document.xml.rels", "<Relationships/>")
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(buf.getvalue())
    assert exc.value.code == "invalid_docx"


def test_hostile_corrupt_xml_in_header():
    data = _minimal_docx(
        _clean_document_xml(),
        extra_entries={"word/header1.xml": "<w:hdr><w:p>"},
    )
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(data)
    assert exc.value.code == "invalid_docx"


# --- macro ---


def test_hostile_macro_fixture():
    with pytest.raises(DocumentRejected) as exc:
        extract_docx((FIX / "macro_enabled.docx").read_bytes())
    assert exc.value.code == "macro_enabled"
    assert exc.value.status_code == 422


def test_hostile_macro_vba_bin_entry():
    data = _minimal_docx(
        _clean_document_xml(),
        extra_entries={"word/vbaProject.bin": b"\x00" * 16},
    )
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(data)
    assert exc.value.code == "macro_enabled"


def test_hostile_macro_vbaproject_name_case():
    data = _minimal_docx(_clean_document_xml())
    # rebuild with oddly-cased vba entry
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("word/document.xml", _clean_document_xml())
        z.writestr("word/VBAProject.BIN", b"\x00")
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(buf.getvalue())
    assert exc.value.code == "macro_enabled"


# --- encrypted / OLE ---


def test_hostile_encrypted_ole_fixture():
    with pytest.raises(DocumentRejected) as exc:
        extract_docx((FIX / "password_protected.docx").read_bytes())
    assert exc.value.code == "encrypted_docx"
    assert exc.value.status_code == 403


def test_hostile_encrypted_ole_magic_prefix():
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1" + b"\x00" * 16)
    assert exc.value.code == "encrypted_docx"


def test_hostile_encrypted_zip_entry_flag():
    data = bytearray(_minimal_docx(_clean_document_xml()))
    sig = b"PK\x01\x02"
    idx = bytes(data).find(sig)
    assert idx != -1
    data[idx + 8] = 0x01
    data[idx + 9] = 0x00
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(bytes(data))
    assert exc.value.code == "encrypted_docx"


# --- zip bomb ---


def test_hostile_zipbomb_fixture():
    with pytest.raises(DocumentRejected) as exc:
        extract_docx((FIX / "zipbomb.docx").read_bytes())
    assert exc.value.code == "zip_bomb"


def test_hostile_zipbomb_oversized_decompressed_total():
    # many small entries whose total declared size exceeds limit
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("word/document.xml", _clean_document_xml())
        big = "\x00" * (1024 * 1024)
        for i in range(60):
            z.writestr(f"word/extra{i}.txt", big)
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(buf.getvalue())
    assert exc.value.code == "zip_bomb"


def test_hostile_too_many_entries():
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("word/document.xml", _clean_document_xml())
        for i in range(250):
            z.writestr(f"word/x{i}.txt", "x")
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(buf.getvalue())
    assert exc.value.code == "too_many_entries"


# --- path traversal ---


def test_hostile_path_traversal_fixture_style():
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("../evil.txt", "x")
        z.writestr("word/document.xml", _clean_document_xml())
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(buf.getvalue())
    assert exc.value.code == "path_traversal"


def test_hostile_path_traversal_dotdot_segment():
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("word/../evil.txt", "x")
        z.writestr("word/document.xml", _clean_document_xml())
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(buf.getvalue())
    assert exc.value.code == "path_traversal"


def test_hostile_path_traversal_absolute():
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("/etc/passwd", "x")
        z.writestr("word/document.xml", _clean_document_xml())
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(buf.getvalue())
    assert exc.value.code == "path_traversal"


def test_hostile_path_traversal_backslash():
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("word\\..\\evil.txt", "x")
        z.writestr("word/document.xml", _clean_document_xml())
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(buf.getvalue())
    assert exc.value.code == "path_traversal"


# --- oversized ---


def test_hostile_oversized_upload(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "10")
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(b"PK" + b"a" * 100)
    assert exc.value.code == "upload_too_large"
    assert exc.value.status_code == 413


def test_hostile_oversized_large_env_clamped(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", str(100 * 1024 * 1024))
    assert max_document_bytes() == MAX_DOCUMENT_BYTES
    data = b"x" * (MAX_DOCUMENT_BYTES + 1)
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(data)
    assert exc.value.code == "upload_too_large"


# --- MIME mismatch ---


def test_hostile_mime_mismatch_pdf():
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(b"PK", declared_mime="application/pdf")
    assert exc.value.code == "mime_mismatch"
    assert exc.value.status_code == 415


def test_hostile_mime_mismatch_text_plain():
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(b"PK", declared_mime="text/plain")
    assert exc.value.code == "mime_mismatch"


def test_hostile_mime_mismatch_with_charset():
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(b"PK", declared_mime="application/pdf; charset=utf-8")
    assert exc.value.code == "mime_mismatch"


def test_hostile_mime_match_accepted():
    data = _minimal_docx(_clean_document_xml())
    r = extract_docx(data, declared_mime="application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    assert r.format == "docx"


# --- external relationship warning (not fetched) ---


def test_hostile_external_rel_warned_not_fetched():
    xml = _clean_document_xml()
    rels = (
        "<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'>"
        "<Relationship Id='rId1' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink' "
        "Target='http://example.com/evil' TargetMode='External'/>"
        "</Relationships>"
    )
    data = _minimal_docx(xml, extra_entries={"word/_rels/document.xml.rels": rels})
    r = extract_docx(data)
    assert any("External relationship" in w for w in r.warnings)
    assert "http://example.com" not in r.text


# --- corrupt fixture ---


def test_hostile_corrupt_fixture():
    with pytest.raises(DocumentRejected) as exc:
        extract_docx((FIX / "corrupt.docx").read_bytes())
    assert exc.value.code == "invalid_docx"
