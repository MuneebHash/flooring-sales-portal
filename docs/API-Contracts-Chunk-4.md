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

**Order number format (locked):** `{store_code}.{salesperson_code}.{order_sequence_number_padded_5}` — e.g. `001.LW1.00042`. Backend-generated; frontend never sends it. Used in invoice PDF filenames.

**File-binary exception (accepted in conventions §3):** file upload endpoints may accept `multipart/form-data`; file download endpoints may return raw binary bytes. Chunk 4 uses this exception only for the invoice PDF download endpoint (D.5).

---

## A. Chunk 4 Scope — Included

- Invoice creation (version 1).
- Manual Rewrite Invoice (next official version, snapshots current live order state).
- Invoice list / version history per order.
- Invoice detail / snapshot metadata.
- Invoice PDF binary download.
- Payment list per order with current official payment summary.
- Payment recording (records money against the **latest official invoice**, atomically creates a payment-driven invoice version that updates `total_paid` / `balance_due` only).

---

## B. Chunk 4 Scope — Excluded / Deferred

Already locked in earlier chunks — not reopened:
- Auth, dashboard, status update (Chunk 1).
- Order create / customer / addresses / details-of-sale (Chunk 2).
- Product/charge lines, sale price override/reset, notes, photo attachments (Chunk 3).

Out of MVP scope or deferred:
- **Operations Portal catalog management is deferred to a separate future Operations Portal API contract. For the Sales Portal MVP, product and charge catalog data will be manually loaded into `store_product` and `store_charge` during onboarding/admin setup. Sales Portal consumes that catalog only through the locked Chunk 3 `available-products` and `available-charges` endpoints.** Operations Portal will have its own login/auth flow, its own users (owners / managers / catalog admins), and its own contract; it is not a sub-path of the Sales Portal API.
- Payment edit / delete (conventions §13: not allowed in MVP).
- Refunds, surcharges, finance products, cheque, partial reversals.
- Stripe / gateway-driven flows (`gateway_transaction_id`, `response_status`, `response_message` remain nullable backend fields for manual MVP payments).
- Invoice email / send-to-customer endpoints (not yet locked in conventions).
- Standalone invoice PDF (re)generation endpoints — PDFs are produced only as a side effect of Create Invoice, manual Rewrite Invoice, and POST Payment.
- `SIGNATURE` attachment kind workflow (deferred from Chunk 3; full e-sign workflow not in MVP).

---

## C. Chunk 4 Endpoint List

All endpoints are **Standard protected** (all 7 checks from conventions §9). Every order-level endpoint additionally enforces `sales_order.business_id = session.business_id` AND `sales_order.store_id = session.store_id`. Every invoice-level endpoint additionally enforces `invoice.order_id = path.orderId`. Payment-level reads/writes additionally enforce `payment_transaction.order_id = path.orderId`.

| # | Method | Path | Purpose |
|---|--------|------|---------|
| 1 | POST | `/api/v1/{slug}/orders/{orderId}/invoices` | Create invoice version 1 |
| 2 | POST | `/api/v1/{slug}/orders/{orderId}/invoices/rewrite` | Manually create the next official invoice version from current live order state |
| 3 | GET  | `/api/v1/{slug}/orders/{orderId}/invoices` | List all invoice versions for the order |
| 4 | GET  | `/api/v1/{slug}/orders/{orderId}/invoices/{invoiceId}` | Read a single invoice snapshot's metadata |
| 5 | GET  | `/api/v1/{slug}/orders/{orderId}/invoices/{invoiceId}/file` | Download the PDF binary |
| 6 | GET  | `/api/v1/{slug}/orders/{orderId}/payments` | List payments + current official payment summary |
| 7 | POST | `/api/v1/{slug}/orders/{orderId}/payments` | Record a payment against the latest official invoice; atomically auto-create the next invoice version |

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

**Purpose.** Manually create the next **official** invoice version. Re-snapshots the current live order state as the new official sale snapshot, plus current total payments.

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

### D.3 GET /api/v1/{slug}/orders/{orderId}/invoices

