# Sales Portal MVP — API Contracts · Chunk 2 (Lock-Ready)

**Source of truth priority:**
1. `docs/api-conventions-locked.md`
2. Locked Chunk 1 contract
3. Locked DB schema (V2 / V3)

**V5 follow-ups (noted only — not applied here):**
1. `business.slug`
2. `sales_order.price_adjustment_inc_gst`

---

## A. Chunk 2 Scope — Included

- Create order shell.
- Read full order workspace state for the sections owned by Chunk 2 (header + customer + addresses + details of sale).
- Create or replace the one customer row per order.
- Create or replace the installation address.
- Create or replace the billing address.
- Copy installation address into billing address ("billing same as install" workflow).
- Update the non-line, non-payment "details of sale" fields stored on `sales_order`.

---

## B. Chunk 2 Scope — Excluded / Deferred

Deferred to **Chunk 3** (product/charge lines, totals/GP, notes, attachments):
- Product line endpoints, charge line endpoints.
- Live order financial summary block (`order_financial_summary` from conventions §12).
- Manual sale price override and Reset Price (these write `sales_order.price_adjustment_inc_gst`, which is a V5 column and is part of conventions §12 — Chunk 3 territory).
- GP info modal contents (Screen 11.2 — pure financial display).
- Notes and attachments.

Deferred to **Chunk 4** (invoice / payments / invoice history / minimum operations portal):
- Invoice creation, rewrite, history, PDF.
- Invoice precondition validation (conventions §14).
- Payment recording and the auto-invoice-version side effect (conventions §13).
- Operations Portal endpoints.

Already locked in **Chunk 1** — not reopened here:
- `auth/login`, `auth/select-store`, `auth/logout`.
- `GET /orders` (dashboard list).
- `PATCH /orders/{orderId}/status` (dashboard-only status change, LAID lock/unlock).

---

## C. Chunk 2 Endpoint List

All endpoints below are **Standard protected** (all 7 checks from conventions §9), and each order-level endpoint additionally verifies the order belongs to the session's `(business_id, store_id)`.

| # | Method | Path | Purpose |
|---|--------|------|---------|
| 1 | POST | `/api/v1/{slug}/orders` | Create a new order shell |
| 2 | GET  | `/api/v1/{slug}/orders/{orderId}` | Read order header + customer + addresses + details of sale |
| 3 | PUT  | `/api/v1/{slug}/orders/{orderId}/customer` | Create or replace the order's customer row |
| 4 | PUT  | `/api/v1/{slug}/orders/{orderId}/addresses/installation` | Create or replace the installation address |
| 5 | PUT  | `/api/v1/{slug}/orders/{orderId}/addresses/billing` | Create or replace the billing address |
| 6 | POST | `/api/v1/{slug}/orders/{orderId}/addresses/billing/copy-from-installation` | Copy installation into billing |
| 7 | PUT  | `/api/v1/{slug}/orders/{orderId}/details-of-sale` | Update non-line, non-payment details of sale |

---

## D. Endpoint Contracts

### D.1 POST /api/v1/{slug}/orders

**Purpose.** Create a new order shell. No customer / address / details / lines required at creation.

**Scope class.** Standard protected.

**Path params.** None beyond `slug`.

**Request DTO**
```json
{ "flooring_type": "SOFT" }
```

**Field rules**
- `flooring_type` — required; must be `SOFT` or `HARD`. Locked at creation per conventions §17.

**Response DTO — 201 Created**
```json
{
  "data": {
    "order_id": 19,
    "order_number": "001.LW1.00001",
    "order_sequence_number": 9,
    "flooring_type": "SOFT",
    "order_status": "LEAD",
    "supply_only": false,
    "plan_numbers": null,
    "proposed_lay_date": null,
    "lay_date_status": null,
    "details_of_sale": null,
    "last_emailed_at": null,
    "week_year": 2026,
    "week_number": 17,
    "created_at": "2026-04-28T10:15:00",
    "updated_at": "2026-04-28T10:15:00",
    "locked": false
  },
  "message": "Order created."
}
```

