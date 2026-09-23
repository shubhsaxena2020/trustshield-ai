"""OCR provider seam. Tesseract is the default; a metadata-only mock is test/demo-only."""
from __future__ import annotations

import os
from abc import ABC, abstractmethod
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from PIL import Image, ImageOps

try:
    import pytesseract
    from pytesseract import Output
    from pytesseract.pytesseract import TesseractNotFoundError
except ImportError:  # pragma: no cover - dependency is declared in requirements.txt
    pytesseract = None
    Output = None

    class TesseractNotFoundError(Exception):
        pass


@dataclass
class OCRResult:
    status: str
    text: str
    provider: str
    provider_version: str | None
    confidence: float | None
    warnings: list[str]
    detail: str


def preprocess_for_ocr(image: Image.Image) -> Image.Image:
    gray = ImageOps.autocontrast(image.convert("L"))
    scale = 2 if max(gray.size) < 1600 else 1
    return gray.resize((gray.width * scale, gray.height * scale)) if scale > 1 else gray


class OCRProvider(ABC):
    name: str

    @abstractmethod
    def is_available(self) -> bool:
        raise NotImplementedError

    @abstractmethod
    def extract(self, image: Image.Image) -> OCRResult:
        raise NotImplementedError


def _configure_tesseract() -> None:
    if pytesseract is None:
        return
    configured = os.getenv("TESSERACT_CMD")
    default = Path(r"C:\Program Files\Tesseract-OCR\tesseract.exe")
    if configured:
        pytesseract.pytesseract.tesseract_cmd = configured
    elif default.exists():
        pytesseract.pytesseract.tesseract_cmd = str(default)


class TesseractProvider(OCRProvider):
    name = "tesseract"

    def is_available(self) -> bool:
        if pytesseract is None:
            return False
        _configure_tesseract()
        try:
            pytesseract.get_tesseract_version()
            return True
        except (TesseractNotFoundError, FileNotFoundError, OSError):
            return False

    def extract(self, image: Image.Image) -> OCRResult:
        if not self.is_available():
            return OCRResult(
                "unavailable", "", self.name, None, None, [],
                "Tesseract is unavailable. Install the Tesseract OCR binary and set TESSERACT_CMD if it is not on PATH.",
            )
        _configure_tesseract()
        try:
            version = str(pytesseract.get_tesseract_version())
            data: dict[str, Any] = pytesseract.image_to_data(preprocess_for_ocr(image), output_type=Output.DICT)
            words: list[str] = []
            confidences: list[float] = []
            for word, raw_conf in zip(data.get("text", []), data.get("conf", [])):
                word = (word or "").strip()
                try:
                    conf = float(raw_conf)
                except (TypeError, ValueError):
                    conf = -1
                if word and conf >= 0:
                    words.append(word)
                    confidences.append(conf)
            text = " ".join(words).strip()
            confidence = sum(confidences) / len(confidences) if confidences else None
            warnings: list[str] = []
            if not text:
                warnings.append("No readable text was found by OCR.")
            if confidence is not None and confidence < 60:
                warnings.append("OCR mean word confidence is low.")
            if confidence is None:
                warnings.append("OCR engine did not report usable word confidence.")
            return OCRResult("completed", text, self.name, version, confidence, warnings, "Tesseract OCR completed.")
        except Exception as exc:  # engine/runtime failures must not turn image analysis into a 500
            return OCRResult("failed", "", self.name, None, None, [], f"Tesseract OCR failed: {exc}")


class MockOCRProvider(OCRProvider):
    name = "mock"

    def is_available(self) -> bool:
        return True

    def extract(self, image: Image.Image) -> OCRResult:
        text = str(image.info.get("trustshield_demo_text", ""))
        return OCRResult(
            "completed", text, self.name, "demo-metadata", None,
            ["MOCK OCR provider: text is read from demo-file metadata, not recognised from pixels"],
            "Mock OCR read the trustshield_demo_text PNG metadata chunk.",
        )


_PROVIDERS = {"tesseract": TesseractProvider, "mock": MockOCRProvider}


def get_ocr_provider() -> OCRProvider:
    """Return the configured provider. PaddleOCR is intentionally a future registry entry, not implemented here."""
    selected = os.getenv("TRUSTSHIELD_OCR_PROVIDER", "tesseract").strip().lower()
    return _PROVIDERS.get(selected, TesseractProvider)()
