import os

import pytest

from services.analysis.extractors.document_types import (
    MAX_DOCUMENT_BYTES,
    max_document_bytes,
)


def test_default_env_returns_hard_cap(monkeypatch):
    monkeypatch.delenv("MAX_UPLOAD_SIZE", raising=False)
    assert max_document_bytes() == MAX_DOCUMENT_BYTES


def test_smaller_env_honored(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "12345")
    assert max_document_bytes() == 12345


def test_larger_env_clamped_to_hard_cap(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", str(100 * 1024 * 1024))
    assert max_document_bytes() == MAX_DOCUMENT_BYTES


def test_invalid_env_falls_back_to_hard_cap(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "not-an-int")
    assert max_document_bytes() == MAX_DOCUMENT_BYTES


def test_empty_env_falls_back_to_hard_cap(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "")
    assert max_document_bytes() == MAX_DOCUMENT_BYTES


def test_zero_or_negative_env_clamped_to_one(monkeypatch):
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "0")
    assert max_document_bytes() == 1
    monkeypatch.setenv("MAX_UPLOAD_SIZE", "-5")
    assert max_document_bytes() == 1

