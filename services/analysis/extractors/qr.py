"""QR decoding with OpenCV. Payloads are only classified/analyzed locally; never fetched."""
from __future__ import annotations

import re
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
import cv2
import numpy as np
from PIL import Image


@dataclass
class QRResult:
    status: str
    payload: str | None
    payload_type: str | None
    decoder: str
    warnings: list[str] = field(default_factory=list)
    detail: str = ""


class QRDecoder(ABC):
    name: str

    @abstractmethod
    def decode(self, image: Image.Image) -> QRResult:
        raise NotImplementedError


def classify_payload(payload: str) -> str:
    raw = (payload or "").strip()
    if raw.lower().startswith("upi://"):
        return "upi"
    if re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*://", raw) or re.match(r"^(?:javascript|data):", raw, re.I):
        return "url"
    if re.match(r"^(?:tel|mailto|sms|smsto|geo|wifi):", raw, re.I) or raw.upper().startswith(("BEGIN:VCARD", "BEGIN:WIFI")):
        return "other"
    return "text"


class OpenCVQRDecoder(QRDecoder):
    name = "opencv"
    _MAX_VARIANT_SIDE = 2000
    _BORDER = 32

    def _attempt(self, detector, image: np.ndarray) -> tuple[str, list[str], bool]:
        """Analyze the top-to-bottom, then left-to-right payload first when points exist."""
        if hasattr(detector, "detectAndDecodeMulti"):
            _ok, decoded, _, points = detector.detectAndDecodeMulti(image)
            decoded = list(decoded if decoded is not None else [])
            nonempty = [index for index, value in enumerate(decoded) if value]
            if points is not None and len(points) == len(decoded):
                try:
                    nonempty.sort(key=lambda index: (
                        float(np.asarray(points[index])[:, 1].min()),
                        float(np.asarray(points[index])[:, 0].min()),
                    ))
                except (IndexError, TypeError, ValueError):
                    pass
            values = [decoded[index] for index in nonempty]
            if values:
                return values[0], ["Multiple QR codes detected; the first payload was analyzed." ] if len(values) > 1 else [], len(values) > 1
        payload, _, _ = detector.detectAndDecode(image)
        if payload:
            return payload, [], False
        return "", [], False

    def _variants(self, base: np.ndarray):
        """Yield bounded QR variants one at a time, in detection priority order."""
        yield base
        height, width = base.shape[:2]
        side = max(height, width)
        if side + 2 * self._BORDER <= self._MAX_VARIANT_SIDE:
            yield cv2.copyMakeBorder(base, self._BORDER, self._BORDER, self._BORDER, self._BORDER,
                                     cv2.BORDER_CONSTANT, value=(255, 255, 255))
        if side < 1000:
            scale = min(2.0, self._MAX_VARIANT_SIDE / side)
            yield cv2.resize(base, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)
        gray = cv2.cvtColor(base, cv2.COLOR_RGB2GRAY)
        yield gray
        yield cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)[1]

    def decode(self, image: Image.Image) -> QRResult:
        base = np.array(image.convert("RGB"))
        detector = cv2.QRCodeDetector()
        for variant in self._variants(base):
            try:
                payload, warnings, _ = self._attempt(detector, variant)
            except cv2.error:
                continue
            if payload:
                return QRResult("completed", payload, classify_payload(payload), self.name, warnings, "QR payload decoded.")
        return QRResult("failed", None, None, self.name, [], "No QR code was detected in the image.")
