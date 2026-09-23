import { ModuleResult, QrModuleData } from "./types";
import { pretty } from "./utils";

interface QrPanelProps {
  qr?: ModuleResult;
}

export function QrPanel({ qr }: QrPanelProps) {
  if (!qr) return null;

  const data = (qr.data ?? {}) as QrModuleData;
  const decoder = data.decoder;
  const payloadType = data.payload_type;
  const payload = data.payload;
  const warnings = data.warnings ?? [];
  const upi = data.upi;
  const isFailed = qr.status === "failed";

  return (
    <div className="rounded-lg border border-slate-300 p-4 dark:border-slate-700">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-base font-semibold">Decoded QR</h3>
        <span className="rounded-full border border-slate-300 px-2.5 py-0.5 text-xs font-medium text-slate-700 dark:border-slate-600 dark:text-slate-300">
          Status: {pretty(qr.status)}
        </span>
      </div>

      <dl className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2 text-xs">
        <div>
          <dt className="text-slate-500 dark:text-slate-400">Decoder</dt>
          <dd className="mt-0.5 font-medium text-slate-900 dark:text-slate-100">
            {decoder || "Not specified"}
          </dd>
        </div>
        <div>
          <dt className="text-slate-500 dark:text-slate-400">Payload Type</dt>
          <dd className="mt-0.5 font-medium text-slate-900 dark:text-slate-100">
            {payloadType ? pretty(payloadType) : "Unknown"}
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

      {isFailed && (
        <div className="mt-3 rounded-md border border-slate-300 bg-slate-50 p-3 text-sm text-slate-800 dark:border-slate-700 dark:bg-slate-800/60 dark:text-slate-200">
          <p>{qr.detail || "Could not decode any QR code from the provided image."}</p>
        </div>
      )}

      {!isFailed && payload !== undefined && (
        <div className="mt-3">
          <h4 className="text-xs font-semibold text-slate-700 dark:text-slate-300">Decoded Payload</h4>
          <pre className="mt-1 max-h-40 overflow-auto whitespace-pre-wrap break-all rounded-md border border-slate-300 bg-slate-50 p-3 font-mono text-xs text-slate-800 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200">
            {payload && payload.trim() ? payload : "(Empty payload)"}
          </pre>
          <p className="mt-1 text-xs text-slate-600 dark:text-slate-400">
            This destination was not opened.
          </p>
        </div>
      )}

      {upi && (upi.payee_vpa || upi.payee_name || upi.amount || upi.note) && (
        <div className="mt-3 rounded-md border border-slate-200 bg-slate-50/70 p-3 dark:border-slate-700 dark:bg-slate-900/60">
          <h4 className="text-xs font-semibold text-slate-700 dark:text-slate-300">
            Parsed UPI Payment Details
          </h4>
          <dl className="mt-2 grid grid-cols-1 gap-2 sm:grid-cols-2 text-xs">
            {upi.payee_vpa && (
              <div>
                <dt className="text-slate-500 dark:text-slate-400">Payee VPA (UPI ID)</dt>
                <dd className="font-mono font-medium text-slate-900 dark:text-slate-100">
                  {upi.payee_vpa}
                </dd>
              </div>
            )}
            {upi.payee_name && (
              <div>
                <dt className="text-slate-500 dark:text-slate-400">Payee Name</dt>
                <dd className="font-medium text-slate-900 dark:text-slate-100">{upi.payee_name}</dd>
              </div>
            )}
            {upi.amount && (
              <div>
                <dt className="text-slate-500 dark:text-slate-400">Requested Amount</dt>
                <dd className="font-medium text-slate-900 dark:text-slate-100">
                  {upi.currency ? `${upi.currency} ` : "₹"}
                  {upi.amount}
                </dd>
              </div>
            )}
            {upi.note && (
              <div className="sm:col-span-2">
                <dt className="text-slate-500 dark:text-slate-400">Transaction Note</dt>
                <dd className="font-medium text-slate-900 dark:text-slate-100">{upi.note}</dd>
              </div>
            )}
          </dl>
        </div>
      )}
    </div>
  );
}
