# Sandbox worker image (Playwright + Chromium only). NOT DEPLOYED in Milestone A: ENABLE_WEB_SANDBOX stays false.
# It is kept separate from the API image on purpose (browser attack surface, different privileges, own network policy).
# Before it may run: an authenticated API-to-worker job transport, an egress-deny network policy (RFC1918/link-local/metadata),
# Linux containment equivalent to the Windows Job Object, and explicit approval (docs/DEPLOYMENT_GAP_ANALYSIS.md, phase D8).
FROM mcr.microsoft.com/playwright/python:v1.63.0-noble

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1 TRUSTSHIELD_ENV=production
RUN useradd --system --uid 10002 --create-home sandbox
WORKDIR /app
RUN pip install playwright==1.63.0 pydantic==2.13.5 tldextract==5.3.2
COPY services/__init__.py ./services/__init__.py
COPY services/analysis/__init__.py ./services/analysis/__init__.py
COPY services/analysis/web_sandbox ./services/analysis/web_sandbox
COPY services/analysis/url_analysis ./services/analysis/url_analysis
COPY services/analysis/evidence ./services/analysis/evidence
COPY services/worker ./services/worker
USER 10002
# One job per process over stdin/stdout today; a network transport is required before deployment.
CMD ["python", "-m", "services.worker.sandbox_worker"]
