# Sales Portal MVP — API Conventions

This document is the single reference for all API contract design.
Every endpoint in every chunk must follow these rules.
If something contradicts this document, this document wins.

---

## 1. Base URL and Path Structure

All API endpoints live under:

```
/api/v1/{business-slug}/...
```

The business slug is the first path segment after `/api/v1/`.
It identifies the business tenant for every request.

Examples:
- `POST /api/v1/aussiefloors/auth/login`
- `GET  /api/v1/aussiefloors/orders`
- `POST /api/v1/aussiefloors/orders/42/product-lines`

### Public vs protected endpoints

- `/api/v1/{slug}/auth/login` — public (no session required)
- Everything else under `/api/v1/{slug}/...` — protected (valid session required)

---

## 2. Naming Conventions

### Paths
- lowercase with hyphens: `/product-lines`, `/charge-lines`, `/details-of-sale`
- plural for collections: `/orders`, `/product-lines`, `/payments`
- singular for sub-resources that are one-per-parent: `/customer`, `/details-of-sale`
- IDs in path for specific resources: `/orders/{orderId}/product-lines/{lineId}`

### Fields
- `snake_case` for all request and response JSON fields
- matches the database column names directly
- examples: `first_name`, `order_id`, `quantity_lm`, `gp_percent`, `sale_price_ex_gst`

### Enums
- UPPER_SNAKE_CASE as strings in JSON
- match the PostgreSQL enum values exactly
- examples: `"SOFT"`, `"HARD"`, `"LEAD"`, `"NEW_ACHIEVED_SALE"`, `"CASH"`, `"EFTPOS"`, `"INSTALLATION"`, `"BILLING"`

---

## 3. Request and Response Style

### Request bodies
- JSON only
- `Content-Type: application/json`
- no query-string parameters for data mutation
- query-string parameters are used only for filtering, searching, and pagination on GET requests

### Response bodies
- JSON only
- all responses use a consistent wrapper:

```json
{
  "data": { ... },
  "message": "optional human-readable message"
}
```

- collection responses:

```json
{
  "data": [ ... ],
  "pagination": {
    "page": 1,
    "page_size": 20,
    "total_items": 84,
    "total_pages": 5
  }
}
```

- error responses:

```json
{
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "One or more fields are invalid.",
    "details": [
      {
        "field": "quantity_lm",
        "message": "Must be greater than 0"
      }
    ]
  }
}
```

### File binary I/O exception

File upload and file download endpoints are the only allowed exception to the JSON-only request and response rules.

- Upload endpoints may accept `multipart/form-data` requests.
- Download endpoints may return raw binary file bytes with the file's stored `mime_type`.
- Error responses for these endpoints still use the standard JSON error wrapper.
- This exception applies only to endpoints whose explicit purpose is file binary I/O, such as attachment upload, attachment file download, and invoice PDF download.
- Every other endpoint in the system remains JSON-only.

### Null vs absent
- null fields are included in responses with value `null`
- optional fields not provided in requests can be omitted entirely

---

## 4. HTTP Methods and Status Codes

### Methods
- `GET` — read
- `POST` — create
- `PUT` — full update of a resource
- `PATCH` — partial update of a resource
- `DELETE` — delete

### Status codes used in MVP

| Code | Meaning | When used |
|------|---------|-----------|
| 200  | OK | Successful GET, PUT, PATCH, DELETE |
| 201  | Created | Successful POST that creates a resource |
| 400  | Bad Request | Malformed JSON, missing required fields |
| 401  | Unauthorized | No session or session expired |
| 403  | Forbidden | User does not have access to this business/store/resource |
| 404  | Not Found | Resource does not exist or does not belong to this tenant |
| 409  | Conflict | Duplicate resource (e.g., duplicate salesperson_code) |
| 422  | Unprocessable Entity | Business rule violation (e.g., invoice preconditions not met) |
| 500  | Internal Server Error | Unexpected server failure |
| 502  | Bad Gateway | Upstream/provider dependency failed to complete a request (e.g., the email provider could not send an invoice — `EMAIL_SEND_FAILED`, Phase 13). Used only for failures of an external dependency the backend calls on the caller's behalf; an internal bug is still 500. |

