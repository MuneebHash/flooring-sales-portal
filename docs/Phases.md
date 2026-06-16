# Flooring Sales Portal — Current Build Plan

## 1. Purpose of this file

This file is the short working context for Claude Code / implementation sessions.

It should answer:

* what the product is
* what is already built
* what rules must not be broken
* what the next task is
* what is deferred

Do not turn this file into a full PR diary. Detailed old PR history belongs in GitHub, not here.

---

## 2. Product summary

Flooring Sales Portal is a vertical SaaS sales application for flooring stores.

It replaces the paper/manual flooring sales process with one digital workflow:

1. salesperson starts a sale/order
2. customer details are captured
3. products and charges are added
4. pricing/costing/GP are calculated
5. notes and photos are recorded
6. invoice is created
7. customer accepts/signs
8. payment is recorded
9. the full sale record stays in the portal

This is not a generic CRM.

The product is multi-tenant. Each customer business has its own business slug and isolated data.

Example tenant URLs:

```text
/flooring-business-slug/login
/flooring-business-slug/dashboard
/flooring-business-slug/select-store
/flooring-business-slug/orders/new
/flooring-business-slug/orders/{orderId}
```

Marketing site:

```text
tradextack.com
```

Application site:

```text
floorxtack.com/{business-slug}
```

---

## 3. Current repo status

Current main state after PR #79 (docs commit 99f0148):

```text
main includes the full Phase 14 multi-tenant foundation
main includes dynamic business slug routing + V11 reserved-slug migration
main includes V12 per-tenant branding / invoice-legal / quick-add schema
main includes tenant slug validation + Business-Not-Found + per-tenant quick-adds
main includes the db/dev-seed workflow + MS1 multi-store demo user
main includes invoice acceptance/signature/email + target GP price control
```

Latest important completed work:

```text
Phase 14A–14D — tenant data model, login validation, quick-adds, dev-seed (PRs #76–#79)
V12 — per-tenant branding + invoice-legal fields + business_quick_description table
Phase 13 — invoice acceptance/signature/resend/email UI and backend
Target GP price control — sale price from target GP%
```

Tenant data note:

```text
A local "Terralux" demo seed was created and then removed.
It contained placeholder (non-real) catalog data and is gone from the local DB.
Real Terralux onboarding is handled by the Phase 14 real-tenant seed workflow,
using the partner's real business/store/user/catalog/invoice-legal data.
```

Current task:

```text
Phase 15 — Invoice & Payment Correctness (money / document trust layer). See §11.
```

(Phase 14A–14D complete — see the roadmap in §11.)

---

## 4. Stack

Frontend:

```text
Vite
React 19
TypeScript
Tailwind v4
React Router
React Hook Form
Zod
TanStack Table
shadcn-style local UI primitives
```

Backend:

```text
Spring Boot
PostgreSQL 17
Flyway migrations
HttpSession auth
```

Local DB:

```text
Docker Postgres 17
compose file: infra/docker-compose.yml
database: flooring_sales_portal
user: flooring_user
password: flooring_pass
```

---

## 5. Critical workflow rules

Claude Code must follow these unless the user explicitly says otherwise:

```text
Do not commit unless the user explicitly says commit.
Do not push unless the user explicitly says push.
Check the current branch before editing.
Keep scope tiny.
Do not freelance future features into current tasks.
Do not edit backend/migrations/docs/openapi unless the task explicitly requires it.
Run the correct build/test command before saying done.
Report exact changed files and validation result.
```

Main is protected for normal feature work. The user may occasionally make tiny manual doc/config edits directly on main. Do not assume permission to do that.

For frontend changes:

```bash
cd /Users/muneebsmacbook/Desktop/flooring-sales-portal/frontend
npm run build
```

For backend changes:

```bash
cd /Users/muneebsmacbook/Desktop/flooring-sales-portal/backend
./mvnw test
```

Local app startup:

Terminal 1:

```bash
cd /Users/muneebsmacbook/Desktop/flooring-sales-portal
docker compose -f infra/docker-compose.yml up -d
```

