"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Image from "next/image";
import Link from "next/link";
import { supabase } from "@/lib/supabaseClient";

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!supabase) {
      setError("Accounts are not configured on this deployment.");
      return;
    }
    setBusy(true);
    setError("");
    const { error: signInError } = await supabase.auth.signInWithPassword({ email, password });
    setBusy(false);
    if (signInError) {
      setError(signInError.message);
      return;
    }
    router.push("/");
  }

  return (
    <main className="min-h-dvh flex flex-col items-center justify-center bg-white px-4">
      <Image src="/logo-icon.png" alt="TrustShield AI" width={40} height={40} className="h-10 w-10 object-contain" unoptimized />
      <h1 className="mt-6 text-2xl font-semibold text-zinc-900">Log in</h1>
      <form onSubmit={handleSubmit} className="mt-6 w-full max-w-sm space-y-4">
        <div className="space-y-1">
          <label htmlFor="login-email" className="text-sm font-medium text-zinc-900">Email</label>
          <input
            id="login-email"
            type="email"
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            className="w-full rounded-lg border border-zinc-300 px-3 py-2.5 text-sm focus:border-zinc-900 focus:outline-none"
          />
        </div>
        <div className="space-y-1">
          <label htmlFor="login-password" className="text-sm font-medium text-zinc-900">Password</label>
          <input
            id="login-password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            className="w-full rounded-lg border border-zinc-300 px-3 py-2.5 text-sm focus:border-zinc-900 focus:outline-none"
          />
        </div>
        <button type="submit" disabled={busy} className="w-full rounded-lg bg-zinc-900 py-2.5 font-medium text-white hover:bg-zinc-800 disabled:opacity-50">
          {busy ? "Logging in…" : "Log in"}
        </button>
        {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
      </form>
      <p className="mt-4 text-sm text-zinc-600">
        No account? <Link href="/signup" className="text-zinc-900 underline">Sign up</Link>
      </p>
    </main>
  );
}
