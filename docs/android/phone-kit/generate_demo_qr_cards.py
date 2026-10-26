#!/usr/bin/env python3
"""Generate printable, synthetic-only UPI QR test cards (never initiate payment)."""

from __future__ import annotations

import argparse
from pathlib import Path
from urllib.parse import urlencode

try:
    import qrcode
    from PIL import Image, ImageDraw, ImageFont
except ImportError as exc:  # pragma: no cover - setup hint only
    raise SystemExit("Install local generator dependencies with: python -m pip install qrcode[pil]") from exc


CARDS = (
    ("EXPECTED · SYNTHETIC", "demo-recipient@upi"),
    ("MISMATCH 1 · SYNTHETIC", "demo-other@upi"),
    ("MISMATCH 2 · SYNTHETIC", "demo-recipient@ubank"),
    ("MISMATCH 3 · SYNTHETIC", "demo-recipient-two@upi"),
)


def make_payload(vpa: str) -> str:
    return "upi://pay?" + urlencode(
        {
            "pa": vpa,
            "pn": "TRUSTSHIELD DEMO ONLY",
            "am": "0.00",
            "cu": "INR",
            "tn": "SYNTHETIC TEST - DO NOT PAY",
        }
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).resolve().parent / "demo_upi_qr_cards.pdf",
        help="output PDF path (default: next to this script)",
    )
    args = parser.parse_args()

    width, height = 1240, 1754  # A4 portrait at print-friendly resolution.
    page = Image.new("RGB", (width, height), "white")
    draw = ImageDraw.Draw(page)
    try:
        title_font = ImageFont.truetype("arial.ttf", 42)
        label_font = ImageFont.truetype("arial.ttf", 27)
        note_font = ImageFont.truetype("arial.ttf", 21)
    except OSError:
        title_font = label_font = note_font = ImageFont.load_default()

    draw.text((70, 48), "TrustShield · Synthetic QR Test Cards", fill="#16305c", font=title_font)
    draw.text((70, 105), "No real payee. Amount is zero. Never approve a payment.", fill="#a32929", font=note_font)

    card_w, card_h, gap_x, gap_y = 520, 710, 70, 90
    for index, (label, vpa) in enumerate(CARDS):
        row, col = divmod(index, 2)
        x = 60 + col * (card_w + gap_x)
        y = 195 + row * (card_h + gap_y)
        qr = qrcode.QRCode(version=None, error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=9, border=4)
        qr.add_data(make_payload(vpa))
        qr.make(fit=True)
        qr_image = qr.make_image(fill_color="black", back_color="white").convert("RGB")
        qr_image.thumbnail((440, 440), Image.Resampling.NEAREST)
        draw.rounded_rectangle((x, y, x + card_w, y + card_h), radius=18, outline="#2F6BFF", width=5)
        draw.text((x + 22, y + 22), label, fill="#16305c", font=label_font)
        draw.text((x + 22, y + 67), vpa, fill="#333333", font=note_font)
        qr_x, qr_y = x + (card_w - qr_image.width) // 2, y + 115
        page.paste(qr_image, (qr_x, qr_y))
        draw.text((x + 22, y + 600), "SYNTHETIC • TEST ONLY • DO NOT PAY", fill="#a32929", font=note_font)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    page.save(args.output, "PDF", resolution=150.0)
    print(f"Created {args.output} (one A4 page, four synthetic zero-amount QR cards).")


if __name__ == "__main__":
    main()
