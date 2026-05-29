# Flooring Sales Portal — MVP Execution Plan

## 1. Current Status

**Completed:**
- Phase 1–4: DB design, migrations V1–V7, seed data, API conventions, API contracts Chunk 1–4, and OpenAPI contract
- Phase 5: real React/TypeScript frontend visual prototype merged to `main`
- Phase 6: frontend/backend handoff doc merged to `main`
- Phase 7: GitHub Actions CI merged to `main`
- Phase 8 backend scaffold foundation merged to `main`
- Phase 8 Chunk 1 backend complete:
  - `POST /api/v1/{slug}/auth/login`
  - `POST /api/v1/{slug}/auth/select-store`
  - `POST /api/v1/{slug}/auth/logout`
  - `GET /api/v1/{slug}/orders`
  - `PATCH /api/v1/{slug}/orders/{orderId}/status`
- Phase 8 → Phase 9 bridge complete:
  - local-dev CORS for Vite frontend origins `http://localhost:5173` and `http://localhost:5174`
- Phase 9 frontend wire Chunk 1 complete:
  - frontend API client foundation (PR #25)
  - real auth wiring (PR #26)
  - real dashboard list + status patch wiring (PR #32)

**Current next phase:**
- Phase 10: Chunk 2 — Order Shell + Customer + Addresses + Details

**Not started:**
- Chunk 2–4 backend/frontend implementation
- Stripe
- Deployment
- Pilot/hardening

---

## 2. MVP Scope

**Included:**
auth, store selection, dashboard, create/open orders, customer + addresses, details of sale, products, charges, totals/GP, sale price override, notes, photos, invoices, invoice versions, PDF, payments, basic Stripe, container deploy.

**Excluded:**
Operations Portal UI, installer workflows, advanced quote comparison, AI features, refunds/disputes, finance products, surcharges, enterprise monitoring.

---

## 3. Tool Roles

- **ChatGPT:** planning, decision control, prompt drafting, audit, review, merge guidance.
- **Claude chat / Opus:** second-opinion reviewer, spec review, prompt review.
- **Claude Code:** implementation in local repo, backend/frontend changes when explicitly prompted.
- **Codex GitHub bot:** PR review after CI.
- **User:** runs terminal commands, approves decisions, commits, pushes, merges.

**Rules:**
- Claude Code and Codex/other tools should not edit the same files at the same time.
- No commit/merge until diff, tests, CI, and review are clean.
- Prefer small branches with one clear capability.

---

## 4. Phase Status

### Phase 1–4: Planning, contracts, DB — ✅ DONE

Completed:
- Locked product scope and MVP boundaries
- Locked stack and repo structure
- PostgreSQL schema/migrations V1–V7
- Seed data
- API conventions
- API contracts Chunk 1–4
- OpenAPI contract

### Phase 5: Frontend Visual Prototype — ✅ DONE

Originally "Claude Design Visual Lock."

Actual output:
- React + TypeScript + Vite frontend prototype
- Mock/static screens for:
  - login
  - store selection
  - dashboard
  - new order modal
  - order workspace
  - customer
  - products/charges
  - details of sale
  - notes/photos
  - payments
  - invoice

Status:
- Merged to `main`
- Mock data only at the time
- Real API wiring later completed in Phase 9 for the Chunk 1 auth/dashboard flow

### Phase 6: Frontend/Backend Handoff Doc — ✅ DONE

Output:
- `docs/Phase-6-Frontend-Backend-Handoff.md`

Captures:
- mock limitations
- hardcoded values
- frontend/backend field mapping risks
- domain rules to preserve
- API integration warnings

### Phase 7: CI Setup — ✅ DONE

Merged in PR #18.

Includes:
- GitHub Actions CI
- frontend build job
- backend Maven verify job
- PostgreSQL 17 service
- locked migration protection for V1–V6

Done when:
- CI runs on PRs
- frontend build and backend verify are checked
- locked migrations are protected

### Phase 8: Backend Foundation + Chunk 1 — ✅ DONE

#### Phase 8.1 Backend Scaffold — ✅ DONE

Merged in PR #19.

Includes:
- `ApiResponse`
- `ErrorResponse`
- `ErrorCode`
- `ApiException` family
- `GlobalExceptionHandler`
- `Business` entity/repository
- `BusinessSlugResolver`
- global Jackson snake_case
- scaffold tests

#### Phase 8.2 Backend Auth — ✅ DONE

Merged in PR #20.

Implemented:
- `POST /api/v1/{slug}/auth/login`
- `POST /api/v1/{slug}/auth/select-store`
- `POST /api/v1/{slug}/auth/logout`

Key rules:
- server-side `HttpSession`
- cookie name `SP_SESSION`
- no JWT
- login uses `salesperson_code + password`
- business resolved from slug
- tenant/store access validated
- session fixation protection via session ID rotation
- logout invalidates session and clears cookie

#### Phase 8.3 Dashboard List — ✅ DONE

Merged in PR #22.

Implemented:
- `GET /api/v1/{slug}/orders`

Key rules:
- standard protected endpoint via `RequestContextGuard`
- strict business/store scoping
- paginated dashboard list
- status/week/search filters
- search includes mobile
- customer/install address nullable
- returns `gp` only
- does not return `gp_percent` or `gp_warning`

#### Phase 8.4 Status Patch — ✅ DONE

Merged in PR #23.

Implemented:
- `PATCH /api/v1/{slug}/orders/{orderId}/status`

Key rules:
- standard protected endpoint via `RequestContextGuard`
- order resource scoped by session business/store
- cross-store/cross-business/nonexistent order returns `ORDER_NOT_FOUND`
- any status can change to any other status
- `LAID` locks conceptually
- changing away from `LAID` unlocks conceptually
- same-status no-op returns 200 without changing `updated_at`
- row lock via `SELECT ... FOR UPDATE`
- real update explicitly sets `updated_at = now()`

#### Phase 8.5 Local-dev CORS Bridge — ✅ DONE

Merged in PR #24.

Purpose:
- Allow Vite frontend to call Spring backend with session cookies during local Phase 9 wiring.

Implemented:
- CORS wired through `SecurityFilterChain`
- scoped to `/api/v1/**`
- explicit local origins:
  - `http://localhost:5173`
  - `http://localhost:5174`
- `allowCredentials(true)`
- origins configured through `app.cors.allowed-origins`
- CSRF/auth/guard behavior unchanged

Tracked deployment follow-up:
- Production must override `app.cors.allowed-origins` with real frontend origin(s) in Phase 14.
- Do not rely on localhost origins in production deployment config.

### Phase 9: Frontend Wire Chunk 1 — ✅ DONE

Goal achieved:
Login → dashboard → status-update run on real backend for the Chunk 1 auth/dashboard flow. Store-select is implemented but not runtime-tested because no multi-store seed user exists yet.

#### Phase 9.1 Frontend API Client Foundation — ✅ DONE

Merged in PR #25.

Implemented:
- `frontend/src/lib/api/` foundation: `config.ts`, `types.ts`, `ApiError.ts`, `client.ts`, `paths.ts`, `index.ts`
- native fetch-based request helper with `credentials: 'include'`
- typed success / collection / error response wrappers
- `ApiError` class normalizing backend error shape
- `apiPath(slug, path)` URL builder
- `VITE_API_BASE_URL` config with local fallback
- Codex-driven hardenings:
  - network failures wrapped as `ApiError` (status 0)
  - response body read failures wrapped as `ApiError`
  - raw `BodyInit` bodies (FormData, Blob, URLSearchParams, etc.) pass through untouched
  - invalid JSON on 2xx surfaces as `ApiError`
  - guarded `ReadableStream` check

#### Phase 9.2 Frontend Auth Wiring — ✅ DONE

Merged in PR #26.

Implemented:
- `frontend/src/lib/tenant.ts` with centralized `DEFAULT_BUSINESS_SLUG = 'aussie-floors-group'`
- `frontend/src/lib/api/authApi.ts` typed login / select-store / logout wrappers
- `auth.tsx` rewired off mock to real backend
- single-store login derives `activeStore` from `active_store_id`
- multi-store path implemented but not runtime-tested — no multi-store seed user yet
- async `selectStore` + `logout`, 409 `STORE_ALREADY_SELECTED` surfaces backend message
- logout clears local React state on both success and failure
- single-store login smoke-tested end-to-end (200 OK)

#### Phase 9.3 Frontend Dashboard Wiring + Status Patch — ✅ DONE

Merged in PR #32.

Implemented:
- `frontend/src/lib/api/ordersApi.ts` with `DashboardOrderRow`, `OrderStatusUpdateResponse`, `fetchDashboardOrders()`, `updateOrderStatus()`
- `frontend/src/lib/api/ordersAdapter.ts` mapping backend rows to frontend display shape
- adapter rules:
  - `customer null → "No customer yet"`, else `"first_name last_name"`
  - `install_address null → "No install address"`, else `"{unit?}/{street_number} {street}, {suburb} {state_code} {postcode}"`
  - `week = "{week_number} / {week_year}"`
  - `gp null → "—"`, else `"$N.NN"`
  - `last_emailed_at null → "Not emailed"`, else `"DD Month YYYY"`
- removed `gp_percent` from `Order` type and dashboard render (Chunk 1 contract returns `gp` only)
- loading / error / empty states on dashboard
- status dropdown wired to real PATCH using `order_id`
- no optimistic update; failed PATCH keeps old status and shows error banner
- same-status PATCH short-circuited client-side
- Codex-driven hardening: pending status PATCHes tracked as a `Set<number>`, each row independently disabled while its own PATCH is in flight (prevents stale response overwrite races)
- end-to-end smoke test passed: login, dashboard load, status persist on refresh, failed PATCH does not fake-update UI, client-side search/filter works

Locked product decisions captured during Phase 9:
- Dashboard is store-level for MVP — anyone with access to a store sees that store's orders.
- No role-based visibility. Access-based only.
- Sales Portal vs Store Portal dashboard split is a future concern, not tracked as an issue.

Tracked follow-ups created as GitHub issues:
- #27 Replace hardcoded business slug with dynamic business selection
- #28 Add multi-store demo user and test store selection
- #29 Add CSRF protection before production
- #30 Configure production CORS origins
- #31 Move shared auth types out of `auth.tsx`

Known deferrals carried forward (not tracked as issues, accepted for MVP):
- Dashboard fetches page 1 at `page_size=100` and filters/searches client-side. Server-side search + pagination deferred until order volume requires it.

### Phase 10: Chunk 2 — Order Shell + Customer + Addresses + Details — ⏭ NEXT

Backend endpoints:
- `POST /orders`
- `GET /orders/{orderId}`
- `PUT /orders/{orderId}/customer`
- `PUT /orders/{orderId}/addresses/installation`
- `PUT /orders/{orderId}/addresses/billing`
- `POST /orders/{orderId}/addresses/billing/copy-from-installation`
- `PUT /orders/{orderId}/details-of-sale`

Frontend wiring:
- New order modal → real `POST /orders`
- Customer tab → real save
- Installation address → real save
- Billing address → real save
- Copy installation to billing → real endpoint
- Details of Sale → real save
- LAID lock blocks edits on protected mutation endpoints

Done when:
- salesperson can create an order shell
- fill customer/address/details
- save and reopen the order workspace

Note:
- Phase 10 introduces the first `SalesOrder` JPA entity (deliberately deferred from Chunk 1).

### Phase 11: Chunk 3 — Products, Charges, Lines, Notes, Photos

Backend endpoints:
- available products search
- available charges search
- get order lines + financial summary
- add/edit/delete product lines
- add/edit/delete charge lines
- sale price override
- reset price
- notes list/add
- attachments list/upload/delete/download

Frontend wiring:
- catalog search modals → real APIs
- product line CRUD → real APIs
- charge line CRUD → real APIs
- financial summary panel
- sale price override/reset
- GP display and GP warning in Details of Sale area, not Dashboard
- notes add/list
- photo upload/view/delete

Known frontend cleanup:
- Remove hardcoded "Required deposit: 40% of invoice total = $400.00" text.

Done when:
- full priced order with totals, GP, notes, and photos works end-to-end

### Phase 12: Chunk 4 — Invoices + Payments

Backend endpoints:
- create invoice v1
- rewrite invoice
- invoice history
- invoice detail
- invoice PDF download
- payment list
- record payment
- payment-driven invoice versioning

Frontend wiring:
- create invoice
- precondition errors
- rewrite invoice
- invoice version history
- PDF download
- record payment form
- balance due display

Known frontend cleanup:
- Replace hardcoded "Aussie Floors Group / Sydney CBD" invoice header with API-driven data.

Done when:
- salesperson can create/rewrite invoices, record payments, view invoice versions, and download PDFs

### Phase 13: Stripe

If slipping:
- cut to post-MVP
- or keep only simplest test-mode payment flow

Steps:
- write `docs/API-Contracts-Stripe-Addendum.md` first
- implement `POST /orders/{orderId}/payments/stripe-intent`
- implement `POST /stripe/webhook`
- webhook idempotency
- reuse payment-driven invoice version logic

Done when:
- Stripe test card creates payment + invoice version
- webhook retry does not duplicate payment/version

### Phase 14: Deployment

Target:
- single VM deployment is acceptable
- Docker Compose:
  - backend
  - frontend/nginx
  - PostgreSQL
- HTTPS via Let's Encrypt
- environment variables for secrets
- persistent DB volume
- basic DB backup

Deployment config requirements:
- production must set real `app.cors.allowed-origins` (issue #30)
- production must not rely on localhost CORS defaults
- cookie `secure=true` should be enabled for HTTPS production
- CSRF strategy must be decided before production if browser session-cookie auth remains (issue #29)
- secrets must not be committed

Done when:
- app is live on public URL
- full sale flow works
- DB survives restart

### Phase 15: Pilot / Hardening

Tasks:
- manually load initial product catalog/prices
- create real users
- run full end-to-end sale with real data
- fix bugs
- record feedback
- harden obvious gaps

Done when:
- one store can complete a full real sale without data loss

---

## 5. Tracked Deferrals / Follow-ups

These are known and accepted. They do not block the next phase.

### GitHub Issues

- #27 Replace hardcoded business slug with dynamic business selection
- #28 Add multi-store demo user and test store selection
- #29 Add CSRF protection before production
- #30 Configure production CORS origins
- #31 Move shared auth types out of `auth.tsx`

### Security / Production Hardening

- CSRF token strategy for browser session-cookie auth (issue #29)
  - currently deferred
  - required before production deployment if cross-site/browser session use remains
- Cookie `secure=true` per environment
  - required for HTTPS production
- CORS production origin config (issue #30)
  - production must override `app.cors.allowed-origins`
  - real frontend origin(s) must be configured during Phase 14
- Rate limiting for login
- Account lockout / brute-force mitigation
- Audit logging for important business actions

### Backend / Performance

- Dashboard `created_at DESC` index if dashboard performance needs it
- RequestContextGuard currently does multiple DB reads per standard-protected request
  - acceptable for MVP
  - can be optimized later if needed
- Dashboard count/list are not wrapped in read-only transaction
  - acceptable for MVP scale

### Frontend

- Dashboard fetches page 1 at `page_size=100` and filters client-side
  - acceptable while seed/early-pilot stores have few orders
  - server-side search + pagination to be added when order volume requires it
- Move shared auth types (`User`, `Store`) out of `auth.tsx` to break the type-only import cycle with `authApi.ts` (issue #31)
- Replace `DEFAULT_BUSINESS_SLUG` constant with dynamic business resolution (issue #27)

### Test / Build Hygiene

- Mockito Java-agent warning is non-blocking
- Can clean up later if it becomes noisy or CI-sensitive

---

## 6. AI Task Division Rules

### Backend

One small capability per branch.

Prefer one endpoint per branch unless splitting causes heavy duplicate setup.

Examples:
- auth endpoints were grouped because they shared session/auth setup
- dashboard list and status patch were separate branches
- CORS bridge was separate because it touched `SecurityConfig`

### Frontend

One screen group or flow per branch.

Phase 9 used:
- `feature/frontend-api-client`
- `feature/frontend-wire-auth`
- `feature/frontend-wire-dashboard` (list + status patch combined)

Adjust only if splitting becomes inefficient.

### Branch naming

- Backend tasks: `feature/backend-<capability>`
- Frontend tasks: `feature/frontend-<capability>`
- Docs tasks: `docs/<topic>`

### Merge rule

Only merge when:
- local build/tests pass
- CI passes
- diff reviewed
- business rule checked against locked docs
- Codex review is clean or consciously deferred
- no unrelated files changed

---

## 7. Immediate Next Step

Phase 10 — Chunk 2: Order Shell + Customer + Addresses + Details.

This is the next vertical slice: backend endpoints built and then wired to the frontend through small branches. It also introduces the first `SalesOrder` JPA entity.

Start by drafting the Phase 10 spec from `docs/API-Contracts-Chunk-2.md` and the existing mock frontend (`NewOrderModal`, order workspace, customer/address/details-of-sale screens).

Recommended branch order to be decided during spec drafting. Likely splits:
- backend order shell (`POST /orders`, `GET /orders/{orderId}`)
- backend customer + addresses
- backend details-of-sale
- frontend wiring per matching slice