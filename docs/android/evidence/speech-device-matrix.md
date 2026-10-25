# Speech device matrix - ASR fallback measurement and decision (B08, issue #40)

**Package:** B08 [P0] ASR fallback experiment. **Branch:** `android/B08`. **Base:** `android-finals` @ `d1c6a5c`.
**Author:** Hermes `agent2` slot 8. **Measured:** 2026-10-25, 22:35-22:45 IST.

## 0. Bottom line first

**Decision: NO-GO. Do not bundle Vosk (or any on-device ASR model) in this phase.**
`apps/android/app/src/main/java/ai/trustshield/android/input/speech/VoskCandidate.kt` was **deliberately NOT created**,
and its matching test was not written. The reason is not preference, it is the absence of measurement:
there is no physical phone attached, the 42 MB Hindi model cannot be downloaded in this window, and the only
device available (emulator-5554) does not carry a `hi-IN` on-device speech pack at all - so even the numbers I
*could* have produced here would have measured the wrong recogniser on the wrong ABI.

Every claim in this document is either a command I ran with its verbatim output, or is explicitly marked
**UNMEASURED**. Nothing in between.

**What this document is not:** it is not a live call interception capability, and it does not establish any
Hindi/Hinglish ASR accuracy figure. See §7 for the staged-demo fallback and §8 for what evidence would reverse
this decision.

---

## 1. Environment (measured, not assumed)

| Item | Value | Source |
|---|---|---|
| Device | `emulator-5554` (product/model `sdk_gphone64_x86_64`, device `emu64xa`) | `adb devices -l` |
| Android release | `16` | `getprop ro.build.version.release` |
| SDK / API | `36` | `getprop ro.build.version.sdk` |
| ABI | `x86_64` | `getprop ro.product.cpu.abi` |
| Build flavour | `sdk_gphone64_x86_64-userdebug` | `getprop ro.build.flavor` |
| Build fingerprint | `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys` | `getprop ro.build.fingerprint` |
| Build tags / type | `dev-keys` / `userdebug` | `getprop` |
| AVD name | `trustshield-api36` | `getprop ro.boot.qemu.avd_name` |
| System image tag | `google_apis` (not `google_apis_playstore`); `PlayStore.enabled = no` | AVD `config.ini`, §2.9 |
| Image sysdir | `system-images\android-36\google_apis\x86_64\` | AVD `config.ini` |
| Device locale | `ro.product.locale = en-US`; `system_locales = null` | `getprop` / `settings` |
| Device clock / TZ | `Sun Oct 25 22:41:00 IST 2026`, `Asia/Calcutta` | `adb shell date` |
| Host clock | `2026-10-25 22:41:01 IST (+0530)` | `date` |
| **Physical phone** | **none attached** | `adb devices -l` lists only `emulator-5554` |

Two honest notes on the above:

* The AVD config declares `hw.ramSize = 1536M` while the running instance reports `MemTotal: 2531832 kB`
  (§2.2). The emulator was evidently launched with a memory override; I report the **running** figure, not the
  config figure, and I did not determine which process applied the override.
* `com.android.vending` (Play Store) is nevertheless installed (§2.3) even though `PlayStore.enabled = no`.
  It is shipped by the `google_apis` image. This does **not** imply any Hindi model is installed - see §2.6.

---

## 2. Verbatim command output

All commands run as `adb -s emulator-5554 ...` from Git Bash on the Windows host, `ANDROID_HOME=C:/Users/shubh/AppData/Local/Android/Sdk`.
Full transcripts were saved under the session scratch dir (`.../agent2/cache/scratch/b08-evidence/`); the key lines
are reproduced here exactly, including empty results and errors.

### 2.1 Device

```
$ adb devices -l
List of devices attached
emulator-5554          device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa transport_id:1
```

```
$ adb -s emulator-5554 shell getprop ro.build.version.sdk            -> 36
$ adb -s emulator-5554 shell getprop ro.build.version.release        -> 16
$ adb -s emulator-5554 shell getprop ro.product.cpu.abi              -> x86_64
$ adb -s emulator-5554 shell getprop ro.product.model                -> sdk_gphone64_x86_64
$ adb -s emulator-5554 shell getprop ro.product.manufacturer         -> Google
```

### 2.2 Memory

```
$ adb -s emulator-5554 shell cat /proc/meminfo | head -8
MemTotal:        2531832 kB
MemFree:          190680 kB
MemAvailable:    1121136 kB
Buffers:           10152 kB
Cached:          1160292 kB
SwapCached:         4432 kB
Active:           992632 kB
Inactive:         578668 kB
```

```
$ adb -s emulator-5554 shell dumpsys meminfo | grep -A6 "Total RAM"
Total RAM: 2,531,832K (status normal)
 Free RAM: 1,440,617K (  828,401K cached pss +   443,752K cached kernel +   168,464K free)
