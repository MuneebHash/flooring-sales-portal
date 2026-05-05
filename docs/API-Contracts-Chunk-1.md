# Sales Portal MVP — API Contracts · Chunk 1 (Lock-Ready)

**Source of truth:** `docs/API-Conventions.md`. Where this doc is less specific than the conventions doc, the conventions doc wins.

**V5 follow-ups (noted only — not applied here):**
1. `business.slug`
2. `sales_order.price_adjustment_inc_gst`

---

## A. Chunk 1 Endpoint List

| # | Method | Path | Purpose | Scope class |
|---|--------|------|---------|-------------|
| 1 | POST | `/api/v1/{slug}/auth/login` | Authenticate user, start a server-side session | Public |
| 2 | POST | `/api/v1/{slug}/auth/select-store` | Multi-store users pick the active store for the session | Special protected (session required, `store_id` in session not required on entry) |
| 3 | POST | `/api/v1/{slug}/auth/logout` | End the current session | Special protected (session required, `store_id` not required) |
| 4 | GET  | `/api/v1/{slug}/orders` | Sales Dashboard order list with filters, search, pagination | Standard protected (all 7 checks from §9) |
| 5 | PATCH | `/api/v1/{slug}/orders/{orderId}/status` | Dashboard-only order status change (LAID lock / unlock) | Standard protected (all 7 checks from §9) |

**Scope class definitions used in Chunk 1**

- **Public** — no session required. Business resolved from URL slug.
- **Special protected** — session must be valid; business resolved from slug; logged-in user must belong to that business; `store_id` in session is not required on entry. Per conventions §8 and §9, this category covers `auth/select-store` and `auth/logout` only.
- **Standard protected** — all 7 checks from conventions §9 apply, including active store in session.

---

## B. Endpoint Contracts

### B.1 POST /api/v1/{slug}/auth/login

**Purpose.** Authenticate a salesperson within the business identified by the URL slug, and start a server-side session.

**Scope class.** Public. Business resolved from URL slug only.

**Request DTO**
```json
{
  "salesperson_code": "LC1",
  "password": "password123"
}
```

**Field rules**
- `salesperson_code` — required, string, non-empty after trim.
- `password` — required, string, non-empty.

**Response DTO — 200 OK, single-store user**
```json
{
  "data": {
    "user": {
      "user_id": 1,
      "first_name": "Liam",
      "last_name": "Carter",
      "salesperson_code": "LC1"
    },
    "stores": [
      {
        "store_id": 1,
        "name": "Aussie Floors Sydney CBD",
        "store_code": "SYD-CBD"
      }
    ],
    "active_store_id": 1,
    "store_selection_required": false
  },
  "message": "Login successful."
}
```

**Response DTO — 200 OK, multi-store user**
```json
{
  "data": {
    "user": {
      "user_id": 1,
      "first_name": "Liam",
      "last_name": "Carter",
      "salesperson_code": "LC1"
    },
    "stores": [
      { "store_id": 1, "name": "Aussie Floors Sydney CBD", "store_code": "SYD-CBD" },
      { "store_id": 2, "name": "Aussie Floors Parramatta", "store_code": "SYD-PARR" }
    ],
    "active_store_id": null,
    "store_selection_required": true
  },
  "message": "Login successful. Select a store to continue."
}
```

**Business rules**
- Resolve business from URL slug. Unknown slug or `business.is_active = false` → 404.
- Look up user by `(business_id, salesperson_code)`.
- If user is not found, OR `app_user.is_active = false`, OR password does not match via BCrypt → 401 `INVALID_CREDENTIALS` (do not reveal which of the three failed).
- Build the `stores` list as every `store` that:
  - has a row in `user_store_access` for this user, AND
  - has `store.is_active = true`.
- **Zero accessible active stores → 403 `NO_STORE_ACCESS`** (locked). Session is not created.
- **Exactly 1 accessible active store** → backend creates the session AND sets `store_id` in session immediately. Response has `store_selection_required: false` and `active_store_id` set to that store.
- **2+ accessible active stores** → backend creates the session WITHOUT `store_id`. Response has `store_selection_required: true` and `active_store_id: null`.
- On success, return the session cookie. Server-side session, no JWT (conventions §8).

