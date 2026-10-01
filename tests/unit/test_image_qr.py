import socket
from io import BytesIO
from types import SimpleNamespace

import pytest
from PIL import Image, ImageDraw, PngImagePlugin

from services.analysis.extractors.image_validation import ImageRejected, validate_image
from services.analysis.extractors.ocr import OCRResult, TesseractProvider
from services.analysis.extractors.qr import OpenCVQRDecoder
from services.analysis.pipeline import analyze_image, analyze_qr, analyze_text
from services.analysis.url_analysis.static import analyze_url_static
import services.analysis.extractors.ocr as ocr_module


def png_bytes(text: str = "", size: tuple[int, int] = (900, 300)) -> bytes:
    image = Image.new("RGB", size, "white")
    ImageDraw.Draw(image).text((20, 20), text, fill="black")
    out = BytesIO()
    info = PngImagePlugin.PngInfo()
    if text:
        info.add_text("trustshield_demo_text", text)
    image.save(out, format="PNG", pnginfo=info)
    return out.getvalue()


def qr_bytes(payload: str) -> bytes:
    segno = pytest.importorskip("segno")
    out = BytesIO()
    segno.make(payload, micro=False).save(out, kind="png", scale=8, border=4)
    return out.getvalue()


def categories(result):
    return {item.category for item in result.evidence}


