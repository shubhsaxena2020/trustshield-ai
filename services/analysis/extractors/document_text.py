"""Bounded text/CSV extraction for W3 uploads."""
from __future__ import annotations

import csv
import io
import time
import unicodedata

from .document_types import (
    DocumentRejected,
    ExtractedDocument,
    MAX_CELL_CHARS,
    MAX_CELLS,
    MAX_COLUMNS,
    MAX_EXTRACTED_TEXT_CHARS,
    MAX_ROWS,
    EXTRACTION_TIMEOUT_SECONDS,
    max_document_bytes as effective_max_document_bytes,
)


def max_document_bytes() -> int:
    return effective_max_document_bytes()


def _deadline(start: float) -> None:
    if time.monotonic() - start > EXTRACTION_TIMEOUT_SECONDS:
        raise DocumentRejected(408, "extraction_timeout", "Document extraction exceeded the time limit.")


def _mime_allowed(declared_mime, allowed: set[str]) -> None:
    if not declared_mime:
        return
    media = declared_mime.split(";", 1)[0].strip().lower()
    if media and media not in {"application/octet-stream", ""} and media not in allowed:
        # Only reject when the declared type is a specific, confident document MIME.
        if "/" in media:
            raise DocumentRejected(415, "mime_mismatch", "The declared MIME type does not match the document format.")


_TXT_MIME = {"text/plain", "text/*"}
_CSV_MIME = {"text/csv", "text/plain", "text/*"}


def _decode_text(data: bytes) -> str:
    for enc in ("utf-8-sig", "utf-8"):
        try:
            return data.decode(enc)
        except UnicodeDecodeError:
            continue
    raise DocumentRejected(422, "undecodable_text", "The uploaded bytes are not valid UTF-8 text.")


def extract_txt(data: bytes, declared_mime: str | None = None, filename: str | None = None) -> ExtractedDocument:
    del filename
    size = len(data or b"")
    if size == 0:
        raise DocumentRejected(422, "empty_document", "The uploaded document is empty.")
    if size > max_document_bytes():
        raise DocumentRejected(413, "upload_too_large", "Document exceeds the maximum upload size.")
    _mime_allowed(declared_mime, {"text/plain"})
    text_raw = _decode_text(data)
    truncated = False
    if len(text_raw) > MAX_EXTRACTED_TEXT_CHARS:
        text_raw = text_raw[:MAX_EXTRACTED_TEXT_CHARS]
        truncated = True
    # normalize whitespace lightly
    normalized = unicodedata.normalize("NFKC", text_raw)
    normalized = "".join(ch for ch in normalized if ch in "\n\t" or unicodedata.category(ch)[0] != "C")
    warnings = []
    if truncated:
        warnings.append("Text was truncated to the extraction limit.")
    return ExtractedDocument(
        format="txt",
        text=normalized.strip(),
        metadata={"size_bytes": size, "char_count": len(normalized.strip()), "truncated": truncated},
        warnings=warnings,
    )


def _detect_delimiter(sample: str) -> str:
    candidates = [",", "\t", ";", "|"]
    first_line = ""
    for line in sample.splitlines():
        if line.strip():
            first_line = line
            break
    if not first_line:
        return ","
    counts = {d: first_line.count(d) for d in candidates}
    best = max(counts, key=lambda d: counts[d])
    if counts[best] == 0:
        return ","
    return best


