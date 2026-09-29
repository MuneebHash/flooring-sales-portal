# Sales Portal MVP — API Contracts · Phase 16B (Quotation) (Lock-Ready)

**Status:** Phase 16B quotation contract. Additive to the locked Phase 12 / Chunk 4 invoices + payments contract (`docs/API-Contracts-Chunk-4.md`) and the Phase 13 acceptance/signature/email contract (`docs/API-Contracts-Phase13-Acceptance-Signature-Email.md`). It was written docs/contracts-only, before any quotation code, as the rails for the 16C–16F quotation build, and has since been amended in place (16D-A price decoupling, 16D-B PR2A retained rows, 16D-C PDF wording) and reconciled with the implemented 16E state in the post-16E docs pass.

**Implementation status (post-16E reconciliation, `main` @ `33c4e22`):**
- **Implemented (16C–16E):** draft workspace/PUT, draft preview PDF, `send-email` issue/resend, `cancel`, stored issued PDF (`GET …/quote/pdf?type=issued`), the salesperson Customer Quote view, and the public read-only surface (`GET /public/quotes/{token}`, `POST …/viewed`, `GET …/pdf`) behind the slugless page `/q/{token}`. Migrations `V16`–`V18`.
- **Planned 16F — not implemented:** public `accept` (remote signing), the signed quote PDF, the store acceptance notification, the Accepted Quote view, and `create-invoice` (Path A). Their definitions below (and in `openapi.yaml`) are contract, **not** evidence of working handlers.
- **Dormant (outside the approved delivery roadmap):** `send-sms` and the other SMS artefacts — see §9. Delivery is **email only**.

**Source of truth priority:**
1. `docs/API-Conventions.md`
2. Locked Chunk 1–3 contracts
3. Locked Chunk 4 contract (Phase 12 invoices + payments)
4. Phase 13 contract (invoice acceptance + signature + email)
5. **This Phase 16B contract (additive — quotation)**
6. Locked DB schema (V1–V18: `V16` quote tables below, `V17` issue-time customer snapshots, `V18` reserved slug `q`)

**Order number format (locked):** `{store_code}.{salesperson_code}.{order_sequence_number_padded_5}` — e.g. `SYD-CBD.LC1.00042`. Backend-generated; used in quote PDF / signature file names.

**File-binary exception (conventions §3):** quote PDF download endpoints return raw binary; the public **accept** endpoint (planned 16F) accepts a `multipart/form-data` signature upload and the public/protected PDF endpoints stream bytes — same exception Phase 13 used for invoice acceptance.

**Auth-model warning (locked):** the protected quote endpoints are Standard-protected (HttpSession, all conventions §9 checks). The **public** quote endpoints are a **new, separate unauthenticated surface** authenticated **only by the secret token**. They MUST NOT reuse, populate, or weaken the session/`RequestContextGuard` model, and MUST NOT expose any tenant data beyond the single quote behind the token.

---

## 1. Purpose

Phase 16A finished the invoice presentation (Aire Compact PDF). Phase 16B specifies the **quotation feature**: a salesperson builds a customer-facing quote on an order, sends it by email as a secure link, the customer opens it on their phone, signs it remotely, the store is notified, and the salesperson converts the accepted quote into an invoice without the customer re-signing. (16C–16E built everything up to the read-only customer view; remote signing, notification and conversion are 16F.)

A quote is **not** an invoice and must not be hacked into the invoice flow. It has its own tables, its own statuses, its own versioning, and its own public signing surface. It **reuses** the Aire Compact document style (title `QUOTATION`, not `TAX INVOICE`) and the existing `stored_file` / `FileStorageService` / email mechanisms.

