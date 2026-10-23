# TrustShield Chat Experience — UI Specification (W2 Discovery)

Source: public reference site web UI (`https://chatgpt.com/`), logged-out surface, observed 2026-10-23.
Method: REA MCP (`list_browser_targets`, `inspect_web_page`, `capture_web_screenshot`) + Playwright 1.53.0 / Chromium 153.0.8010.12 over a throwaway profile (no cookies, no login, no personal data). All measurements in CSS px at device scale factor 1, light color scheme, en-US, UTC.
Parent synthesis of `observations/01..09`. Child 9 cross-check corroborated the core geometry; residual conflicts are listed in section 17.

This document is a **pattern description** (layout, spacing, motion, interaction). It does not reproduce brand assets, logos, names, proprietary fonts, copy, or source code. The tracked evidence contains only visually checked layout-only captures; text-bearing captures are quarantined under ignored work.

## 0. Provenance and method

- Branch: `finals-48h`. Working dir: `C:\Users\shubh\trustshield-ai`.
- Browser: Chromium 153.0.8010.12, headless=new, throwaway profile, stealth UA `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36` + init script unsetting `navigator.webdriver`.
- REA evidence: `work/finals/w2-discovery/observations/00-explore/rea/inspect_web_page_1440.json` (evidence id `ev_72c8f108…`, 664 KB DOM+AX+scripts). Target id `465D1742ADB24EDB10F91881CCC66540` at `http://127.0.0.1:9222`.
- Selected screenshots are layout-only and visually checked at desktop, tablet and mobile sizes.
All nine child findings are present; signed-in-only message controls remain unknown.

## 1. Viewports and breakpoints

| Name | Width x Height | Sidebar | Composer form (w x h) | Textarea (w x h, font) |
|---|---|---|---|---|---|
| Mobile | 390 x 844 | off-canvas drawer (310 px) | 366 x 48 | 224 x 24, 16 px |
| Tablet | 834 x 1112 | persistent (260 px) | 550 x 52 | 400 x 24, 17 px |
| Desktop | 1440 x 900 | persistent (260 px) | 768 x 52 | 618 x 24, 17 px |
| Wide | 1920 x 1080 | persistent (260 px) | 768 x 52 (max) | 618 x 24, 17 px |

Breakpoints (observed, width-only sweep):
- `width <= 800 px`: drawer mode (sidebar off-canvas).
- `width >= 810 px`: persistent sidebar.
- Composer max width caps at 768 px for `>= 1024 px` viewports; below that it tracks viewport (768 px viewport -> 720 px; 390 px -> 366 px).
- `registration action` header button is hidden at `<= 800 px`; only `sign-in action` is shown on mobile.

Device scale factor 1 in all runs. Dark mode is non-deterministic across loads (same trigger can render either theme); all tokens below are the light variant. Color values must be tagged with theme.

## 2. Layout grid (desktop 1440 x 900)

- Sidebar: `aside#ds-sidebar`, role=complementary, aria-label "Sidebar", x=0, y=0, w=260, h=900, position absolute, bg `rgb(252,252,252)`, padding 0, border-width 0 (border-style `none solid none none`, right color `rgba(0,0,0,0.1)`), radius 0.
- Inner nav: `nav[aria-label="navigation items"]`, w=259, h=900 (1 px less than sidebar; no divider element).
- Header: x=260, y=0, w=1180, h=61.
- Main: x=260, y=61, w=1180, h=839 (grows with content; sticky position).
- Content column (composer, messages, empty state): x=466, w=768, centered in main (190 px margins each side).
- Document: scrollWidth 1640, clientWidth 1440 -> real horizontal overflow of 200 px caused by an internal grid `200px <viewport>px 0px` containing an aria-hidden overlay at x=200. `html` overflow-x is `auto`; scrollbars hidden in the reference. Reproduce the grid only if the off-canvas gutter is desired; otherwise constrain to 1440.

## 3. Sidebar

