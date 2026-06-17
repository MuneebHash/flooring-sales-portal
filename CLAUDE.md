# Flooring Sales Portal — Project Memory

## What this is

Flooring Sales Portal is a vertical SaaS sales application for flooring stores.

It replaces the paper/manual flooring sales process with one digital workflow:

1. create/open sale order
2. capture customer and address details
3. add flooring products and charge/labour lines
4. calculate totals, costings, GP, and sale price
5. record notes and photos
6. create/rewrite invoice
7. capture customer signature and accept invoice
8. resend/email accepted invoice
9. record payments
10. keep the full sale record inside the portal

This is not a generic CRM. It is a specialised flooring sales workflow product.

The product is multi-tenant. Each customer business has its own business slug and isolated data.

---

## Critical workflow rules — read every session

- Never commit unless the user explicitly says `commit`.
- Never push unless the user explicitly says `push`.
- Always check the current branch before editing:

```bash
git branch --show-current
```

- `main` is protected for normal feature work. Use feature branches unless the user explicitly says they are making a tiny manual change on main.
- Keep scope tiny. Do exactly the requested task.
- Do not freelance future features into the current task.
- Do not modify `backend/`, `docs/`, migrations, or `openapi.yaml` unless explicitly asked.
- Run the correct build/test command before claiming done.
- Report changed files, validation result, and any risk clearly.
- Do not say “done” if build/test is broken.

---

## Current build status

The app is no longer just a mock frontend. It is wired to the Spring Boot backend for the main MVP sales workflow.

Completed major capabilities:

- auth/login/logout/store selection
- dashboard order list and status update
- create order shell
- open order workspace
- customer save
- installation and billing address save
- details of sale autosave
- product search and product lines
- charge/labour lines
- financial summary, GP, sale price override/reset
- target GP price control
- notes
- photo upload/list/preview/delete
- invoice create/rewrite/view/download
- payment list and record payment
- invoice acceptance with customer signature
- accepted invoice resend/email state
- dynamic business slug routing from URL
- reserved app/system route words blocked as business slugs
- tenant slug validation before login + Business-Not-Found page
- per-tenant quick-add descriptions (authenticated)
- go-forward db/dev-seed workflow + MS1 multi-store demo user

Current phase:

```text
Phase 15 — Invoice & Payment Correctness (money / document trust layer).
See docs/Phases.md §11 for scope.
```

---

## Tech stack

Frontend:

- Vite
- React 19
- TypeScript
- Tailwind CSS v4
- React Router
- React Hook Form
- Zod
- TanStack Table
- shadcn-style local UI primitives

Backend:

- Spring Boot
- PostgreSQL 17
- Flyway
- server-side `HttpSession` auth
- cookie-based session auth
- no JWT

Local DB:

- Docker Postgres 17
- compose file: `infra/docker-compose.yml`
- database: `flooring_sales_portal`
- user: `flooring_user`
- password: `flooring_pass`

---

## Build / dev commands

Start local database:

```bash
cd /Users/muneebsmacbook/Desktop/flooring-sales-portal
docker compose -f infra/docker-compose.yml up -d
```

Start backend:

```bash
cd /Users/muneebsmacbook/Desktop/flooring-sales-portal/backend
./mvnw spring-boot:run
```

Start frontend:

```bash
cd /Users/muneebsmacbook/Desktop/flooring-sales-portal/frontend
npm run dev
```

Frontend build:

```bash
cd /Users/muneebsmacbook/Desktop/flooring-sales-portal/frontend
npm run build
```

Backend tests/build validation:

```bash
cd /Users/muneebsmacbook/Desktop/flooring-sales-portal/backend
./mvnw test
```

Important:

- Do not suggest `docker compose down -v` unless the user intentionally wants to wipe the local DB.
- `down -v` deletes the local Postgres volume and loses local data.
- Backend local test runs may show ~48 known failures from accumulated dirty local-DB state. CI fresh-DB is the authoritative gate. A clean local run is not required to proceed; compare against the known baseline, do not chase those failures.

---

## Routing and tenancy

Tenant app URLs use the business slug as the first path segment.

Valid route shape:

```text
/{business-slug}/login
/{business-slug}/select-store
/{business-slug}/dashboard
/{business-slug}/orders/new
/{business-slug}/orders/{orderId}
```

Examples:

```text
/aussie-floors-group/login
/aussie-floors-group/dashboard
/terralux/login
/terralux/orders/123
```

API calls use the same slug:

```text
/api/v1/{slug}/...
```

Marketing site:

```text
tradextack.com
```

Application site:

```text
floorxtack.com/{business-slug}
```

Bare `/` and reserved/no-slug app paths redirect to the marketing URL (external `window.location`, not React Router `<Navigate>`).

Backend is the source of truth for tenant security:

- business slug resolution
- reserved slug rejection
- session business validation
- store access validation
- order scoping

Frontend slug routing is UX/routing only, not the security boundary.

Login note: the slug is required at login (salesperson codes are unique only within a business), so login posts to `/api/v1/{slug}/auth/login`. At login the slug comes from the URL, not from session/auth state.

---

## Reserved business slugs

Backend V11 reserves app/system route words as invalid business slugs.

Current reserved list:

```text
admin
api
login
static
auth
health
dashboard
orders
select-store
assets
new
logout
account
settings
public
```

Frontend reserved slug guard (`RESERVED_BUSINESS_SLUGS` in `frontend/src/lib/tenant.ts`, via `isReservedBusinessSlug`) must mirror backend V11 `chk_business_slug_reserved`.

If this list changes in the backend migration/constraint, update the frontend reserved list too.

---

## Migration rules

Previously merged Flyway migrations are locked by CI. The migration added in the current PR is not added to the lock range until the next migration PR.

Current locked migrations are V1–V13 (CI guards the V1–V13 range). V14
(`V14__add_payment_void_fields.sql`, Phase 15D payment soft-void) exists on main from PR #85 but is
deliberately NOT yet in the CI locked-guard range. Add V14 to the guard
range in the NEXT migration PR. Do not write "V1–V14 locked" anywhere yet.

Do not edit old migration files.

Rule:

```text
Never edit V1–V13 (and V14 once committed).
Any schema change must be a new migration.
```

Customer onboarding seed scripts are not product migrations.

Do not create a Flyway migration for every customer/tenant.

### Go-forward seed rule (Phase 14D — locked)

```text
schema        = Flyway migration (db/migration, versioned, locked once committed)
demo/dev data = db/dev-seed (manual, idempotent, run by hand — NEVER auto-runs)
new tests     = self-seed required data; do NOT depend on the V4 legacy seed
```

Dev/demo seed scripts live in `backend/src/main/resources/db/dev-seed/` (a sibling of
`db/migration`). Flyway scans only `classpath:db/migration` and `spring.sql.init.mode=never`,
so dev-seed files never auto-apply. Run them MANUALLY after Flyway, in order:

```text
1. start Postgres
2. start backend (Flyway applies V1–V14)
3. psql -f db/dev-seed/quick_descriptions_demo.sql
4. psql -f db/dev-seed/multi_store_user_demo.sql
5. verify login / store-selection / quick-adds / products / charges
```

The production-safe schema-only baseline/squash (separating the V4 demo seed from the
schema) is deferred to Phase 16 / pre-deploy — not done in 14D. The legacy V4–V7 demo
data stays for now (locked + test-dependent).

---

## Locked domain values — never invent variants

### Order statuses

Use only:

```text
LEAD
NEW_ACHIEVED_SALE
FOLLOW_UP
ACCEPTED
LAID
CANCELLED
```

Do not invent statuses like:

```text
NEW
IN_PROGRESS
INVOICED
COMPLETED
WON
LOST
DRAFT
PAID
READY
```

Keep enum values as the source of truth in code. Render human labels in UI.

### Flooring types

```text
SOFT
HARD
```

### Order number format

```text
{store_code}.{salesperson_code}.{order_sequence_number_padded_5}
```

Example:

```text
SYD-CBD.LC1.00001
```

Do not use old pre-V6 codes like `LC01`.

### Salesperson code format

```text
two uppercase letters + one digit
example: LC1
```

### LM/SQM conversion

