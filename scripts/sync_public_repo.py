"""Sync the published (history-less) repository checkout with the current clean publication tree.

    python scripts/sync_public_repo.py --public ../ts-public [--message "..."] [--push]

Builds the tree with scripts/build_public_tree.py into a temp dir, mirrors it into the public checkout (adds, updates and deletes
files; `.git` untouched), then commits (and optionally pushes). Never publishes protected data: the builder enforces the policy.
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--public", type=Path, required=True)
    parser.add_argument("--message", default="Sync from development repository")
    parser.add_argument("--push", action="store_true")
    args = parser.parse_args(argv)
    public = args.public.resolve()
    if not (public / ".git").is_dir():
        print("not a git checkout", file=sys.stderr)
        return 2
    with tempfile.TemporaryDirectory() as tmp:
        tree = Path(tmp) / "tree"
        build = subprocess.run([sys.executable, str(ROOT / "scripts" / "build_public_tree.py"), "--out", str(tree)], capture_output=True, text=True)
        print(build.stdout.strip().splitlines()[-1] if build.stdout.strip() else build.stderr.strip())
        if build.returncode != 0:
            return build.returncode
        for path in list(public.rglob("*")):
            if ".git" in path.relative_to(public).parts or not path.is_file():
                continue
            if not (tree / path.relative_to(public)).exists():
                path.unlink()
        for path in tree.rglob("*"):
            if path.is_file():
                target = public / path.relative_to(tree)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, target)
    subprocess.run(["git", "add", "-A"], cwd=public, check=True, capture_output=True)
    status = subprocess.run(["git", "status", "--porcelain"], cwd=public, capture_output=True, text=True).stdout
    if not status.strip():
        print("public repository already up to date")
        return 0
    subprocess.run(["git", "commit", "-q", "-m", args.message + "\n\nCo-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"], cwd=public, check=True)
    print(subprocess.run(["git", "log", "--oneline", "-1"], cwd=public, capture_output=True, text=True).stdout.strip())
    if args.push:
        subprocess.run(["git", "push", "origin", "HEAD"], cwd=public, check=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