**Validation**
- Request body is valid JSON.
- Both fields are present and non-empty.

**Tenant / session / store scoping**
- No session required on entry.
- Business resolved from URL slug. Client-sent `business_id` is never trusted.
- Session payload after success: `user_id` + `business_id` always; `store_id` only for single-store users.

**Status codes**
| Code | When |
|------|------|
| 200 | Authenticated; session created |
| 400 | Malformed JSON or missing required field |
| 401 | Invalid credentials (user not found, inactive, or wrong password) |
| 403 | Credentials valid but user has no accessible active store in this business |
| 404 | Business slug not found or business inactive |
| 500 | Unexpected |

**Error examples**
```json
{
  "error": {
    "code": "INVALID_CREDENTIALS",
    "message": "Invalid salesperson code or password."
  }
}
```
```json
{
  "error": {
    "code": "NO_STORE_ACCESS",
    "message": "You do not have access to any store in this business."
  }
}
```

---

### B.2 POST /api/v1/{slug}/auth/select-store

**Purpose.** Multi-store users pick the active store for the session. The chosen `store_id` is persisted in the session.

**Scope class.** Special protected. Session must be valid; `store_id` in session is not required on entry.

**Request DTO**
```json
{ "store_id": 1 }
```

**Field rules**
- `store_id` — required, positive integer.

**Response DTO — 200 OK**
```json
{
  "data": {
    "user": {
      "user_id": 1,
      "first_name": "Liam",
      "last_name": "Carter",
      "salesperson_code": "LC1"
    },
    "active_store": {
      "store_id": 1,
      "name": "Aussie Floors Sydney CBD",
      "store_code": "SYD-CBD"
    }
  },
  "message": "Store selected."
}
```

**Business rules**
- Session must be valid (401 otherwise).
- Resolve business from URL slug. 404 if unknown or inactive.
- Logged-in user must belong to the resolved business. 404 otherwise (never confirm cross-tenant existence — conventions §4 / §9).
- Requested `store_id` must belong to the resolved business AND have `store.is_active = true`. Otherwise 404.
- User must have access to the requested store via `user_store_access`. Otherwise 403.
- **If `store_id` is already set in session:**
  - Same `store_id` requested → **200 OK, idempotent** (locked). Session unchanged.
  - Different `store_id` requested → **409 `STORE_ALREADY_SELECTED`** (locked). Session is not modified. The user must log out and log in again to use a different store (conventions §8).
- On success when `store_id` is not yet set in session, backend writes `store_id` into the session.

**Validation**
- Request body is valid JSON.
- `store_id` is a positive integer.

**Tenant / session / store scoping**
- Session + business resolution + user-in-business, per conventions §8 / §9.
- `store_id` in body is validated against the session's business and `user_store_access`. Client-sent `business_id` is never trusted.

**Status codes**
| Code | When |
|------|------|
| 200 | Store selected and saved in session, or idempotent re-select of the same store |
| 400 | Malformed JSON or invalid `store_id` type |
| 401 | No session / session expired |
| 403 | User does not have access to the requested store |
| 404 | Business slug not found/inactive, or store not found in this business |
| 409 | Different store already selected in this session — user must log out |
| 500 | Unexpected |

**Error example — 409**
```json
{
  "error": {
    "code": "STORE_ALREADY_SELECTED",
    "message": "A different store is already selected in this session. Log out to switch stores."
  }
}
```

---

### B.3 POST /api/v1/{slug}/auth/logout

**Purpose.** End the current session.

**Scope class.** Special protected. Session must be valid; `store_id` in session is not required.

**Request DTO.** Empty body.

**Response DTO — 200 OK**
```json
{
  "data": null,
  "message": "Logged out."
}
```

**Business rules**
- Session must be valid (401 otherwise).
- Resolve business from URL slug (404 if unknown or inactive).
- Logged-in user must belong to the resolved business (404 otherwise).
- Invalidate server-side session and clear the session cookie on the client.
- Works regardless of whether a store was selected (conventions §8).

