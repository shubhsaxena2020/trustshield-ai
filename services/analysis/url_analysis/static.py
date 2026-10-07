"""Static URL analysis: inspects the URL string only. Never makes a network request."""
from __future__ import annotations

import ipaddress
import re
import unicodedata
from dataclasses import dataclass, field
from urllib.parse import unquote, urlsplit

import tldextract

from services.analysis.evidence.models import Evidence, Severity
from services.analysis.url_analysis.brands import BRANDS, is_official

# Bundled public-suffix snapshot only: no network call at runtime.
_extract = tldextract.TLDExtract(suffix_list_urls=(), cache_dir=None)

SHORTENERS = {
    "bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "cutt.ly", "rb.gy", "ow.ly", "buff.ly", "shorturl.at",
    "tiny.cc", "rebrand.ly", "bl.ink", "s.id", "v.gd", "t.ly", "lnkd.in", "wa.link", "shorturl.gg",
}
SUSPICIOUS_TLDS = {"xyz", "top", "click", "icu", "tk", "ml", "ga", "cf", "gq", "zip", "mov", "cyou", "buzz", "rest", "monster", "sbs", "cfd", "vip", "work", "support", "loan"}
# Tunnel services that expose a machine on a public address (no account needed for some). Matched on the registered domain.
TUNNEL_HOSTS = {"trycloudflare.com", "ngrok.io", "ngrok-free.app", "ngrok.app", "ngrok.dev", "serveo.net", "localhost.run",
                "loca.lt", "lhr.life", "pinggy.io", "pinggy.link", "localto.net", "telebit.io", "bore.pub", "portmap.io"}
# Free anonymous hosting. Weak signal only: many legitimate sites use these. Our own vercel.app is deliberately not listed.
FREE_HOSTS = {"netlify.app", "pages.dev", "github.io", "glitch.me", "repl.co", "replit.dev", "herokuapp.com", "web.app",
              "firebaseapp.com", "workers.dev", "duckdns.org", "000webhostapp.com", "wixsite.com", "weebly.com"}
EXEC_EXT = {"exe", "apk", "msi", "bat", "cmd", "scr", "jar", "vbs", "ps1", "js", "dmg", "pkg", "com", "lnk", "iso"}
ARCHIVE_EXT = {"zip", "rar", "7z", "gz", "tar"}
CRED_TERMS = {"login", "signin", "verify", "verification", "account", "secure", "update", "kyc", "payment", "confirm", "banking", "password", "otp"}
_DEFAULT_PORTS = {None, 80, 443}


@dataclass
class UrlStaticResult:
    valid: bool
    facts: dict = field(default_factory=dict)
    evidence: list[Evidence] = field(default_factory=list)


_NUMERIC_PART = re.compile(r"^(?:0x[0-9a-f]+|0[0-7]*|[1-9][0-9]*)$", re.IGNORECASE)


def _is_legacy_numeric_ip(host: str) -> bool:
    """True for hex/octal/decimal IPv4 spellings such as 0x7f000001, 2130706433 or 0177.0.0.1."""
    parts = host.split(".")
    if not 1 <= len(parts) <= 4 or not all(_NUMERIC_PART.match(part) for part in parts):
        return False
    try:
        values = [int(part, 16 if part.lower().startswith("0x") else 8 if len(part) > 1 and part.startswith("0") else 10) for part in parts]
    except ValueError:
        return False
    return all(v < 256 for v in values[:-1]) and values[-1] < 256 ** (5 - len(parts))


def _ev(category: str, sev: Severity, conf: float, title: str, desc: str, **meta) -> Evidence:
    return Evidence(source="url_static", category=category, severity=sev, confidence=conf, title=title, description=desc, metadata=meta)


