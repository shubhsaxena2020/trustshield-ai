"""Bounded, environment-configurable web-sandbox policy."""
from __future__ import annotations

import os
from dataclasses import dataclass


def _number(name: str, default: float, low: float, high: float) -> float:
    try:
        value = float(os.getenv(name, default))
    except (TypeError, ValueError):
        value = default
    return max(low, min(high, value))


def _integer(name: str, default: int, low: int, high: int) -> int:
    return int(_number(name, default, low, high))


@dataclass(frozen=True)
class SandboxPolicy:
    total_timeout_s: float = 15.0
    navigation_timeout_ms: int = 10_000
    subresource_bytes: int = 5 * 1024 * 1024
    total_bytes: int = 25 * 1024 * 1024
    download_bytes: int = 10 * 1024 * 1024
    max_redirects: int = 5
    max_requests: int = 200
    visible_text_chars: int = 20_000
    screenshot_bytes: int = 1_500_000
    connection_timeout_s: float = 5.0
    idle_timeout_s: float = 5.0
    max_concurrency: int = 2
    allowed_ports: tuple[int, ...] = (80, 443)

    @classmethod
    def from_env(cls) -> "SandboxPolicy":
        return cls(
            total_timeout_s=_number("TRUSTSHIELD_SANDBOX_WALL_SECONDS", 15.0, 1.0, 60.0),
            navigation_timeout_ms=_integer("TRUSTSHIELD_SANDBOX_NAV_MS", 10_000, 500, 30_000),
            subresource_bytes=_integer("TRUSTSHIELD_SANDBOX_SUBRESOURCE_BYTES", 5 * 1024 * 1024, 1_024, 50 * 1024 * 1024),
            total_bytes=_integer("TRUSTSHIELD_SANDBOX_TOTAL_BYTES", 25 * 1024 * 1024, 4_096, 100 * 1024 * 1024),
            download_bytes=_integer("TRUSTSHIELD_SANDBOX_DOWNLOAD_BYTES", 10 * 1024 * 1024, 1_024, 50 * 1024 * 1024),
            max_redirects=_integer("TRUSTSHIELD_SANDBOX_MAX_REDIRECTS", 5, 0, 20),
            max_requests=_integer("TRUSTSHIELD_SANDBOX_MAX_REQUESTS", 200, 1, 1_000),
            visible_text_chars=_integer("TRUSTSHIELD_SANDBOX_TEXT_CHARS", 20_000, 100, 100_000),
            screenshot_bytes=_integer("TRUSTSHIELD_SANDBOX_SCREENSHOT_BYTES", 1_500_000, 10_000, 10_000_000),
            connection_timeout_s=_number("TRUSTSHIELD_SANDBOX_CONNECT_SECONDS", 5.0, 0.5, 20.0),
            idle_timeout_s=_number("TRUSTSHIELD_SANDBOX_IDLE_SECONDS", 5.0, 0.5, 20.0),
            max_concurrency=_integer("TRUSTSHIELD_SANDBOX_CONCURRENCY", 2, 1, 4),
        )


def feature_enabled() -> bool:
    return os.getenv("ENABLE_WEB_SANDBOX", "false").strip().lower() in {"1", "true", "yes", "on"}


def external_allowed() -> bool:
    return os.getenv("TRUSTSHIELD_SANDBOX_ALLOW_EXTERNAL", "false").strip().lower() in {"1", "true", "yes", "on"}
