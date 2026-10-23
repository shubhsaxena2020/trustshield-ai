/* TrustShield offline bundle — curated, hand-picked data for offline-mode decisions and offline testing.
 * Classic script, no ESM import/export (see CONTRACT.md's dual CommonJS/global export footer pattern).
 * This file feeds the allowlist (child 2) and risk-policy (child 3) workers. It is intentionally small and
 * hand-maintained, not auto-scraped, and ships with the extension so offline mode never needs network access.
 *
 * Exported shape: { BRANDS: {brandKey: {name, officialDomains: [...]}}, SUSPICIOUS_PATTERNS: [...] }
 *
 * (a) SUSPICIOUS_PATTERNS — a short, hand-picked list of EXAMPLE/SYNTHETIC domain substrings and
 *     suspicious free-hosting subdomains, for offline-mode heuristic testing only. This is NOT a live,
 *     exhaustive, or authoritative phishing blocklist, and must never be presented to a user as one — it
 *     exists so signals.js (child 3) and the test suite (child 8) have small known fixtures to exercise
 *     "lookalike substring" / "free hosting" style heuristics against with zero network access. A handful
 *     of entries mirror the known misses already documented in docs/finals/MISSION.md
 *     ("sbl.co.in", "irctc-refund.xyz", "sbii.co.in") so those exact regressions have an offline fixture.
 * (b) BRANDS — a hand-copied mirror of services/analysis/url_analysis/brands.py's BRANDS table: same brand
 *     keys, same display names, same official registered domains. Do NOT add/invent brands here; if the
 *     Python table changes, update this object to match by hand (there is no runtime dependency between
 *     the two languages).
 */
