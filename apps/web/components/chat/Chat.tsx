"use client";

import { useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore, type ReactNode } from "react";
import Image from "next/image";
import { authFetch } from "@/lib/authFetch";
import { supabase } from "@/lib/supabaseClient";
import { useSession } from "@/lib/useSession";
import { EmptyState } from "./EmptyState";
import { AppHeader } from "./AppHeader";
import { RiskCard } from "./RiskCard";
import type { ChatAnalysis, ChatResponse, Message, ResponseType } from "./types";

// Production is same-origin (Vercel Services routes /api/* to the backend); see the deployment runbook.
const API = process.env.NEXT_PUBLIC_API_URL ?? (process.env.NODE_ENV === "production" ? "" : "http://localhost:8000");
const MAX_FILE_BYTES = 4 * 1024 * 1024;
const MAX_CHARS = 5000;
const ALLOWED = ["image/png", "image/jpeg", "image/webp", "image/heic", "image/heif"];
const ALLOWED_EXT = /\.(png|jpe?g|webp|heic|heif)$/i;

const STARTERS = [
  "I got this text: 'URGENT: your bank account is suspended. Verify now at secure-bank-login.top' — is it real?",
  "How does UPI payment-request fraud work?",
  "Someone called claiming to be customs and wants money to release a parcel. Is that normal?",
  "How can I tell if a payment QR code is legitimate before scanning it?",
];

type Conversation = { id: string; title: string; messages: Message[] };
type HistoryTurn = { role: "user" | "assistant"; text: string };
type Context = { analysis: ChatAnalysis; signature: string };

const uid = () => Math.random().toString(36).slice(2);

function newConversation(): Conversation {
  return { id: uid(), title: "New chat", messages: [] };
}

// Signed-in chats are saved in this browser (localStorage) per account, so they survive a refresh but stay scoped to
// whoever is signed in — like ChatGPT, signing out switches away from that history instead of merely hiding it.
// Signed-out ("anon") chats are never written to storage at all: like ChatGPT's logged-out mode, they live only in
// memory for the current page load and are gone on refresh or logout, so a shared device never shows one person's
// anonymous scans to the next. Uploaded image previews are not saved (a blob URL cannot be restored), and
// failed-request messages are dropped. Cross-device sync for a signed-in account needs a backend store, not set up.
const LEGACY_STORE_KEY = "trustshield.chats.v1";
const ANON_IDENTITY = "anon";
const WELCOME_DISMISSED_KEY = "trustshield.welcome_dismissed.v1";
const WELCOME_GATE_EVENT = "trustshield:welcome-gate";
const storeKeyFor = (identity: string) => `trustshield.chats.v1:${identity}`;

function subscribeToWelcomeGate(onChange: () => void) {
  window.addEventListener("storage", onChange);
  window.addEventListener(WELCOME_GATE_EVENT, onChange);
  return () => {
    window.removeEventListener("storage", onChange);
    window.removeEventListener(WELCOME_GATE_EVENT, onChange);
  };
}

function getWelcomeGateSnapshot(signedInOrLoading: boolean) {
  if (signedInOrLoading) return false;
  try {
    return window.localStorage.getItem(WELCOME_DISMISSED_KEY) !== "1";
  } catch {
    return true;
  }
}

// Anonymous chats from before this was identity-scoped (or from an earlier build that persisted them) must never
// resurface: wipe every local chat key that isn't a specific signed-in account's.
function purgeAnonStorage() {
  try {
    window.localStorage.removeItem(LEGACY_STORE_KEY);
    window.localStorage.removeItem(storeKeyFor(ANON_IDENTITY));
  } catch {
    // storage blocked: nothing to purge
  }
}

function loadChats(identity: string): Conversation[] | null {
  if (identity === ANON_IDENTITY) return null;
  try {
    const raw = window.localStorage.getItem(storeKeyFor(identity));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Conversation[];
    return Array.isArray(parsed) && parsed.length > 0 ? parsed : null;
  } catch {
    return null;
  }
}

function saveChats(identity: string, chats: Conversation[]) {
  if (identity === ANON_IDENTITY) return; // signed-out chats are never persisted
  try {
    const clean = chats.slice(0, 50).map((c) => ({
      ...c,
      messages: c.messages
        .filter((m) => m.role === "user" || m.kind !== "error")
        .map((m) => (m.role === "user" ? { id: m.id, role: m.role, text: m.text, fileName: m.fileName } : m)),
    }));
    window.localStorage.setItem(storeKeyFor(identity), JSON.stringify(clean));
  } catch {
    // storage full or blocked: the chat still works in memory
  }
}

// Replies and messages sent before a point, as the model sees them (bounded, client-carried; the server stores none of this).
function toHistory(list: Message[]): HistoryTurn[] {
  return list
    .flatMap((m): HistoryTurn[] => (m.role === "user" ? [{ role: "user", text: m.text }] : m.kind === "error" ? [] : [{ role: "assistant", text: m.reply.text }]))
    .slice(-6)
    .map((h) => ({ role: h.role, text: h.text.slice(0, 2000) }));
}

