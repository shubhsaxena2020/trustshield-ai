"""Bounded, local-only PDF text extraction."""
from __future__ import annotations

import re
import time
import zlib

from .document_types import (
    DocumentRejected,
    ExtractedDocument,
    EXTRACTION_TIMEOUT_SECONDS,
    MAX_DECOMPRESSED_BYTES,
    MAX_EXTRACTED_TEXT_CHARS,
    MAX_PAGES,
    max_document_bytes as effective_max_document_bytes,
)

_PDF_HEADER_RE = re.compile(rb"%PDF-")
_STREAM_RE = re.compile(rb"stream\r?\n(.*?)\r?\n?endstream", re.DOTALL)
_TYPE_PAGE_RE = re.compile(rb"/Type\s*/Page(?!s)")
_TJ_RE = re.compile(rb"\[(.*?)\]\s*TJ", re.DOTALL)
_TJ_STRING_RE = re.compile(rb"\((?:\\.|[^()]*)\)")
_TJ_SINGLE_RE = re.compile(rb"\((?:\\.|[^()]*)\)\s*Tj", re.DOTALL)


def max_document_bytes() -> int:
    return effective_max_document_bytes()


def _deadline(start: float) -> None:
    if time.monotonic() - start > EXTRACTION_TIMEOUT_SECONDS:
        raise DocumentRejected(408, "extraction_timeout", "PDF extraction exceeded the time limit.")


def _bounded_inflate(raw: bytes, cap: int, start: float) -> bytes:
    """Incrementally inflate a FlateDecode stream, never materializing more than cap bytes."""
    if cap < 0:
        cap = 0
    if cap == 0:
        raise DocumentRejected(422, "decompression_bomb", "PDF stream decompressed size exceeds the limit.")
    decompressor = zlib.decompressobj()
    out = bytearray()
    pending = raw
    try:
        while True:
            _deadline(start)
            if decompressor.eof:
                break
            allowed = cap - len(out)
            if allowed <= 0:
                # Output already at cap but stream not finished -> over budget.
                if pending or decompressor.unconsumed_tail:
                    raise DocumentRejected(422, "decompression_bomb", "PDF stream decompressed size exceeds the limit.")
                raise DocumentRejected(422, "invalid_pdf", "A PDF stream could not be decompressed.")
            chunk = decompressor.decompress(pending, max_length=allowed)
            pending = decompressor.unconsumed_tail
            out.extend(chunk)
            if len(out) > cap:
                raise DocumentRejected(422, "decompression_bomb", "PDF stream decompressed size exceeds the limit.")
            # If we filled the budget but the stream is not done, it is over budget.
            if len(out) == cap and not decompressor.eof:
                if pending or decompressor.unconsumed_tail:
                    raise DocumentRejected(422, "decompression_bomb", "PDF stream decompressed size exceeds the limit.")
                # No pending input but not eof -> incomplete stream.
                raise DocumentRejected(422, "invalid_pdf", "A PDF stream could not be decompressed.")
            if not chunk and not pending and not decompressor.eof:
                raise DocumentRejected(422, "invalid_pdf", "A PDF stream could not be decompressed.")
    except zlib.error as exc:
        raise DocumentRejected(422, "invalid_pdf", "A PDF stream could not be decompressed.") from exc
    if not decompressor.eof and raw != b"":
        raise DocumentRejected(422, "invalid_pdf", "A PDF stream could not be decompressed.")
    return bytes(out)


def _decode_pdf_string(raw: bytes) -> str:
    # raw includes surrounding parentheses
    body = raw[1:-1]
    out = bytearray()
    i = 0
    n = len(body)
    while i < n:
        b = body[i]
        if b == 0x5C:  # backslash
            if i + 1 >= n:
                i += 1
                continue
            nxt = body[i + 1]
            if nxt in (0x6E,):  # n
                out.append(0x0A); i += 2; continue
            if nxt in (0x72,):  # r
                out.append(0x0D); i += 2; continue
            if nxt in (0x74,):  # t
                out.append(0x09); i += 2; continue
            if nxt in (0x62,):  # b
                out.append(0x08); i += 2; continue
            if nxt in (0x66,):  # f
                out.append(0x0C); i += 2; continue
            if nxt in (0x28, 0x29, 0x5C):  # ( ) backslash
                out.append(nxt); i += 2; continue
            if chr(nxt).isdigit():
                # octal up to 3 digits
                j = i + 1
                oct_digits = bytearray()
                while j < n and len(oct_digits) < 3 and chr(body[j]).isdigit() and body[j] < 0x38:
                    oct_digits.append(body[j]); j += 1
                if oct_digits:
                    try:
                        out.append(int(oct_digits.decode("ascii"), 8) & 0xFF)
                    except ValueError:
                        pass
                    i = j
                    continue
            # line continuation
            if nxt in (0x0A, 0x0D):
                i += 2
                if nxt == 0x0D and i < n and body[i] == 0x0A:
                    i += 1
                continue
            out.append(nxt); i += 2; continue
        out.append(b); i += 1
    # UTF-16BE BOM handling
    if len(out) >= 2 and out[0] == 0xFE and out[1] == 0xFF:
        try:
            return out[2:].decode("utf-16-be", errors="replace")
        except UnicodeDecodeError:
            return out[2:].decode("latin-1", errors="replace")
    try:
        return out.decode("utf-8")
    except UnicodeDecodeError:
        return out.decode("latin-1", errors="replace")


