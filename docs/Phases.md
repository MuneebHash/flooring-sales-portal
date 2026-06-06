# Flooring Sales Portal — MVP Execution Plan

## 1. Current Status

**Completed:**

* Phase 1–4: DB design, migrations V1–V7, seed data, API conventions, API contracts Chunk 1–4, and OpenAPI contract
* Phase 5: real React/TypeScript frontend visual prototype merged to `main`
* Phase 6: frontend/backend handoff doc merged to `main`
* Phase 7: GitHub Actions CI merged to `main`
* Phase 8 backend scaffold foundation merged to `main`
* Phase 8 Chunk 1 backend complete:

  * `POST /api/v1/{slug}/auth/login`
  * `POST /api/v1/{slug}/auth/select-store`
  * `POST /api/v1/{slug}/auth/logout`
  * `GET /api/v1/{slug}/orders`
  * `PATCH /api/v1/{slug}/orders/{orderId}/status`
* Phase 8 → Phase 9 bridge complete:

  * local-dev CORS for Vite frontend origins `http://localhost:5173` and `http://localhost:5174`
* Phase 9 frontend wire Chunk 1 complete:

  * frontend API client foundation (PR #25)
  * real auth wiring (PR #26)
  * real dashboard list + status patch wiring (PR #32)
* Phase 10 Chunk 2 complete:

  * backend order shell, customer, address, and details-of-sale endpoints complete
  * frontend order shell create + workspace read complete (PR #38)
  * frontend customer + address saves complete (PR #39)
  * frontend details-of-sale save complete (PR #40)
* Phase 11 Chunk 3 complete:

  * backend products, charges, lines, notes, attachments, sale price, numeric validation (PRs #41–#50)
  * frontend products & charges wiring (PR #51)
  * frontend sale price + GP + details autosave (PR #52)
  * frontend notes wiring (PR #53)
  * frontend photos/attachments wiring (PR #54)
  * frontend photo preview modal (PR #56)

**Current next phase:**

* Phase 12: Chunk 4 — Invoices + Payments

**Not started:**

* Phase 12 Chunk 4 backend/frontend implementation
* Stripe
* Deployment
* Pilot/hardening

---

## 2. MVP Scope

**Included:**
auth, store selection, dashboard, create/open orders, customer + addresses, details of sale, products, charges, totals/GP, sale price override, notes, photos, invoices, invoice versions, PDF, payments, basic Stripe, container deploy.

**Excluded:**
Operations Portal UI, installer workflows, advanced quote comparison, AI features, refunds/disputes, finance products, surcharges, enterprise monitoring.

---

## 3. Tool Roles

* **ChatGPT:** planning, decision control, prompt drafting, audit, review, merge guidance.
* **Claude chat / Opus:** second-opinion reviewer, spec review, prompt review.
* **Claude Code:** implementation in local repo, backend/frontend changes when explicitly prompted.
* **Codex GitHub bot:** PR review after CI.
* **User:** runs terminal commands, approves decisions, commits, pushes, merges.

**Rules:**

* Claude Code and Codex/other tools should not edit the same files at the same time.
* No commit/merge until diff, tests, CI, and review are clean.
* Prefer small branches with one clear capability.

---

## 4. Phase Status

### Phase 1–4: Planning, contracts, DB — ✅ DONE

Completed:

* Locked product scope and MVP boundaries
* Locked stack and repo structure
* PostgreSQL schema/migrations V1–V7
* Seed data
* API conventions
* API contracts Chunk 1–4
* OpenAPI contract

### Phase 5: Frontend Visual Prototype — ✅ DONE

Originally "Claude Design Visual Lock."

Actual output:

* React + TypeScript + Vite frontend prototype
* Mock/static screens for:

  * login
  * store selection
  * dashboard
  * new order modal
  * order workspace
  * customer
  * products/charges
  * details of sale
  * notes/photos
  * payments
  * invoice

Status:

* Merged to `main`
* Mock data only at the time
* Real API wiring later completed in Phase 9 for the Chunk 1 auth/dashboard flow
* Real order workspace create/read/save wiring completed in Phase 10 for Chunk 2

### Phase 6: Frontend/Backend Handoff Doc — ✅ DONE

Output:

* `docs/Phase-6-Frontend-Backend-Handoff.md`

Captures:

* mock limitations
* hardcoded values
* frontend/backend field mapping risks
* domain rules to preserve
* API integration warnings

### Phase 7: CI Setup — ✅ DONE

Merged in PR #18.

Includes:

* GitHub Actions CI
* frontend build job
* backend Maven verify job
* PostgreSQL 17 service
* locked migration protection for V1–V6

Done when:

* CI runs on PRs
* frontend build and backend verify are checked
* locked migrations are protected

### Phase 8: Backend Foundation + Chunk 1 — ✅ DONE

#### Phase 8.1 Backend Scaffold — ✅ DONE

Merged in PR #19.

Includes:

* `ApiResponse`
* `ErrorResponse`
* `ErrorCode`
* `ApiException` family
* `GlobalExceptionHandler`
* `Business` entity/repository
* `BusinessSlugResolver`
* global Jackson snake_case
* scaffold tests

#### Phase 8.2 Backend Auth — ✅ DONE

Merged in PR #20.

Implemented:

* `POST /api/v1/{slug}/auth/login`
* `POST /api/v1/{slug}/auth/select-store`
* `POST /api/v1/{slug}/auth/logout`

Key rules:

* server-side `HttpSession`
* cookie name `SP_SESSION`
* no JWT
* login uses `salesperson_code + password`
* business resolved from slug
* tenant/store access validated
* session fixation protection via session ID rotation
* logout invalidates session and clears cookie

#### Phase 8.3 Dashboard List — ✅ DONE

Merged in PR #22.

Implemented:

* `GET /api/v1/{slug}/orders`

Key rules:

* standard protected endpoint via `RequestContextGuard`
* strict business/store scoping
* paginated dashboard list
* status/week/search filters
* search includes mobile
* customer/install address nullable
* returns `gp` only
* does not return `gp_percent` or `gp_warning`

#### Phase 8.4 Status Patch — ✅ DONE

Merged in PR #23.

Implemented:

* `PATCH /api/v1/{slug}/orders/{orderId}/status`

Key rules:

* standard protected endpoint via `RequestContextGuard`
* order resource scoped by session business/store
* cross-store/cross-business/nonexistent order returns `ORDER_NOT_FOUND`
* any status can change to any other status
* `LAID` locks conceptually
* changing away from `LAID` unlocks conceptually
* same-status no-op returns 200 without changing `updated_at`
* row lock via `SELECT ... FOR UPDATE`
* real update explicitly sets `updated_at = now()`

#### Phase 8.5 Local-dev CORS Bridge — ✅ DONE

Merged in PR #24.

Purpose:

* Allow Vite frontend to call Spring backend with session cookies during local Phase 9 wiring.

Implemented:

* CORS wired through `SecurityFilterChain`
* scoped to `/api/v1/**`
* explicit local origins:

  * `http://localhost:5173`
  * `http://localhost:5174`
* `allowCredentials(true)`
* origins configured through `app.cors.allowed-origins`
* CSRF/auth/guard behavior unchanged

Tracked deployment follow-up:

* Production must override `app.cors.allowed-origins` with real frontend origin(s) in Phase 14.
* Do not rely on localhost origins in production deployment config.

### Phase 9: Frontend Wire Chunk 1 — ✅ DONE

Goal achieved:
Login → dashboard → status-update run on real backend for the Chunk 1 auth/dashboard flow. Store-select is implemented but not runtime-tested because no multi-store seed user exists yet.

#### Phase 9.1 Frontend API Client Foundation — ✅ DONE

Merged in PR #25.

Implemented:

* `frontend/src/lib/api/` foundation: `config.ts`, `types.ts`, `ApiError.ts`, `client.ts`, `paths.ts`, `index.ts`
* native fetch-based request helper with `credentials: 'include'`
* typed success / collection / error response wrappers
* `ApiError` class normalizing backend error shape
* `apiPath(slug, path)` URL builder
* `VITE_API_BASE_URL` config with local fallback
* Codex-driven hardenings:

  * network failures wrapped as `ApiError` (status 0)
  * response body read failures wrapped as `ApiError`
  * raw `BodyInit` bodies (FormData, Blob, URLSearchParams, etc.) pass through untouched
  * invalid JSON on 2xx surfaces as `ApiError`
  * guarded `ReadableStream` check

#### Phase 9.2 Frontend Auth Wiring — ✅ DONE

Merged in PR #26.

Implemented:

* `frontend/src/lib/tenant.ts` with centralized `DEFAULT_BUSINESS_SLUG = 'aussie-floors-group'`
* `frontend/src/lib/api/authApi.ts` typed login / select-store / logout wrappers
* `auth.tsx` rewired off mock to real backend
* single-store login derives `activeStore` from `active_store_id`
* multi-store path implemented but not runtime-tested — no multi-store seed user yet
* async `selectStore` + `logout`, 409 `STORE_ALREADY_SELECTED` surfaces backend message
* logout clears local React state on both success and failure
* single-store login smoke-tested end-to-end (200 OK)

#### Phase 9.3 Frontend Dashboard Wiring + Status Patch — ✅ DONE

Merged in PR #32.

Implemented:

* `frontend/src/lib/api/ordersApi.ts` with `DashboardOrderRow`, `OrderStatusUpdateResponse`, `fetchDashboardOrders()`, `updateOrderStatus()`
* `frontend/src/lib/api/ordersAdapter.ts` mapping backend rows to frontend display shape
* adapter rules:

  * `customer null → "No customer yet"`, else `"first_name last_name"`
  * `install_address null → "No install address"`, else `"{unit?}/{street_number} {street}, {suburb} {state_code} {postcode}"`
  * `week = "{week_number} / {week_year}"`
  * `gp null → "—"`, else `"$N.NN"`
  * `last_emailed_at null → "Not emailed"`, else `"DD Month YYYY"`
* removed `gp_percent` from `Order` type and dashboard render (Chunk 1 contract returns `gp` only)
* loading / error / empty states on dashboard
* status dropdown wired to real PATCH using `order_id`
* no optimistic update; failed PATCH keeps old status and shows error banner
* same-status PATCH short-circuited client-side
* Codex-driven hardening: pending status PATCHes tracked as a `Set<number>`, each row independently disabled while its own PATCH is in flight (prevents stale response overwrite races)
* end-to-end smoke test passed: login, dashboard load, status persist on refresh, failed PATCH does not fake-update UI, client-side search/filter works

Locked product decisions captured during Phase 9:

* Dashboard is store-level for MVP — anyone with access to a store sees that store's orders.
* No role-based visibility. Access-based only.
* Sales Portal vs Store Portal dashboard split is a future concern, not tracked as an issue.

Tracked follow-ups created as GitHub issues:

* #27 Replace hardcoded business slug with dynamic business selection
* #28 Add multi-store demo user and test store selection
* #29 Add CSRF protection before production
* #30 Configure production CORS origins
* #31 Move shared auth types out of `auth.tsx`

Known deferrals carried forward (not tracked as issues, accepted for MVP):

* Dashboard fetches page 1 at `page_size=100` and filters/searches client-side. Server-side search + pagination deferred until order volume requires it.

### Phase 10: Chunk 2 — Order Shell + Customer + Addresses + Details — ✅ DONE

Goal achieved:
A salesperson can create an order shell, open the real order workspace, fill customer/address/details data, save it, and reopen/refresh the workspace with data persisted from the backend.

Backend endpoints completed:

* `POST /api/v1/{slug}/orders`
* `GET /api/v1/{slug}/orders/{orderId}`
* `PUT /api/v1/{slug}/orders/{orderId}/customer`
* `PUT /api/v1/{slug}/orders/{orderId}/addresses/installation`
* `PUT /api/v1/{slug}/orders/{orderId}/addresses/billing`
* `POST /api/v1/{slug}/orders/{orderId}/addresses/billing/copy-from-installation`
* `PUT /api/v1/{slug}/orders/{orderId}/details-of-sale`

Frontend wiring completed:

* New Order modal → real `POST /orders`
* `/orders/{orderId}` workspace → real `GET /orders/{orderId}`
* Customer tab → real customer save
* Installation address → real save
* Billing address → real save
* Copy installation to billing → real endpoint
* Details of Sale → real save
* LAID lock blocks edits on protected customer/address/details mutation flows

#### Phase 10D: Backend Chunk 2 — ✅ DONE

Implemented:

* `SalesOrder` JPA entity introduced for Chunk 2
* order shell creation
* workspace read
* customer upsert
* installation address upsert
* billing address upsert
* billing copy-from-installation
* details-of-sale save
* LAID lock protection on protected mutation endpoints
* standard protected checks and order scoping

Key rules:

* `order_id` is the internal numeric route/API identifier
* `order_number` is display-only
* customer and addresses are nullable on workspace read until saved
* customer/address/details PUT endpoints use full-replace semantics
* omitted optional fields are written as `null`
* `GET /orders/{orderId}` is allowed even when LAID
* protected mutation endpoints return `ORDER_LOCKED` when order is LAID
* Chunk 2 mutation responses do not include financial summary blocks

#### Phase 10E.1: Frontend Order Shell Create + Workspace Read — ✅ DONE

Merged in PR #38.

Implemented:

* `frontend/src/lib/api/orderWorkspaceApi.ts`
* `createOrder(flooringType)`
* `fetchOrderWorkspace(orderId)`
* full Chunk 2 workspace type surface for order header, customer, addresses, details, persisted financial readback
* New Order modal calls real backend `POST /orders`
* successful create navigates to `/orders/{order_id}`
* workspace reads real backend `GET /orders/{orderId}`
* loading/error/not-found handling for workspace read
* nullable customer/address/details data threaded into workspace state
* `order_status` and `locked` threaded into workspace shell
* mock workspace read path removed for existing orders

Smoke/build:

* frontend build passed
* create/open workspace flow smoke-tested

#### Phase 10E.2: Frontend Customer + Address Save Wiring — ✅ DONE

Merged in PR #39.

Implemented:

* `saveCustomer(orderId, body)`
* `saveInstallationAddress(orderId, body)`
* `saveBillingAddress(orderId, body)`
* `copyBillingFromInstallation(orderId)`
* Customer tab real single Save button
* sequential save flow:

  * unchecked billing path: customer → installation → billing
  * checked same-as-installation path: customer → installation → copy billing from installation
* stop-on-first-failure behavior
* no rollback of already-succeeded calls
* partial-failure reporting showing saved / failed / did-not-run sections
* parent workspace state updated section-by-section from server-confirmed responses
* optional blank strings normalized to `null`
* strict frontend email validation retained
* strict AU mobile UX validation retained:

  * `04XXXXXXXX`
  * `+614XXXXXXXX`
* same-as-installation checkbox defaults checked when:

  * no saved billing exists, or
  * saved billing equals installation
* same-as-installation defaults unchecked when saved billing differs from installation
* copy endpoint runs only on Save, not checkbox toggle
* locked/LAID disables customer/address inputs, checkbox, and Save

Smoke/build:

* frontend build passed
* manual smoke passed:

  * checked copy path + reload
  * unchecked distinct billing path + reload
  * same-as-installation equality default
  * invalid email blocked
  * strict mobile validation
  * blank optionals save without backend 400
  * LAID disabled controls
  * dashboard/open-order regression check

Review:

* Codex P2 partial-save parent-state issue fixed
* Codex strict-mobile comment consciously deferred as intended MVP UX rule
* final Codex review clean

#### Phase 10E.3: Frontend Details of Sale Save Wiring — ✅ DONE

Merged in PR #40.

Implemented:

* `saveDetailsOfSale(orderId, body)`
* Details of Sale tab real Save button
* all five details fields converted to controlled state:

  * `supply_only`
  * `plan_numbers`
  * `proposed_lay_date`
  * `lay_date_status`
  * `details_of_sale`
* complete full-replace body sent on save
* optional text fields trim and blank → `null`
* local pair-rule validation:

  * both lay date and lay date status blank is valid
  * both set is valid
  * one set without the other is invalid
* backend `ORDER_LOCKED` / validation errors surfaced
* parent workspace state updated from server-confirmed `details_of_sale_fields` and `updated_at`
* quick-add description behavior preserved
* locked/LAID disables details textarea, lay date input, status select, plan number input, supply-only checkbox, quick-add buttons, and Save

Smoke/build:

* frontend build passed
* manual smoke passed:

  * lay date blank + status blank valid
  * lay date set + status blank blocked
  * status set + lay date blank blocked
  * lay date + status set saved successfully
  * hard refresh persisted saved details
  * tab switch preserved saved details
  * LAID order disabled controls

Review:

* Codex lay-date "required" comment resolved by clarifying intended pair-rule requirement
* final Codex review clean

Done when:

* salesperson can create an order shell
* fill customer/address/details
* save and reopen the order workspace
* all Chunk 2 frontend flows run on real backend endpoints

Status:

* Done and merged to `main`

### Phase 11: Chunk 3 — Products, Charges, Lines, Notes, Photos — ✅ DONE

Goal achieved:
A salesperson can search products/charges from catalog, add/edit/delete product and charge lines, see financial summary and GP, override/reset sale price, add notes, upload/preview/delete photos — all on real backend endpoints. Full priced order with products, charges, totals, GP, notes, and photos works end-to-end.

#### Phase 11 Backend + Contract Prep — ✅ DONE (PRs #41–#50)

##### PR #41: Cost Snapshot on Line DTOs — ✅ DONE

* Already-added line read DTOs return `cost_snapshot` for read-only salesperson visibility
* Catalog search stays cost-free
* Client cannot send `cost`/`cost_snapshot`/`line_cost`

##### PR #42: Per-Product LM/SQM Factor — ✅ DONE

* V8 migration added `sqm_per_lm` column to `store_product`
* 3.66 default remains for most carpets
* 4.00 supported for 4m roll carpets
* Conversion must use snapshotted factor per line, not hardcoded 3.66

##### PR #43: Catalog Search — ✅ DONE

* `GET /orders/{orderId}/available-products` — search available products
* `GET /orders/{orderId}/available-charges` — search available charges

##### PR #44: Product Lines + Financial Summary — ✅ DONE

* `GET /orders/{orderId}/lines` — order lines + financial summary
* `POST /orders/{orderId}/product-lines` — add product line
* `PATCH /orders/{orderId}/product-lines/{lineId}` — edit product line
* `DELETE /orders/{orderId}/product-lines/{lineId}` — delete product line
* Financial summary foundation: recomputation on mutations, header field persistence

##### PR #45: Negative Sale Price Rule — ✅ DONE

* V9 migration dropped `sale_price_gte_zero` CHECK constraint
* Product line PATCH/DELETE can persist and return negative `sale_price_ex_gst`
* Negative sale price blocking lives at invoice creation (Phase 12), not line CRUD

##### PR #46: Charge Lines — ✅ DONE

* `POST /orders/{orderId}/charge-lines` — add charge line
* `PATCH /orders/{orderId}/charge-lines/{lineId}` — edit charge line
* `DELETE /orders/{orderId}/charge-lines/{lineId}` — delete charge line
* `OrderChargeLineWriteRepository` write-split pattern
* Snapshot capture for charge code/name/price/cost
* Financial summary recomputation on mutations
* Strict `additionalProperties: false` unknown-field rejection
* All `*_snapshot` fields rejected from client input

##### PR #47: Notes — ✅ DONE

* `GET /orders/{orderId}/notes` — list notes (DESC ordering)
* `POST /orders/{orderId}/notes` — add note
* Append-only in MVP, no edit/delete
* Notes allowed when LAID (no ORDER_LOCKED)
* No financial summary recomputation on note add
* POST response shape: `data.note` (nested)
* No `created_by_user_id` column (future Store Portal scope)
* Strict unknown-field rejection
* Ordering test inserts 3 rows to genuinely verify DESC sort

##### PR #48: Attachments/Photos — ✅ DONE

* `GET /orders/{orderId}/attachments` — list attachments
* `POST /orders/{orderId}/attachments` — upload photo (multipart)
* `DELETE /orders/{orderId}/attachments/{attachmentId}` — delete attachment
* `GET /orders/{orderId}/attachments/{attachmentId}/file` — download file
* Local disk storage (S3 later)
* PHOTO only in Chunk 3; SIGNATURE rejected
* Allowed MIME: `image/jpeg`, `image/png`, `image/webp`
* Max 10 MB
* `storage_path` never exposed
* Upload/list/download allowed when LAID
* Delete blocked when LAID
* Unicode download filenames encoded safely with Spring ContentDisposition
* Delete disk cleanup after successful DB commit
* Upload disk cleanup on transaction rollback
* Upload response shape: `data.attachment` (nested, intentional)

##### PR #49: Sale Price Override/Reset — ✅ DONE

* `PUT /orders/{orderId}/sale-price` — override sale price
* `POST /orders/{orderId}/sale-price/reset` — reset to calculated
* `final_sale_price_inc_gst` is GST-inclusive input
* Backend derives and stores `price_adjustment_inc_gst`
* Zero adjustment stored as `0.00`, not NULL
* Reset clears `price_adjustment_inc_gst` to NULL
* Financial summary recomputed and persisted
* LAID blocked with ORDER_LOCKED
* Forbidden/unknown fields rejected
* Non-finite values (1e309) return 400 VALIDATION_FAILED, not 500
* >2dp input rounded HALF_UP to 2dp (locked decision, consistent with sibling line endpoints)

##### PR #50: Numeric Validation Hardening — ✅ DONE

* Non-finite guard mirrored into `OrderProductLineService` and `OrderChargeLineService`
* Covers `unit_price`, `quantity`, `quantity_lm`, `quantity_sqm`
* Targeted line tests and full backend test suite passed
* Codex review clean

#### Phase 11 Frontend — ✅ DONE (PRs #51–#54, #56)

##### B7: Products & Charges Wiring — ✅ DONE

Merged in PR #51.

Branch: `feature/frontend-products-charges-wiring`

Implemented:

* `orderLinesApi.ts` — catalog search, GET lines, product/charge line CRUD
* Catalog search modals wired to real backend
* GET `/lines` on Products & Charges tab
* Product line add/edit/delete on real endpoints
* Charge line add/edit/delete on real endpoints
* Backend financial summary lifting into OrderWorkspace
* Header Sale total from `order_financial_summary.final_sale_price_inc_gst`
* Product-specific `sqm_per_lm` / `sqm_per_lm_snapshot` instead of hardcoded 3.66
* LAID lock disables product/charge mutations
* Product subtotal and charge subtotal section lines remain

Important B7 fixes:

* Header Sale total loads before Products tab is opened
* Invalid add-panel unit prices blocked
* Mutation summaries still lift after tab unmount
* Stale seed fetch cannot overwrite mutation summary

##### B8: Sale Price + GP + Details Autosave — ✅ DONE

Merged in PR #52.

Branch: `feature/frontend-sale-price-gp-wiring`

Implemented:

* Sale price override/reset API functions in `orderLinesApi`
* Sale price controls in Details of Sale
* Final sale price input is GST-inclusive
* Reset Price calls reset endpoint with no body
* Reset Price remains available unless order is locked or sale-price request is in flight
* GP/GP%/financial info hidden behind info button
* GP info traffic-light style:

  * green if GP% > 15
  * amber/yellow if GP% 10–15
  * red if GP% < 10, including negative
* GP warning wording: "Warning: This sales price is below approved sales persons costings."
* Manager approval required appears only when `gp_warning === true`
* Create Invoice button is visual-only (no invoice API wiring)
* Sale price success message removed completely
* Required deposit text neutralised: "Deposit requirements will appear once payments are wired."
* Details of Sale manual Save panel removed
* Details fields autosave on blur/change (no keystroke, no debounce)

Important B8 fixes:

* Sale-price in-flight lock hoisted to WorkspaceShell (survives tab remount)
* Details autosave single-flight lock/queue hoisted to WorkspaceShell
* Details draft state hoisted to WorkspaceShell (survives Details tab unmount/remount)
* WorkspaceShell keyed by `orderId` — draft/queue/autosave refs reset per order
* Read vs mutation financial summary handling:

  * GET `/lines` treated as read
  * Reads guarded if mutation activity happened after read began
  * Product/charge mutations and sale-price override/reset treated as mutations
  * Mutation summaries apply and bump mutation version

Concurrent mutation note:

* True cross-surface mutation commit-order correctness cannot be fully solved in frontend without backend financial-summary versioning (tracked in issue #55)

##### B9: Notes Wiring — ✅ DONE

Merged in PR #53.

Branch: `feature/frontend-notes-wiring`

Implemented:

* `orderNotesApi.ts` — GET notes, POST note
* Notes loading/error/empty states
* Add note from server-confirmed `data.note`
* Notes remain allowed when LAID
* No `created_by` UI
* No financial summary interaction
* Photos left separate for B10

Important B9 Codex fix:

* After prepending a new note, cap visible list to backend page size 20 so UI does not show 21 notes when backend page 1 would show 20

##### B10: Photos/Attachments Wiring — ✅ DONE

Merged in PR #54.

Branch: `feature/frontend-attachments-wiring`

Implemented:

* `orderAttachmentsApi.ts` — list, upload, delete, download
* List photo attachments from real endpoint
* Single-file photo upload via multipart FormData
* Authenticated blob preview thumbnails (fetch → object URL, not direct `<img src>`)
* Delete photo attachment
* LAID split:

  * list allowed
  * upload allowed
  * preview/download allowed
  * delete gated/blocked
* Object URL cleanup for thumbnail previews (unmount, reload, delete, cap-drop)
* Photos independent from notes

Important B10 Codex fixes:

* Upload disabled while initial photo list is loading
* Stale GET guarded so upload cannot be overwritten by older fetch
* Delete refetches page 1 to backfill truncated pages
* Stale `photosError` cleared on successful upload only

##### B11: Photo Preview Modal — ✅ DONE

Merged in PR #56.

Branch: `feature/frontend-photo-preview-polish`

Implemented:

* Large in-app photo preview modal/lightbox
* Thumbnail click opens modal
* Modal uses existing `Modal` component
* X close, backdrop close, Escape close
* Keyboard-openable thumbnail button
* Modal uses separate authenticated blob object URL (not thumbnail reuse)
* Object URL cleanup on modal close/unmount
* Large preview size fixed after manual smoke showed it was initially too small
* Notes unchanged, upload/list/delete unchanged
* LAID preview allowed, delete still gated when locked

Important B11 Codex fix:

* If currently previewed photo is deleted, successful delete clears `selectedPhotoId` immediately so modal cannot stay open if follow-up attachments refetch fails

#### Phase 11 Smoke Test

Manual smoke test passed before B11 docs:

* Products/charges: OK
* Sale price/GP: OK
* Notes: OK
* Photos upload/preview/delete: OK
* LAID behavior: upload/preview allowed, delete hidden/disabled on LAID, delete worked after unlocking
* DevTools: current requests showed 200/201
* B11 large preview visually worked after size fix

#### Phase 11 Locked Decisions

* Negative `sale_price_ex_gst` persists through all mutation layers; never clamped/floored/rejected; invoice creation (Phase 12) is where blocking lives
* `gp_percent` out-of-range: persist NULL in DB where required; API response may still expose the calculated value when available
* Unknown-field rejection (`additionalProperties: false`): enforced on charge/notes/attachments/sale-price endpoints; product-line backfill planned as standalone cleanup
* POST mutation responses use nested `data.<entity>` shape matching OpenAPI
* Ordering tests insert ≥3 rows to genuinely exercise sort behavior
* `attachment` upload response nested as `data.attachment` (intentional, not a bug)
* >2dp numeric input rounded HALF_UP (consistent across all sibling endpoints)
* Camera capture deferred (native file upload sufficient for MVP including iPad)
* HEIC/HEIF conversion deferred (JPG/PNG/WEBP only for MVP)
* Quick-add descriptions are hardcoded frontend array; store-customisable versions require backend/Operations Portal later

Status:

* Done and merged to `main`

### Phase 12: Chunk 4 — Invoices + Payments — ⏭ NEXT

Backend endpoints:

* create invoice v1
* rewrite invoice
* invoice history
* invoice detail
* invoice PDF download
* payment list
* record payment
* payment-driven invoice versioning

Frontend wiring:

* create invoice
* precondition errors
* rewrite invoice
* invoice version history
* PDF download
* record payment form
* balance due display

Known frontend cleanup:

* Replace hardcoded "Aussie Floors Group / Sydney CBD" invoice header with API-driven data

Done when:

* salesperson can create/rewrite invoices, record payments, view invoice versions, and download PDFs

### Phase 13: Stripe

If slipping:

* cut to post-MVP
* or keep only simplest test-mode payment flow

Steps:

* write `docs/API-Contracts-Stripe-Addendum.md` first
* implement `POST /orders/{orderId}/payments/stripe-intent`
* implement `POST /stripe/webhook`
* webhook idempotency
* reuse payment-driven invoice version logic

Done when:

* Stripe test card creates payment + invoice version
* webhook retry does not duplicate payment/version

### Phase 14: Deployment

Target:

* single VM deployment is acceptable
* Docker Compose:

  * backend
  * frontend/nginx
  * PostgreSQL
* HTTPS via Let's Encrypt
* environment variables for secrets
* persistent DB volume
* basic DB backup

Deployment config requirements:

* production must set real `app.cors.allowed-origins` (issue #30)
* production must not rely on localhost CORS defaults
* cookie `secure=true` should be enabled for HTTPS production
* CSRF strategy must be decided before production if browser session-cookie auth remains (issue #29)
* secrets must not be committed

Done when:

* app is live on public URL
* full sale flow works
* DB survives restart

### Phase 15: Pilot / Hardening

Tasks:

* manually load initial product catalog/prices
* create real users
* run full end-to-end sale with real data
* fix bugs
* record feedback
* harden obvious gaps

Done when:

* one store can complete a full real sale without data loss

---

## 5. Tracked Deferrals / Follow-ups

These are known and accepted. They do not block the next phase.

### GitHub Issues

* #27 Replace hardcoded business slug with dynamic business selection
* #28 Add multi-store demo user and test store selection
* #29 Add CSRF protection before production
* #30 Configure production CORS origins
* #31 Move shared auth types out of `auth.tsx`
* #55 Add backend financial summary versioning for concurrent order mutations

### Security / Production Hardening

* CSRF token strategy for browser session-cookie auth (issue #29)

  * currently deferred
  * required before production deployment if cross-site/browser session use remains
* Cookie `secure=true` per environment

  * required for HTTPS production
* CORS production origin config (issue #30)

  * production must override `app.cors.allowed-origins`
  * real frontend origin(s) must be configured during Phase 14
* Rate limiting for login
* Account lockout / brute-force mitigation
* Audit logging for important business actions

### Backend / Performance

* Dashboard `created_at DESC` index if dashboard performance needs it
* RequestContextGuard currently does multiple DB reads per standard-protected request

  * acceptable for MVP
  * can be optimized later if needed
* Dashboard count/list are not wrapped in read-only transaction

  * acceptable for MVP scale
* Planned standalone cleanup: backfill strict unknown-field rejection on product-line endpoints for consistency with charge/notes/attachments/sale-price endpoints

  * not blocking Phase 12; can be done as a standalone cleanup

### Frontend

* Dashboard fetches page 1 at `page_size=100` and filters client-side

  * acceptable while seed/early-pilot stores have few orders
  * server-side search + pagination to be added when order volume requires it
* Move shared auth types (`User`, `Store`) out of `auth.tsx` to break the type-only import cycle with `authApi.ts` (issue #31)
* Replace `DEFAULT_BUSINESS_SLUG` constant with dynamic business resolution (issue #27)
* Camera capture support (deferred from Phase 11)
* HEIC/HEIF upload conversion (deferred from Phase 11; JPG/PNG/WEBP only for MVP)
* Store-customisable quick-add descriptions (requires backend/Operations Portal)

### Data / Business Rules

* Strict AU mobile validation is currently frontend UX only.

  * Backend Chunk 2 contract accepts non-blank mobile values.
  * The stricter frontend rule is intentional for MVP because the form is the only customer data entry path.
  * Revisit if legacy/imported customer data is introduced later.
* Details of Sale lay date rule is currently pair-based:

  * both `proposed_lay_date` and `lay_date_status` blank is valid
  * both set is valid
  * one set without the other is invalid
  * backend and frontend both protect the pair rule
* Concurrent financial summary mutation ordering (issue #55):

  * theoretical edge when two financial-changing mutations overlap across different surfaces
  * frontend cannot reliably determine backend commit order from response order
  * correct fix: backend summary versioning (etag/version on order_financial_summary responses)
  * not blocking MVP

### Test / Build Hygiene

* Mockito Java-agent warning is non-blocking
* Can clean up later if it becomes noisy or CI-sensitive

---

## 6. AI Task Division Rules

### Backend

One small capability per branch.

Prefer one endpoint per branch unless splitting causes heavy duplicate setup.

Examples:

* auth endpoints were grouped because they shared session/auth setup
* dashboard list and status patch were separate branches
* CORS bridge was separate because it touched `SecurityConfig`
* customer/address frontend save was grouped because it is one Customer tab workflow
* details-of-sale frontend save was separate because it is a separate workspace tab

### Frontend

One screen group or flow per branch.

Phase 9 used:

* `feature/frontend-api-client`
* `feature/frontend-wire-auth`
* `feature/frontend-wire-dashboard`