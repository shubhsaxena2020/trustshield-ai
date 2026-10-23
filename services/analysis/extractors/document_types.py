"""Shared, format-agnostic contract for W3 document text extractors."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Any


class DocumentRejected(ValueError):
    def __init__(self, status_code: int, code: str, message: str):
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message


@dataclass
class ExtractedDocument:
    format: str
    text: str
    metadata: dict[str, Any] = field(default_factory=dict)
    warnings: list[str] = field(default_factory=list)


MAX_DOCUMENT_BYTES = 5 * 1024 * 1024
MAX_EXTRACTED_TEXT_CHARS = 20_000
MAX_ZIP_ENTRIES = 200
MAX_DECOMPRESSED_BYTES = 50 * 1024 * 1024
MAX_PAGES = 100
MAX_ROWS = 10_000
MAX_COLUMNS = 256
MAX_CELLS = 100_000
MAX_CELL_CHARS = 10_000
EXTRACTION_TIMEOUT_SECONDS = 10.0


def max_document_bytes() -> int:
    """Return the effective upload-size cap.

    Honors a lower ``MAX_UPLOAD_SIZE`` environment value but never lets a
    higher one raise the hard ``MAX_DOCUMENT_BYTES`` ceiling. Falls back to
    ``MAX_DOCUMENT_BYTES`` when the environment value is missing or invalid.
    """
    try:
        env_val = int(os.getenv("MAX_UPLOAD_SIZE", str(MAX_DOCUMENT_BYTES)))
    except (ValueError, TypeError):
        return MAX_DOCUMENT_BYTES
    return min(max(1, env_val), MAX_DOCUMENT_BYTES)

