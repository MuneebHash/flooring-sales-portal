# Sales Portal MVP — API Contracts · Chunk 4 (Lock-Ready)

**Source of truth priority:**
1. `docs/API-Conventions.md`
2. Locked Chunk 1 contract
3. Locked Chunk 2 contract
4. Locked Chunk 3 contract
5. Locked DB schema (V2 / V3)

**V5 follow-ups (referenced — required before backend implementation, not redesigned here):**
1. `business.slug`
2. `sales_order.price_adjustment_inc_gst` — drives the live financial summary used by Create Invoice and manual Rewrite Invoice snapshots.

**Order number format (locked):** `{store_code}.{salesperson_code}.{order_sequence_number_padded_5}` — e.g. `SYD-CBD.LC1.00042`. Backend-generated; frontend never sends it. Used in invoice PDF filenames.

**File-binary exception (accepted in conventions §3):** file upload endpoints may accept `multipart/form-data`; file download endpoints may return raw binary bytes. Chunk 4 uses this exception only for the invoice PDF download endpoint (D.4).

> **Phase 13 update (Invoice Acceptance + Signature + Email).** Chunk 4 is the locked **Phase 12** core (invoices + payments). After Chunk 4 was locked, invoice **acceptance + customer signature capture + emailing the accepted PDF** moved out of "deferred post-MVP" and into MVP scope as **Phase 13**. The full Phase 13 endpoint, data-model, PDF, email, and error contracts live in **`docs/API-Contracts-Phase13-Acceptance-Signature-Email.md`** and are additive to this chunk. Where this document still said acceptance/signature/email was deferred, that wording is corrected below to point at Phase 13. What remains deferred post-MVP is unchanged: multi-version invoice **history** (list, detail-by-id, old-version PDF download), refunds, payment **edit**, and any separate invoice-status enum. (**Phase 15D update:** payment **void** (soft void) is now implemented as D.10; it is no longer deferred. There is no hard delete and no payment edit.)

---

## A. Chunk 4 Scope — Included (MVP)

**MVP exposes only the current/latest invoice.** The `invoice` table keeps its
`version_number` and may hold multiple rows per order internally, but the MVP API and
frontend expose a single **current invoice** — no history list, no revision dropdown, and
no access to older versions. See A.1 for what is deferred. No migration is added and no
schema field is removed for this simplification.

- Invoice creation (creates the current invoice).
- Rewrite Invoice (regenerates/updates the current invoice from current live order state).
- Read the current invoice snapshot (metadata).
- Current invoice PDF binary download.
- Payment list per order with current official payment summary.
- Payment recording (records money against the **current/latest invoice**, atomically regenerates the current invoice updating `total_paid` / `balance_due` only, without making unsent live order edits official).

### A.1 Deferred to post-MVP (schema kept, API not exposed)

Reserved in the schema (`invoice.version_number`, multiple `invoice` rows per order) but
NOT exposed by the MVP API or frontend:
- Invoice history / version list per order.
- Invoice detail by `invoiceId`.
- Download of an older invoice version's PDF.
- Signed / accepted invoice revision **history**, the invoice-page revision dropdown, and viewing/downloading **old** signed PDFs. (The signed-invoice **acceptance workflow itself** — capturing a signature, accepting the current invoice, and emailing the accepted PDF — is **no longer deferred**; it is MVP **Phase 13**. See `docs/API-Contracts-Phase13-Acceptance-Signature-Email.md`. Only multi-version *history* of signed invoices stays deferred.)

---

## B. Chunk 4 Scope — Excluded / Deferred

Already locked in earlier chunks — not reopened:
- Auth, dashboard, status update (Chunk 1).
- Order create / customer / addresses / details-of-sale (Chunk 2).
- Product/charge lines, sale price override/reset, notes, photo attachments (Chunk 3).

Out of MVP scope or deferred:
- **Operations Portal catalog management is deferred to a separate future Operations Portal API contract. For the Sales Portal MVP, product and charge catalog data will be manually loaded into `store_product` and `store_charge` during onboarding/admin setup. Sales Portal consumes that catalog only through the locked Chunk 3 `available-products` and `available-charges` endpoints.** Operations Portal will have its own login/auth flow, its own users (owners / managers / catalog admins), and its own contract; it is not a sub-path of the Sales Portal API.
- Payment edit / delete (conventions §13: not allowed in MVP).
- **Invoice history / revision access (deferred post-MVP).** The MVP exposes only the current/latest invoice. Invoice history listing, invoice detail by `invoiceId`, and downloading an older invoice version's PDF are deferred. The schema (`invoice.version_number`, multiple rows per order) is kept and reserved; no migration is added for *history* (see A.1 and D.5).
- **Signed / accepted invoice revision *history* (deferred post-MVP):** the invoice-page revision dropdown and viewing/downloading **old** signed PDFs. (The invoice **acceptance + signature + email** workflow itself is **MVP in Phase 13** — see `docs/API-Contracts-Phase13-Acceptance-Signature-Email.md` — which *does* add the signed-invoice fields `accepted_at`, `accepted_customer_name`, `accepted_signature_file_id`, and `last_emailed_at` via a documented `V10` migration. Phase 13 does **not** introduce a separate invoice-status enum.)
- Refunds, surcharges, finance products, cheque, partial reversals.
- Stripe / gateway-driven flows (`gateway_transaction_id`, `response_status`, `response_message` remain nullable backend fields for manual MVP payments). Stripe is **Phase 14**.
- Invoice email / send-to-customer **before signature/acceptance** (not allowed). Automatic emailing of the **accepted** invoice PDF, and Re-send of the current accepted invoice, are **MVP in Phase 13** (Accept / Resend endpoints — see the Phase 13 contract). Advanced email audit history stays post-MVP.
- Standalone invoice PDF (re)generation endpoints — PDFs are produced only as a side effect of Create Invoice, manual Rewrite Invoice, POST Payment, and (Phase 13) Accept Invoice. **Re-send (D.9) does NOT (re)generate a PDF** — it only re-emails the already-current accepted PDF (no new invoice version).
- `SIGNATURE` attachment kind in the **general Notes & Photos attachments list** (Chunk 3 still rejects `attachment_kind=SIGNATURE` on the attachment upload endpoint). Phase 13 captures the signature through a **dedicated invoice-acceptance endpoint** that stores it via `stored_file` (reusing the existing storage mechanism) without surfacing it as a general order attachment. A full legal e-sign platform is post-MVP.

