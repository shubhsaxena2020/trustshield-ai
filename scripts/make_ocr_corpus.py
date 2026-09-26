"""Generate the deterministic, synthetic OCR validation corpus."""
from __future__ import annotations

import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "benchmarks" / "ocr" / "corpus"
CHAT_HEADER = "Synthetic TrustShield chat"


def choose_font(size: int) -> tuple[ImageFont.ImageFont, str]:
    candidates = [
        Path(r"C:\Windows\Fonts\arial.ttf"),
        Path(r"C:\Windows\Fonts\calibri.ttf"),
        Path(r"C:\Windows\Fonts\segoeui.ttf"),
        Path(r"C:\Windows\Fonts\consola.ttf"),
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size), str(candidate)
    return ImageFont.load_default(), "PIL default bitmap font"


def render_chat(text: str, size: int = 28, *, text_color: str = "#172033", background: str = "#f3f5f8",
                bubble: str = "#e8f0ff") -> tuple[Image.Image, str]:
    font, font_file = choose_font(size)
    image = Image.new("RGB", (1600, 950), background)
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((70, 40, 1530, 910), radius=30, fill="#ffffff", outline="#c9d1df", width=3)
    header_font, _ = choose_font(max(16, size + 5))
    draw.text((115, 85), CHAT_HEADER, fill="#2b3a55", font=header_font)
    y = 165
    line_height = max(28, size + 22)
    for line in text.splitlines():
        box_height = line_height + 20
        draw.rounded_rectangle((115, y - 10, 1485, y + box_height), radius=15, fill=bubble)
        draw.text((140, y + 2), line, fill=text_color, font=font)
        y += box_height + 18
    return image, font_file


def visible_lines(text: str) -> list[str]:
    """Return every string drawn by ``render_chat`` in reading order."""
    return [CHAT_HEADER, *text.splitlines()]


def build_fixture_specs() -> list[dict]:
    """Define message strings once for rendering, references, and manifest generation."""
    specs = [
        {
            "id": "clean_screenshot",
            "message": "DEMO BANK ALERT\nYour account needs a security review.\nOpen https://notice.example/review",
            "description": "Clean screenshot with large chat text",
            "degradation": "none",
            "critical_tokens": [{"category": "org", "value": "DEMO BANK"}, {"category": "url", "value": "https://notice.example/review"}],
        },
        {
            "id": "jpeg_compressed",
            "message": "Demo Bank receipt\nReference 482913\nAmount Rs 1250.50",
            "description": "JPEG-compressed screenshot",
            "degradation": "JPEG quality 25",
            "critical_tokens": [{"category": "org", "value": "Demo Bank"}, {"category": "amount", "value": "Rs 1250.50"}],
            "extension": ".jpg",
            "jpeg_quality": 25,
        },
        {
            "id": "lower_resolution",
            "message": "LOW RES CONTROL\nThis message contains no payment request.",
            "description": "Screenshot downscaled to approximately half width and height",
            "degradation": "0.5x downscale",
            "critical_tokens": [],
            "resize": True,
        },
        {
            "id": "low_contrast",
            "message": "LOW CONTRAST NOTICE\nYour review is pending.\nVisit https://status.example/check",
            "description": "Low-contrast chat screenshot",
            "degradation": "light gray text and background",
            "critical_tokens": [{"category": "url", "value": "https://status.example/check"}],
            "text_color": "#6d6d6d",
            "background": "#dedede",
            "bubble": "#eeeeee",
        },
        {
            "id": "small_text",
            "message": "SMALL TEXT\nPlease verify your profile at https://small.example/id",
            "description": "Chat screenshot rendered with approximately 11px text",
            "degradation": "small text",
            "critical_tokens": [{"category": "url", "value": "https://small.example/id"}],
            "size": 11,
        },
        {
            "id": "benign_control",
            "message": "DEMO BANK DEBIT ALERT\nRs 500.00 debited from A/c XX1234.\nNever share your OTP or PIN with anyone.",
            "description": "Benign bank debit alert control",
            "degradation": "none",
            "critical_tokens": [
                {"category": "org", "value": "DEMO BANK"}, {"category": "amount", "value": "Rs 500.00"},
                {"category": "otp", "value": "OTP"}, {"category": "pin", "value": "PIN"},
            ],
        },
        {
            "id": "phishing_url",
            "message": "DEMO BANK SECURITY TEAM\nYour account will be locked today.\nVerify now: https://demobank-login.invalid/secure",
            "description": "Phishing-style message with a synthetic invalid URL",
            "degradation": "none",
            "critical_tokens": [{"category": "org", "value": "DEMO BANK"}, {"category": "url", "value": "https://demobank-login.invalid/secure"}],
        },
        {
            "id": "token_bundle",
            "message": "DEMOPAY SUPPORT\nOTP 481926 | PIN 7391 | CVV 284\nAmount Rs 1250.50\nCall 9876500000",
            "description": "Synthetic message containing OTP, PIN, CVV, amount, and phone",
            "degradation": "none",
            "critical_tokens": [
                {"category": "org", "value": "DEMOPAY"}, {"category": "otp", "value": "481926"},
                {"category": "pin", "value": "7391"}, {"category": "cvv", "value": "284"},
                {"category": "amount", "value": "Rs 1250.50"}, {"category": "phone", "value": "9876500000"},
            ],
        },
        {
            "id": "demo_kyc",
            "message": "SBI KYC ALERT\nYour account will be blocked today.\nShare OTP now at http://sbi-kyc-update.xyz/login",
            "description": "Fresh corpus rendering of the existing KYC demo screen",
            "degradation": "none",
            "critical_tokens": [{"category": "org", "value": "SBI"}, {"category": "url", "value": "http://sbi-kyc-update.xyz/login"}],
        },
        {
            "id": "demo_job",
            "message": "JOB OFFER\nWork from home and earn Rs 5000 per day.\nPay registration fee Rs 500 to start.",
            "description": "Fresh corpus rendering of the existing job demo screen",
            "degradation": "none",
            "critical_tokens": [{"category": "amount", "value": "Rs 5000"}],
        },
        {
            "id": "demo_legit",
            "message": "BANK DEBIT ALERT\nRs 500.00 debited from A/c XX1234.\nNever share your OTP or PIN with anyone.",
            "description": "Fresh corpus rendering of the existing legitimate control screen",
            "degradation": "none",
            "critical_tokens": [
                {"category": "amount", "value": "Rs 500.00"}, {"category": "otp", "value": "OTP"}, {"category": "pin", "value": "PIN"},
            ],
        },
        {
            "id": "hard_small_compressed_lowres",
            "message": "DEMOPAY CHAT\nOTP 481926 and PIN 7391\nPay Rs 1250.50 now: https://pay.invalid/go",
            "description": "Combined small-text, low-resolution, and JPEG-compressed case",
            "degradation": "11px + 0.5x + JPEG quality 25",
            "critical_tokens": [
                {"category": "org", "value": "DEMOPAY"}, {"category": "otp", "value": "481926"},
                {"category": "pin", "value": "7391"}, {"category": "amount", "value": "Rs 1250.50"},
                {"category": "url", "value": "https://pay.invalid/go"},
            ],
            "size": 11,
            "resize": True,
            "extension": ".jpg",
            "jpeg_quality": 25,
        },
    ]
    for spec in specs:
        spec["visible_lines"] = visible_lines(spec["message"])
    return specs


