# Flooring Sales Portal — Build Plan & Locked Context

Short working context for implementation sessions. It holds what the product is, what is built, the rules that must not be broken, the roadmap, the open issues, and the next step. It is NOT a PR diary — detailed history lives in GitHub.

> If this file conflicts with `CLAUDE.md` or with the live repo, **verify the repo live and trust live code on `main`** — do not blindly trust either doc. A new session should re-read the repo before acting.

---

## 1. Product

Flooring Sales Portal is a vertical SaaS sales application for flooring stores. It replaces the paper/manual flooring sales process with one digital workflow:

1. salesperson starts a sale/order
2. customer details captured
3. products and charges added
4. pricing / costing / GP calculated
5. notes and photos recorded
6. quotation created/sent where needed
7. invoice created after the customer proceeds
8. customer accepts/signs
9. payment recorded
10. the full sale record stays in the portal

This is **not** a generic CRM. The product is multi-tenant: each customer business has its own business slug and isolated data.

```text
Application site:  floorxtack.com/{business-slug}
Marketing site:    tradextack.com

Tenant URL shape:
  /{business-slug}/login
  /{business-slug}/dashboard
  /{business-slug}/select-store
  /{business-slug}/orders/new
  /{business-slug}/orders/{orderId}
```

---

## 2. Stack

```text
Frontend:  Vite · React 19 · TypeScript · Tailwind v4 · React Router ·
           React Hook Form · Zod · TanStack Table · shadcn-style local UI primitives
Backend:   Spring Boot · PostgreSQL 17 · Flyway migrations · HttpSession auth (no JWT)
PDF:       Thymeleaf + openhtmltopdf (server-rendered HTML -> PDF). NOT React PDF.

Local DB (Docker Postgres 17, infra/docker-compose.yml):
  database: flooring_sales_portal
  user:     flooring_user
  password: flooring_pass
```

---

## 3. Critical workflow

Claude Code must follow these unless the user explicitly says otherwise:

```text
Do not commit unless the user explicitly says commit.
Do not push unless the user explicitly says push.
Check the current branch before editing.
Keep scope tiny. Do not freelance future features into the current task.
Do not edit backend / migrations / docs / openapi unless the task explicitly requires it.
Run the correct build/test command before saying done.
Report exact changed files and the validation result. Never say "done" if build/test is broken.
```

The user controls all approvals, commits, pushes, and merges, and all product/UI decisions. Main is protected for feature work; the user occasionally makes tiny manual doc/config edits directly on main — do not assume permission to do that.

Standard build/test:

```bash
# frontend
cd frontend && npm run build
# backend
cd backend && ./mvnw test
```

Local app startup (3 terminals): `docker compose -f infra/docker-compose.yml up -d` · `cd backend && ./mvnw spring-boot:run` · `cd frontend && npm run dev`. Never suggest `docker compose down -v` unless the user intentionally wants to wipe the local DB.

---

## 4. Completed build summary

```text
Phase 1–4    Planning, schema, contracts.
Phase 5–6    Frontend visual prototype + frontend/backend handoff.
Phase 7      CI.
Phase 8–9    Backend foundation + auth/dashboard/status; frontend auth/dashboard wiring.
Phase 10     Order shell, customer, addresses, details of sale.
Phase 11     Products, charges, notes, photos, sale price / GP (incl. target-GP price control).
Phase 12     Invoices + payments.
Phase 13     Invoice acceptance / signature / resend / email.
Issue #27    Dynamic business slug routing.
Phase 14     Rebaseline & tenant foundation: per-tenant data model (V12), slug validation,
             tenant quick-adds, db/dev-seed workflow (14A–14D).
Phase 15     Invoice & payment correctness + Lead Enquiry (15A–15F) — FULLY COMPLETE:
             - per-tenant invoice rendered once on screen + PDF (logo/ABN/bank/per-type terms);
               hardcoded sample branding removed.
             - per-flooring-type terms terms_hard / terms_soft (V13).
             - payment SOFT-VOID (V14): drops active total_paid, raises balance_due,
               regenerates current invoice, carries acceptance/signature forward (no re-sign),
               sends NO email; voided rows stay visible in history.
             - recording a payment no longer auto-emails; manual Resend is the only
               post-payment email action.
             - PaymentsTab payment helpers (display-only): Stripe payment-link button
               (credit card, HTTPS only, opens tenant's external link) + bank-transfer details.
             - 15F Lead Enquiry form (V15 order_enquiry): one-per-order enquiry inside the
               Customer tab — lead type (FLOOR/PHONE/INTERNET), product interest, subfloor,
               install questions, narrative fields. PUT upsert, embedded on workspace GET,
               autosave (debounce + flush + single-flight), LAID-locked writes.
Phase 16A    Invoice presentation pass (layout foundation for later quotation reuse) — COMPLETE:
             - PR1 (#89): Invoice TAB screen redesign (CarpetCall-style). Header reduced to
               logo + TAX INVOICE/number; store/ABN/bank/flooring-type/salesperson removed from
               the SCREEN. Screen logo is an <img> fail-soft to business-name text.
             - PR2 (#90): demo PDF logo path enablement. business.logo_path is now a
               backend-resolvable storage path '/uploads/1/branding/logo.png'; backend PDF renders
               a real PNG via FileStorageService local storage; committed frontend public mirror
               keeps the screen logo working. NO upload UI / S3 yet (deferred to Phase 17 storage).
             - PR3 (#91): backend invoice PDF redesigned to custom "Aire Compact" layout.
               Terms ALWAYS start on page 2 (both SOFT and HARD); page 1 never shows a terms block.
               Footer renders exactly once whether or not terms exist. Template-only —
               no production Java / migration / OpenAPI changes.
             NO migration added in 16A (migrations remain V1–V15).
```

