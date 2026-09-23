"""Standardized evidence object. Every detector emits these; fusion and UI consume only these."""
from __future__ import annotations

import uuid
from datetime import datetime, timezone
from enum import Enum
from typing import Any

from pydantic import BaseModel, Field


class Severity(str, Enum):
    informational = "informational"
    low = "low"
    medium = "medium"
    high = "high"
    critical = "critical"


class Evidence(BaseModel):
    id: str = Field(default_factory=lambda: uuid.uuid4().hex[:12])
    source: str  # e.g. text_rules, url_static, ml, web_sandbox
    category: str  # e.g. urgency, domain_mismatch, credential_request
    severity: Severity
    confidence: float = Field(ge=0.0, le=1.0)
    title: str
    description: str
    metadata: dict[str, Any] = Field(default_factory=dict)
    timestamp: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
