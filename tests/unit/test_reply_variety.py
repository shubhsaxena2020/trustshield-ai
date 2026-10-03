"""Unit tests verifying variety, determinism, and safety grounding for deterministic chat replies."""
from datetime import datetime, timezone

import pytest

from services.chat.contract import Capability, ChatAnalysis, ContractEvidence
from services.chat.explain import (
    DeterministicExplanationProvider,
    chat_grounding_violations,
    grounding_violations,
)

BANNED_WORDS = ("100% safe", "guaranteed", "definitely fraud")


def _make_evidence(eid: str, category: str, severity: str, module: str, title: str, explanation: str) -> ContractEvidence:
    return ContractEvidence(
        id=eid,
        category=category,
        severity=severity,
        source_module=module,
        title=title,
        explanation=explanation,
        confidence=0.88,
        provenance="test_provenance",
    )


def _build_analysis(
    analysis_id: str,
    risk_level: str,
    risk_score: int,
    confidence: str = "medium",
    evidence: list[ContractEvidence] | None = None,
    safe_actions: list[str] | None = None,
    uncertainty: list[str] | None = None,
    urls: list[str] | None = None,
    qr_payload: str | None = None,
    extracted_content: str | None = None,
) -> ChatAnalysis:
    caps = [
        Capability(name="text_rules", description="deterministic scam-pattern rules", used=True),
        Capability(name="reputation", description="external reputation lookup", used=False, reason="none"),
    ]
    return ChatAnalysis.model_validate({
        "contract_version": "2.0",
        "analysis_id": analysis_id,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "input_types": ["text"],
        "risk_score": risk_score,
        "risk_level": risk_level,
        "confidence": confidence,
        "evidence": evidence or [],
        "safe_actions": safe_actions if safe_actions is not None else ["Verify the sender through an official channel before acting."],
        "uncertainty": uncertainty or [],
        "urls": urls or [],
        "qr_payload": qr_payload,
        "qr_payload_type": "url" if qr_payload else None,
        "extracted_content": extracted_content,
        "capabilities": caps,
    })


@pytest.fixture
def varied_analyses() -> list[ChatAnalysis]:
    return [
        _build_analysis(
            analysis_id="analysis-phish-001",
            risk_level="high_risk",
            risk_score=92,
            confidence="high",
            evidence=[
                _make_evidence("e1", "domain_mismatch", "high", "brand_check", "Domain mismatch detected", "The domain does not match SBI"),
                _make_evidence("e2", "urgency_language", "medium", "text_rules", "Urgent deadline threat", "Message threatens suspension within 24 hours"),
            ],
            urls=["http://fake-sbi-login.xyz/auth"],
        ),
        _build_analysis(
            analysis_id="analysis-phish-002",
            risk_level="high_risk",
            risk_score=88,
            confidence="high",
            evidence=[
                _make_evidence("e3", "credential_harvesting", "critical", "text_rules", "Credential harvesting form", "Form asks for user credentials"),
            ],
            urls=["http://secure-verify-alert.top/login"],
        ),
        _build_analysis(
            analysis_id="analysis-suspicious-001",
            risk_level="suspicious",
            risk_score=68,
            confidence="medium",
            evidence=[
                _make_evidence("e4", "lookalike_domain", "medium", "url_static", "Lookalike domain name", "Domain resembles an official financial portal"),
            ],
            urls=["http://paytm-kyc-update.biz/kyc"],
        ),
        _build_analysis(
            analysis_id="analysis-suspicious-002",
            risk_level="suspicious",
            risk_score=62,
            confidence="medium",
            evidence=[
                _make_evidence("e5", "unrealistic_reward", "medium", "text_rules", "Unrealistic lottery claim", "Claims recipient won an unrequested lottery"),
            ],
        ),
        _build_analysis(
            analysis_id="analysis-caution-001",
            risk_level="caution",
            risk_score=38,
            confidence="medium",
            evidence=[
                _make_evidence("e6", "unfamiliar_shortener", "low", "url_static", "URL shortener used", "Link uses a generic URL shortening service"),
            ],
            urls=["http://tiny.example/xyz"],
        ),
        _build_analysis(
            analysis_id="analysis-low-001",
            risk_level="low_risk",
            risk_score=8,
            confidence="high",
            evidence=[],
        ),
        _build_analysis(
            analysis_id="analysis-unable-001",
            risk_level="unable_to_verify",
            risk_score=0,
            confidence="low",
            uncertainty=["image contains no legible text"],
        ),
        _build_analysis(
            analysis_id="analysis-qr-001",
            risk_level="high_risk",
            risk_score=84,
            confidence="high",
            qr_payload="upi://pay?pa=scammer@bank&am=5000",
            evidence=[
                _make_evidence("e7", "fake_collect_request", "high", "qr_payload", "UPI collect disguised as payment", "QR code requests payment instead of sending money"),
            ],
        ),
    ]


@pytest.fixture
def provider() -> DeterministicExplanationProvider:
    return DeterministicExplanationProvider()


