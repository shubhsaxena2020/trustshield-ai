"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { supabase } from "@/lib/supabaseClient";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

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
    router.push("/analyzer");
  }

  return (
    <main className="mx-auto max-w-sm py-16 px-4">
      <Card>
        <CardHeader>
          <CardTitle>
            <h1 className="text-base font-medium">Log in</h1>
          </CardTitle>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="space-y-1">
              <Label htmlFor="login-email">Email</Label>
              <Input id="login-email" type="email" autoComplete="email" value={email}
                     onChange={(e) => setEmail(e.target.value)} required />
            </div>
            <div className="space-y-1">
              <Label htmlFor="login-password">Password</Label>
              <Input id="login-password" type="password" autoComplete="current-password" value={password}
                     onChange={(e) => setPassword(e.target.value)} required />
            </div>
            {error && <p role="alert" className="text-sm font-medium text-red-700">{error}</p>}
            <Button type="submit" disabled={busy} className="w-full bg-blue-700 hover:bg-blue-800 text-white">
              {busy ? "Logging in…" : "Log in"}
            </Button>
          </form>
          <p className="mt-4 text-sm text-slate-600">
            No account? <Link href="/signup" className="font-medium text-blue-700 underline">Sign up</Link>
          </p>
        </CardContent>
      </Card>
    </main>
  );
}
