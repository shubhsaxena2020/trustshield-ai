"""Integrity for the client-held conversation context.

The server keeps no conversation, so the browser sends the previous analysis back with a signature the server issued. A context
whose signature does not verify is ignored (the message is then analysed as new content), so a caller cannot forge risk, evidence or
capability fields and have follow-up answers built on them. Set TRUSTSHIELD_CONTEXT_SECRET so signatures verify across instances;
without it each process uses a random key and a context issued by another instance is simply ignored.
"""
from __future__ import annotations

import hashlib
import hmac
import os
import secrets

from services.chat.contract import ChatAnalysis

_PROCESS_KEY = secrets.token_bytes(32)


def _key() -> bytes:
    configured = os.environ.get("TRUSTSHIELD_CONTEXT_SECRET", "")
    return configured.encode() if configured else _PROCESS_KEY


def sign(analysis: ChatAnalysis) -> str:
    return hmac.new(_key(), analysis.model_dump_json().encode(), hashlib.sha256).hexdigest()


def verify(analysis: ChatAnalysis, signature: str) -> bool:
    return bool(signature) and hmac.compare_digest(sign(analysis), signature)
