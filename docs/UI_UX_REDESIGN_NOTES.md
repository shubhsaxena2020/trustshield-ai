# UI/UX redesign notes

What changed visually tonight, and why, for the team to speak to during the Round 2 presentation.

## IMPLEMENTED

### shadcn/ui adoption

Pulled in shadcn/ui (`Button`, `Input`, `Card`, `Table`, `Dialog`, `Label`) on top of the existing
Tailwind v4 setup instead of hand-rolling new forms/tables, so the new login/signup/history
surfaces got accessible, consistent primitives in minutes rather than hours. The existing analyzer
components (`ResultDashboard`, `FusionPanel`, `MlPanel`, `OcrPanel`, `QrPanel`) were left as
custom Tailwind — only their copy changed (see below), not their markup/logic.

### New TrustShield AI logo

The team's new wordmark logo (`apps/web/public/logo.png`) replaced the text-only brand mark in the
nav bar.

### Nav bar

A new session-aware nav bar (`apps/web/components/NavBar.tsx`) sits above every page: logo,
Analyzer link, and either Log in/Sign up or the user's email + History link + Log out, depending
on session state. Previously there was no persistent navigation at all.

### Coverage-status and confidence wording

A module that was never configured (no Safe Browsing/cert-history key set) and one that was
configured but genuinely failed both rendered as the same word, "Unavailable," in the analysis
coverage panel — two different facts shown identically. `ResultDashboard.tsx` now shows "Not
configured" specifically when the backend's own detail text says so (`getModuleStatusLabel`),
display-only — no change to the underlying module status/detail values or detection logic.
Risk score, confidence, and possible category were already shown as three separately labeled
fields (`<dt>`/`<dd>` pairs) in the assessment card, so no change was needed there — it reads
unambiguously already.

### History "clear all" and a privacy disclosure line

The analyzer page gained one line of copy making the opt-in nature of history explicit: "Scans
are saved to your history only when you're logged in. Scanning without an account stays exactly
as private as before — nothing is stored." The history page's delete affordance includes a
"clear all" action alongside the existing per-row delete.

### What didn't change

No dark mode work — the existing `dark:` Tailwind classes throughout the codebase (visible in
`apps/web/components/utils.ts`'s `LABELS`/`SEV_BADGE` maps) were left exactly as they were; this
pass added no new dark-mode-specific styling and didn't need to, since `prefers-color-scheme`
handling was already in place. The analyzer page's own layout, tabs, and result cards are
visually untouched beyond the copy clarifications above.
