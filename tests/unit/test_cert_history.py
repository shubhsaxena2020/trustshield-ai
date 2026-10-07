"""Online certificate-history check (regression: 2026-10-07, the official college portal psitche.ac.in scored 25 as a possible phish)."""
import datetime as dt

import pytest

from services.analysis.pipeline import analyze_text, analyze_url
from services.analysis.reputation import cert_history


def _old_cert_record(domain, *args):
    years_ago = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=900)).isoformat()
    return 86, years_ago


def _brand_new_record(domain, *args):
    days_ago = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=3)).isoformat()
    return 2, days_ago


@pytest.fixture(autouse=True)
def _offline(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_REPUTATION", "on")
    cert_history._crtsh.cache_clear()


def test_established_institutional_domain_is_low_risk(monkeypatch):
    monkeypatch.setattr(cert_history, "_crtsh", _old_cert_record)
    assert analyze_url("https://erp.psitche.ac.in/").risk.score <= 15
    assert analyze_text("i received this link please check it https://erp.psitche.ac.in/").risk.score <= 15


def test_brand_new_domain_is_flagged(monkeypatch):
    monkeypatch.setattr(cert_history, "_crtsh", _brand_new_record)
    result = analyze_url("https://secure-account-check.com/")
    assert "domain_very_new" in [e.category for e in result.evidence]


def test_unavailable_lookup_is_reported_not_guessed(monkeypatch):
    monkeypatch.setattr(cert_history, "_crtsh", lambda domain, *a: None)
    result = analyze_url("https://erp.psitche.ac.in/")
    assert any("certificate check was unavailable" in lim for lim in result.limitations)
    assert "established_institution" not in [e.category for e in result.evidence]


def test_lookup_can_be_switched_off(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_REPUTATION", "off")
    monkeypatch.setattr(cert_history, "_crtsh", lambda *a: pytest.fail("network must not be called when off"))
    assert cert_history.domain_evidence("psitche.ac.in") == ([], None)
