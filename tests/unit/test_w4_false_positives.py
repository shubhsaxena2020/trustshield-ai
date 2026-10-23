"""W4 false-positive regression tests for brand/lookalike URL rules.

Official brand domains must never be reported as ``lookalike_domain`` or
``domain_mismatch``. The named known misses must still produce evidence.
"""
import pytest

from services.analysis.url_analysis.brands import BRANDS
from services.analysis.url_analysis.static import analyze_url_static


def _categories(url: str) -> set[str]:
    return {e.category for e in analyze_url_static(url).evidence}


OFFICIAL_DOMAINS = sorted({domain for _, (_, domains) in BRANDS.items() for domain in domains})


@pytest.mark.parametrize("domain", OFFICIAL_DOMAINS)
def test_official_brand_domain_is_not_a_lookalike_or_mismatch(domain: str) -> None:
    url = f"https://{domain}"
    cats = _categories(url)
    assert "lookalike_domain" not in cats, (domain, url, sorted(cats))
    assert "domain_mismatch" not in cats, (domain, url, sorted(cats))


@pytest.mark.parametrize("domain", OFFICIAL_DOMAINS)
def test_official_brand_domain_with_path_is_not_a_lookalike_or_mismatch(domain: str) -> None:
    url = f"https://{domain}/"
    cats = _categories(url)
    assert "lookalike_domain" not in cats, (domain, url, sorted(cats))
    assert "domain_mismatch" not in cats, (domain, url, sorted(cats))


NAMED_MISSES = [
    ("https://sbl.co.in/login", "credential_path_terms"),
    ("https://irctc-refund.xyz/login", "suspicious_tld"),
    ("https://sbii.co.in/login", "lookalike_domain"),
    ("http://192.0.2.8/login", "ip_address_host"),
]


@pytest.mark.parametrize("url, expected_category", NAMED_MISSES)
def test_named_miss_is_flagged(url: str, expected_category: str) -> None:
    cats = _categories(url)
    assert expected_category in cats, (url, sorted(cats))
    assert len(cats) > 0, (url, sorted(cats))
