import { ModuleResult, OcrModuleData } from "./types";
import { pretty } from "./utils";

interface OcrPanelProps {
  ocr?: ModuleResult;
}

export function OcrPanel({ ocr }: OcrPanelProps) {
  if (!ocr) return null;

  const data = (ocr.data ?? {}) as OcrModuleData;
  const provider = data.provider;
  const version = data.provider_version;
  const isMock = provider?.toLowerCase() === "mock";
  const confidence = data.confidence;
  const preprocessing = data.preprocessing;
  const warnings = data.warnings ?? [];
  const rawText = data.raw_text ?? data.text ?? "";
  const normalizedText = data.normalized_text;
  const hasNormalized = normalizedText !== undefined && normalizedText !== null;
  const hasDifference = hasNormalized && rawText !== normalizedText;
  const isFailedOrUnavailable = ocr.status === "failed" || ocr.status === "unavailable";

  return (
    <div className="rounded-lg border border-slate-300 p-4 dark:border-slate-700">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-base font-semibold">Extraction (OCR)</h3>
        <span className="rounded-full border border-slate-300 px-2.5 py-0.5 text-xs font-medium text-slate-700 dark:border-slate-600 dark:text-slate-300">
          Status: {pretty(ocr.status)}
        </span>
      </div>

      {isMock && (
        <div
          role="status"
          className="mt-2.5 inline-flex items-center gap-1.5 rounded border border-amber-600 bg-amber-50 px-2.5 py-1 text-xs font-semibold text-amber-900 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-100"
        >
          <span aria-hidden>ℹ</span>
          <span>Demo mode: mock OCR — text was read from demo-file metadata, not recognised from pixels</span>
        </div>
      )}

      <dl className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-3 text-xs">
        <div>
          <dt className="text-slate-500 dark:text-slate-400">Engine / Provider</dt>
          <dd className="mt-0.5 font-medium text-slate-900 dark:text-slate-100">
            {provider ? (
              <>
                {provider}
                {version ? ` (v${version})` : ""}
              </>
            ) : (
              "Not specified"
            )}
          </dd>
        </div>
        <div>
          <dt className="text-slate-500 dark:text-slate-400">Extraction Confidence</dt>
          <dd className="mt-0.5 font-medium text-slate-900 dark:text-slate-100">
            {typeof confidence === "number" && !Number.isNaN(confidence)
              ? `${confidence.toFixed(1)}% (mean word confidence)`
              : "Not reported by this OCR engine"}
          </dd>
        </div>
        <div>
          <dt className="text-slate-500 dark:text-slate-400">Preprocessing Mode</dt>
          <dd className="mt-0.5 font-medium text-slate-900 dark:text-slate-100">
            {preprocessing || "Not specified"}
          </dd>
        </div>
      </dl>

      {warnings.length > 0 && (
        <div className="mt-3 rounded-md border border-amber-300 bg-amber-50/50 p-2.5 dark:border-amber-800 dark:bg-amber-950/30">
          <p className="flex items-center gap-1.5 text-xs font-medium text-amber-900 dark:text-amber-200">
            <span aria-hidden>⚠</span>
            <span>Warnings:</span>
          </p>
          <ul className="mt-1 list-disc pl-5 text-xs text-amber-800 dark:text-amber-300 space-y-0.5">
            {warnings.map((warning, idx) => (
              <li key={idx}>{warning}</li>
            ))}
          </ul>
        </div>
      )}

      {isFailedOrUnavailable && (
        <div className="mt-3 rounded-md border border-slate-300 bg-slate-50 p-3 text-sm text-slate-800 dark:border-slate-700 dark:bg-slate-800/60 dark:text-slate-200">
          <p>
            {ocr.detail ||
              (ocr.status === "unavailable"
                ? "Optical character recognition (OCR) is currently unavailable on the server."
                : "Failed to extract text from the provided image.")}
          </p>
        </div>
      )}

      {ocr.status === "completed" && (
        <div className="mt-3 space-y-2">
          <h4 className="text-xs font-semibold text-slate-700 dark:text-slate-300">Extracted Text</h4>
          {hasDifference && (
            <p className="flex items-center gap-1.5 text-xs text-slate-600 dark:text-slate-400">
              <span aria-hidden>ℹ</span>
              <span>Normalization changed the text (whitespace / character cleanup)</span>
            </p>
          )}

          <details className="rounded-md border border-slate-300 p-2.5 text-xs dark:border-slate-700">
            <summary className="cursor-pointer font-semibold text-slate-700 dark:text-slate-300">
              Raw extracted text
            </summary>
            <pre className="mt-2 max-h-60 overflow-auto whitespace-pre-wrap break-words rounded border border-slate-200 bg-slate-50 p-2.5 font-mono text-xs text-slate-800 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200">
              {rawText && rawText.trim() ? rawText : "(No text detected in image)"}
            </pre>
          </details>

          {hasNormalized && (
            <details className="rounded-md border border-slate-300 p-2.5 text-xs dark:border-slate-700">
              <summary className="cursor-pointer font-semibold text-slate-700 dark:text-slate-300">
                Normalized text used for analysis
              </summary>
              <pre className="mt-2 max-h-60 overflow-auto whitespace-pre-wrap break-words rounded border border-slate-200 bg-slate-50 p-2.5 font-mono text-xs text-slate-800 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200">
                {normalizedText && normalizedText.trim() ? normalizedText : "(No text detected in image)"}
              </pre>
            </details>
          )}
        </div>
      )}
    </div>
  );
}
