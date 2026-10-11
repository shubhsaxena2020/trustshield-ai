# UI/UX and Accessibility Review: Auth, Navigation, and Scan History

**Review date:** 2026-10-11  
**Target branch:** `feature/auth-supabase-history-uiux`  
**Reviewer:** Antigravity (Advanced Agentic UI/UX & Accessibility Review)  
**Scope:**  
- `apps/web/app/login/page.tsx`
- `apps/web/app/signup/page.tsx`
- `apps/web/app/history/page.tsx`
- `apps/web/components/NavBar.tsx`
- `apps/web/components/ResultDashboard.tsx` (coverage copy fix)
- Comparative consistency against `apps/web/app/analyzer/page.tsx`

---

## Executive Summary

The new authentication, navigation, and history features introduce essential user capabilities to TrustShield AI. The Supabase integration, session propagation, and history viewing logic are functionally working, and the copy fix in `ResultDashboard.tsx` improves judge comprehension by distinguishing unconfigured modules from failed ones.

However, from an accessibility (WCAG 2.1 AA), keyboard navigation, mobile layout, and judge-facing UX standpoint, the newly added pages exhibit noticeable gaps when placed alongside the polished `apps/web/app/analyzer/page.tsx` surface. In particular:
1. **Forms lack accessible labels and explicit `<main>` / `<h1>` hierarchy**: Inputs rely solely on disappearing placeholders; card titles render as unsemantic `<div>` elements.
2. **Destructive actions lack confirmation guards**: "Clear all" permanently erases the user's entire history in a single click with no confirmation or undo.
3. **Mobile navbar overflow**: For authenticated users, the top navigation overflows narrow mobile viewports (e.g. 360px–375px) because long emails and buttons are unconstrained and lack mobile menu folding.
4. **Verifiable color contrast failure**: The shadcn `destructive` button variant in light mode yields a contrast ratio of **4.01:1** on small text (`text-[0.8rem]`), failing the WCAG AA minimum of **4.5:1**.
5. **Brand and visual divergence**: The analyzer page uses a custom TrustShield blue colorway and distinct form styling, while the new auth/history pages default to monochromatic shadcn near-black buttons and unskinned tables.

Below is the structured breakdown of findings followed by verified positive implementations.

---

## Findings

### 1. Missing Accessible Form Labels and Autocomplete Attributes on Auth Inputs

**Severity:** high  
**File:** `apps/web/app/login/page.tsx:42-45`, `apps/web/app/signup/page.tsx:47-50`  

**Assessment:**  
The email and password inputs in both `LoginPage` and `SignupPage` rely exclusively on the `placeholder` attribute (`placeholder="Email"`, `placeholder="Password"`, `placeholder="Password (min 6 characters)"`) without corresponding `<label>` elements, `id` / `htmlFor` pairings, or `aria-label` / `aria-labelledby` attributes. In addition, neither form specifies `autoComplete` attributes.

**Concrete scenario & standards impact:**  
- **WCAG 2.1 SC 3.3.2 (Labels or Instructions) & SC 1.3.1 (Info and Relationships):** Placeholders are not accessible labels. When a user begins typing into the input, the placeholder disappears. Users with cognitive disabilities, attention impairments, or low vision utilizing screen magnifiers lose visual context of what field they are editing.
- **Lost constraint cues:** In `signup/page.tsx`, the password requirement constraint (`"min 6 characters"`) is placed only inside the placeholder string. Once the user types their first character, that requirement completely vanishes from the screen.
- **Autofill & Password Managers (WCAG 2.1 SC 1.3.5):** Without `autoComplete="email"`, `autoComplete="current-password"` (login), and `autoComplete="new-password"` (signup), password managers (1Password, Bitwarden, iCloud Keychain, Chrome Autofill) cannot reliably autofill credentials or suggest secure passwords.

**Suggested fix:**  
Add semantic `<Label>` components (or standard `<label htmlFor="...">`) paired with unique `id` attributes for every input. Move constraints out of placeholders into persistent helper text, and declare appropriate `autoComplete` attributes:
```tsx
<div>
  <Label htmlFor="login-email" className="text-sm font-medium">Email address</Label>
  <Input
    id="login-email"
    type="email"
    autoComplete="email"
    value={email}
    onChange={(e) => setEmail(e.target.value)}
    required
  />
</div>
```

---

### 2. Destructive Actions ("Clear all" and "Delete") Lack Confirmation Dialogs