Concretely, Phase 16B adds:
- An **editable quote draft** per order (itemised or non-itemised customer-facing lines, distinct from the order's product/charge lines).
- **Issued quote versions** — immutable snapshots created when the quote is sent, each with a stored issued PDF and a secret public link.
- **Remote customer acceptance** *(planned 16F)* — the customer signs on their phone via the tokenised link; a signed quote PDF is stored; the store is notified.
- **Invoice conversion (Path A)** *(planned 16F)* — create an invoice from the accepted quote snapshot, inheriting the signature (no re-sign).
- A **public, token-only** read/sign surface (read-only view + viewed tracking implemented in 16E-C; signing is 16F).

Phase 16B is the **planning + contract lock**. 16C builds the backend, 16D the salesperson Quote tab, 16E delivery, 16F remote acceptance + conversion — **16C–16E are implemented; 16F is planned**. **16E and 16F are not merged** — token-based remote signing is its own security + legal-state surface.

---

## 2. MVP scope (included)

- **One quote per order**, versioned and append-only at the issued layer (mirrors the invoice append-only model).
- **Editable draft** (`quote_draft`, one row per order) with customer-facing **quote lines** (`quote_draft_line`). Itemised mode copies product/charge lines into editable quote lines; the salesperson may edit quote-line description / quantity / unit price / line total and add **adjustment** lines (±). Non-itemised mode shows the quote total + details-of-sale text, no line breakdown and no filler "Quoted Works" section. Itemised draft lines are **retained (dormant)** while the draft is non-itemised — see §6.1 (amended Phase 16D-B PR2A).
- **Quote lines never mutate the order's product/charge lines.** The product/charge lines remain the operational/costing record; quote lines are customer-facing presentation only.
- **Money model (locked):** in **itemised** mode the quote total = the sum of quote lines; in **non-itemised** mode the quote total = `final_total_inc_gst` carried on the draft header, with **no synthetic line** (amended Phase 16D-B PR2A — see §6.1). The quote draft is **independent of the order sale price** — saving a draft does **not** change the order's sale-price override or header financials (see §6). The **accepted quote snapshot is the legal billing number** (see §6).
- **Total rules (itemised mode only):** reducing the final total directly auto-inserts a negative **adjustment** line to balance; the final total may not be set **above** the line sum (must raise a line or add a positive line, else 422); adjustments are customer-facing. A non-itemised `final_total_inc_gst` is **never** validated against lines (§6.1).
- **GP / below cost:** the draft shows GP warnings while editing (like Details of Sale). **Below cost is blocked at save, send, and accept (16F)** (cannot even save a below-cost draft).
- **Issued version on send:** sending generates and **stores an immutable issued quote PDF**, snapshots the lines + per-flooring-type terms (and, since `V17`, the customer-facing "Quotation To" name + billing lines), mints a secret token, and delivers. Draft **and customer identity unchanged** since the last issue → **resend** the same issued version and its stored PDF with a **new token** (old token marked `REPLACED`, kept for messaging). Draft **or customer name/billing changed** → new issued version that **supersedes** the previous one (its token marked `SUPERSEDED`) — see §7.1 send-email.
- **Delivery (email only):** **send-email** (issued PDF attachment + public quote link + body). Recipient comes from the saved Customer tab record (no send-time override). `send-sms` (link only) exists as **dormant** backend support and is excluded from the approved delivery roadmap (§9).
- **Public link:** top-level, slugless page `/q/{token}` (slug `q` reserved by `V18`) → slugless public API. Token random/secret, **stored hashed**. Link **expires 7 days after send**. **One `ACTIVE` token per order / active issued quote.** Superseded / expired / cancelled links are blocked with their own message. Public payload is **cost-free, no internal IDs**.
- **Remote acceptance** *(planned 16F — not implemented)*: the customer signs on their phone; the accepted quote snapshot is locked; a **signed quote PDF** is stored; the **store is notified by email**. Once signed, the **public link dies** — it is no longer viewable or signable. The signed quote is available only inside the protected salesperson portal.
- **Invoice conversion — Path A** (`create-invoice`) *(planned 16F — not implemented)*: invoice built from the **accepted quote snapshot** (total + frozen terms) inheriting the **signature** (`accepted_at`, name, signature file). Customer does **not** re-sign.
- **Cancel:** the salesperson can cancel an active issued quote → `CANCELLED`, link dies.
- **Quote PDF storage:** draft preview = on-demand (not stored); issued PDF = stored immutable on send; signed PDF = stored immutable on accept (16F). The Accepted Quote tab serves the signed PDF (16F).
- **Quote terms:** the quote shows the same per-flooring-type terms (`terms_soft` / `terms_hard`) the invoice uses, selected by order flooring type, **frozen into the version snapshot at issue and immutable through acceptance**.

---

## 3. Non-goals / post-MVP (explicitly excluded)

- **Multiple named quotes per order** (e.g. "Option A / Option B" comparison). One quote per order only. Advanced quote comparison stays out.
- **Auto-invoice on acceptance.** Invoice creation from an accepted quote is always a **manual** salesperson action (Create Invoice button).
- **Quote edit after acceptance.** An accepted quote is an immutable signed record. "Rewrite Quote" produces a **new editable draft copied from the accepted quote**; it never alters the accepted record or removes its signature.
- **Send-time recipient override**, customer-typed recipient, or multiple recipients.
- **Quote version-history UI / old-token reactivation / old signed-quote list** (parallels the Phase 12/13 current-only invoice model).
- **Payment on a quote.** Payments belong to invoices only.
- **SMS delivery.** Not in the approved roadmap — delivery is email only. The dormant backend SMS support (§9) sits behind a provider-independent interface with no provider configured; no SMS provider is planned.
- **Room-level complexity, installer/laybook, AI.**
- **Reusing the order status enum for quote state** (`LEAD … CANCELLED` are **order** statuses and are unrelated to quote state).

---

## 4. Data model contract

Phase 16B defined **five new tables**, implemented as migration **`V16`** (Phase 16C, next after `V15`). **`V17`** (16E-C) adds the issue-time customer snapshot columns to `quote_version` (§4.3); **`V18`** (16E-C) reserves the business slug `q` for the public page. Every committed migration is locked by policy (never edited). The CI "Locked migration protection" guard covers **V1–V13 only**, so `V14`–`V18` are outside it; all are folded into the Phase 17 squash/baseline.

Money stays `DECIMAL(10,2)`, scale 2, `HALF_UP`. GST handling **reuses the existing invoice model** (lines ex-GST; inc-GST total = the sale-price override; the existing rounding-aware computation is reused — do **not** reinvent it). Timestamps `YYYY-MM-DDTHH:mm:ss` (server-local). Dates `YYYY-MM-DD`.

### 4.1 `quote_draft` — editable working draft (one per order)

| Column | Type | Null | Notes |
|--------|------|------|-------|
| `quote_draft_id` | `BIGINT` GENERATED BY DEFAULT AS IDENTITY PK | no | |
| `order_id` | `BIGINT` | no | FK → `sales_order(order_id)`; **UNIQUE(order_id)** (one draft per order). No ON DELETE CASCADE. |
| `itemised` | `BOOLEAN` | no | default `true`. `false` = single quoted amount, no customer line breakdown. |
| `quote_total_ex_gst` | `DECIMAL(10,2)` | no | server-computed. Itemised: = sum of `quote_draft_line.line_total`. Non-itemised: = `final_total_inc_gst / 1.10` (HALF_UP 2dp), **independent of any retained lines** (Phase 16D-B PR2A, §6.1). |
| `quote_total_inc_gst` | `DECIMAL(10,2)` | no | server-derived (existing GST model); customer-facing quote total only — does **not** update the order sale-price override. Non-itemised: = `final_total_inc_gst` verbatim. |
| `created_at` / `updated_at` | `TIMESTAMP` | no | mutable working copy. |

The draft is **mutable in place** (PUT upsert). It is **not** a legal record.

### 4.2 `quote_draft_line` — customer-facing draft lines

| Column | Type | Null | Notes |
|--------|------|------|-------|
| `quote_draft_line_id` | `BIGINT` IDENTITY PK | no | |
| `quote_draft_id` | `BIGINT` | no | FK → `quote_draft(quote_draft_id)` ON DELETE CASCADE. |
| `line_type` | `VARCHAR(16)` | no | `ITEM` or `ADJUSTMENT` (DB CHECK). App validates before the DB backstop. |
| `description` | `VARCHAR(500)` | no | customer-facing text (editable). |
| `quantity` | `DECIMAL(10,2)` | yes | null for adjustment lines. |
| `unit_price_ex_gst` | `DECIMAL(10,2)` | yes | null for adjustment lines. |
| `line_total_ex_gst` | `DECIMAL(10,2)` | no | ITEM: qty × unit (server-checked). ADJUSTMENT: signed value (may be negative). |
| `sort_order` | `INT` | no | display order. |

**No `cost` column exists on quote lines** — quote lines are customer-facing only; costs live on the order's product/charge lines and are never copied here or exposed (catalog-search/cost discipline preserved).

**Retention (Phase 16D-B PR2A):** `quote_draft_line` rows are written **only by itemised saves** and persist as the **last edited itemised version** of the draft. A non-itemised save never writes and never deletes them — they stay as **dormant** rows while `quote_draft.itemised = false` so that switching back to itemised restores the salesperson's edited lines (surviving toggle-OFF, reload, and returning days later). See §6.1.

### 4.3 `quote_version` — append-only issued snapshots

| Column | Type | Null | Notes |
|--------|------|------|-------|
| `quote_version_id` | `BIGINT` IDENTITY PK | no | |
| `order_id` | `BIGINT` | no | FK → `sales_order(order_id)`. |
| `version_number` | `INT` | no | per-order sequence. **latest version = `max(version_number)`**; **active issued version = the row with `status = ISSUED`**; **latest accepted version = `max(version_number)` where `status = ACCEPTED`**. (The latest version is not necessarily the issued or accepted one.) |
| `status` | `VARCHAR(16)` | no | `ISSUED` · `SUPERSEDED` · `ACCEPTED` · `EXPIRED` · `CANCELLED` (DB CHECK). **`DRAFT` is the draft layer's conceptual state — never stored on `quote_version`.** |
| `itemised` | `BOOLEAN` | no | snapshot of the draft flag at issue. |
| `quote_total_ex_gst` | `DECIMAL(10,2)` | no | immutable snapshot. |
| `quote_total_inc_gst` | `DECIMAL(10,2)` | no | immutable snapshot; **this is the legal billing number once `status = ACCEPTED`.** |
| `flooring_type_snapshot` | `VARCHAR(8)` | no | `SOFT`/`HARD` at issue. |
| `terms_snapshot` | `TEXT` | yes | the per-type terms HTML frozen at issue; immutable through acceptance (nullable — terms may be unset). |
| `details_of_sale_snapshot` | `TEXT` | yes | non-itemised body / supporting text. |
| `customer_name_snapshot` | `TEXT` | yes | **`V17`**. "Quotation To" customer name (first [middle] last) frozen at issue; null when none was saved. |
| `customer_address_line1_snapshot` | `TEXT` | yes | **`V17`**. Billing address line 1 (`[unit/]street_number street`) frozen at issue. |
| `customer_address_line2_snapshot` | `TEXT` | yes | **`V17`**. Billing address line 2 (`suburb state postcode`) frozen at issue. |
| `sent_channel` | `VARCHAR(8)` | yes | `EMAIL` / `SMS` of the **latest** send (`SMS` only via the dormant `send-sms`, §9). |
| `first_sent_at` | `TIMESTAMP` | yes | when this version was first issued/sent. |
| `last_sent_at` | `TIMESTAMP` | yes | **latest** send time (updated on every resend). |
| `last_emailed_at` | `TIMESTAMP` | yes | last successful email of this version (mutable delivery marker). |
| `viewed_at` | `TIMESTAMP` | yes | first public view — stamped write-once by the public `viewed` POST (16E-C); the public GET never stamps it. |
| `accepted_at` | `TIMESTAMP` | yes | set on remote signing (16F). |
| `accepted_customer_name` | `VARCHAR(150)` | yes | captured at accept (16F; the name source is an open 16F-gate question — see §7.2 accept). |
| `accepted_signature_file_id` | `BIGINT` | yes | FK → `stored_file`; **not unique** (16F). |
| `issued_pdf_file_id` | `BIGINT` | yes | FK → `stored_file` (UNIQUE); the immutable issued PDF. |
| `signed_pdf_file_id` | `BIGINT` | yes | FK → `stored_file` (UNIQUE); the immutable signed PDF, set on accept (16F). |
| `created_by_user_id` | `BIGINT` | no | FK → `app_user`. |
| `created_at` | `TIMESTAMP` | no | |

**Invariant:** at most **one** `quote_version` per order in `status = ISSUED` (the active, signable version). Sending a changed draft inserts a new `ISSUED` row and flips the prior `ISSUED` row to `SUPERSEDED`. **`ACCEPTED` versions are historical and immutable — more than one may exist** (e.g. accept V1, later Rewrite Quote → issue + accept V2). The **latest** `ACCEPTED` version (highest `version_number` among `ACCEPTED`) is the **invoice-eligible** one and the one shown in the Accepted Quote tab; older accepted versions remain as signed history.

### 4.4 `quote_version_line` — immutable snapshot of issued lines

| Column | Type | Null | Notes |
|--------|------|------|-------|
| `quote_version_line_id` | `BIGINT` IDENTITY PK | no | |
| `quote_version_id` | `BIGINT` | no | FK → `quote_version` ON DELETE CASCADE. |
| `line_type` / `description` / `quantity` / `unit_price_ex_gst` / `line_total_ex_gst` / `sort_order` | — | — | immutable copy of the draft lines at issue. |

### 4.5 `quote_token` — public-link tokens (kept for messaging; never deleted)

A token is **never deleted or cleared** — that is what makes the per-state customer messages possible. When a link dies, its row is marked dead with a **reason**, so the public GET can still resolve the hash and return the correct message (expired vs replaced vs cancelled vs signed/inactive). A deleted/nulled token could only ever yield a generic 404. **A consumed (signed) token is dead like the others — the link is not a viewable "already signed" page; it shows a "no longer active" message, and the signed quote is only accessible in the protected portal.**

| Column | Type | Null | Notes |
|--------|------|------|-------|
| `quote_token_id` | `BIGINT` IDENTITY PK | no | |
| `quote_version_id` | `BIGINT` | no | FK → `quote_version`. Many tokens may point at one version (each resend mints a new one). |
| `token_hash` | `VARCHAR(255)` | no | **UNIQUE**. Hash of the secret token; the plain token is never stored. CSPRNG-generated, constant-time compared. |
| `status` | `VARCHAR(16)` | no | `ACTIVE` · `REPLACED` · `SUPERSEDED` · `EXPIRED` · `CANCELLED` · `CONSUMED` (DB CHECK). |
| `expires_at` | `TIMESTAMP` | no | `created_at + 7 days`; checked server-side every public hit. |
| `created_at` | `TIMESTAMP` | no | mint time. |
| `dead_at` | `TIMESTAMP` | yes | when the token left `ACTIVE`. |

**One `ACTIVE` token per order / active issued quote** (the live link). Token status → customer-facing state in the public GET:

| `quote_token.status` | Cause | Public `state` / message |
|----------------------|-------|--------------------------|
| `ACTIVE` (and not past `expires_at`) | live link | `ACTIVE` — viewable (signable once 16F adds `accept`) |
| `REPLACED` | resend of the same version minted a newer token | `SUPERSEDED` — "This quote has been replaced. Please use the latest quote link." |
| `SUPERSEDED` | a newer issued version replaced this token's version | `SUPERSEDED` — same message |
| `EXPIRED` | past `expires_at` (lazy-flipped on access) | `EXPIRED` — "This quote link has expired. Please contact the store." |
| `CANCELLED` | quote cancelled | `CANCELLED` — "This quote has been cancelled. Please contact the store." |
| `CONSUMED` | the quote was signed via this token | `INACTIVE` — "This quote link is no longer active. Please contact the store." (not viewable, not signable) |

`quote_version.status` remains the authoritative **quote** state; `quote_token.status` drives the **link message**. When a version flips to `SUPERSEDED`/`CANCELLED`/`ACCEPTED`, its `ACTIVE` token is updated to `SUPERSEDED`/`CANCELLED`/`CONSUMED` respectively; a resend marks the old token `REPLACED` and inserts a new `ACTIVE` token.

**Signature & PDFs** reuse `stored_file` + `FileStorageService` (internal `storage_path` under `/uploads/{businessId}/orders/{orderId}/{uuid}.{ext}`, **never** exposed). The signature is **not** an `order_attachment` and never appears in Notes & Photos.

**Customer contact** comes from `order_customer` (`email`; the existing customer **mobile** field is used only by the dormant `send-sms`) — one row per order. No quote-specific recipient is stored.

**Customer identity snapshot (`V17`, 16E-C).** The customer-facing "Quotation To" block — name + billing address lines — is frozen onto `quote_version` at issue, using the same derivation as the issued PDF, so the stored PDF and the snapshot columns agree. The public payload reads these snapshot columns and never the live `order_customer` / `order_address` rows, so a customer edit after issue cannot reach an already-issued link. The `V17` migration backfilled pre-existing (dev-only) versions from the then-current customer rows.

---

## 5. Quote statuses & lifecycle (locked)

Quote state is **separate from order status** and is not an order-status enum.

```text
Conceptual states:  DRAFT (draft layer) → ISSUED → { ACCEPTED | SUPERSEDED | EXPIRED | CANCELLED }
Persisted quote_version.status: ISSUED · SUPERSEDED · ACCEPTED · EXPIRED · CANCELLED
```

| Transition | Trigger | Effect |
|------------|---------|--------|
| (none) → DRAFT | first `PUT …/quote/draft` | creates/updates `quote_draft`; below-cost blocked at save |
| DRAFT → ISSUED | `send-email` (or the dormant `send-sms`), draft **or customer name/billing changed** since last issue (or no active issued version) | new `quote_version` (`ISSUED`), snapshot lines + terms + `V17` customer identity, store issued PDF, mint `ACTIVE` token; prior `ISSUED` → `SUPERSEDED` (its token → `SUPERSEDED`) |
| ISSUED → ISSUED (resend) | `send-email` (or the dormant `send-sms`), draft **and customer identity unchanged** | same version and its stored PDF re-delivered verbatim (nothing regenerated); new `ACTIVE` token minted, old token → `REPLACED`; `last_sent_at` updated. `last_emailed_at` is updated **only on a successful email send** (an SMS resend updates `last_sent_at` only, never `last_emailed_at`). |
| ISSUED → ACCEPTED | public `accept` *(planned 16F)* | snapshot locked as legal number; store signed PDF; notify store; token → `CONSUMED` |
| ISSUED → CANCELLED | `cancel` | active issued version cancelled; token → `CANCELLED` |
| ISSUED → EXPIRED | an **`ACTIVE`** token passes `expires_at` | lazily on the next public hit (16E-C; no background job exists): token → `EXPIRED`, version → `EXPIRED`. Only an `ACTIVE` token on an `ISSUED` quote can expire; `CONSUMED`/`SUPERSEDED`/`REPLACED`/`CANCELLED` tokens never expire later, and `ACCEPTED` versions never become `EXPIRED`. |
| ACCEPTED → (new DRAFT) | "Rewrite Quote" (a `PUT …/quote/draft`) *(16F)* | new editable draft **copied from the accepted snapshot**; the `ACCEPTED` version is untouched and keeps its signature |

A draft may coexist with an accepted version. Signing the active issued version sets `ACCEPTED`; an unsent draft beside it remains draft work (unsent/unaccepted).

---

## 6. Money / billing model (locked — the core of 16B)

- **Itemised mode: quote total = sum of quote lines** (ex-GST lines; inc-GST total derived via the existing invoice GST model). The system enforces `quote_total_ex_gst == Σ line_total_ex_gst` on every **itemised** save and issue. The invariant is **mode-scoped** (amended Phase 16D-B PR2A): it does **not** apply to a non-itemised draft, whose totals are independent of any retained dormant lines (§6.1).
- **Direct total reduction (itemised):** if the salesperson lowers the final total below the line sum, the backend inserts a negative `ADJUSTMENT` line equal to the difference so the invariant holds. The adjustment is customer-facing.
- **Direct total increase above line sum (itemised saves only):** rejected — 422 `QUOTE_TOTAL_EXCEEDS_LINES`. The salesperson must raise an existing line or add a positive line/adjustment. This check never applies to a non-itemised save.
- **Quote/order price separation (locked):** saving a draft and issuing/sending a quote do **not** change the order's sale-price override or header financials. The quote draft total is a customer-facing figure that may differ from the order's working price; the order price is driven only by Products & Charges plus the manual sale-price override. (Decoupled in Phase 16D-A; supersedes the earlier "cosmetic override coupling.")
- **Legal billing number (snapshot):** the **accepted `quote_version` snapshot** (`quote_total_inc_gst` at `ACCEPTED`) is the legal billing number. It is **not** the live override.
- **Invoice conversion uses the snapshot, not the live override:**
  - **Path A** *(planned 16F — not implemented)* — `create-invoice` from the accepted quote: the invoice's sale price = the **accepted quote snapshot total**, terms = the quote's **frozen `terms_snapshot`**, and the signature/acceptance is **inherited** (no re-sign).
  - **Path B** *(existing)* — the Details-of-Sale → Create Invoice flow (Phase 12/13): uses the **live order sale price / override**, **ignores the quote entirely**, inherits **no** quote signature; the customer signs the invoice normally. If a salesperson overrides the price in Details of Sale and creates an invoice, the quote is bypassed and its number does not apply.
- **Below cost** (GP negative / sale ex-GST below total cost ex-GST, using the order's product/charge cost lines): blocked at **save** (`PUT draft`), **send** (`send-email`, re-checked against the live cost lines before anything is persisted; also the dormant `send-sms`), and **accept** (public `accept`, 16F) → 422 `QUOTE_BELOW_COST`. Warning-range GP is allowed (draft surfaces the warning like Details of Sale). Applies to **both modes**: itemised uses the visible line sum; non-itemised uses the ex total derived from `final_total_inc_gst` (§6.1) — never the retained dormant lines.

### 6.1 Non-itemised mode & itemised-line retention (locked — Phase 16D-B PR2A)

Amends the original Q2 "non-itemised synthetic line" decision. **The synthetic "Quoted works" line is removed** — a non-itemised draft no longer stores any generated line.

- **Request shape unchanged:** a non-itemised save still sends exactly `{ "itemised": false, "final_total_inc_gst": <number>, "lines": [] }` (`final_total_inc_gst` required; `lines` must be empty). No new field is required.
- **Header-only save:** a non-itemised save updates `quote_draft.itemised` and the totals only. `quote_total_inc_gst = final_total_inc_gst`; `quote_total_ex_gst = final_total_inc_gst / 1.10` (HALF_UP 2dp) — the same math as before, without a synthetic line carrying it.
- **Retention:** a non-itemised save **never writes and never deletes** `quote_draft_line` rows. Rows persisted by the last **itemised** save are retained as **dormant** rows so they survive toggle-OFF, page reload, and returning days later. Switching back to itemised edits/replaces those retained rows via a normal itemised save (full replace). The frontend must not re-seed from Products & Charges when persisted itemised rows exist.
- **Reads:** the workspace read (and the save response) of a non-itemised draft returns the retained rows in `draft.lines[]` (empty array when none exist). Clients must **not** render them while itemised is OFF. `quote_total_*` on a non-itemised draft are always the final-total-derived values, independent of `lines[]`.
- **Validation:** `QUOTE_TOTAL_EXCEEDS_LINES` is **itemised-only**. A non-itemised `final_total_inc_gst` is **never** validated against retained dormant lines (they are independent by design while the draft is non-itemised). Below-cost and `gp_percent` on a non-itemised draft are computed from the final-total-derived ex total.
- **PDF:** the non-itemised preview/PDF renders the single-amount presentation from the draft totals; dormant retained rows are never rendered and never affect non-itemised PDF totals.
- **Legacy transition (documented accepted edge — dev data only, pre-deployment):** drafts saved non-itemised **before** PR2A carry a real stored "Quoted works" `ITEM` row. There is no schema flag to identify it, description-string matching is forbidden, and after a toggle-OFF a non-itemised draft legitimately carries retained rows — so a legacy synthetic row is structurally indistinguishable from retained rows, and a non-itemised save deliberately does **not** delete stored lines (deleting on a non-itemised save would also destroy retained rows on the next autosave after toggle-OFF, defeating retention). A legacy synthetic row may therefore appear in `lines[]` on read (it is not rendered in non-itemised mode) and, if the draft is switched to itemised, in the editor; it is healed by the next **itemised** save (full line replace) or by manual dev-data cleanup.
- **16E guard:** issuing a **non-itemised** draft must snapshot the non-itemised presentation (totals; no line breakdown). Dormant retained rows are draft-workspace state only and must **not** be copied into `quote_version_line` for a non-itemised issue. (Implemented in 16E-A: a non-itemised issue snapshots zero lines, and changed-detection compares a non-itemised draft on header fields only.)

> **Planned change — Batch A, [#108](https://github.com/MuneebHash/flooring-sales-portal/issues/108) (approved, NOT implemented).** An explicit switch of a **draft** from non-itemised to itemised will refill the quote lines from the current Products & Charges and discard manual quote rows/adjustments (not continuous auto-sync; issued/accepted snapshots and converted invoices untouched; whether to confirm first is still open). Until that implementation PR merges, the retention rule above is the **active** contract: non-itemised saves retain dormant rows and switching back to itemised restores them. The #108 PR must amend this section, the UX lock and `openapi.yaml` together with its code.

---

## 7. Endpoint contracts

All **protected** quote endpoints are Standard protected (conventions §9), scoped to the session `(business_id, store_id)`, resolve the order, and follow Chunk 4 JSON conventions (snake_case bodies; `{ "data": …, "message": … }` success; `{ "error": { "code", "message", "details" } }` error). Cross-tenant / cross-store / cross-order misses → **404**, never 403.

All **public** endpoints are token-only (no session). Unknown token → **404** `QUOTE_TOKEN_NOT_FOUND` (no existence leak). They expose only the single quote behind the token and never any cost or internal ID.

**Endpoint implementation status (post-16E):** implemented — `workspace`, `draft`, `preview-pdf`, `send-email`, `cancel`, `pdf?type=issued`, public `GET`, `viewed` and `pdf`. Dormant — `send-sms` (implemented but outside the approved roadmap; never called by the frontend). Planned 16F, **no handler exists** — `create-invoice` and public `accept`; `pdf?type=accepted` is routed but always returns 404 `QUOTE_PDF_NOT_FOUND` until 16F creates signed PDFs.

### 7.1 Protected — salesperson

#### GET `/api/v1/{slug}/orders/{orderId}/quote/workspace`
Loads the full quote state for the Quote tab.
- **200** `{ data: { draft, current_issued, accepted }, message }` where:
  - `draft` — editable draft + lines + GP/below-cost flags (null if none yet). For a **non-itemised** draft, `lines[]` contains the **retained dormant** itemised rows (empty when none — §6.1); totals are the final-total-derived values, independent of those rows.
  - `current_issued` — the active `ISSUED` version summary (status, first_sent_at, last_sent_at, viewed_at, active-token `expires_at`, channel, **no token**; since 16E-B also the frozen body — ex total, details of sale, snapshot lines), or null.
  - `accepted` — the **latest** `ACCEPTED` version summary (accepted_at, accepted_customer_name, signature-present), or null. Older accepted versions are signed history and not surfaced here. Always null until 16F.
- Cost-free? No — this is the **protected** salesperson view; GP/cost-derived flags are allowed here (never the public surface).
- **LAID:** read allowed.

#### PUT `/api/v1/{slug}/orders/{orderId}/quote/draft`
Upsert the editable draft (itemised flag, lines, adjustments). An **itemised** save full-replaces the draft lines (like the workspace autosave pattern); a **non-itemised** save is header-only — it updates mode/totals, writes **no** lines (no synthetic line), and **retains** previously saved itemised rows untouched (§6.1).
- Server recomputes totals; on an itemised save it enforces the line/total invariant (§6) and applies auto-adjustment on direct reduction.
- **Below-cost → 422 `QUOTE_BELOW_COST`** (blocked at save; both modes — itemised uses the line sum, non-itemised the final-total-derived ex total).
- **Total > line sum → 422 `QUOTE_TOTAL_EXCEEDS_LINES`** (itemised saves only; never checked against retained dormant lines).
- Does **not** update the order sale-price override or any `sales_order` header financials (quote draft is price-independent; decoupled in Phase 16D-A).
- **LAID → 422 `ORDER_LOCKED`** (write blocked).
- **200** updated `draft`.

#### POST `/api/v1/{slug}/orders/{orderId}/quote/preview-pdf`
On-demand **draft** preview PDF. **Not stored.**
- **200** `application/pdf`, `Content-Disposition: inline; filename="quote-preview-{order_number}.pdf"`.
- Aire Compact style, title `QUOTATION`, shows draft lines/total, selected per-type terms, the display-only 40% deposit line, and the printable declaration/customer acceptance area.
- **LAID:** allowed (read-only render). Below-cost does **not** block a draft *preview* (only save/send/accept block).

#### POST `/api/v1/{slug}/orders/{orderId}/quote/send-email`
Issue (or resend) the quote and email it (issued PDF attachment + public quote link + body; the link is view-only until 16F adds signing).
- **Changed-detection (identity-aware, content only — never timestamps):** the draft is compared with the active `ISSUED` version — itemised flag, totals, order flooring type, order details-of-sale text, the ordered lines (itemised drafts only; dormant rows on a non-itemised draft are ignored), and the current customer name + billing lines against the `V17` snapshot columns. Tenant terms/config are **not** compared.
- **Changed** (or no active issued version) → new `ISSUED` version (supersede prior): snapshot lines + per-type terms + customer identity, **store a new immutable issued PDF**, prior token → `SUPERSEDED`. Editing the customer's name or billing address after issue therefore produces a new version, so the old artifact is never re-sent to the new identity.
- **Unchanged** → **resend**: the same version and its **stored PDF re-delivered verbatim** (no new snapshot, nothing regenerated); prior token → `REPLACED`. A pure resend never re-freezes terms or business settings — that happens only when a new version is issued.
- Either way: mint a **new `ACTIVE` `quote_token`** (`expires_at = now + 7d`), set `sent_channel = EMAIL`, update `first_sent_at`/`last_sent_at` before delivery, and set `last_emailed_at` only after a **successful email send**.
- **Current build:** delivery goes to the in-memory `RecordingQuoteEmailSender` — no real email is sent (§9). A production email provider is Phase 17.
- Re-checks **below-cost → 422 `QUOTE_BELOW_COST`**; line/total invariant.
- Customer **email** required/valid (§9 reuse) → 422 `CUSTOMER_EMAIL_REQUIRED` / `CUSTOMER_EMAIL_INVALID`.
- **LAID → 422 `ORDER_LOCKED`.**
- Email send is the operation → provider failure **502 `EMAIL_SEND_FAILED`** (nothing partially sent; the issued version + PDF persist, but treat per §13 transaction notes).
- **201** issued version summary (no token in the body).

#### POST `/api/v1/{slug}/orders/{orderId}/quote/send-sms`
> **Dormant — existing backend support, excluded from the approved delivery roadmap (email only).** The endpoint, its error codes and the `SMS` channel value are kept as defined here, but no SMS provider exists (in-memory `RecordingSmsSender` only), the frontend never calls it (the "Send by Phone/SMS" button is disabled), and no activation is planned.

Same issue/resend logic as `send-email`, but delivers an **SMS with the link only** (no PDF).
- Customer **mobile** required/valid → 422 `CUSTOMER_MOBILE_REQUIRED` / `CUSTOMER_MOBILE_INVALID`.
- Provider failure → **502 `SMS_SEND_FAILED`**.
- `sent_channel = SMS`; updates `first_sent_at`/`last_sent_at`. **Never** updates `last_emailed_at` (that marker is for email sends only). **201** issued version summary.

> Both send endpoints apply the identical version/resend rule (draft or customer identity changed = new issued version; unchanged = resend same version with a new token). They differ only in delivery payload.

> **Send-failure rule (locked, MVP).** The issued version + issued PDF + token are persisted **before** the delivery attempt. If email/SMS delivery then fails, the backend **keeps** the issued version/PDF/token, returns **502**, and the salesperson **resends** (no new version on a pure resend). **Accepted tradeoff:** if the failed send was for a *changed* draft (a new issued version), the previous version/link is already `SUPERSEDED` even though the new delivery failed — the customer's old link is dead and they must receive the new (resent) link. This is accepted for MVP.

#### POST `/api/v1/{slug}/orders/{orderId}/quote/cancel`
Cancel the active issued quote.
- Requires an active `ISSUED` version, else 422 `QUOTE_NOT_ISSUED`. Already `ACCEPTED` → 409 `QUOTE_ALREADY_ACCEPTED`.
- Sets version `CANCELLED`; marks the active token `CANCELLED` (kept for messaging; link dead).
- **LAID:** create/edit/send are blocked when LAID; **cancel is allowed** as a narrow exception — its only effect is to kill an active public quote link (not an order mutation). Empty body.
- **200** cancelled version summary.

#### POST `/api/v1/{slug}/orders/{orderId}/quote/create-invoice`  *(Path A)*
> **Planned 16F — not implemented.** No handler exists and `QUOTE_NOT_ACCEPTED` is not yet in `ErrorCode`; the definition below is the contract 16F builds to.

Create an invoice from the **latest accepted** quote.
- Requires at least one `ACCEPTED` version, else 422 `QUOTE_NOT_ACCEPTED` (use the Details-of-Sale Create Invoice flow — Path B — instead). Uses the **latest** `ACCEPTED` version's snapshot.
- Invoice sale price = accepted **snapshot** total; invoice terms = the quote's **frozen `terms_snapshot`**; invoice acceptance **inherited** (`accepted_at`, `accepted_customer_name`, `accepted_signature_file_id` carried; customer does **not** re-sign); generates the signed invoice PDF.
- Not blocked by an unsigned draft. Quote is **not mandatory** for invoicing generally (Path B always exists).
- **LAID:** follows the existing invoice-create LAID rule.
- **201** the created `invoice_detail` (Phase 13 E.2 shape, accepted).

#### GET `/api/v1/{slug}/orders/{orderId}/quote/pdf?type=issued|accepted`
Salesperson download of a **stored** quote PDF (no customer token needed).
- `type=issued` → the active issued version's stored PDF; `type=accepted` → the accepted signed PDF (16F; until then always 404 `QUOTE_PDF_NOT_FOUND`).
- Missing requested artifact → 404 `QUOTE_PDF_NOT_FOUND`.
- **200** `application/pdf`, `inline; filename="quote-{order_number}-v{version_number}.pdf"`. **LAID:** read allowed.

### 7.2 Public — customer (token only, slugless API)

The customer page lives at the top-level, **slugless** route **`/q/{token}`** (implemented 16E-C; the slug `q` is reserved by `V18` so no business can shadow it); it calls the **slugless** public API below, without credentials. The link delivered in the quote email is `{app.public-base-url}/q/{token}`.

#### GET `/api/v1/public/quotes/{token}`
Open the quote. *(Implemented 16E-C.)*
- Resolves the token by hash via `quote_token`. Unknown hash → 404 `QUOTE_TOKEN_NOT_FOUND`. A **found** token always yields a `state` (the customer holds the token), derived from `quote_token.status` (§4.5).
- **200** with a **cost-free, internal-ID-free** payload and a **`state`**. For `ACTIVE`, the payload (extended additively in 16E-C) carries:
  - **issued-snapshot content (frozen at issue):** quote lines (description/qty/unit/total, ex-GST presentation per existing model) or the single amount, ex/GST/inc totals, frozen terms, frozen details of sale, the flooring-type snapshot, and the V17 "Quotation To" customer name + billing lines;
  - **derived or read at request time (not part of the snapshot):** the display-only deposit, computed as the hardcoded 40% of the frozen inc-GST total (the percentage is not stored; #112 is future work); the order number, read from the order; and the link expiry, read from the presented token (a resend mints a new token with a new expiry);
  - **live business presentation context (the approved set):** business name, logo, accent, ABN, direct-deposit bank details and the (HTTPS-only) Stripe payment link, read from the tenant's current settings. These are therefore **not** guaranteed to match the stored issued PDF after the business changes its settings; terms never change (they ride the snapshot).
- A non-`ACTIVE` state returns only the minimal payload (state, business name, message). The GET never stamps `viewed_at`. States:
  - `ACTIVE` — viewable (signable once 16F adds `accept`).
  - `EXPIRED` — show "This quote link has expired. Please contact the store." (payload minimal).
  - `SUPERSEDED` — show "This quote has been replaced. Please use the latest quote link." (from a `REPLACED` or `SUPERSEDED` token).
  - `CANCELLED` — show "This quote has been cancelled. Please contact the store."
  - `INACTIVE` — the quote was signed via this link (token `CONSUMED`); show "This quote link is no longer active. Please contact the store." **Not** a viewable signed copy — the signed quote is portal-only.
- Rate-limited (see §8 — not yet implemented). Lazily flips **an `ACTIVE` token** past `expires_at` to `EXPIRED` (and its `ISSUED` version to `EXPIRED`) on access. Tokens already `CONSUMED`/`SUPERSEDED`/`REPLACED`/`CANCELLED`, and `ACCEPTED` versions, are never expired by this check.

#### POST `/api/v1/public/quotes/{token}/viewed`
Mark first view (`viewed_at`). *(Implemented 16E-C.)* Write-once and idempotent (later calls are no-ops; no view count); only valid while `ACTIVE` — a dead link returns the matching 410, an unknown token 404. Empty body. **200** with the refreshed view. The public page calls it after a successful `ACTIVE` GET; the salesperson's Customer Quote shows **Opened** from its next workspace load.

#### POST `/api/v1/public/quotes/{token}/accept`
> **Planned 16F — not implemented.** No handler exists at this base; the public page's acceptance area is static, non-interactive document content. The definition below is the contract 16F builds to.

Customer signs remotely.
- **`multipart/form-data`** (file-binary exception): `signature` — **`image/png` only**, non-empty, **≤ 2 MB** (mirrors Phase 13 D.8). `accepted_customer_name` is taken **server-side from the saved `order_customer` record**. The customer does **not** type or edit their name in MVP (consistent with the locked invoice acceptance rule).
- **Open for the 16F read-only verify gate:** since `V17`, the issued quote also carries a frozen customer-name snapshot. Whether the accepted name should come from the live saved customer record (as written above) or from that issued snapshot is **not decided** by this contract; the gate must resolve it before 16F implementation.
- State must be `ACTIVE`, else:
  - `EXPIRED` → 410 `QUOTE_LINK_EXPIRED`
  - `SUPERSEDED` → 410 `QUOTE_LINK_SUPERSEDED`
  - `CANCELLED` → 410 `QUOTE_LINK_CANCELLED`
  - already signed (`CONSUMED`) → 410 `QUOTE_LINK_INACTIVE`
- **Below-cost re-check → 422 `QUOTE_BELOW_COST`** (defensive; should not occur for an issued quote).
- Persist (one transaction): store signature `stored_file`; set version `status = ACCEPTED`, `accepted_at`, `accepted_customer_name`, `accepted_signature_file_id`; generate + store the **signed quote PDF** (`signed_pdf_file_id`); set the token `CONSUMED` (the public link is now dead).
- **Post-commit:** email the **store** an acceptance notification (non-fatal; failure does not roll back acceptance).
- **201** `{ state: "INACTIVE" }` minimal confirmation — the link is now dead; the signed quote lives only in the portal. Signature must not be discarded on notification-email failure.

#### GET `/api/v1/public/quotes/{token}/pdf`
*(Implemented 16E-C — streams the stored issued bytes verbatim; nothing is regenerated.)* Stream the **issued** PDF to the customer **only while the link is `ACTIVE`**. Cost-free. Once the token is `CONSUMED` (signed) or otherwise dead, returns the matching 410 (`QUOTE_LINK_INACTIVE` / `_EXPIRED` / `_SUPERSEDED` / `_CANCELLED`), or 404 for an unknown token. The **signed** PDF is **never** served on the public surface — it is portal-only (`GET …/quote/pdf?type=accepted`). **200** `application/pdf`, `inline`.

---

## 8. Token / link rules (locked)

```text
- Page URL: /q/{token} (top-level, slugless; slug 'q' reserved by V18) ; API: slugless /api/v1/public/quotes/{token}.
- Token is random/secret (≥ 32 bytes, URL-safe), NOT the order id or quote id.
- Store only the token HASH (`quote_token.token_hash`); never store the plain token.
- Expires 7 days after send (`quote_token.expires_at = created_at + 7d`), checked server-side every public hit.
- One `ACTIVE` token per order / active issued quote.
- Resend an unchanged quote = mint a NEW `ACTIVE` token; the old one → `REPLACED` (kept, not deleted).
- Send a changed draft = NEW issued version; the old version → `SUPERSEDED` and its token → `SUPERSEDED`.
- Tokens are NEVER deleted/cleared — dead links keep their row + reason so the correct message can be shown (a deleted token could only return a generic 404).
- **Only `ACTIVE` tokens can expire.** Lazy expiry applies to an `ACTIVE` token past `expires_at` (→ `EXPIRED`, version → `EXPIRED`). A `CONSUMED` / `SUPERSEDED` / `REPLACED` / `CANCELLED` token **never** changes state again, and an `ACCEPTED` quote version **never** becomes `EXPIRED` — signing is terminal for the quote record regardless of clock time.
- SUPERSEDED / EXPIRED / CANCELLED / INACTIVE(signed) links are blocked (own message each, derived from `quote_token.status`).
- Public payload is cost-free and exposes no internal IDs.
- After signing, the token is `CONSUMED` and the public link is **dead** — no longer viewable or signable (it shows "no longer active"). The accepted/signed quote is available only in the protected portal.
- Rate limiting: do NOT expire normal users by view count. A customer opening the link many
  times within 7 days is fine. Only temporarily throttle/block suspicious rapid repeated
  access by token/IP (basic per-token + per-IP throttle on the public GET/accept paths).
```

**Status (post-16E):** the token shape gate, SHA-256 hash storage, constant-time verification, 7-day lazy expiry and the per-state messages are implemented (16E-A/16E-C). **Rate limiting is not implemented**: no application-level throttle exists and nothing is deployed, so this rule is not satisfied anywhere today. It is Phase 17 work (decided 29 Sep 2026); application vs infrastructure level is undecided (see `docs/Phases.md` §7).

---

## 9. Delivery / email / SMS rules

- **Approved delivery is EMAIL ONLY.** **send-email** delivers the **issued PDF as attachment** plus the public quote **link** plus a short body, and mints/refreshes the token per §5/§8.
- **Current build — recording only:** the only `QuoteEmailSender` is the in-memory `RecordingQuoteEmailSender`; **no real email is sent**. The recorded message (which contains the bearer link) is logged only when DEBUG logging is explicitly enabled for that class — `application.properties` does not enable it. A production email provider is Phase 17.
- **SMS — dormant, excluded from the approved roadmap.** 16E-A left backend support in place: the `send-sms` endpoint (link-only text), the `SMS` channel value, the `CUSTOMER_MOBILE_REQUIRED` / `_INVALID` and `SMS_SEND_FAILED` codes, and an `SmsSender` interface whose only implementation is the in-memory `RecordingSmsSender` (no provider). The frontend never calls it and its "Send by Phone/SMS" button is disabled. It is documented so the definitions stay accurate, not as planned delivery.
- Recipient is the saved customer **email** (email path; the **mobile** only for the dormant SMS path) from the Customer tab. **No send-time override** — to change the recipient, update the Customer tab first.
- Email gate (`CUSTOMER_EMAIL_REQUIRED` / `_INVALID`) and the dormant SMS gate (`CUSTOMER_MOBILE_REQUIRED` / `_INVALID`) are **fatal preconditions** (422 before any state change).
- The **store acceptance notification** (on `accept`, planned 16F) is a **non-fatal** post-commit email to the store/business address (acceptance persists if it fails).
- Senders are **provider-independent** behind interfaces (email per Phase 13). Provider failure on a *send* (where delivery is the whole operation) → **502** (`EMAIL_SEND_FAILED`; `SMS_SEND_FAILED` on the dormant SMS path).

---

## 10. PDF rules

- **Draft preview** (`preview-pdf`) is generated **on-demand and not stored**.
- **Issued PDF** is generated and **stored immutably** when a new version is issued (`issued_pdf_file_id`). A resend re-delivers the stored bytes verbatim; the salesperson (Customer Quote preview) and the public link both stream the stored file — it is never regenerated.
- **Signed PDF** *(planned 16F)* is generated and **stored immutably** on acceptance (`signed_pdf_file_id`); it is served **only** in the protected portal (Accepted Quote tab / `GET …/quote/pdf?type=accepted`). It is **never** served on the public surface — the public link is dead once signed.
- All quote PDFs reuse the **Aire Compact** document style with title **`QUOTATION`** (not `TAX INVOICE`), itemised columns (description / quantity / unit price / amount) in itemised mode, or a totals-only non-itemised presentation with details-of-sale text and no filler line section (dormant retained draft lines are never rendered on a non-itemised quote PDF — §6.1), plus a display-only 40% deposit line, printable declaration/customer acceptance area, and the frozen per-type terms (page 2, single column — same openhtmltopdf constraints as the invoice; tables/conservative CSS only; numeric entities, not named ones).
- The signed PDF (16F) embeds the signature image + accepted customer name + accepted timestamp (mirrors the invoice signed PDF).

---

## 11. Frontend UX contract (Quote tab)

Behavioural contract only. Built in 16D (Quote Draft) and 16E-B (Customer Quote); Accepted Quote is 16F. `docs/Phase16D-Quotation-UX-Lock.md` is the detailed UX lock and wins on frontend workflow/visual decisions (e.g. it replaced the Save button below with autosave).
- The Quote tab has three sub-views: **Quote Draft**, **Customer Quote** (latest issued/sent), **Accepted Quote**. A sent/accepted quote is **never hidden** just because a draft exists.
- **Quote Draft** *(built)*: itemised/non-itemised toggle; editable lines + adjustments; live total = line sum; GP/below-cost warning (like Details of Sale); **autosave** (no Save button — UX lock §10), **Preview PDF**, and **Send Quote** (confirmation modal → **Send by Email**; **Send by Phone/SMS** is disabled — SMS is dormant, §9). Below-cost blocks save/send (surfaced inline).
- **Customer Quote** *(built, 16E-B)*: the active issued version — status (Sent / Opened / Not delivered), channel, sent time, link expiry, **Preview PDF** of the stored issued PDF, **Resend** (through the same confirmation), **Cancel quote**. Never shows cost.
- **Accepted Quote** *(planned 16F; empty state today)*: accepted time, captured signature (display), **Download signed PDF**, and **Create Invoice** (Path A). Create Invoice is **not** disabled merely because some draft is unsigned.
- **LAID:** draft inputs/save/send/resend disabled; reads (preview/stored PDF/view state) and Cancel quote allowed.
- Recipient errors (`CUSTOMER_EMAIL_*`) prompt the user to fix the Customer tab.

---

## 12. Error contract

Envelope unchanged (conventions §3): `{ "error": { "code", "message", "details"? } }`, `UPPER_SNAKE_CASE`. **New** Phase 16B codes (add to `ErrorCode` in the existing `NAME(HttpStatus.X, "Sentence-case message.")` style). All are in `ErrorCode` at the post-16E base **except** `QUOTE_NOT_ACCEPTED`, which 16F adds with `create-invoice`:

| Code | HTTP | Default message | Where |
|------|------|-----------------|-------|
| `QUOTE_NOT_FOUND` | 404 | "No quote was found for this order." | workspace/draft (defensive); send with no saved draft |
| `QUOTE_BELOW_COST` | 422 | "This quote is below cost and cannot be saved, sent, or accepted." | draft / send-* / accept (16F) |
| `QUOTE_TOTAL_EXCEEDS_LINES` | 422 | "The quote total cannot exceed the sum of its lines. Add or raise a line instead." | draft |
| `QUOTE_NOT_ISSUED` | 422 | "There is no active quote to cancel." | cancel |
| `QUOTE_NOT_ACCEPTED` | 422 | "This quote has not been accepted yet." | create-invoice (planned 16F; not yet in `ErrorCode`) |
| `QUOTE_ALREADY_ACCEPTED` | 409 | "This quote has already been accepted." | cancel (defensive until 16F can create accepted versions) |
| `QUOTE_PDF_NOT_FOUND` | 404 | "The requested quote PDF is not available." | quote/pdf; public pdf (missing stored artifact) |
| `CUSTOMER_MOBILE_REQUIRED` | 422 | "A valid customer mobile number is required to send by SMS." | send-sms (dormant) |
| `CUSTOMER_MOBILE_INVALID` | 422 | "Customer mobile number is not valid." | send-sms (dormant) |
| `SMS_SEND_FAILED` | 502 | "The quote could not be sent by SMS. Please try again." | send-sms (dormant) |
| `QUOTE_TOKEN_NOT_FOUND` | 404 | "Quote not found." | all public (unknown or malformed token; no leak) |
| `QUOTE_LINK_EXPIRED` | 410 | "This quote link has expired. Please contact the store." | public viewed/pdf (and accept, 16F) |
| `QUOTE_LINK_SUPERSEDED` | 410 | "This quote has been replaced. Please use the latest quote link." | public viewed/pdf (and accept, 16F) |
| `QUOTE_LINK_CANCELLED` | 410 | "This quote has been cancelled. Please contact the store." | public viewed/pdf (and accept, 16F) |
| `QUOTE_LINK_INACTIVE` | 410 | "This quote link is no longer active. Please contact the store." | public viewed/pdf (and accept, 16F) (token `CONSUMED`/signed) |
| `SIGNATURE_REQUIRED` | 422 | (reuse Phase 13) | public accept (16F) |
| `SIGNATURE_INVALID` | 400 | (reuse Phase 13) | public accept (16F) |

**Reused** existing codes (do not rename/duplicate): `ORDER_LOCKED` (422, LAID write block on draft/send), `ORDER_NOT_FOUND` (404), `CUSTOMER_EMAIL_REQUIRED` / `CUSTOMER_EMAIL_INVALID` (422, send-email), `EMAIL_SEND_FAILED` (502, send-email), `VALIDATION_FAILED` (400, malformed body / bad multipart part / non-empty cancel body).

Clarifications:
- The public GET returns **200 with a `state`** for EXPIRED/SUPERSEDED/CANCELLED/INACTIVE (the customer holds the token, so showing the state message is intended); only an **unknown** token is 404. The **viewed**/PDF actions (and **accept**, 16F) on any non-`ACTIVE` state return the matching **410** (`QUOTE_LINK_EXPIRED` / `_SUPERSEDED` / `_CANCELLED` / `_INACTIVE`).
- `410 Gone` is used for dead links (expired/superseded/cancelled) — add to conventions §4 alongside the Phase 13 `502`.

---

## 13. Implementation notes / risks

- **Migrations:** `V16` (five tables §4), then `V17` (issue-time customer snapshots) and `V18` (reserved slug `q`) in 16E-C. All are outside the CI guard (V1–V13) but, like every committed migration, never edited; they fold into the Phase 17 squash. Any change to the guard itself is deliberate migration/CI work, not a side effect of a feature PR.
- **Quote ≠ invoice.** Do **not** reuse the `invoice` table or invoice endpoints for quote state. The only invoice touchpoint is **Path A** `create-invoice` (reads the accepted quote snapshot, writes a normal accepted invoice).
- **Append-only issued layer.** `quote_version` is append-only; only delivery markers (`last_emailed_at`, `last_sent_at`, `viewed_at`) and the lifecycle `status` mutate on it. Token state lives in `quote_token` (insert per send; status updated, never deleted). The draft layer (`quote_draft`/`_line`) is freely mutable.
- **Transaction boundaries.** On `accept` (16F): store signature + signed PDF in one transaction (file-write-first + rollback cleanup, as Phase 13). The **store notification email is post-commit and non-fatal.** On `send-*`: **persist the issued version + issued PDF + token before delivery. If delivery fails, keep the issued version/PDF/token, return 502, and allow resend. This is locked for MVP** (see the §7.1 send-failure rule and its accepted tradeoff).
- **`invoice_template_key`** exists (V12) but is **dormant** (not read by the invoice PDF assembler/generator today). The quote PDF reuses the Aire Compact template directly; do **not** wire `invoice_template_key` in 16B/16C unless explicitly scoped.
- **Cost discipline.** Quote lines carry no cost; the public surface is cost-free and ID-free; GP/below-cost is computed server-side from the order's product/charge cost lines and surfaced only on the **protected** Quote tab.
- **Token security.** Generate with a CSPRNG; store only the hash; constant-time compare on lookup; per-token + per-IP throttle on public endpoints; lazy-expire on access.
- **Risks:** (a) the original SMS deliverability / AU number-formatting risk no longer applies — SMS is outside the approved delivery scope (§9); (b) ensuring a carried-forward/inherited signature `stored_file` is not deleted while referenced (non-unique FK — same caution as Phase 13); (c) the quote draft is price-independent (a save never writes the order override) and Path A bills the **accepted snapshot**, never the live order price — both must be coded explicitly; (d) public-surface isolation — a token must resolve to exactly one quote and leak nothing else.

---

## Appendix — OpenAPI alignment

`docs/openapi.yaml` is updated additively in the same 16B docs change:
- New tag **`Quotes`**.
- Protected paths: `GET …/quote/workspace` (`getQuoteWorkspace`), `PUT …/quote/draft` (`upsertQuoteDraft`), `POST …/quote/preview-pdf` (`previewQuotePdf`), `POST …/quote/send-email` (`sendQuoteEmail`), `POST …/quote/send-sms` (`sendQuoteSms`), `POST …/quote/cancel` (`cancelQuote`), `POST …/quote/create-invoice` (`createInvoiceFromQuote`), `GET …/quote/pdf` (`downloadQuotePdf`).
- Public paths: `GET /public/quotes/{token}` (`getPublicQuote`), `POST /public/quotes/{token}/viewed` (`markPublicQuoteViewed`), `POST /public/quotes/{token}/accept` (`acceptPublicQuote`), `GET /public/quotes/{token}/pdf` (`downloadPublicQuotePdf`).
- New schemas: `QuoteWorkspace`, `QuoteDraft`, `QuoteDraftLine`, `QuoteIssuedSummary`, `QuoteAcceptedSummary`, `QuoteDraftUpsertRequest`, `QuoteSendResponse`, `PublicQuoteView`, `PublicQuoteState` (enum), `QuoteAcceptRequest` (multipart).
- New shared response `Gone` (410); reuse `BadGateway` (502).
- `error.code` strings remain free-form (OpenAPI does not enumerate codes).
- Later amendments: `QuoteIssuedSummary` gained the frozen body fields (16E-B) and `PublicQuoteView` was extended additively with the public-page document fields (16E-C).
- A path definition is not evidence of an implementation: `acceptPublicQuote` and `createInvoiceFromQuote` are planned 16F with no handler at this base, and `sendQuoteSms` is dormant (§9).