Terminal 2:

```bash
cd /Users/muneebsmacbook/Desktop/flooring-sales-portal/backend
./mvnw spring-boot:run
```

Terminal 3:

```bash
cd /Users/muneebsmacbook/Desktop/flooring-sales-portal/frontend
npm run dev
```

Never suggest `docker compose down -v` unless the user intentionally wants to wipe the local DB.

---

## 6. Completed build summary

### Phase 1–4 — Planning, schema, contracts — DONE

Completed:

```text
product scope
MVP boundaries
database schema
Flyway migrations
seed data
API conventions
API contract chunks
OpenAPI contract
```

### Phase 5 — Frontend visual prototype — DONE

The original mock frontend was created and merged.

Historical only now. The app is no longer just a mock frontend.

### Phase 6 — Frontend/backend handoff — DONE / HISTORICAL

File:

```text
docs/Phase-6-Frontend-Backend-Handoff.md
```

This file is historical. It captured mock limitations from the old prototype stage.

Do not treat “frontend has no backend/API integration” statements from that handoff as current truth.

### Phase 7 — CI — DONE

GitHub Actions CI exists for frontend build, backend Maven validation, Postgres service, and migration protection.

### Phase 8 — Backend foundation + auth/dashboard/status — DONE

Implemented:

```text
POST /api/v1/{slug}/auth/login
POST /api/v1/{slug}/auth/select-store
POST /api/v1/{slug}/auth/logout
GET  /api/v1/{slug}/orders
PATCH /api/v1/{slug}/orders/{orderId}/status
```

Auth rules:

```text
server-side HttpSession
cookie auth
no JWT
business resolved from URL slug
salesperson_code + password login
tenant/store access validated
```

### Phase 9 — Frontend auth/dashboard wiring — DONE

Implemented:

```text
real API client
real login/logout/store-selection
real dashboard list
real status patch
dynamic slug routing from URL
```

Current routing rule:

```text
/:slug/login
/:slug/select-store
/:slug/dashboard
/:slug/orders/new
/:slug/orders/:orderId
```

No-slug or reserved top-level routes redirect to marketing.

### Phase 10 — Order shell, customer, addresses, details — DONE

Implemented:

```text
create order
open workspace
customer save
installation address save
billing address save
copy billing from installation
details of sale save/autosave
LAID lock protection
```

### Phase 11 — Products, charges, notes, photos, sale price/GP — DONE

Implemented:

```text
catalog search
product lines
charge lines
financial summary
sale price override/reset
GP/GP% display
details autosave
notes list/add
photo upload/list/preview/delete
large photo preview modal
```

Important rules:

```text
costs are visible only in controlled salesperson UI areas
line cost snapshots are server-created
client must not send cost_snapshot fields
JPG/PNG/WEBP only for MVP photo upload
HEIC conversion deferred
camera capture deferred
```

### Phase 12 — Invoices + payments — DONE

Implemented:

```text
create current invoice
rewrite/regenerate current invoice
view current invoice
download current invoice PDF
record payment
payment list
balance due display
```

### Phase 13 — Invoice acceptance/signature/email — DONE

Implemented:

```text
accept current invoice with customer signature
store accepted_at
store accepted_customer_name
store accepted signature file
generate signed PDF
re-send accepted invoice
show last_emailed_at
fetch signature image
dashboard invoice accepted indicator
```

Acceptance rule:

```text
customer does not type accepted name manually
accepted customer name comes from saved customer record
signature is captured separately
```

Email is currently MVP/stub/local depending on environment. Production email setup belongs to Phase 16.

### Issue #27 — Dynamic business slug routing — DONE

Completed in PR #71.

Frontend now derives the business slug from the first URL segment instead of hardcoding `aussie-floors-group`.

API calls now use `/api/v1/{slug}/...`.

Reserved top-level words redirect to marketing instead of being treated as tenant slugs.

Backend V11 reserves app/system words as invalid business slugs.

Reserved business slugs:

```text
admin api login static auth health dashboard orders
select-store assets new logout account settings public
```

Frontend reserved-slug guard must mirror backend V11 `chk_business_slug_reserved`.

Known gap from PR #71 (now tracked, fixed in Phase 14):

```text
Login page does not validate the slug before rendering, and the login + invoice
header still print a hardcoded "Aussie Floors Group". This was left untracked
after PR #71 — that must never happen again. Now scheduled in Phase 14.
```

---

## 7. Migration rules

All committed Flyway migrations are locked.

Do not edit old migration files.

Current migrations include:

```text
V1–V7 original schema/seed/auth/order setup
V8 per-product LM/SQM factor
V9 negative sale price constraint adjustment
V10 invoice acceptance/signature/email fields
V11 reserved app/system route words in business slug
V12 per-tenant branding + invoice-legal fields + business_quick_description table
```

Rule:

```text
Never edit V1–V12.
Any schema change must be a new migration.
```

Customer onboarding seed scripts are not product migrations.

Do not create a Flyway migration for every customer/tenant.

Go-forward seed rule (Phase 14D): schema = Flyway migration; demo/dev data =
`db/dev-seed` (manual, idempotent, never auto-runs); new tests self-seed required data
and must NOT depend on the V4 legacy seed.

Phase 14 note: production must start schema-only. The demo seed (V4 Aussie/Premier
data) must not run on the production database. This is a controlled change, done on
a branch with a verified fresh-DB-from-zero test — not by deleting old migrations.
The schema-only baseline/squash itself is deferred to Phase 16/pre-deploy; the legacy
V4–V7 demo data stays in the migration path for now (locked + backend-test-dependent).

---

## 8. Tenant / slug rules

Tenant URL shape:

```text
/{business-slug}/login
/{business-slug}/dashboard
/{business-slug}/select-store
/{business-slug}/orders/new
/{business-slug}/orders/{orderId}
```

The URL slug identifies the tenant namespace.

Security is still enforced by backend session/business/store checks.

A user changing the URL from one business slug to another must not gain access.

Backend is the source of truth for:

```text
valid business slug
reserved slug rejection
tenant isolation
store access
order scoping
```

Frontend reserved-slug guard is UX/routing only, not security.

---

## 9. Domain rules to preserve

Order statuses:

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
NEW IN_PROGRESS INVOICED COMPLETED WON LOST DRAFT PAID READY
```

Flooring types:

```text
SOFT
HARD
```

Order number format:

```text
{store_code}.{salesperson_code}.{order_sequence_number_padded_5}
```

Example:

```text
SYD-CBD.LC1.00001
```

Salesperson code format:

```text
two uppercase letters + one digit
example: LC1
```

LM/SQM conversion:

```text
Default 1 LM = 3.66 SQM (carpet roll width).
A per-product `sqm_per_lm` factor exists (V8) and MUST be respected where available.
Do not hardcode the 3.66 default in place of the per-product factor.
```

LAID rule:

```text
LAID orders are locked from protected edits.
Reads remain allowed.
Notes/photos upload/signature-related reads may remain allowed where explicitly implemented.
Status can still be changed from dashboard.
```

GP rule:

```text
GP is MARGIN on sale price ex-GST: (sale_ex - cost) / sale_ex. NOT markup on cost.
Sale price override input is GST-INCLUSIVE.
GP warning threshold is around 10–15% (see DetailsOfSaleTab thresholds:
above ~15% reads healthy; the band below is warning/danger).

