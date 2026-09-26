"use client";

import { useEffect, useState } from "react";
import { AnalysisResult } from "@/components/types";
import { FileUpload } from "@/components/FileUpload";
import { ResultDashboard } from "@/components/ResultDashboard";

const API = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8000";

type TabMode = "text" | "url" | "image" | "qr";

const TABS: { id: TabMode; label: string }[] = [
  { id: "text", label: "Text" },
  { id: "url", label: "URL" },
  { id: "image", label: "Screenshot" },
  { id: "qr", label: "QR Code" },
];

export default function Home() {
  const [mode, setMode] = useState<TabMode>("text");
  const [value, setValue] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState<AnalysisResult | null>(null);
  const [ocrHealthy, setOcrHealthy] = useState<boolean | null>(null);
  const [mlHealthy, setMlHealthy] = useState<boolean | null>(null);
  const [sandboxAvailable, setSandboxAvailable] = useState(false);
  const [useSandbox, setUseSandbox] = useState(false);

  // Check backend health for optional OCR and ML module status notice
  useEffect(() => {
    let isMounted = true;
    fetch(`${API}/api/v1/health`)
      .then((res) => (res.ok ? res.json() : null))
      .then((data) => {
        if (!isMounted) return;
        const ocr = data?.modules?.ocr;
        if (typeof ocr === "boolean") setOcrHealthy(ocr);
        else if (ocr && typeof ocr.available === "boolean") setOcrHealthy(ocr.available);

        setSandboxAvailable(data?.modules?.web_sandbox === true);

        const ml = data?.modules?.ml;
        if (typeof ml === "boolean") setMlHealthy(ml);
        else if (ml && typeof ml.available === "boolean") setMlHealthy(ml.available);
      })
      .catch(() => {
        // Fail gracefully if health check fails or is unreachable
      });

    return () => {
      isMounted = false;
    };
  }, []);

  function handleTabChange(nextMode: TabMode) {
    if (nextMode === mode) return;
    setMode(nextMode);
    setValue("");
    setFile(null);
    setError("");
    setResult(null);
    setUseSandbox(false);
  }

  async function analyze(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    setResult(null);

    try {
      let res: Response;

      if (mode === "text") {
        res = await fetch(`${API}/api/v1/analyze/text`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ text: value }),
        });
      } else if (mode === "url") {
        res = await fetch(`${API}/api/v1/analyze/url`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ url: value, sandbox: useSandbox && sandboxAvailable }),
        });
      } else {
        if (!file) {
          throw new Error("Please select an image file first.");
        }
        const formData = new FormData();
        formData.append("file", file);
        const endpoint = mode === "image" ? "image" : "qr";

        // Do not set Content-Type header manually; let fetch set the boundary
        res = await fetch(`${API}/api/v1/analyze/${endpoint}`, {
          method: "POST",
          body: formData,
        });
      }

      if (!res.ok) {
        let detailMsg = `Request failed (${res.status})`;
        try {
          const errJson = await res.json();
          if (errJson && typeof errJson.detail === "string") {
            detailMsg = errJson.detail;
          } else if (errJson && Array.isArray(errJson.detail)) {
            detailMsg = errJson.detail
              .map((d: { msg?: string }) => d.msg ?? JSON.stringify(d))
              .join(", ");
          }
        } catch {
          // Response body was not JSON
        }
        throw new Error(detailMsg);
      }

      let data: AnalysisResult = await res.json();
      setResult(data);
      // The isolated-browser inspection finishes in the background; poll until it settles.
      for (let attempt = 0; data.status === "sandboxing" && attempt < 40; attempt++) {
        await new Promise((resolve) => setTimeout(resolve, 1500));
        const poll = await fetch(`${API}/api/v1/analysis/${data.analysis_id}`);
        if (!poll.ok) break;
        data = await poll.json();
        setResult(data);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not reach the analysis service.");
    } finally {
      setBusy(false);
    }
  }

  const isSubmitDisabled =
    busy || (mode === "text" || mode === "url" ? !value.trim() : !file);

  return (
    <main className="mx-auto w-full max-w-4xl px-4 py-8 sm:px-6">
      <header className="mb-8">
        <p className="text-sm font-medium tracking-wide text-slate-600 dark:text-slate-400">
          TRUSTSHIELD AI · TEAM SCAM SCAN
        </p>
        <h1 className="mt-1 text-3xl font-semibold">Think Before You Trust.</h1>
        <p className="mt-2 max-w-2xl text-slate-700 dark:text-slate-300">
          Check a suspicious message or link before you click, pay or share anything. Every result
          lists the evidence behind it.
        </p>
      </header>

      {mlHealthy === false && (
        <p
          role="status"
          data-testid="ml-unavailable-banner"
          className="mb-4 rounded-md border border-amber-600 bg-amber-50 p-3 text-sm font-medium text-amber-900 dark:border-amber-400 dark:bg-amber-950 dark:text-amber-200"
        >
          Notice: ML analysis is unavailable on this server; results use rule-based analysis only and
          are less complete.
        </p>
      )}

      <section
        aria-labelledby="analyze-h"
        className="rounded-lg border border-slate-300 p-4 dark:border-slate-700"
      >
        <h2 id="analyze-h" className="sr-only">
          Analyze suspicious content
        </h2>

        <div role="tablist" aria-label="Input type" className="mb-4 flex flex-wrap gap-2">
          {TABS.map((tab) => {
            const isSelected = mode === tab.id;
            return (
              <button
                key={tab.id}
                role="tab"
                aria-selected={isSelected}
                onClick={() => handleTabChange(tab.id)}
                className={`rounded-md border px-3 py-1.5 text-sm transition-colors focus:outline-none focus:ring-2 focus:ring-slate-500 ${
                  isSelected
                    ? "border-slate-900 bg-slate-900 text-white dark:border-white dark:bg-white dark:text-slate-900"
                    : "border-slate-300 hover:bg-slate-100 dark:border-slate-600 dark:hover:bg-slate-800"
                }`}
              >
                {tab.label}
              </button>
            );
          })}
        </div>

        <form onSubmit={analyze}>
          {mode === "text" && (
            <div>
              <label htmlFor="input" className="mb-1 block text-sm font-medium">
                Paste the message
              </label>
              <textarea
                id="input"
                value={value}
                onChange={(e) => setValue(e.target.value)}
                rows={6}
                maxLength={20000}
                required
                disabled={busy}
                className="w-full rounded-md border border-slate-300 bg-transparent p-3 dark:border-slate-600 focus:outline-none focus:ring-2 focus:ring-slate-500"
                placeholder="e.g. Your bank KYC expires today. Verify immediately at ..."
              />
            </div>
          )}

          {mode === "url" && (
            <div>
              <label htmlFor="input" className="mb-1 block text-sm font-medium">
                Paste the link (it will not be opened in your browser)
              </label>
              <input
                id="input"
                type="text"
                value={value}
                onChange={(e) => setValue(e.target.value)}
                maxLength={2048}
                required
                disabled={busy}
                className="w-full rounded-md border border-slate-300 bg-transparent p-3 dark:border-slate-600 focus:outline-none focus:ring-2 focus:ring-slate-500"
                placeholder="https://..."
              />
              {sandboxAvailable && (
                <label className="mt-3 flex items-start gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={useSandbox}
                    onChange={(e) => setUseSandbox(e.target.checked)}
                    disabled={busy}
                    className="mt-1"
                  />
                  <span>
                    Also inspect the page in an isolated server-side browser. It never fills or
                    submits forms and blocks private addresses.
                  </span>
                </label>
              )}
            </div>
          )}

          {mode === "image" && (
            <div>
              <FileUpload
                id="screenshot-file-input"
                label="Upload screenshot"
                explanation="This image is analysed by the service and not stored."
                file={file}
                onFileSelect={setFile}
                onError={setError}
                disabled={busy}
              />
              {ocrHealthy === false && (
                <p className="mt-2 text-xs text-amber-800 dark:text-amber-300">
                  Notice: Optical character recognition (OCR) is currently unavailable on the
                  server; screenshot text analysis may be limited.
                </p>
              )}
            </div>
          )}

          {mode === "qr" && (
            <div>
              <FileUpload
                id="qr-file-input"
                label="Upload QR code"
                explanation="This image is analysed by the service and not stored. The decoded link is not opened."
                file={file}
                onFileSelect={setFile}
                onError={setError}
                disabled={busy}
              />
            </div>
          )}

          <button
            type="submit"
            disabled={isSubmitDisabled}
            className="mt-4 rounded-md bg-blue-700 px-4 py-2 font-medium text-white transition-colors hover:bg-blue-800 disabled:opacity-50 disabled:cursor-not-allowed focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 dark:focus:ring-offset-slate-900"
          >
            {busy ? "Analyzing…" : "Analyze Suspicious Content"}
          </button>
        </form>

        {error && (
          <p role="alert" className="mt-3 text-sm font-medium text-red-700 dark:text-red-300">
            {error}
          </p>
        )}
      </section>

      {result && <ResultDashboard result={result} />}
    </main>
  );
}
