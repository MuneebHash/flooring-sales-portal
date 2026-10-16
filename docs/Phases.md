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
Phase 16F    Remote quote acceptance + invoice conversion (Path A), COMPLETE:
             - PR1 (#117, merged 8 October 2026): backend public accept (signature-only; name = V17
               snapshot), signed quote PDF, store notification, D6b price write, D5(c), V19.
             - PR2 (#118, merged 8 October 2026): backend Path A create-invoice (inherited signature,
               frozen terms, strict newer-signature rule), InvoiceDetail terms_html / terms_source.
             - PR3 (#119, merged 9 October 2026): frontend public signing UI, Accepted Quote with
               Create Invoice, refresh guards, D9 warning, Invoice tab terms from InvoiceDetail.
             Migrations are now V1 to V19.
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
                 Invoice terms source (16F, D7): a Path A invoice uses the frozen quote
                 terms_snapshot (terms_source QUOTE; null = no terms); a Path B invoice uses the
                 live terms_hard / terms_soft (terms_source LIVE).
                 16G PR 2 (#125, planned): every invoice version stores and renders its own terms
                 (frozen at Create/Rewrite, copied by payment/void/signing, null = no terms page);
                 terms_source LIVE is renamed BUSINESS (terms frozen from the business at
                 Create/Rewrite); QUOTE unchanged. See docs/API-Contracts-Phase16G-Invoice-Link.md §4.
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
                 deliberate exception: an ACTIVE secret link token returns the documented
                 presentation fields for its single document. Quote token (16E-C /q/{token}):
                 business name/logo/accent, ABN, direct-deposit bank details, Stripe payment link,
                 and the quote's frozen terms snapshot. Invoice token (16G PR 4/5, planned,
                 /i/{token}): the same business fields plus the order's current invoice version
                 (frozen Invoice To, details, totals, Payment Made, Balance Due, dates, stored terms;
                 contract §9.3). A dead token shows only its message. Quick-adds and other config
                 are never public.
Lead Enquiry:    order_enquiry (V15), one row per order, UNIQUE(order_id); order-specific data,
                 NOT customer identity — never stored on order_customer, never widens sales_order.
```

**Migration rules**

```text
Current migrations are V1 to V20. Never edit any committed migration; any schema change is a NEW migration.
  V1–V7 base · V8 LM/SQM factor · V9 negative-price constraint · V10 invoice accept/signature/email
  V11 reserved slug words · V12 per-tenant branding/invoice-legal/quick-add · V13 per-type terms
  V14 payment void fields (voided_at + voided_by_user_id) · V15 order_enquiry (Lead Enquiry form)
  V16 quote tables · V17 quote_version customer name/billing snapshots · V18 reserved slug 'q'
  V19 accepted-name columns widened to TEXT + invoice.source_quote_version_id / terms_snapshot
  V20 (16G PR 2, #125) invoice frozen identity columns (customer_name_snapshot,
      customer_address_line1_snapshot, customer_address_line2_snapshot) + backfill
Phase 16A added NO migration (invoice tab + PDF logo path + Aire Compact PDF were app/template only).
Phase 16 quote migrations are V16 (16C), V17 and V18 (16E-C), and V19 (16F PR1); 16G PR 2 added V20.
16G plans exactly one more migration, numbered when it is written (the next free number after
  V20): PR 4 (invoice_token table + chk_business_slug_reserved replaced with the 16 V18 words plus
  'i'). V1 to V19 were unchanged by V20.
CI "Locked migration protection" guards V1–V13 only (.github/workflows/ci.yml). V14 to V20 are on main
  but OUTSIDE the guard; they are still never edited — the rule applies regardless of guard
  coverage. Do not casually expand or rewrite the guard: the Phase 17 squash/baseline folds
  V1 to V20 (and the PR 4 migration once merged) into one baseline and re-locks CI. Verify the
  live CI guard range if it matters.
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
  16G PR 4 (planned) replaces the constraint with the 16 V18 words plus 'i' for /i/{token},
  and adds 'i' to RESERVED_BUSINESS_SLUGS. V18 is never edited.
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
- Terms product rule (do not re-raise): changing business terms requires a NEW invoice +
  re-sign. Implementation today (16G PR 2, #125): every invoice version freezes its words (V20
  identity columns + the V19 terms_snapshot). Create and Rewrite freeze the saved Invoice To name,
  billing lines and sanitised per-type terms; payment, void and D.8 (and remote signing from PR 4)
  copy the previous version's words verbatim (null terms kept); Path A copies its quote's V17
  identity and frozen terms. Every version renders its stored words; null terms = no terms page,
  no live fallback, and no Path B live render remains. terms_source is QUOTE or BUSINESS (BUSINESS
  replaced the 16F LIVE; 16G contract §4). Pre-PR-2 Path B versions keep a null terms_snapshot (no
  terms backfill): their InvoiceDetail and every later payment, void or D.8 version show no terms
  (their own stored PDF keeps its terms page) until a Rewrite (refused when LAID; clears a
  signature) or Path A writes a version with frozen terms.
- Rewrite-after-accept CLEARS acceptance/signature by design — a rewrite is a new
  customer-facing invoice that requires fresh acceptance.
- Payment AND payment-void carry acceptance/signature forward (no re-sign) and send NO email.
  Manual Resend is the only post-payment email action. (16G staff rule: a signed invoice is always
  emailed at signing or conversion, by any route — D.8 today, Path A from 16G PR 3, remote signing
  from 16G PR 4 — non-fatally, after commit. That policy is for signing; payment and void still
  never auto-email a carried signed version.)
- Blank per-type terms display is CORRECT (terms_hard / terms_soft are nullable; no backfill).
- Logo is fail-soft to business-name TEXT on BOTH surfaces: the screen renders an <img> that
  falls back to the business name, and the backend PDF embeds a safe PNG/JPEG data URI
  (magic-byte + size validated) that falls back to the business name. There is still no logo
  upload UI / pipeline — the dev demo uses a seeded path + local-storage copy (Phase 17 storage).
- Invoice table is APPEND-ONLY: Create / Rewrite / Payment / Void / Accept (D.8) / Create Invoice from
  the accepted quote (Path A), and from 16G PR 4 remote signing, each INSERT a new version;
  current invoice = max(version_number). last_emailed_at is the only in-place update.
- Negative sale_price_ex_gst persists through line CRUD; only invoice creation blocks a
  negative FINAL sale price.
- Invoice PDF style is custom "Aire Compact" (PR3): modern, compact, low-whitespace, print-friendly.
  Do NOT clone the CarpetCall PDF — it was used only for content order; a direct clone was tried,
  disliked, and reverted. Terms always render on page 2 (SOFT and HARD), single column.
- Quote/order price separation (16D-A): quote draft saves and quote issue/send operations never
  set the order sale-price override or sales_order header financials. The one exception (D6b, 16F):
  public acceptance writes the signed quote's inc-GST total as the order's sale-price override.
  Path A (16F, shipped: Create Invoice from the accepted quote) bills the ACCEPTED quote snapshot
  and inherits its signature; existing Path B (Details of Sale -> Create Invoice) uses the order's
  working price independently and ignores the quote. 16G amends Path A (planned): a mandatory
  {"quote_version_id": N} stale check (PR 3), the quote's V17 identity as frozen Invoice To (PR 2)
  and the signed invoice PDF emailed after commit with no email gate (PR 3; amends 16F D6).
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
  never reuses or weakens this session model. The 16G invoice link — page /i/{token}, API
  /api/v1/public/invoices/{token}, planned in PR 4/5 — follows the same rule, with its own tokens.)
