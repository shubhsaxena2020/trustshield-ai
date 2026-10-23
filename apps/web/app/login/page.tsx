"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import Image from "next/image";
import Link from "next/link";
import { supabase } from "@/lib/supabaseClient";

type InvalidFields = { email: boolean; password: boolean };

const NO_INVALID_FIELDS: InvalidFields = { email: false, password: false };

// auth-js reports a failed fetch with status 0 and a bare "Failed to fetch".
const NETWORK_ERROR_MESSAGE = "Could not reach the server. Check your connection and try again.";

/** Which field(s) a failed sign-in implicates, for aria-invalid and focus placement. */
function invalidFieldsFor(message: string): InvalidFields {
  const text = message.toLowerCase();
  const mentionsEmail = text.includes("email");
  const mentionsPassword = text.includes("password");
  if (mentionsEmail && mentionsPassword) return { email: true, password: true };
  if (mentionsEmail) return { email: true, password: false };
  if (mentionsPassword) return { email: false, password: true };
  // "Invalid login credentials" names neither field, so either one may be wrong.
  return { email: true, password: true };
}

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [invalid, setInvalid] = useState<InvalidFields>(NO_INVALID_FIELDS);
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);
  const emailRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  const errorRef = useRef<HTMLParagraphElement>(null);

  // Move focus to the rejected field so its label + description are announced;
  // fall back to the alert when no single field is at fault.
  useEffect(() => {
    if (!error) return;
    if (invalid.password) passwordRef.current?.focus();
    else if (invalid.email) emailRef.current?.focus();
    else errorRef.current?.focus();
  }, [error, invalid]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    // One auth request at a time. `busy` is state and only reaches the DOM after
    // React re-renders, so a second submit dispatched in the same tick would
    // otherwise pass the check and open a second session attempt.
    if (inFlight.current) return;
    if (!supabase) {
      setInvalid(NO_INVALID_FIELDS);
      setError("Accounts are not configured on this deployment.");
      return;
    }
    inFlight.current = true;
    setBusy(true);
    setError("");
    setInvalid(NO_INVALID_FIELDS);
    let succeeded = false;
    try {
      const { error: signInError } = await supabase.auth.signInWithPassword({ email, password });
      if (signInError) {
        // Only a rejected credential (4xx) implicates the fields; a server or
        // network failure leaves them valid and sends focus to the alert instead.
        const status = signInError.status ?? 0;
        const rejected = status >= 400 && status < 500;
        setInvalid(rejected ? invalidFieldsFor(signInError.message) : NO_INVALID_FIELDS);
        setError(status === 0 ? NETWORK_ERROR_MESSAGE : signInError.message);
        return;
      }
      succeeded = true;
      router.push("/");
    } catch {
      setInvalid(NO_INVALID_FIELDS);
      setError(NETWORK_ERROR_MESSAGE);
    } finally {
      inFlight.current = false;
      // Keep "Logging in…" visible while the redirect lands.
      if (!succeeded) setBusy(false);
    }
  }

  return (
    <main className="min-h-dvh flex flex-col items-center justify-center bg-white px-4">
      <Image src="/logo-icon.png" alt="TrustShield AI" width={40} height={40} className="h-10 w-10 object-contain" unoptimized />
      <h1 className="mt-6 text-2xl font-semibold text-zinc-900">Log in</h1>
      <form onSubmit={handleSubmit} aria-busy={busy} className="mt-6 w-full max-w-sm space-y-4">
        <div className="space-y-1">
          <label htmlFor="login-email" className="text-sm font-medium text-zinc-900">Email</label>
          <input
            id="login-email"
            ref={emailRef}
            type="email"
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            aria-invalid={invalid.email || undefined}
            aria-describedby={error ? "login-error" : undefined}
            className="w-full rounded-lg border border-zinc-300 px-3 py-2.5 text-base focus:border-zinc-900 focus:outline-none aria-invalid:border-red-500 sm:text-sm"
          />
        </div>
        <div className="space-y-1">
          <label htmlFor="login-password" className="text-sm font-medium text-zinc-900">Password</label>
          <input
            id="login-password"
            ref={passwordRef}
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            aria-invalid={invalid.password || undefined}
            aria-describedby={error ? "login-error" : undefined}
            className="w-full rounded-lg border border-zinc-300 px-3 py-2.5 text-base focus:border-zinc-900 focus:outline-none aria-invalid:border-red-500 sm:text-sm"
          />
        </div>
        <button type="submit" disabled={busy} className="w-full rounded-lg bg-zinc-900 py-2.5 font-medium text-white hover:bg-zinc-800 disabled:opacity-50">
          {busy ? "Logging in…" : "Log in"}
        </button>
        <p role="status" className="sr-only">{busy ? "Signing in…" : ""}</p>
        {error && (
          <p id="login-error" ref={errorRef} role="alert" tabIndex={-1} className="text-sm text-red-600">
            {error}
          </p>
        )}
      </form>
      <p className="mt-4 text-sm text-zinc-600">
        No account? <Link href="/signup" className="text-zinc-900 underline">Sign up</Link>
      </p>
    </main>
  );
}
