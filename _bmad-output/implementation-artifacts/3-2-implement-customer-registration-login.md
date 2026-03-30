# Story 3.2: Implement Customer Registration & Login

Status: done

## Story

As a customer,
I want to register and log in using email/password or social login (Google/GitHub) through Keycloak,
So that I can access personalized features like persistent cart and order history.

## Acceptance Criteria

1. **Given** the Customer Website **When** I click the user icon in the header **Then** a Login modal (PrimeVue Dialog) appears with social login buttons (Google, GitHub) prominent at top, and email/password form below (UX Spec: Modal & Overlay Patterns — Login Modal; Journey 5 Key UX Decisions)
2. **Given** the Login modal **When** I click "Login with Google" **Then** I am redirected to Keycloak → Google OAuth2 consent screen, and upon authorization, redirected back to the app with JWT tokens stored (FR38)
3. **Given** the Login modal **When** I click "Login with GitHub" **Then** I am redirected to Keycloak → GitHub OAuth2 consent screen, and upon authorization, redirected back to the app with JWT tokens stored (FR38)
4. **Given** the Login modal **When** I enter valid email/password and submit **Then** Keycloak authenticates via Authorization Code + PKCE flow and returns JWT access + refresh tokens, stored securely in the app (FR37)
5. **Given** the Login modal **When** I enter invalid credentials and submit **Then** a clear error message is displayed without clearing the form
6. **Given** a new user **When** I click "Register" in the Login modal and complete the registration form (email, password, confirm password, first name, last name) **Then** a new Keycloak account is created with CUSTOMER role, and I am automatically logged in (FR37)
7. **Given** an authenticated user **When** the JWT access token expires (15 minutes) **Then** the app silently uses the refresh token to obtain a new access token without interrupting the user session (FR42)
8. **Given** an authenticated user **When** viewing the header **Then** the user icon is replaced with the user's name/email and a "Logout" option in a dropdown menu
9. **Given** an authenticated user **When** I click "Logout" **Then** tokens are cleared from memory and storage, Keycloak session is ended, and UI reverts to unauthenticated state
10. **Given** Pinia `useAuthStore` **When** authentication state changes **Then** it manages: user profile, JWT tokens, `isAuthenticated` (computed), `login()`, `logout()`, `register()`, `refreshToken()`, `initAuth()` actions
11. **Given** the Axios HTTP client **When** a request is made by an authenticated user **Then** the `Authorization: Bearer <accessToken>` header is attached automatically, and if a 401 response is received, token refresh is attempted once before failing
12. **Given** the frontend **When** the app initializes (page load/refresh) **Then** `useAuthStore.initAuth()` checks for existing tokens, validates them, and restores authentication state (session survives browser refresh)

## Tasks / Subtasks

- [ ] Task 1: Implement Keycloak OIDC Auth Service (AC: 2, 3, 4, 5, 6, 7, 9)
  - [ ] 1.1 Add `oidc-client-ts` dependency to `customer-website/package.json` — lightweight OIDC/OAuth2 library that handles Authorization Code + PKCE flow, token storage, and silent refresh
  - [ ] 1.2 Create `src/auth/keycloak.ts` — configure `UserManager` from `oidc-client-ts` with Keycloak OIDC discovery endpoint (`http://localhost:8180/realms/robomart/.well-known/openid-configuration`), client ID `robo-mart-frontend`, redirect URIs, PKCE S256, scopes `openid profile email`
  - [ ] 1.3 Create `src/auth/authService.ts` — wrapper around `UserManager` exposing: `login()` (redirect to Keycloak), `loginCallback()` (handle redirect back), `register()` (redirect to Keycloak registration page via `kc_action=register`), `logout()` (end Keycloak session + clear local state), `renewToken()` (silent refresh via hidden iframe), `getUser()` (current OIDC user), `getAccessToken()` (current JWT)
  - [ ] 1.4 Create `src/views/AuthCallbackView.vue` — callback page at `/auth/callback` that calls `authService.loginCallback()`, then redirects to the originally requested page (or home)
  - [ ] 1.5 Create `src/views/AuthSilentRenewView.vue` — silent renew page at `/auth/silent-renew` (hidden iframe for token refresh)
  - [ ] 1.6 Add environment variables to `.env.development`: `VITE_KEYCLOAK_URL=http://localhost:8180`, `VITE_KEYCLOAK_REALM=robomart`, `VITE_KEYCLOAK_CLIENT_ID=robo-mart-frontend`, `VITE_API_URL=http://localhost:8080` (API Gateway — replacing direct service URL `8081`)
  - [ ] 1.7 Create `public/silent-renew.html` — minimal HTML page for silent token renewal iframe

