export type Evidence = {
  id: string;
  source: string;
  category: string;
  severity: string;
  confidence: number;
  title: string;
  description: string;
  metadata?: Record<string, unknown>;
};

export type OcrModuleData = {
  provider?: string;
  provider_version?: string | null;
  confidence?: number | null;
  text?: string;
  raw_text?: string;
  normalized_text?: string;
  preprocessing?: string;
  tesseract_cmd_source?: string;
  warnings?: string[];
  image?: {
    width?: number;
    height?: number;
    format?: string;
  };
};

export type QrUpiData = {
  payee_vpa?: string;
  payee_name?: string;
  amount?: string;
  currency?: string;
  note?: string;
};

export type QrModuleData = {
  decoder?: string;
  payload?: string | null;
  payload_type?: "url" | "text" | "upi" | "other" | string | null;
  warnings?: string[];
  upi?: QrUpiData;
};

export type MlModuleData = {
  model_name?: string;
  model_version?: string;
  dataset_version?: string;
  dataset_manifest_sha256?: string;
  feature_pipeline_version?: string;
  calibration_version?: string;
  p_malicious?: number;
  threshold?: number;
  predicted_binary?: "legitimate" | "malicious_or_fraudulent" | boolean | string;
  category?: string;
  category_probs?: Record<string, number>;
  latency_ms?: number;
};

export type StrengthCounts = {
  weak?: number;
  moderate?: number;
  strong?: number;
};

export type FusionModuleData = {
  strategy?: "ml_aware_weighted" | "strength_gated" | "legacy" | string;
  version?: string;
  agreement?: "agree" | "ml_only" | "rules_only" | "both_negative" | string;
  disagreement?: boolean;
  strength_counts?: StrengthCounts;
  strength_class_counts?: StrengthCounts;
  drivers?: string[];
  parameters?: Record<string, unknown>;
  [key: string]: unknown;
};

export type ModuleResult = {
  status: "completed" | "unavailable" | "failed" | "not_run" | "timeout" | string;
  detail?: string;
  data?: (OcrModuleData & QrModuleData & MlModuleData & FusionModuleData) | Record<string, unknown>;
};


export type Risk = {
  score: number;
  label: string;
  confidence: string;
};

export type Classification = {
  primary: string;
  secondary?: string[];
};

export type AnalysisResult = {
  schema_version?: string;
  analysis_id: string;
  created_at?: string;
  input_type: "text" | "url" | "image" | "qr" | string;
  status: string;
  risk: Risk;
  classification: Classification;
  evidence: Evidence[];
  modules: Record<string, ModuleResult>;
  explanation: string;
  recommended_actions: string[];
  limitations: string[];
};