Structure (desktop, top to bottom):
- Top bar: h=52 (y=0..52). Contains the wordmark span (x=10, y=4, w=92.4, h=44, font 16 px / 650, color `rgb(13,13,13)`, bg transparent, radius 8 px) and a "navigation-close action" button (x=210, y=4, w=44, h=44, radius 999 px, bg transparent, border 1 px `rgba(0,0,0,0)`). An "navigation-open action" button (x=8, y=8, w=40, h=36, radius 10 px) is the collapsed-state toggle and is not visible when expanded.
- Shortcut list: 7 rows, each x=6, w=247, h=36, radius 10 px, padding 8 px 10 px, font 14 px / 400, color `rgb(13,13,13)`, bg transparent. Pitch 36 px, 0 px gap between rows, 12 px gap between wordmark bottom (y=48) and first row (y=60).
  - new-session action (y=60)
  - image feature (y=96)
  - integration feature (y=132)
  - plans link (y=168)
  - help link (y=204)
  - research option (y=240) — distinct border-color `rgb(0,0,0)` vs `rgba(0,0,0,0)` for the others.
  - settings action (y=276)
- Bottom upsell (starts y~703, height ~197): heading "account-benefits heading" (x=20, y=724, w=220, h=48, font 17 px / 700), body (x=20, y=780, w=220, h=48), "sign-in action" pill (x=20, y=836, w=220, h=44, bg `rgb(243,243,243)`, text `rgb(13,13,13)`, radius 999 px, padding 0 16 px, border 1 px `rgba(0,0,0,0)`).

Interaction styling:
- Wordmark hover bg `rgba(236,236,236,0.96)`.
- Nav item hover bg `rgb(232,232,232)`, text `rgb(13,13,13)`, radius stays 10 px.
- Active (mousedown) "new-session action": bg `rgb(205,205,205)`.

Hidden when logged out: `nav[aria-label="conversation history"]` present in DOM but 0 x 0. Dialogs `#mobile-new-chat-dialog` and `#mobile-auth-dialog` present but 0 x 0 at desktop.

## 4. Header

- Height 61 px, starts at x=260 on desktop (w=1180 at 1440, w=1660 at 1920); full-width bar on mobile.
- Desktop auth triggers (right side): "sign-in action" pill (x=1229.3, y=8.5, w=62.8, h=44, bg `rgb(13,13,13)`, text white, radius 999 px, font 14 px / 600) and "registration action" pill (x=1300.1, y=8.5, w=123.9, h=44, bg white, text `rgb(13,13,13)`, radius 999 px, font 14 px / 600). Both use `command="show-modal" commandfor="mobile-auth-dialog"`.
  - Note: one child report (07-auth) swaps these fills and uses `rgb(33,33,33)`; the independent re-measurement and children 01/08 agree on the values above for the light theme. Treat 07-auth's colors as a dark/experiment variant (see section 17, C-01).
- Mobile header (`<= 800 px`): full-width 61 px bar; hamburger left (x=16, 44 x 44, radius 50 %, bg `rgb(48,48,48)`, border 1 px `rgba(255,255,255,0.15)`, shadow `rgba(0,0,0,0.05) 0 4px 16px`, aria-label "navigation-open action", aria-expanded toggles); wordmark centered (SVG 75 x 13 at x~157.5); only "sign-in action" (69.7 x 44, radius 999, 14 px) on the right. "registration action" is not rendered on mobile.

## 5. Composer

Container form (`action="/unauth-mweb/conversation"`, method POST), `#mobile-composer-prompt` textarea inside.

Geometry:
- Desktop: form x=466, y=420, w=768, h=52, radius 28 px, bg white, border 1 px `rgba(0,0,0,0.1)`, shadow stack `0 0 0 1px rgba(0,0,0,0.04), 0 2px 8px rgba(0,0,0,0.04), 0 4px 80px 8px rgba(0,0,0,0.024)`, padding 7 px 10 px. Textarea x=523, y=433.5, w=618, h=24, font 17 px / line-height 24 px, color `rgb(13,13,13)`, max-height 192 px, min-height 24 px, overflow-y auto, resize none, placeholder "message entry placeholder", aria-label "message entry field".
- Tablet (834): form 272,526,550,52, padding 7 10; textarea 329,539.5,400,24, font 17/24.
- Mobile (390): form 12,412,366,48, padding 7 8; textarea 65,423.5,224,24, font 16/24, min-height 20 px (desktop min-height is 24 px).

