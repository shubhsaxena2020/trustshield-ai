import { MlModuleData, ModuleResult } from "./types";
import { pretty } from "./utils";

interface MlPanelProps {
  ml?: ModuleResult;
}

/**
 * Derives a user-friendly confidence band ("High", "Medium", "Low")
 * from the calibrated probability of maliciousness (p_malicious).
 *
 * Distance from uncertainty threshold:
 * - High confidence: Decisive estimate — p >= 0.90 (strongly malicious) or p <= 0.10 (strongly benign)
 * - Medium confidence: Moderately clear estimate — 0.75 <= p < 0.90 or 0.10 < p <= 0.25
 * - Low confidence: Borderline / ambiguous estimate — 0.25 < p < 0.75 (near the decision boundary)
 *
 * This is display-only; labeled as "model confidence (calibrated probability band)".
 */
function getConfidenceBand(p: number | undefined): "High" | "Medium" | "Low" {
  if (typeof p !== "number" || Number.isNaN(p)) {
    return "Low";
  }
  if (p >= 0.9 || p <= 0.1) {
    return "High";
  }
  if ((p >= 0.75 && p < 0.9) || (p > 0.1 && p <= 0.25)) {
    return "Medium";
  }
  return "Low";
}

function getFriendlyClassification(data: MlModuleData): string {
  const isExplicitLegit =
    data.predicted_binary === "legitimate" ||
    (data.predicted_binary as unknown) === false;

  if (isExplicitLegit) {
    return "No fraud pattern detected";
  }

  if (
    data.category &&
    data.category.toLowerCase() !== "legitimate" &&
    data.category.toLowerCase() !== "none" &&
    data.category.toLowerCase() !== "unknown"
  ) {
    return pretty(data.category);
  }

  if (
    data.predicted_binary === "malicious_or_fraudulent" ||
    (data.predicted_binary as unknown) === true
  ) {
    return "Suspicious or fraudulent pattern";
  }

  if (typeof data.p_malicious === "number" && typeof data.threshold === "number") {
    if (data.p_malicious < data.threshold) {
      return "No fraud pattern detected";
    }
    return "Suspicious pattern";
  }

  return "No fraud pattern detected";
}

