"""SSRF guard. Any code that will fetch a user-supplied URL (web sandbox) MUST pass it through here.

Static URL analysis never fetches, so it does not need this; the sandbox does.
Re-validate again after DNS resolution and after every redirect hop (DNS-rebinding / redirect-to-private).
"""
from __future__ import annotations

import ipaddress
import re
import socket
from typing import Callable, Iterable
from urllib.parse import urlsplit

ALLOWED_SCHEMES = {"http", "https"}
BLOCKED_HOSTNAMES = {"localhost", "localhost.localdomain", "metadata.google.internal", "metadata", "instance-data"}
BLOCKED_SUFFIXES = (".local", ".localhost", ".internal", ".lan", ".home", ".corp", ".intranet", ".arpa")
_NUMERIC_HOST = re.compile(r"^(?:0x[0-9a-f]+|[0-9]+)(?:\.(?:0x[0-9a-f]+|[0-9]+)){0,3}$", re.I)

Resolver = Callable[[str], list[str]]


class UnsafeURL(ValueError):
    """Raised when a URL must not be fetched. Message is safe to show the user."""


def _default_resolver(host: str) -> list[str]:
    try:
        return sorted({ai[4][0] for ai in socket.getaddrinfo(host, None)})
    except socket.gaierror as e:
        raise UnsafeURL(f"hostname could not be resolved: {host}") from e


def _numeric_host_to_ip(host: str) -> ipaddress._BaseAddress | None:
    """Parse decimal/hex/octal/short IPv4 forms (2130706433, 0x7f.1, 127.1) that inet_aton accepts."""
    try:
        return ipaddress.ip_address(host)
    except ValueError:
        pass
    if _NUMERIC_HOST.match(host):
        try:
            return ipaddress.ip_address(socket.inet_ntoa(socket.inet_aton(host)))
        except OSError:
            return None
    return None


def _is_public(ip: ipaddress._BaseAddress) -> bool:
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return ip.is_global and not (ip.is_multicast or ip.is_loopback or ip.is_link_local or ip.is_private or ip.is_reserved or ip.is_unspecified)


def validate_public_url(url: str, resolver: Resolver | None = None,
                        allowed_test_hosts: Iterable[str] | None = None) -> str:
    """Return the URL unchanged if it is safe to fetch, else raise UnsafeURL."""
    resolver = resolver or _default_resolver
    if not isinstance(url, str) or not url.strip() or len(url) > 2048:
        raise UnsafeURL("URL is empty or too long")
    try:
        parts = urlsplit(url.strip())
        host = (parts.hostname or "").rstrip(".").lower()
        _ = parts.port  # raises ValueError on malformed port
    except ValueError as e:
        raise UnsafeURL("URL is malformed") from e
    if parts.scheme.lower() not in ALLOWED_SCHEMES:
        raise UnsafeURL(f"scheme '{parts.scheme}' is not allowed; only http/https")
    if parts.username is not None or parts.password is not None:
        raise UnsafeURL("URLs with embedded credentials are not fetched")
    if not host:
        raise UnsafeURL("URL has no hostname")
    if host in BLOCKED_HOSTNAMES or host.endswith(BLOCKED_SUFFIXES) or "." not in host and not _numeric_host_to_ip(host):
        raise UnsafeURL("internal hostnames are blocked")

    literal = _numeric_host_to_ip(host)
    addrs = [str(literal)] if literal else resolver(host)
    if not addrs:
        raise UnsafeURL("hostname resolved to no addresses")
    test_host = f"{host}:{parts.port or (443 if parts.scheme.lower() == 'https' else 80)}"
    allow_test_loopback = (test_host in set(allowed_test_hosts or ()) and
                           __import__("os").environ.get("TRUSTSHIELD_ENV", "").lower() == "test")
    for a in addrs:
        address = ipaddress.ip_address(a)
        if allow_test_loopback and address.is_loopback:
            continue
        if not _is_public(address):
            raise UnsafeURL("destination resolves to a private, loopback, link-local or reserved address")
    return url
