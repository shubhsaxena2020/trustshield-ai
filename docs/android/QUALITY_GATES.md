# Merge and release gates

1. **Scope:** `android-finals`, issue/PR references, only package-owned files, reviewed diff; no website/production, forbidden data/model/.env or pre-existing untracked changes. No secret in commit, APK metadata or logs.
2. **Evidence:** worker states exact command, exit code, environment, fixtures, screenshots/device identity and limitations. Codex reproduces claimed pass and reviews source/contract/privacy; a skipped test is not a pass.
3. **Build:** `:app:assembleDebug`, `:app:testDebugUnitTest`, lint and manifest inspection green on a clean checkout. Debug APK SHA-256 and size recorded. No baseline test regression if backend touched.
4. **Behavior:** local deterministic verdict, exact quote validation, legitimate-pair false-alarm tests, no URL/QR navigation, no raw content persistence, honest `Unable to Verify`, explicit mic/camera consent. A real phone test is required for “installs and runs on phone.”
5. **Device/stability:** named phone or emulator; 500-event package-scoped monkey run with zero crash/ANR; permission revoke/regrant, airplane mode, rotation and long session smoke. Targets: APK <=50 MB without optional ASR model, cold start <=2.5 s, peak PSS <=250 MB on named mid-range device. These are provisional acceptance budgets, not measured Phase 1 facts; record measured results and explain any exception before release.
6. **UI:** no clipping at 320 dp/200% text, TalkBack usable, risk not color-only, haptics optional, no continuous microphone/camera use after Stop/Back.
7. **Release:** 10 timed rehearsals on the candidate APK, install/hash verified, backup demo path labeled, all open gaps on `STATE.md`. P0 cannot be waived to squeeze in P1. One PR merged at a time; no production deploy.