**Business rules**
- Backend sets from session: `business_id`, `store_id`, `user_id`. Client never sends these.
- Backend generates: `order_sequence_number` (next per business, conventions §18), `order_number` (formatted display string, conventions §18), `week_year` and `week_number` (ISO week of creation, conventions §19).
- `order_status` defaults to `LEAD` per `sales_order.order_status DEFAULT 'LEAD'` (V2).
- `supply_only` defaults to `FALSE` per V2.
- Other fields default to NULL: `plan_numbers`, `proposed_lay_date`, `lay_date_status`, `details_of_sale`, `last_emailed_at`, `sale_price_ex_gst`, `total_cost`, `gp`, `gp_percent`.
- `locked` is a derived convenience flag: `true` iff `order_status == 'LAID'`. Always `false` for a freshly created order.

**Validation**
- Body is valid JSON.
- `flooring_type` is present and is one of `SOFT, HARD`.

**Tenant / session / store scoping**
- All 7 checks from conventions §9.
- Order is created scoped to the session's `(business_id, store_id)`.

**LAID lock.** N/A — creation, not edit.

**Status codes**
| Code | When |
|------|------|
| 201 | Order created |
| 400 | Malformed JSON, missing or invalid `flooring_type` |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Business slug not found / inactive |
| 500 | Unexpected |

---

### D.2 GET /api/v1/{slug}/orders/{orderId}

**Purpose.** Return the order workspace state needed for Chunk 2 sections: header, customer (if any), installation address (if any), billing address (if any), details of sale.

**Scope class.** Standard protected.

**Path params**
- `orderId` — internal `order_id`, positive integer.

**Query params.** None.

**Request DTO.** None.

**Response DTO — 200 OK**
```json
{
  "data": {
    "order_id": 1,
    "order_number": "001.LW1.00001",
    "order_sequence_number": 1,
    "flooring_type": "SOFT",
    "order_status": "ACCEPTED",
    "supply_only": false,
    "plan_numbers": null,
    "proposed_lay_date": "2026-05-01",
    "lay_date_status": "CONFIRMED",
    "details_of_sale": "Supply and install plush carpet to lounge and dining rooms. Furniture to be moved by installer.",
    "last_emailed_at": null,
    "week_year": 2026,
    "week_number": 15,
    "created_at": "2026-04-14T09:20:00",
    "updated_at": "2026-04-14T11:05:00",
    "locked": false,

    "customer": {
      "first_name": "James",
      "middle_name": null,
      "last_name": "Wilson",
      "email": "james.wilson@email.com",
      "mobile": "0412345678",
      "home_phone": "0298765432",
      "work_phone": null,
      "company_name": null
    },

    "install_address": {
      "unit_number": null,
      "street_number": "42",
      "street": "Oxford Street",
      "suburb": "Paddington",
      "state_code": "NSW",
      "postcode": "2021"
    },

    "billing_address": {
      "unit_number": "3",
      "street_number": "15",
      "street": "Pitt Street",
      "suburb": "Sydney",
      "state_code": "NSW",
      "postcode": "2000"
    },

    "persisted_financials": {
      "sale_price_ex_gst": 840.00,
      "total_cost": 432.00,
      "gp": 408.00,
      "gp_percent": 48.57
    }
  }
}
```

**Field rules**
- All header fields are sourced from `sales_order` and follow the locked schema's nullability.
- `locked` — derived: `true` iff `order_status == 'LAID'`.
- `customer` — nested object with all `order_customer` columns except IDs and timestamps. **`null` when no `order_customer` row exists for this order.** When the row exists: `first_name`, `last_name`, `email`, `mobile` are NOT NULL (DB constraints); `middle_name`, `home_phone`, `work_phone`, `company_name` may be `null`.
- `install_address` — nested object built from the `order_address` row where `address_type = 'INSTALLATION'`. **`null` when no installation row exists.** When the row exists: `street_number`, `street`, `suburb`, `state_code`, `postcode` are NOT NULL; `unit_number` may be `null`.
- `billing_address` — same shape as `install_address`, sourced from the row where `address_type = 'BILLING'`. **`null` when no billing row exists.**
- `persisted_financials` — readback of nullable scalar fields on `sales_order` (`sale_price_ex_gst`, `total_cost`, `gp`, `gp_percent`). All four are `null` until Chunk 3 logic writes them. Money formatting applies to `sale_price_ex_gst`, `total_cost`, and `gp`. Percentage formatting applies to `gp_percent`. Both formatted with two decimal places.
- The live `order_financial_summary` block from conventions §12 is NOT included on GET — it is produced only by Chunk 3 mutation responses (line add/edit/delete, sale price override).

