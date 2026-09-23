import { AnalysisResult } from "./types";
import { LABELS, SEV_BADGE, pretty } from "./utils";
import { OcrPanel } from "./OcrPanel";
import { QrPanel } from "./QrPanel";

interface ResultDashboardProps {
  result: AnalysisResult;
}

export function ResultDashboard({ result }: ResultDashboardProps) {
  const label =
    LABELS[result.risk.label] ?? {
      text: pretty(result.risk.label || "unknown"),
      tone: "border-slate-500 bg-slate-100 text-slate-900 dark:bg-slate-800 dark:text-slate-100",
      icon: "?",
    };

  const showOcr = Boolean(result.modules?.ocr || result.input_type === "image");
  const showQr = Boolean(result.modules?.qr || result.input_type === "qr");
  const evidenceList = result.evidence ?? [];
  const recommendedActions = result.recommended_actions ?? [];
  const limitations = result.limitations ?? [];
  const moduleEntries = Object.entries(result.modules ?? {});

  return (
    <section aria-labelledby="result-h" className="mt-8 space-y-6">
      <h2 id="result-h" className="sr-only">
        Assessment
      </h2>

      <div className={`rounded-lg border-2 p-5 ${label.tone}`}>
        <p className="text-2xl font-semibold">
          <span aria-hidden>{label.icon} </span>
          {label.text}
        </p>
        <dl className="mt-3 grid grid-cols-1 sm:grid-cols-3 gap-4 text-sm">
          <div>
            <dt className="opacity-80">Risk score</dt>
            <dd className="text-lg font-semibold">{result.risk.score}/100</dd>
          </div>
          <div>
            <dt className="opacity-80">Confidence</dt>
            <dd className="text-lg font-semibold">{pretty(result.risk.confidence || "")}</dd>
          </div>
          <div>
            <dt className="opacity-80">Possible category</dt>
            <dd className="text-lg font-semibold">
              {pretty(result.classification.primary || "")}
            </dd>
          </div>
        </dl>
      </div>

      <div>
        <h3 className="font-semibold">Explanation</h3>
        <p className="mt-1 text-slate-800 dark:text-slate-200">{result.explanation}</p>
      </div>

      {showOcr && result.modules?.ocr && <OcrPanel ocr={result.modules.ocr} />}

      {showQr && result.modules?.qr && <QrPanel qr={result.modules.qr} />}

      <div>
        <h3 className="font-semibold">Evidence ({evidenceList.length})</h3>
        {evidenceList.length === 0 && (
          <p className="mt-1 text-slate-600 dark:text-slate-400">
            No indicators were detected by the modules that ran.
          </p>
        )}
        <ul className="mt-2 space-y-2">
          {evidenceList.map((ev) => (
            <li key={ev.id}>
              <details className="rounded-md border border-slate-300 p-3 dark:border-slate-700">
                <summary className="cursor-pointer">
                  <span
                    className={`mr-2 rounded px-2 py-0.5 text-xs font-medium ${
                      SEV_BADGE[ev.severity] ?? "bg-slate-200 text-slate-800 dark:bg-slate-700 dark:text-slate-100"
                    }`}
                  >
                    {pretty(ev.severity)}
                  </span>
                  {ev.title}
                </summary>
                <p className="mt-2 text-sm">{ev.description}</p>
                <p className="mt-1 text-xs text-slate-600 dark:text-slate-400">
                  Source: {ev.source} · Detector confidence: {Math.round(ev.confidence * 100)}%
                </p>
              </details>
            </li>
          ))}
        </ul>
      </div>

      <div>
        <h3 className="font-semibold">Recommended actions</h3>
        {recommendedActions.length === 0 ? (
          <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
            No specific actions recommended.
          </p>
        ) : (
          <ul className="mt-1 list-disc space-y-1 pl-5">
            {recommendedActions.map((action, i) => (
              <li key={i}>{action}</li>
            ))}
          </ul>
        )}
      </div>

      <div>
        <h3 className="font-semibold">Analysis coverage</h3>
        <ul className="mt-1 text-sm space-y-1">
          {moduleEntries.map(([k, m]) => (
            <li key={k}>
              <span className="font-mono">{k}</span>: {pretty(m.status)}
              {m.detail ? ` — ${m.detail}` : ""}
            </li>
          ))}
        </ul>
        {limitations.length > 0 && (
          <ul className="mt-2 list-disc pl-5 text-sm text-slate-700 dark:text-slate-300">
            {limitations.map((lim, i) => (
              <li key={i}>{lim}</li>
            ))}
          </ul>
        )}
      </div>

      <details className="rounded-md border border-slate-300 p-3 dark:border-slate-700">
        <summary className="cursor-pointer font-semibold">Technical details</summary>
        <pre className="mt-2 max-h-96 overflow-auto text-xs">{JSON.stringify(result, null, 2)}</pre>
      </details>
    </section>
  );
}