**Validation.** None.

**Status codes**
| Code | When |
|------|------|
| 200 | Session ended |
| 401 | No session to end |
| 404 | Business slug not found/inactive |
| 500 | Unexpected |

---

### B.4 GET /api/v1/{slug}/orders

**Purpose.** Return the paginated Sales Dashboard order list for the active business + active store.

**Scope class.** Standard protected. All 7 checks from conventions §9 apply. Data is scoped to the active `(business_id, store_id)` from the session.

**Request.** Query-string parameters only (no body).

**Query parameters**

| Name | Type | Required | Default | Notes |
|------|------|----------|---------|-------|
| `page` | int | no | 1 | Min 1. Conventions §6. |
| `page_size` | int | no | 20 | Min 1, max 100. Conventions §6. |
| `status` | string | no | — | **Single** enum value: one of `LEAD, NEW_ACHIEVED_SALE, FOLLOW_UP, ACCEPTED, LAID, CANCELLED`. Matches the single-select dashboard dropdown. Multi-value not supported in Chunk 1. |
| `week_year` | int | no | — | 2000–2100. Grounded in conventions §7 (example), §19, and DB column `sales_order.week_year`. |
| `week_number` | int | no | — | 1–53. Grounded in conventions §7 (example), §19, and DB column `sales_order.week_number`. |
| `search` | string | no | — | Case-insensitive partial match. See Business rules. Grounded in conventions §7. |

**Response DTO — 200 OK**
```json
{
  "data": [
    {
      "order_id": 1,
      "order_number": "SYD-CBD.LC1.00001",
      "order_sequence_number": 1,
      "flooring_type": "SOFT",
      "order_status": "ACCEPTED",
      "customer": {
        "first_name": "James",
        "last_name": "Wilson",
        "email": "james.wilson@email.com"
      },
      "install_address": {
        "unit_number": null,
        "street_number": "42",
        "street": "Oxford Street",
        "suburb": "Paddington",
        "state_code": "NSW",
        "postcode": "2021"
      },
      "last_emailed_at": null,
      "week_year": 2026,
      "week_number": 15,
      "gp": 408.00,
      "gp_percent": 48.57,
      "gp_warning": false
    }
  ],
  "pagination": {
    "page": 1,
    "page_size": 20,
    "total_items": 8,
    "total_pages": 1
  }
}
```

**Per-row field rules — all grounded in the locked schema**

- `order_id` — `sales_order.order_id`. Internal PK. Returned so the frontend can build URLs (e.g. `/orders/{orderId}`). Not a display value (conventions §18).
- `order_number` — `sales_order.order_number` exactly as stored. The dashboard's "Order #" column displays this string verbatim. The format is locked in conventions §18: `{store_code}.{salesperson_code}.{order_sequence_number_padded_5}`.
- `order_sequence_number` — `sales_order.order_sequence_number`. Returned alongside `order_number` so the frontend has the raw sequence value if needed.
- `flooring_type` — `sales_order.flooring_type`. Always `SOFT` or `HARD`. NOT NULL on every order, set at creation, locked per conventions §17.
- `order_status` — `sales_order.order_status` enum value. NOT NULL.
- `customer` — nested object with `first_name`, `last_name`, and `email` from the order's `order_customer` row. **Nullable: when no `order_customer` row exists for the order, the entire `customer` object is `null`** (locked). When the row exists, all three fields are NOT NULL per the DB schema (`order_customer.first_name`, `last_name`, and `email` are all NOT NULL with non-blank CHECK constraints). The `email` field is included on dashboard rows because the dashboard surfaces customer email next to last-emailed timing.
- `install_address` — nested object built from the `order_address` row where `address_type = 'INSTALLATION'`. Fields exposed are the raw DB columns: `unit_number, street_number, street, suburb, state_code, postcode`. **Nullable: when no installation address row exists for the order, the entire `install_address` object is `null`** (locked). When the row exists, `unit_number` may be `null`; all other fields are NOT NULL (DB constraints).
- `last_emailed_at` — `sales_order.last_emailed_at`. May be `null`. Returned as ISO 8601 timestamp per conventions §5. The field is exposed as-stored; this contract does not introduce update behavior or a display format for it.
- `week_year`, `week_number` — `sales_order.week_year` and `sales_order.week_number`. NOT NULL (DB constraints).
- `gp` — `sales_order.gp`. Two decimal places. May be `null`.
- `gp_percent` — `sales_order.gp_percent`. Two decimal places. May be `null`.
- `gp_warning` — derived. **Locked rule:** `gp_warning = true` iff `gp_percent` is non-null AND `gp_percent < 15`. Otherwise `gp_warning = false`. In particular, when `gp` and `gp_percent` are both `null` (empty-order GP), `gp_warning = false` (conventions §12).

