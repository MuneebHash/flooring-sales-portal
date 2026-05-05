# Sales Portal MVP — API Contracts · Chunk 3 (Lock-Ready)

**Source of truth priority:**
1. `docs/API-Conventions.md`
2. Locked Chunk 1 contract
3. Locked Chunk 2 contract
4. Locked DB schema (V2 / V3)

**V5 follow-ups (referenced — required by Chunk 3 logic):**
1. `business.slug`
2. `sales_order.price_adjustment_inc_gst` — required for sale price override / reset.

**Order number format (locked):** `{store_code}.{salesperson_code}.{order_sequence_number_padded_5}` — e.g. `SYD-CBD.LC1.00042`. Backend-generated at order creation. Frontend never sends `order_number`. Used in Chunk 3 examples below.

---

## A. Chunk 3 Scope — Included

- Catalog search inside an order: available products and available charges.
- Combined read of an order's lines and live financial summary.
- Product lines: add, edit (quantity / unit_price), delete.
- Charge lines: add, edit (quantity / unit_price), delete.
- Manual sale price override (GST-inclusive) and Reset Price.
- Notes: list, add (append-only).
- Attachments / photos: list, add (multipart upload), delete, file download.

Every mutation that affects financials returns the live `order_financial_summary` block (conventions §12).

---

## B. Chunk 3 Scope — Excluded / Deferred

Already locked in Chunk 1 / Chunk 2 — not reopened:
- Auth, dashboard, status update.
- Order create / customer / addresses / details-of-sale.
- `GET /orders/{orderId}` (order workspace shell).

Deferred to Chunk 4 or out of MVP scope:
- Invoice creation / rewrite / history / PDF generation.
- Invoice precondition validation (conventions §14).
- Payment recording.
- Auto invoice version on payment (conventions §13).
- `SIGNATURE` attachment kind workflow (the enum exists in the schema; Chunk 3 only accepts `PHOTO` on upload).
- Operations Portal management of products and charges (catalog write endpoints). Chunk 3 only reads the catalog.
- Note edit / delete (notes are immutable for MVP).
- Stock decrement on sale (out of MVP).

---

## C. Chunk 3 Endpoint List

All endpoints are **Standard protected** (all 7 checks from conventions §9). Every order-level endpoint additionally enforces `sales_order.business_id = session.business_id` AND `sales_order.store_id = session.store_id`. Every line-level endpoint additionally enforces the line belongs to the order in the path.

| # | Method | Path | Purpose |
|---|--------|------|---------|
| 1 | GET | `/api/v1/{slug}/orders/{orderId}/available-products` | Catalog search of products available to this order |
| 2 | GET | `/api/v1/{slug}/orders/{orderId}/available-charges` | Catalog search of charges available to this order |
| 3 | GET | `/api/v1/{slug}/orders/{orderId}/lines` | Read product lines + charge lines + live order financial summary |
| 4 | POST | `/api/v1/{slug}/orders/{orderId}/product-lines` | Add a product line |
| 5 | PATCH | `/api/v1/{slug}/orders/{orderId}/product-lines/{lineId}` | Edit product line quantity and/or unit price |
| 6 | DELETE | `/api/v1/{slug}/orders/{orderId}/product-lines/{lineId}` | Delete a product line |
| 7 | POST | `/api/v1/{slug}/orders/{orderId}/charge-lines` | Add a charge line |
| 8 | PATCH | `/api/v1/{slug}/orders/{orderId}/charge-lines/{lineId}` | Edit charge line quantity and/or unit price |
| 9 | DELETE | `/api/v1/{slug}/orders/{orderId}/charge-lines/{lineId}` | Delete a charge line |
| 10 | PUT | `/api/v1/{slug}/orders/{orderId}/sale-price` | Manual GST-inclusive sale price override |
| 11 | POST | `/api/v1/{slug}/orders/{orderId}/sale-price/reset` | Reset sale price (clear adjustment) |
| 12 | GET | `/api/v1/{slug}/orders/{orderId}/notes` | List notes (paginated) |
| 13 | POST | `/api/v1/{slug}/orders/{orderId}/notes` | Append a note |
| 14 | GET | `/api/v1/{slug}/orders/{orderId}/attachments` | List attachments (paginated) |
| 15 | POST | `/api/v1/{slug}/orders/{orderId}/attachments` | Upload a new attachment (multipart) |
| 16 | DELETE | `/api/v1/{slug}/orders/{orderId}/attachments/{attachmentId}` | Remove an attachment |
| 17 | GET | `/api/v1/{slug}/orders/{orderId}/attachments/{attachmentId}/file` | Download / serve attachment binary |

Endpoints 16 and 17 are additions on top of the suggested set; rationale in section G.

---

## D. Endpoint Contracts

### D.1 GET /api/v1/{slug}/orders/{orderId}/available-products

**Purpose.** Catalog search for products that the salesperson can add to this order.

**Scope class.** Standard protected.

**Path params.** `orderId` — positive integer.

**Query params**

| Name | Type | Required | Default | Notes |
|------|------|----------|---------|-------|
| `page` | int | no | 1 | Min 1 |
| `page_size` | int | no | 20 | Min 1, max 100 |
| `search` | string | no | — | Case-insensitive partial match on `code` or `name` |

**Request DTO.** None.

**Response DTO — 200 OK**
```json
{
  "data": [
    {
      "product_id": 1,
      "code": "PLU-001",
      "name": "Plush Carpet Premium",
      "flooring_type": "SOFT",
      "pricing_unit": "LM",
      "price": 45.00,
      "stock_quantity": 320.00,
      "stock_unit": "LM"
    }
  ],
  "pagination": {
    "page": 1,
    "page_size": 20,
    "total_items": 2,
    "total_pages": 1
  }
}
```

**Field rules**
- All fields sourced directly from `store_product`. NOT NULL columns are always present.
- `stock_quantity` and `stock_unit` may be `null` (DB allows; paired by `chk_store_product_stock_pair`).
- **`cost` is NEVER returned** (conventions §10 — backend-only).

