"""Convert bounded browser telemetry into canonical fusion Evidence objects."""
from __future__ import annotations

import re
from urllib.parse import urlsplit

import tldextract

from services.analysis.evidence.models import Evidence, Severity
from services.analysis.url_analysis.brands import BRANDS, TEXT_CLAIMS
from .telemetry import clean_text

_extract = tldextract.TLDExtract(suffix_list_urls=(), cache_dir=None)

_STRENGTH = {
    "redirect_chain_unexpected": "moderate", "cross_domain_redirect": "moderate",
    "credential_form_detected": "strong", "password_field": "strong", "otp_field": "strong",
    "card_field": "strong", "brand_domain_mismatch_page": "strong", "js_auto_download": "strong",
    "dialog_abuse": "moderate", "popup_storm": "moderate", "page_blocks_automation": "weak",
    "load_timeout": "weak", "tls_error": "weak", "non_https_login_form": "moderate",
    "hidden_iframe_form": "strong", "blocked_by_policy": "weak", "download_attempt": "strong",
}


def _registered(url: str) -> str:
    host = (urlsplit(url).hostname or "").rstrip(".").lower()
    ext = _extract(host)
    return (ext.top_domain_under_public_suffix or host).lower()


def _ev(category: str, severity: Severity, confidence: float, title: str, description: str, **metadata) -> Evidence:
    metadata = {**metadata, "strength_class": _STRENGTH.get(category, "weak")}
    return Evidence(source="web_sandbox", category=category, severity=severity, confidence=confidence,
                    title=clean_text(title), description=clean_text(description, 1_000), metadata=metadata)


def telemetry_to_evidence(telemetry: dict, *, initial_url: str | None = None) -> list[Evidence]:
    evidence: list[Evidence] = []
    initial = initial_url or telemetry.get("initial_url", "")
    final = telemetry.get("final_url", "") or initial
    redirects = telemetry.get("redirects") or []
    if len(redirects) > 1:
        evidence.append(_ev("redirect_chain_unexpected", Severity.medium, 0.85,
                            "The link used multiple redirects", f"Observed {len(redirects)} navigation hops.", hops=len(redirects)))
    if _registered(initial) and _registered(final) and _registered(initial) != _registered(final):
        evidence.append(_ev("cross_domain_redirect", Severity.high, 0.9,
                            "The page redirected to a different registered domain",
                            f"The final page is on {_registered(final)} instead of {_registered(initial)}.",
                            initial_registered_domain=_registered(initial), final_registered_domain=_registered(final)))
    forms = telemetry.get("forms") or []
    kinds = {str(item.get("field_type", "")).lower() for form in forms for item in form.get("fields", [])}
    if forms and any(k in kinds for k in {"password", "otp", "card", "cvv", "upi"}):
        evidence.append(_ev("credential_form_detected", Severity.high, 0.95,
                            "The page contains a form requesting sensitive credentials",
                            "The browser observed sensitive fields but did not fill or submit them.", field_types=sorted(kinds)))
    field_map = {
        "password": ("password_field", "Password field detected"),
        "otp": ("otp_field", "One-time-password field detected"),
        "card": ("card_field", "Payment-card field detected"),
        "cvv": ("card_field", "Card security-code field detected"),
        "upi": ("card_field", "UPI identifier field detected"),
    }
    seen_categories: set[str] = set()
    for kind in sorted(kinds):
        if kind in field_map:
            category, title = field_map[kind]
            if category in seen_categories:
                continue
            seen_categories.add(category)
            evidence.append(_ev(category, Severity.high, 0.9, title,
                                "Sensitive input was observed; no value was collected or submitted.", field_type=kind))
    text = f"{telemetry.get('title', '')} {telemetry.get('visible_text', '')}".lower()
    final_domain = _registered(final)
    for key, (brand, officials) in BRANDS.items():
        claim = TEXT_CLAIMS.get(key)
        if claim and re.search(claim, text) and final_domain not in officials:
            evidence.append(_ev("brand_domain_mismatch_page", Severity.high, 0.9,
                                f"Page appears to reference {brand} on an unrelated domain",
                                f"The final registered domain is {final_domain}, not a known {brand} domain.",
                                brand=brand, final_registered_domain=final_domain, page_text_untrusted=True))
            break
    downloads = telemetry.get("downloads") or []
    if downloads:
        evidence.append(_ev("download_attempt", Severity.high, 0.9, "The page attempted a file download",
                            "The download was quarantined, hashed, and deleted without execution.", count=len(downloads)))
    if any(d.get("trigger") == "javascript" for d in downloads):
        evidence.append(_ev("js_auto_download", Severity.high, 0.9, "JavaScript triggered an automatic download",
                            "The download was observed only; no downloaded file was opened."))
    if int(telemetry.get("dialogs", 0) or 0) >= 3:
        evidence.append(_ev("dialog_abuse", Severity.medium, 0.8, "The page repeatedly opened JavaScript dialogs",
                            "Dialogs were automatically dismissed."))
    if int(telemetry.get("popups", 0) or 0) >= 3:
        evidence.append(_ev("popup_storm", Severity.medium, 0.8, "The page repeatedly opened popups",
                            "New windows were blocked and closed."))
    if telemetry.get("page_blocks_automation"):
        evidence.append(_ev("page_blocks_automation", Severity.informational, 0.8,
                            "The page may block automated observation",
                            "This lowers confidence in what could be verified; it does not by itself increase risk."))
    if telemetry.get("load_timeout"):
        evidence.append(_ev("load_timeout", Severity.informational, 0.8, "The page did not finish loading within the limit",
                            "Dynamic content may not have been observed."))
    if telemetry.get("tls_error"):
        evidence.append(_ev("tls_error", Severity.informational, 0.9, "The browser reported a TLS error",
                            "The page could not be fully verified."))
    if any((form.get("action_scheme") == "http") and any(f.get("field_type") in {"password", "otp", "card", "cvv", "upi"} for f in form.get("fields", [])) for form in forms):
        evidence.append(_ev("non_https_login_form", Severity.high, 0.95, "Sensitive form submits over HTTP",
                            "Credentials would be exposed without transport encryption."))
    if any(form.get("hidden") for form in forms):
        evidence.append(_ev("hidden_iframe_form", Severity.high, 0.85, "A hidden frame contains a sensitive form",
                            "Hidden credential collection was observed without interacting with the frame."))
    for item in telemetry.get("blocked", [])[:20]:
        evidence.append(_ev("blocked_by_policy", Severity.informational, 0.95, "A browser request was blocked by sandbox policy",
                            "The request did not leave the validating proxy.", reason=item.get("reason", "policy")))
    return evidence
