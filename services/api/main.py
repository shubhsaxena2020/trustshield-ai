"""TrustShield AI API. Run: uvicorn services.api.main:app  (configuration comes from the environment; see .env.example)"""
from __future__ import annotations

import asyncio
import json
import logging
import math
import os
import sys
import threading
import time
from contextlib import asynccontextmanager
from urllib.parse import urlsplit

from fastapi import APIRouter, Depends, FastAPI, File, HTTPException, Request, Response, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

from services.analysis import pipeline as analysis_pipeline
from services.analysis.extractors.image_validation import ImageRejected, max_upload_size
from services.analysis.extractors.ocr import get_ocr_provider
from services.analysis.fusion.result import SCHEMA_VERSION, AnalysisResult, ModuleResult
from services.analysis.normalization.text import InvalidInput
from services.analysis.pipeline import analyze_image, analyze_qr, analyze_text, analyze_url, merge_sandbox_result
from services.analysis.url_analysis.ssrf import UnsafeURL, validate_public_url
from services.analysis.web_sandbox.client import run_sandbox
from services.analysis.web_sandbox.policy import SandboxPolicy, feature_enabled

from .security import BodyLimitMiddleware, ObservabilityMiddleware, RateLimiter, ResultStore, client_key, event_log
from .settings import Settings
from .startup import startup_problems

_SANDBOX_BUCKETS: dict[str, tuple[float, float]] = {}
_SANDBOX_RATE = 5.0
_SANDBOX_MAX_PENDING = 8  # accepted-but-unfinished jobs; bounds threads and queued subprocesses
_SANDBOX_PENDING = threading.BoundedSemaphore(_SANDBOX_MAX_PENDING)
_SANDBOX_WINDOW = 60.0

router = APIRouter()


class TextIn(BaseModel):
    text: str = Field(max_length=20_000)


class UrlIn(BaseModel):
    url: str = Field(max_length=2048)
    sandbox: bool = False


# ---- per-request helpers -------------------------------------------------------------------------------------------

def _state(request: Request):
    return request.app.state


def _client(request: Request) -> str:
    hops = _state(request).settings.trusted_proxy_hops
    return client_key((request.client.host, request.client.port) if request.client else None,
                      request.headers.get("x-forwarded-for"), hops)


def _limit(limiter_name: str):
    def dependency(request: Request) -> None:
        allowed, retry = getattr(_state(request), limiter_name).allow(_client(request))
        if not allowed:
            raise HTTPException(429, "Too many requests; please slow down and retry shortly",
                                headers={"Retry-After": str(max(1, math.ceil(retry)))})
    return dependency


limit_light = _limit("limit_light")
limit_heavy = _limit("limit_heavy")


def _publish(request: Request, response: Response, result: AnalysisResult, *, retain: bool = False) -> AnalysisResult:
    """Attach the analysis id, log module problems (never content), and store only if policy requires it."""
    response.headers["X-Analysis-ID"] = result.analysis_id
    request_id = getattr(request.state, "request_id", None)
    for name, module in result.modules.items():
        status = module.status
        if status in {"failed", "timeout"} or (name in {"ml", "ocr", "qr"} and status == "unavailable"):
            event = "ml_unavailable" if name == "ml" else "ocr_failure" if name == "ocr" else "module_failure"
            event_log.warning(json.dumps({"event": event, "request_id": request_id, "analysis_id": result.analysis_id,
                                          "module": name, "status": status}))
    _state(request).store.put(result, retain=retain)
    return result


async def run_heavy(request: Request, func, *args):
    """CPU-heavy work (OCR/QR): bounded concurrency, queue timeout, overall timeout, off the event loop."""
    state = _state(request)
    try:
        await asyncio.wait_for(state.heavy_gate.acquire(), state.settings.heavy_queue_timeout_s)
    except asyncio.TimeoutError:
        raise HTTPException(503, "The server is busy analysing other uploads; please retry shortly", headers={"Retry-After": "5"})
    try:
        return await asyncio.wait_for(run_in_threadpool(func, *args), state.settings.request_timeout_s)
    except asyncio.TimeoutError:
        raise HTTPException(504, "The analysis took too long and was stopped")
    finally:
        state.heavy_gate.release()


# ---- health --------------------------------------------------------------------------------------------------------

@router.get("/api/v1/health")
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


@router.get("/healthz", include_in_schema=False)
@router.get("/api/v1/healthz", include_in_schema=False)
def liveness():
    return {"status": "alive"}


@router.get("/readyz", include_in_schema=False)
@router.get("/api/v1/readyz", include_in_schema=False)
def readiness(response: Response):
    reasons: list[str] = []
    classifier = analysis_pipeline._text_classifier()
    if not classifier.available:
        reasons.append("ml_unavailable")
    if not get_ocr_provider().is_available():
        reasons.append("ocr_unavailable")
    if reasons:
        response.status_code = 503
    return {"ready": not reasons, "reasons": reasons}


