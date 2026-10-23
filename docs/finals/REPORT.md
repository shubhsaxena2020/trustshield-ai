# W2 Discovery — REPORT

## Follow-up implementation update (2026-10-24)

- REA was attempted first through the configured Hermes MCP session, but the current public ChatGPT page returned Cloudflare HTTP 403 before inspection. This update uses the existing measured `UI_SPEC.md` and sanitized reference captures. It does not claim current-live 1:1 parity or add new reference measurements.
- Tracked TrustShield changes: `apps/web/components/chat/Chat.tsx`, `AppHeader.tsx`, `EmptyState.tsx`, plus the implementation note in `docs/finals/UI_SPEC.md`. The keyed identity workspace restores per-account history on initialization and flushes the last committed bucket on unmount. Welcome-gate visibility uses `useSyncExternalStore`; no `eslint-disable` was added. Sidebar/new-chat, composer, empty state, message spacing, and mobile drawer geometry/style were adjusted. Auth, scan, chat, and image-upload handlers were retained; login/signup pages were not changed.
- Local Playwright DOM measurement: the main column, empty-state title, starter-card row, and composer centers differ by 0 px at 390x844, 834x1112, 1280x800, 1440x900, 1536x769, and 1920x1080. Evidence screenshots: `docs/finals/evidence/w2/local-app-390x844.png`, `local-app-834x1112.png`, `local-app-1440x900.png`, `local-app-1280x800.png`, `local-app-1536x769.png`, `local-app-1920x1080.png`, and `local-app-drawer-390.png`.
- Local Playwright multiline input check: at 390 px, entering four lines grew the textarea from 48 px to 96 px and left `scrollTop` at 0. Growth is capped at 192 px by the existing composer max height.
- Local browser checks: `/`, `/login`, and `/signup` loaded; welcome-gate skip dismissed the dialog; the mobile drawer opened; a TrustShield image showed an attachment preview; the starter scam scan returned a risk card with score 57. A free-text chat request displayed the local-model-unavailable message instead of a streamed model reply. This is not a model-backed pass.
- `apps/web` checks: `npm run lint`, `npx tsc --noEmit`, and `npm run build` exited 0. Lighthouse 13.5.0 on optimized local `next start` scored Performance 94 and Accessibility 100; page-load CLS was 0.065, while streaming CLS remains unmeasured. See `docs/finals/evidence/w2/lighthouse-local-2026-10-24.md`.
- Full Python suite: `.venv\Scripts\python.exe -m pytest -q` passed 1280 tests, skipped 7, and emitted 6 warnings in 246.01s. No Python source changed in the W2 patch.
- Remaining acceptance gaps: current reference capture/comparison and pixel-diff overlays are unavailable due the 403; the streaming CLS threshold remains unmeasured; live model streaming, authenticated sign-in/out, history isolation between real identities, non-image upload analysis, and full action/keyboard coverage remain unverified. Keep GitHub issues #2, #3, and #18 open.

---

#### Original W2 discovery record (below; not a report of this follow-up)

The original discovery work encountered different conditions at its recorded date. Its methodology/findings below document that past discovery lane, not fresh REA activity from 2026-10-24.

---

#### Original content


Package root: `C:\Users\shubh\trustshield-ai\work\finals\w2-discovery`
Branch: finals-48h. No application source changed. Interim masked evidence was pushed at bdf2a7b; this final discovery spec/report and the remaining audited layout-only captures are now ready for commit.
Deliverables: `docs/finals/UI_SPEC.md`, `docs/finals/evidence/w2/`, this `REPORT.md`.

## 1. Method and tools

### REA (used — available via MCP `mcp-rea`)

Exact REA tools invoked:
- `mcp__rea__list_browser_targets` — endpoint `http://127.0.0.1:9222`, allowed_origins `["https://chatgpt.com"]`. Result: 1 page target, 7 non-page targets excluded, 0 disallowed-origin. Evidence id `ev_fe7ce1845fe36ab2e034d9ba21935a7684402bf8b6fd9de604080ce1ec8af656`.
- `mcp__rea__inspect_web_page` — cdp_endpoint `http://127.0.0.1:9222`, target_id `465D1742ADB24EDB10F91881CCC66540`, observation_ms 800, include_accessibility_text true. Result: 664.6 KB DOM+AX+scripts+network+storage metadata. Evidence id `ev_72c8f1080e6f5ab579208419740ccbe809644c2b10ea8bab47d416ac85c3b2d2`. Saved (verbatim) to `work/finals/w2-discovery/observations/00-explore/rea/inspect_web_page_1440.json`.
- `mcp__rea__capture_web_screenshot` — NOT invoked (it returns an inline content-addressed PNG artifact with no path; the lane captured PNGs via Playwright and attempted a CSS mask. Visual audit later found that some states retained text or branding; those captures were quarantined, and only layout-only screenshots were selected for evidence.). Documented as a REA capability that was available but not used for the final artifacts because of the masking requirement.
- `mcp__rea__capture_browser_scenario` — available (schema reviewed) but not used; interaction was driven through Playwright because the scenario actions needed computed-style reads and DOM sampling that REA's scenario runner does not return.

