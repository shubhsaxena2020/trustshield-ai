"""Decode and validate uploaded raster images before any modality extractor sees them."""
from __future__ import annotations

import os
import warnings
from dataclasses import dataclass
from io import BytesIO

import pillow_heif
from PIL import Image, ImageOps

# iPhones save camera photos as HEIC/HEIF by default; without this, every such upload was rejected before Pillow even
# saw the bytes. pillow-heif ships libheif bundled in its wheel, so this needs no extra system package.
pillow_heif.register_heif_opener()

MAX_DEFAULT_UPLOAD_SIZE = 5 * 1024 * 1024
MAX_SIDE = 8000
MAX_PIXELS = 25_000_000
ALLOWED_MIME = {"image/png", "image/jpeg", "image/webp", "image/heic", "image/heif"}
FORMAT_MIME = {"PNG": "image/png", "JPEG": "image/jpeg", "WEBP": "image/webp", "HEIF": "image/heic"}

# Pillow's bomb guard is an additional protection; explicit dimension checks below
# still produce the API's stable 422 response for oversized images.
Image.MAX_IMAGE_PIXELS = MAX_PIXELS


class ImageRejected(ValueError):
    def __init__(self, status_code: int, code: str, message: str):
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message


@dataclass
class ValidatedImage:
    image: Image.Image
    detected_format: str
    width: int
    height: int
    size_bytes: int


def max_upload_size() -> int:
    try:
        return max(1, int(os.getenv("MAX_UPLOAD_SIZE", str(MAX_DEFAULT_UPLOAD_SIZE))))
    except ValueError:
        return MAX_DEFAULT_UPLOAD_SIZE


def _media_type(declared_mime: str | None) -> str | None:
    if not declared_mime:
        return None
    media_type = declared_mime.split(";", 1)[0].strip().lower()
    # HEIC and HEIF are the same container for a single still image; browsers are inconsistent about which they
    # declare, and the decoded format can only map to one of them (see FORMAT_MIME), so treat them as equivalent.
    return "image/heic" if media_type == "image/heif" else media_type


def validate_image(data: bytes, declared_mime: str | None, filename: str | None) -> ValidatedImage:
    """Validate decoded pixels; declared MIME and filename are never trusted, in either direction: a generic or
    missing content type (a real-world case -- some browsers/OSes send "application/octet-stream" for HEIC because
    they have no registered MIME mapping for it, which used to get a genuine photo rejected before Pillow even saw
    the bytes) never blocks a valid image, and a confidently-claimed but wrong image/* type never lets a mismatched
    one through."""
    del filename  # retained in the public seam for API callers; it never selects a decoder
    size = len(data or b"")
    media_type = _media_type(declared_mime)
    if size > max_upload_size():
        raise ImageRejected(413, "upload_too_large", "Image exceeds the maximum upload size.")
    # Fast-reject only a type that confidently claims to be an image we don't support; a generic/unknown/missing
    # type (octet-stream, blank, or anything not image/*) falls through to the real decode below instead.
    if media_type and media_type.startswith("image/") and media_type not in ALLOWED_MIME:
        raise ImageRejected(415, "unsupported_mime", "Only PNG, JPEG, WEBP, and HEIC/HEIF images are supported.")
    if not data:
        raise ImageRejected(422, "empty_image", "The uploaded image is empty.")

    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(BytesIO(data)) as opened:
                detected = (opened.format or "").upper()
                if detected not in FORMAT_MIME:
                    raise ImageRejected(415, "unsupported_format", "The decoded image format is not supported.")
                # Only a confidently-declared image/* type can mismatch; a generic/unknown declared type (handled
                # above) has nothing to compare against, so the decoded format alone decides.
                if media_type and media_type.startswith("image/") and media_type != FORMAT_MIME[detected]:
                    raise ImageRejected(415, "mime_mismatch", "The declared MIME type does not match the decoded image format.")
                width, height = opened.size
                if width <= 0 or height <= 0:
                    raise ImageRejected(422, "invalid_dimensions", "The image has invalid dimensions.")
                if width > MAX_SIDE or height > MAX_SIDE or width * height > MAX_PIXELS:
                    raise ImageRejected(422, "dimensions_too_large", "Image dimensions exceed the 8000px-per-side or 25 megapixel limit.")
                opened.seek(0)  # first frame only for animated WEBP
                opened.load()
                use_demo_metadata = os.getenv("TRUSTSHIELD_OCR_PROVIDER", "tesseract").strip().lower() == "mock"
                demo_text = opened.info.get("trustshield_demo_text") if use_demo_metadata else None
                image = ImageOps.exif_transpose(opened).convert("RGB")
                image.format = detected
                if use_demo_metadata and demo_text is not None:
                    image.info["trustshield_demo_text"] = demo_text
                else:
                    image.info.pop("trustshield_demo_text", None)
    except ImageRejected:
        raise
    except (Image.DecompressionBombError, Image.DecompressionBombWarning) as exc:
        raise ImageRejected(422, "decompression_bomb", "The image is too large or unsafe to decode.") from exc
    except (Image.UnidentifiedImageError, OSError, ValueError, SyntaxError) as exc:
        raise ImageRejected(422, "undecodable_image", "The uploaded bytes are not a valid decodable image.") from exc

    return ValidatedImage(image=image, detected_format=detected, width=width, height=height, size_bytes=size)
