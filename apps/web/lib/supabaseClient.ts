import { createClient, type SupabaseClient } from "@supabase/supabase-js";

const url = process.env.NEXT_PUBLIC_SUPABASE_URL ?? "";
const anonKey = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY ?? "";

export const supabaseConfigured = Boolean(url && anonKey);

// One client per browser tab, not one per module evaluation. A module-level binding is enough for a
// cold load, but Next.js Fast Refresh re-evaluates this module while the previous client is still
// alive (its auto-refresh timer and visibility listener keep it referenced, and nothing disposes it),
// so an edited file would leave two GoTrueClients sharing one storage key and racing to rotate the
// same refresh token. Pinning the instance on globalThis makes the re-evaluated module reuse it.
type SupabaseGlobal = typeof globalThis & { __trustshieldSupabase?: SupabaseClient };

const globalStore = globalThis as SupabaseGlobal;

export const supabase = supabaseConfigured
  ? (globalStore.__trustshieldSupabase ??= createClient(url, anonKey))
  : null;
