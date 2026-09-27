# Dependency audit — FINAL OVERNIGHT MASTER COMPLETION PASS

## Python (`pip-audit` against `requirements.txt`)
**0 known vulnerabilities.** `pip-audit` was installed fresh for this check (a free, open-source PyPI tool — no
account, no paid service) and run against the pinned `requirements.txt`.

8 packages have a newer release available but no reported CVE against the currently-pinned version: `filelock`,
`fsspec`, `huggingface_hub`, `multidict`, `pip` itself, `pydantic_core`, `pyee`, `uvicorn`. None were upgraded this
pass — per the brief's own instruction not to perform blanket upgrades for non-exploitable, dev-only version gaps.
`huggingface_hub` (1.32.0 → 2.0.0, a major version bump) is the one worth a deliberate look before any future upgrade,
since a major version can change its API surface; it is used only by the manual `scripts/fetch_difraud_sms.py`
research script, not by any production or served path.

## Frontend (`npm audit --audit-level=high` in `apps/web`)
**0 vulnerabilities** at the high/critical threshold.

## Not attempted this pass
A full `npm audit` at the low/moderate threshold (dev-tooling-only advisories are common and low-signal; not run to
avoid burning time on non-actionable noise) and a Rust/cargo audit if any such dependency exists in the repo (not
checked — the project is Python + Node, no Rust toolchain was found in scope).
