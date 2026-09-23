from services.analysis.extractors.ocr_metrics import (
    _token_match,
    aggregate_scores,
    cer,
    check_critical_tokens,
    score_text,
    wer,
    word_diff,
)


def test_wer_and_cer_use_edit_distance_and_case_sensitive_primary():
    assert wer("alpha beta", "alpha gamma beta") == 0.5
    assert cer("Cat", "cat") == 1 / 3
    assert wer("", "") == 0.0
    assert wer("", "extra") == 1.0
    assert cer("", "extra") == 1.0


def test_metrics_normalize_unicode_and_whitespace():
    assert wer("Ａ  beta\n", "A beta") == 0.0
    assert cer("café", "CAFE") == 4 / 4


def test_word_diff_lists_missed_corrupted_and_inserted_words():
    diff = word_diff("send OTP today", "send O7P now today please")
    assert diff["corrupted"] == [{"expected": "OTP", "actual": "O7P"}]
    assert diff["missed"] == []
    assert diff["inserted"] == ["now", "please"]


def test_word_diff_edit_operations_match_wer_edit_count():
    diff = word_diff("alpha beta gamma", "alpha X Y gamma")
    edits = sum(item["operation"] != "equal" for item in diff["alignment"])
    assert edits == score_text("alpha beta gamma", "alpha X Y gamma")["wer_edits"]


def test_critical_token_matching_requires_boundaries_and_url_exclusion():
    negative_cases = [
        ("pin", "PIN", "In my OPINION this is good"),
        ("amount", "Rs 500", "You won Rs 5000 today"),
        ("otp", "481926", "Call 98748192600"),
        ("cvv", "284", "Total amount is Rs 12840"),
        ("phone", "9876500000", "Ref 1987650000029"),
        ("org", "SBI", "visit http://sbi-fake.xyz/login"),
        ("org", "DEMO", "paid with DEMOPAY"),
    ]
    for category, expected, raw_text in negative_cases:
        assert _token_match(expected, raw_text, category) == (False, False)

    positive_cases = [
        ("pin", "PIN", "PIN"),
        ("amount", "Rs 500", "Rs 500"),
        ("otp", "481926", "481926"),
        ("cvv", "284", "284"),
        ("phone", "9876500000", "9876500000"),
        ("org", "SBI", "SBI"),
        ("org", "SBI", "sbi"),
    ]
    for category, expected, raw_text in positive_cases:
        exact, lenient = _token_match(expected, raw_text, category)
        assert lenient is True
        assert exact is (raw_text == expected or category != "org")


def test_score_text_returns_edit_counts_and_handles_empty_reference():
    scores = score_text("", "hallucinated")
    assert scores["reference_words"] == 0
    assert scores["reference_chars"] == 0
    assert scores["wer_edits"] == 1
    assert scores["cer_edits"] == len("hallucinated")
    assert scores["wer"] == 1.0
    assert aggregate_scores([scores])["wer"]["micro"] == 1.0


def test_security_tokens_report_exact_and_lenient_preservation():
    result = check_critical_tokens(
        "Visit https://demo .invalid/verify. Call 98765 00000. Demo Bank",
        [
            {"category": "url", "value": "https://demo.invalid/verify"},
            {"category": "phone", "value": "9876500000"},
            {"category": "org", "value": "demo bank"},
            {"category": "otp", "value": "481926"},
        ],
    )
    by_category = {item["category"]: item for item in result["tokens"]}
    assert by_category["url"]["exact"] is False
    assert by_category["url"]["lenient"] is True
    assert by_category["phone"]["exact"] is False
    assert by_category["phone"]["lenient"] is True
    assert by_category["org"]["exact"] is False
    assert by_category["org"]["lenient"] is True
    assert by_category["otp"]["exact"] is False
    assert result["rates"]["overall"]["exact"] == 0.0
    assert result["rates"]["overall"]["lenient"] == 3 / 4
    assert result["failures"]
