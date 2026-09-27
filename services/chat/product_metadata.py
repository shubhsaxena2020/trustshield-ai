"""Authoritative TrustShield product metadata for Product Q&A. The AI provider is given this structured data and must not
invent product facts (name, model, capabilities, limitations, sealed metrics) beyond what is recorded here."""
from __future__ import annotations

PRODUCT_METADATA = {
    "name": "TrustShield AI",
    "team": "Scam Scan",
    "tagline": "Think Before You Trust.",
    "purpose": "A privacy-first multimodal cyber-fraud and phishing risk analyzer: checks suspicious text, links, "
               "screenshots and QR codes and explains the evidence behind the result.",
    "current_production_model": "trustshield_text_v3",
    "sealed_holdout_metrics": {"recall": "82.1%", "specificity": "78.3%", "precision": "82.1%", "f1": "82.1%",
                               "note": "These are one-time sealed-holdout results, not a live accuracy guarantee. The 90% "
                                      "recall/specificity goals were not met. Real-world performance varies by source/domain."},
    "capabilities_used": ["deterministic scam-pattern rules", "a text classification model (trustshield_text_v3)",
                          "static URL structure analysis (no network request)", "OCR for screenshots (Tesseract)",
                          "QR code decoding (the destination is read but never opened)"],
    "capabilities_not_used": ["threat/reputation intelligence lookups", "live webpage inspection in an isolated browser"],
    "privacy": "Analysis happens for the current session only; TrustShield keeps no permanent history of what you send. "
              "Avoid sharing real passwords, OTPs or card details.",
    "is_chatgpt": False,
    "not_a_general_assistant": "TrustShield's conversation stays focused on cybersecurity, scams and its own product; it "
                               "is not a general-purpose assistant.",
    "guarantees": "TrustShield never states a message is 100% safe or definitely fraudulent; it reports risk evidence and "
                 "uncertainty, not a guarantee.",
}