- [ ] Task 2: Implement Auth Pinia Store (AC: 10, 12)
  - [ ] 2.1 Create `src/types/auth.ts` — TypeScript interfaces: `AuthUser` (id, email, firstName, lastName, roles), `AuthState`
  - [ ] 2.2 Create `src/stores/useAuthStore.ts` — Pinia store with composition API: `user` (ref), `isAuthenticated` (computed), `isLoading` (ref), `error` (ref), `login()`, `register()`, `logout()`, `refreshToken()`, `initAuth()`, `handleCallback()`
  - [ ] 2.3 `initAuth()` — called on app startup: check if `oidc-client-ts` has a stored user, validate token expiry, populate store state. If token expired, attempt silent refresh. If that fails, clear state (user must re-login)
  - [ ] 2.4 `login()` — call `authService.login()` (redirects to Keycloak). Store current route path for post-login redirect
  - [ ] 2.5 `register()` — call `authService.register()` (redirects to Keycloak registration)
  - [ ] 2.6 `logout()` — call `authService.logout()`, clear store state, reset cart store's user context
  - [ ] 2.7 `handleCallback()` — called from AuthCallbackView: call `authService.loginCallback()`, map OIDC user claims to `AuthUser`, populate store state, return saved redirect path
  - [ ] 2.8 `refreshToken()` — call `authService.renewToken()`, update store state on success

- [ ] Task 3: Update Axios HTTP Client for JWT (AC: 11)
  - [ ] 3.1 Update `src/api/client.ts` — add request interceptor: if `useAuthStore.isAuthenticated`, attach `Authorization: Bearer <accessToken>` header. Keep existing `X-User-Id` header for anonymous users (falls back to localStorage UUID when not authenticated)
  - [ ] 3.2 Add response interceptor for 401 handling: on 401 response, attempt `authStore.refreshToken()` once, then retry the original request. If refresh fails, call `authStore.logout()` and show toast "Session expired, please log in again". Use a shared refresh-in-progress promise to prevent concurrent refresh requests — if a refresh is already in flight, queue subsequent 401 retries to await the same promise
  - [ ] 3.3 When authenticated, use the JWT `sub` claim as the `X-User-Id` header value instead of the generated UUID — this ensures backend services receive the authenticated user ID

- [ ] Task 4: Implement Login Modal Component (AC: 1, 4, 5)
  - [ ] 4.1 Create `src/components/auth/LoginModal.vue` — PrimeVue `Dialog` component, closable, responsive. Layout: social login buttons at top (Google, GitHub), divider "or", email/password form below
  - [ ] 4.2 Social login buttons: styled prominently (full-width, with provider icons/logos). Google button calls `authStore.login()` with `kc_idp_hint=google`. GitHub button calls `authStore.login()` with `kc_idp_hint=github`
  - [ ] 4.3 Email/password form: PrimeVue `InputText` for email, `Password` for password (with toggle visibility), `Button` for submit. Inline validation on blur (email format, password required). VeeValidate + Yup schema
  - [ ] 4.4 Email/password submit: calls `authStore.login()` which redirects to Keycloak login page (pre-filled with email if possible via `login_hint` parameter)
  - [ ] 4.5 "Don't have an account? Register" link below form — calls `authStore.register()`
  - [ ] 4.6 Error state: display error message from authStore.error in a PrimeVue `Message` component (severity="error")
  - [ ] 4.7 Loading state: disable form inputs and show spinner on submit button while authenticating

