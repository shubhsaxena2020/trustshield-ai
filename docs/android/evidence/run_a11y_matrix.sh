#!/usr/bin/env bash
# Drive one API 36 emulator through the #97 accessibility matrix and keep the raw evidence.
#
# Produces, for every (font_scale x width) configuration and every screen, a real
# `uiautomator dump` of the accessibility tree plus (for selected configurations) a screenshot.
# `measure_a11y.py` turns the dumps into the numbers quoted in accessibility.md.
#
#   ./run_a11y_matrix.sh <phase-label> <out-dir> [screenshot-configs]
#
#     phase-label        "before" / "after" — prefixes every dump name
#     out-dir            where the dumps and screenshots go
#     screenshot-configs comma-separated config ids to screenshot, "all" or "none"
#                        (default "2.0x__320dp,2.0x__411dp")
#
# Requirements: exactly ONE emulator attached, booted, with the app installed. The caller owns the
# shared emulator lock (C:/Users/shubh/AppData/Local/Temp/android/phase4-emulator.lock) and stops the
# emulator afterwards — this script never starts or kills a device.
set -u

ADB="${ADB:-C:/Users/shubh/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
PKG=ai.trustshield.android
ACT="$PKG/.MainActivity"
SCAM="SBI Aapka KYC 24 ghante main band ho jaayega Link par KYC update karo aur OTP batao"

PHASE="${1:?usage: run_a11y_matrix.sh <phase-label> <out-dir> [shot-configs]}"
OUT="${2:?usage: run_a11y_matrix.sh <phase-label> <out-dir> [shot-configs]}"
SHOT_CFGS="${3:-2.0x__320dp,2.0x__411dp}"
mkdir -p "$OUT"

say() { printf '%s\n' "$*"; }

dump_screen() {  # dump_screen <screen> <config-id>
  local screen="$1" cfg="$2" file="$OUT/${PHASE}__${screen}__${cfg}.xml"
  local attempt
  for attempt in 1 2 3; do
    $ADB shell uiautomator dump /sdcard/a11y.xml >/dev/null 2>&1
    $ADB pull /sdcard/a11y.xml "$file" >/dev/null 2>&1
    if [ -s "$file" ] && grep -q "<hierarchy" "$file"; then
      say "  dump  $screen  ($(wc -c <"$file") bytes)"
      return 0
    fi
    sleep 1
  done
  say "  DUMP FAILED  $screen  $cfg"
  return 1
}

launch() {  # launch <screen>
  $ADB shell am force-stop $PKG
  sleep 0.4
  case "$1" in
    home)     $ADB shell am start -n $ACT >/dev/null ;;
    call)     $ADB shell am start -n $ACT --es "$PKG.extra.ROUTE" call_shield >/dev/null ;;
    qr)       $ADB shell am start -n $ACT --es "$PKG.extra.ROUTE" qr_guard >/dev/null ;;
    settings) $ADB shell am start -n $ACT --es "$PKG.extra.ROUTE" settings >/dev/null ;;
    result)   $ADB shell am start -n $ACT -a android.intent.action.SEND -t text/plain \
                --es android.intent.extra.TEXT "'$SCAM'" >/dev/null ;;
  esac
  sleep 2.5
}

shot() {  # shot <screen> <config-id>
  $ADB exec-out screencap -p > "$OUT/${PHASE}__${screen}__${1}__${2}.png" 2>/dev/null
}

SCREENS="${SCREENS:-home result call qr settings}"
CONFIGS="${CONFIGS:-1.0x__411dp 1.3x__411dp 2.0x__411dp 1.0x__320dp 1.3x__320dp 2.0x__320dp}"

say "== phase=$PHASE  out=$OUT"
say "== device: $($ADB shell getprop ro.build.version.sdk | tr -d '\r')  $( $ADB shell wm size | tr -d '\r')  $( $ADB shell wm density | tr -d '\r')"

for cfg in $CONFIGS; do
  scale="${cfg%%__*}"; scale="${scale%x}"
  width="${cfg##*__}"; width="${width%dp}"
  case "$width" in
    320) size="840x2400" ;;   # 320 dp at density 420 (840 / 2.625)
    *)   size="reset" ;;
  esac

  say "-- config $cfg (font_scale=$scale, wm size=$size)"
  $ADB shell settings put system font_scale "$scale"
  if [ "$size" = "reset" ]; then $ADB shell wm size reset; else $ADB shell wm size "$size"; fi
  sleep 1

  for screen in $SCREENS; do
    launch "$screen"
    dump_screen "$screen" "$cfg" || true
    case ",$SHOT_CFGS," in
      *,all,*) shot "$screen" "$cfg" ;;
      *",$cfg,"*) shot "$screen" "$cfg" ;;
    esac
  done
done

# Leave the device in its default configuration; the caller re-checks wm size / font_scale.
$ADB shell wm size reset
$ADB shell settings delete system font_scale >/dev/null
say "== restored: $( $ADB shell wm size | tr -d '\r')  $( $ADB shell wm density | tr -d '\r')  font_scale=$($ADB shell settings get system font_scale | tr -d '\r')"