---

## C. Chunk 4 Endpoint List

All endpoints are **Standard protected** (all 7 checks from conventions §9). Every order-level endpoint additionally enforces `sales_order.business_id = session.business_id` AND `sales_order.store_id = session.store_id`. The current-invoice endpoints (D.3, D.4) resolve the current invoice as the latest `invoice` row for the order in scope (it always belongs to the order). Payment-level reads/writes additionally enforce `payment_transaction.order_id = path.orderId`.

**MVP endpoint list (current invoice only):**

| Method | Path | Purpose | Contract |
|--------|------|---------|----------|
| POST | `/api/v1/{slug}/orders/{orderId}/invoices` | Create the current invoice | D.1 |
| POST | `/api/v1/{slug}/orders/{orderId}/invoices/rewrite` | Rewrite/regenerate the current invoice from current live order state | D.2 |
| GET  | `/api/v1/{slug}/orders/{orderId}/invoices/current` | Read the current invoice snapshot | D.3 |
| GET  | `/api/v1/{slug}/orders/{orderId}/invoices/current/file` | Download the current invoice PDF binary | D.4 |
| GET  | `/api/v1/{slug}/orders/{orderId}/payments` | List payments + current official payment summary | D.6 |
| POST | `/api/v1/{slug}/orders/{orderId}/payments` | Record a payment against the current invoice; atomically regenerate the current invoice | D.7 |
| POST | `/api/v1/{slug}/orders/{orderId}/payments/{paymentTransactionId}/void` | Soft-void a payment; atomically regenerate the current invoice (Phase 15D) | D.10 |

**Added in Phase 13 (Invoice Acceptance + Signature + Email — full contracts in `docs/API-Contracts-Phase13-Acceptance-Signature-Email.md`):**

| Method | Path | Purpose | Contract |
|--------|------|---------|----------|
| POST | `/api/v1/{slug}/orders/{orderId}/invoices/current/accept` | Accept/sign the current invoice + auto-email the accepted PDF | Phase 13 |
| POST | `/api/v1/{slug}/orders/{orderId}/invoices/current/resend` | Re-send the current accepted invoice PDF | Phase 13 |
| GET  | `/api/v1/{slug}/orders/{orderId}/invoices/current/signature` | Stream the accepted signature image | Phase 13 |

Phase 13 also adds a customer-email precondition to D.1 / D.2 (and to Accept / Resend), and makes D.7 carry acceptance/signature metadata forward when the current invoice was already accepted. See the Phase 13 contract for the deltas. **Phase 15D update:** recording a payment (D.7) and voiding a payment (D.10) **never auto-email** — manual Resend is the only email action after a payment change. (The old "re-email on payment after acceptance" behaviour was removed.)

**Deferred to post-MVP (NOT implemented in MVP — see D.5):** `GET .../invoices` (history
list), `GET .../invoices/{invoiceId}` (detail by id), `GET .../invoices/{invoiceId}/file`
(older-version PDF). The schema supports these; they can be added later without a migration.

---

## D. Endpoint Contracts

### D.1 POST /api/v1/{slug}/orders/{orderId}/invoices

**Purpose.** Create the first invoice for the order (version 1). Validates the 9 invoice preconditions, snapshots the **current live order state** as the official sale snapshot, generates a PDF, stores the file, and persists the `invoice` row.

**Scope class.** Standard protected.

**Path params.** `orderId` — positive integer.

**Request DTO**
```json
{}
```

**Field rules.** No request fields. The frontend never sends `due_date`. Backend derives it from `sales_order.proposed_lay_date` (see Business rules).

**Response DTO — 201 Created**
```json
{
  "data": {
    "invoice": { "...see E.2 invoice_detail..." }
  },
  "message": "Invoice created."
}
```

**Business rules** (conventions §14)
1. Verify no invoice currently exists for this order. If any exists → **409 `INVOICE_ALREADY_EXISTS`** (the salesperson should use Rewrite Invoice).
2. Validate the 9 invoice preconditions (see F.1). Any failure → **422 `INVOICE_PRECONDITIONS_NOT_MET`** with structured `details` listing every missing/invalid item.
3. Compute the live `order_financial_summary` from current product lines, charge lines, and `price_adjustment_inc_gst`. `final_sale_price_inc_gst` must be > 0 (precondition 9).
4. Snapshot:
   - `version_number = 1`
   - `invoice_date = current date (server local time)`
   - `due_date = sales_order.proposed_lay_date − 2 calendar days` (proposed_lay_date is guaranteed non-null by precondition 7). May be earlier than `invoice_date` — that is valid (see G.2).
   - `details_of_sale_snapshot = sales_order.details_of_sale`
   - `sale_price_ex_gst = order_financial_summary.sale_price_ex_gst`
   - `sale_price_inc_gst = order_financial_summary.final_sale_price_inc_gst`
   - `total_paid = round(sum(payment_transaction.amount for this order), 2)` — typically `0.00` at first invoice
   - `balance_due = round(sale_price_inc_gst − total_paid, 2)` — must be ≥ 0 (DB CHECK)
   - `created_by_user_id = session.user_id`
5. Generate the PDF, write a `stored_file` row, link via `invoice.stored_file_id`.
6. Persist the `invoice` row.
7. Steps 4–6 happen in one DB transaction. PDF / file-write failure → full rollback (no `invoice` row, no `stored_file` row).

