"""Deterministic input router. No language model decides what a message is.

Routing rules (also documented in docs/CONVERSATIONAL_ARCHITECTURE.md):

1. A follow-up is a short, question-like message that matches a known follow-up intent, has NO attachment and NO URL, and is
   sent with an existing analysis context. Everything else is new content and is analysed.
2. An attachment is always analysed as an image: QR decoding and OCR both run; their evidence is combined.
   The accompanying message is treated as the user's QUESTION (not scored as message content); URLs inside it are still
   checked structurally.
3. A message that is only a URL (optionally quoted or in angle brackets) is a URL analysis.
4. Any other message is analysed as text; URLs inside it are extracted and analysed statically as part of that text analysis
   (a question wrapped around a link is therefore "text + URL").
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Literal

from services.analysis.normalization.text import extract_urls
from services.chat.intent import FOLLOWUP_MAX_CHARS, FOLLOWUP_MAX_WORDS

Kind = Literal["followup", "url", "text", "image", "empty"]
MAX_MESSAGE_CHARS = 5000

_BARE_URL = re.compile(r"^[\s<\"'(\[]*((?:https?://|www\.)\S+|[a-z0-9-]+(?:\.[a-z0-9-]+)+(?:/\S*)?)[\s>\"')\].,;!?]*$", re.I)


@dataclass
class RoutePlan:
    kind: Kind
    reason: str
    urls: list[str] = field(default_factory=list)
    intent: str | None = None
    has_attachment: bool = False
    modules: list[str] = field(default_factory=list)

    def public(self) -> dict:
        return {"kind": self.kind, "reason": self.reason, "urls": self.urls[:10], "intent": self.intent,
                "has_attachment": self.has_attachment, "modules": self.modules}


def route(message: str, has_attachment: bool, has_context: bool, followup_intent) -> RoutePlan:
    """`followup_intent(message) -> str | None` is injected to keep this module free of answer logic."""
    text = (message or "").strip()[:MAX_MESSAGE_CHARS]
    urls = extract_urls(text)
    if has_attachment:
        return RoutePlan("image", "attachment present: QR decoding and OCR both run; the message is the user's question",
                         urls=urls, has_attachment=True, modules=["qr", "ocr", "text_rules", "ml", "url_static"])
    if not text:
        return RoutePlan("empty", "nothing to analyse")
    if has_context and not urls and len(text) <= FOLLOWUP_MAX_CHARS and len(text.split()) <= FOLLOWUP_MAX_WORDS:
        intent = followup_intent(text)
        if intent:
            return RoutePlan("followup", f"short question matching follow-up intent '{intent}' with an analysis in context", intent=intent)
    if _BARE_URL.match(text) and len(urls) <= 1 and not re.search(r"\s", text.strip("<>\"'() ")):
        return RoutePlan("url", "message is a single URL", urls=urls or [text.strip("<>\"'() ")], modules=["url_static"])
    modules = ["text_rules", "ml"] + (["url_static"] if urls else [])
    reason = "message contains text and a URL: analysed as text, with the URL checked structurally" if urls else "plain text"
    return RoutePlan("text", reason, urls=urls, modules=modules)
