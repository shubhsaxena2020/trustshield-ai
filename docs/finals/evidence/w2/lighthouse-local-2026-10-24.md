# Local Lighthouse evidence (2026-10-24)

- Lighthouse 13.5.0 against the optimized local `next start` server at `http://localhost:3000/` after `npm run build`.
- Scores: Performance 94/100; Accessibility 100/100.
- Largest Contentful Paint: 3.0 s. Page-load CLS: 0.065 (one shift on the main element).
- The required CLS-under-0.02 check during model streaming was not measured: the product chat request returned the configured model-unavailable message, so there was no model stream to measure.
- Full Lighthouse JSON is retained in ignored `work/finals/two-hour-w2/lighthouse-production.json`.
