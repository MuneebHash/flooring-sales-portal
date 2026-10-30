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

Review workflow: GPT drafts task prompts and Fable independently reviews them; both then review the actual implementation diff, and neither reviewer automatically outranks the other. Claude Code implements, validates and self-reviews, then stops with the branch uncommitted. After both approvals and the user's explicit go-ahead, the user commits/pushes and opens the PR from an HTTPS compare link; the PR review trigger is exactly `@codex review`. There is no docs-only review exemption, and changes made after approval need the affected changes reviewed again.

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

Phase 16B–16E (quotation foundation + email delivery + public read-only link) is COMPLETE on main:
  - 16B contract + OpenAPI lock · 16C (#92/#93) V16 quote tables, draft money core, preview PDF
  - 16D-A (#94) quote/order price decoupling · 16D-B (#95/#97/#98) Quote tab, autosave,
    itemised editor, retained itemised rows · 16D-C (#99/#100) acceptance-ready QUOTATION PDF
  - 16E-A (#102) issue/resend/cancel + stored issued PDFs · 16E-B (#103) Send by Email + Customer Quote
  - 16E-C (#106) public read-only /q/{token} page + viewed tracking, V17 issue-time customer
    snapshots (identity-aware resend), V18 reserved slug 'q'

Phase 16F (remote quote acceptance + invoice conversion, Path A) is COMPLETE on main:
  - PR1 (#117, backend, merged 8 October 2026): public POST /api/v1/public/quotes/{token}/accept
    (signature-only multipart; accepted name = the issued V17 customer_name_snapshot, D1; allowed
    when LAID, D4; public-safe 422 wording, D11); stored signed quote PDF; version ISSUED ->
    ACCEPTED, token -> CONSUMED; post-commit store notification to store.email (non-fatal,
    recording-only, D12); accepted inc-GST total written as the order sale-price override (D6b);
    in-app invoice accept (D.8) cancels the ISSUED quote and its ACTIVE token in the same
    transaction (D5(c)); protected reads workspace.accepted, quote/pdf?type=accepted and
    quote/accepted/signature (both replaced by the version-addressed reads in 16G PR 6); quote
    email link-only; migration V19
  - PR2 (#118, backend, merged 8 October 2026): POST .../quote/create-invoice (Path A): an invoice
    version from the latest ACCEPTED quote, inheriting its signature, accepted name and time, and
    copying its frozen details of sale, ex/inc totals and terms_snapshot (source_quote_version_id
    set); strict newer-signature rule D5(b) as amended 8 October 2026 (allowed with no invoice, an
    unsigned current invoice, or a quote signed strictly later; else 409 INVOICE_ALREADY_ACCEPTED);
    allowed when LAID; no email; InvoiceDetail terms_html (nullable) + terms_source (QUOTE / LIVE),
    D7; no migration
  - PR3 (#119, frontend, merged 9 October 2026): public signing UI on /q/{token} (two declarations
    as a frontend gate, D8; signature pad; Accept Quote; local "Quote accepted." confirmation;
    410/422 messages verbatim; re-read before any retry); Accepted Quote sub-tab (frozen content,
    signature, Preview signed PDF, Create Invoice with confirmation, disabled with an explanation
    when invoice_eligible is false); Customer Quote accepted state; issued/accepted re-read on tab
    activation, sub-tab change and before Send, with stale-response guards; send-after-acceptance
    warning (D9); Invoice tab terms from InvoiceDetail terms_html with a terms_source line
  - Smoke test on the real stack passed on 9 October 2026 (run by Muneeb, as reported): send, open,
    sign, dead link on reload, Accepted Quote, Preview signed PDF, Create Invoice with the inherited
    signature and frozen terms, invoice PDF terms on page 2, D9 warning, version 2 signed, Create
    Invoice replacing the signed invoice, Rewrite clears the signature.
  16F added one migration, V19.

Post-16F docs reconciliation: done (#137, merged 13 October 2026).
16G (remote invoice signing link): read-only verify gate done (13 October 2026, audited at 91e0506);
  Muneeb's 16G decisions done (13 October 2026, amended before PR 1). Contract:
  docs/API-Contracts-Phase16G-Invoice-Link.md (UX: docs/Phase16D-Quotation-UX-Lock.md §20).
  16G PR 1 = docs + OpenAPI amendments only. 16G is COMPLETE on main: all six PRs merged (#139,
  #140, #141, #143, #146 and #154, the last on 23 October 2026):
  PR 2 #125 frozen invoice words (implemented, V20) · PR 3 #69/#123 stale checks + Path A signed-PDF email
  (implemented for in-app D.8 + Path A, server + Invoice/Quote tabs, no migration; remote signing's check is PR 4) ·
  PR 4 remote invoice backend (implemented, V21: invoice link + send-link, public invoice API, remote signing
  with its signed-PDF email and store notification, link kills in D.2/D.8/Path A, slug 'i') ·
  PR 5 remote invoice frontend (implemented: /i/{token}, Accept Remotely, Preview PDF, #126) · PR 6 #120 #121 #122 #124 (implemented).

Next development step: Batch A (docs/Phases.md section 7).

Approved sequence (details: docs/Phases.md §7/§9):
  GitHub cleanup (done) -> post-16E docs reconciliation (done) -> 16F verify gate (done) -> 16F (done)
  -> post-16F docs reconciliation (done, #137) -> 16G read-only verify gate (done) -> 16G decisions (done)
  -> 16G PR 1 (contract + OpenAPI amendments) (done) -> 16G PRs 2 to 6 (done) -> Batch A -> Batch B
  -> 17 -> 18 -> 19
```

The app is wired to the Spring Boot backend across the full MVP sales workflow: auth/login/logout/store-selection; dashboard order list + status update; create order shell; order workspace; customer + address save; details-of-sale autosave; product search + product lines; charge/labour lines; financial summary, GP, sale-price override/reset, target-GP price control; notes; photo upload/list/preview/delete; invoice create/rewrite/view/download; payment list/record/**void**; invoice acceptance with customer signature; resend/email state; per-tenant invoice on screen + PDF; per-flooring-type terms; PaymentsTab Stripe-link + bank-transfer helpers; **Lead Enquiry form (one-per-order order_enquiry) in the Customer tab**; dynamic business-slug routing; reserved-slug blocking; slug validation + Business-Not-Found; per-tenant quick-adds; go-forward db/dev-seed workflow + MS1 multi-store demo user; **quotation (16B–16E)**: conditional Quote tab with autosaved non-itemised/itemised quote draft editor and draft Preview PDF, Send by Email (issue/resend with a stored immutable issued PDF) and Cancel quote, Customer Quote sub-tab (Sent / Opened / Not delivered, stored issued PDF preview), and the public read-only quote page `/q/{token}` with viewed tracking; **quote acceptance and invoice conversion (16F)**: public signing on `/q/{token}`, the stored signed quote PDF, the store notification on acceptance (recording-only), the Accepted Quote sub-tab with Preview signed PDF, Create Invoice from the accepted quote (Path A) inheriting the customer's signature, and Invoice tab terms from InvoiceDetail; **remote invoice signing (16G)**: Accept Remotely on the Invoice tab (emails the invoice link), the public invoice page `/i/{token}` with remote signing and Preview PDF, Invoice and Quote tab polling, and version-addressed accepted quote reads. Every email sender is recording-only in this build (see Quotation rules).

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
# backend (Flyway applies V1 to V21 on boot)
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
Public quote (16E-C, slugless, token-only):  page /q/{token} · API /api/v1/public/quotes/{token}
Public invoice (16G, API since PR 4 / page since PR 5; slugless, token-only):  page /i/{token} ·
  API /api/v1/public/invoices/{token} (GET view · POST /accept · GET /pdf?version_number=N)
```

- Bare `/` and reserved/no-slug app paths redirect to the marketing URL (external `window.location`, NOT React Router `<Navigate>`).
- Login posts to `/api/v1/{slug}/auth/login` — salesperson codes are unique only within a business, so the slug is required at login and comes from the URL, not session state.
- Use the `apiPath(slug, …)` helper for tenant-scoped endpoints; bypass it only for genuinely public endpoints.
- Backend is the source of truth for tenant security (slug resolution, reserved-slug rejection, session business validation, store access, order scoping). Frontend slug routing is UX/routing only — **not** the security boundary. Cross-business/cross-store misses return 404, never 403 (do not leak existence).

---

## Reserved business slugs

Backend `V11` reserves app/system route words as invalid slugs; `V18` replaces that same `chk_business_slug_reserved` constraint with the V11 list plus `q` (the top-level, slugless public quote page `/q/{token}` would otherwise shadow a business whose slug is `q`); `V21` (16G PR 4) replaces it again with the 16 `V18` words plus `i` (for the slugless public invoice page `/i/{token}`) — 17 words:

```text
admin · api · login · static · auth · health · dashboard · orders ·
select-store · assets · new · logout · account · settings · public · q · i
```

The frontend guard (`RESERVED_BUSINESS_SLUGS` in `frontend/src/lib/tenant.ts`, via `isReservedBusinessSlug`) MUST mirror the backend `chk_business_slug_reserved` constraint as last replaced by `V21` (it includes `q` and `i`). If the backend list changes, update the frontend list too. `V21` (16G PR 4) replaced the constraint with the 16 `V18` words plus `i` and added `i` to `RESERVED_BUSINESS_SLUGS` in the same PR; `V18` is never edited.

---

## Migration rules

```text
Current migrations are V1 to V21:
  V1–V7 base · V8 LM/SQM factor · V9 negative-price constraint · V10 invoice accept/signature/email
  V11 reserved slug words · V12 per-tenant branding/invoice-legal/quick-add · V13 per-type terms
  V14 payment void fields (voided_at + voided_by_user_id) · V15 order_enquiry (Lead Enquiry form)
  V16 quote tables (quote_draft/_line, quote_version/_line, quote_token)
  V17 quote_version customer name + billing-line snapshots (frozen at issue)
  V18 reserved slug 'q' (replaces the V11 chk_business_slug_reserved constraint)
  V19 (16F PR1) quote_version.accepted_customer_name + invoice.accepted_customer_name widened to TEXT ·
      invoice.source_quote_version_id BIGINT NULL (non-unique FK fk_invoice_source_quote_version,
      index idx_invoice_source_quote_version) · invoice.terms_snapshot TEXT NULL
  V20 (16G PR 2, #125) invoice.customer_name_snapshot / customer_address_line1_snapshot /
      customer_address_line2_snapshot TEXT NULL + backfill of every invoice row from its order's saved
      customer + BILLING address (the V17 derivation); terms_snapshot is not backfilled
  V21 (16G PR 4) invoice_token table (the order-bound invoice link: ACTIVE / REPLACED / REWRITTEN /
      SIGNED / EXPIRED, hash only, uq_invoice_token_one_active_per_order) + chk_business_slug_reserved
      replaced with the 16 V18 words plus 'i'

Never edit any committed migration. Any schema change is a NEW migration.
Phase 16A added NO migration (invoice tab + PDF logo path + Aire Compact PDF were app/template only).
CI "Locked migration protection" guards V1–V13 only (.github/workflows/ci.yml). V14 to V21 are on main
  but OUTSIDE the guard — they are still never edited; the rule applies regardless of guard coverage.
  Any migration/CI work before the Phase 17 squash must explicitly account for V14 to V21. Do not
  casually expand or rewrite the guard if the Phase 17 squash/baseline will supersede it.
The Phase 17 schema-only squash/baseline must collapse ALL committed pre-production migrations —
  V1 to V21 plus any later pre-production migrations — into one clean baseline before any real store
  data exists, and re-lock it in CI.
Do NOT create a Flyway migration per customer/tenant — onboarding seeds are not product migrations.
```

### Go-forward seed rule (Phase 14D — locked)

```text
schema        = Flyway migration (db/migration, versioned, locked once committed)
demo/dev data = db/dev-seed (manual, idempotent, run by hand — NEVER auto-runs)
new tests     = self-seed required data; do NOT depend on the V4 legacy seed
```

Dev-seed scripts live in `backend/src/main/resources/db/dev-seed/` (sibling of `db/migration`). Flyway scans only `classpath:db/migration` and `spring.sql.init.mode=never`, so dev-seed files never auto-apply. Run them MANUALLY after Flyway, in order. Host `psql` is not installed on the dev Mac — the exact `docker exec -i flooring-sales-portal-postgres psql …` command for each step is in `backend/src/main/resources/db/dev-seed/README.md`; the list below is shorthand:

```text
1. start Postgres
2. start backend (Flyway applies V1 to V21)
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
                    (accept / resend / signature-download) · payment VOID (inverse of recording) · public quote accept + its D6b order-price write (16F)
                    · Create Invoice from the accepted quote (Path A, 16F)
                    · 16G (PR 4): invoice link send-link · public invoice view / accept / pdf
                    (no CANCELLED rule; a status change never kills an invoice link)
```

### Lead Enquiry (Phase 15F — shipped)

Order-specific enquiry captured inside the Customer tab. Schema: `order_enquiry` (V15), one row per order, `UNIQUE(order_id)`. This is order/job enquiry data, **not** customer identity — never stored on `order_customer`, never widens `sales_order`.

```text
- Embedded on the workspace GET (nullable data.enquiry). No standalone GET enquiry endpoint.
- PUT /api/v1/{slug}/orders/{orderId}/enquiry is a FULL-REPLACE upsert — the whole body is sent
  on every save (autosave and manual).
- Autosave: debounce + blur flush + unmount flush + single-flight + queue. No manual Save button (removed in Batch A PR 1, Muneeb's decision 30 October 2026).
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
  action after a payment change. (16G staff rule, for signing only: a signed invoice is always
  emailed at signing/conversion — D.8 since Phase 13, Path A since 16G PR 3, remote signing since
  16G PR 4.)
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
Cost (9 October 2026): staff order views may show cost: Details of Sale shows total cost + GP (Chunk 3
  F.2) and order lines return a read-only cost_snapshot (never line_cost per line); catalog search is
  cost-free; the client never sends any cost field. Quote tab, quote requests and PDFs, the public page,
  emails, invoice PDFs and notifications stay cost-free (Quote tab: only gp_percent + below-cost flag).
```

### Quotation (Phase 16B to 16G shipped)

Contract: `docs/API-Contracts-Phase16B-Quotation.md` · UX lock: `docs/Phase16D-Quotation-UX-Lock.md` · 16G amendments (implemented in 16G PRs 2 to 6): [`docs/API-Contracts-Phase16G-Invoice-Link.md`](docs/API-Contracts-Phase16G-Invoice-Link.md). Quote state is separate from order status.

```text
- Quote ≠ invoice. Quote draft saves AND issue/send never write the order sale-price override or
  sales_order header financials; quote lines never mutate Products & Charges. The one quote-to-order
  price write is D6b: at public acceptance the signed quote's inc-GST total becomes the order's
  sale-price override. Path A (16F, shipped: Accepted Quote -> Create Invoice) bills the ACCEPTED
  quote snapshot (details of sale, totals, frozen terms) and inherits its signature, never the live
  order price. Path B (existing Details of Sale -> Create Invoice) is unchanged: it uses the order's
  working price and ignores the quote. 16G amends Path A (implemented, docs/API-Contracts-Phase16G-
  Invoice-Link.md): mandatory {"quote_version_id": N} body, 409 QUOTE_VERSION_STALE (PR 3); the
  quote's V17 identity frozen as Invoice To (PR 2); the signed invoice PDF emailed after commit,
  non-fatal, NO email gate (PR 3, amends 16F D6 "no email"). It still never touches quote rows/tokens.
- Draft (one per order): itemised total = sum of lines (a direct reduction auto-adds a negative
  ADJUSTMENT; above the line sum -> 422 QUOTE_TOTAL_EXCEEDS_LINES); non-itemised = final total only.
  Below cost is blocked at save and send (QUOTE_BELOW_COST). Itemised rows are RETAINED as dormant
  rows on non-itemised saves and restored on toggle-ON (contract §6.1) — the active rule until #108
  (Batch A) changes it in its own PR.
- Issue/send: append-only quote_version snapshot (lines, totals, flooring type, details of sale,
  frozen terms, V17 customer name + billing lines) + stored immutable issued PDF + hashed 7-day
  token. A changed draft OR changed customer name/billing -> NEW version (prior SUPERSEDED);
  otherwise resend = same version, stored PDF re-delivered verbatim, new token (old REPLACED).
- LAID: draft save and send blocked (ORDER_LOCKED); workspace read, previews, stored PDF, cancel allowed.
- Public /q/{token}: slugless, token-only; read, write-once viewed_at, and signing (accept, 16F);
  cost-free, no internal IDs; never reuses or weakens the HttpSession model. The link dies when the
  quote is signed (token CONSUMED) and the signed quote is portal-only. Customer identity comes from
  the V17 issue snapshot; the approved business presentation fields (name, logo, accent, ABN,
  direct-deposit bank details, Stripe link) are read LIVE.
- Delivery is EMAIL ONLY. Quote email uses the in-memory RecordingQuoteEmailSender — NO real email
  is sent. The recorded message (with the bearer link) is logged only when DEBUG is explicitly
  enabled for that class; application.properties does not enable it. Real email = Phase 17.
- SMS is NOT approved customer delivery. Dormant backend support exists (send-sms endpoint, SMS
  enum value/error codes, recording-only SmsSender, no provider) and the "Send by Phone/SMS"
  button is disabled. Do not activate or extend it.
- The display-only 40% deposit is hardcoded (QuotePdfGenerator + PublicQuoteService). Configurable
  deposit is #112 (Batch B) — not implemented.
- Accepted name for a remotely signed quote (D1): the issued version's V17 customer_name_snapshot,
  never the live saved customer, never typed, never truncated (TEXT since V19); a null or blank
  snapshot -> 422 with the public-safe message.
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
- Invoice table is APPEND-ONLY: Create / Rewrite / Payment / Void / Accept (D.8) / Create Invoice from
  the accepted quote (Path A) — and from 16G PR 4 remote signing — each INSERT a new version;
  current invoice = max(version_number). last_emailed_at is the only in-place update.
- Terms product rule (do not re-raise): changing business terms needs a NEW invoice + re-sign.
  Implementation today (16G PR 2, #125): every invoice version stores and renders its own words
  (V20 identity columns + V19 terms_snapshot). Create/Rewrite freeze the saved Invoice To name,
  billing lines + sanitised per-type terms; payment/void/D.8 (and remote signing from PR 4) copy the
  previous version's words verbatim (null terms kept); Path A copies its quote's V17 identity +
  frozen terms. Null terms = no terms page, NO live fallback, and no Path B live render remains.
  terms_source keeps its name: QUOTE (signed quote's frozen terms) or BUSINESS (business terms
  frozen at Create/Rewrite; replaced the 16F LIVE). InvoiceDetail carries customer_name_snapshot /
  customer_address_line1_snapshot / customer_address_line2_snapshot; the Invoice tab takes Invoice To
  from them. Pre-PR-2 Path B versions keep a null terms_snapshot (terms are not backfilled): their
  InvoiceDetail and every later payment/void/D.8 version show no terms (their own stored PDF keeps its
  terms page) until a Rewrite (refused when LAID; clears a signature) or Path A writes frozen terms.
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
- Accepted customer name (invoice acceptance) is never typed. Since 16G PR 3 (implemented) D.8 takes it
  on the server from the current invoice VERSION's frozen customer_name_snapshot, verbatim and full
  length: there is no accepted_customer_name part (sending one = 400 VALIDATION_FAILED "Not allowed.")
  and no 150 limit; a blank frozen name = 422 ACCEPTED_CUSTOMER_NAME_REQUIRED (fix: save the name, then
  Rewrite). The Invoice tab shows that frozen name under the pad. Remote invoice signing (16G PR 4)
  uses the same frozen name. Remote quote acceptance takes the issued V17 customer_name_snapshot
  (D1), never typed, never truncated (see Quotation above). Both columns are TEXT since V19.
- 16G version preconditions (#69/#123; D.8 + Path A implemented in PR 3, remote invoice accept in PR 4):
  D.8 and remote invoice accept carry a mandatory expected_version_number (409 INVOICE_VERSION_STALE
  "Invoice updated, please refresh"); Path A carries {"quote_version_id": N} (409 QUOTE_VERSION_STALE).
  Compared under the order lock with the row read there, NEVER used as a lookup key.
  Rewrite/payment/void get no precondition in 16G.
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
1. Customer  2. Products & Charges  3. Details of Sale  4. Notes & Photos  5. Payments
6. Quote (conditional)  7. Invoice
```

The Quote tab is hidden by default. It appears when the user clicks Create Quote in the Details of Sale action modal (for the rest of that workspace session), or on load when the quote-workspace probe succeeds and returns a saved draft, an active issued quote or an accepted quote. A failed probe is never treated as "no quote" — the tab stays hidden and Create Quote stays unavailable (with a retry) until a probe succeeds.

---

## Out of scope (do not start unless explicitly requested)

```text
full Operations Portal · Store Portal/dashboard · installer/laybook workflows ·
advanced quote comparison · room-level complexity · AI features ·
payment edit / hard delete (only soft void is in scope) · refunds · finance products ·
Stripe Connect / full payment-gateway build / webhooks ·
major frontend redesign / FloorxTack chrome (Phase 18) · invoice version-history UI ·
tenant logo upload UI / S3 serving (Phase 17) · configurable per-store guarantee text above terms ·
SMS delivery of quotes or invoices (email only; SMS support is dormant) ·
Operations settings/roles-management UI and self-service onboarding (after launch)
```

---

## Open / deferred issues

Issue base: https://github.com/MuneebHash/flooring-sales-portal/issues/

- [#75](https://github.com/MuneebHash/flooring-sales-portal/issues/75) centralize backend auth enforcement (fail-closed) before production → Phase 17
- [#29](https://github.com/MuneebHash/flooring-sales-portal/issues/29) CSRF protection before production → Phase 17
- [#30](https://github.com/MuneebHash/flooring-sales-portal/issues/30) production CORS origins → Phase 17
- [#34](https://github.com/MuneebHash/flooring-sales-portal/issues/34) app/database timezone before production → Phase 17
- [#69](https://github.com/MuneebHash/flooring-sales-portal/issues/69) backend version precondition on invoice accept → **closed**, implemented in **16G PR 3** (in-app D.8, server + Invoice tab) and **16G PR 4** (remote invoice signing): one mandatory `expected_version_number` contract
- [#55](https://github.com/MuneebHash/flooring-sales-portal/issues/55) financial summary versioning (concurrent mutations) → deferred-hardening (post-pilot)
- 16G (decided 13 October 2026; PR placement in `docs/Phases.md` §7) is complete: all six PRs merged, the last on 23 October 2026, and its implementation issues are closed: [#125](https://github.com/MuneebHash/flooring-sales-portal/issues/125) (PR 2) · [#69](https://github.com/MuneebHash/flooring-sales-portal/issues/69) and [#123](https://github.com/MuneebHash/flooring-sales-portal/issues/123) (PR 3; #69 also PR 4) · [#126](https://github.com/MuneebHash/flooring-sales-portal/issues/126) (PR 5) · [#120](https://github.com/MuneebHash/flooring-sales-portal/issues/120) · [#121](https://github.com/MuneebHash/flooring-sales-portal/issues/121) · [#122](https://github.com/MuneebHash/flooring-sales-portal/issues/122) · [#124](https://github.com/MuneebHash/flooring-sales-portal/issues/124) (PR 6). Batch B's [#113](https://github.com/MuneebHash/flooring-sales-portal/issues/113) (Preview PDF) closed on 22 October 2026 after PR 5. [#138](https://github.com/MuneebHash/flooring-sales-portal/issues/138) OpenAPI drift stays open: PR 1 corrected its original items and the 16G docs pass (issue #145) removes its two broken upload response refs; the 69 `nullable` + `allOf` properties remain.
- 16G follow-ups, filed on 22 October 2026 (audit item → issue; placements in the lines below; details in `docs/API-Contracts-Phase16G-Invoice-Link.md` §17 and `docs/Phases.md` §8): F49 [#148](https://github.com/MuneebHash/flooring-sales-portal/issues/148) · F52 [#149](https://github.com/MuneebHash/flooring-sales-portal/issues/149) · F55 [#150](https://github.com/MuneebHash/flooring-sales-portal/issues/150) · F57/F59 [#151](https://github.com/MuneebHash/flooring-sales-portal/issues/151) · F60 [#152](https://github.com/MuneebHash/flooring-sales-portal/issues/152) · W1 [#153](https://github.com/MuneebHash/flooring-sales-portal/issues/153). The proposed additions to [#128](https://github.com/MuneebHash/flooring-sales-portal/issues/128), [#136](https://github.com/MuneebHash/flooring-sales-portal/issues/136), [#114](https://github.com/MuneebHash/flooring-sales-portal/issues/114) and [#138](https://github.com/MuneebHash/flooring-sales-portal/issues/138) were posted as issue comments.
- Phase 17: [#127](https://github.com/MuneebHash/flooring-sales-portal/issues/127) Restore the login session on page reload · [#128](https://github.com/MuneebHash/flooring-sales-portal/issues/128) Rate limiting on public quote endpoints and a decode concurrency cap (widened by an issue comment to the 16G public invoice endpoints) · [#129](https://github.com/MuneebHash/flooring-sales-portal/issues/129) Tenant-independent test fixtures · [#130](https://github.com/MuneebHash/flooring-sales-portal/issues/130) Terms per store · [#142](https://github.com/MuneebHash/flooring-sales-portal/issues/142) Signed-PDF email WARN logs carry no failure reason (with the real email provider) · [#144](https://github.com/MuneebHash/flooring-sales-portal/issues/144) Public quote gate: lazy expiry re-checks status but not the time after taking the order lock · [#150](https://github.com/MuneebHash/flooring-sales-portal/issues/150) F55: apply the public accept's PNG content checks to the in-store D.8 accept · [#153](https://github.com/MuneebHash/flooring-sales-portal/issues/153) W1: business_quick_description rows must be deleted before the business row (onboarding) · [#151](https://github.com/MuneebHash/flooring-sales-portal/issues/151) F57/F59: older public-controller tests bypass the filter chain; recording senders retain bearer links (Phase 17 or Phase 19, undecided)
- Phase 18: [#131](https://github.com/MuneebHash/flooring-sales-portal/issues/131) Shared Modal: focus trap and inert background · [#132](https://github.com/MuneebHash/flooring-sales-portal/issues/132) AppHeader overflow on narrow screens · [#133](https://github.com/MuneebHash/flooring-sales-portal/issues/133) Disabled Send by Phone/SMS button wording
- Phase 19: [#136](https://github.com/MuneebHash/flooring-sales-portal/issues/136) End-of-project cosmetic cleanups from the 16F reviews
- Batch A (own PR after 16G): [#107](https://github.com/MuneebHash/flooring-sales-portal/issues/107) · [#108](https://github.com/MuneebHash/flooring-sales-portal/issues/108) · [#104](https://github.com/MuneebHash/flooring-sales-portal/issues/104) · [#109](https://github.com/MuneebHash/flooring-sales-portal/issues/109) · [#110](https://github.com/MuneebHash/flooring-sales-portal/issues/110) · [#96](https://github.com/MuneebHash/flooring-sales-portal/issues/96) · [#101](https://github.com/MuneebHash/flooring-sales-portal/issues/101) · [#134](https://github.com/MuneebHash/flooring-sales-portal/issues/134) — details in `docs/Phases.md` §7
- Batch B (own PR after Batch A): [#111](https://github.com/MuneebHash/flooring-sales-portal/issues/111) · [#112](https://github.com/MuneebHash/flooring-sales-portal/issues/112) · [#114](https://github.com/MuneebHash/flooring-sales-portal/issues/114) — details in `docs/Phases.md` §7
- Batch A or Batch B (placement undecided): [#135](https://github.com/MuneebHash/flooring-sales-portal/issues/135) Rewrite Invoice with active payments above the new price returns 500 instead of 422 · [#147](https://github.com/MuneebHash/flooring-sales-portal/issues/147) Blank frozen customer name: warn before Accept Remotely and end signing on the customer's 422
- Batch A or post-pilot (placement undecided): [#149](https://github.com/MuneebHash/flooring-sales-portal/issues/149) F52: Rewrite from a stale screen clears an unseen remote signature
- Post-pilot hardening: [#148](https://github.com/MuneebHash/flooring-sales-portal/issues/148) F49: delayed-email windows (with the Phase 17 email provider; related #142) · [#152](https://github.com/MuneebHash/flooring-sales-portal/issues/152) F60: rollback hooks on an unknown commit outcome

Docs are authoritative for phase numbering.

Other deferred-hardening (not all ticketed): secure cookies for HTTPS, production email provider (SES), DB backup strategy, S3/object storage for production uploads.

---

## Where the real rules live

For backend/API/business-rule questions, read the source directly — do not guess. If docs conflict with code, inspect the code and ask before changing behaviour.

```text
docs/Phases.md
docs/API-Conventions.md
docs/API-Contracts-Chunk-1.md … Chunk-4.md
docs/API-Contracts-Phase13-Acceptance-Signature-Email.md
docs/API-Contracts-Phase16B-Quotation.md     (quotation contract)
docs/Phase16D-Quotation-UX-Lock.md           (quotation UX lock; §20 = 16G Invoice tab + public invoice page)
docs/API-Contracts-Phase16G-Invoice-Link.md  (16G contract: invoice link, remote signing, #125, #69/#123)
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