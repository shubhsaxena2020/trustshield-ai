"""Pure OCR benchmark metrics and security-token checks."""
from __future__ import annotations

import difflib
import re
import unicodedata
from collections import defaultdict
from typing import Iterable


def _prepare(value: str) -> str:
    value = unicodedata.normalize("NFKC", value or "")
    return re.sub(r"\s+", " ", value).strip()


def _distance(reference: list[str], hypothesis: list[str]) -> int:
    previous = list(range(len(hypothesis) + 1))
    for i, ref_item in enumerate(reference, 1):
        current = [i]
        for j, hyp_item in enumerate(hypothesis, 1):
            current.append(min(
                current[-1] + 1,
                previous[j] + 1,
                previous[j - 1] + (ref_item != hyp_item),
            ))
        previous = current
    return previous[-1]


def _rate(reference: str, hypothesis: str, *, characters: bool = False, case_sensitive: bool = True) -> float:
    ref_tokens, hyp_tokens = _metric_tokens(reference, hypothesis, characters=characters, case_sensitive=case_sensitive)
    if not ref_tokens:
        return 0.0 if not hyp_tokens else 1.0
    return _distance(ref_tokens, hyp_tokens) / len(ref_tokens)


def _metric_tokens(reference: str, hypothesis: str, *, characters: bool, case_sensitive: bool) -> tuple[list[str], list[str]]:
    ref = _prepare(reference)
    hyp = _prepare(hypothesis)
    if not case_sensitive:
        ref, hyp = ref.casefold(), hyp.casefold()
    return (list(ref) if characters else ref.split(), list(hyp) if characters else hyp.split())


def wer(ref: str, hyp: str) -> float:
    """Case-sensitive word error rate after NFKC/whitespace preparation."""
    return _rate(ref, hyp)


def cer(ref: str, hyp: str) -> float:
    """Case-sensitive character error rate after NFKC/whitespace preparation."""
    return _rate(ref, hyp, characters=True)


def wer_case_insensitive(ref: str, hyp: str) -> float:
    return _rate(ref, hyp, case_sensitive=False)


def cer_case_insensitive(ref: str, hyp: str) -> float:
    return _rate(ref, hyp, characters=True, case_sensitive=False)


def _alignment(reference: list[str], hypothesis: list[str]) -> list[tuple[str, str | None, str | None]]:
    rows = len(reference) + 1
    cols = len(hypothesis) + 1
    dp = [[0] * cols for _ in range(rows)]
    for i in range(rows):
        dp[i][0] = i
    for j in range(cols):
        dp[0][j] = j
    for i in range(1, rows):
        for j in range(1, cols):
            dp[i][j] = min(
                dp[i - 1][j] + 1,
                dp[i][j - 1] + 1,
                dp[i - 1][j - 1] + (reference[i - 1] != hypothesis[j - 1]),
            )
    aligned: list[tuple[str, str | None, str | None]] = []
    i, j = len(reference), len(hypothesis)
    while i or j:
        if i and j and reference[i - 1] == hypothesis[j - 1] and dp[i][j] == dp[i - 1][j - 1]:
            aligned.append(("equal", reference[i - 1], hypothesis[j - 1]))
            i, j = i - 1, j - 1
        elif i and j and dp[i][j] == dp[i][j - 1] + 1:
            aligned.append(("insert", None, hypothesis[j - 1]))
            j -= 1
        elif i and j and dp[i][j] == dp[i - 1][j - 1] + 1:
            aligned.append(("substitute", reference[i - 1], hypothesis[j - 1]))
            i, j = i - 1, j - 1
        elif i and dp[i][j] == dp[i - 1][j] + 1:
            aligned.append(("delete", reference[i - 1], None))
            i -= 1
        else:
            aligned.append(("insert", None, hypothesis[j - 1]))
            j -= 1
    return list(reversed(aligned))


def word_diff(ref: str, hyp: str) -> dict[str, list]:
    """Return readable missed, corrupted, and inserted word lists."""
    reference = _prepare(ref).split()
    hypothesis = _prepare(hyp).split()
    alignment = _alignment(reference, hypothesis)
    return {
        "missed": [expected for operation, expected, _ in alignment if operation == "delete"],
        "corrupted": [
            {"expected": expected, "actual": actual}
            for operation, expected, actual in alignment
            if operation == "substitute"
        ],
        "inserted": [actual for operation, _, actual in alignment if operation == "insert"],
        "alignment": [
            {"operation": operation, "expected": expected, "actual": actual}
            for operation, expected, actual in alignment
        ],
    }


def _nearest_substring(expected: str, observed: str) -> str | None:
    if not observed:
        return None
    candidates = observed.split()
    width = max(1, len(expected))
    for size in range(max(1, width - 4), width + 5):
        candidates.extend(observed[i:i + size] for i in range(max(0, len(observed) - size + 1)))
    return max(candidates, key=lambda candidate: difflib.SequenceMatcher(None, expected, candidate).ratio())


_ALNUM = r"[^\W_]"
_URL_SPAN = re.compile(r"(?i)(?:https?://|www\.)[^\s<>()]+|(?<![\w@])(?:[a-z0-9-]+\.)+[a-z]{2,}(?:/[^\s<>()]*)?")


def _is_url_span(raw_text: str, start: int, end: int) -> bool:
    return any(match.start() <= start and end <= match.end() for match in _URL_SPAN.finditer(raw_text))


