import asyncio

import pytest

from services.analysis.web_sandbox.egress_proxy import ValidatingEgressProxy
from services.analysis.web_sandbox.network_guard import SandboxGuard
from services.analysis.web_sandbox.policy import SandboxPolicy


def _run(coro):
    return asyncio.run(coro)


async def _upstream(reader, writer):
    data = await reader.read(4096)
    if data.startswith(b"GET"):
        writer.write(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
        await writer.drain()
    writer.close()
    await writer.wait_closed()


def _auth(proxy, request: bytes) -> bytes:
    """Insert the per-job proxy credential before the blank line that ends the headers."""
    return request.replace(b"\r\n\r\n", b"\r\n" + proxy.auth_header + b"\r\n\r\n", 1)


def test_proxy_pins_first_dns_answer_and_does_not_resolve_again(monkeypatch):
    async def scenario():
        server = await asyncio.start_server(_upstream, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        answers = iter([["127.0.0.1"], ["10.0.0.1"]])
        calls = []

        def resolver(host):
            calls.append(host)
            return next(answers)

        monkeypatch.setenv("TRUSTSHIELD_ENV", "test")
        guard = SandboxGuard(resolver=resolver, test_allowlist=[f"public.test:{port}"])
        proxy = ValidatingEgressProxy(guard, SandboxPolicy(total_bytes=1024, subresource_bytes=1024, idle_timeout_s=1))
        host, proxy_port = await proxy.start()
        reader, writer = await asyncio.open_connection(host, proxy_port)
        writer.write(_auth(proxy, f"GET http://public.test:{port}/ HTTP/1.1\r\nHost: public.test\r\n\r\n".encode()))
        await writer.drain()
        data = await reader.read(4096)
        writer.close()
        await writer.wait_closed()
        await proxy.close()
        server.close()
        await server.wait_closed()
        assert b"200 OK" in data
        assert calls == ["public.test"]

    _run(scenario())


def test_proxy_rejects_private_target_and_blocked_connect_port(monkeypatch):
    async def scenario():
        monkeypatch.delenv("TRUSTSHIELD_ENV", raising=False)
        guard = SandboxGuard(resolver=lambda host: ["10.0.0.1"])
        proxy = ValidatingEgressProxy(guard, SandboxPolicy(idle_timeout_s=1))
        host, port = await proxy.start()
        reader, writer = await asyncio.open_connection(host, port)
        writer.write(_auth(proxy, b"GET http://private.example/ HTTP/1.1\r\nHost: private.example\r\n\r\n"))
        await writer.drain()
        assert b"403" in await reader.read(1024)
        writer.close(); await writer.wait_closed()
        await proxy.close()

        public_guard = SandboxGuard(resolver=lambda host: ["93.184.216.34"], allow_external=True)
        proxy = ValidatingEgressProxy(public_guard, SandboxPolicy(idle_timeout_s=1))
        host, port = await proxy.start()
        reader, writer = await asyncio.open_connection(host, port)
        writer.write(_auth(proxy, b"CONNECT example.com:22 HTTP/1.1\r\nHost: example.com\r\n\r\n"))
        await writer.drain()
        assert b"403" in await reader.read(1024)
        writer.close(); await writer.wait_closed()
        await proxy.close()

    _run(scenario())


def test_proxy_enforces_byte_and_idle_caps(monkeypatch):
    async def scenario():
        async def huge(reader, writer):
            await reader.read(4096)
            writer.write(b"HTTP/1.1 200 OK\r\nContent-Length: 20\r\n\r\n" + b"x" * 20)
            await writer.drain(); await asyncio.sleep(0.05); writer.close()

        server = await asyncio.start_server(huge, "127.0.0.1", 0)
        upstream_port = server.sockets[0].getsockname()[1]
        monkeypatch.setenv("TRUSTSHIELD_ENV", "test")
        guard = SandboxGuard(resolver=lambda host: ["127.0.0.1"], test_allowlist=[f"public.test:{upstream_port}"])
        proxy = ValidatingEgressProxy(guard, SandboxPolicy(total_bytes=8, subresource_bytes=8, idle_timeout_s=0.2))
        host, proxy_port = await proxy.start()
        reader, writer = await asyncio.open_connection(host, proxy_port)
        writer.write(_auth(proxy, f"GET http://public.test:{upstream_port}/ HTTP/1.1\r\nHost: public.test\r\n\r\n".encode())); await writer.drain()
        data = await reader.read(4096)
        assert len(data) <= 8
        writer.close(); await writer.wait_closed()
        await proxy.close(); server.close(); await server.wait_closed()

    _run(scenario())


def test_proxy_refuses_connect_on_port_80(monkeypatch):
    async def scenario():
        monkeypatch.delenv("TRUSTSHIELD_ENV", raising=False)
        guard = SandboxGuard(resolver=lambda host: ["93.184.216.34"], allow_external=True)
        proxy = ValidatingEgressProxy(guard, SandboxPolicy(idle_timeout_s=1))
        host, port = await proxy.start()
        reader, writer = await asyncio.open_connection(host, port)
        writer.write(_auth(proxy, b"CONNECT example.com:80 HTTP/1.1\r\nHost: example.com\r\n\r\n"))
        await writer.drain()
        assert b"403" in await reader.read(1024)
        writer.close(); await writer.wait_closed()
        await proxy.close()

    _run(scenario())


def test_proxy_requires_credentials_and_never_dials_upstream(monkeypatch):
    async def scenario():
        monkeypatch.setenv("TRUSTSHIELD_ENV", "test")
        dialed = []

        def resolver(host):
            dialed.append(host)
            return ["127.0.0.1"]

        guard = SandboxGuard(resolver=resolver, test_allowlist=["public.test:80"])
        proxy = ValidatingEgressProxy(guard, SandboxPolicy(idle_timeout_s=1))
        host, port = await proxy.start()
        for extra in (b"", b"Proxy-Authorization: Basic d3Jvbmc6Y3JlZHM=\r\n"):
            reader, writer = await asyncio.open_connection(host, port)
            writer.write(b"GET http://public.test:80/ HTTP/1.1\r\nHost: public.test\r\n" + extra + b"\r\n")
            await writer.drain()
            assert b"407" in await reader.read(1024)
            writer.close(); await writer.wait_closed()
        await proxy.close()
        assert dialed == []
        assert len(proxy.password) >= 24

    _run(scenario())
