"""Environment-driven API settings. No secrets live here; nothing is read from the request."""
from __future__ import annotations

import os
from dataclasses import dataclass
from urllib.parse import urlsplit

ENVIRONMENTS = ("development", "staging", "production", "test")
TRUTHY = {"1", "true", "yes", "on"}


def _int(name: str, default: int, low: int, high: int) -> int:
    try:
        value = int(os.getenv(name, default))
    except (TypeError, ValueError):
        value = default
    return max(low, min(high, value))


def _float(name: str, default: float, low: float, high: float) -> float:
    try:
        value = float(os.getenv(name, default))
    except (TypeError, ValueError):
        value = default
    return max(low, min(high, value))


@dataclass(frozen=True)
class Settings:
    env: str = "development"
    cors_origins: tuple[str, ...] = ("http://localhost:3000",)
    public_api_url: str = ""
    max_body_bytes: int = 5 * 1024 * 1024 + 64 * 1024
    request_timeout_s: float = 45.0
    heavy_concurrency: int = 2
    heavy_queue_timeout_s: float = 8.0
    rate_light_per_min: int = 120
    rate_heavy_per_min: int = 30
    trusted_proxy_hops: int = 0
    store_policy: str = "all"  # "all" (development) or "sandbox_only" (retain nothing but jobs that must be polled)
    store_ttl_s: float = 900.0
    store_max: int = 200

    @property
    def strict(self) -> bool:
        """Staging and production run the same hardened profile."""
        return self.env in {"staging", "production"}

    @property
    def docs_enabled(self) -> bool:
        return not self.strict

    @classmethod
    def from_env(cls) -> "Settings":
        env = os.getenv("TRUSTSHIELD_ENV", "development").strip().lower() or "development"
        origins_raw = os.getenv("CORS_ALLOWED_ORIGINS")
        if origins_raw is None:
            origins = ("http://localhost:3000",) if env in {"development", "test"} else ()
        else:
            origins = tuple(item.strip().rstrip("/") for item in origins_raw.split(",") if item.strip())
        try:
            upload = int(os.getenv("MAX_UPLOAD_SIZE", 5 * 1024 * 1024))
        except ValueError:
            upload = 5 * 1024 * 1024
        strict = env in {"staging", "production"}
        return cls(
            env=env,
            cors_origins=origins,
            public_api_url=os.getenv("PUBLIC_API_URL", "").strip().rstrip("/"),
            max_body_bytes=max(upload, 1024) + 64 * 1024,
            request_timeout_s=_float("TRUSTSHIELD_REQUEST_TIMEOUT", 45.0, 5.0, 120.0),
            heavy_concurrency=_int("TRUSTSHIELD_HEAVY_CONCURRENCY", 2, 1, 8),
            heavy_queue_timeout_s=_float("TRUSTSHIELD_HEAVY_QUEUE_TIMEOUT", 8.0, 0.5, 60.0),
            rate_light_per_min=_int("TRUSTSHIELD_RATE_LIGHT_PER_MIN", 120, 1, 10_000),
            rate_heavy_per_min=_int("TRUSTSHIELD_RATE_HEAVY_PER_MIN", 30, 1, 10_000),
            trusted_proxy_hops=_int("TRUSTSHIELD_TRUSTED_PROXY_HOPS", 0, 0, 5),
            store_policy=os.getenv("TRUSTSHIELD_RESULT_STORE", "sandbox_only" if strict else "all").strip().lower(),
            store_ttl_s=_float("TRUSTSHIELD_RESULT_TTL", 900.0, 30.0, 86_400.0),
            store_max=_int("TRUSTSHIELD_RESULT_MAX", 200, 10, 5_000),
        )

    def problems(self) -> list[str]:
        """Configuration errors that must stop a staging/production start."""
        found: list[str] = []
        if self.env not in ENVIRONMENTS:
            found.append(f"TRUSTSHIELD_ENV must be one of {', '.join(ENVIRONMENTS)} (got {self.env!r})")
        if self.strict:
            # An empty list is valid and safest: same-origin deployments (Vercel Services: frontend and API share one origin) need no CORS.
            for origin in self.cors_origins:
                parts = urlsplit(origin)
                if origin == "*" or "*" in origin:
                    found.append("CORS_ALLOWED_ORIGINS must not contain wildcards")
                elif parts.scheme != "https" or not parts.netloc or parts.path not in {"", "/"}:
                    found.append(f"CORS origin {origin!r} must be a bare https origin")
                elif parts.hostname in {"localhost", "127.0.0.1"}:
                    found.append(f"CORS origin {origin!r} must not be a localhost origin")
            if os.getenv("TRUSTSHIELD_ML", "on").strip().lower() == "off":
                found.append("TRUSTSHIELD_ML=off is not allowed in staging/production")
            if os.getenv("TRUSTSHIELD_OCR_PROVIDER", "tesseract").strip().lower() == "mock":
                found.append("the mock OCR provider is demo/test only and not allowed in staging/production")
            if os.getenv("ENABLE_WEB_SANDBOX", "false").strip().lower() in TRUTHY:
                found.append("ENABLE_WEB_SANDBOX must be false for this deployment")
            if os.getenv("TRUSTSHIELD_SANDBOX_ALLOW_EXTERNAL", "false").strip().lower() in TRUTHY:
                found.append("TRUSTSHIELD_SANDBOX_ALLOW_EXTERNAL must be false")
            if os.getenv("ENABLE_EXTERNAL_REPUTATION", "false").strip().lower() in TRUTHY:
                found.append("ENABLE_EXTERNAL_REPUTATION is not implemented and must be false")
            override = os.getenv("TRUSTSHIELD_ML_MODEL", "").strip()
            rollback_ok = override == "trustshield_text_v1" and os.getenv("TRUSTSHIELD_ROLLBACK_CONFIRM", "") == "v1"
            if override not in {"", "trustshield_text_v3"} and not rollback_ok:
                found.append("TRUSTSHIELD_ML_MODEL must be unset or trustshield_text_v3 (rollback to v1 additionally needs TRUSTSHIELD_ROLLBACK_CONFIRM=v1)")
            if self.store_policy != "sandbox_only":
                found.append("TRUSTSHIELD_RESULT_STORE must be sandbox_only (retain minimal user content)")
        return found
