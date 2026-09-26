"""Runs the existing TrustShield security modules for one conversational turn and combines their evidence.

The orchestrator only chooses which existing analyses to run and merges their structured evidence through the same fusion as
every other endpoint. It never scores anything itself.
"""
from __future__ import annotations

from services.analysis import pipeline
from services.analysis.evidence.models import Evidence, Severity
from services.analysis.extractors.image_validation import validate_image
from services.analysis.extractors.qr import OpenCVQRDecoder
from services.analysis.fusion.result import AnalysisResult, ModuleResult
from services.chat.router import RoutePlan


def _key(e: Evidence) -> tuple[str, str, str]:
    return (e.source, e.category, e.title)


def merge_results(parts: list[AnalysisResult], input_type: str) -> AnalysisResult:
    """Combine several analyses of the same content into one fused result (evidence union, module union, limitation union)."""
    evidence: list[Evidence] = []
    seen: set[tuple[str, str, str]] = set()
    modules: dict[str, ModuleResult] = {}
    limitations: list[str] = []
    for part in parts:
        for e in part.evidence:
            if _key(e) not in seen:
                seen.add(_key(e))
                evidence.append(e)
        for name, module in part.modules.items():
            if name == "fusion":
                continue
            current = modules.get(name)
            if current is None or (current.status != "completed" and module.status == "completed"):
                modules[name] = module
        limitations += [x for x in part.limitations if x not in limitations]
    input_ok = any(p.status != "failed" for p in parts)
    return pipeline._finish(input_type, evidence, modules, limitations, input_ok=input_ok)


def _add_message_urls(result: AnalysisResult, urls: list[str]) -> AnalysisResult:
    """URLs typed next to an attachment are checked structurally and their evidence joins the result."""
    extras = [pipeline.analyze_url(url) for url in urls[:3]]
    extras = [e for e in extras if e.modules.get("url_static") is not None and e.modules["url_static"].status == "completed"]
    return merge_results([result] + extras, result.input_type) if extras else result


def analyze_turn(message: str, image: tuple[bytes, str | None, str | None] | None, plan: RoutePlan) -> tuple[AnalysisResult, list[str]]:
    """Returns (fused result, input_types that were actually analysed)."""
    text = (message or "").strip()
    if plan.kind == "url":
        return pipeline.analyze_url(plan.urls[0] if plan.urls else text), ["url"]
    if plan.kind == "text":
        return pipeline.analyze_text(text), ["text"] + (["url"] if plan.urls else [])
    assert image is not None, "an image plan requires an attachment"
    data, mime, name = image
    validated = validate_image(data, mime, name)
    parts: list[AnalysisResult] = []
    types: list[str] = []
    qr = OpenCVQRDecoder().decode(validated.image)
    if qr.status == "completed" and qr.payload:
        parts.append(pipeline.analyze_qr(data, mime, name))
        types.append("qr")
    ocr_result = pipeline.analyze_image(data, mime, name)
    ocr_module = ocr_result.modules.get("ocr")
    if ocr_module is not None and ocr_module.status == "completed" and ocr_module.data.get("normalized_text"):
        parts.append(ocr_result)
        types.append("screenshot")
    elif not parts:
        parts.append(ocr_result)  # nothing decodable: keep the honest "unable to verify" result with its limitations
        types.append("image")
    result = parts[0] if len(parts) == 1 else merge_results(parts, "image")
    if plan.urls:
        result = _add_message_urls(result, plan.urls)
        types.append("url")
    return result, types