**Business rules / scoping**
- Result set is filtered to:
  - `store_product.store_id = session.store_id`
  - `store_product.flooring_type = sales_order.flooring_type` (the order's flooring type drives the filter — conventions §17)
  - `store_product.is_active = true`
- Order must exist within the session's `(business_id, store_id)`. Otherwise 404.
- Empty result set → 200 with `"data": []`.
- Default ordering: `code` ascending.

**Validation**
- `orderId` is a positive integer.
- `page` ≥ 1; `page_size` ∈ [1, 100].

**LAID lock.** GET allowed regardless of order status.

**Status codes**
| Code | When |
|------|------|
| 200 | OK |
| 400 | Invalid `orderId` format, invalid `page` / `page_size` |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in session's `(business_id, store_id)`, or business slug not found / inactive |
| 500 | Unexpected |

---

### D.2 GET /api/v1/{slug}/orders/{orderId}/available-charges

**Purpose.** Catalog search for charges that the salesperson can add to this order.

**Scope class.** Standard protected.

**Path params.** `orderId` — positive integer.

**Query params.** Same as D.1 (`page`, `page_size`, `search`).

**Request DTO.** None.

**Response DTO — 200 OK**
```json
{
  "data": [
    {
      "charge_id": 1,
      "code": "INST-S",
      "name": "Carpet Installation",
      "flooring_type": "SOFT",
      "price": 15.00
    }
  ],
  "pagination": {
    "page": 1,
    "page_size": 20,
    "total_items": 2,
    "total_pages": 1
  }
}
```

**Field rules**
- Sourced from `store_charge`. **`cost` is NEVER returned** (conventions §10).
- Note: `store_charge` has no pricing unit or stock — those are product-only.

**Business rules / scoping**
- Result set filtered to:
  - `store_charge.store_id = session.store_id`
  - `store_charge.flooring_type = sales_order.flooring_type`
  - `store_charge.is_active = true`
- Otherwise identical to D.1.

**Validation, LAID lock, status codes.** Same as D.1.

---

### D.3 GET /api/v1/{slug}/orders/{orderId}/lines

**Purpose.** Single read of the order's product lines, charge lines, and live financial summary. Used when the frontend opens the order workspace to populate Products + Charges + Sale Price sections.

**Scope class.** Standard protected.

**Path params.** `orderId` — positive integer.

**Query params.** None.

**Request DTO.** None.

**Response DTO — 200 OK**
```json
{
  "data": {
    "product_lines": [
      {
        "order_product_line_id": 1,
        "product_id": 1,
        "product_code_snapshot": "PLU-001",
        "product_name_snapshot": "Plush Carpet Premium",
        "pricing_unit_snapshot": "LM",
        "price_snapshot": 45.00,
        "quantity_lm": 8.00,
        "quantity_sqm": 29.28,
        "unit_price": 45.00,
        "line_total": 360.00,
        "created_at": "2026-04-14T09:30:00",
        "updated_at": "2026-04-14T09:30:00"
      }
    ],
    "charge_lines": [
      {
        "order_charge_line_id": 1,
        "charge_id": 1,
        "charge_code_snapshot": "INST-S",
        "charge_name_snapshot": "Carpet Installation",
        "price_snapshot": 15.00,
        "quantity": 32.00,
        "unit_price": 15.00,
        "line_total": 480.00,
        "created_at": "2026-04-14T09:32:00",
        "updated_at": "2026-04-14T09:32:00"
      }
    ],
    "order_financial_summary": { "...see E.1..." }
  }
}
```

**Field rules**
- See shared DTOs in section E (`product_line_read`, `charge_line_read`, `order_financial_summary`).
- **`cost_snapshot` and `line_cost` are NEVER returned per line.** Aggregate `total_cost`, `gp`, `gp_percent` are returned in `order_financial_summary` only (conventions §10 / §12).
- `order_financial_summary` is computed live from the current persisted lines and the persisted `price_adjustment_inc_gst` on `sales_order`.
- Pagination is intentionally not applied here — the frontend needs all lines to render the workspace and the financial summary depends on all lines being present.

**Business rules / scoping**
- Lines are returned for the requested order only. Cross-tenant / cross-store / cross-order misses → 404.
- Line ordering: by `created_at` ascending (insertion order). Stable for the salesperson's mental model.
- Empty order → `product_lines: []`, `charge_lines: []`, summary still returned with zero subtotals (see section F.5).

**Validation**
- `orderId` is a positive integer.

**LAID lock.** GET allowed regardless of order status.

**Status codes**
| Code | When |
|------|------|
| 200 | OK |
| 400 | Invalid `orderId` format |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in session's `(business_id, store_id)`, or business slug not found / inactive |
| 500 | Unexpected |

---

### D.4 POST /api/v1/{slug}/orders/{orderId}/product-lines

**Purpose.** Add a product line to the order. Backend snapshots, derives quantities, and recomputes financials.

**Scope class.** Standard protected.

**Path params.** `orderId` — positive integer.

**Request DTO**
```json
{
  "product_id": 1,
  "quantity_lm": 8.00,
  "quantity_sqm": null,
  "unit_price": null
}
```

**Field rules**
- `product_id` — required, positive integer.
- `quantity_lm` — required-or-`quantity_sqm`; if provided, must be > 0.
- `quantity_sqm` — required-or-`quantity_lm`; if provided, must be > 0.
- **Exactly one of `quantity_lm` and `quantity_sqm` must be provided** (conventions §11). Sending neither or both → 400.
- `unit_price` — optional. If provided, must be > 0; treated as the salesperson's manual override. If omitted or `null`, backend uses `price_snapshot` as the unit price.

**Response DTO — 201 Created**
```json
{
  "data": {
    "product_line": { "...see E.2 product_line_read..." },
    "order_financial_summary": { "...see E.1..." }
  },
  "message": "Product line added."
}
```

**Business rules** (conventions §10, §11, §12, §17)
1. Load `store_product` by `product_id`.
2. Verify `store_product.store_id = session.store_id`. Otherwise → 404 `PRODUCT_NOT_FOUND` (no cross-store leak).
3. Verify `store_product.is_active = true`. Otherwise → 422 `PRODUCT_INACTIVE`.
4. Verify `store_product.flooring_type = sales_order.flooring_type`. Otherwise → 422 `FLOORING_TYPE_MISMATCH` (conventions §17).
5. Snapshot: `product_code_snapshot`, `product_name_snapshot`, `pricing_unit_snapshot`, `price_snapshot`, `cost_snapshot` (all from current `store_product`).
6. Derive missing quantity using fixed conversion `1 LM = 3.66 SQM` (conventions §11):
   - if `quantity_lm` given → `quantity_sqm = round(quantity_lm × 3.66, 2)`
   - if `quantity_sqm` given → `quantity_lm = round(quantity_sqm / 3.66, 2)`
7. `unit_price_used = unit_price` (if provided) else `price_snapshot`.
8. Calculation per `pricing_unit_snapshot` (conventions §11):
   - `LM`: `line_total = round(quantity_lm × unit_price_used, 2)`, `line_cost = round(quantity_lm × cost_snapshot, 2)`
   - `SQM`: `line_total = round(quantity_sqm × unit_price_used, 2)`, `line_cost = round(quantity_sqm × cost_snapshot, 2)`
9. Persist row in `order_product_line`.
10. Recompute live `order_financial_summary` (section F.5) and return it.

**Validation**
- `orderId` is a positive integer.
- `product_id` is a positive integer.
- Exactly one of `quantity_lm`, `quantity_sqm`. The provided value is > 0.
- `unit_price`, if present and non-null, > 0.
- `cost`, `cost_snapshot`, `line_total`, `line_cost`, `price_snapshot`, `pricing_unit_snapshot`, snapshot codes/names — **never accepted from the client**. Sending them → 400 `VALIDATION_FAILED`.

**Tenant / session / store / order scoping**
- All 7 checks from conventions §9.
- Order belongs to session's `(business_id, store_id)`. Otherwise 404.

**LAID lock.** Blocked when order is `LAID` → **422 `ORDER_LOCKED`** (conventions §16).

**Status codes**
| Code | When |
|------|------|
| 201 | Product line added |
| 400 | Malformed JSON, invalid `orderId` format, missing `product_id`, both or neither quantity, non-positive quantities or unit_price, or any forbidden snapshot/cost field present |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in scope, or product not in active store |
| 422 | Order is `LAID` (`ORDER_LOCKED`), product inactive (`PRODUCT_INACTIVE`), flooring type mismatch (`FLOORING_TYPE_MISMATCH`) |
| 500 | Unexpected |

**Error example — flooring mismatch**
```json
{
  "error": {
    "code": "FLOORING_TYPE_MISMATCH",
    "message": "Product flooring type does not match the order's flooring type."
  }
}
```

---

### D.5 PATCH /api/v1/{slug}/orders/{orderId}/product-lines/{lineId}

**Purpose.** Edit quantity and/or unit price of an existing product line. Re-derives the missing quantity if either is changed; re-computes line totals and the order financial summary.

**Scope class.** Standard protected.

**Path params.** `orderId`, `lineId` — both positive integers.

**Request DTO** (all fields optional; at least one must be present)
```json
{
  "quantity_lm": 10.00,
  "quantity_sqm": null,
  "unit_price": 42.00
}
```

**Field rules**
- `quantity_lm` — optional; if provided, > 0.
- `quantity_sqm` — optional; if provided, > 0.
- **At most one** of `quantity_lm` / `quantity_sqm` may be provided. Sending both → 400. Sending neither → quantities are unchanged.
- `unit_price` — optional; if provided, > 0. If omitted, the line's existing `unit_price` is unchanged.
- The line's `product_id` and all snapshot fields cannot be changed via PATCH. To change product → DELETE then POST.
- Body must contain at least one editable field. Empty body → 400.

**Response DTO — 200 OK**
```json
{
  "data": {
    "product_line": { "...see E.2..." },
    "order_financial_summary": { "...see E.1..." }
  },
  "message": "Product line updated."
}
```

**Business rules**
1. Load `order_product_line` by `lineId`.
2. Verify the line belongs to the order in the path. Otherwise → 404 `LINE_NOT_FOUND` (no cross-order or cross-store leak).
3. Determine new quantities:
   - Both quantity fields absent → quantities unchanged.
   - `quantity_lm` provided → recompute `quantity_sqm = round(quantity_lm × 3.66, 2)`.
   - `quantity_sqm` provided → recompute `quantity_lm = round(quantity_sqm / 3.66, 2)`.
4. Determine new `unit_price`: provided value, else existing.
5. Recompute `line_total` and `line_cost` using `pricing_unit_snapshot` (snapshot stays as it was at line creation) — same rule as D.4.
6. Persist updated row.
7. Recompute `order_financial_summary` and return it.

**Validation**
- `orderId`, `lineId` are positive integers.
- At least one editable field present.
- Mutually exclusive quantity fields.
- All numeric fields (when provided) > 0.
- No forbidden snapshot / cost fields.

**Scoping.** Standard 7 checks + order-belongs-to-store + line-belongs-to-order.

**LAID lock.** Blocked when order is `LAID` → **422 `ORDER_LOCKED`**.

**Status codes**
| Code | When |
|------|------|
| 200 | Product line updated |
| 400 | Malformed JSON, invalid `orderId` / `lineId`, both quantity fields, no editable fields, non-positive values, forbidden fields |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in scope, or line not on this order |
| 422 | Order is `LAID` (`ORDER_LOCKED`) |
| 500 | Unexpected |

---

### D.6 DELETE /api/v1/{slug}/orders/{orderId}/product-lines/{lineId}

**Purpose.** Remove a product line from the order.

**Scope class.** Standard protected.

**Path params.** `orderId`, `lineId` — positive integers.

**Request DTO.** None.

**Response DTO — 200 OK**
```json
{
  "data": {
    "deleted_product_line_id": 1,
    "order_financial_summary": { "...see E.1..." }
  },
  "message": "Product line deleted."
}
```

**Business rules**
- Load and verify line belongs to the order. Otherwise → 404 `LINE_NOT_FOUND`.
- Hard delete the row.
- Recompute summary and return it (the order may now have zero product lines — see F.5).

**Validation**
- `orderId`, `lineId` are positive integers.

**Scoping.** Same as D.5.

**LAID lock.** Blocked when order is `LAID` → **422 `ORDER_LOCKED`**.

**Status codes**
| Code | When |
|------|------|
| 200 | Product line deleted |
| 400 | Invalid `orderId` / `lineId` format |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in scope, or line not on this order |
| 422 | Order is `LAID` (`ORDER_LOCKED`) |
| 500 | Unexpected |

---

### D.7 POST /api/v1/{slug}/orders/{orderId}/charge-lines

**Purpose.** Add a charge line to the order. Backend snapshots, calculates, and recomputes financials.

**Scope class.** Standard protected.

**Path params.** `orderId` — positive integer.

**Request DTO**
```json
{
  "charge_id": 1,
  "quantity": 32.00,
  "unit_price": null
}
```

**Field rules**
- `charge_id` — required, positive integer.
- `quantity` — required, > 0.
- `unit_price` — optional; if provided, > 0. Treated as the salesperson's override. If omitted or `null`, backend uses `price_snapshot`.

**Response DTO — 201 Created**
```json
{
  "data": {
    "charge_line": { "...see E.3 charge_line_read..." },
    "order_financial_summary": { "...see E.1..." }
  },
  "message": "Charge line added."
}
```

**Business rules** (conventions §10, §12, §17)
1. Load `store_charge` by `charge_id`.
2. Verify `store_charge.store_id = session.store_id`. Otherwise → 404 `CHARGE_NOT_FOUND`.
3. Verify `store_charge.is_active = true`. Otherwise → 422 `CHARGE_INACTIVE`.
4. Verify `store_charge.flooring_type = sales_order.flooring_type`. Otherwise → 422 `FLOORING_TYPE_MISMATCH`.
5. Snapshot: `charge_code_snapshot`, `charge_name_snapshot`, `price_snapshot`, `cost_snapshot`.
6. `unit_price_used = unit_price` (if provided) else `price_snapshot`.
7. `line_total = round(quantity × unit_price_used, 2)`. `line_cost = round(quantity × cost_snapshot, 2)`.
8. Persist row in `order_charge_line`.
9. Recompute and return `order_financial_summary`.

**Validation**
- `orderId` is a positive integer.
- `charge_id` is a positive integer.
- `quantity` > 0.
- `unit_price`, if present and non-null, > 0.
- No `cost`, `cost_snapshot`, `line_total`, `line_cost`, `price_snapshot`, or snapshot codes/names from the client.

**Scoping.** Standard 7 checks + order-belongs-to-store.

**LAID lock.** Blocked when order is `LAID` → **422 `ORDER_LOCKED`**.

**Status codes**
| Code | When |
|------|------|
| 201 | Charge line added |
| 400 | Malformed JSON, invalid `orderId`, missing `charge_id` / `quantity`, non-positive values, forbidden fields |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in scope, or charge not in active store |
| 422 | Order is `LAID`, charge inactive (`CHARGE_INACTIVE`), flooring type mismatch (`FLOORING_TYPE_MISMATCH`) |
| 500 | Unexpected |

---

### D.8 PATCH /api/v1/{slug}/orders/{orderId}/charge-lines/{lineId}

**Purpose.** Edit quantity and/or unit price of an existing charge line.

**Scope class.** Standard protected.

**Path params.** `orderId`, `lineId` — positive integers.

**Request DTO** (all fields optional; at least one must be present)
```json
{
  "quantity": 40.00,
  "unit_price": 14.00
}
```

**Field rules**
- `quantity` — optional; if provided, > 0.
- `unit_price` — optional; if provided, > 0.
- Body must contain at least one of `quantity` / `unit_price`. Empty body → 400.
- The line's `charge_id` and all snapshot fields cannot be changed via PATCH.

**Response DTO — 200 OK**
```json
{
  "data": {
    "charge_line": { "...see E.3..." },
    "order_financial_summary": { "...see E.1..." }
  },
  "message": "Charge line updated."
}
```

**Business rules**
1. Load `order_charge_line` by `lineId`.
2. Verify line belongs to the order. Otherwise → 404 `LINE_NOT_FOUND`.
3. New `quantity` = provided value or existing. New `unit_price` = provided value or existing.
4. Recompute `line_total` and `line_cost` using existing `cost_snapshot`.
5. Persist updated row. Recompute summary.

**Validation, scoping, LAID lock, status codes.** Mirror D.5 (with `quantity` instead of LM/SQM).

---

### D.9 DELETE /api/v1/{slug}/orders/{orderId}/charge-lines/{lineId}

**Purpose.** Remove a charge line from the order.

**Scope class.** Standard protected.

**Path params.** `orderId`, `lineId` — positive integers.

**Request DTO.** None.

**Response DTO — 200 OK**
```json
{
  "data": {
    "deleted_charge_line_id": 1,
    "order_financial_summary": { "...see E.1..." }
  },
  "message": "Charge line deleted."
}
```

**Business rules / Validation / Scoping / LAID lock / Status codes.** Mirror D.6.

---

### D.10 PUT /api/v1/{slug}/orders/{orderId}/sale-price

**Purpose.** Salesperson manually overrides the GST-inclusive sale price shown in Details of Sale. Backend derives and stores `price_adjustment_inc_gst` (V5 column) and reapplies it on every future recalculation (conventions §12).

**Scope class.** Standard protected.

**Path params.** `orderId` — positive integer.

**Request DTO**
```json
{ "final_sale_price_inc_gst": 800.00 }
```

**Field rules**
- `final_sale_price_inc_gst` — required, money (DECIMAL(10,2)), must be > 0.
  - This is the GST-inclusive number the salesperson typed in the Details of Sale field, not ex-GST.
  - The frontend never sends `price_adjustment_inc_gst`, `gp`, `total_cost`, `calculated_total_inc_gst`, `sale_price_ex_gst`, or any other derived field.

**Response DTO — 200 OK**
```json
{
  "data": {
    "order_financial_summary": { "...see E.1..." }
  },
  "message": "Sale price updated."
}
```

**Business rules** (conventions §12)
1. Recompute current `calculated_total_inc_gst` from the order's current product and charge lines:
   - `product_subtotal = sum(line_total of product lines)` (ex-GST)
   - `charge_subtotal = sum(line_total of charge lines)` (ex-GST)
   - `calculated_total_inc_gst = round((product_subtotal + charge_subtotal) × 1.10, 2)`
2. `price_adjustment_inc_gst = round(final_sale_price_inc_gst − calculated_total_inc_gst, 2)`. May be negative (discount), positive (increase), or zero. Zero is allowed and stored as the actual zero adjustment.
3. Persist `sales_order.price_adjustment_inc_gst` with that value.
4. Reapply on summary computation (section F.5):
   - `final_sale_price_inc_gst = calculated_total_inc_gst + price_adjustment_inc_gst`
   - `sale_price_ex_gst = round(final_sale_price_inc_gst / 1.10, 2)`
5. Recompute and return `order_financial_summary`.

**Validation**
- `orderId` is a positive integer.
- `final_sale_price_inc_gst` is present, numeric, > 0, with up to 2 decimal places.
- No forbidden derived fields in the body.

**Scoping.** Standard 7 checks + order-belongs-to-store.

**LAID lock.** Blocked when order is `LAID` → **422 `ORDER_LOCKED`** (manual sale price override is a financial edit per conventions §16).

**Status codes**
| Code | When |
|------|------|
| 200 | Sale price updated |
| 400 | Malformed JSON, invalid `orderId`, missing or non-positive `final_sale_price_inc_gst`, forbidden derived fields |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in session's `(business_id, store_id)`, or business slug not found / inactive |
| 422 | Order is `LAID` (`ORDER_LOCKED`) |
| 500 | Unexpected |

---

### D.11 POST /api/v1/{slug}/orders/{orderId}/sale-price/reset

**Purpose.** Clear the manual sale price override — `price_adjustment_inc_gst` is set back to `null`. After reset, `final_sale_price_inc_gst` equals `calculated_total_inc_gst` again (conventions §12).

**Scope class.** Standard protected.

**Path params.** `orderId` — positive integer.

**Request DTO.** Empty body.

**Response DTO — 200 OK**
```json
{
  "data": {
    "order_financial_summary": { "...see E.1... with price_adjustment_inc_gst: null" }
  },
  "message": "Sale price reset."
}
```

**Business rules**
- Set `sales_order.price_adjustment_inc_gst = NULL`.
- Idempotent: calling reset when there is no current adjustment is a 200 no-op.
- Recompute and return `order_financial_summary`.

**Validation.** `orderId` is a positive integer.

**Scoping.** Same as D.10.

**LAID lock.** Blocked when order is `LAID` → **422 `ORDER_LOCKED`**.

**Status codes**
| Code | When |
|------|------|
| 200 | Sale price reset (or no-op) |
| 400 | Invalid `orderId` format |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in scope, or business slug not found / inactive |
| 422 | Order is `LAID` (`ORDER_LOCKED`) |
| 500 | Unexpected |

---

### D.12 GET /api/v1/{slug}/orders/{orderId}/notes

**Purpose.** List notes for the order.

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
      "order_note_id": 1,
      "note_text": "Customer prefers Saturday morning installation.",
      "created_at": "2026-04-14T10:05:00"
    }
  ],
  "pagination": {
    "page": 1,
    "page_size": 20,
    "total_items": 1,
    "total_pages": 1
  }
}
```

**Field rules.** Sourced from `order_note`. `note_text` is NOT NULL and non-blank by DB constraint.

**Business rules / scoping**
- Notes scoped to the order (and via the order, to session's store).
- Default ordering: `created_at` descending (newest first).

**Validation.** `orderId` is positive integer; `page` ≥ 1; `page_size` ∈ [1,100].

**LAID lock.** GET allowed regardless of order status.

**Status codes**
| Code | When |
|------|------|
| 200 | OK |
| 400 | Invalid `orderId`, invalid pagination params |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in scope, or business slug not found / inactive |
| 500 | Unexpected |

---

### D.13 POST /api/v1/{slug}/orders/{orderId}/notes

**Purpose.** Append a note to the order. Notes are immutable (no edit / no delete in MVP).

**Scope class.** Standard protected.

**Path params.** `orderId` — positive integer.

**Request DTO**
```json
{ "note_text": "Customer prefers Saturday morning installation." }
```

**Field rules**
- `note_text` — required, non-blank after trim. Mirrors DB CHECK `chk_order_note_text_not_blank`.

**Response DTO — 201 Created**
```json
{
  "data": {
    "order_note_id": 1,
    "note_text": "Customer prefers Saturday morning installation.",
    "created_at": "2026-04-14T10:05:00"
  },
  "message": "Note added."
}
```

**Business rules**
- Append-only. Insert into `order_note`.
- Adding a note does NOT trigger financial summary recomputation. The response does not include `order_financial_summary`.

**Validation**
- `orderId` is a positive integer.
- `note_text` is present and non-blank after trim.

**Scoping.** Standard 7 checks + order-belongs-to-store.

**LAID lock.** **Allowed when LAID** (conventions §16: notes may be added on LAID orders).

**Status codes**
| Code | When |
|------|------|
| 201 | Note added |
| 400 | Malformed JSON, invalid `orderId`, missing or blank `note_text` |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in scope, or business slug not found / inactive |
| 500 | Unexpected |

---

### D.14 GET /api/v1/{slug}/orders/{orderId}/attachments

**Purpose.** List attachments for the order.

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
      "order_attachment_id": 1,
      "attachment_kind": "PHOTO",
      "file_name": "site-photo-lounge.jpg",
      "mime_type": "image/jpeg",
      "file_size": 2048576,
      "download_path": "/api/v1/aussiefloors/orders/1/attachments/1/file",
      "created_at": "2026-04-14T09:45:00"
    }
  ],
  "pagination": {
    "page": 1,
    "page_size": 20,
    "total_items": 1,
    "total_pages": 1
  }
}
```