**Validation**
- `orderId` is a positive integer.
- Request body must be empty (`{}`). Any request fields, including `due_date`, return 400 `VALIDATION_FAILED`.

**Tenant / session / store / order scoping**
- All 7 checks from conventions §9.
- Order belongs to session's `(business_id, store_id)`. Otherwise 404.

**LAID lock.** **Allowed when LAID** if no invoice exists yet. Conventions §16 blocks "manually Rewrite Invoice" when LAID, but does not block first invoice creation. Rationale: LAID can be set on a completed job whose invoice was never raised; the salesperson must still be able to invoice it.

**Status codes**
| Code | When |
|------|------|
| 201 | Invoice version 1 created |
| 400 | Malformed JSON, invalid `orderId` format, non-empty request body (any field including `due_date` → `VALIDATION_FAILED`) |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in scope, or business slug not found / inactive |
| 409 | Invoice already exists for this order (`INVOICE_ALREADY_EXISTS`) |
| 422 | Preconditions not met (`INVOICE_PRECONDITIONS_NOT_MET`) |
| 500 | Unexpected (transactional rollback) |

**Error example — preconditions not met**
```json
{
  "error": {
    "code": "INVOICE_PRECONDITIONS_NOT_MET",
    "message": "Complete required fields before creating invoice.",
    "details": [
      { "section": "customer", "field": "last_name",         "message": "Customer last name is required." },
      { "section": "address",  "field": "billing_address",   "message": "Billing address is required." },
      { "section": "details",  "field": "proposed_lay_date", "message": "Proposed lay date is required." }
    ]
  }
}
```

**Error example — already exists**
```json
{
  "error": {
    "code": "INVOICE_ALREADY_EXISTS",
    "message": "An invoice already exists for this order. Use Rewrite Invoice to create a new version."
  }
}
```

---

### D.2 POST /api/v1/{slug}/orders/{orderId}/invoices/rewrite

**Purpose.** Regenerate the **current invoice** from the current live order state (internally the next `version_number`). Re-snapshots the current live order state as the new sale snapshot, plus current total payments. MVP exposes only the resulting current invoice (no history).

**Scope class.** Standard protected.

**Path params.** `orderId` — positive integer.

**Request DTO**
```json
{}
```

**Field rules.** No request fields. The frontend never sends `due_date`. Backend derives it from current `sales_order.proposed_lay_date` (see Business rules).

**Response DTO — 201 Created**
```json
{
  "data": {
    "invoice": { "...see E.2 invoice_detail..." }
  },
  "message": "Invoice rewritten."
}
```

**Business rules** (conventions §14)
1. Verify at least one invoice already exists for this order. Otherwise → **422 `INVOICE_REQUIRED`**.
2. Validate the 9 invoice preconditions (see F.1). Any failure → **422 `INVOICE_PRECONDITIONS_NOT_MET`**.
3. Determine `version_number = max(existing version_number) + 1`.
4. Determine `due_date = sales_order.proposed_lay_date − 2 calendar days` (proposed_lay_date is guaranteed non-null by precondition 7). The latest existing invoice's `due_date` is NOT carried forward on a manual rewrite — manual rewrite is what makes new live edits (including a changed proposed lay date) official. May be earlier than `invoice_date` — that is valid (see G.2).
5. Snapshot from current live order state (NOT from any previous snapshot):
   - `invoice_date = current date`
   - `details_of_sale_snapshot = sales_order.details_of_sale`
   - `sale_price_ex_gst`, `sale_price_inc_gst` — from live `order_financial_summary`
   - `total_paid = round(sum(payment_transaction.amount for this order), 2)` (current at this moment)
   - `balance_due = round(sale_price_inc_gst − total_paid, 2)` — must be ≥ 0
   - `created_by_user_id = session.user_id`
6. Generate a **new** PDF, write a new `stored_file` row, link via `invoice.stored_file_id`.
7. Persist the new `invoice` row. Old versions are not modified.
8. Steps 5–7 happen in one DB transaction. PDF / file-write failure → full rollback.

**Validation**
- `orderId` is a positive integer.
- Request body must be empty (`{}`). Any request fields, including `due_date`, return 400 `VALIDATION_FAILED`.

**Tenant / session / store / order scoping.** Same as D.1.

**LAID lock.** **Blocked when LAID** → **422 `ORDER_LOCKED`** (conventions §16: manual Rewrite Invoice is in the LAID-blocked list).

**Status codes**
| Code | When |
|------|------|
| 201 | New invoice version created |
| 400 | Malformed JSON, invalid `orderId` format, non-empty request body (any field including `due_date` → `VALIDATION_FAILED`) |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in scope, or business slug not found / inactive |
| 422 | No prior invoice (`INVOICE_REQUIRED`); order is LAID (`ORDER_LOCKED`); preconditions not met (`INVOICE_PRECONDITIONS_NOT_MET`) |
| 500 | Unexpected (transactional rollback) |

**Error example — no prior invoice**
```json
{
  "error": {
    "code": "INVOICE_REQUIRED",
    "message": "Create the first invoice before rewriting."
  }
}
```

---

### D.3 GET /api/v1/{slug}/orders/{orderId}/invoices/current

**Purpose.** Return the **current (latest) invoice** snapshot for the order. MVP exposes
only the current invoice; there is no history list and no per-version detail (deferred — see D.5).

**Scope class.** Standard protected.

**Path params.** `orderId` — positive integer.

**Query params.** None.

**Request DTO.** None.

**Response DTO — 200 OK**
```json
{
  "data": {
    "invoice": { "...see E.2 invoice_detail..." }
  }
}
```

The current invoice is the `invoice` row with the highest `version_number` for the order.
`version_number` is an internal field returned in the snapshot; the MVP frontend does not
surface it (no revision dropdown).

