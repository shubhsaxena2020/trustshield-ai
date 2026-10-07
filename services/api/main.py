"""TrustShield AI API. Run: uvicorn services.api.main:app  (configuration comes from the environment; see .env.example)"""
from __future__ import annotations

import asyncio
import json
import time
import logging
import math
import os
import re
import sys
from contextlib import asynccontextmanager

from typing import Literal

from fastapi import APIRouter, Depends, FastAPI, File, Form, HTTPException, Request, Response, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

from services.analysis import pipeline as analysis_pipeline
from services.analysis.extractors.image_validation import ImageRejected, max_upload_size
from services.analysis.extractors.ocr import get_ocr_provider
from services.analysis.fusion.result import SCHEMA_VERSION, AnalysisResult, ModuleResult
from services.analysis.normalization.text import InvalidInput
from services.analysis.pipeline import analyze_image, analyze_qr, analyze_text, analyze_url

from services.chat.contract import ChatAnalysis, to_contract
from services.chat import agent
from services.chat.explain import DeterministicExplanationProvider, Reply, followup_intent, get_provider

_log = logging.getLogger("trustshield.api")
from services.analysis.normalization.text import extract_urls
from services.chat.intent import classify_intent, has_action_or_object_risk, has_suspicious_signal, is_question_or_task
from services.chat.orchestrator import analyze_turn
from services.chat.router import MAX_MESSAGE_CHARS, route
from services.chat.signing import sign, verify

from .security import BodyLimitMiddleware, ObservabilityMiddleware, RateLimiter, ResultStore, client_key, event_log
from .settings import Settings
from .startup import startup_problems

router = APIRouter()


class TextIn(BaseModel):
    text: str = Field(max_length=20_000)


class UrlIn(BaseModel):
    url: str = Field(max_length=2048)


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
        "qr": True,
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


@router.post("/api/v1/analyze/url", response_model=AnalysisResult, dependencies=[Depends(limit_light)])
def analyze_url_endpoint(body: UrlIn, request: Request, response: Response):
    return _publish(request, response, analyze_url(body.url))


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


# ---- conversational API (v1 endpoints above are unchanged) ----------------------------------------------------------------------

class Turn(BaseModel):
    role: Literal["user", "assistant"]
    text: str = Field(max_length=2000)


class ChatIn(BaseModel):
    message: str = Field(default="", max_length=MAX_MESSAGE_CHARS)
    context: ChatAnalysis | None = None  # the previous analysis, sent back by the browser (the server stores no conversation)
    context_signature: str = Field(default="", max_length=128)
    history: list[Turn] = Field(default_factory=list, max_length=6)


class ReplyOut(BaseModel):
    text: str
    cited_evidence: list[str] = Field(default_factory=list)
    provider: str
    grounded: bool = True


ResponseType = Literal["chat", "analysis", "follow_up", "education", "product_info"]


class ChatOut(BaseModel):
    kind: Literal["analysis", "followup", "chat"]  # kept for compatibility; response_type is the richer field
    response_type: ResponseType
    intent: dict
    route: dict = Field(default_factory=dict)
    reply: ReplyOut
    analysis: ChatAnalysis | None = None
    context_signature: str | None = None


def _reply_out(reply: Reply) -> ReplyOut:
    return ReplyOut(text=reply.text, cited_evidence=reply.cited_evidence[:10], provider=reply.provider, grounded=reply.grounded)


# Intents handled without ever touching the analysis pipeline: this is the fix for the "yo" -> "Low Risk 0/100" bug. Analysis
# always wins when there is a URL, an attachment, or unmatched content (see services/chat/intent.py's priority order).
_CHAT_ONLY_REPLY = {
    "greeting": ("chat", "reply_greeting"), "assistant_qa": ("chat", "reply_greeting"), "off_topic": ("chat", "reply_off_topic"),
    "general_cyber_qa": ("education", "reply_cyber_qa"), "product_qa": ("product_info", "reply_product_qa"),
    "general_chat": ("chat", "reply_chat"),
}



