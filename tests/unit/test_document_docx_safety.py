import io
import zipfile

import pytest

from services.analysis.extractors.document_types import (
    MAX_DOCUMENT_BYTES,
    DocumentRejected,
)
from services.analysis.extractors.docx_text import extract_docx, max_document_bytes

FIX = __import__("pathlib").Path("tests/fixtures/uploads")


def _minimal_docx(document_xml: str) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr(
            "word/document.xml",
            document_xml,
        )
        z.writestr(
            "word/_rels/document.xml.rels",
            "<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'/>",
        )
    return buf.getvalue()


def _clean_document_xml(text: str = "TrustShield synthetic") -> str:
    return (
        "<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'>"
        "<w:body><w:p><w:r><w:t>" + text + "</w:t></w:r></w:p></w:body></w:document>"
    )


def test_docx_clean_fixture_accepted():
    r = extract_docx((FIX / "clean.docx").read_bytes())
    assert r.format == "docx"
    assert "TrustShield synthetic" in r.text


def test_docx_minimal_accepted():
    data = _minimal_docx(_clean_document_xml())
    r = extract_docx(data)
    assert r.format == "docx"
    assert "TrustShield synthetic" in r.text


def test_docx_empty_rejected():
    with pytest.raises(DocumentRejected):
        extract_docx(b"")


def test_docx_invalid_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(b"not a docx")
    assert exc.value.code == "invalid_docx"


def test_docx_encrypted_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1" + b"\x00" * 16)
    assert exc.value.code == "encrypted_docx"


def test_docx_defused_xml_rejected():
    xml = (
        '<?xml version="1.0"?>\n'
        '<!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>\n'
        + _clean_document_xml("&xxe;")
    )
    data = _minimal_docx(xml)
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(data)
    assert exc.value.code == "invalid_docx"


def test_docx_malformed_xml_rejected():
    data = _minimal_docx("<w:document><w:body><w:p>")
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(data)
    assert exc.value.code == "invalid_docx"


def test_docx_crc_corrupt_rejected():
    data = bytearray(_minimal_docx(_clean_document_xml()))
    sig = b"PK\x01\x02"
    idx = bytes(data).find(sig)
    assert idx != -1
    data[idx + 16] ^= 0xFF
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(bytes(data))
    assert exc.value.code == "invalid_docx"


def test_docx_mime_mismatch_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(b"PK", declared_mime="application/pdf")
    assert exc.value.code == "mime_mismatch"


def test_docx_zipbomb_rejected():
    import io as _io
    import zipfile as _zipfile

    buf = _io.BytesIO()
    with _zipfile.ZipFile(buf, "w") as z:
        z.writestr("word/document.xml", _clean_document_xml())
        # oversized declared size via info? Use many entries? Instead craft bomb not needed;
        # rely on existing zipbomb fixture.
    # Use provided fixture if available
    try:
        r = extract_docx((FIX / "zipbomb.docx").read_bytes())
    except DocumentRejected as exc:
        assert exc.code == "zip_bomb"
    else:
        pytest.fail("zipbomb fixture was not rejected")


def test_max_document_bytes_clamps_large_env(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", str(100 * 1024 * 1024))
    assert max_document_bytes() == MAX_DOCUMENT_BYTES


def test_max_document_bytes_honors_smaller_env(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "12345")
    assert max_document_bytes() == 12345


def test_max_document_bytes_invalid_env(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "not-an-int")
    assert max_document_bytes() == MAX_DOCUMENT_BYTES


def test_docx_oversized_upload_rejected(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "10")
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(b"PK" + b"a" * 100)
    assert exc.value.code == "upload_too_large"