Target GP price control: the MERGED implementation applies a sale-price override
using a backend-rounding-aware candidate cent-search so the DISPLAYED GP matches
the typed target within 2 decimals (it simulates the backend's HALF_UP rounding
and picks the cent value whose recomputed GP hits the target).
Do NOT replace this with the naive `total_cost / (1 - target_gp_rate)` formula —
that under/overshoots at cent boundaries and was the exact bug Codex caught.
Negative sale_price_ex_gst persists through line CRUD; only invoice creation
blocks a negative final sale price.
```

---

## 10. Current demo login data

Known seeded demo users (Aussie Floors Group business 1, Premier Flooring Co business 2):

```text
LC1 / password123   SN1 / password123   JW1 / password123   EP1 / password123
OS1 / password123   MJ1 / password123   NB1 / password123   CT1 / password123
EL1 / password123
```

Do not use old `LC01`.

Multi-store user may exist only if manually inserted into local DB. Check DB before relying on `MS1`.

This demo data is dev-only and must NOT run in production (see Phase 14 / section 7).

---

## 11. LOCKED ROADMAP — Phase 14 onward

Principle: no known bug or follow-up is left untracked. Audit all issues, fix each in
the phase it belongs to. Deferred items below are unbuilt FEATURES, not open bugs.

### Phase 14 — Rebaseline & Tenant Foundation (data + identity)

```text
Phase 14 branch breakdown:
  14A  feature/phase14-tenant-data-model        #74        V12 migration + Business entity + public endpoint        [COMPLETE]
  14B  feature/phase14-tenant-login-validation  #72, #31   slug validation, Business-Not-Found, real login name; move shared auth types out of auth.tsx   [COMPLETE]
  14C  feature/phase14-tenant-quick-adds        #74        authenticated quick-descriptions endpoint + DetailsOfSaleTab reads tenant quick-adds (EMPTY if none — no hardcoded fallback)   [COMPLETE]
  14D  feature/phase14-dev-seed-workflow        #73, #28   go-forward db/dev-seed workflow (manual, idempotent) + MS1 multi-store demo user + all-users-all-stores grant; schema-only baseline/squash deferred to Phase 16   [COMPLETE]
```

Phase 14 builds the DATA MODEL + endpoints + seed workflow only.
It does NOT redesign the invoice display (that is Phase 15).

```text
- GitHub issue audit: catalogue every open issue, tag each to its phase, nothing untracked.
- Clean migrations: production-safe seed structure (schema-only prod path;
  demo seed V4 stays dev-only). Verify fresh-DB-from-zero. Controlled branch, not file deletion.
- ONE new migration adds all per-tenant fields:
    branding:       logo (file path/URL), accent_colour   (business name already exists)
    invoice-legal:  abn, bank_name, bsb, account_number, account_name,
                    terms_and_conditions (single free-text block)
    payments:       stripe_payment_link_url
    invoice-config: invoice_template_key DEFAULT 'standard'
    quick-add:      new table business_quick_description (business_id, description, sort_order)
                    (column is `description`, not `text` — `text` is a Postgres type-name footgun)
- Public tenant-lookup endpoint  GET /api/v1/public/businesses/{slug}  -> name, logo, accent ONLY.
- Private invoice/legal data (abn, bank, T&Cs, quick-adds) -> AUTHENTICATED only, never public.
- Slug validation before login + proper "Business Not Found" page.
- Remove hardcoded "Aussie Floors Group" from the login page.
- Real-tenant seed workflow (captures every field above; runnable against local and prod).
- Quick-add wiring: DetailsOfSaleTab reads the tenant list instead of the
  hardcoded QUICK_DESCRIPTIONS constant.
```

### Phase 15 — Invoice & Payment Correctness (money / document trust layer)

```text
- Fix invoice PDF generation to match the provided sample template
  (generated PDF must match the on-screen / required invoice design).
- Render per-tenant invoice data ONCE, on screen + PDF together
  (logo, ABN, bank details, T&Cs). Remove hardcoded Aussie Floors / sample ABN /
  sample TERMS from InvoiceTab. Do not smear invoice branding across phases.
- Kill automatic invoice email after a payment update.
  (Manual "Resend invoice" already exists in the Invoice tab — keep it, add nothing.)
- Payment delete / reversal:
    delete payment -> paid amount drops -> balance_due increases
    invoice stays accepted/signed; NO customer re-sign; NO automatic email
    salesperson may manually resend if they choose
    (exact mirror of how adding a payment behaves)
