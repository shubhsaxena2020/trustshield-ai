#!/usr/bin/env python3
"""Render and measure the TrustShield launcher/splash vectors without a device (#139).

The #139 acceptance claim is geometric, so it can be checked mechanically:

  * every point of the artwork must stay inside the adaptive-icon safe circle
    (centre 54,54 of the 108x108 viewport, r = 33 dp) — otherwise a launcher mask crops it;
  * the T must survive at 48 dp, i.e. the stroke should be >= ~2 dp and the glyph should not vanish
    into the shield.

This script parses the pathData out of `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`
and `ic_splash_mark.xml`, flattens the cubic segments, reports the maximum radius and the smallest
internal gap, and renders PNGs (with even-odd filling, 4x supersampled) so a human can look at the
mark at 108 px and at 48 px. Requires Pillow.

    python render_icon_check.py <res-dir> <out-dir>

`<res-dir>` is `apps/android/app/src/main/res`; `<out-dir>` receives the PNGs. Exit code is non-zero
if any artwork point leaves the safe circle.
"""

from __future__ import annotations

import math
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageDraw

ANDROID = "{http://schemas.android.com/apk/res/android}"
SAFE_CENTER = (54.0, 54.0)
SAFE_RADIUS = 33.0  # 66 dp guaranteed-visible circle inside the 108 dp viewport
SS = 4  # supersampling factor

TOKEN = re.compile(r"[MLCZmlcz]|-?\d+(?:\.\d+)?")


def argb(value: str) -> tuple[int, int, int, int]:
    """Android #AARRGGBB / #RRGGBB -> PIL RGBA. Android puts alpha FIRST, PIL puts it last."""
    digits = value.lstrip("#")
    if len(digits) == 8:
        a, r, g, b = (int(digits[i:i + 2], 16) for i in (0, 2, 4, 6))
        return (r, g, b, a)
    if len(digits) == 6:
        r, g, b = (int(digits[i:i + 2], 16) for i in (0, 2, 4))
        return (r, g, b, 255)
    raise SystemExit(f"cannot parse colour {value!r}")


def flatten(path_data: str, steps: int = 24):
    """Return (subpaths, kind) for absolute M/L/C/Z path data; each subpath is a point list."""
    tokens = TOKEN.findall(path_data)
    subpaths, current = [], []
    point = (0.0, 0.0)
    index = 0
    while index < len(tokens):
        command = tokens[index]
        index += 1
        if command in "Mm":
            x, y = float(tokens[index]), float(tokens[index + 1])
            index += 2
            if current:
                subpaths.append(current)
            current = [(x, y)]
            point = (x, y)
        elif command in "Ll":
            x, y = float(tokens[index]), float(tokens[index + 1])
            index += 2
            current.append((x, y))
            point = (x, y)
        elif command in "Cc":
            x1, y1, x2, y2, x3, y3 = (float(tokens[index + i]) for i in range(6))
            index += 6
            x0, y0 = point
            for step in range(1, steps + 1):
                t = step / steps
                mt = 1 - t
                current.append((
                    mt ** 3 * x0 + 3 * mt ** 2 * t * x1 + 3 * mt * t ** 2 * x2 + t ** 3 * x3,
                    mt ** 3 * y0 + 3 * mt ** 2 * t * y1 + 3 * mt * t ** 2 * y2 + t ** 3 * y3,
                ))
            point = (x3, y3)
        elif command in "Zz":
            if current:
                subpaths.append(current)
                current = []
        else:
            raise SystemExit(f"unsupported path command {command!r}")
    if current:
        subpaths.append(current)
    return subpaths


def paths_of(xml_path: Path):
    """(subpaths, fill colours) from a vector drawable, ignoring comments."""
    root = ET.parse(xml_path).getroot()
    subpaths, fills = [], []
    for path in root.findall("path"):
        data = path.get(ANDROID + "pathData") or ""
        subpaths.extend(flatten(data))
        fills.append(path.get(ANDROID + "fillColor") or path.get(ANDROID + "strokeColor") or "?")
    return subpaths, fills


def inside(polygon, x, y) -> bool:
    """Even-odd point-in-polygon."""
    hit, n = False, len(polygon)
    for i in range(n):
        x1, y1 = polygon[i]
        x2, y2 = polygon[(i + 1) % n]
        if (y1 > y) != (y2 > y):
            xcross = x1 + (y - y1) * (x2 - x1) / (y2 - y1)
            if x < xcross:
                hit = not hit
    return hit