### Important security note on 404
When a resource exists but belongs to a different business, return 404 not 403.
Do not confirm the existence of another tenant's data.

---

## 5. Date, Time, and Money Formats

### Dates
- format: `"YYYY-MM-DD"` (ISO 8601 date only)
- example: `"2026-05-01"`
- used for: `proposed_lay_date`, `invoice_date`, `due_date`

### Timestamps
- format: `"YYYY-MM-DDTHH:mm:ss"` (ISO 8601 without timezone)
- all timestamps are stored and returned in server local time (Australia)
- example: `"2026-04-22T14:30:00"`
- used for: `created_at`, `updated_at`, `last_emailed_at`

### Money
- all monetary values are `DECIMAL(10,2)`
- always two decimal places in JSON: `45.00`, not `45`
- currency is AUD — not included in the field, implied globally
- GST rate is 10% for MVP, applied as a backend constant

### Rounding
- line totals and line costs are stored to 2 decimal places
- all totals returned in responses are rounded to 2 decimal places
- GST-derived amounts are rounded to 2 decimal places

---

## 6. Pagination

- page-based pagination for collection endpoints
- query parameters: `?page=1&page_size=20`
- default page size: 20
- maximum page size: 100
- page numbering starts at 1
- response includes pagination metadata (see section 3)

---

## 7. Filtering and Search

- filters are query-string parameters on GET collection endpoints
- example: `GET /api/v1/aussiefloors/orders?status=LEAD&week_year=2026&week_number=15`
- text search: `?search=wilson` (searches across relevant text fields for that resource)
- multiple enum values: `?status=LEAD,FOLLOW_UP` (comma-separated)
- all filters are optional

---

## 8. Authentication and Session

### Login
- endpoint: `POST /api/v1/{slug}/auth/login`
- credentials: `salesperson_code` + `password`
- backend resolves business from URL slug
- backend looks up user by `(business_id, salesperson_code)`
- backend verifies password against `password_hash` using BCrypt
- on success: creates a server-side session, returns session cookie
- no JWT — server-side session only for MVP

### Login response — single-store user
If the user has exactly 1 accessible store:
- backend sets `store_id` in the session automatically at login
- response includes user info and that store's details
- frontend can go straight into the app without calling `auth/select-store`

### Login response — multi-store user
If the user has multiple accessible stores:
- backend does NOT set `store_id` in the session yet
- response includes user info and the full list of accessible stores
- frontend shows the store selection screen
- user must call `auth/select-store` before accessing any store-scoped endpoint

### Login response fields
On successful login, return:
- user info: `user_id`, `first_name`, `last_name`, `salesperson_code`
- list of accessible stores for that user within the business
- whether `store_id` was auto-selected (single-store) or still needs selection (multi-store)

### Store selection
- endpoint: `POST /api/v1/{slug}/auth/select-store`
- body: `{ "store_id": 1 }`
- this is a special protected endpoint — it requires a valid logged-in session but does NOT require an already-selected `store_id` in session
- backend checks:
  - session exists and is valid
  - business is resolved from URL slug
  - logged-in user belongs to that business
  - requested store belongs to that business
  - user has access to that store via `user_store_access`
- on success: active store is saved in the session
- user must log out to switch stores — no mid-session store switching

### Session content
After login and store selection, the session holds:
- `user_id`
- `business_id` (resolved from slug at login, never from client)
- `store_id` (set automatically at login for single-store users, or set by `auth/select-store` for multi-store users)

### Logout
- endpoint: `POST /api/v1/{slug}/auth/logout`
- only requires a valid session — works even if store selection has not happened yet
- clears the session

---

## 9. Tenant Scoping — The Most Important Rule

Every standard protected endpoint (everything except `auth/select-store` and `auth/logout`) must enforce all of the following checks:

1. Resolve business from URL slug — reject if slug is invalid or business is inactive
2. Verify session exists and is valid
3. Verify the logged-in user belongs to the resolved business
4. Verify the active store is set in the session
5. Verify the active store belongs to the resolved business
6. Verify the user has access to the active store via `user_store_access`
7. Scope all data queries to `(business_id, store_id)` from the session

`auth/select-store` and `auth/logout` have their own scoping rules defined in section 8.

### Store scoping rule
By default, all protected endpoints are scoped to the active store in the session.
The backend must not return data from another store in the same business.

### What the backend must never do
- Never trust a client-sent `business_id`
- Never trust a client-sent `store_id` for scoping (the session holds the active store)
- Never return resources that belong to a different business
- Never return resources that belong to a different store within the same business
- Never confirm existence of another tenant's data — use 404, not 403

### What the client sends vs what the backend uses
- Client sends: the business slug in the URL path
- Backend uses: the `business_id` and `store_id` from the validated session
- For order-level operations: client sends `order_id` in the URL path, backend verifies the order belongs to the session's `(business_id, store_id)`

---

## 10. Snapshotting Rules

### When a product line is added
Backend must:
1. Load the product from `store_product` by `product_id`
2. Verify the product belongs to the active store
3. Verify the product is active
4. Copy snapshot fields: `product_code_snapshot`, `product_name_snapshot`, `pricing_unit_snapshot`, `price_snapshot`, `cost_snapshot`, `sqm_per_lm_snapshot`
5. Derive quantities using the product's snapshotted conversion factor `sqm_per_lm_snapshot` (see section 11), not a fixed constant
6. Set `unit_price` = `price_snapshot` initially (can be overridden later)
7. Calculate `line_total` = quantity × `unit_price` (which quantity depends on pricing unit — see section 11)
8. Calculate `line_cost` = quantity × `cost_snapshot` (which quantity depends on pricing unit — see section 11)

### When a charge line is added
Backend must:
1. Load the charge from `store_charge` by `charge_id`
2. Verify the charge belongs to the active store
3. Verify the charge is active
4. Copy snapshot fields: `charge_code_snapshot`, `charge_name_snapshot`, `price_snapshot`, `cost_snapshot`
5. Set `unit_price` = `price_snapshot` initially (can be overridden later)
6. Calculate `line_total` = quantity × `unit_price`
7. Calculate `line_cost` = quantity × `cost_snapshot`

### Snapshot immutability
- `price_snapshot` and `cost_snapshot` always reflect the catalog value at the moment the line was created
- they are never updated if the catalog changes later
- `unit_price` is the actual selling price — starts from `price_snapshot`, can be manually overridden by the salesperson

### Cost is never overridable
- cost values come from the catalog only
- the frontend cannot send cost overrides
- the backend must reject any attempt to set cost from the client

---

## 11. Quantity Conversion Rules

### Per-product conversion factor
Each product carries its own conversion factor `store_product.sqm_per_lm` — "how many SQM are in 1 LM". Default is `3.66` (standard 3.66 m wide roll). Some carpet rolls are 4 m wide and use `4.00`.

When a product line is added, the backend snapshots `store_product.sqm_per_lm` into `order_product_line.sqm_per_lm_snapshot`. All quantity derivation for that line uses the snapshotted factor, never a fixed constant. The snapshot is immutable for the life of the line.

### Product line request
Frontend sends exactly one of:
- `quantity_lm` (nullable)
- `quantity_sqm` (nullable)

Exactly one must be provided, the other must be null or absent.
Backend derives the missing value using `sqm_per_lm_snapshot`:
- if `quantity_lm` is provided: `quantity_sqm = round(quantity_lm × sqm_per_lm_snapshot, 2)`
- if `quantity_sqm` is provided: `quantity_lm = round(quantity_sqm / sqm_per_lm_snapshot, 2)`

