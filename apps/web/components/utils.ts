export const LABELS: Record<string, { text: string; tone: string; icon: string }> = {
  low_risk: {
    text: "Low Risk",
    tone: "border-emerald-600 bg-emerald-50 text-emerald-900 dark:bg-emerald-950 dark:text-emerald-100",
    icon: "✓",
  },
  caution: {
    text: "Caution",
    tone: "border-amber-500 bg-amber-50 text-amber-900 dark:bg-amber-950 dark:text-amber-100",
    icon: "!",
  },
  suspicious: {
    text: "Suspicious",
    tone: "border-orange-600 bg-orange-50 text-orange-900 dark:bg-orange-950 dark:text-orange-100",
    icon: "▲",
  },
  high_risk: {
    text: "High Risk",
    tone: "border-red-700 bg-red-50 text-red-900 dark:bg-red-950 dark:text-red-100",
    icon: "✕",
  },
  unable_to_verify: {
    text: "Unable to Verify",
    tone: "border-slate-500 bg-slate-100 text-slate-900 dark:bg-slate-800 dark:text-slate-100",
    icon: "?",
  },
};

export const SEV_BADGE: Record<string, string> = {
  informational: "bg-slate-200 text-slate-800 dark:bg-slate-700 dark:text-slate-100",
  low: "bg-sky-100 text-sky-900 dark:bg-sky-900 dark:text-sky-100",
  medium: "bg-amber-100 text-amber-900 dark:bg-amber-900 dark:text-amber-100",
  high: "bg-orange-100 text-orange-900 dark:bg-orange-900 dark:text-orange-100",
  critical: "bg-red-100 text-red-900 dark:bg-red-900 dark:text-red-100",
};

export const pretty = (s: string): string =>
  s.replace(/_/g, " ").replace(/^\w/, (c) => c.toUpperCase());
