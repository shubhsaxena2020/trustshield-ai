"use client";
import { useEffect, useState } from "react";
import type { Session } from "@supabase/supabase-js";
import { supabase } from "./supabaseClient";

export function useSession() {
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(() => (supabase ? true : false));

  useEffect(() => {
    if (!supabase) {
      return;
    }
    let active = true;
    // getSession() rejects when storage is unavailable (blocked storage, private mode) or when a refresh
    // throws a non-auth error. Unhandled, that is an uncaught rejection in the console and `loading` stays
    // true forever, so every consumer waits on a session that is never coming. Treat it as signed out.
    supabase.auth
      .getSession()
      .then(({ data }) => {
        if (active) setSession(data.session);
      })
      .catch(() => {
        if (active) setSession(null);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    const { data: sub } = supabase.auth.onAuthStateChange((_event, next) => setSession(next));
    return () => {
      active = false;
      sub.subscription.unsubscribe();
    };
  }, []);

  return { session, loading };
}