def test_mock_ocr_screenshot_uses_shared_text_pipeline(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_OCR_PROVIDER", "mock")
    result = analyze_image(
        png_bytes("Your SBI account will be blocked today. Share OTP now at http://sbi-kyc-update.xyz/login"),
        "image/png",
        "anything.bin",
    )
    assert result.input_type == "image"
    assert result.modules["ocr"].status == "completed"
    assert {"credential_request", "credential_pressure", "domain_mismatch"} <= categories(result)
    assert result.modules["ocr"].data["image"]["format"] == "PNG"
    assert result.modules["ocr"].data["text"].startswith("Your SBI")
    assert any("OCR" in warning for warning in result.modules["ocr"].data["warnings"])


def test_short_mock_ocr_text_is_analyzed_with_limited_context(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_OCR_PROVIDER", "mock")
    result = analyze_image(png_bytes("OTP 481926"), "image/png", "short.png")

    assert result.status != "failed"
    assert result.modules["ocr"].status == "completed"
    assert result.modules["text_rules"].status == "completed"
    assert result.risk.label != "unable_to_verify"
    assert any("Limited context" in limitation for limitation in result.limitations)


def test_ocr_unavailable_returns_valid_unable_to_verify(monkeypatch):
    monkeypatch.delenv("TRUSTSHIELD_OCR_PROVIDER", raising=False)
    monkeypatch.setattr(TesseractProvider, "is_available", lambda self: False)
    result = analyze_image(png_bytes("A readable message that is not used"), "image/png", "x.png")
    assert result.status == "failed"
    assert result.risk.label == "unable_to_verify"
    assert result.modules["ocr"].status == "unavailable"
    assert result.limitations


def test_oversized_ocr_text_is_truncated_before_text_analysis(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_OCR_PROVIDER", "mock")
    text = "A" * 20_005
    result = analyze_image(png_bytes(text), "image/png", "large.png")
    assert result.status == "completed"
    assert len(result.modules["ocr"].data["text"]) == 20_000
    assert any("OCR text was truncated" in limitation for limitation in result.limitations)


def test_non_mock_provider_never_sees_demo_metadata(monkeypatch):
    png = png_bytes("spoofed OCR text")
    monkeypatch.setenv("TRUSTSHIELD_OCR_PROVIDER", "tesseract")
    seen = {}

    class SpyProvider:
        name = "spy"

        def extract(self, image):
            seen.update(image.info)
            return OCRResult("completed", "ordinary text long enough", self.name, "test", 90, [], "test")

    monkeypatch.setattr("services.analysis.pipeline.get_ocr_provider", lambda: SpyProvider())
    analyze_image(png, "image/png", "spoof.png")
    assert "trustshield_demo_text" not in seen


def test_mime_parameters_are_accepted():
    result = analyze_image(png_bytes("ordinary text long enough"), "image/png; charset=utf-8", "x.png")
    assert result.input_type == "image"


def test_low_quality_ocr_has_lower_confidence_than_direct_text(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_OCR_PROVIDER", "mock")
    monkeypatch.setenv("TRUSTSHIELD_ML", "off")
    text = "Your account will be blocked today. Share OTP now at http://sbi-kyc-update.xyz/login"
    monkeypatch.setattr(
        "services.analysis.pipeline.get_ocr_provider",
        lambda: type("LowQuality", (), {
            "name": "low-quality",
            "extract": lambda self, image: OCRResult(
                status="completed", text=text, provider="low-quality", provider_version="1", confidence=20.0,
                warnings=[], detail="test",
            ),
        })(),
    )
    direct = analyze_text(text)
    image_result = analyze_image(png_bytes(text), "image/png", "x.png")
    rank = {"low": 0, "medium": 1, "high": 2}
    assert rank[image_result.risk.confidence] < rank[direct.risk.confidence]


@pytest.mark.parametrize("data,declared", [
    (b"not an image", "image/png"),
    (b"", "image/png"),
])
def test_image_validation_rejects_empty_or_corrupt(data, declared):
    with pytest.raises(ImageRejected) as exc:
        validate_image(data, declared, "x.png")
    assert exc.value.status_code == 422


def test_image_validation_rejects_gif_and_mismatched_mime():
    image = Image.new("RGB", (10, 10), "white")
    out = BytesIO()
    image.save(out, format="GIF")
    with pytest.raises(ImageRejected) as gif:
        validate_image(out.getvalue(), "image/gif", "x.gif")
    assert gif.value.status_code == 415
    with pytest.raises(ImageRejected) as mismatch:
        validate_image(png_bytes("hello"), "image/jpeg", "x.jpg")
    assert mismatch.value.status_code == 415
    out = BytesIO()
    image.save(out, format="BMP")
    with pytest.raises(ImageRejected) as bmp:
        validate_image(out.getvalue(), "image/bmp", "x.png")
    assert bmp.value.status_code == 415


def test_blank_image_returns_unable_to_verify(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_OCR_PROVIDER", "mock")
    result = analyze_image(png_bytes(), "image/png", "x.png")
    assert result.risk.label == "unable_to_verify"
    assert any("no readable text" in item.lower() for item in result.limitations)


def test_legitimate_security_awareness_screenshot_is_not_credential_phishing(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_OCR_PROVIDER", "mock")
    result = analyze_image(
        png_bytes("Rs 500.00 debited from A/c XX1234. Never share your OTP or PIN with anyone."),
        "image/png",
        "bank.png",
    )
    assert "credential_request" not in categories(result)
    assert result.risk.label not in {"high_risk", "suspicious"}


def test_upload_and_dimension_limits(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "10")
    with pytest.raises(ImageRejected) as upload:
        validate_image(png_bytes("hello"), "image/png", "x.png")
    assert upload.value.status_code == 413
    monkeypatch.delenv("MAX_UPLOAD_SIZE")
    with pytest.raises(ImageRejected) as dimensions:
        validate_image(png_bytes("x", (8001, 1)), "image/png", "x.png")
    assert dimensions.value.status_code == 422


def test_decompression_bomb_is_rejected():
    image = Image.new("1", (5001, 5001), 0)
    out = BytesIO()
    image.save(out, format="PNG", optimize=True)
    with pytest.raises(ImageRejected) as rejected:
        validate_image(out.getvalue(), "image/png", "x.png")
    assert rejected.value.status_code == 422
    assert rejected.value.code in {"decompression_bomb", "dimensions_too_large"}


def test_qr_url_uses_static_analysis_without_fetching(monkeypatch):
    def fail(*args, **kwargs):
        raise AssertionError("network access attempted")

    monkeypatch.setattr(socket, "getaddrinfo", fail)
    monkeypatch.setattr(socket, "socket", fail)
    monkeypatch.setattr(socket, "create_connection", fail)
    monkeypatch.setattr("httpx.request", fail)
    monkeypatch.setattr("urllib.request.urlopen", fail, raising=False)

    result = analyze_qr(qr_bytes("http://sbi-secure-login.top:8080/kyc/verify.apk"), "image/png", "qr.png")
    assert result.input_type == "qr"
    assert result.modules["qr"].data["payload_type"] == "url"
    assert {"domain_mismatch", "executable_download", "unusual_port"} <= categories(result)

    analyze_qr(qr_bytes("https://example.com/safe"), "image/png", "qr.png")
    analyze_qr(qr_bytes("upi://pay?pa=merchant@upi&pn=Store&am=100&tn=Dinner"), "image/png", "qr.png")


def test_qr_text_and_short_text(monkeypatch):
    monkeypatch.setenv("TRUSTSHIELD_ML", "off")
    result = analyze_qr(qr_bytes("This is a plain QR message with enough text"), "image/png", "qr.png")
    assert result.modules["qr"].data["payload_type"] == "text"
    assert result.modules["text_rules"].status == "completed"
    short = analyze_qr(qr_bytes("hello"), "image/png", "qr.png")
    assert short.status != "failed"
    assert short.modules["text_rules"].status == "completed"
    assert short.risk.label == "low_risk"
    assert short.risk.confidence == "low"
    assert any("Limited context" in limitation for limitation in short.limitations)


def test_benign_qr_and_image_without_qr():
    benign = analyze_qr(qr_bytes("https://en.wikipedia.org/wiki/QR_code"), "image/png", "qr.png")
    assert benign.modules["qr"].data["payload_type"] == "url"
    assert "domain_mismatch" not in categories(benign)
    blank = analyze_qr(png_bytes("nothing"), "image/png", "blank.png")
    assert blank.modules["qr"].status == "failed"
    assert blank.risk.label == "unable_to_verify"


def test_upi_qr_is_structurally_parsed():
    result = analyze_qr(qr_bytes("upi://pay?pa=merchant@upi&pn=Demo%20Store&am=125.50&cu=INR&tn=Order%20payment%20for%20demo&mc=5812&mode=02"), "image/png", "qr.png")
    upi = result.modules["qr"].data["upi"]
    assert result.modules["qr"].data["payload_type"] == "upi"
    assert upi["payee_vpa"] == "merchant@upi"
    assert upi["amount"] == "125.50"
    assert "qr_payment_request" in categories(result)


def test_short_upi_note_does_not_register_failed_text_rules():
    result = analyze_qr(qr_bytes("upi://pay?pa=merchant@upi&pn=Store&am=100&tn=Dinner"), "image/png", "qr.png")
    assert result.status == "completed"
    assert result.risk.label != "unable_to_verify"
    assert result.modules["text_rules"].status == "completed"
    assert any("Limited context" in limitation for limitation in result.limitations)


def test_upi_query_keys_are_case_insensitive():
    from services.analysis.pipeline import _parse_upi

    upi, evidence, limitations = _parse_upi("upi://pay?PA=merchant@upi&PN=Store&AM=100&CU=INR&TN=Dinner&MC=5812&MODE=02")
    assert upi["payee_vpa"] == "merchant@upi"
    assert upi["payee_name"] == "Store"
    assert upi["amount"] == "100"
    assert upi["currency"] == "INR"
    assert upi["note"] == "Dinner"
    assert "qr_missing_payee" not in {item.category for item in evidence}


def test_upi_malformed_vpa_is_safe_and_bounded():
    from services.analysis.pipeline import _parse_upi

    malicious = "merchant\n<script>@upi"
    upi, evidence, _ = _parse_upi(
        "upi://pay?pa=" + malicious + "&tn=" + ("Dinner\x00" * 60)
    )
    assert "qr_malformed_payee" in {item.category for item in evidence}
    assert "\n" not in upi["payee_vpa"]
    assert "\x00" not in upi["note"]
    assert len(upi["note"]) <= 500
    for item in evidence:
        assert "\n" not in item.title + item.description


def test_multiple_qr_codes_use_leftmost_payload_deterministically():
    first = Image.open(BytesIO(qr_bytes("https://example.com/first"))).convert("RGB")
    second = Image.open(BytesIO(qr_bytes("https://example.org/second"))).convert("RGB")
    combined = Image.new("RGB", (first.width + second.width, max(first.height, second.height)), "white")
    combined.paste(first, (0, 0))
    combined.paste(second, (first.width, 0))
    out = BytesIO()
    combined.save(out, format="PNG")
    result = analyze_qr(out.getvalue(), "image/png", "multiple.png")
    assert result.modules["qr"].data["payload"] == "https://example.com/first"


def test_large_qr_does_not_upscale(monkeypatch):
    image = Image.new("RGB", (1200, 1200), "white")
    resized = []

    def fail_resize(*args, **kwargs):
        resized.append(True)
        raise AssertionError("large image was upscaled")

    class Detector:
        def detectAndDecode(self, image):
            return "safe payload with enough length", None, None

    monkeypatch.setattr("services.analysis.extractors.qr.cv2.resize", fail_resize)
    monkeypatch.setattr("services.analysis.extractors.qr.cv2.QRCodeDetector", lambda: Detector())
    result = OpenCVQRDecoder().decode(image)
    assert result.payload == "safe payload with enough length"
    assert not resized


def test_tesseract_confidence_ignores_empty_words(monkeypatch):
    fake = SimpleNamespace(
        pytesseract=SimpleNamespace(tesseract_cmd=None),
        get_tesseract_version=lambda: "test",
        image_to_data=lambda image, output_type: {"text": ["", "word", ""], "conf": ["99", "50", "80"]},
    )
    monkeypatch.setattr(ocr_module, "pytesseract", fake)
    monkeypatch.setattr(ocr_module, "Output", SimpleNamespace(DICT="dict"))
    monkeypatch.setattr(TesseractProvider, "is_available", lambda self: True)
    result = TesseractProvider().extract(Image.new("RGB", (10, 10), "white"))
    assert result.text == "word"
    assert result.confidence == 50.0


def test_javascript_payload_is_sent_to_static_analyzer():
    result = analyze_qr(qr_bytes("javascript:alert(1)"), "image/png", "qr.png")
    assert "unsupported_scheme" in categories(result)
    assert result.risk.label in {"caution", "suspicious", "high_risk"}


def test_multiple_qr_codes_warn_and_use_first():
    first = Image.open(BytesIO(qr_bytes("https://example.com/first"))).convert("RGB")
    second = Image.open(BytesIO(qr_bytes("https://example.org/second"))).convert("RGB")
    combined = Image.new("RGB", (first.width + second.width, max(first.height, second.height)), "white")
    combined.paste(first, (0, 0))
    combined.paste(second, (first.width, 0))
    out = BytesIO()
    combined.save(out, format="PNG")
    result = analyze_qr(out.getvalue(), "image/png", "multiple.png")
    assert result.modules["qr"].status == "completed"
    assert any("multiple" in warning.lower() for warning in result.modules["qr"].data["warnings"])


@pytest.mark.skipif(not TesseractProvider().is_available(), reason="Tesseract binary is unavailable")
def test_real_tesseract_smoke():
    result = analyze_image(png_bytes("Never share your OTP with anyone.", (1200, 300)), "image/png", "ocr.png")
    assert result.modules["ocr"].status == "completed"


def test_regression_url_categories_only():
    result = analyze_url_static("http://sbi-secure-login.top:8080/kyc/verify.apk")
    assert {"domain_mismatch", "executable_download", "unusual_port"} <= categories(result)
