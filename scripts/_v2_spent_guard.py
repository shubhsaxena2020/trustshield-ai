"""Refuse to run historical v2-holdout scripts. The v2 holdout is permanently spent."""
from __future__ import annotations

import sys

OVERRIDE_FLAG = "--archival-override-v2-spent"
MESSAGE = (
    "REFUSED: v2 is spent; historical script only.\n"
    "The sealed v2 holdout and the v1 test split were each evaluated once and must not be used for tuning, model selection or "
    "re-evaluation. This script reads or rewrites spent data or encodes the pre-promotion state, and would mislead or overwrite "
    "historical evidence.\n"
    f"Archival/reading use only: pass {OVERRIDE_FLAG} explicitly. It does not make v2 usable for development."
)


def require_archival_override(argv: list[str] | None) -> list[str]:
    """Return argv without the override flag, or exit with status 3 when it is absent."""
    args = list(sys.argv[1:] if argv is None else argv)
    if OVERRIDE_FLAG not in args:
        print(MESSAGE, file=sys.stderr)
        raise SystemExit(3)
    return [arg for arg in args if arg != OVERRIDE_FLAG]
