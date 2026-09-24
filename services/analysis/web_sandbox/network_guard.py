"""URL and DNS policy shared by the worker and validating egress proxy."""
from __future__ import annotations

import ipaddress
import os
from dataclasses import dataclass
from typing import Callable, Iterable
from urllib.parse import urlsplit

from services.analysis.url_analysis.ssrf import UnsafeURL, validate_public_url

Resolver = Callable[[str], list[str]]


def _idna_host(host: str) -> str:
    host = (host or "").rstrip(".").lower()
    try:
        return host.encode("idna").decode("ascii").lower()
    except UnicodeError as exc:
        raise UnsafeURL("hostname is not valid IDN") from exc


def _port(parts) -> int:
    try:
        return parts.port or (443 if parts.scheme.lower() == "https" else 80)
    except ValueError as exc:
        raise UnsafeURL("URL is malformed") from exc


@dataclass(frozen=True)
class ValidatedTarget:
    url: str
    scheme: str
    hostname: str
    port: int
    addresses: tuple[str, ...]


class SandboxGuard:
    """Validate every destination before a socket is opened.

    ``test_allowlist`` is intentionally constructor-only and is accepted only
    in ``TRUSTSHIELD_ENV=test``. The public API never exposes this parameter.
    """

    def __init__(self, resolver: Resolver | None = None, *,
                 test_allowlist: Iterable[str] | None = None,
                 production: bool = False,
                 allow_external: bool | None = None,
                 allowed_ports: Iterable[int] = (80, 443)):
        self.resolver = resolver
        self.production = production
        self.allow_external = (os.getenv("TRUSTSHIELD_SANDBOX_ALLOW_EXTERNAL", "false").lower() in {"1", "true", "yes", "on"}
                               if allow_external is None else bool(allow_external))
        self.allowed_ports = frozenset(int(p) for p in allowed_ports)
        self.test_allowlist = frozenset(test_allowlist or ()) if (not production and os.getenv("TRUSTSHIELD_ENV", "").lower() == "test") else frozenset()

    @property
    def test_mode(self) -> bool:
        return bool(self.test_allowlist) and not self.production and os.getenv("TRUSTSHIELD_ENV", "").lower() == "test"

    def validate(self, url: str) -> ValidatedTarget:
        try:
            parts = urlsplit(url.strip())
            hostname = _idna_host(parts.hostname or "")
            port = _port(parts)
        except (AttributeError, ValueError) as exc:
            raise UnsafeURL("URL is malformed") from exc
        if port not in self.allowed_ports and f"{hostname}:{port}" not in self.test_allowlist:
            raise UnsafeURL("destination port is not allowed")
        if not self.test_mode and not self.allow_external:
            raise UnsafeURL("external browsing is disabled by policy")
        if parts.username is not None or parts.password is not None:
            raise UnsafeURL("URLs with embedded credentials are not fetched")
        # Rebuild with an ASCII hostname so the resolver and Chromium see the
        # same canonical destination. Existing SSRF behavior remains unchanged
        # because this extension is only used by the sandbox.
        canonical = url
        if parts.hostname != hostname:
            netloc = hostname
            if parts.port is not None:
                netloc = f"{netloc}:{parts.port}"
            canonical = parts._replace(netloc=netloc).geturl()
        literal = None
        try:
            literal = ipaddress.ip_address(hostname)
        except ValueError:
            pass
        if literal is not None:
            resolved = [str(literal)]
        else:
            resolved = list(self.resolver(hostname) if self.resolver else self._resolve(hostname))
        # Pass the already-resolved set into the existing guard. This is the
        # rebinding defence: validation and connection use one DNS answer.
        value = validate_public_url(canonical, resolver=lambda _host: resolved,
                                    allowed_test_hosts=self.test_allowlist)
        addrs = tuple(str(a) for a in resolved)
        if not addrs:
            raise UnsafeURL("hostname resolved to no addresses")
        for raw in addrs:
            address = ipaddress.ip_address(raw)
            if self.test_mode and f"{hostname}:{port}" in self.test_allowlist and address.is_loopback:
                continue
            if not address.is_global or address.is_multicast or address.is_loopback or address.is_link_local or address.is_private or address.is_reserved or address.is_unspecified:
                raise UnsafeURL("destination resolves to a blocked address")
        return ValidatedTarget(value, parts.scheme.lower(), hostname, port, addrs)

    @staticmethod
    def _resolve(hostname: str) -> list[str]:
        from services.analysis.url_analysis.ssrf import _default_resolver
        return _default_resolver(hostname)

    def validate_request(self, url: str, method: str = "GET") -> ValidatedTarget:
        if method.upper() not in {"GET", "HEAD"}:
            raise UnsafeURL("only GET and HEAD requests are allowed")
        parts = urlsplit(url)
        if parts.scheme.lower() not in {"http", "https"}:
            raise UnsafeURL("only http/https requests are allowed")
        return self.validate(url)