def _refine_with_model(intent_result, message: str, has_image: bool):
    """Model decides only the uncertain cases: plain text with soft risk words and no strong scam signal. Strong signals,
    links and attachments keep the rule decision. The model may move a question or task to chat, never a pasted message."""
    from services.chat.intent import has_suspicious_signal, has_transaction_signal, is_question_or_task
    if (intent_result.intent != "analyze_text" or has_image or intent_result.urls or has_suspicious_signal(message)
            or not agent.configured() or not is_question_or_task(message) or has_transaction_signal(message)):
        return intent_result
    verdict = agent.route_text(message)
    if verdict == "chat":
        from services.chat.intent import IntentResult
        return IntentResult("general_chat", "model routing: a question or task, not content to check")
    return intent_result

async def _agent_chat(request: Request, response: Response, message: str, history: list[Turn], intent_result) -> ChatOut | None:
    """Model-led chat turn. Returns None when the model is unavailable or a reply fails a check."""
    captured: dict = {}

    def scan(text: str) -> str:
        plan = route(text, False, False, followup_intent)
        try:
            result, types = analyze_turn(text, None, plan)
        except (InvalidInput, ImageRejected) as exc:
            return json.dumps({"error": str(exc)[:200]})
        analysis = to_contract(result, types, plan.urls)
        captured.update(result=result, plan=plan, analysis=analysis)
        return json.dumps(analysis.model_dump(mode="json"), default=str)[:6000]

    recent = [{"role": t.role, "text": t.text[:1500]} for t in history[-8:]]
    agent_reply = await run_in_threadpool(agent.run, message, recent, scan)
    if agent_reply is None:
        return None
    analysis = captured.get("analysis")
    if not agent.reply_is_acceptable(agent_reply.text, analysis.risk_score if analysis is not None else None):
        return None
    if analysis is not None:
        _publish(request, response, captured["result"])
        return ChatOut(kind="analysis", response_type="analysis", intent=intent_result.public(), route=captured["plan"].public(),
                       reply=ReplyOut(text=agent_reply.text, provider="nilabh", grounded=True), analysis=analysis,
                       context_signature=sign(analysis))
    response_type = _CHAT_ONLY_REPLY[intent_result.intent][0]
    return ChatOut(kind="chat", response_type=response_type, intent=intent_result.public(),
                   reply=ReplyOut(text=agent_reply.text, provider="nilabh", grounded=True))


def _sse(event: dict) -> str:
    return "data: " + json.dumps(event, default=str) + "\n\n"


def _text_events(text: str):
    """Deterministic text is sent as word-sized tokens so the client paints it the same way as a streamed model answer."""
    for piece in re.findall(r"\S+\s*|\s+", text):
        yield _sse({"type": "token", "text": piece})


_UNAVAILABLE_TEXT = ("The language model isn't responding right now, so I can't answer that yet. "
                     "Please send your message again in a moment.")
_REJECTED_TEXT = "I couldn't give a reliable answer to that one. Could you ask it another way?"


_CONVERSATIONAL_REFERENCE = re.compile(
    r"\b(this|that|it)\s+(is\s+|was\s+)?(a\s+|an\s+|about\s+|the\s+|related\s+|same\s+|another\s+)*"
    r"(question|topic|thing|one|subject|answer|reply|point|query)\b", re.I)


def _may_scan(message: str) -> bool:
    """The scanner is for content the user shares: a link, a fraud or payment signal, or a passage of text. A question, a
    request, or a remark about an earlier reply ("but this is a related question") is never scanned."""
    if extract_urls(message):
        return True
    words = len(message.split())
    if has_suspicious_signal(message) or has_action_or_object_risk(message):
        return not is_question_or_task(message) or words >= 12
    if is_question_or_task(message) or _CONVERSATIONAL_REFERENCE.search(message):
        return False
    return words >= 4


