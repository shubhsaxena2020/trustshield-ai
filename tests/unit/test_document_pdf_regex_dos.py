"""Small in-memory regressions for malformed PDF text scanning."""
import time

from services.analysis.extractors.pdf_text import _extract_strings


def test_unmatched_open_brackets_are_scanned_in_linear_time():
    malformed = b"[" * 24_000

    started = time.perf_counter()
    assert _extract_strings(malformed) == []
    elapsed = time.perf_counter() - started

    # The prior lazy regex took multiple seconds at this size on the same host.
    assert elapsed < 1.0


def test_text_showing_array_extracts_parenthesized_strings():
    assert _extract_strings(b"[(first) -120 (second)] TJ") == ["first", "second"]


def test_brackets_inside_literal_strings_do_not_change_array_depth():
    assert _extract_strings(b"[(left [middle] right)] TJ") == ["left [middle] right"]
