"""Orchestrates detectors -> evidence -> fusion -> explanation. Detection is rule/ML based; explanation only restates evidence."""
from __future__ import annotations

import re
import unicodedata

from services.analysis.evidence.models import Evidence, Severity
from services.analysis.explanation.template import explain
from services.analysis.fusion.engine import fuse
from services.analysis.fusion.result import AnalysisResult, Classification, ModuleResult, Risk
from services.analysis.normalization.text import MAX_TEXT_CHARS, extract_urls, normalize_text
from services.analysis.rules.text_rules import brand_name, claimed_brands, run_text_rules
from services.analysis.extractors.image_validation import validate_image
from services.analysis.extractors.ocr import OCRResult, get_ocr_provider
from services.analysis.extractors.qr import QRResult, OpenCVQRDecoder
from services.analysis.url_analysis.brands import is_official
from services.analysis.url_analysis.static import analyze_url_static

MIN_TEXT_CHARS = 15

LIMIT_ML = "Machine-learning classification was not available for this analysis."
LIMIT_REP = "No threat-intelligence reputation information is available; this is not the same as the link being known-clean."
LIMIT_SANDBOX = "The destination page was not inspected in an isolated browser, so its actual content and behaviour are unknown."


def _finish(input_type: str, evidence: list[Evidence], modules: dict[str, ModuleResult], limitations: list[str],
            input_ok: bool = True, module_quality: dict[str, float] | None = None) -> AnalysisResult:
    fusion = fuse(evidence, {k: v.status for k, v in modules.items()}, input_ok, module_quality)
    explanation, actions = explain(fusion.label, evidence, fusion.primary, limitations)
    bad = any(m.status in {"failed", "timeout"} for m in modules.values())
    status = "failed" if not input_ok else "partial" if bad else "completed"
    order = list(Severity)
    evidence = sorted(evidence, key=lambda e: order.index(e.severity), reverse=True)
    return AnalysisResult(
        input_type=input_type, status=status,
        risk=Risk(score=fusion.score, label=fusion.label, confidence=fusion.confidence),
        classification=Classification(primary=fusion.primary, secondary=fusion.secondary),
        evidence=evidence, modules=modules, explanation=explanation,
        recommended_actions=actions, limitations=limitations,
    )


def analyze_text(raw: str, input_type: str = "text", extra_modules: dict[str, ModuleResult] | None = None,
                 module_quality: dict[str, float] | None = None,
                 extra_limitations: list[str] | None = None) -> AnalysisResult:
    text = normalize_text(raw)
    modules: dict[str, ModuleResult] = dict(extra_modules or {})
    if len(text) < MIN_TEXT_CHARS:
        modules["text_rules"] = ModuleResult(status="failed", detail=f"Input shorter than {MIN_TEXT_CHARS} characters")
        return _finish(input_type, [], modules, ["The input is too short to analyze reliably."] + list(extra_limitations or []), input_ok=False, module_quality=module_quality)

    evidence = run_text_rules(text)
    modules["text_rules"] = ModuleResult(status="completed", data={"rules_matched": len(evidence)})

    urls = extract_urls(text)
    limitations = [LIMIT_ML] + list(extra_limitations or [])
    modules["ml"] = ModuleResult(status="unavailable", detail="Classifier not yet trained/loaded")
    if urls:
        claims = claimed_brands(text)
        analyzed = []
        for u in urls[:10]:
            r = analyze_url_static(u)
            if not r.valid:
                continue
            evidence += r.evidence
            analyzed.append(r.facts["registered_domain"])
            flagged = any(e.category in {"domain_mismatch", "lookalike_domain"} for e in r.evidence)
            if claims and not flagged and not any(is_official(c, r.facts["registered_domain"]) for c in claims):
                names = ", ".join(brand_name(c) for c in claims)
                evidence.append(Evidence(source="brand_check", category="domain_mismatch", severity=Severity.high, confidence=0.85,
                                         title="Claimed organization does not match the link's domain",
                                         description=f"The message mentions {names}, but the link points to '{r.facts['registered_domain']}', which is not an official domain.",
                                         metadata={"claimed": claims, "registered_domain": r.facts["registered_domain"]}))
        modules["url_static"] = ModuleResult(status="completed", data={"domains": analyzed})
        modules["reputation"] = ModuleResult(status="unavailable", detail="No reputation provider configured")
        limitations += [LIMIT_REP, LIMIT_SANDBOX]
    return _finish(input_type, evidence, modules, limitations, module_quality=module_quality)


