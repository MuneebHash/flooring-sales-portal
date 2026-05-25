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

**Current next phase:**
- Phase 9: Frontend wire Chunk 1

**Not started:**
- Frontend wiring to real APIs
- Chunk 2–4 backend/frontend implementation
- Stripe
- Deployment
- Pilot/hardening

**Target:** Deployed MVP in 12–13 working days. Stretch goal, not a contract.

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
- Mock data only
- No real API wiring yet

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

### Phase 9: Frontend Wire Chunk 1 — ⏭ NEXT

**Estimate:** 0.5–1 day

Goal:
Wire the existing Phase 5 frontend prototype to real Chunk 1 backend APIs.

Backend already available:
- login
- select-store
- logout
- dashboard list
- status patch
- local-dev CORS

Frontend work:
- Add API client
- Add credentialed requests with cookies
- Replace mock auth with real auth calls
- Replace mock dashboard orders with real `GET /orders`
- Wire status dropdown to real `PATCH /orders/{orderId}/status`
- Add loading states
- Add error states
- Handle session loss by returning to login
- Keep Phase 5 UI structure unless a specific integration issue forces a small adjustment

Done when:
- login works against backend
- multi-store select works if needed
- dashboard loads real seeded orders
- status dropdown updates real backend status
- browser cookie/session flow works end-to-end

### Phase 10: Chunk 2 — Order Shell + Customer + Addresses + Details

**Estimate:** 3 days backend + frontend wiring

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

### Phase 11: Chunk 3 — Products, Charges, Lines, Notes, Photos

**Estimate:** 3.5 days backend + frontend wiring

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

**Estimate:** 3 days backend + frontend wiring

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

**Estimate:** 1.5 days hard cap

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

**Estimate:** 1–2 days

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
- production must set real `app.cors.allowed-origins`
- production must not rely on localhost CORS defaults
- cookie `secure=true` should be enabled for HTTPS production
- CSRF strategy must be decided before production if browser session-cookie auth remains
- secrets must not be committed

Done when:
- app is live on public URL
- full sale flow works
- DB survives restart

### Phase 15: Pilot / Hardening

**Estimate:** 1–2 days buffer

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

These are known and accepted. They do not block Phase 9.

### Security / Production Hardening

- CSRF token strategy for browser session-cookie auth
  - currently deferred
  - required before production deployment if cross-site/browser session use remains
- Cookie `secure=true` per environment
  - required for HTTPS production
- CORS production origin config
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

For Phase 9, likely branches:
- `feature/frontend-api-client`
- `feature/frontend-wire-auth`
- `feature/frontend-wire-dashboard`
- `feature/frontend-wire-status-patch`

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

## 7. Time Budget Checklist

| # | Phase | Estimate | Status |
|---|-------|----------|--------|
| 7 | CI Setup | 0.5–1 day | ✅ Done |
| 8 | Backend Chunk 1 + CORS bridge | 2–2.5 days | ✅ Done |
| 9 | Frontend wire Chunk 1 | 0.5–1 day | ⏭ Next |
| 10 | Backend Chunk 2 + frontend wire | 3 days | ⬜ |
| 11 | Backend Chunk 3 + frontend wire | 3.5 days | ⬜ |
| 12 | Backend Chunk 4 + frontend wire | 3 days | ⬜ |
| 13 | Stripe | 1.5 days hard cap | ⬜ |
| 14 | Deployment | 1–2 days | ⬜ |
| 15 | Pilot fixes | 1–2 days buffer | ⬜ |

**Original target:** 12–13 working days stretch.  
**Realistic target:** 14–17 working days.

---

## 8. Immediate Next Step

Phase 9 — Frontend wire Chunk 1.

Start by planning the first frontend integration branch.

Recommended first branch:

`feature/frontend-api-client`

Purpose:
- create API client foundation
- configure credentialed requests
- centralize API error handling
- prepare auth/dashboard wiring without changing too many screens at once

Do not start Chunk 2 backend until Phase 9 Chunk 1 frontend flow is working end-to-end.
