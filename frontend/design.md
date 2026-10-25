# CampusCore frontend design system

Status: approved white-background design, implemented October 2026.
Scope: replacement frontend for the supplied Spring Boot application. API contracts are retained.

## Visual reference

The approved design has a white canvas, a rounded left navigation rail, a generous main column
and a narrower Today panel on the Courses overview. Bold sans-serif headings establish hierarchy.
Warm cream cards, restrained black surfaces and paprika accents provide contrast. No gradients,
serif headings, decorative analytics, fake progress or hardcoded student data.

## Color tokens

| Token | Value | Purpose |
|---|---|---|
| Canvas | #FFFFFF | Page and sidebar background |
| Floral white | #FFFCF2 | Course cards and quiet supporting surfaces |
| Dust grey | #CCC5B9 | Strong borders and neutral details |
| Charcoal brown | #403D39 | Secondary text |
| Carbon black | #252422 | Main text, primary buttons, active navigation |
| Spicy paprika | #EB5E28 | Today's first scheduled class, selected day, registration action |

Derived neutral border #E9E6DF and fill #F2F0EC support separation. Muted text #68635B is deliberately
stronger than dust grey for legibility. Error text uses dark paprika #8D3010 with a light tint;
errors must also have a message/icon. Small links are carbon black, not orange on white.
Orange buttons use dark labels. Never encode meaning with color alone.

## Type

Font stack: 'SF Pro Display', -apple-system, BlinkMacSystemFont, 'Segoe UI', Arial, sans-serif.
Only a locally installed SF Pro Display is referenced; no Apple font binaries are distributed.
Windows normally renders Segoe UI. Exact Apple letterforms depend on local font availability.
Use one sans family throughout, including wordmarks and headings; use monospace for technical IDs.

- Hero: 38–60px, weight 700, line-height 1.06, tracking -0.055em.
- Page heading: 34–38px; section heading: 24–28px; card title: 22–24px.
- Body: 15–16px / 1.55; labels: 14px; metadata: 12–13px.
- Do not shrink important information to make a crowded screen fit.

## Spacing and shape

Use a 4px spacing scale. Desktop outer shell 24–36px; overview section gaps 32–40px;
column gap 36–48px at wide sizes; card gaps 24px; card padding 28px; related controls 16px.
Cards: 24px radius; sidebar/dialog: 28px; buttons/fields: 14–16px; navigation items: 16px.
Controls target at least 44px height; text inputs 48px; overview search 60px.
Shadows are low-opacity neutral, used for separation, never glow.

## Layout and navigation

- Desktop sidebar: 232px; sticky within viewport, internally scrollable. Preserve role-based access.
- Courses: main flexible column plus 320px Today rail, growing to 370px on wide displays.
- Below 1020px: move Today below courses. Below 800px: navigation becomes a dismissible drawer.
- At phone sizes: single-column cards and full-width filters; 16px outer spacing.
- Academic/administrative tables use the full content area with local horizontal scrolling.
- Avoid placing a Today rail on every page. Data-entry screens need useful width.
- Signed-out landing/login/registration reuse the same palette, type and shape tokens.

## Courses overview

Greeting and concise headline, then search. Registered/catalog tabs and department filter precede
course cards. Four results per page prevent long unstructured screens. Each card has the same order:
code/credits and explicit status; title; instructor; schedule; view-details action. Registration is
available in the catalog. A native modal shows course details and a two-step drop confirmation.
The approved mockup's dark card is reserved for actual interaction emphasis, not random alternating
colors. White/cream cards maintain a stable browsing state.

Today uses real timetable API data, the browser's current local date, and a seven-day selector.
Credit progress uses real registered credits/max credits. The notice preview uses the latest inbox
entry. Empty and error states replace unavailable content; no fabricated counts, schedules or alerts.

## Shared component rules

- Reuse Brand, Avatar, Alert, Card, Field, Tabs, Empty and Loading from components/ui.jsx.
- Tokens and foundational components are in styles.css; integrated layout rules in extras.css.
- Every page follows heading → context → controls → content. Use cards for browsing, tables for comparisons.
- Preserve existing academic actions, faculty editing, notifications, short links, crawler and metrics APIs.
- Forms have persistent labels and inline errors. Disable pending mutations to avoid repeated submission.
- Show loading and meaningful empty states. Success messages describe the completed action.
- Keep typography and spacing identical across student, faculty and admin roles.

## Accessibility and behavior

Keyboard-visible focus, named icon buttons, a skip link, native modal focus behavior, Esc to close
modal/navigation, readable text contrast, reduced-motion support, and text equivalents for statuses.
Do not remove keyboard outlines. Responsive tables may scroll inside their section, not the page.
The selected date uses aria-pressed; credit progress exposes its value and maximum.

## Assets and dependencies

Icons are Font Awesome Free bundled via npm; no icon CDN or Google Fonts request is required at runtime.
Primary design is always light/white, irrespective of a previously saved theme preference.
No backend files, database credentials or demo content are included in this replacement.

## Validation

Run npm ci and npm run build. tests/ui-smoke.cjs is an optional Playwright browser test using explicit
mock API responses: it validates frontend rendering/interactions, not real backend behavior.
Set PLAYWRIGHT_MODULE if Playwright is installed elsewhere and SHOT_DIR to a writable test-output folder.
Never describe this fixture run as end-to-end database verification.

## October UI corrections

Use the shared Autocomplete component for course search, directory search, inbox lookup and recipient fields. Suggestions support arrows, Enter and Escape, display API failures, debounce requests and discard stale responses. Multi-recipient fields insert IDs but display names in suggestions. Course suggestions use the already-loaded visible catalog; directory/recipient suggestions use the trie-backed API.

Use aligned-form for adjacent inputs: labels above each field, equal 48px controls, bottom-aligned actions, 16px gaps. Exam tabs show assessment names only; maximum marks belong in the mark column heading. Progress-bar selectors must exclude .toolbar to avoid changing toolbar height or icon geometry.
