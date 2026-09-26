import pytest

from services.analysis.url_analysis.ssrf import UnsafeURL, validate_public_url

PUBLIC = lambda host: ["93.184.216.34"]


@pytest.mark.parametrize("url", [
    "http://localhost/admin", "http://127.0.0.1/", "http://127.1/", "http://2130706433/", "http://0x7f000001/",
    "http://[::1]/", "http://10.0.0.5/", "http://192.168.1.1/", "http://172.16.0.1/", "http://169.254.169.254/latest/meta-data/",
    "http://metadata.google.internal/", "http://printer.local/", "http://intranet/", "file:///etc/passwd", "ftp://example.com/x",
    "gopher://example.com", "http://user:pw@example.com/", "http://[::ffff:127.0.0.1]/", "", "http://",
])
def test_blocked(url):
    with pytest.raises(UnsafeURL):
        validate_public_url(url, resolver=PUBLIC)


def test_dns_rebinding_to_private_blocked():
    with pytest.raises(UnsafeURL):
        validate_public_url("http://evil.example.com/", resolver=lambda h: ["93.184.216.34", "10.0.0.1"])


def test_public_allowed():
    assert validate_public_url("https://example.com/path", resolver=PUBLIC)
    assert validate_public_url("http://93.184.216.34/", resolver=PUBLIC)
