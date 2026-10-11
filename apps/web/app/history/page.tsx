"use client";
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { useSession } from "@/lib/useSession";
import { authFetch } from "@/lib/authFetch";
import { AnalysisResult } from "@/components/types";
import { ResultDashboard } from "@/components/ResultDashboard";
import { Button, buttonVariants } from "@/components/ui/button";
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
    if (!window.confirm("Delete this scan from your history? This cannot be undone.")) return;
    const res = await authFetch(`${API}/api/v1/history/${id}`, { method: "DELETE" });
    if (res.ok) setRows((prev) => prev.filter((r) => r.id !== id));
  }

  async function clearAll() {
    if (!window.confirm("Delete your entire scan history? This cannot be undone.")) return;
    const res = await authFetch(`${API}/api/v1/history`, { method: "DELETE" });
    if (res.ok) setRows([]);
  }

  if (loading || !session) {
    return (
      <div className="mx-auto max-w-4xl animate-pulse py-10 px-4">
        <div className="mb-4 h-6 w-48 rounded bg-slate-200" />
        <div className="h-32 rounded bg-slate-100" />
      </div>
    );
  }

  return (
    <main className="mx-auto max-w-4xl py-10 px-4">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-xl font-semibold">{selected ? "Scan details" : "Your scan history"}</h1>
        {!selected && rows.length > 0 && (
          <Button variant="outline" size="sm" onClick={clearAll}>Clear all</Button>
        )}
      </div>
      {error && <p role="alert" className="text-sm font-medium text-red-700">{error}</p>}
      {selected ? (
        <div>
          <Button variant="outline" size="sm" className="mb-4" onClick={() => setSelected(null)}>
            Back to history
          </Button>
          <ResultDashboard result={selected} />
        </div>
      ) : rows.length === 0 && !error ? (
        <div className="rounded-lg border border-dashed border-slate-300 p-8 text-center">
          <p className="text-sm text-slate-600">No scans saved yet.</p>
          <Link href="/analyzer" className={buttonVariants({ variant: "outline", size: "sm" }) + " mt-3"}>
            Run your first scan
          </Link>
        </div>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Date</TableHead>
              <TableHead>Type</TableHead>
              <TableHead>Summary</TableHead>
              <TableHead>Risk</TableHead>
              <TableHead><span className="sr-only">Actions</span></TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((row) => {
              const when = new Date(row.created_at).toLocaleDateString();
              return (
                <TableRow key={row.id}>
                  <TableCell className="whitespace-nowrap">{new Date(row.created_at).toLocaleString()}</TableCell>
                  <TableCell>{row.input_type}</TableCell>
                  <TableCell className="max-w-[220px] truncate" title={row.input_summary}>
                    {row.input_summary}
                  </TableCell>
                  <TableCell className="whitespace-nowrap">{row.risk_label} ({row.risk_score})</TableCell>
                  <TableCell className="space-x-2 text-right whitespace-nowrap">
                    <Button variant="outline" size="sm" onClick={() => setSelected(row.result_json)}
                            aria-label={`View ${row.input_type} scan from ${when}`}>
                      View
                    </Button>
                    <Button variant="destructive" size="sm" onClick={() => removeRow(row.id)}
                            aria-label={`Delete ${row.input_type} scan from ${when}`}>
                      Delete
                    </Button>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      )}
    </main>
  );
}