**Business rules / scoping**
- Resolve the current invoice = `max(version_number)` row in `invoice` for this order.
- Order belongs to session's `(business_id, store_id)`. Otherwise → 404.
- If no invoice exists yet for this order → 404 `INVOICE_NOT_FOUND`.

**Validation.** `orderId` positive integer.

**LAID lock.** GET allowed regardless of order status.

**Status codes**
| Code | When |
|------|------|
| 200 | OK |
| 400 | Invalid `orderId` format |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in scope, no invoice exists yet (`INVOICE_NOT_FOUND`), or business slug not found / inactive |
| 500 | Unexpected |

---

### D.4 GET /api/v1/{slug}/orders/{orderId}/invoices/current/file

**Purpose.** Stream the PDF binary for the **current (latest) invoice**. Runs under the
file-binary exception to conventions §3.

**Scope class.** Standard protected. Runs under the file-binary exception to conventions §3.

**Path params.** `orderId` — positive integer.

**Query params.** None.

**Request DTO.** None.

**Response — 200 OK**
- Body: raw PDF bytes.
- `Content-Type: application/pdf`.
- `Content-Disposition: inline; filename="invoice-{order_number}-v{version_number}.pdf"` — backend builds the filename from the locked order_number and the current invoice's `version_number` (e.g. `invoice-SYD-CBD.LC1.00042-v3.pdf`).
- `Content-Length`: file size.

This is **not** a JSON response (file-binary exception). Errors at this endpoint use the standard JSON error wrapper.

**Business rules**
- Resolve the current invoice = `max(version_number)` row for the order; stream its linked `stored_file`.
- Order belongs to session's store; if no invoice exists yet → 404 `INVOICE_NOT_FOUND`.
- `stored_file.storage_path` is internal and never returned in any field.
- Stream from the configured storage location. If the file row exists but the underlying file is missing on disk → 500.

**Validation.** `orderId` positive integer.

**LAID lock.** GET allowed regardless of order status.

**Status codes**
| Code | When |
|------|------|
| 200 | PDF streamed |
| 400 | Invalid `orderId` format |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in scope, no invoice exists yet (`INVOICE_NOT_FOUND`), or business slug not found / inactive |
| 500 | Unexpected (including file missing on disk) |

---

### D.5 Deferred to post-MVP — invoice history & old-version access

The following endpoints are **deferred to post-MVP** and are NOT implemented in the MVP.
The schema supports them (multiple `invoice` rows per order with `version_number`), so they
can be added later **without a migration**:

- `GET /api/v1/{slug}/orders/{orderId}/invoices` — list all invoice versions (history).
- `GET /api/v1/{slug}/orders/{orderId}/invoices/{invoiceId}` — read a specific invoice version.
- `GET /api/v1/{slug}/orders/{orderId}/invoices/{invoiceId}/file` — download a specific older version's PDF.

Still deferred (multi-version history only): the signed / accepted invoice revision **history**,
the invoice-page revision dropdown, and viewing/downloading **old** signed PDFs. The
signed-invoice **acceptance + signature + email** workflow itself is **not** deferred — it is
MVP **Phase 13** (Accept / Resend / signature endpoints; see
`docs/API-Contracts-Phase13-Acceptance-Signature-Email.md`).

MVP clients use **D.3** (current invoice), **D.4** (current invoice PDF), and the Phase 13
current-invoice acceptance endpoints. No older-version invoice or signature endpoint is exposed.

---

### D.6 GET /api/v1/{slug}/orders/{orderId}/payments

**Purpose.** List payments for the order plus the current **official** payment summary.

**Scope class.** Standard protected.

**Path params.** `orderId` — positive integer.

**Query params**

| Name | Type | Required | Default | Notes |
|------|------|----------|---------|-------|
| `page` | int | no | 1 | Min 1 |
| `page_size` | int | no | 20 | Min 1, max 100 |

**Request DTO.** None.

**Response DTO — 200 OK**
```json
{
  "data": {
    "payments": [
      {
        "payment_transaction_id": 1,
        "payment_method": "EFTPOS",
        "amount": 500.00,
        "payment_reference": "EFTPOS-20260414",
        "created_at": "2026-04-14T11:05:00"
      }
    ],
    "payment_summary": {
      "total_paid": 500.00,
      "balance_due": 424.00
    },
    "pagination": {
      "page": 1,
      "page_size": 20,
      "total_items": 1,
      "total_pages": 1
    }
  }
}
```

**Field rules**
- Each payment row uses the shared DTO `payment_transaction_read` (see E.3).
- `payment_summary` uses the shared DTO `payment_summary` (see E.4).
- `total_paid` = sum of all `payment_transaction.amount` for the order, rounded to 2 decimals.
- `balance_due` = the **latest official invoice version's `balance_due`** (i.e. `latest_invoice.balance_due`). This is the official outstanding balance the customer owes; it does NOT reflect unsent live order edits made after the latest invoice.
- If no invoice exists yet for this order: `balance_due` is `null`. `total_paid` is normally `0.00` in this state (and is in any case the literal sum across `payment_transaction`).

**Business rules / scoping**
- Payments scoped to the order. Default ordering: `created_at` descending (newest first).
- Empty payments → `"payments": []`.

**Validation.** `orderId` positive integer; pagination params in range.

**LAID lock.** GET allowed regardless of order status.

**Status codes**
| Code | When |
|------|------|
| 200 | OK |
| 400 | Invalid `orderId` format, invalid pagination |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in scope, or business slug not found / inactive |
| 500 | Unexpected |

---

### D.7 POST /api/v1/{slug}/orders/{orderId}/payments

**Purpose.** Record a payment against the **current invoice's balance** and atomically regenerate the current invoice reflecting the updated payment state. The regenerated current invoice is a payment-receipt snapshot — it carries forward the current invoice's sale snapshot fields and updates only `total_paid` and `balance_due`. It must NOT silently make unsent live order edits official. (Internally the backend appends a new `invoice` row / `version_number`; the MVP exposes this only as the updated current invoice — no history.)