Default:

```text
1 LM = 3.66 SQM (carpet roll width)
```

A per-product `sqm_per_lm` factor exists (V8) and MUST be respected when available. Do not hardcode the 3.66 default in place of the per-product factor.

### LAID rule

When `order_status = LAID`, protected edits are locked.

Blocked when LAID:

- customer/address/details protected saves
- product/charge mutations
- sale price mutations
- attachment delete

Allowed when LAID where implemented:

- reads
- notes (append-only; notes are allowed when LAID)
- photo upload/list/preview
- status change from dashboard
- invoice/signature/payment flows where explicitly supported (accept/resend/signature-download allowed when LAID)
- payment **void** (Phase 15D) — LAID-allowed, the inverse of recording a payment

Backend returns 422 `ORDER_LOCKED` ("Order is laid and cannot be edited.") for blocked protected edits.

### Payment void rule (Phase 15D — backend PR1)

Removing a payment is a **SOFT VOID, never a hard delete**:

- `POST /api/v1/{slug}/orders/{orderId}/payments/{paymentTransactionId}/void` (201; no body). Use the
  word **void/voided** consistently — NOT "reverse/reversal" — in API/errors/docs.
- The original `payment_transaction` row is preserved; `V14` adds `voided_at` + `voided_by_user_id`
  (the **session actor**, not the order-bound salesperson; `voided_by_name` is exposed, the raw user id
  is not). The `amount > 0` CHECK is never weakened; no negative payment rows.
- **Active `total_paid` excludes voided payments** (`sumAmountByOrderId` filters `voided_at IS NULL`);
  **payment history (list/count) still includes voided rows** (do NOT filter the list).
- Void recalculates `total_paid`/`balance_due`, regenerates the current invoice version, carries
  acceptance/signature forward (customer does **not** re-sign), resets the email mirror to null.
- **Payment record AND payment void never auto-email.** Manual **Re-send Invoice** is the ONLY email
  action after a payment change (the old "re-email after a payment on an accepted invoice" is removed).
- New error codes: `PAYMENT_NOT_FOUND` (404), `PAYMENT_ALREADY_VOIDED` (409). Double-void is caught by
  the `WHERE voided_at IS NULL` update guard (0 rows → 409, no second invoice version).
- Backend PR1 only; the frontend void button (PaymentsTab) is PR2 — the workflow is not usable in-app
  until PR2 lands.

### GP rule

GP is MARGIN on sale price ex-GST: `(sale_ex - cost) / sale_ex`. NOT markup on cost.

Sale price override input is GST-INCLUSIVE.

GP warning threshold is around 10–15% (see `DetailsOfSaleTab` thresholds: above ~15% reads healthy; the band below is warning/danger).

Target GP price control: the MERGED implementation applies a sale-price override using a backend-rounding-aware candidate cent-search so the DISPLAYED GP matches the typed target within 2 decimals (it simulates the backend HALF_UP rounding and picks the cent value whose recomputed GP hits the target). Do NOT replace this with the naive `total_cost / (1 - target_gp_rate)` formula — that under/overshoots at cent boundaries and was the exact bug Codex caught.

Negative `sale_price_ex_gst` persists through line CRUD; never clamped/rejected. Only invoice creation blocks a negative final sale price (DB CHECK).

Money is `BigDecimal` 2dp; >2dp input is rounded HALF_UP consistently across sibling endpoints. Costs are never exposed in the salesperson frontend; catalog search is cost-free.

---

## Current demo login data

Known seeded users usually include:

```text
LC1 / password123
SN1 / password123
JW1 / password123
EP1 / password123
OS1 / password123
MJ1 / password123
NB1 / password123
CT1 / password123
EL1 / password123
```

Do not use `LC01`.

Multi-store user `MS1` may exist only if manually inserted into the local DB. Check DB before relying on it.

---

## Frontend structure

Important frontend files:

```text
frontend/src/App.tsx
frontend/src/lib/tenant.ts
frontend/src/lib/useTenantSlug.ts
frontend/src/lib/auth.tsx
frontend/src/lib/api/
frontend/src/components/
frontend/src/components/workspace/
frontend/src/components/ui/
```