Target identity: the page was a logged-out `https://chatgpt.com/` (title "page title omitted") held open by `tools/keep_page.py` on a throwaway profile. No personal profile, no cookies, no login.

### Fallback / measurement layer (Playwright 1.53.0, Chromium 153.0.8010.12)

All computed-style geometry, layout trees, interaction timings, and screenshots were captured through Playwright over CDP against the same throwaway browser. Representative commands (run from `work/finals/w2-discovery`):

```
# shared browser (throwaway profile, loopback CDP)
python tools/w2browser.py --port 9222

# single probe (layout tree / control inventory / overflow / grid)
python tools/w2probe.py --url https://chatgpt.com/ --width 1440 --height 900 --dsf 1 \
  --stealth --user-agent "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36" \
  --color-scheme light --settle-ms 8000 --js-file tools/probes/layout_tree.js \
  --out observations/01-desktop-shell/data/tree-1440.json --shot work/finals/w2-discovery/review-quarantine/desktop-shell-1440.png --mask-brand

# scripted interaction (composer enter, streaming samples, auth modal, drawer)
python tools/w2scenario.py --steps-file tools/scenarios/<name>.json --out observations/<lane>/data/<name>.json --shot-dir <dir>

# rich conversation capture (retries until a variant with code/table/lists appears)
python tools/capture_rich.py observations/03-messages/data/rich-conversation.json docs/finals/evidence/w2/rich-conversation-1440.png 14

# keep a page open for REA passive inspection
python tools/keep_page.py --url https://chatgpt.com/ --width 1440 --height 900
```

Browser version: Chromium 153.0.8010.12 (Playwright 1.53.0 bundled), headless=new, throwaway profile, stealth UA. Date of observation: 2026-10-23 (IST). Locale en-US, timezone UTC, color scheme light, reduced motion no-preference, DSF 1.

Cloudflare bypass: the stealth UA + init script (`Object.defineProperty(navigator,'webdriver',...)`, `window.chrome = ...`) were required; the default HeadlessChrome UA returned "Just a moment..." interstitial. Documented as a fallback-method detail.

## 2. Findings summary (observation vs inference vs unknown)

Full detail in `docs/finals/UI_SPEC.md`. Headline patterns:

Observation (measured):
- Desktop shell: 260 px sidebar (nav 259) + 1180 px content at x=260, 61 px header, 839 px main. Horizontal overflow of 200 px from an internal grid `200px 1440px 0px` (real, not a scrollbar-gutter artifact).
- Composer: rounded pill 768 x 52, radius 28, white bg, border `rgba(0,0,0,0.1)` 1 px, shadow stack, padding 7 10; textarea 618 x 24, 17 px / 24 px, max-height 192, min-height 24 (desktop) / 20 (mobile). Send button 44 x 44, aria-disabled + opacity 0.5 when empty.
- Empty state: h1 "welcome heading" 24 px / 28 px (desktop) / "alternate mobile welcome heading" (mobile); suggested pill "suggested prompt" 142.9 x 44 radius 999.
- Conversation: centered 768 px column, body 16 px / 24 px, footer disclaimer 12 px / 16 px `#5d5d5d`. Logged-out "lightweight conversation" uses "user-role label" / "assistant-role label" blocks, not `[data-message-author-role]`.
- Markdown observed: p, ul, ol, li, table, inline code, a. Never observed: h1-h3, strong, em, b, i, fenced pre.
- Streaming: not token-incremental (placeholder 13 chars -> final in one mutation); first text ~1.96-2.43 s; caret is a 13.8 px blue dot animated 0.55 s; stop button 44 x 44 "stop action"; no auto-scroll for short responses (scrollY 0, 238 px overflow below fold).
- Mobile drawer: off-canvas, 310 px (at 390) / 330 px (at 768), bg `#212121`, z-index 31, scroll-driven animation (open ~284 ms, close ~119 ms), focus trap over 12 items, scrim opacity 0->0.35.
- Breakpoint: sidebar collapses at `<= 800 px` (drawer), persistent at `>= 810 px`.
- Auth modal: 448 x 504, radius 20, overlay `rgba(0,0,0,0.5)`, entrance 0.3 s cubic-bezier; SSO buttons 365 x 48 radius 999; email input 365 x 48; focus ring `#3566f0` 2 px. Mobile: bottom sheet.
- Accessibility: landmarks, 23-element (desktop) / 11-element (mobile) focus order, `/` focuses composer, Escape closes modal, contrast table (muted `#8f8f8f` on white = 3.23 fails AA normal). No skip links, no `aria-modal`, conversation not a live region, heading hierarchy skips h3.

