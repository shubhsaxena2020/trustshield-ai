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
  const warnings = data.warnings ?? [];
  const text = data.text;
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

      <dl className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2 text-xs">
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
            {confidence != null ? `${Math.round(confidence)}%` : "Not reported by this OCR engine"}
          </dd>
        </div>
      </dl>

      {warnings.length > 0 && (
        <div className="mt-3 rounded-md border border-amber-300 bg-amber-50/50 p-2.5 dark:border-amber-800 dark:bg-amber-950/30">
          <p className="text-xs font-medium text-amber-900 dark:text-amber-200">Warnings:</p>
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
        <div className="mt-3">
          <h4 className="text-xs font-semibold text-slate-700 dark:text-slate-300">Extracted Text</h4>
          <pre className="mt-1 max-h-60 overflow-auto whitespace-pre-wrap rounded-md border border-slate-300 bg-slate-50 p-3 font-mono text-xs text-slate-800 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200">
            {text && text.trim() ? text : "(No text detected in image)"}
          </pre>
        </div>
      )}
    </div>
  );
}