Buttons (all 44 x 44, radius 999 px):
- "attachment-menu action" (left): x=473, y=424 (desktop), transparent bg, dark icon `rgb(13,13,13)`. Opens a 220 px-wide white popover (radius 16 px, padding 6 px 0, shadow `0 8px 12px rgba(0,0,0,0.08)`) anchored below the button.
- "voice-input action" (right): x=1141, y=424, transparent bg, dark icon.
- "send action" (right): x=1185, y=424, transparent bg, `::before` 32 x 32 blue circle `rgb(53,102,240)` radius 16 px arrow.
  - Empty state: aria-disabled="true", opacity 0.5, cursor not-allowed.
  - Typed state: aria-disabled null, opacity 1, cursor pointer.
  - Button stays in DOM in both states (display flex).

Attachment menu items (each 208 x 44, radius 10 px, padding 6 px 10 px, 20 x 20 icon, 6 px gap, grid layout):
- image-attachment action; search-mode toggle (toggle, aria-pressed, blue `rgb(53,102,240)` text when active); divider; "authentication-required upsell" upsell row (36 px); image-generation option; research option; file attachment option (gray `rgb(143,143,143)`, aria-label "… sign-in action to use.", cursor pointer).
- Hidden file inputs (Camera/Photos/Files, 0 x 0) exist in DOM. "expanded-input mode" and "voice-input action" are separate form controls, not menu items.
- Menu item hover: bg `rgb(48,48,48)` with white text (observed on "image-attachment action").
- Escape closes the menu; arrow keys do not navigate items (focus leaves the menu).

Auto-grow: 1 line = 24 px, 2 lines = 64 px, 3 lines = 88 px, caps at 192 px (overflow scrolls; scrollHeight 304 for long text).

Keyboard:
- Enter submits and navigates to `/uc/<uuid>`.
- Shift+Enter inserts a newline (textarea grows, no submit).
- Tab order: textarea -> attachment-menu action -> voice-input action -> send action -> footer links (legal terms link, privacy link, information link) -> header controls.
- `/` (body focused) focuses the composer textarea.
- No other shortcuts observed (Ctrl/Cmd+Shift+O had no effect logged out; `?` opened nothing).

## 6. Messages and conversation

Logged-out conversations render at `/uc/<uuid>` as a "lightweight conversation": "user-role label" / "assistant-role label" blocks inside a centered 768 px column. There are NO `[data-message-author-role]` nodes on this surface.

- Main area: x=260, y=61, w=1180, h~1077 (grows). Conversation column: x=466, y=77, w=768 (margins 190 px each side).
- Body type: 16 px / 24 px / 400, family `platform system sans-serif stack`, color `rgb(13,13,13)` on light.
- Footer disclaimer: 12 px / 16 px, color `rgb(93,93,93)`.
- Message container: 768 x 871 (static, observed).

Markdown observed across canned variants (templates rotate; ~6 variants):
- Observed elements: `p`, `ul`, `ol`, `li`, `table`, inline `code`, `a`.
- NEVER observed in any variant: `h1/h2/h3`, `strong`, `em`, `b`, `i`, fenced `pre` (code blocks). Their styling is therefore UNKNOWN from this lane (do not fabricate). The SVG sprite includes glyphs for copy/edit/pencil/share/spinner/stop, implying richer signed-in controls, but they did not render in the captured DOM.

Counts in the richest captured variant: p=16, ul=1, ol=1, li=6, table=1, code=1 (inline), a=1.