---

## 5. Locked rules

These prevent real mistakes. Do not break them.

**Working discipline**

```text
Fix real, in-scope bugs immediately — do not defer them to "later".
Do not smear one feature across phases (e.g. invoice branding was done ONCE, in Phase 15).
Deployment/config issues may be deferred ONLY with a tracked issue.
Untracked follow-ups must be filed as GitHub issues — nothing left untracked.
```

**Order / domain rules**

```text
Order statuses:  LEAD · NEW_ACHIEVED_SALE · FOLLOW_UP · ACCEPTED · LAID · CANCELLED
  Do NOT invent: NEW, IN_PROGRESS, INVOICED, COMPLETED, WON, LOST, DRAFT, PAID, READY.
Flooring types:  SOFT · HARD   (one order = one flooring type).
Order number:    {store_code}.{salesperson_code}.{order_seq_padded_5}   e.g. SYD-CBD.LC1.00001
Salesperson code: two uppercase letters + one digit, e.g. LC1   (not old LC01).
LM/SQM:          default 1 LM = 3.66 SQM; per-product sqm_per_lm (V8) MUST be respected
                 where available — do not hardcode 3.66 over the per-product factor.
LAID:            locked from protected edits; reads allowed; status still changeable from
                 dashboard; notes/photos/signature reads allowed where explicitly implemented.
                 Lead Enquiry: read allowed, write blocked (422 ORDER_LOCKED).
```

**GP rule**

```text
GP is MARGIN on sale price ex-GST: (sale_ex - cost) / sale_ex.  NOT markup on cost.
Sale-price override input is GST-INCLUSIVE. Healthy-margin flag reads above ~15%.
Target-GP price control uses a backend-rounding-aware cent-search so the DISPLAYED GP
matches the typed target within 2 decimals (simulates HALF_UP rounding). Do NOT replace
with the naive total_cost / (1 - target_gp_rate) — it under/overshoots at cent boundaries
(the exact bug Codex caught). Negative sale_price_ex_gst persists through line CRUD;
only invoice creation blocks a negative final sale price.
```

**Terms / data-model decisions (business-level)**

```text
T&Cs:            legacy terms_and_conditions kept for compatibility but NOT used for invoice
                 rendering; current invoice (screen + PDF) uses per-flooring-type terms_hard /
                 terms_soft (V13), selected by order flooring type.
Quick-adds:      business_quick_description(business_id, description, sort_order).
Invoice template: invoice_template_key DEFAULT 'standard'.
Bank / ABN:      business-level.
Logo:            business.logo_path. In the current dev/demo setup it is seeded as
                 '/uploads/1/branding/logo.png'. The screen can render it via the committed frontend
                 public mirror, and the backend PDF can render it via FileStorageService local storage
                 after copy_demo_logo.sh is run. Production upload/serving/storage is deferred to Phase 17.
Public tenant endpoint: name / logo / accent ONLY.
Private invoice/legal (ABN, bank, T&Cs, quick-adds): AUTHENTICATED only, never public.
Lead Enquiry:    order_enquiry (V15), one row per order, UNIQUE(order_id); order-specific data,
                 NOT customer identity — never stored on order_customer, never widens sales_order.
```