(function (root) {
  "use strict";

  // ---- (b) brand -> official domains, hand-copied from services/analysis/url_analysis/brands.py ----------------
  // Keep this in sync by hand with brands.py's BRANDS dict. Same keys, same names, same official domains.
  var BRANDS = {
    sbi: {
      name: "State Bank of India",
      officialDomains: ["sbi.co.in", "onlinesbi.sbi", "onlinesbi.com", "bank.sbi", "sbi.bank.in", "sbicard.com", "sbilife.co.in"],
    },
    hdfc: {
      name: "HDFC Bank",
      officialDomains: ["hdfcbank.com", "hdfc.com", "hdfclife.com", "hdfcbank.net", "hdfc.bank.in"],
    },
    icici: {
      name: "ICICI Bank",
      officialDomains: ["icicibank.com", "icicilombard.com", "iciciprulife.com", "icici.bank.in"],
    },
    axis: {
      name: "Axis Bank",
      officialDomains: ["axisbank.com", "axis.bank.in"],
    },
    kotak: {
      name: "Kotak Mahindra Bank",
      officialDomains: ["kotak.com", "kotakbank.com", "kotak.bank.in"],
    },
    pnb: {
      name: "Punjab National Bank",
      officialDomains: ["pnbindia.in", "netpnb.com", "pnb.bank.in"],
    },
    paytm: {
      name: "Paytm",
      officialDomains: ["paytm.com", "paytmbank.com"],
    },
    phonepe: {
      name: "PhonePe",
      officialDomains: ["phonepe.com"],
    },
    amazon: {
      name: "Amazon",
      officialDomains: ["amazon.in", "amazon.com", "amazonpay.in"],
    },
    flipkart: {
      name: "Flipkart",
      officialDomains: ["flipkart.com"],
    },
    google: {
      name: "Google",
      officialDomains: ["google.com", "google.co.in", "gmail.com", "goo.gl"],
    },
    microsoft: {
      name: "Microsoft",
      officialDomains: ["microsoft.com", "live.com", "office.com", "outlook.com"],
    },
    facebook: {
      name: "Facebook/Meta",
      officialDomains: ["facebook.com", "fb.com", "meta.com", "instagram.com"],
    },
    whatsapp: {
      name: "WhatsApp",
      officialDomains: ["whatsapp.com", "whatsapp.net", "wa.me"],
    },
    netflix: {
      name: "Netflix",
      officialDomains: ["netflix.com"],
    },
    indiapost: {
      name: "India Post",
      officialDomains: ["indiapost.gov.in"],
    },
    uidai: {
      name: "UIDAI / Aadhaar",
      officialDomains: ["uidai.gov.in"],
    },
    incometax: {
      name: "Income Tax Department",
      officialDomains: ["incometax.gov.in", "incometaxindia.gov.in"],
    },
    paypal: {
      name: "PayPal",
      officialDomains: ["paypal.com", "paypal.me"],
    },
    apple: {
      name: "Apple",
      officialDomains: ["apple.com", "icloud.com", "appleid.apple.com"],
    },
    dhl: {
      name: "DHL",
      officialDomains: ["dhl.com", "dhl.co.in"],
    },
    fedex: {
      name: "FedEx",
      officialDomains: ["fedex.com"],
    },
    chase: {
      name: "Chase",
      officialDomains: ["chase.com"],
    },
    wellsfargo: {
      name: "Wells Fargo",
      officialDomains: ["wellsfargo.com"],
    },
    coinbase: {
      name: "Coinbase",
      officialDomains: ["coinbase.com"],
    },
    binance: {
      name: "Binance",
      officialDomains: ["binance.com"],
    },
    linkedin: {
      name: "LinkedIn",
      officialDomains: ["linkedin.com"],
    },
    spotify: {
      name: "Spotify",
      officialDomains: ["spotify.com"],
    },
    irctc: {
      name: "IRCTC",
      officialDomains: ["irctc.co.in"],
    },
  };

  // ---- (a) curated EXAMPLE/SYNTHETIC suspicious patterns for offline-mode testing --------------------------------
  // "example: true" on every entry — none of this is a live feed. "kind" groups entries for whatever signal
  // evaluator wants to consume a subset (e.g. only "free_hosting_suffix" entries for a hostname-suffix check).
  var SUSPICIOUS_PATTERNS = [
    { pattern: "irctc-refund", kind: "substring", example: true,
      note: "brand+action lure substring; mirrors the known-miss fixture 'irctc-refund.xyz' in docs/finals/MISSION.md" },
    { pattern: "sbi-verify", kind: "substring", example: true,
      note: "brand+verify lure substring, synthetic example only" },
    { pattern: "sbii", kind: "substring", example: true,
      note: "doubled-letter lookalike of 'sbi'; mirrors the known-miss fixture 'sbii.co.in'" },
    { pattern: "sbl", kind: "substring", example: true,
      note: "visually-similar letter swap of 'sbi' -> 'sbl'; mirrors the known-miss fixture 'sbl.co.in'" },
    { pattern: "kyc-update", kind: "substring", example: true,
      note: "common Indian-banking-scam lure phrase, synthetic example only" },
    { pattern: "account-suspended", kind: "substring", example: true,
      note: "generic urgency lure phrase, synthetic example only" },
    { pattern: "secure-login", kind: "substring", example: true,
      note: "generic credential-phishing lure phrase, synthetic example only" },
    { pattern: ".pages.dev", kind: "free_hosting_suffix", example: true,
      note: "Cloudflare Pages free subdomain suffix; frequently abused for disposable phishing pages" },
    { pattern: ".weebly.com", kind: "free_hosting_suffix", example: true,
      note: "free site-builder subdomain suffix; frequently abused" },
    { pattern: ".000webhostapp.com", kind: "free_hosting_suffix", example: true,
      note: "free hosting subdomain suffix; frequently abused" },
    { pattern: ".glitch.me", kind: "free_hosting_suffix", example: true,
      note: "free app-hosting subdomain suffix; frequently abused for short-lived phishing pages" },
    { pattern: ".repl.co", kind: "free_hosting_suffix", example: true,
      note: "free coding-sandbox subdomain suffix; frequently abused" },
    { pattern: ".netlify.app", kind: "free_hosting_suffix", example: true,
      note: "free deployment subdomain suffix; frequently abused for throwaway phishing pages" },
    { pattern: "bit.ly", kind: "shortener", example: true,
      note: "URL shortener; hides the real destination, a weak signal on its own, never sufficient alone" },
    { pattern: "tinyurl.com", kind: "shortener", example: true,
      note: "URL shortener; hides the real destination, a weak signal on its own, never sufficient alone" },
  ];

  var api = {
    BRANDS: BRANDS,
    SUSPICIOUS_PATTERNS: SUSPICIOUS_PATTERNS,
  };

  if (typeof module !== "undefined" && module.exports) {
    module.exports = api; // CommonJS, used by node-side unit tests (child 8)
  } else {
    root.TrustShieldOfflineBundle = api; // classic-script global, used by extension contexts
  }
})(typeof globalThis !== "undefined" ? globalThis : this);