- [ ] Task 5: Update Header for Auth State (AC: 8, 9)
  - [ ] 5.1 Update `DefaultLayout.vue` — integrate `useAuthStore`. When unauthenticated: user icon button opens LoginModal. When authenticated: show user display name + dropdown with "My Account" (disabled, future), "Logout"
  - [ ] 5.2 Implement user dropdown: PrimeVue `Menu` component with `popup` mode, triggered by clicking the user area in header
  - [ ] 5.3 Logout action: calls `authStore.logout()`, shows toast "Logged out successfully"
  - [ ] 5.4 Auth state display: show first name or email when authenticated, smooth transition between states

- [ ] Task 6: Configure Router for Auth (AC: 12)
  - [ ] 6.1 Add routes: `/auth/callback` → `AuthCallbackView` (handles OIDC redirect), `/auth/silent-renew` → `AuthSilentRenewView` (hidden iframe renewal)
  - [ ] 6.2 Call `authStore.initAuth()` in `main.ts` before mounting the app — ensures auth state is restored before any route guard runs
  - [ ] 6.3 No protected routes for this story — all customer pages are public. Cart merge (Story 3.4) and checkout auth guard (Epic 4) will add guards later. The auth infrastructure (store, guards) is ready but not enforced

- [ ] Task 7: Update Keycloak Realm for Frontend Redirects (AC: 2, 3, 4, 6)
  - [ ] 7.1 Update `infra/docker/keycloak/robomart-realm.json` — add `/auth/callback` and `/auth/silent-renew` to `robo-mart-frontend` client redirect URIs if not already present
  - [ ] 7.2 Verify `robo-mart-frontend` client has `standardFlowEnabled: true`, `publicClient: true`, PKCE S256 enforced
  - [ ] 7.3 Ensure Keycloak registration action is enabled (realm `registrationAllowed: true` — already set in Story 3.1)

- [ ] Task 8: Write Frontend Tests (All ACs)
  - [ ] 8.1 `useAuthStore.spec.ts` — test: initAuth (with/without stored user, with expired token), login (redirects), register (redirects), logout (clears state, resets cart), handleCallback (maps claims), refreshToken (updates state)
  - [ ] 8.2 `authService.spec.ts` — test: UserManager configuration, login/register/logout/renewToken call delegation, callback handling
  - [ ] 8.3 `LoginModal.spec.ts` — test: renders social buttons + email form, social button clicks trigger login with idp_hint, form validation (empty email, invalid email), submit triggers login, register link triggers register, error display, loading state
  - [ ] 8.4 `DefaultLayout.spec.ts` (auth integration) — test: unauthenticated shows user icon, authenticated shows user name + dropdown, logout calls authStore.logout, LoginModal opens on user icon click
  - [ ] 8.5 `client.ts` (interceptor updates) — test: attaches Bearer token when authenticated, sends X-User-Id with JWT sub when authenticated, retries on 401 with refresh, logs out on refresh failure, concurrent 401s only trigger one refresh (mutex guard), gracefully handles OIDC discovery endpoint unreachable during initAuth
  - [ ] 8.6 `AuthCallbackView.spec.ts` — test: calls handleCallback on mount, redirects to saved path on success, shows error on failure

## Dev Notes

### Critical: Keycloak OAuth2 Authorization Code + PKCE Flow

This story implements the **Authorization Code flow with PKCE** — the standard for SPAs. The flow:

1. User clicks "Login" → app redirects to Keycloak login page
2. User authenticates at Keycloak (email/password or social provider)
3. Keycloak redirects back to `/auth/callback` with authorization code
4. `oidc-client-ts` exchanges code for tokens (access + refresh + id_token) using PKCE verifier
5. Tokens stored by `oidc-client-ts` in `sessionStorage` (default, secure)
6. Silent refresh via hidden iframe before access token expires

**Why `oidc-client-ts` over direct Keycloak JS adapter:**
- `keycloak-js` adapter is tightly coupled to Keycloak and is heavy (~50KB)
- `oidc-client-ts` is standard OIDC/OAuth2 library (~15KB), works with any OIDC provider
- Better TypeScript support, actively maintained
- Used by most production Vue.js + Keycloak setups

### Critical: Token Storage Strategy

