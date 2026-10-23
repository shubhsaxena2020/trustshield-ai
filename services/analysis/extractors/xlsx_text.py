"""Bounded, local-only XLSX text extraction."""
from __future__ import annotations

import io
import re
import time
import zipfile
import zlib

import defusedxml.ElementTree as ET
from defusedxml.common import DefusedXmlException

from .document_types import (
    DocumentRejected,
    ExtractedDocument,
    MAX_CELL_CHARS,
    MAX_CELLS,
    MAX_COLUMNS,
    MAX_DECOMPRESSED_BYTES,
    MAX_EXTRACTED_TEXT_CHARS,
    MAX_ROWS,
    MAX_ZIP_ENTRIES,
    EXTRACTION_TIMEOUT_SECONDS,
    max_document_bytes as effective_max_document_bytes,
)

SS_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"


def max_document_bytes() -> int:
    return effective_max_document_bytes()


def _deadline(start: float) -> None:
    if time.monotonic() - start > EXTRACTION_TIMEOUT_SECONDS:
        raise DocumentRejected(408, "extraction_timeout", "XLSX extraction exceeded the time limit.")


def _safe_zip(data: bytes) -> zipfile.ZipFile:
    if data[:8] == b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1":
        raise DocumentRejected(403, "encrypted_xlsx", "Encrypted or password-protected XLSX is not supported.")
    try:
        zf = zipfile.ZipFile(io.BytesIO(data))
    except (zipfile.BadZipFile, OSError, RuntimeError) as exc:
        raise DocumentRejected(422, "invalid_xlsx", "The uploaded bytes are not a valid XLSX (zip) archive.") from exc
    infos = zf.infolist()
    if len(infos) > MAX_ZIP_ENTRIES:
        raise DocumentRejected(422, "too_many_entries", "XLSX archive contains too many entries.")
    total = 0
    for info in infos:
        name = info.filename
        if name.startswith("/") or "\\" in name:
            raise DocumentRejected(422, "path_traversal", "XLSX archive contains an unsafe entry path.")
        parts = [p for p in name.split("/") if p not in ("", ".")]
        if ".." in parts:
            raise DocumentRejected(422, "path_traversal", "XLSX archive contains an unsafe entry path.")
        if info.flag_bits & 0x1:
            raise DocumentRejected(403, "encrypted_xlsx", "Encrypted or password-protected XLSX is not supported.")
        total += info.file_size
        if total > MAX_DECOMPRESSED_BYTES:
            raise DocumentRejected(422, "zip_bomb", "XLSX archive decompressed size exceeds the limit.")
    for info in infos:
        low = info.filename.lower()
        if "vbaproject" in low or ("vba" in low and low.endswith(".bin")):
            raise DocumentRejected(422, "macro_enabled", "Macro-enabled XLSX workbooks are not supported.")
    return zf


def _parse_shared_strings(zf: zipfile.ZipFile) -> list[str]:
    root = _read_xml(zf, "xl/sharedStrings.xml", "XLSX shared strings are malformed.")
    if root is None:
        return []
    result: list[str] = []
    for si in root.iter(f"{{{SS_NS}}}si"):
        # concatenate all <t> (handles rich text runs)
        texts = [t.text or "" for t in si.iter(f"{{{SS_NS}}}t")]
        result.append("".join(texts))
    return result


def _read_xml(zf: zipfile.ZipFile, name: str, malformed_message: str):
    try:
        data = zf.read(name)
    except KeyError:
        return None
    except (zipfile.BadZipFile, OSError, RuntimeError, zlib.error) as exc:
        raise DocumentRejected(422, "invalid_xlsx", "XLSX contains a corrupt or unreadable part.") from exc
    try:
        return ET.fromstring(data)
    except (ET.ParseError, DefusedXmlException, ValueError, TypeError) as exc:
        raise DocumentRejected(422, "invalid_xlsx", malformed_message) from exc


