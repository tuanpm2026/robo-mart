# Story 1.6: Setup Customer Website Foundation & Design System

Status: done

## Story

As a customer,
I want to access a well-designed, accessible website with consistent branding,
So that I can navigate and interact with the store in a polished, trustworthy experience.

## Acceptance Criteria

1. **Shared Design Tokens**: Given @robo-mart/shared package, when imported by Customer Website, then it provides design tokens: colors.ts (primary blue palette primary-50 through primary-900, semantic success/warning/error/info with 50 and 500 variants, neutral grays), typography.ts (Inter font, customer 16px base scale), spacing.ts (4px base unit: xs through 3xl) (UX-DR1)

2. **PrimeVue Customer Theme**: Given PrimeVue with customer-theme, when the app renders, then Aura preset is active with: 16px base font, 1.6 line-height, 24px card padding, 8px border-radius, primary-600 CTA color, white page background, 200ms animation duration (UX-DR2)

3. **Tailwind CSS Integration**: Given Tailwind CSS, when configured via tailwind.config.ts, then it extends @robo-mart/shared tokens with customer-specific generous spacing values

4. **DefaultLayout**: Given the Customer Website layout, when viewing the app, then DefaultLayout renders: Fixed top header with Logo (left), search bar placeholder (center), Cart icon with Badge + User menu placeholder (right); Horizontal category nav below header; `<main>` content area; Footer (UX-DR9)

5. **Toast System**: Given PrimeVue Toast, when configured globally, then toast system works at bottom-right: Success (3s auto-dismiss), Error (sticky/manual dismiss), Warning (5s), Info (4s), Max 3 stacked (UX-DR14)

6. **Button Hierarchy**: Given button components, when rendered in the app, then they follow hierarchy: Primary (solid primary-600, max 1 per view), Secondary (outlined), Text, Danger, Ghost, with 40px min height and loading state (disabled + spinner) (UX-DR15)

7. **EmptyState Component**: Given EmptyState component in @robo-mart/shared, when used with variant="search-results", then it renders: SVG line art illustration (aria-hidden), Title "No results found", Description "Try different keywords or filters", CTA button "Clear Filters" (keyboard focusable) (UX-DR7)

8. **Accessibility**: Given all interactive elements, when navigated via keyboard, then: 2px primary-500 focus outline is visible, Skip-to-main-content link exists and works, Semantic HTML landmarks (header, nav, main, footer) are present (UX-DR17)

9. **Reduced Motion**: Given the app with prefers-reduced-motion: reduce enabled, when animations would normally play, then all transitions are instant (no animation) (UX-DR17)

## Tasks / Subtasks

