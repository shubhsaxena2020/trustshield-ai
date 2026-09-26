# TrustShield API image (Milestone A: sandbox worker NOT included; ENABLE_WEB_SANDBOX must stay false).
# Build from the repository root:
#   docker build -f docker/api.Dockerfile -t trustshield-api .
# The promoted model artifact is never trained here. Supply it either as release-artifacts/trustshield_text_v3.joblib in the
# build context or with --build-arg MODEL_ARTIFACT_URL=<https release asset>; the build FAILS unless its SHA-256 matches
# models/release/v3_release_manifest.json.
FROM python:3.13-slim-bookworm@sha256:2325bb286ec344af3e5898cc224b5844e2707ac6e26b1632516fd3edc84a5e26

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    TRUSTSHIELD_ENV=production \
    TESSERACT_CMD=/usr/bin/tesseract \
    OMP_NUM_THREADS=1 \
    OPENBLAS_NUM_THREADS=1

RUN apt-get update \
 && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-eng libglib2.0-0 \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin trustshield

WORKDIR /app
COPY requirements-api.lock.txt ./
RUN pip install -r requirements-api.lock.txt

COPY services ./services
COPY models/metadata ./models/metadata
COPY models/release/v3_release_manifest.json ./models/release/v3_release_manifest.json
COPY scripts/fetch_model_artifact.py ./scripts/fetch_model_artifact.py
COPY release-artifacts ./release-artifacts

ARG MODEL_ARTIFACT_URL=""
RUN python scripts/fetch_model_artifact.py --from-dir release-artifacts ${MODEL_ARTIFACT_URL:+--url "$MODEL_ARTIFACT_URL"} \
 && rm -rf release-artifacts scripts \
 && mkdir -p /tmp/trustshield && chown -R trustshield:trustshield /tmp/trustshield

USER 10001
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD python -c "import os,urllib.request; urllib.request.urlopen('http://127.0.0.1:%s/healthz' % os.environ.get('PORT','8000'), timeout=3)"

# One worker (the model lives in memory once); own access log, so uvicorn's (which prints query strings) is off.
CMD ["sh", "-c", "exec python -m uvicorn services.api.main:app --host 0.0.0.0 --port ${PORT:-8000} --workers 1 --no-access-log --no-server-header --timeout-keep-alive 5 --limit-concurrency 40"]