- Stripe payment-link button:
    opens the tenant's external Stripe link in a new tab
    money goes to the tenant's own Stripe account
    NO webhook, NO auto-confirm; salesperson still records the payment manually
```

### Phase 16 — Deploy & Hardening (no revamp here)

```text
- AWS: App Runner + RDS + S3 + Secrets Manager + domain + HTTPS.
- CSRF (#29), production CORS (#30), secure cookies, timezone (#34).
- Production email provider (SES). RDS automated backups BEFORE any real data.
- Secrets / env config.
- Seed the real launch tenant into production.
```

### Phase 17 — Revamp (app chrome only)

```text
- FloorxTack identity + per-tenant logo / name / accent colour on
  login / dashboard / workspace + clean payment screen.
- Invoice is ALREADY branded in Phase 15 — do not redo it here.
- Light skin only: preserve workflow, placeholders, and backend wiring.
  No generic-CRM redesign. Keep iPad-friendly and compact.
```

### Phase 18 — Pitch Features

```text
- Quotation PDF (quote on the spot — pitch-critical).
- Lead-source field in Customer Details.
```

### Phase 19 — Final Audit Gate

```text
- Fresh-DB rebuild from zero.
- Tenant isolation test.
- Full E2E: order -> invoice -> payment -> reversal -> signature.
- Production smoke test.
- Confirm no untracked follow-ups remain.
```

---

## 12. Locked data-model decisions (Phase 14)

```text
T&Cs:                 one free-text block per business; render preserving line breaks.
Quick-add descriptions: separate table business_quick_description(business_id, description, sort_order).
Invoice template:     invoice_template_key DEFAULT 'standard'.
Bank / ABN:           business-level for now.
Logo:                 stored as file path/URL (local in dev, S3 URL in prod).
Public tenant endpoint: name / logo / accent only.
Private invoice/legal:  authenticated only (ABN, bank, T&Cs, quick-adds).
```

---

## 13. Known issues — tagged to phases

```text
#72  validate tenant slug before login + remove hardcoded login business name   -> Phase 14B
#73  separate demo seed from production schema migrations                        -> Phase 14D
#74  per-tenant data model (branding/invoice-legal/quick-add/stripe/template)    -> Phase 14 (14A done; 14C consumes private fields)
#75  centralize backend auth enforcement (fail-closed) before production         -> Phase 16 (deferred-hardening)
#28  multi-store demo user / store-selection testing                            -> Phase 14D
#31  move shared auth types out of auth.tsx                                     -> Phase 14B
#29  CSRF protection before production                                          -> Phase 16
#30  production CORS origins                                                    -> Phase 16
#34  app/database timezone before production                                    -> Phase 16
#55  financial summary versioning (concurrent mutations)                        -> deferred-hardening (post-pilot)
#69  backend version precondition on invoice accept                            -> deferred-hardening (post-pilot)
```

```text
Open follow-ups (not yet ticketed):
- [DONE] CI "Locked migration protection" guard now covers V1–V12
  (.github/workflows/ci.yml, commit d8c6374 extended the range V1–V6 -> V1–V12).
- W1: business_quick_description has a FK to business with no ON DELETE. The Phase 14D
  tenant seed/wipe workflow must DELETE business_quick_description rows BEFORE the
  business row, or the delete is FK-blocked.
```

---

## 14. Deferred — features, not bugs

Do not start these unless explicitly requested:

```text
Twilio remote invoice signing
Real Stripe webhook / auto-confirm / Stripe Connect (in-app checkout)
Operations Portal (tenant self-loads its own catalog)
Store Portal / analytics dashboard
installer / laybook workflows
advanced quote comparison
room-level complexity
AI features
invoice version-history UI / old signed invoice download
payment void/edit beyond the Phase 15 delete/reversal flow
```

---

## 15. Claude Code response format

When completing a task, report:

```text
changed files
what changed
build/test command run
build/test result
whether backend/migrations/docs were touched
whether commit/push was done
any risks or manual checks needed
```

Do not say “done” if the build/test is broken.

Do not commit unless the user explicitly says to commit.