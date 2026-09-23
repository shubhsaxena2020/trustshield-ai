from io import BytesIO
from types import SimpleNamespace

import pytest
from PIL import Image, ImageDraw, ImageFont, PngImagePlugin

import services.analysis.extractors.ocr as ocr_module
from services.analysis.extractors.ocr import OCRResult, TesseractProvider, resolve_tesseract_cmd
from services.analysis.pipeline import analyze_image


def _png(text="HELLO WORLD OCR CHECK", metadata="not the pixels", size=(900, 260)):
    image = Image.new("RGB", size, "white")
    draw = ImageDraw.Draw(image)
    draw.text((35, 80), text, fill="black", font=ImageFont.load_default(size=32))
    out = BytesIO()
    info = PngImagePlugin.PngInfo()
    info.add_text("trustshield_demo_text", metadata)
    image.save(out, format="PNG", pnginfo=info)
    return out.getvalue()


def test_canonical_ocr_data_contains_bounded_provenance(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_OCR_PROVIDER", "mock")
    result = analyze_image(_png("ignored pixels", "metadata text"), "image/png", "x.png")
    data = result.modules["ocr"].data
    assert data["provider"] == "mock"
    assert data["text"] == data["raw_text"]
    assert data["normalized_text"] == "metadata text"
    assert data["preprocessing"] == "mock"
    assert data["tesseract_cmd_source"] in {"env:TESSERACT_CMD", "path", "default-location"}


def test_tesseract_runtime_failure_becomes_failed_result(monkeypatch):
    fake = SimpleNamespace(
        pytesseract=SimpleNamespace(tesseract_cmd=None),
        get_tesseract_version=lambda: "test",
        image_to_string=lambda *args, **kwargs: (_ for _ in ()).throw(RuntimeError("process timeout")),
        image_to_data=lambda *args, **kwargs: {},
    )
    monkeypatch.setattr(ocr_module, "pytesseract", fake)
    monkeypatch.setattr(ocr_module, "Output", SimpleNamespace(DICT="dict"))
    monkeypatch.setattr(TesseractProvider, "is_available", lambda self: True)
    result = TesseractProvider().extract(Image.new("RGB", (10, 10), "white"))
    assert result.status == "failed"
    assert "timed out" in result.detail.lower()


def test_tesseract_engine_failure_becomes_failed_result(monkeypatch):
    fake = SimpleNamespace(
        pytesseract=SimpleNamespace(tesseract_cmd=None),
        get_tesseract_version=lambda: "test",
        image_to_string=lambda *args, **kwargs: (_ for _ in ()).throw(ocr_module.TesseractError(1, "failed")),
        image_to_data=lambda *args, **kwargs: {},
    )
    monkeypatch.setattr(ocr_module, "pytesseract", fake)
    monkeypatch.setattr(ocr_module, "Output", SimpleNamespace(DICT="dict"))
    monkeypatch.setattr(TesseractProvider, "is_available", lambda self: True)
    result = TesseractProvider().extract(Image.new("RGB", (10, 10), "white"))
    assert result.status == "failed"
    assert result.detail == "Tesseract OCR engine failure."


def test_tesseract_confidence_failure_keeps_extracted_text(monkeypatch):
    fake = SimpleNamespace(
        pytesseract=SimpleNamespace(tesseract_cmd=None),
        get_tesseract_version=lambda: "test",
        image_to_string=lambda *args, **kwargs: "OCR text survives",
        image_to_data=lambda *args, **kwargs: (_ for _ in ()).throw(RuntimeError("confidence timeout")),
    )
    monkeypatch.setattr(ocr_module, "pytesseract", fake)
    monkeypatch.setattr(ocr_module, "Output", SimpleNamespace(DICT="dict"))
    monkeypatch.setattr(TesseractProvider, "is_available", lambda self: True)

    result = TesseractProvider().extract(Image.new("RGB", (10, 10), "white"))

    assert result.status == "completed"
    assert result.text == "OCR text survives"
    assert result.confidence is None
    assert any("confidence" in warning.lower() for warning in result.warnings)


def test_unset_and_unknown_provider_never_select_mock(monkeypatch):
    from services.analysis.extractors.ocr import MockOCRProvider, get_ocr_provider

    monkeypatch.delenv("TRUSTSHIELD_OCR_PROVIDER", raising=False)
    assert not isinstance(get_ocr_provider(), MockOCRProvider)
    monkeypatch.setenv("TRUSTSHIELD_OCR_PROVIDER", "unexpected")
    assert not isinstance(get_ocr_provider(), MockOCRProvider)


def test_tesseract_resolution_prefers_env_then_path_then_default(monkeypatch):
    monkeypatch.setenv("TESSERACT_CMD", "C:/env/tesseract.exe")
    monkeypatch.setattr(ocr_module.Path, "is_file", lambda self: "env" in str(self) and "tesseract.exe" in str(self))
    monkeypatch.setattr(ocr_module.shutil, "which", lambda _: "C:/path/tesseract.exe")
    assert resolve_tesseract_cmd() == ("C:/env/tesseract.exe", "env:TESSERACT_CMD")

    monkeypatch.setenv("TESSERACT_CMD", "C:/missing/tesseract.exe")
    monkeypatch.setattr(ocr_module.Path, "is_file", lambda self: False)
    assert resolve_tesseract_cmd() == ("C:/path/tesseract.exe", "path")

    monkeypatch.setattr(ocr_module.shutil, "which", lambda _: None)
    monkeypatch.setattr(ocr_module.Path, "is_file", lambda self: str(self) == str(ocr_module.DEFAULT_TESSERACT_PATH))
    assert resolve_tesseract_cmd() == (str(ocr_module.DEFAULT_TESSERACT_PATH), "default-location")


def test_tesseract_unavailable_when_resolution_finds_nothing(monkeypatch):
    fake = SimpleNamespace(
        pytesseract=SimpleNamespace(tesseract_cmd=None),
        get_tesseract_version=lambda: (_ for _ in ()).throw(ocr_module.TesseractNotFoundError()),
    )
    monkeypatch.delenv("TESSERACT_CMD", raising=False)
    monkeypatch.setattr(ocr_module.shutil, "which", lambda _: None)
    monkeypatch.setattr(ocr_module.Path, "is_file", lambda self: False)
    monkeypatch.setattr(ocr_module, "pytesseract", fake)

    result = TesseractProvider().extract(Image.new("RGB", (10, 10), "white"))

    assert result.status == "unavailable"
    assert result.tesseract_cmd_source == "path"
    assert "unavailable" in result.detail.lower()


@pytest.mark.parametrize("mode", ["none", "current", "gray", "gray_autocontrast", "gray_resize2x"])
def test_preprocessing_modes_are_selectable(monkeypatch, mode):
    monkeypatch.setenv("TRUSTSHIELD_OCR_PREPROCESS", mode)
    image = TesseractProvider().preprocess(Image.new("RGB", (100, 50), "white"))
    assert image.mode == ("RGB" if mode == "none" else "L")
    if mode == "gray_resize2x":
        assert image.size == (200, 100)


def test_preprocessing_modes_produce_distinct_pixel_arrays():
    image = Image.new("RGB", (3, 2))
    image.putdata([(255, 0, 0), (0, 255, 0), (0, 0, 255), (20, 30, 40), (80, 90, 100), (150, 160, 170)])

    none = ocr_module.preprocess_for_ocr(image, "none")
    gray = ocr_module.preprocess_for_ocr(image, "gray")
    gray_resize2x = ocr_module.preprocess_for_ocr(image, "gray_resize2x")

    assert none.mode == "RGB"
    assert gray.mode == "L"
    assert gray_resize2x.mode == "L"
    assert none.size != gray_resize2x.size
    assert none.tobytes() != gray.tobytes()
    assert gray.tobytes() != gray_resize2x.tobytes()


@pytest.mark.skipif(not TesseractProvider().is_available(), reason="Tesseract binary is unavailable")
def test_real_tesseract_uses_pixels_not_demo_metadata(monkeypatch):
    monkeypatch.delenv("TRUSTSHIELD_OCR_PROVIDER", raising=False)
    result = analyze_image(_png(), "image/png", "pixel-check.png")
    raw = result.modules["ocr"].data["raw_text"].upper()
    assert "HELLO" in raw
    assert "WORLD" in raw
    assert "NOT THE PIXELS" not in raw


@pytest.mark.skipif(not TesseractProvider().is_available(), reason="Tesseract binary is unavailable")
@pytest.mark.parametrize("color", ["white", "black"])
def test_real_tesseract_blank_and_noise_are_clean(monkeypatch, color):
    monkeypatch.delenv("TRUSTSHIELD_OCR_PROVIDER", raising=False)
    image = Image.new("RGB", (800, 300), color)
    if color == "black":
        pixels = image.load()
        for x in range(0, 800, 17):
            for y in range(0, 300, 19):
                pixels[x, y] = (255, 255, 255)
    out = BytesIO()
    image.save(out, format="PNG")
    result = analyze_image(out.getvalue(), "image/png", "noise.png")
    assert result.risk.label == "unable_to_verify"
    assert result.modules["ocr"].status == "completed"
