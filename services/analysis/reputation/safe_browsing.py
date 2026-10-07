"""Google Safe Browsing check for one registered domain (Lookup API v4, threatMatches:find).

Only the registered domain is sent, as http://<domain>/. The message, the full link and the page itself are never sent.
Needs GOOGLE_SAFE_BROWSING_KEY in the environment (or .env). With no key, or with TRUSTSHIELD_SAFE_BROWSING=off, the check is skipped
and nothing is reported as checked. A failed request is reported as unavailable, never guessed."""
from __future__ import annotations

import functools
import json
import os
import urllib.request

_ENDPOINT = "https://safebrowsing.googleapis.com/v4/threatMatches:find"
_TIMEOUT_SECONDS = 5
_THREAT_TYPES = ["MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE", "POTENTIALLY_HARMFUL_APPLICATION"]


def api_key() -> str:
    return os.getenv("GOOGLE_SAFE_BROWSING_KEY", "").strip()


def enabled() -> bool:
    return bool(api_key()) and os.getenv("TRUSTSHIELD_SAFE_BROWSING", "on").strip().lower() != "off"


@functools.lru_cache(maxsize=512)
def _lookup(domain: str, key: str) -> tuple[str, ...] | None:
    """Threat types Google lists for this domain (empty tuple = not listed), or None if the lookup failed. Cached per process."""
    body = {
        "client": {"clientId": "trustshield-ai", "clientVersion": "1.0"},
        "threatInfo": {
            "threatTypes": _THREAT_TYPES,
            "platformTypes": ["ANY_PLATFORM"],
            "threatEntryTypes": ["URL"],
            "threatEntries": [{"url": f"http://{domain}/"}],
        },
    }
    req = urllib.request.Request(
        f"{_ENDPOINT}?key={key}", data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "User-Agent": "TrustShield-AI/1.0 (domain check)"},
    )
    try:
        with urllib.request.urlopen(req, timeout=_TIMEOUT_SECONDS) as r:  # noqa: S310 (fixed https host)
            reply = json.loads(r.read())
    except Exception:  # network down, quota, bad key: the caller says so, never guesses
        return None
    matches = reply.get("matches", []) if isinstance(reply, dict) else None
    if matches is None:
        return None
    return tuple(sorted({str(m.get("threatType", "UNKNOWN")) for m in matches if isinstance(m, dict)}))


def listed_threats(domain: str) -> tuple[str, ...] | None:
    """None when the check is off or failed; otherwise the threat types listed for the domain (empty = clean as far as Google knows)."""
    if not domain or not enabled():
        return None
    return _lookup(domain, api_key())
