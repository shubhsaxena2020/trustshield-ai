"use client";
import Image from "next/image";
import Link from "next/link";
import { useSession } from "@/lib/useSession";
import { supabase } from "@/lib/supabaseClient";
import { Button } from "@/components/ui/button";

export function NavBar() {
  const { session, loading } = useSession();

  return (
    <nav className="flex items-center justify-between border-b px-6 py-3">
      <Link href="/" className="flex items-center gap-2 font-semibold">
        <Image src="/logo.png" alt="TrustShield AI" width={140} height={24} priority />
      </Link>
      <div className="flex items-center gap-4 text-sm">
        <Link href="/analyzer">Analyzer</Link>
        {session && <Link href="/history">History</Link>}
        {!loading && (
          session ? (
            <div className="flex items-center gap-3">
              <span className="text-slate-500">{session.user.email}</span>
              <Button variant="outline" size="sm" onClick={() => supabase?.auth.signOut()}>
                Log out
              </Button>
            </div>
          ) : (
            <div className="flex items-center gap-3">
              <Link href="/login">Log in</Link>
              <Link href="/signup">Sign up</Link>
            </div>
          )
        )}
      </div>
    </nav>
  );
}
