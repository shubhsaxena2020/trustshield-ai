"""Static accessibility audit for the TrustShield Android UI (P3-18 evidence helper).

Counts the accessibility affordances each screen actually declares in source, so
docs/android/evidence/accessibility.md can carry measured numbers instead of claims.
Run from apps/android/app/src/main/java/ai/trustshield/android:

    python accessibility_audit.py
"""
import os
import re
import sys

SCREENS = {
    "Home / Scan": "ui/home/ScanScreen.kt",
    "Result": "ui/result/ResultScreen.kt",
    "Call Shield": "ui/call/CallShieldScreen.kt",
    "QR Guard": "ui/qr/QrGuardScreen.kt",
    "QR Guard host": "ui/qr/QrGuardHost.kt",
    "Settings": "ui/settings/SettingsScreen.kt",
    "Navigation": "ui/navigation/Placeholders.kt",
}

PATTERNS = {
    "contentDescription": re.compile(r"contentDescription\s*="),
    "heading()": re.compile(r"\bheading\(\)"),
    "role=Button": re.compile(r"Role\.Button|role\s*=\s*Role"),
    "stateDescription": re.compile(r"stateDescription"),
    "liveRegion": re.compile(r"liveRegion"),
    "MinTouchTarget": re.compile(r"MinTouchTarget"),
    "semantics{}": re.compile(r"\.semantics\s*\{"),
    "Text(": re.compile(r"(?<![A-Za-z.])Text\("),
    "Button/Outlined/TextButton": re.compile(
        r"(?<![A-Za-z])(Button|OutlinedButton|TextButton|Switch|FilterChip|FilledTonalButton)\("
    ),
    "fixed height(<48dp)": re.compile(r"\.height\((\d+(?:\.\d+)?)\.dp\)"),
    "maxLines=1": re.compile(r"maxLines\s*=\s*1"),
    "verticalScroll": re.compile(r"verticalScroll\("),
}

DEV_WORDS = re.compile(
    r"(abhi ban rahi|placeholder|stub\b|coming soon|not wired|juda nahi|TODO|FIXME|coming soon)",
    re.IGNORECASE,
)

# User-visible string literals only: ToneCopy("...") and bare Text("...") style literals.
VISIBLE = re.compile(r'(?:ToneCopy|SettingText|NavCopy|Text)\(\s*"([^"]{6,})"')


def audit(path):
    with open(path, encoding="utf-8") as fh:
        src = fh.read()
    counts = {k: len(p.findall(src)) for k, p in PATTERNS.items()}
    # Comment lines are not shipped UI, but they are review evidence: report them separately.
    dev_hits = []
    for i, line in enumerate(src.splitlines(), 1):
        if DEV_WORDS.search(line):
            stripped = line.strip()
            kind = "comment" if stripped.startswith(("//", "*", "/*")) else "CODE"
            dev_hits.append((i, kind, stripped[:110]))
    visible_dev = [
        (i, line.strip()[:110])
        for i, line in enumerate(src.splitlines(), 1)
        if any(w in line.lower() for w in ("abhi ban rahi", "coming soon", "not wired", "juda nahi"))
        and not line.strip().startswith(("//", "*", "/*"))
    ]
    return counts, dev_hits, visible_dev, len(src.splitlines())


def main():
    root = os.getcwd()
    total = {k: 0 for k in PATTERNS}
    print(f"{'screen':<16}{'lines':>6}{'desc':>6}{'head':>6}{'sem':>5}{'48dp':>6}{'btns':>6}{'fixedH':>7}{'maxL1':>7}")
    missing = []
    for name, rel in SCREENS.items():
        path = os.path.join(root, rel.replace("/", os.sep))
        if not os.path.exists(path):
            missing.append(rel)
            continue
        counts, dev_hits, visible_dev, lines = audit(path)
        for k, v in counts.items():
            total[k] += v
        print(
            f"{name:<16}{lines:>6}{counts['contentDescription']:>6}{counts['heading()']:>6}"
            f"{counts['semantics{}']:>5}{counts['MinTouchTarget']:>6}"
            f"{counts['Button/Outlined/TextButton']:>6}{counts['fixed height(<48dp)']:>7}"
            f"{counts['maxLines=1']:>7}"
        )
        if visible_dev:
            print(f"  !! dev wording in shipped strings: {visible_dev}")
        fixed = [
            (n, m)
            for n, line in enumerate(open(path, encoding='utf-8').read().splitlines(), 1)
            for m in PATTERNS["fixed height(<48dp)"].findall(line)
            if float(m) < 48
        ]
        if fixed:
            print(f"  note: fixed .height() < 48dp at lines {fixed}")
    print()
    print("totals:", {k: v for k, v in total.items() if v})
    if missing:
        print("MISSING (file absent on this tree):", missing)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
