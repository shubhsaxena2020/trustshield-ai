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


@pytest.mark.parametrize("brand,domain", [("hdfc", "hdfc.bank.in"), ("icici", "icici.bank.in"), ("sbi", "sbi.bank.in")])
def test_bank_in_domains_are_official(brand, domain):
    assert is_official(brand, domain)


def test_bank_in_subdomain_links_are_not_flagged():
    # Live pages: onlinesbi.sbi redirects to onlinesbi.sbi.bank.in, and the SBI notice links to yonobusiness.sbi.bank.in.
    result = analyze_text("Log in to yonobusiness.sbi.bank.in to avail business banking services.")
    assert result.risk.score <= 10, result.risk.score


def test_official_notice_with_no_link_and_no_fraud_action_is_not_scored():
    # A real SBI homepage screenshot (OCR text) scored 25 before this rule. It names a bank, asks for nothing and has no link.
    result = analyze_text("SBI never asks for confidential information such as PIN and OTP from customers. "
                          "Any such call can be made only by a fraudster. State Bank of India announces a notice to customers.")
    assert result.risk.score <= 10, (result.risk.score, result.risk.label)


def test_lookalike_sbi_link_is_still_flagged():
    result = analyze_text("https://sbi-kyc-update.xyz/login")
    assert result.risk.score >= 30
