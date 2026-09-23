"""TrustShield AI API. Run: uvicorn services.api.main:app --reload"""
from __future__ import annotations

from collections import OrderedDict

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from services.analysis.fusion.result import SCHEMA_VERSION, AnalysisResult
from services.analysis.normalization.text import InvalidInput
from services.analysis.pipeline import analyze_text, analyze_url

app = FastAPI(title="TrustShield AI", version="0.1.0")
app.add_middleware(CORSMiddleware, allow_origins=["http://localhost:3000"], allow_methods=["GET", "POST"], allow_headers=["*"])

# MVP in-memory store (bounded). SQLite persistence of metadata only is planned; raw content is never stored.
_STORE: "OrderedDict[str, AnalysisResult]" = OrderedDict()
_STORE_MAX = 200


class TextIn(BaseModel):
    text: str = Field(max_length=20_000)


class UrlIn(BaseModel):
    url: str = Field(max_length=2048)


def _remember(r: AnalysisResult) -> AnalysisResult:
    _STORE[r.analysis_id] = r
    while len(_STORE) > _STORE_MAX:
        _STORE.popitem(last=False)
    return r


@app.get("/api/v1/health")
def health():
    return {"status": "ok", "schema_version": SCHEMA_VERSION, "modules": {"text": True, "url_static": True, "ml": False, "ocr": False, "qr": False, "web_sandbox": False}}


@app.post("/api/v1/analyze/text", response_model=AnalysisResult)
def analyze_text_endpoint(body: TextIn):
    try:
        return _remember(analyze_text(body.text))
    except InvalidInput as e:
        raise HTTPException(422, str(e))


@app.post("/api/v1/analyze/url", response_model=AnalysisResult)
def analyze_url_endpoint(body: UrlIn):
    return _remember(analyze_url(body.url))


@app.get("/api/v1/analysis/{analysis_id}", response_model=AnalysisResult)
def get_analysis(analysis_id: str):
    if analysis_id not in _STORE:
        raise HTTPException(404, "analysis not found")
    return _STORE[analysis_id]