def _cell_value(cell, shared: list[str]) -> str:
    t = cell.get("t")
    if t == "inlineStr":
        is_el = cell.find(f"{{{SS_NS}}}is")
        if is_el is None:
            return ""
        return "".join((tt.text or "") for tt in is_el.iter(f"{{{SS_NS}}}t"))
    v = cell.find(f"{{{SS_NS}}}v")
    raw = v.text if v is not None and v.text is not None else ""
    if t == "s":
        try:
            idx = int(raw)
            if 0 <= idx < len(shared):
                return shared[idx]
        except ValueError:
            pass
        return ""
    if t == "str":
        return raw
    # numeric / boolean / date: keep raw string, do not evaluate formulas
    f = cell.find(f"{{{SS_NS}}}f")
    if f is not None and v is None:
        return ""  # formula without cached value is not evaluated
    return raw


def extract_xlsx(data: bytes, declared_mime: str | None = None, filename: str | None = None) -> ExtractedDocument:
    del filename
    size = len(data or b"")
    if size == 0:
        raise DocumentRejected(422, "empty_document", "The uploaded document is empty.")
    if size > max_document_bytes():
        raise DocumentRejected(413, "upload_too_large", "Document exceeds the maximum upload size.")
    if declared_mime:
        media = declared_mime.split(";", 1)[0].strip().lower()
        allowed = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        if media and media not in {"application/octet-stream", ""} and media != allowed:
            raise DocumentRejected(415, "mime_mismatch", "The declared MIME type does not match the XLSX format.")

    start = time.monotonic()
    zf = _safe_zip(data)
    _deadline(start)

    shared = _parse_shared_strings(zf)
    _deadline(start)

    # find worksheet parts
    sheet_names: list[str] = []
    # try workbook.xml
    try:
        wb = _read_xml(zf, "xl/workbook.xml", "XLSX workbook is malformed.")
        if wb is None:
            raise DocumentRejected(422, "invalid_xlsx", "XLSX is missing xl/workbook.xml.")
        for sh in wb.iter(f"{{{SS_NS}}}sheet"):
            sheet_names.append(sh.get("name") or f"Sheet{len(sheet_names)+1}")
    except DocumentRejected:
        raise
    # discover sheet xml files
    sheet_files = sorted(n for n in zf.namelist() if re.match(r"xl/worksheets/sheet\d+\.xml", n))
    if not sheet_files:
        raise DocumentRejected(422, "invalid_xlsx", "XLSX contains no worksheets.")
    if len(sheet_files) > 20:
        raise DocumentRejected(422, "too_many_sheets", "XLSX exceeds the sheet limit.")

    lines: list[str] = []
    row_count = 0
    cell_count = 0
    for idx, sf in enumerate(sheet_files):
        _deadline(start)
        root = _read_xml(zf, sf, "XLSX worksheet is malformed.")
        if root is None:
            raise DocumentRejected(422, "invalid_xlsx", "XLSX worksheet is missing.")
        name = sheet_names[idx] if idx < len(sheet_names) else f"Sheet{idx+1}"
        lines.append(f"[{name}]")
        for row in root.iter(f"{{{SS_NS}}}row"):
            if row_count >= MAX_ROWS:
                raise DocumentRejected(422, "too_many_rows", "XLSX exceeds the row limit.")
            cells: list[str] = []
            for c in row.iter(f"{{{SS_NS}}}c"):
                if len(cells) >= MAX_COLUMNS:
                    raise DocumentRejected(422, "too_many_columns", "XLSX row exceeds the column limit.")
                val = _cell_value(c, shared)
                if len(val) > MAX_CELL_CHARS:
                    val = val[:MAX_CELL_CHARS]
                cells.append(val)
                cell_count += 1
                if cell_count > MAX_CELLS:
                    raise DocumentRejected(422, "too_many_cells", "XLSX exceeds the cell limit.")
            lines.append(" | ".join(cells))
            row_count += 1
        lines.append("")

    text = "\n".join(lines).strip()
    text = re.sub(r"\n{3,}", "\n\n", text)

    truncated = False
    if len(text) > MAX_EXTRACTED_TEXT_CHARS:
        text = text[:MAX_EXTRACTED_TEXT_CHARS]
        truncated = True

    warnings: list[str] = []
    if truncated:
        warnings.append("XLSX text was truncated to the extraction limit.")

    return ExtractedDocument(
        format="xlsx",
        text=text,
        metadata={
            "size_bytes": size,
            "sheet_count": len(sheet_files),
            "row_count": row_count,
            "cell_count": cell_count,
            "entry_count": len(zf.infolist()),
            "char_count": len(text),
            "truncated": truncated,
        },
        warnings=warnings,
    )
