"""Orchestrates detectors -> evidence -> fusion -> explanation. Detection is rule/ML based; explanation only restates evidence."""
from __future__ import annotations

from services.analysis.evidence.models import Evidence, Severity
from services.analysis.explanation.template import explain
from services.analysis.fusion.engine import fuse
from services.analysis.fusion.result import AnalysisResult, Classification, ModuleResult, Risk
from services.analysis.normalization.text import extract_urls, normalize_text
from services.analysis.rules.text_rules import brand_name, claimed_brands, run_text_rules
from services.analysis.url_analysis.brands import is_official
from services.analysis.url_analysis.static import analyze_url_static

MIN_TEXT_CHARS = 15

LIMIT_ML = "Machine-learning classification was not available for this analysis."
LIMIT_REP = "No threat-intelligence reputation information is available; this is not the same as the link being known-clean."
LIMIT_SANDBOX = "The destination page was not inspected in an isolated browser, so its actual content and behaviour are unknown."


def _finish(input_type: str, evidence: list[Evidence], modules: dict[str, ModuleResult], limitations: list[str], input_ok: bool = True) -> AnalysisResult:
    fusion = fuse(evidence, {k: v.status for k, v in modules.items()}, input_ok)
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


def analyze_text(raw: str, input_type: str = "text", extra_modules: dict[str, ModuleResult] | None = None) -> AnalysisResult:
    text = normalize_text(raw)
    modules: dict[str, ModuleResult] = dict(extra_modules or {})
    if len(text) < MIN_TEXT_CHARS:
        modules["text_rules"] = ModuleResult(status="failed", detail=f"Input shorter than {MIN_TEXT_CHARS} characters")
        return _finish(input_type, [], modules, ["The input is too short to analyze reliably."], input_ok=False)

    evidence = run_text_rules(text)
    modules["text_rules"] = ModuleResult(status="completed", data={"rules_matched": len(evidence)})

    urls = extract_urls(text)
    limitations = [LIMIT_ML]
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
    return _finish(input_type, evidence, modules, limitations)


def analyze_url(raw: str) -> AnalysisResult:
    r = analyze_url_static(raw)
    if not r.valid:
        modules = {"url_static": ModuleResult(status="failed", detail="URL is malformed or uses an unsupported scheme")}
        return _finish("url", r.evidence, modules, ["The URL could not be parsed or uses an unsupported scheme."], input_ok=bool(r.evidence))
    modules = {
        "url_static": ModuleResult(status="completed", data=r.facts),
        "reputation": ModuleResult(status="unavailable", detail="No reputation provider configured"),
        "web_sandbox": ModuleResult(status="not_run", detail="Isolated browser inspection not enabled"),
    }
    return _finish("url", r.evidence, modules, [LIMIT_REP, LIMIT_SANDBOX])