**Severity:** high  
**File:** `apps/web/app/history/page.tsx:59-61`, `apps/web/app/history/page.tsx:89-92`  

**Assessment:**  
In `HistoryPage`, clicking the "Clear all" button immediately sends a `DELETE` request to `/api/v1/history` and clears the state. Similarly, clicking "Delete" on any row deletes that scan record instantly. Neither action prompts the user for confirmation (no modal, browser `window.confirm()`, or popover).

**Concrete scenario & standards impact:**  
- **Accidental data loss:** "Clear all" is positioned right next to the main page heading `<h1>Your scan history</h1>`. A judge or user clicking or tapping on mobile near the header can easily trigger "Clear all" accidentally, destroying their entire scan history irreversibly.
- **Irreversible state change:** In security and fraud inspection tools, scan records represent audit trails of suspicious emails, QR codes, or URLs. Deleting history should always require explicit intent confirmation.

**Suggested fix:**  
Guard destructive operations behind an accessible confirmation modal (using shadcn's `AlertDialog`) or at minimum a browser confirmation prompt before executing the `DELETE` request:
```tsx
async function handleClearAll() {
  if (!window.confirm("Are you sure you want to delete all saved scans? This action cannot be undone.")) return;
  await clearAll();
}
```

---

### 3. Mobile Viewport Overflow and Navbar Clipping for Authenticated Users

**Severity:** high  
**File:** `apps/web/components/NavBar.tsx:12-34`  

**Assessment:**  
The top navigation container uses `className="flex items-center justify-between border-b px-6 py-3"`. When a user is logged in, the right container renders `Analyzer`, `History`, the user's raw email `<span className="text-slate-500">{session.user.email}</span>`, and the "Log out" button with `gap-3` / `gap-4`. The email text has no `truncate` class, no `max-w-*` constraint, and no responsive visibility rule (such as `hidden md:inline`).

**Concrete scenario & standards impact:**  
- Tested on standard mobile viewports (360px–375px width, such as iPhone SE or standard Android devices):
  - Fixed elements: Logo (`140px`) + Nav padding (`48px`) + Links (`~110px`) + Logout button (`~65px`) = `363px` baseline.
  - When an authenticated user's email is rendered (e.g., `judge.demo@hackathon.org` or longer corporate emails), the navigation width expands to `389px–500px+`.
  - Result: The navigation bar breaks viewport bounds, introducing horizontal page scrolling across the entire site on mobile devices, wrapping the email into multiple vertical lines, and pushing the "Log out" button off-screen to the right.

**Suggested fix:**  
1. Add responsive truncation or hide the full email on small viewports:
   ```tsx
   <span className="hidden sm:inline max-w-[160px] truncate text-slate-500">
     {session.user.email}
   </span>
   ```
2. Or implement a responsive hamburger/sheet menu pattern on viewports below `md`.

---

### 4. Color Contrast Failure on Destructive Button in Light Mode (WCAG 1.4.3)

**Severity:** medium  
**File:** `apps/web/components/ui/button.tsx:17-18`, `apps/web/app/history/page.tsx:91`  

**Assessment:**  
The "Delete" action button in the history table uses `Button variant="destructive" size="sm"`.  
Under `buttonVariants`, `variant: "destructive"` is styled as:  
`bg-destructive/10 text-destructive hover:bg-destructive/20`.  
In light mode (`globals.css:70`), `--destructive` is `oklch(0.577 0.245 27.325)`.  
- Rendered text color: `lab(48.45 77.43 61.55)` (bright red `#e7000b`)
- Rendered background: `oklab(0.577 ... / 0.1)` on white `#ffffff` = `rgb(253, 230, 231)` (pale pink)
- **Measured contrast ratio:** **4.01:1**.

**Concrete scenario & standards impact:**  
- **WCAG 2.1 SC 1.4.3 (Contrast Minimum):** Regular text (under 18pt / 24px, or under 14pt / 18.66px bold) requires a minimum contrast ratio of **4.5:1** against its background.  
- The small button font size is `text-[0.8rem]` (12.8px normal weight). At 4.01:1, this fails WCAG 2.1 AA. Users with moderate visual impairments or viewing in high ambient lighting will struggle to read the "Delete" label.

**Suggested fix:**  
Darken the foreground destructive color or use a solid background with white text for destructive actions in light mode:
```tsx
destructive: "bg-red-600 text-white hover:bg-red-700 ..."
```
Or darken `text-destructive` in light mode so that it achieves at least 4.5:1 (e.g., `#b91c1c` / `text-red-700` yields 4.9:1+ on pink).

---

### 5. Unconstrained Table Layout and Truncation Deficits on Mobile Viewports

**Severity:** medium  
**File:** `apps/web/app/history/page.tsx:72-96`, `apps/web/components/ui/table.tsx:80-90`  

**Assessment:**  
In `apps/web/components/ui/table.tsx`, `TableCell` is styled with `whitespace-nowrap`. In `HistoryPage`, the 5-column table renders the full date (`toLocaleString()`), input type, input summary (`row.input_summary`), risk label/score, and action buttons.

**Concrete scenario & standards impact:**  
- Scanned phishing URLs or email snippets routinely exceed 80–120 characters (e.g., `https://secure-login-verify-account.bank-security.top/auth/login?session=...`).
- Because `whitespace-nowrap` is applied to `TableCell` with no column width restrictions or truncation, the table row stretches to `900px–1200px+`.
- While `<Table>` is wrapped in `overflow-x-auto`, on mobile devices (360px–400px width), the "View" and "Delete" action buttons are pushed hundreds of pixels off-screen to the right. There is no visual scroll affordance, leaving mobile users unaware that action buttons exist unless they swipe blindly.

**Suggested fix:**  
1. Apply `max-w-[220px] truncate` to the Summary table cell.
2. Format dates more concisely on mobile (e.g. date only or short format).
3. Alternatively, render a mobile card list (`block sm:hidden`) alongside the desktop table (`hidden sm:table`).

---

### 6. Brand Identity and Visual Hierarchy Inconsistency with Analyzer Page

**Severity:** medium  
**File:** `apps/web/app/login/page.tsx:47-49`, `apps/web/app/signup/page.tsx:52-54`, vs `apps/web/app/analyzer/page.tsx:310-316`  

**Assessment:**  
The existing `apps/web/app/analyzer/page.tsx` features a distinct product branding and visual language:
- Primary button CTA: `bg-blue-700 hover:bg-blue-800 text-white font-medium focus:ring-blue-500` ("TrustShield Blue").
- Sub-brand header: `TRUSTSHIELD AI · TEAM SCAM SCAN` with tagline `Think Before You Trust.`
- Links: `text-blue-700 underline font-medium`.
- Visual cards: `border-slate-300 rounded-lg`.

In contrast, `login/page.tsx` and `signup/page.tsx` use the uncustomized shadcn default:
- Primary button CTA: Monochromatic near-black `bg-primary` (`oklch(0.205 0 0)`).
- Card Title: plain black font with zero brand accent or logo.
- Links: plain uncolored underline `text-slate-600`.
- The navbar links ("Log in", "Sign up") are unstyled text strings rather than standard button/pill CTAs.

**Concrete scenario & standards impact:**  
A hackathon judge navigating between `/analyzer` and `/login` will immediately notice that the authentication flow feels like an unstyled boilerplate template disconnected from TrustShield AI’s brand identity.

**Suggested fix:**  
Align the button styling on auth pages with the analyzer's primary colorway:
```tsx
<Button type="submit" disabled={busy} className="w-full bg-blue-700 hover:bg-blue-800 text-white">
  {busy ? "Logging in…" : "Log in"}
</Button>
```
Include a small TrustShield AI header or shield badge above the card title.

---

### 7. Missing Landmark (`<main>`) and Semantic Heading (`<h1>`) Hierarchy on Auth Pages

**Severity:** medium  
**File:** `apps/web/app/login/page.tsx:35-39`, `apps/web/app/signup/page.tsx:34-38`, `apps/web/components/ui/card.tsx:35-46`  

**Assessment:**  
1. `apps/web/app/layout.tsx` wraps pages directly in `body`: `<body className="min-h-full flex flex-col"><NavBar />{children}</body>`.
2. While `analyzer/page.tsx` wraps its content in a top-level `<main className="...">`, `login/page.tsx` and `signup/page.tsx` use an unsemantic `<div className="mx-auto max-w-sm py-16 px-4">`.
3. In `apps/web/components/ui/card.tsx:35-46`, `<CardTitle>` renders a `<div>`:
   `function CardTitle(...) { return <div data-slot="card-title" ... /> }`
   As a result, neither the login page nor the signup page contains an `<h1>` (or any heading element at all).

**Concrete scenario & standards impact:**  
- **WCAG 2.1 SC 1.3.1 (Info and Relationships) & SC 2.4.1 (Bypass Blocks):** Screen reader users relying on landmarks (`role="main"` or `<main>`) have no landmark to jump directly to the login/signup card.
- **WCAG 2.1 SC 2.4.6 (Headings and Labels):** When navigating via heading shortcuts (`H` in NVDA/VoiceOver/JAWS), screen readers announce "No headings found" on `/login` and `/signup`.

**Suggested fix:**  
Wrap page content in `<main>` and render an explicit `<h1>` inside `CardHeader`:
```tsx
<main className="mx-auto max-w-sm py-16 px-4">
  <Card>
    <CardHeader>
      <CardTitle asChild>
        <h1 className="text-xl font-semibold">Log in</h1>
      </CardTitle>
    </CardHeader>
...
```

---

### 8. Broken Empty State Rendering in History Page

**Severity:** medium  
**File:** `apps/web/app/history/page.tsx:72-98`  

**Assessment:**  
In `HistoryPage`, lines 72–98 contain:
```tsx
{selected ? (
  ...
) : (
  <Table>
    <TableHeader>
      <TableRow>
        <TableHead>Date</TableHead>
        <TableHead>Type</TableHead>
        <TableHead>Summary</TableHead>
        <TableHead>Risk</TableHead>
        <TableHead />
      </TableRow>
    </TableHeader>
    <TableBody>
      {rows.map((row) => ( ... ))}
    </TableBody>
  </Table>
)}
{rows.length === 0 && !error && <p className="text-sm text-slate-500">No scans saved yet.</p>}
```

**Concrete scenario & standards impact:**  
When an authenticated user has zero scans (`rows.length === 0`), the component renders the entire table header row (`Date | Type | Summary | Risk`), followed by an empty table body, and directly underneath the naked table header: `<p>No scans saved yet.</p>`.  
To a judge testing a fresh sign-up, this looks unpolished and visually broken compared to the clean empty states in the chat and analyzer components.

**Suggested fix:**  
Only render the `<Table>` structure when `rows.length > 0`:
```tsx
{rows.length === 0 && !error ? (
  <div className="rounded-lg border border-dashed p-8 text-center">
    <p className="text-sm text-slate-600">No scans saved yet.</p>
    <Button asChild variant="outline" size="sm" className="mt-3">
      <Link href="/analyzer">Run your first scan</Link>
    </Button>
  </div>
) : (
  <Table>...</Table>
)}
```

---

### 9. Error Messages and Async State Transitions Lack Live Region Announcements

**Severity:** low  
**File:** `apps/web/app/login/page.tsx:46`, `apps/web/app/signup/page.tsx:51`, `apps/web/app/history/page.tsx:63`  

**Assessment:**  
In `analyzer/page.tsx:319-323`, error messages and unreachable banners use explicit accessibility markup:
`<p role="alert" className="mt-3 text-sm font-medium text-red-700 ...">{error}</p>`
In contrast, `login/page.tsx`, `signup/page.tsx`, and `history/page.tsx` render error messages as plain `<p>` elements:
`{error && <p className="text-sm text-red-700">{error}</p>}`  
Similarly, the confirmation message upon successful sign-up in `signup/page.tsx:40-45` replaces the form without an `aria-live` announcement.

**Concrete scenario & standards impact:**  
- **WCAG 2.1 SC 4.1.3 (Status Messages):** When a user submits invalid credentials or a network error occurs, the error text is rendered into the DOM, but screen reader software does not announce the error because the element lacks `role="alert"` or `aria-live="assertive"`. A blind user may assume the form is still processing.

**Suggested fix:**  
Add `role="alert"` or `aria-live="polite"` to error and confirmation blocks:
```tsx
{error && <p role="alert" className="text-sm text-red-700 font-medium">{error}</p>}
```

---

### 10. Flash of Blank Screen / Unhandled Loading State on History Route

**Severity:** low  
**File:** `apps/web/app/history/page.tsx:53`  

**Assessment:**  
`HistoryPage` includes:
`if (loading || !session) return null;`
While Supabase retrieves the session from `localStorage` or remote auth, `loading` is `true`. During this period, the page renders `null`.

**Concrete scenario & standards impact:**  
- When navigating to `/history`, users experience a sudden flash of blank white space before content mounts.
- If the user is unauthenticated, the blank screen appears briefly before an abrupt client-side redirect to `/login`.
- A judge clicking between tabs experiences perceptible visual layout shift (CLS).

**Suggested fix:**  
Provide a lightweight skeleton layout or loading indicator rather than returning `null`:
```tsx
if (loading) {
  return (
    <div className="mx-auto max-w-4xl py-10 px-4 animate-pulse">
      <div className="h-6 w-48 bg-slate-200 rounded mb-4" />
      <div className="h-32 bg-slate-100 rounded" />
    </div>
  );
}
```

---

### 11. Generic Row Action Button Names and Empty Table Header Column

**Severity:** low  
**File:** `apps/web/app/history/page.tsx:79`, `apps/web/app/history/page.tsx:90-91`  

**Assessment:**  
1. Line 79: `<TableHead />` renders an empty table header without content or an accessible label.
2. Lines 90–91: Every table row contains:
   `<Button variant="outline" size="sm" onClick={() => setSelected(row.result_json)}>View</Button>`
   `<Button variant="destructive" size="sm" onClick={() => removeRow(row.id)}>Delete</Button>`

**Concrete scenario & standards impact:**  
- **WCAG 2.1 SC 2.4.4 (Link/Button Purpose - In Context):** When a screen reader user navigates using the "Buttons List" rotor/virtual navigation, they hear a repetitive sequence: "View", "Delete", "View", "Delete", with no contextual indication of which scan entry is affected.
- Screen readers announce the final column of the table as an unnamed header.

**Suggested fix:**  
1. Give the header column a hidden label:
   ```tsx
   <TableHead className="text-right"><span className="sr-only">Actions</span></TableHead>
   ```
2. Add `aria-label` attributes to the buttons with the row's date or input type:
   ```tsx
   <Button
     variant="outline"
     size="sm"
     onClick={() => setSelected(row.result_json)}
     aria-label={`View scan for ${row.input_type} from ${new Date(row.created_at).toLocaleDateString()}`}
   >
     View
   </Button>
   ```

---

### 12. "Clear All" Action Retained in History Inspection Detail View

**Severity:** low  
**File:** `apps/web/app/history/page.tsx:57-61`, `apps/web/app/history/page.tsx:64-70`  

**Assessment:**  
When a user clicks "View", `selected` is populated, rendering `<ResultDashboard result={selected} />` preceded by a `<Button>Back to history</Button>`.  
However, lines 57–61 remain mounted at the top:
```tsx
<div className="mb-4 flex items-center justify-between">
  <h1 className="text-xl font-semibold">Your scan history</h1>
  {rows.length > 0 && (
    <Button variant="outline" size="sm" onClick={clearAll}>Clear all</Button>
  )}
</div>
```

**Concrete scenario & standards impact:**  
While actively inspecting a single past scan result, the page header still reads "Your scan history" and the "Clear all" button remains prominently visible right above the result. Clicking "Clear all" while viewing a result wipes the underlying history records, causing confusion.

**Suggested fix:**  
Conditionally hide the "Clear all" button and update the header when `selected` is active:
```tsx
{!selected && rows.length > 0 && (
  <Button variant="outline" size="sm" onClick={clearAll}>Clear all</Button>
)}
```

---

### 13. Navbar Links Lack Active Route Indication (`aria-current`)

**Severity:** informational  
**File:** `apps/web/components/NavBar.tsx:16-33`  

**Assessment:**  
The navigation links ("Analyzer", "History", "Log in", "Sign up") are static text links without active-route detection.

**Concrete scenario & standards impact:**  
- **WCAG 2.1 SC 3.2.4 (Consistent Identification):** There is no visual highlight (active color, underline, pill) or assistive hint (`aria-current="page"`) to inform users which page they are currently visiting. When on `/analyzer`, the "Analyzer" link looks identical to all other links.

**Suggested fix:**  
Use Next.js `usePathname()` from `next/navigation`:
```tsx
const pathname = usePathname();
...
<Link
  href="/analyzer"
  aria-current={pathname === "/analyzer" ? "page" : undefined}
  className={cn(pathname === "/analyzer" ? "font-semibold text-blue-700" : "text-slate-600 hover:text-slate-900")}
>
  Analyzer
</Link>
```

---

### 14. Subtle Input Field Border Contrast in Light Mode

**Severity:** informational  
**File:** `apps/web/components/ui/input.tsx:11`, `apps/web/app/globals.css:72`  

**Assessment:**  
In `globals.css`, `--input` is set to `oklch(0.922 0 0)` (approx `#e6e6e6`). Input components have transparent backgrounds (`bg-transparent`) resting directly on a white card (`#ffffff`).  
The measured contrast ratio of the input boundary against the background is **1.26:1**.

**Concrete scenario & standards impact:**  
- **WCAG 2.1 SC 1.4.11 (Non-text Contrast):** User interface components require 3:1 contrast against adjacent colors unless active/focused. While the focused ring has good visibility, when unfocused, users with low contrast sensitivity may struggle to perceive the bounding box of the input.

**Suggested fix:**  
Consider darkening `--input` or applying `border-slate-300` (ratio ~ 2.1:1+) or adding a slight background fill (`bg-slate-50`) to provide component definition.

---

## Verifications with No Finding (What Is Genuinely Fine)

### 1. Logo Alt Text and Asset Delivery
- **File:** `apps/web/components/NavBar.tsx:14`
- **Assessment:** `<Image src="/logo.png" alt="TrustShield AI" width={140} height={24} priority />`
- The `alt` text accurately reflects the brand name. The image exists in `public/logo.png`, is flagged with `priority` to prevent LCP penalties, and maintains proper aspect ratio.

### 2. ResultDashboard Coverage Copy Clarification
- **File:** `apps/web/components/ResultDashboard.tsx:36-45, 173`
- **Assessment:**  
  ```tsx
  function getModuleStatusLabel(status: string, detail: string | undefined): string {
    if (status === "unavailable" && detail && /not configured|no .* provider configured/i.test(detail)) {
      return "Not configured";
    }
    return pretty(status);
  }
  ```
- **Verdict:** **Genuinely fine and a tangible UX improvement.**
- **Why:** During hackathon evaluations, judges often inspect the module coverage breakdown. Showing "Unavailable" for modules that simply do not have optional cloud credentials configured (e.g. cloud web sandbox or third-party ML providers) gives the false impression that a service failed or crashed. Relabeling this display string to "Not configured" provides honest, accurate transparency without mutating the raw API payload.

### 3. Logical Focus Reading Order
- **Files:** `apps/web/app/login/page.tsx`, `apps/web/app/signup/page.tsx`, `apps/web/app/history/page.tsx`
- **Assessment:** Keyboard `Tab` navigation travels in natural reading order:
  `Logo -> Nav links -> Form inputs / Table controls -> Submit button -> Secondary links`.
  There are no positive `tabIndex` values disrupting the natural DOM order.

### 4. Primary Body and Error Text Contrast
- **Files:** `apps/web/app/login/page.tsx`, `apps/web/app/signup/page.tsx`, `apps/web/app/history/page.tsx`
- **Assessment:**
  - `text-red-700` (`#b91c1c`) against `#ffffff`: measured contrast is **6.45:1** (exceeds WCAG AA 4.5:1).
  - `text-slate-600` (`#475569`) against `#ffffff`: measured contrast is **7.00:1** (exceeds WCAG AAA 7:1).
  - `text-slate-700` (`#334155`) against `#ffffff`: measured contrast is **9.60:1** (exceeds WCAG AAA 7:1).
  - `text-slate-500` (`#64748b`) against `#ffffff`: measured contrast is **4.76:1** (passes WCAG AA 4.5:1).
  - Primary button text (white on near-black): measured contrast is **>15:1**.

---

## Review Method

1. **Static Analysis:** Line-by-line inspection of `apps/web/app/login/page.tsx`, `signup/page.tsx`, `history/page.tsx`, `components/NavBar.tsx`, `ResultDashboard.tsx`, and `apps/web/app/analyzer/page.tsx`.
2. **Browser Execution & DOM Auditing:** Active Playwright automated browser instance navigating the local Next.js dev server on port 3000 (`http://localhost:3000`).
3. **Accessibility Tree Inspection:** Evaluation of accessibility snapshots (roles, landmarks, heading levels, accessible names).
4. **Color Contrast Measurement:** Exact Canvas RGB extraction and WCAG 2.1 relative luminance calculation for all button states, text tokens, and borders in light mode.
5. **Responsive Viewport Testing:** Evaluation at desktop (1280px) and mobile viewport widths (360px and 375px) to measure bounding box collisions, overflow, and layout shifts.
