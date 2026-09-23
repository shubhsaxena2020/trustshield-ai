"""TrustShield AI API. Run: uvicorn services.api.main:app --reload"""
from __future__ import annotations

from collections import OrderedDict

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from services.analysis.fusion.result import SCHEMA_VERSION, AnalysisResult
from services.analysis.normalization.text import InvalidInput
from services.analysis.extractors.image_validation import ImageRejected, max_upload_size
from services.analysis.extractors.ocr import get_ocr_provider
from services.analysis.pipeline import analyze_image, analyze_qr, analyze_text, analyze_url

app = FastAPI(title="TrustShield AI", version="0.1.0")
app.add_middleware(CORSMiddleware, allow_origins=["http://localhost:3000"], allow_methods=["GET", "POST"], allow_headers=["*"])

# MVP result store: bounded in-memory AnalysisResult objects retain extracted text but never raw image bytes; all entries are lost on restart.
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
    ocr = get_ocr_provider()
    return {"status": "ok", "schema_version": SCHEMA_VERSION, "modules": {
        "text": True, "url_static": True, "ml": False,
        "ocr": {"available": ocr.is_available(), "provider": ocr.name},
        "qr": True, "web_sandbox": False,
    }}


@app.post("/api/v1/analyze/text", response_model=AnalysisResult)
def analyze_text_endpoint(body: TextIn):
    try:
        return _remember(analyze_text(body.text))
    except InvalidInput as e:
        raise HTTPException(422, str(e))


@app.post("/api/v1/analyze/url", response_model=AnalysisResult)
def analyze_url_endpoint(body: UrlIn):
    return _remember(analyze_url(body.url))


def _read_upload(upload: UploadFile) -> bytes:
    # The extra byte detects oversize input without unbounded reads or storing it.
    return upload.file.read(max_upload_size() + 1)


def _image_error(exc: ImageRejected) -> HTTPException:
    return HTTPException(status_code=exc.status_code, detail=exc.message)


@app.post("/api/v1/analyze/image", response_model=AnalysisResult)
async def analyze_image_endpoint(file: UploadFile = File(...)):
    try:
        return _remember(analyze_image(_read_upload(file), file.content_type, file.filename))
    except ImageRejected as exc:
        raise _image_error(exc)
    except InvalidInput as exc:
        raise HTTPException(422, str(exc))
    finally:
        await file.close()


@app.post("/api/v1/analyze/qr", response_model=AnalysisResult)
async def analyze_qr_endpoint(file: UploadFile = File(...)):
    try:
        return _remember(analyze_qr(_read_upload(file), file.content_type, file.filename))
    except ImageRejected as exc:
        raise _image_error(exc)
    except InvalidInput as exc:
        raise HTTPException(422, str(exc))
    finally:
        await file.close()


@app.get("/api/v1/analysis/{analysis_id}", response_model=AnalysisResult)
def get_analysis(analysis_id: str):
    if analysis_id not in _STORE:
        raise HTTPException(404, "analysis not found")
    return _STORE[analysis_id]
