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
6. invoice created
7. customer accepts/signs
8. payment recorded
9. the full sale record stays in the portal

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
Phase 15     Invoice & payment correctness (15A–15E):
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
Logo:            file path/URL (local in dev, S3 URL in prod).
Public tenant endpoint: name / logo / accent ONLY.
Private invoice/legal (ABN, bank, T&Cs, quick-adds): AUTHENTICATED only, never public.
```

**Migration rules**

```text
Current migrations are V1–V14. Never edit any committed migration; any schema change is a NEW migration.
  V1–V7 base · V8 LM/SQM factor · V9 negative-price constraint · V10 invoice accept/signature/email
  V11 reserved slug words · V12 per-tenant branding/invoice-legal/quick-add · V13 per-type terms
  V14 payment void fields (voided_at + voided_by_user_id)
CI "Locked migration protection" currently guards V1–V13. V14 is on main but NOT yet in the
  guard range — add it in the next migration/CI PR, unless the Phase 16 squash/baseline replaces this.
Schema = Flyway migration. Demo/dev data = db/dev-seed (manual, idempotent, never auto-runs).
  New tests must self-seed and must NOT depend on the V4 legacy seed.
Do NOT create a Flyway migration per customer/tenant. Production starts schema-only;
  the V4 demo seed must not run in prod. Schema-only baseline/squash is a Phase 16 task.
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
  MS1 multi-store access, payment helpers, hard/soft invoice terms.
```

**Deferred — features, not bugs (do not start unless explicitly requested)**

```text
Twilio remote invoice signing · real Stripe webhook/auto-confirm/Connect · Operations Portal ·
Store Portal / analytics dashboard · installer/laybook workflows · advanced quote comparison ·
room-level complexity · AI features · invoice version-history UI / old signed-invoice download ·
payment edit / hard delete (beyond the Phase 15 soft-void flow).
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
- Logo display is fail-soft to business-name TEXT — there is no logo upload route/pipeline yet.
- Quick-adds: DetailsOfSaleTab reads the tenant list; EMPTY if none — no hardcoded fallback.
- Invoice table is APPEND-ONLY: Create / Rewrite / Payment / Void each INSERT a new version;
  current invoice = max(version_number). last_emailed_at is the only in-place update.
- Negative sale_price_ex_gst persists through line CRUD; only invoice creation blocks a
  negative FINAL sale price.
```

Footguns — must not break:

```text
- Cost visibility: costs appear only in controlled salesperson UI; line cost snapshots are
  SERVER-created; the client must NEVER send cost_snapshot fields; catalog search stays cost-free.
- business_quick_description column is `description`, NOT `text` (Postgres type-name footgun).
- Auth model: HttpSession only — no JWT, no Spring principal. Spring Security is
  anyRequest().permitAll(); protection is enforced via RequestContextGuard.requireStandardProtected.
  Do NOT touch this model.
- Use apiPath(slug, …) for tenant-scoped endpoints; bypass only for genuinely public endpoints.
- Accepted customer name comes from the SAVED customer record — the customer does not type it
  at accept time.
- Never leave follow-ups untracked — file a GitHub issue (the PR #71 lesson).
```

---

## 7. Roadmap

### Phase 16 — Deploy & Hardening (no revamp)

```text
- Schema-only migration squash/baseline (deferred from 14D): collapse V1–V14 into one clean
  baseline before any production data exists; re-lock the new baseline in CI.
- Repeatable per-tenant ONBOARDING seed: parameterised script/process to insert a real
  business -> its stores -> its users (login/codes) -> invoice-legal (ABN/bank/terms/stripe link).
  Real data, separate from the throwaway db/dev-seed demo scripts.
- AWS: App Runner + RDS + S3 + Secrets Manager + domain + HTTPS.
- CSRF (#29), production CORS (#30), secure cookies, timezone (#34).
- Centralize backend auth enforcement / fail-closed filter (#75) before production.
- Production email provider (SES). RDS automated backups BEFORE any real data.
- Secrets / env config. Seed the real launch tenant into production (using the onboarding seed).
```

### Phase 17 — Revamp (app chrome only)

```text
- FloorxTack identity + per-tenant logo/name/accent on login/dashboard/workspace + clean payment screen.
- Invoice is ALREADY branded (Phase 15) — do not redo it.
- Light skin only: preserve workflow, placeholders, backend wiring. iPad-friendly, compact. No CRM redesign.
```

### Phase 18 — Pitch features

```text
- Quotation PDF (quote on the spot — pitch-critical).
- Lead-source field in Customer Details.
```

### Phase 19 — Final audit gate

```text
- Fresh-DB rebuild from zero. Tenant isolation test.
- Full E2E: order -> invoice -> payment -> void -> signature. Production smoke test.
- Confirm no untracked follow-ups remain.
```

---

## 8. Open / deferred issues

```text
#75  centralize backend auth enforcement (fail-closed) before production   -> Phase 16
#29  CSRF protection before production                                     -> Phase 16
#30  production CORS origins                                               -> Phase 16
#34  app/database timezone before production                              -> Phase 16
#55  financial summary versioning (concurrent mutations)                  -> deferred-hardening (post-pilot)
#69  backend version precondition on invoice accept                       -> deferred-hardening (post-pilot)

Known follow-up needing ticket:
W1  business_quick_description has a FK to business with no ON DELETE — the tenant seed/wipe
    workflow must DELETE business_quick_description rows BEFORE the business row, or the delete is FK-blocked.
```

---

## 9. Next step

**Phase 16 — Deploy & Hardening.** Begin with detailed Phase 16 planning. Early tasks: the schema-only **migration squash/baseline** (collapse V1–V14, re-lock in CI) and designing the **repeatable real-tenant onboarding seed**. Then proceed through the AWS/hardening items.