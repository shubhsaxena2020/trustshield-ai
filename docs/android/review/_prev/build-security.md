# Build / Security / Privacy / Release review (slice: build-security)

Scope: `apps/android/build.gradle.kts`, `app/build.gradle.kts`, `AndroidManifest.xml`, proguard, `input/share/**`, `input/speech` (storage/network parts), `data/**`, `MainActivity.kt` intent handling. Read-only, no Gradle/adb run, keystore never opened (only `keystore/` existence checked: directory absent in worktree, `.gitignore` lists `keystore/`, no keystore/`release.properties` tracked by git).
Base: android-finals 74db613.

## Clean checks (verified)
- **Log calls:** `grep Log\.x( | println | printStackTrace | Toast` over `main/` = **0 hits**. Only `println` is in unit tests (`DemoMessagesTest`, `DetectionFixturesTest` prints fixture text rows, `DigitalArrestSignalTest:223` writes a file) – test-only, synthetic fixtures. Redacted `toString()` on share types.
- **Network:** manifest has no `INTERNET`; only `ACCESS_NETWORK_STATE`. No `java.net`/OkHttp/URL use in main code (`AnalysisApi.kt` is an offline interface; `OfflineContractTest` exists). The only `URL(...).openStream()` is in the Gradle task (build-time).
- **Storage:** SharedPreferences `trustshield_user_preferences`, MODE_PRIVATE, allow-listed 5 keys, `clear()` + rewrite drops strays (`UserPreferencesStore.kt:34-41`). No other file writes except the Vosk model copy (`filesDir/model-hi`). No raw text/audio persisted; `onSaveInstanceState` saves only route + counter.
- **Backup:** `allowBackup="false"` (manifest:9). No `res/xml` dir, so no `dataExtractionRules`/`fullBackupContent` (fine because backup is off; see B-07).
- **Exported components:** only `MainActivity` (required for LAUNCHER/SEND). No services, receivers, providers.
- **Share handling:** non-`content` schemes (file:, http:, javascript:, intent:) refused as a whole; read-grant flag required; APK/HTML declared MIME refused; >5 URIs refused; ClipData items guarded by try/catch; 8 MB budget enforced in `BoundedShareStream`. URLs never opened.
- **Signing:** secrets come from gitignored `keystore/release.properties`; nothing hardcoded; `debuggable` not forced.
- **Licences:** `docs/android/THIRD_PARTY.md` covers Vosk/JNA/Vosk Hindi model (Apache-2.0) only → see B-09.

## Findings

### B-01  P1  Share intent parsing runs on the main thread, with provider IPC (ANR / DoS)
`MainActivity.kt:77-78` (`onCreate`), `:194-195` (`onNewIntent`) → `sharedText()` + `sharedImageUri()` (`:221-241`) each construct `ShareIntentReader(this).read(intent)`, which per URI does `contentResolver.query(uri, SIZE…)` and `contentResolver.getType(uri)` (`ShareIntentReader.kt` `streamSnapshot`/`declaredBytes`, ~lines 580-600). That is up to 5 URIs × 2 binder calls × 2 reads = ~20 blocking cross-process calls on the UI thread, before first frame.
Scenario: a hostile/slow app shares `ACTION_SEND_MULTIPLE` with 5 `content://evil.provider/…` URIs whose provider sleeps (or a hung provider) → ANR dialog on TrustShield, even though the user only tapped "Share". Also reads the intent twice (double IPC).
Fix: read once, off the main thread (`withContext(Dispatchers.IO)` in a LaunchedEffect keyed on intent) with a timeout; pass the single `ShareIntentRead` to both text/image paths.
Test: fake `ContentResolver` provider that blocks 10 s; assert `onCreate` returns <100 ms / StrictMode `detectNetwork/DiskReads` penaltyDeath androidTest launching with such an intent.

### B-02  P1  Speech: `DefaultMayUseNetwork` path can send audio to cloud, contradicting "no network by default"
`CallSpeechSession.kt:526-532, 653`; `AndroidSpeechRecognizerAdapter.kt:604-606` use `SpeechRecognizer.createSpeechRecognizer(context)` (system recognizer, typically Google cloud) whenever `cloudMayBeUsed`. The app has no INTERNET permission, but the *recognition service* does; the audio leaves the device. `AppScreens.kt:398,521,573` only toggles Vosk-offline vs. this route based on `networkIsValidated`. Verified the mode exists and is selected; I did not verify that the UI discloses it before mic start or that a consent gate (SpeechConsent) names cloud (unverified wording).
Fix: if retained, require explicit per-session opt-in naming "audio may go to Google"; default to Vosk/on-device only; never auto-select cloud when online.
Test: `recognitionModeFor(report)` with `cloudMayBeUsed=true` and no explicit cloud consent must return null / OnDevice.

