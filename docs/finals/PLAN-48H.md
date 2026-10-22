# 48-hour execution plan

**T0:** launch timestamp recorded in `STATE.md` (UTC). Hour labels are elapsed from T0; rebase only if the process actually restarts and record the original launch. Slots are package assignments, not permission to exceed the per-session child limit. Both lanes stay occupied with a ready, non-conflicting package.

| Hour | Lane A | Lane B | Gate / buffer |
|---:|---|---|---|
| 00 | Phase 0 plan authoring | Phase 0 source/tool fact gathering | Plans only before baseline tests |
| 01 | Phase 0 complete, commit | Review plan/tool constraints | Commit plan docs; then guarded pytest baseline |
| 02 | W1 auth reproduction | W2 REA/public UI observation | W1 baseline and W2 method recorded |
| 03 | W1 auth fixes | W2 reference measurements | Capture login, chat, responsive behaviors |
| 04 | W1 auth fixes | W2 UI spec draft | Shared web paths reserved by W1 |
| 05 | W1 Playwright matrix | W2 UI spec/captures | Review W1 defects; W2 deliverable review |
| 06 | W1 fixes/retest | W2 implementation prep only | Lane B stays read-only on app code |
| 07 | W1 three-run acceptance | W2 UI implementation starts if W1 paths released; otherwise spec refinement | W1 gate or bounded feedback round |
| 08 | W1 backend/tests finish | W2 UI implementation | No merge before W1 ownership release |
| 09 | W3 parser design/fixture inventory | W2 UI implementation | Keep parser, web UI files disjoint |
| 10 | W3 bounded extraction implementation | W2 UI implementation | synthetic-only fixtures |
| 11 | W3 API/parser tests | W2 responsive/accessibility | W1–W2 regressions checked |
| 12 | W3 browser/mobile upload | W2 type/lint/build | Half-day gate; 60-minute integration buffer |
| 13 | W3 fixes/retest | W2 visual review | review screenshots and console |
| 14 | W3 acceptance | W2 final acceptance | W1–W3 green gate for W5 |
| 15 | W5 extension policy/core | W4 guard review + regression set | W5 starts only if W1–W3 green |
| 16 | W5 core | W4 named rules | No dataset/holdout reads |
| 17 | W5 UI/unblock/offline | W4 60+ link fixture | official-domain baseline first |
| 18 | W5 browser tests | W4 validation-only evaluation if guarded | report measurements/limits |
| 19 | W5 permission/reversibility audit | W4 regression tests | W4 false-positive gate |
| 20 | W5 fixes | W4 report/review | worker claim review |
| 21 | W5 acceptance | W4 acceptance | W4 model holdout not opened |
| 22 | W6 Compose scaffold/chat | W7 injection harness guard audit | W5 functional gate required |
| 23 | W6 API/chat flow | W7 routing harness guard audit | No secrets in output |
| 24 | W6 scanner/share flow | W7 demo flow setup | Mid-run state/commit checkpoint |
| 25 | W6 file/QR/settings/history | W7 run 256 injection cases | Check exact harness and fixtures first |
| 26 | W6 error/offline states | W7 run 35 routing cases | Per-IP limits preserved |
| 27 | W6 unit/build | W7 inspect regression evidence | No device claim without device |
| 28 | W6 fixes/build | W7 demo rehearsal | W5/W6 exact status |
| 29 | W6 device smoke or documented gap | W7 recording script/offline fallback | Local only |
| 30 | W6 final gate | W7 presentation numbers/notes | No unmeasured claims |
| 31 | W1–W4 defect buffer | W5/W6 defect buffer | Return defects, max four rounds/package |
| 32 | Security attack-surface review | Cross-browser/viewport review | Upload/extension and identity boundaries |
| 33 | W7 regression rerun after fixes | Demo rehearsal | Changed-path suites rerun |
| 34 | Integration stabilization | Documentation/evidence audit | Preserve one uninterrupted buffer hour |
| 35 | Branch-wide checks | Final screenshot/console review | No production deployment |
| 36 | Defect buffer | Defect buffer | Prioritize W1–W4/W7 acceptance |
| 37 | Final diff review | Evidence index/final report draft | Every claim linked to evidence |
| 38 | Final test suite | Exact command reproduction | Keep machine cool; no new scope |
| 39 | Commit green milestone | Verify remote/branch availability | Push only if green and remote exists |
| 40 | Final report completion | Review gaps/risks | No implied completion for blocked gates |
| 41 | Re-run critical W1/W3 | Re-run critical W2/W4 | clean-context/high-risk cases |
| 42 | Re-run W5 policy | Re-run W6 build/tests | device limits explicit |
| 43 | W7 demo + regression | Presentation claim verification | Freeze candidate facts |
| 44 | Fix only release-blocking defects | Verify targeted fixes | no broad refactor |
| 45 | Full relevant regression | Final report update | Commit after checks |
| 46 | Final git/status/evidence review | Independent checklist against mission | Identify anything not done |
| 47 | Close log/state and final report | Verify push/commit evidence | final handoff to Claude Code |

## Never cut

Spent-data boundaries, no-production rule, identity isolation, security regression suites, truthful metric reporting, evidence review, local reproduction of release claims, and final report. If time or tools do not permit a gate, record it as not done with reason.

## Schedule adjustment

The roadmap is dependency-first. If an upstream gate slips, shift downstream packages into the next safe slot while keeping independent work moving. Never claim both lanes were busy if no dependency-ready safe package existed; log the actual idle reason. Recovery from power-off follows `STATE.md` and does not reset the original T0.