**Business rules**
- Read-only.
- Order must exist within the session's `(business_id, store_id)`. Otherwise 404 `ORDER_NOT_FOUND` (conventions §4 / §9 — never confirm cross-tenant or cross-store existence).

**Validation**
- `orderId` is a positive integer.

**Tenant / session / store scoping**
- All 7 checks from conventions §9.
- Resource-level: `sales_order.business_id = session.business_id` AND `sales_order.store_id = session.store_id`.

**LAID lock.** GET is allowed regardless of `order_status`, including `LAID`.

**Status codes**
| Code | When |
|------|------|
| 200 | Order returned |
| 400 | Invalid `orderId` format |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in session's `(business_id, store_id)`, or business slug not found / inactive |
| 500 | Unexpected |

---

### D.3 PUT /api/v1/{slug}/orders/{orderId}/customer

**Purpose.** Create or replace the single customer row for an order.

**Scope class.** Standard protected.

**Path params**
- `orderId` — internal `order_id`, positive integer.

**Request DTO**
```json
{
  "first_name": "James",
  "middle_name": null,
  "last_name": "Wilson",
  "email": "james.wilson@email.com",
  "mobile": "0412345678",
  "home_phone": "0298765432",
  "work_phone": null,
  "company_name": null
}
```

**Field rules — all map directly to `order_customer` columns and constraints**
- `first_name` — required; non-blank after trim.
- `last_name` — required; non-blank after trim.
- `email` — required; non-blank after trim; must contain `@` (matches DB CHECK `email LIKE '%@%'`).
- `mobile` — required; non-blank after trim.
- `middle_name` — optional; if provided, non-blank after trim, otherwise `null`.
- `home_phone` — optional; if provided, non-blank, otherwise `null` (matches DB CHECK).
- `work_phone` — optional; if provided, non-blank, otherwise `null` (matches DB CHECK).
- `company_name` — optional; if provided, non-blank, otherwise `null` (matches DB CHECK).

**Response DTO — 200 OK**
```json
{
  "data": {
    "customer": {
      "first_name": "James",
      "middle_name": null,
      "last_name": "Wilson",
      "email": "james.wilson@email.com",
      "mobile": "0412345678",
      "home_phone": "0298765432",
      "work_phone": null,
      "company_name": null
    }
  },
  "message": "Customer saved."
}
```

**Business rules**
- One `order_customer` row per order (UNIQUE on `order_id`, V3). PUT is upsert: insert if absent, replace all columns if present.
- Replace semantics: any optional field omitted from the body is written as `null`.
- Order must exist within the session's `(business_id, store_id)`. Otherwise 404.

**Validation**
- `orderId` is a positive integer.
- Body is valid JSON.
- All required fields present and non-blank after trim.
- `email` contains `@`.
- Optional string fields, when provided as a non-null value, are non-blank.

**Tenant / session / store scoping**
- All 7 checks from conventions §9, plus the order-belongs-to-store check.

**LAID lock.** Blocked when order is `LAID` → **422 `ORDER_LOCKED`** (conventions §16: "edit customer details" is blocked when LAID).

**Status codes**
| Code | When |
|------|------|
| 200 | Customer created or replaced |
| 400 | Malformed JSON, invalid `orderId` format, missing required fields, blank required fields, invalid email format |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in session's `(business_id, store_id)`, or business slug not found / inactive |
| 422 | Order is `LAID` (`ORDER_LOCKED`) |
| 500 | Unexpected |

**Error example — 422**
```json
{
  "error": {
    "code": "ORDER_LOCKED",
    "message": "Order is laid and cannot be edited."
  }
}
```

---

### D.4 PUT /api/v1/{slug}/orders/{orderId}/addresses/installation

**Purpose.** Create or replace the order's installation address.

**Scope class.** Standard protected.

**Path params**
- `orderId` — positive integer.

**Request DTO**
```json
{
  "unit_number": null,
  "street_number": "42",
  "street": "Oxford Street",
  "suburb": "Paddington",
  "state_code": "NSW",
  "postcode": "2021"
}
```

