"""Test-suite defaults. The shared TestClient identity would otherwise trip the production rate limits.

Rate-limit behaviour is tested explicitly with dedicated Settings in tests/integration/test_production_hardening.py.
"""
import os

os.environ.setdefault("TRUSTSHIELD_RATE_LIGHT_PER_MIN", "100000")
os.environ.setdefault("TRUSTSHIELD_RATE_HEAVY_PER_MIN", "100000")
