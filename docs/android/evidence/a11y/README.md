# Accessibility evidence — protocol

What goes in this directory and how it is produced. **The directory currently holds no
measurements: every row in `docs/android/A11Y_AUDIT.md` is `not run`.** Nothing here may claim a run
that was not performed.

## 1. The one emulator

There is exactly one Android emulator on this host and it is shared. Before starting anything:

**Lock file** — `C:\Users\shubh\AppData\Local\Temp\android\phase4-emulator.lock`

1. Try to create it exclusively. If it already exists, **stop** — read the owner name and timestamp
   inside it and come back later.
2. On acquiring it, write exactly two lines: your lane name and an ISO-8601 timestamp with IST offset,
   e.g.
   ```
   laneC-agent3
   2026-10-27T20:15:00+05:30
   ```
3. Delete it the moment you have stopped the emulator — not before (`adb emu kill` first, then the
   lock), and also if you give up without starting anything.
4. A lock older than **45 minutes** whose owner has no live `emulator.exe` / `qemu-system-*`
   process may be reclaimed — state in your evidence file that you reclaimed it and why.

**Start it** exactly like this, once, and stop it afterwards:

```bash
emulator -avd trustshield-api36 -memory 2048 -no-snapshot -no-boot-anim
adb -s emulator-5554 wait-for-device
adb -s emulator-5554 shell getprop sys.boot_completed   # expect 1
adb -s emulator-5554 shell getprop ro.boot.qemu.avd_name # expect trustshield-api36
# ... run the matrix ...
adb -s emulator-5554 emu kill                           # then delete the lock file
```

`-no-snapshot` is not optional: a snapshot restore resurrects stale app state and makes a
"cold start" claim false. One emulator at a time, one Gradle build at a time — a build owned by
another lane must not be running while you drive the device.

**Never** run any command against the owner's phone, serial `63b0de52`, under any circumstance, and
never leave `ANDROID_SERIAL` exported to it. Always name the target: `-s emulator-5554`.

## 2. Screenshots — naming and location

Save into this directory (`docs/android/evidence/a11y/`) using **exactly**:

```
a11y-<screen>-<fontscale>-<theme>-<width>-<orientation>.png
```

| token | allowed values |
|---|---|
| `<screen>` | `home`, `result-high`, `result-low`, `call-consent`, `call-critical`, `qr-setup`, `qr-mismatch`, `settings`, `demo` |
| `<fontscale>` | `1.0`, `1.3`, `1.5`, `2.0` |
| `<theme>` | `light`, `dark` |
| `<width>` | `320dp`, `360dp` |
| `<orientation>` | `portrait`, `landscape` |

Examples:

```
a11y-result-high-2.0-dark-320dp-portrait.png
a11y-qr-mismatch-1.0-light-360dp-portrait.png
a11y-settings-1.5-light-320dp-landscape.png
```

Capture with the platform tool, never a phone photo of a screen:

```bash
adb -s emulator-5554 exec-out screencap -p > a11y-home-1.0-light-360dp-portrait.png
```

Screen-reader transcripts (the only other accepted evidence) are named
`talkback-<screen>-<YYYYMMDD>.txt`, one line per utterance, in the order heard, written by the person
who heard them. A machine-generated accessibility-tree dump is **not** a substitute — it proves the
tree, not the speech.

## 3. Restore, every time

The commands that change state, and how each one is undone. Run the restore block even if you think
you did not change anything:

| setting | set | restore |
|---|---|---|
| font scale | `settings put system font_scale 1.3` (also `1.0` / `1.5` / `2.0`) | `settings put system font_scale 1.0` |
| width | `wm size 840x2400` (320 dp) / `wm size 945x2400` (360 dp) @ 420 dpi | `wm size reset` |
| reduced motion | `settings put global animator_duration_scale 0` (+ `transition_animation_scale 0`, `window_animation_scale 0`) | all three back to `1` |
| orientation | `settings put system accelerometer_rotation 0` + `user_rotation 1` | `settings put system accelerometer_rotation 1` |
| TalkBack | `settings put secure accessibility_enabled 1` + `enabled_accessibility_services …TalkBackService` | `accessibility_enabled 0`, `settings delete secure enabled_accessibility_services` |

Verify with `settings get system font_scale`, `wm size`, `settings get global
animator_duration_scale`, `settings get secure enabled_accessibility_services` before you release the
lock. Do **not** "fix" a width override by changing `wm density` — that changes the dp width you were
testing.

## 4. The PASS rule

A row in `A11Y_AUDIT.md` may be marked **PASS** only with **one** of:

- a screenshot file that exists in this directory and whose name matches §2; or
- a spoken-TalkBack transcript (`talkback-*.txt`) recorded by a **human listener** who heard the
  utterances, naming the row id.

Everything else stays `not run` / `OPEN` / `FIXED-BY-WAVE-1`. Specifically:

- a source read cannot produce a PASS;
- a passing Compose semantics assertion cannot produce a PASS for a *spoken* row — it proves the tree,
  not the speech;
- a machine accessibility dump attached without a listener still counts as `not run`;
- "the label looks right in the code" is not evidence.

Every measurement row starts as **`not run`** and only moves when the file or the transcript exists.

## 5. Required companion record

For each session, add a short `session-<YYYYMMDD>-<lane>.md` next to the captures stating: lock
owner and times, AVD + image, `wm size` / `wm density` / `font_scale` / animator scales actually read
back, the build under test (git commit + APK SHA-256), which matrix cells were attempted, which
succeeded, which failed and the defect id each failure maps to, and everything that could not be
measured. A session note that lists no failures is a claim worth doubting.
