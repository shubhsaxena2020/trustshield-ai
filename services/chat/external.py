"""External-intelligence provider interface (FOUNDATION ONLY: nothing here performs a network request).

Every provider must return a ProviderResult with status, source, timestamp, evidence, confidence, privacy implications and an
error state. Nothing a provider returns becomes trusted automatically: results are ordinary Evidence objects that go through the
same fusion, and a provider that did not run is reported as not used in the analysis coverage.

Privacy modes: LOCAL_ONLY never sends a URL anywhere. ENHANCED requires an explicit, informed user opt-in per request and is not
enabled in any deployment today (docs/EXTERNAL_INTELLIGENCE_ROADMAP.md).
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Literal

from services.analysis.evidence.models import Evidence


class PrivacyMode(str, Enum):
    LOCAL_ONLY = "local_only"
    ENHANCED = "enhanced"


ProviderStatus = Literal["completed", "disabled", "refused_privacy", "error", "timeout"]


@dataclass
class ProviderResult:
    source: str
    status: ProviderStatus
    timestamp: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    evidence: list[Evidence] = field(default_factory=list)
    confidence: float = 0.0
    privacy_implications: str = ""
    error: str | None = None


class ExternalIntelligenceProvider(ABC):
    name = "abstract"
    #: What leaves the machine if this provider runs (shown to the user before an ENHANCED check).
    privacy_implications = "Unknown."
    sends_url_to_third_party = True

    @abstractmethod
    def lookup(self, url: str) -> ProviderResult: ...


class DisabledProvider(ExternalIntelligenceProvider):
    """The only implementation shipped: reports that external intelligence did not run."""
    name = "disabled"
    sends_url_to_third_party = False
    privacy_implications = "No data leaves TrustShield."

    def lookup(self, url: str) -> ProviderResult:
        return ProviderResult(source=self.name, status="disabled", privacy_implications=self.privacy_implications,
                              error="External intelligence is not enabled in this deployment.")


def run_providers(providers: list[ExternalIntelligenceProvider], url: str, mode: PrivacyMode, user_opted_in: bool) -> list[ProviderResult]:
    """Runs providers only when the privacy mode and an explicit opt-in allow it; otherwise reports why nothing ran."""
    results = []
    for provider in providers:
        if provider.sends_url_to_third_party and not (mode is PrivacyMode.ENHANCED and user_opted_in):
            results.append(ProviderResult(source=provider.name, status="refused_privacy", privacy_implications=provider.privacy_implications,
                                          error="Not run: this provider would disclose the URL to a third party and no explicit opt-in was given."))
            continue
        try:
            results.append(provider.lookup(url))
        except Exception as exc:
            results.append(ProviderResult(source=provider.name, status="error", error=type(exc).__name__))
    return results
