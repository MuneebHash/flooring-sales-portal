# Phase 6 — Frontend/Backend Handoff

## 1. Phase 5 Status

- Phase 5 frontend visual prototype is complete and merged into `main`.
- It is a static/mock frontend prototype.
- No backend/API integration exists yet.
- No persistence/localStorage/sessionStorage.
- React state only.
- Polish/beautification is deferred until later.

Completed screens:

- Login
- Store selection
- Dashboard
- New order modal
- Order workspace shell
- Customer tab with local validation
- Products & Charges tab
- Details of Sale tab
- Notes & Photos tab
- Payments tab
- Invoice tab

## 2. Bootstrap / Build Instructions

From repo root:

```
cd frontend
npm install
npm run build
```

Note:

- `npm install` (or `npm ci`) must be run before `npm run build` in a fresh environment.
- The previous Codex build failure was due to missing dependencies, not broken frontend code.

## 3. Known Mock-Only Behaviours

- Customer Save validates locally only; it does not update the dashboard or backend.
- Product/charge add/edit/delete is local prototype state only.
- Product/charge totals are prototype calculations only.
- Payment form is visual only and does not save.
- Invoice actions are visual only.
- Signature area is visual only.
- Notes can be added locally but are not persisted.
- Photos/upload are visual placeholders.
- Refresh/navigation may reset local state.

## 4. Hardcoded Values To Replace With API Data

- Invoice header business/store identity is hardcoded and must become API-driven.
- Payments tab fixed deposit text `"Required deposit: 40% of invoice total = $400.00"` is prototype-only and must be removed or replaced with backend/store-configured logic.
- Order workspace header sale total currently uses mock `pricing_summary` and must come from backend financial summary later.
- Mock invoice terms are placeholder/sample content only.
- Mock product catalogue/search is local only and must be replaced by backend product search.

## 5. Frontend Mock Field Map Risks

Known mapping differences between frontend mock shapes and API contracts:

- `mockOrders.status` → API `order_status`
- `mockOrders.gp` is formatted string `"$408.00"` → API `gp` is numeric DECIMAL
- `mockOrders.gp_percent` is number → API `gp_percent` numeric
- `mockOrders.last_emailed = "Not emailed"` → API `last_emailed_at` nullable timestamp
- `pricing_summary.sale_total_inc_gst` → API `final_sale_price_inc_gst`
- `pricing_summary.sale_total_ex_gst` → API `sale_price_ex_gst`
- `invoice_summary.invoice_total` → API invoice `sale_price_inc_gst`
- `invoice_summary.current_version` → should be derived from invoice version metadata/current version
- product line `product_code` / `product_name` → API `product_code_snapshot` / `product_name_snapshot`
- `pricing_unit` → API `pricing_unit_snapshot`

A frontend adapter layer should handle these mappings during API wiring.

## 6. Mock Fixtures That Do Not Match Backend Seed

- LC1 mock login aligns with seeded-style user.
- MS1 / Morgan Shaw is prototype-only and does not currently exist in seed data.
- Decide during backend auth integration whether to:
  1. add MS1 to seed data, or
  2. change frontend mock/demo flow to use seeded users only.
- Mock order IDs 1–4 are frontend demo fixtures and must be aligned with backend seeded orders or replaced by API data.

## 7. Domain Rules To Preserve

- Order number format:
  `{store_code}.{salesperson_code}.{order_sequence_number_padded_5}`
  Example: `SYD-CBD.LC1.00001`.
- All order number examples must use the post-V6 format, e.g. `SYD-CBD.LC1.00001`.
- Do not reference pre-V6 salesperson codes like `LC01`.
- `salesperson_code` format: two uppercase letters + one digit, e.g. `LC1`.
- Flooring types: `SOFT`, `HARD`.
- LM/SQM conversion: `1 LM = 3.66 SQM`.
- Payment is allowed only after an invoice exists in the real backend flow.
- Invoice snapshots are versioned.
- Manual invoice versions: `due_date = proposed_lay_date - 2 days`.
- Payment-driven invoice versions: carry forward the existing official sale snapshot and update payment/balance fields only.
- LAID orders are locked from edits in the backend.
- Status changes happen from the dashboard dropdown only.
- GP warning threshold is `GP% < 15%`.

## 8. Migration / Documentation Reconciliation

- Migrations V1–V6 exist and should be treated as locked.
- Some older docs/contracts may reference V1–V5 only.
- V6 is real and important because it syncs salesperson codes/order numbers and adds format checks.
- `CLAUDE.md` already says V1–V6 are locked.
- Action for Phase 7 CI/documentation cleanup: update phase planning docs where applicable, `docs/API-Contracts-Chunk-1.md`, `docs/API-Contracts-Chunk-2.md`, `docs/API-Contracts-Chunk-3.md`, `docs/API-Contracts-Chunk-4.md`, and `docs/openapi.yaml` references from V1–V5 to V1–V6 where applicable.
- This is documentation-only. No migration changes.

## 9. Phase 6 Decisions To Lock Before Backend

1. Whether MS1 multi-store mock user becomes seeded backend data or stays frontend-only.
2. Whether deposit percentage is part of store configuration, and whether it belongs in MVP backend now or later.
3. Exact DTO adapter field mapping for dashboard/order workspace.
4. Whether the backend exposes `current_version`, or the frontend selects the current invoice snapshot using `max(version_number)`.
5. Whether frontend mock data should be aligned to one canonical backend seed order before integration.

## 10. Recommended Next Step

After this document is committed:

- Start backend implementation planning.
- Do not polish frontend further unless a bug blocks backend integration.
- Phase 7 CI should protect frontend build and backend tests.
- Phase 8+ should begin backend chunks using the locked API contracts and this handoff doc.

## 11. Scope

This is a handoff document, not a redesign.
It does not change locked API contracts, OpenAPI, migrations, database design, or business rules.
Any disagreement with locked docs must be raised separately, not resolved inside this handoff document.