Copy/regenerate/edit controls:
- No copy/regenerate/edit/share buttons rendered in the captured shallow DOM for completed conversations. Sprite glyphs exist (`lightweight-conversation-copy`, `-edit`, `-pencil`, `-share`, `-copy-compact`, `-check`, `-prompt-copy`, etc.), so they are expected to be hover-revealed icon buttons (~24-32 px, inference only).
- Visible conversation action buttons in the captured state: "Copy" (44 x 44, radius 10), "copy-response action" (44 x 44, radius 8), "Share" (44 x 44, radius 999), "scroll-to-latest action" (44 x 44, radius 999). No "Regenerate" button was found in any variant.
- Signed-in affordances (model picker, chat history, message editing UI) are out of scope (logged-out only).

## 7. Streaming and caret

- Streaming is NOT token-incremental in the DOM. Across 52 samples at ~140 ms and 220 at ~20 ms, assistant text length changed exactly once (placeholder 13 chars -> final 72-162 chars). MutationObserver recorded 3 commits: assistant absent -> placeholder -> final.
- First non-zero content text appears at ~1.96-2.43 s after submit; generation latency ~2.4 s.
- Caret indicator: a 13.8 x 13.8 px circular dot, `data-streaming-dot` / `data-streaming-placeholder-dot`, bg `rgb(53,102,240)`, radius 999 px, position absolute, animated `x1uvjygu-B` 0.55 s ease-in infinite alternate-reverse. No vertical-bar caret and no `::after`/`::before` pseudo content on the last text parent. No class contains "caret"/"streaming"/"blinking".
- Stop button: 44 x 44 px at (1185,424), aria-label "stop action", visible during generation, removed on completion. Clicking mid-stream yields variable partial/full results.
- Scroll: window scrollY stayed 0 in all 52 samples; document scrollHeight 1138 px vs viewport 900 px -> 238 px overflow below the fold. Composer stayed visible without scrolling (fixed footer, top 812 px, z-index 10). `main` is `position: sticky`; header is `position: relative` (not fixed) but did not move because the page did not scroll.
- Layout boxes were stable across all samples (no CLS-like jumps): header 260,0 1180x61 (relative); sidebar 0,0 260x900 (absolute); composer 466,824 768x52 (fixed footer); message container 466,77 768x871 (static).

## 8. Empty state

Desktop (1440 x 900):
- Main pad 20 16 180; content column x=466, w=768; footer container x=260, w=1180, h=204, pad 12 12 24, display grid.
- h1 "welcome heading" at x=718.5, y=360, w=262.9, h=28, font 24 px / 28 px / 400, color `rgb(13,13,13)`, centered in the 768 px column.
- Suggested prompt pill "suggested prompt" at x=778.5, y=484, w=142.9, h=44, font 14 px / 20 px / 600, color `rgb(13,13,13)`, bg white, radius 999 px, padding 0 14.
- "message entry field" is the composer placeholder, not a separate button.
- Empty submit is blocked: send button stays visible (44 x 44, radius 999) but aria-disabled="true", opacity 0.5, cursor not-allowed. No validation tooltip or error text.
- Login upsell is the sidebar footer block (x=20, y=724 relative to sidebar: h2 "account-benefits heading" 17 px / 700, "sign-in action" pill 220 x 44 bg `rgb(243,243,243)`), NOT a banner above the composer. README phrasing was misleading; observation wins (C-08).

Mobile (390 x 844):
- h1 "alternate mobile welcome heading" at x=58.7, y=360, w=272.6, h=28, font 24 px / 28 px.
- "suggested prompt" pill at x=123.5, y=472, w=142.9, h=44.
- Main pad 20 16 132; composer 12,412,366,48.

Loading state:
- No spinner, skeleton, progress bar, or aria-busy observed at any sampled point (0 across 80 samples at 50 ms). Transition is a brief blank: 0 text / 20 nodes from t~51-102 ms; first text at ~181 ms; URL change at ~2044 ms; full response (~416 chars) at ~3308 ms. Time from Enter to first visible text ~181 ms.
- Composer and shell remain mounted while content swaps.

