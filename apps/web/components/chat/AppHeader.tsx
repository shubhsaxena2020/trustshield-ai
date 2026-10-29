"use client";

import { useRef } from "react";
import Image from "next/image";
import { Icon } from "./icons";

export type AppHeaderSession = { user: { email?: string } } | null;

export interface AppHeaderProps {
  title: string;
  session: AppHeaderSession;
  loading: boolean;
  onToggleSidebar: () => void;
  sidebarOpen: boolean;
  theme: "light" | "dark";
  onToggleTheme: (origin: { x: number; y: number }) => void;
  scrolled: boolean;
  apiUp: boolean | null;
  mlUp: boolean | null;
}

export function AppHeader({ title, session, loading, onToggleSidebar, sidebarOpen, theme, onToggleTheme, scrolled, apiUp, mlUp }: AppHeaderProps) {
  const loggedOut = !loading && session === null;
  const themeBtn = useRef<HTMLButtonElement>(null);

  return (
    <>
      <header className="ts-header" data-scrolled={scrolled}>
        <button
          type="button"
          onClick={onToggleSidebar}
          aria-label={sidebarOpen ? "Close sidebar" : "Open sidebar"}
          aria-expanded={sidebarOpen}
          className="ts-btn-ghost h-10 w-10 shrink-0"
        >
          <Icon name="sidebar" size={20} />
        </button>

        <div className="pointer-events-none absolute left-1/2 top-1/2 flex -translate-x-1/2 -translate-y-1/2 items-center gap-2 min-[801px]:hidden">
          <Image src="/logo-icon.png" alt="TrustShield" width={22} height={22} className="ts-logo shrink-0 object-contain" unoptimized />
          <span className="text-sm font-semibold tracking-tight">TrustShield</span>
        </div>

        <h1 className="min-w-0 flex-1 truncate text-sm font-medium text-ts-muted max-[800px]:sr-only">{title}</h1>
        <div className="flex-1 min-[801px]:hidden" aria-hidden="true" />

        <span className="hidden shrink-0 items-center gap-2 rounded-full border border-ts-border px-3 py-1 text-xs text-ts-muted min-[1000px]:flex">
          <span
            aria-hidden="true"
            className={`h-1.5 w-1.5 rounded-full ${apiUp === false ? "bg-red-500" : apiUp ? "bg-emerald-500" : "bg-ts-muted"}`}
          />
          {apiUp === false ? "Service offline" : apiUp ? "Scam checker · evidence-first" : "Connecting…"}
        </span>

        <button
          ref={themeBtn}
          type="button"
          onClick={() => {
            const r = themeBtn.current?.getBoundingClientRect();
            onToggleTheme({ x: r ? r.left + r.width / 2 : window.innerWidth - 40, y: r ? r.top + r.height / 2 : 28 });
          }}
          aria-label={theme === "dark" ? "Switch to light theme" : "Switch to dark theme"}
          className="ts-btn-ghost h-10 w-10 shrink-0"
        >
          <Icon name={theme === "dark" ? "sun" : "moon"} size={19} />
        </button>

        {loggedOut && (
          <nav aria-label="Account" className="flex shrink-0 items-center gap-2">
            <a href="/login" className="rounded-full bg-ts-text px-4 py-2 text-sm font-semibold text-ts-bg transition-transform hover:scale-[1.04] active:scale-95">
              Log in
            </a>
            <a href="/signup" className="rounded-full border border-ts-border-strong px-4 py-2 text-sm font-semibold transition-colors hover:bg-ts-hover max-[800px]:hidden">
              Sign up
            </a>
          </nav>
        )}

        {!loggedOut && session?.user?.email && (
          <span className="hidden max-w-[220px] shrink-0 truncate text-xs text-ts-muted min-[801px]:block">{session.user.email}</span>
        )}
      </header>

      {apiUp === false && (
        <p role="alert" className="mx-4 mt-2 rounded-xl border border-red-300/60 bg-red-500/10 px-3 py-2 text-xs text-red-700 dark:text-red-300">
          The analysis service is unreachable right now. Please try again shortly.
        </p>
      )}
      {apiUp && mlUp === false && (
        <p role="status" className="mx-4 mt-2 rounded-xl border border-amber-300/60 bg-amber-500/10 px-3 py-2 text-xs text-amber-800 dark:text-amber-300">
          The machine-learning module is unavailable, so results use rule-based checks only.
        </p>
      )}
    </>
  );
}

export default AppHeader;
