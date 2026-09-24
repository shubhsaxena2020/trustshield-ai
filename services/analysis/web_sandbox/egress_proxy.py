"""Small validating HTTP forward proxy used as Chromium's only egress."""
from __future__ import annotations

import asyncio
import base64
import hmac
import re
import secrets
from dataclasses import dataclass
from urllib.parse import urlsplit

from services.analysis.url_analysis.ssrf import UnsafeURL
from .network_guard import SandboxGuard, ValidatedTarget
from .policy import SandboxPolicy

_HEADER_LIMIT = 64 * 1024
_CONNECT_RE = re.compile(r"^CONNECT\s+([^\s]+)\s+HTTP/", re.I)


class ProxyRejected(Exception):
    pass


class ProxyAuthRequired(Exception):
    pass


@dataclass
class ByteBudget:
    total: int
    used: int = 0

    def take(self, count: int, cap: int) -> bool:
        if count > cap or self.used + count > self.total:
            return False
        self.used += count
        return True


class ValidatingEgressProxy:
    def __init__(self, guard: SandboxGuard, policy: SandboxPolicy | None = None):
        self.guard = guard
        self.policy = policy or SandboxPolicy.from_env()
        self.budget = ByteBudget(self.policy.total_bytes)
        self.server: asyncio.AbstractServer | None = None
        # Per-job credential: other local processes cannot use this loopback port.
        self.username = "trustshield"
        self.password = secrets.token_urlsafe(24)

    @property
    def auth_header(self) -> bytes:
        return b"Proxy-Authorization: Basic " + base64.b64encode(f"{self.username}:{self.password}".encode())

    def _authorized(self, header: bytes) -> bool:
        expected = base64.b64encode(f"{self.username}:{self.password}".encode())
        for line in header.split(b"\r\n")[1:]:
            name, _, value = line.partition(b":")
            if name.strip().lower() == b"proxy-authorization":
                scheme, _, credential = value.strip().partition(b" ")
                return scheme.lower() == b"basic" and hmac.compare_digest(credential.strip(), expected)
        return False

    async def start(self) -> tuple[str, int]:
        self.server = await asyncio.start_server(self._handle, "127.0.0.1", 0)
        host, port = self.server.sockets[0].getsockname()[:2]
        return host, port

    async def close(self) -> None:
        if self.server is not None:
            self.server.close()
            await self.server.wait_closed()

    async def _handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        upstream: tuple[asyncio.StreamReader, asyncio.StreamWriter] | None = None
        try:
            header = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), self.policy.idle_timeout_s)
            if len(header) > _HEADER_LIMIT:
                raise ProxyRejected("headers too large")
            if not self._authorized(header):
                raise ProxyAuthRequired()
            first = header.split(b"\r\n", 1)[0].decode("latin-1", "replace")
            match = _CONNECT_RE.match(first)
            if match:
                upstream = await self._connect_tunnel(match.group(1))
                writer.write(b"HTTP/1.1 200 Connection Established\r\n\r\n")
                await writer.drain()
                await self._relay(reader, writer, upstream)
                return
            method, target, _ = first.split(" ", 2)
            validated = self.guard.validate_request(target, method)
            if validated.scheme != "http":
                raise ProxyRejected("HTTPS must use CONNECT")
            upstream = await self._open_pinned(validated)
            rewritten = self._origin_form_request(header, validated)
            upstream[1].write(rewritten)
            await upstream[1].drain()
            await self._relay(upstream[0], writer, None)
        except ProxyAuthRequired:
            try:
                writer.write(b'HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm="trustshield"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n')
                await writer.drain()
            except (OSError, ConnectionError):
                pass
        except (asyncio.IncompleteReadError, asyncio.TimeoutError, OSError, ValueError, UnsafeURL, ProxyRejected):
            try:
                writer.write(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                await writer.drain()
            except (OSError, ConnectionError):
                pass
        finally:
            if upstream is not None:
                upstream[1].close()
                await upstream[1].wait_closed()
            writer.close()
            await writer.wait_closed()

    async def _open_pinned(self, target: ValidatedTarget) -> tuple[asyncio.StreamReader, asyncio.StreamWriter]:
        # The resolver has already returned every validated address. Connect
        # directly to that address; open_connection never receives the hostname.
        return await asyncio.wait_for(
            asyncio.open_connection(target.addresses[0], target.port),
            self.policy.connection_timeout_s,
        )

    async def _connect_tunnel(self, authority: str) -> tuple[asyncio.StreamReader, asyncio.StreamWriter]:
        host, sep, port_text = authority.rpartition(":")
        if not sep:
            raise ProxyRejected("CONNECT authority must include a port")
        host = host.strip("[]")
        port = int(port_text)
        if port != 443:
            raise ProxyRejected("CONNECT tunneling is permitted only on port 443")
        target = self.guard.validate(f"https://{host}:{port}/")
        return await self._open_pinned(target)

    @staticmethod
    def _origin_form_request(header: bytes, target: ValidatedTarget) -> bytes:
        lines = header.split(b"\r\n")
        method, _, _ = lines[0].decode("latin-1").split(" ", 2)
        origin = urlsplit(target.url).path or "/"
        if urlsplit(target.url).query:
            origin += "?" + urlsplit(target.url).query
        out = [f"{method} {origin} HTTP/1.1".encode("latin-1")]
        has_host = False
        for line in lines[1:]:
            if not line:
                continue
            name = line.split(b":", 1)[0].lower()
            if name.startswith(b"proxy-"):
                continue
            if name == b"host":
                has_host = True
            out.append(line)
        if not has_host:
            out.append(f"Host: {target.hostname}".encode("latin-1"))
        out.append(b"")
        out.append(b"")
        return b"\r\n".join(out)

    async def _relay(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter,
                     second: tuple[asyncio.StreamReader, asyncio.StreamWriter] | None) -> None:
        connection_used = 0

        async def send_chunk(data: bytes, destination: asyncio.StreamWriter) -> bool:
            nonlocal connection_used
            if connection_used + len(data) > self.policy.subresource_bytes:
                return False
            if not self.budget.take(len(data), self.policy.subresource_bytes):
                return False
            connection_used += len(data)
            destination.write(data)
            await destination.drain()
            return True

        if second is None:
            while True:
                data = await asyncio.wait_for(reader.read(64 * 1024), self.policy.idle_timeout_s)
                if not data:
                    break
                if not await send_chunk(data, writer):
                    break
            return

        async def one_way(src, dst):
            while True:
                data = await asyncio.wait_for(src.read(64 * 1024), self.policy.idle_timeout_s)
                if not data:
                    break
                if not await send_chunk(data, dst):
                    break

        await asyncio.gather(one_way(reader, second[1]), one_way(second[0], writer), return_exceptions=True)
