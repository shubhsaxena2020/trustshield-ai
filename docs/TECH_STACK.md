# TrustShield AI technical stack

This reference reflects the checked-in dependency manifests, implementation imports, deployment files, and model metadata in this repository. Python versions below are resolved pins from the lock files; browser package versions are the resolved versions in `apps/web/package-lock.json` (the `package.json` ranges are not exact pins).

## Backend language and API

| Technology | Version | What it does in this project |
|---|---:|---|
| Python | 3.13 (container base) | Runs the FastAPI analysis service, model inference, image processing, and supporting scripts. |
| FastAPI | 0.141.1 | Defines the HTTP API, request handling, validation integration, and API routes. |
| Uvicorn | 0.53.0 | Serves the FastAPI application as the ASGI process in local and deployed containers. |
| Pydantic | 2.13.5 | Validates API/chat inputs and represents typed analysis results and evidence. |
| pydantic-settings | 2.15.0 | Listed in the development requirements for settings-oriented Python tooling; it is not in the minimal API image lock. |
| python-multipart | 0.0.32 | Parses uploaded image/form data accepted by the API. |
| tldextract | 5.3.2 | Extracts registered domains and public suffixes for static URL analysis. |

The API uses Python standard-library modules for HTTP calls (`urllib`), URL parsing, JSON, hashing, and other utilities; it does not add a general HTTP client dependency to the API runtime.

## API runtime Python lock

`requirements-api.in` names the API's top-level dependencies. `requirements-api.lock.txt` resolves the Linux container environment. Every resolved entry is listed here; entries marked as supporting packages arrive through the locked frameworks or libraries and are not directly imported by application services.

| Locked package | Version | What it does in this project |
|---|---:|---|
| annotated-doc | 0.0.5 | Supplies FastAPI documentation metadata support. |
| annotated-types | 0.8.0 | Supplies type annotation metadata used by Pydantic validation. |
| anyio | 4.15.1 | Provides async concurrency primitives used by Starlette/FastAPI. |
| certifi | 2026.7.22 | Supplies trusted CA certificates to Python HTTP dependencies. |
| charset-normalizer | 3.5.1 | Detects/normalizes response text encodings for Requests dependencies. |
| click | 8.5.0 | Supports command-line entry points used by runtime tooling. |
| cloudpickle | 3.1.2 | Serialization support required by the scikit-learn runtime. |
| fastapi | 0.141.1 | Implements the TrustShield HTTP API. |
| filelock | 4.0.3 | Provides file-lock support used by model/scientific Python dependencies. |
| h11 | 0.16.0 | HTTP/1.1 protocol implementation used by Uvicorn. |
| idna | 3.20 | Provides internationalized-domain handling used by HTTP dependencies. |
| joblib | 1.6.0 | Loads the trained `.joblib` classifier artifact for inference. |
| narwhals | 2.26.0 | Dataframe interoperability support pulled in by the numerical/model stack. |
| numpy | 2.5.3 | Supplies arrays and numerical operations used by image/ML processing. |
| opencv-python-headless | 5.0.0.93 | Provides OpenCV QR detection and image transformations without GUI libraries. |
| packaging | 26.3 | Version and package metadata utilities used by Python dependencies. |
| pillow | 12.3.0 | Opens, validates, transforms, and preprocesses uploaded images. |
| pydantic | 2.13.5 | Validates API payloads and structured analysis objects. |
| pydantic_core | 2.46.5 | Implements Pydantic's compiled validation and serialization core. |
| pytesseract | 0.3.13 | Calls the installed Tesseract executable to extract text from images. |
| python-multipart | 0.0.32 | Parses multipart image uploads. |
| requests | 2.34.2 | HTTP client present in the resolved runtime environment; application service imports use Python's `urllib` instead. |
| requests-file | 3.0.1 | File URL adapter for Requests, included in the resolved dependency graph. |
| scikit-learn | 1.9.1 | Provides the TF-IDF pipelines, calibrated classifiers, and inference implementation. |
| scipy | 1.18.1 | Numerical/scientific dependency required by scikit-learn and its model operations. |
| starlette | 1.7.0 | ASGI toolkit underlying FastAPI, including middleware and response support. |
| threadpoolctl | 3.7.0 | Controls native numerical-library thread pools; the containers also cap OpenMP/OpenBLAS threads. |
| tldextract | 5.3.2 | Identifies registered domains during URL analysis. |
| typing-inspection | 0.4.4 | Runtime typing introspection support used by Pydantic. |
| typing_extensions | 4.16.0 | Backports typing constructs used by Python dependencies. |
| urllib3 | 2.8.0 | HTTP transport dependency for Requests. |
| uvicorn | 0.53.0 | Runs the FastAPI ASGI application. |

## ML and data science

