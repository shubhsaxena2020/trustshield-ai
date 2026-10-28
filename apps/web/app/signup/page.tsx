"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import Image from "next/image";
import Link from "next/link";
import { supabase } from "@/lib/supabaseClient";
import { authNetworkMessage } from "@/lib/authNetworkMessage.mjs";

const errorId = "signup-error";
const passwordHintId = "signup-password-hint";

/** Which field, if any, a GoTrue error message/code is about. */
function fieldForAuthError(message: string, code?: string): "email" | "password" | null {
  if (code === "user_already_exists" || code === "email_exists" || code === "email_address_invalid") {
    return "email";
  }
  if (/already registered|already exists|invalid email|unable to validate email|invalid format/i.test(message)) {
    return "email";
  }
  if (code === "weak_password" || /password/i.test(message)) {
    return "password";
  }
  return null;
}

export default function SignupPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [errorField, setErrorField] = useState<"email" | "password" | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  // Guard against a second submit before React re-renders the disabled button.
  const inFlight = useRef(false);
  const emailRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  const doneRef = useRef<HTMLParagraphElement>(null);
  const errorRef = useRef<HTMLParagraphElement>(null);

  useEffect(() => {
    if (done) doneRef.current?.focus();
  }, [done]);

  useEffect(() => {
    if (!error) return;
    if (errorField === "email") emailRef.current?.focus();
    else if (errorField === "password") passwordRef.current?.focus();
    else errorRef.current?.focus();
  }, [error, errorField]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (inFlight.current) return;
    if (!supabase) {
      setError("Accounts are not configured on this deployment.");
      setErrorField(null);
      return;
    }
    inFlight.current = true;
    setBusy(true);
    setError("");
    setErrorField(null);
    try {
      const { data, error: signUpError } = await supabase.auth.signUp({ email, password });
      if (signUpError) {
        setBusy(false);
        // Surface the server message verbatim; only classify which field it refers to.
        const status = signUpError.status ?? 0;
        setError(status === 0 ? authNetworkMessage(typeof navigator === "undefined" || navigator.onLine !== false) : signUpError.message);
        setErrorField(status === 0 ? null : fieldForAuthError(signUpError.message, signUpError.code));
        return;
      }
      if (data.session) {
        // Email confirmation is disabled on this project: signUp already signed the user in.
        // Stay busy (form disabled) until navigation lands, like /login does.
        router.push("/");
        return;
      }
      setBusy(false);
      if (data.user) {
        // Confirmation email sent: no session yet.
        setDone(true);
        return;
      }
      setError("Sign-up did not complete. Please try again.");
    } catch {
      setBusy(false);
      setErrorField(null);
      setError(authNetworkMessage(typeof navigator === "undefined" || navigator.onLine !== false));
    } finally {
      inFlight.current = false;
    }
  }

  const emailDescribedBy = errorField === "email" ? errorId : undefined;
  const passwordDescribedBy = errorField === "password" ? `${passwordHintId} ${errorId}` : passwordHintId;

  return (
    <main className="min-h-dvh flex flex-col items-center justify-center bg-white px-4">
      <Image src="/logo-icon.png" alt="TrustShield AI" width={40} height={40} className="h-10 w-10 object-contain" unoptimized />
      <h1 className="mt-6 text-2xl font-semibold text-zinc-900">Sign up</h1>
      {done ? (
        <p
          ref={doneRef}
          role="status"
          aria-live="polite"
          tabIndex={-1}
          className="mt-6 w-full max-w-sm text-sm text-zinc-600 focus:outline-none"
        >
          Check your email to confirm your account, then{" "}
          <Link href="/login" className="text-zinc-900 underline">log in</Link>.
        </p>
      ) : (
        <form onSubmit={handleSubmit} className="mt-6 w-full max-w-sm space-y-4">
          <div className="space-y-1">
            <label htmlFor="signup-email" className="text-sm font-medium text-zinc-900">Email</label>
            <input
              id="signup-email"
              ref={emailRef}
              type="email"
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              aria-invalid={errorField === "email" ? true : undefined}
              aria-describedby={emailDescribedBy}
              className="w-full rounded-lg border border-zinc-300 px-3 py-2.5 text-base focus:border-zinc-900 focus:outline-none aria-invalid:border-red-500 sm:text-sm"
            />
          </div>
          <div className="space-y-1">
            <label htmlFor="signup-password" className="text-sm font-medium text-zinc-900">Password</label>
            <input
              id="signup-password"
              ref={passwordRef}
              type="password"
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              minLength={6}
              aria-invalid={errorField === "password" ? true : undefined}
              aria-describedby={passwordDescribedBy}
              className="w-full rounded-lg border border-zinc-300 px-3 py-2.5 text-base focus:border-zinc-900 focus:outline-none aria-invalid:border-red-500 sm:text-sm"
            />
            <p id={passwordHintId} className="text-xs text-zinc-500">Minimum 6 characters.</p>
          </div>
          <button type="submit" disabled={busy} className="w-full rounded-lg bg-zinc-900 py-2.5 font-medium text-white hover:bg-zinc-800 disabled:opacity-50">
            {busy ? "Signing up…" : "Sign up"}
          </button>
          {error && <p id={errorId} ref={errorRef} role="alert" tabIndex={-1} className="text-sm text-red-600">{error}</p>}
        </form>
      )}
      <p className="mt-4 text-sm text-zinc-600">
        Already have an account? <Link href="/login" className="text-zinc-900 underline">Log in</Link>
      </p>
    </main>
  );
}
