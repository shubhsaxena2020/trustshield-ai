"use client";

import Image from "next/image";
export type AppHeaderSession = { user: { email?: string } } | null;

export interface AppHeaderProps {
  title: string;
  session: AppHeaderSession;
  loading: boolean;
  onOpenSidebar: () => void;
  apiUp: boolean | null;
  mlUp: boolean | null;
}

export function AppHeader({
  title,
  session,
  loading,
  onOpenSidebar,
  apiUp,
  mlUp,
}: AppHeaderProps) {
  const loggedOut = !loading && session === null;

  return (
    <>
      <header className="relative flex h-[61px] items-center gap-3 border-b border-zinc-200 bg-white px-4">
        <button
          type="button"
          onClick={onOpenSidebar}
          aria-label="Open chats"
          className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-zinc-700 hover:bg-zinc-100 focus:outline-none focus:ring-2 focus:ring-blue-500 min-[801px]:hidden"
        >
          <svg
            width="20"
            height="20"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            aria-hidden="true"
          >
            <path d="M4 6h16M4 12h16M4 18h16" />
          </svg>
        </button>

        <div className="pointer-events-none absolute left-1/2 top-1/2 flex -translate-x-1/2 -translate-y-1/2 items-center gap-2 min-[801px]:hidden">
          <Image
            src="/logo-icon.png"
            alt="TrustShield"
            width={24}
            height={24}
            className="shrink-0 object-contain"
            unoptimized
          />
          <span className="text-sm font-semibold tracking-tight text-zinc-900">
            TrustShield
          </span>
        </div>

        <h1 className="min-w-0 flex-1 truncate text-sm font-semibold text-zinc-800 max-[800px]:sr-only">
          {title}
        </h1>

        <span className="hidden shrink-0 text-xs text-zinc-500 min-[801px]:block">
          Scam checker · evidence-first
        </span>

        {loggedOut && (
          <nav aria-label="Account" className="flex shrink-0 items-center gap-2">
            <a
              href="/login"
              className="rounded-full bg-zinc-900 px-4 py-2.5 text-sm font-semibold text-white hover:bg-zinc-800 focus:outline-none focus:ring-2 focus:ring-[#3566f0]"
            >
              Log in
            </a>
            <a
              href="/signup"
              className="rounded-full border border-zinc-200 bg-white px-4 py-2.5 text-sm font-semibold text-zinc-900 hover:bg-zinc-50 focus:outline-none focus:ring-2 focus:ring-[#3566f0] max-[800px]:hidden"
            >
              Sign up
            </a>
          </nav>
        )}

        {!loggedOut && session?.user?.email && (
          <span className="hidden shrink-0 text-xs text-zinc-500 min-[801px]:block">
            {session.user.email}
          </span>
        )}
      </header>

      {apiUp === false && (
        <p
          role="alert"
          className="mx-4 mt-2 rounded-xl border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-800"
        >
          The analysis service is unreachable right now. Please try again shortly.
        </p>
      )}
      {apiUp && mlUp === false && (
        <p
          role="status"
          className="mx-4 mt-2 rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800"
        >
          The machine-learning module is unavailable, so results use rule-based checks only.
        </p>
      )}
    </>
  );
}

export default AppHeader;
