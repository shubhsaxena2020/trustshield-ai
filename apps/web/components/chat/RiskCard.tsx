import { LABELS, SEV_BADGE, pretty } from "@/components/utils";
import type { ChatAnalysis } from "./types";

// Risk (how suspicious) and confidence (how much evidence supports the assessment) are shown separately on purpose.
const CONFIDENCE_TEXT = { high: "High", medium: "Medium", low: "Low" } as const;

export function RiskCard({ analysis }: { analysis: ChatAnalysis }) {
  const label = LABELS[analysis.risk_level] ?? LABELS.unable_to_verify;
  const used = analysis.capabilities.filter((c) => c.used);
  const unused = analysis.capabilities.filter((c) => !c.used);
  return (
    <section aria-label="Risk assessment" className="mt-3 rounded-xl border border-slate-300 bg-white p-4 dark:border-slate-700 dark:bg-slate-900">
      <div className="flex flex-wrap items-center gap-3">
        <span className={`inline-flex items-center gap-2 rounded-full border-2 px-3 py-1 text-sm font-semibold ${label.tone}`}>
          <span aria-hidden="true">{label.icon}</span>
          {label.text}
        </span>
        <dl className="flex flex-wrap gap-x-6 gap-y-1 text-sm">
          <div className="flex gap-1">
            <dt className="text-slate-600 dark:text-slate-400">Risk score</dt>
            <dd className="font-semibold">{analysis.risk_score}/100</dd>
          </div>
          <div className="flex gap-1">
            <dt className="text-slate-600 dark:text-slate-400">Confidence</dt>
            <dd className="font-semibold">{CONFIDENCE_TEXT[analysis.confidence]}</dd>
          </div>
        </dl>
      </div>
      <div className="mt-3 h-2 w-full overflow-hidden rounded-full bg-slate-200 dark:bg-slate-700" role="img" aria-label={`Risk score ${analysis.risk_score} out of 100`}>
        <div className="h-full rounded-full bg-teal-700 dark:bg-teal-400" style={{ width: `${Math.min(100, Math.max(0, analysis.risk_score))}%` }} />
      </div>

      {analysis.qr_payload && (
        <p className="mt-3 break-all text-sm">
          <span className="font-medium">QR code contains (not opened): </span>
          <code className="rounded bg-slate-100 px-1 py-0.5 dark:bg-slate-800">{analysis.qr_payload}</code>
        </p>
      )}

      {analysis.evidence.length > 0 && (
        <details className="mt-3 rounded-lg border border-slate-200 dark:border-slate-700">
          <summary className="cursor-pointer px-3 py-2 text-sm font-medium">Evidence ({analysis.evidence.length})</summary>
          <ul className="divide-y divide-slate-200 dark:divide-slate-700">
            {analysis.evidence.map((e) => (
              <li key={e.id} className="px-3 py-2 text-sm">
                <div className="flex flex-wrap items-center gap-2">
                  <span className={`rounded px-1.5 py-0.5 text-xs font-semibold ${SEV_BADGE[e.severity] ?? SEV_BADGE.informational}`}>{pretty(e.severity)}</span>
                  <span className="font-medium">{e.title}</span>
                  <span className="text-xs text-slate-600 dark:text-slate-400">{pretty(e.source_module)}</span>
                </div>
                <p className="mt-1 text-slate-700 dark:text-slate-300">{e.explanation}</p>
                {e.raw_signal && Object.keys(e.raw_signal).length > 0 && (
                  <code className="mt-1 block break-all text-xs text-slate-600 dark:text-slate-400">
                    {Object.entries(e.raw_signal).map(([k, v]) => `${k}: ${String(v)}`).join(" · ")}
                  </code>
                )}
              </li>
            ))}
          </ul>
        </details>
      )}

      {analysis.extracted_content && (
        <details className="mt-2 rounded-lg border border-slate-200 dark:border-slate-700">
          <summary className="cursor-pointer px-3 py-2 text-sm font-medium">Extracted text (untrusted, shown as data)</summary>
          <pre className="max-h-48 overflow-auto whitespace-pre-wrap break-words px-3 pb-3 text-xs">{analysis.extracted_content}</pre>
        </details>
      )}

      <details className="mt-2 rounded-lg border border-slate-200 dark:border-slate-700">
        <summary className="cursor-pointer px-3 py-2 text-sm font-medium">Analysis coverage</summary>
        <div className="grid gap-3 px-3 pb-3 text-sm sm:grid-cols-2">
          <div>
            <p className="font-medium">Used</p>
            <ul className="mt-1 list-disc pl-5">{used.map((c) => <li key={c.name}>{c.description}</li>)}</ul>
          </div>
          <div>
            <p className="font-medium">Not used</p>
            <ul className="mt-1 list-disc pl-5">{unused.map((c) => <li key={c.name}>{c.description} — {c.reason}</li>)}</ul>
          </div>
        </div>
      </details>

      {(analysis.uncertainty.length > 0 || analysis.warnings.length > 0) && (
        <ul className="mt-3 list-disc pl-5 text-sm text-slate-700 dark:text-slate-300">
          {[...analysis.warnings, ...analysis.uncertainty].map((u, i) => <li key={i}>{u}</li>)}
        </ul>
      )}
      {analysis.safe_actions.length > 0 && (
        <div className="mt-3 text-sm">
          <p className="font-medium">Safer next steps</p>
          <ul className="mt-1 list-disc pl-5">{analysis.safe_actions.map((a, i) => <li key={i}>{a}</li>)}</ul>
        </div>
      )}
    </section>
  );
}
