"""Text normalization applied before any rule or model sees user content."""
from __future__ import annotations

import re
import unicodedata

MAX_TEXT_CHARS = 20_000
_ZERO_WIDTH = dict.fromkeys(map(ord, "​‌‍⁠﻿­"))
_URL_RE = re.compile(
    r"(?i)\b(?:https?://|www\.)[^\s<>\"'()]+"
    r"|\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|in|co\.in|net|org|xyz|top|click|link|info|online|site|shop|club|vip|icu|cc|tk|ly|gl|me)(?:/[^\s<>\"'()]*)?"
)


class InvalidInput(ValueError):
    pass


def normalize_text(raw: str) -> str:
    """NFKC-normalize, drop zero-width/control characters, collapse whitespace."""
    if not isinstance(raw, str):
        raise InvalidInput("text must be a string")
    if len(raw) > MAX_TEXT_CHARS:
        raise InvalidInput(f"text exceeds {MAX_TEXT_CHARS} characters")
    text = unicodedata.normalize("NFKC", raw).translate(_ZERO_WIDTH)
    text = "".join(ch for ch in text if ch in "\n\t" or unicodedata.category(ch)[0] != "C")
    return re.sub(r"[ \t\r\f\v]+", " ", text).strip()


def extract_urls(text: str) -> list[str]:
    """Return de-duplicated URL-looking tokens, trailing punctuation stripped."""
    seen: list[str] = []
    for m in _URL_RE.finditer(text):
        u = m.group(0).rstrip(".,;:!?)")
        if u and u not in seen:
            seen.append(u)
    return seen