**Field rules — all map directly to `order_address` columns and constraints**
- `street_number` — required; non-blank.
- `street` — required; non-blank.
- `suburb` — required; non-blank.
- `state_code` — required; non-blank.
- `postcode` — required; non-blank.
- `unit_number` — optional; if provided, non-blank, otherwise `null` (matches DB CHECK).
- `address_type` is NOT accepted in the body. The path determines `address_type = 'INSTALLATION'`.

**Response DTO — 200 OK**
```json
{
  "data": {
    "install_address": {
      "unit_number": null,
      "street_number": "42",
      "street": "Oxford Street",
      "suburb": "Paddington",
      "state_code": "NSW",
      "postcode": "2021"
    }
  },
  "message": "Installation address saved."
}
```

**Business rules**
- One row per `(order_id, address_type)` (UNIQUE constraint, V3). PUT is upsert: insert if absent, replace all columns if present.
- Replace semantics: omitted optional fields are written as `null`.
- Order must exist within the session's `(business_id, store_id)`. Otherwise 404.

**Validation**
- `orderId` is a positive integer.
- Body is valid JSON.
- All required fields present and non-blank.
- `unit_number`, when provided as non-null, is non-blank.

**Tenant / session / store scoping**
- All 7 checks from conventions §9, plus the order-belongs-to-store check.

**LAID lock.** Blocked when order is `LAID` → **422 `ORDER_LOCKED`** (conventions §16: "edit addresses" is blocked when LAID).

**Status codes**
| Code | When |
|------|------|
| 200 | Installation address created or replaced |
| 400 | Malformed JSON, invalid `orderId` format, missing required fields, blank required fields |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in session's `(business_id, store_id)`, or business slug not found / inactive |
| 422 | Order is `LAID` (`ORDER_LOCKED`) |
| 500 | Unexpected |

---

### D.5 PUT /api/v1/{slug}/orders/{orderId}/addresses/billing

**Purpose.** Create or replace the order's billing address.

**Scope class.** Standard protected.

**Path params**
- `orderId` — positive integer.

**Request DTO**
```json
{
  "unit_number": "3",
  "street_number": "15",
  "street": "Pitt Street",
  "suburb": "Sydney",
  "state_code": "NSW",
  "postcode": "2000"
}
```

**Field rules.** Identical to D.4 (installation), against the same `order_address` schema. The path determines `address_type = 'BILLING'`. `address_type` is not accepted in the body.

**Response DTO — 200 OK**
```json
{
  "data": {
    "billing_address": {
      "unit_number": "3",
      "street_number": "15",
      "street": "Pitt Street",
      "suburb": "Sydney",
      "state_code": "NSW",
      "postcode": "2000"
    }
  },
  "message": "Billing address saved."
}
```

**Business rules.** Same as D.4: one row per `(order_id, BILLING)`, upsert; omitted optional fields written as `null`; order must belong to the session's store.

**Validation.** Same as D.4.

**Tenant / session / store scoping.** Same as D.4.

**LAID lock.** Blocked when order is `LAID` → **422 `ORDER_LOCKED`**.

**Status codes.** Same as D.4.

---

### D.6 POST /api/v1/{slug}/orders/{orderId}/addresses/billing/copy-from-installation

**Purpose.** Backend copies the existing installation address into the billing address row. Supports the "billing same as installation" workflow (conventions §14: "Billing same as install" is a UI convenience; the backend copies the installation address into a billing address row).

**Scope class.** Standard protected.

**Path params**
- `orderId` — positive integer.

**Request DTO.** Empty body.

**Response DTO — 200 OK**
```json
{
  "data": {
    "billing_address": {
      "unit_number": null,
      "street_number": "42",
      "street": "Oxford Street",
      "suburb": "Paddington",
      "state_code": "NSW",
      "postcode": "2021"
    }
  },
  "message": "Billing address copied from installation address."
}
```

**Business rules**
- Backend reads the existing `INSTALLATION` row for the order.
- If no installation row exists → **422 `INSTALLATION_ADDRESS_REQUIRED`**. No write occurs.
- If installation row exists:
  - If a `BILLING` row already exists, replace it with the copied installation values.
  - If no `BILLING` row exists, create one.
  - Backend copies all 6 columns: `unit_number`, `street_number`, `street`, `suburb`, `state_code`, `postcode`.
