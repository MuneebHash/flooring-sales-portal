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
Phase 16B    Quotation contract + OpenAPI lock (docs/API-Contracts-Phase16B-Quotation.md) — COMPLETE.
Phase 16C    Backend quote foundation (#92, #93) — COMPLETE: V16 quote tables; quote workspace GET +
             draft PUT money core (itemised = line sum, auto negative adjustment on reduction,
             QUOTE_TOTAL_EXCEEDS_LINES, below-cost block); on-demand draft preview PDF (not stored).
Phase 16D    Frontend Quote tab — COMPLETE (UX lock: docs/Phase16D-Quotation-UX-Lock.md):
             - 16D-A (#94): quote draft decoupled from order pricing (a quote save never writes the
               order override / header financials).
             - 16D-B (#95, #97, #98): conditional Quote tab, autosave, non-itemised + itemised editor,
               seed from Products & Charges, retained dormant itemised rows (contract §6.1).
             - 16D-C (#99, #100): acceptance-ready QUOTATION PDF (display-only 40% deposit line,
               printable declaration/acceptance area, terms on page 2).
Phase 16E    Quote delivery (email) + public read-only link — COMPLETE:
             - 16E-A (#102): issue / resend / cancel; immutable issued snapshot + stored issued PDF;
               hashed 7-day public token. Email sender is recording-only (no real email is sent).
             - 16E-B (#103): Send by Email confirmation modal + Customer Quote sub-tab (Sent /
               Opened / Not delivered, stored issued-PDF preview, Resend, Cancel quote).
             - 16E-C (#106): slugless public read-only page /q/{token} + viewed tracking; V17
               issue-time customer name/billing snapshots with identity-aware resend detection;
               V18 reserves slug 'q'. No signing/acceptance (16F).
             Migrations are now V1–V18.
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
Public tenant endpoint (/api/v1/public/businesses/{slug}): name / logo / accent ONLY — public
                 business discovery never exposes ABN, bank, T&Cs, Stripe link or quick-adds.
Private invoice/legal (ABN, bank, T&Cs, Stripe link, quick-adds): AUTHENTICATED only, with ONE
                 deliberate exception: an ACTIVE secret quote token (16E-C public quote page)
                 returns the documented presentation fields for that single quote — business
                 name/logo/accent, ABN, direct-deposit bank details, Stripe payment link, and the
                 quote's frozen terms snapshot. Quick-adds and other config are never public.
Lead Enquiry:    order_enquiry (V15), one row per order, UNIQUE(order_id); order-specific data,
                 NOT customer identity — never stored on order_customer, never widens sales_order.
```

**Migration rules**

```text
Current migrations are V1–V18. Never edit any committed migration; any schema change is a NEW migration.
  V1–V7 base · V8 LM/SQM factor · V9 negative-price constraint · V10 invoice accept/signature/email
  V11 reserved slug words · V12 per-tenant branding/invoice-legal/quick-add · V13 per-type terms
  V14 payment void fields (voided_at + voided_by_user_id) · V15 order_enquiry (Lead Enquiry form)
  V16 quote tables · V17 quote_version customer name/billing snapshots · V18 reserved slug 'q'
Phase 16A added NO migration (invoice tab + PDF logo path + Aire Compact PDF were app/template only).
Phase 16 quote migrations are V16 (16C), V17 and V18 (16E-C).
CI "Locked migration protection" guards V1–V13 only (.github/workflows/ci.yml). V14–V18 are on main
  but OUTSIDE the guard; they are still never edited — the rule applies regardless of guard
  coverage. Do not casually expand or rewrite the guard: the Phase 17 squash/baseline folds
  V1–V18 into one baseline and re-locks CI. Verify the live CI guard range if it matters.
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
Reserved slugs = V18 chk_business_slug_reserved (the V11 list plus 'q' for /q/{token}).
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
lead-source field in Customer Details (small, unscheduled — distinct from the 15F Lead Enquiry form) ·
Operations settings/roles-management UI and self-service onboarding (after launch).

SMS is NOT an approved customer delivery channel — quote and invoice delivery is EMAIL ONLY.
  Dormant backend support exists from 16E-A (send-sms endpoint, SMS channel enum value,
  CUSTOMER_MOBILE_* / SMS_SEND_FAILED codes, recording-only SmsSender, no provider) and the
  "Send by Phone/SMS" button is disabled. It is kept, not promised; do not activate or extend it
  without a new product decision.
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
- Quote/order price separation (16D-A): quote draft saves and quote issue/send operations never
  set the order sale-price override or sales_order header financials. Planned Path A (16F) bills
  the ACCEPTED quote snapshot; existing Path B (Details of Sale -> Create Invoice) uses the order's
  working price independently and ignores the quote.
- Issued quotes are frozen (16E): lines, totals, flooring type, details of sale, terms and (V17)
  customer name/billing lines are snapshotted at issue with a stored issued PDF. A changed draft
  or changed customer name/billing issues a NEW version; otherwise a resend re-delivers the same
  frozen PDF with a new token. The public page reads customer identity from the snapshot but the
  approved business presentation fields live, so after a business-settings change the page can
  differ from the stored PDF (current implemented behaviour).
```

Footguns — must not break:

```text
- Cost visibility: costs are stored server-side and used for GP calculations; do not expose
  new cost surfaces casually. Catalog search stays cost-free and the client must NEVER send
  cost_snapshot fields (line cost snapshots are SERVER-created).
- business_quick_description column is `description`, NOT `text` (Postgres type-name footgun).
- Auth model: HttpSession only — no JWT, no Spring principal. Spring Security is
  anyRequest().permitAll(); protection is enforced via RequestContextGuard.requireStandardProtected.
  Do NOT touch this model. (NOTE: the 16E-C public tokenized quote link — page /q/{token}, API
  /api/v1/public/quotes/{token} — is a separate, slugless, token-only unauthenticated surface; it
  never reuses or weakens this session model.)
- Use apiPath(slug, …) for tenant-scoped endpoints; bypass only for genuinely public endpoints.
- Accepted customer name (invoice acceptance) comes from the SAVED customer record — the customer
  does not type it at accept time. For quote acceptance (16F), whether the name comes from the live
  saved customer or the V17 issued customer snapshot is an OPEN question for the 16F verify gate.
- openhtmltopdf (1.0.10) is XML-strict and CSS-limited: tables/conservative CSS only — no
  flexbox/grid; no true CSS multi-column (column-count is parsed but ignored). Use literal chars
  or numeric entities (e.g. &#183;), NOT named HTML entities (&middot; / &nbsp; / &hellip;).
- Never leave follow-ups untracked — file a GitHub issue (the PR #71 lesson).
```

---

## 7. Roadmap

### Approved sequence

```text
GitHub cleanup (done) -> post-16E docs reconciliation -> 16F read-only verify gate -> 16F
  -> 16G -> Batch A -> Batch B -> 17 -> 18 -> 19
```

The 16F verify gate is a read-only step; 16F, 16G, Batch A and Batch B each get their own branch/PR. Steps are not cut, merged or reordered without the user's decision.

### Phase 16 — Quotation + remote signing  (16A–16E done; 16F, 16G remaining)

```text
16A — Invoice presentation foundation — COMPLETE
      PR1 invoice tab · PR2 PDF logo path · PR3 Aire Compact invoice PDF.
      Established the reusable Aire Compact document style for the quote PDF.

16B — Quote contract lock — COMPLETE. Decisions are locked in
      docs/API-Contracts-Phase16B-Quotation.md + openapi.yaml (quote model, statuses, versioning,
      public token design, PDF / email / signature / invoice-conversion rules). The original
      planning idea "quote total = order sale-price override" was superseded in 16D-A: the quote
      draft is independent of order pricing.

16C — Backend quote foundation — COMPLETE (#92, #93): V16, workspace GET, draft PUT money core,
      on-demand preview PDF.

16D — Frontend Quote tab — COMPLETE (#94, #95, #97, #98, #99, #100). See §4 and
      docs/Phase16D-Quotation-UX-Lock.md.

16E — Quote delivery by EMAIL + public read-only link — COMPLETE (#102, #103, #106): issue /
      resend / cancel, stored issued PDF, Customer Quote sub-tab, slugless /q/{token} page with
      viewed tracking, V17 customer snapshots, V18 reserved 'q'. Delivery is recording-only in
      this build (no real email until the Phase 17 provider). No signing / acceptance.
```

**16F — Remote quote acceptance + invoice conversion** (next, after its read-only verify gate). Approved scope:

- the customer signs the quote remotely through the tokenised email link;
- the accepted snapshot is locked as the legal billing number, and a signed quote PDF is stored;
- the store is notified by email on acceptance;
- a manual Create Invoice from the accepted quote (no auto-invoice) inherits its signature — the customer does not re-sign;
- the direct-order invoice path (Path B) is preserved unchanged. Email only. Kept separate from 16G.

The 16F read-only verify gate runs after the post-16E docs reconciliation PR merges. It must assess [#101](https://github.com/MuneebHash/flooring-sales-portal/issues/101)'s impact on 16F and the accepted-customer-name source (live saved customer record vs the V17 issued customer snapshot). Neither outcome is pre-decided here.

**16G — Remote invoice signing link.** Approved scope:

- email the customer a link to the latest invoice version; an unsigned invoice can be viewed and signed, a signed invoice is view-only;
- notify the store on signing;
- reject stale versions server-side, including simultaneous updates, with "Invoice updated, please refresh"; [#69](https://github.com/MuneebHash/flooring-sales-portal/issues/69) must cover both the current in-app acceptance and the new remote signing;
- keep the signature rules explicit: payment and payment void carry acceptance forward; a manual rewrite clears it.

The user specifies the 16G frontend when 16G begins. The final API, token and UI design are not defined here.

### Batch A — own branch/PR after 16G

- [#107](https://github.com/MuneebHash/flooring-sales-portal/issues/107) Customer tab: autosave customer details and addresses.
- [#108](https://github.com/MuneebHash/flooring-sales-portal/issues/108) Quote: switching a draft from non-itemised to itemised refills lines from Products & Charges.
- [#104](https://github.com/MuneebHash/flooring-sales-portal/issues/104) Seed the non-itemised quote total from the order total when a quote is first created.
- [#109](https://github.com/MuneebHash/flooring-sales-portal/issues/109) Quote helper: "Add extra cost" button when the intended total is above the lines.
- [#110](https://github.com/MuneebHash/flooring-sales-portal/issues/110) Quote helper: mismatch message hides after "Add adjustment".
- [#96](https://github.com/MuneebHash/flooring-sales-portal/issues/96) Lead Enquiry autosave stale-baseline race.
- [#101](https://github.com/MuneebHash/flooring-sales-portal/issues/101) QuoteTab autosave unmount/remount stale-overwrite hardening.

Boundaries: #104 seeds only at first quote creation and does not re-couple quote and order pricing. #108 (approved) is draft-only — the explicit OFF→ON switch refills from current Products & Charges and discards manual quote rows/adjustments; it is not continuous auto-sync, and issued/accepted snapshots and invoices converted from accepted quotes are never touched. Until the #108 PR ships, the contract §6.1 retained-row rule stays active (non-itemised saves retain dormant rows; switching back restores them); the #108 PR amends the contract, UX lock and OpenAPI wording together with its implementation.

### Batch B — own branch/PR after Batch A

- [#111](https://github.com/MuneebHash/flooring-sales-portal/issues/111) Lead Enquiry: Preview PDF for printing.
- [#112](https://github.com/MuneebHash/flooring-sales-portal/issues/112) Payments tab deposit display; deposit % becomes a per-business setting.
- [#113](https://github.com/MuneebHash/flooring-sales-portal/issues/113) Invoice tab: replace "Download PDF" with "Preview PDF".
- [#114](https://github.com/MuneebHash/flooring-sales-portal/issues/114) Invoice tab: "Last emailed invoice" button.

Boundaries: #112 adds a per-business deposit percentage (default 40%, set by SQL until an Operations surface exists), frozen on quote issue and preserved through acceptance/conversion; existing issued/accepted quotes keep 40%. Today 40% is hardcoded in two places (`QuotePdfGenerator`, `PublicQuoteService`) — configurable deposit is not implemented. #114 previews the exact stored PDF of the invoice version with the latest successful email (including future 16G sends), labelled "Last emailed invoice: Version N"; it never claims the customer received it and adds no invoice history list. Versions already have stored PDFs and per-version `last_emailed_at`; storage/schema reuse is checked during implementation.

### Open decisions (the user's; none blocks an earlier step)

| Due | Decision (Muneeb) |
|---|---|
| Before Batch A | Keep or remove the manual "Save customer" button (#107); confirmation before the refill discards edits (#108); default "Add extra cost" row text (#109) |
| Before Batch B | Lead Enquiry PDF content/layout and generated-on-request vs stored (#111); deposit base amount, remaining-deposit calculation and empty-state display (#112) |
| Before 16G | Remote invoice-signing frontend design |

### Phase 17 — Deployment & Hardening (no revamp)

```text
- Schema-only migration squash/baseline (deferred from 14D): collapse all committed pre-production
  migrations into one clean baseline — V1–V18 plus any later pre-production migrations — before
  any real store's data exists; re-lock the new baseline in CI.
- Automatic deployment on merge to main.
- A private sample-data test environment before commercial launch.
- Repeatable per-tenant ONBOARDING seed: parameterised script/process to insert a real
  business -> its stores -> its users (login/codes) -> invoice-legal (ABN/bank/terms/stripe link).
  Real data, separate from the throwaway db/dev-seed demo scripts.
- Tenant logo upload + serving/storage design (S3) — replaces the dev-only seeded-path + local-copy demo.
- AWS: App Runner + RDS + S3 + Secrets Manager + domain + HTTPS.
- CSRF (#29), production CORS (#30), secure cookies, timezone (#34).
- Centralize backend auth enforcement / fail-closed filter (#75) before production.
- Rate limiting on the public quote endpoints (contract §8: basic per-token + per-IP throttle; never
  expire normal users by view count). Not implemented anywhere today; decide application-level vs
  infrastructure-level (WAF / App Runner) here, before the public link is internet-facing. Unknown
  and malformed tokens must stay an identical 404.
- Production email provider (SES) — invoice and quote email senders are recording-only today (no
  real email leaves the system). RDS automated backups BEFORE any real data.
- Secrets / env config, including the production app.public-base-url for the /q/{token} link.
  Seed the real launch tenant into production (using the onboarding seed).
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
- Full E2E across all three signing flows (future checks — none has been run yet):
    quote-led:       order -> quote -> email link -> remote quote sign (16F) -> invoice from the
                     accepted quote -> payment -> void -> signature carried forward;
    direct invoice:  order -> Create Invoice -> in-app accept/sign -> payment -> void -> signature;
    remote invoice:  invoice link (16G) -> remote sign, view-only once signed, stale-version
                     rejection ("Invoice updated, please refresh").
  Production smoke test.
- Confirm no untracked follow-ups remain.
```

---

## 8. Open / deferred issues

- [#75](https://github.com/MuneebHash/flooring-sales-portal/issues/75) centralize backend auth enforcement (fail-closed) before production → Phase 17
- [#29](https://github.com/MuneebHash/flooring-sales-portal/issues/29) CSRF protection before production → Phase 17
- [#30](https://github.com/MuneebHash/flooring-sales-portal/issues/30) production CORS origins → Phase 17
- [#34](https://github.com/MuneebHash/flooring-sales-portal/issues/34) app/database timezone before production → Phase 17
- [#69](https://github.com/MuneebHash/flooring-sales-portal/issues/69) backend version precondition on invoice accept → **required for 16G**: must cover both the current in-app acceptance and the new remote invoice signing
- [#55](https://github.com/MuneebHash/flooring-sales-portal/issues/55) financial summary versioning (concurrent mutations) → deferred-hardening (post-pilot)
- Batch A and Batch B issues: see §7.

Docs are authoritative for phase numbering.

Known follow-ups needing a ticket (no issue number yet):

- **W1** — `business_quick_description` has a FK to `business` with no `ON DELETE`: the tenant seed/wipe workflow must DELETE `business_quick_description` rows BEFORE the business row, or the delete is FK-blocked.
- **SMS "coming soon" wording** — the disabled "Send by Phone/SMS" button in the Quote tab still says "Available soon", and code comments (`QuoteTab.tsx`, `orderQuoteApi.ts`, `SmsSender` / `RecordingSmsSender`, `application.properties`) still describe SMS/Twilio delivery as coming; SMS is outside the approved scope (email only).
- **Public quote-link rate limiting** is not implemented (contract §8 requires it); Phase 17 work, needs an issue.

---

## 9. Next step

**16F read-only verify gate.** Phases 16A–16E are complete on `main` (§4). After the post-16E docs reconciliation PR merges, the next development step is a read-only verify gate for 16F (remote quote acceptance + invoice conversion — §7). The gate must assess [#101](https://github.com/MuneebHash/flooring-sales-portal/issues/101)'s impact on 16F and the accepted-customer-name source (live saved customer record vs the V17 issued customer snapshot); neither outcome is pre-decided. The gate is its own task, not part of the docs pass. After it: 16F → 16G → Batch A → Batch B → 17 → 18 → 19. Do NOT merge 16F and 16G.