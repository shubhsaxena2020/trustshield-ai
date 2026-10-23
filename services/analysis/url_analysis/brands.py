"""Brand -> official registered domains. Compared with public-suffix-aware parsing, never substring."""
from __future__ import annotations

# key -> (display name, official registered domains)
BRANDS: dict[str, tuple[str, set[str]]] = {
    # SBI's own domains: sbi.co.in (corporate), onlinesbi.sbi and onlinesbi.com (internet banking), bank.sbi and sbi.bank.in
    # (current SBI domains per public reporting, 2025), sbicard.com, sbilife.co.in. Verify against sbi.co.in before a release.
    "sbi": ("State Bank of India", {"sbi.co.in", "onlinesbi.sbi", "onlinesbi.com", "bank.sbi", "sbi.bank.in", "sbicard.com", "sbilife.co.in"}),
    # *.bank.in: Indian banks are moving to .bank.in under an RBI directive. hdfcbank.com and icicibank.com redirect to
    # hdfc.bank.in and icici.bank.in (verified in a browser on 2026-10-06).
    "hdfc": ("HDFC Bank", {"hdfcbank.com", "hdfc.com", "hdfclife.com", "hdfcbank.net", "hdfc.bank.in"}),
    "icici": ("ICICI Bank", {"icicibank.com", "icicilombard.com", "iciciprulife.com", "icici.bank.in"}),
    # axisbank.com, kotak.com and pnbindia.in redirect to axis.bank.in, kotak.bank.in and pnb.bank.in (browser-verified 2026-10-06).
    "axis": ("Axis Bank", {"axisbank.com", "axis.bank.in"}),
    "kotak": ("Kotak Mahindra Bank", {"kotak.com", "kotakbank.com", "kotak.bank.in"}),
    "pnb": ("Punjab National Bank", {"pnbindia.in", "netpnb.com", "pnb.bank.in"}),
    "paytm": ("Paytm", {"paytm.com", "paytmbank.com"}),
    "phonepe": ("PhonePe", {"phonepe.com"}),
    "amazon": ("Amazon", {"amazon.in", "amazon.com", "amazonpay.in"}),
    "flipkart": ("Flipkart", {"flipkart.com"}),
    "google": ("Google", {"google.com", "google.co.in", "gmail.com", "goo.gl"}),
    "microsoft": ("Microsoft", {"microsoft.com", "live.com", "office.com", "outlook.com"}),
    "facebook": ("Facebook/Meta", {"facebook.com", "fb.com", "meta.com", "instagram.com"}),
    "whatsapp": ("WhatsApp", {"whatsapp.com", "whatsapp.net", "wa.me"}),
    "netflix": ("Netflix", {"netflix.com"}),
    "indiapost": ("India Post", {"indiapost.gov.in"}),
    "uidai": ("UIDAI / Aadhaar", {"uidai.gov.in"}),
    "incometax": ("Income Tax Department", {"incometax.gov.in", "incometaxindia.gov.in"}),
    "paypal": ("PayPal", {"paypal.com", "paypal.me"}),
    "apple": ("Apple", {"apple.com", "icloud.com", "appleid.apple.com"}),
    "dhl": ("DHL", {"dhl.com", "dhl.co.in"}),
    "fedex": ("FedEx", {"fedex.com"}),
    "chase": ("Chase", {"chase.com"}),
    "wellsfargo": ("Wells Fargo", {"wellsfargo.com"}),
    "coinbase": ("Coinbase", {"coinbase.com"}),
    "binance": ("Binance", {"binance.com"}),
    "linkedin": ("LinkedIn", {"linkedin.com"}),
    "spotify": ("Spotify", {"spotify.com"}),
    # IRCTC (Indian Railway Catering and Tourism Corporation). Official site and customer support use irctc.co.in;
    # equery.irctc.co.in is the same registered domain. Verified via docs/ORCHESTRATOR-LOG.md and IRCTC support pages.
    "irctc": ("IRCTC", {"irctc.co.in"}),
}

# Phrases in free text that claim a brand (regex on lowercased text).
TEXT_CLAIMS: dict[str, str] = {
    "sbi": r"\b(sbi|state bank of india)\b",
    "hdfc": r"\bhdfc\b",
    "icici": r"\bicici\b",
    "axis": r"\baxis bank\b",
    "kotak": r"\bkotak\b",
    "pnb": r"\b(pnb|punjab national bank)\b",
    "paytm": r"\bpaytm\b",
    "phonepe": r"\bphone ?pe\b",
    "amazon": r"\bamazon\b",
    "flipkart": r"\bflipkart\b",
    "google": r"\bgoogle\b",
    "microsoft": r"\bmicrosoft\b",
    "facebook": r"\b(facebook|instagram)\b",
    "whatsapp": r"\bwhatsapp\b",
    "netflix": r"\bnetflix\b",
    "indiapost": r"\bindia post\b",
    "uidai": r"\b(uidai|aadhaar|aadhar)\b",
    "incometax": r"\bincome tax\b",
    "irctc": r"\birctc\b",
}


def is_official(brand_key: str, registered_domain: str) -> bool:
    return registered_domain.lower() in BRANDS[brand_key][1]
