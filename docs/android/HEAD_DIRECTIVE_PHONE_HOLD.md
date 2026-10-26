# Head directive: phone hold and Phase 4 completion

Issued 2026-10-26, binding through 22:45 IST unless the owner explicitly says the real Android phone is connected sooner.

## Phone hold

Until that release signal, do not start or wait for any physical-phone work. This includes speech tests on real hardware, real-camera QR decoding, phone performance/battery/TalkBack checks, and phone install steps. Emulator and JVM work remains in scope. Do not stop or relaunch the existing Hermes Phase 4 sessions; review their PRs normally.

## Work to finish

- Close review and emulator/JVM proof for #135 (native barcode crash), #134, and #136. For #135, run monkey seeds 4242, 7, and 99 with 2,000 events each; record the QR Guard first-launch run too.
- Produce and verify a locally signed release APK with the TrustShield icon/splash, `INSTALL.md`, universal and per-ABI APKs, including `arm64-v8a`.
- Complete the fixtures table, Devanagari checks, settings checks, conversation mode, and payment advisory per `HEAD_ORDERS_PHASE4.md`.
- Prepare the phone-ready kit under `docs/android/phone-kit/`: a 30-minute owner/head checklist, adb-only idempotent helper scripts, and printable synthetic UPI QR cards.
- Make missing Hindi recognition, camera permission, busy camera, and microphone capability degrade to clear explanation plus a demo/simulate path; add JVM/instrumented fallback tests on the emulator.
- Add a Settings-hidden Device check screen for Android version, ABI, recognizer/locale support, and camera/mic permission state, with tests.

## Execution limits

- Keep no more than one emulator and one Gradle build active at a time. Use the Phase 4 shared locks before either resource is used. Gradle heap remains `-Xmx2g`.
- Commit and push small completed units, update `ORCHESTRATOR-LOG.md`, and keep `STATE.md` current. Do not report unmeasured phone results as passing.
- At 22:45 IST, if the owner has not released the phone hold, continue only phone-free work and log the kit/phone-free status. Continue past that time; do not stop automatically.