**Field rules**
- `order_attachment_id` — `order_attachment.order_attachment_id`.
- `attachment_kind` — enum `PHOTO` / `SIGNATURE`.
- `file_name`, `mime_type`, `file_size` — joined from `stored_file`.
- `download_path` — relative path to D.17 (file stream endpoint), pre-built so the frontend doesn't have to assemble it.
- `storage_path` is NOT exposed (internal disk path).

**Business rules / scoping**
- Attachments scoped to the order. Default ordering: `created_at` descending.

**Validation.** `orderId` positive integer; pagination params in range.

**LAID lock.** GET allowed regardless of order status.

**Status codes.** Same as D.12.

---

### D.15 POST /api/v1/{slug}/orders/{orderId}/attachments

**Purpose.** Upload a new attachment for the order.

**Scope class.** Standard protected.

**Path params.** `orderId` — positive integer.

**Content-Type.** **`multipart/form-data`** — narrow exception to the JSON-only convention (§3), via the accepted file-endpoints amendment. See section F.7.

**Multipart parts**
- `file` — required; the binary image file (PHOTO).
- `attachment_kind` — required form field; for Chunk 3 the only accepted value is `PHOTO`. (`SIGNATURE` exists in the schema enum but is rejected by this endpoint until its workflow is delivered in a later chunk.)

