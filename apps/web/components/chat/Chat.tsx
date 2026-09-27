"use client";

import { useEffect, useRef, useState } from "react";
import { RiskCard } from "./RiskCard";
import type { ChatAnalysis, ChatResponse, Message } from "./types";

// Production is same-origin (Vercel Services routes /api/* to the backend); see app/analyzer/page.tsx.
const API = process.env.NEXT_PUBLIC_API_URL ?? (process.env.NODE_ENV === "production" ? "" : "http://localhost:8000");
const MAX_FILE_BYTES = 4 * 1024 * 1024;
const MAX_CHARS = 5000;
const ALLOWED = ["image/png", "image/jpeg", "image/webp"];

// Starters only fill the composer; the user decides whether to send.
const STARTERS = [
  "I got this text: 'URGENT: your bank account is suspended. Verify now at secure-bank-login.top' — is it real?",
  "How does UPI payment-request fraud work?",
  "Someone called claiming to be customs and wants money to release a parcel — is that normal?",
  "How can I tell if a payment QR code is legitimate before scanning it?",
];

const uid = () => Math.random().toString(36).slice(2);

export function Chat() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [draft, setDraft] = useState("");
  const [attached, setAttached] = useState<{ file: File; url: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [busyLabel, setBusyLabel] = useState("TrustShield is thinking\u2026");
  const [notice, setNotice] = useState("");
  const [dragging, setDragging] = useState(false);
  const [apiUp, setApiUp] = useState<boolean | null>(null);
  const [mlUp, setMlUp] = useState<boolean | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const endRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let live = true;
    fetch(`${API}/api/v1/health`)
      .then((r) => (r.ok ? r.json() : null))
      .then((d) => {
        if (!live) return;
        setApiUp(d !== null);
        const ml = d?.modules?.ml;
        setMlUp(typeof ml === "boolean" ? ml : typeof ml?.available === "boolean" ? ml.available : null);
      })
      .catch(() => live && setApiUp(false));
    return () => {
      live = false;
    };
  }, []);

  useEffect(() => {
    endRef.current?.scrollIntoView?.({ behavior: "smooth", block: "end" });
  }, [messages, busy]);

  const lastAnalysis: { analysis: ChatAnalysis; signature: string } | null = (() => {
    for (let i = messages.length - 1; i >= 0; i--) {
      const m = messages[i];
      if (m.role === "assistant" && m.kind === "analysis") return { analysis: m.analysis, signature: m.signature };
    }
    return null;
  })();

  function accept(f: File | undefined | null) {
    if (!f) return;
    if (!ALLOWED.includes(f.type)) return setNotice("Only PNG, JPEG or WebP images can be attached.");
    if (f.size > MAX_FILE_BYTES) return setNotice("That image is larger than 4 MB. Please use a smaller screenshot.");
    setNotice("");
    setAttached((prev) => {
      if (prev) URL.revokeObjectURL(prev.url);
      return { file: f, url: URL.createObjectURL(f) };
    });
  }

  function removeAttachment() {
    setAttached((prev) => {
      if (prev) URL.revokeObjectURL(prev.url);
      return null;
    });
    requestAnimationFrame(() => inputRef.current?.focus()); // keep keyboard focus in the composer
  }

  async function send(text: string, attachment: File | null, context: { analysis: ChatAnalysis; signature: string } | null, history: { role: "user" | "assistant"; text: string }[]) {
    setBusy(true);
    setBusyLabel(attachment ? "Analyzing the attachment\u2026" : "TrustShield is thinking\u2026");
    setNotice("");
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      let res: Response;
      if (attachment) {
        const form = new FormData();
        form.append("file", attachment);
        form.append("message", text);
        if (context) {
          form.append("context", JSON.stringify(context.analysis));
          form.append("context_signature", context.signature);
        }
        form.append("history", JSON.stringify(history));
        res = await fetch(`${API}/api/v1/chat/upload`, { method: "POST", body: form, signal: controller.signal });
      } else {
        res = await fetch(`${API}/api/v1/chat`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ message: text, context: context?.analysis ?? null, context_signature: context?.signature ?? "", history }),
          signal: controller.signal,
        });
      }
      if (!res.ok) {
        const detail = await res.json().then((d) => (typeof d.detail === "string" ? d.detail : "")).catch(() => "");
        const busyMsg = res.status === 429 ? "Too many requests. Please wait a moment and try again." : res.status === 503 ? "The service is busy right now. Please try again shortly." : "";
        throw new Error(detail || busyMsg || `The request failed (${res.status}).`);
      }
      const data: ChatResponse = await res.json();
      setMessages((prev) => [
        ...prev,
        data.kind === "analysis" && data.analysis
          ? { id: uid(), role: "assistant", kind: "analysis", responseType: data.response_type, reply: data.reply, analysis: data.analysis, signature: data.context_signature ?? "", route: data.route }
          : { id: uid(), role: "assistant", kind: "followup", responseType: data.response_type, reply: data.reply },
      ]);
    } catch (err) {
      if (controller.signal.aborted) {
        setMessages((prev) => [...prev, { id: uid(), role: "assistant", kind: "followup", responseType: "chat", reply: { text: "Analysis cancelled.", cited_evidence: [], provider: "none", grounded: true } }]);
      } else {
        const msg = err instanceof Error && err.message ? err.message : "Could not reach the analysis service.";
        const errorId = uid();
        setMessages((prev) => [...prev, { id: errorId, role: "assistant", kind: "error", text: msg, retry: () => {
          setMessages((curr) => curr.filter((m) => m.id !== errorId));
          void send(text, attachment, context, history);
        } }]);
      }
    } finally {
      abortRef.current = null;
      setBusy(false);
    }
  }

  function submit() {
    const text = draft.trim();
    if (busy || (!text && !attached)) return;
    // Bounded, client-carried recent history so a remote/Ollama provider (if ever enabled) has short-term conversational
    // context; the server stores none of this. Capped at 6 turns / 2000 chars each, matching the backend's own bound.
    type HistoryTurn = { role: "user" | "assistant"; text: string };
    const history: HistoryTurn[] = messages
      .flatMap((m): HistoryTurn[] => (m.role === "user" ? [{ role: "user", text: m.text }] : m.kind === "error" ? [] : [{ role: "assistant", text: m.reply.text }]))
      .slice(-6)
      .map((h) => ({ role: h.role, text: h.text.slice(0, 2000) }));
    setMessages((prev) => [...prev, { id: uid(), role: "user", text, fileName: attached?.file.name, previewUrl: attached?.url }]);
    const attachment = attached?.file ?? null;
    setDraft("");
    setAttached(null); // the URL stays alive: the sent message still shows the thumbnail
    void send(text, attachment, lastAnalysis, history);
  }

  function newChat() {
    abortRef.current?.abort();
    setMessages([]);
    setDraft("");
    removeAttachment();
    setNotice("");
    inputRef.current?.focus();
  }

  const empty = messages.length === 0;

  return (
    <div
      className="mx-auto flex min-h-screen w-full max-w-3xl flex-col px-4 sm:px-6"
      onDragOver={(e) => {
        e.preventDefault();
        setDragging(true);
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={(e) => {
        e.preventDefault();
        setDragging(false);
        accept(e.dataTransfer.files?.[0]);
      }}
    >
      <header className="flex items-center justify-between py-4">
        <div className="flex items-center gap-2">
          <span aria-hidden="true" className="flex h-9 w-9 items-center justify-center rounded-lg bg-teal-700 text-lg text-white">⛨</span>
          <div>
            <h1 className="text-base font-semibold leading-tight">TrustShield AI</h1>
            <p className="text-xs text-slate-600 dark:text-slate-400">Think Before You Trust · Team Scam Scan</p>
          </div>
        </div>
        <nav className="flex items-center gap-3 text-sm">
          <a href="/analyzer" className="font-medium text-teal-800 underline dark:text-teal-300">Advanced analyzer</a>
          <button type="button" onClick={newChat} className="min-h-11 rounded-md border border-slate-300 px-3 py-2 font-medium hover:bg-slate-100 focus:outline-none focus:ring-2 focus:ring-teal-600 dark:border-slate-600 dark:hover:bg-slate-800">
            New chat
          </button>
        </nav>
      </header>

      {apiUp === false && (
        <p role="alert" className="mb-2 rounded-md border border-red-600 bg-red-50 px-3 py-2 text-sm text-red-900 dark:bg-red-950 dark:text-red-100">
          The analysis service is unreachable right now. Please try again shortly.
        </p>
      )}
      {apiUp && mlUp === false && (
        <p role="status" className="mb-2 rounded-md border border-amber-500 bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:bg-amber-950 dark:text-amber-100">
          The machine-learning module is unavailable, so results use rule-based checks only.
        </p>
      )}

      <main className="flex-1 pb-4">
        <div aria-live="polite" aria-atomic="true" className="sr-only">
          {busy ? "Analysing." : messages.length > 0 && messages[messages.length - 1].role === "assistant" ? "New response received." : ""}
        </div>
        {empty && (
          <section className="py-8">
            <h2 className="text-2xl font-semibold">Ask me anything about scams, or check something suspicious.</h2>
            <p className="mt-2 max-w-xl text-slate-700 dark:text-slate-300">
              I can explain how phishing, UPI fraud or KYC scams work, or check a message, link, screenshot or QR code for you.
              Every result comes with the evidence behind it, and I never say something is guaranteed safe.
            </p>
            <div className="mt-5 grid gap-2 sm:grid-cols-2">
              {STARTERS.map((s) => (
                <button
                  key={s}
                  type="button"
                  onClick={() => {
                    setDraft(s);
                    inputRef.current?.focus();
                  }}
                  className="rounded-lg border border-slate-300 p-3 text-left text-sm hover:border-teal-600 hover:bg-teal-50 focus:outline-none focus:ring-2 focus:ring-teal-600 dark:border-slate-700 dark:hover:bg-slate-800"
                >
                  {s}
                </button>
              ))}
            </div>
          </section>
        )}

        <ol className="space-y-4">
          {messages.map((m) => (
            <li key={m.id} className={m.role === "user" ? "flex justify-end" : ""}>
              {m.role === "user" ? (
                <div className="max-w-[85%] rounded-2xl rounded-br-sm bg-slate-800 px-4 py-2 text-sm text-white dark:bg-slate-200 dark:text-slate-900">
                  {/* eslint-disable-next-line @next/next/no-img-element -- blob: preview */}
                  {m.previewUrl && <img src={m.previewUrl} alt={m.fileName ?? "Attached image"} className="mb-2 max-h-48 rounded-lg" />}
                  {m.text && <p className="whitespace-pre-wrap break-words">{m.text}</p>}
                </div>
              ) : m.kind === "error" ? (
                <div role="alert" className="rounded-xl border border-red-600 bg-red-50 px-4 py-3 text-sm text-red-900 dark:bg-red-950 dark:text-red-100">
                  <p>{m.text}</p>
                  {m.retry && (
                    <button type="button" onClick={m.retry} disabled={busy} className="mt-2 min-h-11 rounded-md border border-red-700 px-4 py-2 font-medium hover:bg-red-100 disabled:opacity-50 dark:hover:bg-red-900">
                      Retry
                    </button>
                  )}
                </div>
              ) : (
                <div className="max-w-full">
                  <div className="max-w-[85%] rounded-2xl rounded-bl-sm border border-slate-300 bg-slate-50 px-4 py-3 text-sm dark:border-slate-700 dark:bg-slate-900">
                    <p className="whitespace-pre-wrap break-words">{m.reply.text}</p>
                    <div className="mt-2 flex items-center gap-3 text-xs text-slate-600 dark:text-slate-400">
                      {m.kind === "analysis" && <span>{m.reply.grounded ? "Grounded in the analysis evidence" : "Not grounded"}</span>}
                      <button type="button" onClick={() => void navigator.clipboard?.writeText(m.reply.text)} className="min-h-11 px-2 font-medium underline focus:outline-none focus:ring-2 focus:ring-teal-600">
                        Copy
                      </button>
                    </div>
                  </div>
                  {m.kind === "analysis" && <RiskCard analysis={m.analysis} />}
                </div>
              )}
            </li>
          ))}
          {busy && (
            <li role="status" className="text-sm text-slate-600 dark:text-slate-400">
              {busyLabel}{" "}
              <button type="button" onClick={() => abortRef.current?.abort()} className="font-medium underline">Cancel</button>
            </li>
          )}
        </ol>
        <div ref={endRef} />
      </main>

      <div className="sticky bottom-0 bg-[var(--background)] pb-4 pt-2">
        {dragging && <p className="mb-2 rounded-md border-2 border-dashed border-teal-600 p-3 text-center text-sm">Drop an image to attach it</p>}
        {notice && <p role="alert" className="mb-2 text-sm font-medium text-red-700 dark:text-red-300">{notice}</p>}
        {attached && (
          <div className="mb-2 flex items-center gap-3 rounded-lg border border-slate-300 p-2 dark:border-slate-700">
            {/* eslint-disable-next-line @next/next/no-img-element -- blob: preview */}
            <img src={attached.url} alt="Attachment preview" className="h-14 w-14 rounded object-cover" />
            <span className="min-w-0 flex-1 truncate text-sm">{attached.file.name}</span>
            <button type="button" onClick={removeAttachment} aria-label="Remove attachment" className="min-h-11 rounded-md px-3 py-2 text-sm font-medium underline focus:outline-none focus:ring-2 focus:ring-teal-600">
              Remove
            </button>
          </div>
        )}
        <form
          onSubmit={(e) => {
            e.preventDefault();
            submit();
          }}
          className="flex items-end gap-2 rounded-2xl border border-slate-400 bg-white p-2 focus-within:ring-2 focus-within:ring-teal-600 dark:border-slate-600 dark:bg-slate-900"
        >
          <input ref={fileRef} type="file" accept={ALLOWED.join(",")} className="sr-only" aria-label="Attach an image" tabIndex={-1} onChange={(e) => { accept(e.target.files?.[0]); e.target.value = ""; }} />
          <button type="button" onClick={() => fileRef.current?.click()} aria-label="Attach a screenshot or QR code" className="h-11 w-11 shrink-0 rounded-full border border-slate-400 text-xl font-medium hover:bg-slate-100 focus:outline-none focus:ring-2 focus:ring-teal-600 dark:hover:bg-slate-800">
            +
          </button>
          <textarea
            ref={inputRef}
            value={draft}
            onChange={(e) => setDraft(e.target.value.slice(0, MAX_CHARS))}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
                e.preventDefault();
                submit();
              }
            }}
            onPaste={(e) => {
              const img = Array.from(e.clipboardData.files).find((f) => f.type.startsWith("image/"));
              if (img) {
                e.preventDefault();
                accept(img);
              }
            }}
            rows={2}
            aria-label="Message"
            placeholder={lastAnalysis ? "Ask a follow-up, or paste something new to check…" : "Ask a cybersecurity question, or paste something to check…"}
            className="max-h-40 min-h-10 flex-1 resize-none bg-transparent px-2 py-2 text-base outline-none"
          />
          <button type="submit" disabled={busy || (!draft.trim() && !attached)} className="h-11 shrink-0 rounded-full bg-teal-700 px-4 text-sm font-semibold text-white hover:bg-teal-800 focus:outline-none focus:ring-2 focus:ring-teal-600 focus:ring-offset-2 disabled:opacity-50">
            Send
          </button>
        </form>
        <p className="mt-2 text-xs text-slate-600 dark:text-slate-400">
          Analyzed for this session. Avoid uploading passwords, OTPs or card secrets. Results are risk evidence, not a guarantee of safety.
        </p>
      </div>
    </div>
  );
}
