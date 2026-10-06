"""Official brand domains must not be scored as scams (regression: 2026-10-06, a message with the official SBI link was flagged)."""
import pytest

from services.analysis.pipeline import analyze_text
from services.analysis.url_analysis.brands import is_official


@pytest.mark.parametrize("domain", ["sbi.co.in", "onlinesbi.sbi", "onlinesbi.com", "bank.sbi", "sbi.bank.in"])
def test_sbi_domains_are_official(domain):
    assert is_official("sbi", domain)


@pytest.mark.parametrize("text", [
    "https://www.onlinesbi.sbi/",
    "https://sbi.co.in",
    "https://bank.sbi/",
    "Check my account at https://www.onlinesbi.sbi/ please",
])
def test_official_sbi_links_are_low_risk(text):
    result = analyze_text(text)
    assert result.risk.score <= 10, (text, result.risk.score, result.risk.label)


def test_lookalike_sbi_link_is_still_flagged():
    result = analyze_text("https://sbi-kyc-update.xyz/login")
    assert result.risk.score >= 30