DMA-BUF:         0K (        0K mapped +         0K unmapped)
DMA-BUF Heaps:         0K
DMA-BUF Heaps pool:         0K
      GPU:         0K
 Used RAM: 1,499,219K (1,130,095K used pss +   369,124K kernel)
```

Note that the emulator's RAM budget is **host RAM handed to x86_64**, not phone RAM. It is not evidence about a
mid-range phone, which is the device the quality gate names (`QUALITY_GATES.md` §5: peak PSS <= 250 MB on a named
mid-range device).

### 2.3 Installed packages relevant to speech

```
$ adb -s emulator-5554 shell pm list packages | grep -i -E "speech|googlequicksearchbox|tts|voice"
package:com.google.android.apps.accessibility.voiceaccess
package:com.google.android.tts
package:com.google.android.googlequicksearchbox
```

```
$ adb -s emulator-5554 shell pm list packages | wc -l
249
$ adb -s emulator-5554 shell pm list packages | grep -E "com.android.vending|com.google.android.gms$"
package:com.android.vending
package:com.google.android.gms
```

### 2.4 Which `RecognitionService` providers are actually registered

```
$ adb -s emulator-5554 shell cmd package query-services -a android.speech.RecognitionService
2 services found:
  Service #0:
    priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
    ServiceInfo:
      name=com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService
      packageName=com.google.android.as
      enabled=true exported=true directBootAware=false
      ...
        sourceDir=/product/priv-app/DevicePersonalizationPrebuiltPixel2021-bfinal_aiai_20250217.00_RC08/DevicePersonalizationPrebuiltPixel2021-bfinal_aiai_20250217.00_RC08.apk
        enabled=true minSdkVersion=36 targetSdkVersion=36 versionCode=14753270 targetSandboxVersion=1
  Service #1:
    priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
    ServiceInfo:
      name=com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService
      packageName=com.google.android.tts
      enabled=true exported=true directBootAware=true
      ...
        sourceDir=/product/app/GoogleTTS/GoogleTTS.apk
        enabled=true minSdkVersion=26 targetSdkVersion=35 versionCode=210526446 targetSandboxVersion=1
```

```
$ adb -s emulator-5554 shell dumpsys package | grep -i -A6 "android.speech.RecognitionService"
      android.speech.RecognitionService:
        8d1bcaa com.google.android.as/com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService
        ebc61ee com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService
        70ce658 com.google.android.googlequicksearchbox/com.google.android.voicesearch.serviceapi.GoogleRecognitionService
```

**Honest discrepancy, recorded rather than smoothed over:** the package registry lists **three** intent-filter
bindings (including a `GoogleRecognitionService` in `com.google.android.googlequicksearchbox`), while
`query-services` returns **two**. I did not establish the cause (it is not needed for this decision) and I am not
guessing at it. Both states of the airplane-mode experiment returned the same two, so this is stable, not a race.

### 2.5 Default recogniser actually selected by the OS

```
$ adb -s emulator-5554 shell settings get secure voice_recognition_service
com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService

