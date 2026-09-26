"""Install the promoted model artifact from a trusted release location and verify it against the release manifest.

Never trains. The artifact must match `artifact_sha256` in models/release/v3_release_manifest.json byte for byte;
otherwise nothing is installed and the command fails (so an image build or deployment fails loudly).

Sources, tried in order:
  1. --from-dir DIR      a directory that already contains trustshield_text_v3.joblib (local build / CI cache)
  2. --url URL           an HTTPS URL, e.g. a GitHub Release asset (redirects followed; https only; size-capped)

Usage:  python scripts/fetch_model_artifact.py --from-dir release-artifacts
        python scripts/fetch_model_artifact.py --url https://github.com/<org>/<repo>/releases/download/<tag>/trustshield_text_v3.joblib
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODEL = "trustshield_text_v3"  # default; --model trustshield_text_v1 installs the rollback artifact
MAX_BYTES = 50 * 1024 * 1024


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


class _HttpsOnlyRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not newurl.lower().startswith("https://"):
            raise urllib.error.URLError(f"refusing non-HTTPS redirect to {newurl.split('?')[0]}")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download(url: str, destination: Path) -> None:
    if not url.lower().startswith("https://"):
        raise SystemExit("refusing a non-HTTPS artifact URL")
    opener = urllib.request.build_opener(_HttpsOnlyRedirects)
    total = 0
    with opener.open(url, timeout=60) as response, destination.open("wb") as out:  # noqa: S310 (https enforced above)
        while True:
            chunk = response.read(1 << 20)
            if not chunk:
                break
            total += len(chunk)
            if total > MAX_BYTES:
                raise SystemExit("artifact exceeds the size cap; refusing")
            out.write(chunk)


def install(source: Path, manifest_path: Path, dest_dir: Path, model: str = MODEL) -> Path:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    expected = manifest["models"][model]
    actual = sha256(source)
    if actual != expected["artifact_sha256"]:
        raise SystemExit(f"SHA-256 mismatch: got {actual}, release manifest expects {expected['artifact_sha256']}. Not installed.")
    dest_dir.mkdir(parents=True, exist_ok=True)
    target = dest_dir / f"{model}.joblib"
    partial = target.with_suffix(".joblib.part")
    shutil.copyfile(source, partial)
    os.replace(partial, target)  # atomic: a half-written artifact is never visible
    return target


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--from-dir", type=Path)
    parser.add_argument("--url")
    parser.add_argument("--model", default=MODEL, choices=("trustshield_text_v3", "trustshield_text_v1"))
    parser.add_argument("--manifest", type=Path, default=ROOT / "models" / "release" / "v3_release_manifest.json")
    parser.add_argument("--dest", type=Path, default=ROOT / "models" / "trained")
    args = parser.parse_args(argv)
    candidate = args.from_dir / f"{args.model}.joblib" if args.from_dir else None
    if candidate is not None and candidate.is_file():
        installed = install(candidate, args.manifest, args.dest, args.model)
    elif args.url:
        with tempfile.TemporaryDirectory() as tmp:
            downloaded = Path(tmp) / f"{args.model}.joblib"
            download(args.url, downloaded)
            installed = install(downloaded, args.manifest, args.dest, args.model)
    else:
        print("no artifact source available: put trustshield_text_v3.joblib in --from-dir or pass --url", file=sys.stderr)
        return 2
    print(f"installed and verified {installed.name} ({sha256(installed)[:16]}...)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