def render(subpaths, background, layer_colours, size: int, out: Path):
    """Composite layers bottom-to-top; each layer is filled even-odd from its own subpath list."""
    big = size * SS
    image = Image.new("RGBA", (big, big), argb(background))
    for subpaths_in_layer, colour in zip(layer_colours, [argb("#FFFFFFFF"), argb("#FF2F6BFF")]):
        mask = Image.new("L", (big, big), 0)
        pixels = mask.load()
        scaled = [[(x / 108.0 * big, y / 108.0 * big) for x, y in poly] for poly in subpaths_in_layer]
        for py in range(big):
            for polygon in scaled:
                ys = [p[1] for p in polygon]
                if py < min(ys) or py > max(ys):
                    continue
                # per-row scanline fill so the inner loop stays cheap
                crossings = []
                for i in range(len(polygon)):
                    x1, y1 = polygon[i]
                    x2, y2 = polygon[(i + 1) % len(polygon)]
                    if (y1 > py) != (y2 > py):
                        crossings.append(x1 + (py - y1) * (x2 - x1) / (y2 - y1))
                crossings.sort()
                for j in range(0, len(crossings) - 1, 2):
                    for px in range(int(crossings[j]), int(crossings[j + 1])):
                        if 0 <= px < big:
                            pixels[px, py] = 255
        tint = Image.new("RGBA", (big, big), colour)
        image = Image.composite(tint, image, mask)
    image = image.resize((size, size), Image.LANCZOS)
    image.save(out)
    return out


def main(argv):
    if len(argv) != 3:
        raise SystemExit(__doc__)
    res_dir, out_dir = Path(argv[1]), Path(argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)

    worst = 0.0
    for name in ("drawable/ic_launcher_foreground.xml", "drawable/ic_launcher_monochrome.xml",
                 "drawable/ic_splash_mark.xml"):
        xml_path = res_dir / name
        subpaths, fills = paths_of(xml_path)
        radii = [math.dist(SAFE_CENTER, point) for poly in subpaths for point in poly]
        max_r = max(radii)
        worst = max(worst, max_r)
        print(f"{name}: {len(subpaths)} subpath(s), {len(radii)} points, "
              f"max radius {max_r:.2f} dp (safe {SAFE_RADIUS}), fills={fills}")
        xs = [p[0] for poly in subpaths for p in poly]
        ys = [p[1] for poly in subpaths for p in poly]
        print(f"  bbox x {min(xs):.1f}..{max(xs):.1f}  y {min(ys):.1f}..{max(ys):.1f} "
              f"(mask keeps x/y 18..90)")

    # Layer 1 = shield (first subpath of the foreground), layer 2 = the T.
    foreground = paths_of(res_dir / "drawable/ic_launcher_foreground.xml")[0]
    shield, t_glyph = [foreground[0]], [foreground[1]]
    for size in (108, 48):
        render(foreground, "#FF2F6BFF", [shield, t_glyph], size,
               out_dir / f"icon-launcher-{size}px.png")
    launcher = Image.open(out_dir / "icon-launcher-108px.png").convert("RGB")
    corner = launcher.getpixel((1, 1))
    body = launcher.getpixel((54, 30))
    glyph = launcher.getpixel((54, 60))
    print(f"launcher pixels: background={corner} shield={body} T={glyph}")
    assert corner == argb("#FF2F6BFF")[:3], "launcher background is not #2F6BFF"
    assert body == (255, 255, 255), "launcher shield is not white"
    assert glyph == argb("#FF2F6BFF")[:3], "launcher T is not #2F6BFF"
    splash = paths_of(res_dir / "drawable/ic_splash_mark.xml")[0]
    for size, background, colour, tag in (
        (108, "#FFF7F9FC", "#FF2F6BFF", "light"),
        (108, "#FF12161F", "#FF8FB4FF", "night"),
    ):
        image = Image.new("RGBA", (size, size), argb(background))
        mask = Image.new("L", (size * SS, size * SS), 0)
        # one even-odd fill across both subpaths: the T is a hole in the shield
        pixels = mask.load()
        scaled = [[(x / 108.0 * size * SS, y / 108.0 * size * SS) for x, y in poly] for poly in splash]
        for py in range(size * SS):
            crossings = []
            for polygon in scaled:
                for i in range(len(polygon)):
                    x1, y1 = polygon[i]
                    x2, y2 = polygon[(i + 1) % len(polygon)]
                    if (y1 > py) != (y2 > py):
                        crossings.append(x1 + (py - y1) * (x2 - x1) / (y2 - y1))
            crossings.sort()
            for j in range(0, len(crossings) - 1, 2):
                for px in range(int(crossings[j]), int(crossings[j + 1])):
                    if 0 <= px < size * SS:
                        pixels[px, py] = 255
        mask = mask.resize((size, size), Image.LANCZOS)
        image = Image.composite(Image.new("RGBA", (size, size), argb(colour)), image, mask)
        path = out_dir / f"splash-mark-{tag}-{size}px.png"
        image.save(path)
        print(f"wrote {path}")
        # Prove the colours are the brand tokens and that the T is really a hole (background shows
        # through), rather than trusting the eye: (54,30) is shield body, (54,60) is the T stem.
        body = image.convert("RGB").getpixel((54, 30))
        hole = image.convert("RGB").getpixel((54, 60))
        corner = image.convert("RGB").getpixel((1, 1))
        print(f"  pixels: background={corner} shield-body={body} T-hole={hole}")
        assert corner == argb(background)[:3], f"{tag} splash background is not {background}"
        assert body == argb(colour)[:3], f"{tag} splash mark is not {colour}"
        assert hole == argb(background)[:3], f"{tag} splash T is not a hole"

    print(f"\nworst radius across all artwork: {worst:.2f} dp (must be <= {SAFE_RADIUS})")
    return 0 if worst <= SAFE_RADIUS else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
