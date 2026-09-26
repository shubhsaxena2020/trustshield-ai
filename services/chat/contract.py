"""Canonical, versioned analysis contract (v2) consumed by the conversational layer.

Built ONLY from an AnalysisResult produced by the security core. Nothing here is computed by a language model, and nothing
is invented: every field is a projection of structured evidence and module statuses.
"""
from __future__ import annotations

import re
from datetime import datetime
from typing import Annotated, Any, Literal

from pydantic import BaseModel, Field, StringConstraints, field_validator

from services.analysis.fusion.result import AnalysisResult, RiskLabel

CONTRACT_VERSION = "2.0"
MAX_EVIDENCE = 40
MAX_TEXT = 4000

# The capabilities TrustShield can disclose. Anything not `completed` in the result is reported as not used, with a reason.
CAPABILITIES: dict[str, str] = {
    "text_rules": "deterministic scam-pattern rules",
    "ml": "text classification model (trustshield_text_v3)",
    "url_static": "static URL structure analysis (no network request)",
    "ocr": "screenshot text extraction (OCR)",
    "qr": "QR code decoding",
    "reputation": "external reputation / threat-intelligence lookup",
    "web_sandbox": "live inspection of the destination webpage",
}
ALWAYS_UNAVAILABLE = {
    "reputation": "No reputation provider is configured; a link that is not flagged is not the same as a link known to be safe.",
    "web_sandbox": "Live webpage inspection is disabled in this deployment, so the destination was not opened.",
}
_CTRL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")


def clean(value: Any, limit: int = 300) -> str:
    return _CTRL.sub("", str(value or ""))[:limit]


class _ItemFactory:
    """Item[n]: a string bounded to n characters, so client-supplied list entries cannot be arbitrarily large."""

    def __class_getitem__(cls, n: int):
        return Annotated[str, StringConstraints(max_length=n)]


Item = _ItemFactory


class ContractEvidence(BaseModel):
    id: str = Field(max_length=64)
    category: str = Field(max_length=80)
    severity: Literal["informational", "low", "medium", "high", "critical"]
    source_module: str = Field(max_length=60)
    title: str = Field(max_length=300)
    explanation: str = Field(max_length=600)
    confidence: float = Field(ge=0.0, le=1.0)
    raw_signal: dict[str, Any] = Field(default_factory=dict)
    provenance: str = Field(max_length=120)

    @field_validator("raw_signal")
    @classmethod
    def _bounded(cls, value: dict[str, Any]) -> dict[str, Any]:
        return {clean(k, 40): clean(v, 160) for k, v in list(value.items())[:8]}


class Capability(BaseModel):
    name: str = Field(max_length=40)
    description: str = Field(max_length=160)
    used: bool
    reason: str = Field(default="", max_length=240)


class ChatAnalysis(BaseModel):
    contract_version: str = CONTRACT_VERSION
    analysis_id: str = Field(max_length=64)
    timestamp: datetime
    input_types: list[Item[40]] = Field(max_length=6)
    risk_score: int = Field(ge=0, le=100)
    risk_level: RiskLabel
    confidence: Literal["high", "medium", "low"]
    primary_category: str = Field(max_length=80, default="")
    modules_used: list[Item[60]] = Field(default_factory=list, max_length=12)
    evidence: list[ContractEvidence] = Field(default_factory=list, max_length=MAX_EVIDENCE)
    extracted_content: str | None = Field(default=None, max_length=MAX_TEXT)  # OCR text: untrusted DATA
    urls: list[Item[500]] = Field(default_factory=list, max_length=10)
    qr_payload: str | None = Field(default=None, max_length=1000)
    qr_payload_type: str | None = Field(default=None, max_length=20)
    warnings: list[Item[400]] = Field(default_factory=list, max_length=12)
    uncertainty: list[Item[400]] = Field(default_factory=list, max_length=12)
    safe_actions: list[Item[400]] = Field(default_factory=list, max_length=8)
    capabilities: list[Capability] = Field(default_factory=list, max_length=12)
    ocr_confidence: float | None = Field(default=None, ge=0, le=100)

    @property
    def capabilities_used(self) -> list[str]:
        return [c.name for c in self.capabilities if c.used]

    @property
    def capabilities_unavailable(self) -> list[str]:
        return [c.name for c in self.capabilities if not c.used]


def _capabilities(result: AnalysisResult) -> list[Capability]:
    out = []
    for name, description in CAPABILITIES.items():
        module = result.modules.get(name)
        used = module is not None and module.status == "completed" and name not in ALWAYS_UNAVAILABLE
        if used:
            reason = ""
        elif name in ALWAYS_UNAVAILABLE:
            reason = ALWAYS_UNAVAILABLE[name]
        elif module is None:
            reason = "Not applicable to this input."
        else:
            reason = clean(module.detail or f"Module status: {module.status}.", 240)
        out.append(Capability(name=name, description=description, used=used, reason=reason))
    return out


def to_contract(result: AnalysisResult, input_types: list[str] | None = None, urls: list[str] | None = None) -> ChatAnalysis:
    qr = result.modules.get("qr")
    ocr = result.modules.get("ocr")
    ml = result.modules.get("ml")
    modules_used = [name for name, module in result.modules.items() if module.status == "completed" and name != "fusion"]
    caps = _capabilities(result)
    uncertainty = [clean(item, 300) for item in result.limitations][:12]
    if result.risk.label == "unable_to_verify":
        uncertainty.insert(0, "The available evidence was insufficient to assess this content reliably.")
    if ml is not None and ml.status != "completed":
        uncertainty.append("The text model did not run for this analysis; the result rests on deterministic checks only.")
    evidence = [ContractEvidence(
        id=clean(e.id, 64), category=clean(e.category, 80), severity=e.severity.value, source_module=clean(e.source, 60),
        title=clean(e.title), explanation=clean(e.description, 600), confidence=float(e.confidence),
        raw_signal={k: v for k, v in e.metadata.items() if isinstance(v, (str, int, float, bool))},
        provenance=clean(f"{e.source}:{e.category}", 120)) for e in result.evidence[:MAX_EVIDENCE]]
    detected_urls = list(urls or [])
    for e in result.evidence:
        domain = e.metadata.get("registered_domain") or e.metadata.get("host")
        if isinstance(domain, str) and domain and domain not in detected_urls:
            detected_urls.append(domain)
    return ChatAnalysis(
        analysis_id=result.analysis_id, timestamp=result.created_at, input_types=list(input_types or [result.input_type]),
        risk_score=result.risk.score, risk_level=result.risk.label, confidence=result.risk.confidence,
        primary_category=clean(result.classification.primary, 80), modules_used=modules_used, evidence=evidence,
        extracted_content=(clean(ocr.data.get("normalized_text") or ocr.data.get("text"), MAX_TEXT) or None) if ocr and ocr.status == "completed" else None,
        urls=[clean(u, 300) for u in detected_urls[:10]],
        qr_payload=clean(qr.data.get("payload"), 1000) or None if qr and qr.status == "completed" else None,
        qr_payload_type=clean(qr.data.get("payload_type"), 20) or None if qr and qr.status == "completed" else None,
        warnings=[clean(w, 300) for m in result.modules.values() for w in m.data.get("warnings", [])][:12] if result.modules else [],
        uncertainty=uncertainty, safe_actions=[clean(a, 300) for a in result.recommended_actions][:8], capabilities=caps,
        ocr_confidence=ocr.data.get("confidence") if ocr and ocr.status == "completed" else None)