- [x] Task 1: Install dependencies (AC: #1, #2, #3)
  - [x] 1.1 Add PrimeVue + @primevue/themes to customer-website
  - [x] 1.2 Add Tailwind CSS + @tailwindcss/vite to customer-website
  - [x] 1.3 Add Inter font via @fontsource/inter

- [x] Task 2: Create shared design tokens in @robo-mart/shared (AC: #1)
  - [x] 2.1 Create `tokens/colors.ts` — primary palette, semantic colors, neutral grays
  - [x] 2.2 Create `tokens/typography.ts` — Inter font, customer type scale
  - [x] 2.3 Create `tokens/spacing.ts` — 4px base unit, xs through 3xl
  - [x] 2.4 Export all from `src/index.ts`

- [x] Task 3: Create PrimeVue customer theme (AC: #2)
  - [x] 3.1 Create `themes/customer-theme.ts` — Aura preset with customer tokens

- [x] Task 4: Configure Tailwind CSS (AC: #3)
  - [x] 4.1 Configure via CSS `@theme` directive (Tailwind 4.x approach, no JS config)
  - [x] 4.2 Create `src/assets/app.css` with Tailwind directives + global styles

- [x] Task 5: Setup PrimeVue + Toast in main.ts (AC: #2, #5)
  - [x] 5.1 Configure PrimeVue plugin with customer-theme
  - [x] 5.2 Add ToastService + Toast component globally
  - [x] 5.3 Configure Toast position bottom-right, max 3

- [x] Task 6: Create DefaultLayout (AC: #4, #8)
  - [x] 6.1 Create `layouts/DefaultLayout.vue` — header, category nav, main, footer
  - [x] 6.2 Add skip-to-main-content link
  - [x] 6.3 Use semantic HTML landmarks

- [x] Task 7: Create EmptyState component (AC: #7)
  - [x] 7.1 Create `components/EmptyState.vue` in shared package
  - [x] 7.2 Support variant prop (search-results, cart, orders, generic)
  - [x] 7.3 Include SVG illustration, title, description, CTA button

- [x] Task 8: Update App.vue and router (AC: #4)
  - [x] 8.1 Replace scaffold App.vue with DefaultLayout
  - [x] 8.2 Update HomeView as placeholder
  - [x] 8.3 Add 404 catch-all route with NotFoundView

- [x] Task 9: Configure accessibility (AC: #8, #9)
  - [x] 9.1 Add focus outline styles (2px primary-500)
  - [x] 9.2 Add prefers-reduced-motion CSS
  - [x] 9.3 Update index.html with lang attribute and title
  - [x] 9.4 Add PrimeVue button min-height 40px override

- [x] Task 10: Write unit tests (AC: #1, #4, #7)
  - [x] 10.1 Test design tokens export correctly (6 tests)
  - [x] 10.2 Test DefaultLayout renders all landmarks (7 tests)
  - [x] 10.3 Test EmptyState renders with variant prop (7 tests)
  - [x] 10.4 Test App renders skip-to-main, Toast, DefaultLayout (3 tests)

### Review Findings

- [x] [Review][Patch] Add PrimeVue button min-height 40px CSS override for AC #6 compliance [app.css]
- [x] [Review][Patch] Add 404 catch-all route with NotFoundView for undefined routes [router/index.ts]
- [x] [Review][Defer] Focus management on route navigation — move focus to #main-content after route change — deferred, future story scope
- [x] [Review][Defer] Router error boundary / onError handler — deferred, Epic 8 resilience scope

## Dev Notes

### Architecture Compliance

- **Package structure**: `@robo-mart/shared` for tokens/themes, `customer-website` consumes via npm workspace
- **Component library**: PrimeVue 4.5.4 with Aura preset + custom tokens
- **Styling**: Tailwind CSS 4.2.2 via @tailwindcss/vite with `@theme` directive
- **Layout**: DefaultLayout wraps all pages via App.vue
- **Button variants**: PrimeVue Button natively supports Primary/Secondary/Text/Danger/Ghost + loading state

### Critical Technical Requirements

**PrimeVue 4.x Setup:**
- Use `@primevue/themes` for theming (Aura preset)
- `definePreset()` to customize Aura with project tokens
- Import PrimeVue plugin + ToastService in main.ts
- No CSS import needed — PrimeVue 4.x uses styled preset system

**Tailwind CSS 4.x / @tailwindcss/vite:**
- Tailwind CSS 4 uses Vite plugin + CSS `@theme` directive (no tailwind.config.js needed)
- Design tokens defined as CSS custom properties via `@theme` block
- `@import 'tailwindcss'` replaces old `@tailwind base/components/utilities`

**Font Loading:**
- Inter via @fontsource/inter (self-hosted, bundled in build output)
- Weights: 400, 500, 600, 700 (matching typography token scale)

### Existing Components to REUSE

- Vite + Vue Router + Pinia already configured
- ESLint + Prettier + Oxlint toolchain in place
- npm workspace structure ready

### What NOT to Implement

- Product browsing/search UI — Story 1-7
- API integration — Story 1-7
- Dark mode — not in scope
- Mobile/tablet responsive — desktop-only per spec
- Admin dashboard theming — separate story

### Testing Requirements

- **Test naming**: `should{Expected}When{Condition}()` or descriptive `it('renders ...')`
- **Assertions**: vitest expect + Vue Test Utils
- **Component tests**: Mount with required plugins (PrimeVue, router)
- **Test results**: 4 test files, 23 tests, all passing

### References

- [Source: ux-design-specification.md] — full design system specs
- [Source: architecture.md] — PrimeVue + Tailwind selection, project structure
- [Source: epics.md#Story 1.6] — acceptance criteria
