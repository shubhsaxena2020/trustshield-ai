#!/usr/bin/env python3
"""Drive one API 36 emulator through the #97 accessibility matrix and keep the raw evidence.

For every (font_scale x width) configuration and every screen this writes a real
`uiautomator dump` of the accessibility tree (plus a screenshot for the chosen configurations).
`measure_a11y.py` turns those dumps into the numbers quoted in `accessibility.md`.

    python run_a11y_matrix.py <phase-label> <out-dir> [--screens ...] [--configs ...] [--shots ...]

Defaults cover the matrix that matters: 1.0x / 1.3x / 2.0x font scale at the emulator's own 411 dp
width, then the same three scales at a simulated 320 dp width (wm size 840x2400 at density 420).

Call Shield only renders its risk meter and stage chips once a session is staged, so the driver taps
"Staged demo" and then advances the demo line twice. Every tap is by *label*, found in the live dump,
so the driver fails loudly instead of tapping a hardcoded coordinate that drifted.

Requirements: exactly ONE emulator attached, booted, with the app installed. The caller owns the
shared emulator lock (…/phase4-emulator.lock) and stops the emulator afterwards — this script never
starts or kills a device, and it restores `wm size` and `font_scale` when it finishes.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = "C:/Users/shubh/AppData/Local/Android/Sdk/platform-tools/adb.exe"
PKG = "ai.trustshield.android"
ACTIVITY = f"{PKG}/.MainActivity"
ROUTE_EXTRA = f"{PKG}.extra.ROUTE"
SCAM = "SBI Aapka KYC 24 ghante main band ho jaayega Link par KYC update karo aur OTP batao"

BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def adb_bytes(*args: str, timeout: int = 60) -> bytes:
    return subprocess.run([ADB, *args], capture_output=True, timeout=timeout).stdout


def adb(*args: str, timeout: int = 60) -> str:
    return adb_bytes(*args, timeout=timeout).decode("utf-8", "replace").strip()


def wait_for_focus(marker: str = PKG, timeout_s: int = 25) -> bool:
    """Wait until the app window is actually focused. Cold start here measured ~5.4 s, so a fixed
    sleep is not enough: uiautomator returns 'null root node' while the window is still coming up,
    and the previous /sdcard/a11y.xml then survives and gets recorded as fresh evidence."""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        focus = adb("shell", "dumpsys", "window")
        line = next((l for l in focus.splitlines() if "mCurrentFocus" in l), "")
        if marker in line:
            time.sleep(0.8)
            return True
        time.sleep(0.5)
    print(f"      WARNING: {marker} never took focus within {timeout_s}s")
    return False


def launch(screen: str) -> None:
    adb("shell", "am", "force-stop", PKG)
    time.sleep(0.5)
    if screen == "home":
        adb("shell", "am", "start", "-n", ACTIVITY)
    elif screen in ("call", "qr", "settings"):
        adb("shell", "am", "start", "-n", ACTIVITY, "--es", ROUTE_EXTRA,
            {"call": "call_shield", "qr": "qr_guard", "settings": "settings"}[screen])
    elif screen == "result":
        adb("shell", "am", "start", "-n", ACTIVITY, "-a", "android.intent.action.SEND",
            "-t", "text/plain", "--es", "android.intent.extra.TEXT", f"'{SCAM}'")
    else:
        raise SystemExit(f"unknown screen {screen!r}")
    wait_for_focus()


def dump_xml(retries: int = 4) -> ET.Element | None:
    for _ in range(retries):
        adb("shell", "rm", "-f", "/sdcard/a11y.xml")
        out = adb("shell", "uiautomator", "dump", "/sdcard/a11y.xml")
        if "dumped" not in out.lower():
            time.sleep(1.5)
            continue
        raw = adb("exec-out", "cat", "/sdcard/a11y.xml")
        if "<hierarchy" not in raw:
            time.sleep(1.5)
            continue
        try:
            tree = ET.fromstring(raw)
        except ET.ParseError:
            time.sleep(1.5)
            continue
        root = next(tree.iter("node"), None)
        if root is None or root.get("package") != PKG:
            time.sleep(1.5)
            continue
        return tree
    return None


def tap_label(wanted: str, timeout_s: int = 8) -> bool:
    """Tap the node whose label matches `wanted`, preferring an exact match.

    The demo screen contains the hint sentence "A05 script chalane ke liye Agli line dabayein." next
    to the real "Agli line" button, so a plain substring search taps the hint and nothing happens.
    """
    needle = wanted.lower()

    def label(node) -> str:
        return ((node.get("text") or "") + " " + (node.get("content-desc") or "")).strip().lower()

    deadline = time.time() + timeout_s
    while time.time() < deadline:
        tree = dump_xml(retries=1)
        if tree is not None:
            nodes = [n for n in tree.iter("node") if n.get("bounds")]
            for match in (lambda l: l == needle, lambda l: l.startswith(needle),
                          lambda l: needle in l):
                for node in nodes:
                    text = label(node)
                    if text and match(text):
                        x1, y1, x2, y2 = (int(g) for g in BOUNDS.search(node.get("bounds")).groups())
                        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                        time.sleep(1.4)
                        return True
        time.sleep(0.8)
    return False


def prepare(screen: str) -> None:
    """Put the screen into the state whose semantics we want to record."""
    if screen != "call":
        return
    if not (tap_label("staged demo") or tap_label("demo")):
        print("      WARNING: staged demo control not found; call shield stays on its intro state")
        return
    print("      opened the staged demo path")
    for _ in range(3):
        if tap_label("agli line") or tap_label("next line"):
            print("      advanced one demo line")
        else:
            print("      no further demo line control")
            break


def capture(screen: str, cfg: str, out_dir: Path, phase: str, shot: bool) -> bool:
    tree = dump_xml()
    if tree is None:
        print(f"  DUMP FAILED  {screen}  {cfg}")
        return False
    path = out_dir / f"{phase}__{screen}__{cfg}.xml"
    path.write_bytes(ET.tostring(tree, encoding="utf-8"))
    print(f"  dump  {screen:8s} {path.name}  ({path.stat().st_size} bytes)")
    if shot:
        png = out_dir / f"{phase}__{screen}__{cfg}.png"
        png.write_bytes(adb_bytes("exec-out", "screencap", "-p"))
        print(f"  shot  {screen:8s} {png.name}  ({png.stat().st_size} bytes)")
    return True


def set_config(scale: str, width_dp: str) -> str:
    size = "840x2400" if width_dp == "320" else None  # 320 dp at density 420 (840 / 2.625)
    adb("shell", "settings", "put", "system", "font_scale", scale)
    if size:
        adb("shell", "wm", "size", size)
    else:
        adb("shell", "wm", "size", "reset")
    time.sleep(1.2)
    return size or "default"


def restore() -> None:
    adb("shell", "wm", "size", "reset")
    adb("shell", "settings", "delete", "system", "font_scale")
    print("== restored: " + adb("shell", "wm", "size") + "  " + adb("shell", "wm", "density")
          + "  font_scale=" + adb("shell", "settings", "get", "system", "font_scale"))


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("phase", help='"before" / "after" — prefixes every dump name')
    ap.add_argument("out_dir", type=Path)
    ap.add_argument("--screens", default="home,result,call,qr,settings")
    ap.add_argument("--configs", default="1.0x__411dp,1.3x__411dp,2.0x__411dp,"
                                         "1.0x__320dp,1.3x__320dp,2.0x__320dp")
    ap.add_argument("--shots", default="1.0x__411dp,2.0x__320dp",
                    help='config ids to screenshot, "all" or "none"')
    args = ap.parse_args(argv)

    screens = [s for s in args.screens.split(",") if s]
    configs = [c for c in args.configs.split(",") if c]
    shots = {c.strip() for c in args.shots.split(",")}
    args.out_dir.mkdir(parents=True, exist_ok=True)

    print(f"== phase={args.phase} out={args.out_dir}")
    print("== device: sdk " + adb("shell", "getprop", "ro.build.version.sdk")
          + "  " + adb("shell", "wm", "size") + "  " + adb("shell", "wm", "density"))

    failures, ok = [], 0
    for cfg in configs:
        scale, width = cfg.split("__")
        size = set_config(scale.rstrip("x"), width.rstrip("dp"))
        print(f"-- config {cfg}  (font_scale={scale.rstrip('x')}, wm size={size})")
        for screen in screens:
            launch(screen)
            prepare(screen)
            want_shot = "all" in shots or cfg in shots
            if capture(screen, cfg, args.out_dir, args.phase, want_shot):
                ok += 1
            else:
                failures.append(f"{cfg} {screen}")

    restore()
    print(f"\n== {ok} dumps written, {len(failures)} failed")
    for failure in failures:
        print(f"   FAILED {failure}")
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
