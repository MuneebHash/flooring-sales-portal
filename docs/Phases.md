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

Current main state after PR #71:

```text
main includes dynamic business slug routing
main includes V11 reserved business slug migration
main includes invoice acceptance/signature/email frontend and backend
main includes target GP price control
main includes persistent local upload storage config
```

Latest important completed work:

```text
PR #71 — dynamic business slug routing from URL
V11 — reserved app/system route words as invalid business slugs
Phase 13 — invoice acceptance/signature/resend/email UI and backend
Target GP price control — hidden GP panel can calculate sale price from target GP%
Local uploads now persist under user home instead of OS temp
```

Current planned order:

```text
1. Issue #27 dynamic slug routing — DONE
2. Update Phases.md — CURRENT
3. Fix Terralux seed SQL so business insert includes slug = 'terralux'
4. Seed Terralux into local DB
5. Frontend revamp / polish
```

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

Deferred:

```text
invoice version-history UI
old signed invoice download
payment edit/delete/void workflow
refunds/reversals
Stripe
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

Email is currently MVP/stub/local depending on environment. Production email setup belongs to deployment/hardening.

### Issue #27 — Dynamic business slug routing — DONE

Completed in PR #71.

Frontend now derives the business slug from the first URL segment instead of hardcoding `aussie-floors-group`.

Examples:

```text
/aussie-floors-group/login
/aussie-floors-group/dashboard
/terralux/login
```

API calls now use:

```text
/api/v1/{slug}/...
```

Reserved top-level words redirect to marketing instead of being treated as tenant slugs.

Backend V11 reserves app/system words as invalid business slugs.

Reserved business slugs:

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

Frontend reserved-slug guard must mirror backend V11 `chk_business_slug_reserved`.

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
```

Rule:

```text
Never edit V1–V11.
Any schema change must be a new migration.
```

Customer onboarding seed scripts are not product migrations.

Do not create a Flyway migration for every customer/tenant.

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

Do not use old `LC01`.

Multi-store user may exist only if manually inserted into local DB. Check DB before relying on `MS1`.

---

## 11. Current immediate task: Terralux seed

Next real task after this Phases.md update:

```text
Fix Terralux seed SQL and seed Terralux locally.
```

Important:

```text
business slug must be terralux
business insert must include slug = 'terralux'
do not create a Flyway migration for Terralux
customer/tenant seed script is onboarding data, not product schema
```

Expected local URL after seed:

```text
http://localhost:5173/terralux/login
```

Terralux seed script may be local/outside repo. If a `seed-terralux.sql` file is added or edited, inspect carefully before running.

Before running the seed:

```text
confirm no existing business slug conflicts
confirm stores/users/products/charges are scoped to the Terralux business
confirm salesperson codes are unique within that business
confirm prices/costs are ex GST if using the current product model
```

Do not commit real customer data unless the user explicitly decides it belongs in the repo.

---

## 12. Frontend revamp direction

Frontend revamp is after Terralux seed.

Do not start revamp while seed/routing/data setup is incomplete.

Revamp goals:

```text
make demo look sharper
preserve existing workflow
do not break backend wiring
do not redesign into generic CRM
keep iPad-friendly layout
keep app compact and professional
```

Do not remove working functionality for visual polish.

---

## 13. Deferred / future work

Important deferred work:

```text
Stripe / payment gateway
deployment
production email provider
CSRF before production if session-cookie auth remains
production CORS config
cookie secure=true for HTTPS
database backup strategy
S3/object storage for production uploads
payment void/edit/delete workflow
invoice revision/history UI
Operations Portal
Store Portal / dashboard
installer / laybook workflows
advanced quote comparison
AI features
```

Known issues / follow-ups:

```text
#28 multi-store demo user and store-selection testing
#29 CSRF protection before production
#30 production CORS origins
#31 shared auth types cleanup
#55 backend financial summary versioning for concurrent mutations
#69 backend version precondition on invoice accept
```

---

## 14. What not to do next

Do not start these before Terralux seed and demo readiness:

```text
full deployment
Stripe Connect
Operations Portal
Store Portal
installer workflows
major frontend rewrite
AI features
quote comparison
room-level complexity
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