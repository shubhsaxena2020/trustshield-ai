"""Synthetic XML security checks for local office-document extraction."""

import io
import zipfile

import pytest

from services.analysis.extractors.document_types import DocumentRejected
from services.analysis.extractors.docx_text import extract_docx
from services.analysis.extractors.xlsx_text import extract_xlsx


def _archive(entries: dict[str, str]) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, content in entries.items():
            archive.writestr(name, content)
    return buffer.getvalue()


def test_docx_rejects_entity_declaration():
    xml = (
        '<!DOCTYPE w:document [<!ENTITY x "expanded">]>'
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        '<w:body><w:p><w:r><w:t>&x;</w:t></w:r></w:p></w:body></w:document>'
    )
    with pytest.raises(DocumentRejected):
        extract_docx(_archive({"word/document.xml": xml}))


def test_xlsx_rejects_entity_declaration():
    workbook = (
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        '<sheets><sheet name="Sheet1" sheetId="1"/></sheets></workbook>'
    )
    sheet = (
        '<!DOCTYPE worksheet [<!ENTITY x "expanded">]>'
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        '<sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>&x;</t></is></c></row></sheetData>'
        '</worksheet>'
    )
    with pytest.raises(DocumentRejected):
        extract_xlsx(_archive({"xl/workbook.xml": workbook, "xl/worksheets/sheet1.xml": sheet}))
