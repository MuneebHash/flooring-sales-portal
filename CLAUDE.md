# Flooring Sales Portal — Project Memory

Operational memory for implementation sessions. `docs/Phases.md` holds strategy/roadmap; **this file holds how to work and the rules that must not break.**

> If this file conflicts with `docs/Phases.md` or with the repo, **inspect the live code on `main` and trust it** — verify, don't blindly trust either doc. Ask before changing behaviour on a conflict.

---

## What this is

Flooring Sales Portal is a vertical SaaS sales application for flooring stores. It replaces the paper/manual flooring sales process with one digital workflow:

1. create/open sale order
2. capture customer + address details
3. add flooring products and charge/labour lines
4. calculate totals, costings, GP, sale price
5. record notes and photos
6. create/send quotation where needed
7. create/rewrite invoice after customer proceeds
8. capture customer signature and accept invoice
9. resend/email accepted invoice
10. record (and void) payments
11. keep the full sale record inside the portal

Not a generic CRM — a specialised flooring sales workflow product. Multi-tenant: each business has its own slug and isolated data.

---

## Critical workflow rules — read every session

- Never commit unless the user explicitly says `commit`. Never push unless the user explicitly says `push`.
- Always check the current branch before editing: `git branch --show-current`.
- `main` is protected for feature work — use feature branches unless the user says they're making a tiny manual doc/config edit on main.
- Keep scope tiny. Do exactly the requested task. Do not freelance future features into it.
- Do not modify `backend/`, `docs/`, migrations, or `openapi.yaml` unless explicitly asked.
- Run the correct build/test command before claiming done. Report changed files, validation result, and any risk. Never say "done" if build/test is broken.
- For serious verify/implementation/review work, Claude Code must create a Desktop `.md` audit/report file (e.g. `/Users/muneebsmacbook/Desktop/<task>-audit.md`) — never inside the repo.
- For major PRs after Phase 15F, run a read-only local Claude Code audit BEFORE PR/Codex review, focused on lifecycle bugs, async callbacks, stale state, autosave races, tenancy scoping, validation ordering, docs/OpenAPI parity, and regression risk.

The user controls all approvals, commits, pushes, merges, and all product/UI decisions. Claude Code may run build/test/terminal commands during implementation, but never commits, pushes, or creates audit files inside the repo.

---

## Status

```text
Phase 15 (Invoice & Payment correctness + Lead Enquiry, 15A–15F) is COMPLETE — see docs/Phases.md §4.
Phase 16A (invoice presentation foundation) is COMPLETE on main:
  - PR1 (#89): Invoice TAB screen redesign (CarpetCall-style); screen logo is an <img> fail-soft
    to business-name text; store/ABN/bank/flooring-type/salesperson removed from the SCREEN.
  - PR2 (#90): demo PDF logo path — business.logo_path seeded as '/uploads/1/branding/logo.png';
    backend PDF renders a real PNG via FileStorageService local storage (no upload UI / S3 yet).
  - PR3 (#91): backend invoice PDF redesigned to custom "Aire Compact" layout; terms ALWAYS on
    page 2 (SOFT and HARD); footer renders exactly once with or without terms. Template-only.
  Phase 16A added NO migration (still V1–V15) and NO production Java in PR3.

Current focus: Phase 16E — Quote delivery planning / send quote by email/SMS. See docs/Phases.md §7/§9.

Phase 16B–16D-C quotation foundation is complete on main: quote contract, backend quote draft/workspace/preview PDF, frontend Quote tab, itemised quote editor, retained itemised rows, and acceptance-ready quotation PDF. Next scope must not mix delivery with remote acceptance: 16E is send/email/SMS + issued customer quote; 16F is public signing/accepted quote/create invoice from accepted quote.

Roadmap: 16 Quotation PDF · 17 Deploy & Hardening · 18 Revamp/app chrome · 19 Final audit gate
  (full roadmap in docs/Phases.md §7).
```

