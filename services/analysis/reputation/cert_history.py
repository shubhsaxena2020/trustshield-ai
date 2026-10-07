"""Online domain check from the public certificate-transparency log (crt.sh). Only the registered domain name is sent, never the
message or the full link, and the page itself is never requested.

Why this exists (2026-10-07): a real college portal (psitche.ac.in) scored 25 because the scanner could not tell it apart from a new
lookalike. A domain that has held certificates for years and sits under an institutional suffix (.ac.in, .gov.in, .edu) is evidence of
legitimacy; a domain whose first certificate is days old is evidence of risk. Neither is proof on its own.

Set TRUSTSHIELD_REPUTATION=off to disable the lookup (for example in offline tests)."""
from __future__ import annotations

import datetime as dt
import functools
import json
import os
import urllib.parse
import urllib.request

from services.analysis.evidence.models import Evidence, Severity
from services.analysis.reputation import safe_browsing

INSTITUTIONAL_SUFFIXES = (".ac.in", ".edu.in", ".gov.in", ".nic.in", ".edu", ".gov", ".mil")
_TIMEOUT_SECONDS = 5


def enabled() -> bool:
    return os.getenv("TRUSTSHIELD_REPUTATION", "on").strip().lower() != "off"


@functools.lru_cache(maxsize=512)
def _crtsh(domain: str) -> tuple[int, str] | None:
    """(certificate count, earliest issuance date as ISO text), or None if the lookup failed. Cached per process."""
    query = urllib.parse.quote(f"%.{domain}")
    url = f"https://crt.sh/?q={query}&output=json"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "TrustShield-AI/1.0 (domain check)"})
        with urllib.request.urlopen(req, timeout=_TIMEOUT_SECONDS) as r:  # noqa: S310 (fixed https host)
            rows = json.loads(r.read())
    except Exception:  # network down, crt.sh busy, or malformed reply: the caller says so, never guesses
        return None
    if not isinstance(rows, list):
        return None
    dates = [row.get("entry_timestamp") or row.get("not_before") for row in rows if isinstance(row, dict)]
    dates = [d for d in dates if d]
    return len(rows), (min(dates) if dates else "")


def age_days(first_seen: str) -> int | None:
    if not first_seen:
        return None
    try:
        when = dt.datetime.fromisoformat(first_seen.replace("Z", "+00:00"))
    except ValueError:
        return None
    if when.tzinfo is None:
        when = when.replace(tzinfo=dt.timezone.utc)
    return (dt.datetime.now(dt.timezone.utc) - when).days


def is_institutional(domain: str) -> bool:
    return any(domain.endswith(suffix) for suffix in INSTITUTIONAL_SUFFIXES)


def domain_evidence(domain: str) -> tuple[list[Evidence], str | None]:
    """Evidence for one registered domain, plus a limitation text when a lookup could not be made."""
    if not domain or not enabled():
        return [], None
    evidence, limit = _certificate_evidence(domain)
    listed = safe_browsing.listed_threats(domain)
    if listed:
        evidence.append(Evidence(source="reputation", category="safe_browsing_listed", severity=Severity.high, confidence=0.9,
                                 title="Google Safe Browsing lists this domain",
                                 description=f"Google Safe Browsing flags '{domain}' as {', '.join(t.lower().replace('_', ' ') for t in listed)}.",
                                 metadata={"registered_domain": domain, "threat_types": list(listed)}))
    elif listed is None and safe_browsing.enabled():
        note = "The Google Safe Browsing check was unavailable, so the domain was not checked against its threat list."
        limit = f"{limit} {note}" if limit else note
    return evidence, limit


def _certificate_evidence(domain: str) -> tuple[list[Evidence], str | None]:
    info = _crtsh(domain)
    if info is None:
        return [], "The online certificate check was unavailable, so the domain's history was not checked."
    count, first_seen = info
    days = age_days(first_seen)
    evidence: list[Evidence] = []
    if count == 0:
        evidence.append(Evidence(source="reputation", category="no_certificate_history", severity=Severity.low, confidence=0.4,
                                 title="No public certificate history for this domain",
                                 description="No TLS certificate for this domain appears in public certificate logs. Many new or temporary sites have none.",
                                 metadata={"registered_domain": domain}))
    elif days is not None and days < 30:
        evidence.append(Evidence(source="reputation", category="domain_very_new", severity=Severity.medium, confidence=0.6,
                                 title="Domain's first certificate is less than 30 days old",
                                 description=f"'{domain}' first received a certificate {days} days ago. Phishing sites are often brand new.",
                                 metadata={"registered_domain": domain, "age_days": days}))
    elif days is not None and is_institutional(domain) and days >= 365:
        evidence.append(Evidence(source="reputation", category="established_institution", severity=Severity.informational, confidence=0.8,
                                 title="Established institutional domain",
                                 description=f"'{domain}' is under an institutional suffix and has certificates going back {days} days.",
                                 metadata={"registered_domain": domain, "age_days": days}))
    return evidence, None