Error and retry:
- Rate limit: 6 rapid submits produced no "too many requests", captcha, or error UI. Threshold above 6 is UNKNOWN.
- Network failure: not feasible; UNKNOWN.
- Empty submit: blocked by disabled send button (no tooltip/error text).
- Latent retry UI inside the composer form: `<div data-composer-submission-status hidden>` containing `<span role="status" aria-live="polite">Chat stopped unexpectedly</span>` plus `<button data-composer-submission-retry hidden>Retry</button>`. Container: font 12 px, color `rgb(93,93,93)`, padding 0 6 px, gap 8 px, flex row, baseline align. Retry button: font 12 px / 600, color `rgb(13,13,13)`, radius 4 px, bg transparent, cursor pointer. `hidden` in all normal runs; not visibly triggered. No "Regenerate" button found (only Copy / copy-response action / Share / scroll-to-latest action).

Conversation state (desktop): main pad 16 16 124, position sticky; composer form x=466, y=824, w=768, h=52, position relative (in-flow), padding 7 8; textarea x=521, y=837.5, w=622, h=24; "scroll-to-latest action" button 44 x 44 at x=828, y=776 radius 999.

Logged-out limit: 8 consecutive exchanges produced no login modal or rate-limit text; only the persistent sidebar upsell. Threshold beyond 8 is UNKNOWN.

## 9. Scroll behavior

- Composer is held by a `position: fixed` footer (top 812 px, z-index 10) on the conversation route per child 4; child 5 measured the form as `position: relative` in-flow. Geometry agrees (466,824,768,52); the CSS mechanism is unresolved (C-02). Spec should describe observed behavior (composer stays visible at viewport bottom) without committing to a mechanism.
- `main` is `position: sticky`; header is `position: relative` (not fixed/sticky) but did not move because the page did not scroll for short responses.
- Auto-scroll for short responses: none observed (scrollY 0). Whether auto-scroll triggers for tall responses is UNKNOWN (C-09).
- Horizontal overflow: 200 px on desktop (grid `200px 1440px 0px`), 404 px on mobile (off-canvas drawer). `html` overflow-x auto, scrollbars hidden.

## 10. Mobile drawer

- Trigger: hamburger 44 x 44, radius 50 %, bg `rgb(48,48,48)`, border 1 px `rgba(255,255,255,0.15)`, shadow `rgba(0,0,0,0.05) 0 4px 16px`, aria-label "navigation-open action", aria-expanded toggles. Icon 24 x 24 SVG.
- Drawer: width 310 px (at 390) / 330 px (at 768), height = viewport, bg `rgb(33,33,33)` (dark in both themes), border-right 1 px `rgba(255,255,255,0.15)`, z-index 31. Scrim: full viewport, black, opacity 0.35 when open, z-index 30, aria-label "navigation-close action", aria-hidden true, tabindex -1.
- Animation is scroll-driven, not a CSS transition: transitionDuration 0 s, animation-timeline `scroll(root inline)`, animation-name `x11jiux3-B` (transform) + `xmorylh-B` (scrim opacity). Root scrollLeft animates from drawer width to 0. Open ~284 ms, close ~119 ms (measured). Scrim opacity 0 -> 0.35 in lockstep.
- On open, focus moves to the `aside` (aria-label "Sidebar"). Focus trap: Tab cycles through 12 items (reference logo, conversation search, image feature, integration feature, plans link, help link, research option, sign-in action x2, new-session action, settings action) then wraps. Escape closes and returns focus state to closed.
- Scrim close: direct `.click()` on the scrim element closes the drawer; Playwright's center click is obstructed by the sidebar (z-index 31 over 30). Whether a real pointer click on the visible scrim region (x > 310) closes it was not tested.
- "registration action" is not rendered on mobile.

## 11. Accessibility

Landmarks (desktop empty state): header (no role) 260,0,1180,61; nav "navigation items" 0,0,259,900; aside "Sidebar" complementary 0,0,260,900; nav "conversation history" 0,0,0,0 (hidden when logged out); main 260,61,1180,839; footer 260,348,1180,204; aside "authentication modal heading" 0,0,0,0; form (composer) 466,420,768,52; nav "attachment actions" 0,0,0,0; dialogs 0,0,0,0. No skip links on either viewport.

