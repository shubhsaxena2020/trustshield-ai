"use client";

import { useState } from "react";
import Image from "next/image";
import Link from "next/link";
import { supabase } from "@/lib/supabaseClient";

export default function SignupPage() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!supabase) {
      setError("Accounts are not configured on this deployment.");
      return;
    }
    setBusy(true);
    setError("");
    const { error: signUpError } = await supabase.auth.signUp({ email, password });
    setBusy(false);
    if (signUpError) {
      setError(signUpError.message);
      return;
    }
    setDone(true);
  }

  return (
    <main className="min-h-dvh flex flex-col items-center justify-center bg-white px-4">
      <Image src="/logo-icon.png" alt="TrustShield AI" width={40} height={40} className="h-10 w-10 object-contain" unoptimized />
      <h1 className="mt-6 text-2xl font-semibold text-zinc-900">Sign up</h1>
      {done ? (
        <p role="alert" className="mt-6 w-full max-w-sm text-sm text-zinc-600">
          Check your email to confirm your account, then{" "}
          <Link href="/login" className="text-zinc-900 underline">log in</Link>.
        </p>
      ) : (
        <form onSubmit={handleSubmit} className="mt-6 w-full max-w-sm space-y-4">
          <div className="space-y-1">
            <label htmlFor="signup-email" className="text-sm font-medium text-zinc-900">Email</label>
            <input
              id="signup-email"
              type="email"
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              className="w-full rounded-lg border border-zinc-300 px-3 py-2.5 text-sm focus:border-zinc-900 focus:outline-none"
            />
          </div>
          <div className="space-y-1">
            <label htmlFor="signup-password" className="text-sm font-medium text-zinc-900">Password</label>
            <input
              id="signup-password"
              type="password"
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              minLength={6}
              className="w-full rounded-lg border border-zinc-300 px-3 py-2.5 text-sm focus:border-zinc-900 focus:outline-none"
            />
            <p className="text-xs text-zinc-500">Minimum 6 characters.</p>
          </div>
          <button type="submit" disabled={busy} className="w-full rounded-lg bg-zinc-900 py-2.5 font-medium text-white hover:bg-zinc-800 disabled:opacity-50">
            {busy ? "Signing up…" : "Sign up"}
          </button>
          {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
        </form>
      )}
      <p className="mt-4 text-sm text-zinc-600">
        Already have an account? <Link href="/login" className="text-zinc-900 underline">Log in</Link>
      </p>
    </main>
  );
}