### B-03  P1  Vosk model copy is non-atomic: partial copy treated as complete
`OfflineSpeechEngine.kt:108-112`: copy skipped when `am/final.mdl` exists. `copyAssets` writes children in `AssetManager.list` order (alphabetical: `am/` first), so a process kill / low-storage mid-copy leaves `am/final.mdl` present but `conf`, `graph`, `ivector` incomplete; every later start then loads a corrupt model (`Model(...)` throws or native crash — native SIGABRT in Vosk is not catchable) and never self-heals. Also 42 MB duplicated in `filesDir` (APK + copy) and never size-checked.
Fix: copy into `model-hi.tmp`, verify file count/size manifest (or a version marker file written last), then `renameTo`. Delete on failure.
Test: instrumented/JVM with fake AssetManager abstraction – copy interrupted after `am/` ⇒ next start must re-copy.

### B-04  P1  ABI config likely broken / unsafe for release
`app/build.gradle.kts:25` (`ndk.abiFilters`) **and** `:28-34` (`splits.abi`) both set. AGP rejects this combination ("Conflicting configuration: ndk abiFilters and splits abi filters") unless the build is only ever run in a way that disables splits (e.g. `-P` / bundle). Unverified (no Gradle run allowed) – flagged for the build owner to confirm `assembleRelease` passes.
Independently verified: only `arm64-v8a` + `x86_64` ship → 32-bit-only ARM devices (armeabi-v7a, still common on low-end Indian phones, the target audience) get `INSTALL_FAILED_NO_MATCHING_ABIS`; and `isUniversalApk=true` plus per-ABI APKs means the shipped universal APK contains x86_64 (waste of ~10–20 MB of Vosk/ML Kit/JNA libs for phones). Vosk 0.3.75 includes armeabi-v7a/x86.
Fix: decide explicitly. Either add `armeabi-v7a` or document unsupported devices in INSTALL.md/Play listing; for sideloaded demo APK drop x86_64 from the release universal APK (keep it in debug only). Use only one of abiFilters/splits.
Test: CI step `unzip -l app-release*.apk | grep lib/` asserting ABI set and APK size budget (see B-10).