**Field rules**
- `file` — required; non-empty (`file_size > 0`).
- `file` — MIME type must be one of `image/jpeg`, `image/png`, `image/webp`. Any other type → 400 `UNSUPPORTED_FILE_TYPE`.
- `file` — size must be ≤ **10 MB** (10,485,760 bytes). Larger → 400 `FILE_TOO_LARGE`.
- `attachment_kind` — required; must equal `PHOTO`. Any other value (including `SIGNATURE`) → 400 `VALIDATION_FAILED`.

PDF and other document types are intentionally not accepted in Chunk 3, because PDF is not semantically a `PHOTO` and the locked schema's `attachment_kind` enum does not include a `DOCUMENT` / `PLAN` value. Document support can be added later via a schema change plus a new attachment kind.

**Response DTO — 201 Created**
```json
{
  "data": {
    "order_attachment_id": 7,
    "attachment_kind": "PHOTO",
    "file_name": "lounge-1.jpg",
    "mime_type": "image/jpeg",
    "file_size": 1843200,
    "download_path": "/api/v1/aussiefloors/orders/1/attachments/7/file",
    "created_at": "2026-04-28T11:30:00"
  },
  "message": "Attachment uploaded."
}
```

**Business rules**
- Backend stores the binary on disk (S3 later) and writes a `stored_file` row.
- Backend writes an `order_attachment` row linking the file to the order with `attachment_kind = PHOTO`.
- `order_attachment.stored_file_id` has a UNIQUE constraint (V3) — every attachment row references a distinct file row. The backend must not attempt to reuse an existing file row for a new attachment.
- Adding an attachment does NOT trigger financial summary recomputation. Response does not include `order_financial_summary`.