**Business rules / scoping**
- Only rows where `sales_order.business_id = session.business_id` AND `sales_order.store_id = session.store_id` are returned.
- No data from other stores, even within the same business (conventions §9).
- `CANCELLED` orders are included unless filtered out via `?status=...`.
- Empty result set → 200 with `"data": []`. Never 404.
- **Default ordering (locked):** newest first by `sales_order.created_at` descending. No client-facing `sort` parameter in MVP — conventions do not define one.
- **Search behavior (locked):** case-insensitive partial match across the following text fields:
  - `sales_order.order_number`
  - `order_customer.first_name`
  - `order_customer.last_name`
  - `order_customer.email`
  - `order_customer.company_name`
  A row matches if any of those fields matches. Orders with no `order_customer` row can still match via `order_number`.

**Validation**
- `page` must be ≥ 1. Invalid → 400 `VALIDATION_FAILED`.
- `page_size` must be 1–100. Invalid → 400 `VALIDATION_FAILED`.
- `status`, if present, must be exactly one of the 6 enum values. Unknown value → 400 `VALIDATION_FAILED`. Multiple comma-separated values are not accepted in Chunk 1.
- `week_year`, if present, must be 2000–2100.
- `week_number`, if present, must be 1–53.
- Unknown query parameters are ignored (do not fail).

**Status codes**
| Code | When |
|------|------|
| 200 | OK (empty list is still 200) |
| 400 | Invalid query parameter (`page`, `page_size`, `status`, `week_year`, `week_number`) |
| 401 | No session |
| 403 | Session has no active store OR user does not have access to the active store |
| 404 | Business slug not found/inactive |
| 500 | Unexpected |

---

### B.5 PATCH /api/v1/{slug}/orders/{orderId}/status

**Purpose.** Change an order's status from the Sales Dashboard. Setting `LAID` locks the order; changing away from `LAID` unlocks it.

**Scope class.** Standard protected. All 7 checks from conventions §9 apply, plus a resource-level check that the order belongs to the session's `(business_id, store_id)`.

**Path parameters**
- `orderId` — internal `order_id`, positive integer.

**Request DTO**
```json
{ "order_status": "ACCEPTED" }
```

**Field rules**
- `order_status` — required; must be one of `LEAD, NEW_ACHIEVED_SALE, FOLLOW_UP, ACCEPTED, LAID, CANCELLED`.

**Response DTO — 200 OK**
```json
{
  "data": {
    "order_id": 1,
    "order_number": "SYD-CBD.LC1.00001",
    "order_status": "LAID",
    "previous_order_status": "ACCEPTED",
    "locked": true,
    "updated_at": "2026-04-23T11:20:00"
  },
  "message": "Order status updated."
}
```

**Response field notes**
- `previous_order_status` — `sales_order.order_status` immediately before this request. Equal to `order_status` when the request is a no-op.
- `locked` — convenience flag: `true` iff new `order_status == "LAID"` (conventions §15 / §16).
- `updated_at` — `sales_order.updated_at` after the change.