### Default starting values by flooring type
Default starting quantities are product-specific (driven by the product's own `sqm_per_lm`), not a hardcoded constant for every product:
- SOFT flooring (priced per LM): default starting quantity is 1 LM → derived SQM is `sqm_per_lm` (e.g. 3.66, or 4.00 for a 4 m roll)
- HARD flooring (priced per SQM): default starting quantity is 1 SQM → derived LM is `round(1 / sqm_per_lm, 2)` (e.g. 0.27 at 3.66, 0.25 at 4.00)

### Which quantity drives calculation
The pricing unit determines which quantity is used for line_total and line_cost:
- if `pricing_unit_snapshot` = LM: `line_total = quantity_lm × unit_price`, `line_cost = quantity_lm × cost_snapshot`
- if `pricing_unit_snapshot` = SQM: `line_total = quantity_sqm × unit_price`, `line_cost = quantity_sqm × cost_snapshot`

---

## 12. Order Financial Calculations

### When recalculation happens
Backend recalculates live order financials in the same response whenever any of these actions occur:
- add / edit / delete a product line
- add / edit / delete a charge line
- manual unit price override on any line
- sale price update in Details of Sale

No separate recalculate endpoint exists.

### What is recalculated

**Subtotals (ex-GST):**
- `product_subtotal` = sum of all product line `line_total` values (ex-GST)
- `charge_subtotal` = sum of all charge line `line_total` values (ex-GST)

**GST-inclusive calculated total:**
- `calculated_total_inc_gst` = (`product_subtotal` + `charge_subtotal`) × 1.10

**Manual price adjustment:**
- `price_adjustment_inc_gst` is a fixed GST-inclusive dollar adjustment stored on `sales_order`
- it can be negative (discount) or positive (increase)
- if `price_adjustment_inc_gst` is not null: `final_sale_price_inc_gst` = `calculated_total_inc_gst` + `price_adjustment_inc_gst`
- if `price_adjustment_inc_gst` is null: `final_sale_price_inc_gst` = `calculated_total_inc_gst`

**Ex-GST sale price (derived from the GST-inclusive final price):**
- `sale_price_ex_gst` = `final_sale_price_inc_gst` / 1.10

**Cost and GP:**
- `total_cost` = sum of all product `line_cost` values + sum of all charge `line_cost` values
- `gp` = `sale_price_ex_gst` - `total_cost`
- `gp_percent` = if `sale_price_ex_gst` > 0 then (`gp` / `sale_price_ex_gst`) × 100, else null

All amounts are rounded to 2 decimal places.

### How manual sale price override works

The salesperson edits the GST-inclusive sale price shown in Details of Sale.
The backend derives and stores the adjustment, then reapplies it on every future recalculation.

**Step-by-step example:**

1. Current ex-GST subtotals (product + charge) = $909.09
2. Current calculated total inc GST = $909.09 × 1.10 = **$1,000.00**
3. Salesperson changes the displayed sale price to $800.00 inc GST
4. Backend calculates: `price_adjustment_inc_gst` = 800.00 − 1,000.00 = **−200.00**
5. Backend stores −200.00 on `sales_order.price_adjustment_inc_gst`
6. Later, a new charge line of $20.00 ex-GST is added
7. New ex-GST subtotals = $909.09 + $20.00 = **$929.09**
8. New calculated total inc GST = $929.09 × 1.10 = **$1,022.00**
9. Backend reapplies the stored adjustment: $1,022.00 + (−200.00) = **$822.00 inc GST** (this is `final_sale_price_inc_gst`)
10. Backend derives ex-GST: $822.00 / 1.10 = **$747.27** (this is `sale_price_ex_gst`)
11. GP is recalculated from the new `sale_price_ex_gst` and `total_cost`

**Reset Price** sets `price_adjustment_inc_gst` back to null.
After reset, `final_sale_price_inc_gst` equals `calculated_total_inc_gst` again.

### GP rules
- negative GP is allowed — warning only, never a hard block
- GP warning threshold: GP% < 15%
- if `sale_price_ex_gst` = 0: `gp_percent` = null (division by zero)
- GP is always based on the final discounted ex-GST amount, not the pre-discount amount, and not the GST-inclusive figure

### Order financial summary response
Every mutation that affects order financials must include the financial summary in the response.
The summary is nested inside the standard response wrapper:

```json
{
  "data": {
    "...other fields for this endpoint...",
    "order_financial_summary": {
      "product_subtotal": 360.00,
      "charge_subtotal": 480.00,
      "calculated_total_inc_gst": 924.00,
      "price_adjustment_inc_gst": null,
      "final_sale_price_inc_gst": 924.00,
      "sale_price_ex_gst": 840.00,
      "total_cost": 432.00,
      "gp": 408.00,
      "gp_percent": 48.57,
      "gp_warning": false
    }
  }
}
```

---

## 13. Payment Rules

### When payment is allowed
- payment can only be recorded after at least one invoice exists for the order
- if no invoice exists, payment endpoints return 422

### Payment recording
- frontend sends: `payment_method`, `amount`, `payment_reference` (optional for MVP)
- backend creates the `payment_transaction` row
- gateway fields (`gateway_transaction_id`, `response_status`, `response_message`) are nullable for MVP manual payments

### Payment financial effect
- adding a payment updates `total_paid` and `balance_due` only
- payment does not change GP, sale price, or any order line data

### Payment response
After adding a payment, backend returns the payment summary inside the standard response wrapper:

```json
{
  "data": {
    "payment_transaction_id": 2,
    "payment_method": "EFTPOS",
    "amount": 500.00,
    "payment_reference": "EFTPOS-20260422",
    "created_at": "2026-04-22T14:30:00",
    "payment_summary": {
      "total_paid": 500.00,
      "balance_due": 424.00
    }
  }
}
```

### Payment restrictions

- salesperson cannot delete payments in MVP
- salesperson cannot edit existing payments in MVP
- payment amount must be greater than 0
- payment amount must not exceed the latest official invoice version's `balance_due`
- no overpayments in MVP

### Auto invoice version on payment

Every time a payment is added, backend must automatically generate a new payment-driven invoice version.

This payment-driven version:
- carries forward the latest official invoice sale snapshot
- updates `total_paid`
- updates `balance_due`
- does not make unsent live order edits official

Payment balance is based on the latest official invoice version’s `balance_due`, not on unsent live order edits.

To make live order edits official, the salesperson must use Rewrite Invoice first.
---

## 14. Invoice Rules

### Invoice creation preconditions
An invoice can be created or rewritten only if all of these are true:

1. Customer first name is present
2. Customer last name is present
3. Installation address is present (all required address fields filled)
4. Billing address is present (all required address fields filled)
5. At least one priced line exists (product or charge)
6. Details of Sale description is present
7. Proposed lay date is present
8. Lay date status is present
9. Final sale price is valid and greater than 0

### Address requirement
Both installation address and billing address must exist as separate rows in `order_address`.
"Billing same as install" is a UI convenience only — when the user selects it, the backend copies the installation address into a billing address row.
Invoice validation checks that both rows exist regardless of how they were created.

### When preconditions are checked
- only when the user clicks Create Invoice or Rewrite Invoice
- NOT on every order mutation

### Validation failure response
If preconditions are not met, return 422 with a structured body:

```json
{
  "error": {
    "code": "INVOICE_PRECONDITIONS_NOT_MET",
    "message": "Complete required fields before creating invoice.",
    "details": [
      {
        "section": "customer",
        "field": "last_name",
        "message": "Customer last name is required."
      },
      {
        "section": "address",
        "field": "billing_address",
        "message": "Billing address is required."
      },
      {
        "section": "details",
        "field": "proposed_lay_date",
        "message": "Proposed lay date is required."
      }
    ]
  }
}
```

### Invoice versioning
- first invoice = version 1, created by Create Invoice
- manual Rewrite Invoice = new version number, created from current live order state
- Add Payment = also creates a new payment-driven invoice version automatically
- manual invoice versions make the current live order state official
- payment-driven invoice versions carry forward the latest official sale snapshot and only update payment fields: `total_paid` and `balance_due`
- payment-driven invoice versions must not silently include unsent live order edits
- old versions are never modified
- old versions remain in invoice history (retained internally)

**MVP scope (Phase 12).** The versioning mechanism above runs internally, but the MVP API and
frontend expose only the **current/latest invoice**. Invoice history listing, choosing an older
version, and downloading old-version PDFs are deferred to post-MVP (along with signed/accepted
invoice revision history and the revision dropdown). No schema field is removed and no migration
is added — version history is reserved for post-MVP. See `docs/API-Contracts-Chunk-4.md` A.1 / D.5.


### Invoice due date rule

Invoice `due_date` is derived from the order's `proposed_lay_date`, not from `invoice_date`.

For manual invoice versions created by Create Invoice or Rewrite Invoice:

- `due_date = proposed_lay_date - 2 calendar days`
- `proposed_lay_date` must be present before invoice creation/rewrite
- `due_date` may be earlier than `invoice_date`

Payment-driven invoice versions carry forward the latest official invoice's `due_date` unchanged.

Customer payment date does not change `due_date`.

### What is stored in the invoice snapshot (internal)
Each invoice snapshot stores internally:
- `details_of_sale_snapshot`
- `sale_price_ex_gst`
- `sale_price_inc_gst`
- `total_paid` (at the moment of snapshot)
- `balance_due` (at the moment of snapshot)
- linked PDF via `stored_file_id`

### What is shown on the customer-facing invoice PDF
The customer-facing invoice PDF shows:
- invoice number and date
- due date
- customer name and billing address
- details of sale
- total amount inc GST (`sale_price_inc_gst`)
- payment made (`total_paid`)
- balance due (`balance_due`)

The ex-GST amount is stored internally but the customer-facing PDF displays the GST-inclusive total as the headline price.

### Invoice PDF
- generated from the latest invoice snapshot
- billing address is used on the invoice (not installation address)
- older versions keep their PDFs in history

---

## 15. Order Status Rules

### Where status changes happen
- all status changes happen from the Sales Dashboard only
- no status changes from inside the order screen

### Available statuses
`LEAD`, `NEW_ACHIEVED_SALE`, `FOLLOW_UP`, `ACCEPTED`, `LAID`, `CANCELLED`

### Transition rules for MVP
- no strict transition matrix
- any status can change to any other status
- CANCELLED can be reopened
- LAID can be changed back to another status

### LAID lock behaviour
- when status is LAID: order is locked (see section 16 for exactly what is blocked)
- when status is changed from LAID to any other status: order becomes editable again
- LAID = locked, not LAID = editable

### LAID does not require payment
- a job can be marked LAID even if there is an outstanding balance
- payment status and job completion status are separate concepts

### Status change endpoint
- `PATCH /api/v1/{slug}/orders/{orderId}/status`
- body: `{ "order_status": "ACCEPTED" }`
- backend validates the order belongs to the current tenant/store
- backend checks if order is currently LAID and the new status is not LAID — if so, unlocks the order
- backend checks if new status is LAID — if so, locks the order

---

## 16. Order Editability Rules

### Editable
An order is editable when its status is anything other than LAID:
- LEAD — editable
- NEW_ACHIEVED_SALE — editable
- FOLLOW_UP — editable
- ACCEPTED — editable
- CANCELLED — editable
- LAID — locked

### What "locked" means — blocked when LAID
When an order is LAID, the backend must reject any attempt to:
- edit customer details
- edit addresses
- add / edit / delete product lines
- add / edit / delete charge lines
- edit details of sale
- manually Rewrite Invoice

### What is still allowed when LAID
These actions are allowed even when the order is LAID:
- add payment (and the automatic invoice version that comes with it)
- add note
- add attachment

Reason: LAID does not require full payment. Final payments may still be collected after job completion. Notes and attachments may still need to be added for record-keeping after the job is done.

### What "editable" means
When an order is not LAID, all order data can be modified freely.
An order that has an invoice can still be edited — the existing invoice snapshot is not affected.
A new invoice version is created only when the user clicks Rewrite Invoice.

---

## 17. Flooring Type Lock

- every order has exactly one flooring type: SOFT or HARD
- flooring type is set at order creation and cannot be changed afterwards
- product search and charge search are filtered by the order's flooring type
- a SOFT order can only add SOFT products and SOFT charges
- a HARD order can only add HARD products and HARD charges

---

## 18. Order Numbering

### 18.1 salesperson_code

`salesperson_code` is locked to exactly 3 characters.

Format:

```text
{first_initial}{last_initial}{number}
```

Database format rule:

```text
^[A-Z]{2}[0-9]$
```

Examples:
- Liam Carter -> LC1
- Sophie Nguyen -> SN1
- Jack Williams -> JW1
- Emma Patel -> EP1

### 18.2 order_number

Order number format for MVP:

```text
{store_code}.{salesperson_code}.{order_sequence_number_padded_5}
```

Examples:
- SYD-CBD.LC1.00001
- SYD-CBD.SN1.00003
- SYD-PARR.JW1.00005
- MEL-CBD.OS1.00001

Worked example:

```text
store_code = SYD-CBD
salesperson_code = LC1
order_sequence_number = 1

order_number = SYD-CBD.LC1.00001
```

Rules:
- `store_code` comes from `store.store_code`.
- `salesperson_code` comes from `app_user.salesperson_code` and must match the locked format in §18.1.
- `order_sequence_number` remains unique per business.
- The sequence number is padded to 5 digits:
  - 1 -> 00001
  - 25 -> 00025
  - 12345 -> 12345
- Backend generates `order_number` at order creation.
- Frontend displays `order_number` verbatim.
- Frontend never sends or edits `order_number`.
- Once created, `order_number` does not change even if `store_code` or `salesperson_code` changes later.

---

## 19. Week Number

- `week_number` and `week_year` are set by the backend at order creation
- based on the ISO week of the creation date
- used for dashboard filtering by week
- the frontend never sends these values

---

## 20. V5 Migration — Required Before Backend Implementation

Two columns need to be added before coding starts:

1. `business.slug` — `VARCHAR(50) NOT NULL UNIQUE`
   - lowercase alphanumeric + hyphens only
   - no reserved words (admin, api, login, static, auth, health)
   - used for URL-based business resolution

2. `sales_order.price_adjustment_inc_gst` — `DECIMAL(10,2) DEFAULT NULL`
   - null = no manual override
   - a value = persistent GST-inclusive adjustment reapplied on every recalculation
   - can be negative (discount) or positive (increase)
   - Reset Price sets this back to null

---

## 21. Conventions Checklist for Every Endpoint

Before designing any endpoint, verify:

- [ ] Path follows `/api/v1/{slug}/...` structure
- [ ] Path uses lowercase-hyphenated names
- [ ] Fields use snake_case
- [ ] Enums use UPPER_SNAKE_CASE matching DB values
- [ ] Money fields have 2 decimal places
- [ ] Dates are YYYY-MM-DD, timestamps are ISO 8601
- [ ] Tenant scoping checks are defined (all 7 checks from section 9 for standard endpoints)
- [ ] Store scoping is enforced — no data from other stores in the same business
- [ ] LAID lock is respected (block commercial edits, allow payments/notes/attachments)
- [ ] Flooring type filter is applied where relevant
- [ ] Snapshots are server-side only
- [ ] Cost is never accepted from client
- [ ] Financial recalculation is included in mutation responses where applicable
- [ ] Error responses follow the standard shape
- [ ] 404 is used instead of 403 for cross-tenant access
- [ ] File upload/download endpoints use the narrow binary I/O exception; all other endpoints remain JSON-only