**Validation**
- `orderId` is a positive integer.
- `file` part is present and non-empty.
- `attachment_kind` is present and equals `PHOTO`. Any other value, including `SIGNATURE` → 400 `VALIDATION_FAILED`.
- File MIME type ∈ `{image/jpeg, image/png, image/webp}` → otherwise 400 `UNSUPPORTED_FILE_TYPE`.
- File size ≤ 10 MB (10,485,760 bytes) → otherwise 400 `FILE_TOO_LARGE`.

**Scoping.** Standard 7 checks + order-belongs-to-store.

**LAID lock.** **Allowed when LAID** (conventions §16: attachments may be added on LAID orders).

**Status codes**
| Code | When |
|------|------|
| 201 | Attachment uploaded |
| 400 | Missing file, invalid `orderId` format, missing or invalid `attachment_kind` (`VALIDATION_FAILED`), unsupported MIME type (`UNSUPPORTED_FILE_TYPE`), file larger than 10 MB (`FILE_TOO_LARGE`) |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in scope, or business slug not found / inactive |
| 500 | Unexpected |

**Error example — unsupported file type**
```json
{
  "error": {
    "code": "UNSUPPORTED_FILE_TYPE",
    "message": "File type is not allowed for attachments."
  }
}
```

**Error example — file too large**
```json
{
  "error": {
    "code": "FILE_TOO_LARGE",
    "message": "File exceeds the 10 MB maximum size."
  }
}
```