| Technology | Version | What it does in this project |
|---|---:|---|
| NumPy | 2.5.3 | Supports numerical feature and evaluation operations. |
| SciPy | 1.18.1 | Supplies scientific routines required by scikit-learn. |
| scikit-learn | 1.9.1 | Builds and serves TrustShield's TF-IDF classifiers and calibration. |
| joblib | 1.6.0 | Serializes trained classifier artifacts and loads them for prediction. |
| pandas | 3.0.6 | Tabular dataset preparation and analysis in the training environment. |
| datasets | 5.0.1 | Loads datasets used by preparation/training scripts. |
| huggingface_hub | 1.32.0 | Downloads source datasets in preparation scripts when required. |
| hf-xet | 1.6.0 | Transfer support used by Hugging Face Hub downloads. |
| pyarrow | 25.0.1 | Columnar data support used by the Hugging Face datasets stack. |
| matplotlib | not pinned in the checked-in full lock | Optional plotting imports in training/evaluation scripts; the package is named in `requirements.txt` but is absent from `requirements-lock.txt`. |
| segno | 1.6.6 | Creates QR-code images in the demo-fixture generation script. |

`requirements-dev.txt` includes `requirements.txt` plus Segno, Matplotlib, Hugging Face Datasets, and Hugging Face Hub. `requirements-lock.txt` resolves the installable development/training/test environment. The following additional resolved entries complete that lock; packages already listed in the API lock, ML table, or testing table retain those exact versions and purposes above, except for the `filelock` version difference called out below.

| Additional locked package | Version | What it does in this project |
|---|---:|---|
| aiohappyeyeballs | 2.7.1 | Happy Eyeballs connection-racing support used by aiohttp's async networking dependencies. |
| aiohttp | 3.14.3 | Async HTTP client dependency in the Hugging Face dataset/download dependency graph; services use `urllib` for their own network calls. |
| aiosignal | 1.4.0 | Async event signal support used by aiohttp. |
| attrs | 26.1.0 | Attribute/data-class support used by aiohttp and related dependencies. |
| colorama | 0.4.6 | Enables colored terminal output for Python tooling on Windows. |
| dill | 0.4.1 | Extends Python object serialization used by Hugging Face Datasets. |
| filelock | 4.0.1 (dev lock; API lock is 4.0.3) | Provides file-lock support used by model/scientific Python dependencies; the two checked-in locks resolve different versions. |
| frozenlist | 1.8.0 | Immutable list support used by aiohttp. |
| fsspec | 2026.6.0 | Filesystem abstraction used to read local and remote datasets. |
| greenlet | 3.5.6 | Coroutine/runtime support pulled in by the data tooling dependency graph. |
| httpcore | 1.0.9 | Low-level HTTP transport used by HTTPX. |
| iniconfig | 2.3.0 | Configuration parsing support used by pytest. |
| multidict | 6.9.1 | Multi-value HTTP headers/parameters support used by aiohttp. |
| multiprocess | 0.70.19 | Parallel mapping support used by Hugging Face Datasets. |
| pluggy | 1.6.0 | Plugin mechanism used by pytest. |
| propcache | 0.5.4 | Caching support used by aiohttp. |
| pyee | 13.0.1 | Event-emitter support used by Playwright's Python bindings. |
| Pygments | 2.21.0 | Syntax highlighting support used in Python test/tool output. |
| python-dateutil | 2.9.0.post0 | Date parsing utilities used in the data tooling dependency graph. |
| python-dotenv | 1.2.3 | Reads `.env` files for local Python tooling where explicitly requested. |
| PyYAML | 6.0.3 | YAML parsing support for scripts/configuration tooling. |
| six | 1.17.0 | Compatibility utilities used by date/time dependencies. |
| tqdm | 4.70.1 | Progress indicators used by dataset/model preparation tooling. |
| tzdata | 2026.4 | Time-zone database data for Python date/time operations. |
| xxhash | 4.0.1 | Fast hashing support used by Hugging Face Datasets for dataset caching/fingerprints. |
| yarl | 1.25.1 | URL representation and parsing support used by aiohttp. |

The full lock also contains the already-listed runtime packages plus `httpx` 0.28.1, `pytest` 9.1.1, `playwright` 1.63.0, `pandas` 3.0.6, `datasets` 5.0.1, `huggingface_hub` 1.32.0, and the other ML/OCR packages above. `pydantic-settings` is requested by `requirements.txt` but does not appear in `requirements-lock.txt`; it is not part of the deployed API image.

### Promoted text model and feature path

The default artifact is `trustshield_text_v3` (metadata `model_version` phase3-1), loaded by `services/analysis/ml/classifier.py`. Its metadata records a two-stage architecture, `linear_svc` family, combined word-and-character TF-IDF features, five-fold isotonic calibration (`sklearn-CalibratedClassifierCV-v1`), and a malicious-probability threshold of `0.16`. The artifact is version/hash checked and the runtime requires scikit-learn 1.9.1.

