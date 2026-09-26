"""Assemble the Vercel Services deployment bundle (one project: Next.js frontend + FastAPI container).

    python scripts/build_vercel_bundle.py --out ../trustshield-vercel --artifacts ../artifacts

`--artifacts` must hold trustshield_text_v3.joblib and trustshield_text_v1.joblib downloaded from the PRIVATE GitHub release
(`scripts/fetch_model_artifact.py --github-release ... --dest <dir>`); both are verified against models/release/v3_release_manifest.json
before anything is copied. The bundle contains no credentials: the model reaches Vercel inside the CLI upload of this bundle, never
through Git and never through a build secret. Deploy with `vercel deploy` from the bundle directory (no Git integration).

Layout:  vercel.json | web/ (Next.js, from apps/web) | api/ (Dockerfile.vercel, services/, manifests, release-artifacts/)
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODELS = ("trustshield_text_v3", "trustshield_text_v1")
VERCEL_JSON = {
    "services": {
        "web": {"root": "web/"},
        "api": {"root": "api/", "runtime": "container", "entrypoint": "Dockerfile.vercel"},
    },
    "rewrites": [
        {"source": "/api/(.*)", "destination": {"service": "api"}},
        {"source": "/(.*)", "destination": {"service": "web"}},
    ],
}
SKIP_WEB = {"node_modules", ".next", "tsconfig.tsbuildinfo", ".vercel", ".env.local"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def tracked(prefix: str) -> list[str]:
    out = subprocess.check_output(["git", "ls-files", prefix], cwd=ROOT, text=True)
    return [line for line in out.splitlines() if line.strip()]


def copy(rel: str, dest_root: Path, source_root: Path = ROOT) -> None:
    target = dest_root / rel
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source_root / rel, target)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--artifacts", type=Path, required=True)
    args = parser.parse_args(argv)
    out = args.out.resolve()
    if out.exists() and any(out.iterdir()):
        print("refusing: output directory is not empty", file=sys.stderr)
        return 2
    manifest = json.loads((ROOT / "models/release/v3_release_manifest.json").read_text(encoding="utf-8"))
    for model in MODELS:
        artifact = args.artifacts / f"{model}.joblib"
        expected = manifest["models"][model]["artifact_sha256"]
        if not artifact.is_file():
            print(f"missing artifact {artifact.name}", file=sys.stderr)
            return 2
        if sha256(artifact) != expected:
            print(f"SHA-256 mismatch for {artifact.name}: refusing to build the bundle", file=sys.stderr)
            return 2
    api = out / "api"
    for rel in tracked("services") + ["requirements-api.lock.txt", "models/release/v3_release_manifest.json",
                                      "scripts/fetch_model_artifact.py"] + tracked("models/metadata"):
        if rel.endswith(".pyc") or "__pycache__" in rel:
            continue
        copy(rel, api)
    shutil.copyfile(ROOT / "docker" / "Dockerfile.vercel", api / "Dockerfile.vercel")
    (api / "release-artifacts").mkdir(parents=True)
    for model in MODELS:
        shutil.copyfile(args.artifacts / f"{model}.joblib", api / "release-artifacts" / f"{model}.joblib")
    for rel in tracked("apps/web"):
        parts = Path(rel).parts
        if any(part in SKIP_WEB for part in parts):
            continue
        target = out / "web" / Path(*parts[2:])
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / rel, target)
    (out / "vercel.json").write_text(json.dumps(VERCEL_JSON, indent=2) + "\n", encoding="utf-8")
    (out / ".vercelignore").write_text("**/__pycache__\n**/*.pyc\n", encoding="utf-8")
    (out / "BUNDLE_MANIFEST.json").write_text(json.dumps({
        "source_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "artifacts": {m: manifest["models"][m]["artifact_sha256"] for m in MODELS},
    }, indent=2), encoding="utf-8")
    files = [p for p in out.rglob("*") if p.is_file()]
    print(f"bundle: {len(files)} files, {sum(p.stat().st_size for p in files) / 1024 / 1024:.1f} MiB at {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
