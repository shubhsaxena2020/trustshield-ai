"""Functional-equivalence fixtures for trustshield_text_v3.

A regenerated artifact is not byte-identical (timestamps/timings are embedded), so
these fixed probabilities, taken from the promoted artifact, prove a regenerated
model behaves the same. See docs/REPRODUCIBILITY.md.
"""
import pytest
from pathlib import Path

from services.analysis.ml.classifier import TextClassifier

FIXTURES = [
    ("Your SBI account is blocked, share OTP now http://sbi-kyc.xyz", 0.6207),
    ("Lunch at 1pm tomorrow?", 0.0048),
    ("Congratulations you won a lottery of 5 crore claim now", 0.8772),
    ("Your electricity bill of BDT 1500 is due tomorrow.", 0.2072),
    ("URGENT: verify your KYC within 24 hours or your account will be suspended http://kyc-update-hdfc.top", 0.9786),
    ("Your OTP is 481516. Do not share it with anyone.", 0.0),
    ("Dear customer your parcel is held, pay Rs 49 customs fee at http://delhivery-track.xyz", 0.921),
    ("Team meeting moved to 3 PM, please confirm.", 0.4797),
]
V3_ARTIFACT = Path(__file__).resolve().parents[2] / "models" / "trained" / "trustshield_text_v3.joblib"
pytestmark = pytest.mark.skipif(
    not V3_ARTIFACT.is_file(),
    reason="MODEL_ARTIFACT_MISSING: trustshield_text_v3.joblib is required for fixed-probability regression checks; no model was downloaded.",
)


@pytest.fixture(scope="module")
def classifier():
    model = TextClassifier("trustshield_text_v3")
    assert model.available, model.reason
    return model


@pytest.mark.parametrize("text,expected", FIXTURES)
def test_fixed_fixture_probabilities(classifier, text, expected):
    assert classifier.predict(text).p_malicious == pytest.approx(expected, abs=2e-4)


def test_metadata_matches_release(classifier):
    assert classifier.metadata["threshold"] == 0.16
    assert classifier.metadata["sklearn_version"] == "1.9.1"
    assert classifier.metadata["model_name"] == "trustshield_text_v3"