$ adb -s emulator-5554 shell settings get secure assistant
com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService
```

The default recogniser on this image is **Google TTS's** recognition service, not the on-device `com.google.android.as`
one and not Google app's cloud `GoogleRecognitionService`.

### 2.6 Is there any on-device ASR model, and for which language?

Unrooted, the model directory is not readable, and I record that failure verbatim instead of describing it:

```
$ adb -s emulator-5554 shell ls -l /data/data/com.google.android.as/
ls: /data/data/com.google.android.as/: Permission denied
```

To answer the question with evidence rather than inference I escalated to `adb root` on this **userdebug, dev-keys**
emulator - inspection only, no writes, no installs - and unrooted afterwards (§2.10). Read as root:

```
$ adb -s emulator-5554 shell ls /data/user/0/com.google.android.as/files/
mdd
mdd_pds_config
photo-ocr
silo
superpacks
```

```
$ adb -s emulator-5554 shell ls -R /data/user/0/com.google.android.as/files/superpacks | head -40
/data/user/0/com.google.android.as/files/superpacks:
captions_i18n_v0_en-US
captions_i18n_v0_mandatory
manifests

/data/user/0/com.google.android.as/files/superpacks/captions_i18n_v0_en-US:
asr_en-US_v3003
asr_en-US_v3056
input_predictions_en-US_v4

/data/user/0/com.google.android.as/files/superpacks/captions_i18n_v0_en-US/asr_en-US_v3003:
SODA_punctuation_config.pb
SODA_punctuation_model.tflite
acousticmodel
configs
context_prebuilt
denorm
endtoendmodel
magic_mic
metadata
voice_match
```

```
$ adb -s emulator-5554 shell ls -l /data/user/0/com.google.android.googlequicksearchbox/app_g3_models/
total 0
```

```
$ adb -s emulator-5554 exec-out cat /data/user/0/com.google.android.googlequicksearchbox/files/SodaResourcesRecord.pb \
    | grep -a -o -E "[a-z]{2}-[A-Z]{2}" | sort | uniq -c | sort -rn | head
      6 en-US

$ ... | grep -a -c -i -E "hi-IN|hi_IN|hindi"
0
```

**This is the single most consequential measurement in the document.** The on-device recogniser on this emulator
carries exactly one language superpack, `captions_i18n_v0_en-US` (`asr_en-US_v3003`, `asr_en-US_v3056`). There is
**no `hi-IN` pack**, the Google-app model directory `app_g3_models` is **empty (`total 0`)**, and the SODA resource
record contains `en-US` six times and **zero** occurrences of `hi-IN`/`hindi`. A Hinglish ASR measurement on this
emulator would therefore measure English acoustics and report it as a Hindi result. That is the exact class of
unmeasured claim this programme forbids.

A bounded `find` for `*speech*` / `*soda*` under `com.google.android.as/files` returned no paths (recorded as an
empty result, not as proof of absence; the superpack listing above is the reliable evidence).

### 2.7 Microphone and audio input surface

```
$ adb -s emulator-5554 shell dumpsys media.audio_policy | grep -i -m8 -E "input|mic"
 Available input devices (4):
  1. Port ID: 8; "Built-In Mic"; {AUDIO_DEVICE_IN_BUILTIN_MIC, @:bottom}

$ adb -s emulator-5554 shell cat /proc/asound/cards
cat: /proc/asound/cards: Permission denied

$ adb -s emulator-5554 shell dumpsys audio | grep -i -E "input devices|mic|speech" | head
  mic mute FromSwitch=false FromRestrictions=false FromApi=false from system=false
```

An input device named `Built-In Mic` is exposed and is not muted by policy. It is the emulator's **virtual host
audio** bridge; there is no phone microphone and no guarantee that host audio capture works in CI. Interpret any
`SpeechRecognizer` result obtained here accordingly.

### 2.8 Camera, telephony, and "no modem"

The brief expected "synthetic/virtual camera, no modem". The camera expectation holds; the modem expectation is
**wrong on this image** and I record what is actually there:

```
$ adb -s emulator-5554 shell dumpsys media.camera | head -25
== Service global info: ==

Number of camera devices: 1
Number of normal camera devices: 1
Number of public camera devices visible to API1: 1
    Device 0 maps to "1"
...
== Camera HAL device device@1.1/internal/1 (v1.3) static information: ==
  Resource cost: 100
  Conflicting devices: None
  API1 info:
    Has a flash unit: true
    Facing: Back
    Orientation: 90