function lastAnalysisOf(list: Message[]): Context | null {
  for (let i = list.length - 1; i >= 0; i--) {
    const m = list[i];
    if (m.role === "assistant" && m.kind === "analysis") return { analysis: m.analysis, signature: m.signature };
  }
  return null;
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

function Logo({ className = "" }: { className?: string }) {
  return <Image src="/logo-icon.png" alt="TrustShield AI" width={28} height={28} className={`shrink-0 object-contain ${className}`} unoptimized />;
}

function IconButton({ label, onClick, children, className = "" }: { label: string; onClick: () => void; children: ReactNode; className?: string }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      title={label}
      className={`flex h-8 w-8 items-center justify-center rounded-lg text-zinc-500 hover:bg-zinc-100 hover:text-zinc-900 focus:outline-none focus:ring-2 focus:ring-[#3566f0] ${className}`}
    >
      {children}
    </button>
  );
}

export function Chat() {
  const { session, loading } = useSession();
  const identity = loading ? null : (session?.user.id ?? ANON_IDENTITY);
  return <ChatWorkspace key={identity ?? "session-loading"} identity={identity} session={session} loading={loading} />;
}

function ChatWorkspace({ identity, session, loading }: {
  identity: string | null;
  session: ReturnType<typeof useSession>["session"];
  loading: boolean;
}) {
  const [chats, setChats] = useState<Conversation[]>(() => {
    if (identity === null || typeof window === "undefined") return [newConversation()];
    return loadChats(identity) ?? [newConversation()];
  });
  const [activeId, setActiveId] = useState<string>(() => chats[0].id);
  const identityReady = identity !== null;
  const [draft, setDraft] = useState("");
  const [attached, setAttached] = useState<{ file: File; url: string } | null>(null);
  // Conversations whose reply is still streaming. Each has its own controller, so switching chats never cancels a reply.
  const [streaming, setStreaming] = useState<Set<string>>(() => new Set());
  const [busyLabel, setBusyLabel] = useState("Thinking…");
  const [notice, setNotice] = useState("");
  const [dragging, setDragging] = useState(false);
  const [apiUp, setApiUp] = useState<boolean | null>(null);
  const [mlUp, setMlUp] = useState<boolean | null>(null);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [attachOpen, setAttachOpen] = useState(false);
  const [listening, setListening] = useState(false);
  const [editing, setEditing] = useState<{ id: string; text: string } | null>(null);
  const [renaming, setRenaming] = useState<{ id: string; title: string } | null>(null);
  const [copied, setCopied] = useState<string | null>(null);
  const speechSupported = useSyncExternalStore(
    () => () => {},
    () => getSpeechCtor() !== null,
    () => false,
  );
  const controllersRef = useRef(new Map<string, AbortController>());
  const endRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const recogRef = useRef<SpeechRecognitionLike | null>(null);
  const chatsRef = useRef(chats);
  const showGate = useSyncExternalStore(
    subscribeToWelcomeGate,
    () => getWelcomeGateSnapshot(loading || Boolean(session)),
    () => false,
  );
  const gateRef = useRef<HTMLDivElement>(null);
  const prevFocusRef = useRef<HTMLElement | null>(null);
  const sidebarRef = useRef<HTMLElement | null>(null);
  const sidebarPrevFocusRef = useRef<HTMLElement | null>(null);

  useLayoutEffect(() => {
    const input = inputRef.current;
    if (!input) return;
    input.style.height = "auto";
    input.style.height = `${Math.min(input.scrollHeight, 192)}px`;
  }, [draft]);

  const active = chats.find((c) => c.id === activeId) ?? chats[0];
  const busy = streaming.has(active.id);
  const messages = active.messages;
  const lastAnalysis = lastAnalysisOf(messages);

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

  // Scroll on new messages only, not on every streamed character (that per-token smooth scroll made the page feel slow).
  useEffect(() => {
    endRef.current?.scrollIntoView?.({ behavior: busy ? "auto" : "smooth", block: "end" });
  }, [messages.length, busy]);

  useEffect(() => () => recogRef.current?.stop(), []);

  useEffect(() => {
    if (!sidebarOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        setSidebarOpen(false);
        return;
      }
      if (e.key !== "Tab") return;
      const aside = sidebarRef.current;
      if (!aside) return;
      const focusable = Array.from(
        aside.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), textarea, input, select, [tabindex]:not([tabindex="-1"])',
        ),
      ).filter((el) => el.getClientRects().length > 0);
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = document.activeElement;
      if (e.shiftKey) {
        if (active === first || !aside.contains(active)) {
          e.preventDefault();
          last.focus();
        }
      } else {
        if (active === last || !aside.contains(active)) {
          e.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [sidebarOpen]);

  useEffect(() => {
    if (sidebarOpen) {
      sidebarPrevFocusRef.current = (document.activeElement as HTMLElement) ?? null;
      // Move focus into the drawer for screen-reader/keyboard users.
      window.requestAnimationFrame(() => sidebarRef.current?.focus());
    } else {
      const prev = sidebarPrevFocusRef.current;
      if (prev && typeof prev.focus === "function") {
        try {
          prev.focus();
        } catch {
          /* ignore */
        }
      }
      sidebarPrevFocusRef.current = null;
    }
  }, [sidebarOpen]);

  useLayoutEffect(() => {
    chatsRef.current = chats;
  }, [chats]);

  useEffect(() => {
    if (!identity) return;
    purgeAnonStorage();
  }, [identity]);

  // Save in the background, after the chat has been idle briefly: writing the whole history on every streamed token is slow.
  useEffect(() => {
    if (identity === null) return;
    const t = window.setTimeout(() => saveChats(identity, chats), 400);
    return () => window.clearTimeout(t);
  }, [chats, identity]);

  useEffect(() => () => {
    if (identity) saveChats(identity, chatsRef.current);
  }, [identity]);

  // The sign-in gate visibility is now adjusted during render (see above), so no effect is needed here.

  // Accessibility: the dialog claims aria-modal="true", so on open it must take focus, trap Tab, close on
  // Escape, and restore focus when dismissed. Implemented with plain React (no focus-trap dependency).
  useEffect(() => {
    if (!showGate) return;
    prevFocusRef.current = (document.activeElement as HTMLElement) ?? null;
    gateRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        dismissGate();
        return;
      }
      if (e.key !== "Tab") return;
      const dialog = gateRef.current;
      if (!dialog) return;
      const focusable = Array.from(
        dialog.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), textarea, input, select, [tabindex]:not([tabindex="-1"])',
        ),
      ).filter((el) => el.getClientRects().length > 0);
      if (focusable.length === 0) {
        e.preventDefault();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = document.activeElement;
      if (active === dialog) {
        e.preventDefault();
        (e.shiftKey ? last : first).focus();
        return;
      }
      if (e.shiftKey) {
        if (active === first || !dialog.contains(active)) {
          e.preventDefault();
          last.focus();
        }
      } else {
        if (active === last || !dialog.contains(active)) {
          e.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("keydown", onKey);
      const prev = prevFocusRef.current;
      if (prev && typeof prev.focus === "function") {
        try {
          prev.focus();
        } catch {
          /* ignore */
        }
      }
    };
  }, [showGate]);

  function dismissGate() {
    try {
      window.localStorage.setItem(WELCOME_DISMISSED_KEY, "1");
    } catch {
      // storage blocked: the gate just won't persist across a refresh
    }
    window.dispatchEvent(new Event(WELCOME_GATE_EVENT));
  }

  function logOut() {
    try {
      window.localStorage.removeItem(WELCOME_DISMISSED_KEY);
    } catch {
      // storage blocked: harmless, the gate simply won't re-show
    }
    window.dispatchEvent(new Event(WELCOME_GATE_EVENT));
    void supabase?.auth.signOut();
  }

  function accept(f: File | undefined | null) {
    if (!f) return;
    // Some mobile browsers (notably older Android WebViews on a camera capture) report an empty or generic
    // f.type instead of the real image MIME type. Fall back to the filename extension rather than blocking the
    // upload outright; the backend decodes and validates the actual bytes regardless of what either side claims.
    const typeOk = f.type ? ALLOWED.includes(f.type) : ALLOWED_EXT.test(f.name);
    if (!typeOk) return setNotice("Only PNG, JPEG, WebP or HEIC/HEIF images can be attached.");
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

  // Streams a text reply as it is written. Text arrives as server events; it is shown at a steady pace (no waiting for the
  // whole answer), and the server's final check can replace it before the message is marked done.
  async function streamChat(text: string, context: Context | null, history: HistoryTurn[], convId: string, controller: AbortController) {
    const msgId = uid();
    const patch = (fn: (m: Message) => Message) =>
      setChats((prev) => prev.map((c) => (c.id === convId ? { ...c, messages: c.messages.map((m) => (m.id === msgId ? fn(m) : m)) } : c)));
    const setText = (t: string) => patch((m) => (m.role === "assistant" && m.kind !== "error" ? { ...m, reply: { ...m.reply, text: t } } : m));
    setChats((prev) => prev.map((c) => (c.id === convId ? { ...c, messages: [...c.messages, { id: msgId, role: "assistant", kind: "followup", responseType: "chat", reply: { text: "", cited_evidence: [], provider: "", grounded: true } }] } : c)));

    let full = "";
    let shown = 0;
    let timer: number | null = null;
    const tick = () => {
      timer = null;
      if (shown >= full.length) return;
      const step = Math.max(1, Math.ceil((full.length - shown) / 10));
      shown = Math.min(full.length, shown + step);
      setText(full.slice(0, shown));
      timer = window.setTimeout(tick, 16);
    };
    const kick = () => {
      if (timer === null) timer = window.setTimeout(tick, 16);
    };
    const flush = () => {
      if (timer !== null) clearTimeout(timer);
      timer = null;
      shown = full.length;
      setText(full);
    };

    try {
      const res = await authFetch(`${API}/api/v1/chat/stream`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: text, context: context?.analysis ?? null, context_signature: context?.signature ?? "", history }),
        signal: controller.signal,
      });
      if (!res.ok || !res.body) {
        const detail = await res.json().then((d) => (typeof d.detail === "string" ? d.detail : "")).catch(() => "");
        throw new Error(detail || `The request failed (${res.status}).`);
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      let finished = false;
      while (!finished) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let end = buffer.indexOf("\n\n");
        while (end >= 0) {
          const block = buffer.slice(0, end);
          buffer = buffer.slice(end + 2);
          end = buffer.indexOf("\n\n");
          const line = block.split("\n").find((l) => l.startsWith("data: "));
          if (!line) continue;
          const ev = JSON.parse(line.slice(6)) as {
            type: string; text?: string; detail?: string; response_type?: ResponseType;
            analysis?: ChatAnalysis; signature?: string; route?: ChatResponse["route"];
          };
          if (ev.type === "meta" && ev.response_type) {
            const rt = ev.response_type;
            patch((m) => (m.role === "assistant" && m.kind !== "error" ? { ...m, responseType: rt } : m));
          } else if (ev.type === "token") {
            full += ev.text ?? "";
            kick();
          } else if (ev.type === "reset") {
            full = "";
            flush();
          } else if (ev.type === "analysis" && ev.analysis) {
            const analysis = ev.analysis;
            const signature = ev.signature ?? "";
            const route = ev.route as ChatResponse["route"];
            patch((m) =>
              m.role === "assistant" && m.kind !== "error"
                ? { id: m.id, role: "assistant", kind: "analysis", responseType: "analysis", reply: m.reply, analysis, signature, route }
                : m,
            );
          } else if (ev.type === "replace") {
            full = ev.text ?? "";
            flush();
          } else if (ev.type === "error") {
            throw new Error(ev.detail || "Something went wrong.");
          } else if (ev.type === "done") {
            finished = true;
          }
        }
      }
      flush();
    } catch (err) {
      if (timer !== null) clearTimeout(timer);
      timer = null;
      if (controller.signal.aborted) {
        if (!full) setText("Stopped.");
        else flush();
      } else {
        const raw = err instanceof Error ? err.message : "";
        const dropped = !raw || /network|fetch|load failed|aborted|terminated|stream|body/i.test(raw);
        const msg = dropped ? "The connection to TrustShield dropped. Please send your message again." : raw;
        patch(() => ({ id: msgId, role: "assistant", kind: "error", text: msg }));
      }
    }
  }

  async function send(text: string, attachment: File | null, context: Context | null, history: HistoryTurn[], convId: string) {
    setBusyLabel(attachment ? "Checking the attachment…" : "Thinking…");
    setNotice("");
    const controller = new AbortController();
    controllersRef.current.set(convId, controller);
    setStreaming((prev) => new Set(prev).add(convId));
    const finish = () => {
      controllersRef.current.delete(convId);
      setStreaming((prev) => {
        const next = new Set(prev);
        next.delete(convId);
        return next;
      });
    };
    if (!attachment) {
      try {
        await streamChat(text, context, history, convId, controller);
      } finally {
        finish();
      }
      return;
    }
    const append = (m: Message) => setChats((prev) => prev.map((c) => (c.id === convId ? { ...c, messages: [...c.messages, m] } : c)));
    try {
      const form = new FormData();
      form.append("file", attachment);
      form.append("message", text);
      if (context) {
        form.append("context", JSON.stringify(context.analysis));
        form.append("context_signature", context.signature);
      }
      form.append("history", JSON.stringify(history));
      const res = await authFetch(`${API}/api/v1/chat/upload`, { method: "POST", body: form, signal: controller.signal });
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
        const raw = err instanceof Error ? err.message : "";
        const dropped = !raw || /network|fetch|load failed|aborted|terminated|stream|body/i.test(raw);
        const msg = dropped ? "The connection to TrustShield dropped. Please send your message again." : raw;
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
      finish();
    }
  }

  // Adds a user message after `base` (the messages kept before it) and asks for a reply. Used for new sends, edits and regenerate.
  function dispatch(convId: string, base: Message[], text: string, attachment: File | null, preview?: { url: string; name: string }) {
    const title = base.length === 0 ? (text || preview?.name || "New chat").slice(0, 40) : undefined;
    const userMsg: Message = { id: uid(), role: "user", text, fileName: preview?.name, previewUrl: preview?.url };
    setChats((prev) => prev.map((c) => (c.id === convId ? { ...c, title: title ?? c.title, messages: [...base, userMsg] } : c)));
    void send(text, attachment, lastAnalysisOf(base), toHistory(base), convId);
  }

  function submit(textOverride?: string) {
    const text = (textOverride ?? draft).trim();
    if (busy || (!text && !attached)) return;
    if (listening) {
      recogRef.current?.stop();
      setListening(false);
    }
    const attachment = attached?.file ?? null;
    dispatch(active.id, messages, text, attachment, attached ? { url: attached.url, name: attached.file.name } : undefined);
    setDraft("");
    setAttached(null); // the URL stays alive: the sent message still shows the thumbnail
  }

  // Edit a message you sent: it replaces that message and everything after it, and the reply is asked again.
  function saveEdit(index: number) {
    const text = editing?.text.trim() ?? "";
    if (!text || busy) return;
    setEditing(null);
    dispatch(active.id, messages.slice(0, index), text, null);
  }

  // Regenerate: ask the last question again, replacing the last reply.
  function regenerate(replyIndex: number) {
    const question = messages[replyIndex - 1];
    if (busy || !question || question.role !== "user") return;
    dispatch(active.id, messages.slice(0, replyIndex - 1), question.text, null);
  }

  function newChat() {
    const c = newConversation();
    setChats((prev) => [c, ...prev]);
    setActiveId(c.id);
    setDraft("");
    removeAttachment();
    setNotice("");
    setEditing(null);
    setSidebarOpen(false);
    inputRef.current?.focus();
  }

  function switchTo(id: string) {
    setActiveId(id);
    setEditing(null);
    setSidebarOpen(false);
  }

  function deleteChat(id: string) {
    const chat = chats.find((c) => c.id === id);
    if (!chat || !window.confirm(`Delete "${chat.title}"? This cannot be undone.`)) return;
    controllersRef.current.get(id)?.abort();
    const remaining = chats.filter((c) => c.id !== id);
    if (remaining.length === 0) {
      const c = newConversation();
      setChats([c]);
      setActiveId(c.id);
    } else {
      setChats(remaining);
      if (id === active.id) setActiveId(remaining[0].id);
    }
  }

  function commitRename() {
    if (!renaming) return;
    const title = renaming.title.trim().slice(0, 60);
    if (title) setChats((prev) => prev.map((c) => (c.id === renaming.id ? { ...c, title } : c)));
    setRenaming(null);
  }

  function copyText(id: string, text: string) {
    void navigator.clipboard?.writeText(text);
    setCopied(id);
    window.setTimeout(() => setCopied((c) => (c === id ? null : c)), 1500);
  }

  const empty = messages.length === 0;
  // Regenerate only the newest reply, to a text question (an attachment is not kept for a second ask).
  const canRegenerate = (i: number) => {
    const prev = messages[i - 1];
    return i === messages.length - 1 && !busy && prev?.role === "user" && !prev.previewUrl;
  };

  return (
    <div
      className="relative flex h-dvh w-full bg-white text-base text-zinc-900"
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
        ref={sidebarRef}
        tabIndex={-1}
        aria-label="Sidebar"
        inert={!identityReady}
        className={`${sidebarOpen ? "fixed inset-y-0 left-0 z-30 w-[310px] shadow-2xl" : "hidden"} flex flex-col border-r border-[rgba(0,0,0,0.1)] bg-[rgb(252,252,252)] p-3 text-zinc-900 min-[801px]:static min-[801px]:flex min-[801px]:w-[260px] min-[801px]:shrink-0 min-[801px]:shadow-none`}
      >
        <div className="flex h-[52px] items-center gap-2 px-2 pb-1 pt-0">
          <Logo className="h-7 w-7 text-xs" />
          <span className="font-semibold tracking-tight">TrustShield</span>
        </div>
        <button
          type="button"
          onClick={newChat}
          className="flex min-h-9 items-center gap-2 rounded-[10px] border border-zinc-300 bg-white px-2.5 py-2 text-left text-sm hover:bg-zinc-100 focus:outline-none focus:ring-2 focus:ring-[#3566f0]"
        >
          <span aria-hidden="true" className="text-lg leading-none">+</span> New chat
        </button>
        <nav className="mt-3 flex-1 overflow-y-auto" aria-label="Previous chats">
          <p className="px-2 pb-2 text-xs font-medium uppercase tracking-wider text-zinc-500">Chats</p>
          <ul className="space-y-0.5">
            {identityReady && chats.map((c) => {
              const isActive = c.id === active.id;
              const isRenaming = renaming?.id === c.id;
              return (
                <li key={c.id} className={`group flex items-center gap-1 rounded-[10px] pr-1 ${isActive ? "bg-zinc-200 max-[800px]:bg-white/10" : "hover:bg-zinc-100 max-[800px]:hover:bg-white/10"}`}>
                  {isRenaming ? (
                    <input
                      autoFocus
                      aria-label="Chat name"
                      value={renaming.title}
                      onChange={(e) => setRenaming({ id: c.id, title: e.target.value })}
                      onBlur={commitRename}
                      onKeyDown={(e) => {
                        if (e.key === "Enter") commitRename();
                        if (e.key === "Escape") setRenaming(null);
                      }}
                      className="min-w-0 flex-1 rounded-lg bg-white px-3 py-2 text-sm outline-none ring-2 ring-[#3566f0]"
                    />
                  ) : (
                    <button
                      type="button"
                      onClick={() => switchTo(c.id)}
                      onDoubleClick={() => setRenaming({ id: c.id, title: c.title })}
                      aria-current={isActive ? "true" : undefined}
                      className="min-h-9 min-w-0 flex-1 truncate rounded-[10px] px-2.5 py-2 text-left text-sm focus:outline-none focus:ring-2 focus:ring-[#3566f0]"
                    >
                      {c.title}
                    </button>
                  )}
                  {!isRenaming && (
                    <div className="flex shrink-0 opacity-100 min-[801px]:opacity-0 min-[801px]:group-hover:opacity-100 min-[801px]:group-focus-within:opacity-100">
                      <IconButton label={`Rename ${c.title}`} onClick={() => setRenaming({ id: c.id, title: c.title })} className="h-7 w-7">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><path d="M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4 12.5-12.5z" /></svg>
                      </IconButton>
                      <IconButton label={`Delete ${c.title}`} onClick={() => deleteChat(c.id)} className="h-7 w-7 hover:text-red-400">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><path d="M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14" /></svg>
                      </IconButton>
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        </nav>
        <p className="px-2 pt-3 text-xs leading-5 text-zinc-500 max-[800px]:text-zinc-400">
          {session ? "Chats are saved to your account, so they stay after a refresh." : "Sign in to save your chats. Without an account, this history clears when you refresh or log out."}
        </p>
        {session && (
          <div className="mt-2 flex items-center justify-between gap-2 rounded-lg px-2 py-2 text-xs text-zinc-500">
            <span className="min-w-0 truncate">{session.user.email}</span>
            <button type="button" onClick={logOut} className="shrink-0 hover:text-zinc-900 max-[800px]:text-zinc-100 max-[800px]:hover:text-white">Log out</button>
          </div>
        )}
      </aside>
      {identityReady && sidebarOpen && <button type="button" aria-label="Close chats" onClick={() => setSidebarOpen(false)} className="fixed inset-0 z-20 bg-black/35 min-[801px]:hidden" />}

      <div className="flex min-w-0 flex-1 flex-col" inert={!identityReady}>
        <AppHeader
          title={identityReady ? active.title : "Your chats"}
          session={session}
          loading={loading}
          onOpenSidebar={() => setSidebarOpen(true)}
          apiUp={apiUp}
          mlUp={mlUp}
        />

        <main className="flex-1 overflow-y-auto bg-[#fcfcfc] px-4" aria-busy={!identityReady}>
          <div aria-live="polite" aria-atomic="true" className="sr-only">
            {busy ? "Working on it." : messages.length > 0 && messages[messages.length - 1].role === "assistant" ? "New response received." : ""}
          </div>

          {!identityReady && <p role="status" className="mx-auto mt-12 max-w-3xl text-center text-sm text-zinc-500">Loading your chats…</p>}
          {identityReady && empty && <EmptyState starters={STARTERS} onPick={(s) => submit(s)} />}

          {identityReady && <ol aria-label="Conversation" className="mx-auto max-w-3xl space-y-7 py-8">
            {messages.map((m, index) => (
              <li key={m.id} className={m.role === "user" ? "flex justify-end" : "flex gap-3"}>
                {m.role === "user" ? (
                  <div className="group flex max-w-[85%] flex-col items-end gap-1">
                    {editing?.id === m.id ? (
                      <div className="w-full min-w-[16rem] rounded-2xl bg-zinc-200 p-3">
                        <textarea
                          aria-label="Edit message"
                          autoFocus
                          value={editing.text}
                          onChange={(e) => setEditing({ id: m.id, text: e.target.value.slice(0, MAX_CHARS) })}
                          onKeyDown={(e) => {
                            if (e.key === "Escape") setEditing(null);
                            if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
                              e.preventDefault();
                              saveEdit(index);
                            }
                          }}
                          rows={3}
                          className="w-full resize-none bg-transparent text-base outline-none"
                        />
                        <div className="mt-2 flex justify-end gap-2 text-sm">
                          <button type="button" onClick={() => setEditing(null)} className="min-h-9 rounded-lg px-3 hover:bg-zinc-100">Cancel</button>
                          <button type="button" onClick={() => saveEdit(index)} disabled={!editing.text.trim() || busy} className="min-h-9 rounded-lg bg-zinc-100 px-3 font-medium text-zinc-900 disabled:opacity-40">Save and resend</button>
                        </div>
                      </div>
                    ) : (
                      <div className="rounded-3xl bg-zinc-200 px-4 py-2.5">
                        {/* eslint-disable-next-line @next/next/no-img-element -- blob: preview */}
                        {m.previewUrl && <img src={m.previewUrl} alt={m.fileName ?? "Attached image"} className="mb-2 max-h-56 rounded-xl" />}
                        {m.text && <p className="whitespace-pre-wrap break-words leading-7">{m.text}</p>}
                      </div>
                    )}
                    {editing?.id !== m.id && (
                      <div className="flex gap-1 opacity-100 min-[801px]:opacity-0 min-[801px]:group-hover:opacity-100 min-[801px]:group-focus-within:opacity-100">
                        {m.text && (
                          <button type="button" onClick={() => copyText(m.id, m.text)} className="min-h-11 rounded-lg px-2 text-xs text-zinc-500 hover:bg-zinc-100 hover:text-zinc-900 focus:outline-none focus:ring-2 focus:ring-[#3566f0]">
                            {copied === m.id ? "Copied" : "Copy"}
                          </button>
                        )}
                        {m.text && !busy && (
                          <button type="button" onClick={() => setEditing({ id: m.id, text: m.text })} className="min-h-11 rounded-lg px-2 text-xs text-zinc-500 hover:bg-zinc-100 hover:text-zinc-900 focus:outline-none focus:ring-2 focus:ring-[#3566f0]">
                            Edit
                          </button>
                        )}
                      </div>
                    )}
                  </div>
                ) : m.kind === "error" ? (
                  <div role="alert" className="w-full rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-red-800">
                    <p>{m.text}</p>
                    {m.retry && (
                      <button type="button" onClick={m.retry} disabled={busy} className="mt-2 min-h-11 rounded-lg border border-red-200 px-4 py-2 font-medium hover:bg-red-100 focus:outline-none focus:ring-2 focus:ring-[#3566f0] disabled:opacity-50">
                        Retry
                      </button>
                    )}
                  </div>
                ) : (
                  <>
                    <Logo className="mt-0.5 h-7 w-7 text-[10px]" />
                    <div className="group min-w-0 flex-1">
                      <p className="whitespace-pre-wrap break-words leading-7 text-zinc-900">{m.reply.text}</p>
                      <div className="mt-2 flex flex-wrap items-center gap-1 text-xs text-zinc-500 opacity-100 min-[801px]:opacity-0 min-[801px]:group-hover:opacity-100 min-[801px]:group-focus-within:opacity-100">
                        {m.kind === "analysis" && <span className="mr-2">{m.reply.grounded ? "Based on the evidence" : "Not verified against the evidence"}</span>}
                        {m.reply.text && (
                          <button type="button" onClick={() => copyText(m.id, m.reply.text)} className="min-h-11 rounded-lg px-2 text-zinc-500 hover:bg-zinc-100 hover:text-zinc-900 focus:outline-none focus:ring-2 focus:ring-[#3566f0]">
                            {copied === m.id ? "Copied" : "Copy"}
                          </button>
                        )}
                        {m.reply.text && canRegenerate(index) && (
                          <button type="button" onClick={() => regenerate(index)} className="min-h-11 rounded-lg px-2 text-zinc-500 hover:bg-zinc-100 hover:text-zinc-900 focus:outline-none focus:ring-2 focus:ring-[#3566f0]">
                            Regenerate
                          </button>
                        )}
                      </div>
                      {m.kind === "analysis" && <div className="mt-3"><RiskCard analysis={m.analysis} /></div>}
                    </div>
                  </>
                )}
              </li>
            ))}
            {busy && (
              <li role="status" className="flex items-center gap-3 text-sm text-zinc-500">
                <Logo className="h-7 w-7 text-[10px] motion-safe:animate-pulse" />
                <span>{busyLabel}</span>
                <button type="button" onClick={() => controllersRef.current.get(active.id)?.abort()} className="min-h-11 rounded-lg px-2 py-1 font-medium text-zinc-800 underline hover:bg-zinc-100 focus:outline-none focus:ring-2 focus:ring-[#3566f0]">Stop</button>
              </li>
            )}
          </ol>}
          <div ref={endRef} />
        </main>

        <div className={`${empty ? "absolute left-0 right-0 mx-auto top-[calc(50%-18px)] min-[801px]:left-[260px] min-[801px]:right-0 min-[801px]:w-[calc(100%-260px)] min-[801px]:top-[calc(50%-38px)]" : "mx-auto w-full"} w-full max-w-[800px] px-3 pb-3 pt-2 min-[1024px]:px-4`}>
          {dragging && <p className="mb-2 rounded-2xl border-2 border-dashed border-teal-500 p-3 text-center text-sm">Drop an image to attach it</p>}
          {notice && <p role="alert" className="mb-2 text-sm font-medium text-red-600">{notice}</p>}
          {attached && (
            <div className="mb-2 flex items-center gap-3 rounded-2xl bg-zinc-200 p-2">
              {/* eslint-disable-next-line @next/next/no-img-element -- blob: preview */}
              <img src={attached.url} alt="Attachment preview" className="h-14 w-14 rounded-xl object-cover" />
              <span className="min-w-0 flex-1 truncate text-sm">{attached.file.name}</span>
              <button type="button" onClick={removeAttachment} aria-label="Remove attachment" className="min-h-10 rounded-lg px-3 py-2 text-sm underline">Remove</button>
            </div>
          )}

          <form
            onSubmit={(e) => {
              e.preventDefault();
              submit();
            }}
            className="group relative flex items-center gap-0 rounded-[28px] border border-[rgba(0,0,0,0.1)] bg-white px-1.5 py-[3px] shadow-[0_0_0_1px_rgba(0,0,0,0.04),0_2px_8px_rgba(0,0,0,0.04),0_4px_40px_8px_rgba(0,0,0,0.03)] focus-within:border-zinc-300"
          >
            <input ref={fileRef} type="file" accept={ALLOWED.join(",")} className="sr-only" aria-label="Attach an image" tabIndex={-1} onChange={(e) => { accept(e.target.files?.[0]); e.target.value = ""; setAttachOpen(false); }} />
            <div className="relative">
              <button type="button" onClick={() => setAttachOpen((o) => !o)} aria-label="Attach" aria-expanded={attachOpen} className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full text-2xl text-zinc-800 hover:bg-zinc-100 focus:outline-none focus:ring-2 focus:ring-[#3566f0] min-[801px]:h-11 min-[801px]:w-11">
                +
              </button>
              {attachOpen && (
                <div role="menu" className="absolute bottom-14 left-0 z-10 w-60 rounded-2xl border border-zinc-300 bg-white p-1 shadow-xl">
                  <button type="button" role="menuitem" onClick={() => { setAttachOpen(false); fileRef.current?.click(); }} className="flex w-full items-center gap-2 rounded-xl px-3 py-2 text-left text-sm hover:bg-zinc-100">
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
              className="max-h-48 min-h-6 flex-1 resize-none bg-transparent px-1.5 py-0 text-base leading-6 text-zinc-900 outline-none placeholder:text-zinc-500 min-[801px]:text-[17px]"
            />
            {speechSupported && (
              <button type="button" onClick={toggleListening} aria-pressed={listening} aria-label={listening ? "Stop voice input" : "Speak your message"} className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-full hover:bg-zinc-100 focus:outline-none focus:ring-2 focus:ring-[#3566f0] min-[801px]:h-11 min-[801px]:w-11 ${listening ? "bg-red-100" : ""}`}>
                {listening ? "■" : "🎙"}
              </button>
            )}
            <button type="submit" disabled={busy || (!draft.trim() && !attached)} aria-label="Send" className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full text-zinc-900 hover:bg-zinc-100 focus:outline-none focus:ring-2 focus:ring-[#3566f0] disabled:opacity-50 min-[801px]:h-11 min-[801px]:w-11">
              <span aria-hidden="true" className="flex h-8 w-8 items-center justify-center rounded-full bg-[#3566f0] text-lg font-semibold text-white">↑</span>
            </button>
          </form>
          <p className="mt-2 text-center text-xs text-zinc-500">
            Avoid sharing passwords, OTPs or card secrets. Results are risk evidence, not a guarantee of safety.
          </p>
        </div>
      </div>

      {showGate && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
          <div role="dialog" aria-modal="true" aria-labelledby="gate-heading" aria-describedby="gate-description" ref={gateRef} tabIndex={-1} className="relative w-full max-w-sm rounded-2xl bg-white p-6 text-center shadow-2xl focus:outline-none">
              <button type="button" onClick={dismissGate} aria-label="Skip for now" className="absolute right-3 top-3 flex h-9 w-9 items-center justify-center rounded-full text-zinc-400 hover:bg-zinc-100 hover:text-zinc-900 focus:outline-none focus:ring-2 focus:ring-[#3566f0] after:absolute after:-inset-2 after:content-['']">
                ✕
              </button>
              <Logo className="mx-auto h-10 w-10" />
              <h2 id="gate-heading" className="mt-4 text-xl font-semibold text-zinc-900">Welcome to TrustShield</h2>
              <p id="gate-description" className="mt-2 text-sm text-zinc-500">Log in or sign up to save your scan history to your account.</p>
            <div className="mt-6 flex flex-col gap-2">
              <a href="/login" className="rounded-lg bg-zinc-900 py-2.5 text-sm font-medium text-white hover:bg-zinc-800">Log in</a>
              <a href="/signup" className="rounded-lg border border-zinc-300 py-2.5 text-sm font-medium text-zinc-900 hover:bg-zinc-50">Sign up</a>
            </div>
            <button type="button" onClick={dismissGate} className="relative mt-4 text-sm text-zinc-500 underline hover:text-zinc-900 after:absolute after:-inset-2 after:content-['']">Skip for now</button>
          </div>
        </div>
      )}
    </div>
  );
}