Routing lives mainly in:

```text
frontend/src/App.tsx
frontend/src/lib/tenant.ts
frontend/src/lib/useTenantSlug.ts
```

API wrappers live in:

```text
frontend/src/lib/api/
```

Note: a few download helpers (`fetchAttachmentBlob`, `fetchCurrentInvoiceSignature`) consume backend-returned download paths verbatim and must NOT be rewritten to build their own slug path.

Shared UI primitives live in:

```text
frontend/src/components/ui/
```

Do not invent a parallel design system.

---

## Current immediate task: Phase 15 — Invoice & Payment Correctness

Scope (see docs/Phases.md §11 for full detail):

- Fix invoice PDF generation to match the provided sample template.
- Render per-tenant invoice data ONCE on screen + PDF (logo, ABN, bank, T&Cs);
  remove the hardcoded Aussie Floors / sample ABN / sample TERMS from InvoiceTab.tsx.
- Kill automatic invoice email after a payment update (keep manual Resend).
- Payment void (soft void — paid drops, balance rises, stays accepted/signed,
  no re-sign, no auto-email; no hard delete). Backend PR1 DONE; frontend void button is PR2.
- Stripe payment-link button (opens tenant's external link, manual recording, no webhook).

Note: the old "Terralux" demo seed was created then removed. Real tenant onboarding is
handled by the db/dev-seed workflow, not a one-off Terralux script.

---

## MVP scope — out of scope for now

Do not start these unless explicitly requested:

- full Operations Portal
- Store Portal/dashboard
- installer / laybook workflows
- advanced quote comparison
- room-level complexity
- AI features
- refunds beyond the Phase 15 payment void flow
- payment edit / hard delete (only soft void is in scope)
- finance products
- Stripe Connect/full payment gateway build
- major deployment work
- major frontend redesign (FloorxTack chrome is Phase 17)

Frontend revamp/polish (per-tenant chrome) is Phase 17, after Phase 15/16.

---

## Known deferred hardening

Important future work:

- CSRF protection before production if browser session-cookie auth remains
- production CORS origin config
- `cookie secure=true` for HTTPS
- production email provider
- database backup strategy
- S3/object storage for production uploads
- payment void/edit/delete workflow
- invoice version-history UI
- backend financial summary versioning for concurrent mutations
- backend version precondition on invoice accept

Known issues/follow-ups:

```text
#29 CSRF protection before production
#30 production CORS origins
#34 app/database timezone before production
#55 backend financial summary versioning for concurrent mutations
#69 backend version precondition on invoice accept
#74 per-tenant private config (ABN/bank/T&Cs/Stripe link/template) — CLOSED during Phase 15
#75 centralize backend auth enforcement (fail-closed) — Phase 16
```

---

## Design system rules

The UI should be:

- corporate
- compact
- clean
- professional
- iPad-friendly
- no bulky sidebar shell
- no marketing imagery inside the app
- no generic CRM feel

Preserve the existing flooring sales workflow. Do not remove working functionality for visual polish.

Order Workspace tab order:

1. Customer
2. Products & Charges
3. Details of Sale
4. Notes & Photos
5. Payments
6. Invoice

---

## Where the real rules live

For backend/API/business-rule questions, read the relevant source files directly. Do not guess.

Key files:

```text
docs/Phases.md
docs/API-Conventions.md
docs/API-Contracts-Chunk-1.md
docs/API-Contracts-Chunk-2.md
docs/API-Contracts-Chunk-3.md
docs/API-Contracts-Chunk-4.md
docs/API-Contracts-Phase13-Acceptance-Signature-Email.md
docs/openapi.yaml
backend/src/main/resources/db/migration/V*.sql
frontend/src/lib/api/
frontend/src/components/workspace/
```

If docs conflict with current code, inspect current code and ask before changing behavior.

---

## Reporting back when a task is done

Output in this order:

1. changed files, modified + new
2. what changed
3. build/test command run
4. build/test result
5. whether backend/migrations/docs were touched
6. whether commit/push was done
7. any risks or manual checks needed

Do not commit. Wait for the user to say commit.