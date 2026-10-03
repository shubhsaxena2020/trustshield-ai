# TrustShield AI

Analyses a message, link, screenshot or QR code for fraud and phishing risk, and explains the result in plain language.

Written for: engineers and reviewers joining the project, and the presenter preparing for the tech-expo screening.

## Start here

| File | Read it when you need to know |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | how the system is put together and why |
| [DETECTION.md](DETECTION.md) | how a message becomes a risk score |
| [API.md](API.md) | the HTTP endpoints and their contracts |
| [CHAT_ASSISTANT.md](CHAT_ASSISTANT.md) | how the conversational layer answers without changing the verdict |
| [CONFIGURATION.md](CONFIGURATION.md) | every environment variable the code reads |
| [DEPLOYMENT.md](DEPLOYMENT.md) | how to run it locally, and the expo booth setup |
| [SECURITY.md](SECURITY.md) | trust boundaries, known risks, what must never be exposed |
| [MODEL.md](MODEL.md) | the fraud classifier: what it is, how it was validated, how it is released |
| [TESTING.md](TESTING.md) | how to run the tests and which data must never be touched |
| [DECISIONS.md](DECISIONS.md) | why things are the way they are, and what was rejected |

## Quick start

```bash
python -m venv .venv && .venv\Scripts\activate
pip install -r requirements.txt
uvicorn services.api.main:app --host 127.0.0.1 --port 8000
```

Then `cd apps/web && npm install && npm run dev` for the frontend on port 3000.

## Where older material lives

Around seventy earlier documents remain in `docs/`. They record the history of decisions, benchmarks and reviews, and are
kept as evidence. The files above are the current, authoritative description; where they disagree with an older
document, these win.