The feature pipeline (`trustshield-text-features-v1`) combines lowercased word TF-IDF with word uni/bi-grams and character-within-word n-grams of sizes 2–5. In the two-stage artifact, the calibrated binary LinearSVC estimates malicious probability; scores at or above 0.16 are eligible for the separate category classifier, which predicts a fraud category. The model is an additional statistical signal: text rules and other evidence sources remain separate and feed the configured risk-fusion logic. OCR text and readable QR payload text can use the same text-analysis path; URL-only scans do not run this text classifier. Metadata and further training/evaluation rules are in `docs/ML_PIPELINE.md`.

## OCR and QR decoding

The implementation is in `services/analysis/extractors/ocr.py` and `services/analysis/extractors/qr.py` (there are no `services/analysis/ocr/` or `services/analysis/qr/` directories in this checkout).

| Technology | Version | What it does in this project |
|---|---:|---|
| Tesseract OCR executable | 5.3.0 in the documented Vercel build; installed from Debian packages | Performs local image-to-text recognition. The API image installs `tesseract-ocr` and English data (`tesseract-ocr-eng`). |
| pytesseract | 0.3.13 | Python adapter that calls Tesseract and returns recognized text and word-level confidence data. |
| Pillow | 12.3.0 | Opens and preprocesses uploaded images before OCR and QR analysis. |
| OpenCV (`opencv-python-headless`) | 5.0.0.93 | Uses `cv2.QRCodeDetector` plus grayscale, threshold, padding, and resize variants to decode QR payloads without a GUI. |
| NumPy | 2.5.3 | Supplies image arrays passed to OpenCV. |

OCR defaults to the Tesseract provider; its mock provider is explicitly for demo/test use. QR recognition is local OpenCV processing. Readable QR text is passed into the text analysis pipeline, while QR-contained URLs are also analyzed as URLs.

## Frontend framework and libraries

Versions below are the resolved package-lock versions, not inferred from the semver ranges in `package.json`.

| Technology | Version | What it does in this project |
|---|---:|---|
| Next.js | 16.3.6 | Provides the React app router, pages/layouts, client bundles, and production web server. |
| React | 19.2.8 | Renders the interactive scanner, results, account, history, and chat interfaces. |
| React DOM | 19.2.8 | Mounts React UI into the browser DOM. |
| TypeScript | 5.9.3 | Type-checks frontend code and API result shapes. |
| Tailwind CSS | 4.3.3 | Styles the interface through utility classes and the Tailwind PostCSS integration. |
| @tailwindcss/postcss | 4.3.3 | Runs Tailwind CSS as the frontend's PostCSS plugin. |
| @supabase/supabase-js | 2.117.3 | Handles browser-side Supabase sign-in/session operations. |
| shadcn | 4.21.4 | Generates/maintains the project's locally checked-in UI component source. |
| @base-ui/react | 1.9.0 | Supplies accessible React primitives used by local button, input, and dialog components. |
| class-variance-authority | 0.7.1 | Defines component style variants, including button variants. |
| cn | 0.4.0 | Provides the class-name helper imported by the local UI components. |
| lucide-react | 1.55.0 | Supplies icons used by UI components. |
| tw-animate-css | 1.4.0 | Supplies Tailwind-compatible animation utility styles. |
| eslint | 9.39.5 | Runs frontend lint checks through the package script. |
| eslint-config-next | 16.3.6 | Applies Next.js-specific ESLint rules. |
| @types/node | 20.19.43 | Provides Node.js declarations for TypeScript configuration/build code. |
| @types/react | 19.3.0 | Provides React declarations for TypeScript. |
| @types/react-dom | 19.3.0 | Provides React DOM declarations for TypeScript. |

The UI system is shadcn-generated local components styled with Tailwind and built on Base UI primitives; it is not a separately imported `shadcn/ui` runtime package. `package.json` scripts provide `next dev`, `next build`, `next start`, type generation/type checking, and ESLint.

## Authentication and database: Supabase

| Service | What it does in this project |
|---|---|
| Supabase Auth | Optional email/password accounts; authentication is not required to scan. The frontend uses the publishable key with `@supabase/supabase-js`. |
| Supabase Postgres | Stores a user's `scan_history` entries (input type/summary, risk score/label, result JSON, and timestamp). |
| PostgreSQL Row Level Security | Restricts history reads/deletes to the owning `auth.uid()`; backend writes use its server-only secret/service-role key and are additionally filtered by user ID. |

