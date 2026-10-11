"use client";
import { useState } from "react";
import Link from "next/link";
import { supabase } from "@/lib/supabaseClient";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

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
    <div className="mx-auto max-w-sm py-16 px-4">
      <Card>
        <CardHeader>
          <CardTitle>Sign up</CardTitle>
        </CardHeader>
        <CardContent>
          {done ? (
            <p className="text-sm text-slate-700">
              Check your email to confirm your account, then{" "}
              <Link href="/login" className="underline">log in</Link>.
            </p>
          ) : (
            <form onSubmit={handleSubmit} className="space-y-4">
              <Input type="email" placeholder="Email" value={email}
                     onChange={(e) => setEmail(e.target.value)} required />
              <Input type="password" placeholder="Password (min 6 characters)" value={password}
                     onChange={(e) => setPassword(e.target.value)} required minLength={6} />
              {error && <p className="text-sm text-red-700">{error}</p>}
              <Button type="submit" disabled={busy} className="w-full">
                {busy ? "Signing up…" : "Sign up"}
              </Button>
            </form>
          )}
          <p className="mt-4 text-sm text-slate-600">
            Already have an account? <Link href="/login" className="underline">Log in</Link>
          </p>
        </CardContent>
      </Card>
    </div>
  );
}
