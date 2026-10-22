# Finals roadmap — done / left / planned

Owners are session slots: Lane A and Lane B. A package is considered **done** only after Codex verifies its evidence and gate.

| Priority | Item | Status at T0 | Owner | Dependency | Planned package / done condition |
|---|---|---|---|---|---|
| P0 | Phase 0 plan documents | In progress | Codex | None | Write this suite, hour plan and tracking files; review and commit before baseline tests. |
| W1 | Auth/backend/login/signup | Left; baseline source exists | A | Phase 0 | `w1-auth`; reproduce matrix, fix, three Playwright passes, pytest, screenshots. |
| W2 | Reference observation and measured UI spec | Left | B | Phase 0 | `w2-discovery`; REA observation or documented Playwright fallback; deliver UI_SPEC and 3 viewport captures. |
| W2 | ChatGPT-class web implementation | Left | B | W2 discovery; W1 releases shared paths | `w2-ui`; build from measurements; responsive/accessibility/behavior gates. |
| W3 | General safe uploads | Left; current UI/API are image-oriented | A | W1 shared UI stable; W2 may finish unaffected paths | `w3-upload`; bounded parsers, synthetic fixture matrix, UI/mobile tests. |
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
