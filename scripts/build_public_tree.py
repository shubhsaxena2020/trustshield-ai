"""Build the clean, publishable tree from `git ls-files` (no git history), applying the publication policy.

    python scripts/build_public_tree.py --out ../trustshield-ai-public

Policy (docs/PUBLICATION_AUDIT.md): keep source, docs, CI, deployment files, manifests/hashes/provenance, synthetic fixtures and
demo assets; drop every file that carries raw message text from real corpora or from the spent v1 test / v2 holdout, corpora of
unclear redistribution rights, and any file with a `text`/`message` column. Fails if a secret is detected or an excluded path leaks.
"""
from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

EXCLUDE_GLOBS = {
    "datasets/processed/**": "real/public SMS corpora and spent v1 test text; redistribution rights unclear or protected",
    "datasets/raw/holdout_v2/**": "raw v2 holdout candidates (spent, protected)",
    "datasets/raw/synthetic/codex_holdout.jsonl": "raw v2 holdout synthetic rows (spent, protected)",
    "datasets/holdout/*/holdout.jsonl": "sealed v2 holdout rows (spent, protected)",
    "datasets/holdout/*/audit_blind/**": "v2 holdout audit inputs/outputs contain holdout text",
    "datasets/holdout/*/audit_rejected_templated/**": "v2 holdout audit files contain holdout text",
    "models/evaluation/**/error_analysis*.md": "manual error analyses quote spent test/validation messages",
    "models/trained/**": "model artifacts are distributed only through the private release mechanism",
    "**/*.joblib": "model artifacts are distributed only through the private release mechanism",
}
TEXT_HEADERS = {"text", "message", "raw_text"}
MAX_FILE_BYTES = 2 * 1024 * 1024


def _regex(pattern: str) -> re.Pattern:
    out, i = "", 0
    while i < len(pattern):
        if pattern.startswith("**/", i):
            out += "(?:.*/)?"
            i += 3
        elif pattern.startswith("**", i):
            out += ".*"
            i += 2
        elif pattern[i] == "*":
            out += "[^/]*"
            i += 1
        else:
            out += re.escape(pattern[i])
            i += 1
    return re.compile("^" + out + "$")


_RULES = [(_regex(pattern), reason) for pattern, reason in EXCLUDE_GLOBS.items()]


def rule_for(path: str) -> str | None:
    for regex, reason in _RULES:
        if regex.match(path):
            return reason
    return None


def has_text_column(path: Path) -> bool:
    if path.suffix != ".csv":
        return False
    try:
        header = path.open(encoding="utf-8").readline().strip().lower().split(",")
    except (OSError, UnicodeDecodeError):
        return False
    return bool(TEXT_HEADERS & set(header))


def tracked() -> list[str]:
    return subprocess.check_output(["git", "ls-files"], cwd=ROOT, text=True).splitlines()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    out = args.out.resolve()
    if out.exists() and any(out.iterdir()):
        print("refusing: output directory is not empty", file=sys.stderr)
        return 2
    included, excluded = [], {}
    for rel in tracked():
        source = ROOT / rel
        reason = rule_for(rel) or ("file carries a text/message column (raw message content)" if has_text_column(source) else None)
        if reason:
            excluded.setdefault(reason, []).append(rel)
            continue
        if source.stat().st_size > MAX_FILE_BYTES:
            excluded.setdefault("larger than the 2 MiB publication cap", []).append(rel)
            continue
        target = out / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        included.append(rel)
    readme = out / "datasets" / "README.md"
    readme.parent.mkdir(parents=True, exist_ok=True)
    readme.write_text(
        "# datasets/\n\nThis published tree deliberately omits corpora with real message text, the spent v1 test split and the sealed v2\n"
        "holdout rows. Kept: synthetic training rows under `raw/synthetic/`, and the v2 holdout **manifest, access log and SEALED.md**\n"
        "(hashes/provenance only). Manifests under `models/release/` and `datasets/holdout/*/manifest.json` let the owner verify that the\n"
        "unpublished data matches what the models were built from. See `docs/PUBLICATION_AUDIT.md` and `docs/SPENT_DATA_POLICY.md`.\n",
        encoding="utf-8")
    included.append("datasets/README.md")
    manifest = {
        "files": {rel: hashlib.sha256((out / rel).read_bytes()).hexdigest() for rel in sorted(included)},
        "excluded": {reason: {"count": len(paths), "paths": sorted(paths)} for reason, paths in sorted(excluded.items())},
    }
    (out / "PUBLICATION_MANIFEST.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    leaked = [rel for rel in included if rule_for(rel)]
    if leaked:
        print("LEAK: excluded paths present:", leaked[:5], file=sys.stderr)
        return 1
    sys.path.insert(0, str(ROOT))
    from scripts import scan_secrets

    scan_secrets.ROOT = out
    findings = scan_secrets.scan([out / r for r in included if (out / r).is_file()])
    print(f"secret scan of the public tree: {len(findings)} finding(s)")
    if findings:
        for rel, number, name in findings:
            print(f"  {rel}:{number}: possible {name}")
        return 1
    total = sum((out / r).stat().st_size for r in included) / 1024 / 1024
    print(f"public tree: {len(included)} files, {total:.1f} MiB; excluded {sum(len(v) for v in excluded.values())} tracked files in {len(excluded)} categories")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