def write_fixture(fixture_id: str, text: str, description: str, degradation: str,
                  critical_tokens: list[dict], *, image: Image.Image, font_file: str,
                  extension: str = ".png", jpeg_quality: int | None = None) -> dict:
    path = OUT / f"{fixture_id}{extension}"
    if extension == ".jpg":
        image.save(path, format="JPEG", quality=jpeg_quality or 25, optimize=False, progressive=False)
    else:
        image.save(path, format="PNG", optimize=False)
    (OUT / f"{fixture_id}.txt").write_text(text, encoding="utf-8", newline="\n")
    return {
        "id": fixture_id,
        "file": path.name,
        "description": description,
        "degradation": degradation,
        "critical_tokens": critical_tokens,
        "font_file": font_file,
    }


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    fixtures: list[dict] = []
    for spec in build_fixture_specs():
        image, font_file = render_chat(
            spec["message"],
            size=spec.get("size", 28),
            text_color=spec.get("text_color", "#172033"),
            background=spec.get("background", "#f3f5f8"),
            bubble=spec.get("bubble", "#e8f0ff"),
        )
        if spec.get("resize"):
            image = image.resize((800, 475), Image.Resampling.LANCZOS)
        ground_truth = "\n".join(spec["visible_lines"])
        fixtures.append(write_fixture(
            spec["id"], ground_truth, spec["description"], spec["degradation"], spec["critical_tokens"],
            image=image, font_file=font_file, extension=spec.get("extension", ".png"),
            jpeg_quality=spec.get("jpeg_quality"),
        ))

    manifest = {
        "version": 1,
        "synthetic": True,
        "font_files_used": sorted({fixture["font_file"] for fixture in fixtures}),
        "fixtures": sorted(fixtures, key=lambda fixture: fixture["id"]),
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Wrote {len(fixtures)} synthetic OCR fixtures to {OUT}")
    print("Fonts: " + ", ".join(manifest["font_files_used"]))


if __name__ == "__main__":
    main()