### B-05  P2  Release signing config fails opaquely when properties are missing; release is un-assemblable on clean checkout
`app/build.gradle.kts:17-20, 38-46, 49`: with no `keystore/release.properties`, `storePassword/keyAlias/keyPassword` are null and `storeFile` points to a non-existent jar → `assembleRelease` fails with "Keystore file not found"/NPE-like message instead of a clear one. No guard distinguishing local vs release builds; also no fallback to env vars for CI. Safety itself is good (no secrets committed).
Fix: `if (props has keys) create signingConfig else release unsigned + `logger.warn`; or `require()` with a clear message in a `gradle.taskGraph.whenReady` for `assembleRelease` only.
Test: CI job running `assembleRelease` without keystore expects the explicit error text.

### B-06  P2  `preBuild` depends on a network download of a 42 MB model → clean/offline/unit-test builds break
`app/build.gradle.kts:84-86`: every build (including `testDebugUnitTest` on CI) triggers download unless `assets/model-hi/am/final.mdl` exists. No timeouts on `URL.openStream()`, whole archive read via `readBytes()` (42 MB heap, fine with 2 GB), SHA-256 pinned (good), zip-slip check present (good: `startsWith(prefix)` and no `..`). `outputs.dir` is declared but no `inputs`, so task is never invalidated by hash/URL change; a corrupt `final.mdl` stays forever. The download is plain build-time network, no runtime impact.
Fix: depend only on `merge*Assets`/`package*` tasks, not `preBuild`; add connect/read timeouts; cache dir keyed on hash.
Test: CI offline run of `testDebugUnitTest` should pass without the model.

### B-07  P2  Backup/extraction rules not explicit
Manifest sets only `allowBackup="false"`; no `android:dataExtractionRules` (API 31+) nor `fullBackupContent`. With targetSdk 36, lint (`DataExtractionRules`) warns; allowBackup=false is sufficient today, but if someone flips it the model copy (42 MB) and prefs would be uploaded. Also D2D transfer behaviour is implicit.
Fix: add `res/xml/data_extraction_rules.xml` with `<cloud-backup><exclude domain="root"/> sharedpref file…` and `<device-transfer>` excluding `file` domain; reference in manifest.
Test: a JVM test parsing manifest/xml asserting `allowBackup=false` and exclusions (see gaps).

### B-08  P2  INTERNET permission may arrive via manifest merge (ML Kit / Play Services / data-transport) – unverified
Source manifest is clean, but the merged manifest is not asserted anywhere. `mlkit text-recognition` (bundled) pulls `mlkit:common`, `play-services-base`, `datatransport` (telemetry uploader). Some of these have historically declared `INTERNET`/`ACCESS_NETWORK_STATE`/`WAKE_LOCK` and `MlKitInitProvider`; a telemetry uploader could then exist at runtime. I could not inspect the merged manifest (no Gradle). `ACCESS_NETWORK_STATE` is declared by this app (used by `networkIsValidated`) – acceptable.
Fix: add `tools:node="remove"` for `android.permission.INTERNET` in the manifest plus a CI assertion on `app/build/intermediates/merged_manifests/**/AndroidManifest.xml`; consider `MlKitInitProvider` removal / disabling ML Kit telemetry only if merged manifest shows it.
Test: CI grep merged manifest for `INTERNET`, and `aapt dump permissions` on the release APK.

### B-09  P2  Third-party notices incomplete; no in-app licence screen
`docs/android/THIRD_PARTY.md` only lists Vosk/JNA/model. Shipped but missing: ML Kit text-recognition + devanagari (Google ML Kit Terms/ Apache for SDK wrappers; bundled models under their own terms), CameraX (Apache-2.0), ZXing core 3.5.3 (Apache-2.0), AndroidX/Compose/Material3/Profileinstaller/Splashscreen (Apache-2.0), Kotlin stdlib/coroutines (Apache-2.0). Apache-2.0 requires NOTICE retention; Vosk model has its own notice. No "Open source licences" screen in settings (not verified under `ui/settings`, none referenced in manifest).
Fix: extend THIRD_PARTY.md; add `assets/licenses` or use `oss-licenses` plugin (would need Play-services — avoid), ship a static text.
Test: UI test that settings has a licences entry; doc-lint test that each `implementation(` group appears in THIRD_PARTY.md.

### B-10  P2  No APK size/ABI/shrink verification; R8 never exercised by tests
Release minify+shrink are enabled, but all unit/androidTest run against debug (`testBuildType` default). Keep rules (`proguard-rules.pro`): `-keep class org.vosk.** {*;}`, `-keep class com.sun.jna.** {*;}` plus 4 `-dontwarn java.awt.*`. Concerns:
 - JNA references more AWT types (`java.awt.Toolkit`, `java.awt.Native…`, `sun.*`) – the 4 dontwarn lines may be incomplete → R8 "Missing class" failure at `minifyReleaseWithR8` (unverified; depends on JNA 5.18.1 jar-vs-aar: Vosk uses the Android aar variant which strips awt references, so probably OK).
 - Reflection-only users: JSONObject on Vosk results (fine, platform), `Model`/`Recognizer` JNA `Structure` fields covered by the broad keep. `-keep` is wide (not obfuscating) but harmless; it also keeps the whole of JNA Windows/desktop classes (size).
 - ML Kit and CameraX ship consumer rules; no extra keep needed (unverified without building).
 - Model is stored uncompressed? `.mdl` in assets gets compressed by aapt by default (no `androidResources.noCompress`), costing install size but not correctness.
Fix/Test: add a CI "release smoke": build `assembleRelease`, install on emulator, run one share+QR+Vosk-start androidTest via `testBuildType = "release"` (or a `benchmark`-style minified variant); log APK size (expect ≈ 42 MB model + ML Kit 2 scripts ≈ 20 MB + libs); gate on a size budget.

### B-11  P2  `EXTRA_ROUTE` is externally controllable on an exported activity
`MainActivity.kt:218-219` / `AppRoute.kt:37-40`: any app can launch `MainActivity` with `route=call_shield|qr_guard|result|settings`. Verified it only navigates (no mic/camera start without UI action/consent as far as read here). `result` with no outcome: handled by restore guard (`:87-95`) only on restore path, not for a fresh explicit launch (unverified what Result renders with null outcome).
Fix: ignore `EXTRA_ROUTE` unless action is MAIN/internal; never route to RESULT from outside. Test: instrumented launch with `route=result` and no outcome → Home.

### B-12  P2  Multi-image share silently analyses only the first image
`MainActivity.kt:235-243`: `MultipleImages -> content.images.firstOrNull()`. Filter accepts `SEND_MULTIPLE` image/*, ShareReceiver validates up to 5 images, then UI drops 4 with no message. Wrong-verdict risk: scam in image #2. Fix: say "only first of N analysed" or iterate. Test: `SEND_MULTIPLE` with 2 images → disclosure shown.

### B-13  P2  Shared intent re-processed on every recreation; shared text lives in the task's Intent
`onCreate` re-reads `intent` after rotation/process restore (`:77-85`), so a share re-triggers analysis (and re-routing to RESULT) after config change; the original `EXTRA_TEXT` stays on the activity's Intent in Recents/task snapshot memory (no persistence to disk, but outside the "in-memory only for the analysis" claim). Fix: after consuming, `intent.replaceExtras(Bundle())` / `setIntent(Intent())`, and track consumed via a savedInstance flag. Test: rotate after share → analysis runs once.

### B-14  P2  Unbounded ClipData text items/huge text
`ShareIntentReader.kt:470-515`: URI count is capped at 5 but text items are not; ClipData with thousands of text items (each ≤ Binder limit, total ≤ ~1 MB) all `toString()`'d, trimmed, de-duplicated into a `LinkedHashSet` on the main thread (B-01). Bounded in practice by the 1 MB binder limit; `LocalScanPipeline.MAX_INPUT_CHARS` caps analysis. Fix: cap items (e.g. 20) and total chars at read time, emit an issue. Test: 50 000 single-char clip items finishes <50 ms and is flagged.

### B-15  P2  Confused-deputy: any `content://` URI + sender-set grant flag is accepted
`ShareIntentReaderCore` accepts any `content` scheme when `FLAG_GRANT_READ_URI_PERMISSION` is set on the intent; grant is derived from flags, not checked (`checkUriPermission`). Android enforces actual access, so no escalation beyond sender's own rights, but a sender can point at an arbitrary exported third-party provider (e.g. one that logs access or has side-effects on read). Low severity. Fix: also require `context.checkUriPermission(uri, pid, uid, MODE_FLAG_GRANT_READ_URI_PERMISSION) == GRANTED` in the adapter.

### B-16  P2  `assets/` & model not in worktree; `.gitignore` OK
`apps/android/app/src/main/assets/model-hi/` is gitignored (verified). OK; note B-06.

## Unverified (no Gradle/adb allowed)
- U1 Whether AGP accepts `abiFilters` + `splits.abi` together (B-04).
- U2 Merged-manifest permissions/providers from ML Kit / CameraX / Vosk (B-08).
- U3 R8 missing-class warnings for JNA beyond the 4 `dontwarn` lines; ML Kit/CameraX consumer rules sufficiency (B-10).
- U4 Cloud-speech consent wording in the UI (B-02).
- U5 Result screen behaviour for explicit `route=result` without outcome (B-11).
- U6 Whether `noCompress` affects model asset install size; APK size values are estimates.

## Test coverage gaps
- No test parses `AndroidManifest.xml` (permissions, exported set, allowBackup, intent filters).
- No test of `MainActivity` with hostile intents (`file://`, 6 URIs, null stream, huge ClipData, `SEND_MULTIPLE` text) – `ShareIntentReader` Android adapter is "device-only" by its own header; `Phase3JourneysTest` not touched.
- No R8/release-variant test, no APK size/ABI gate, no merged-manifest INTERNET assertion.
- No test for `OfflineSpeechEngine.copyAssets` interruption/atomicity (`VoskDecoderFactory` lambda untested).
- No test that `ImageScanLauncher` / `SharedImageReader.openInputStream` runs off main thread.
- No privacy "grep gate" test: add a unit test scanning `src/main` for `android.util.Log`, `println`, `INTERNET`, `java.net.URL` to lock today's clean state.
- `data/api/AnalysisApi.kt` offline contract test exists; no test that no `INTERNET` permission is declared.