def _make_scan(message: str, captured: dict):
    """The tool the model can call. It refuses anything that is not content the user shared, and records the analysis."""
    def scan(text: str) -> str:
        if not _may_scan(message):
            return json.dumps({"error": "the user asked a question or replied; answer it directly, do not scan"})
        plan = route(text, False, False, followup_intent)
        try:
            result, types = analyze_turn(text, None, plan)
        except (InvalidInput, ImageRejected) as exc:
            return json.dumps({"error": str(exc)[:200]})
        analysis = to_contract(result, types, plan.urls)
        captured.update(result=result, plan=plan, analysis=analysis)
        return json.dumps(analysis.model_dump(mode="json"), default=str)[:6000]
    return scan


def _analysis_event(captured: dict) -> str:
    a = captured["analysis"]
    return _sse({"type": "analysis", "analysis": a.model_dump(mode="json"), "signature": sign(a),
                 "route": captured["plan"].public(), "response_type": "analysis"})


def _explain_analysis_events(message: str, analysis):
    """The model explains a scanner result (it cannot change it). If the explanation fails a check, the template explanation is shown."""
    final_text = ""
    if agent.configured():
        try:
            for kind, payload in agent.stream_explain(message, analysis.model_dump_json()):
                if kind == "token":
                    yield _sse({"type": "token", "text": payload})
                elif kind == "final":
                    final_text = payload
        except Exception:  # model unavailable: the template explanation below is used
            final_text = ""
    if not (final_text and agent.reply_is_acceptable(final_text, analysis.risk_score, explanation=True)):
        yield _sse({"type": "replace", "text": DeterministicExplanationProvider().reply_for_analysis(analysis).text})


def _model_events(message: str, recent: list[dict], captured: dict, scan):
    """Every message goes to the model first. The model answers, and calls the scanner only when agent.stream_turn decides the
    user shared content. A failure before any text is shown is retried once. If the model is still down, shared content is
    still scanned directly, so a real scam is never left unchecked."""
    if not agent.configured():
        yield _sse({"type": "replace", "text": _UNAVAILABLE_TEXT})
        return
    final_text = ""
    for attempt in range(3):
        shown = False
        final_text = ""
        ok = False
        if attempt:
            time.sleep(1.5 * attempt)  # the model host sheds load with 503s; a short pause usually clears it
        try:
            for kind, payload in agent.stream_turn(message, recent, scan):
                if kind == "token":
                    shown = True
                    yield _sse({"type": "token", "text": payload})
                elif kind == "reset":
                    shown = False
                    yield _sse({"type": "reset"})
                elif kind == "scanned" and captured.get("analysis") is not None:
                    yield _analysis_event(captured)
                elif kind == "final":
                    final_text = payload
            # An empty reply (a failed call) is never accepted, so the loop retries it.
            ok = captured.get("analysis") is not None or (bool(final_text) and agent.reply_is_acceptable(final_text, None, explanation=True))
        except Exception:  # network, timeout or malformed response from the model
            final_text = ""
        if ok or captured.get("analysis") is not None:
            break
        # A reply that failed the check (or a failed connection) is retried once, with the earlier text cleared first.
        if attempt < 2 and (shown or final_text):
            yield _sse({"type": "reset"})
    analysis = captured.get("analysis")
    if analysis is not None:
        if not (final_text and agent.reply_is_acceptable(final_text, analysis.risk_score, explanation=True)):
            yield _sse({"type": "replace", "text": DeterministicExplanationProvider().reply_for_analysis(analysis).text})
    elif final_text:
        if not agent.reply_is_acceptable(final_text, None, explanation=True):
            yield _sse({"type": "replace", "text": _REJECTED_TEXT})
    elif _may_scan(message) and (has_suspicious_signal(message) or has_action_or_object_risk(message) or extract_urls(message)):
        scan(message)  # the model is down: a message with scam signals is still analysed directly
        if captured.get("analysis") is not None:
            yield _analysis_event(captured)
            yield _sse({"type": "replace", "text": DeterministicExplanationProvider().reply_for_analysis(captured["analysis"]).text})
        else:
            yield _sse({"type": "replace", "text": _UNAVAILABLE_TEXT})
    else:
        yield _sse({"type": "replace", "text": _UNAVAILABLE_TEXT})


