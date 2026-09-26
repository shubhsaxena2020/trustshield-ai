"""OCR provider seam. Tesseract is the default; a metadata-only mock is test/demo-only."""
from __future__ import annotations

import os
import shutil
from abc import ABC, abstractmethod
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from PIL import Image, ImageOps

try:
    import pytesseract
    from pytesseract import Output
    from pytesseract.pytesseract import TesseractError, TesseractNotFoundError
except ImportError:  # pragma: no cover - dependency is declared in requirements.txt
    pytesseract = None
    Output = None

    class TesseractNotFoundError(Exception):
        pass

    class TesseractError(Exception):
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
    normalized_text: str = ""
    preprocessing: str = "current"
    tesseract_cmd_source: str = "path"

    @property
    def raw_text(self) -> str:
        """Canonical name for the backwards-compatible ``text`` field."""
        return self.text


DEFAULT_TESSERACT_PATH = Path(r"C:\Program Files\Tesseract-OCR\tesseract.exe")
OCR_TEXT_LIMIT = 20_000
OCR_PREPROCESS_MODES = {"none", "current", "gray", "gray_autocontrast", "gray_resize2x"}


def selected_preprocess_mode() -> str:
    selected = os.getenv("TRUSTSHIELD_OCR_PREPROCESS", "current").strip().lower()
    return selected if selected in OCR_PREPROCESS_MODES else "current"


def preprocess_for_ocr(image: Image.Image, mode: str | None = None) -> Image.Image:
    mode = mode or selected_preprocess_mode()
    if mode == "none":
        return image.copy()
    gray = image.convert("L")
    if mode == "gray":
        return gray
    if mode == "gray_autocontrast":
        return ImageOps.autocontrast(gray)
    if mode == "gray_resize2x":
        return gray.resize((gray.width * 2, gray.height * 2))
    # ``current`` is the Phase 2 default: autocontrast and conservative upscaling.
    gray = ImageOps.autocontrast(gray)
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


def resolve_tesseract_cmd() -> tuple[str | None, str]:
    """Resolve Tesseract in the required env -> PATH -> default order."""
    configured = os.getenv("TESSERACT_CMD", "").strip()
    if configured and Path(configured).is_file():
        return configured, "env:TESSERACT_CMD"
    on_path = shutil.which("tesseract")
    if on_path:
        return on_path, "path"
    if DEFAULT_TESSERACT_PATH.is_file():
        return str(DEFAULT_TESSERACT_PATH), "default-location"
    return None, "path"


def _configure_tesseract() -> tuple[str | None, str]:
    if pytesseract is None:
        return None, "path"
    resolved, source = resolve_tesseract_cmd()
    pytesseract.pytesseract.tesseract_cmd = resolved or "tesseract"
    return resolved, source


def ocr_timeout_seconds() -> float:
    try:
        return max(0.1, float(os.getenv("TRUSTSHIELD_OCR_TIMEOUT", "30")))
    except ValueError:
        return 30.0


class TesseractProvider(OCRProvider):
    name = "tesseract"

    def is_available(self) -> bool:
        if pytesseract is None:
            return False
        _configure_tesseract()
        try:
            pytesseract.get_tesseract_version()
            return True
        except (TesseractNotFoundError, TesseractError, FileNotFoundError, OSError, RuntimeError):
            return False

    def preprocess(self, image: Image.Image) -> Image.Image:
        return preprocess_for_ocr(image, selected_preprocess_mode())

    def extract(self, image: Image.Image) -> OCRResult:
        _, source = _configure_tesseract()
        mode = selected_preprocess_mode()
        if not self.is_available():
            return OCRResult(
                "unavailable", "", self.name, None, None, [],
                "Tesseract is unavailable. Install the Tesseract OCR binary and set TESSERACT_CMD if it is not on PATH.",
                preprocessing=mode, tesseract_cmd_source=source,
            )
        try:
            version = str(pytesseract.get_tesseract_version())
            prepared = self.preprocess(image)
            timeout = ocr_timeout_seconds()
            image_to_string = getattr(pytesseract, "image_to_string", None)
            if image_to_string is None:
                raw_text = ""
            else:
                raw_text = image_to_string(prepared, lang="eng", timeout=timeout)
            warnings: list[str] = []
            try:
                try:
                    data: dict[str, Any] = pytesseract.image_to_data(
                        prepared, lang="eng", output_type=Output.DICT, timeout=timeout
                    )
                except TypeError as exc:
                    # Keep small test doubles compatible with the historical seam;
                    # real pytesseract receives the timeout above.
                    if "unexpected keyword" not in str(exc).lower():
                        raise
                    data = pytesseract.image_to_data(prepared, output_type=Output.DICT)
            except Exception:
                data = {}
                warnings.append("OCR confidence extraction failed; extracted text was retained without confidence.")
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
            text = str(raw_text or "")[:OCR_TEXT_LIMIT] if image_to_string is not None else " ".join(words).strip()
            confidence = sum(confidences) / len(confidences) if confidences else None
            if not text:
                warnings.append("No readable text was found by OCR.")
            if confidence is not None and confidence < 60:
                warnings.append("OCR mean word confidence is low.")
            if confidence is None:
                warnings.append("OCR engine did not report usable word confidence.")
            return OCRResult(
                "completed", text, self.name, version, confidence, warnings, "Tesseract OCR completed.",
                preprocessing=mode, tesseract_cmd_source=source,
            )
        except TesseractError:
            return OCRResult("failed", "", self.name, None, None, [], "Tesseract OCR engine failure.",
                             preprocessing=mode, tesseract_cmd_source=source)
        except RuntimeError as exc:
            detail = "Tesseract OCR timed out." if "timeout" in str(exc).lower() else "Tesseract OCR runtime failure."
            return OCRResult("failed", "", self.name, None, None, [], detail,
                             preprocessing=mode, tesseract_cmd_source=source)
        except Exception:  # engine/runtime failures must not turn image analysis into a 500
            return OCRResult("failed", "", self.name, None, None, [], "Tesseract OCR failed.",
                             preprocessing=mode, tesseract_cmd_source=source)


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
            normalized_text=text, preprocessing="mock", tesseract_cmd_source="path",
        )


_PROVIDERS = {"tesseract": TesseractProvider, "mock": MockOCRProvider}


def get_ocr_provider() -> OCRProvider:
    """Return the configured provider. PaddleOCR is intentionally a future registry entry, not implemented here."""
    selected = os.getenv("TRUSTSHIELD_OCR_PROVIDER", "tesseract").strip().lower()
    return _PROVIDERS.get(selected, TesseractProvider)()
