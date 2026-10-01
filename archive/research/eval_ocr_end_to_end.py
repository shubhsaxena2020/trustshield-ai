#!/usr/bin/env python3
"""Validation-only OCR end-to-end benchmark using real Tesseract and synthetic renders."""
from __future__ import annotations

import argparse
import csv
import io
import json
import os
import random
import sys
import textwrap
from collections import defaultdict
from pathlib import Path

PROJECT_ROOT = str(Path(__file__).resolve().parents[1])
if PROJECT_ROOT not in sys.path:
    sys.path.insert(0, PROJECT_ROOT)

from PIL import Image, ImageDraw, ImageFont

from services.analysis.extractors.ocr_metrics import aggregate_scores, score_text
from services.analysis.pipeline import analyze_image, analyze_text

SEED = 42
TESSERACT = r"C:\Users\shubh\Tools\Tesseract-OCR\tesseract.exe"


def _font_paths() -> list[Path]:
    root = Path(r"C:\Windows\Fonts")
    preferred = ["arial.ttf", "calibri.ttf", "segoeui.ttf", "tahoma.ttf", "verdana.ttf", "consola.ttf"]
    paths = [root / name for name in preferred if (root / name).exists()]
    return paths or sorted(root.glob("*.ttf"))[:6]


def _font(path: Path, size: int):
    try:
        return ImageFont.truetype(str(path), size)
    except OSError:
        return ImageFont.load_default()


def _wrapped(text: str, width: int) -> str:
    return "\n".join(textwrap.wrap(text, width=width, break_long_words=False, break_on_hyphens=False) or [text])


def render_message(text: str, index: int, out_path: Path, rng: random.Random) -> None:
    style = ["whatsapp", "sms", "email", "notification"][index % 4]
    dark = index % 5 == 0
    size = [28, 32, 36, 40][index % 4]
    font_path = _font_paths()[index % len(_font_paths())]
    font = _font(font_path, size)
    title_font = _font(font_path, size + 4)
    bg = "#101820" if dark else ("#e8edf2" if style == "whatsapp" else "#f5f7fa")
    fg = "#f4f7fb" if dark else "#18212b"
    image = Image.new("RGB", (1400, 920), bg)
    draw = ImageDraw.Draw(image)
    wrapped = _wrapped(text, 52 if style == "email" else 46)
    if style == "whatsapp":
        draw.rectangle((0, 0, 1400, 92), fill="#075e54" if not dark else "#1f403d")
        draw.text((48, 26), "TrustShield contact", font=title_font, fill="#ffffff")
        box = draw.multiline_textbbox((0, 0), wrapped, font=font, spacing=10)
        width, height = box[2] - box[0] + 54, box[3] - box[1] + 54
        x, y = 1400 - width - 70, 180
        draw.rounded_rectangle((x, y, x + width, y + height), radius=22, fill="#128c7e" if not dark else "#245e58")
        draw.multiline_text((x + 27, y + 24), wrapped, font=font, fill="#ffffff", spacing=10)
    elif style == "sms":
        draw.rounded_rectangle((45, 42, 1355, 878), radius=28, fill="#ffffff" if not dark else "#202a35", outline="#9aa9b7", width=3)
        draw.text((85, 76), "Messages  •  09:41", font=title_font, fill=fg)
        draw.line((85, 142, 1315, 142), fill="#9aa9b7", width=2)
        draw.multiline_text((88, 190), wrapped, font=font, fill=fg, spacing=12)
    elif style == "email":
        draw.rectangle((0, 0, 1400, 120), fill="#1f4e79" if not dark else "#263c52")
        draw.text((48, 36), "Inbox  •  TrustShield demo", font=title_font, fill="#ffffff")
        draw.text((70, 172), "From: service@example.invalid", font=font, fill=fg)
        draw.text((70, 225), "Subject: Account message", font=font, fill=fg)
        draw.line((70, 285, 1330, 285), fill="#9aa9b7", width=2)
        draw.multiline_text((70, 340), wrapped, font=font, fill=fg, spacing=12)
    else:
        draw.rounded_rectangle((80, 130, 1320, 790), radius=34, fill="#ffffff" if not dark else "#202a35", outline="#9aa9b7", width=3)
        draw.ellipse((125, 184, 195, 254), fill="#5b8def")
        draw.text((225, 190), "Notification", font=title_font, fill=fg)
        draw.multiline_text((130, 315), wrapped, font=font, fill=fg, spacing=12)
    # Mild resampling and JPEG compression emulate screenshot transport/storage.
    if index % 3 == 0:
        image = image.resize((980, 644), Image.Resampling.LANCZOS)
    quality = [55, 70, 82, 92][index % 4]
    image.save(out_path, format="JPEG", quality=quality, optimize=True)


