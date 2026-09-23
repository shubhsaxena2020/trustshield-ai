from scripts.ocr_benchmark import _scoped_word_diff
from services.analysis.extractors.ocr_metrics import word_diff


def test_scoped_word_diff_uses_reference_header_span_not_global_token_membership():
    reference = (
        "Synthetic TrustShield chat\n"
        "DEMOPAY CHAT\n"
        "OTP 481926 and PIN 7391\n"
        "Pay Rs 1250.50 now: https://pay.invalid/go"
    )
    scoped = _scoped_word_diff(word_diff(reference, ""), reference)

    assert scoped["ui_chrome"]["missed"] == ["Synthetic", "TrustShield", "chat"]
    assert scoped["message"]["missed"] == [
        "DEMOPAY", "CHAT", "OTP", "481926", "and", "PIN", "7391",
        "Pay", "Rs", "1250.50", "now:", "https://pay.invalid/go",
    ]