def analyze_url(raw: str, input_type: str = "url", extra_modules: dict[str, ModuleResult] | None = None,
                extra_limitations: list[str] | None = None,
                module_quality: dict[str, float] | None = None) -> AnalysisResult:
    r = analyze_url_static(raw)
    if not r.valid:
        modules = {"url_static": ModuleResult(status="completed" if r.evidence else "failed", data=r.facts,
                                               detail="URL is malformed or uses an unsupported scheme"),
                   "web_sandbox": ModuleResult(status="not_run", detail="Isolated browser inspection not enabled")}
        modules.update(extra_modules or {})
        return _finish(input_type, r.evidence, modules,
                       ["The URL could not be parsed or uses an unsupported scheme."] + list(extra_limitations or []),
                       input_ok=bool(r.evidence), module_quality=module_quality)
    modules = {
        "url_static": ModuleResult(status="completed", data=r.facts),
        "reputation": ModuleResult(status="unavailable", detail="No reputation provider configured"),
        "web_sandbox": ModuleResult(status="not_run", detail="Isolated browser inspection not enabled"),
    }
    modules.update(extra_modules or {})
    return _finish(input_type, r.evidence, modules, [LIMIT_REP, LIMIT_SANDBOX] + list(extra_limitations or []), module_quality=module_quality)


def _ocr_module(result: OCRResult, image) -> ModuleResult:
    raw_text = result.raw_text[:MAX_TEXT_CHARS]
    normalized_text = normalize_text(raw_text)
    return ModuleResult(status=result.status, detail=result.detail, data={
        "provider": result.provider,
        "provider_version": result.provider_version,
        "confidence": result.confidence,
        "raw_text": raw_text,
        "normalized_text": normalized_text,
        "text": raw_text,
        "warnings": result.warnings,
        "preprocessing": result.preprocessing,
        "tesseract_cmd_source": result.tesseract_cmd_source,
        "image": {"width": image.width, "height": image.height, "format": image.format},
    })


def _ocr_quality(result: OCRResult) -> tuple[float, list[str]]:
    warnings = list(result.warnings)
    if result.status != "completed":
        return 0.0, warnings
    if result.confidence is None:
        warnings.append("OCR provider did not report confidence; a conservative quality factor was used for fusion.")
        return 0.75, warnings
    return max(0.0, min(1.0, result.confidence / 100.0)), warnings


def analyze_image(data: bytes, declared_mime: str | None = None, filename: str | None = None) -> AnalysisResult:
    validated = validate_image(data, declared_mime, filename)
    provider = get_ocr_provider()
    try:
        ocr = provider.extract(validated.image)
    except Exception:  # provider failures become a structured result, never an API 500
        ocr = OCRResult("failed", "", getattr(provider, "name", "unknown"), None, None, [], "OCR failed while processing the image.")
    quality, warnings = _ocr_quality(ocr)
    if warnings != ocr.warnings:
        ocr = OCRResult(
            ocr.status, ocr.text, ocr.provider, ocr.provider_version, ocr.confidence, warnings, ocr.detail,
            normalized_text=ocr.normalized_text, preprocessing=ocr.preprocessing,
            tesseract_cmd_source=ocr.tesseract_cmd_source,
        )
    module = _ocr_module(ocr, validated.image)
    if ocr.status != "completed":
        limitation = "OCR was unavailable; the image could not be verified." if ocr.status == "unavailable" else "OCR failed; the image could not be verified."
        return _finish("image", [], {"ocr": module}, [limitation], input_ok=False, module_quality={"ocr": quality})
    normalized_text = module.data["normalized_text"]
    if not normalized_text:
        return _finish("image", [], {"ocr": module}, ["No readable text was found in the image."], input_ok=False, module_quality={"ocr": quality})
    ocr_text = normalized_text
    limitations = ["Text was extracted by OCR and may contain recognition errors."]
    if len(ocr.raw_text) > MAX_TEXT_CHARS:
        limitations.append("OCR text was truncated to the text-analysis limit.")
    return analyze_text(
        ocr_text, input_type="image", extra_modules={"ocr": module},
        module_quality={"ocr": quality},
        extra_limitations=limitations,
    )


def _qr_module(result: QRResult) -> ModuleResult:
    return ModuleResult(status=result.status, detail=result.detail, data={
        "decoder": result.decoder,
        "payload": (result.payload or "")[:2048],
        "payload_type": result.payload_type,
        "warnings": result.warnings,
    })


def _strip_controls(value: str, limit: int = 500) -> str:
    return "".join(ch for ch in value if not unicodedata.category(ch).startswith("C"))[:limit]


def _qr_evidence(category: str, severity: Severity, confidence: float, title: str, description: str, **metadata) -> Evidence:
    def clean(value):
        if isinstance(value, str):
            return _strip_controls(value)
        if isinstance(value, dict):
            return {key: clean(item) for key, item in value.items()}
        if isinstance(value, list):
            return [clean(item) for item in value]
        return value

    return Evidence(source="qr_payload", category=category, severity=severity, confidence=confidence,
                    title=clean(title), description=clean(description), metadata=clean(metadata))