The browser uses `NEXT_PUBLIC_SUPABASE_URL` and `NEXT_PUBLIC_SUPABASE_ANON_KEY` (the current publishable-key format); the backend uses `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY`. Supabase configuration is optional, and the setup/schema details are in `docs/SUPABASE_SETUP.md`.

## Testing and quality tools

| Technology | Version | What it does in this project |
|---|---:|---|
| pytest | 9.1.1 | Runs Python unit, integration, and security-focused test suites. |
| httpx | 0.28.1 | Provides the HTTP client used by FastAPI's `TestClient` test setup. |
| Playwright Python | 1.63.0 | Drives browser end-to-end and manual QA scripts; the sandbox image also uses the Playwright Python base image but is not currently deployed. |
| TypeScript compiler | 5.9.3 | Checks frontend types through `npm run typecheck`. |
| ESLint | 9.39.5 | Checks frontend code through `npm run lint`. |

## External APIs and services

| Service | What it does in this project |
|---|---|
| Google Safe Browsing API v4 (`threatMatches:find`) | Optional domain threat-list lookup. When enabled, the service sends the registered domain as a root URL, not the submitted message or full page; it requires `GOOGLE_SAFE_BROWSING_KEY` and can be switched off with `TRUSTSHIELD_SAFE_BROWSING=off`. |
| crt.sh certificate-transparency search | Looks up public certificate history for the registered domain to derive age/history evidence; it does not fetch the site or send the message/full submitted URL. It can be disabled with `TRUSTSHIELD_REPUTATION=off`. |
| OpenAI-compatible chat-completions endpoint | Optional remote conversational explanation provider, selected only with explicit provider/base URL/API key configuration. The implementation uses Python `urllib`; it is not part of the verdict/model path. |
| 9Router local endpoint (`http://127.0.0.1:20128/v1`) | Documented and verified local OpenAI-compatible route for development; provider/model selection comes from environment variables. This endpoint is not a public deployed dependency. |
| Ollama local server | Development-only option for conversational responses; production/staging refuse this provider. |
| Supabase hosted project | Provides the optional Auth and Postgres service described above. |

`.env.example` does not contain an NVIDIA endpoint or provider setting, and searches of the service code and provider documentation found no NVIDIA-hosted LLM integration. OpenAI and Groq appear as provider recommendations in `docs/PROVIDER_CONFIGURATION.md`, not as enabled production services. The checked-in Vercel configuration leaves the LLM provider unset, so deployed explanations use the deterministic provider.

## Deployment and runtime

| Technology/service | What it does in this project |
|---|---|
| Vercel Services | Hosts one project with the Next.js web service at `/` and a FastAPI container service at `/api/*`, routed by the generated `vercel.json`. |
| Vercel CLI | Uploads/deploys a locally assembled bundle; the documented flow does not use Git integration for model artifacts. |
| Docker / Docker Compose | Builds and runs the API locally with production-like restrictions; Compose exposes port 8000 and applies read-only root filesystem, temporary `/tmp`, dropped Linux capabilities, no-new-privileges, CPU/memory/PID limits. |
| `python:3.13-slim-bookworm` | Pinned container base image used by API and Vercel API Dockerfiles. The API Dockerfile pins its base by image digest. |
| `docker/api.Dockerfile` | Builds the standalone API runtime with the exact API lock, Tesseract, verified model artifact, non-root UID 10001, health check, and one Uvicorn worker. |
| `docker/Dockerfile.vercel` | Builds the Vercel API container from the assembled bundle and rechecks both v3 and v1 artifact hashes. |
| `docker/sandbox.Dockerfile` | Defines a separate Playwright/Chromium browser worker image; it is intentionally not deployed and web sandboxing remains disabled. |
| Debian Tesseract packages | Install the OCR engine and English language data in API container images. |
| `scripts/build_vercel_bundle.py` | Verifies v3/v1 artifact SHA-256 values against the release manifest and assembles the `web/`, `api/`, and routing manifest bundle. |
| Private GitHub Release artifacts | Source of the promoted model files at bundle preparation time; the local fetch/build process verifies hashes before deploying, and the API checks artifacts again at startup. |

The deployment runtime uses the promoted pre-trained artifact; training is not run during image build/deployment. See `docs/VERCEL_DEPLOYMENT.md` and `docs/DEPLOYMENT_ARCHITECTURE.md` for the deployment procedure and limits.

## Why these choices

TrustShield keeps the verdict path inspectable: deterministic text/URL rules and reputation or image/QR evidence are combined with a calibrated statistical text classifier. OCR and QR parsing run locally in the API. A conversational LLM is an optional explanation layer that must stay grounded in the structured analysis; it does not decide the risk verdict, and the deployed configuration uses deterministic explanations by default. Accounts and scan history are opt-in, while anonymous scanning remains available. The API and web app are deployed together under one origin, and model artifacts are hash-verified before use.
