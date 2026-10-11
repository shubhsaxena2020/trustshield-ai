"use client";
import Image from "next/image";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useSession } from "@/lib/useSession";
import { supabase } from "@/lib/supabaseClient";
import { Button } from "@/components/ui/button";

function NavLink({ href, children }: { href: string; children: React.ReactNode }) {
  const pathname = usePathname();
  const active = pathname === href;
  return (
    <Link
      href={href}
      aria-current={active ? "page" : undefined}
      className={active ? "font-semibold text-blue-700" : "text-slate-700 hover:text-slate-900"}
    >
      {children}
    </Link>
  );
}

export function NavBar() {
  const { session, loading } = useSession();

  return (
    <nav className="flex flex-wrap items-center justify-between gap-3 border-b px-6 py-3">
      <Link href="/" className="flex items-center gap-2 font-semibold">
        <Image src="/logo.png" alt="TrustShield AI" width={140} height={24} priority />
      </Link>
      <div className="flex items-center gap-4 text-sm">
        <NavLink href="/analyzer">Analyzer</NavLink>
        {session && <NavLink href="/history">History</NavLink>}
        {!loading && (
          session ? (
            <div className="flex items-center gap-3">
              <span className="hidden max-w-[160px] truncate text-slate-500 sm:inline">
                {session.user.email}
              </span>
              <Button variant="outline" size="sm" onClick={() => supabase?.auth.signOut()}>
                Log out
              </Button>
            </div>
          ) : (
            <div className="flex items-center gap-3">
              <NavLink href="/login">Log in</NavLink>
              <NavLink href="/signup">Sign up</NavLink>
            </div>
          )
        )}
      </div>
    </nav>
  );
}
