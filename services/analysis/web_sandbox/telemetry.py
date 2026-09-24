"""Bounded telemetry types and sanitization for untrusted browser output."""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field
from typing import Any

_CONTROL_RE = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
_SECRET_KEY_RE = re.compile(r"(password|passwd|secret|token|authorization|cookie|set-cookie|api[_-]?key|csrf)", re.I)


def clean_text(value: Any, limit: int = 500) -> str:
    text = str(value or "")
    text = _CONTROL_RE.sub("", text)
    text = "".join(ch for ch in text if not unicodedata.category(ch).startswith("C"))
    return text[:limit]


def clean(value: Any, limit: int = 500) -> Any:
    if isinstance(value, str):
        return clean_text(value, limit)
    if isinstance(value, dict):
        return {clean_text(k, 80): clean(v, limit) for k, v in value.items()
                if not _SECRET_KEY_RE.search(str(k))}
    if isinstance(value, (list, tuple)):
        return [clean(v, limit) for v in value[:200]]
    if isinstance(value, (int, float, bool)) or value is None:
        return value
    return clean_text(value, limit)


@dataclass
class Telemetry:
    initial_url: str
    final_url: str = ""
    title: str = ""
    visible_text: str = ""
    visible_text_truncated: bool = False
    redirects: list[dict[str, Any]] = field(default_factory=list)
    requests: list[dict[str, Any]] = field(default_factory=list)
    forms: list[dict[str, Any]] = field(default_factory=list)
    downloads: list[dict[str, Any]] = field(default_factory=list)
    dialogs: int = 0
    dialog_types: list[str] = field(default_factory=list)
    popups: int = 0
    blocked: list[dict[str, Any]] = field(default_factory=list)
    screenshot_jpeg_b64: str | None = None
    page_blocks_automation: bool = False
    load_timeout: bool = False
    tls_error: bool = False
    post_attempts: int = 0
    total_bytes: int = 0

    def as_dict(self) -> dict[str, Any]:
        data = {
            "schema_version": "sandbox-1",
            "initial_url": clean_text(self.initial_url, 2048),
            "final_url": clean_text(self.final_url, 2048),
            "title": clean_text(self.title, 500),
            "visible_text": clean_text(self.visible_text, 20_000),
            "visible_text_untrusted": True,
            "visible_text_truncated": self.visible_text_truncated,
            "redirects": self.redirects[:20], "requests": self.requests[:200],
            "forms": self.forms[:100], "downloads": self.downloads[:50],
            "dialogs": self.dialogs, "dialog_types": self.dialog_types[:20],
            "popups": self.popups, "blocked": self.blocked[:100],
            "screenshot_jpeg_b64": self.screenshot_jpeg_b64,
            "page_blocks_automation": self.page_blocks_automation,
            "load_timeout": self.load_timeout, "tls_error": self.tls_error,
            "post_attempts": self.post_attempts, "total_bytes": self.total_bytes,
        }
        return clean(data, 20_000)


def bounded_telemetry(data: dict[str, Any]) -> dict[str, Any]:
    """Sanitize worker output before it crosses the process boundary."""
    out = clean(dict(data), 20_000)
    out["visible_text_untrusted"] = True
    out["requests"] = list(out.get("requests", []))[:200]
    out["redirects"] = list(out.get("redirects", []))[:20]
    out["forms"] = list(out.get("forms", []))[:100]
    out["downloads"] = list(out.get("downloads", []))[:50]
    return out