- Use apiPath(slug, …) for tenant-scoped endpoints; bypass only for genuinely public endpoints.
- Accepted customer name (invoice acceptance) is never typed. Today the Invoice tab derives it from
  the SAVED customer record and sends it as the D.8 accepted_customer_name part (150-character request
  limit). From 16G PR 3 the server takes it from the current invoice version's frozen
  customer_name_snapshot (#125): the part and its 150-character limit are removed (sending it is a
  400), a blank frozen name is 422, and the stored name is never truncated. Remote invoice signing
  (16G PR 4) uses the same frozen name. Quote acceptance (16F, D1) takes the issued version's V17
  customer_name_snapshot, never the live saved customer, never typed, never truncated (TEXT since V19).
- openhtmltopdf (1.0.10) is XML-strict and CSS-limited: tables/conservative CSS only — no
  flexbox/grid; no true CSS multi-column (column-count is parsed but ignored). Use literal chars
  or numeric entities (e.g. &#183;), NOT named HTML entities (&middot; / &nbsp; / &hellip;).
- Never leave follow-ups untracked — file a GitHub issue (the PR #71 lesson).
```

---

## 7. Roadmap

### Approved sequence

```text
GitHub cleanup (done) -> post-16E docs reconciliation (done) -> 16F verify gate (done) -> 16F (done)
  -> post-16F docs reconciliation (done, #137) -> 16G read-only verify gate (done) -> 16G decisions (done)
  -> 16G PR 1, contract and OpenAPI amendments -> 16G PRs 2 to 6 -> Batch A -> Batch B -> 17 -> 18 -> 19
```

The 16G verify gate was a read-only step. Each 16G PR, Batch A and Batch B get their own branch/PR. Steps are not cut, merged or reordered without the user's decision.

### Phase 16 — Quotation + remote signing  (16A to 16F done; 16G in progress: decided, PRs 1 to 6)

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

**16F — Remote quote acceptance + invoice conversion**: COMPLETE (#117, #118, #119). Delivered scope (as approved):

- the customer signs the quote remotely through the tokenised email link;
- the accepted snapshot is locked as the legal billing number, and a signed quote PDF is stored;
- the store is notified by email on acceptance;
- a manual Create Invoice from the accepted quote (no auto-invoice) inherits its signature — the customer does not re-sign;
- the direct-order invoice path (Path B) is preserved unchanged. Email only. Kept separate from 16G.

Email (the quote link and the store notification) is recording-only in this build until the Phase 17 provider. The full send, sign and Create Invoice smoke test passed on the real stack on 9 October 2026 (run by Muneeb).

The 16F read-only verify gate ran on 1 October 2026, and its decisions (D1 to D12) are recorded in the quotation contract (`docs/API-Contracts-Phase16B-Quotation.md`) and the UX lock (`docs/Phase16D-Quotation-UX-Lock.md`). [#101](https://github.com/MuneebHash/flooring-sales-portal/issues/101) stays in Batch A.

**16G — Remote invoice signing link.** The read-only verify gate ran on 13 October 2026 (audited at `91e0506`), and Muneeb's 16G decisions were recorded the same day (reviewed by Fable and GPT) and amended before PR 1. The target contract is [`docs/API-Contracts-Phase16G-Invoice-Link.md`](API-Contracts-Phase16G-Invoice-Link.md), and the Invoice tab and public invoice page UX is the UX lock §20. Nothing below is implemented until its PR merges. Decided scope:

- email the customer a link (`/i/{token}`) to the order's **latest** invoice version: the link follows the order, so a payment or void before signing shows on the next read and the link keeps working; an unsigned invoice can be viewed and signed there;
- the link dies when the invoice is signed by any route (remote, in store, or Create Invoice from the accepted quote: `SIGNED`), rewritten (`REWRITTEN`), replaced by a new link (`REPLACED`) or after 7 days (`EXPIRED`). There is **no view-only mode after signing**: the customer receives the signed PDF by email. No link status display, no staff cancel, no opens tracking;
- notify the store on signing (text only, to `store.email`; WARN when blank or on failure);
- reject stale versions server-side, including simultaneous updates, with "Invoice updated, please refresh": [#69](https://github.com/MuneebHash/flooring-sales-portal/issues/69) covers both the in-app acceptance (D.8) and the new remote signing with a mandatory `expected_version_number`, and [#123](https://github.com/MuneebHash/flooring-sales-portal/issues/123) adds the mandatory Path A `{"quote_version_id": N}` (`QUOTE_VERSION_STALE`);
- keep the signature rules explicit: payment and payment void carry acceptance forward; a manual rewrite clears it;
- a signed invoice is always emailed after commit, by any route (D.8; Create Invoice from the accepted quote, amending 16F D6; remote signing);
- every invoice version freezes its Invoice To name, billing lines and terms ([#125](https://github.com/MuneebHash/flooring-sales-portal/issues/125), in 16G); D.8 takes the accepted name from the frozen version and loses its name part;
- both public pages show the specific dead-link reason ([#126](https://github.com/MuneebHash/flooring-sales-portal/issues/126), in 16G);
- send link and remote signing are allowed when LAID; no CANCELLED rule.

The six 16G PRs (in order; two new migrations: V20 in PR 2, and one in PR 4, numbered when written):

| PR | Scope |
|---|---|
| 1 | Docs and OpenAPI amendments: the 16G contract, the contract/UX lock/roadmap updates, #138 folded in |
| 2 | #125: freeze invoice name, billing lines and terms (one migration, every backend writer, Invoice To from InvoiceDetail, `terms_source` `LIVE` → `BUSINESS`, tests) |
| 3 | #69 + #123: mandatory stale checks on the server **and** in the Invoice tab and Quote tab (so no broken client/server interval); D.8 name from the frozen version, name part removed; D.8 post-commit work non-throwing; Path A automatic signed-PDF email; one shared token hash helper |
| 4 | Remote invoice backend: `invoice_token` migration (also reserves the slug `i`), protected send-link, public API and signing, link and customer emails, store notification, quote-link cancellation on remote signing, plus the derived InvoiceDetail `invoice_link_expires_at` (no migration of its own) |
| 5 | Remote invoice frontend: `/i/{token}`, Accept Remotely, Preview PDF label, #126 on both public pages |
| 6 | [#120](https://github.com/MuneebHash/flooring-sales-portal/issues/120) polling (on server state; the Invoice tab polls while `invoice_link_expires_at` is non-null) and signing-draft invalidation when a refresh installs another invoice version, [#121](https://github.com/MuneebHash/flooring-sales-portal/issues/121) financial refresh and an edit-gated Update Sale Price, [#122](https://github.com/MuneebHash/flooring-sales-portal/issues/122) version-bound accepted quote reads, [#124](https://github.com/MuneebHash/flooring-sales-portal/issues/124) provenance boolean and whole-second quote timestamps, plus the derived `QuoteIssuedSummary.last_email_delivered` for the Not delivered badge |

16G issues: #120, #121, #122, #123 (with #69) and #124; #125 and #126 (in); [#69](https://github.com/MuneebHash/flooring-sales-portal/issues/69) (PR 3, with PR 4 for remote signing); [#138](https://github.com/MuneebHash/flooring-sales-portal/issues/138) OpenAPI drift (folded into PR 1). Each code PR carries the mandatory test list in the 16G contract §16; no frontend test runner is added (build plus manual smoke test). Muneeb reviews the Invoice tab result in the smoke test.

### Batch A — own branch/PR after 16G

- [#107](https://github.com/MuneebHash/flooring-sales-portal/issues/107) Customer tab: autosave customer details and addresses.
- [#108](https://github.com/MuneebHash/flooring-sales-portal/issues/108) Quote: switching a draft from non-itemised to itemised refills lines from Products & Charges.
- [#104](https://github.com/MuneebHash/flooring-sales-portal/issues/104) Seed the non-itemised quote total from the order total when a quote is first created.
- [#109](https://github.com/MuneebHash/flooring-sales-portal/issues/109) Quote helper: "Add extra cost" button when the intended total is above the lines.
- [#110](https://github.com/MuneebHash/flooring-sales-portal/issues/110) Quote helper: mismatch message hides after "Add adjustment".
- [#96](https://github.com/MuneebHash/flooring-sales-portal/issues/96) Lead Enquiry autosave stale-baseline race.
- [#101](https://github.com/MuneebHash/flooring-sales-portal/issues/101) QuoteTab autosave unmount/remount stale-overwrite hardening.
- [#134](https://github.com/MuneebHash/flooring-sales-portal/issues/134) Require non-blank details of sale before a quote can be sent.

Boundaries: #104 seeds only at first quote creation and does not re-couple quote and order pricing. #108 (approved) is draft-only — the explicit OFF→ON switch refills from current Products & Charges and discards manual quote rows/adjustments; it is not continuous auto-sync, and issued/accepted snapshots and invoices converted from accepted quotes are never touched. Until the #108 PR ships, the contract §6.1 retained-row rule stays active (non-itemised saves retain dormant rows; switching back restores them); the #108 PR amends the contract, UX lock and OpenAPI wording together with its implementation.

### Batch B — own branch/PR after Batch A

- [#111](https://github.com/MuneebHash/flooring-sales-portal/issues/111) Lead Enquiry: Preview PDF for printing.
- [#112](https://github.com/MuneebHash/flooring-sales-portal/issues/112) Payments tab deposit display; deposit % becomes a per-business setting.
- [#113](https://github.com/MuneebHash/flooring-sales-portal/issues/113) Invoice tab: replace "Download PDF" with "Preview PDF". 16G PR 5 renames the button and opens the stored PDF as a preview (UX lock §20.1), which meets #113's criteria; whether #113 then closes is Muneeb's call after the PR 5 smoke test.
- [#114](https://github.com/MuneebHash/flooring-sales-portal/issues/114) Invoice tab: "Last emailed invoice" button.

Boundaries: #112 adds a per-business deposit percentage (default 40%, set by SQL until an Operations surface exists), frozen on quote issue and preserved through acceptance/conversion; existing issued/accepted quotes keep 40%. Today 40% is hardcoded in two places (`QuotePdfGenerator`, `PublicQuoteService`) — configurable deposit is not implemented. #114 previews the exact stored PDF of the invoice version with the latest successful signed-PDF email (including the 16G emails after a Path A conversion or a remote signing), labelled "Last emailed invoice: Version N"; it never claims the customer received it and adds no invoice history list. 16G invoice **link** sends never count: they stamp only the `invoice_token` markers, never `invoice.last_emailed_at` or the dashboard mirror. Versions already have stored PDFs and per-version `last_emailed_at`; storage/schema reuse is checked during implementation. #114 also takes the Invoice tab PDF/signature read binding (audit F53, an edit to the issue proposed after 16G PR 1).

Batch A or Batch B, placement undecided: [#135](https://github.com/MuneebHash/flooring-sales-portal/issues/135) Rewrite Invoice with active payments above the new price returns 500 instead of 422.

### Open decisions (the user's; none blocks an earlier step)

| Due | Decision (Muneeb) |
|---|---|
| Before Batch A | Keep or remove the manual "Save customer" button (#107); confirmation before the refill discards edits (#108); default "Add extra cost" row text (#109) |
| Before Batch B | Lead Enquiry PDF content/layout and generated-on-request vs stored (#111); deposit base amount, remaining-deposit calculation and empty-state display (#112) |
| 16G | None open: decided on 13 October 2026 and amended before PR 1 (16G contract). Muneeb reviews the Invoice tab result in the implementation smoke test and may ask for changes then. |

### Phase 17 — Deployment & Hardening (no revamp)

```text
- Schema-only migration squash/baseline (deferred from 14D): collapse all committed pre-production
  migrations into one clean baseline — V1 to V20 plus any later pre-production migrations — before
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
- Rate limiting on the public quote endpoints and (16G) the public invoice endpoints (#128, to be
  widened to the invoice endpoints; contract §8: basic per-token + per-IP throttle; never expire
  normal users by view count), plus a PNG decode concurrency cap. Not implemented anywhere today;
  decide application-level vs infrastructure-level (WAF / App Runner) here, before the public links
  are internet-facing. Unknown and malformed tokens must stay an identical 404 on each surface.
- Restore the login session on page reload (#127).
- Tenant-independent test fixtures (#129).
- Terms per store (#130); today terms are per business and per flooring type.
- Production email provider (SES) — invoice and quote email senders are recording-only today (no
  real email leaves the system). RDS automated backups BEFORE any real data.
- Secrets / env config, including the production app.public-base-url for the /q/{token} and (16G)
  /i/{token} links.
  Seed the real launch tenant into production (using the onboarding seed).
```

### Phase 18 — Revamp (app chrome only)

```text
- FloorxTack identity + per-tenant logo/name/accent on login/dashboard/workspace + clean payment screen.
- Invoice is ALREADY branded (Phase 15/16A) — do not redo it.
- Light skin only: preserve workflow, placeholders, backend wiring. iPad-friendly, compact. No CRM redesign.
- Shared Modal: focus trap and inert background (#131).
- AppHeader overflow on narrow screens (#132).
- Disabled Send by Phone/SMS button wording (#133).
```

### Phase 19 — Final audit gate

```text
- Fresh-DB rebuild from zero. Tenant isolation test.
- Full E2E across all three signing flows (future checks — none has been run yet):
    quote-led:       order -> quote -> email link -> remote quote sign (16F) -> invoice from the
                     accepted quote -> payment -> void -> signature carried forward;
    direct invoice:  order -> Create Invoice -> in-app accept/sign -> payment -> void -> signature;
    remote invoice:  invoice link (16G) -> a payment shows on the open link -> remote sign ->
                     the link dies (signed; no view-only mode) and the signed PDF is emailed;
                     stale-version rejection ("Invoice updated, please refresh") in store and
                     remotely; Path A conversion emails the signed PDF.
  Production smoke test.
- End-of-project cosmetic cleanups from the 16F reviews (#136).
- Confirm no untracked follow-ups remain.
```

---

## 8. Open / deferred issues

- [#75](https://github.com/MuneebHash/flooring-sales-portal/issues/75) centralize backend auth enforcement (fail-closed) before production → Phase 17
- [#29](https://github.com/MuneebHash/flooring-sales-portal/issues/29) CSRF protection before production → Phase 17
- [#30](https://github.com/MuneebHash/flooring-sales-portal/issues/30) production CORS origins → Phase 17
- [#34](https://github.com/MuneebHash/flooring-sales-portal/issues/34) app/database timezone before production → Phase 17
- [#69](https://github.com/MuneebHash/flooring-sales-portal/issues/69) backend version precondition on invoice accept → **required for 16G** (16G PR 3 for in-app D.8, PR 4 for remote signing; mandatory `expected_version_number`, 409 `INVOICE_VERSION_STALE`)
- [#55](https://github.com/MuneebHash/flooring-sales-portal/issues/55) financial summary versioning (concurrent mutations) → deferred-hardening (post-pilot)
- Batch A and Batch B issues: see §7.

Docs are authoritative for phase numbering.

Known follow-ups needing a ticket (no issue number yet):

- **W1** — `business_quick_description` has a FK to `business` with no `ON DELETE`: the tenant seed/wipe workflow must DELETE `business_quick_description` rows BEFORE the business row, or the delete is FK-blocked. Needs a ticket (Phase 17 onboarding scripts).

Ticketed since the post-16E docs pass:

- **SMS "coming soon" wording**: [#133](https://github.com/MuneebHash/flooring-sales-portal/issues/133) Disabled Send by Phone/SMS button wording (Phase 18). The disabled "Send by Phone/SMS" button in the Quote tab still says "Available soon", and code comments (`QuoteTab.tsx`, `orderQuoteApi.ts`, `SmsSender` / `RecordingSmsSender`, `application.properties`) still describe SMS/Twilio delivery as coming; SMS is outside the approved scope (email only).
- **Public quote-link rate limiting**: [#128](https://github.com/MuneebHash/flooring-sales-portal/issues/128) Rate limiting on public quote endpoints and a decode concurrency cap (Phase 17). Not implemented today (contract section 8 requires it).

The other issues filed after 16F (16G, Phase 17, Phase 18, Phase 19, Batch A and the undecided #135) are listed with their phases in section 7. [#138](https://github.com/MuneebHash/flooring-sales-portal/issues/138) (OpenAPI drift) is folded into 16G PR 1.

16G follow-ups to file as GitHub issues (proposed in the 16G decisions; **not yet filed** — Muneeb files them after 16G PR 1; details in the 16G contract §17):

- D.8 and D.9 post-commit email windows: the emailed PDF, the stamped row and the response can describe different versions (audit F49). Post-pilot.
- Rewrite from a stale screen clears a remote signature the salesperson never saw (F52). Batch A or post-pilot.
- In-store D.8 trusts the declared PNG type; apply the public path's content checks (F55). Phase 17.
- Public controller tests run without the security filter chain; test recorders retain bearer links (F57, F59). Phase 17 or 19.
- Rollback hooks on an unknown commit outcome (F60). Hardening.
- Edits proposed to existing issues: #128 (add the invoice public endpoints), #138 (the extra drift the audit found), #136 (the stale comments listed by the audit, F63), #114 (the Invoice tab read binding, F53).

---

## 9. Next step

**16G PRs 2 to 6.** Phases 16A to 16F are complete on `main` (section 4). The post-16F docs reconciliation (#137), the 16G read-only verify gate (13 October 2026) and Muneeb's 16G decisions are done. 16G PR 1 writes the decided rules into the contracts, the UX lock, this roadmap and `docs/openapi.yaml` ([`docs/API-Contracts-Phase16G-Invoice-Link.md`](API-Contracts-Phase16G-Invoice-Link.md)). The 16G implementation PRs follow in order, starting with PR 2 (#125, frozen invoice words), each on its own branch and reviewed as usual (section 7). After 16G: Batch A, Batch B, Phase 17 deployment, 18, 19.