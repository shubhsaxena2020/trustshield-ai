# P8 review shared context
Worktree: C:\Users\shubh\trustshield-android-p4\agent4\wt-review (branch android/P8-review, base origin/android-finals 74db613).
App: apps/android (Kotlin, Compose, minSdk 26). Main src: apps/android/app/src/main/java/ai/trustshield/android/
 core/{analysis,model,normalize,privacy,rules,score}  data/{api,preferences}  input/{qr,share,speech}  ui/{call,home,navigation,qr,result,settings,theme,voice}
Tests: apps/android/app/src/test (+androidTest). Read first: docs/android/MISSION.md, ARCHITECTURE.md, BEHAVIOR_SPEC.md, UI_SPEC.md (if present).
Privacy rules: never open links; no raw text or raw audio stored; only masked ids shown; no network by default.
Hard limits: READ-ONLY on code. Write ONLY your own docs/android/review/<slice>.md. Do NOT run Gradle, adb, emulator or git write commands. Never touch main checkout, other worktrees, .env, secrets, keystores, models, datasets. Do not touch Phase3JourneysTest.
Finding format: ID, severity (P0 crash/data/privacy, P1 wrong verdict/broken flow, P2 quality), file:line, concrete failure scenario with inputs, suggested fix, failing-test idea. Verify each by reading the code path; unverified suspicions go in a separate "Unverified" list.
