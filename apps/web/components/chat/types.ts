// Mirrors services/chat/contract.py (contract_version 2.0). Server-side text is DATA and is only ever rendered as text.
export type RiskLevel = "low_risk" | "caution" | "suspicious" | "high_risk" | "unable_to_verify";

export interface ContractEvidence {
  id: string;
  category: string;
  severity: string;
  source_module: string;
  title: string;
  explanation: string;
  confidence: number;
  raw_signal?: Record<string, string | number | boolean>;
  provenance?: string;
}

export interface Capability {
  name: string;
  description: string;
  used: boolean;
  reason: string;
}

export interface ChatAnalysis {
  contract_version: string;
  analysis_id: string;
  timestamp: string;
  input_types: string[];
  risk_score: number;
  risk_level: RiskLevel;
  confidence: "high" | "medium" | "low";
  primary_category: string | null;
  modules_used: string[];
  evidence: ContractEvidence[];
  extracted_content: string;
  urls: unknown[];
  qr_payload: string | null;
  qr_payload_type: string | null;
  warnings: string[];
  uncertainty: string[];
  safe_actions: string[];
  capabilities: Capability[];
  ocr_confidence: number | null;
}

export interface ChatReply {
  text: string;
  cited_evidence: string[];
  provider: string;
  grounded: boolean;
}

export interface ChatResponse {
  kind: "analysis" | "followup";
  route: { kind: string; reason: string; urls: string[]; intent: string | null; has_attachment: boolean; modules: string[] };
  reply: ChatReply;
  analysis: ChatAnalysis | null;
  context_signature: string | null;
}

export type Message =
  | { id: string; role: "user"; text: string; fileName?: string; previewUrl?: string }
  | { id: string; role: "assistant"; kind: "analysis"; reply: ChatReply; analysis: ChatAnalysis; signature: string; route: ChatResponse["route"] }
  | { id: string; role: "assistant"; kind: "followup"; reply: ChatReply }
  | { id: string; role: "assistant"; kind: "error"; text: string; retry?: () => void };
