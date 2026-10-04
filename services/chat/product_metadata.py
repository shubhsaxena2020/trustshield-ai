"""Authoritative TrustShield product metadata. The AI model is given this structured data and must not invent product facts
(team, names, model, capabilities, limitations, metrics) beyond what is recorded here. Source: the team's submission brief
and problem statement in the owner's Second Brain (TechExpo - TrustShield AI Team Explainer / Problem Statement)."""
from __future__ import annotations

PRODUCT_METADATA = {
    "name": "TrustShield AI",
    "team": "Scam Scan",
    "team_leader": "SHUBH SAXENA",
    "team_members": ["SUGANDHI AGARWAL", "STUTTI JHA", "SHRUTI SHUKLA", "VAISHNAVI MISHRA"],
    "event": "PSIT Tech Expo 2026 (screening 9-10 October 2026, finale 23-24 October 2026)",
    "project_title": "TrustShield AI: Privacy-First Multimodal Cyber-Fraud & Phishing Risk Analyzer",
    "theme": "Artificial Intelligence & Machine Learning, Cybersecurity Innovations, Edge AI",
    "tagline": "Think Before You Trust.",
    "purpose": "Help non-technical users find the warning signs in suspicious messages, screenshots, links and QR codes "
               "before they click, pay or share anything, and explain the evidence behind every result.",
    "problem": "Scams reach people through WhatsApp, SMS, email, screenshots, QR codes, shortened links, fake payment requests "
               "and impersonation. Most people cannot spot the signs in seconds, and existing tools are often single-channel, "
               "technical, or send content to the cloud.",
    "how_it_works": [
        "Input: pasted text, a screenshot upload (WhatsApp/SMS/email), a pasted link, or a QR code.",
        "Local extraction: OCR (Tesseract) reads screenshot text; a QR decoder reads QR contents without opening them.",
        "Deterministic rules: link structure, lookalike and shortened domains, IP-address URLs, urgency, impersonation and "
        "payment or OTP requests.",
        "Machine-learning classifier (trustshield_text_v3) with a calibrated confidence score.",
        "Risk score and verdict come from the rules and the classifier only. The language model explains the evidence; it "
        "does not decide whether something is a scam.",
        "Safe-action guidance: verify through the organisation's official app or website, never share an OTP, PIN or password, "
        "and report financial cyber fraud to the 1930 helpline or the NCRP portal (cybercrime.gov.in).",
    ],
    "why_it_matters": "Government data (PIB, NCRP) shows financial cyber-fraud complaints rose from about 2.63 lakh in 2021 "
                      "to over 24 lakh in 2025, with about Rs 22,495 crore reported in 2025.",
    "current_production_model": "trustshield_text_v3",
    "sealed_holdout_metrics": {"recall": "82.1%", "specificity": "78.3%", "precision": "82.1%", "f1": "82.1%",
                               "note": "These are one-time sealed-holdout results, not a live accuracy guarantee. The 90% "
                                      "recall/specificity goals were not met. Real-world performance varies by source/domain."},
    "capabilities_used": ["deterministic scam-pattern rules", "a text classification model (trustshield_text_v3)",
                          "static URL structure analysis (no network request)", "OCR for screenshots (Tesseract)",
                          "QR code decoding (the destination is read but never opened)"],
    "capabilities_not_used": ["threat/reputation intelligence lookups", "live webpage inspection in an isolated browser"],
    "architecture": "A conversational language model (DeepSeek V4.1 Flash, via the team's provider) is the chat assistant. "
                    "The TrustShield scanner (rules plus trustshield_text_v3) is a tool the assistant calls when content needs "
                    "checking. The scanner's verdict is authoritative; the assistant can explain it but cannot change the score.",
    "privacy": "TrustShield keeps no permanent history of what you send; the conversation lives in this browser tab. Avoid "
               "sharing real passwords, OTPs or card details.",
    "scope": "TrustShield answers cybersecurity, scam and general questions as a chat assistant, and it checks suspicious "
             "content with its scanner. It cannot open links, browse websites or look up reputation services.",
    "guarantees": "TrustShield never states a message is 100% safe or definitely fraudulent; it reports risk evidence and "
                  "uncertainty, not a guarantee.",
}
