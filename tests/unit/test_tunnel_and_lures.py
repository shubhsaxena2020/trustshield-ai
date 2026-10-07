"""Tunnel addresses and location or camera lures (regression: 2026-10-07, a GPS-tracking link scored 0 and then 50 from a screenshot)."""
import pytest

from services.analysis.pipeline import analyze_text, analyze_url

TUNNEL = "https://gps-tech-core-exceptional.trycloudflare.com"


def test_tunnel_link_is_at_least_suspicious():
    assert analyze_url(TUNNEL).risk.score >= 50


@pytest.mark.parametrize("text", [
    f"Track my location here: {TUNNEL}",
    f"Allow camera access to view the photo: {TUNNEL}",
    f"Share your location to verify you are human {TUNNEL}",
])
def test_tunnel_with_location_or_camera_lure_is_high_risk(text):
    assert analyze_text(text).risk.score >= 75


@pytest.mark.parametrize("url", [
    "https://www.onlinesbi.sbi/",
    "https://hdfc.bank.in/",
    "https://myapp.vercel.app/",
    "https://www.google.com/",
])
def test_ordinary_hosts_are_not_flagged(url):
    assert analyze_url(url).risk.score <= 10


def test_free_hosting_alone_is_weak():
    assert analyze_url("https://my-portfolio.netlify.app/").risk.score < 50