```

`device@1.1/internal/1` is the emulated camera HAL: one synthetic back camera with flash, no real sensor.

```
$ adb -s emulator-5554 shell dumpsys telephony.registry | grep -i -E "mServiceState|mCallState" | head -2
    mCallState=0
    mServiceState={mVoiceRegState=0(IN_SERVICE), mDataRegState=0(IN_SERVICE), mChannelNumber=9000, ...
    mOperatorAlphaLong=T-Mobile, mOperatorAlphaShort=TMOBILE, ... mPci = 555 mTac = 8514 ... mBands = [41]
    mMcc = 310 mMnc = 260 ...
```

So: an **emulated RIL with a synthetic T-Mobile (MCC/MNC 310-260) profile and a fake NR/LTE cell** is present,
`mCallState=0`, no SIM, no real carrier, and no way to place a real call. It is a modem *simulation*, not a phone.
Consequences: real cellular/VoIP remote audio was **not captured and was not attempted** - which also matches the
product rule that Call Shield is visible, user-started microphone speech and never a claim of remote-audio capture.

### 2.9 AVD identity from the host

```
$ ls ~/.android/avd/
trustshield-api36.avd
trustshield-api36.ini

$ grep -E "image.sysdir|PlayStore|hw.ramSize|tag.id|abi.type" ~/.android/avd/trustshield-api36.avd/config.ini
PlayStore.enabled = no
abi.type = x86_64
hw.cpu.arch = x86_64
hw.cpu.ncore = 4
hw.ramSize = 1536M
image.sysdir.1 = system-images\android-36\google_apis\x86_64\
tag.id = google_apis

$ ls $ANDROID_HOME/system-images/android-36
google_apis
```

Only the `google_apis` image is installed locally. As the brief says, `google_apis` guarantees nothing about
on-device Hindi ASR - and §2.6 shows that on this build it guarantees the opposite.

### 2.10 Airplane-mode experiment, both directions, with state restored

`T0` is the untouched original state; every later snapshot uses the same four probes.

```
### T0 ORIGINAL STATE (before any change)
  settings global airplane_mode_on = 0
  wifi state: Wi-Fi is enabled
  interfaces UP: 2
  default net: Active default network: 100
```

```
### T1 enable airplane mode
$ adb -s emulator-5554 shell cmd connectivity airplane-mode enable
  exit=0
  settings global airplane_mode_on = 1
  wifi state: Wi-Fi is disabled
  interfaces UP: 0
  default net: Active default network: none
```

```
### T2 RecognitionService providers while in airplane mode
2 services found:
      name=com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService
      packageName=com.google.android.as
      name=com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService
      packageName=com.google.android.tts
```

```
### T3 disable airplane mode (restore)
$ adb -s emulator-5554 shell cmd connectivity airplane-mode disable
  exit=0
  settings global airplane_mode_on = 0
  wifi state: Wi-Fi is enabled
  interfaces UP: 2
  default net: Active default network: 102

### T3 wifi re-enable check + restore to original 'Wi-Fi is enabled'
  observed: Wi-Fi is enabled
  no action needed
### FINAL STATE
  settings global airplane_mode_on = 0
  wifi state: Wi-Fi is enabled
  interfaces UP: 2
  default net: Active default network: 102
```

**Restoration statement:** the original state was airplane mode **off** with Wi-Fi **enabled**. It was enabled for
the test, then disabled again, and the final snapshot matches the original on all four probes (`airplane_mode_on=0`,
`Wi-Fi is enabled`, `2` interfaces UP, an active default network). No `svc wifi enable` was needed. `adb root` was
also raised for §2.6 and reversed with `adb unroot`; post-unroot `adb shell id` returned
`uid=2000(shell) gid=2000(shell) ... context=u:r:shell:s0`, i.e. the default shell identity. **The emulator was left
in the state I found it in.**

What the experiment shows, and what it does not:

* **Shows:** losing all connectivity does not remove either `RecognitionService` binding - provider registration is
  package state, so it is unaffected by radio state. Therefore "a recogniser is available" proves nothing about
  offline capability.
* **Does not show:** whether those providers can transcribe with the network down. Determining that requires a
  runtime `SpeechRecognizer` session from inside an app (B04's `SpeechCapability` runtime probe), with `EXTRA_PREFER_OFFLINE`
  semantics. **Offline transcription success/failure is UNMEASURED.**

---

## 3. Capability matrix

`emulator measured value` = a value I obtained with the command in that row. `UNMEASURED` = no command in this
package produces it, on that device.

| # | Capability | Emulator-5554 (measured) | Command that produced it | Real phone (`hi-IN`, on-device) |
|---|---|---|---|---|
| 1 | OS / API level | Android 16, API 36, `x86_64` | `getprop ro.build.version.*`, `ro.product.cpu.abi` | **UNMEASURED** - no phone attached |
| 2 | `RecognitionService` providers registered | 2 via `query-services` (`com.google.android.as`, `com.google.android.tts`); 3 listed in `dumpsys package` incl. GSA | `cmd package query-services -a android.speech.RecognitionService`; `dumpsys package` | **UNMEASURED** |
| 3 | Default recogniser binding | `com.google.android.tts/...GoogleTTSRecognitionService` | `settings get secure voice_recognition_service` | **UNMEASURED** |
| 4 | On-device ASR language packs present | **`en-US` only** (`captions_i18n_v0_en-US`: `asr_en-US_v3003`, `asr_en-US_v3056`). `hi-IN` pack: **absent**. Google-app model dir: `total 0` | `ls -R .../com.google.android.as/files/superpacks` (root); `grep -a -c -i "hi-IN\|hindi"` on `SodaResourcesRecord.pb` -> `0` | **UNMEASURED** |
| 5 | `hi-IN` recognition accuracy (WER/CER) | **UNMEASURED** - no `hi-IN` model exists here to measure | - | **UNMEASURED** |
| 6 | `hi-IN` recognition latency | **UNMEASURED** | - | **UNMEASURED** |
| 7 | Recognition with radio off (true offline) | **UNMEASURED** - providers persist (T2) but no transcription session was run; that needs app code (B04) | `cmd connectivity airplane-mode enable/disable` | **UNMEASURED** |
| 8 | Hinglish (mixed EN/HI) behaviour | **UNMEASURED** | - | **UNMEASURED** |
| 9 | Device RAM | `MemTotal 2531832 kB`; `MemAvailable 1121136 kB`; `Total RAM: 2,531,832K`, `Free RAM: 1,440,617K`, `Used RAM: 1,499,219K` - **host RAM via x86_64 emulation** | `cat /proc/meminfo`; `dumpsys meminfo` | **UNMEASURED** |
| 10 | Audio input device | `Built-In Mic` present, unmuted; emulator virtual host audio | `dumpsys media.audio_policy`, `dumpsys audio` | **UNMEASURED** |
| 11 | Camera | 1 synthetic back camera with flash (`device@1.1/internal/1`) | `dumpsys media.camera` | **UNMEASURED** |
| 12 | Cellular / VoLTE call audio | Emulated RIL only, fake T-Mobile 310-260 cell, `mCallState=0`, no SIM. **Not captured, not attempted** (product rule: no remote-audio capture claim) | `dumpsys telephony.registry` | **UNMEASURED** and out of product scope |
| 13 | Vosk `hi-IN` end-to-end latency | **UNMEASURED** - model download not permitted this window; `org.vosk.*` not on the classpath | - | **UNMEASURED** |
| 14 | Vosk `hi-IN` RSS/PSS delta | **UNMEASURED** - same reason | - | **UNMEASURED** |
| 15 | Vosk Hinglish error rate on 10 scripted calls | **UNMEASURED** - same reason | - | **UNMEASURED** |
| 16 | APK size with ASR model | **Debug APK measured: `10,090,308` bytes** (`app/build/outputs/apk/debug/app-debug.apk`, built 22:50 IST from this branch). Gate is `<=50 MB without optional ASR model` (`QUALITY_GATES.md` §5). The 42 MB model size is the **brief's figure, not measured here** (no download permitted); adding that estimated asset size to the measured APK size projects a package over 50 MB by simple arithmetic | `./gradlew :app:assembleDebug --offline` + `ls -l` | **UNMEASURED** |
| 17 | Peak PSS of a live speech session | **UNMEASURED**; gate is `<=250 MB` on a *named mid-range device* | - | **UNMEASURED** |

**Count, stated precisely:** every row's *real phone* column is **UNMEASURED** (no phone attached). On the emulator,
8 of 17 rows carry no measurement at all (5, 6, 7, 8, 13, 14, 15, 17) - including all three numbers this package
exists to produce: latency, RAM delta and Hinglish error rate. That is the finding.

---

## 4. Emulator limits that bound what the above can prove

1. **Wrong ABI.** `x86_64` emulation, not `arm64-v8a`. Vosk ships per-ABI native `.so` files; an x86_64 latency
   number would not transfer to a phone's arm64 build, and no arm64 device exists here.
2. **Wrong acoustics.** No `hi-IN` pack (2.6). Anything measured would be English.
3. **Wrong microphone.** Virtual host audio bridge, not a phone mic in a noisy room. The edge case "noise" in the
   package spec cannot be exercised.
4. **Wrong memory model.** 2.5 GB of host RAM, not 3-8 GB of phone RAM with a real low-memory killer and a real
   thermal budget.
5. **No real call.** Emulated RIL, no SIM, `mCallState=0`; a real digital-arrest call cannot be placed or received.
6. **`google_apis`, not `google_apis_playstore`.** As briefed: guarantees nothing about Hindi on-device ASR. Here
   it demonstrably carries `en-US` only.

---

## 5. DECISION AND RATIONALE

### Decision

**NO-GO for bundling Vosk (or any downloadable on-device ASR model) in Phase 2.** Do **not** create
`input/speech/VoskCandidate.kt`. Do **not** add an `org.vosk:*` dependency. Ship Phase 2 speech on the platform
`SpeechRecognizer` path (B04 `SpeechCapability` + B05 `CallSpeechSession`), with the staged-transcript fallback in
§7 for the demo, and label offline/`hi-IN` capability as unproven until a phone is measured.

### Rationale (each point tied to evidence above)

1. **The model cannot be obtained in this window.** The 42 MB Hindi model requires a download; downloads are
   forbidden in this run (no URL fetch, no model download). A candidate implementation that cannot be built,
   run, or measured is not a deliverable - it is a liability that would silently become an unmeasured claim.
2. **Nothing here can stand in for the target.** §2.6: the only device available has an `en-US`-only on-device
   recogniser. Measuring Hinglish on it would produce a number about English. §4.1: it is also `x86_64`.
3. **The three numbers the package exists to produce are all absent** - latency, RAM delta, Hinglish error rate
   (matrix rows 13-15). A NO-GO that says so is honest; a code file would imply they exist.
4. **The requirement already forbids the claim.** `REQUIREMENTS.md` N02: "No claim that OS speech recognition is
   offline until the selected device passes an offline test." Vosk would be *our* offline engine, but the same
   standard applies to it - and it has not been measured either.
5. **It risks the size gate.** `QUALITY_GATES.md` §5 sets `APK <= 50 MB without optional ASR model`.
   This branch's debug APK measures **10,090,308 bytes** (about 9.6 MB); the brief's unmeasured estimate for the Hindi model is
   42 MB. Adding that estimate to the measured APK size projects a package over the gate. Not a close call, and not something to discover after merging.
6. **It needs a dependency I may not add.** `COMMON.md` BUILD REALITY 3: `org.vosk.*` is not on the classpath;
   `*.gradle.kts` is Codex-owned. Bundling would be a Gradle change plus an asset pipeline plus ProGuard/native-lib
   handling - unauthorised for me and unverified by anyone.
7. **There is a working alternative for the demo that needs none of this** (§7), and a working alternative for the
   product (`SpeechRecognizer`, B04/B05), so the NO-GO costs the 30 Oct demo nothing.

### Why `VoskCandidate.kt` does not exist - stated as required

The package brief allows that file **only if actually measured**. It was not measured, because:

* no physical phone is attached (`adb devices -l` shows only `emulator-5554`);
* the Vosk `hi-IN` model cannot be downloaded in this window (hard prohibition);
* the emulator has no `hi-IN` speech pack to compare against, so there is no baseline either;
* and `org.vosk.*` is not on the compile classpath, so the file could not be compiled or verified even if written.

Writing a `VoskCandidate` object containing a recogniser class reference I never executed would break the
"verified by running something" rule at the top of the orders. So the file is absent **by decision**, and this
section is the record of that decision. Anyone who later creates it must attach the measurements in §8 first.

---

## 6. Impact on the other speech packages

* **B04 `SpeechCapability`** - unaffected. Its runtime probe is exactly the tool that would settle §3 row 7 on a
  real phone. Recommendation: have it report `onDeviceHiIn = false/true/unknown` and persist which it was, so the
  UI never implies offline Hindi it did not verify.
* **B05 `CallSpeechSession`** - unaffected, and it is the seam the demo uses (§7).
* **A05 scoring** - unaffected; verdict logic never consumes a recogniser, only text.

---

## 7. Stage-speech fallback plan for the demo

**This is a staged demonstration. It is not live call interception, it does not use the microphone, and it records
no audio.** It must be labelled as staged on screen at all times.

**Why it exists:** the demo needs a Call Shield story that cannot fail on stage because a vendor recogniser is
busy, a room is noisy, or the phone lacks a Hindi pack.

### Shape

`CallSpeechSession` (B05, seam only - **B05 owns the file, I do not add it here**) consumes a stream of partial
and final transcript strings. Today its source is `SpeechRecognizer`. For the demo, a *staged source* supplies the
same interface, on a timer, from a scripted Hinglish digital-arrest transcript. Nothing downstream changes: the
same normalisation, the same A03/A04 rules, the same stage state machine, the same bounded two-minute window, the
same "ask + two stage" gate. Only the origin of the words is scripted.

Interface sketch the demo needs (for B05/demo owner to bind in their own file - **not created by B08**):

```kotlin
// Owner: B05 (CallSpeechSession) / demo wiring. Not part of B08.
interface TranscriptSource {
    fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit)
    fun stop()
}
// Live:     SpeechRecognizerSource(SpeechRecognizer)      -> real, visible, user-started mic
// Demo:     StagedScriptSource(script, clock)             -> scripted, labelled DEMO
```

### Scripted transcript (10 turns, Hinglish, all identifiers fake and masked)

| # | Speaker | Line (demo transcript) |
|---|---|---|
| 1 | Caller | "Sir main CBI cyber cell se bol raha hoon, aapke naam par ek parcel mila hai." |
| 2 | Caller | "Usme kuch illegal documents the, isliye aapka Aadhaar link ho gaya hai is case se." |
| 3 | Caller | "Aapko abhi kisi ko nahi batana, ye confidential investigation hai." |
| 4 | Victim | "Ji... main kya karun?" |
| 5 | Caller | "Aap turant video call par aaiye, uniform wale officer aapko warrant dikhayenge." |
| 6 | Caller | "Aapke account se paise transfer hue hain, wo clear karna padega verification ke liye." |
| 7 | Caller | "Rs 49,000 ek government treasury account mein bhejiye, refund ho jayega." |
| 8 | Caller | "Aur jo OTP aaya hai woh mujhe bata dijiye, warna 2 ghante mein arrest ho jayega." |
| 9 | Victim | "Itna jaldi? Main bank jaata hoon." |
| 10 | Caller | "Nahi bank nahi, online hi karna hoga, abhi, warna case file ho jayegi." |

(The OTP **value** never appears; the phone/UPI/card numbers are deliberately absent rather than dummy-filled, so
the demo cannot leak a plausible credential into a screenshot. `BEHAVIOR_SPEC.md` "Conversation mode and Call
Shield" requires exactly this: stage IDs and quotes persist, words drop after the bounded window, and a single
misheard token lowers confidence rather than firing a critical alert.)

Example of the same call delivered by `adb` for a rehearsal-only playback path, if the demo owner prefers pushing
text over a debug channel instead of a timer - again, scripted text, no audio:

```bash
adb -s <device> shell input text "Sir%smain%sCBI%scyber%scell%23se%sbol%sraha%shoon"
```

### Non-negotiable labels

1. A persistent, non-dismissable banner: **"DEMO - scripted transcript. Not a live call. No audio is recorded."**
2. The demo script must state aloud, before and after: this is a staged demonstration; Call Shield in the product
   transcribes visible, user-started microphone speech, and no app can read the remote party's audio of a real call.
3. No accuracy, latency or "detects scams on real calls" claim attaches to the staged run. The only true claim is:
   *the deterministic rules produced these evidence items and this score from this text* - which is real, and is
   what the app actually does.
4. Never show the staged run with airplane mode off and imply online enrichment; A06/A07 keep enrichment disabled
   by default.

---

## 8. What would change the decision

The decision is NO-GO **on evidence**, so it reverses on evidence - not on argument. In priority order:

1. **A physical phone whose on-device recogniser reports `hi-IN` support.** Verified either by the same
   superpack listing as §2.6 (a `captions_i18n_v0_hi-IN` / `asr_hi-IN_*` pack) or by B04's runtime probe returning
   `hi-IN` supported with radio off. If that passes, **Vosk is not needed at all** and this package should close as
   "platform recogniser sufficient", which is the better outcome.
2. **Permission to download the 42 MB model plus one named target phone.** Then produce, on that phone, and only
   then write `VoskCandidate.kt`:
   * end-to-end latency per utterance (first partial, final), median and p90 over the 10-call script;
   * PSS delta of the app process with the model loaded versus baseline, against the `<=250 MB` gate;
   * Hinglish error rate on the 10 scripted calls, scored by a written-down rubric, compared against the same
     transcript through the platform recogniser;
   * APK/asset size against the `<=50 MB without optional ASR model` gate, with the model as a **post-install
     download**, not an APK asset, or the gate changes on the record.
3. **Airplane-mode transcription actually succeeding on that phone** (§3 row 7), since offline is the whole
   reason to carry our own engine.
4. **A decision that the demo must run with no network and no platform recogniser at all.** Even then the answer
   is §7's staged transcript, not a 42 MB bundle inside an unmeasured APK.

If 1 fails and 2 is not granted, the answer stays: no Vosk this phase, platform recogniser plus staged demo, and
"offline Hindi not verified" on the slide.

---

## 9. Codex Gradle/manifest needs

**None.** This package adds one Markdown file and no code, no asset, no dependency, no permission.

For the record, what a *future* Vosk package would require - conditional on the measurements in §8 and therefore
**not** requested now:

| Item | Exact coordinates | Status |
|---|---|---|
| Maven coordinate | `com.alphacephei:vosk-android:0.3.47` (version to be pinned by whoever measures it) | **conditional, not requested** |
| Native libs / ABI filters | `arm64-v8a`, `armeabi-v7a` in `android.defaultConfig.ndk.abiFilters` | **conditional, not requested** |
| Packaging | `packaging { jniLibs { useLegacyPackaging = true } }` (Vosk's `.so` loading needs it) | **conditional, not requested** |
| Model asset | **must not** be an APK asset (42 MB against a 50 MB gate); download-after-install with a user-visible consent step and an offline-degraded path | **conditional, not requested** |
| Manifest | `RECORD_AUDIO` already needed by B04/B05; **no new permission** for on-device ASR | none |
| ProGuard | keep `org.vosk.**` if minification is ever enabled | **conditional, not requested** |

---

## 10. Reproduction

Everything above is reproducible with `adb` from `$ANDROID_HOME/platform-tools` against `emulator-5554`; no network
access is required for any step. Read-only except §2.10, which restores itself and is certified restored there, and
§2.6's `adb root` / `adb unroot` pair, which is reversed on the spot.

`./gradlew :app:testDebugUnitTest` is **not** used as evidence in this package: per `COMMON.md` BUILD REALITY 1 it
cannot compile any test today (`testImplementation` is missing from `apps/android/app/build.gradle.kts`, Codex-owned).
No test file was added here because no production code was added - there is nothing to test.

The build gate was nevertheless run, from `apps/android`, with no code change:

```
$ ./gradlew :app:assembleDebug --offline
> Task :app:assembleDebug UP-TO-DATE

BUILD SUCCESSFUL in 1s
37 actionable tasks: 37 up-to-date
GRADLE_EXIT=0

$ ls -l app/build/outputs/apk/debug/
-rw-r--r-- 1 shubh 197610 10090308 Oct 25 22:50 app-debug.apk
-rw-r--r-- 1 shubh 197610      406 Oct 25 22:50 output-metadata.json
```

`UP-TO-DATE` because the build was already green from the first run at 22:50; the APK timestamp and size above are
that build's, and a docs-only change cannot invalidate it.
