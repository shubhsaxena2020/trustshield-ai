import pytest

from services.analysis.normalization.text import InvalidInput, extract_urls, normalize_text
from services.analysis.pipeline import analyze_text, analyze_url
from services.analysis.rules.text_rules import run_text_rules
from services.analysis.url_analysis.static import analyze_url_static


def cats(evidence):
    return {e.category for e in evidence}


# ---------- normalization
def test_normalize_strips_zero_width_and_collapses():
    assert normalize_text("Ver​ify   now please") == "Verify now please"


def test_normalize_rejects_oversized():
    with pytest.raises(InvalidInput):
        normalize_text("a" * 20_001)


def test_extract_urls_trims_punctuation():
    assert extract_urls("go to http://sbi-kyc.xyz/login, now.") == ["http://sbi-kyc.xyz/login"]


# ---------- text rules
def test_kyc_phishing_message_high_risk():
    r = analyze_text("Dear customer your SBI account will be blocked today. Verify now and share your OTP at http://sbi-kyc-update.xyz/login")
    c = cats(r.evidence)
    assert {"urgency", "credential_request", "credential_pressure", "domain_mismatch"} <= c
    assert r.risk.label == "high_risk"
    assert r.classification.primary in {"credential_phishing", "phishing"}


def test_legit_bank_alert_not_flagged():
    r = analyze_text("Rs 500.00 debited from A/c XX1234 on 12-09-26. Never share your OTP or PIN with anyone. If not you call the number on your card.")
    assert "credential_request" not in cats(r.evidence)
    assert r.risk.label in {"low_risk", "unable_to_verify"}
    assert r.risk.score < 25


def test_digital_arrest_is_critical():
    r = analyze_text("This is CBI officer. A case is registered against your Aadhaar for money laundering. Stay on video call, do not tell anyone and transfer money to the RBI safe account.")
    assert "digital_arrest" in cats(r.evidence)
    assert r.classification.primary == "digital_arrest"


def test_fake_job_with_fee():
    r = analyze_text("Work from home, earn Rs 5000 per day, instant selected, no interview. Pay registration fee Rs 500 to start.")
    assert {"fake_job", "payment_request", "job_fee_scam"} <= cats(r.evidence)


def test_single_keyword_not_decisive(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_ML", "off")
    ev = run_text_rules("we will review this immediately and get back to you tomorrow")
    r = analyze_text("Hi Rahul, the class notes are uploaded. Please check them immediately when free.")
    assert r.risk.score < 25


def test_short_benign_input_is_analyzed_with_low_confidence(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_ML", "off")
    r = analyze_text("hello there")
    assert r.status != "failed"
    assert r.risk.label == "low_risk"
    assert r.risk.confidence == "low"
    assert any("Limited context" in limitation for limitation in r.limitations)


@pytest.mark.parametrize("text, expected_category", [
    ("OTP 481926", None),
    ("Enter CVV", "credential_request"),
    ("Pay ₹5000", "payment_request"),
    ("bit.ly/x", "url_shortener"),
])
def test_meaningful_short_text_is_analyzed_with_limited_context(text, expected_category):
    short = analyze_text(text)
    long = analyze_text("This is a longer message with enough context for analysis.")
    confidence_rank = {"low": 0, "medium": 1, "high": 2}

    assert short.status != "failed"
    assert short.modules["text_rules"].status == "completed"
    assert short.risk.label != "unable_to_verify"
    assert confidence_rank[short.risk.confidence] <= confidence_rank[long.risk.confidence]
    assert any("Limited context" in limitation for limitation in short.limitations)
    if expected_category:
        assert expected_category in cats(short.evidence)


def test_short_credential_phrase_keeps_existing_rule_evidence():
    result = analyze_text("Send your OTP now")

    assert result.status != "failed"
    assert result.modules["text_rules"].status == "completed"
    assert "credential_request" in cats(result.evidence)


@pytest.mark.parametrize("text", ["", "   ", "!!!", "🙂🙂", "..."])
def test_text_without_alphanumeric_tokens_is_unable_to_verify(text):
    result = analyze_text(text)

    assert result.status == "failed"
    assert result.risk.label == "unable_to_verify"
    assert any("alphanumeric" in limitation.lower() for limitation in result.limitations)


# ---------- url static
def test_ip_url_and_exec():
    r = analyze_url_static("http://192.168.1.5:8080/app.apk")
    assert {"ip_address_host", "unusual_port", "executable_download", "no_https"} <= cats(r.evidence)


def test_brand_domain_mismatch_and_official_ok():
    bad = analyze_url_static("https://secure.sbi-login-verify.xyz/kyc")
    assert "domain_mismatch" in cats(bad.evidence)
    good = analyze_url_static("https://retail.onlinesbi.sbi/login")
    assert "domain_mismatch" not in cats(good.evidence)
    assert good.facts["registered_domain"] == "onlinesbi.sbi"


def test_subdomain_uses_registered_domain():
    r = analyze_url_static("https://secure.hdfcbank.com/login")
    assert r.facts["registered_domain"] == "hdfcbank.com"
    assert "domain_mismatch" not in cats(r.evidence)


def test_typosquat_lookalike():
    assert "lookalike_domain" in cats(analyze_url_static("https://hdfcbannk.com").evidence)


def test_homograph_cyrillic():
    r = analyze_url_static("https://xn--pypal-4ve.com")  # 'pаypal' with Cyrillic a
    assert {"punycode_host", "homograph_domain"} <= cats(r.evidence)


def test_userinfo_trick_and_shortener():
    assert "userinfo_in_url" in cats(analyze_url_static("http://www.sbi.co.in@evil.example.top/x").evidence)
    assert "url_shortener" in cats(analyze_url_static("https://bit.ly/3abcde").evidence)


def test_bad_schemes():
    assert not analyze_url_static("javascript:alert(1)").valid
    assert "unsupported_scheme" in cats(analyze_url_static("javascript:alert(1)").evidence)
    assert not analyze_url_static("").valid


def test_url_endpoint_result_shape():
    r = analyze_url("https://example.com")
    assert r.modules["web_sandbox"].status == "not_run"
    assert r.risk.label in {"low_risk", "unable_to_verify"}
    assert any("not the same as" in l for l in r.limitations)