`oidc-client-ts` defaults to `sessionStorage` for token storage:
- **sessionStorage** — cleared when tab closes. More secure (XSS in one tab doesn't leak to others)
- For this project, `sessionStorage` is appropriate: tokens survive page refreshes but not tab close
- The `automaticSilentRenew` feature handles token refresh before expiry

### Critical: Social Login via `idp_hint`

Keycloak supports `kc_idp_hint` parameter to skip the Keycloak login page and go directly to the social provider:
- `authService.login({ extraQueryParams: { kc_idp_hint: 'google' } })` → goes directly to Google
- `authService.login({ extraQueryParams: { kc_idp_hint: 'github' } })` → goes directly to GitHub
- `authService.login()` without hint → shows Keycloak login page (email/password)

### Critical: Registration via Keycloak

Keycloak handles registration natively. To redirect to the registration form:
- Use `authService.login({ extraQueryParams: { kc_action: 'register' } })` — redirects to Keycloak's built-in registration form
- After registration, the normal callback flow handles token retrieval
- No custom registration API or backend endpoint needed — Keycloak manages user creation

### Frontend API Base URL Change

Currently `client.ts` points directly to Product Service (`http://localhost:8081`). With API Gateway in place (Story 3.1), requests should go through the gateway:
- Update `VITE_API_URL` to `http://localhost:8080` (API Gateway port)
- This is required because JWT validation happens at the Gateway level
- All API calls (products, cart, future orders) will route through the Gateway

### X-User-Id Header Transition

Current anonymous flow uses `localStorage('robomart-user-id')` as `X-User-Id`. When authenticated:
- Use the JWT `sub` claim (Keycloak user UUID) as `X-User-Id`
- This ensures backend services identify the real user
- Anonymous users continue using the generated UUID
- Story 3.4 (cart merge) will handle merging anonymous cart to authenticated user
- **Important for Story 3.4**: The anonymous UUID in `localStorage('robomart-user-id')` is preserved after login. Story 3.4 should read this value during cart merge before clearing it. Consider exposing `getAnonymousUserId()` in the auth store or keeping it accessible via localStorage

### Keycloak OIDC Discovery Endpoint

`oidc-client-ts` uses OIDC discovery to auto-configure endpoints:
- Discovery URL: `http://localhost:8180/realms/robomart/.well-known/openid-configuration`
- This provides: authorization_endpoint, token_endpoint, userinfo_endpoint, end_session_endpoint, jwks_uri
- No need to hardcode individual Keycloak endpoints

### Existing Keycloak Realm Configuration (from Story 3.1)

The realm JSON already has:
- Client `robo-mart-frontend`: public, PKCE S256, redirect URIs for localhost:5173 and localhost:5174
- Registration enabled (`registrationAllowed: true`)
- Google and GitHub identity providers (with placeholder credentials)
- Demo users: `demo-customer@robomart.com` / `customer123`, `demo-admin@robomart.com` / `admin123`
- Token lifespans: access=900s (15min), refresh not explicitly set (Keycloak default ~30min for refresh in dev mode)

Redirect URIs may need updating to include `/auth/callback` path.

### UX Design Decisions (from UX Spec)

- Login is a **modal**, not a page redirect — preserves user context (UX-DR20)
- Social login buttons are **primary** (top, prominent) — email/password is secondary
- No auth wall before cart — browse and cart freely, login only when needed (checkout guard is Story 3.4/Epic 4)
- JWT refresh is **silent** — user never gets logged out during active session
- Maximum 1 overlay at a time — LoginModal follows overlay rules (closable, Esc to close, focus trapped)

### Project Structure — New Files

```
frontend/customer-website/
├── public/
│   └── silent-renew.html                    # NEW: Silent token renewal page
├── .env.development                         # NEW: Keycloak env vars
└── src/
    ├── auth/
    │   ├── keycloak.ts                      # NEW: oidc-client-ts UserManager config
    │   └── authService.ts                   # NEW: Auth service wrapper
    ├── types/
    │   └── auth.ts                          # NEW: AuthUser, AuthState types
    ├── stores/
    │   └── useAuthStore.ts                  # NEW: Auth Pinia store
    ├── components/
    │   └── auth/
    │       └── LoginModal.vue               # NEW: Login/Register modal
    ├── views/
    │   ├── AuthCallbackView.vue             # NEW: OIDC callback handler
    │   └── AuthSilentRenewView.vue          # NEW: Silent renew handler
    ├── api/
    │   └── client.ts                        # MODIFIED: JWT interceptors
    ├── router/
    │   └── index.ts                         # MODIFIED: Auth routes
    ├── layouts/
    │   └── DefaultLayout.vue                # MODIFIED: Auth UI in header
    └── main.ts                              # MODIFIED: Init auth on startup
```

### Testing Strategy

**Unit tests** (Vitest + vue-test-utils):
- Mock `oidc-client-ts` `UserManager` — don't actually redirect to Keycloak
- Use `vi.mock()` for authService in store tests
- Use `mount()` with `global.plugins: [pinia]` for component tests
- Test naming: `should{Expected}When{Condition}` (consistent with project convention)

**Component tests** (PrimeVue components):
- Provide PrimeVue plugin in test setup
- Mock `useAuthStore` return values for different states
- Test user interactions: click social buttons, submit form, toggle modal

**Integration verification** (manual):
- Start Docker Compose + frontend dev server
- Click Login → redirects to Keycloak login page
- Login with demo-customer credentials → redirected back, header shows user name
- Refresh page → still authenticated (sessionStorage persists)
- Wait 15 minutes (or manually expire token) → silent refresh works
- Click Logout → redirected to home, header shows login icon

### Dependencies

- **Story 3.1 (DONE)**: Keycloak container, realm, API Gateway, security-lib — all ready
- **`oidc-client-ts`**: npm package, no backend changes needed
- **PrimeVue components used**: Dialog, InputText, Password, Button, Menu, Message, Divider

### References

- [Source: _bmad-output/planning-artifacts/epics.md — Epic 3, Story 3.2 (Lines 775-806)]
- [Source: _bmad-output/planning-artifacts/architecture.md — Frontend Architecture (Lines 430-452)]
- [Source: _bmad-output/planning-artifacts/architecture.md — Auth & Security (Lines 345-363)]
- [Source: _bmad-output/planning-artifacts/architecture.md — Frontend Directory Structure (Lines 1495-1573)]
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md — Journey 5: Customer Registration & Auth (Lines 1022-1060)]
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md — Login Modal Pattern (Line 1484)]
- [Source: _bmad-output/planning-artifacts/prd.md — FR37-FR42 Identity & Access]
- [Source: _bmad-output/implementation-artifacts/epic-2-retro-2026-03-29.md — Epic 3 Risks & Dependencies]
- [Source: infra/docker/keycloak/robomart-realm.json — Client configuration]