**Business rules**
- No transition matrix: any status → any status allowed (conventions §15).
- New status `LAID` → order becomes locked per conventions §16.
- Previous status `LAID` + new status anything else → order becomes editable again.
- New status equal to current status → no-op; return 200 with `previous_order_status == order_status`. No write needs to occur.
- Dashboard-only surface. There is no equivalent status-change endpoint inside the order workspace (conventions §15).
- Order must exist within `(session.business_id, session.store_id)`. Any other case → 404 `ORDER_NOT_FOUND`. This includes orders that exist in a different store of the same business (conventions §4 / §9 — never confirm cross-tenant or cross-store existence).

**Validation**
- `orderId` path parameter is a positive integer.
- Request body is valid JSON.
- `order_status` is present and is one of the 6 enum values.

**Tenant / session / store scoping**
- All 7 checks from conventions §9.
- Additional resource-level check: `sales_order.business_id = session.business_id` AND `sales_order.store_id = session.store_id`. Either failing → 404.

**Status codes**
| Code | When |
|------|------|
| 200 | Status updated (or no-op) |
| 400 | Malformed JSON, missing `order_status`, invalid enum value, or invalid `orderId` format |
| 401 | No session |
| 403 | Session has no active store OR user does not have access to the active store |
| 404 | Order not in session's `(business_id, store_id)`, or business slug not found/inactive |
| 500 | Unexpected |

**Error examples**

Invalid enum:
```json
{
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "One or more fields are invalid.",
    "details": [
      { "field": "order_status", "message": "Must be one of LEAD, NEW_ACHIEVED_SALE, FOLLOW_UP, ACCEPTED, LAID, CANCELLED." }
    ]
  }
}
```

Not found:
```json
{
  "error": {
    "code": "ORDER_NOT_FOUND",
    "message": "Order not found."
  }
}
```

---

## C. Global Business / Validation / Scoping Rules (Chunk 1)

These apply across every Chunk 1 endpoint and are the single source of enforcement.

**C.1 Slug resolution**
- Business slug is read from the URL path.
- Unknown slug or `business.is_active = false` → 404 on every endpoint.

**C.2 Session**
- Server-side session backed by a session cookie (conventions §8). No JWT.
- Session payload after login: `user_id`, `business_id`. `store_id` is added at login (single-store user) or by `auth/select-store` (multi-store user).
- Every protected endpoint verifies the session first. Missing or invalid session → 401.
- Logged-in user must belong to the business resolved from the slug. Otherwise 404 (never confirm cross-tenant existence).

**C.3 Store scoping**
- Standard protected endpoints require `store_id` in session. If absent → 403.
- Standard protected endpoints scope every data query to `(business_id, store_id)` from the session.
- Rows belonging to a different store (even in the same business) are invisible — look like 404 for singular resources, absent for list responses.

**C.4 Store switching (locked)**
- A user cannot change the active store during a session. To work under a different store, the user must log out and log back in (conventions §8).
- `auth/select-store` enforces this: 409 `STORE_ALREADY_SELECTED` when a different store is already set in the session; 200 idempotent when the same store is re-selected.

**C.5 Cross-tenant / cross-store misses**
- Never 403 for cross-tenant or cross-store. Use 404 `*_NOT_FOUND` to avoid leaking existence (conventions §4 / §9).

**C.6 Response shape**
- All success responses use the `{ "data": ..., "message"?: ... }` wrapper (conventions §3).
- Collection responses include the `pagination` object.
- All error responses use the `{ "error": { code, message, details? } }` shape (conventions §3).

**C.7 Money, percentages, dates, timestamps**
- Money formatting applies to `gp` — `DECIMAL(10,2)`, always two decimal places (conventions §5).
- Percentage formatting applies to `gp_percent`, also returned with two decimal places.
- Dates: `YYYY-MM-DD`. Timestamps: `YYYY-MM-DDTHH:mm:ss`, server local time (Australia).

**C.8 GP display (locked)**
- `gp`, `gp_percent` may be `null` for orders with no priced lines or zero revenue (conventions §12).
- `gp_warning = true` iff `gp_percent` is non-null AND `< 15`. Otherwise `false`. Empty-order GP (`gp = null`, `gp_percent = null`) → `gp_warning = false`.

