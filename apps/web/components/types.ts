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

export type ModuleResult = {
  status: "completed" | "unavailable" | "failed" | "not_run" | "timeout" | string;
  detail?: string;
  data?: (OcrModuleData & QrModuleData) | Record<string, unknown>;
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
