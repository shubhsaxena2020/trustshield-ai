#!/usr/bin/env python3
"""Build extension/dist/trustshield-extension.zip from the extension/ source tree.

Standalone: run as

    python extension/build.py

from the repo root (any Python 3 on PATH; no third-party dependencies, stdlib `zipfile` only -- there is
no `zip` CLI binary on this machine, confirmed). All paths below are resolved relative to this script's own
location (not the current working directory), so it also works run as `python build.py` from inside
`extension/`.

Zips:
  - extension/manifest.json   (written by the coordinator at integration time, after every child lands)
  - extension/src/**          (every file, recursively -- walked at build time, not a hardcoded list, so
                                this script keeps working unmodified as every child's files land)

Excludes (never zipped even if somehow present under src/):
  - extension/tests/              (child 8's Node/Playwright test suite -- never shipped)
  - extension/CONTRACT.md         (internal planning doc)
  - extension/manifest.SAMPLE.json (sample only; the coordinator's real manifest.json ships instead)

Run this AFTER extension/manifest.json exists -- it does not write or guess one.
"""
from __future__ import annotations

import sys
import zipfile
from pathlib import Path

EXTENSION_DIR = Path(__file__).resolve().parent
SRC_DIR = EXTENSION_DIR / "src"
MANIFEST_PATH = EXTENSION_DIR / "manifest.json"
DIST_DIR = EXTENSION_DIR / "dist"
OUTPUT_ZIP = DIST_DIR / "trustshield-extension.zip"

# Defensive excludes -- src/ should never actually contain these, but skip them if it somehow does.
EXCLUDED_DIR_NAMES = {"tests"}
EXCLUDED_FILE_NAMES = {"CONTRACT.md", "manifest.SAMPLE.json", "Thumbs.db", ".DS_Store"}


def iter_src_files(src_dir: Path):
    """Yield every real file under src_dir, recursively, skipping excluded dirs/files, in sorted order
    (sorted so the zip's member order -- and therefore its bytes -- is deterministic build to build)."""
    for path in sorted(src_dir.rglob("*")):
        if not path.is_file():
            continue
        relative_parts = path.relative_to(src_dir).parts
        if any(part in EXCLUDED_DIR_NAMES for part in relative_parts[:-1]):
            continue
        if path.name in EXCLUDED_FILE_NAMES:
            continue
        yield path


def build() -> int:
    if not MANIFEST_PATH.is_file():
        print(
            f"ERROR: manifest not found at {MANIFEST_PATH}\n"
            f"  extension/manifest.json is written by the coordinator at integration time, after every\n"
            f"  child's files have landed. Run this script again once it exists.",
            file=sys.stderr,
        )
        return 1
    if not SRC_DIR.is_dir():
        print(f"ERROR: source directory not found at {SRC_DIR}", file=sys.stderr)
        return 1

    DIST_DIR.mkdir(parents=True, exist_ok=True)

    file_count = 0
    with zipfile.ZipFile(OUTPUT_ZIP, mode="w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.write(MANIFEST_PATH, arcname="manifest.json")
        file_count += 1

        for path in iter_src_files(SRC_DIR):
            # Zip entries must use forward slashes regardless of host OS for cross-platform compatibility.
            arcname = "src/" + "/".join(path.relative_to(SRC_DIR).parts)
            zf.write(path, arcname=arcname)
            file_count += 1

    print(f"Wrote {OUTPUT_ZIP} ({file_count} files)")
    return 0


if __name__ == "__main__":
    sys.exit(build())