---

### D.16 DELETE /api/v1/{slug}/orders/{orderId}/attachments/{attachmentId}

**Purpose.** Remove an attachment from the order. Backs the "Remove attachment" action shown in the Job Plan and Photos workflow.

**Scope class.** Standard protected.

**Path params.** `orderId`, `attachmentId` — positive integers.

**Request DTO.** None.

**Response DTO — 200 OK**
```json
{
  "data": { "deleted_attachment_id": 7 },
  "message": "Attachment removed."
}
```

**Business rules**
- Load `order_attachment` by `attachmentId`.
- Verify the attachment belongs to the order in the path. Otherwise → 404 `ATTACHMENT_NOT_FOUND`.
- Hard-delete the `order_attachment` row, then hard-delete the linked `stored_file` row (1-to-1 via the UNIQUE constraint), then remove the underlying disk file.
- Deletion does NOT affect financial summary.

**Validation.** `orderId`, `attachmentId` are positive integers.

**Scoping.** Standard 7 checks + order-belongs-to-store + attachment-belongs-to-order.

**LAID lock.** Blocked when order is `LAID` → **422 `ORDER_LOCKED`**. Conventions §16 explicitly allows adding attachments on a LAID order, but does not extend that allowance to deletion. Deletion is destructive and weakens record-keeping for completed jobs, so it is locked when the order is LAID.

**Status codes**
| Code | When |
|------|------|
| 200 | Attachment removed |
| 400 | Invalid `orderId` / `attachmentId` format |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in scope, or attachment not on this order |
| 422 | Order is `LAID` (`ORDER_LOCKED`) |
| 500 | Unexpected |

---

### D.17 GET /api/v1/{slug}/orders/{orderId}/attachments/{attachmentId}/file

**Purpose.** Stream the binary file for an attachment so the frontend can display photos / preview plans.

**Scope class.** Standard protected.

**Path params.** `orderId`, `attachmentId` — positive integers.

**Query params.** None.

**Request DTO.** None.