def _parse_upi(payload: str) -> tuple[dict, list[Evidence], list[str]]:
    from urllib.parse import parse_qs, urlsplit
    fields = {key.lower(): values[-1] for key, values in parse_qs(urlsplit(payload).query, keep_blank_values=True).items()}
    raw_selected = {"payee_vpa": fields.get("pa"), "payee_name": fields.get("pn"), "amount": fields.get("am"),
                    "currency": fields.get("cu"), "note": fields.get("tn"), "merchant_code": fields.get("mc"),
                    "mode": fields.get("mode")}
    evidence = [_qr_evidence("qr_payment_request", Severity.medium, 0.75, "QR requests a UPI payment",
                             "Verify the payee and amount in your payment app before approving this QR request.")]
    limitations: list[str] = []
    field_limits = {"payee_name": 200, "amount": 32, "currency": 8, "note": 500, "merchant_code": 16, "mode": 32}
    oversized = [key for key, limit in field_limits.items() if raw_selected[key] and len(raw_selected[key]) > limit]
    if oversized:
        evidence.append(_qr_evidence("qr_malformed_fields", Severity.low, 0.7, "UPI QR contains oversized fields",
                                     "Some UPI fields were too long to trust structurally.", fields=oversized))
        for key in oversized:
            raw_selected[key] = None

    limits = {**field_limits, "payee_vpa": 320}
    selected = {
        key: (_strip_controls(value, limits[key])
              if value is not None else None)
        for key, value in raw_selected.items()
    }
    raw_vpa = raw_selected["payee_vpa"] or ""
    if not raw_vpa:
        evidence.append(_qr_evidence("qr_missing_payee", Severity.low, 0.65, "UPI QR has no payee VPA", "The payment destination could not be identified structurally."))
    elif len(raw_vpa) > 320 or not re.match(r"^[A-Za-z0-9._-]{2,256}@[A-Za-z0-9.-]{2,64}$", raw_vpa):
        evidence.append(_qr_evidence("qr_malformed_payee", Severity.low, 0.7, "UPI payee VPA looks malformed", "Verify the payee identifier manually before paying."))
    if selected["amount"]:
        try:
            from decimal import Decimal
            amount = Decimal(selected["amount"])
            if amount < 0 or amount > Decimal("100000000"):
                raise ValueError
            evidence.append(_qr_evidence("qr_prefilled_amount", Severity.low, 0.6, "UPI QR contains a pre-filled amount", "Confirm the amount before approving the payment.", amount=selected["amount"]))
        except Exception:
            evidence.append(_qr_evidence("qr_malformed_amount", Severity.low, 0.65, "UPI amount is malformed", "Do not approve until the amount is verified."))
    if (selected["mode"] or "").lower() in {"04", "collect", "collect-request"}:
        evidence.append(_qr_evidence("qr_collect_mode", Severity.low, 0.65, "UPI QR uses collect mode", "A collect request asks the payer to approve a debit; verify it is expected."))
    return selected, evidence, limitations


def analyze_qr(data: bytes, declared_mime: str | None = None, filename: str | None = None) -> AnalysisResult:
    validated = validate_image(data, declared_mime, filename)
    decoded = OpenCVQRDecoder().decode(validated.image)
    qr_module = _qr_module(decoded)
    if decoded.status != "completed" or not decoded.payload:
        return _finish("qr", [], {"qr": qr_module}, ["No QR code could be decoded from the image."], input_ok=False)
    payload = decoded.payload
    if decoded.payload_type == "url":
        return analyze_url(payload, input_type="qr", extra_modules={"qr": qr_module},
                           extra_limitations=["The QR destination was analyzed as text only; it was never fetched."],
                           module_quality={"qr": 0.8 if decoded.warnings else 1.0})
    if decoded.payload_type == "text":
        return analyze_text(payload, input_type="qr", extra_modules={"qr": qr_module},
                            module_quality={"qr": 0.8 if decoded.warnings else 1.0})
    if decoded.payload_type == "upi":
        upi, evidence, limitations = _parse_upi(payload)
        qr_module.data["upi"] = upi
        modules = {"qr": qr_module}
        if upi.get("note") and len(upi["note"].strip()) >= MIN_TEXT_CHARS:
            note = analyze_text(upi["note"], input_type="qr")
            evidence += note.evidence
            modules.update(note.modules)
        return _finish("qr", evidence, modules, limitations + ["UPI fields were parsed structurally; no payment action was performed."],
                       module_quality={"qr": 0.8 if decoded.warnings else 1.0})
    evidence = [_qr_evidence("qr_non_web_payload", Severity.informational, 0.9, "QR contains a non-web payload",
                             "This QR payload was not opened or executed; verify its meaning before using it.", payload_type=decoded.payload_type)]
    return _finish("qr", evidence, {"qr": qr_module}, ["The QR payload is not a web URL and was not executed."],
                   module_quality={"qr": 0.8 if decoded.warnings else 1.0})
