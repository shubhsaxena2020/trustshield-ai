"use client";

import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { RiskCard } from "./RiskCard";
import type { ChatAnalysis, ChatResponse, Message } from "./types";

// Production is same-origin (Vercel Services routes /api/* to the backend); see the deployment runbook.
const API = process.env.NEXT_PUBLIC_API_URL ?? (process.env.NODE_ENV === "production" ? "" : "http://localhost:8000");
const MAX_FILE_BYTES = 4 * 1024 * 1024;
const MAX_CHARS = 5000;
const ALLOWED = ["image/png", "image/jpeg", "image/webp"];

const STARTERS = [
  "I got this text: 'URGENT: your bank account is suspended. Verify now at secure-bank-login.top' — is it real?",
  "How does UPI payment-request fraud work?",
  "Someone called claiming to be customs and wants money to release a parcel. Is that normal?",
  "How can I tell if a payment QR code is legitimate before scanning it?",
];

type Conversation = { id: string; title: string; messages: Message[] };
type HistoryTurn = { role: "user" | "assistant"; text: string };

const uid = () => Math.random().toString(36).slice(2);

function newConversation(): Conversation {
  return { id: uid(), title: "New chat", messages: [] };
}

// Minimal typing for the Web Speech API, which is not in the default DOM lib.
type SpeechRecognitionLike = {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  start: () => void;
  stop: () => void;
  onresult: ((e: { results: ArrayLike<ArrayLike<{ transcript: string }> & { isFinal: boolean }>; resultIndex: number }) => void) | null;
  onend: (() => void) | null;
  onerror: (() => void) | null;
};

function getSpeechCtor(): (new () => SpeechRecognitionLike) | null {
  if (typeof window === "undefined") return null;
  const w = window as unknown as { SpeechRecognition?: new () => SpeechRecognitionLike; webkitSpeechRecognition?: new () => SpeechRecognitionLike };
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null;
}