**C.9 Dashboard row nullability (locked)**
- Orders without an `order_customer` row → `customer: null`.
- Orders without an `INSTALLATION` row in `order_address` → `install_address: null`.
- All other dashboard row fields are sourced from `sales_order` and are always present (NOT NULL columns) except `last_emailed_at` (nullable in DB).

**C.10 Search (locked)**
- Case-insensitive partial match on `sales_order.order_number`, `order_customer.first_name`, `order_customer.last_name`, `order_customer.email`, `order_customer.company_name`.
- A row matches if any of those fields matches.
- Orders with no `order_customer` row can still match via `order_number`.

**C.11 Default ordering (locked)**
- `GET /orders` default sort: `sales_order.created_at` descending (newest first). Backend-only; no `sort` query parameter is part of the Chunk 1 contract.

---

## D. Changes Made from Previous Draft

1. **Removed `GET /auth/me`.** No app-context endpoint in Chunk 1. On session loss, the frontend re-logs in.
2. **Locked store-switching behavior on `auth/select-store`.**
   - Same store re-selected → 200 idempotent.
   - Different store selected → **409 `STORE_ALREADY_SELECTED`**.
   - No longer an open question.
3. **Locked "zero accessible active stores" on login** → **403 `NO_STORE_ACCESS`**.
4. **Locked empty-order GP on dashboard rows** → `gp = null`, `gp_percent = null`, `gp_warning = false`.
5. **Replaced prescriptive display strings with grounded structured fields** on dashboard rows:
   - `customer_name` (concatenated string) → `customer` nested object with `first_name` and `last_name`.
   - `install_address_summary` (formatted string) → `install_address` nested object with the raw DB columns (`unit_number, street_number, street, suburb, state_code, postcode`).
   - Both objects are nullable when the underlying row does not exist; nullability is now locked, not open.
   - This avoids inventing punctuation/concatenation rules; the frontend builds the display string from the grounded fields.
6. **`order_number` uses the locked conventions §18 format.** It is returned exactly as stored on `sales_order.order_number`, and the frontend displays it verbatim. `order_sequence_number` is exposed alongside it for completeness.
7. **`flooring_type` retained on dashboard rows.** Strict definition: `sales_order.flooring_type`, NOT NULL, value `SOFT` or `HARD`, locked at order creation per conventions §17. No conditional language.
8. **`last_emailed_at` retained as a grounded, nullable timestamp field.** No display formatting, no update semantics defined here — that is out of scope for Chunk 1.
9. **`status` filter locked to single value.** Comma-separated multi-status (allowed by conventions §7 in general) is not used in Chunk 1; the dashboard filter UI is a single-select dropdown. Only the 6 currently locked statuses are accepted.
10. **`week_year` and `week_number` filters retained.** Grounded in conventions §7 (example) and §19, and in the DB.
11. **`sort` query parameter removed.** Not grounded in conventions. Default ordering is locked backend-side (`created_at` descending).
12. **Dropped soft / non-contract questions:** session cookie attributes, slug case-sensitivity, `auth/me`, `flooring_type` "if needed" wording. None remain.
13. **Login normalized.** `app_user.is_active = false` is treated as 401 `INVALID_CREDENTIALS` (does not reveal which check failed). Stores list requires both `user_store_access` row AND `store.is_active = true`.
14. **Added `email` to the dashboard `customer` nested object.** The dashboard surfaces customer email next to last-emailed timing, so the frontend needs it. `email` is sourced from `order_customer.email`, which is NOT NULL with a non-blank CHECK constraint when the row exists. Search rule (B.4 and C.10) updated to also match against `order_customer.email`.
15. **Corrected wording in C.7.** `gp_percent` is no longer described as money. C.7 now distinguishes money formatting (applies to `gp`) from percentage formatting (applies to `gp_percent`); both are returned with two decimal places.

---

## E. Remaining Open Questions

None. All previously open items have been resolved against the locked conventions doc, the 5 locked Phase 4 decisions, the locked DB/schema, and the relevant Chunk 1 screenshots/workflow (login, salesperson login, dashboard, dashboard filter).