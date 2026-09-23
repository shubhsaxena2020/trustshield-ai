"use client";

import { useState } from "react";

const API = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8000";

type Evidence = { id: string; source: string; category: string; severity: string; confidence: number; title: string; description: string; metadata: Record<string, unknown> };
type Result = {
  analysis_id: string;
  input_type: string;
  status: string;
  risk: { score: number; label: string; confidence: string };
  classification: { primary: string; secondary: string[] };
  evidence: Evidence[];
  modules: Record<string, { status: string; detail: string }>;
  explanation: string;
  recommended_actions: string[];
  limitations: string[];
};

const LABELS: Record<string, { text: string; tone: string; icon: string }> = {
  low_risk: { text: "Low Risk", tone: "border-emerald-600 bg-emerald-50 text-emerald-900 dark:bg-emerald-950 dark:text-emerald-100", icon: "✓" },
  caution: { text: "Caution", tone: "border-amber-500 bg-amber-50 text-amber-900 dark:bg-amber-950 dark:text-amber-100", icon: "!" },
  suspicious: { text: "Suspicious", tone: "border-orange-600 bg-orange-50 text-orange-900 dark:bg-orange-950 dark:text-orange-100", icon: "▲" },
  high_risk: { text: "High Risk", tone: "border-red-700 bg-red-50 text-red-900 dark:bg-red-950 dark:text-red-100", icon: "✕" },
  unable_to_verify: { text: "Unable to Verify", tone: "border-slate-500 bg-slate-100 text-slate-900 dark:bg-slate-800 dark:text-slate-100", icon: "?" },
};
const SEV_BADGE: Record<string, string> = {
  informational: "bg-slate-200 text-slate-800 dark:bg-slate-700 dark:text-slate-100",
  low: "bg-sky-100 text-sky-900 dark:bg-sky-900 dark:text-sky-100",
  medium: "bg-amber-100 text-amber-900 dark:bg-amber-900 dark:text-amber-100",
  high: "bg-orange-100 text-orange-900 dark:bg-orange-900 dark:text-orange-100",
  critical: "bg-red-100 text-red-900 dark:bg-red-900 dark:text-red-100",
};
const pretty = (s: string) => s.replace(/_/g, " ").replace(/^\w/, (c) => c.toUpperCase());

