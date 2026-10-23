"""Bounded, local-only DOCX text extraction."""
from __future__ import annotations

import io
import time
import zipfile
import zlib

import defusedxml.ElementTree as ET
from defusedxml.common import DefusedXmlException

from .document_types import (
    DocumentRejected,
    ExtractedDocument,
    MAX_DECOMPRESSED_BYTES,
    MAX_EXTRACTED_TEXT_CHARS,
    MAX_ZIP_ENTRIES,
    EXTRACTION_TIMEOUT_SECONDS,
    max_document_bytes as effective_max_document_bytes,
)

W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"


def max_document_bytes() -> int:
    return effective_max_document_bytes()


def _deadline(start: float) -> None:
    import time as _t
    if _t.monotonic() - start > EXTRACTION_TIMEOUT_SECONDS:
        raise DocumentRejected(408, "extraction_timeout", "DOCX extraction exceeded the time limit.")


def _safe_zip(data: bytes) -> zipfile.ZipFile:
    if data[:8] == b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1":
        raise DocumentRejected(403, "encrypted_docx", "Encrypted or password-protected DOCX is not supported.")
    try:
        zf = zipfile.ZipFile(io.BytesIO(data))
    except (zipfile.BadZipFile, OSError, RuntimeError) as exc:
        raise DocumentRejected(422, "invalid_docx", "The uploaded bytes are not a valid DOCX (zip) archive.") from exc
    infos = zf.infolist()
    if len(infos) > MAX_ZIP_ENTRIES:
        raise DocumentRejected(422, "too_many_entries", "DOCX archive contains too many entries.")
    total = 0
    for info in infos:
        name = info.filename
        if name.startswith("/") or "\\" in name:
            raise DocumentRejected(422, "path_traversal", "DOCX archive contains an unsafe entry path.")
        parts = [p for p in name.split("/") if p not in ("", ".")]
        if ".." in parts:
            raise DocumentRejected(422, "path_traversal", "DOCX archive contains an unsafe entry path.")
        if info.flag_bits & 0x1:
            raise DocumentRejected(403, "encrypted_docx", "Encrypted or password-protected DOCX is not supported.")
        total += info.file_size
        if total > MAX_DECOMPRESSED_BYTES:
            raise DocumentRejected(422, "zip_bomb", "DOCX archive decompressed size exceeds the limit.")
    # macro detection
    for info in infos:
        low = info.filename.lower()
        if "vbaproject" in low or ("vba" in low and low.endswith(".bin")):
            raise DocumentRejected(422, "macro_enabled", "Macro-enabled DOCX documents are not supported.")
    return zf


def _extract_paragraph_text(root) -> list[str]:
    paragraphs: list[str] = []
    for p in root.iter(f"{{{W_NS}}}p"):
        chunks: list[str] = []
        for t in p.iter(f"{{{W_NS}}}t"):
            chunks.append(t.text or "")
        for tab in p.iter(f"{{{W_NS}}}tab"):
            chunks.append("\t")
        for br in p.iter(f"{{{W_NS}}}br"):
            chunks.append("\n")
        paragraphs.append("".join(chunks))
    return paragraphs


def _read_xml(zf: zipfile.ZipFile, name: str):
    try:
        data = zf.read(name)
    except KeyError:
        return None
    except (zipfile.BadZipFile, OSError, RuntimeError, zlib.error) as exc:
        raise DocumentRejected(422, "invalid_docx", "DOCX contains a corrupt or unreadable XML part.") from exc
    try:
        return ET.fromstring(data)
    except (ET.ParseError, DefusedXmlException, ValueError, TypeError) as exc:
        raise DocumentRejected(422, "invalid_docx", "DOCX contains malformed XML.") from exc


def extract_docx(data: bytes, declared_mime: str | None = None, filename: str | None = None) -> ExtractedDocument:
    del filename
    size = len(data or b"")
    if size == 0:
        raise DocumentRejected(422, "empty_document", "The uploaded document is empty.")
    if size > max_document_bytes():
        raise DocumentRejected(413, "upload_too_large", "Document exceeds the maximum upload size.")
    if declared_mime:
        media = declared_mime.split(";", 1)[0].strip().lower()
        if media and media not in {"application/octet-stream", ""} and media != "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
            raise DocumentRejected(415, "mime_mismatch", "The declared MIME type does not match the DOCX format.")

    start = time.monotonic()
    zf = _safe_zip(data)
    _deadline(start)

    # External links are never fetched; warn if present.
    warnings: list[str] = []
    rels = _read_xml(zf, "word/_rels/document.xml.rels")
    if rels is not None:
        for rel in rels.iter():
            if rel.get("TargetMode", "").lower() == "external":
                warnings.append("External relationship target ignored (not fetched).")
                break

    doc_root = _read_xml(zf, "word/document.xml")
    if doc_root is None:
        raise DocumentRejected(422, "invalid_docx", "DOCX is missing word/document.xml.")
    _deadline(start)

    paragraphs = _extract_paragraph_text(doc_root)
    # headers/footers
    for name in zf.namelist():
        low = name.lower()
        if low.startswith("word/header") or low.startswith("word/footer"):
            root = _read_xml(zf, name)
            if root is not None:
                paragraphs.extend(_extract_paragraph_text(root))

    _deadline(start)
    text = "\n".join(p for p in paragraphs)
    text = text.strip()
    # collapse excessive blank lines
    import re
    text = re.sub(r"\n{3,}", "\n\n", text)

    truncated = False
    if len(text) > MAX_EXTRACTED_TEXT_CHARS:
        text = text[:MAX_EXTRACTED_TEXT_CHARS]
        truncated = True
        warnings.append("DOCX text was truncated to the extraction limit.")

    if not text:
        warnings.append("No readable text was found in the DOCX.")

    return ExtractedDocument(
        format="docx",
        text=text,
        metadata={
            "size_bytes": size,
            "paragraph_count": len(paragraphs),
            "entry_count": len(zf.infolist()),
            "char_count": len(text),
            "truncated": truncated,
        },
        warnings=warnings,
    )
