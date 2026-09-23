# API (schema_version 1.0)
- `GET /api/v1/health`
- `POST /api/v1/analyze/text` — `{ "text": str (<=20000) }`
- `POST /api/v1/analyze/url` — `{ "url": str (<=2048) }`
- `GET /api/v1/analysis/{id}`

Planned: `/analyze/image`, `/analyze/qr`. All return `AnalysisResult`: risk{score,label,confidence}, classification, evidence[], modules{}, explanation, recommended_actions[], limitations[].