**Scope class.** Standard protected.

**Path params.** `orderId` — positive integer.

**Request DTO**
```json
{
  "payment_method": "EFTPOS",
  "amount": 500.00,
  "payment_reference": "EFTPOS-20260422"
}
```

**Field rules**
- `payment_method` — required; must be one of `CASH`, `CREDIT_CARD`, `EFTPOS`, `BANK_TRANSFER` (conventions §13; locked schema enum).
- `amount` — required; money (DECIMAL(10,2)); must be > 0 (conventions §13: no zero-value payments).
- `payment_reference` — optional; if present and non-null, non-blank after trim. Maps to nullable `payment_transaction.payment_reference`.
- `gateway_transaction_id`, `response_status`, `response_message` — **never accepted from the client**. They remain `null` on MVP manual payments. Sending any → 400 `VALIDATION_FAILED`.

**Response DTO — 201 Created**
```json
{
  "data": {
    "payment_transaction": {
      "payment_transaction_id": 2,
      "payment_method": "EFTPOS",
      "amount": 500.00,
      "payment_reference": "EFTPOS-20260422",
      "created_at": "2026-04-22T14:30:00"
    },
    "payment_summary": {
      "total_paid": 500.00,
      "balance_due": 424.00
    },
    "current_invoice": { "...see E.1 current_invoice_summary..." }
  },
  "message": "Payment recorded. Current invoice updated."
}
```

**Business rules** (conventions §13 / §14, with payment-balance correction in section G)
1. Verify at least one invoice exists for this order. Otherwise → **422 `INVOICE_REQUIRED`** (payment is invoice-first).
2. Identify the latest official invoice: `latest_invoice = max(version_number) row in invoice for this order`.
3. Validate amount against the latest official invoice's outstanding balance:
   - if `amount > latest_invoice.balance_due` → **422 `PAYMENT_EXCEEDS_BALANCE`**.
   - DB CHECK requires `balance_due ≥ 0`; MVP does not support overpayment.
4. Persist `payment_transaction` row with the supplied values. Backend leaves `gateway_transaction_id`, `response_status`, `response_message` as `null`.
5. Compute new `total_paid_after = round(sum(payment_transaction.amount for this order), 2)` (including the just-added row).
6. Regenerate the current invoice (internally a new `invoice` row with `version_number = max + 1`). **Sale snapshot fields are carried forward from the current invoice — not re-derived from current live order state.** This is the key rule that prevents unsent live edits from being silently made official.
   - `version_number = max(existing version_number) + 1`
   - `invoice_date = current date`
   - `due_date = latest_invoice.due_date` (carried forward)
   - `details_of_sale_snapshot = latest_invoice.details_of_sale_snapshot` (carried forward)
   - `sale_price_ex_gst = latest_invoice.sale_price_ex_gst` (carried forward)
   - `sale_price_inc_gst = latest_invoice.sale_price_inc_gst` (carried forward)
   - `total_paid = total_paid_after`
   - `balance_due = round(sale_price_inc_gst − total_paid, 2)` — must be ≥ 0 (DB CHECK; guaranteed by step 3)
   - `created_by_user_id = session.user_id`
7. Generate a new PDF, write `stored_file`, link via `invoice.stored_file_id`.
8. Persist the new `invoice` row.
9. **Atomicity:** steps 4–8 happen in a single DB transaction. If PDF generation, file write, or the new invoice insert fails, the payment insert is rolled back. The salesperson must retry; partial state is never persisted.

**Validation**
- `orderId` is a positive integer.
- Body is valid JSON.
- `payment_method` is one of the four enum values.
- `amount` is a money value > 0.
- `payment_reference`, when non-null, is non-blank.
- No forbidden gateway fields in the body.

**Tenant / session / store / order scoping.** Standard 7 checks + order-belongs-to-store.

**LAID lock.** **Allowed when LAID** (conventions §16: add payment is in the LAID-allowed list).

**Status codes**
| Code | When |
|------|------|
| 201 | Payment recorded and auto invoice version created |
| 400 | Malformed JSON, invalid `orderId`, missing or invalid `payment_method`, missing or non-positive `amount`, blank `payment_reference`, forbidden gateway fields |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in scope, or business slug not found / inactive |
| 422 | No prior invoice (`INVOICE_REQUIRED`); amount exceeds balance (`PAYMENT_EXCEEDS_BALANCE`) |
| 500 | Unexpected (atomic rollback) |

**Error example — payment exceeds balance**
```json
{
  "error": {
    "code": "PAYMENT_EXCEEDS_BALANCE",
    "message": "Payment amount cannot exceed the current balance due."
  }
}
```

---

### D.10 POST /api/v1/{slug}/orders/{orderId}/payments/{paymentTransactionId}/void  *(Phase 15D)*

**Purpose.** **Soft-void** a recorded payment and atomically regenerate the current invoice — the exact mirror of recording a payment (D.7). A void is **never a hard delete**: the original `payment_transaction` row is preserved (an immutable financial record) and stays visible in payment history. The voided payment is excluded from the **active** `total_paid`, so `total_paid` drops and `balance_due` rises. There is **no request body**.

**Scope class.** Standard protected. Additionally enforces `payment_transaction.order_id = path.orderId` (the payment is resolved only after the order is in scope).

**Path params.** `orderId` — positive integer. `paymentTransactionId` — positive integer.

**Response DTO — 201 Created** (same shape as D.7)
```json
{
  "data": {
    "payment_transaction": {
      "payment_transaction_id": 1,
      "payment_method": "EFTPOS",
      "amount": 500.00,
      "payment_reference": "EFTPOS-20260414",
      "created_at": "2026-04-14T11:05:00",
      "voided_at": "2026-04-22T15:10:00",
      "voided_by_name": "Liam Carter"
    },
    "payment_summary": { "total_paid": 0.00, "balance_due": 924.00 },
    "current_invoice": { "...see E.1 current_invoice_summary..." }
  },
  "message": "Payment voided. Current invoice updated."
}
```