def _bounded_match(value: str, raw_text: str, category: str, *, case_sensitive: bool, compact: bool = False) -> bool:
    if not value:
        return False
    boundary = r"\d" if value.isdigit() else _ALNUM
    body = r"\s*".join(re.escape(char) for char in value) if compact else re.escape(value)
    pattern = rf"(?<!{boundary}){body}(?!{boundary})"
    flags = 0 if case_sensitive else re.IGNORECASE
    for match in re.finditer(pattern, raw_text, flags):
        if category != "org" or not _is_url_span(raw_text, match.start(), match.end()):
            return True
    return False


def _token_match(value: str, raw_text: str, category: str) -> tuple[bool, bool]:
    exact = _bounded_match(value, raw_text, category, case_sensitive=True)
    if category == "org":
        lenient = _bounded_match(value, raw_text, category, case_sensitive=False)
    elif category in {"url", "phone"}:
        lenient = _bounded_match(value, raw_text, category, case_sensitive=True, compact=True)
    else:
        lenient = exact
    return exact, lenient


def check_critical_tokens(raw_text: str, critical_tokens: Iterable[dict]) -> dict:
    """Check exact and permitted whitespace-insensitive token preservation."""
    tokens = []
    failures = []
    grouped: dict[str, list[dict]] = defaultdict(list)
    for token in critical_tokens:
        category = str(token["category"])
        value = str(token["value"])
        exact, lenient = _token_match(value, raw_text or "", category)
        item = {
            "category": category,
            "expected": value,
            "exact": exact,
            "lenient": lenient,
            "nearest_ocr_substring": None if exact else _nearest_substring(value, raw_text or ""),
        }
        tokens.append(item)
        grouped[category].append(item)
        if not exact:
            failures.append(item.copy())

    def rate(items: list[dict]) -> dict[str, float]:
        if not items:
            return {"exact": 0.0, "lenient": 0.0}
        return {
            "exact": sum(item["exact"] for item in items) / len(items),
            "lenient": sum(item["lenient"] for item in items) / len(items),
        }

    all_tokens = list(tokens)
    rates = {category: rate(items) for category, items in sorted(grouped.items())}
    rates["overall"] = rate(all_tokens)
    return {"tokens": tokens, "rates": rates, "failures": failures}


def score_text(reference: str, hypothesis: str) -> dict[str, float | int]:
    """Return WER/CER variants plus their integer edit counts and denominators."""
    ref_words, hyp_words = _metric_tokens(reference, hypothesis, characters=False, case_sensitive=True)
    ref_chars, hyp_chars = _metric_tokens(reference, hypothesis, characters=True, case_sensitive=True)
    ref_words_ci, hyp_words_ci = _metric_tokens(reference, hypothesis, characters=False, case_sensitive=False)
    ref_chars_ci, hyp_chars_ci = _metric_tokens(reference, hypothesis, characters=True, case_sensitive=False)
    word_edits = _distance(ref_words, hyp_words)
    char_edits = _distance(ref_chars, hyp_chars)
    word_edits_ci = _distance(ref_words_ci, hyp_words_ci)
    char_edits_ci = _distance(ref_chars_ci, hyp_chars_ci)
    return {
        "wer": 0.0 if not ref_words and not hyp_words else (1.0 if not ref_words else word_edits / len(ref_words)),
        "cer": 0.0 if not ref_chars and not hyp_chars else (1.0 if not ref_chars else char_edits / len(ref_chars)),
        "wer_case_insensitive": 0.0 if not ref_words_ci and not hyp_words_ci else (1.0 if not ref_words_ci else word_edits_ci / len(ref_words_ci)),
        "cer_case_insensitive": 0.0 if not ref_chars_ci and not hyp_chars_ci else (1.0 if not ref_chars_ci else char_edits_ci / len(ref_chars_ci)),
        "reference_words": len(ref_words),
        "reference_chars": len(ref_chars),
        "wer_edits": word_edits,
        "cer_edits": char_edits,
        "wer_case_insensitive_edits": word_edits_ci,
        "cer_case_insensitive_edits": char_edits_ci,
    }


def aggregate_scores(scores: Iterable[dict]) -> dict[str, dict[str, float]]:
    """Compute micro and macro rates from per-fixture score records."""
    records = list(scores)
    result = {}
    for key in ("wer", "cer", "wer_case_insensitive", "cer_case_insensitive"):
        values = [float(item[key]) for item in records]
        result[key] = {"macro": sum(values) / len(values) if values else 0.0}
    result["wer"]["micro"] = _micro_rate(records, "reference_words", "wer_edits")
    result["cer"]["micro"] = _micro_rate(records, "reference_chars", "cer_edits")
    result["wer_case_insensitive"]["micro"] = _micro_rate(records, "reference_words", "wer_case_insensitive_edits")
    result["cer_case_insensitive"]["micro"] = _micro_rate(records, "reference_chars", "cer_case_insensitive_edits")
    return result


def _micro_rate(records: list[dict], denominator_key: str, edits_key: str) -> float:
    denominator = sum(int(item.get(denominator_key, 0)) for item in records)
    edits = sum(int(item.get(edits_key, 0)) for item in records)
    return edits / denominator if denominator else (1.0 if edits else 0.0)
