# Flooring Sales Portal — MVP Execution Plan

## 1. Current Status

**Completed:**
- DB design, migrations V1–V6, seed data
- API conventions, contracts (Chunk 1–4), OpenAPI (36 operations)
- Stack and repo structure locked
- Phase 5: real React/TypeScript frontend prototype (merged to `main`)
- Phase 6: frontend/backend handoff doc (`docs/Phase-6-Frontend-Backend-Handoff.md`)

**Not started:**
- CI setup
- All backend implementation
- Frontend wiring to real APIs
- Stripe, deployment, pilot

**Target:** Deployed MVP in 12–13 working days. Stretch goal, not a contract.

---

## 2. MVP Scope

**Included:**
auth, store selection, dashboard, create/open orders, customer + addresses, details of sale, products, charges, totals/GP, sale price override, notes, photos, invoices, invoice versions, PDF, payments, basic Stripe, container deploy.

**Excluded:**
Operations Portal, installer workflows, advanced quote comparison, AI features, refunds/disputes, finance products, surcharges, enterprise monitoring.

---

## 3. Tool Roles

- **ChatGPT / Claude (chat):** planning, audit, prompts, merge decisions. No repo edits.
- **Claude Code:** backend implementation, deep repo changes, integration tests, Docker.
- **Codex:** frontend wiring, React components, UI fixes, CI workflow.
- **Rule:** Claude Code and Codex never edit the same files at the same time.

---

## 4. Phase Status

### Phase 1–4: Planning, contracts, DB — ✅ DONE

### Phase 5: Frontend Visual Prototype — ✅ DONE
Originally "Claude Design Visual Lock." Actual output: full React/TypeScript prototype with all MVP screens, merged to `main`. Mock data only, no API wiring.

### Phase 6: Frontend/Backend Handoff Doc — ✅ DONE
Originally "UI Spec." Actual output: `docs/Phase-6-Frontend-Backend-Handoff.md` capturing mock limitations, field mapping risks, hardcoded values, and domain rules to preserve.

### Phase 7: CI Setup — ⏭ NEXT
**Estimate:** 0.5–1 day. Hard cap. Branch: `phase7-ci`.

GitHub Actions for:
- **Frontend:** `npm ci`, `npm run build` (TypeScript check runs inside the build step)
- **Backend:** Maven compile, Spring Boot tests
- **Migrations:** Flyway validation, if practical within the time cap
- **Locked file protection:** fail CI if any pushed commit modifies `backend/src/main/resources/db/migration/V1__*.sql` through `V6__*.sql`

**Done when:** CI runs on every PR, broken code fails CI, locked migrations V1–V6 are protected.

### Phase 8: Backend Chunk 1 — Auth + Dashboard
**Estimate:** 2–2.5 days

Endpoints:
- `POST /auth/login`
- `POST /auth/select-store`
- `POST /auth/logout`
- `GET /orders`
- `PATCH /orders/{orderId}/status`

Includes: Spring scaffold, session auth, BCrypt, slug resolution, tenant/store scoping, standard error wrapper, dashboard query, LAID lock flag.

**Done when:** all 5 endpoints work, tenant leakage tested, CI passes.

### Phase 9: Frontend Wire Chunk 1
**Estimate:** 0.5–1 day

- Add API client (axios + TanStack Query)
- Replace `mockAuth.ts` with real auth calls
- Replace `mockOrders.ts` with real dashboard data
- Wire status dropdown to real PATCH
- Add loading and error states

**Done when:** login → store select → dashboard → status update all work end-to-end with no mock data.

### Phase 10: Chunk 2 — Order Shell + Customer + Addresses + Details
**Estimate:** 3 days (backend + frontend wiring)

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
- Addresses (install + billing + copy) → real save
- Details of Sale → real save
- LAID lock blocks edits

**Done when:** salesperson can create order, fill customer/addresses/details, save and reopen.

### Phase 11: Chunk 3 — Products, Charges, Lines, Notes, Photos
**Estimate:** 3.5 days (heaviest chunk)

Backend endpoints:
- Available products + charges search
- Get order lines + financial summary
- Add/edit/delete product lines
- Add/edit/delete charge lines
- Sale price override + reset
- Notes list/add
- Attachments list/upload/delete/download

