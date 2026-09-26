"""Brand -> official registered domains. Compared with public-suffix-aware parsing, never substring."""
from __future__ import annotations

# key -> (display name, official registered domains)
BRANDS: dict[str, tuple[str, set[str]]] = {
    "sbi": ("State Bank of India", {"sbi.co.in", "onlinesbi.sbi", "sbi.bank.in", "sbicard.com", "sbilife.co.in"}),
    "hdfc": ("HDFC Bank", {"hdfcbank.com", "hdfc.com", "hdfclife.com", "hdfcbank.net"}),
    "icici": ("ICICI Bank", {"icicibank.com", "icicilombard.com", "iciciprulife.com"}),
    "axis": ("Axis Bank", {"axisbank.com"}),
    "kotak": ("Kotak Mahindra Bank", {"kotak.com", "kotakbank.com"}),
    "pnb": ("Punjab National Bank", {"pnbindia.in", "netpnb.com"}),
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
}


def is_official(brand_key: str, registered_domain: str) -> bool:
    return registered_domain.lower() in BRANDS[brand_key][1]