The app is wired to the Spring Boot backend across the full MVP sales workflow: auth/login/logout/store-selection; dashboard order list + status update; create order shell; order workspace; customer + address save; details-of-sale autosave; product search + product lines; charge/labour lines; financial summary, GP, sale-price override/reset, target-GP price control; notes; photo upload/list/preview/delete; invoice create/rewrite/view/download; payment list/record/**void**; invoice acceptance with customer signature; resend/email state; per-tenant invoice on screen + PDF; per-flooring-type terms; PaymentsTab Stripe-link + bank-transfer helpers; **Lead Enquiry form (one-per-order order_enquiry) in the Customer tab**; dynamic business-slug routing; reserved-slug blocking; slug validation + Business-Not-Found; per-tenant quick-adds; go-forward db/dev-seed workflow + MS1 multi-store demo user.

---

## Tech stack

```text
Frontend:  Vite · React 19 · TypeScript · Tailwind v4 · React Router ·
           React Hook Form · Zod · TanStack Table · shadcn-style local UI primitives
Backend:   Spring Boot · PostgreSQL 17 · Flyway · server-side HttpSession (cookie) auth · NO JWT
Local DB:  Docker Postgres 17 (infra/docker-compose.yml) —
           db flooring_sales_portal · user flooring_user · password flooring_pass
```

---

## Build / dev commands

```bash
# local DB
cd /Users/muneebsmacbook/Desktop/flooring-sales-portal && docker compose -f infra/docker-compose.yml up -d
# backend (Flyway applies V1–V15 on boot)
cd /Users/muneebsmacbook/Desktop/flooring-sales-portal/backend && ./mvnw spring-boot:run
# frontend
cd /Users/muneebsmacbook/Desktop/flooring-sales-portal/frontend && npm run dev

# validation
cd frontend && npm run build       # frontend changes
cd backend && ./mvnw test          # backend changes
```

```text
- Do NOT suggest `docker compose down -v` unless the user intentionally wants to wipe the local DB
  (it deletes the Postgres volume).
- Backend local tests may show a varying number of known failures (observed ~48–108) from
  accumulated dirty local-DB state. CI fresh-DB is the authoritative gate. Compare against the
  known baseline; do not chase these. A fresh DB is needed for a clean local full-suite run.
```

---

## Routing and tenancy

Tenant app URLs use the business slug as the first path segment; API calls use the same slug.

```text
/{business-slug}/login · /select-store · /dashboard · /orders/new · /orders/{orderId}
API:  /api/v1/{slug}/...
Application site:  floorxtack.com/{business-slug}      Marketing site:  tradextack.com
```

- Bare `/` and reserved/no-slug app paths redirect to the marketing URL (external `window.location`, NOT React Router `<Navigate>`).
- Login posts to `/api/v1/{slug}/auth/login` — salesperson codes are unique only within a business, so the slug is required at login and comes from the URL, not session state.
- Use the `apiPath(slug, …)` helper for tenant-scoped endpoints; bypass it only for genuinely public endpoints.
- Backend is the source of truth for tenant security (slug resolution, reserved-slug rejection, session business validation, store access, order scoping). Frontend slug routing is UX/routing only — **not** the security boundary. Cross-business/cross-store misses return 404, never 403 (do not leak existence).

---

## Reserved business slugs

Backend `V11` reserves app/system route words as invalid slugs:

```text
admin · api · login · static · auth · health · dashboard · orders ·
select-store · assets · new · logout · account · settings · public
```

The frontend guard (`RESERVED_BUSINESS_SLUGS` in `frontend/src/lib/tenant.ts`, via `isReservedBusinessSlug`) MUST mirror backend `V11 chk_business_slug_reserved`. If the backend list changes, update the frontend list too.

---

## Migration rules

```text
Current migrations are V1–V15:
  V1–V7 base · V8 LM/SQM factor · V9 negative-price constraint · V10 invoice accept/signature/email
  V11 reserved slug words · V12 per-tenant branding/invoice-legal/quick-add · V13 per-type terms
  V14 payment void fields (voided_at + voided_by_user_id) · V15 order_enquiry (Lead Enquiry form)

Never edit any committed migration. Any schema change is a NEW migration.
Phase 16A added NO migration (invoice tab + PDF logo path + Aire Compact PDF were app/template only).
CI "Locked migration protection" guards V1–V13 only (latest-known). V14 and V15 are on main but
  NOT yet locked unless live CI says otherwise. Any migration/CI work before the Phase 17 squash must
  explicitly account for V14/V15. Do not casually expand or rewrite the guard if the Phase 17
  squash/baseline will supersede it.
The Phase 17 schema-only squash/baseline must collapse ALL committed pre-production migrations —
  including V1–V15 and any Phase 16 quotation migrations — into one clean baseline and re-lock it in CI.
Do NOT create a Flyway migration per customer/tenant — onboarding seeds are not product migrations.
```

### Go-forward seed rule (Phase 14D — locked)

```text
schema        = Flyway migration (db/migration, versioned, locked once committed)
demo/dev data = db/dev-seed (manual, idempotent, run by hand — NEVER auto-runs)
new tests     = self-seed required data; do NOT depend on the V4 legacy seed
```

Dev-seed scripts live in `backend/src/main/resources/db/dev-seed/` (sibling of `db/migration`). Flyway scans only `classpath:db/migration` and `spring.sql.init.mode=never`, so dev-seed files never auto-apply. Run them MANUALLY after Flyway, in order:

```text
1. start Postgres
2. start backend (Flyway applies V1–V15)
3. psql -f db/dev-seed/quick_descriptions_demo.sql
4. psql -f db/dev-seed/multi_store_user_demo.sql
5. psql -f db/dev-seed/payment_helpers_demo.sql      # bank + Stripe-link demo data (Phase 15E)
6. psql -f db/dev-seed/terms_demo.sql                # demo hard/soft invoice terms
7. psql -f db/dev-seed/branding_demo.sql             # demo logo_path '/uploads/1/branding/logo.png' (Phase 16A)
8. bash db/dev-seed/copy_demo_logo.sh                # copy PNG into local storage so the backend PDF logo renders
9. verify login / store-selection / quick-adds / products / charges / payment helpers / terms / invoice PDF logo
```

The production-safe schema-only baseline/squash (separating the V4 demo seed from schema) is deferred to Phase 17 / pre-deploy. Legacy V4–V7 demo data stays for now (locked + test-dependent).

---

## Locked domain values — never invent variants

### Order statuses

```text
LEAD · NEW_ACHIEVED_SALE · FOLLOW_UP · ACCEPTED · LAID · CANCELLED
```

Do NOT invent: `NEW`, `IN_PROGRESS`, `INVOICED`, `COMPLETED`, `WON`, `LOST`, `DRAFT`, `PAID`, `READY`. Keep enum values as the source of truth in code; render human labels in UI.

### Flooring types

```text
SOFT · HARD        (one order = one flooring type)
```

### Order number / salesperson code

```text
Order number:     {store_code}.{salesperson_code}.{order_seq_padded_5}   e.g. SYD-CBD.LC1.00001
Salesperson code: two uppercase letters + one digit, e.g. LC1            (not old pre-V6 LC01)
```

### LM/SQM conversion

Default `1 LM = 3.66 SQM` (carpet roll width). A per-product `sqm_per_lm` factor exists (`V8`) and MUST be respected when available — do not hardcode 3.66 over the per-product factor.

### LAID rule

When `order_status = LAID`, protected edits are locked. Backend returns 422 `ORDER_LOCKED` ("Order is laid and cannot be edited.").

```text
Blocked when LAID:  customer/address/details protected saves · product/charge mutations ·
                    sale-price mutations · attachment delete · Lead Enquiry write
Allowed when LAID:  reads · notes (append-only) · photo upload/list/preview ·
                    status change from dashboard · Lead Enquiry read ·
                    invoice/signature/payment flows where explicitly supported
                    (accept / resend / signature-download) · payment VOID (inverse of recording)
```

### Lead Enquiry (Phase 15F — shipped)

Order-specific enquiry captured inside the Customer tab. Schema: `order_enquiry` (V15), one row per order, `UNIQUE(order_id)`. This is order/job enquiry data, **not** customer identity — never stored on `order_customer`, never widens `sales_order`.

```text
- Embedded on the workspace GET (nullable data.enquiry). No standalone GET enquiry endpoint.
- PUT /api/v1/{slug}/orders/{orderId}/enquiry is a FULL-REPLACE upsert — the whole body is sent
  on every save (autosave and manual).
- Autosave: debounce + blur flush + unmount flush + single-flight + queue. Manual Save button kept.
- LAID: read allowed; write blocked with 422 ORDER_LOCKED.
- lead_type: FLOOR / PHONE / INTERNET / null (exact-case only). Invalid on an editable order ->
  400 VALIDATION_FAILED (field lead_type); on a LAID order the 422 ORDER_LOCKED fires first.
- carpet / hard_floor: independent booleans, decoupled from order flooring_type (never auto-filled).
- fully_installed / uplift / furniture / stairs: tri-state nullable booleans — NULL is "unanswered"
  and is NEVER coerced to false.
- subfloor_concrete / subfloor_timber / subfloor_tile: independent multi-select booleans.
```

### Payment void rule (Phase 15D — fully shipped, backend + frontend)

Removing a payment is a **SOFT VOID, never a hard delete**:

```text
- POST /api/v1/{slug}/orders/{orderId}/payments/{paymentTransactionId}/void  (201; no body).
  Use the word VOID/VOIDED consistently — never "reverse/reversal" — in API/errors/docs/UI.
- Original payment_transaction row is preserved; V14 adds voided_at + voided_by_user_id
  (the SESSION ACTOR, not the order-bound salesperson; voided_by_name is exposed, raw user id is not).
  amount > 0 CHECK never weakened; no negative payment rows.
- Active total_paid EXCLUDES voided payments (sumAmountByOrderId filters voided_at IS NULL);
  payment history list/count STILL INCLUDES voided rows — do NOT filter the list.
- Void recalculates total_paid/balance_due, regenerates the current invoice version, carries
  acceptance/signature forward (customer does NOT re-sign), resets the email mirror to null.
- Payment record AND payment void NEVER auto-email. Manual Re-send Invoice is the ONLY email
  action after a payment change.
- Errors: PAYMENT_NOT_FOUND (404), PAYMENT_ALREADY_VOIDED (409). Double-void caught by the
  WHERE voided_at IS NULL update guard (0 rows -> 409, no second invoice version).
- Frontend void button + confirm modal + voided-row rendering shipped (PR2). Usable in-app.
```

### GP rule

```text
GP is MARGIN on sale price ex-GST: (sale_ex - cost) / sale_ex.  NOT markup on cost.
Sale-price override input is GST-INCLUSIVE. Warning band ~10–15% (DetailsOfSaleTab: above ~15% healthy).
Target-GP price control uses a backend-rounding-aware candidate cent-search so the DISPLAYED GP matches
  the typed target within 2dp (simulates backend HALF_UP, picks the cent value whose recomputed GP hits
  target). Do NOT replace with naive total_cost / (1 - target_gp_rate) — it under/overshoots at cent
  boundaries (the exact bug Codex caught).
Negative sale_price_ex_gst persists through line CRUD (never clamped); only invoice creation blocks a
  negative FINAL sale price (DB CHECK). Money is BigDecimal 2dp; >2dp rounded HALF_UP across sibling endpoints.
Costs are NEVER exposed in the salesperson frontend; catalog search is cost-free.
```

---

## Operational invariants — do not break

```text
- Auth model: HttpSession only (no JWT, no Spring principal). Spring Security is
  anyRequest().permitAll(); protection is enforced via RequestContextGuard.requireStandardProtected.
  Do NOT touch this model or add a Spring principal.
- Client must NEVER send cost_snapshot fields; line cost snapshots are SERVER-created.
- Download helpers (fetchAttachmentBlob, fetchCurrentInvoiceSignature) consume backend-returned
  download paths VERBATIM — do NOT rewrite them to build their own slug path.
- Invoice table is APPEND-ONLY: Create / Rewrite / Payment / Void each INSERT a new version;
  current invoice = max(version_number). last_emailed_at is the only in-place update.
- Terms are FROZEN at acceptance; changing business terms needs a NEW invoice + re-sign (do not re-raise).
- Rewrite-after-accept CLEARS acceptance/signature by design (new customer-facing invoice; re-accept).
- Blank per-type terms display is CORRECT (terms_hard/terms_soft nullable; no backfill).
- Logo is fail-soft to business-name TEXT on BOTH surfaces (screen <img> and backend PDF data URI).
  Dev/demo uses a seeded logo_path '/uploads/1/branding/logo.png' + copy_demo_logo.sh local copy;
  there is no logo upload UI / pipeline yet (prod upload/serving/storage deferred to Phase 17).
- Invoice PDF style is custom "Aire Compact" (PR3): terms ALWAYS render on page 2 (SOFT and HARD),
  page 1 never shows a terms block, footer renders exactly once with or without terms. Do NOT clone
  the CarpetCall PDF (tried, disliked, reverted) — it was used only for content order.
- openhtmltopdf (1.0.10) is XML-strict and CSS-limited: tables/conservative CSS only — no flexbox/grid,
  no true CSS multi-column (column-count is parsed but ignored). Use literal chars or numeric entities
  (e.g. &#183;), NOT named HTML entities (&middot; / &nbsp; / &hellip;). Old stored PDFs do NOT
  auto-update on template change — rewrite/regenerate to see changes.
- Quick-adds: DetailsOfSaleTab reads the tenant list; EMPTY if none — no hardcoded fallback.
- Accepted customer name comes from the SAVED customer record — not typed at accept time.
- Never leave follow-ups untracked — file a GitHub issue (the PR #71 lesson).
- business_quick_description column is `description`, NOT `text` (Postgres type-name footgun);
  its FK to business has no ON DELETE — wipe child rows before the business row.
```

---

## Current demo login data

```text
Aussie Floors Group (business 1) + Premier Flooring Co (business 2):
  LC1 · SN1 · JW1 · EP1 · OS1 · MJ1 · NB1 · CT1 · EL1   (all password123)   — not old LC01.
MS1 multi-store user exists only if manually inserted locally — check the DB before relying on it.
Demo bank/Stripe + hard/soft terms appear only after running payment_helpers_demo.sql + terms_demo.sql.
Demo invoice-PDF logo appears only after running branding_demo.sql + copy_demo_logo.sh (Phase 16A).
```

---

## Frontend structure

```text
Routing:   frontend/src/App.tsx · frontend/src/lib/tenant.ts · frontend/src/lib/useTenantSlug.ts
Auth:      frontend/src/lib/auth.tsx
API:       frontend/src/lib/api/         (wrappers; use apiPath(slug, …) for tenant-scoped)
UI:        frontend/src/components/ui/   (shared primitives — do NOT invent a parallel design system)
Workspace: frontend/src/components/workspace/
```

---

## Design system rules

UI must be: corporate, compact, clean, professional, iPad-friendly. No bulky sidebar shell, no marketing imagery inside the app, no generic-CRM feel. Preserve the existing workflow — do not remove working functionality for visual polish.

Order Workspace tab order:

```text
1. Customer  2. Products & Charges  3. Details of Sale  4. Notes & Photos  5. Payments  6. Invoice
```

---

## Out of scope (do not start unless explicitly requested)

```text
full Operations Portal · Store Portal/dashboard · installer/laybook workflows ·
advanced quote comparison · room-level complexity · AI features ·
payment edit / hard delete (only soft void is in scope) · refunds · finance products ·
Stripe Connect / full payment-gateway build / webhooks ·
major frontend redesign / FloorxTack chrome (Phase 18) · invoice version-history UI ·
tenant logo upload UI / S3 serving (Phase 17) · configurable per-store guarantee text above terms
```

---

## Open / deferred issues

```text
#75  centralize backend auth enforcement (fail-closed) before production   -> Phase 17
#29  CSRF protection before production                                     -> Phase 17
#30  production CORS origins                                               -> Phase 17
#34  app/database timezone before production                              -> Phase 17
#55  financial summary versioning (concurrent mutations)                  -> deferred-hardening (post-pilot)
#69  backend version precondition on invoice accept                       -> deferred-hardening (post-pilot)
```

GitHub issue labels may still read `phase-16` for #29/#30/#34/#75 — these are Phase 17 now; update the labels when convenient. Docs are authoritative for phase numbering.

Other deferred-hardening (not all ticketed): secure cookies for HTTPS, production email provider (SES), DB backup strategy, S3/object storage for production uploads.

---

## Where the real rules live

For backend/API/business-rule questions, read the source directly — do not guess. If docs conflict with code, inspect the code and ask before changing behaviour.

```text
docs/Phases.md
docs/API-Conventions.md
docs/API-Contracts-Chunk-1.md … Chunk-4.md
docs/API-Contracts-Phase13-Acceptance-Signature-Email.md
docs/openapi.yaml
backend/src/main/resources/db/migration/V*.sql
backend/src/main/resources/db/dev-seed/
frontend/src/lib/api/
frontend/src/components/workspace/
```

---

## Reporting back when a task is done

```text
1. changed files (modified + new)
2. what changed
3. build/test command run
4. build/test result
5. whether backend/migrations/docs were touched
6. whether commit/push was done
7. any risks or manual checks needed
```