# ---- analysis endpoints --------------------------------------------------------------------------------------------

@router.post("/api/v1/analyze/text", response_model=AnalysisResult, dependencies=[Depends(limit_light)])
def analyze_text_endpoint(body: TextIn, request: Request, response: Response):
    try:
        return _publish(request, response, analyze_text(body.text))
    except InvalidInput as e:
        raise HTTPException(422, str(e))


def _sandbox_rate_allowed(key: str) -> bool:
    now = time.monotonic()
    if len(_SANDBOX_BUCKETS) > 5000:
        for stale in [k for k, (t, _) in _SANDBOX_BUCKETS.items() if now - t > _SANDBOX_WINDOW]:
            del _SANDBOX_BUCKETS[stale]
    started, tokens = _SANDBOX_BUCKETS.get(key, (now, _SANDBOX_RATE))
    elapsed = max(0.0, now - started)
    tokens = min(_SANDBOX_RATE, tokens + elapsed * (_SANDBOX_RATE / _SANDBOX_WINDOW))
    if tokens < 1.0:
        _SANDBOX_BUCKETS[key] = (now, tokens)
        return False
    _SANDBOX_BUCKETS[key] = (now, tokens - 1.0)
    return True


async def _finish_sandbox_job(analysis_id: str, url: str, store: ResultStore) -> None:
    try:
        outcome = await run_sandbox(url)
        current = store.get(analysis_id)
        if current is not None:
            store.put(merge_sandbox_result(current, outcome), retain=True)
    except Exception as exc:  # background failures become structured module failures
        event_log.warning(json.dumps({"event": "sandbox_failure", "analysis_id": analysis_id, "error_type": type(exc).__name__}))
        current = store.get(analysis_id)
        if current is not None:
            store.put(merge_sandbox_result(current, {"status": "failed", "detail": "sandbox worker failed"}), retain=True)


def _start_sandbox_job(analysis_id: str, url: str, store: ResultStore) -> None:
    def run() -> None:
        try:
            asyncio.run(_finish_sandbox_job(analysis_id, url, store))
        finally:
            _SANDBOX_PENDING.release()

    threading.Thread(target=run, name="trustshield-sandbox-job", daemon=True).start()


@router.post("/api/v1/analyze/url", response_model=AnalysisResult, dependencies=[Depends(limit_light)])
async def analyze_url_endpoint(body: UrlIn, request: Request, response: Response):
    store = _state(request).store
    static = analyze_url(body.url)
    if not body.sandbox:
        return _publish(request, response, static)
    if not feature_enabled():
        modules = dict(static.modules)
        modules["web_sandbox"] = ModuleResult(status="unavailable", detail="Disabled by ENABLE_WEB_SANDBOX=false")
        return _publish(request, response, static.model_copy(update={"modules": modules}))
    if (static.modules.get("url_static", ModuleResult(status="failed")).status != "completed" or
            static.modules.get("url_static").detail):
        modules = dict(static.modules)
        modules["web_sandbox"] = ModuleResult(status="unavailable", detail="URL is malformed or unsupported")
        return _publish(request, response, static.model_copy(update={"modules": modules}))
    try:
        validate_public_url(body.url)
        port = urlsplit(body.url).port
    except (UnsafeURL, ValueError) as exc:
        raise HTTPException(422, f"URL not eligible for sandbox: {exc}")
    if port is not None and port not in SandboxPolicy.from_env().allowed_ports:
        raise HTTPException(422, f"URL not eligible for sandbox: port {port} is not allowed")
    if not _sandbox_rate_allowed(_client(request)):
        raise HTTPException(429, "sandbox rate limit exceeded; try again later")
    if not _SANDBOX_PENDING.acquire(blocking=False):
        raise HTTPException(429, "sandbox is busy; try again later")
    modules = dict(static.modules)
    modules["web_sandbox"] = ModuleResult(status="sandboxing", detail="Sandbox worker is running")
    limitations = [item for item in static.limitations if "not inspected in an isolated browser" not in item]
    limitations.append("The isolated browser inspection is still in progress.")
    in_progress = static.model_copy(update={"status": "sandboxing", "modules": modules, "limitations": limitations})
    _publish(request, response, in_progress, retain=True)
    try:
        _start_sandbox_job(in_progress.analysis_id, body.url, store)
    except Exception:
        _SANDBOX_PENDING.release()
        raise
    response.status_code = 202
    return in_progress


def _image_error(exc: ImageRejected) -> HTTPException:
    return HTTPException(status_code=exc.status_code, detail=exc.message)


