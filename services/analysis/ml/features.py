"""Versioned sklearn feature pipelines for text classifiers."""
from __future__ import annotations

from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.pipeline import FeatureUnion, Pipeline

FEATURE_PIPELINE_VERSION = "trustshield-text-features-v1"


def build_feature_pipeline(kind: str):
    if kind == "word":
        return Pipeline([("tfidf", TfidfVectorizer(ngram_range=(1, 2), sublinear_tf=True, lowercase=True, min_df=2))])
    if kind == "char":
        return Pipeline([("tfidf", TfidfVectorizer(analyzer="char_wb", ngram_range=(2, 5), sublinear_tf=True, lowercase=True, min_df=2))])
    if kind == "combined":
        return FeatureUnion([
            ("word", TfidfVectorizer(ngram_range=(1, 2), sublinear_tf=True, lowercase=True, min_df=2)),
            ("char", TfidfVectorizer(analyzer="char_wb", ngram_range=(2, 5), sublinear_tf=True, lowercase=True, min_df=2)),
        ])
    raise ValueError(f"unknown feature pipeline: {kind}")