def _levenshtein(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def _decode_host(host: str) -> str:
    labels = []
    for lab in host.split("."):
        if lab.startswith("xn--"):
            try:
                lab = lab.encode("ascii").decode("idna")
            except UnicodeError:
                pass
        labels.append(lab)
    return ".".join(labels)


def _scripts(label: str) -> set[str]:
    out = set()
    for ch in label:
        if ch.isalpha():
            try:
                out.add(unicodedata.name(ch).split()[0])
            except ValueError:
                out.add("UNKNOWN")
    return out


def analyze_url_static(url: str) -> UrlStaticResult:
    raw = (url or "").strip()
    if not raw or len(raw) > 2048 or any(c in raw for c in "\r\n\x00"):
        return UrlStaticResult(valid=False)

    ev: list[Evidence] = []
    scheme_match = re.match(r"^([a-zA-Z][a-zA-Z0-9+.-]*):", raw)
    has_scheme = bool(scheme_match and (raw[scheme_match.end():].startswith("//") or scheme_match.group(1).lower() in {"javascript", "data", "file", "vbscript", "ftp"}))
    if has_scheme and scheme_match.group(1).lower() not in {"http", "https"}:
        s = scheme_match.group(1).lower()
        ev.append(_ev("unsupported_scheme", Severity.high if s in {"javascript", "data", "vbscript", "file"} else Severity.medium, 0.9,
                      f"Unsupported or risky URL scheme ({s}:)", "Only http/https links are analyzed; other schemes can execute code or read local files.", scheme=s))
        return UrlStaticResult(valid=False, facts={"scheme": s}, evidence=ev)

    parse_target = raw if has_scheme else "http://" + raw
    try:
        parts = urlsplit(parse_target)
        port = parts.port
    except ValueError:
        return UrlStaticResult(valid=False)
    host = (parts.hostname or "").rstrip(".").lower()
    if not host:
        return UrlStaticResult(valid=False)

    scheme = parts.scheme.lower() if has_scheme else "unspecified"
    decoded_host = _decode_host(host)
    ext = _extract(decoded_host)
    registered = ext.top_domain_under_public_suffix if hasattr(ext, "top_domain_under_public_suffix") else ext.registered_domain
    registered = (registered or host).lower()
    # ".bank.in" is itself a public suffix, so the parser reports "bank.in" for sbi.bank.in: keep one label more (2026-10-06).
    if decoded_host.lower().endswith(".bank.in") and decoded_host.lower().count(".") >= 2:
        registered = ".".join(decoded_host.lower().split(".")[-3:])
    subdomain = ext.subdomain.lower()
    path = parts.path or ""
    facts = {"scheme": scheme, "hostname": host, "hostname_decoded": decoded_host, "registered_domain": registered,
             "subdomain": subdomain, "suffix": ext.suffix, "port": port, "path": path, "query": parts.query, "fragment": parts.fragment}

    # --- IP literal host
    try:
        ipaddress.ip_address(host)
        ev.append(_ev("ip_address_host", Severity.high, 0.85, "URL uses a raw IP address", "Legitimate services almost always use domain names; raw IPs are common in phishing and malware links.", host=host))
    except ValueError:
        if _is_legacy_numeric_ip(host):
            ev.append(_ev("ip_address_host", Severity.high, 0.85, "URL uses an obfuscated numeric IP address", "Hex, octal or single-number IP forms are browser-accepted spellings of raw IPs and are used to hide the destination.", host=host))

    # --- misleading userinfo (user@host)
    if parts.username:
        misleading = "." in parts.username
        ev.append(_ev("userinfo_in_url", Severity.high if misleading else Severity.medium, 0.85 if misleading else 0.6,
                      "URL contains 'username@' before the real host",
                      f"Everything before '@' is ignored by the browser; the real destination is '{host}'.", username=parts.username, real_host=host))

    # --- shortener
    if registered in SHORTENERS:
        ev.append(_ev("url_shortener", Severity.medium, 0.6, "Shortened link hides the real destination",
                      "The final destination is unknown until the link is expanded safely in isolation.", shortener=registered))

    # --- punycode / homograph
    if any(l.startswith("xn--") for l in host.split(".")):
        ev.append(_ev("punycode_host", Severity.medium, 0.6, "Domain uses Punycode (internationalized characters)", f"Decoded hostname: {decoded_host}", decoded=decoded_host))
    for lab in decoded_host.split("."):
        sc = _scripts(lab)
        if len(sc) > 1 or (sc and sc != {"LATIN"} and sc.isdisjoint({"CJK", "DEVANAGARI", "ARABIC", "HANGUL", "HIRAGANA", "KATAKANA", "THAI", "BENGALI", "TAMIL"})):
            ev.append(_ev("homograph_domain", Severity.high, 0.85, "Domain mixes look-alike character sets",
                          f"Label '{lab}' uses {sorted(sc)}; attackers swap letters for visually identical ones.", label=lab, scripts=sorted(sc)))
            break

    # --- brand impersonation by domain
    tokens = set(re.split(r"[.\-_]", decoded_host))
    reg_label = registered.split(".")[0]
    flagged = False
    for key, (name, officials) in BRANDS.items():
        if registered in officials:
            continue
        # "bank" is generic (bank.sbi, sbi.bank.in): it must not make every .bank.in host look like SBI (2026-10-06).
        official_labels = {d.split(".")[0] for d in officials} - {"bank"}
        if key in tokens or (tokens & official_labels):
            ev.append(_ev("domain_mismatch", Severity.high, 0.9, f"Domain references {name} but is not an official {name} domain",
                          f"The registered domain is '{registered}', which is not one of {name}'s known domains.", brand=name, registered_domain=registered))
            flagged = True
            break
    if not flagged:
        label_tokens = {p for p in re.split(r"[-.]", reg_label) if p}
        for key, (name, officials) in BRANDS.items():
            for off in officials:
                ol = off.split(".")[0]
                near_token = any(len(p) >= 5 and p != ol and _levenshtein(p, ol) <= 1 for p in label_tokens)
                short_brand = len(ol) >= 3 and len(reg_label) >= 4 and reg_label != ol and _levenshtein(reg_label, ol) <= 1  # "sbii" for "sbi" (2026-10-06)
                if registered not in officials and ((len(ol) >= 5 and (near_token or (reg_label != ol and _levenshtein(reg_label, ol) <= 2 and abs(len(reg_label) - len(ol)) <= 2))) or short_brand):
                    ev.append(_ev("lookalike_domain", Severity.high, 0.85, f"Domain closely resembles {name}'s domain",
                                  f"'{registered}' differs from '{off}' by a few characters (typosquatting).", brand=name, official=off, registered_domain=registered))
                    flagged = True
                    break
            if flagged:
                break

    # --- hosting signals (2026-10-07). Tunnels give a public address to any machine in seconds (Cofense, Securonix and Duo report
    # TryCloudflare abused for credential theft and malware). Free anonymous hosts are weaker, because many real sites use them.
    if any(registered == s or registered.endswith("." + s) for s in TUNNEL_HOSTS):
        ev.append(_ev("disposable_tunnel_host", Severity.high, 0.8, "Link uses a temporary tunnel address",
                      f"'{registered}' is a tunnel service that gives any computer a public web address in seconds. Attackers use these for fake login pages and tracking pages.",
                      registered_domain=registered))
    elif any(registered.endswith("." + s) for s in FREE_HOSTS):
        ev.append(_ev("free_hosting_subdomain", Severity.low, 0.4, "Link is on a free anonymous hosting service",
                      f"'{registered}' is on a free hosting service that anyone can use. Weak signal on its own.", registered_domain=registered))
    # --- structure signals
    if ext.suffix.split(".")[-1] in SUSPICIOUS_TLDS:
        ev.append(_ev("suspicious_tld", Severity.low, 0.5, f"Top-level domain '.{ext.suffix}' is frequently abused", "Weak signal on its own; many legitimate sites are unaffected.", tld=ext.suffix))
    if subdomain and subdomain.count(".") + 1 >= 4:
        ev.append(_ev("excessive_subdomains", Severity.medium, 0.5, "Unusually deep subdomain chain", f"Subdomain '{subdomain}' can bury the real domain '{registered}'.", subdomain=subdomain))
    if len(host) > 45:
        ev.append(_ev("long_hostname", Severity.low, 0.4, "Unusually long hostname", f"Hostname is {len(host)} characters.", length=len(host)))
    if reg_label.count("-") >= 3:
        ev.append(_ev("excessive_hyphens", Severity.low, 0.4, "Domain contains many hyphens", "Hyphen-stuffed names are a common lookalike tactic.", domain=registered))
    if port not in _DEFAULT_PORTS:
        ev.append(_ev("unusual_port", Severity.medium, 0.5, f"Non-standard port :{port}", "Legitimate public sites rarely use custom ports.", port=port))
    if scheme == "http":
        ev.append(_ev("no_https", Severity.low, 0.4, "Link does not use HTTPS", "Traffic is unencrypted; not proof of fraud on its own.", scheme=scheme))
    decoded_path = unquote(path)
    if "%" in path and decoded_path != path and re.search(r"%(?:2e|2f|5c|00|0d|0a)", path, re.I):
        ev.append(_ev("encoded_characters", Severity.low, 0.4, "URL path contains encoded control/path characters", "Encoding can hide traversal or redirects.", path=path[:120]))
    last = decoded_path.rsplit("/", 1)[-1].lower()
    fext = last.rsplit(".", 1)[-1] if "." in last else ""
    if fext in EXEC_EXT:
        ev.append(_ev("executable_download", Severity.high, 0.8, f"Link points to an installable/executable file (.{fext})", "Do not download or open files from unverified links; .apk/.exe are common malware carriers.", extension=fext))
    elif fext in ARCHIVE_EXT:
        ev.append(_ev("archive_download", Severity.medium, 0.5, f"Link points to an archive (.{fext})", "Archives can conceal executables.", extension=fext))
    found = sorted(t for t in CRED_TERMS if re.search(rf"(?<![a-z]){t}(?![a-z])", (decoded_path + "?" + parts.query).lower()))
    if found:
        ev.append(_ev("credential_path_terms", Severity.informational, 0.3, "URL path mentions account/login terms", f"Terms: {', '.join(found)}. Weak signal by itself.", terms=found))
    if len(raw) > 200:
        ev.append(_ev("very_long_url", Severity.low, 0.3, "Very long URL", f"URL is {len(raw)} characters.", length=len(raw)))

    return UrlStaticResult(valid=True, facts=facts, evidence=ev)