**Business rules**
1. Scope the order (`FOR UPDATE`). Missing / cross-store / cross-business → **404 `ORDER_NOT_FOUND`** (no existence leak).
2. Resolve the payment by `(paymentTransactionId, orderId)`. Missing / belongs to another order → **404 `PAYMENT_NOT_FOUND`**.
3. If the payment is already voided → **409 `PAYMENT_ALREADY_VOIDED`** (idempotent; a concurrent double-void is caught by the `WHERE voided_at IS NULL` update guard and also returns 409 — no second invoice version is created).
4. Require an existing current invoice, else **422 `INVOICE_REQUIRED`** (defensive).
5. Stamp `voided_at = now`, `voided_by_user_id = session.user_id` (the **session actor**, NOT the order-bound salesperson).
6. Recompute `total_paid_after = round(sum(active payment_transaction.amount), 2)` (excludes the just-voided row) and regenerate the current invoice (new `version_number = max + 1`) carrying forward the sale snapshot fields and — when the current invoice was accepted — the acceptance/signature metadata (the customer does **NOT** re-sign; the same signature `stored_file` is referenced).
7. The new version starts `last_emailed_at = null` and the dashboard mirror (`sales_order.last_emailed_at`) is reset to null. **No email is sent.** Manual Resend remains available.
8. **Atomicity:** the void stamp + new invoice version + PDF write are one DB transaction with a rollback-cleanup hook (no orphan PDF).

**LAID lock.** **Allowed when LAID** (the inverse of recording a payment — conventions §16).

**Status codes**
| Code | When |
|------|------|
| 201 | Payment voided and a new current invoice version created. No email sent. |
| 400 | Invalid `orderId` or `paymentTransactionId` (`VALIDATION_FAILED`) |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in scope (`ORDER_NOT_FOUND`) or payment not in this order (`PAYMENT_NOT_FOUND`); or business slug not found / inactive (`NOT_FOUND`) |
| 409 | Payment already voided (`PAYMENT_ALREADY_VOIDED`) |
| 422 | No current invoice (`INVOICE_REQUIRED`) |
| 500 | Unexpected (atomic rollback) |

---

## E. Shared DTOs

### E.1 `current_invoice_summary`

Used inside D.7's `current_invoice`. (This same row shape is reserved for the deferred
post-MVP invoice-history list — see D.5.)

```json
{
  "invoice_id": 4,
  "version_number": 3,
  "invoice_date": "2026-04-22",
  "due_date": "2026-05-06",
  "sale_price_inc_gst": 924.00,
  "total_paid": 500.00,
  "balance_due": 424.00,
  "created_by_user_id": 1,
  "created_at": "2026-04-22T14:30:00",
  "pdf_download_path": "/api/v1/aussiefloors/orders/1/invoices/current/file"
}
```

**Field rules**
- All fields sourced from `invoice` except `pdf_download_path`, which is the relative URL to D.4 (the current invoice PDF, built backend-side).
- Money fields with two decimal places. `due_date` may be `null` only if the invoice was created before the due-date rule was applied; new invoices always have a non-null `due_date`.
- `due_date` semantics: for manual versions (D.1, D.2) it is `proposed_lay_date − 2 calendar days`. For payment-driven versions (D.7) it is carried forward from the latest official invoice. It may be earlier than `invoice_date` — see G.2.
- `stored_file.storage_path` is never exposed; only the API download path is.

### E.2 `invoice_detail`

Used in D.1 / D.2 / D.3 responses (the current invoice snapshot).

```json
{
  "invoice_id": 4,
  "order_id": 1,
  "version_number": 3,
  "invoice_date": "2026-04-22",
  "due_date": "2026-05-06",
  "details_of_sale_snapshot": "Supply and install plush carpet to lounge and dining rooms.",
  "sale_price_ex_gst": 840.00,
  "sale_price_inc_gst": 924.00,
  "total_paid": 500.00,
  "balance_due": 424.00,
  "created_by_user_id": 1,
  "created_at": "2026-04-22T14:30:00",
  "pdf_download_path": "/api/v1/aussiefloors/orders/1/invoices/current/file"
}
```

**Field rules**
- All fields from the locked `invoice` table except `stored_file_id` (replaced by `pdf_download_path`).
- `due_date` semantics: for manual versions (D.1, D.2) it is `proposed_lay_date − 2 calendar days`. For payment-driven versions (D.7) it is carried forward from the latest official invoice. It may be earlier than `invoice_date` — see G.2.
- Money fields with two decimal places.

### E.3 `payment_transaction_read`

Used in D.6 list rows and D.7 response.

```json
{
  "payment_transaction_id": 1,
  "payment_method": "EFTPOS",
  "amount": 500.00,
  "payment_reference": "EFTPOS-20260414",
  "created_at": "2026-04-14T11:05:00",
  "voided_at": null,
  "voided_by_name": null
}
```

**Field rules**
- Sourced from `payment_transaction`. Gateway fields (`gateway_transaction_id`, `response_status`, `response_message`) are NOT exposed in MVP — they remain backend-only and stay `null` for manual payments.
- `payment_reference` may be `null`.
- **Phase 15D void markers** (always present): `voided_at` — timestamp the payment was voided, or `null` when active. `voided_by_name` — display name (`app_user` first + last) of the session actor who voided it, or `null` when active. The raw `voided_by_user_id` is never exposed. A voided payment stays visible in payment history (D.6) but is excluded from the active `total_paid`.

### E.4 `payment_summary`

Used in D.6 and D.7 responses.

```json
{
  "total_paid": 500.00,
  "balance_due": 424.00
}
```