**Migration rules**

```text
Current migrations are V1–V15. Never edit any committed migration; any schema change is a NEW migration.
  V1–V7 base · V8 LM/SQM factor · V9 negative-price constraint · V10 invoice accept/signature/email
  V11 reserved slug words · V12 per-tenant branding/invoice-legal/quick-add · V13 per-type terms
  V14 payment void fields (voided_at + voided_by_user_id) · V15 order_enquiry (Lead Enquiry form)
Phase 16A added NO migration (invoice tab + PDF logo path + Aire Compact PDF were app/template only).
The Phase 16 quotation feature will add its first quote migration at V16+ (see §7).
CI "Locked migration protection" guards V1–V13 only (latest-known). V14 and V15 are on main but NOT
  yet in the guard range — add them in the next migration/CI PR, unless the Phase 17 squash/baseline
  replaces this. Any Phase 16 quote migration (V16+) is also outside the current guard and must be
  folded into the Phase 17 squash. Verify the live CI guard range if it matters.
Schema = Flyway migration. Demo/dev data = db/dev-seed (manual, idempotent, never auto-runs).
  New tests must self-seed and must NOT depend on the V4 legacy seed.
Do NOT create a Flyway migration per customer/tenant. Production starts schema-only;
  the V4 demo seed must not run in prod. Schema-only baseline/squash is a Phase 17 task.
```

**Tenant / slug security**

```text
The URL slug identifies the tenant namespace; security is enforced by backend
session/business/store checks. Changing the slug in the URL must not grant access.
Backend is source of truth for: valid slug, reserved-slug rejection, tenant isolation,
store access, order scoping. Frontend reserved-slug guard is UX/routing only, not security.
Cross-business / cross-store access must not leak existence (404, not 403).
```

**Demo login data (dev only — must NOT run in production)**

```text
Aussie Floors Group (business 1) + Premier Flooring Co (business 2):
  LC1 / SN1 / JW1 / EP1 / OS1 / MJ1 / NB1 / CT1 / EL1   (all password123)   — not old LC01.
MS1 multi-store user exists only if manually inserted locally — check the DB before relying on it.
db/dev-seed demo files (run manually after Flyway, idempotent): quick descriptions,
  MS1 multi-store access, payment helpers, hard/soft invoice terms, branding (logo path + ABN).
Demo logo: branding_demo.sql sets logo_path '/uploads/1/branding/logo.png'; for the backend PDF
  to render it locally you must run copy_demo_logo.sh (copies the committed PNG into local storage at
  $HOME/flooring-sales-portal-data/uploads/uploads/1/branding/logo.png — the double 'uploads' is
  intentional). Old stored invoice PDFs do NOT auto-update; rewrite/regenerate to see template changes.
```

**Deferred — features, not bugs (do not start unless explicitly requested)**

```text
real Stripe webhook/auto-confirm/Connect · Operations Portal ·
Store Portal / analytics dashboard · installer/laybook workflows · advanced quote comparison ·
room-level complexity · AI features · invoice version-history UI / old signed-invoice download ·
payment edit / hard delete (beyond the Phase 15 soft-void flow) · tenant logo upload UI / S3 serving ·
configurable per-store guarantee text above terms (noted in PR3 as a possible future addition) ·
lead-source field in Customer Details (small, unscheduled — distinct from the 15F Lead Enquiry form).

NOTE: Twilio SMS remote quote signing is NO LONGER a vague deferral — it is now planned as part of
  the Phase 16 quotation feature (designed in 16B, delivered in 16E/16F). See §7.
```

---

## 6. Lessons & accepted tradeoffs

Settled decisions — do **not** re-litigate or "fix" these; they are deliberate:

```text
- Terms are FROZEN at acceptance time. Changing business terms requires a NEW invoice +
  re-sign; an accepted invoice keeps the terms it was signed under. (Do not re-raise this.)
- Rewrite-after-accept CLEARS acceptance/signature by design — a rewrite is a new
  customer-facing invoice that requires fresh acceptance.
- Payment AND payment-void carry acceptance/signature forward (no re-sign) and send NO email.
  Manual Resend is the only post-payment email action.
- Blank per-type terms display is CORRECT (terms_hard / terms_soft are nullable; no backfill).
- Logo is fail-soft to business-name TEXT on BOTH surfaces: the screen renders an <img> that
  falls back to the business name, and the backend PDF embeds a safe PNG/JPEG data URI
  (magic-byte + size validated) that falls back to the business name. There is still no logo
  upload UI / pipeline — the dev demo uses a seeded path + local-storage copy (Phase 17 storage).
- Invoice table is APPEND-ONLY: Create / Rewrite / Payment / Void each INSERT a new version;
  current invoice = max(version_number). last_emailed_at is the only in-place update.
- Negative sale_price_ex_gst persists through line CRUD; only invoice creation blocks a
  negative FINAL sale price.
- Invoice PDF style is custom "Aire Compact" (PR3): modern, compact, low-whitespace, print-friendly.
  Do NOT clone the CarpetCall PDF — it was used only for content order; a direct clone was tried,
  disliked, and reverted. Terms always render on page 2 (SOFT and HARD), single column.
```

Footguns — must not break:

```text
- Cost visibility: costs are stored server-side and used for GP calculations; do not expose
  new cost surfaces casually. Catalog search stays cost-free and the client must NEVER send
  cost_snapshot fields (line cost snapshots are SERVER-created).
- business_quick_description column is `description`, NOT `text` (Postgres type-name footgun).
- Auth model: HttpSession only — no JWT, no Spring principal. Spring Security is
  anyRequest().permitAll(); protection is enforced via RequestContextGuard.requireStandardProtected.
  Do NOT touch this model. (NOTE: Phase 16 public tokenized quote links are a NEW, separate
  unauthenticated surface — they must NOT reuse or weaken this session model; see §7 16B.)
- Use apiPath(slug, …) for tenant-scoped endpoints; bypass only for genuinely public endpoints.
- Accepted customer name comes from the SAVED customer record — the customer does not type it
  at accept time.
- openhtmltopdf (1.0.10) is XML-strict and CSS-limited: tables/conservative CSS only — no
  flexbox/grid; no true CSS multi-column (column-count is parsed but ignored). Use literal chars
  or numeric entities (e.g. &#183;), NOT named HTML entities (&middot; / &nbsp; / &hellip;).
- Never leave follow-ups untracked — file a GitHub issue (the PR #71 lesson).
```

---

## 7. Roadmap

### Phase 16 — Quotation PDF / quote sending  (16A done; 16B–16F = quotation feature)

```text
16A — Invoice presentation foundation — COMPLETE
      PR1 invoice tab · PR2 PDF logo path · PR3 Aire Compact invoice PDF.
      Established the reusable Aire Compact document style for the quote PDF.

16B — Quote planning + contract lock — NEXT. Planning/contract only, NO product-code implementation.
      This is the heavy decision phase. Lock, in order, BEFORE any code:
      1. MONEY MODEL (the real risk, not the PDF):
         - quote total = order sale-price override.
         - the ACCEPTED quote snapshot (NOT the live override) is the legal billing number.
         - below-cost block fires at quote SAVE/ACCEPT (not invoice creation).
         - define the full quote <-> order <-> invoice money flow and what is snapshotted when.
      2. QUOTE MODEL: separate versioned entity vs. draft-invoice view vs. new table.
         Decide single-current / append-only shape (mirror the invoice append-only model or not).
      3. QUOTE STATUSES + lifecycle (do NOT invent order-status variants; quote has its own states).
      4. VERSIONING rules (current quote = max version; what create/rewrite do).
      5. PUBLIC TOKENIZED QUOTE-LINK DESIGN (for Twilio remote signing, implemented later):
         token model, expiry, rate limiting, NO logged-in session, public read-only surface.
         Must NOT reuse or weaken the HttpSession auth model. DESIGNED in 16B, built in 16E/16F.
      6. PDF / EMAIL / SMS / SIGNATURE / INVOICE-CONVERSION rules:
         - quote PDF reuses the Aire Compact document style, title "QUOTE" (DISTINCT from invoice).
         - delivery channels (email and/or SMS via Twilio) — lock exact channel rules here.
         - signature/acceptance rules — do NOT reuse invoice acceptance/signature/payment rules blindly.
         - invoice-conversion + signature-inheritance rules: does the accepted quote signature carry
           into the created invoice (no re-sign), like payment-void carry-forward, or fresh acceptance?
      Lock the API contract (openapi.yaml + contract docs) FIRST. Decisions are discussed one at a time.

16C — Backend quote foundation:
      Migration V16, quote create / rewrite / get-current, quote PDF generate/download,
      quote total snapshot rules, backend tests. No email / SMS / signing yet.

16D — Frontend salesperson Quote tab:
      Quote tab/screen, create/rewrite quote, preview/download PDF, show quote status.
      No sending / signing yet.

16E — Quote delivery link:
      Send / resend the quote by email and/or SMS (Twilio) link (exact channel rules per the 16B lock);
      public tokenized quote-view page; READ-ONLY customer view. Track sent_at / last_emailed_at.
      No signing / acceptance yet.

16F — Remote quote acceptance + invoice conversion:
      Customer signs the quote remotely on their phone via the tokenized link; the accepted quote
      snapshot is locked as the legal number; store notification email on accept; manual Create Invoice
      button (NO auto-invoice); the created invoice inherits the accepted quote signature/snapshot per
      the 16B-locked rules.
      Do NOT merge 16E and 16F — public-token remote signing is its own security + legal-state surface,
      Phase-13-sized on its own.

Migration note: the quote schema is V16+, outside the current V1–V13 CI guard, and MUST be folded
  into the Phase 17 squash/baseline.
```