def extract_csv(data: bytes, declared_mime: str | None = None, filename: str | None = None) -> ExtractedDocument:
    del filename
    size = len(data or b"")
    if size == 0:
        raise DocumentRejected(422, "empty_document", "The uploaded document is empty.")
    if size > max_document_bytes():
        raise DocumentRejected(413, "upload_too_large", "Document exceeds the maximum upload size.")
    _mime_allowed(declared_mime, {"text/csv", "text/plain"})
    raw = _decode_text(data)
    start = time.monotonic()
    delim = _detect_delimiter(raw[:4096])
    reader = csv.reader(io.StringIO(raw), delimiter=delim)
    rows_out = []
    row_count = 0
    cell_count = 0
    max_cols = 0
    try:
        for row in reader:
            _deadline(start)
            if len(row) > MAX_COLUMNS:
                raise DocumentRejected(422, "too_many_columns", "CSV row exceeds the column limit.")
            if row_count >= MAX_ROWS:
                raise DocumentRejected(422, "too_many_rows", "CSV exceeds the row limit.")
            cleaned = []
            for cell in row:
                if len(cell) > MAX_CELL_CHARS:
                    raise DocumentRejected(422, "cell_too_large", "A CSV cell exceeds the size limit.")
                cleaned.append(cell)
            cell_count += len(cleaned)
            if cell_count > MAX_CELLS:
                raise DocumentRejected(422, "too_many_cells", "CSV exceeds the cell limit.")
            max_cols = max(max_cols, len(cleaned))
            rows_out.append(" | ".join(cleaned))
            row_count += 1
    except csv.Error as exc:
        raise DocumentRejected(422, "invalid_csv", "The CSV content is invalid.") from exc
    text = "\n".join(rows_out)
    truncated = False
    if len(text) > MAX_EXTRACTED_TEXT_CHARS:
        text = text[:MAX_EXTRACTED_TEXT_CHARS]
        truncated = True
    warnings = []
    if truncated:
        warnings.append("CSV text was truncated to the extraction limit.")
    return ExtractedDocument(
        format="csv",
        text=text.strip(),
        metadata={
            "size_bytes": size,
            "row_count": row_count,
            "column_count": max_cols,
            "cell_count": cell_count,
            "delimiter": delim,
            "truncated": truncated,
        },
        warnings=warnings,
    )


def extract_document(data: bytes, declared_mime: str | None = None, filename: str | None = None) -> ExtractedDocument:
    """Format dispatcher used by the API and pipeline.

    Detection order: explicit filename extension, then declared MIME, then
    magic bytes / decodability. Never raises anything other than
    ``DocumentRejected`` for a rejected/unsupported upload.
    """
    from .document_types import DocumentRejected
    from .pdf_text import extract_pdf
    from .docx_text import extract_docx
    from .xlsx_text import extract_xlsx

    size = len(data or b"")
    if size == 0:
        raise DocumentRejected(422, "empty_document", "The uploaded document is empty.")
    if size > max_document_bytes():
        raise DocumentRejected(413, "upload_too_large", "Document exceeds the maximum upload size.")

    ext = ""
    if filename and "." in filename:
        ext = filename.rsplit(".", 1)[-1].lower().strip()
    media = ""
    if declared_mime:
        media = declared_mime.split(";", 1)[0].strip().lower()

    # Explicit extension wins.
    if ext == "pdf":
        return extract_pdf(data, declared_mime, filename)
    if ext == "docx":
        return extract_docx(data, declared_mime, filename)
    if ext == "xlsx":
        return extract_xlsx(data, declared_mime, filename)
    if ext == "csv":
        return extract_csv(data, declared_mime, filename)
    if ext == "txt":
        return extract_txt(data, declared_mime, filename)

    # MIME-based detection.
    if media == "application/pdf":
        return extract_pdf(data, declared_mime, filename)
    if media == "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
        return extract_docx(data, declared_mime, filename)
    if media == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":
        return extract_xlsx(data, declared_mime, filename)
    if media == "text/csv":
        return extract_csv(data, declared_mime, filename)
    if media == "text/plain" or media.startswith("text/"):
        return extract_txt(data, declared_mime, filename)

    # Magic-byte / decodability fallback.
    head = (data or b"")[:8]
    if head.startswith(b"%PDF"):
        return extract_pdf(data, declared_mime, filename)
    if head.startswith(b"PK"):
        try:
            import io, zipfile
            with zipfile.ZipFile(io.BytesIO(data)) as zf:
                names = set(zf.namelist())
            if "word/document.xml" in names:
                return extract_docx(data, declared_mime, filename)
            if "xl/workbook.xml" in names:
                return extract_xlsx(data, declared_mime, filename)
        except Exception:
            pass
    # Last resort: try UTF-8 text, but only for genuinely textual bytes.
    if 0 in (data or b""):
        raise DocumentRejected(415, "unsupported_format", "The uploaded document format is not supported.")
    try:
        (data or b"").decode("utf-8")
        return extract_txt(data, declared_mime, filename)
    except UnicodeDecodeError:
        pass

    raise DocumentRejected(415, "unsupported_format", "The uploaded document format is not supported.")
