# Sales Portal MVP — API Contracts · Phase 16B (Quotation) (Lock-Ready)

**Status:** Phase 16B quotation contract. Additive to the locked Phase 12 / Chunk 4 invoices + payments contract (`docs/API-Contracts-Chunk-4.md`) and the Phase 13 acceptance/signature/email contract (`docs/API-Contracts-Phase13-Acceptance-Signature-Email.md`). It was written docs/contracts-only, before any quotation code, as the rails for the 16C–16F quotation build, and has since been amended in place (16D-A price decoupling, 16D-B PR2A retained rows, 16D-C PDF wording), reconciled with the implemented 16E state in the post-16E docs pass, amended for 16F PR1 (public acceptance, signed quote PDF, store notification, protected accepted reads, the D5(c) link cancellation on in-app invoice accept, link-only quote email, `V19`, and decision D6b, an explicit amendment to the 16D-A quote/order price separation, section 6), and amended for 16F PR2 (Path A `create-invoice` from the latest accepted quote, the runtime use of the `V19` invoice columns, InvoiceDetail `terms_html` / `terms_source`, and decision D5(b) as amended on 8 October 2026).

**Implementation status (16F PR3: frontend; base `main` @ `917a247`):**
- **Implemented (16C–16E):** draft workspace/PUT, draft preview PDF, `send-email` issue/resend, `cancel`, stored issued PDF (`GET …/quote/pdf?type=issued`), the salesperson Customer Quote view, and the public read-only surface (`GET /public/quotes/{token}`, `POST …/viewed`, `GET …/pdf`) behind the slugless page `/q/{token}`. Migrations `V16`–`V18`.
- **Implemented (16F PR1, backend only):** public `POST /public/quotes/{token}/accept` (signature-only multipart; the accepted name is the issued version's `V17` `customer_name_snapshot` — decision D1); the stored signed quote PDF; token `CONSUMED`; the post-commit store notification to `store.email` (recording-only sender); `workspace.accepted`, `GET …/quote/pdf?type=accepted` and the new `GET …/quote/accepted/signature`; the D6b order sale-price write on acceptance (§6); the D5(c) cancellation of an active issued quote on in-app invoice accept (Phase 13 D.8); link-only quote email (no PDF attachment); migration `V19` (§4.6).
- **Implemented (16F PR2, backend only):** `POST …/quote/create-invoice` (Path A: an invoice version from the latest accepted quote, inheriting its signature; section 7.1) and the new `QUOTE_NOT_ACCEPTED` code; the runtime use of the `V19` invoice columns `source_quote_version_id` and `terms_snapshot` (write table, source-order check and terms rule in section 4.6); InvoiceDetail `terms_html` / `terms_source`; the amended `invoice_eligible` rule (decision D5(b) as amended on 8 October 2026, section 7.1). No new migration.
- **Implemented (16F PR3, frontend only):** the public signing UI on `/q/{token}`, the Accepted Quote view (Preview signed PDF only) and its Create Invoice button, the issued/accepted refresh guards, the send-after-acceptance warning (D9), and the Invoice tab rendering of the InvoiceDetail terms (`terms_html` with its `terms_source`). No backend change and no migration.
- **Dormant (outside the approved delivery roadmap):** `send-sms` and the other SMS artefacts — see §9. Delivery is **email only**.

**Source of truth priority:**
1. `docs/API-Conventions.md`
2. Locked Chunk 1–3 contracts
3. Locked Chunk 4 contract (Phase 12 invoices + payments)
4. Phase 13 contract (invoice acceptance + signature + email)
5. **This Phase 16B contract (additive — quotation)**
6. Locked DB schema (V1–V19: `V16` quote tables below, `V17` issue-time customer snapshots, `V18` reserved slug `q`, `V19` accepted-name widening + invoice quote-source/terms columns — §4.6)

**Order number format (locked):** `{store_code}.{salesperson_code}.{order_sequence_number_padded_5}` — e.g. `SYD-CBD.LC1.00042`. Backend-generated; used in quote PDF / signature file names.

**File-binary exception (conventions §3):** quote PDF download endpoints return raw binary; the public **accept** endpoint (implemented 16F PR1) accepts a `multipart/form-data` signature upload; the public/protected PDF endpoints and the protected accepted-signature GET (16F PR1) stream bytes — same exception Phase 13 used for invoice acceptance and its signature GET.

**Auth-model warning (locked):** the protected quote endpoints are Standard-protected (HttpSession, all conventions §9 checks). The **public** quote endpoints are a **new, separate unauthenticated surface** authenticated **only by the secret token**. They MUST NOT reuse, populate, or weaken the session/`RequestContextGuard` model, and MUST NOT expose any tenant data beyond the single quote behind the token.

---

## 1. Purpose

Phase 16A finished the invoice presentation (Aire Compact PDF). Phase 16B specifies the **quotation feature**: a salesperson builds a customer-facing quote on an order, sends it by email as a secure link, the customer opens it on their phone, signs it remotely, the store is notified, and the salesperson converts the accepted quote into an invoice without the customer re-signing. (16C–16E built everything up to the read-only customer view; 16F PR1 built the remote-signing backend: public accept, signed PDF, store notification and the protected accepted reads; 16F PR2 built the conversion backend (Path A); 16F PR3 built the signing, Accepted Quote and Create Invoice UI.)

A quote is **not** an invoice and must not be hacked into the invoice flow. It has its own tables, its own statuses, its own versioning, and its own public signing surface. It **reuses** the Aire Compact document style (title `QUOTATION`, not `TAX INVOICE`) and the existing `stored_file` / `FileStorageService` / email mechanisms.

Concretely, Phase 16B adds:
- An **editable quote draft** per order (itemised or non-itemised customer-facing lines, distinct from the order's product/charge lines).
- **Issued quote versions** — immutable snapshots created when the quote is sent, each with a stored issued PDF and a secret public link.
- **Remote customer acceptance** *(backend implemented 16F PR1; signing UI implemented 16F PR3)* — the customer signs on their phone via the tokenised link; a signed quote PDF is stored; the order's sale price becomes the accepted total (D6b, §6); the store is notified.
- **Invoice conversion (Path A)** *(backend implemented 16F PR2; the Create Invoice button implemented 16F PR3)*: create an invoice from the accepted quote snapshot, inheriting the signature (no re-sign).
- A **public, token-only** read/sign surface (read-only view + viewed tracking implemented in 16E-C; the `accept` endpoint in 16F PR1; the signing UI in 16F PR3).

Phase 16B is the **planning + contract lock**. 16C builds the backend, 16D the salesperson Quote tab, 16E delivery, 16F remote acceptance + conversion: **16C–16E, 16F PR1 (backend acceptance), 16F PR2 (backend Path A conversion) and 16F PR3 (frontend) are implemented**. **16E and 16F are separate phases**: token-based remote signing is its own security + legal-state surface.

---

## 2. MVP scope (included)

- **One quote per order**, versioned and append-only at the issued layer (mirrors the invoice append-only model).
- **Editable draft** (`quote_draft`, one row per order) with customer-facing **quote lines** (`quote_draft_line`). Itemised mode copies product/charge lines into editable quote lines; the salesperson may edit quote-line description / quantity / unit price / line total and add **adjustment** lines (±). Non-itemised mode shows the quote total + details-of-sale text, no line breakdown and no filler "Quoted Works" section. Itemised draft lines are **retained (dormant)** while the draft is non-itemised — see §6.1 (amended Phase 16D-B PR2A).
- **Quote lines never mutate the order's product/charge lines.** The product/charge lines remain the operational/costing record; quote lines are customer-facing presentation only.
- **Money model (locked):** in **itemised** mode the quote total = the sum of quote lines; in **non-itemised** mode the quote total = `final_total_inc_gst` carried on the draft header, with **no synthetic line** (amended Phase 16D-B PR2A — see §6.1). The quote draft is **independent of the order sale price** — saving a draft or issuing/sending a quote does **not** change the order's sale-price override or header financials; the **one exception** is public acceptance, which writes the accepted `quote_total_inc_gst` as the order's sale-price override (D6b, 16F PR1 — see §6). The **accepted quote snapshot is the legal billing number** (see §6).
- **Total rules (itemised mode only):** reducing the final total directly auto-inserts a negative **adjustment** line to balance; the final total may not be set **above** the line sum (must raise a line or add a positive line, else 422); adjustments are customer-facing. A non-itemised `final_total_inc_gst` is **never** validated against lines (§6.1).
- **GP / below cost:** the draft shows GP warnings while editing (like Details of Sale). **Below cost is blocked at save, send, and public accept** (accept: 16F PR1, with a public-safe message — §7.2) (cannot even save a below-cost draft).
- **Issued version on send:** sending generates and **stores an immutable issued quote PDF**, snapshots the lines + per-flooring-type terms (and, since `V17`, the customer-facing "Quotation To" name + billing lines), mints a secret token, and delivers. Draft **and customer identity unchanged** since the last issue → **resend** the same issued version (its stored PDF unchanged, nothing regenerated) with a **new token** (old token marked `REPLACED`, kept for messaging). Draft **or customer name/billing changed** → new issued version that **supersedes** the previous one (its token marked `SUPERSEDED`) — see §7.1 send-email.
- **Delivery (email only):** **send-email** (public quote link + short body — **link-only, no PDF attachment** since 16F PR1; the issued PDF is still generated and stored, downloadable from the public page while the link is `ACTIVE`, and readable in the portal). Recipient comes from the saved Customer tab record (no send-time override). `send-sms` (link only) exists as **dormant** backend support and is excluded from the approved delivery roadmap (§9).
- **Public link:** top-level, slugless page `/q/{token}` (slug `q` reserved by `V18`) → slugless public API. Token random/secret, **stored hashed**. Link **expires 7 days after send**. **One `ACTIVE` token per order / active issued quote.** Superseded / expired / cancelled / signed (`INACTIVE`) links are blocked with their own message. Public payload is **cost-free, no internal IDs**.
- **Remote acceptance** *(backend implemented 16F PR1; public signing UI implemented 16F PR3)*: the customer signs on their phone (signature only — the accepted name is the issued version's `V17` `customer_name_snapshot`, never the live record and never typed; D1); allowed on a LAID order (D4); the accepted quote snapshot is locked; a **signed quote PDF** is stored; the order's sale-price override is set to the accepted inc-GST total (D6b); the **store is notified by email** at the order's `store.email` (post-commit, non-fatal, no attachment). Once signed, the **public link dies** — it is no longer viewable or signable. The signed quote is available only inside the protected salesperson portal.
- **Invoice conversion, Path A** (`create-invoice`) *(backend implemented 16F PR2, using the `V19` invoice columns; the Accepted Quote Create Invoice button implemented 16F PR3)*: an invoice version built from the **latest accepted quote snapshot** (details, totals and frozen terms) inheriting the **signature** (`accepted_at`, name, the same signature file). Customer does **not** re-sign. Allowed when there is no invoice, the current invoice is unsigned, or the quote was signed strictly later than the current invoice (decision D5(b) as amended on 8 October 2026); see section 7.1.
- **Cancel:** the salesperson can cancel an active issued quote → `CANCELLED`, link dies. A successful in-app invoice accept (Phase 13 D.8) also cancels the active issued version and its token inside the D.8 transaction (16F PR1, D5(c)). One direction only: an accepted quote never blocks D.8 and is never cancelled by it.
- **Quote PDF storage:** draft preview = on-demand (not stored); issued PDF = stored immutable on send; signed PDF = stored immutable on accept (16F PR1) and streamed by `GET …/quote/pdf?type=accepted`. The Accepted Quote tab's **Preview signed PDF** is implemented (16F PR3).
- **Quote terms:** the quote shows the same per-flooring-type terms (`terms_soft` / `terms_hard`) the invoice uses, selected by order flooring type, **frozen into the version snapshot at issue and immutable through acceptance**.

---

## 3. Non-goals / post-MVP (explicitly excluded)

- **Multiple named quotes per order** (e.g. "Option A / Option B" comparison). One quote per order only. Advanced quote comparison stays out.
- **Auto-invoice on acceptance.** Invoice creation from an accepted quote is always a **manual** salesperson action (Create Invoice button).
- **Quote edit after acceptance.** An accepted quote is an immutable signed record. **"Rewrite Quote"** (a new draft copied from the accepted snapshot) is **out of scope** (decision D10). Cancel + new quote is the model: an unsigned issued quote can be cancelled, and the salesperson edits the order's one draft and sends it, which issues a **new version that needs a new signature** (after acceptance this is decision D9 — §5). The accepted record and its signature are never altered or removed.
- **Send-time recipient override**, customer-typed recipient, or multiple recipients.
- **Quote version-history UI / old-token reactivation / old signed-quote list** (parallels the Phase 12/13 current-only invoice model).
- **Payment on a quote.** Payments belong to invoices only.
- **SMS delivery.** Not in the approved roadmap — delivery is email only. The dormant backend SMS support (§9) sits behind a provider-independent interface with no provider configured; no SMS provider is planned.
- **Room-level complexity, installer/laybook, AI.**
- **Reusing the order status enum for quote state** (`LEAD … CANCELLED` are **order** statuses and are unrelated to quote state).

---

## 4. Data model contract

Phase 16B defined **five new tables**, implemented as migration **`V16`** (Phase 16C, next after `V15`). **`V17`** (16E-C) adds the issue-time customer snapshot columns to `quote_version` (§4.3); **`V18`** (16E-C) reserves the business slug `q` for the public page. **`V19`** (16F PR1) widens both accepted-name columns to `TEXT` and adds two nullable invoice columns (schema-only in PR1, used at runtime since 16F PR2) (§4.3, §4.6). Every committed migration is locked by policy (never edited). The CI "Locked migration protection" guard covers **V1–V13 only**, so `V14`–`V19` are outside it; all are folded into the Phase 17 squash/baseline.

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
| `quote_total_inc_gst` | `DECIMAL(10,2)` | no | immutable snapshot; **this is the legal billing number once `status = ACCEPTED`.** It is also the value D6b writes to the order's sale-price override at acceptance (§6). |
| `flooring_type_snapshot` | `VARCHAR(8)` | no | `SOFT`/`HARD` at issue. |
| `terms_snapshot` | `TEXT` | yes | the per-type terms HTML frozen at issue; immutable through acceptance (nullable — terms may be unset). |
| `details_of_sale_snapshot` | `TEXT` | yes | non-itemised body / supporting text. |
| `customer_name_snapshot` | `TEXT` | yes | **`V17`**. "Quotation To" customer name (first [middle] last) frozen at issue; null when none was saved. Since 16F PR1 it is the **only source of `accepted_customer_name`** (D1); a null/blank snapshot at accept → 422 `ACCEPTED_CUSTOMER_NAME_REQUIRED` (§7.2). |
| `customer_address_line1_snapshot` | `TEXT` | yes | **`V17`**. Billing address line 1 (`[unit/]street_number street`) frozen at issue. |
| `customer_address_line2_snapshot` | `TEXT` | yes | **`V17`**. Billing address line 2 (`suburb state postcode`) frozen at issue. |
| `sent_channel` | `VARCHAR(8)` | yes | `EMAIL` / `SMS` of the **latest** send (`SMS` only via the dormant `send-sms`, §9). |
| `first_sent_at` | `TIMESTAMP` | yes | when this version was first issued/sent. |
| `last_sent_at` | `TIMESTAMP` | yes | **latest** send time (updated on every resend). |
| `last_emailed_at` | `TIMESTAMP` | yes | last successful email of this version (mutable delivery marker). |
| `viewed_at` | `TIMESTAMP` | yes | first public view — stamped write-once by the public `viewed` POST (16E-C); the public GET never stamps it. |
| `accepted_at` | `TIMESTAMP` | yes | set at public acceptance (16F PR1). |
| `accepted_customer_name` | `TEXT` (`V19`; `VARCHAR(150)` in `V16`) | yes | copied at public accept from this version's `customer_name_snapshot`, trimmed (D1) — never the live `order_customer`, never typed, never truncated. |
| `accepted_signature_file_id` | `BIGINT` | yes | FK → `stored_file`; **not unique**; set at public accept (16F PR1). A Path A invoice (16F PR2) references this same file (D6: no new file). |
| `issued_pdf_file_id` | `BIGINT` | yes | FK → `stored_file` (UNIQUE); the immutable issued PDF. |
| `signed_pdf_file_id` | `BIGINT` | yes | FK → `stored_file` (UNIQUE); the immutable signed PDF, set at public accept (16F PR1); streamed by `GET …/quote/pdf?type=accepted`. |
| `created_by_user_id` | `BIGINT` | no | FK → `app_user`. |
| `created_at` | `TIMESTAMP` | no | |

**Invariant:** at most **one** `quote_version` per order in `status = ISSUED` (the active, signable version). Sending a changed draft inserts a new `ISSUED` row and flips the prior `ISSUED` row to `SUPERSEDED`. **`ACCEPTED` versions are historical and immutable; more than one may exist** (e.g. accept V1, later edit the draft and send, then issue and accept V2; D9). The **latest** `ACCEPTED` version (highest `version_number` among `ACCEPTED`) is the one returned as `workspace.accepted` (shown in the Accepted Quote tab since 16F PR3) and the only one Path A (16F PR2) converts. A newer draft or a newer `ISSUED`, `SUPERSEDED`, `CANCELLED` or `EXPIRED` version never replaces it as the Path A source. Whether it can be converted follows the strict signature-precedence rule (`invoice_eligible`, section 7.1). Older accepted versions remain as signed history.

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
| `ACTIVE` (and not past `expires_at`) | live link | `ACTIVE` — viewable and signable (public `accept`, 16F PR1) |
| `REPLACED` | resend of the same version minted a newer token | `SUPERSEDED` — "This quote has been replaced. Please use the latest quote link." |
| `SUPERSEDED` | a newer issued version replaced this token's version | `SUPERSEDED` — same message |
| `EXPIRED` | past `expires_at` (lazy-flipped on access) | `EXPIRED` — "This quote link has expired. Please contact the store." |
| `CANCELLED` | quote cancelled by the salesperson, or by a successful in-app invoice accept (Phase 13 D.8 — D5(c), 16F PR1) | `CANCELLED` — "This quote has been cancelled. Please contact the store." |
| `CONSUMED` | the quote was signed via this token (public `accept` — reachable since 16F PR1) | `INACTIVE` — "This quote link is no longer active. Please contact the store." (not viewable, not signable) |

`quote_version.status` remains the authoritative **quote** state; `quote_token.status` drives the **link message**. When a version flips to `SUPERSEDED`/`CANCELLED`/`ACCEPTED`, its `ACTIVE` token is updated to `SUPERSEDED`/`CANCELLED`/`CONSUMED` respectively; a resend marks the old token `REPLACED` and inserts a new `ACTIVE` token.

**Signature & PDFs** reuse `stored_file` + `FileStorageService` (internal `storage_path` under `/uploads/{businessId}/orders/{orderId}/{uuid}.{ext}`, **never** exposed). The signature is **not** an `order_attachment` and never appears in Notes & Photos.

**Customer contact** comes from `order_customer` (`email`; the existing customer **mobile** field is used only by the dormant `send-sms`) — one row per order. No quote-specific recipient is stored.

**Customer identity snapshot (`V17`, 16E-C).** The customer-facing "Quotation To" block — name + billing address lines — is frozen onto `quote_version` at issue, using the same derivation as the issued PDF, so the stored PDF and the snapshot columns agree. The public payload reads these snapshot columns and never the live `order_customer` / `order_address` rows, so a customer edit after issue cannot reach an already-issued link. The `V17` migration backfilled pre-existing (dev-only) versions from the then-current customer rows. Since 16F PR1 the accepted name is copied from `customer_name_snapshot` (D1), and the signed PDF renders its "Quotation To" block from these snapshot columns, never the live rows.

### 4.6 `V19` (16F PR1) — accepted-name widening + invoice quote-source columns

- `quote_version.accepted_customer_name` and `invoice.accepted_customer_name` → `TEXT` (were `VARCHAR(150)`; nullability unchanged; no data rewrite or backfill). An accepted name is never truncated. Phase 13 D.8's own 150-character request validation of `accepted_customer_name` is unchanged.
- `invoice.source_quote_version_id BIGINT NULL` — FK `fk_invoice_source_quote_version` → `quote_version(quote_version_id)`, **non-unique** (payment/void versions of a Path A invoice carry it forward), with a plain, non-unique index `idx_invoice_source_quote_version`.
- `invoice.terms_snapshot TEXT NULL`.
- **Runtime use (16F PR2).** PR1 added the two invoice columns schema-only. Since 16F PR2 every invoice write sets them by this table (no new migration; `V19` is unchanged):

  | Writer | `source_quote_version_id` | `terms_snapshot` |
  |---|---|---|
  | Path A `create-invoice` (16F PR2) | the selected accepted quote version | that version's frozen `terms_snapshot`, verbatim (including null) |
  | D.1 Create | null | null |
  | D.2 manual Rewrite | null (cleared) | null (cleared); acceptance and signature are cleared as before |
  | D.7 payment and D.10 payment void | carried verbatim from the current invoice | carried verbatim from the current invoice |
  | D.8 in-app accept | carried verbatim from the current invoice (defensive) | carried verbatim from the current invoice (defensive) |

- **Source ownership (enforced in code, 16F PR2).** The `V19` foreign key is single-column, so the database alone does not tie a source quote version to the invoice's own order. Before every invoice insert with a non-null `source_quote_version_id`, the backend verifies at the one shared insert boundary (so Path A, payment, void and D.8 all pass through it) that the referenced version exists and belongs to the invoice's order. A missing or cross-order source is an internal error (500, `INTERNAL_SERVER_ERROR`): the whole transaction rolls back and no invoice row is written. No composite foreign key or new migration was added. The source id is never exposed by the API.
- **Terms rule (decision D7, implemented 16F PR2).** `source_quote_version_id` present: the invoice uses `invoice.terms_snapshot` verbatim, **even when it is null** (frozen "no terms": no terms page, even when the business has live terms). Absent: the live per-flooring-type terms through the existing sanitiser (Path B, unchanged). Source presence, never whether the HTML is null or blank, selects the branch. The frozen terms are never trimmed, re-sanitised, re-frozen or replaced from the source quote's current row (the quote snapshot was sanitised once at issue). The same rule drives the invoice PDF (Path A, payment, void and D.8 renders) and InvoiceDetail `terms_html` (nullable) + `terms_source` (`QUOTE` / `LIVE`), which are always present on every InvoiceDetail response. The payment and void responses (`CurrentInvoiceSummary`) are unchanged; clients re-read the current invoice for the terms. The Invoice tab renders these fields (16F PR3): the terms from `terms_html` only, and a terms-source line from `terms_source`. Freezing Path B terms and Invoice To across payment and void is a separate follow-up; Path B stays live.

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
| ISSUED → ISSUED (resend) | `send-email` (or the dormant `send-sms`), draft **and customer identity unchanged** | same version re-sent with a new link (link-only email since 16F PR1; its stored issued PDF is unchanged and still served — nothing regenerated); new `ACTIVE` token minted, old token → `REPLACED`; `last_sent_at` updated. `last_emailed_at` is updated **only on a successful email send** (an SMS resend updates `last_sent_at` only, never `last_emailed_at`). |
| ISSUED → ACCEPTED | public `accept` (16F PR1; allowed when LAID — D4) | one transaction under the order row lock: signature + **signed PDF** stored; `accepted_at` + `accepted_customer_name` (= the `V17` snapshot) set; snapshot locked as the legal number; token → `CONSUMED`; order sale-price override := accepted `quote_total_inc_gst` (D6b). Post-commit: notify the store (non-fatal) |
| ISSUED → CANCELLED | `cancel`, or a successful in-app invoice accept (D.8, inside its transaction — D5(c), 16F PR1) | active issued version cancelled; token → `CANCELLED`. One direction only: an accepted quote never blocks D.8 and is never cancelled by it |
| ISSUED → EXPIRED | an **`ACTIVE`** token passes `expires_at` | lazily on the next public hit (16E-C; no background job exists), including the locked re-read of a public `accept` (16F PR1; the flip commits before its 410): token → `EXPIRED`, version → `EXPIRED`. Only an `ACTIVE` token on an `ISSUED` quote can expire; `CONSUMED`/`SUPERSEDED`/`REPLACED`/`CANCELLED` tokens never expire later, and `ACCEPTED` versions never become `EXPIRED`. |
| ACCEPTED → (new ISSUED) | `send-email` after acceptance (D9; with no `ISSUED` version every send issues one — existing backend behaviour) | new version `max(version_number) + 1` from the current draft, needing a new signature; the `ACCEPTED` version is untouched and keeps its signature; no order-price write. "Rewrite Quote" (a draft copied from the accepted snapshot) is **out of scope** (D10). The frontend warning before such a send is implemented (16F PR3) |

A draft may coexist with an accepted version. Signing the active issued version sets `ACCEPTED`; an unsent draft beside it remains draft work (unsent/unaccepted). Acceptance never reads or writes the draft.

---

## 6. Money / billing model (locked — the core of 16B)

- **Itemised mode: quote total = sum of quote lines** (ex-GST lines; inc-GST total derived via the existing invoice GST model). The system enforces `quote_total_ex_gst == Σ line_total_ex_gst` on every **itemised** save and issue. The invariant is **mode-scoped** (amended Phase 16D-B PR2A): it does **not** apply to a non-itemised draft, whose totals are independent of any retained dormant lines (§6.1).
- **Direct total reduction (itemised):** if the salesperson lowers the final total below the line sum, the backend inserts a negative `ADJUSTMENT` line equal to the difference so the invariant holds. The adjustment is customer-facing.
- **Direct total increase above line sum (itemised saves only):** rejected — 422 `QUOTE_TOTAL_EXCEEDS_LINES`. The salesperson must raise an existing line or add a positive line/adjustment. This check never applies to a non-itemised save.
- **Quote/order price separation (locked; amended 16F PR1 — D6b):** saving a draft and issuing/sending a quote (also resend, cancel and preview) do **not** change the order's sale-price override or header financials. The quote draft total is a customer-facing figure that may differ from the order's working price; quote lines never mutate Products & Charges, and the order price is driven by Products & Charges plus the sale-price override. (Decoupled in Phase 16D-A; supersedes the earlier "cosmetic override coupling.")
  - **D6b amendment — the one quote → order price write (16F PR1):** a signed quote is the final price. A successful public `accept` applies the signed inc-GST total as the order's **standard sale-price override**, inside the acceptance transaction: `price_adjustment_inc_gst` = accepted `quote_total_inc_gst` − the calculated line total at acceptance (`calculated_total_inc_gst` of the persisted product + charge lines, read under the order lock; HALF_UP 2dp). It **replaces** any existing adjustment, so at that moment the order's final sale price inc GST equals the accepted total. `sale_price_ex_gst`, `total_cost`, `gp` and `gp_percent` are recomputed and persisted (`gp_percent` is stored null when not computable or outside `DECIMAL(5,2)` — the manual-override rule). It is allowed when the order is LAID (D4): it is an internal write of the acceptance, and the protected sale-price override/reset endpoints keep their LAID gate. A result that cannot be persisted (a value outside `DECIMAL(10,2)`, or a negative total cost) → 422 `BUSINESS_RULE_VIOLATION` with the public-safe message, nothing written (§7.2).
  - **Afterwards it is an ordinary override.** The stored adjustment is fixed, so later Products & Charges edits (only possible while the order is not LAID) shift the working price relative to the signed total; the manual override and reset in Details of Sale remain the correction tools. The accepted snapshot never changes, and Path A (PR2) bills that frozen accepted snapshot, never the working order price. Nothing else on the order changes at acceptance: draft, product/charge lines, order status, invoices and payments are untouched.
- **Legal billing number (snapshot):** the **accepted `quote_version` snapshot** (`quote_total_inc_gst` at `ACCEPTED`) is the legal billing number. It is **not** the live override: D6b copies it into the override at acceptance, but later order-price changes never alter the accepted snapshot, and Path A bills the snapshot.
- **Invoice conversion uses the snapshot, not the live override:**
  - **Path A** *(backend implemented 16F PR2)*: `create-invoice` from the latest accepted quote. The invoice's details of sale and ex/inc sale prices are the **accepted quote snapshot**, copied verbatim (never the live order price), the terms are the quote's **frozen `terms_snapshot`** (stored as `invoice.terms_snapshot` with `invoice.source_quote_version_id` set; `V19`, section 4.6), and the signature/acceptance is **inherited** (no re-sign). Details in section 7.1.
  - **Path B** *(existing)* — the Details-of-Sale → Create Invoice flow (Phase 12/13): uses the **live order sale price / override** (after a public acceptance this starts at the accepted total — D6b), never reads the quote's totals, lines or terms, and inherits **no** quote signature; the customer signs the invoice normally. A successful in-app accept (D.8) cancels any active issued quote (D5(c)); an accepted quote never blocks D.8. If a salesperson overrides the price in Details of Sale and creates an invoice, the quote is bypassed and its number does not apply.
- **Below cost** (GP negative / sale ex-GST below total cost ex-GST, using the order's product/charge cost lines): blocked at **save** (`PUT draft`), **send** (`send-email`, re-checked against the live cost lines before anything is persisted; also the dormant `send-sms`), and **accept** (public `accept`, 16F PR1) → 422 `QUOTE_BELOW_COST`. At accept, the issued version's frozen ex-GST total is re-checked against the live cost lines under the order lock; this is reachable, because cost-line changes after issue never supersede the version. The public message is "This quote can no longer be accepted online. Please contact the store." (D11) and nothing is persisted. Warning-range GP is allowed (draft surfaces the warning like Details of Sale). Applies to **both modes**: itemised uses the visible line sum; non-itemised uses the ex total derived from `final_total_inc_gst` (§6.1) — never the retained dormant lines.

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

**Endpoint implementation status (after 16F PR2):** implemented: `workspace` (incl. `accepted`, 16F PR1; its `invoice_eligible` rule amended in 16F PR2), `draft`, `preview-pdf`, `send-email` (link-only since 16F PR1), `cancel`, `pdf?type=issued|accepted` (`accepted` since 16F PR1), `accepted/signature` (16F PR1), `create-invoice` (16F PR2, Path A), public `GET`, `viewed`, `accept` (16F PR1) and `pdf`. Dormant: `send-sms` (implemented but outside the approved roadmap; never called by the frontend).

### 7.1 Protected — salesperson

#### GET `/api/v1/{slug}/orders/{orderId}/quote/workspace`
Loads the full quote state for the Quote tab.
- **200** `{ data: { draft, current_issued, accepted }, message }` where:
  - `draft` — editable draft + lines + GP/below-cost flags (null if none yet). For a **non-itemised** draft, `lines[]` contains the **retained dormant** itemised rows (empty when none — §6.1); totals are the final-total-derived values, independent of those rows.
  - `current_issued` — the active `ISSUED` version summary (status, first_sent_at, last_sent_at, viewed_at, active-token `expires_at`, channel, **no token**; since 16E-B also the frozen body — ex total, details of sale, snapshot lines), or null (null after an acceptance until a new version is sent).
  - `accepted` — the **latest** `ACCEPTED` version summary (`QuoteAcceptedSummary`, 16F PR1), or null; selected independently of `draft` and `current_issued`. All keys are always present; it never carries a file id, storage path, token, hash, cost or GP, and older accepted versions (signed history) are not surfaced here:
    - frozen body: `quote_version_id`, `version_number`, `quote_total_ex_gst`, `quote_total_inc_gst`, `itemised`, `flooring_type`, `details_of_sale` (nullable), `lines` (the `current_issued` line shape; always `[]` for a non-itemised version);
    - acceptance: `accepted_at`, `accepted_customer_name` (the `V17` snapshot — D1), `accepted_signature_present`, `signature_download_path` (`/api/v1/{slug}/orders/{orderId}/quote/accepted/signature`, a protected path consumed verbatim; null when no signature is stored), `signed_pdf_available`;
    - `invoice_eligible`: the strict signature-precedence rule that `create-invoice` enforces (decision D5(b) as amended on 8 October 2026; both use one shared predicate). **True** when the order has no invoice, when its current invoice (highest `version_number`) is unsigned, or when this quote's `accepted_at` is **strictly later** than the current invoice's `accepted_at`. **False** when the current invoice was signed at the same time or later than this quote. Signature times are compared, never invoice creation times, version numbers or email times (a payment- or void-created version keeps its original signature time). The flag reflects this rule only: LAID, a draft or a newer issued quote, missing invoice preconditions, a blank frozen details of sale, a zero total or overpayment never make it false; `create-invoice` validates those itself. It is computed inside the same one-snapshot read.
- **One snapshot (16F PR1):** the read runs in a single read-only `REPEATABLE_READ` transaction (no row locks; it never blocks a writer), so `draft`, `current_issued`, `accepted` and `invoice_eligible` all come from one consistent snapshot — the same version never appears as both `current_issued` and `accepted`, even while a public accept, send, cancel or in-app invoice acceptance commits.
- Cost-free? No — this is the **protected** salesperson view; GP/cost-derived flags are allowed here (never the public surface).
- **LAID:** read allowed.

#### PUT `/api/v1/{slug}/orders/{orderId}/quote/draft`
Upsert the editable draft (itemised flag, lines, adjustments). An **itemised** save full-replaces the draft lines (like the workspace autosave pattern); a **non-itemised** save is header-only — it updates mode/totals, writes **no** lines (no synthetic line), and **retains** previously saved itemised rows untouched (§6.1).
- Server recomputes totals; on an itemised save it enforces the line/total invariant (§6) and applies auto-adjustment on direct reduction.
- **Below-cost → 422 `QUOTE_BELOW_COST`** (blocked at save; both modes — itemised uses the line sum, non-itemised the final-total-derived ex total).
- **Total > line sum → 422 `QUOTE_TOTAL_EXCEEDS_LINES`** (itemised saves only; never checked against retained dormant lines).
- Does **not** update the order sale-price override or any `sales_order` header financials (quote draft is price-independent; decoupled in Phase 16D-A; only a public acceptance writes the override — D6b, §6).
- **LAID → 422 `ORDER_LOCKED`** (write blocked).
- **200** updated `draft`.

#### POST `/api/v1/{slug}/orders/{orderId}/quote/preview-pdf`
On-demand **draft** preview PDF. **Not stored.**
- **200** `application/pdf`, `Content-Disposition: inline; filename="quote-preview-{order_number}.pdf"`.
- Aire Compact style, title `QUOTATION`, shows draft lines/total, selected per-type terms, the display-only 40% deposit line, and the printable declaration/customer acceptance area.
- **LAID:** allowed (read-only render). Below-cost does **not** block a draft *preview* (only save/send/accept block).

#### POST `/api/v1/{slug}/orders/{orderId}/quote/send-email`
Issue (or resend) the quote and email it. Since 16F PR1 the email is **link-only**: the public quote link (`{app.public-base-url}/q/{token}`, exactly once) plus a short body, **no PDF attachment**. The issued PDF is still generated and stored on a new issue, and is served by the public page (while the link is `ACTIVE`) and by `pdf?type=issued`. The backend `accept` exists since 16F PR1 and the page's signing UI since 16F PR3.
- **Changed-detection (identity-aware, content only — never timestamps):** the draft is compared with the active `ISSUED` version — itemised flag, totals, order flooring type, order details-of-sale text, the ordered lines (itemised drafts only; dormant rows on a non-itemised draft are ignored), and the current customer name + billing lines against the `V17` snapshot columns. Tenant terms/config are **not** compared.
- **Changed** (or no active issued version) → new `ISSUED` version (supersede prior): snapshot lines + per-type terms + customer identity, **store a new immutable issued PDF**, prior token → `SUPERSEDED`. Editing the customer's name or billing address after issue therefore produces a new version, so the old artifact is never re-sent to the new identity.
- **After acceptance** (no `ISSUED` version exists) → a send always issues a **new version** (`max(version_number) + 1`) from the current draft, needing a new signature (D9); the `ACCEPTED` version is untouched and the order price is not written.
- **Unchanged** → **resend**: the same version is re-sent with a new link (no new snapshot, nothing regenerated; the link serves the same stored issued PDF); prior token → `REPLACED`. A pure resend never re-freezes terms or business settings — that happens only when a new version is issued.
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

> Both send endpoints apply the identical version/resend rule (draft or customer identity changed = new issued version; unchanged = resend same version with a new token). They differ only in channel, recipient and the email-only `last_emailed_at` marker; both are link-only since 16F PR1.

> **Send-failure rule (locked, MVP).** The issued version + issued PDF + token are persisted **before** the delivery attempt. If email/SMS delivery then fails, the backend **keeps** the issued version/PDF/token, returns **502**, and the salesperson **resends** (no new version on a pure resend). **Accepted tradeoff:** if the failed send was for a *changed* draft (a new issued version), the previous version/link is already `SUPERSEDED` even though the new delivery failed — the customer's old link is dead and they must receive the new (resent) link. This is accepted for MVP.

#### POST `/api/v1/{slug}/orders/{orderId}/quote/cancel`
Cancel the active issued quote.
- Requires an active `ISSUED` version. If there is none and an `ACCEPTED` version exists → 409 `QUOTE_ALREADY_ACCEPTED` (reachable since 16F PR1; an accepted version is never cancelled); otherwise 422 `QUOTE_NOT_ISSUED`. With an accepted v1 and a newer issued v2, cancel cancels v2 only.
- Sets version `CANCELLED`; marks the active token `CANCELLED` (kept for messaging; link dead).
- A successful in-app invoice accept (D.8) applies the same version/token cancellation inside the D.8 transaction (D5(c), 16F PR1). It does not call this endpoint: with no `ISSUED` version it is a silent no-op, never a 409/422.
- **LAID:** create/edit/send are blocked when LAID; **cancel is allowed** as a narrow exception — its only effect is to kill an active public quote link (not an order mutation). Empty body.
- **200** cancelled version summary.

#### POST `/api/v1/{slug}/orders/{orderId}/quote/create-invoice`  *(Path A)*
> **Implemented 16F PR2 (backend).** The Accepted Quote **Create Invoice** button that calls it is implemented in 16F PR3.

Create an invoice version from the **latest accepted** quote. `QuoteController` delegates to the invoice service, which persists it with the established invoice pattern. A quote is **not mandatory** for invoicing generally (Path B always exists).
- **Request:** an empty body only (`{}` or no body). Any field, including a quote-version selector, a price, terms, a signature id or a due date, gives 400 `VALIDATION_FAILED` (one "Not allowed." detail per field); a non-object body gives 400 `VALIDATION_FAILED` on `body`; unparseable JSON gives 400 `MALFORMED_JSON`. The server selects the quote version.
- **Order of checks.** After the standard guard and the `orderId` parse, the scoped order lookup takes the order row lock, which is held through every later check, the PDF and file work, the invoice insert and the mirror reset; every price, payment, version and quote input is read after it.
  1. Standard protected guard; a positive integer `orderId` (else 400 `VALIDATION_FAILED` on `order_id`); the order scoped to the session business and store and locked (missing or out of scope: 404 `ORDER_NOT_FOUND`, no existence leak).
  2. Empty-body validation (above).
  3. The **latest `ACCEPTED`** version: highest `version_number` where `status = ACCEPTED` for the order. None: 422 `QUOTE_NOT_ACCEPTED` ("This quote has not been accepted yet."; use Path B instead). A newer draft or a newer `ISSUED`, `SUPERSEDED`, `CANCELLED` or `EXPIRED` version never replaces it.
  4. **Signature precedence** against the current invoice (highest `version_number`), decision D5(b) as amended on 8 October 2026:

     | Current invoice | Result | New version |
     |---|---|---|
     | None | allowed | 1 |
     | Unsigned (`accepted_at` null) | allowed | current + 1 |
     | Signed, and the quote's `accepted_at` is strictly later than the invoice's `accepted_at` | allowed | current + 1 |
     | Signed, and the quote's `accepted_at` is equal to or earlier than the invoice's | 409 `INVOICE_ALREADY_ACCEPTED`, message "The current invoice was signed at the same time or later than this quote. A newer signed quote is required to create an invoice from a quote." | nothing appended |

     Signature timestamps are compared strictly; never invoice creation times, version numbers or email times. A payment- or void-created version keeps its original signature time, so creating it later never makes its signature newer. Equality is a refusal, so the same signed quote cannot be converted again while the current invoice still carries its signature (the conversion copies the quote's `accepted_at`, and payment and void versions keep it). A manual Rewrite (D.2) clears the acceptance, after which the current invoice is unsigned and the same accepted quote is convertible again (the unsigned row). The earlier signed invoice, quote and signature are never revoked or changed; earlier versions stay in history. D.8's own 409 message is unchanged.
  5. **Preconditions**, all collected into one 422 `INVOICE_PRECONDITIONS_NOT_MET` with the existing `{section, field, message}` details:
     - retained from Path B, with the same details and messages: customer first name and last name present and non-blank (`customer` / `first_name`, `last_name`); installation and billing address rows exist (`address` / `installation_address`, `billing_address`); proposed lay date present and lay date status present and non-blank (`details` / `proposed_lay_date`, `lay_date_status`);
     - replaced by the signed snapshot: the accepted quote's frozen details of sale must be present and non-blank (`details` / `details_of_sale`, "Details of sale on the accepted quote is required."; never filled from the live order); each signed total must be greater than zero, checked independently (`financial` / `sale_price_ex_gst`, "The accepted quote total (ex GST) must be greater than zero."; `financial` / `sale_price_inc_gst`, "The accepted quote total (inc GST) must be greater than zero.");
     - not required: live priced lines, a positive live order price, the live Details of Sale, or a customer email (**no email gate**).
  6. **Overpayment:** active (non-voided) payments above the signed inc-GST total give 422 `BUSINESS_RULE_VIOLATION` with "Recorded payments exceed the accepted quote total. Void the excess payments before creating an invoice from this quote." Nothing is written. Payments exactly equal to the signed total are allowed (zero balance).
  7. Read the inherited signature, then persist.
- **LAID:** allowed in every permitted branch (no invoice, unsigned invoice, strictly newer quote signature), following D4 and the D.1 rule; the D.2 manual-rewrite LAID block does not apply. The refusals above still apply on a LAID order.
- **Field mapping** (the signed snapshot only; the live sale content is never used and the signed totals are never recomputed):

  | Invoice / PDF value | Source |
  |---|---|
  | `details_of_sale_snapshot` | the accepted version's `details_of_sale_snapshot`, verbatim |
  | `sale_price_ex_gst` | the accepted version's `quote_total_ex_gst`, verbatim |
  | `sale_price_inc_gst` | the accepted version's `quote_total_inc_gst`, verbatim |
  | `terms_snapshot` | the accepted version's `terms_snapshot`, verbatim, including null (sanitised once at issue) |
  | `source_quote_version_id` | the selected accepted version's id |
  | `accepted_at` | the accepted version's stored `accepted_at` (not the conversion time) |
  | `accepted_customer_name` | the accepted version's stored name, verbatim (no re-derivation, truncation or D.8 length gate) |
  | `accepted_signature_file_id` | the accepted version's signature `stored_file` (the same row and file) |
  | `invoice_date` | the conversion date |
  | `due_date` | the order's current `proposed_lay_date` minus 2 calendar days |
  | Invoice To name and billing lines | the current saved customer and billing address rows (existing derivation) |
  | `created_by_user_id` | the session user |
  | `total_paid` | the sum of active, non-voided payments for the order |
  | `balance_due` | the signed inc-GST total minus active payments |
  | `last_emailed_at` | null |

  The invoice stays details plus totals, also for an itemised quote: no invoice lines, and nothing is copied into Products & Charges.
- **Inherited signature:** the invoice references the accepted version's own signature `stored_file`; there is no upload, copy, re-normalisation or new signature file. Incomplete acceptance metadata, a missing signature row or an unreadable file is a 500 with nothing written, never an unsigned invoice.
- **Persistence:** the new signed invoice PDF (`invoice-{order_number}-v{version_number}.pdf`: Aire Compact layout with the inherited signature, name and time, the frozen details, totals and terms, and the live Invoice To and business presentation) is written first, then its `stored_file` row, the invoice row (both `V19` columns set, source ownership verified; section 4.6) and `sales_order.last_emailed_at` reset to null, the dashboard mirror. Any render, storage, insert or commit failure rolls back every write and deletes only the newly written invoice PDF; the inherited signature and existing PDFs are never deleted.
- **Not touched:** order status, the working sale price and override, GP, cost and header financials, product and charge lines, the quote draft, versions and tokens (an `ISSUED` version and its `ACTIVE` token stay live; only a successful D.8 cancels a link, D5(c)), customer and address rows, and payments. The D6b price write happened at acceptance and is not rerun. **No email** is sent and no store notification.
- **201** `{ "data": { "invoice": InvoiceDetail }, "message": "Invoice created from accepted quote." }`: the Phase 13 E.2 shape, accepted, with `terms_source: "QUOTE"` and `terms_html` = the frozen terms (or null).

#### GET `/api/v1/{slug}/orders/{orderId}/quote/pdf?type=issued|accepted`
Salesperson download of a **stored** quote PDF (no customer token needed).
- `type=issued` → the active issued version's stored PDF; `type=accepted` → the **latest** `ACCEPTED` version's stored signed PDF (16F PR1; portal-only), streamed verbatim.
- Missing requested artifact → 404 `QUOTE_PDF_NOT_FOUND` (`issued`: no active issued version; `accepted`: no accepted version or no stored signed PDF).
- **200** `application/pdf`, `inline; filename="quote-{order_number}-v{version_number}.pdf"` (issued) or `"quote-{order_number}-v{version_number}-signed.pdf"` (accepted). **LAID:** read allowed.

#### GET `/api/v1/{slug}/orders/{orderId}/quote/accepted/signature`
*(Implemented 16F PR1.)* Stream the **latest** `ACCEPTED` version's stored signature image — the quote analogue of the Phase 13 §5.3 invoice signature GET (file-binary exception).
- **200** `image/png`, `Content-Disposition: inline; filename="quote-signature-{order_number}-v{version_number}.png"`.
- The bytes are the **stored server-normalised PNG** (§7.2), streamed verbatim: the same dimensions and pixels as the customer's upload, but not byte-identical to it (ancillary chunks and trailing bytes were dropped at acceptance).
- Out-of-scope order → 404 `ORDER_NOT_FOUND` (scoped first, no leak). No accepted version, or no stored signature → 404 `QUOTE_SIGNATURE_NOT_FOUND`. A referenced file missing on disk → 500 (standard JSON error, the invoice-signature posture).
- Clients reach it through `accepted.signature_download_path`, used verbatim. File ids and storage paths are never exposed.
- **LAID:** read allowed.

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
  - `ACTIVE` — viewable and signable via `accept` (16F PR1; the page's signing UI since 16F PR3).
  - `EXPIRED` — show "This quote link has expired. Please contact the store." (payload minimal).
  - `SUPERSEDED` — show "This quote has been replaced. Please use the latest quote link." (from a `REPLACED` or `SUPERSEDED` token).
  - `CANCELLED` — show "This quote has been cancelled. Please contact the store."
  - `INACTIVE` — the quote was signed via this link (token `CONSUMED`); show "This quote link is no longer active. Please contact the store." **Not** a viewable signed copy — the signed quote is portal-only.
- Rate-limited (see §8 — not yet implemented). Lazily flips **an `ACTIVE` token** past `expires_at` to `EXPIRED` (and its `ISSUED` version to `EXPIRED`) on access. Tokens already `CONSUMED`/`SUPERSEDED`/`REPLACED`/`CANCELLED`, and `ACCEPTED` versions, are never expired by this check.

#### POST `/api/v1/public/quotes/{token}/viewed`
Mark first view (`viewed_at`). *(Implemented 16E-C.)* Write-once and idempotent (later calls are no-ops; no view count); only valid while `ACTIVE` — a dead link returns the matching 410, an unknown token 404. Empty body. **200** with the refreshed view. The public page calls it after a successful `ACTIVE` GET; the salesperson's Customer Quote shows **Opened** from its next workspace load.

#### POST `/api/v1/public/quotes/{token}/accept`
> **Implemented 16F PR1 (backend); the public page's signing UI is implemented in 16F PR3.** No session and no slug: the token is the only credential.

Customer signs remotely.
- **`multipart/form-data`** (file-binary exception) with exactly **one** part, `signature`:
  - **`image/png` only** (declared type), non-empty, **≤ 2 MB** (2,097,152 bytes is allowed) — mirrors Phase 13 D.8;
  - stricter than D.8 — the bytes must be a PNG that decodes safely: they start with the 8-byte PNG signature, the first chunk is a 13-byte `IHDR`, the `IHDR` (checked before any pixel is decoded) is within the **safe-decode bounds** — **≤ 8,192 px per side**, **≤ 4,000,000 px** in total, **bit depth ≤ 8** (16-bit PNGs are rejected) and a valid PNG colour type — and the JDK PNG reader decodes it; otherwise 400 `SIGNATURE_INVALID`. (Typical DPR-scaled signature pads stay well inside these bounds, but an uncapped canvas at extreme browser zoom can exceed 4,000,000 px — the public signing page (16F PR3) caps its PNG export to these bounds.);
  - **server normalisation:** the decoded pixels are re-encoded as a clean PNG, and only those bytes are stored (`stored_file` + file) and embedded in the signed PDF. The stored signature has the **same dimensions and pixels** as the upload but is not byte-identical to it: ancillary chunks and any bytes after `IEND` are dropped. The protected signature download (§7.1) returns this stored normalised PNG, never the original upload;
  - any other file part, **any** form field or request parameter (e.g. `accepted_customer_name`, declaration flags, money values), or a second `signature` part → 400 `VALIDATION_FAILED` (one detail per offending part: "Not allowed." / "Must appear at most once.").
- **Accepted name (decision D1):** `accepted_customer_name` is taken **server-side from this version's `V17` `customer_name_snapshot`** (trimmed) — never the live `order_customer`, never typed or edited by the customer, never truncated (`TEXT` since `V19`; no 150-character limit). A null/blank snapshot → 422 `ACCEPTED_CUSTOMER_NAME_REQUIRED` with the public-safe message below.
- The declaration checkboxes are a frontend-only gate (D8): nothing is sent or stored for them; the signed PDF renders them ticked.
- **Order of checks** (no 4xx persists anything except an expiry flip):
  1. **Token gate**, **before** the body is validated (a dead link with a bad body still gets its 410): shape + hash lookup (unknown or malformed → 404 `QUOTE_TOKEN_NOT_FOUND`), then state, including lazy expiry (the flip commits before its 410). State must be `ACTIVE`, else:
     - `EXPIRED` → 410 `QUOTE_LINK_EXPIRED`
     - `SUPERSEDED` → 410 `QUOTE_LINK_SUPERSEDED`
     - `CANCELLED` → 410 `QUOTE_LINK_CANCELLED`
     - already signed (`CONSUMED`) → 410 `QUOTE_LINK_INACTIVE`
  2. **Body validation** (no database access, no lock): 400 `VALIDATION_FAILED` (parts); missing/empty `signature` → 422 `SIGNATURE_REQUIRED` with the quote wording "A signature is required to accept this quote."; wrong declared type, over 2 MB, or not a PNG within the safe-decode bounds that the PNG reader decodes → 400 `SIGNATURE_INVALID`. (Transport-level rejections happen earlier, in the framework, before the token gate — unchanged global behaviour: a request over the global multipart limit → the existing 400 `FILE_TOO_LARGE`; a request that is not `multipart/form-data` → 415 with code `VALIDATION_FAILED`. So "a dead link with a bad body still gets its 410" holds for application-level body errors only.)
  3. **Under the order row lock** (the lock every protected quote / invoice / line / price mutation takes), the token is **re-read**: if it died meanwhile → the 410 for its true state; if its expiry fell due meanwhile → the lazy-expiry flip is **committed**, then 410 `QUOTE_LINK_EXPIRED`. Then, before any write: name snapshot (422 `ACCEPTED_CUSTOMER_NAME_REQUIRED`) → below cost (422 `QUOTE_BELOW_COST`) → the D6b price plan (422 `BUSINESS_RULE_VIOLATION` when it cannot be persisted — §6).
- **Below-cost re-check → 422 `QUOTE_BELOW_COST`:** the frozen `quote_total_ex_gst` vs the live product + charge cost lines, under the lock. It is **reachable**, not merely defensive: cost-line changes after issue never supersede the version. The code is unchanged; only the message differs (D11).
- **Public-safe messages:** `ACCEPTED_CUSTOMER_NAME_REQUIRED`, `QUOTE_BELOW_COST` and the D6b `BUSINESS_RULE_VIOLATION` all return "This quote can no longer be accepted online. Please contact the store." — never cost, GP or financial-validation figures. The staff wording on draft/send is unchanged.
- A blank details-of-sale snapshot or a $0 total does **not** block acceptance (those guards belong to Path A conversion, PR2).
- **LAID:** allowed (D4), including the D6b price write.
- **Persist** (one transaction, order row lock held; file-write-first — any failure rolls back every write and deletes only the files this request wrote; the issued PDF and other existing files are never touched):
  - store the **server-normalised** signature PNG + its `stored_file` (`quote-signature-{order_number}-v{version_number}.png`, `image/png`, `file_size` = the normalised length);
  - render + store the **signed quote PDF** + its `stored_file` (`quote-{order_number}-v{version_number}-signed.pdf`; §10);
  - version `ISSUED → ACCEPTED` (status-guarded) with `accepted_at`, `accepted_customer_name`, `accepted_signature_file_id`, `signed_pdf_file_id`;
  - the presented token `ACTIVE → CONSUMED` (status-guarded; the public link is now dead);
  - the D6b order sale-price write (§6);
  - nothing else: no draft, line, order-status, invoice or payment change. `accepted_at`, the token's `dead_at` and the order's `updated_at` are the same instant.
- **Post-commit only (never on rollback):** email the **store** at the order's `store.email`:
  - staff-facing and **no attachment** (the signed PDF stays portal-only); the subject and body carry the order number, version number, accepted name, accepted time and the accepted inc-GST total — never a link, token, signature, storage reference, cost or GP;
  - blank `store.email` → skipped, with a WARN log;
  - failure is non-fatal: WARN log only (order id + version number), no persisted marker or column (D12);
  - recording-only sender in this build (§9).
- **201** `{ "data": { "state": "INACTIVE" }, "message": "Quote accepted." }` — a minimal confirmation with no id, amount, name, timestamp or file reference. The link is now dead; the signed quote lives only in the portal. Signature must not be discarded on notification-email failure.
- After acceptance the public GET returns 200 `state: INACTIVE` (minimal payload); `viewed`, `pdf` and a second `accept` return 410 `QUOTE_LINK_INACTIVE`. The `CONSUMED` token and the `ACCEPTED` version never expire later (§8).

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
- A successful in-app invoice accept (D.8) cancels the active issued version and its `ACTIVE` token (→ `CANCELLED`) inside the D.8 transaction (16F PR1, D5(c)).
- Rate limiting: do NOT expire normal users by view count. A customer opening the link many
  times within 7 days is fine. Only temporarily throttle/block suspicious rapid repeated
  access by token/IP (basic per-token + per-IP throttle on the public GET/accept paths).
```

**Status (after 16F PR1):** the token shape gate, SHA-256 hash storage, constant-time verification, 7-day lazy expiry and the per-state messages are implemented (16E-A/16E-C); the `CONSUMED` transition (public `accept`) and the D5(c) cancellation are implemented (16F PR1). **Rate limiting is not implemented** (including on the public `accept` path): no application-level throttle exists and nothing is deployed, so this rule is not satisfied anywhere today. It is Phase 17 work (decided 29 Sep 2026); application vs infrastructure level is undecided (see `docs/Phases.md` §7).

---

## 9. Delivery / email / SMS rules

- **Approved delivery is EMAIL ONLY.** **send-email** delivers the public quote **link** plus a short body, with **no PDF attachment** (link-only since 16F PR1; 16E-A attached the issued PDF), and mints/refreshes the token per §5/§8. The issued PDF is still stored; the customer can download it from the public page while the link is `ACTIVE`.
- **Current build — recording only:** the only `QuoteEmailSender` is the in-memory `RecordingQuoteEmailSender`; **no real email is sent**. The recorded message (which contains the bearer link) is logged only when DEBUG logging is explicitly enabled for that class — `application.properties` does not enable it. A production email provider is Phase 17. The store acceptance notification has its own recording-only sender (`QuoteAcceptanceNotificationSender`, implemented only by `RecordingQuoteAcceptanceNotificationSender`, 16F PR1); it sends nothing real either.
- **SMS — dormant, excluded from the approved roadmap.** 16E-A left backend support in place: the `send-sms` endpoint (link-only text), the `SMS` channel value, the `CUSTOMER_MOBILE_REQUIRED` / `_INVALID` and `SMS_SEND_FAILED` codes, and an `SmsSender` interface whose only implementation is the in-memory `RecordingSmsSender` (no provider). The frontend never calls it and its "Send by Phone/SMS" button is disabled. It is documented so the definitions stay accurate, not as planned delivery.
- Recipient is the saved customer **email** (email path; the **mobile** only for the dormant SMS path) from the Customer tab. **No send-time override** — to change the recipient, update the Customer tab first.
- Email gate (`CUSTOMER_EMAIL_REQUIRED` / `_INVALID`) and the dormant SMS gate (`CUSTOMER_MOBILE_REQUIRED` / `_INVALID`) are **fatal preconditions** (422 before any state change).
- The **store acceptance notification** (on `accept`, 16F PR1) is a **non-fatal**, post-commit email to the order's store (decisions D3/D12):
  - the recipient is `store.email` — no business-level, salesperson or customer fallback; blank → skipped, with a WARN log;
  - no attachment;
  - failure → WARN log only (order id + version number; never the recipient, body, token, link or signature), with no persisted marker or column. Acceptance always persists, and the notification never yields 502.
- Senders are **provider-independent** behind interfaces (email per Phase 13). Provider failure on a *send* (where delivery is the whole operation) → **502** (`EMAIL_SEND_FAILED`; `SMS_SEND_FAILED` on the dormant SMS path).

---

## 10. PDF rules

- **Draft preview** (`preview-pdf`) is generated **on-demand and not stored**.
- **Issued PDF** is generated and **stored immutably** when a new version is issued (`issued_pdf_file_id`). It is never regenerated: a resend reuses it, and the salesperson (Customer Quote preview / `pdf?type=issued`) and the public link both stream the stored file. Since 16F PR1 it is not attached to the quote email (link-only).
- **Signed PDF** *(16F PR1)* is generated and **stored immutably** on acceptance (`signed_pdf_file_id`, file name `quote-{order_number}-v{version_number}-signed.pdf`); it is served **only** in the protected portal (`GET …/quote/pdf?type=accepted`; the Accepted Quote tab's Preview signed PDF since 16F PR3). It is **never** served on the public surface — the public link is dead once signed — and never attached to the store notification.
- All quote PDFs reuse the **Aire Compact** document style with title **`QUOTATION`** (not `TAX INVOICE`), itemised columns (description / quantity / unit price / amount) in itemised mode, or a totals-only non-itemised presentation with details-of-sale text and no filler line section (dormant retained draft lines are never rendered on a non-itemised quote PDF — §6.1), plus a display-only 40% deposit line, printable declaration/customer acceptance area, and the frozen per-type terms (page 2, single column — same openhtmltopdf constraints as the invoice; tables/conservative CSS only; numeric entities, not named ones).
- The signed PDF (16F PR1) is the same `QUOTATION` document rendered from the accepted version's immutable snapshot only: the itemised flag, frozen totals, snapshot lines (itemised only), flooring type, details of sale, the frozen `terms_snapshot` (null → no terms page) and the `V17` "Quotation To" identity — never the live draft, customer/address rows or tenant terms. The business presentation (name, logo, ABN, bank details, store contact, salesperson) is read live at acceptance and then frozen in the stored bytes. The acceptance block is filled: both declaration squares render ticked (D8), the stored server-normalised signature PNG (§7.2) is embedded, and the caption reads "Accepted by {name} on {dd/MM/yyyy HH:mm}" (mirrors the invoice signed PDF). Draft previews and issued PDFs keep the blank acceptance area.

---

## 11. Frontend UX contract (Quote tab)

Behavioural contract only. Built in 16D (Quote Draft), 16E-B (Customer Quote) and 16F PR3 (Accepted Quote, the accepted-aware Customer Quote state, the refresh guards and the D9 warning; their backend reads exist since 16F PR1 and the Create Invoice backend since 16F PR2). `docs/Phase16D-Quotation-UX-Lock.md` is the detailed UX lock and wins on frontend workflow/visual decisions (e.g. it replaced the Save button below with autosave).
- The Quote tab has three sub-views: **Quote Draft**, **Customer Quote** (latest issued/sent), **Accepted Quote**. A sent/accepted quote is **never hidden** just because a draft exists.
- **Quote Draft** *(built)*: itemised/non-itemised toggle; editable lines + adjustments; live total = line sum; GP/below-cost warning (like Details of Sale); **autosave** (no Save button — UX lock §10), **Preview PDF**, and **Send Quote** (confirmation modal → **Send by Email**; **Send by Phone/SMS** is disabled — SMS is dormant, §9). Below-cost blocks save/send (surfaced inline).
- **Customer Quote** *(built, 16E-B; accepted-aware since 16F PR3)*: the active issued version: status (Sent / Opened / Not delivered), channel, sent time, link expiry, **Preview PDF** of the stored issued PDF, **Resend** (through the same confirmation), **Cancel quote**. With no active issued version but an accepted one, it shows the accepted state and a control that opens Accepted Quote, never the never-sent empty state. Never shows cost.
- **Accepted Quote** *(UI implemented 16F PR3; backed by `workspace.accepted`, `pdf?type=accepted` and `accepted/signature` (16F PR1) and by `create-invoice` (16F PR2))*: accepted time and name, captured signature (display), **Preview signed PDF** only (no separate download button; the browser downloads from the preview), and **Create Invoice** (Path A: the backend endpoint is built in 16F PR2 and the button in 16F PR3; it confirms first and posts `{}`). Create Invoice is **not** disabled merely because a draft or a newer issued quote is unsigned, and it is allowed on a LAID order. It is available exactly when `invoice_eligible` is true: there is no invoice, the current invoice is unsigned, or this quote was signed strictly later than the current invoice (D5(b) as amended on 8 October 2026). When `invoice_eligible` is false the button is disabled with an explanation: an invoice has already been created from this quote, or the current invoice was signed at the same time or later than this quote and a newer signed quote is needed. The endpoint's own 409 and 422 responses (section 7.1) still apply and are shown verbatim.
- **Refresh / send after acceptance** *(implemented 16F PR3)*: the Quote tab re-reads the issued/accepted state on tab activation, on sub-tab change and before opening Send; an older response never overwrites a newer one, and pending reads are discarded on unmount or order change; a failed read keeps the last known state and offers a retry; draft rows, totals and the autosave baseline are never re-seeded. Sending after acceptance warns that it issues a new version needing a new signature (D9).
- **LAID:** draft inputs/save/send/resend disabled; reads (preview/stored PDF/view state) and Cancel quote allowed.
- Recipient errors (`CUSTOMER_EMAIL_*`) prompt the user to fix the Customer tab.

---

## 12. Error contract

Envelope unchanged (conventions section 3): `{ "error": { "code", "message", "details"? } }`, `UPPER_SNAKE_CASE`. **New** Phase 16B codes (added to `ErrorCode` in the existing `NAME(HttpStatus.X, "Sentence-case message.")` style). All are in `ErrorCode`: 16F PR1 added `QUOTE_SIGNATURE_NOT_FOUND`, and 16F PR2 added `QUOTE_NOT_ACCEPTED` with `create-invoice`:

| Code | HTTP | Default message | Where |
|------|------|-----------------|-------|
| `QUOTE_NOT_FOUND` | 404 | "No quote was found for this order." | workspace/draft (defensive); send with no saved draft |
| `QUOTE_BELOW_COST` | 422 | "This quote is below cost and cannot be saved, sent, or accepted." | draft / send-* (this staff wording) / public accept (16F PR1 — public message "This quote can no longer be accepted online. Please contact the store.", D11) |
| `QUOTE_TOTAL_EXCEEDS_LINES` | 422 | "The quote total cannot exceed the sum of its lines. Add or raise a line instead." | draft |
| `QUOTE_NOT_ISSUED` | 422 | "There is no active quote to cancel." | cancel |
| `QUOTE_NOT_ACCEPTED` | 422 | "This quote has not been accepted yet." | create-invoice (16F PR2): the order has no `ACCEPTED` quote version |
| `QUOTE_ALREADY_ACCEPTED` | 409 | "This quote has already been accepted." | cancel (no `ISSUED` version and an `ACCEPTED` one exists — reachable since 16F PR1) |
| `QUOTE_PDF_NOT_FOUND` | 404 | "The requested quote PDF is not available." | quote/pdf (`issued`: no active issued version; `accepted`: no accepted version or signed PDF); public pdf (missing stored artifact) |
| `QUOTE_SIGNATURE_NOT_FOUND` | 404 | "No accepted quote signature is available for this order." | `GET …/quote/accepted/signature` (16F PR1): no accepted version, or no stored signature |
| `CUSTOMER_MOBILE_REQUIRED` | 422 | "A valid customer mobile number is required to send by SMS." | send-sms (dormant) |
| `CUSTOMER_MOBILE_INVALID` | 422 | "Customer mobile number is not valid." | send-sms (dormant) |
| `SMS_SEND_FAILED` | 502 | "The quote could not be sent by SMS. Please try again." | send-sms (dormant) |
| `QUOTE_TOKEN_NOT_FOUND` | 404 | "Quote not found." | all public (unknown or malformed token; no leak) |
| `QUOTE_LINK_EXPIRED` | 410 | "This quote link has expired. Please contact the store." | public viewed/pdf/accept |
| `QUOTE_LINK_SUPERSEDED` | 410 | "This quote has been replaced. Please use the latest quote link." | public viewed/pdf/accept |
| `QUOTE_LINK_CANCELLED` | 410 | "This quote has been cancelled. Please contact the store." | public viewed/pdf/accept |
| `QUOTE_LINK_INACTIVE` | 410 | "This quote link is no longer active. Please contact the store." | public viewed/pdf/accept (token `CONSUMED`/signed) |
| `SIGNATURE_REQUIRED` | 422 | reused Phase 13 code; this surface uses the quote wording "A signature is required to accept this quote." (the default names the invoice) | public accept (16F PR1): missing/empty `signature` |
| `SIGNATURE_INVALID` | 400 | reused Phase 13 code and default "Signature must be a PNG image no larger than 2 MB." | public accept (16F PR1): declared type not `image/png`, over 2 MB, or not a safely decodable PNG — no PNG signature, first chunk not a 13-byte `IHDR`, over 8,192 px per side or 4,000,000 px, bit depth over 8, an invalid colour type, or not decodable by the PNG reader (§7.2) |

**Reused** existing codes (do not rename/duplicate): `ORDER_LOCKED` (422, LAID write block on draft/send — never on public accept, which is allowed when LAID), `ORDER_NOT_FOUND` (404), `ACCEPTED_CUSTOMER_NAME_REQUIRED` (422, public accept with a null/blank `V17` name snapshot — public-safe message "This quote can no longer be accepted online. Please contact the store."), `BUSINESS_RULE_VIOLATION` (422, public accept when the D6b price write cannot be persisted — same public-safe message), `CUSTOMER_EMAIL_REQUIRED` / `CUSTOMER_EMAIL_INVALID` (422, send-email), `EMAIL_SEND_FAILED` (502, send-email), `VALIDATION_FAILED` (400, malformed body / non-empty cancel body / bad, extra or duplicate multipart part — on accept, any part, form field or request parameter other than one `signature`, including `accepted_customer_name`).

**Reused by `create-invoice` (16F PR2; no second refusal code is added):** `ORDER_NOT_FOUND` (404, out-of-scope order), `VALIDATION_FAILED` / `MALFORMED_JSON` (400, any request field or an unparseable body), `INVOICE_ALREADY_ACCEPTED` (409, the current invoice was signed at the same time or later than the accepted quote; Path A message "The current invoice was signed at the same time or later than this quote. A newer signed quote is required to create an invoice from a quote."; D.8 keeps its default message), `INVOICE_PRECONDITIONS_NOT_MET` (422, the collected Path A preconditions), `BUSINESS_RULE_VIOLATION` (422, active payments exceed the accepted quote total). `create-invoice` never returns `ORDER_LOCKED` and has no customer-email gate.

Clarifications:
- The public GET returns **200 with a `state`** for EXPIRED/SUPERSEDED/CANCELLED/INACTIVE (the customer holds the token, so showing the state message is intended); only an **unknown** token is 404. The **viewed**/PDF/**accept** actions on any non-`ACTIVE` state return the matching **410** (`QUOTE_LINK_EXPIRED` / `_SUPERSEDED` / `_CANCELLED` / `_INACTIVE`).
- `410 Gone` is used for dead links (expired/superseded/cancelled) — add to conventions §4 alongside the Phase 13 `502`.

---

## 13. Implementation notes / risks

- **Migrations:** `V16` (five tables, section 4), then `V17` (issue-time customer snapshots) and `V18` (reserved slug `q`) in 16E-C, then `V19` in 16F PR1 (accepted-name columns → `TEXT` on `quote_version` and `invoice`; nullable `invoice.source_quote_version_id` FK + `invoice.terms_snapshot`, schema-only in PR1 and used at runtime since 16F PR2, section 4.6). 16F PR2 adds no migration. All are outside the CI guard (V1–V13) but, like every committed migration, never edited; they fold into the Phase 17 squash. Any change to the guard itself is deliberate migration/CI work, not a side effect of a feature PR.
- **Quote ≠ invoice.** Do **not** reuse the `invoice` table or invoice endpoints for quote state. The only quote → order write is the D6b override at acceptance (§6). Invoice touchpoints:
  - **Path A** `create-invoice` (16F PR2; under the order lock it reads the latest accepted quote snapshot and appends a normal accepted invoice version using the `V19` columns; it never writes quote rows or tokens);
  - **D5(c)** (16F PR1): a successful in-app invoice accept cancels the active issued quote version + token inside the D.8 transaction. One direction only: quote acceptance never writes invoices, and an accepted quote never blocks D.8.
- **Append-only issued layer.** `quote_version` is append-only; only delivery markers (`last_emailed_at`, `last_sent_at`, `viewed_at`), the lifecycle `status`, and the acceptance columns (`accepted_at`, `accepted_customer_name`, `accepted_signature_file_id`, `signed_pdf_file_id` — written once, on `ISSUED → ACCEPTED`) mutate on it. Token state lives in `quote_token` (insert per send; status updated, never deleted). The draft layer (`quote_draft`/`_line`) is freely mutable.
- **Transaction boundaries.** On `accept` (16F PR1): under the order row lock, re-read the token; then store the signature + signed PDF, apply the guarded version/token transitions and the D6b override write, all in one transaction (file-write-first + rollback cleanup of only this request's files, as Phase 13). An expiry found under the lock commits its flip before the 410. The **store notification is post-commit, non-fatal and never sent on rollback.** On `send-*`: **persist the issued version + issued PDF + token before delivery. If delivery fails, keep the issued version/PDF/token, return 502, and allow resend. This is locked for MVP** (see the §7.1 send-failure rule and its accepted tradeoff).
- **Path A transaction (16F PR2).** `create-invoice` runs in one transaction. After the session guard and the `orderId` parse, the scoped order lookup takes the order row lock, which is held to commit through every later read and write (checks, signature read, PDF render, file write, invoice insert, mirror reset). It writes the new invoice PDF first with rollback cleanup (in-method and after completion) that deletes only that file, and has no post-commit side effect (no email, no notification). It serialises with D.8, payment and void, rewrite and the public quote accept on the same order lock, so a race resolves to one of the documented outcomes (for example D.8 first gives the Path A 409, Path A first gives D.8's 409).
- **`invoice_template_key`** exists (V12) but is **dormant** (not read by the invoice PDF assembler/generator today). The quote PDF reuses the Aire Compact template directly; do **not** wire `invoice_template_key` in 16B/16C unless explicitly scoped.
- **Cost discipline.** Quote lines carry no cost; the public surface is cost-free and ID-free; GP/below-cost is computed server-side from the order's product/charge cost lines and surfaced as figures/warnings only on the **protected** Quote tab. The public accept returns no figures, but its 422 `error.code` (`QUOTE_BELOW_COST`, like `ACCEPTED_CUSTOMER_NAME_REQUIRED` / `BUSINESS_RULE_VIOLATION`) names the condition in the raw response — accepted under D11 (the code stays `QUOTE_BELOW_COST`); the customer-facing message is the single neutral sentence.
- **Token security.** Generate with a CSPRNG; store only the hash; constant-time compare on lookup; per-token + per-IP throttle on public endpoints; lazy-expire on access.
- **Risks:** (a) the original SMS deliverability / AU number-formatting risk no longer applies — SMS is outside the approved delivery scope (§9); (b) ensuring a carried-forward/inherited signature `stored_file` is not deleted while referenced (non-unique FK — same caution as Phase 13); (c) draft saves and sends are price-independent, and only a public acceptance writes the order override (D6b — the accepted inc total); Path A bills the **accepted snapshot**, never the live order price — all must be coded explicitly; (d) public-surface isolation — a token must resolve to exactly one quote and leak nothing else.
- **A quote signed after an in-app invoice signature (decision D5(b) as amended on 8 October 2026):** D5(c) cancels only the quote link that is `ISSUED` at the moment of the in-app invoice acceptance (D.8). A quote sent and signed after that is a new `ISSUED` version that can still be signed remotely (there is no send or signing guard), and its D6b write changes the order's working sale price. Because its signature is strictly newer than the current invoice's, `create-invoice` may convert it: a new invoice version is appended from that quote, and the earlier signed invoice stays in history. D5(c) still cancels only at successful D.8 time.

---

## Appendix — OpenAPI alignment

`docs/openapi.yaml` is updated additively in the same 16B docs change:
- New tag **`Quotes`**.
- Protected paths: `GET …/quote/workspace` (`getQuoteWorkspace`), `PUT …/quote/draft` (`upsertQuoteDraft`), `POST …/quote/preview-pdf` (`previewQuotePdf`), `POST …/quote/send-email` (`sendQuoteEmail`), `POST …/quote/send-sms` (`sendQuoteSms`), `POST …/quote/cancel` (`cancelQuote`), `POST …/quote/create-invoice` (`createInvoiceFromQuote`), `GET …/quote/pdf` (`downloadQuotePdf`), and — added in 16F PR1 — `GET …/quote/accepted/signature` (`downloadAcceptedQuoteSignature`).
- Public paths: `GET /public/quotes/{token}` (`getPublicQuote`), `POST /public/quotes/{token}/viewed` (`markPublicQuoteViewed`), `POST /public/quotes/{token}/accept` (`acceptPublicQuote`), `GET /public/quotes/{token}/pdf` (`downloadPublicQuotePdf`).
- New schemas: `QuoteWorkspace`, `QuoteDraft`, `QuoteDraftLine`, `QuoteIssuedSummary`, `QuoteAcceptedSummary`, `QuoteDraftUpsertRequest`, `QuoteSendResponse`, `PublicQuoteView`, `PublicQuoteState` (enum), `QuoteAcceptRequest` (multipart).
- New shared response `Gone` (410); reuse `BadGateway` (502).
- `error.code` strings remain free-form (OpenAPI does not enumerate codes).
- Later amendments: `QuoteIssuedSummary` gained the frozen body fields (16E-B) and `PublicQuoteView` was extended additively with the public-page document fields (16E-C). 16F PR1: `QuoteAcceptedSummary` gained `quote_total_ex_gst`, `itemised`, `flooring_type`, `details_of_sale`, `lines`, `signed_pdf_available` and `signature_download_path` (with the PR1 `invoice_eligible` rule, since amended in 16F PR2); the accepted-signature path was added; `acceptPublicQuote` / `QuoteAcceptRequest` take the name from the `V17` snapshot (signature-only multipart) and state the safe-decode bounds and the server-normalised stored signature; `getQuoteWorkspace` notes the one-snapshot read and `QuoteWorkspace.accepted` describes the latest accepted version; `acceptCurrentInvoice` notes the D5(c) link cancellation; `downloadQuotePdf` `type=accepted` is live; `sendQuoteEmail` is link-only; `cancelQuote`'s 409 `QUOTE_ALREADY_ACCEPTED` is reachable; `getPublicQuote` notes signability via `acceptPublicQuote` and the D5(c) `CANCELLED` state; the accept 201 (`PublicQuoteAcceptResponseWrapper`, `{state: "INACTIVE"}`) is documented. 16F PR2: `createInvoiceFromQuote` is implemented (Path A: the check order, signature-precedence table, preconditions, overpayment response, field mapping, LAID allowance and no email); `InvoiceDetail` gains the always-present `terms_html` (nullable) and `terms_source` (`QUOTE` / `LIVE`); `QuoteAcceptedSummary.invoice_eligible` follows the strict signature-precedence rule (section 7.1); `acceptCurrentInvoice` notes that a quote signed after it can be converted by `createInvoiceFromQuote`.
- A path definition is not evidence of an implementation: `createInvoiceFromQuote` is implemented (16F PR2), `acceptPublicQuote` is implemented (16F PR1), and `sendQuoteSms` is dormant (section 9).