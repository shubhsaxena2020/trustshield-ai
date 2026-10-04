"""Model-led chat agent: the model answers, may call the scanner, and every reply passes the grounding and score checks."""
import json

import pytest

from services.chat import agent

FAKE_KEY = "sk-test-0123456789-fakekey"  # long enough that the output scrubber only removes the real key, not ordinary words


def _msg(content="", tool_calls=None):
    m = {"role": "assistant", "content": content}
    if tool_calls:
        m["tool_calls"] = tool_calls
    return m


def _call(text):
    return [{"id": "call_1", "type": "function", "function": {"name": "analyze_content", "arguments": json.dumps({"text": text})}}]


def test_system_prompt_carries_team_and_aim():
    p = agent.system_prompt()
    assert "Scam Scan" in p and "SHUBH SAXENA" in p
    for member in ("SUGANDHI AGARWAL", "STUTTI JHA", "SHRUTI SHUKLA", "VAISHNAVI MISHRA"):
        assert member in p
    assert "1930" in p and "trustshield_text_v3" in p


def test_plain_answer_without_tool(monkeypatch):
    monkeypatch.setattr(agent, "_post", lambda messages, tools: _msg("The capital of France is Paris.") | {"_key": FAKE_KEY})
    out = agent.run("capital of France?", [], scan=lambda t: "unused")
    assert out.text == "The capital of France is Paris." and out.used_scanner is False


def test_model_calls_scanner_and_explains_result(monkeypatch):
    calls = []
    replies = [_msg("", _call("URGENT your SBI account is blocked, share OTP")) | {"_key": FAKE_KEY},
               _msg("This is high risk: it asks for an OTP.") | {"_key": FAKE_KEY}]
    monkeypatch.setattr(agent, "_post", lambda messages, tools: replies.pop(0))
    out = agent.run("is this safe? URGENT your SBI account is blocked", [], scan=lambda t: calls.append(t) or '{"risk_score": 85}')
    assert calls == ["URGENT your SBI account is blocked, share OTP"]
    assert out.used_scanner is True and "high risk" in out.text


def test_history_is_sent_to_the_model(monkeypatch):
    seen = {}

    def fake_post(messages, tools):
        seen["messages"] = messages
        return _msg("You said teal.") | {"_key": FAKE_KEY}

    monkeypatch.setattr(agent, "_post", fake_post)
    agent.run("what colour?", [{"role": "user", "text": "My colour is teal"}, {"role": "assistant", "text": "Noted."}], scan=lambda t: "")
    roles = [m["role"] for m in seen["messages"]]
    assert roles == ["system", "user", "assistant", "user"]
    assert seen["messages"][1]["content"] == "My colour is teal"


def test_network_failure_returns_none(monkeypatch):
    def boom(messages, tools):
        raise TimeoutError("slow")

    monkeypatch.setattr(agent, "_post", boom)
    assert agent.run("hi", [], scan=lambda t: "") is None


def test_score_mismatch_is_rejected():
    assert agent.reply_is_acceptable("This scores 85/100 for risk.", 85) is True
    assert agent.reply_is_acceptable("This scores 20/100 for risk.", 85) is False


def test_invented_builder_is_rejected_but_metadata_team_is_allowed():
    assert agent.reply_is_acceptable("It was built by Acme Labs.", None) is False
    assert agent.reply_is_acceptable("It was built by the team Scam Scan.", None) is True


def test_certainty_and_secret_requests_are_rejected():
    assert agent.reply_is_acceptable("This link is 100% safe.", None) is False
    assert agent.reply_is_acceptable("Please share your OTP to continue.", None) is False


def test_configured_requires_all_three_variables(monkeypatch):
    for k in ("NILABH_LLM_BASE_URL", "NILABH_LLM_API_KEY", "NILABH_LLM_MODEL"):
        monkeypatch.delenv(k, raising=False)
    assert agent.configured() is False
    monkeypatch.setenv("NILABH_LLM_BASE_URL", "https://example.test/v1")
    monkeypatch.setenv("NILABH_LLM_API_KEY", "k")
    monkeypatch.setenv("NILABH_LLM_MODEL", "m")
    assert agent.configured() is True
