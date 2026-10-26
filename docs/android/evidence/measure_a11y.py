#!/usr/bin/env python3
"""Measure the TrustShield accessibility tree from `uiautomator dump` XML files (issue #97).

Why this exists: `accessibility_audit.py` is a *static* grep over Compose source. It cannot see a
rendered 48 dp target, a node that has no label at runtime, or text that overflowed at 200 % font
scale. This script reads the real accessibility tree that `adb shell uiautomator dump` captured on
the emulator and reports, per dump:

  * how many nodes are clickable and the smallest clickable node in dp (48 dp minimum, UI_SPEC),
  * clickable nodes that are smaller than 48 dp in either dimension,
  * clickable nodes with neither visible text nor a content description (unlabelled control),
  * nodes whose text already ends in an ellipsis ("…" or "..."), i.e. rendered truncation,
  * nodes whose bounds leave the screen box (horizontal or vertical overflow).

Dump file names must be `<phase>__<screen>__<font_scale>x__<width_dp>dp.xml`, for example
`after__call__2.0x__320dp.xml`; the name is parsed into the report so one table covers every
configuration. Density comes from `--density` (the emulator's `wm density`, default 420).

Usage:
    python measure_a11y.py <dump-dir> [--density 420] [--csv a11y-measurements.csv]
"""

from __future__ import annotations

import argparse
import csv
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
MIN_TOUCH_DP = 48.0
NAME = re.compile(r"^(?P<phase>[^_]+(?:_[^_]+)*?)__(?P<screen>[a-z_]+)__(?P<scale>\d+(?:\.\d+)?)x__(?P<width>\d+)dp$")


def parse_dump(path: Path, density: int):
    """Return (row, undersized, unlabelled) for one dump.

    Compose exposes a clickable *container* node with no text of its own whose visible label is a
    child TextView, so a clickable node counts as labelled when it or any descendant carries text or
    a content description. Reporting the container as "unlabelled" would be a false positive.
    """
    root = ET.parse(path).getroot()
    px_per_dp = density / 160.0
    nodes = list(root.iter("node"))

    def own_label(node) -> str:
        return ((node.get("text") or "") + " " + (node.get("content-desc") or "")).strip()

    def subtree_label(node) -> str:
        parts = [own_label(node)]
        parts.extend(own_label(child) for child in node.iter("node") if child is not node)
        return " ".join(p for p in parts if p).strip()

    # Screen box from the root node (the dump's own bounds), which is the real display size.
    root_bounds = BOUNDS.search(nodes[0].get("bounds", "") or "")
    screen_w = int(root_bounds.group(3)) if root_bounds else 0
    screen_h = int(root_bounds.group(4)) if root_bounds else 0

    clickable = 0
    min_dp = None
    text_nodes = 0
    undersized, unlabelled, ellipsis, overflow = [], [], [], []

    for node in nodes:
        cls = node.get("class", "")
        text = (node.get("text") or "").strip()
        desc = (node.get("content-desc") or "").strip()
        is_clickable = node.get("clickable") == "true"
        bounds = BOUNDS.search(node.get("bounds", "") or "")
        if not bounds:
            continue
        x1, y1, x2, y2 = (int(g) for g in bounds.groups())
        w_px, h_px = x2 - x1, y2 - y1
        w_dp, h_dp = w_px / px_per_dp, h_px / px_per_dp
        label = subtree_label(node)

        if text:
            text_nodes += 1
            if text.endswith("\u2026") or text.endswith("..."):
                ellipsis.append(f"{cls.split('.')[-1]}:{text[:40]}")

        if is_clickable:
            clickable += 1
            small = min(w_dp, h_dp)
            min_dp = small if min_dp is None else min(min_dp, small)
            if small < MIN_TOUCH_DP:
                undersized.append(f"{cls.split('.')[-1]} '{label[:32]}' {w_dp:.1f}x{h_dp:.1f}dp")
            if not label:
                unlabelled.append(cls.split('.')[-1])

        if screen_w and screen_h and (x2 > screen_w or y2 > screen_h or x1 < 0 or y1 < 0):
            overflow.append(f"{cls.split('.')[-1]} '{label[:28]}' [{x1},{y1}][{x2},{y2}]")

    match = NAME.match(path.stem)
    if match:
        phase, screen, scale, width = (match.group(k) for k in ("phase", "screen", "scale", "width"))
    else:  # keep the row even when the name is off-spec
        phase, screen, scale, width = "unknown", path.stem, "?", "?"

    row = {
        "phase": phase,
        "screen": screen,
        "font_scale": scale,
        "width_dp": width,
        "clickable_nodes": clickable,
        "min_clickable_dp": "-" if min_dp is None else f"{min_dp:.1f}",
        "undersized": len(undersized),
        "unlabelled": len(unlabelled),
        "ellipsis": len(ellipsis),
        "overflow": len(overflow),
        "text_nodes": text_nodes,
    }
    return row, undersized, unlabelled, ellipsis, overflow


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("dump_dir", type=Path)
    ap.add_argument("--density", type=int, default=420, help="wm density of the device (default 420)")
    ap.add_argument("--csv", type=Path, default=None, help="write the table to this CSV file")
    args = ap.parse_args(argv)

    dumps = sorted(args.dump_dir.glob("*.xml"))
    if not dumps:
        print(f"no *.xml dumps in {args.dump_dir}", file=sys.stderr)
        return 2

    rows, details = [], []
    for path in dumps:
        row, undersized, unlabelled, ellipsis, overflow = parse_dump(path, args.density)
        rows.append(row)
        details.append((path.name, undersized, unlabelled, ellipsis, overflow))

    header = ["phase", "screen", "font_scale", "width_dp", "clickable_nodes", "min_clickable_dp",
              "undersized", "unlabelled", "ellipsis", "overflow", "text_nodes"]
    print("| " + " | ".join(header) + " |")
    print("|" + "|".join("---" for _ in header) + "|")
    for row in rows:
        print("| " + " | ".join(str(row[h]) for h in header) + " |")

    problems = False
    for name, undersized, unlabelled, ellipsis, overflow in details:
        if undersized or unlabelled or overflow:
            problems = True
            print(f"\n{name}")
            for label, items in (
                ("clickable < 48 dp", undersized),
                ("clickable with no text/description", unlabelled),
                ("bounds outside the screen box", overflow),
            ):
                if items:
                    print(f"  {label}:")
                    for item in items:
                        print(f"    - {item}")
    if not problems:
        print("\nNo undersized, unlabelled or overflowing clickable node in any dump.")

    if args.csv:
        with args.csv.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=header)
            writer.writeheader()
            writer.writerows(rows)
        print(f"\nwrote {args.csv} ({len(rows)} rows)")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
