"""Brand lookalike detection: a one-character typo inside a hyphenated token must be flagged; official domains must not."""
import pytest

from services.analysis.url_analysis.static import analyze_url_static


@pytest.mark.parametrize("url", [
    "http://paypa1-secure-login.xyz/verify",
    "http://paypa1-verify.top/login",
])
def test_paypal_typosquat_is_flagged(url):
    cats = {e.category for e in analyze_url_static(url).evidence}
    assert "lookalike_domain" in cats


@pytest.mark.parametrize("url", [
    "https://www.paypal.com/signin",
    "https://paypal.me/shubh",
    "https://www.google.com/search?q=x",
    "https://github.com/shubhsaxena2020",
])
def test_official_brand_domains_are_not_flagged_as_lookalikes(url):
    cats = {e.category for e in analyze_url_static(url).evidence}
    assert "lookalike_domain" not in cats
    assert "domain_mismatch" not in cats
