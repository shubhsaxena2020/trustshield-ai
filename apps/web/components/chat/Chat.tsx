"use client";

import { useCallback, useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore, type ReactNode } from "react";
import Image from "next/image";
import { authFetch } from "@/lib/authFetch";
import { userFacingRequestMessage } from "@/lib/userFacingError.mjs";
import { supabase } from "@/lib/supabaseClient";
import { useSession } from "@/lib/useSession";
import { EmptyState } from "./EmptyState";
import { AppHeader } from "./AppHeader";
import { RiskCard } from "./RiskCard";
import { Icon } from "./icons";
import { ShieldOrb } from "./ShieldOrb";
import { gsap, Flip, useGSAP, prefersReducedMotion } from "@/lib/gsap";
import { useTheme } from "@/lib/useTheme";
import type { ChatAnalysis, ChatResponse, Message, ResponseType } from "./types";

// Production is same-origin (Vercel Services routes /api/* to the backend); see the deployment runbook.
const API = process.env.NEXT_PUBLIC_API_URL ?? (process.env.NODE_ENV === "production" ? "" : "http://localhost:8000");
const MAX_FILE_BYTES = 4 * 1024 * 1024;
const MAX_DOCUMENT_BYTES = 5 * 1024 * 1024;
const MAX_ATTACHMENTS = 5;
const MAX_TOTAL_FILE_BYTES = 4 * 1024 * 1024;
const MAX_CHARS = 5000;
const IMAGE_TYPES = ["image/png", "image/jpeg", "image/webp", "image/heic", "image/heif"];
const DOCUMENT_TYPES = ["application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/plain", "text/csv"];
const ALLOWED = [...IMAGE_TYPES, ...DOCUMENT_TYPES];
const ALLOWED_EXT = /\.(png|jpe?g|webp|heic|heif|pdf|docx|xlsx|txt|csv)$/i;

const STARTERS = [
  "I got this text: 'URGENT: your bank account is suspended. Verify now at secure-bank-login.top' — is it real?",
  "How does UPI payment-request fraud work?",
  "Someone called claiming to be customs and wants money to release a parcel. Is that normal?",
  "How can I tell if a payment QR code is legitimate before scanning it?",
];

type Conversation = { id: string; title: string; messages: Message[] };
type ScanHistoryEntry = { id: string; input_type: string; input_summary: string; risk_score: number; risk_label: string; created_at: string };
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
        .map((m) => (m.role === "user" ? { id: m.id, role: m.role, text: m.text, fileName: m.fileName,
          attachments: m.attachments?.map((a) => ({ fileName: a.fileName })) } : m)),
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

function spotlight(e: React.PointerEvent<HTMLElement>) {
  const r = e.currentTarget.getBoundingClientRect();
  e.currentTarget.style.setProperty("--mx", `${e.clientX - r.left}px`);
  e.currentTarget.style.setProperty("--my", `${e.clientY - r.top}px`);
}

const DESKTOP_MQ = "(min-width: 801px)";
function subscribeDesktop(onChange: () => void) {
  const mq = window.matchMedia(DESKTOP_MQ);
  mq.addEventListener("change", onChange);
  return () => mq.removeEventListener("change", onChange);
}

function Logo({ className = "" }: { className?: string }) {
  return <Image src="/logo-icon.png" alt="TrustShield AI" width={28} height={28} className={`ts-logo shrink-0 object-contain ${className}`} unoptimized />;
}

