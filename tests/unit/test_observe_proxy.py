from fastapi.testclient import TestClient

from scripts import observe_proxy_chain as obs
from services.api import main as api
from services.api.settings import Settings


def test_hops_equal_the_entries_the_platform_appended():
    one = obs.analyse("203.0.113.7, 198.51.100.9, 192.0.2.10", "192.0.2.10")
    assert one["conclusive"] and one["hops"] == 1 and one["client_entry"] == "192.0.2.10"
    two = obs.analyse("203.0.113.7, 198.51.100.9, 192.0.2.10, 10.1.1.1", "192.0.2.10")
    assert two["conclusive"] and two["hops"] == 2


def test_platform_that_overwrites_forwarded_for_is_recognised_only_when_it_matches_the_caller():
    result = obs.analyse("192.0.2.10", "192.0.2.10")  # spoofed entries discarded, client address set by the edge
    assert result["conclusive"] and result["hops"] == 1 and result["platform_overwrites_xff"]


def test_inconclusive_cases_are_never_turned_into_a_hop_count():
    assert not obs.analyse("192.0.2.10", "203.0.113.99")["conclusive"]  # single entry that is not this machine
    assert obs.analyse("203.0.113.7, 198.51.100.9", "192.0.2.10")["hops"] == 0
    wrong = obs.analyse("203.0.113.7, 198.51.100.9, 10.9.9.9", "192.0.2.10")
    assert not wrong["conclusive"] and "not this machine" in wrong["reason"]


def test_debug_route_exists_only_when_explicitly_enabled(monkeypatch):
    monkeypatch.delenv("TRUSTSHIELD_DEBUG_PROXY", raising=False)
    assert TestClient(api.create_app(Settings(env="development"))).get("/api/v1/debug/proxy").status_code == 404
    monkeypatch.setenv("TRUSTSHIELD_DEBUG_PROXY", "1")
    body = TestClient(api.create_app(Settings(env="development"))).get("/api/v1/debug/proxy", headers={"X-Forwarded-For": "1.2.3.4"}).json()
    assert body["x_forwarded_for"] == "1.2.3.4" and set(body) == {"peer", "x_forwarded_for", "x_forwarded_proto", "memory"}