def _extract_strings(content: bytes) -> list[str]:
    found: list[str] = []
    # TJ arrays
    for m in _TJ_RE.finditer(content):
        for sm in _TJ_STRING_RE.finditer(m.group(1)):
            found.append(_decode_pdf_string(sm.group(0)))
    # single Tj
    for m in _TJ_SINGLE_RE.finditer(content):
        # extract the parenthesized part
        s = m.group(0)
        # find first '(' ... matching last ')' before Tj
        idx = s.rfind(b")")
        if idx != -1:
            found.append(_decode_pdf_string(s[: idx + 1]))
    # fallback: any parenthesized string
    if not found:
        for m in re.finditer(rb"\((?:\\.|[^()]*)\)", content):
            found.append(_decode_pdf_string(m.group(0)))
    return found


def extract_pdf(data: bytes, declared_mime: str | None = None, filename: str | None = None) -> ExtractedDocument:
    del filename
    size = len(data or b"")
    if size == 0:
        raise DocumentRejected(422, "empty_document", "The uploaded document is empty.")
    if size > max_document_bytes():
        raise DocumentRejected(413, "upload_too_large", "Document exceeds the maximum upload size.")
    if declared_mime:
        media = declared_mime.split(";", 1)[0].strip().lower()
        if media and media not in {"application/octet-stream", ""} and media != "application/pdf":
            raise DocumentRejected(415, "mime_mismatch", "The declared MIME type does not match the PDF format.")

    start = time.monotonic()
    head = data[:1024]
    if b"%PDF-" not in head:
        raise DocumentRejected(422, "invalid_pdf", "The uploaded bytes are not a valid PDF.")
    _deadline(start)

    if b"/Encrypt" in data:
        raise DocumentRejected(403, "encrypted_pdf", "Encrypted or password-protected PDFs are not supported.")

    _deadline(start)
    page_count = len(_TYPE_PAGE_RE.findall(data))
    if page_count == 0:
        page_count = 1
    if page_count > MAX_PAGES:
        raise DocumentRejected(422, "too_many_pages", "PDF exceeds the page limit.")

    _deadline(start)
    # Decompress streams and extract text.
    text_parts: list[str] = []
    stream_count = 0
    decompressed_total = 0
    for m in _STREAM_RE.finditer(data):
        _deadline(start)
        # Determine if the containing object declares FlateDecode.
        # Look backwards a bounded window for the object header.
        stream_start = m.start()
        window_start = max(0, stream_start - 512)
        header = data[window_start:stream_start]
        raw = m.group(1)
        # Strip a single trailing newline before endstream (already handled by regex).
        if b"FlateDecode" in header:
            remaining_budget = MAX_DECOMPRESSED_BYTES - decompressed_total
            if remaining_budget <= 0:
                raise DocumentRejected(422, "decompression_bomb", "PDF stream decompressed size exceeds the limit.")
            try:
                decompressed = _bounded_inflate(raw, min(remaining_budget, MAX_DECOMPRESSED_BYTES), start)
            except DocumentRejected:
                raise
        else:
            decompressed = raw
        # Account for this stream toward the cumulative decompressed budget.
        stream_size = len(decompressed)
        decompressed_total += stream_size
        if decompressed_total > MAX_DECOMPRESSED_BYTES:
            raise DocumentRejected(422, "decompression_bomb", "PDF stream decompressed size exceeds the limit.")
        stream_count += 1
        strings = _extract_strings(decompressed)
        if strings:
            text_parts.append(" ".join(strings))

    _deadline(start)
    text = "\n".join(text_parts)
    # collapse whitespace
    text = re.sub(r"[ \t\r\f\v]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text).strip()

    truncated = False
    if len(text) > MAX_EXTRACTED_TEXT_CHARS:
        text = text[:MAX_EXTRACTED_TEXT_CHARS]
        truncated = True

    warnings = []
    if stream_count == 0:
        warnings.append("No content streams were found; the PDF may be scanned or empty.")
    if truncated:
        warnings.append("PDF text was truncated to the extraction limit.")

    return ExtractedDocument(
        format="pdf",
        text=text,
        metadata={
            "size_bytes": size,
            "page_count": page_count,
            "stream_count": stream_count,
            "char_count": len(text),
            "truncated": truncated,
        },
        warnings=warnings,
    )