Focus order (desktop, 23 elements before wrap): textarea -> attachment-menu action -> voice-input action -> send action -> legal terms link -> privacy link -> information link -> body -> header sign-in action -> registration action -> reference wordmark -> new-session action -> image feature -> integration feature -> plans link -> help link -> research option -> sign-in action (upsell) -> registration action (upsell) -> settings action -> navigation-close action -> sign-in action (sidebar footer) -> suggested prompt -> wraps to textarea. Mobile: 11 elements (textarea, attachment action, voice-input action, send action, legal terms link, privacy link, information link, body, navigation-open action, sign-in action, suggested prompt).

Keyboard: Enter submits; Shift+Enter newline; Escape closes modal; `/` focuses composer; `?` no effect; Ctrl/Cmd+Shift+O no effect logged out; Tab moves through the order above.

Labels: textarea aria-label "message entry field" + placeholder "message entry placeholder". All icon-only buttons carry aria-label (attachment-menu action, voice-input action, send action with aria-disabled when empty, Close/navigation-open action, research option, voice action on mobile). Sidebar links carry aria-label even with text. aria-expanded on navigation-open action (true), reference logo (false, aria-haspopup=dialog), research option (false), expanded-input mode (false). aria-pressed on search-mode toggle toggle (false). aria-disabled on send action (true when empty). aria-haspopup="dialog" on reference logo span and research option.

Contrast (WCAG AA, light theme):
- Body text `#0d0d0d` on page bg `#fcfcfc`: 18.94 (pass).
- Composer text `#0d0d0d` on white: 19.44 (pass).
- Header "sign-in action" label white on `#0d0d0d`: 19.44 (pass).
- "registration action" label `#0d0d0d` on white: 19.44 (pass).
- Link blue `#3566f0` on white: 4.87 (pass normal).
- Muted `#8f8f8f` on white: 3.23 (fail normal, pass large).
- Muted `#5d5d5d` on page bg: 6.42 (pass).
- Error red `#ba2623` on white: 6.20 (pass).
- Focus ring `#3566f0` on white: 4.87 (pass).

Other:
- `html lang="en-US"`; title "page title omitted".
- 13 CSS rules contain `prefers-reduced-motion`; `matchMedia('(prefers-reduced-motion: reduce)')` false in the measurement env (no-preference).
- Dialogs (`#mobile-auth-dialog`, `#mobile-new-chat-dialog`, product-card popovers) have role=dialog and aria-labelledby but NO `aria-modal` attribute.
- Live regions: 8 on empty state, 11 on conversation page (role=status spans aria-live polite/assertive, some atomic=true; role=alert for auth errors). Conversation is `<ol aria-label="Conversation">` with no role=log or aria-live; turns are `<li>` with h4 labels. New messages are not auto-announced.
- Heading hierarchy: h1 (empty state) + h2s (including hidden variants "account-benefits heading", "session-clear confirmation heading", "authentication modal heading", "returning-user heading"); conversation turns are h4 "user-role label" / "assistant-role label" (skips h3). No h3/h5/h6 observed.
- Focus-visible: most controls show a 2 px solid `#3566f0` outline; footer/legal links show browser-default 1 px `auto`; several controls show `none` (composer textarea, Add files/dictation/Send, voice action/voice-input action, body) — potential a11y issue.

## 12. Login/signup modal

Triggered by header "sign-in action" / "registration action" (both `command="show-modal" commandfor="mobile-auth-dialog"`). Single `<dialog id="mobile-auth-dialog">` (role=dialog, aria-labelledby="mobile-auth-title", aria-describedby="mobile-auth-description", closedby="any"). No tab/tablist controls; entry action carried by the trigger. Title/description remain "authentication modal heading" in both variants.