Frontend wiring:
- Catalog search modals → real APIs
- Product/charge line CRUD → real APIs
- LM/SQM conversion live
- Financial summary panel + GP warning
- Sale price override + reset
- **Remove "Required deposit: 40% of invoice total = $400.00" text**
- Notes + photo upload/view/delete

**Done when:** full priced order with totals, GP, notes, photos works end-to-end.

### Phase 12: Chunk 4 — Invoices + Payments
**Estimate:** 3 days

Backend endpoints:
- Create invoice (v1)
- Rewrite invoice (new version)
- Invoice history + detail
- Invoice PDF (Thymeleaf + OpenHTMLtoPDF)
- Payment list
- Add payment (auto-creates payment-driven invoice version)

Frontend wiring:
- Create invoice + precondition errors
- Rewrite invoice
- Invoice version history
- PDF download
- Record payment form
- Balance due display
- **Replace hardcoded "Aussie Floors Group / Sydney CBD" with API-driven invoice header**

**Done when:** salesperson can invoice, rewrite, record payments, see version history, download PDFs.

### Phase 13: Stripe
**Estimate:** 1.5 days hard cap. If slipping, cut to post-MVP or keep only the simplest test-mode payment flow.

- Write `docs/API-Contracts-Stripe-Addendum.md` first
- `POST /orders/{orderId}/payments/stripe-intent`
- `POST /stripe/webhook`
- Idempotency on webhook retries
- Reuse payment-driven invoice version logic

**Done when:** Stripe test card creates payment + invoice version; no duplicates on webhook retry.

### Phase 14: Deployment
**Estimate:** 1–2 days

- Single VM (Azure/AWS/Hetzner)
- Docker Compose: backend, frontend/nginx, PostgreSQL
- HTTPS via Let's Encrypt
- Environment variables for secrets
- Persistent DB volume + backup

**Done when:** app live on public URL, full sale flow works, DB persists across restart.

### Phase 15: Pilot / Hardening
**Estimate:** buffer / 1–2 days

- Manually load product catalog and prices
- Create real users
- End-to-end test with real data
- Fix bugs

**Done when:** one store can complete a full real sale without data loss.

---

## 5. AI Task Division Rules

### Backend (Claude Code)
**One small capability per branch. Prefer one endpoint per branch.**

Group endpoints onto one branch only when separating them duplicates significant setup. Examples:
- `POST /auth/login` and `POST /auth/logout` → separate branches
- `PUT /orders/{id}/addresses/installation` and `PUT /orders/{id}/addresses/billing` → may share one branch (same controller, same DTOs, same validation)

Reason: small branches reduce hallucination, make PRs easy to audit, and help me learn the backend properly.

### Frontend (Codex)
One tab or component wiring per branch.
Example: `feature/frontend-wire-customer`, `feature/frontend-wire-payments`.

### Branch naming
- Phase 7: `phase7-ci`
- Backend tasks: `feature/backend-<capability>` (e.g. `feature/backend-login`)
- Frontend tasks: `feature/frontend-<capability>` (e.g. `feature/frontend-wire-dashboard`)

### Merge rule
Only merge when:
- Code runs locally
- CI passes
- Diff reviewed
- Business rule checked against locked docs
- No locked files touched (V1–V6 migrations, API contracts, OpenAPI, conventions)

---

## 6. Time Budget Checklist

| # | Phase | Estimate | Status |
|---|-------|----------|--------|
| 7 | CI Setup | 0.5–1 day | ⏭ Next |
| 8 | Backend Chunk 1 (auth + dashboard) | 2–2.5 days | ⬜ |
| 9 | Frontend wire Chunk 1 | 0.5–1 day | ⬜ |
| 10 | Backend Chunk 2 + frontend wire | 3 days | ⬜ |
| 11 | Backend Chunk 3 + frontend wire | 3.5 days | ⬜ |
| 12 | Backend Chunk 4 + frontend wire | 3 days | ⬜ |
| 13 | Stripe | 1.5 days (hard cap) | ⬜ |
| 14 | Deployment | 1–2 days | ⬜ |
| 15 | Pilot fixes | 1–2 days buffer | ⬜ |

**Total target:** 12–13 working days (stretch). Realistic: 14–17.

---

## 7. Immediate Next Step

Phase 7 — CI Setup.
Branch: `phase7-ci`.
After CI green on `main`, start Phase 8 (Backend Chunk 1).