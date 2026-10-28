"""Runs the existing TrustShield security modules for one conversational turn and combines their evidence.

The orchestrator only chooses which existing analyses to run and merges their structured evidence through the same fusion as
every other endpoint. It never scores anything itself.
"""
from __future__ import annotations

import os

from services.analysis import pipeline
from services.analysis.evidence.models import Evidence, Severity
from services.analysis.extractors.image_validation import validate_image
from services.analysis.extractors.document_types import DocumentRejected
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



_DOCUMENT_EXTENSIONS = {".pdf", ".docx", ".xlsx", ".txt", ".csv"}
_DOCUMENT_MIMES = {
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "text/csv", "text/plain",
}


_DOCUMENT_EXTENSIONS = {".pdf", ".docx", ".xlsx", ".txt", ".csv"}
_DOCUMENT_MIMES = {
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "text/csv", "text/plain",
}


def _is_document_attachment(data=None, declared_mime=None, filename=None):
    raw = data or b""
    if raw.startswith((b"\x89PNG\r\n\x1a\n", b"\xff\xd8\xff")) or (raw.startswith(b"RIFF") and raw[8:12] == b"WEBP"):
        return False
    if filename and "." in filename:
        if filename.rsplit(".", 1)[-1].lower() in {e.lstrip(".") for e in _DOCUMENT_EXTENSIONS}:
            return True
    if declared_mime:
        media = declared_mime.split(";", 1)[0].strip().lower()
        if media in _DOCUMENT_MIMES or media.startswith("text/"):
            return True
    head = raw[:8]
    if head.startswith(b"%PDF"):
        return True
    if head.startswith(b"PK"):
        try:
            import io, zipfile
            with zipfile.ZipFile(io.BytesIO(raw)) as zf:
                names = set(zf.namelist())
            if "word/document.xml" in names or "xl/workbook.xml" in names:
                return True
        except Exception:
            pass
    if raw and 0 not in raw:
        try:
            raw.decode("utf-8")
            if not declared_mime or not declared_mime.split(";", 1)[0].strip().lower().startswith("image/"):
                return True
        except UnicodeDecodeError:
            pass
    return False


def analyze_turn(message: str, image: tuple[bytes, str | None, str | None] | list[tuple[bytes, str | None, str | None]] | None, plan: RoutePlan) -> tuple[AnalysisResult, list[str]]:
    """Returns (fused result, input_types that were actually analysed)."""
    text = (message or "").strip()
    if plan.kind == "url":
        return pipeline.analyze_url(plan.urls[0] if plan.urls else text), ["url"]
    if plan.kind == "text":
        return pipeline.analyze_text(text), ["text"] + (["url"] if plan.urls else [])
    if isinstance(image, list):
        results = [analyze_turn(message, attachment, plan) for attachment in image]
        return merge_results([item[0] for item in results], "image"), list(dict.fromkeys(t for _, types in results for t in types))
    assert image is not None, "an attachment is required for this plan"
    data, mime, name = image
    if _is_document_attachment(data, mime, name):
        from services.analysis.extractors.document_text import extract_document
        extracted = extract_document(data, mime, name)
        result = pipeline.analyze_text(extracted.text, input_type="document",
                                       extra_limitations=list(extracted.warnings or []))
        if plan.urls:
            result = _add_message_urls(result, plan.urls)
            types = ["document", "url"]
        else:
            types = ["document"]
        return result, types
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
        ocr_text = ocr_module.data["normalized_text"]
        letters = sum(character.isalpha() for character in ocr_text)
        words = [word for word in ocr_text.split() if sum(character.isalpha() for character in word) >= 2]
        is_real_text = len(ocr_text) >= 8 and letters / max(1, len(ocr_text)) >= 0.5 and len(words) >= 2
        if not qr.payload or is_real_text:
            parts.append(ocr_result)
            types.append("screenshot")
    if qr.payload and "OCR mean word confidence is low." in (ocr_module.data.get("warnings", []) if ocr_module else []):
        for part in parts:
            if part.risk.confidence == "high":
                part.risk.confidence = "medium"
    elif not parts:
        parts.append(ocr_result)  # nothing decodable: keep the honest "unable to verify" result with its limitations
        types.append("image")
        parts[0].limitations.append("A QR code could not be read from this image. Upload the original image file or type the link/UPI text to check it.")
    result = parts[0] if len(parts) == 1 else merge_results(parts, "image")
    if plan.urls:
        result = _add_message_urls(result, plan.urls)
        types.append("url")
    return result, types
