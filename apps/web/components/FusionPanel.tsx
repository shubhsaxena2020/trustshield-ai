import { FusionModuleData, ModuleResult, StrengthCounts } from "./types";
import { pretty } from "./utils";

interface FusionPanelProps {
  fusion?: ModuleResult;
}

function getAgreementText(agreement?: string | null): string {
  if (typeof agreement !== "string" || !agreement.trim()) {
    return "Not specified";
  }
  switch (agreement) {
    case "agree":
      return "Rules and the ML classifier both flagged this";
    case "ml_only":
      return "Only the ML classifier flagged this; no deterministic rule fired, so confidence is lower";
    case "rules_only":
      return "Deterministic rules flagged this; the ML classifier did not, so confidence is lower";
    case "both_negative":
      return "Neither rules nor the ML classifier flagged this";
    default:
      return agreement;
  }
}

function getAgreementIcon(agreement?: string | null): string {
  switch (agreement) {
    case "agree":
      return "✓";
    case "ml_only":
    case "rules_only":
      return "ℹ";
    case "both_negative":
      return "✓";
    default:
      return "ℹ";
  }
}

export function FusionPanel({ fusion }: FusionPanelProps) {
  if (!fusion) return null;

  const data = (fusion.data ?? {}) as FusionModuleData;
  const agreement = data.agreement;
  const agreementText = getAgreementText(agreement);
  const agreementIcon = getAgreementIcon(agreement);

  const counts: StrengthCounts | undefined =
    data.strength_counts || data.strength_class_counts;
  const hasCounts =
    Boolean(counts) &&
    typeof counts === "object" &&
    (typeof counts.weak === "number" ||
      typeof counts.moderate === "number" ||
      typeof counts.strong === "number");

  const strategy = data.strategy;
  const version = data.version;

  return (
    <div className="rounded-lg border border-slate-300 p-4 dark:border-slate-700">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-base font-semibold">How this assessment was reached</h3>
        {fusion.status && (
          <span className="rounded-full border border-slate-300 px-2.5 py-0.5 text-xs font-medium text-slate-700 dark:border-slate-600 dark:text-slate-300">
            Status: {pretty(fusion.status)}
          </span>
        )}
      </div>

      <div className="mt-3">
        <p className="text-xs font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
          Detector agreement
        </p>
        <p className="mt-1 flex items-start text-sm text-slate-800 dark:text-slate-200">
          <span aria-hidden className="mr-1.5 font-semibold text-slate-600 dark:text-slate-400">
            {agreementIcon}
          </span>
          <span>{agreementText}</span>
        </p>
      </div>

      <p className="mt-2 text-xs text-slate-600 dark:text-slate-400">
        Risk and confidence are separate: disagreement between detectors lowers confidence, not risk.
      </p>

      {hasCounts && (
        <div className="mt-3">
          <p className="text-xs font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
            Evidence strength
          </p>
          <ul className="mt-1 list-disc pl-5 text-sm text-slate-800 dark:text-slate-200 space-y-0.5">
            <li>Weak: {counts?.weak ?? 0}</li>
            <li>Moderate: {counts?.moderate ?? 0}</li>
            <li>Strong: {counts?.strong ?? 0}</li>
          </ul>
        </div>
      )}

      <details className="mt-3 rounded-md border border-slate-300 p-2.5 text-xs dark:border-slate-700">
        <summary className="cursor-pointer font-semibold text-slate-700 dark:text-slate-300">
          Fusion details
        </summary>
        <div className="mt-2 space-y-1 text-slate-700 dark:text-slate-300">
          <p>
            <span className="font-medium text-slate-500 dark:text-slate-400">Strategy:</span>{" "}
            <span className="font-mono">{strategy || "Not specified"}</span>
          </p>
          <p>
            <span className="font-medium text-slate-500 dark:text-slate-400">Version:</span>{" "}
            <span className="font-mono">{version || "Not specified"}</span>
          </p>
          {fusion.detail && (
            <p>
              <span className="font-medium text-slate-500 dark:text-slate-400">Detail:</span>{" "}
              <span>{fusion.detail}</span>
            </p>
          )}
          {Array.isArray(data.drivers) && data.drivers.length > 0 && (
            <p>
              <span className="font-medium text-slate-500 dark:text-slate-400">Drivers:</span>{" "}
              <span>{data.drivers.map((d) => pretty(d)).join(", ")}</span>
            </p>
          )}
        </div>
      </details>
    </div>
  );
}
