# Finals roadmap — done / left / planned

Owners are session slots: Lane A and Lane B. A package is considered **done** only after Codex verifies its evidence and gate.

| Priority | Item | Status at T0 | Owner | Dependency | Planned package / done condition |
|---|---|---|---|---|---|
| P0 | Phase 0 plan documents | Done | Codex | None | Plan suite committed at d02645e; baseline completed. |
| W1 | Auth/backend/login/signup | Done locally | A | Phase 0 | Full pytest and three-run auth matrix verified; real Supabase delivery/refresh gap is in NEEDS_SHUBH.md. |
| W2 | Reference observation and measured UI spec | Done | B | Phase 0 | Audited UI_SPEC, report and 17 layout-only captures pushed at 9a831b3. |
| W2 | ChatGPT-class web implementation | In progress | B | W2 discovery accepted; W1 shared paths released | `w2-ui`; implementation active; responsive/accessibility/behavior gates pending. |
| W3 | General safe uploads | In progress | A | W1 shared UI stable; W2 may finish unaffected paths | `w3-upload`; bounded parsers, synthetic fixture matrix, UI/mobile tests. |
| W4 | Accuracy/rules | Left; baseline metrics are 82.1 recall / 78.3 specificity, 90% target not met | B | W1–W3 green; guard review | `w4-rules`; named misses, >=60 links, validation-only report; no v3/default changes. |
| W5 | MV3 extension | Left; `extension/` absent | A | W1–W3 green | `w5-extension`; policy, rules, UI, tests, README/privacy/build. |
| W6 | Kotlin/Compose Android | Left; `android/` absent | B | W5 functional; SDK present | `w6-android`; required app flows, build/unit/device evidence. |
| W7 | Injection and routing security regression | Left | A | Feature paths stabilize; inspect harness guards | `w7-regression`; exact 256 + 35 suites; no regression and limits retained. |
| W7 | Demo, presentation, offline fallback | Left | B | Feature state stable | `w7-demo`; 5-minute local flow, recording script, offline plan, honest notes. |
| P-final | Final report and push | Left | Codex | All available gates/packages | Evidence-led done/not-done/risks, commands, final diff review; push only green milestone. |

## Shared-path collision control

Lane A owns auth/signup/login/backend history first. Lane B may observe UI read-only and write `UI_SPEC.md` during this time. Lane B cannot modify `apps/web` until W1's merge releases those paths. W3 parser/backend changes use distinct files; any composer overlap waits until W2 has a reviewed boundary. Accuracy stays in rules/tests/docs and never owns model artifacts.

## Status legend

- **Done:** locally reproduced by Codex with evidence.
- **In progress:** active package with PID/session and last evidence in `STATE.md`.
- **Left:** not accepted yet, including any worker-reported but unverified work.
- **Blocked:** only when an external prerequisite prevents safe work; document it and continue independent packages.
