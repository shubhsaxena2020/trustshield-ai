"use client";
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useSession } from "@/lib/useSession";
import { authFetch } from "@/lib/authFetch";
import { AnalysisResult } from "@/components/types";
import { ResultDashboard } from "@/components/ResultDashboard";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

const API =
  process.env.NEXT_PUBLIC_API_URL ?? (process.env.NODE_ENV === "production" ? "" : "http://localhost:8000");

type HistoryRow = {
  id: string;
  input_type: string;
  input_summary: string;
  risk_score: number;
  risk_label: string;
  result_json: AnalysisResult;
  created_at: string;
};

export default function HistoryPage() {
  const { session, loading } = useSession();
  const router = useRouter();
  const [rows, setRows] = useState<HistoryRow[]>([]);
  const [selected, setSelected] = useState<AnalysisResult | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!loading && !session) router.push("/login");
  }, [loading, session, router]);

  useEffect(() => {
    if (!session) return;
    authFetch(`${API}/api/v1/history`)
      .then((res) => (res.ok ? res.json() : Promise.reject(new Error(`Request failed (${res.status})`))))
      .then(setRows)
      .catch((err) => setError(err instanceof Error ? err.message : "Could not load history"));
  }, [session]);

  async function removeRow(id: string) {
    const res = await authFetch(`${API}/api/v1/history/${id}`, { method: "DELETE" });
    if (res.ok) setRows((prev) => prev.filter((r) => r.id !== id));
  }

  async function clearAll() {
    const res = await authFetch(`${API}/api/v1/history`, { method: "DELETE" });
    if (res.ok) setRows([]);
  }

  if (loading || !session) return null;

  return (
    <div className="mx-auto max-w-4xl py-10 px-4">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-xl font-semibold">Your scan history</h1>
        {rows.length > 0 && (
          <Button variant="outline" size="sm" onClick={clearAll}>Clear all</Button>
        )}
      </div>
      {error && <p className="text-sm text-red-700">{error}</p>}
      {selected ? (
        <div>
          <Button variant="outline" size="sm" className="mb-4" onClick={() => setSelected(null)}>
            Back to history
          </Button>
          <ResultDashboard result={selected} />
        </div>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Date</TableHead>
              <TableHead>Type</TableHead>
              <TableHead>Summary</TableHead>
              <TableHead>Risk</TableHead>
              <TableHead />
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((row) => (
              <TableRow key={row.id}>
                <TableCell>{new Date(row.created_at).toLocaleString()}</TableCell>
                <TableCell>{row.input_type}</TableCell>
                <TableCell>{row.input_summary}</TableCell>
                <TableCell>{row.risk_label} ({row.risk_score})</TableCell>
                <TableCell className="space-x-2 text-right">
                  <Button variant="outline" size="sm" onClick={() => setSelected(row.result_json)}>View</Button>
                  <Button variant="destructive" size="sm" onClick={() => removeRow(row.id)}>Delete</Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      {rows.length === 0 && !error && <p className="text-sm text-slate-500">No scans saved yet.</p>}
    </div>
  );
}