- Returns 200 on success in both create and replace cases (consistent with PUT addresses/billing, which also returns 200 on first create).
- Order must exist within the session's `(business_id, store_id)`. Otherwise 404.

**Validation**
- `orderId` is a positive integer.

**Tenant / session / store scoping**
- All 7 checks from conventions §9, plus the order-belongs-to-store check.

**LAID lock.** Blocked when order is `LAID` → **422 `ORDER_LOCKED`** (this is an address edit per conventions §16).

**Status codes**
| Code | When |
|------|------|
| 200 | Billing address created or replaced from installation |
| 400 | Invalid `orderId` format |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in session's `(business_id, store_id)`, or business slug not found / inactive |
| 422 | Order is `LAID` (`ORDER_LOCKED`), or installation address is missing (`INSTALLATION_ADDRESS_REQUIRED`) |
| 500 | Unexpected |

**Error example — 422 missing installation**
```json
{
  "error": {
    "code": "INSTALLATION_ADDRESS_REQUIRED",
    "message": "Installation address must exist before copying it to billing."
  }
}
```

---

### D.7 PUT /api/v1/{slug}/orders/{orderId}/details-of-sale

**Purpose.** Update the non-line, non-payment "details of sale" fields stored on `sales_order`.

**Scope class.** Standard protected.

**Path params**
- `orderId` — positive integer.

**Request DTO**
```json
{
  "supply_only": false,
  "plan_numbers": "PLN-4420",
  "proposed_lay_date": "2026-05-01",
  "lay_date_status": "CONFIRMED",
  "details_of_sale": "Supply and install plush carpet to lounge and dining rooms."
}
```

**Field rules — all grounded in `sales_order` schema**
- `supply_only` — required; boolean. Maps to `sales_order.supply_only` (NOT NULL, default FALSE).
- `plan_numbers` — optional; string or `null`. Maps to nullable `sales_order.plan_numbers`. If the client sends a non-null value, it must be non-blank after trim.
- `proposed_lay_date` — optional; date string `YYYY-MM-DD` or `null`. Maps to nullable `sales_order.proposed_lay_date`.
- `lay_date_status` — optional; one of `CONFIRMED, TO_BE_CONFIRMED` or `null`. Maps to nullable `sales_order.lay_date_status`.
- `details_of_sale` — optional; string or `null`. Maps to nullable `sales_order.details_of_sale`. If the client sends a non-null value, it must be non-blank after trim.

**Pair rule (DB-enforced).** `proposed_lay_date` and `lay_date_status` must be either both `null` (or both omitted) or both non-null. Mixed → 400 `VALIDATION_FAILED`. (Backed by `chk_sales_order_lay_date_pair` in V3.)

**Replace semantics.** PUT replaces this set of fields:
- Required fields not present → 400.
- Optional fields omitted are treated as `null` and written as `null` to the DB.

**Response DTO — 200 OK**
```json
{
  "data": {
    "details_of_sale_fields": {
      "supply_only": false,
      "plan_numbers": "PLN-4420",
      "proposed_lay_date": "2026-05-01",
      "lay_date_status": "CONFIRMED",
      "details_of_sale": "Supply and install plush carpet to lounge and dining rooms."
    },
    "updated_at": "2026-04-28T11:05:00"
  },
  "message": "Details of sale saved."
}
```

The response intentionally does NOT include the `order_financial_summary` block — none of these fields affect financials (conventions §12). Sale price override and Reset Price are deferred to Chunk 3.

**Business rules**
- Order must exist within the session's `(business_id, store_id)`. Otherwise 404.
- Backend writes `updated_at` automatically.

**Validation**
- `orderId` is a positive integer.
- Body is valid JSON.
- `supply_only` present and boolean.
- `lay_date_status`, when non-null, is one of the two enum values.
- `proposed_lay_date`, when non-null, is a valid `YYYY-MM-DD` date string.
- Lay-date pair rule above.
- Non-null string fields are non-blank after trim.

**Tenant / session / store scoping**
- All 7 checks from conventions §9, plus the order-belongs-to-store check.

**LAID lock.** Blocked when order is `LAID` → **422 `ORDER_LOCKED`** (conventions §16: "edit details of sale" is blocked when LAID).