**Response — 200 OK**
- Body: raw binary bytes of the file.
- `Content-Type`: the file's stored MIME type (e.g. `image/jpeg`).
- `Content-Disposition`: `inline; filename="..."` so browsers display photos inline.
- `Content-Length`: file size in bytes.

This is **not** a JSON response — narrow exception to convention §3 via the accepted file-endpoints amendment (see section F.7).

**Business rules**
- Verify attachment belongs to the order, the order belongs to the session's store, etc.
- Stream the file from the configured storage location.

**Validation.** `orderId`, `attachmentId` positive integers.

**Scoping.** Standard 7 checks + order-belongs-to-store + attachment-belongs-to-order.

**LAID lock.** GET allowed regardless of order status.

**Status codes**
| Code | When |
|------|------|
| 200 | File streamed |
| 400 | Invalid `orderId` / `attachmentId` format |
| 401 | No session |
| 403 | Session has no active store / user does not have access |
| 404 | Order not in scope, or attachment not on this order |
| 500 | Unexpected (including missing-on-disk) |

Errors at this endpoint are returned as the standard JSON error wrapper (only the success body is binary).

---

## E. Shared DTOs

### E.1 `order_financial_summary`

Returned in every Chunk 3 response that recomputes financials (D.3 read, D.4–D.11 mutations). Format and rules are locked by conventions §12.

```json
{
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
```

**Field rules**
- All money fields formatted with two decimal places. `gp_percent` is a percentage, also formatted with two decimal places.
- `product_subtotal`, `charge_subtotal` — ex-GST sums of `line_total` from product and charge lines respectively.
- `calculated_total_inc_gst` = `round((product_subtotal + charge_subtotal) × 1.10, 2)`.
- `price_adjustment_inc_gst` — current value persisted on `sales_order.price_adjustment_inc_gst`. May be `null` (no override), positive (uplift), negative (discount), or zero.
- `final_sale_price_inc_gst` = `calculated_total_inc_gst` if adjustment is null; else `round(calculated_total_inc_gst + price_adjustment_inc_gst, 2)`.
- `sale_price_ex_gst` = `round(final_sale_price_inc_gst / 1.10, 2)`.
- `total_cost` = `round(sum(product line_cost) + sum(charge line_cost), 2)` — server-side only, never returned per line.
- `gp` = `round(sale_price_ex_gst − total_cost, 2)`. Negative GP is allowed.
- `gp_percent` = `round((gp / sale_price_ex_gst) × 100, 2)` if `sale_price_ex_gst > 0`, else `null`.
- `gp_warning` = `true` iff `gp_percent` is non-null AND `gp_percent < 15`. Otherwise `false`.

### E.2 `product_line_read`

Used in D.3, D.4, D.5 responses.

```json
{
  "order_product_line_id": 1,
  "product_id": 1,
  "product_code_snapshot": "PLU-001",
  "product_name_snapshot": "Plush Carpet Premium",
  "pricing_unit_snapshot": "LM",
  "price_snapshot": 45.00,
  "quantity_lm": 8.00,
  "quantity_sqm": 29.28,
  "unit_price": 45.00,
  "line_total": 360.00,
  "created_at": "2026-04-14T09:30:00",
  "updated_at": "2026-04-14T09:30:00"
}
```

**Field rules**
- All NOT NULL columns from `order_product_line` are exposed except `cost_snapshot` and `line_cost`, which are never returned (conventions §10).
- `pricing_unit_snapshot` is `LM` or `SQM` — the value at the moment the line was created; never changes if the catalog changes later.
- Money formatted with two decimal places.

### E.3 `charge_line_read`

Used in D.3, D.7, D.8 responses.

```json
{
  "order_charge_line_id": 1,
  "charge_id": 1,
  "charge_code_snapshot": "INST-S",
  "charge_name_snapshot": "Carpet Installation",
  "price_snapshot": 15.00,
  "quantity": 32.00,
  "unit_price": 15.00,
  "line_total": 480.00,
  "created_at": "2026-04-14T09:32:00",
  "updated_at": "2026-04-14T09:32:00"
}
```

**Field rules**
- All NOT NULL columns from `order_charge_line` exposed except `cost_snapshot` and `line_cost`.

### E.4 `attachment_read`

Used in D.14, D.15, D.16 responses (D.16 returns just an id).

```json
{
  "order_attachment_id": 1,
  "attachment_kind": "PHOTO",
  "file_name": "site-photo-lounge.jpg",
  "mime_type": "image/jpeg",
  "file_size": 2048576,
  "download_path": "/api/v1/aussiefloors/orders/1/attachments/1/file",
  "created_at": "2026-04-14T09:45:00"
}
```

`storage_path` is server-internal and is never exposed.

---

## F. Global Chunk 3 Rules

**F.1 Snapshot rules (conventions §10).**
- Backend snapshots catalog values when a line is added: `product_code_snapshot`, `product_name_snapshot`, `pricing_unit_snapshot`, `price_snapshot`, `cost_snapshot` for product lines; `charge_code_snapshot`, `charge_name_snapshot`, `price_snapshot`, `cost_snapshot` for charge lines.
- Snapshots are immutable for the life of the line. Catalog changes never propagate to existing lines.
- `unit_price` is the actual selling price used for `line_total`. It starts at `price_snapshot` and may be overridden by the salesperson (POST or PATCH).

**F.2 Cost is hidden from the frontend (conventions §10).**
- The frontend cannot send `cost`, `cost_snapshot`, `line_cost`, or any derived cost field. Sending any → 400 `VALIDATION_FAILED`.
- The frontend never receives `cost`, `cost_snapshot`, or `line_cost` per line. Only the aggregate `total_cost`, `gp`, and `gp_percent` are exposed inside `order_financial_summary` (necessary for GP display).

**F.3 Quantity conversion (conventions §11).**
- Fixed MVP conversion: `1 LM = 3.66 SQM`.
- For product lines, the frontend sends exactly one of `quantity_lm` or `quantity_sqm`. Backend derives the other.
- `pricing_unit_snapshot` decides which quantity drives calculation:
  - `LM`: `line_total = quantity_lm × unit_price`, `line_cost = quantity_lm × cost_snapshot`
  - `SQM`: `line_total = quantity_sqm × unit_price`, `line_cost = quantity_sqm × cost_snapshot`