def _stream_chat_events(message: str, history: list[Turn], context: ChatAnalysis | None, signature: str):
    try:
        if sum(len(t.text) for t in history) > 12_000:
            yield _sse({"type": "error", "detail": "conversation context is too large; start a new chat"})
            return
        if context is not None and not verify(context, signature):
            context = None
        provider = get_provider()
        recent = [{"role": t.role, "text": t.text[:1500]} for t in history[-8:]]
        intent_result = classify_intent(message, False, context is not None, followup_intent)
        if intent_result.intent == "empty":
            yield _sse({"type": "error", "detail": "Send a message, link, screenshot or QR code to analyse."})
            return
        meta = intent_result.public()

        # Every message goes to the model first; it decides whether to call the scanner (see _model_events and _may_scan).
        response_type = _CHAT_ONLY_REPLY.get(intent_result.intent, ("chat", ""))[0]
        yield _sse({"type": "meta", "kind": "chat", "response_type": response_type, "intent": meta})
        captured: dict = {}
        scan = _make_scan(message, captured)
        # Content that carries a link or a scam signal is always scanned first (2026-10-07): the model must not be able to skip a link.
        # The model then explains the result; the score is the scanner's.
        if _may_scan(message) and (extract_urls(message) or has_suspicious_signal(message) or has_action_or_object_risk(message)):
            scan(message)
            if captured.get("analysis") is not None:
                yield _analysis_event(captured)
                yield from _explain_analysis_events(message, captured["analysis"])
                yield _sse({"type": "done"})
                return
        yield from _model_events(message, recent, captured, scan)
        yield _sse({"type": "done"})
    except (InvalidInput, ImageRejected) as exc:
        yield _sse({"type": "error", "detail": str(exc)[:300]})
    except Exception:
        _log.exception("chat stream failed")
        yield _sse({"type": "error", "detail": "Something went wrong. Please try again."})