**Field rules**
- `total_paid` — sum of all **active (non-voided)** `payment_transaction.amount` for the order, rounded to 2 decimals (Phase 15D: voided payments are excluded).
- `balance_due` — `latest_invoice.balance_due` (latest **official** invoice version, regardless of whether it was created manually or auto-created by a payment/void). May be `null` on D.6 when no invoice exists yet. On D.7 / D.10-void an invoice always exists (they return the auto-created version), so `balance_due` is never `null` there.

---

## F. Global Chunk 4 Rules

**F.1 Invoice precondition rules (conventions §14).** Checked only on D.1 (Create) and D.2 (manual Rewrite). Not checked on D.7 (payment auto-version), on routine order mutations, or on any read endpoint.

The 9 preconditions:
1. `order_customer.first_name` exists and is non-blank.
2. `order_customer.last_name` exists and is non-blank.
3. `order_address` row with `address_type = 'INSTALLATION'` exists with all required fields filled.
4. `order_address` row with `address_type = 'BILLING'` exists with all required fields filled.
5. At least one priced line exists in `order_product_line` or `order_charge_line`.
6. `sales_order.details_of_sale` is present and non-blank.
7. `sales_order.proposed_lay_date` is present.
8. `sales_order.lay_date_status` is present.
9. `order_financial_summary.final_sale_price_inc_gst` is valid and `> 0`.

Failure response: 422 with `error.code = "INVOICE_PRECONDITIONS_NOT_MET"` and `error.details[]` listing every failing item with `section`, `field`, and `message`.

> **Phase 13 delta.** Phase 13 adds a **customer-email gate** to D.1 (Create) and D.2 (Rewrite), evaluated before these 9 preconditions: a missing/blank email → 422 `CUSTOMER_EMAIL_REQUIRED`, an invalid email → 422 `CUSTOMER_EMAIL_INVALID`. The same email gate also guards the Phase 13 Accept / Resend endpoints. See `docs/API-Contracts-Phase13-Acceptance-Signature-Email.md` §Error contract.

**F.2 Invoice creation — current invoice (MVP).**

MVP exposes only the current/latest invoice. Internally the backend still writes `invoice`
rows with an incrementing `version_number` (schema unchanged), but clients only ever see the
current invoice (D.3 / D.4). History listing and old-version access are deferred (see D.5).

- **D.1 Create Invoice** = first invoice, `version_number = 1`. Snapshots current live order state. Allowed when LAID (if no prior invoice).
- **D.2 Rewrite Invoice** = regenerates the current invoice (internally next `version_number`). Snapshots current live order state — this is what makes new live edits official. Blocked when LAID.
- **D.7 payment** = regenerates the current invoice (internally next `version_number`). **Carries forward sale snapshot fields from the current invoice**, updates only `total_paid` / `balance_due`. Allowed when LAID.

In all paths:
- Older internal invoice rows are never modified (and are simply not exposed in MVP).
- Each invoice row has its own `stored_file_id` (UNIQUE constraint).
- The current invoice is always rebuilt fresh (new `invoice` row + new PDF); it is never built from a previous PDF.
- Invoice rows do not store `order_number`. The PDF and the download filename use `sales_order.order_number` (locked format `{store_code}.{salesperson_code}.{seq_padded_5}`).