**Purpose.** Return the order's invoice version history.

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
  "data": [
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
      "pdf_download_path": "/api/v1/aussiefloors/orders/1/invoices/4/file"
    }
  ],
  "pagination": {
    "page": 1,
    "page_size": 20,
    "total_items": 3,
    "total_pages": 1
  }
}
```

Each row uses the shared DTO `invoice_history_row` (see E.1).

**Business rules / scoping**
- Invoices scoped to the order (and via the order, to session's store).
- **Default ordering:** `version_number` descending (newest version first).
- Empty result → 200 with `"data": []`.

**Validation.** `orderId` positive integer; `page` ≥ 1; `page_size` ∈ [1, 100].

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

### D.4 GET /api/v1/{slug}/orders/{orderId}/invoices/{invoiceId}

**Purpose.** Return the snapshot metadata for a specific invoice version.

**Scope class.** Standard protected.

**Path params.** `orderId`, `invoiceId` — positive integers.

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

**Business rules / scoping**
- Verify invoice belongs to the order. Otherwise → 404 `INVOICE_NOT_FOUND` (no cross-order leak).
- Order belongs to session's `(business_id, store_id)`. Otherwise → 404.

**Validation.** `orderId`, `invoiceId` positive integers.

**LAID lock.** GET allowed regardless of order status.

**Status codes**
| Code | When |
|------|------|
| 200 | OK |
| 400 | Invalid `orderId` / `invoiceId` format |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in scope, or invoice not on this order, or business slug not found / inactive |
| 500 | Unexpected |

---

### D.5 GET /api/v1/{slug}/orders/{orderId}/invoices/{invoiceId}/file

**Purpose.** Stream the PDF binary for the invoice version.

**Scope class.** Standard protected. Runs under the file-binary exception to conventions §3.

**Path params.** `orderId`, `invoiceId` — positive integers.

**Query params.** None.

**Request DTO.** None.

**Response — 200 OK**
- Body: raw PDF bytes.
- `Content-Type: application/pdf`.
- `Content-Disposition: inline; filename="invoice-{order_number}-v{version_number}.pdf"` — backend builds the filename using the locked order_number format (e.g. `invoice-001.LW1.00042-v3.pdf`).
- `Content-Length`: file size.

This is **not** a JSON response (file-binary exception). Errors at this endpoint use the standard JSON error wrapper.

**Business rules**
- Verify invoice belongs to the order, order belongs to session's store.
- `stored_file.storage_path` is internal and never returned in any field.
- Stream from the configured storage location. If the file row exists but the underlying file is missing on disk → 500.

**Validation.** `orderId`, `invoiceId` positive integers.

**LAID lock.** GET allowed regardless of order status.

**Status codes**
| Code | When |
|------|------|
| 200 | PDF streamed |
| 400 | Invalid `orderId` / `invoiceId` format |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in scope, or invoice not on this order, or business slug not found / inactive |
| 500 | Unexpected (including file missing on disk) |

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

**Purpose.** Record a payment against the **latest official invoice's balance** and atomically create the next invoice version reflecting the updated payment state. The auto-created version is a payment-receipt snapshot — it carries forward the latest official sale snapshot fields and updates only `total_paid` and `balance_due`. It must NOT silently make unsent live order edits official.

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
    "auto_invoice_version": { "...see E.1 invoice_history_row..." }
  },
  "message": "Payment recorded. Invoice version created."
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
6. Snapshot a new payment-driven invoice version. **Sale snapshot fields are carried forward from the latest official invoice — not re-derived from current live order state.** This is the key correction to prevent unsent live edits from being silently made official.
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

## E. Shared DTOs

### E.1 `invoice_history_row`

Used in D.3 list responses and inside D.7's `auto_invoice_version`.

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
  "pdf_download_path": "/api/v1/aussiefloors/orders/1/invoices/4/file"
}
```

**Field rules**
- All fields sourced from `invoice` except `pdf_download_path`, which is the relative URL to D.5 (built backend-side).
- Money fields with two decimal places. `due_date` may be `null` only if the invoice was created before the due-date rule was applied; new invoices always have a non-null `due_date`.
- `due_date` semantics: for manual versions (D.1, D.2) it is `proposed_lay_date − 2 calendar days`. For payment-driven versions (D.7) it is carried forward from the latest official invoice. It may be earlier than `invoice_date` — see G.2.
- `stored_file.storage_path` is never exposed; only the API download path is.

### E.2 `invoice_detail`

