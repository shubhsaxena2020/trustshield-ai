"""Guarded final-holdout access logger; this script never evaluates a model."""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main(argv: list[str] | None = None) -> int:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from scripts._v2_spent_guard import require_archival_override
    argv = require_archival_override(argv)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--holdout-v2", type=Path, required=True)
    parser.add_argument("--model-artifact", type=Path, required=True)
    parser.add_argument("--access-log", type=Path, required=True)
    parser.add_argument("--confirm-final-holdout", action="store_true")
    args = parser.parse_args(argv)
    if not args.confirm_final_holdout:
        print("Refusing final-holdout access: pass --confirm-final-holdout after explicit project-lead approval.", file=sys.stderr)
        return 2
    if not args.holdout_v2.exists() or not args.model_artifact.exists():
        print("holdout and model artifact must exist", file=sys.stderr)
        return 2
    entries = json.loads(args.access_log.read_text(encoding="utf-8")) if args.access_log.exists() else []
    command = " ".join([sys.executable, *sys.argv])
    entries.append({
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "model_artifact_sha256": sha256(args.model_artifact),
        "holdout_sha256": sha256(args.holdout_v2),
        "command": command,
    })
    args.access_log.parent.mkdir(parents=True, exist_ok=True)
    args.access_log.write_text(json.dumps(entries, indent=2) + "\n", encoding="utf-8")
    print(f"access count: {len(entries)}")
    print("No model evaluation was performed; this command only logged access.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
