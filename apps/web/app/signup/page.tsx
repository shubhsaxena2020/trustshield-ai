"use client";
import { useState } from "react";
import Link from "next/link";
import { supabase } from "@/lib/supabaseClient";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
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
    <main className="mx-auto max-w-sm py-16 px-4">
      <Card>
        <CardHeader>
          <CardTitle>
            <h1 className="text-base font-medium">Sign up</h1>
          </CardTitle>
        </CardHeader>
        <CardContent>
          {done ? (
            <p role="alert" className="text-sm text-slate-700">
              Check your email to confirm your account, then{" "}
              <Link href="/login" className="font-medium text-blue-700 underline">log in</Link>.
            </p>
          ) : (
            <form onSubmit={handleSubmit} className="space-y-4">
              <div className="space-y-1">
                <Label htmlFor="signup-email">Email</Label>
                <Input id="signup-email" type="email" autoComplete="email" value={email}
                       onChange={(e) => setEmail(e.target.value)} required />
              </div>
              <div className="space-y-1">
                <Label htmlFor="signup-password">Password</Label>
                <Input id="signup-password" type="password" autoComplete="new-password" value={password}
                       onChange={(e) => setPassword(e.target.value)} required minLength={6} />
                <p className="text-xs text-slate-500">Minimum 6 characters.</p>
              </div>
              {error && <p role="alert" className="text-sm font-medium text-red-700">{error}</p>}
              <Button type="submit" disabled={busy} className="w-full bg-blue-700 hover:bg-blue-800 text-white">
                {busy ? "Signing up…" : "Sign up"}
              </Button>
            </form>
          )}
          <p className="mt-4 text-sm text-slate-600">
            Already have an account? <Link href="/login" className="font-medium text-blue-700 underline">Log in</Link>
          </p>
        </CardContent>
      </Card>
    </main>
  );
}