**F.3 Payment rules (conventions §13, with corrected balance semantics).**
- Payment is invoice-first: D.7 requires at least one existing invoice → otherwise `INVOICE_REQUIRED`.
- Payment methods: only `CASH`, `CREDIT_CARD`, `EFTPOS`, `BANK_TRANSFER`.
- `amount > 0`; no zero-value payments.
- `amount ≤ latest_invoice.balance_due` (the **latest official** invoice version's balance, NOT a balance derived from current live order state); otherwise `PAYMENT_EXCEEDS_BALANCE`. No overpayment in MVP — DB CHECK requires `balance_due ≥ 0`.
- Frontend never sends `gateway_transaction_id`, `response_status`, `response_message`. They stay `null` for MVP manual payments.
- Payments are immutable: no edit, no delete in MVP.
- Adding a payment does not change product lines, charge lines, sale price, GP, costs, or any unsent live order edits. It only updates `total_paid` and `balance_due` via the auto-created invoice version (which carries the sale snapshot forward).

**F.4 Payment regenerates the current invoice.**
- D.7 atomically: inserts the `payment_transaction` row, regenerates the current `invoice` (internally a new row / `version_number`) that carries the current sale snapshot forward and updates payment fields, generates and stores its PDF.
- The regenerated current invoice must not silently make unsent live order edits official. Sale snapshot fields (`details_of_sale_snapshot`, `sale_price_ex_gst`, `sale_price_inc_gst`, `due_date`) are copied from the current invoice. To make new live edits official, the salesperson must use D.2 Rewrite Invoice first.
- All side effects in a single DB transaction. Any failure → full rollback. The salesperson must retry; partial state is never persisted.
- D.7 response includes the created payment, the updated `payment_summary`, and the updated `current_invoice` metadata (E.1 shape).

> **Phase 13 delta.** When the current invoice has already been **accepted**, the payment-regenerated version additionally **carries the acceptance/signature metadata forward** (`accepted_at`, `accepted_customer_name`, `accepted_signature_file_id`) — the customer does **not** re-accept — and the updated signed PDF is **automatically re-emailed**, updating `last_emailed_at`. That email is **best-effort and non-fatal**: if the email provider fails, the **payment and the new invoice version still succeed**, the acceptance metadata still carries forward, `last_emailed_at` stays **null**, `D.7` **still returns `201` (never `502`)**, and the success message tells the user to Re-send (`D.9`); the backend **must not** roll back the payment because the email failed. (This mirrors Accept, where acceptance persists even if the email fails; `502`/`EMAIL_SEND_FAILED` is reserved for an explicit Re-send.) When the current invoice has **not** been accepted, the payment is still allowed, the new version stays unaccepted, and **no** email is sent. In all of these cases the dashboard mirror `sales_order.last_emailed_at` is kept equal to the **current** invoice's `last_emailed_at` (including `null` when the new version is unemailed), so the dashboard never shows a stale emailed time from a previous version — see the Phase 13 contract §11.1 mirror invariant. See `docs/API-Contracts-Phase13-Acceptance-Signature-Email.md` §Payment interaction rules. (E.1 / E.2 also gain the acceptance/email fields in Phase 13.)

**F.5 Invoice due_date rule.**
- Manual invoice versions (D.1 Create, D.2 Manual Rewrite) derive `due_date = sales_order.proposed_lay_date − 2 calendar days`. The frontend never sends `due_date`.
- Payment-driven invoice versions (D.7) carry forward `due_date = latest_invoice.due_date` unchanged. They never re-derive `due_date` from current live `proposed_lay_date`. This prevents a payment from silently making an unsent install-date change official.
- `due_date` is independent of payment date. A customer may pay after `due_date`.
- `due_date` may be earlier than `invoice_date` (e.g. a payment-driven version is generated after the original due date). This is valid — see G.2.

**F.6 PDF / file download rule (current invoice).**
- D.4 streams the current invoice's `application/pdf` raw bytes under the file-binary exception (conventions §3).
- `Content-Disposition: inline; filename="invoice-{order_number}-v{version_number}.pdf"` (backend-built; uses the locked order number format and the current invoice's version number).
- `stored_file.storage_path` is server-internal and never exposed. The only field clients see is `pdf_download_path`, which is the relative URL to D.4.
- File-missing-on-disk → 500 (operational error).
- Errors at D.4 use the standard JSON error wrapper.
- Downloading older invoice versions is deferred to post-MVP (see D.5).

**F.7 Tenant / store / order / resource scoping (conventions §9).**
- Every Chunk 4 endpoint enforces the 7 checks from §9.
- Order-level endpoints additionally enforce `sales_order.business_id = session.business_id` AND `sales_order.store_id = session.store_id`.
- The current-invoice endpoints (D.3, D.4) resolve the current invoice as the latest `invoice` row for the order in scope; it always belongs to the order. If no invoice exists → 404 `INVOICE_NOT_FOUND`.
- Payment writes additionally enforce that the order in the path belongs to session's `(business_id, store_id)`. Payment reads in D.6 are scoped through the order.
- Cross-tenant / cross-store / cross-order / cross-resource misses always return 404, never 403.

**F.8 LAID lock matrix (conventions §16).**

| Endpoint | Allowed when LAID? |
|----------|--------------------|
| D.1 POST invoices (Create v1) | **Yes** if no invoice exists yet AND preconditions pass (rationale in D.1) |
| D.2 POST invoices/rewrite | No → 422 `ORDER_LOCKED` (conventions §16: Rewrite Invoice is blocked) |
| D.3 GET invoices/current (current detail) | Yes |
| D.4 GET invoices/current/file (current PDF) | Yes |
| D.6 GET payments | Yes |
| D.7 POST payments | **Yes** (conventions §16: add payment is allowed; the current invoice is regenerated as the side effect of allowed payments) |
| Phase 13 POST invoices/current/accept | **Yes** (acceptance parallels payment, not rewrite — a laid job's invoice may still need signing/emailing; carries the snapshot forward, never makes live edits official). See Phase 13 contract. |
| Phase 13 POST invoices/current/resend | **Yes** (re-emails the existing accepted PDF) |
| Phase 13 GET invoices/current/signature | **Yes** (read) |

**F.9 Backend-controlled fields.** Client never sends or controls:
- `business_id`, `store_id`, `user_id`, `order_id`, `invoice_id`, `payment_transaction_id` in any body.
- `version_number`, `invoice_date`, `details_of_sale_snapshot`, `sale_price_ex_gst`, `sale_price_inc_gst`, `total_paid`, `balance_due`, `stored_file_id`, `created_by_user_id` on invoices.
- `gateway_transaction_id`, `response_status`, `response_message` on payments.
- `created_at` everywhere.

---

## G. Alignment Notes

**G.1 Manual vs payment-driven invoice versions.**

Chunk 4 follows the locked conventions rule that manual invoice versions and payment-driven invoice versions behave differently.

- Manual versions created by Create Invoice or Rewrite Invoice snapshot the current live order state and make live edits official.
- Payment-driven versions created by Add Payment carry forward the latest official invoice sale snapshot and update only `total_paid` and `balance_due`.
- Payment-driven versions must not silently include unsent live order edits.
- MVP note: these distinctions are internal. The MVP exposes only the current/latest invoice (no history list, no version selection — see A.1).

**G.2 Invoice due_date rule.**

Chunk 4 follows the locked conventions rule:

- Manual invoice versions derive `due_date = proposed_lay_date − 2 calendar days`.
- Payment-driven versions carry forward the latest official invoice's `due_date`.
- `due_date` may be earlier than `invoice_date`.
- Customer payment date does not change `due_date`.

**G.3 Invoice due_date DB constraint.**

The invalid `chk_invoice_due_date_gte_invoice_date` constraint has been removed from V3.

Required locked DB state:
- no CHECK constraint requiring `due_date >= invoice_date`
- no replacement date-comparison CHECK for MVP

Reason: `due_date` is based on `proposed_lay_date`, not `invoice_date`.

**G.4 Operations Portal — out of scope for Sales Portal API.**

Operations Portal catalog management is a separate future product and a separate future API contract. It is not under `/api/v1/{slug}/operations/...` in this Chunk 4. For the Sales Portal MVP, product and charge catalog data will be manually loaded into `store_product` and `store_charge` during onboarding/admin setup. Sales Portal consumes that catalog only through the locked Chunk 3 `available-products` and `available-charges` endpoints. This is not a contract gap — it is a deliberate separation and is documented here for clarity.

---

## H. Remaining Open Questions

None. Chunk 4 resolves cleanly against the locked conventions doc, locked Chunks 1–3, the locked DB schema (V3 already corrected), and the relevant screenshots. Section G records the alignment notes for reference.