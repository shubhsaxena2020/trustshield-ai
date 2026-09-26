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
_ALNUM_TOKEN_RE = re.compile(r"[^\W_]+", re.UNICODE)


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


def alphanumeric_token_count(text: str) -> int:
    """Count runs of letters/digits used to decide whether text is analyzable."""
    return len(_ALNUM_TOKEN_RE.findall(text))


def text_context_quality(text: str, full_context_tokens: int = 8) -> float:
    """Return a context factor that reaches 1.0 at eight alphanumeric tokens."""
    if full_context_tokens <= 0:
        return 1.0
    return min(1.0, alphanumeric_token_count(text) / full_context_tokens)


def extract_urls(text: str) -> list[str]:
    """Return de-duplicated URL-looking tokens, trailing punctuation stripped."""
    seen: list[str] = []
    for m in _URL_RE.finditer(text):
        u = m.group(0).rstrip(".,;:!?)")
        if u and u not in seen:
            seen.append(u)
    return seen
