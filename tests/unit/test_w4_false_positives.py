"""W4 false-positive regression tests for brand/lookalike URL rules.

Official brand domains must never be reported as ``lookalike_domain`` or
``domain_mismatch``. The named known misses must still produce evidence.
"""
import json
from pathlib import Path

import pytest

from services.analysis import pipeline
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


def test_real_three_letter_and_official_domains_are_not_false_positives(monkeypatch: pytest.MonkeyPatch) -> None:
    fixtures = Path(__file__).parents[1] / "fixtures"
    three_letter = [json.loads(line) for line in (fixtures / "w4_real_three_letter_domains.jsonl").read_text(encoding="utf-8").splitlines() if line]
    official = [json.loads(line) for line in (fixtures / "w4_real_official_domains.jsonl").read_text(encoding="utf-8").splitlines() if line]
    registry = [json.loads(line) for line in (fixtures / "w4_link_regression.jsonl").read_text(encoding="utf-8").splitlines() if line]
    cases = three_letter + official + [case for case in registry if case["source_type"] == "real_local"]
    assert len(three_letter) >= 20
    assert len(cases) >= 60
    monkeypatch.setattr(pipeline, "domain_evidence", lambda _domain: ([], None))

    for case in cases:
        result = pipeline.analyze_url(case["url"])
        categories = {e.category for e in result.evidence}
        assert result.risk.score <= case.get("expected_max_score", 0), (case["url"], result.risk.score, sorted(categories))
        assert not ({"lookalike_domain", "domain_mismatch"} & categories), (case["url"], sorted(categories))


def test_three_letter_sbi_typosquat_still_requires_and_uses_credential_lure(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(pipeline, "domain_evidence", lambda _domain: ([], None))
    result = pipeline.analyze_url("https://sbl.co.in/login")
    assert result.risk.score >= 50
    assert "lookalike_domain" in {e.category for e in result.evidence}
