"""Create deterministic synthetic TrustShield OCR/QR demo images without network access."""
from __future__ import annotations

from pathlib import Path
import segno
from PIL import Image, ImageDraw, ImageFont, PngImagePlugin


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "demo" / "fixtures"
DEMO_HEADER = "TrustShield demo chat"


def visible_transcription(text: str) -> str:
    """Return the reading-order text visibly drawn by ``screenshot``."""
    return "\n".join([DEMO_HEADER, *text.splitlines()])


def font(size: int):
    candidates = [
        Path(r"C:\Windows\Fonts\arial.ttf"),
        Path(r"C:\Windows\Fonts\segoeui.ttf"),
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default()


def screenshot(name: str, text: str) -> None:
    image = Image.new("RGB", (1500, 900), "#f4f5f7")
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((80, 55, 1420, 845), radius=28, fill="white", outline="#d8dce3", width=3)
    draw.text((125, 100), DEMO_HEADER, fill="#253047", font=font(32))
    y = 180
    for line in text.splitlines():
        draw.rounded_rectangle((125, y - 12, 1375, y + 52), radius=16, fill="#eaf1ff")
        draw.text((150, y), line, fill="#101828", font=font(28))
        y += 92
    info = PngImagePlugin.PngInfo()
    info.add_text("trustshield_demo_text", text)
    image.save(OUT / name, format="PNG", pnginfo=info)


def qr(name: str, payload: str) -> None:
    segno.make(payload, micro=False).save(OUT / name, kind="png", scale=10, border=4)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    screenshot("kyc_screenshot.png", "SBI KYC ALERT\nYour account will be blocked today.\nShare OTP now at http://sbi-kyc-update.xyz/login")
    screenshot("job_screenshot.png", "JOB OFFER\nWork from home and earn Rs 5000 per day.\nPay registration fee Rs 500 to start.")
    screenshot("legit_screenshot.png", "BANK DEBIT ALERT\nRs 500.00 debited from A/c XX1234.\nNever share your OTP or PIN with anyone.")
    qr("qr_suspicious_url.png", "http://sbi-kyc-update.xyz/login")
    qr("qr_benign.png", "https://en.wikipedia.org/wiki/QR_code")
    qr("qr_text.png", "This is synthetic plain text for TrustShield QR testing.")
    qr("qr_upi.png", "upi://pay?pa=demo-store@upi&pn=Demo%20Store&am=125.50&cu=INR&tn=Demo%20order&mc=5812&mode=02")


if __name__ == "__main__":
    main()