### Phase 17 — Deployment & Hardening (no revamp)

```text
- Schema-only migration squash/baseline (deferred from 14D): collapse all committed pre-production
  migrations into one clean baseline, including V1–V15 and any Phase 16 quotation migrations, before
  any production data exists; re-lock the new baseline in CI.
- Repeatable per-tenant ONBOARDING seed: parameterised script/process to insert a real
  business -> its stores -> its users (login/codes) -> invoice-legal (ABN/bank/terms/stripe link).
  Real data, separate from the throwaway db/dev-seed demo scripts.
- Tenant logo upload + serving/storage design (S3) — replaces the dev-only seeded-path + local-copy demo.
- AWS: App Runner + RDS + S3 + Secrets Manager + domain + HTTPS.
- CSRF (#29), production CORS (#30), secure cookies, timezone (#34).
- Centralize backend auth enforcement / fail-closed filter (#75) before production.
- Production email provider (SES). RDS automated backups BEFORE any real data.
- Secrets / env config. Seed the real launch tenant into production (using the onboarding seed).
```

### Phase 18 — Revamp (app chrome only)

```text
- FloorxTack identity + per-tenant logo/name/accent on login/dashboard/workspace + clean payment screen.
- Invoice is ALREADY branded (Phase 15/16A) — do not redo it.
- Light skin only: preserve workflow, placeholders, backend wiring. iPad-friendly, compact. No CRM redesign.
```

### Phase 19 — Final audit gate

```text
- Fresh-DB rebuild from zero. Tenant isolation test.
- Full E2E: order -> quote -> remote sign -> invoice -> payment -> void -> signature. Production smoke test.
- Confirm no untracked follow-ups remain.
```

---

## 8. Open / deferred issues

```text
#75  centralize backend auth enforcement (fail-closed) before production   -> Phase 17
#29  CSRF protection before production                                     -> Phase 17
#30  production CORS origins                                               -> Phase 17
#34  app/database timezone before production                              -> Phase 17
#55  financial summary versioning (concurrent mutations)                  -> deferred-hardening (post-pilot)
#69  backend version precondition on invoice accept                       -> deferred-hardening (post-pilot)

NOTE: docs are authoritative for phase numbering. GitHub issue labels may still read phase-16 for
  #29/#30/#34/#75 — these are Phase 17 now; update the labels to match when convenient.

Known follow-up needing ticket:
W1  business_quick_description has a FK to business with no ON DELETE — the tenant seed/wipe
    workflow must DELETE business_quick_description rows BEFORE the business row, or the delete is FK-blocked.
```

---

## 9. Next step

**Phase 16B — Quote planning + contract lock.** Phase 16A (invoice tab + PDF logo path + Aire Compact invoice PDF) is complete on `main`. 16B is **planning + contract only — no product-code implementation** (it may still update `openapi.yaml`/contract docs). Lock the decisions in order: (1) the money model — quote total = order override, accepted quote snapshot = legal billing number, below-cost block at quote save/accept; (2) the quote model — versioned entity vs. draft-invoice view vs. new table — plus statuses and versioning; (3) the public tokenized quote-link design for later Twilio remote signing (token/expiry/rate-limit, no session, never weakens the HttpSession model); (4) PDF / email / SMS / signature / invoice-conversion + signature-inheritance rules. Update `openapi.yaml` + contract docs FIRST, before any 16C code. Discuss each decision one at a time. Do NOT merge 16E and 16F.