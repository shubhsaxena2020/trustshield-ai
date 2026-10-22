# Prompt architecture

Every package prompt is a cold-start contract stored at `work/finals/<pkg>/PROMPT.txt`.

## Required sections

1. **Goal and context:** one measurable outcome; link the relevant mission and project docs; state only verified baseline facts.
2. **Owned files:** exact files/directories. List prohibited or other-lane-owned paths. Split children by disjoint files.
3. **Hard boundaries:** branch/local only; no production, secrets, spent data, v3/threshold edits, external link fetches, new providers, commits, or pushes.
4. **Work plan:** at most nine children with single-owner file lists; parent merges, resolves conflicts, and reviews. If fewer independent scopes exist, use fewer children rather than inventing work.
5. **Acceptance:** exact command, fixture, expected condition, and artifact path for each claim. State unavailable checks plainly.
6. **Report:** write `REPORT.md` with changed paths, reasoning, checks and raw outcomes, screenshots/evidence, known gaps, and exact reproduction commands. No success claims without output.

## Quality rules

Prompts request small readable changes in existing style, no filler or unrelated cleanup. Require evidence and honest unknowns. Workers must stop on file collision, missing authorization, a spent-data boundary, production access, or a secret. Codex—not the worker—owns final acceptance and commits.
