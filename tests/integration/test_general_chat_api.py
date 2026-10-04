"""General-chat intent over the API: chat responses without analysis, analysis untouched for scam payloads."""
import pytest
from fastapi.testclient import TestClient

from services.api.main import app
from services.chat.explain import DeterministicExplanationProvider

client = TestClient(app)
CAPTURED: list[tuple] = []

_SBI_SCAM = ("Hello, I am SBI branch officer Ramesh Kumar, your KYC has expired and your account will be blocked "
             "within 24 hours. Send the OTP you just received to this number immediately to restore access.")


def post_chat(message: str, history: list | None = None) -> dict:
    body: dict = {"message": message}
    if history is not None:
        body["history"] = history
    return client.post("/api/v1/chat", json=body).json()


@pytest.fixture
def spy_reply_chat(monkeypatch):
    CAPTURED.clear()
    original = DeterministicExplanationProvider.reply_chat

    def spy(self, message: str, history: list | None = None):
        CAPTURED.append((message, history))
        return original(self, message, history)

    monkeypatch.setattr(DeterministicExplanationProvider, "reply_chat", spy)
    return CAPTURED


def test_who_are_you_is_chat_without_analysis():
    out = post_chat("who are you")
    assert out["kind"] == "chat"
    assert out["response_type"] == "chat"
    assert out["analysis"] is None
    assert out["reply"]["text"].strip()
    assert out["context_signature"] is None


def _force_general_chat(monkeypatch, spy_reply_chat):
    """Pin the classifier to agent1's 'general_chat' intent so the API's own dispatch/history wiring is verifiable
    independently of when agent1's services/chat/intent.py change lands."""

    class StubIntent:
        intent = "general_chat"
        followup_intent = None

        def public(self):
            return {"intent": "general_chat"}

    monkeypatch.setattr("services.api.main.classify_intent", lambda *a, **k: StubIntent())
    return spy_reply_chat


def test_capital_of_france_is_chat_without_risk_card(monkeypatch, spy_reply_chat):
    _force_general_chat(monkeypatch, spy_reply_chat)
    out = post_chat("what is the capital of France?")
    assert out["kind"] == "chat"
    assert out["response_type"] == "chat"
    assert out["intent"]["intent"] == "general_chat"
    assert out["analysis"] is None
    assert out["route"] == {}  # chat-only intents never build a module routing plan
    # the deterministic fallback honestly declines to invent the fact; with a model configured it would answer it
    assert out["reply"]["text"].strip()
    assert out["reply"]["grounded"] is True
    # agent2's provider contract: reply_chat(message, history) was called for this intent
    assert spy_reply_chat and spy_reply_chat[-1][0] == "what is the capital of France?"


def test_history_is_passed_trimmed_and_capped(monkeypatch, spy_reply_chat):
    _force_general_chat(monkeypatch, spy_reply_chat)
    history = [
        {"role": "user", "text": "hello" + "x" * 1600},          # > 1500 chars: must be trimmed
        {"role": "assistant", "text": "hi"},
        {"role": "user", "text": "what is 2+2"},
        {"role": "assistant", "text": "4"},
        {"role": "user", "text": "why"},
        {"role": "assistant", "text": "because"},
    ]
    out = post_chat("what is the capital of France?", history=history)
    assert out["kind"] == "chat" and out["analysis"] is None
    passed = spy_reply_chat[-1][1]
    assert len(passed) == 6
    assert all(len(t["text"]) <= 1500 for t in passed)   # per-turn trim at the API boundary
    assert passed[0]["text"] == "hello" + "x" * 1495
    # oversized histories are rejected by the request model, never silently truncated
    body = {"message": "what is the capital of France?", "history": [{"role": "user", "text": "x"}] * 7}
    assert client.post("/api/v1/chat", json=body).status_code == 422


def test_sbi_scam_still_returns_analysis_risk_card():
    out = post_chat(_SBI_SCAM)
    assert out["kind"] == "analysis"
    assert out["response_type"] == "analysis"
    assert out["analysis"] is not None
    vm = out["analysis"]
    assert vm["risk_score"] > 0 and vm["risk_level"] != "LOW"
    assert vm["input_types"]