Desktop (light variant, ~3/4 of runs; geometry identical in dark variant):
- Modal: x=490, y=198, w=448, h=504, radius 20 px, bg white, color `rgb(13,13,13)`, shadow `rgba(0,0,0,0.22) 0 24px 64px, rgba(0,0,0,0.12) 0 4px 16px`. Overlay backdrop `rgba(0,0,0,0.5)`, no blur. maxWidth `min(100% - 32px, 448px)`, maxHeight 672 px. Entrance transition `transform 0.3s cubic-bezier(0.2,0,0,1)`.
- Title "authentication modal heading": 24 px / 32 px / 400. Description: 15 px / 22 px.
- SSO buttons: SSO provider A (x=531.5, y=346, w=365, h=48), SSO provider B (x=531.5, y=406, w=365, h=48), "phone authentication action" (x=531.5, y=466, w=365, h=48). All radius 999 px, bg white, border 1 px `rgba(0,0,0,0.2)`, text 16 px / 500 black. SSO provider C SSO link present in DOM (href connection=windowslive) but `display:none` in the login variant; visible in the signup variant.
- Email form: label "email-field label"; input `#mobile-auth-email` type=email, autocomplete=email, inputmode=email, placeholder "email-field label", bg `rgb(252,252,252)`, border 1 px `rgba(0,0,0,0.1)`, radius 999 px, w=365, h=48 at x=531.5, y=566. Error container `#mobile-auth-email-error` role=alert, text "invalid-email alert", color `rgb(186,38,35)`, 12 px / 16 px, hidden by default. primary auth action button (submit) w=365, h=48, radius 999, bg `rgb(13,13,13)`, white text 16 px / 500.
- Phone form hidden until "phone authentication action" is clicked; then `#mobile-auth-phone` (type=tel) at x~576, y=596, w~319, h=46 inside a country selector container (border 1 px `rgba(0,0,0,0.1)`, padding 0 36 0 44, gap 8) showing "default country and dial code" and "+1". Phone error container `#mobile-auth-phone-error` role=alert, color `rgb(186,38,35)`, 12 px, empty/hidden by default.
- Validation: empty blur does NOT set aria-invalid, does not change border color, does not reveal the error message. primary auth action button is NOT disabled when empty. Error text is pre-rendered with role=alert. Focus ring: outline `rgb(53,102,240) solid 2px`, offset 2 px, border unchanged.
- Close button (aria-label "Close", type=submit) 44 x 44 at top-right (x=882, y=210, absolute top 12 right 12), radius 999, transparent bg. A separate aria-label "Dismiss" button exists at 0 x 0 (visually hidden).
- Focus: on open, focus moves to the Close button (autofocus). Escape closes and returns focus to the triggering "sign-in action" button.

Mobile (390 x 844): modal opens as a bottom sheet. Container `#mobile-auth-dialog` transparent, fixed, x=0, y=70, w=390, h=774. Visible white sheet starts at y~379, w=390, bg white, radius 0 (full width), position relative. SSO buttons SSO provider A/SSO provider B at x=40, w=310, h=44, radius 999 (SSO provider A y=526, SSO provider B y=580); SSO provider C hidden. Email input at x=40, y~726, w=310, h=44, bg `rgb(252,252,252)`, border 1 px `rgba(0,0,0,0.1)`, radius 999. Close button 44 x 44 at x=338, y=383, radius 999, transparent.

No Cloudflare/captcha/rate-limit interstitial was observed during these runs.

## 13. Color and typography tokens (light theme)

