"""Runtime dependency checks for defusedxml."""

from pathlib import Path

import defusedxml

ROOT = Path(__file__).resolve().parents[2]


def _version_tuple(version: str) -> tuple[int, ...]:
    parts = []
    for token in version.split("."):
        digits = ""
        for char in token:
            if char.isdigit():
                digits += char
            else:
                break
        parts.append(int(digits) if digits else 0)
    while len(parts) < 3:
        parts.append(0)
    return tuple(parts[:3])


def test_defusedxml_importable():
    assert defusedxml is not None


def test_defusedxml_version_meets_minimum():
    assert _version_tuple(defusedxml.__version__) >= (0, 7, 1)


def test_defusedxml_elementtree_available():
    import defusedxml.ElementTree as ET

    assert hasattr(ET, "parse")
    assert hasattr(ET, "fromstring")


def test_requirements_declares_defusedxml():
    requirements = (ROOT / "requirements.txt").read_text(encoding="utf-8")
    assert "defusedxml>=0.7.1" in requirements


def test_production_extractors_use_defusedxml():
    from services.analysis.extractors import docx_text, xlsx_text

    assert getattr(docx_text.ET, "__name__", "") == "defusedxml.ElementTree"
    assert getattr(xlsx_text.ET, "__name__", "") == "defusedxml.ElementTree"