def _sample(rows: list[dict], size: int, seed: int) -> list[dict]:
    rng = random.Random(seed)
    by_label: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        by_label[row["binary_label"]].append(row)
    selected: list[dict] = []
    labels = sorted(by_label)
    for index, label in enumerate(labels):
        target = size // len(labels) + (1 if index < size % len(labels) else 0)
        group = list(by_label[label])
        rng.shuffle(group)
        selected.extend(group[:target])
    rng.shuffle(selected)
    return selected


def _read_rows(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _write_csv(path: Path, rows: list[dict]) -> None:
    keys = list(rows[0]) if rows else ["id"]
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=keys)
        writer.writeheader(); writer.writerows(rows)


def _decision(result, kind: str) -> bool:
    if kind == "ml":
        return bool(result.modules.get("ml") and result.modules["ml"].data.get("predicted_binary", False))
    return result.risk.score >= 25


def run(dataset_dir: Path, output_dir: Path, split: str = "validation", sample_size: int = 120, seed: int = SEED) -> dict:
    if split != "validation":
        raise ValueError("this stage only permits --split validation")
    os.environ["TESSERACT_CMD"] = TESSERACT
    os.environ.pop("TRUSTSHIELD_OCR_PROVIDER", None)
    rows = _sample(_read_rows(dataset_dir / f"{split}.jsonl"), sample_size, seed)
    render_dir = output_dir / "renders"
    render_dir.mkdir(parents=True, exist_ok=True)
    metric_rows = []
    scores = []
    labels = []
    decisions = {"clean_ml": [], "ocr_ml": [], "clean_combined": [], "ocr_combined": []}
    for index, row in enumerate(rows):
        image_path = render_dir / f"{index:03d}_{row['id']}.jpg"
        render_message(row["text"], index, image_path, random.Random(seed + index))
        clean = analyze_text(row["text"])
        ocr = analyze_image(image_path.read_bytes(), declared_mime="image/jpeg", filename=image_path.name)
        ocr_text = str(ocr.modules.get("ocr").data.get("normalized_text", "")) if ocr.modules.get("ocr") else ""
        score = score_text(row["text"], ocr_text)
        scores.append(score)
        labels.append(int(row["binary_label"] == "malicious_or_fraudulent"))
        decisions["clean_ml"].append(_decision(clean, "ml")); decisions["ocr_ml"].append(_decision(ocr, "ml"))
        decisions["clean_combined"].append(_decision(clean, "combined")); decisions["ocr_combined"].append(_decision(ocr, "combined"))
        metric_rows.append({"id": row["id"], "binary_label": row["binary_label"], "source_name": row["source_name"],
                            "style": ["whatsapp", "sms", "email", "notification"][index % 4],
                            "image": str(image_path), "ocr_status": ocr.modules.get("ocr").status if ocr.modules.get("ocr") else "missing",
                            "ocr_text": ocr_text, "wer": score["wer"], "cer": score["cer"],
                            "clean_ml": decisions["clean_ml"][-1], "ocr_ml": decisions["ocr_ml"][-1],
                            "clean_combined": decisions["clean_combined"][-1], "ocr_combined": decisions["ocr_combined"][-1]})
    def binary(kind: str, threshold: float = 0.5):
        from services.analysis.ml.metrics import binary_metrics
        return binary_metrics(labels, [int(v) for v in decisions[kind]], threshold)
    report = {
        "split": split, "sample_size": len(rows), "seed": seed, "render_type": "synthetic Pillow WhatsApp/SMS/email/notification renders, not real screenshots",
        "tesseract_cmd": TESSERACT, "ocr_provider_env_unset": "TRUSTSHIELD_OCR_PROVIDER" not in os.environ,
        "text_metrics": aggregate_scores(scores),
        "binary_metrics": {"ml_clean": binary("clean_ml"), "ml_ocr": binary("ocr_ml"),
                           "combined_clean": binary("clean_combined", 0.5), "combined_ocr": binary("ocr_combined", 0.5)},
        "decision_flips": {"ml": sum(a != b for a, b in zip(decisions["clean_ml"], decisions["ocr_ml"])),
                           "combined": sum(a != b for a, b in zip(decisions["clean_combined"], decisions["ocr_combined"]))},
        "ocr_completed": sum(row["ocr_status"] == "completed" for row in metric_rows),
    }
    output_dir.mkdir(parents=True, exist_ok=True)
    _write_csv(output_dir / "per_message.csv", metric_rows)
    (output_dir / "evaluation.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-dir", type=Path, default=Path("datasets/processed/trustshield-text-v1"))
    parser.add_argument("--output-dir", type=Path, default=Path("models/evaluation/validation/ocr_end_to_end"))
    parser.add_argument("--split", choices=("validation",), default="validation")
    parser.add_argument("--sample-size", type=int, default=120)
    parser.add_argument("--seed", type=int, default=SEED)
    args = parser.parse_args()
    print(json.dumps(run(args.dataset_dir, args.output_dir, args.split, args.sample_size, args.seed), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
