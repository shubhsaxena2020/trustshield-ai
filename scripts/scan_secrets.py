"""Dependency-free secret scan of git-tracked files (CI gate). Exits 1 on any finding.

    python scripts/scan_secrets.py            # scans `git ls-files`
    python scripts/scan_secrets.py path ...   # scans given paths

Detects private keys, cloud/API tokens with recognisable prefixes, and generic `KEY=<long random value>` assignments.
`.env.example` placeholders and documentation strings are not flagged. Findings print file:line and the rule, never the value.
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RULES = {
    "private key block": re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----"),
    "aws access key id": re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    "github token": re.compile(r"\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36,}\b|\bgithub_pat_[A-Za-z0-9_]{60,}\b"),
    "slack token": re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}\b"),
    "openai/anthropic style key": re.compile(r"\bsk-(?:ant-|proj-)?[A-Za-z0-9_-]{32,}\b"),
    "google api key": re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b"),
    "vercel/render token": re.compile(r"\b(?:rnd_|vercel_)[A-Za-z0-9]{20,}\b"),
    "generic secret assignment": re.compile(
        r"(?i)\b(?:api[_-]?key|secret|token|passwd|password)\b\s*[:=]\s*['\"]?(?=[A-Za-z0-9/+_-]*\d)(?=[A-Za-z0-9/+_-]*[A-Za-z])[A-Za-z0-9/+_-]{28,}['\"]?"),
}
SKIP_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp", ".joblib", ".pkl", ".svg", ".lock", ".ico", ".woff", ".woff2"}
SKIP_FILES = {"package-lock.json", "requirements-lock.txt", "requirements-api.lock.txt"}
# Fixtures that intentionally contain fake credentials to exercise the redaction/analysis code.
ALLOW_PATH_PARTS = ("tests/fixtures", "datasets/", "demo/fixtures", "benchmarks/", "tests/unit/test_secret_scan_and_fetch.py")


def tracked_files() -> list[Path]:
    out = subprocess.check_output(["git", "ls-files"], cwd=ROOT, text=True)
    return [ROOT / line for line in out.splitlines() if line.strip()]


def scan(paths: list[Path]) -> list[tuple[str, int, str]]:
    findings: list[tuple[str, int, str]] = []
    for path in paths:
        rel = path.relative_to(ROOT).as_posix() if path.is_absolute() and ROOT in path.parents else path.as_posix()
        if path.suffix.lower() in SKIP_SUFFIXES or path.name in SKIP_FILES or any(part in rel for part in ALLOW_PATH_PARTS):
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for number, line in enumerate(text.splitlines(), 1):
            if path.name == ".env.example" or "example" in line.lower() and "=" not in line:
                continue
            for name, pattern in RULES.items():
                if pattern.search(line):
                    findings.append((rel, number, name))
    return findings


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    paths = [Path(a).resolve() for a in args] if args else tracked_files()
    findings = scan(paths)
    for rel, number, name in findings:
        print(f"{rel}:{number}: possible {name}")
    print(f"secret scan: {len(findings)} finding(s) in {len(paths)} file(s)")
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