export function Chat() {
  const [chats, setChats] = useState<Conversation[]>(() => [newConversation()]);
  const [activeId, setActiveId] = useState<string>(() => "");
  const [draft, setDraft] = useState("");
  const [attached, setAttached] = useState<{ file: File; url: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [busyLabel, setBusyLabel] = useState("Thinking…");
  const [notice, setNotice] = useState("");
  const [dragging, setDragging] = useState(false);
  const [apiUp, setApiUp] = useState<boolean | null>(null);
  const [mlUp, setMlUp] = useState<boolean | null>(null);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [attachOpen, setAttachOpen] = useState(false);
  const [listening, setListening] = useState(false);
  const speechSupported = useSyncExternalStore(
    () => () => {},
    () => getSpeechCtor() !== null,
    () => false,
  );
  const abortRef = useRef<AbortController | null>(null);
  const endRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const recogRef = useRef<SpeechRecognitionLike | null>(null);

  const active = chats.find((c) => c.id === activeId) ?? chats[0];
  const messages = active.messages;


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

  useEffect(() => () => recogRef.current?.stop(), []);

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
  }

  function toggleListening() {
    const Ctor = getSpeechCtor();
    if (!Ctor) return;
    if (listening) {
      recogRef.current?.stop();
      setListening(false);
      return;
    }
    const recog = new Ctor();
    recog.lang = "en-IN";
    recog.continuous = true;
    recog.interimResults = true;
    let finalText = draft;
    recog.onresult = (e) => {
      let interim = "";
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const r = e.results[i];
        if (r.isFinal) finalText = `${finalText}${finalText && !finalText.endsWith(" ") ? " " : ""}${r[0].transcript.trim()}`;
        else interim += r[0].transcript;
      }
      setDraft((finalText + (interim ? ` ${interim}` : "")).slice(0, MAX_CHARS));
    };
    recog.onend = () => setListening(false);
    recog.onerror = () => {
      setListening(false);
      setNotice("Voice input stopped. Check that microphone access is allowed for this site.");
    };
    recogRef.current = recog;
    recog.start();
    setListening(true);
    inputRef.current?.focus();
  }

  async function send(text: string, attachment: File | null, context: { analysis: ChatAnalysis; signature: string } | null, history: HistoryTurn[], convId: string) {
    setBusy(true);
    setBusyLabel(attachment ? "Checking the attachment…" : "Thinking…");
    setNotice("");
    const controller = new AbortController();
    abortRef.current = controller;
    const append = (m: Message) => setChats((prev) => prev.map((c) => (c.id === convId ? { ...c, messages: [...c.messages, m] } : c)));
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
      append(
        data.kind === "analysis" && data.analysis
          ? { id: uid(), role: "assistant", kind: "analysis", responseType: data.response_type, reply: data.reply, analysis: data.analysis, signature: data.context_signature ?? "", route: data.route }
          : { id: uid(), role: "assistant", kind: "followup", responseType: data.response_type, reply: data.reply },
      );
    } catch (err) {
      if (controller.signal.aborted) {
        append({ id: uid(), role: "assistant", kind: "followup", responseType: "chat", reply: { text: "Stopped.", cited_evidence: [], provider: "none", grounded: true } });
      } else {
        const msg = err instanceof Error && err.message ? err.message : "Could not reach the analysis service.";
        const errorId = uid();
        append({
          id: errorId,
          role: "assistant",
          kind: "error",
          text: msg,
          retry: () => {
            setChats((prev) => prev.map((c) => (c.id === convId ? { ...c, messages: c.messages.filter((m) => m.id !== errorId) } : c)));
            void send(text, attachment, context, history, convId);
          },
        });
      }
    } finally {
      abortRef.current = null;
      setBusy(false);
    }
  }

  function submit(textOverride?: string) {
    const text = (textOverride ?? draft).trim();
    if (busy || (!text && !attached)) return;
    if (listening) {
      recogRef.current?.stop();
      setListening(false);
    }
    // Bounded, client-carried recent history. The server stores none of this.
    const history: HistoryTurn[] = messages
      .flatMap((m): HistoryTurn[] => (m.role === "user" ? [{ role: "user", text: m.text }] : m.kind === "error" ? [] : [{ role: "assistant", text: m.reply.text }]))
      .slice(-6)
      .map((h) => ({ role: h.role, text: h.text.slice(0, 2000) }));
    const title = messages.length === 0 ? (text || attached?.file.name || "New chat").slice(0, 40) : active.title;
    setChats((prev) => prev.map((c) => (c.id === active.id ? { ...c, title, messages: [...c.messages, { id: uid(), role: "user", text, fileName: attached?.file.name, previewUrl: attached?.url }] } : c)));
    const attachment = attached?.file ?? null;
    setDraft("");
    setAttached(null); // the URL stays alive: the sent message still shows the thumbnail
    void send(text, attachment, lastAnalysis, history, active.id);
  }

  function newChat() {
    abortRef.current?.abort();
    const c = newConversation();
    setChats((prev) => [c, ...prev]);
    setActiveId(c.id);
    setDraft("");
    removeAttachment();
    setNotice("");
    setSidebarOpen(false);
    inputRef.current?.focus();
  }

  function switchTo(id: string) {
    abortRef.current?.abort();
    setActiveId(id);
    setSidebarOpen(false);
  }

  const empty = messages.length === 0;

  return (
    <div
      className="flex h-dvh w-full bg-[#212121] text-[15px] text-zinc-100"
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
      {/* Sidebar: desktop always visible, mobile as a drawer */}
      <aside
        aria-label="Chats"
        className={`${sidebarOpen ? "fixed inset-y-0 left-0 z-30 w-72" : "hidden"} flex flex-col bg-[#171717] p-3 md:static md:flex md:w-64 md:shrink-0`}
      >
        <button type="button" onClick={newChat} className="flex min-h-11 items-center gap-2 rounded-lg border border-zinc-700 px-3 py-2 text-left hover:bg-zinc-800 focus:outline-none focus:ring-2 focus:ring-teal-500">
          <span aria-hidden="true">+</span> New chat
        </button>
        <nav className="mt-4 flex-1 overflow-y-auto" aria-label="Previous chats">
          <p className="px-2 pb-2 text-xs uppercase tracking-wide text-zinc-500">This session</p>
          <ul className="space-y-1">
            {chats.map((c) => (
              <li key={c.id}>
                <button
                  type="button"
                  onClick={() => switchTo(c.id)}
                  aria-current={c.id === active.id ? "true" : undefined}
                  className={`w-full truncate rounded-lg px-3 py-2 text-left text-sm hover:bg-zinc-800 focus:outline-none focus:ring-2 focus:ring-teal-500 ${c.id === active.id ? "bg-zinc-800" : ""}`}
                >
                  {c.title}
                </button>
              </li>
            ))}
          </ul>
        </nav>
        <p className="px-2 pt-3 text-xs text-zinc-500">Chats are kept only in this browser tab and are cleared on reload.</p>
      </aside>
      {sidebarOpen && <button type="button" aria-label="Close chats" onClick={() => setSidebarOpen(false)} className="fixed inset-0 z-20 bg-black/50 md:hidden" />}

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center gap-3 px-4 py-3">
          <button type="button" onClick={() => setSidebarOpen(true)} aria-label="Open chats" className="flex h-11 w-11 items-center justify-center rounded-lg hover:bg-zinc-800 md:hidden">
            ☰
          </button>
          <h1 className="text-base font-semibold">TrustShield AI</h1>
        </header>

        {apiUp === false && (
          <p role="alert" className="mx-4 mb-2 rounded-md border border-red-600 bg-red-950 px-3 py-2 text-sm text-red-100">
            The analysis service is unreachable right now. Please try again shortly.
          </p>
        )}
        {apiUp && mlUp === false && (
          <p role="status" className="mx-4 mb-2 rounded-md border border-amber-500 bg-amber-950 px-3 py-2 text-sm text-amber-100">
            The machine-learning module is unavailable, so results use rule-based checks only.
          </p>
        )}

        <main className="flex-1 overflow-y-auto px-4">
          <div aria-live="polite" aria-atomic="true" className="sr-only">
            {busy ? "Working on it." : messages.length > 0 && messages[messages.length - 1].role === "assistant" ? "New response received." : ""}
          </div>

          {empty && (
            <section className="mx-auto flex max-w-2xl flex-col items-center py-16 text-center">
              <h2 className="text-3xl font-semibold">What should I check for you?</h2>
              <p className="mt-3 max-w-xl text-zinc-400">
                Paste a message or a link, attach a screenshot or QR code, or ask how a scam works. I show the evidence behind every answer and never say something is guaranteed safe.
              </p>
              <div className="mt-8 grid w-full gap-2 sm:grid-cols-2">
                {STARTERS.map((s) => (
                  <button
                    key={s}
                    type="button"
                    onClick={() => submit(s)}
                    className="rounded-xl border border-zinc-700 p-3 text-left text-sm hover:bg-zinc-800 focus:outline-none focus:ring-2 focus:ring-teal-500"
                  >
                    {s}
                  </button>
                ))}
              </div>
            </section>
          )}

          <ol className="mx-auto max-w-3xl space-y-6 py-6">
            {messages.map((m) => (
              <li key={m.id} className={m.role === "user" ? "flex justify-end" : ""}>
                {m.role === "user" ? (
                  <div className="max-w-[85%] rounded-3xl bg-[#2f2f2f] px-4 py-2.5">
                    {/* eslint-disable-next-line @next/next/no-img-element -- blob: preview */}
                    {m.previewUrl && <img src={m.previewUrl} alt={m.fileName ?? "Attached image"} className="mb-2 max-h-56 rounded-xl" />}
                    {m.text && <p className="whitespace-pre-wrap break-words">{m.text}</p>}
                  </div>
                ) : m.kind === "error" ? (
                  <div role="alert" className="rounded-xl border border-red-700 bg-red-950 px-4 py-3 text-red-100">
                    <p>{m.text}</p>
                    {m.retry && (
                      <button type="button" onClick={m.retry} disabled={busy} className="mt-2 min-h-11 rounded-md border border-red-500 px-4 py-2 font-medium hover:bg-red-900 disabled:opacity-50">
                        Retry
                      </button>
                    )}
                  </div>
                ) : (
                  <div className="max-w-full">
                    <p className="whitespace-pre-wrap break-words leading-7">{m.reply.text}</p>
                    <div className="mt-2 flex items-center gap-3 text-xs text-zinc-400">
                      {m.kind === "analysis" && <span>{m.reply.grounded ? "Based on the evidence" : "Not verified against the evidence"}</span>}
                      <button type="button" onClick={() => void navigator.clipboard?.writeText(m.reply.text)} className="min-h-11 px-2 font-medium underline focus:outline-none focus:ring-2 focus:ring-teal-500">
                        Copy
                      </button>
                    </div>
                    {m.kind === "analysis" && <div className="mt-3"><RiskCard analysis={m.analysis} /></div>}
                  </div>
                )}
              </li>
            ))}
            {busy && (
              <li role="status" className="text-sm text-zinc-400">
                {busyLabel}{" "}
                <button type="button" onClick={() => abortRef.current?.abort()} className="font-medium underline">Stop</button>
              </li>
            )}
          </ol>
          <div ref={endRef} />
        </main>

        <div className="mx-auto w-full max-w-3xl px-4 pb-4 pt-2">
          {dragging && <p className="mb-2 rounded-xl border-2 border-dashed border-teal-500 p-3 text-center text-sm">Drop an image to attach it</p>}
          {notice && <p role="alert" className="mb-2 text-sm font-medium text-red-300">{notice}</p>}
          {attached && (
            <div className="mb-2 flex items-center gap-3 rounded-xl bg-[#2f2f2f] p-2">
              {/* eslint-disable-next-line @next/next/no-img-element -- blob: preview */}
              <img src={attached.url} alt="Attachment preview" className="h-14 w-14 rounded-lg object-cover" />
              <span className="min-w-0 flex-1 truncate text-sm">{attached.file.name}</span>
              <button type="button" onClick={removeAttachment} aria-label="Remove attachment" className="min-h-11 rounded-md px-3 py-2 text-sm underline">Remove</button>
            </div>
          )}

          <form
            onSubmit={(e) => {
              e.preventDefault();
              submit();
            }}
            className="relative flex items-end gap-2 rounded-3xl bg-[#2f2f2f] px-2 py-2 focus-within:ring-2 focus-within:ring-teal-500"
          >
            <input ref={fileRef} type="file" accept={ALLOWED.join(",")} className="sr-only" aria-label="Attach an image" tabIndex={-1} onChange={(e) => { accept(e.target.files?.[0]); e.target.value = ""; setAttachOpen(false); }} />
            <div className="relative">
              <button type="button" onClick={() => setAttachOpen((o) => !o)} aria-label="Attach" aria-expanded={attachOpen} className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-2xl hover:bg-zinc-700 focus:outline-none focus:ring-2 focus:ring-teal-500">
                +
              </button>
              {attachOpen && (
                <div role="menu" className="absolute bottom-14 left-0 z-10 w-56 rounded-xl border border-zinc-700 bg-[#2a2a2a] p-1 shadow-xl">
                  <button type="button" role="menuitem" onClick={() => { setAttachOpen(false); fileRef.current?.click(); }} className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm hover:bg-zinc-700">
                    Upload a screenshot or QR code
                  </button>
                </div>
              )}
            </div>
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
              rows={1}
              aria-label="Message"
              placeholder={lastAnalysis ? "Ask a follow-up, or paste something new to check" : "Message TrustShield, or paste something to check"}
              className="max-h-48 min-h-11 flex-1 resize-none bg-transparent px-2 py-2.5 text-base text-zinc-100 outline-none placeholder:text-zinc-500"
            />
            {speechSupported && (
              <button type="button" onClick={toggleListening} aria-pressed={listening} aria-label={listening ? "Stop voice input" : "Speak your message"} className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-full hover:bg-zinc-700 focus:outline-none focus:ring-2 focus:ring-teal-500 ${listening ? "bg-red-700" : ""}`}>
                {listening ? "■" : "🎙"}
              </button>
            )}
            <button type="submit" disabled={busy || (!draft.trim() && !attached)} aria-label="Send" className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-zinc-100 font-semibold text-zinc-900 hover:bg-white focus:outline-none focus:ring-2 focus:ring-teal-500 disabled:opacity-40">
              ↑
            </button>
          </form>
          <p className="mt-2 text-center text-xs text-zinc-500">
            Avoid sharing passwords, OTPs or card secrets. Results are risk evidence, not a guarantee of safety.
          </p>
        </div>
      </div>
    </div>
  );
}
