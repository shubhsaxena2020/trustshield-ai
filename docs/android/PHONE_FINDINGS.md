# Phone findings (head, 2026-10-28 15:48, real Redmi Note 5 Pro, light system theme, WIP snapshot of P9 = 2999922 + uncommitted MainActivity/ResultScreen + Modifier import)
Onboarding screen is nearly unreadable in the WHITE theme:
- App name 'TrustShield AI' in the top bar is white on near-white (invisible).
- Primary button ('Next/Continue') is a white pill with white label (label invisible); 'Skip' outlined button label is white (invisible) and the button overlaps the system navigation bar.
- Body text 'Paste a message or link...' is very light grey (fails WCAG AA).
- Status bar icons/time render white on a light background.
Fix: use theme foreground colours (onBackground / onSurface) everywhere in onboarding and the app bar, test BOTH themes with a light and a dark SYSTEM setting, edge-to-edge insets so buttons clear the nav bar, status-bar icon appearance must follow the theme. Check the startup (splash) icon: the logo is cropped inside a circle.