def test_reply_for_analysis_variety_and_grounding(provider, varied_analyses):
    replies = [provider.reply_for_analysis(a) for a in varied_analyses]
    texts = [r.text for r in replies]

    # Different analyses must not produce identical replies
    assert len(set(texts)) > 1

    # Determinism: same input gives exact same output
    for a in varied_analyses:
        r1 = provider.reply_for_analysis(a).text
        r2 = provider.reply_for_analysis(a).text
        assert r1 == r2

    # Every reply must pass grounding_violations and contain no banned words
    for a, r in zip(varied_analyses, replies):
        assert r.grounded
        assert r.provider == "deterministic"
        violations = grounding_violations(r.text, a)
        assert not violations, f"Violations for {a.analysis_id}: {violations}"
        for b in BANNED_WORDS:
            assert b not in r.text


def test_reply_for_analysis_variety_within_same_risk_level(provider):
    high_analyses = [
        _build_analysis(f"high-variant-{i}", "high_risk", 90, "high", evidence=[
            _make_evidence(f"ev-{i}", "domain_mismatch", "high", "brand_check", "Domain mismatch", "Domain does not match genuine site")
        ])
        for i in range(8)
    ]
    texts = [provider.reply_for_analysis(a).text for a in high_analyses]
    assert len(set(texts)) > 1
    for a, text in zip(high_analyses, texts):
        assert not grounding_violations(text, a)


@pytest.mark.parametrize("intent", [
    "why_suspicious",
    "evidence",
    "url_flag",
    "sender_name",
    "what_to_do",
    "open_link",
    "qr_content",
    "ocr_text",
    "confidence",
    "confidence_vs_risk",
    "opened_website",
    "reputation_checked",
    "definitely_fraud",
    "can_trust",
])
def test_reply_for_followup_variety_and_grounding(provider, varied_analyses, intent):
    replies = [provider.reply_for_followup(intent, "question", a) for a in varied_analyses]
    texts = [r.text for r in replies]

    # Across varied analyses, the follow-up replies must not all be identical
    assert len(set(texts)) > 1, f"Intent {intent} produced only 1 distinct reply across varied analyses"

    # Determinism check
    for a in varied_analyses:
        r1 = provider.reply_for_followup(intent, "question", a).text
        r2 = provider.reply_for_followup(intent, "question", a).text
        assert r1 == r2

    # Grounding check
    for a, r in zip(varied_analyses, replies):
        assert r.grounded
        assert r.provider == "deterministic"
        violations = grounding_violations(r.text, a)
        assert not violations, f"Intent {intent} violation for {a.analysis_id}: {violations}"
        for b in BANNED_WORDS:
            assert b not in r.text


def test_reply_greeting_variety_and_grounding(provider):
    greetings = ["hello", "hi", "hey there", "good morning", "yo", "greetings"]
    replies = [provider.reply_greeting(m) for m in greetings]
    texts = [r.text for r in replies]

    assert len(set(texts)) > 1
    for m in greetings:
        assert provider.reply_greeting(m).text == provider.reply_greeting(m).text

    for r in replies:
        assert r.provider == "deterministic"
        violations = chat_grounding_violations(r.text)
        assert not violations, f"Greeting violation: {violations}"
        for b in BANNED_WORDS:
            assert b not in r.text


def test_reply_cyber_qa_variety_and_grounding(provider):
    # Test both topic queries and general cyber queries
    questions = [
        "what is phishing?",
        "how does smishing work?",
        "explain vishing",
        "tell me about digital arrest scams",
        "how do hackers steal credentials?",
        "what is zero-day malware?",
    ]
    replies = [provider.reply_cyber_qa(q) for q in questions]
    texts = [r.text for r in replies]

    assert len(set(texts)) > 1
    for q in questions:
        assert provider.reply_cyber_qa(q).text == provider.reply_cyber_qa(q).text

    for r in replies:
        assert r.provider == "deterministic"
        violations = chat_grounding_violations(r.text)
        assert not violations, f"Cyber QA violation: {violations}"
        for b in BANNED_WORDS:
            assert b not in r.text


def test_reply_product_qa_variety_and_grounding(provider):
    questions = [
        "what model do you use?",
        "which model is active?",
        "are you chatgpt?",
        "how accurate are you?",
        "do you store my data?",
        "do you browse websites?",
        "who made you?",
        "what can you do?",
    ]
    replies = [provider.reply_product_qa(q) for q in questions]
    texts = [r.text for r in replies]

    assert len(set(texts)) > 1
    for q in questions:
        assert provider.reply_product_qa(q).text == provider.reply_product_qa(q).text

    for r in replies:
        assert r.provider == "deterministic"
        violations = chat_grounding_violations(r.text)
        assert not violations, f"Product QA violation: {violations}"
        for b in BANNED_WORDS:
            assert b not in r.text


def test_reply_off_topic_variety_and_grounding(provider):
    messages = [
        "write me a poem",
        "what is the capital of France?",
        "tell me a joke",
        "how to cook pasta",
        "who won the cricket match?",
    ]
    replies = [provider.reply_off_topic(m) for m in messages]
    texts = [r.text for r in replies]

    assert len(set(texts)) > 1
    for m in messages:
        assert provider.reply_off_topic(m).text == provider.reply_off_topic(m).text

    for r in replies:
        assert r.provider == "deterministic"
        violations = chat_grounding_violations(r.text)
        assert not violations, f"Off topic violation: {violations}"
        for b in BANNED_WORDS:
            assert b not in r.text