export default function Home() {
  const [mode, setMode] = useState<"text" | "url">("text");
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState<Result | null>(null);

  async function analyze(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true); setError(""); setResult(null);
    try {
      const res = await fetch(`${API}/api/v1/analyze/${mode}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(mode === "text" ? { text: value } : { url: value }),
      });
      if (!res.ok) throw new Error((await res.json().catch(() => ({}))).detail ?? `Request failed (${res.status})`);
      setResult(await res.json());
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not reach the analysis service.");
    } finally {
      setBusy(false);
    }
  }

  const label = result ? LABELS[result.risk.label] : null;

  return (
    <main className="mx-auto w-full max-w-4xl px-4 py-8 sm:px-6">
      <header className="mb-8">
        <p className="text-sm font-medium tracking-wide text-slate-600 dark:text-slate-400">TRUSTSHIELD AI · TEAM SCAM SCAN</p>
        <h1 className="mt-1 text-3xl font-semibold">Think Before You Trust.</h1>
        <p className="mt-2 max-w-2xl text-slate-700 dark:text-slate-300">
          Check a suspicious message or link before you click, pay or share anything. Every result lists the evidence behind it.
        </p>
      </header>

      <section aria-labelledby="analyze-h" className="rounded-lg border border-slate-300 p-4 dark:border-slate-700">
        <h2 id="analyze-h" className="sr-only">Analyze suspicious content</h2>
        <div role="tablist" aria-label="Input type" className="mb-4 flex flex-wrap gap-2">
          {(["text", "url"] as const).map((m) => (
            <button key={m} role="tab" aria-selected={mode === m} onClick={() => { setMode(m); setValue(""); setResult(null); }}
              className={`rounded-md border px-3 py-1.5 text-sm ${mode === m ? "border-slate-900 bg-slate-900 text-white dark:border-white dark:bg-white dark:text-slate-900" : "border-slate-300 dark:border-slate-600"}`}>
              {m === "text" ? "Text" : "URL"}
            </button>
          ))}
          {["Screenshot", "QR Code"].map((m) => (
            <button key={m} disabled title="Coming in the next phase" className="cursor-not-allowed rounded-md border border-dashed border-slate-300 px-3 py-1.5 text-sm text-slate-500 dark:border-slate-600">{m} (soon)</button>
          ))}
        </div>
        <form onSubmit={analyze}>
          <label htmlFor="input" className="mb-1 block text-sm font-medium">{mode === "text" ? "Paste the message" : "Paste the link (it will not be opened in your browser)"}</label>
          {mode === "text" ? (
            <textarea id="input" value={value} onChange={(e) => setValue(e.target.value)} rows={6} maxLength={20000} required
              className="w-full rounded-md border border-slate-300 bg-transparent p-3 dark:border-slate-600" placeholder="e.g. Your bank KYC expires today. Verify immediately at ..." />
          ) : (
            <input id="input" type="text" value={value} onChange={(e) => setValue(e.target.value)} maxLength={2048} required
              className="w-full rounded-md border border-slate-300 bg-transparent p-3 dark:border-slate-600" placeholder="https://..." />
          )}
          <button type="submit" disabled={busy || !value.trim()} className="mt-3 rounded-md bg-blue-700 px-4 py-2 font-medium text-white disabled:opacity-50">
            {busy ? "Analyzing…" : "Analyze Suspicious Content"}
          </button>
        </form>
        {error && <p role="alert" className="mt-3 text-red-700 dark:text-red-300">{error}</p>}
      </section>

      {result && label && (
        <section aria-labelledby="result-h" className="mt-8 space-y-6">
          <h2 id="result-h" className="sr-only">Assessment</h2>
          <div className={`rounded-lg border-2 p-5 ${label.tone}`}>
            <p className="text-2xl font-semibold"><span aria-hidden>{label.icon} </span>{label.text}</p>
            <dl className="mt-3 grid grid-cols-3 gap-4 text-sm">
              <div><dt className="opacity-80">Risk score</dt><dd className="text-lg font-semibold">{result.risk.score}/100</dd></div>
              <div><dt className="opacity-80">Confidence</dt><dd className="text-lg font-semibold">{pretty(result.risk.confidence)}</dd></div>
              <div><dt className="opacity-80">Possible category</dt><dd className="text-lg font-semibold">{pretty(result.classification.primary)}</dd></div>
            </dl>
          </div>

          <div><h3 className="font-semibold">Explanation</h3><p className="mt-1 text-slate-800 dark:text-slate-200">{result.explanation}</p></div>

          <div>
            <h3 className="font-semibold">Evidence ({result.evidence.length})</h3>
            {result.evidence.length === 0 && <p className="mt-1 text-slate-600 dark:text-slate-400">No indicators were detected by the modules that ran.</p>}
            <ul className="mt-2 space-y-2">
              {result.evidence.map((ev) => (
                <li key={ev.id}>
                  <details className="rounded-md border border-slate-300 p-3 dark:border-slate-700">
                    <summary className="cursor-pointer">
                      <span className={`mr-2 rounded px-2 py-0.5 text-xs font-medium ${SEV_BADGE[ev.severity]}`}>{pretty(ev.severity)}</span>{ev.title}
                    </summary>
                    <p className="mt-2 text-sm">{ev.description}</p>
                    <p className="mt-1 text-xs text-slate-600 dark:text-slate-400">Source: {ev.source} · Detector confidence: {Math.round(ev.confidence * 100)}%</p>
                  </details>
                </li>
              ))}
            </ul>
          </div>

          <div>
            <h3 className="font-semibold">Recommended actions</h3>
            <ul className="mt-1 list-disc space-y-1 pl-5">{result.recommended_actions.map((a) => <li key={a}>{a}</li>)}</ul>
          </div>

          <div>
            <h3 className="font-semibold">Analysis coverage</h3>
            <ul className="mt-1 text-sm">
              {Object.entries(result.modules).map(([k, m]) => <li key={k}><span className="font-mono">{k}</span>: {pretty(m.status)}{m.detail ? ` — ${m.detail}` : ""}</li>)}
            </ul>
            {result.limitations.length > 0 && <ul className="mt-2 list-disc pl-5 text-sm text-slate-700 dark:text-slate-300">{result.limitations.map((l) => <li key={l}>{l}</li>)}</ul>}
          </div>

          <details className="rounded-md border border-slate-300 p-3 dark:border-slate-700">
            <summary className="cursor-pointer font-semibold">Technical details</summary>
            <pre className="mt-2 max-h-96 overflow-auto text-xs">{JSON.stringify(result, null, 2)}</pre>
          </details>
        </section>
      )}
    </main>
  );
}