**Status codes**
| Code | When |
|------|------|
| 200 | Details of sale saved |
| 400 | Malformed JSON, invalid `orderId` format, missing `supply_only`, invalid date format, invalid `lay_date_status` enum, lay-date pair rule violated, blank non-null string fields |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in session's `(business_id, store_id)`, or business slug not found / inactive |
| 422 | Order is `LAID` (`ORDER_LOCKED`) |
| 500 | Unexpected |

**Error example — lay-date pair violation**
```json
{
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "One or more fields are invalid.",
    "details": [
      {
        "field": "lay_date_status",
        "message": "proposed_lay_date and lay_date_status must both be set or both be null."
      }
    ]
  }
}
```

---

## E. Global Chunk 2 Rules

**E.1 Scope class.** Every Chunk 2 endpoint is **Standard protected** — all 7 checks from conventions §9 apply (slug → business; valid session; user belongs to business; active `store_id` in session; store belongs to business; user has store access; data scoped to session's `(business_id, store_id)`).

**E.2 Order-belongs-to-store check.** Every order-level endpoint additionally enforces:
- `sales_order.business_id = session.business_id` AND `sales_order.store_id = session.store_id`.
- Failure → **404 `ORDER_NOT_FOUND`**, never 403 (conventions §4 / §9 — never confirm cross-tenant or cross-store existence).

**E.3 Backend-controlled fields.** Client never sends or controls:
- `business_id`, `store_id`, `user_id`
- `order_sequence_number`, `order_number`
- `week_year`, `week_number`
- `created_at`, `updated_at`
- Any cost field
- Any calculated/derived field (totals, GP, persisted financials)
- `address_type` on address endpoints (path determines it)

**E.4 LAID lock (conventions §16).**
| Action | Allowed when LAID? |
|--------|--------------------|
| `POST /orders` | N/A — creating a new order |
| `GET /orders/{orderId}` | Yes |
| `PUT /orders/{orderId}/customer` | No → 422 `ORDER_LOCKED` |
| `PUT /orders/{orderId}/addresses/installation` | No → 422 `ORDER_LOCKED` |
| `PUT /orders/{orderId}/addresses/billing` | No → 422 `ORDER_LOCKED` |
| `POST /orders/{orderId}/addresses/billing/copy-from-installation` | No → 422 `ORDER_LOCKED` |
| `PUT /orders/{orderId}/details-of-sale` | No → 422 `ORDER_LOCKED` |

LAID lock check happens after all session/scoping/order-existence checks pass. The 422 response uses the standard error wrapper.

**E.5 Replace semantics for PUT.** PUT endpoints in Chunk 2 are full replacement of the fields they own:
- Required fields must be present in the body (else 400).
- Optional fields omitted from the body are written as `null` to the DB.
- For nested resources that are upsertable (customer, addresses), PUT inserts if absent and replaces all columns if present.

**E.6 Response shape and formatting.**
- Standard wrapper: `{ "data": ..., "message"?: ... }` (conventions §3).
- Error wrapper: `{ "error": { code, message, details? } }` (conventions §3).
- snake_case JSON fields throughout (conventions §2).
- UPPER_SNAKE_CASE for enums (`SOFT`, `HARD`, `LEAD`, `CONFIRMED`, `TO_BE_CONFIRMED`, etc.).
- Dates: `YYYY-MM-DD`. Timestamps: `YYYY-MM-DDTHH:mm:ss` server local time (conventions §5).
- Money formatting (two decimal places) applies to the persisted financial readback fields (`sale_price_ex_gst`, `total_cost`, `gp`). Percentage formatting (also two decimal places) applies to `gp_percent`.

**E.7 No financial mutation responses in Chunk 2.** None of the Chunk 2 mutations affect order financial inputs (lines, prices, costs, sale-price override). Therefore none of the Chunk 2 mutation responses include the `order_financial_summary` block from conventions §12. Chunk 3 owns that block.

**E.8 Cross-business / cross-store misses.** Always 404 `*_NOT_FOUND`, never 403, to avoid leaking existence (conventions §4 / §9).

---

## F. Remaining Open Questions

None. All Chunk 2 rules resolve cleanly against the locked conventions doc, the locked Chunk 1 contract, and the locked DB schema (V2 / V3).