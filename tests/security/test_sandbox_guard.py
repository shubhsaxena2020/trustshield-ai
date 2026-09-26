import ipaddress

import pytest

from services.analysis.url_analysis.ssrf import UnsafeURL
from services.analysis.web_sandbox.network_guard import SandboxGuard


PUBLIC = lambda host: ["93.184.216.34"]


@pytest.mark.parametrize("url", [
    "http://localhost/", "http://127.0.0.1/", "http://127.1/", "http://2130706433/",
    "http://0x7f000001/", "http://[::1]/", "http://[fc00::1]/", "http://[fe80::1]/",
    "http://[::ffff:127.0.0.1]/", "http://10.0.0.1/", "http://172.16.0.1/",
    "http://192.168.1.1/", "http://100.64.0.1/", "http://169.254.169.254/",
    "http://224.0.0.1/", "http://255.255.255.255/", "http://[ff02::1]/",
    "http://metadata.google.internal/", "http://printer.local/", "http://intranet/",
    "file:///etc/passwd", "ftp://example.com/", "http://user:pw@example.com/",
    "http://example.com:22/", "http://example.com:8080/",
])
def test_sandbox_guard_blocks_private_numeric_credentials_and_ports(url):
    with pytest.raises(UnsafeURL):
        SandboxGuard(resolver=PUBLIC, allow_external=True).validate(url)


def test_dns_returning_mixed_public_and_private_is_rejected():
    with pytest.raises(UnsafeURL):
        SandboxGuard(resolver=lambda host: ["93.184.216.34", "10.0.0.1"]).validate("https://evil.example/")


def test_external_browsing_is_disabled_by_default(monkeypatch):
    monkeypatch.delenv("TRUSTSHIELD_SANDBOX_ALLOW_EXTERNAL", raising=False)
    with pytest.raises(UnsafeURL):
        SandboxGuard(resolver=PUBLIC).validate("https://example.com/")


def test_uppercase_trailing_dot_and_idn_are_canonicalized():
    guard = SandboxGuard(resolver=PUBLIC, allow_external=True)
    assert guard.validate("HTTPS://EXAMPLE.COM./")
    assert guard.validate("https://xn--bcher-kva.example/")


def test_test_allowlist_is_loopback_only_and_ignored_in_production(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_ENV", "test")
    resolver = lambda host: ["127.0.0.1"]
    allowed = ["benign.test:80"]
    assert SandboxGuard(resolver=resolver, test_allowlist=allowed, production=False).validate("http://benign.test/")
    with pytest.raises(UnsafeURL):
        SandboxGuard(resolver=resolver, test_allowlist=allowed, production=True).validate("http://benign.test/")


def test_test_allowlist_cannot_be_enabled_without_test_environment(monkeypatch):
    monkeypatch.delenv("TRUSTSHIELD_ENV", raising=False)
    with pytest.raises(UnsafeURL):
        SandboxGuard(resolver=lambda host: ["127.0.0.1"], test_allowlist=["benign.test:80"]).validate("http://benign.test/")
