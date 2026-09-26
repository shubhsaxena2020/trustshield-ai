"""Unified, versioned analysis result returned by every endpoint."""
from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any, Literal

from pydantic import BaseModel, Field

from services.analysis.evidence.models import Evidence

SCHEMA_VERSION = "1.0"

RiskLabel = Literal["low_risk", "caution", "suspicious", "high_risk", "unable_to_verify"]
ModuleState = Literal["completed", "not_run", "unavailable", "failed", "timeout", "queued", "sandboxing"]


class Risk(BaseModel):
    score: int = Field(ge=0, le=100)
    label: RiskLabel
    confidence: Literal["high", "medium", "low"]


class Classification(BaseModel):
    primary: str
    secondary: list[str] = Field(default_factory=list)


class ModuleResult(BaseModel):
    status: ModuleState
    detail: str = ""
    data: dict[str, Any] = Field(default_factory=dict)


class AnalysisResult(BaseModel):
    schema_version: str = SCHEMA_VERSION
    analysis_id: str = Field(default_factory=lambda: uuid.uuid4().hex)
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    input_type: Literal["text", "url", "image", "qr"]
    status: Literal["completed", "partial", "failed", "queued", "sandboxing"]
    risk: Risk
    classification: Classification
    evidence: list[Evidence]
    modules: dict[str, ModuleResult]
    explanation: str
    recommended_actions: list[str]
    limitations: list[str]