Used in D.1 / D.2 / D.4 responses (single invoice snapshot).

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
  "pdf_download_path": "/api/v1/aussiefloors/orders/1/invoices/4/file"
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
  "created_at": "2026-04-14T11:05:00"
}
```

**Field rules**
- Sourced from `payment_transaction`. Gateway fields (`gateway_transaction_id`, `response_status`, `response_message`) are NOT exposed in MVP — they remain backend-only and stay `null` for manual payments.
- `payment_reference` may be `null`.

### E.4 `payment_summary`

Used in D.6 and D.7 responses.

```json
{
  "total_paid": 500.00,
  "balance_due": 424.00
}
```

**Field rules**
- `total_paid` — sum of all `payment_transaction.amount` for the order, rounded to 2 decimals.
- `balance_due` — `latest_invoice.balance_due` (latest **official** invoice version, regardless of whether it was created manually or auto-created by a payment). May be `null` on D.6 when no invoice exists yet. On D.7 an invoice always exists (D.7 returns the auto-created version), so `balance_due` is never `null` on D.7.

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

**F.2 Invoice versioning — three creation paths.**
- **D.1 first invoice** = `version_number = 1`. Snapshots current live order state. Allowed when LAID (if no prior invoice).
- **D.2 manual Rewrite Invoice** = next `version_number`. Snapshots current live order state — this is what makes new live edits official. Blocked when LAID.
- **D.7 payment auto-version** = next `version_number`. **Carries forward sale snapshot fields from the latest official invoice**, updates only `total_paid` / `balance_due`. Allowed when LAID.

In all three paths:
- Old invoice rows are never modified.
- Each version has its own `stored_file_id` (UNIQUE constraint).
- New versions are never built from a previous PDF; they always rebuild the `invoice` row and the file.
- Invoice rows do not store `order_number`. The PDF and the download filename use `sales_order.order_number` (locked format `{store_code}.{salesperson_code}.{seq_padded_5}`).

**F.3 Payment rules (conventions §13, with corrected balance semantics).**
- Payment is invoice-first: D.7 requires at least one existing invoice → otherwise `INVOICE_REQUIRED`.
- Payment methods: only `CASH`, `CREDIT_CARD`, `EFTPOS`, `BANK_TRANSFER`.
- `amount > 0`; no zero-value payments.
- `amount ≤ latest_invoice.balance_due` (the **latest official** invoice version's balance, NOT a balance derived from current live order state); otherwise `PAYMENT_EXCEEDS_BALANCE`. No overpayment in MVP — DB CHECK requires `balance_due ≥ 0`.
- Frontend never sends `gateway_transaction_id`, `response_status`, `response_message`. They stay `null` for MVP manual payments.
- Payments are immutable: no edit, no delete in MVP.
- Adding a payment does not change product lines, charge lines, sale price, GP, costs, or any unsent live order edits. It only updates `total_paid` and `balance_due` via the auto-created invoice version (which carries the sale snapshot forward).

**F.4 Auto invoice version on payment.**
- D.7 atomically: inserts the `payment_transaction` row, snapshots a new `invoice` version that carries the latest official sale snapshot forward and updates payment fields, generates and stores its PDF.
- The auto version must not silently make unsent live order edits official. Sale snapshot fields (`details_of_sale_snapshot`, `sale_price_ex_gst`, `sale_price_inc_gst`, `due_date`) are copied from `latest_invoice`. To make new live edits official, the salesperson must use D.2 manual Rewrite Invoice first.
- All side effects in a single DB transaction. Any failure → full rollback. The salesperson must retry; partial state is never persisted.
- D.7 response includes the created payment, the updated `payment_summary`, and the auto invoice version metadata (E.1 shape).

**F.5 Invoice due_date rule.**
- Manual invoice versions (D.1 Create, D.2 Manual Rewrite) derive `due_date = sales_order.proposed_lay_date − 2 calendar days`. The frontend never sends `due_date`.
- Payment-driven invoice versions (D.7) carry forward `due_date = latest_invoice.due_date` unchanged. They never re-derive `due_date` from current live `proposed_lay_date`. This prevents a payment from silently making an unsent install-date change official.
- `due_date` is independent of payment date. A customer may pay after `due_date`.
- `due_date` may be earlier than `invoice_date` (e.g. a payment-driven version is generated after the original due date). This is valid — see G.2.

**F.6 PDF / file download rule.**
- D.5 streams `application/pdf` raw bytes under the file-binary exception (conventions §3).
- `Content-Disposition: inline; filename="invoice-{order_number}-v{version_number}.pdf"` (backend-built; uses the locked order number format).
- `stored_file.storage_path` is server-internal and never exposed. The only field clients see is `pdf_download_path`, which is the relative URL to D.5.
- File-missing-on-disk → 500 (operational error).
- Errors at D.5 use the standard JSON error wrapper.

**F.7 Tenant / store / order / resource scoping (conventions §9).**
- Every Chunk 4 endpoint enforces the 7 checks from §9.
- Order-level endpoints additionally enforce `sales_order.business_id = session.business_id` AND `sales_order.store_id = session.store_id`.
- Invoice-level endpoints additionally enforce `invoice.order_id = path.orderId`.
- Payment writes additionally enforce that the order in the path belongs to session's `(business_id, store_id)`. Payment reads in D.6 are scoped through the order.
- Cross-tenant / cross-store / cross-order / cross-resource misses always return 404, never 403.

**F.8 LAID lock matrix (conventions §16).**

| Endpoint | Allowed when LAID? |
|----------|--------------------|
| D.1 POST invoices (Create v1) | **Yes** if no invoice exists yet AND preconditions pass (rationale in D.1) |
| D.2 POST invoices/rewrite | No → 422 `ORDER_LOCKED` (conventions §16: manual Rewrite Invoice is blocked) |
| D.3 GET invoices (history) | Yes |
| D.4 GET invoices/{id} (detail) | Yes |
| D.5 GET invoices/{id}/file (PDF) | Yes |
| D.6 GET payments | Yes |
| D.7 POST payments | **Yes** (conventions §16: add payment is allowed; auto invoice version is the side effect of allowed payments) |

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