@router.post("/api/v1/chat/stream", dependencies=[Depends(limit_light)])
async def chat_stream_endpoint(body: ChatIn):
    return StreamingResponse(_stream_chat_events(body.message, body.history, body.context, body.context_signature),
                             media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


async def _chat_turn(request: Request, response: Response, message: str, context: ChatAnalysis | None, signature: str, history: list[Turn],
                     image: tuple[bytes, str | None, str | None] | None) -> ChatOut:
    if sum(len(t.text) for t in history) > 12_000:
        raise HTTPException(422, "conversation context is too large; start a new chat")
    if context is not None and not verify(context, signature):
        context = None  # unsigned or altered context is never trusted
    provider = get_provider()
    intent_result = classify_intent(message, image is not None, context is not None, followup_intent)
    if intent_result.intent == "empty":
        raise HTTPException(422, "Send a message, link, screenshot or QR code to analyse.")

    if intent_result.intent in _CHAT_ONLY_REPLY and agent.configured():
        # The model is the chat assistant and may call the scanner. Any failed check falls through to the deterministic path.
        outcome = await _agent_chat(request, response, message, history, intent_result)
        if outcome is not None:
            return outcome

    if intent_result.intent in _CHAT_ONLY_REPLY:
        response_type, method_name = _CHAT_ONLY_REPLY[intent_result.intent]
        method = getattr(provider, method_name)
        # Every chat-only handler takes the recent turns, so follow-ups and general questions see the conversation.
        trimmed = [{"role": t.role, "text": t.text[:1500]} for t in history[-6:]]
        reply = await run_in_threadpool(method, message, trimmed)
        return ChatOut(kind="chat", response_type=response_type, intent=intent_result.public(), reply=_reply_out(reply))

    if intent_result.intent == "follow_up_analysis":
        assert context is not None and intent_result.followup_intent is not None
        reply = await run_in_threadpool(provider.reply_for_followup, intent_result.followup_intent, message, context)
        return ChatOut(kind="followup", response_type="follow_up", intent=intent_result.public(), reply=_reply_out(reply))

    plan = route(message, image is not None, False, followup_intent)  # follow-up already ruled out above; this call only
    # selects which pipeline modules to run (url/text/image) for a message intent classification has already decided to analyse
    try:
        if image is not None:
            result, types = await run_heavy(request, analyze_turn, message, image, plan)
        else:
            result, types = await run_in_threadpool(analyze_turn, message, None, plan)
    except ImageRejected as exc:
        raise _image_error(exc)
    except InvalidInput as exc:
        raise HTTPException(422, str(exc))
    analysis = to_contract(result, types, plan.urls)
    if image is None and intent_result.intent == "analyze_text" and agent.configured():
        # The model reads through spelling mistakes. The scanner checks the corrected text too; the higher risk wins.
        corrected = await run_in_threadpool(agent.correct_spelling, message)
        if corrected:
            plan2 = route(corrected, False, False, followup_intent)
            try:
                result2, types2 = await run_in_threadpool(analyze_turn, corrected, None, plan2)
            except (InvalidInput, ImageRejected):
                result2 = None
            if result2 is not None:
                analysis2 = to_contract(result2, types2, plan2.urls)
                if analysis2.risk_score > analysis.risk_score:
                    result, plan, analysis = result2, plan2, analysis2
    _publish(request, response, result)
    if agent.configured() and message.strip() and analysis.risk_level != "unable_to_verify":
        text = await run_in_threadpool(agent.explain, message, analysis.model_dump_json(), analysis.risk_score)
        if text:
            return ChatOut(kind="analysis", response_type="analysis", intent=intent_result.public(), route=plan.public(),
                           reply=ReplyOut(text=text, provider="nilabh", grounded=True), analysis=analysis,
                           context_signature=sign(analysis))
    reply = await run_in_threadpool(provider.reply_for_analysis, analysis)
    return ChatOut(kind="analysis", response_type="analysis", intent=intent_result.public(), route=plan.public(),
                   reply=_reply_out(reply), analysis=analysis, context_signature=sign(analysis))


@router.post("/api/v1/chat", response_model=ChatOut, dependencies=[Depends(limit_light)])
async def chat_endpoint(body: ChatIn, request: Request, response: Response):
    return await _chat_turn(request, response, body.message, body.context, body.context_signature, body.history, None)


@router.post("/api/v1/chat/upload", response_model=ChatOut, dependencies=[Depends(limit_heavy)])
async def chat_upload_endpoint(request: Request, response: Response, file: UploadFile = File(...),
                               message: str = Form(default="", max_length=MAX_MESSAGE_CHARS), context: str = Form(default="", max_length=60_000),
                               context_signature: str = Form(default="", max_length=128), history: str = Form(default="[]", max_length=16_000)):
    try:
        parsed = ChatAnalysis.model_validate_json(context) if context.strip() else None
    except ValueError:
        raise HTTPException(422, "invalid conversation context")
    try:
        turns = [Turn.model_validate(t) for t in json.loads(history)] if history.strip() else []
    except (ValueError, TypeError):
        raise HTTPException(422, "invalid conversation history")
    try:
        data = await file.read(max_upload_size() + 1)
        return await _chat_turn(request, response, message, parsed, context_signature, turns, (data, file.content_type, file.filename))
    finally:
        await file.close()


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