function IconButton({ label, onClick, children, className = "" }: { label: string; onClick: () => void; children: ReactNode; className?: string }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      title={label}
      className={`ts-btn-ghost h-8 w-8 rounded-lg focus-visible:outline-2 ${className}`}
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
  const [scanHistory, setScanHistory] = useState<ScanHistoryEntry[]>([]);
  const [historyLoading, setHistoryLoading] = useState(Boolean(session));
  const [historyError, setHistoryError] = useState("");
  const [draft, setDraft] = useState("");
  const [attached, setAttached] = useState<{ file: File; url: string }[]>([]);
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
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [exiting, setExiting] = useState(false);
  const [scrolled, setScrolled] = useState(false);
  const [atBottom, setAtBottom] = useState(true);
  const { theme, toggle: toggleTheme } = useTheme();
  const desktop = useSyncExternalStore(subscribeDesktop, () => window.matchMedia(DESKTOP_MQ).matches, () => true);
  const speechSupported = useSyncExternalStore(
    () => () => {},
    () => getSpeechCtor() !== null,
    () => false,
  );
  const controllersRef = useRef(new Map<string, AbortController>());
  const endRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const mainRef = useRef<HTMLElement>(null);
  const colRef = useRef<HTMLDivElement>(null);
  const composerRef = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLUListElement>(null);
  const flipState = useRef<ReturnType<typeof Flip.getState> | null>(null);
  const prevCount = useRef({ id: "", n: 0 });
  const prevChats = useRef(0);
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

  const refreshHistory = useCallback(async () => {
    if (!session) return;
    try {
      const res = await authFetch(`${API}/api/v1/history`);
      if (!res.ok) throw new Error("history request failed");
      const rows = await res.json();
      if (!Array.isArray(rows)) throw new Error("invalid history response");
      setScanHistory(rows as ScanHistoryEntry[]);
      setHistoryError("");
    } catch {
      setHistoryError("Scan history could not be loaded. Please try again.");
    } finally {
      setHistoryLoading(false);
    }
  }, [session]);

  useEffect(() => {
    if (!session) return;
    const task = window.setTimeout(() => void refreshHistory(), 0);
    return () => window.clearTimeout(task);
  }, [identity, refreshHistory, session]);

  useLayoutEffect(() => {
    const input = inputRef.current;
    if (!input) return;
    input.style.height = "auto";
    input.style.height = `${Math.min(input.scrollHeight, 192)}px`;
  }, [draft, desktop, chats]);

  const active = chats.find((c) => c.id === activeId) ?? chats[0];
  const busy = streaming.has(active.id);
  const messages = active.messages;
  const lastAnalysis = lastAnalysisOf(messages);
  const wasEmpty = useRef(messages.length === 0);

  // The composer glides from the centre of the welcome screen down to the bottom when the first message is sent.
  useLayoutEffect(() => {
    const nowEmpty = messages.length === 0;
    if (wasEmpty.current && !nowEmpty && flipState.current && !prefersReducedMotion()) {
      Flip.from(flipState.current, { duration: 0.85, ease: "expo.out", scale: false });
    }
    flipState.current = null;
    wasEmpty.current = nowEmpty;
  }, [messages.length]);

  // Keep --comp-h (composer height) current so the starter chips sit just under it on the welcome screen.
  useEffect(() => {
    const el = composerRef.current;
    const col = colRef.current;
    if (!el || !col) return;
    const ro = new ResizeObserver(() => col.style.setProperty("--comp-h", `${el.offsetHeight}px`));
    ro.observe(el);
    return () => ro.disconnect();
  }, [identityReady]);

  // New messages ease in; switching chats does not replay the animation.
  useGSAP(
    () => {
      const prev = prevCount.current;
      const grew = prev.id === active.id && messages.length > prev.n;
      prevCount.current = { id: active.id, n: messages.length };
      if (!grew || prefersReducedMotion()) return;
      const items = mainRef.current?.querySelectorAll<HTMLElement>("[data-msg]");
      const last = items?.[items.length - 1];
      if (last) gsap.from(last, { opacity: 0, y: 18, duration: 0.7, ease: "power3.out", clearProps: "opacity,transform" });
    },
    { dependencies: [messages.length, active.id] },
  );

  // New chats slide into the sidebar list.
  useGSAP(
    () => {
      const grew = chats.length > prevChats.current && prevChats.current > 0;
      prevChats.current = chats.length;
      if (!grew || prefersReducedMotion()) return;
      const first = listRef.current?.firstElementChild;
      if (first) gsap.from(first, { opacity: 0, x: -16, height: 0, duration: 0.55, ease: "power3.out", clearProps: "all" });
    },
    { dependencies: [chats.length] },
  );

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

  function onMainScroll(e: React.UIEvent<HTMLElement>) {
    const el = e.currentTarget;
    setScrolled(el.scrollTop > 4);
    setAtBottom(el.scrollHeight - el.scrollTop - el.clientHeight < 140);
  }

  function scrollToEnd() {
    endRef.current?.scrollIntoView?.({ behavior: prefersReducedMotion() ? "auto" : "smooth", block: "end" });
  }

  function toggleSidebar() {
    if (desktop) setSidebarCollapsed((c) => !c);
    else setSidebarOpen((o) => !o);
  }

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

  function accept(input: FileList | File[] | File | undefined | null) {
    if (!input) return;
    const incoming = input instanceof File ? [input] : Array.from(input);
    if (attached.length + incoming.length > MAX_ATTACHMENTS) return setNotice("You can attach up to 5 files per message.");
    if ([...attached.map((item) => item.file), ...incoming].reduce((sum, file) => sum + file.size, 0) > MAX_TOTAL_FILE_BYTES) return setNotice("Attachments must total 4 MB or less. Remove a file or choose smaller files.");
    for (const f of incoming) {
    // Some mobile browsers (notably older Android WebViews on a camera capture) report an empty or generic
    // f.type instead of the real image MIME type. Fall back to the filename extension rather than blocking the
    // upload outright; the backend decodes and validates the actual bytes regardless of what either side claims.
    const isDocument = DOCUMENT_TYPES.includes(f.type) || /\.(pdf|docx|xlsx|txt|csv)$/i.test(f.name);
    const typeOk = f.type ? ALLOWED.includes(f.type) || (f.type.startsWith("text/") && isDocument) : ALLOWED_EXT.test(f.name);
    if (!typeOk) return setNotice("Attach PNG, JPEG, WebP, HEIC/HEIF images or PDF, DOCX, XLSX, TXT and CSV documents.");
    if (f.size > (isDocument ? MAX_DOCUMENT_BYTES : MAX_FILE_BYTES)) return setNotice(isDocument ? "Each document must be 5 MB or smaller." : "Each image must be 4 MB or smaller.");
    }
    setNotice("");
    setAttached((prev) => [...prev, ...incoming.map((file) => ({ file, url: URL.createObjectURL(file) }))]);
  }

  function removeAttachment(index?: number) {
    setAttached((prev) => {
      if (index === undefined) {
        prev.forEach((item) => URL.revokeObjectURL(item.url));
        return [];
      }
      URL.revokeObjectURL(prev[index]?.url);
      return prev.filter((_, i) => i !== index);
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
        throw new Error(userFacingRequestMessage(res.status, detail));
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
            void refreshHistory();
          } else if (ev.type === "replace") {
            full = ev.text ?? "";
            flush();
          } else if (ev.type === "error") {
            throw new Error(userFacingRequestMessage(422, ev.detail));
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

  async function send(text: string, attachment: File[], context: Context | null, history: HistoryTurn[], convId: string) {
    setBusyLabel(attachment.length ? "Checking the attachments…" : "Thinking…");
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
    if (attachment.length === 0) {
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
      for (const item of attachment) form.append("files", item);
      form.append("message", text);
      if (context) {
        form.append("context", JSON.stringify(context.analysis));
        form.append("context_signature", context.signature);
      }
      form.append("history", JSON.stringify(history));
      const res = await authFetch(`${API}/api/v1/chat/upload`, { method: "POST", body: form, signal: controller.signal });
      if (!res.ok) {
        const detail = await res.json().then((d) => (typeof d.detail === "string" ? d.detail : "")).catch(() => "");
        throw new Error(userFacingRequestMessage(res.status, detail));
      }
      const data: ChatResponse = await res.json();
      append(
        data.kind === "analysis" && data.analysis
          ? { id: uid(), role: "assistant", kind: "analysis", responseType: data.response_type, reply: data.reply, analysis: data.analysis, signature: data.context_signature ?? "", route: data.route }
          : { id: uid(), role: "assistant", kind: "followup", responseType: data.response_type, reply: data.reply },
      );
      if (data.kind === "analysis") void refreshHistory();
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
  function dispatch(convId: string, base: Message[], text: string, attachment: File[], previews: { url: string; name: string }[] = []) {
    const title = base.length === 0 ? (text || previews[0]?.name || "New chat").slice(0, 40) : undefined;
    const userMsg: Message = { id: uid(), role: "user", text, fileName: previews[0]?.name, previewUrl: previews[0]?.url,
      attachments: previews.map(({ url, name }) => ({ fileName: name, previewUrl: url })) };
    setChats((prev) => prev.map((c) => (c.id === convId ? { ...c, title: title ?? c.title, messages: [...base, userMsg] } : c)));
    void send(text, attachment, lastAnalysisOf(base), toHistory(base), convId);
  }

  function submit(textOverride?: string) {
    const text = (textOverride ?? draft).trim();
    if (busy || (!text && !attached.length)) return;
    if (listening) {
      recogRef.current?.stop();
      setListening(false);
    }
    const attachment = attached.map(({ file }) => file);
    if (messages.length === 0) {
      if (composerRef.current) flipState.current = Flip.getState(composerRef.current);
      setExiting(true);
    }
    dispatch(active.id, messages, text, attachment, attached.map(({ url, file }) => ({ url, name: file.name })));
    setDraft("");
    setAttached([]); // the URLs stay alive: the sent message still shows the thumbnails
  }

  // Edit a message you sent: it replaces that message and everything after it, and the reply is asked again.
  function saveEdit(index: number) {
    const text = editing?.text.trim() ?? "";
    if (!text || busy) return;
    setEditing(null);
    dispatch(active.id, messages.slice(0, index), text, []);
  }

  // Regenerate: ask the last question again, replacing the last reply.
  function regenerate(replyIndex: number) {
    const question = messages[replyIndex - 1];
    if (busy || !question || question.role !== "user") return;
    dispatch(active.id, messages.slice(0, replyIndex - 1), question.text, []);
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
  const showWelcome = identityReady && (empty || exiting);
  // Regenerate only the newest reply, to a text question (an attachment is not kept for a second ask).
  const canRegenerate = (i: number) => {
    const prev = messages[i - 1];
    return i === messages.length - 1 && !busy && prev?.role === "user" && !prev.previewUrl;
  };
  const level = lastAnalysis?.analysis.risk_level;
  const actionBtn = "ts-btn-ghost h-8 w-8 rounded-lg focus-visible:outline-2";

  return (
    <div
      className="relative flex h-dvh w-full overflow-hidden bg-ts-bg text-base text-ts-text"
      onDragOver={(e) => {
        e.preventDefault();
        setDragging(true);
      }}
      onDragLeave={(e) => {
        if (e.currentTarget.contains(e.relatedTarget as Node | null)) return;
        setDragging(false);
      }}
      onDrop={(e) => {
        e.preventDefault();
        setDragging(false);
        accept(e.dataTransfer.files);
      }}
    >
      {/* Sidebar: collapsible on desktop, drawer on mobile */}
      <aside
        ref={sidebarRef}
        tabIndex={-1}
        aria-label="Sidebar"
        inert={!identityReady}
        data-open={sidebarOpen}
        data-collapsed={sidebarCollapsed}
        className="ts-aside"
      >
        <div className="flex h-11 items-center justify-between px-1.5">
          <div className="flex items-center gap-2.5">
            <Logo className="h-7 w-7" />
            <span className="font-semibold tracking-tight">TrustShield</span>
          </div>
          <button type="button" onClick={toggleSidebar} aria-label="Close sidebar" className="ts-btn-ghost h-9 w-9 min-[801px]:hidden">
            <Icon name="x" size={18} />
          </button>
        </div>
        <button type="button" onClick={newChat} className="ts-newchat mt-2">
          <Icon name="edit" size={17} />
          New chat
        </button>
        <nav className="ts-scroll mt-3 flex-1 overflow-y-auto" aria-label="Previous chats">
          <p className="px-2.5 pb-2 text-xs font-medium text-ts-muted">Chats</p>
          <ul ref={listRef} className="space-y-0.5">
            {identityReady && chats.map((c) => {
              const isActive = c.id === active.id;
              const isRenaming = renaming?.id === c.id;
              return (
                <li key={c.id} className="ts-chat-item group flex items-center gap-1 rounded-xl pr-1" aria-current={isActive ? "true" : undefined}>
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
                      className="min-w-0 flex-1 rounded-lg bg-ts-surface px-3 py-2 text-sm outline-none ring-2 ring-ts-accent"
                    />
                  ) : (
                    <button
                      type="button"
                      onClick={() => switchTo(c.id)}
                      onDoubleClick={() => setRenaming({ id: c.id, title: c.title })}
                      aria-current={isActive ? "true" : undefined}
                      className="min-h-10 min-w-0 flex-1 truncate rounded-xl px-2.5 py-2 text-left text-sm focus-visible:outline-2"
                    >
                      {c.title}
                    </button>
                  )}
                  {!isRenaming && (
                    <div className="flex shrink-0 opacity-100 transition-opacity min-[801px]:opacity-0 min-[801px]:group-hover:opacity-100 min-[801px]:group-focus-within:opacity-100">
                      <IconButton label={`Rename ${c.title}`} onClick={() => setRenaming({ id: c.id, title: c.title })} className="h-7 w-7">
                        <Icon name="edit" size={14} />
                      </IconButton>
                      <IconButton label={`Delete ${c.title}`} onClick={() => deleteChat(c.id)} className="h-7 w-7 hover:!text-red-500">
                        <Icon name="trash" size={14} />
                      </IconButton>
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
          {session && (
            <section aria-label="Recent scan history" className="mt-5">
              <div className="flex items-center justify-between px-2.5 pb-2">
                <p className="text-xs font-medium text-ts-muted">Recent checks</p>
                <button type="button" onClick={() => { setHistoryLoading(true); void refreshHistory(); }} disabled={historyLoading} className="text-xs text-ts-muted underline-offset-2 hover:underline disabled:opacity-50">
                  {historyLoading ? "Loading…" : "Refresh"}
                </button>
              </div>
              {historyError ? (
                <p role="alert" className="px-2.5 py-2 text-xs text-red-700 dark:text-red-300">{historyError}</p>
              ) : historyLoading && scanHistory.length === 0 ? (
                <p role="status" className="px-2.5 py-2 text-xs text-ts-muted">Loading saved checks…</p>
              ) : scanHistory.length === 0 ? (
                <p className="px-2.5 py-2 text-xs text-ts-muted">No saved checks yet.</p>
              ) : (
                <ul className="space-y-1">
                  {scanHistory.map((entry) => (
                    <li key={entry.id} className="rounded-xl px-2.5 py-2 text-xs hover:bg-ts-hover">
                      <p className="truncate font-medium" title={entry.input_summary}>{entry.input_summary || `${entry.input_type} check`}</p>
                      <p className="mt-1 flex items-center justify-between gap-2 text-ts-muted">
                        <span className="truncate">{entry.risk_label.replaceAll("_", " ")} · {entry.risk_score}/100</span>
                        <time className="shrink-0" dateTime={entry.created_at}>{new Date(entry.created_at).toLocaleDateString()}</time>
                      </p>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          )}
        </nav>
        <p className="px-2.5 pt-3 text-xs leading-5 text-ts-muted">
          {session ? "Checks are saved to your account. Conversations stay on this device." : "Sign in to save and view your scan history across sessions."}
        </p>
        {session && (
          <div className="mt-2 flex items-center justify-between gap-2 rounded-lg px-2.5 py-2 text-xs text-ts-muted">
            <span className="min-w-0 truncate">{session.user.email}</span>
            <button type="button" onClick={logOut} className="shrink-0 transition-colors hover:text-ts-text">Log out</button>
          </div>
        )}
      </aside>
      {identityReady && sidebarOpen && <button type="button" aria-label="Close chats" onClick={() => setSidebarOpen(false)} className="ts-scrim min-[801px]:hidden" />}

      <div
        ref={colRef}
        className="ts-col relative flex min-w-0 flex-1 flex-col"
        inert={!identityReady}
      >
        <div className={`ts-ambient ${level ? `ts-level-${level}` : ""}`} data-on={Boolean(level) && !empty} aria-hidden="true" />
        <AppHeader
          title={identityReady ? active.title : "Your chats"}
          session={session}
          loading={loading}
          onToggleSidebar={toggleSidebar}
          sidebarOpen={desktop ? !sidebarCollapsed : sidebarOpen}
          theme={theme}
          onToggleTheme={toggleTheme}
          scrolled={scrolled}
          apiUp={apiUp}
          mlUp={mlUp}
        />

        <main ref={mainRef} onScroll={onMainScroll} className="ts-scroll relative flex-1 overflow-y-auto px-4" aria-busy={!identityReady}>
          <div aria-live="polite" aria-atomic="true" className="sr-only">
            {busy ? "Working on it." : messages.length > 0 && messages[messages.length - 1].role === "assistant" ? "New response received." : ""}
          </div>

          {!identityReady && <p role="status" className="mx-auto mt-12 max-w-3xl text-center text-sm text-ts-muted">Loading your chats…</p>}

          {identityReady && <ol aria-label="Conversation" className="mx-auto max-w-3xl space-y-7 pb-10 pt-10">
            {messages.map((m, index) => (
              <li key={m.id} data-msg className={m.role === "user" ? "flex justify-end" : "flex gap-3"}>
                {m.role === "user" ? (
                  <div className="group flex max-w-[85%] flex-col items-end gap-1">
                    {editing?.id === m.id ? (
                      <div className="ts-pop w-full min-w-[16rem] rounded-3xl bg-ts-bubble p-3">
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
                          <button type="button" onClick={() => setEditing(null)} className="min-h-9 rounded-full px-4 font-medium transition-colors hover:bg-ts-hover">Cancel</button>
                          <button type="button" onClick={() => saveEdit(index)} disabled={!editing.text.trim() || busy} className="min-h-9 rounded-full bg-ts-text px-4 font-medium text-ts-bg transition-transform hover:scale-[1.03] disabled:opacity-40">Save and resend</button>
                        </div>
                      </div>
                    ) : (
                      <div className="rounded-3xl bg-ts-bubble px-4 py-2.5">
                        {(m.attachments?.length ? m.attachments : m.previewUrl ? [{ fileName: m.fileName ?? "Attached image", previewUrl: m.previewUrl }] : []).map((a, i) => (
                          <span key={`${a.fileName}-${i}`} className="mb-2 mr-2 inline-flex items-center gap-2 rounded-xl bg-ts-hover p-2 text-sm">
                            {a.previewUrl && <Image src={a.previewUrl} alt={a.fileName} width={48} height={48} className="h-12 w-12 rounded-lg object-cover" unoptimized />}{a.fileName}
                          </span>
                        ))}
                        {m.text && <p className="whitespace-pre-wrap break-words leading-7">{m.text}</p>}
                      </div>
                    )}
                    {editing?.id !== m.id && (
                      <div className="ts-actions flex gap-0.5">
                        {m.text && (
                          <button type="button" onClick={() => copyText(m.id, m.text)} aria-label={copied === m.id ? "Copied" : "Copy"} title="Copy" className={actionBtn}>
                            <Icon name={copied === m.id ? "check" : "copy"} size={15} />
                          </button>
                        )}
                        {m.text && !busy && (
                          <button type="button" onClick={() => setEditing({ id: m.id, text: m.text })} aria-label="Edit" title="Edit" className={actionBtn}>
                            <Icon name="edit" size={15} />
                          </button>
                        )}
                      </div>
                    )}
                  </div>
                ) : m.kind === "error" ? (
                  <div role="alert" className="w-full rounded-2xl border border-red-300/60 bg-red-500/10 px-4 py-3 text-red-800 dark:text-red-200">
                    <p>{m.text}</p>
                    {m.retry && (
                      <button type="button" onClick={m.retry} disabled={busy} className="mt-2 min-h-10 rounded-full border border-red-400/50 px-4 py-2 text-sm font-medium transition-colors hover:bg-red-500/15 disabled:opacity-50">
                        Retry
                      </button>
                    )}
                  </div>
                ) : (
                  <>
                    <Logo className="mt-0.5 h-7 w-7" />
                    <div className="group min-w-0 flex-1">
                      <p className="whitespace-pre-wrap break-words leading-7">
                        {m.reply.text}
                        {busy && index === messages.length - 1 && m.kind !== "analysis" && <span className="ts-caret" aria-hidden="true" />}
                      </p>
                      <div className="ts-actions mt-1.5 flex flex-wrap items-center gap-0.5 text-xs text-ts-muted">
                        {m.kind === "analysis" && <span className="mr-2">{m.reply.grounded ? "Based on the evidence" : "Not verified against the evidence"}</span>}
                        {m.reply.text && (
                          <button type="button" onClick={() => copyText(m.id, m.reply.text)} aria-label={copied === m.id ? "Copied" : "Copy"} title="Copy" className={actionBtn}>
                            <Icon name={copied === m.id ? "check" : "copy"} size={15} />
                          </button>
                        )}
                        {m.reply.text && canRegenerate(index) && (
                          <button type="button" onClick={() => regenerate(index)} aria-label="Regenerate" title="Regenerate" className={actionBtn}>
                            <Icon name="refresh" size={15} />
                          </button>
                        )}
                      </div>
                      {m.kind === "analysis" && <RiskCard analysis={m.analysis} />}
                    </div>
                  </>
                )}
              </li>
            ))}
            {busy && (
              <li role="status" data-msg className="flex items-center gap-3 text-sm text-ts-muted">
                <span className="h-7 w-7 shrink-0"><ShieldOrb mode="scan" density="small" className="h-full w-full" /></span>
                <span className="ts-shimmer">{busyLabel}</span>
              </li>
            )}
          </ol>}
          <div ref={endRef} />
        </main>

        {showWelcome && <EmptyState starters={STARTERS} onPick={(s) => submit(s)} leaving={exiting} onExited={() => setExiting(false)} />}

        <div
          ref={composerRef}
          className={`${empty ? "absolute inset-x-0 z-[2]" : "relative z-[2]"} mx-auto w-full max-w-[800px] px-4 pb-1 pt-2`}
          style={empty ? { top: "var(--empty-top)" } : undefined}
        >
          {!empty && !atBottom && (
            <button type="button" onClick={scrollToEnd} aria-label="Scroll to latest" className="ts-pop absolute -top-11 left-1/2 z-10 grid h-9 w-9 -translate-x-1/2 place-items-center rounded-full border border-ts-border-strong bg-ts-surface shadow-lg transition-transform hover:scale-110 active:scale-95">
              <Icon name="down" size={18} />
            </button>
          )}
          {notice && <p role="alert" className="ts-pop mb-2 px-2 text-sm font-medium text-red-600 dark:text-red-400">{notice}</p>}
          {attached.length > 0 && (
            <div className="mb-2 flex flex-wrap gap-2 rounded-2xl bg-ts-bubble p-2">
              {attached.map((item, index) => <div key={`${item.file.name}-${index}`} className="ts-pop flex min-w-0 items-center gap-2 rounded-xl bg-ts-surface p-2">
                {IMAGE_TYPES.includes(item.file.type) && <Image src={item.url} alt={`${item.file.name} preview`} width={56} height={56} className="h-14 w-14 rounded-xl object-cover" unoptimized />}
                <span className="max-w-36 truncate text-sm">{item.file.name}</span>
                <button type="button" onClick={() => removeAttachment(index)} aria-label={`Remove ${item.file.name}`} className="ts-btn-ghost h-9 w-9 shrink-0"><Icon name="x" size={16} /></button>
              </div>)}
            </div>
          )}

          <form
            onSubmit={(e) => {
              e.preventDefault();
              if (busy) return;
              submit();
            }}
            onPointerMove={spotlight}
            className="ts-composer flex items-end gap-0.5 px-1.5 py-1.5"
          >
            <input ref={fileRef} type="file" multiple accept={ALLOWED.join(",")} className="sr-only" aria-label="Attach images" tabIndex={-1} onChange={(e) => { accept(e.target.files); e.target.value = ""; setAttachOpen(false); }} />
            <div className="relative">
              <button type="button" onClick={() => setAttachOpen((o) => !o)} aria-label="Attach" aria-expanded={attachOpen} className="ts-btn-ghost h-11 w-11 rounded-full">
                <Icon name="plus" size={22} className={`transition-transform duration-300 ${attachOpen ? "rotate-45" : ""}`} />
              </button>
              {attachOpen && (
                <div role="menu" className="ts-menu absolute bottom-14 left-0 z-10 w-64 rounded-2xl border border-ts-border-strong bg-ts-surface p-1.5 shadow-[var(--ts-pop-shadow)]">
                  <button type="button" role="menuitem" onClick={() => { setAttachOpen(false); fileRef.current?.click(); }} className="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-sm transition-colors hover:bg-ts-hover">
                    <Icon name="clip" size={17} className="text-ts-muted" />
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
                const imgs = Array.from(e.clipboardData.files).filter((f) => f.type.startsWith("image/"));
                if (imgs.length) {
                  e.preventDefault();
                  accept(imgs);
                }
              }}
              rows={1}
              aria-label="Message"
              placeholder={desktop ? (lastAnalysis ? "Ask a follow-up, or paste something new to check" : "Message TrustShield, or paste something to check") : (lastAnalysis ? "Ask a follow-up…" : "Paste to check…")}
              className="max-h-48 min-h-6 flex-1 resize-none self-center bg-transparent px-1.5 py-2 text-base leading-6 text-ts-text outline-none placeholder:text-ts-muted min-[801px]:text-[17px]"
            />
            {speechSupported && (
              <button type="button" onClick={toggleListening} aria-pressed={listening} aria-label={listening ? "Stop voice input" : "Speak your message"} className="ts-btn-ghost ts-mic relative h-11 w-11 rounded-full">
                <Icon name="mic" size={20} />
              </button>
            )}
            {busy ? (
              <button type="button" onClick={() => controllersRef.current.get(active.id)?.abort()} aria-label="Stop generating" data-stop="true" className="ts-send m-1">
                <Icon name="stop" size={14} />
              </button>
            ) : (
              <button type="submit" disabled={!draft.trim() && !attached.length} aria-label="Send" className="ts-send m-1">
                <Icon name="send" size={19} strokeWidth={2.4} />
              </button>
            )}
          </form>
        </div>
        <p className={`${empty ? "absolute inset-x-0 bottom-3 z-[2] max-[800px]:hidden" : "relative"} px-4 pb-2.5 text-center text-xs text-ts-muted`}>
          Avoid sharing passwords, OTPs or card secrets. Results are risk evidence, not a guarantee of safety.
        </p>

        {dragging && (
          <div className="ts-pop pointer-events-none absolute inset-3 z-40 grid place-items-center rounded-3xl border-2 border-dashed border-ts-accent bg-ts-bg/80 backdrop-blur-sm">
            <p className="flex items-center gap-2 text-lg font-medium"><Icon name="clip" size={20} /> Drop a screenshot, QR code or document</p>
          </div>
        )}
      </div>

      {showGate && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 p-4 backdrop-blur-sm [animation:ts-fade_0.3s_both]">
          <div role="dialog" aria-modal="true" aria-labelledby="gate-heading" aria-describedby="gate-description" ref={gateRef} tabIndex={-1} className="ts-pop relative w-full max-w-sm rounded-3xl border border-ts-border bg-ts-surface p-7 text-center shadow-[var(--ts-pop-shadow)] focus:outline-none">
            <button type="button" onClick={dismissGate} aria-label="Skip for now" className="ts-btn-ghost absolute right-3 top-3 h-9 w-9 rounded-full after:absolute after:-inset-2 after:content-['']">
              <Icon name="x" size={18} />
            </button>
            <div className="mx-auto h-16 w-16"><ShieldOrb mode="idle" density="small" className="h-full w-full" /></div>
            <h2 id="gate-heading" className="font-display mt-3 text-[28px] leading-tight">Welcome to TrustShield</h2>
            <p id="gate-description" className="mt-2 text-sm text-ts-muted">Log in or sign up to save your scan history to your account.</p>
            <div className="mt-6 flex flex-col gap-2">
              <a href="/login" className="rounded-full bg-ts-text py-3 text-sm font-semibold text-ts-bg transition-transform hover:scale-[1.02] active:scale-[0.98]">Log in</a>
              <a href="/signup" className="rounded-full border border-ts-border-strong py-3 text-sm font-semibold transition-colors hover:bg-ts-hover">Sign up</a>
            </div>
            <button type="button" onClick={dismissGate} className="relative mt-4 text-sm text-ts-muted underline transition-colors hover:text-ts-text after:absolute after:-inset-2 after:content-['']">Skip for now</button>
          </div>
        </div>
      )}
    </div>
  );
}