### Previous Epic Intelligence

**From Epic 2 Retrospective:**
- Pinia Composition API pattern established: `defineStore('name', () => { ... })` with ref, computed, async actions
- Optimistic UI with rollback pattern: snapshot → update → API → sync/rollback
- Axios interceptors pattern (X-User-Id) — now extended with JWT Bearer token
- PrimeVue Toast with custom #message slot — reusable for auth feedback
- Pre-auth identity resolution via X-User-Id header bridges anonymous → authenticated

**From Story 3.1 Completion Notes:**
- Spring Cloud Gateway starter: `spring-cloud-starter-gateway-server-webflux`
- OAuth2 starters renamed: `spring-boot-starter-security-oauth2-resource-server`
- WebTestClient not auto-configured — use `WebTestClient.bindToServer()`
- Keycloak 26.x env vars: `KC_BOOTSTRAP_ADMIN_USERNAME` / `KC_BOOTSTRAP_ADMIN_PASSWORD`

### Key Risks

1. **CORS for Keycloak redirects** — Browser redirects to Keycloak and back. CORS is configured in API Gateway (Story 3.1) for Keycloak origin. If issues arise, check `allowedOrigins` includes `http://localhost:8180`
2. **Silent refresh iframe** — Requires Keycloak to allow iframe embedding. Keycloak in dev mode allows this. If Content-Security-Policy blocks it, add `frame-src` directive
3. **Social login with placeholder credentials** — Google/GitHub identity providers have placeholder client IDs. Social login buttons will render but actual social auth won't work until real credentials are configured. Dev testing uses email/password with demo users
4. **Token expiry timing** — `oidc-client-ts` `automaticSilentRenew` renews token ~60s before expiry. If network is slow, user might hit 401. The Axios 401 interceptor provides a safety net
