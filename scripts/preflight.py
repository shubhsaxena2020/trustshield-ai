"""Startup / demo preflight. Run:  python scripts/preflight.py [--json] [--ports 8000 3000]

Exit status 0 = every FAIL-level check passed (WARN items are advisory); 1 = at least one FAIL.
Makes no network requests and never opens the sealed v2 holdout.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import socket
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

EXPECTED_MODEL = "trustshield_text_v3"
EXPECTED_THRESHOLD = 0.16
EXPECTED_V3_SHA256 = "d69aadbb758d186c8b33946895b456588cf8f5ce5eca504647d9e7a009c1189a"
TRUTHY = {"1", "true", "yes", "on"}
results: list[dict] = []


def check(name: str, status: str, detail: str = "") -> None:
    results.append({"check": name, "status": status, "detail": detail})


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def check_model() -> None:
    from services.analysis.ml.classifier import DEFAULT_MODEL, TextClassifier

    if os.getenv("TRUSTSHIELD_ML", "on").strip().lower() == "off":
        check("ML enabled", "FAIL", "TRUSTSHIELD_ML=off: the demo would run rules-only")
        return
    override = os.getenv("TRUSTSHIELD_ML_MODEL", "").strip()
    check("default model constant", "PASS" if DEFAULT_MODEL == EXPECTED_MODEL else "FAIL", f"DEFAULT_MODEL={DEFAULT_MODEL}")
    if override:
        check("model override", "WARN" if override != EXPECTED_MODEL else "PASS", f"TRUSTSHIELD_ML_MODEL={override} (active model differs from v3 default)" if override != EXPECTED_MODEL else override)
    artifact = ROOT / "models" / "trained" / f"{EXPECTED_MODEL}.joblib"
    if not artifact.is_file():
        check("v3 artifact present", "FAIL",
              "MODEL MISSING - ML would be UNAVAILABLE and the demo would run rules-only. Regenerate: see docs/REPRODUCIBILITY.md")
        return
    actual = sha256(artifact)
    check("v3 artifact hash", "PASS" if actual == EXPECTED_V3_SHA256 else "WARN",
          "matches the promoted release" if actual == EXPECTED_V3_SHA256 else
          f"sha256 differs from the promoted artifact ({actual[:12]}...); regenerated artifacts may differ byte-for-byte, see docs/REPRODUCIBILITY.md")
    classifier = TextClassifier(EXPECTED_MODEL)
    if not classifier.available:
        check("v3 artifact loads", "FAIL", f"unavailable: {classifier.reason}")
        return
    check("v3 artifact loads", "PASS", f"sklearn {classifier.metadata.get('sklearn_version')}")
    threshold = float(classifier.metadata.get("threshold", -1))
    check("v3 threshold", "PASS" if threshold == EXPECTED_THRESHOLD else "FAIL", f"threshold={threshold}")
    probe = classifier.predict("URGENT: your SBI account is blocked, share the OTP at http://sbi-kyc.xyz")
    check("v3 smoke prediction", "PASS" if 0.0 <= probe.p_malicious <= 1.0 and probe.error is None else "FAIL", f"p_malicious={probe.p_malicious:.3f}")


def check_ocr() -> None:
    from services.analysis.extractors.ocr import get_ocr_provider

    provider = get_ocr_provider()
    if provider.name == "mock":
        check("OCR provider", "WARN", "mock provider selected (demo-only; not real OCR)")
    elif provider.is_available():
        check("Tesseract", "PASS", f"provider={provider.name}")
    else:
        check("Tesseract", "FAIL", "unavailable; set TESSERACT_CMD (see README). Screenshot analysis would report unable_to_verify")


def check_frontend() -> None:
    web = ROOT / "apps" / "web"
    node = shutil.which("node")
    check("node", "PASS" if node else "FAIL", node or "node not found on PATH")
    check("frontend dependencies", "PASS" if (web / "node_modules").is_dir() else "FAIL", "apps/web/node_modules" + ("" if (web / "node_modules").is_dir() else " missing: run npm ci"))
    built = (web / ".next" / "BUILD_ID").is_file()
    check("frontend production build", "PASS" if built else "WARN", "present" if built else ".next build missing: run npm run build (needed for `next start`)")
    check("demo fixtures", "PASS" if (ROOT / "demo" / "fixtures" / "kyc_screenshot.png").is_file() else "WARN", "demo/fixtures")
    api_url = os.getenv("NEXT_PUBLIC_API_URL", "http://localhost:8000")
    check("frontend API URL", "PASS" if api_url.rstrip("/") == "http://localhost:8000" else "WARN",
          f"{api_url} (CORS allows only http://localhost:3000 origin)")


def check_api() -> None:
    missing = []
    for module in ("fastapi", "uvicorn", "pydantic", "sklearn", "PIL", "cv2", "pytesseract"):
        try:
            __import__(module)
        except Exception:
            missing.append(module)
    check("API python dependencies", "PASS" if not missing else "FAIL", "all importable" if not missing else "missing: " + ", ".join(missing))
    fusion = os.getenv("TRUSTSHIELD_FUSION", "ml_aware_weighted").strip()
    check("fusion strategy", "PASS" if fusion == "ml_aware_weighted" else "WARN", f"TRUSTSHIELD_FUSION={fusion}")


def check_production(remote_url: str | None) -> None:
    """Deployment profile: the same identity/limits/config checks the API enforces at startup, plus deployment-only ones."""
    from services.api.settings import Settings
    from services.api.startup import environment_problems, model_problems

    settings = Settings.from_env()
    check("TRUSTSHIELD_ENV is staging or production", "PASS" if settings.strict else "FAIL", f"TRUSTSHIELD_ENV={settings.env}")
    problems = environment_problems(settings)
    check("API environment (CORS, ML, OCR, fusion, tmp)", "PASS" if not problems else "FAIL", "; ".join(problems))
    model = model_problems()
    check("v3 identity: artifact present, hash, load, sklearn, threshold 0.16", "PASS" if not model else "FAIL", "; ".join(model))
    dev_flags = [name for name, bad in (
        ("TRUSTSHIELD_ENV=test", os.getenv("TRUSTSHIELD_ENV", "").lower() == "test"),
        ("TRUSTSHIELD_OCR_PROVIDER=mock", os.getenv("TRUSTSHIELD_OCR_PROVIDER", "").lower() == "mock"),
        ("localhost in PUBLIC_API_URL", "localhost" in os.getenv("PUBLIC_API_URL", "")),
        ("localhost in NEXT_PUBLIC_API_URL", "localhost" in os.getenv("NEXT_PUBLIC_API_URL", "")),
        ("TRUSTSHIELD_DEBUG_PROXY", os.getenv("TRUSTSHIELD_DEBUG_PROXY", "") == "1"),
        ("DEBUG", os.getenv("DEBUG", "").lower() in TRUTHY)) if bad]
    check("no development configuration enabled", "PASS" if not dev_flags else "FAIL", ", ".join(dev_flags))
    explanation_provider = os.getenv("TRUSTSHIELD_EXPLANATION_PROVIDER", "deterministic").strip().lower()
    check("explanation provider is deterministic in production", "PASS" if explanation_provider in ("", "deterministic") else "FAIL",
          f"TRUSTSHIELD_EXPLANATION_PROVIDER={explanation_provider} (runtime already ignores this in production, but it must not be set)")
    ai_base_url = os.getenv("TRUSTSHIELD_AI_BASE_URL", "")
    localhost_ai_url = any(h in ai_base_url for h in ("localhost", "127.0.0.1", "0.0.0.0"))
    check("TRUSTSHIELD_AI_BASE_URL is not localhost in production", "PASS" if not localhost_ai_url else "FAIL",
          f"TRUSTSHIELD_AI_BASE_URL={ai_base_url} -- a localhost LLM router (e.g. 9Router) only reaches the developer's own "
          "machine and cannot serve deployed traffic; see docs/PROVIDER_CONFIGURATION.md" if localhost_ai_url else "")
    public = os.getenv("PUBLIC_API_URL", "")
    check("PUBLIC_API_URL is https (or unset for a same-origin deployment)", "PASS" if not public or public.startswith("https://") else "FAIL", public or "unset (same-origin)")
    frontend_api = os.getenv("NEXT_PUBLIC_API_URL", "")
    same_origin = not frontend_api  # unset = relative /api URLs (Vercel Services: frontend and API share one origin)
    check("frontend API URL is same-origin or matches PUBLIC_API_URL",
          "PASS" if same_origin or frontend_api.rstrip("/") == public.rstrip("/") else "FAIL", frontend_api or "unset (same-origin /api)")
    db = os.getenv("DATABASE_URL", "").strip()
    check("database", "PASS" if not db else "WARN",
          "stateless deployment: no database used" if not db else "DATABASE_URL is set but Milestone A does not use a database")
    for rel in ("services", "models/release", "models/metadata"):
        check(f"required directory {rel}", "PASS" if (ROOT / rel).is_dir() else "FAIL")
    if remote_url:
        import urllib.request

        try:
            with urllib.request.urlopen(remote_url.rstrip("/") + "/api/v1/health", timeout=10) as response:
                health = json.loads(response.read())
            ml = health["modules"]["ml"]
            ok = ml.get("available") and ml.get("model_name") == EXPECTED_MODEL
            check("remote API health (v3 loaded)", "PASS" if ok else "FAIL", f"{remote_url}: {ml.get('model_name')} available={ml.get('available')}")
        except Exception as exc:
            check("remote API health", "FAIL", type(exc).__name__)


def check_ports(ports: list[int]) -> None:
    for port in ports:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 0)
            try:
                probe.bind(("127.0.0.1", port))
                check(f"port {port} free", "PASS")
            except OSError:
                check(f"port {port} free", "FAIL", "already in use; stop the other process (by PID) or pick another port")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--ports", type=int, nargs="*", default=[8000, 3000])
    parser.add_argument("--skip-frontend", action="store_true", help="API-only host (Render image / backend CI job): no Node or web build checks")
    parser.add_argument("--profile", choices=("development", "production"), default="development")
    parser.add_argument("--remote", help="also verify a deployed API base URL (one GET to /api/v1/health)")
    args = parser.parse_args(argv)
    steps = [check_model, check_ocr, check_api] if args.skip_frontend else [check_model, check_ocr, check_api, check_frontend]
    if args.profile == "production":
        def check_production_step() -> None:
            check_production(args.remote)
        steps.append(check_production_step)
    for step in steps:
        try:
            step()
        except Exception as exc:  # a crashing check is a failed check, never a silent pass
            check(step.__name__, "FAIL", f"{type(exc).__name__}: {exc}")
    check_ports(args.ports)
    failed = [item for item in results if item["status"] == "FAIL"]
    if args.json:
        print(json.dumps({"ok": not failed, "results": results}, indent=2))
    else:
        for item in results:
            print(f"[{item['status']:4}] {item['check']}" + (f" - {item['detail']}" if item["detail"] else ""))
        print("\nPREFLIGHT " + ("PASSED" if not failed else f"FAILED ({len(failed)} blocking issue(s))"))
        if any(item["check"] == "v3 artifact present" and item["status"] == "FAIL" for item in results):
            print("*** ML IS UNAVAILABLE: do not present this as the full system. ***")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