export function MlPanel({ ml }: MlPanelProps) {
  if (!ml) return null;

  const data = (ml.data ?? {}) as MlModuleData;
  const isCompleted = ml.status === "completed";
  const classification = getFriendlyClassification(data);
  const confidence = getConfidenceBand(data.p_malicious);
  const categoryProbs = data.category_probs;
  const hasData = Boolean(ml.data && Object.keys(ml.data).length > 0);

  return (
    <div className="rounded-lg border border-slate-300 p-4 dark:border-slate-700">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-base font-semibold">Machine-learning analysis</h3>
        <span className="rounded-full border border-slate-300 px-2.5 py-0.5 text-xs font-medium text-slate-700 dark:border-slate-600 dark:text-slate-300">
          Status: {pretty(ml.status)}
        </span>
      </div>

      {!isCompleted && (
        <div className="mt-3 rounded-md border border-slate-300 bg-slate-50 p-3 text-sm text-slate-800 dark:border-slate-700 dark:bg-slate-800/60 dark:text-slate-200">
          <p>
            {ml.detail ||
              (ml.status === "unavailable"
                ? "Machine-learning analysis is currently unavailable."
                : ml.status === "not_run"
                ? "Machine-learning analysis was not run for this input."
                : "Machine-learning analysis failed to complete.")}
          </p>
        </div>
      )}

      {isCompleted && (
        <div className="mt-3 space-y-2 text-sm">
          <p>
            <span className="font-medium text-slate-700 dark:text-slate-300">ML classification:</span>{" "}
            <span className="font-semibold text-slate-900 dark:text-slate-100">{classification}</span>
          </p>
          <p>
            <span className="font-medium text-slate-700 dark:text-slate-300">ML confidence:</span>{" "}
            <span className="font-semibold text-slate-900 dark:text-slate-100">{confidence}</span>{" "}
            <span className="text-xs text-slate-500 dark:text-slate-400">
              (model confidence (calibrated probability band))
            </span>
          </p>
        </div>
      )}

      <p className="mt-3 text-xs text-slate-600 dark:text-slate-400">
        The ML result is one evidence source among several; it does not decide the final assessment by itself.
      </p>

      {hasData && (
        <details className="mt-4 rounded-md border border-slate-300 p-3 text-xs dark:border-slate-700">
          <summary className="cursor-pointer font-semibold text-slate-700 dark:text-slate-300">
            Model details
          </summary>
          <div className="mt-3 space-y-3">
            <dl className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              <div>
                <dt className="text-slate-500 dark:text-slate-400">p_malicious</dt>
                <dd className="font-mono font-medium text-slate-900 dark:text-slate-100">
                  {typeof data.p_malicious === "number"
                    ? `${(data.p_malicious * 100).toFixed(1)}%`
                    : "Not specified"}
                </dd>
              </div>
              <div>
                <dt className="text-slate-500 dark:text-slate-400">Threshold</dt>
                <dd className="font-mono font-medium text-slate-900 dark:text-slate-100">
                  {typeof data.threshold === "number"
                    ? `${(data.threshold * 100).toFixed(1)}% (${data.threshold})`
                    : "Not specified"}
                </dd>
              </div>
              <div>
                <dt className="text-slate-500 dark:text-slate-400">Model name / version</dt>
                <dd className="font-medium text-slate-900 dark:text-slate-100">
                  {data.model_name
                    ? `${data.model_name}${data.model_version ? ` v${data.model_version}` : ""}`
                    : "Not specified"}
                </dd>
              </div>
              <div>
                <dt className="text-slate-500 dark:text-slate-400">Dataset version</dt>
                <dd className="font-medium text-slate-900 dark:text-slate-100">
                  {data.dataset_version || "Not specified"}
                </dd>
              </div>
              {data.dataset_manifest_sha256 && (
                <div className="sm:col-span-2">
                  <dt className="text-slate-500 dark:text-slate-400">Dataset manifest SHA-256</dt>
                  <dd className="font-mono break-all text-slate-900 dark:text-slate-100">
                    {data.dataset_manifest_sha256}
                  </dd>
                </div>
              )}
              <div>
                <dt className="text-slate-500 dark:text-slate-400">Feature pipeline</dt>
                <dd className="font-medium text-slate-900 dark:text-slate-100">
                  {data.feature_pipeline_version || "Not specified"}
                </dd>
              </div>
              <div>
                <dt className="text-slate-500 dark:text-slate-400">Calibration</dt>
                <dd className="font-medium text-slate-900 dark:text-slate-100">
                  {data.calibration_version || "Not specified"}
                </dd>
              </div>
              <div>
                <dt className="text-slate-500 dark:text-slate-400">Latency</dt>
                <dd className="font-medium text-slate-900 dark:text-slate-100">
                  {typeof data.latency_ms === "number"
                    ? `${data.latency_ms.toFixed(1)} ms`
                    : "Not specified"}
                </dd>
              </div>
              {data.predicted_binary !== undefined && (
                <div>
                  <dt className="text-slate-500 dark:text-slate-400">Predicted binary</dt>
                  <dd className="font-medium text-slate-900 dark:text-slate-100">
                    {pretty(String(data.predicted_binary))}
                  </dd>
                </div>
              )}
            </dl>

            {categoryProbs && Object.keys(categoryProbs).length > 0 && (
              <div className="mt-3">
                <p className="font-semibold text-slate-700 dark:text-slate-300">
                  Category probabilities
                </p>
                <div className="mt-1 overflow-x-auto">
                  <table className="w-full text-left">
                    <thead>
                      <tr className="border-b border-slate-200 text-slate-500 dark:border-slate-700 dark:text-slate-400">
                        <th className="py-1 pr-4 font-medium">Category</th>
                        <th className="py-1 font-medium">Probability</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                      {Object.entries(categoryProbs).map(([cat, prob]) => (
                        <tr key={cat}>
                          <td className="py-1 pr-4 font-medium text-slate-800 dark:text-slate-200">
                            {pretty(cat)}
                          </td>
                          <td className="py-1 font-mono text-slate-700 dark:text-slate-300">
                            {typeof prob === "number"
                              ? `${(prob * 100).toFixed(1)}%`
                              : String(prob)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
          </div>
        </details>
      )}
    </div>
  );
}
