"""TrustShield AI API. Run: uvicorn services.api.main:app --reload"""
from __future__ import annotations

import asyncio
import threading
import time
from collections import OrderedDict

from fastapi import FastAPI, File, HTTPException, Request, Response, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from services.analysis.fusion.result import SCHEMA_VERSION, AnalysisResult, ModuleResult
from services.analysis.normalization.text import InvalidInput
from services.analysis.extractors.image_validation import ImageRejected, max_upload_size
from services.analysis.extractors.ocr import get_ocr_provider
from services.analysis.pipeline import analyze_image, analyze_qr, analyze_text, analyze_url, merge_sandbox_result
from services.analysis import pipeline as analysis_pipeline
from services.analysis.web_sandbox.client import run_sandbox
from services.analysis.url_analysis.ssrf import UnsafeURL, validate_public_url
from services.analysis.web_sandbox.policy import feature_enabled

app = FastAPI(title="TrustShield AI", version="0.1.0")
app.add_middleware(CORSMiddleware, allow_origins=["http://localhost:3000"], allow_methods=["GET", "POST"], allow_headers=["*"])

# MVP result store: bounded in-memory AnalysisResult objects retain extracted text but never raw image bytes; all entries are lost on restart.
_STORE: "OrderedDict[str, AnalysisResult]" = OrderedDict()
_STORE_MAX = 200
_SANDBOX_BUCKETS: dict[str, tuple[float, float]] = {}
_SANDBOX_RATE = 5.0
_SANDBOX_WINDOW = 60.0


class TextIn(BaseModel):
    text: str = Field(max_length=20_000)


class UrlIn(BaseModel):
    url: str = Field(max_length=2048)
    sandbox: bool = False


def _remember(r: AnalysisResult) -> AnalysisResult:
    _STORE[r.analysis_id] = r
    while len(_STORE) > _STORE_MAX:
        _STORE.popitem(last=False)
    return r


@app.get("/api/v1/health")
def health():
    ocr = get_ocr_provider()
    if __import__("os").getenv("TRUSTSHIELD_ML", "on").strip().lower() == "off":
        ml = {"available": False, "reason": "disabled_by_env", "model_name": None, "model_version": None}
    else:
        classifier = analysis_pipeline._text_classifier()
        ml = {"available": classifier.available, "model_name": classifier.model_name,
              "model_version": classifier.metadata.get("model_version") if classifier.available else None}
        if not classifier.available:
            ml["reason"] = getattr(classifier, "reason", "unavailable")
    return {"status": "ok", "schema_version": SCHEMA_VERSION, "modules": {
        "text": True, "url_static": True,
        "ml": ml,
        "ocr": {"available": ocr.is_available(), "provider": ocr.name},
        "qr": True, "web_sandbox": feature_enabled(),
    }}


@app.post("/api/v1/analyze/text", response_model=AnalysisResult)
def analyze_text_endpoint(body: TextIn):
    try:
        return _remember(analyze_text(body.text))
    except InvalidInput as e:
        raise HTTPException(422, str(e))


def _sandbox_rate_allowed(client_key: str) -> bool:
    now = time.monotonic()
    if len(_SANDBOX_BUCKETS) > 5000:
        for key in [k for k, (t, _) in _SANDBOX_BUCKETS.items() if now - t > _SANDBOX_WINDOW]:
            del _SANDBOX_BUCKETS[key]
    started, tokens = _SANDBOX_BUCKETS.get(client_key, (now, _SANDBOX_RATE))
    elapsed = max(0.0, now - started)
    tokens = min(_SANDBOX_RATE, tokens + elapsed * (_SANDBOX_RATE / _SANDBOX_WINDOW))
    if tokens < 1.0:
        _SANDBOX_BUCKETS[client_key] = (now, tokens)
        return False
    _SANDBOX_BUCKETS[client_key] = (now, tokens - 1.0)
    return True


async def _finish_sandbox_job(analysis_id: str, url: str) -> None:
    try:
        outcome = await run_sandbox(url)
        current = _STORE.get(analysis_id)
        if current is not None:
            _remember(merge_sandbox_result(current, outcome))
    except Exception as exc:  # background failures become structured module failures
        current = _STORE.get(analysis_id)
        if current is not None:
            _remember(merge_sandbox_result(current, {"status": "failed", "detail": str(exc)}))


def _start_sandbox_job(analysis_id: str, url: str) -> None:
    threading.Thread(
        target=lambda: asyncio.run(_finish_sandbox_job(analysis_id, url)),
        name="trustshield-sandbox-job", daemon=True,
    ).start()


@app.post("/api/v1/analyze/url", response_model=AnalysisResult)
async def analyze_url_endpoint(body: UrlIn, request: Request, response: Response):
    static = analyze_url(body.url)
    if not body.sandbox:
        return _remember(static)
    if not feature_enabled():
        modules = dict(static.modules)
        modules["web_sandbox"] = ModuleResult(status="unavailable", detail="Disabled by ENABLE_WEB_SANDBOX=false")
        result = static.model_copy(update={"modules": modules})
        return _remember(result)
    if (static.modules.get("url_static", ModuleResult(status="failed")).status != "completed" or
            static.modules.get("url_static").detail):
        modules = dict(static.modules)
        modules["web_sandbox"] = ModuleResult(status="unavailable", detail="URL is malformed or unsupported")
        return _remember(static.model_copy(update={"modules": modules}))
    try:
        validate_public_url(body.url)
    except UnsafeURL as exc:
        raise HTTPException(422, f"URL not eligible for sandbox: {exc}")
    client_key = request.client.host if request.client else "unknown"
    if not _sandbox_rate_allowed(client_key):
        raise HTTPException(429, "sandbox rate limit exceeded; try again later")
    modules = dict(static.modules)
    modules["web_sandbox"] = ModuleResult(status="sandboxing", detail="Sandbox worker is running")
    limitations = [item for item in static.limitations if "not inspected in an isolated browser" not in item]
    limitations.append("The isolated browser inspection is still in progress.")
    in_progress = static.model_copy(update={"status": "sandboxing", "modules": modules, "limitations": limitations})
    _remember(in_progress)
    _start_sandbox_job(in_progress.analysis_id, body.url)
    response.status_code = 202
    return in_progress


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
