"""ASGI hardening: request IDs, security headers, structured access logs, body-size cap, rate limiting, bounded result store.

Nothing here logs request bodies, uploaded content, query strings or headers other than the request id.
"""
from __future__ import annotations

import json
import logging
import re
import threading
import time
import uuid
from collections import OrderedDict
from typing import Any

from starlette.datastructures import MutableHeaders

from .settings import Settings

access_log = logging.getLogger("trustshield.access")
event_log = logging.getLogger("trustshield.events")
_REQUEST_ID_RE = re.compile(r"^[A-Za-z0-9._-]{8,64}$")


def security_headers(settings: Settings) -> dict[str, str]:
    headers = {
        "X-Content-Type-Options": "nosniff",
        "X-Frame-Options": "DENY",
        "Referrer-Policy": "no-referrer",
        "Cache-Control": "no-store",
        "Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'",
        "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
        "Cross-Origin-Resource-Policy": "cross-origin",
    }
    if settings.strict:
        headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"
    return headers


def _json_response_parts(status: int, payload: dict[str, Any], extra: list[tuple[bytes, bytes]] | None = None):
    body = json.dumps(payload).encode()
    headers = [(b"content-type", b"application/json"), (b"content-length", str(len(body)).encode())] + (extra or [])
    return {"type": "http.response.start", "status": status, "headers": headers}, {"type": "http.response.body", "body": body}


class ObservabilityMiddleware:
    """Request ID + security headers + one structured access-log line; converts any unhandled error to a safe 500."""

    def __init__(self, app, settings: Settings):
        self.app, self.settings = app, settings

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        incoming = next((v.decode("latin-1") for k, v in scope["headers"] if k == b"x-request-id"), "")
        request_id = incoming if _REQUEST_ID_RE.match(incoming) else uuid.uuid4().hex
        scope.setdefault("state", {})["request_id"] = request_id
        started = time.perf_counter()
        info: dict[str, Any] = {"status": 500, "analysis_id": None, "response_started": False}
        extra = security_headers(self.settings)

        async def send_wrapper(message):
            if message["type"] == "http.response.start":
                info["status"], info["response_started"] = message["status"], True
                headers = MutableHeaders(scope=message)
                headers["X-Request-ID"] = request_id
                for key, value in extra.items():
                    headers.setdefault(key, value)
                info["analysis_id"] = headers.get("x-analysis-id")
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        except Exception as exc:  # never leak internals; log the exception class only (messages can contain user text)
            event_log.error(json.dumps({"event": "unhandled_error", "request_id": request_id, "error_type": type(exc).__name__}))
            if not info["response_started"]:
                start, body = _json_response_parts(500, {"detail": "Internal error", "request_id": request_id})
                await send_wrapper(start)
                await send_wrapper(body)
        finally:
            access_log.info(json.dumps({
                "event": "request", "request_id": request_id, "method": scope["method"], "path": scope["path"],
                "status": info["status"], "duration_ms": round((time.perf_counter() - started) * 1000, 1),
                "analysis_id": info["analysis_id"]}))


class _BodyTooLarge(Exception):
    pass


class BodyLimitMiddleware:
    """Reject oversize bodies before they are parsed or spooled to disk (Content-Length and streamed bytes)."""

    def __init__(self, app, max_bytes: int):
        self.app, self.max_bytes = app, max_bytes

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope["method"] not in {"POST", "PUT", "PATCH"}:
            return await self.app(scope, receive, send)
        declared = next((v for k, v in scope["headers"] if k == b"content-length"), None)
        if declared is not None and declared.isdigit() and int(declared) > self.max_bytes:
            start, body = _json_response_parts(413, {"detail": "Request body too large"})
            await send(start)
            await send(body)
            return
        seen = 0
        started = False
        exceeded = False
        suppress = False

        async def limited_receive():
            nonlocal seen, exceeded
            message = await receive()
            if message["type"] == "http.request":
                seen += len(message.get("body", b""))
                if seen > self.max_bytes:
                    exceeded = True
                    raise _BodyTooLarge()
            return message

        async def tracking_send(message):
            nonlocal started, suppress
            if suppress:
                return
            if message["type"] == "http.response.start":
                if exceeded and not started:
                    # A parser downstream may have turned the aborted read into a 400; the real reason is size.
                    start, body = _json_response_parts(413, {"detail": "Request body too large"})
                    started = suppress = True
                    await send(start)
                    await send(body)
                    return
                started = True
            await send(message)

        try:
            await self.app(scope, limited_receive, tracking_send)
        except _BodyTooLarge:
            if not started:
                start, body = _json_response_parts(413, {"detail": "Request body too large"})
                await send(start)
                await send(body)


class RateLimiter:
    """Thread-safe token bucket per client key, memory-bounded."""

    def __init__(self, per_minute: int, burst: int | None = None, max_keys: int = 5000):
        self.rate = per_minute / 60.0
        self.capacity = float(burst if burst is not None else max(1, per_minute // 3))
        self.max_keys = max_keys
        self._buckets: "OrderedDict[str, tuple[float, float]]" = OrderedDict()
        self._lock = threading.Lock()

    def allow(self, key: str) -> tuple[bool, float]:
        """Returns (allowed, retry_after_seconds)."""
        now = time.monotonic()
        with self._lock:
            stamp, tokens = self._buckets.pop(key, (now, self.capacity))
            tokens = min(self.capacity, tokens + (now - stamp) * self.rate)
            allowed = tokens >= 1.0
            if allowed:
                tokens -= 1.0
            self._buckets[key] = (now, tokens)
            while len(self._buckets) > self.max_keys:
                self._buckets.popitem(last=False)
            return allowed, 0.0 if allowed else (1.0 - tokens) / self.rate


def client_key(scope_client: tuple[str, int] | None, forwarded_for: str | None, hops: int) -> str:
    """Client identity. With N trusted proxy hops the client is the Nth-from-right X-Forwarded-For entry (spoof-resistant)."""
    if hops > 0 and forwarded_for:
        parts = [item.strip() for item in forwarded_for.split(",") if item.strip()]
        if len(parts) >= hops:
            return parts[-hops][:64]
    return scope_client[0] if scope_client else "unknown"


class ResultStore:
    """Bounded, TTL-evicting, in-memory analysis store. Stateless deployment: a restart clears everything."""

    def __init__(self, settings: Settings):
        self.settings = settings
        self._items: "OrderedDict[str, tuple[float, Any]]" = OrderedDict()
        self._lock = threading.Lock()

    def _evict(self, now: float) -> None:
        cutoff = now - self.settings.store_ttl_s
        while self._items:
            key, (stamp, _) = next(iter(self._items.items()))
            if stamp < cutoff or len(self._items) > self.settings.store_max:
                self._items.popitem(last=False)
            else:
                break

    def put(self, result, *, retain: bool = True) -> None:
        """`retain=False` results (everything but sandbox jobs under sandbox_only) are never stored."""
        if not retain and self.settings.store_policy == "sandbox_only":
            return
        now = time.monotonic()
        with self._lock:
            self._items[result.analysis_id] = (now, result)
            self._items.move_to_end(result.analysis_id)
            self._evict(now)

    def get(self, analysis_id: str):
        now = time.monotonic()
        with self._lock:
            self._evict(now)
            entry = self._items.get(analysis_id)
            return entry[1] if entry else None

    def __len__(self) -> int:
        with self._lock:
            return len(self._items)