Colors:
- Page bg `rgb(252,252,252)` (#fcfcfc); sidebar bg same; composer bg white `#ffffff`.
- Primary text `rgb(13,13,13)` (#0d0d0d); muted `#8f8f8f` (small text fails AA) and `#5d5d5d` (passes).
- Link/accent `#3566f0` (rgb(53,102,240)); used for focus rings, active toggles, send dot, streaming dot.
- Error red `#ba2623` (rgb(186,38,35)).
- Disabled/greyed affordances `#8f8f8f` (#8f8f8f).
- Dark filled button bg `#0d0d0d` with white text; upsell pill bg `#f3f3f3`.
- Nav item hover `#e8e8e8`; active `#cdcdcd`; wordmark hover `rgba(236,236,236,0.96)`.
- Sidebar drawer (mobile) bg `#212121` (dark) with border `rgba(255,255,255,0.15)`.
- Modal overlay `rgba(0,0,0,0.5)`; modal shadow stack.
- Border default `rgba(0,0,0,0.1)` (1 px); SSO border `rgba(0,0,0,0.2)`.

Typography (system stack, no web fonts observed):
- Body: 16 px / 24 px / 400.
- Composer textarea: 17 px / 24 px (desktop/tablet), 16 px / 24 px (mobile).
- Empty-state heading: 24 px / 28 px / 400.
- Upsell heading: 17 px / 24 px / 700.
- Button labels: 14 px / 20 px / 600.
- Sidebar rows: 14 px / 400.
- Wordmark: 16 px / 650.
- Footer legal/disclaimer: 12 px / 16 px.
- Modal title: 24 px / 32 px / 400; description 15 px / 22 px; SSO/email labels 16 px / 500.
- Error text: 12 px / 16 px.

Spacing scale observed: 4, 6, 8, 10, 12, 14, 16, 20, 24, 36, 44, 48, 52, 61, 768, 1180. Radius scale: 0, 4, 8, 10, 16, 20, 28, 999 (pill).

## 14. Unknowns and gaps (from child 9, G-01..G-21)

Not observed in this lane; must not be assumed in implementation:
- Signed-in surface (`#prompt-textarea`, `[data-message-author-role]`, history sidebar, regenerate/edit, model switcher) — out of scope (logged-out only).
- Streaming caret CSS keyframes (`x1uvjygu-B`) and whether a vertical-bar caret exists.
- Scroll-follow for responses tall enough to push the composer below the fold.
- Loading indicator geometry (none observed; may be sub-100 ms).
- Rate-limit threshold and error UI (none at <=6 rapid submits).
- Logged-out login-wall threshold (none within 8 exchanges).
- Tooltip text content (`data-oai-tooltip` empty in DOM).
- Tablet/mobile attachment-menu layout.
- Code-block copy button, syntax highlighting, language label, line numbers (fenced `pre` never observed).
- Message edit/regenerate controls (no regenerate button found).
- Dedicated dark mode (only non-deterministic theme observed).
- High-DPI (DSF > 1), `prefers-reduced-motion` behavior, RTL, print, touch gestures, high contrast, font loading, empty-state heading variant cause, mobile conversation/retry UI, empty-submit mechanism, mobile conversation action buttons.

Substantive unresolved conflicts to resolve before normative use:
- C-01: header auth button fill colors (07-auth swaps login/signup and uses `rgb(33,33,33)`; 01/08/independent say login dark-filled, signup white). Re-verify per theme.
- C-02: composer CSS positioning model in conversation (fixed footer vs in-flow relative). Geometry agrees; mechanism unresolved.
- C-09: auto-scroll trigger condition for tall responses.

## 15. Evidence index

Seventeen layout-only screenshots were visually checked and committed after audit.

Desktop: a11y-desktop-1440.png, composer-empty-1440.png, composer-menu-1440.png, composer-typed-1440.png, control_inventory-1440.png, conversation-state-1440.png, empty-1440.png, loading-state-1440.png, retry-state-1440.png, rich-conversation-1440.png.
Tablet: tablet-834.png.
Mobile: a11y-mobile-390.png, conversation-state-390.png, empty-390.png, mobile-conversation-390.png, mobile-drawer-open-390.png, mobile-empty-390.png.

The raw REA DOM/accessibility inspection is retained only in ignored work at work/finals/w2-discovery/observations/00-explore/rea/inspect_web_page_1440.json. It is not repository evidence because it contains public page text. Ten captures with visible reference branding or copy are retained only in work/finals/w2-discovery/review-quarantine/ and are excluded from this evidence set.

Raw measurements and all nine findings are under ignored work/finals/w2-discovery/observations/.
---

Every value above is a layout, spacing, motion or interaction observation. The tracked screenshots were visually checked as layout-only artifacts; text-bearing captures remain quarantined under ignored work.