Inference (labelled in findings):
- Composer CSS positioning in conversation (fixed footer vs in-flow relative) unresolved (C-02).
- Auto-scroll trigger condition for tall responses (C-09).
- Copy/edit/share controls are hover-revealed icon buttons (sprite glyphs exist; not rendered in captured DOM).
- Header auth button colors (C-01) depend on theme/experiment bucket.

Unknown (see `docs/finals/UI_SPEC.md` section 14 and child 9 gaps G-01..G-21):
- Signed-in surface, code-block styling, dark mode, high-DPI, reduced motion, RTL, print, touch, high contrast, tooltips, tablet/mobile menus, rate-limit threshold, login-wall threshold, loading-indicator geometry, tooltip text, empty-state heading variant cause, mobile conversation/retry UI, empty-submit mechanism, font loading.

## 3. Unavailable / ambiguous states encountered

Child 3 delivered its message-rendering finding after the initial synthesis; all nine findings have now been reviewed.
- Theme non-determinism: identical triggers produced light or dark shells across runs even with `color_scheme=light`. All color tokens are tagged to the light variant; raw `control_inventory_*.json` from dark runs must not be used verbatim (C-03).
- The default headless UA encountered an anti-bot interstitial; observation used a throwaway browser with the recorded alternate UA and init script.
- The logged-out surface changed mid-session: after several runs, composer submission began returning the auth modal and `/uc/` URLs redirected to home. All conversation/streaming measurements come from captures taken before that change.
- `nodeCount` in `layout_tree.js` is always 0 (recorded before `walk` runs); node counts are not reliable. Tree depth capped at 14 / 900 nodes.
- Shared browser used `--hide-scrollbars`, so visual scrollbar presence could not be verified; only `scrollWidth`/`clientWidth` and computed overflow were measured.

## 4. Contradictions resolved by the parent

- C-01 header auth button colors: adopt 01/08/independent (login dark-filled, signup white) for the light theme; 07-auth's swapped colors treated as a dark/experiment variant.
- C-02 composer positioning: geometry agreed (466,824,768,52); mechanism left unspecified in the spec.
- C-03 raw color data from dark runs: cite light runs only.
- C-04 mobile textarea min-height: 24 px at >=834 px, 20 px at <=800 px.
- C-05 mobile textarea font: 16 px (correct 05-states's 17 px).
- C-07 mobile focus order: "voice action" mislabel corrected to "send action".
- C-08 login upsell: sidebar footer, not above composer.
- C-09 auto-scroll: marked UNKNOWN for tall responses.
- C-11 horizontal overflow cause: propagated 01's grid explanation.

## 5. Evidence paths

Seventeen layout-only screenshots were visually checked and are being committed under docs/finals/evidence/w2. The committed set contains desktop composer states, mobile empty/conversation/drawer states, and the tablet empty state.

Additional visually checked layout-only captures:
- Desktop: a11y-desktop-1440.png, control_inventory-1440.png, conversation-state-1440.png, empty-1440.png, loading-state-1440.png, retry-state-1440.png, rich-conversation-1440.png.
- Mobile: a11y-mobile-390.png, conversation-state-390.png, empty-390.png.

Ten unmasked captures with visible reference branding or copy are quarantined under ignored work/finals/w2-discovery/review-quarantine and are not repository evidence. The raw REA DOM/accessibility inspection, which retains public page text, is stored only at ignored work/finals/w2-discovery/observations/00-explore/rea/inspect_web_page_1440.json.

All nine child findings and raw measurements are under ignored work/finals/w2-discovery/observations. No raw page text or unmasked captures are promoted.

## 6. Acceptance check

- `docs/finals/UI_SPEC.md` written: layout grid, sidebar, composer, messages/actions, streaming caret, code, copy/regenerate/edit, empty/loading/error states, scroll, mobile drawer, accessibility, measured viewport/breakpoint values — present.
- Seventeen committed layout-only screenshots cover desktop, tablet and mobile. All unmasked or text-bearing captures are excluded.
- `REPORT.md` present with exact REA tools and fallback commands, findings, unavailable/ambiguous states, evidence paths, and observation/inference/unknown distinction.
- The implementation-facing spec uses generic labels and layout patterns. Reference branding and page text are absent from committed screenshots; unmasked captures are quarantined.
- No application source (`apps/web`, models, datasets, config) was modified.

## 7. Risks

- Theme non-determinism means color tokens must be re-verified per theme before implementation.
- C-01 and C-02 need a controlled re-run before they enter implementation as normative.
- Logged-out surface differs from signed-in; the spec describes the logged-out reference. Signed-in-only affordances are listed as unknowns and must be designed separately for the TrustShield build.
Child 3 delivered its message-rendering finding after the initial synthesis; all nine findings have now been reviewed.