- Charge lines use a single `quantity` field; the conversion does not apply.

**F.4 Flooring type lock (conventions §17).**
- A product or charge can be added to an order only if its `flooring_type` matches the order's `flooring_type`. Mismatch → 422 `FLOORING_TYPE_MISMATCH`.
- The order's flooring type is fixed at order creation (Chunk 2) and cannot change.

**F.5 Live financial recomputation (conventions §12).**
- Every mutation that affects financials returns the live `order_financial_summary` in the same response. Affected mutations: D.4, D.5, D.6, D.7, D.8, D.9, D.10, D.11.
- Mutations that do NOT affect financials and therefore do NOT include the summary: D.13 (note add), D.15 (attachment upload), D.16 (attachment delete).
- There is **no separate recalculate endpoint**. D.3 (`GET /lines`) is a read endpoint that computes the summary on demand for the order workspace; it is never used to "trigger" recalculation.
- Empty-order arithmetic:
  - No lines → `product_subtotal = 0.00`, `charge_subtotal = 0.00`, `calculated_total_inc_gst = 0.00`.
  - With no adjustment: `final_sale_price_inc_gst = 0.00`, `sale_price_ex_gst = 0.00`, `total_cost = 0.00`, `gp = 0.00`, `gp_percent = null`, `gp_warning = false`.
  - With an adjustment: standard formulas apply; `gp_percent` may be 100.00 (or `null` if the resulting `sale_price_ex_gst` is `0.00`).

**F.6 Sale price override / reset (conventions §12).**
- The frontend sends only the GST-inclusive number from the Details of Sale field. Backend derives the persisted `price_adjustment_inc_gst` and reapplies it on every future recalculation, including line changes that come after the override.
- Reset Price sets `price_adjustment_inc_gst = NULL`. After reset, `final_sale_price_inc_gst` equals `calculated_total_inc_gst`.

**F.7 Attachment / file-upload rule.**
- The conventions doc now includes a narrow file-endpoints amendment to §3: file upload endpoints may accept `multipart/form-data`, and file download endpoints may return raw binary bytes with the file's stored MIME type. Error responses for these endpoints still use the standard JSON error wrapper. All other endpoints remain JSON-only.
- Chunk 3 has exactly two endpoints under this amendment: D.15 (`multipart/form-data` upload) and D.17 (binary file download). Every other request and response in Chunk 3 is JSON.

**F.8 LAID lock (conventions §16).**

| Endpoint | Allowed when LAID? |
|----------|--------------------|
| D.1 GET available-products | Yes |
| D.2 GET available-charges | Yes |
| D.3 GET lines | Yes |
| D.4 POST product-lines | No → 422 `ORDER_LOCKED` |
| D.5 PATCH product-line | No → 422 `ORDER_LOCKED` |
| D.6 DELETE product-line | No → 422 `ORDER_LOCKED` |
| D.7 POST charge-lines | No → 422 `ORDER_LOCKED` |
| D.8 PATCH charge-line | No → 422 `ORDER_LOCKED` |
| D.9 DELETE charge-line | No → 422 `ORDER_LOCKED` |
| D.10 PUT sale-price | No → 422 `ORDER_LOCKED` |
| D.11 POST sale-price/reset | No → 422 `ORDER_LOCKED` |
| D.12 GET notes | Yes |
| D.13 POST notes | **Yes** (conventions §16 allows adding notes on LAID) |
| D.14 GET attachments | Yes |
| D.15 POST attachments | **Yes** (conventions §16 allows adding attachments on LAID) |
| D.16 DELETE attachments | No → 422 `ORDER_LOCKED` (deletion is destructive; conventions §16 only allows adding attachments on LAID, not removing them) |
| D.17 GET attachment file | Yes |

**F.9 Tenant / store / order / line scoping (conventions §9).**
- Every endpoint enforces the 7 checks from §9.
- Every order-level endpoint additionally enforces `sales_order.business_id = session.business_id` AND `sales_order.store_id = session.store_id` — failure → 404.
- Every line-level endpoint additionally enforces the line belongs to the order in the path — failure → 404.
- Catalog search results are scoped to `session.store_id`. Products and charges from other stores are never returned (no `?store_id=` override; client never controls store).
- Cross-tenant / cross-store / cross-order misses always return 404, never 403.

**F.10 Backend-controlled fields.** Client never sends or controls:
- `business_id`, `store_id`, `user_id`, `order_id` (in body).
- Any snapshot field (`*_snapshot`).
- Any cost field (`cost`, `cost_snapshot`, `line_cost`, `total_cost`).
- Any computed total (`line_total`, `product_subtotal`, `charge_subtotal`, `calculated_total_inc_gst`, `final_sale_price_inc_gst`, `sale_price_ex_gst`, `gp`, `gp_percent`).
- `price_adjustment_inc_gst` directly — only the GST-inclusive sale price the salesperson typed.
- `created_at`, `updated_at`.

---

## G. Changes / Notes from Suggested Design

The suggested set was 15 endpoints. The final set is 17. Two additions:

1. **D.16 `DELETE /attachments/{attachmentId}` — added.** The Job Plan and Photos workflow shows a "Remove attachment" action. Without a delete endpoint, the salesperson can upload but never remove a wrong photo. Blocked when the order is `LAID` — conventions §16 allows adding attachments on a LAID order but not deleting them, since deletion is destructive and weakens record-keeping for completed jobs.

2. **D.17 `GET /attachments/{attachmentId}/file` — added.** The frontend cannot display photos without a way to fetch the binary bytes. This endpoint serves the file on the same auth model as the JSON endpoints, so cross-store / cross-order leaks are impossible. When storage moves to S3 in a later phase, this can be swapped to a redirect or pre-signed URL without a contract change.

No other changes from the suggested set. Endpoint shapes follow conventions §3 / §4 strictly, except for the two file endpoints (see F.7), which run under the now-accepted file-endpoints amendment to §3.

---

## H. Remaining Open Questions

None. The file-endpoints amendment to conventions §3 is accepted (see F.7), so D.15 (`multipart/form-data` upload) and D.17 (binary download) are fully aligned with the locked source of truth.