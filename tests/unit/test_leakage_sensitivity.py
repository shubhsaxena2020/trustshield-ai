from __future__ import annotations

import pytest

from scripts.leakage_sensitivity import max_char_tfidf_similarity, prevalence_adjusted_precision


def test_similarity_vectorizer_is_fit_on_train_and_expands_pool_only():
    train = ["claim prize now", "ordinary meeting reminder"]
    test = ["claim prize now"]
    train_only = max_char_tfidf_similarity(test, train, train)
    train_plus_validation = max_char_tfidf_similarity(test, train, train + ["claim prize now"])
    assert train_only[0] == pytest.approx(1.0)
    assert train_plus_validation[0] >= train_only[0]


def test_prevalence_adjusted_precision_is_explicitly_derived():
    assert prevalence_adjusted_precision(0.8, 0.1, 0.2) == pytest.approx(0.6666666667)
