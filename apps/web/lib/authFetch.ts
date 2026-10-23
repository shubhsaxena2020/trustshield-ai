import { supabase } from "./supabaseClient";

// A user access token must never be sent to the Supabase origin: its auth endpoints authenticate with
// the anon key, and the API only accepts the token on its own endpoints. NEXT_PUBLIC_* values are
// inlined into the browser bundle at build time.
const supabaseOrigin = (() => {
  const configured = process.env.NEXT_PUBLIC_SUPABASE_URL ?? "";
  try {
    return configured ? new URL(configured).origin : "";
  } catch {
    return "";
  }
})();

function targetsSupabase(url: string): boolean {
  if (!supabaseOrigin) return false;
  try {
    const base = typeof window === "undefined" ? undefined : window.location.href;
    return new URL(url, base).origin === supabaseOrigin;
  } catch {
    return false;
  }
}

// A body fetch has already consumed (ReadableStream, Request, Response) cannot be replayed by a retry.
// Everything this app sends (JSON strings, FormData uploads, Blobs) can be sent a second time.
function isReplayable(body: RequestInit["body"]): boolean {
  return (
    body == null ||
    typeof body === "string" ||
    body instanceof FormData ||
    body instanceof URLSearchParams ||
    body instanceof Blob ||
    body instanceof ArrayBuffer ||
    ArrayBuffer.isView(body)
  );
}

async function sessionToken(): Promise<string | null> {
  if (!supabase) return null;
  try {
    const { data } = await supabase.auth.getSession();
    return data.session?.access_token ?? null;
  } catch {
    // Storage blocked or a refresh threw a non-auth error: continue unauthenticated rather than
    // failing the caller's request outright. The response then carries the API's own 401.
    return null;
  }
}

async function refreshedToken(): Promise<string | null> {
  if (!supabase) return null;
  try {
    const { data, error } = await supabase.auth.refreshSession();
    if (error) return null;
    return data.session?.access_token ?? null;
  } catch {
    return null;
  }
}

export async function authFetch(url: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  // The caller's own Authorization wins: this helper supplies the session token only when nobody else
  // did, so an explicit header is never dropped. Headers are copied, never mutated on the caller's init.
  const callerSuppliedAuth = headers.has("Authorization");
  const mayAttach = !callerSuppliedAuth && !targetsSupabase(url);

  let token: string | null = null;
  if (mayAttach) {
    token = await sessionToken();
    if (token) headers.set("Authorization", `Bearer ${token}`);
  }

  const res = await fetch(url, { ...init, headers });

  // 401 means the API rejected the credential (or the absence of one). One refresh plus a replay
  // recovers a token that expired in flight or whose background refresh failed, instead of leaving the
  // caller to report an unexplained 401. Aborted calls and un-replayable bodies are never retried, and
  // a failed refresh returns the original response untouched so the caller's error path still runs.
  if (res.status !== 401 || !mayAttach || !supabase || init.signal?.aborted || !isReplayable(init.body)) {
    return res;
  }

  const refreshed = await refreshedToken();
  if (!refreshed || refreshed === token || init.signal?.aborted) return res;

  headers.set("Authorization", `Bearer ${refreshed}`);
  if (res.body) void res.body.cancel().catch(() => {});
  return fetch(url, { ...init, headers });
}
