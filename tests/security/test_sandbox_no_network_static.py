from services.analysis.pipeline import analyze_qr, analyze_url
from services.analysis.extractors.image_validation import ImageRejected


def test_static_url_and_qr_paths_make_no_socket_calls(monkeypatch):
    def fail(*args, **kwargs):
        raise AssertionError("static/QR analysis attempted network I/O")
    monkeypatch.setattr("socket.socket", fail)
    assert analyze_url("https://example.com/login").modules["url_static"].status == "completed"
    # A malformed/non-QR image is rejected locally before any network operation.
    try:
        analyze_qr(b"not-an-image", "image/png", "x.png")
    except ImageRejected as exc:
        assert exc.status_code == 422
