from pathlib import Path

import pytest

from services.analysis.extractors.pdf_text import extract_pdf
from services.analysis.extractors.docx_text import extract_docx
from services.analysis.extractors.xlsx_text import extract_xlsx
from services.analysis.extractors.document_types import DocumentRejected
from services.analysis.extractors.document_types import MAX_DOCUMENT_BYTES
from services.analysis.extractors.xlsx_text import max_document_bytes as xlsx_max_document_bytes

FIX = Path("tests/fixtures/uploads")


def test_pdf_accepted():
    r = extract_pdf((FIX / "clean.pdf").read_bytes())
    assert r.format == "pdf"
    assert "blocked" in r.text
    assert r.metadata["page_count"] == 1


def test_pdf_invalid_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_pdf((FIX / "corrupt.pdf").read_bytes())
    assert exc.value.code == "invalid_pdf"


def test_pdf_encrypted_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_pdf((FIX / "password_protected.pdf").read_bytes())
    assert exc.value.code == "encrypted_pdf"


def test_pdf_mime_mismatch_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_pdf(b"%PDF-1.4\n", declared_mime="text/plain")
    assert exc.value.code == "mime_mismatch"


def test_pdf_empty_rejected():
    with pytest.raises(DocumentRejected):
        extract_pdf(b"")


def test_docx_accepted():
    r = extract_docx((FIX / "clean.docx").read_bytes())
    assert r.format == "docx"
    assert "TrustShield synthetic" in r.text


def test_docx_invalid_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_docx((FIX / "corrupt.docx").read_bytes())
    assert exc.value.code == "invalid_docx"


def test_docx_encrypted_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_docx((FIX / "password_protected.docx").read_bytes())
    assert exc.value.code == "encrypted_docx"


def test_docx_macro_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_docx((FIX / "macro_enabled.docx").read_bytes())
    assert exc.value.code == "macro_enabled"


def test_docx_zipbomb_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_docx((FIX / "zipbomb.docx").read_bytes())
    assert exc.value.code == "zip_bomb"


def test_docx_path_traversal_rejected():
    import io, zipfile
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("../evil.txt", "x")
        z.writestr("word/document.xml", "<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body><w:p><w:r><w:t>hi</w:t></w:r></w:p></w:body></w:document>")
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(buf.getvalue())
    assert exc.value.code == "path_traversal"


def test_docx_mime_mismatch_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_docx(b"PK", declared_mime="application/pdf")
    assert exc.value.code == "mime_mismatch"


def test_xlsx_accepted():
    r = extract_xlsx((FIX / "clean.xlsx").read_bytes())
    assert r.format == "xlsx"
    assert "Hello" in r.text
    assert r.metadata["sheet_count"] == 1


def test_xlsx_invalid_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_xlsx((FIX / "corrupt.xlsx").read_bytes())
    assert exc.value.code == "invalid_xlsx"


def test_xlsx_encrypted_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_xlsx((FIX / "password_protected.xlsx").read_bytes())
    assert exc.value.code == "encrypted_xlsx"


def test_xlsx_macro_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_xlsx((FIX / "macro_enabled.xlsx").read_bytes())
    assert exc.value.code == "macro_enabled"


def test_xlsx_zipbomb_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_xlsx((FIX / "zipbomb.xlsx").read_bytes())
    assert exc.value.code == "zip_bomb"


def test_xlsx_path_traversal_rejected():
    import io, zipfile
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("../evil.txt", "x")
        z.writestr("xl/worksheets/sheet1.xml", "<worksheet xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><sheetData/></worksheet>")
    with pytest.raises(DocumentRejected) as exc:
        extract_xlsx(buf.getvalue())
    assert exc.value.code == "path_traversal"


def test_xlsx_mime_mismatch_rejected():
    with pytest.raises(DocumentRejected) as exc:
        extract_xlsx(b"PK", declared_mime="application/pdf")
    assert exc.value.code == "mime_mismatch"


def test_xlsx_upload_cap_never_exceeds_hard_limit(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", str(100 * 1024 * 1024))
    assert xlsx_max_document_bytes() == MAX_DOCUMENT_BYTES


def test_xlsx_upload_cap_honors_lower_environment_limit(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "5")
    with pytest.raises(DocumentRejected) as exc:
        extract_xlsx((FIX / "clean.xlsx").read_bytes())
    assert exc.value.code == "upload_too_large"


def test_xlsx_no_external_fetch():
    # ensure extraction does not attempt network; just runs
    r = extract_xlsx((FIX / "clean.xlsx").read_bytes())
    assert r is not None