async def _upload_endpoint(request: Request, response: Response, file: UploadFile, func) -> AnalysisResult:
    try:
        data = await file.read(max_upload_size() + 1)  # the extra byte detects oversize input without unbounded reads
        return _publish(request, response, await run_heavy(request, func, data, file.content_type, file.filename))
    except ImageRejected as exc:
        raise _image_error(exc)
    except InvalidInput as exc:
        raise HTTPException(422, str(exc))
    finally:
        await file.close()


@router.post("/api/v1/analyze/image", response_model=AnalysisResult, dependencies=[Depends(limit_heavy)])
async def analyze_image_endpoint(request: Request, response: Response, file: UploadFile = File(...)):
    return await _upload_endpoint(request, response, file, analyze_image)


@router.post("/api/v1/analyze/qr", response_model=AnalysisResult, dependencies=[Depends(limit_heavy)])
async def analyze_qr_endpoint(request: Request, response: Response, file: UploadFile = File(...)):
    return await _upload_endpoint(request, response, file, analyze_qr)


@router.get("/api/v1/analysis/{analysis_id}", response_model=AnalysisResult, dependencies=[Depends(limit_light)])
def get_analysis(analysis_id: str, request: Request):
    result = _state(request).store.get(analysis_id)
    if result is None:
        raise HTTPException(404, "analysis not found (results are short-lived and not kept after a restart)")
    return result


# ---- application factory -------------------------------------------------------------------------------------------

def _configure_logging() -> None:
    for name in ("trustshield.access", "trustshield.events"):
        logger = logging.getLogger(name)
        if not logger.handlers:
            handler = logging.StreamHandler(sys.stdout)
            handler.setFormatter(logging.Formatter("%(message)s"))
            logger.addHandler(handler)
            logger.setLevel(logging.INFO)
            logger.propagate = False


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    _configure_logging()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if settings.strict:
            problems = startup_problems(settings)
            if problems:
                for problem in problems:
                    event_log.error(json.dumps({"event": "startup_check_failed", "problem": problem}))
                raise RuntimeError("startup checks failed: " + "; ".join(problems))
        try:  # load the model at startup so the first request is not slow, and record if it is unavailable
            classifier = analysis_pipeline._text_classifier()
            if not classifier.available:
                event_log.error(json.dumps({"event": "ml_unavailable", "reason": getattr(classifier, "reason", "unavailable")}))
        except Exception as exc:
            event_log.error(json.dumps({"event": "ml_load_error", "error_type": type(exc).__name__}))
        yield

    app = FastAPI(title="TrustShield AI", version="0.1.0", lifespan=lifespan,
                  docs_url="/docs" if settings.docs_enabled else None, redoc_url=None,
                  openapi_url="/openapi.json" if settings.docs_enabled else None)
    app.state.settings = settings
    app.state.store = ResultStore(settings)
    app.state.limit_light = RateLimiter(settings.rate_light_per_min)
    app.state.limit_heavy = RateLimiter(settings.rate_heavy_per_min)
    app.state.heavy_gate = asyncio.Semaphore(settings.heavy_concurrency)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request: Request, exc: RequestValidationError):
        # Default FastAPI echoes the submitted input back; return only where and why.
        details = [{"loc": list(item.get("loc", [])), "msg": item.get("msg", "invalid"), "type": item.get("type", "invalid")}
                   for item in exc.errors()]
        return JSONResponse(status_code=422, content={"detail": details})

    app.include_router(router)
    if os.getenv("TRUSTSHIELD_DEBUG_PROXY", "").strip() == "1":
        # Temporary observation window only (scripts/observe_proxy_chain.py, scripts/stress_remote.py): peer address, forwarding
        # headers and container memory counters. Never enabled in production (the production preflight fails if it is set).
        def cgroup(name: str):
            try:
                with open(f"/sys/fs/cgroup/{name}", encoding="utf-8") as handle:
                    return handle.read().strip()
            except OSError:
                return None

        @app.get("/api/v1/debug/proxy", include_in_schema=False)
        def debug_proxy(request: Request):
            return {"peer": request.client.host if request.client else None,
                    "x_forwarded_for": request.headers.get("x-forwarded-for", ""),
                    "x_forwarded_proto": request.headers.get("x-forwarded-proto", ""),
                    "memory": {"current": cgroup("memory.current"), "peak": cgroup("memory.peak"), "max": cgroup("memory.max")}}

    app.add_middleware(BodyLimitMiddleware, max_bytes=settings.max_body_bytes)
    app.add_middleware(ObservabilityMiddleware, settings=settings)
    app.add_middleware(CORSMiddleware, allow_origins=list(settings.cors_origins), allow_methods=["GET", "POST", "OPTIONS"],
                       allow_headers=["Content-Type", "X-Request-ID"], expose_headers=["X-Request-ID", "X-Analysis-ID", "Retry-After"],
                       max_age=600)
    return app


app = create_app()
