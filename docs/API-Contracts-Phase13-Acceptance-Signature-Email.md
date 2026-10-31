# Sales Portal MVP — API Contracts · Phase 13 (Invoice Acceptance + Signature + Email) (Lock-Ready)

**Status:** Phase 13 MVP contract: implemented in Phase 13 (migration `V10`) and amended since by Phase 15D (payment void) and 16F (Create Invoice from the accepted quote, D5(c)). Additive to the locked **Phase 12 / Chunk 4** invoices + payments contract (`docs/API-Contracts-Chunk-4.md`).

**16G amendments (written in 16G PR 1; all implemented).** Remote invoice signing through an emailed link, the frozen words on every invoice version, the mandatory version precondition on D.8 and the signed-PDF email on every signing route are specified in [the 16G contract](API-Contracts-Phase16G-Invoice-Link.md). The passages below that 16G changes are updated in place and name their implementation PR (16G PR 2 to PR 6). Every rule marked with a 16G PR is implemented (PR 6 was the last); the rest describes current behaviour.

**Source of truth priority:**
1. `docs/API-Conventions.md`
2. Locked Chunk 1–3 contracts
3. Locked Chunk 4 contract (Phase 12 invoices + payments)
4. This Phase 13 contract (additive)
5. Locked DB schema (V2 / V3, plus the documented Phase 13 `V10` additions)

**Order number format (locked):** `{store_code}.{salesperson_code}.{order_sequence_number_padded_5}` — e.g. `SYD-CBD.LC1.00042`. Backend-generated; frontend never sends it. Used in invoice/signature file names.

**File-binary exception (conventions §3):** file upload endpoints may accept `multipart/form-data`; file download endpoints may return raw binary bytes. Phase 13 uses this exception for invoice **acceptance** (signature multipart upload) and the **signature** image download.

---

## 1. Purpose

Phase 12 lets a salesperson create the current invoice, rewrite it, record payments, view it, and download its PDF. Phase 13 closes the in-home sale: the **customer signs the invoice on the iPad, the salesperson accepts it, and the accepted PDF is emailed to the customer automatically.** A post-acceptance payment carries the signature forward without re-signing. *(Phase 15D update: a post-acceptance payment — and a payment void — no longer auto-emails; manual Re-send is the only email action after a payment change. See §6.)*

Phase 13 is a **practical MVP signature-capture + email workflow**, not a full legal e-sign platform. It keeps the Phase 12 *current-invoice-only* model: no invoice-status enum, no version history UI, no old-version access. 16G keeps this model: an invoice link is link state on its own table, not an invoice status, and the public invoice surface shows only the current version (16G contract §7, §9).

Concretely, Phase 13 adds:
- **Customer-email precondition** on Create / Rewrite (and Accept / Resend) so the accepted invoice can always be emailed.
- **Accept current invoice** — capture a customer signature + accepted name, regenerate a **signed** PDF, and **auto-email** it to the customer.
- **Resend current accepted invoice** — re-email the current accepted PDF.
- **Signature image download** — stream the accepted signature for display.
- **Payment ↔ email** rules — a payment (or payment void) on an accepted invoice carries acceptance/signature forward; *(Phase 15D)* it does **not** email. Manual Re-send is the only email path after a payment change. See §6.
- **PDF redesign** — a customer-facing invoice layout that matches the Invoice tab, with a signature area (blank before acceptance, embedded signature after).
- **Dashboard** — `invoice_accepted` + the existing `last_emailed_at`.

---

## 2. MVP scope (included)

- Customer **email is required and valid** before Create Invoice, Rewrite Invoice, Accept, and Resend, and (16G PR 4) before the invoice link send. Backend contract rule, not frontend-only. Create Invoice from the accepted quote (Path A) and remote invoice signing (16G PR 4) have **no** email gate (16G contract §6).
- Customer **signature capture** on the Invoice tab (draw on iPad/touchscreen). The **accepted customer name** is shown with it and is never typed: since 16G PR 3 the server takes it from the current invoice version's frozen `customer_name_snapshot` and the request carries no name (16G contract §4, §5.4); before 16G PR 3 the Invoice tab derived it from the saved customer record and sent it.
- **Accept Invoice** (one endpoint): requires an existing current invoice, a signature and a valid customer email (since 16G PR 3 also the mandatory `expected_version_number`, the version on screen; before 16G PR 3 the accepted-name part instead). Stores `accepted_at`, `accepted_customer_name`, and a signature file reference; regenerates the signed PDF; **auto-emails** the accepted PDF; updates `last_emailed_at`.
- Accepted invoice is **not directly mutated** afterward (see §4, §7).
- **Re-send Invoice**: re-emails the current accepted invoice PDF; updates `last_emailed_at`. Re-send Invoice is shown once the current invoice is **accepted**, including when the initial automatic email failed — it does **not** depend on a prior successful email (an accepted invoice with `last_emailed_at = null` can still be re-sent).
- **Download PDF** remains available before acceptance (Phase 12 `D.4`); 16G PR 5 renamed the button **Preview PDF** (the same `D.4` read, opened in a new tab). The invoice **PDF** is never emailed before signature/acceptance. Since 16G PR 4 an unsigned current invoice can be sent to the customer as a **link** (`POST …/invoices/current/send-link`, behind the **Accept Remotely** button of 16G PR 5; link only, no PDF attached) for the customer to review and sign remotely (16G contract §8, §9).
- **Payments** remain allowed before and after acceptance (do **not** require acceptance). Each payment (and Phase 15D payment **void**) still creates a new current invoice version with updated `total_paid` / `balance_due`. If the invoice was accepted, acceptance/signature carries forward (no re-sign); *(Phase 15D)* **no email is sent** on payment record or void — manual Re-send is the only email path.
- **Signature image download** for display in the app.
- **PDF** shows store header, customer details, billing address, details of sale, totals, total paid, balance due, terms, and a signature area (embedded signature + accepted name + accepted timestamp after acceptance).
- **Dashboard** exposes `invoice_accepted` (boolean) and the already-present `last_emailed_at`.
- Email provider is **TBD at implementation time**; the contract is provider-independent (an email service sits behind the backend). Email template branding/content may be simple MVP wording.

---

## 3. Non-goals / post-MVP (explicitly excluded)

These stay **out** of Phase 13:
- Full invoice revision/history dropdown; viewing or downloading **old** invoice versions or **old** signed PDFs; signed-invoice history list.
- A separate **invoice-status enum** / invoice-status state machine (Draft / Created / Accepted / Sent). Phase 13 uses explicit nullable fields instead (§4). The order status enum (`LEAD`, `NEW_ACHIEVED_SALE`, `FOLLOW_UP`, `ACCEPTED`, `LAID`, `CANCELLED`) is unchanged and is **not** reused for invoice state — note `ACCEPTED` is an **order** status and is unrelated to invoice acceptance.
- Payment edit; refunds; finance products; surcharges; overpayment/credit workflows. *(Phase 15D update: payment **void** (soft void) is now implemented; see Chunk 4 D.10. Hard delete and payment edit remain out of scope.)*
- Advanced **email audit trail** / per-send history; advanced legal **e-sign** workflow (witnessing, certificates, tamper-evidence, audit packets).
- **Stripe** (Phase 14) and deployment (Phase 15).
- Installer / laybook workflows.

---

## 4. Data model contract

Phase 13 added four nullable columns to the existing `invoice` table in migration `V10` (applied, and locked like every committed migration). 16G PR 2 adds three frozen identity columns in a new migration (16G contract §4.1).

**`V10` — `invoice` additions:**

| Column | Type | Null | Notes |
|--------|------|------|-------|
| `accepted_at` | `TIMESTAMP` | NULL | Set when the current invoice is accepted; null = unaccepted. |
| `accepted_customer_name` | `VARCHAR(150)`; widened to `TEXT` by `V19` (16F PR1) | NULL | The accepted name. Since 16G PR 3, D.8 stores the signed version's frozen `customer_name_snapshot` in full (the request part and its 150-character limit were removed, section 5.1), and remote signing (16G PR 4) does the same. Path A copies the accepted quote's name; payment and void carry it. |
| `accepted_signature_file_id` | `BIGINT` | NULL | FK → `stored_file(stored_file_id)`. **Not unique** (a carried-forward version references the same signature file). |
| `last_emailed_at` | `TIMESTAMP` | NULL | Last successful email of **this invoice version's signed PDF**; null = never. Mutable delivery marker (see below). Invoice **link** emails (16G PR 4) never set it: they are recorded on `invoice_token` only (16G contract §8). |

Notes:
- `accepted_signature_file_id` is a **non-unique** nullable FK (contrast `invoice.stored_file_id`, the PDF, which is UNIQUE per row). Acceptance and payment-carry-forward may point multiple invoice versions at the same signature `stored_file`.
- **Append-only vs mutable.** The `invoice` table is append-only: every version writer *inserts* a new row (Create, Rewrite, Accept (D.8), payment, payment void, Create Invoice from the accepted quote (Path A) and, since 16G PR 4, remote signing); the current invoice is `max(version_number)`. Phase 13 preserves this:
  - The **sale snapshot** and the **acceptance fields** (`accepted_at`, `accepted_customer_name`, `accepted_signature_file_id`) are **immutable once written**, and from 16G PR 2 so are the frozen words (`customer_name_snapshot`, the two billing lines and `terms_snapshot`, 16G contract §4). Acceptance does not mutate an existing row; it **appends** a new current version (§5.1, §6).
  - `last_emailed_at` is a **delivery marker**, not part of the immutable snapshot; it **may be updated in place** on the current invoice row (e.g. Resend bumps it without creating a version).
- **Signature storage** reuses the existing `stored_file` + `FileStorageService` mechanism (server-internal `storage_path` under `/uploads/{businessId}/orders/{orderId}/{uuid}.png`, **never** exposed). The signature is **not** inserted into `order_attachment` and **not** surfaced in the Notes & Photos attachments list. The `attachment_kind` enum value `SIGNATURE` (already present in `V1`, still rejected by the Chunk 3 attachment-upload endpoint) is **not** required for this; signatures are invoice-specific and linked only via `invoice.accepted_signature_file_id`.
- **Customer email** lives on `order_customer.email VARCHAR(255) NOT NULL` (DB CHECK `trim(email) <> '' AND email LIKE '%@%'`). One row per order. The Phase 12 invoice preconditions do **not** require email; Phase 13's email gate (§12) is the dedicated check, validating more strictly than the DB `LIKE '%@%'`.
- **Dashboard mirror.** `sales_order.last_emailed_at` already exists (`V2`) and already feeds the dashboard row. Phase 13 redefines it as a **mirror of the CURRENT invoice's `last_emailed_at`** — it must always equal `invoice.last_emailed_at` for the current version, **including `null`**: it is set to the new current invoice's value (`null`) whenever a new version is created, and to the timestamp on a successful signed-PDF email (Accept, Re-send and, since 16G PR 3, Create Invoice from the accepted quote; since 16G PR 4 remote signing). Invoice link sends never move it. This prevents the dashboard from showing a stale "emailed" time from a previous version after a new, unemailed current invoice is created. See the **§11.1 mirror invariant** for the per-operation table. `invoice.last_emailed_at` remains the authoritative per-invoice marker shown on the Invoice tab.
- Money stays `DECIMAL(10,2)`, scale 2, `HALF_UP`. Timestamps are `YYYY-MM-DDTHH:mm:ss` (server-local, no timezone). Dates are `YYYY-MM-DD`.

**No** invoice-status column is added. **No** `V*` change to `order_customer`, `payment_transaction`, or `stored_file` is required *for Phase 13*. *(Phase 15D adds the soft-void columns `voided_at` + `voided_by_user_id` to `payment_transaction` via migration `V14` — see Chunk 4 D.10.)*

---

## 5. Endpoint contracts

All Phase 13 endpoints are **Standard protected** (all 7 conventions §9 checks), scoped to the session `(business_id, store_id)`, and resolve the **current invoice** as the `max(version_number)` row for the order. (The 16G public invoice endpoints are token-only and are specified in the 16G contract §9.) Cross-tenant / cross-store / cross-order misses return **404**, never 403. Path style and JSON conventions follow Chunk 4 exactly (snake_case bodies; `{ "data": … , "message": … }` success wrapper; `{ "error": { "code", "message", "details" } }` error wrapper).

The invoice DTOs `current_invoice_summary` (E.1) and `invoice_detail` (E.2) from Chunk 4 §E gain **five** fields in Phase 13 (always present; nullable values serialized as `null`):

| Field | Type | Notes |
|-------|------|-------|
| `accepted_at` | timestamp \| null | null = unaccepted |
| `accepted_customer_name` | string \| null | |
| `accepted_signature_present` | boolean | `true` iff a signature is stored |
| `accepted_signature_download_path` | string \| null | relative URL to the signature GET (§5.3); null when no signature |
| `last_emailed_at` | timestamp \| null | null = never emailed |

Since 16F PR2, `invoice_detail` (E.2) also always carries `terms_html` (string or null) and `terms_source`. Since **16G PR 2** `terms_source` is `QUOTE` or `BUSINESS` (`BUSINESS` replaced the 16F value `LIVE`; rule in `docs/API-Contracts-Phase16B-Quotation.md` section 4.6): every version renders the terms stored on it (frozen at Create or Rewrite, or copied from the signed quote), never a live lookup. PR 2 also added three always-present, nullable frozen identity fields: `customer_name_snapshot`, `customer_address_line1_snapshot` and `customer_address_line2_snapshot` ([16G contract §4.4–§4.5](API-Contracts-Phase16G-Invoice-Link.md#4-every-invoice-version-freezes-its-words-125)). **16G PR 4** added one more always-present, nullable field that is not a frozen word: the derived `invoice_link_expires_at`, the `expires_at` of the order's `ACTIVE`, unexpired invoice link, otherwise null, computed in the read from server state. It is not an `invoice` column, no writer copies it, and reading it writes nothing and never expires a token; a signed current invoice always gives null. It is never displayed; since 16G PR 6 the Invoice tab polls on it ([16G contract §4.5](API-Contracts-Phase16G-Invoice-Link.md#45-invoicedetail-e2-changes-pr-2-pr-4), §13.2). `current_invoice_summary` (E.1) carries none of these.

Updated **`invoice_detail` (E.2)** example (returned by Phase 12 `D.1`/`D.2`/`D.3` and Phase 13 Accept/Resend; the 16G shape: the three `customer_*_snapshot` fields and `BUSINESS` are returned since PR 2, and `invoice_link_expires_at` is returned since PR 4; this invoice is signed, so the link field is null):

```json
{
  "invoice_id": 4,
  "order_id": 1,
  "version_number": 4,
  "invoice_date": "2026-04-22",
  "due_date": "2026-05-06",
  "details_of_sale_snapshot": "Supply and install plush carpet to lounge and dining rooms.",
  "sale_price_ex_gst": 840.00,
  "sale_price_inc_gst": 924.00,
  "total_paid": 500.00,
  "balance_due": 424.00,
  "created_by_user_id": 1,
  "created_at": "2026-04-22T14:30:00",
  "pdf_download_path": "/api/v1/aussiefloors/orders/1/invoices/current/file",
  "customer_name_snapshot": "Jordan Wilson",
  "customer_address_line1_snapshot": "4/12 Oxford Street",
  "customer_address_line2_snapshot": "Paddington NSW 2021",
  "accepted_at": "2026-04-22T14:31:10",
  "accepted_customer_name": "Jordan Wilson",
  "accepted_signature_present": true,
  "accepted_signature_download_path": "/api/v1/aussiefloors/orders/1/invoices/current/signature",
  "last_emailed_at": "2026-04-22T14:31:12",
  "terms_html": "<p>…</p>",
  "terms_source": "BUSINESS",
  "invoice_link_expires_at": null
}
```

An **unaccepted** current invoice returns `accepted_at: null`, `accepted_customer_name: null`, `accepted_signature_present: false`, `accepted_signature_download_path: null`, `last_emailed_at: null`. The same five fields are added to **`current_invoice_summary` (E.1)** (returned inside the `D.7` payment response) so the frontend can render acceptance/email state after a payment without an extra `GET`.

`accepted_signature_file_id` and `stored_file.storage_path` are **server-internal** and never returned (mirrors how `stored_file_id` is hidden behind `pdf_download_path`).

---

### 5.1 D.8 — POST `/api/v1/{slug}/orders/{orderId}/invoices/current/accept`

**Purpose.** Accept/sign the **current** invoice, store the signature + acceptance metadata, regenerate the signed PDF, and automatically email the accepted PDF to the customer.

**Scope:** Standard protected. Path param `orderId` — positive integer.

**Request — `multipart/form-data`** (file-binary exception). Since **16G PR 3** ([16G contract §5](API-Contracts-Phase16G-Invoice-Link.md#5-version-preconditions-69-123)):

| Part | Type | Required | Rules |
|------|------|----------|-------|
| `expected_version_number` | text | yes (since 16G PR 3) | exactly once; a positive integer: the `version_number` of the invoice on screen. Compared under the order lock with the current version, never used to look a row up. |
| `signature` | file | yes | **`image/png` only**; non-empty; **max 2 MB** (`2_097_152` bytes; `==` allowed, `>` rejected) |

`image/png` is the canonical format (HTML canvas `toBlob('image/png')`). Any other part name, or a repeated part, → 400 `VALIDATION_FAILED`. Since 16G PR 3 only the multipart form field carries `expected_version_number`: a URL query-string copy is not the carrier and is refused as `Not allowed.`.

**Before 16G PR 3** the request carried `accepted_customer_name` (text; non-blank after trim, else 422 `ACCEPTED_CUSTOMER_NAME_REQUIRED`; at most 150 characters, else 400) and `signature`, and no version value. PR 3 removed the `accepted_customer_name` part and its 150-character request limit together with the Invoice tab change: sending it is now 400 `VALIDATION_FAILED` like any other unexpected part (rule 6 below), whatever its value, and the accepted name is the current version's frozen `customer_name_snapshot`, stored in full, never typed, trimmed or truncated.

**Response — `201 Created`** (acceptance committed; email sent):

```json
{
  "data": {
    "invoice": { "…invoice_detail (E.2) with acceptance + last_emailed_at set…" }
  },
  "message": "Invoice accepted and emailed to the customer."
}
```

**Response — `201 Created`** (acceptance committed; email send failed — **non-fatal**):

```json
{
  "data": {
    "invoice": { "…invoice_detail (E.2); accepted_at set; last_emailed_at = null…" }
  },
  "message": "Invoice accepted. The invoice could not be emailed — use Re-send Invoice to try again."
}
```

The acceptance (signature + `accepted_at`) is **durably persisted even if the email fails**; we never discard a captured signature because the provider hiccuped. After a `201`, `accepted_at` is non-null; `last_emailed_at` is non-null **iff** the auto-email succeeded and its stamp was recorded (null ⇒ not recorded ⇒ frontend offers Re-send). The message reports the delivery outcome. Known limitation, not fixed in 16G (audit F49, tracked as [#148](https://github.com/MuneebHash/flooring-sales-portal/issues/148)): if another version is appended between the commit and the stamp, the `201` body describes that newer current version.

**Business rules (in order).** This is the order since **16G PR 3** ([16G contract §5.4](API-Contracts-Phase16G-Invoice-Link.md#5-version-preconditions-69-123)). Before PR 3, rules 3 and 4 did not exist and rule 7 checked the `accepted_customer_name` part instead. Every refusal below writes nothing.
1. Order in scope (locked), else 404 `ORDER_NOT_FOUND`.
2. A current invoice exists, else 422 `INVOICE_REQUIRED` (accept the *first* invoice via `D.1` Create before signing).
3. *(since 16G PR 3)* `expected_version_number` present exactly once and a positive integer, else 400 `VALIDATION_FAILED` (`Required.` / `Must appear at most once.` / `Must be a positive integer.` on `expected_version_number`).
4. *(since 16G PR 3)* `expected_version_number` equals the current invoice's `version_number`, else 409 `INVOICE_VERSION_STALE`, "Invoice updated, please refresh". Nothing is written.
5. Current invoice **not already accepted** (`accepted_at IS NULL`), else 409 `INVOICE_ALREADY_ACCEPTED` (use Re-send to email an already-accepted invoice).
6. Only the allowed parts, each at most once, else 400 `VALIDATION_FAILED`.
7. *(since 16G PR 3)* The current version's frozen `customer_name_snapshot` is non-blank, else 422 `ACCEPTED_CUSTOMER_NAME_REQUIRED`. It becomes `accepted_customer_name`, verbatim and in full.
8. `signature` part present and non-empty, else 422 `SIGNATURE_REQUIRED`.
9. `signature` is `image/png` and ≤ 2 MB, else 400 `SIGNATURE_INVALID`.
10. Customer email present and valid (§12), else 422 `CUSTOMER_EMAIL_REQUIRED` / `CUSTOMER_EMAIL_INVALID`.
11. **Persist (one transaction):** store the signature as a `stored_file`; **append** a new current invoice version (`version_number = max + 1`) that **carries forward** the sale snapshot (`details_of_sale_snapshot`, `sale_price_ex_gst`, `sale_price_inc_gst`, `due_date`) and `total_paid`/`balance_due` **unchanged** (from 16G PR 2 also the frozen name, billing lines and terms, verbatim), sets `accepted_at = now`, `accepted_customer_name`, `accepted_signature_file_id`, and `last_emailed_at = null`; generate the **signed** PDF (`stored_file`, linked via `invoice.stored_file_id`). **`invoice_date`:** since 16G PR 3 it is copied from the current version, so the signed PDF shows the date of the invoice the customer was shown, while `accepted_at` records the signing time ([16G contract §5.4](API-Contracts-Phase16G-Invoice-Link.md#54-d8-order-of-checks-pr-3)); before PR 3 the new version was dated the signing day (`LocalDate.now()`). Set the dashboard mirror `sales_order.last_emailed_at` to this new current version's value (`null`) — see §11.1. PDF/file/insert failure → full rollback. Since 16F PR1 (D5(c)) the same transaction cancels the order's ISSUED quote version and its ACTIVE token (both `CANCELLED`); no ISSUED quote is a no-op; an ACCEPTED quote is never touched and never blocks D.8. Since 16G PR 4 the same transaction also marks an `ACTIVE` invoice link `SIGNED`.
12. **Post-commit:** email the signed PDF to the customer. On success set `last_emailed_at` to the send timestamp on **both** `invoice` and the `sales_order` mirror. On failure leave **both** `invoice.last_emailed_at` and `sales_order.last_emailed_at` = `null` (non-fatal; see message above). **Since 16G PR 3 no post-commit step can turn the committed acceptance into an error:** any sender runtime failure, and a failure of the stamp transaction, is logged at WARN (order id and version only) and leaves both markers null, and the response is still 201 ([16G contract §6.2](API-Contracts-Phase16G-Invoice-Link.md#6-signed-invoices-are-emailed-after-commit)). The message still reports the delivery outcome: a failed delivery (including a sender runtime failure) gives the not-emailed message, while a successful delivery whose stamp then fails gives `Invoice accepted and emailed to the customer.` with both markers null, so Re-send stays available. The emailed PDF is the appended version's own PDF, and the recipient is the saved email read in the committing transaction. (Before PR 3 only the transport failure was caught; another sender failure or a stamp failure returned 500 after the commit.)
13. Return the updated current invoice (E.2).

**LAID:** **allowed** (parallels payment, not rewrite — see Chunk 4 F.8). Acceptance carries the snapshot forward and never makes unsent live edits official.

**Status codes:** 201; 400 (`VALIDATION_FAILED`, `SIGNATURE_INVALID`); 401; 403; 404 (`ORDER_NOT_FOUND`); 409 (`INVOICE_ALREADY_ACCEPTED`; `INVOICE_VERSION_STALE` since 16G PR 3); 422 (`INVOICE_REQUIRED`, `ACCEPTED_CUSTOMER_NAME_REQUIRED`, `SIGNATURE_REQUIRED`, `CUSTOMER_EMAIL_REQUIRED`, `CUSTOMER_EMAIL_INVALID`); 500 (transactional rollback only, since 16G PR 3). Note: an email **send** failure does **not** error this endpoint (it returns 201, non-fatal).

---

### 5.2 D.9 — POST `/api/v1/{slug}/orders/{orderId}/invoices/current/resend`

**Purpose.** Re-send the **current accepted** invoice PDF to the customer.

**Request body:** empty `{}` (any field → 400 `VALIDATION_FAILED`). No new signature required.

**Response — `200 OK`:**

```json
{
  "data": {
    "invoice": { "…invoice_detail (E.2); last_emailed_at updated…" }
  },
  "message": "Invoice re-sent to the customer."
}
```

**Business rules:**
1. Order in scope, else 404 `ORDER_NOT_FOUND`.
2. A current invoice exists, else 422 `INVOICE_REQUIRED`.
3. Current invoice **accepted** (`accepted_at IS NOT NULL`), else 422 `INVOICE_NOT_ACCEPTED`.
4. Customer email present and valid (§12), else 422 `CUSTOMER_EMAIL_REQUIRED` / `CUSTOMER_EMAIL_INVALID`.
5. Email the current accepted PDF. On success update `last_emailed_at` **in place** to the send timestamp on **both** `invoice` and the `sales_order` mirror — no new invoice version is created. On provider failure → 502 `EMAIL_SEND_FAILED` (here the send is the whole operation, so failure is fatal for this call; **nothing is written** — the current invoice's `last_emailed_at` and the `sales_order` mirror are **unchanged** — and the salesperson may retry).

**LAID:** allowed (re-emails an existing artifact).

**Status codes:** 200; 400 (`VALIDATION_FAILED`); 401; 403; 404 (`ORDER_NOT_FOUND`); 422 (`INVOICE_REQUIRED`, `INVOICE_NOT_ACCEPTED`, `CUSTOMER_EMAIL_REQUIRED`, `CUSTOMER_EMAIL_INVALID`); 502 (`EMAIL_SEND_FAILED`); 500.

16G leaves D.9 unchanged: it is the manual route for re-emailing the signed PDF, including after a failed or skipped automatic email. Sending an unsigned invoice as a link is a separate endpoint (16G contract §8). D.9's unlocked split read and delayed stamp belong to the F49 follow-up, [#148](https://github.com/MuneebHash/flooring-sales-portal/issues/148).

---

### 5.3 Signature GET — GET `/api/v1/{slug}/orders/{orderId}/invoices/current/signature`

**Purpose.** Stream the **accepted signature image** for the current invoice (file-binary exception). For displaying the captured signature in the app; **not** an old-version signature-history endpoint.

**Label note.** This read has no D-number; refer to it by its path or as section 5.3. In the invoice and payment contracts `D.10` is the payment void (Chunk 4 D.10); Chunk 3's `D.10` is the sale-price PUT (labels are scoped to their chunk). Code comments that call this signature read "D.10" are stale.

**Response — `200 OK`:** raw image bytes.
- `Content-Type: image/png`
- `Content-Disposition: inline; filename="signature-{order_number}-v{version_number}.png"` (backend-built from the locked order number + current `version_number`, e.g. `signature-SYD-CBD.LC1.00042-v4.png`)
- `Content-Length`: file size

Errors use the standard JSON error wrapper (conventions §3).

**Business rules:**
1. Order in scope, else 404 `ORDER_NOT_FOUND`.
2. A current invoice exists, else 404 `INVOICE_NOT_FOUND`.
3. The current invoice has an accepted signature (`accepted_signature_file_id IS NOT NULL`), else 422 `INVOICE_NOT_ACCEPTED`. (The frontend only calls this when `accepted_signature_present == true`; this is a defensive guard. `INVOICE_NOT_ACCEPTED` is 422 everywhere for a stable code↔status mapping.)
4. Stream the linked `stored_file`. `storage_path` is internal and never returned. File-missing-on-disk → 500.

**LAID:** allowed (read).

**Status codes:** 200; 400; 401; 403; 404 (`ORDER_NOT_FOUND`, `INVOICE_NOT_FOUND`); 422 (`INVOICE_NOT_ACCEPTED`); 500.

---

### 5.4 Existing endpoints — Phase 13 deltas

- **`D.1` POST `…/invoices` (Create).** Now requires a valid customer email (§12) — evaluated **before** the 9 invoice preconditions; failure → 422 `CUSTOMER_EMAIL_REQUIRED` / `CUSTOMER_EMAIL_INVALID`. Still creates an **unsigned/unaccepted** invoice (`accepted_at` null, `last_emailed_at` null). **No email is sent on Create.** Sets the dashboard mirror `sales_order.last_emailed_at = null` (§11.1). Response `invoice_detail` now carries the five acceptance/email fields (all null/false).
- **`D.2` POST `…/invoices/rewrite` (Rewrite).** Now requires a valid customer email (§12). See §7 for the full rewrite-after-acceptance behaviour. Rewrite remains **blocked when LAID** → 422 `ORDER_LOCKED`. *(Batch A PR 2, #149)* The request is the mandatory JSON body `{"expected_version_number": N}` (the version the Details of Sale confirmation was bound to), compared under the order lock with the current version before the email gate; a mismatch → 409 `INVOICE_VERSION_STALE` with nothing written ([16G contract §5.8](API-Contracts-Phase16G-Invoice-Link.md#58-d2-rewrite-order-of-checks-batch-a-pr-2-149)). Before Batch A PR 2 the body had to be `{}`.
- **`D.7` POST `…/payments` (Record payment).** See §6. A payment never requires acceptance; if the current invoice was accepted, acceptance/signature carries forward (the customer does not re-sign) and the regenerated signed PDF is produced — but **Phase 15D: no email is sent** (the old "re-email after a payment on an accepted invoice" behaviour was removed). `last_emailed_at` stays null and the message is `"Payment recorded. Current invoice updated."`. To email the updated PDF, use Re-send (`D.9`).
- **`D.10` POST `…/payments/{paymentTransactionId}/void` (Void payment — Phase 15D).** See §6 and Chunk 4 D.10. Soft-voids a payment and regenerates the current invoice exactly like `D.7` (acceptance/signature carries forward, no re-sign, no email); the voided payment is excluded from the active `total_paid`.
- **`D.3` GET `…/invoices/current`**, **`D.4` GET `…/invoices/current/file`** — unchanged behaviour; `D.3`'s `invoice_detail` now carries the five new fields. `D.4` continues to stream the current PDF (which is the **signed** PDF once accepted).
- **16G deltas** ([16G contract](API-Contracts-Phase16G-Invoice-Link.md)): D.1 and D.2 freeze the live Invoice To name, billing lines and terms into the new version, and D.7, D.10 and D.8 copy the previous version's words (PR 2, §4). Since PR 3, D.8 copies `invoice_date` from the current version into its signed version (§5.4; before PR 3 it was the signing day, rule 11 above); remote signing does the same since PR 4 (§10.1), and D.1, D.2, D.7 and D.10 keep dating their version the day it is written. D.2 also marks an `ACTIVE` invoice link `REWRITTEN`; D.7 and D.10 leave it `ACTIVE`, and the link then shows the new version (PR 4, §7.5). D.3 returns the three frozen identity fields and `terms_source` `QUOTE` / `BUSINESS` (PR 2), and the derived `invoice_link_expires_at` (PR 4), on which the Invoice tab polls since PR 6 (§13.2). D.4 is unchanged; the Invoice tab button became Preview PDF (PR 5). The new protected `POST …/invoices/current/send-link` (PR 4, §8) emails an unsigned invoice as a link.

---

## 6. Payment interaction rules

> **Phase 15D update.** Recording a payment (`D.7`) and **voiding** a payment (`D.10`) **never auto-email** — the earlier "re-email the updated signed PDF after a payment on an accepted invoice" behaviour was **removed**. Manual **Re-send** (`D.9`) is the only email action after a payment change. Acceptance/signature still carries forward (the customer never re-signs) and the regenerated signed PDF is still produced and downloadable — it is just not emailed. The rules below reflect this.

Payments are independent of acceptance:
- A payment is **allowed before and after** acceptance. **Recording a payment does not require the invoice to be accepted.** Voiding a payment is likewise allowed regardless of acceptance, and both are **allowed when LAID**.
- Every payment **appends a new current invoice version** with updated `total_paid` / `balance_due` (Chunk 4 F.4), carrying the sale snapshot forward. A **void** does the same, recalculating `total_paid` from the **active (non-voided)** payments so it drops and `balance_due` rises. Overpayment stays blocked (422 `PAYMENT_EXCEEDS_BALANCE`); **no refunds/credits** are introduced.
- **If the current invoice was accepted** when a payment is **recorded or voided**:
  - a **new current invoice version is created**;
  - it **carries forward** the acceptance/signature metadata (`accepted_at`, `accepted_customer_name`, `accepted_signature_file_id`) — the customer does **not** re-accept/re-sign;
  - the regenerated PDF embeds the carried-forward signature;
  - `last_emailed_at` starts `null` and the dashboard mirror `sales_order.last_emailed_at` is reset to that new version's value (`null`) — see §11.1;
  - **no email is sent.** The response message is `"Payment recorded. Current invoice updated."` (record) or `"Payment voided. Current invoice updated."` (void). To email the updated signed PDF, the salesperson uses **Re-send** (`D.9`).
- **If the current invoice was not accepted**:
  - the payment is still recorded / voided and a new **unaccepted** version is created (`accepted_at` null, `last_emailed_at` null, mirror null);
  - **no email is sent.**

This preserves both invariants: **(1)** payments do not require acceptance, and **(2)** the invoice PDF is emailed **only** by the signing actions and Re-send: **Accept** auto-emails on acceptance, **Re-send** re-emails on demand, and (16G) Create Invoice from the accepted quote (PR 3) and remote signing (PR 4) auto-email the signed PDF after commit. Payment record/void never email, even when they carry a signed version forward. A 16G invoice **link** email (PR 4) carries no PDF and is not an invoice email for these markers.

**Email model.** `EMAIL_SEND_FAILED` / **502** is raised **only** where emailing is the whole operation: an explicit **Re-send** (`D.9`) failure and (16G PR 4) an invoice link send failure, with the message "The invoice link could not be emailed. Please try again.". `D.7` (record) and `D.10` (void) never email and so never return `502` for an email reason. The automatic signed-PDF emails (D.8, and in 16G Path A and remote signing) are non-fatal and never return `502`.

**16G link effect.** A payment or a payment void before signing leaves an `ACTIVE` invoice link working: the public page then shows the new version with its Payment Made and Balance Due, and a signature submitted for the old version is refused as stale (16G contract §5, §7.5).

Payment **edit** remains **not allowed**. Payment **void** (soft void) is implemented in Phase 15D (`D.10`); the original `payment_transaction` row is preserved (never hard-deleted) and stays visible in payment history with `voided_at` / `voided_by_name`.

---

## 7. Rewrite (and acceptance) interaction rules

- **Manual Rewrite** (`D.2`) re-snapshots the **live** order state and is what makes live edits official. It requires a valid customer email (§12) and stays **blocked when LAID** (422 `ORDER_LOCKED`). Since Batch A PR 2 (#149) it also carries the version precondition `expected_version_number`: a stale Rewrite is refused with 409 `INVOICE_VERSION_STALE` and writes nothing, so it never clears a signature on a version the salesperson did not see.
- **Rewrite after an accepted invoice** produces a **new current invoice version that is unsigned/unaccepted**: `accepted_at`, `accepted_customer_name`, and `accepted_signature_file_id` are **cleared (null)** on the new version, and `last_emailed_at` is **null** on it. The customer **must sign/accept again** (the sale changed, so the prior signature no longer applies). No email is sent by Rewrite. **Critically, the dashboard mirror `sales_order.last_emailed_at` must be reset to `null`** to match the new unemailed current invoice (§11.1) — otherwise the dashboard would keep showing the *previous* accepted version's emailed time for an invoice that now needs re-acceptance.
- **Acceptance does not mutate** an accepted invoice. Once accepted, the only ways the current invoice changes are: a **payment** or **payment void** (carries acceptance forward; *Phase 15D: no auto-email* — §6), a **manual Rewrite** (clears acceptance, requires re-acceptance — above), or (16F) **Create Invoice from the accepted quote** (Path A) when the quote was signed strictly later than the current invoice (a new version carrying the quote's signature; the earlier signed version stays in history — 16B §7.1). There is no "edit accepted invoice" path, and no remote signing of a signed invoice (16G: the send-link refuses a signed invoice).
- Acceptance itself (`D.8`) **appends** a new current version carrying the snapshot forward (it does not re-snapshot live state), so accepting never silently makes unsent live edits official. Remote signing (16G PR 4) appends in the same way.

Summary of acceptance state per transition (on the resulting **current** invoice):

| Transition | accepted_at | signature | email sent |
|------------|-------------|-----------|------------|
| Create (`D.1`) | null | none | no |
| Manual Rewrite (`D.2`) | null (cleared) | none (cleared) | no |
| Accept (`D.8`) | set | stored | yes (auto, signed PDF) |
| Resend (`D.9`) | unchanged | unchanged | yes (re-send) |
| Payment (`D.7`) on **accepted** invoice | carried forward | carried forward | no — Phase 15D (Re-send to email) |
| Payment (`D.7`) on **unaccepted** invoice | null | none | no |
| Payment void (`D.10`) on **accepted** invoice | carried forward | carried forward | no — Phase 15D (Re-send to email) |
| Payment void (`D.10`) on **unaccepted** invoice | null | none | no |
| Create Invoice from the accepted quote (Path A, 16F) | inherited from the quote | the quote's signature file | **yes since 16G PR 3** (auto, signed PDF, after commit; skipped with a WARN when the saved email is missing or invalid; no email gate; no email before PR 3) |
| Remote invoice accept (16G PR 4) | set | stored (normalised PNG) | yes (auto, signed PDF, after commit; skipped with a WARN when the saved email is missing or invalid; no email gate) |
| Invoice link send (16G PR 4) | unchanged (no new version) | unchanged | a link email only, no PDF; never stamps `last_emailed_at` |

---

## 8. PDF rules

- The **Invoice tab** displays the final customer-facing invoice layout; the **downloaded/emailed PDF visually matches** it as closely as practical.
- The PDF includes: store logo/header styling, customer details, billing address, details of sale, totals, **total paid**, **balance due**, terms, and a **customer signature area**.
- **Before acceptance:** the signature area is **blank** (the PDF is still downloadable via `D.4`).
- **After acceptance:** the PDF **embeds the accepted signature image**, the **accepted customer name**, and the **accepted timestamp**.
- PDFs are produced **only as side effects** of Create (`D.1`), Rewrite (`D.2`), Payment (`D.7`), Payment void (`D.10`), **Accept (`D.8`)**, Create Invoice from the accepted quote (Path A, 16F) and, since 16G PR 4, remote invoice signing — there is no standalone (re)generate endpoint. Each invoice version has its own UNIQUE `stored_file_id`; the current PDF is always rebuilt fresh, never derived from a previous PDF. From 16G PR 2 every render uses the version's own frozen Invoice To name, billing lines and terms (16G contract §4.3).
- Download is `D.4` (`…/invoices/current/file`), `application/pdf`, credentialed blob, `Content-Disposition: inline; filename="invoice-{order_number}-v{version_number}.pdf"`.
- Implementation extends the existing `templates/invoice.html` (openhtmltopdf-pdfbox + Thymeleaf) with a signature/acceptance block, and the `InvoicePdfGenerator` model with `accepted_customer_name`, `accepted_at`, and the signature image. **Full invoice-history UI is not MVP.**

---

## 9. Email rules

- **On Accept (`D.8`)**, the backend **automatically emails** the accepted invoice PDF to the customer.
- **16G: a signed invoice is always emailed, by any signing route.** From 16G PR 3, Create Invoice from the accepted quote (Path A) also emails the new signed invoice PDF after commit, and since 16G PR 4 so does remote invoice signing. Both are non-fatal, have **no** email gate, and skip the send with a WARN log when the saved email is missing or invalid ([16G contract §6](API-Contracts-Phase16G-Invoice-Link.md#6-signed-invoices-are-emailed-after-commit)). Payment record and void never email.
- **16G PR 4 invoice link email:** the send-link emails the customer a link (`{app.public-base-url}/i/{token}`) to review and sign an **unsigned** current invoice, with no PDF attached, through its own sender. It records delivery on the link (`invoice_token`) only, never on `invoice.last_emailed_at` or the mirror (16G contract §8).
- The customer **email comes from the current order's customer record** (`order_customer.email`).
- A valid customer email must exist before invoice **creation, rewrite, acceptance (D.8), Re-send** and (16G PR 4) **the link send** (§12). The **email-format gate is a fatal precondition** (422 before any state change); the **actual send** on Accept is **non-fatal** (acceptance persists; `last_emailed_at` stays null on failure; Re-send retries). Path A and remote signing have no gate (above).
- **Re-send Invoice** (`D.9`) is shown once the current invoice is **accepted**, including when the initial automatic email failed (an accepted invoice with `last_emailed_at = null`). Re-send depends on the invoice being **accepted**, **not** on a prior successful email. It emails the current accepted PDF and, on success, updates `last_emailed_at`.
- `last_emailed_at` is stored on the invoice and shown on the Invoice screen; `sales_order.last_emailed_at` is a **dashboard mirror that always equals the current invoice's `last_emailed_at`, including `null`** — set to the new current version's value whenever a version is created, and to the send timestamp on a successful signed-PDF email (§11.1).
- The invoice **PDF** is never emailed before signature/acceptance. Before acceptance the salesperson can open the PDF (`D.4`; the button is Preview PDF since 16G PR 5, Download PDF before it) and, since 16G PR 4, email the customer a **link** to the unsigned invoice (above).
- The **email provider is provider-independent** behind the backend (SendGrid / SES / Resend / SMTP — **not** decided here). The contract only requires: a provider-independent endpoint behaviour, a clear `EMAIL_SEND_FAILED` (502) on provider failure (Resend path), and the non-fatal accept-send behaviour above.
- Email content/branding may be **simple MVP wording** (e.g. subject "Your invoice from {business_name}", a short body, the accepted invoice PDF attached). **Advanced email audit history is post-MVP.**

---

## 10. Frontend UX contract (Invoice tab)

Behavioural contract (implemented in Phase 13 and amended since; the 16G Invoice tab changes are in [the UX lock §20](Phase16D-Quotation-UX-Lock.md#20-invoice-tab-and-public-invoice-page-16g)):
- **Signature pad** on the Invoice tab: the customer draws their signature on the iPad/touchscreen (canvas → `image/png`). The **accepted customer name** is displayed with the signature and is never typed: since 16G PR 3 it is the displayed version's frozen `customer_name_snapshot` (before PR 3 it was derived from the saved customer record), with no length limit.
- **Accept Invoice** button appears under the signature section and is **enabled only when a signature has been drawn**, both declarations are ticked and the version's frozen name is not blank. It calls `D.8` with the `expected_version_number` of the invoice on screen and no name part (since 16G PR 3).
- **Stale version (16G PR 3, implemented):** on 409 `INVOICE_VERSION_STALE` the tab clears the signature pad and the declaration ticks (even if the re-read then fails), shows the message, re-reads the current invoice and never retries on its own. Its existing best-effort re-read before posting stays as an early hint, with its existing message ("The invoice changed since it was loaded. Please review the updated invoice and sign again.").
- **Customer-email error** is surfaced **at/before invoice creation**, not deferred to acceptance: if Create/Rewrite returns `CUSTOMER_EMAIL_REQUIRED` / `CUSTOMER_EMAIL_INVALID`, prompt the user to add/fix the customer email (Customer tab) before continuing.
- **State rendering** is derived from the current invoice fields (no invoice-status enum):
  - `accepted_at == null` → show the signature pad + Accept Invoice; **Preview PDF** allowed (**Download PDF** before 16G PR 5); no PDF send control. Since 16G PR 5 the bottom bar also offers **Accept Remotely**, which emails the customer a link to the unsigned invoice (no ink or ticks needed; no link status line and no cancel).
  - `accepted_at != null && last_emailed_at != null` → show **emailed success** state (with `last_emailed_at`) + **Re-send Invoice**; display the captured signature via `accepted_signature_download_path`.
  - `accepted_at != null && last_emailed_at == null` → accepted but email failed → show a clear "email failed" notice + **Re-send Invoice**.
- After a **payment** (`D.7`) or **payment void** (`D.10`) on an accepted invoice, the response's `current_invoice` carries acceptance/signature forward but `last_emailed_at` is **`null`** (*Phase 15D: payment record/void never auto-email*). The UI therefore shows the **accepted-but-not-emailed** state (the captured signature is still displayed) with **Re-send Invoice** offered — it does **not** show a fresh emailed time until the salesperson Re-sends (`D.9`).
- **No PDF send is offered before acceptance.** The only pre-acceptance send is the 16G link (**Accept Remotely**, PR 5), and a signed invoice never shows Accept Remotely. Preview PDF (Download PDF before 16G PR 5) is always offered.

---

## 11. Dashboard contract

The dashboard list (`GET /api/v1/{slug}/orders`, Chunk 1) shows, per order row (`DashboardOrderRow`), **both** (a) whether the current invoice has been accepted and (b) when the invoice was last emailed — **without** an invoice-status enum. Each row exposes these two fields together:

- **`invoice_accepted`** — `boolean` (**new in Phase 13**). Whether the order's current invoice is accepted: `true` iff its `accepted_at IS NOT NULL`; `false` when unaccepted or when no invoice exists.
- **`last_emailed_at`** — `string | null` (ISO `YYYY-MM-DDTHH:mm:ss` timestamp or `null`). The date/time the **current** invoice's signed PDF was last emailed to the customer (an invoice link email never counts). This field is **already present** on the dashboard row (sourced from `sales_order.last_emailed_at`), so Phase 13 does **not** add or duplicate it — it only redefines how the source column is maintained (below).

Both fields are **required keys** on the row (`invoice_accepted` always a boolean; `last_emailed_at` always present, value `null` when the current invoice has not been emailed). The dashboard **must not** expose a full invoice-status enum, and no other dashboard fields change.

### 11.1 Dashboard mirror invariant (`sales_order.last_emailed_at`)

**General rule.** `sales_order.last_emailed_at` is a **dashboard mirror of the CURRENT invoice's `last_emailed_at`** — it must always equal `invoice.last_emailed_at` for the current (`max(version_number)`) invoice, **including `null`**.

- Whenever a **new current invoice version is created** (Create, Rewrite, Accept, payment, payment void, Create Invoice from the accepted quote (Path A) and, since 16G PR 4, remote signing), the mirror must be set to **that new current invoice's** `last_emailed_at` — which is `null` at the moment the version is created, and only becomes a timestamp if/when that version's email succeeds.
- On a **successful signed-PDF email** for the current invoice, set **both** `invoice.last_emailed_at` and `sales_order.last_emailed_at` to the **same** timestamp. The signed-PDF email paths are the Accept auto-email and Re-send; from 16G PR 3 also the Path A auto-email, and since 16G PR 4 the remote-signing auto-email. *Phase 15D: payment record/void never email.* An invoice **link** send (16G PR 4) never moves either value; its delivery markers live on `invoice_token` only.
- The dashboard must **never** show an old emailed timestamp carried over from a **previous** invoice version when the current invoice has not (yet) been emailed. (This is the failure the rule prevents: e.g. a payment or payment void after acceptance creates a new current invoice version with `last_emailed_at = null` and sends no email, so the mirror must also become `null` — not retain the prior version's timestamp.)

**Per-operation mirror behaviour** (current invoice's `last_emailed_at` == `sales_order.last_emailed_at` in every row):

| Operation | New current invoice's `last_emailed_at` | `sales_order.last_emailed_at` (mirror) |
|-----------|------------------------------------------|-----------------------------------------|
| Create Invoice (`D.1`) | `null` (unsigned/unemailed) | `null` |
| Rewrite Invoice (`D.2`) | `null` (unsigned/unemailed; even when rewriting a previously accepted/emailed invoice) | `null` |
| Accept (`D.8`) — email succeeds | `<timestamp>` | `<same timestamp>` |
| Accept (`D.8`) — email fails | `null` (acceptance still persists) | `null` |
| Resend (`D.9`) — succeeds | `<timestamp>` | `<same timestamp>` |
| Resend (`D.9`) — fails (502) | unchanged (no new version; no success timestamp written) | unchanged |
| Payment after **accepted** (`D.7`) | `null` (new version; **no auto-email** — Phase 15D; `201`; Re-send to email) | `null` |
| Payment **before** acceptance (`D.7`) | `null` (unaccepted/unemailed) | `null` |
| Payment **void** (`D.10`), accepted or not | `null` (new version; **no auto-email** — Phase 15D; `201`; Re-send to email) | `null` |
| Create Invoice from the accepted quote (Path A) — before 16G PR 3 | `null` (new version; no email) | `null` |
| Path A — from 16G PR 3, email succeeds | `<timestamp>` | `<same timestamp>` |
| Path A — from 16G PR 3, email fails (or its stamp fails) | `null` (the invoice is still created; `201`; Re-send to email) | `null` |
| Path A — from 16G PR 3, saved email missing or invalid (send skipped, WARN) | `null` | `null` |
| Accept (`D.8`) — from 16G PR 3, a sender runtime failure or a stamp failure after commit | `null` (acceptance persists; `201`, never `500`) | `null` |
| Remote invoice accept (16G PR 4) — email succeeds | `<timestamp>` | `<same timestamp>` |
| Remote invoice accept (16G PR 4) — email fails, is skipped, or its stamp fails | `null` | `null` |
| Invoice link send (16G PR 4), delivered or failed | unchanged (no new version; only `invoice_token.sent_at` / `emailed_at`) | unchanged |
| Store notification on remote signing (16G PR 4) | unchanged | unchanged |

Resend and the invoice link send are the only email operations that do **not** create a new current version. On a Resend **failure** nothing is written and the current invoice's `last_emailed_at` (and therefore the mirror) is **unchanged**; on Resend success both are set to the new timestamp. A link send never touches either value. Known limitation (audit F49, tracked as [#148](https://github.com/MuneebHash/flooring-sales-portal/issues/148)): when another version is appended between a D.8 or D.9 email and its stamp, the emailed version keeps its own stamp and the mirror follows the newer current version (`null`), as this rule requires, while the response describes the newer version.

---

## 12. Error contract

Error envelope is unchanged (conventions §3): `{ "error": { "code", "message", "details"? } }`, codes `UPPER_SNAKE_CASE`. **New** Phase 13 codes (to be added to `com.flooring.salesportal.common.error.ErrorCode` in the existing style — `NAME(HttpStatus.X, "Sentence-case message.")`):

| Code | HTTP | Default message | Where |
|------|------|-----------------|-------|
| `CUSTOMER_EMAIL_REQUIRED` | 422 | "A valid customer email is required before this action." | `D.1`, `D.2`, `D.8`, `D.9`; 16G send-link (PR 4). Never Path A or remote signing. |
| `CUSTOMER_EMAIL_INVALID` | 422 | "Customer email is not a valid email address." | `D.1`, `D.2`, `D.8`, `D.9`; 16G send-link (PR 4). Never Path A or remote signing. |
| `ACCEPTED_CUSTOMER_NAME_REQUIRED` | 422 | "Accepted customer name is required." | `D.8` (since 16G PR 3 a blank frozen `customer_name_snapshot` on the current version; before PR 3 a blank name part); 16G remote accept (PR 4), with the public sentence "This invoice can no longer be accepted online. Please contact the store." |
| `SIGNATURE_REQUIRED` | 422 | "A customer signature is required to accept the invoice." | `D.8`; 16G remote accept (PR 4) |
| `SIGNATURE_INVALID` | 400 | "Signature must be a PNG image no larger than 2 MB." | `D.8`; 16G remote accept (PR 4, with the public PNG rules) |
| `INVOICE_ALREADY_ACCEPTED` | 409 | "This invoice has already been accepted. Use Re-send to email it again." | `D.8`; 16G send-link on a signed invoice (PR 4). Path A uses its own message (16B §7.1). |
| `INVOICE_NOT_ACCEPTED` | 422 | "This invoice has not been accepted yet." | `D.9`, signature GET (§5.3) |
| `EMAIL_SEND_FAILED` | 502 | "The invoice could not be emailed. Please try again." | `D.9`, and (16G PR 4) the invoice link send with "The invoice link could not be emailed. Please try again.". The automatic signed-PDF emails (`D.8`; Path A since 16G PR 3; since 16G PR 4 remote signing) are non-fatal: a failure shows as `last_emailed_at = null` with a success message, **without** this error and **without** 502. Payment record and void never email. |

**16G codes** (full table and messages: [16G contract §14](API-Contracts-Phase16G-Invoice-Link.md#14-error-contract)): `INVOICE_VERSION_STALE` (409, "Invoice updated, please refresh"; `D.8` since 16G PR 3, remote accept and the public PDF read since PR 4, `D.2` Rewrite since Batch A PR 2, #149); `QUOTE_VERSION_STALE` (409, Path A, since PR 3); `INVOICE_TOKEN_NOT_FOUND` (404, "Invoice not found.") and `INVOICE_LINK_REPLACED` / `INVOICE_LINK_REWRITTEN` / `INVOICE_LINK_SIGNED` / `INVOICE_LINK_EXPIRED` (410) on the public invoice endpoints (PR 4).

**Reused** existing codes (do **not** rename or duplicate):

| Code | HTTP | Phase 13 use |
|------|------|--------------|
| `INVOICE_REQUIRED` | 422 | No current invoice exists for Accept/Resend |
| `INVOICE_NOT_FOUND` | 404 | No current invoice for the signature `GET` (empty state) |
| `INVOICE_PRECONDITIONS_NOT_MET` | 422 | The existing 9 Create/Rewrite preconditions (email gate is separate, above) |
| `PAYMENT_EXCEEDS_BALANCE` | 422 | Overpayment on `D.7` (unchanged) |
| `ORDER_LOCKED` | 422 | Rewrite blocked when LAID (unchanged); Accept/Resend/signature are allowed when LAID |
| `ORDER_NOT_FOUND` | 404 | Order not in scope (cross-tenant returns 404, never 403) |
| `VALIDATION_FAILED` | 400 | Malformed body, non-empty Resend body, unexpected or repeated multipart part (since 16G PR 3 including an `accepted_customer_name` part on `D.8`), and since 16G PR 3 a missing, repeated or malformed `expected_version_number` |

Clarifications:
- **Email gate vs preconditions.** On Create/Rewrite the email gate (`CUSTOMER_EMAIL_*`) is evaluated **before** the 9 `INVOICE_PRECONDITIONS_NOT_MET` checks; if the email gate fails, that request returns the email code and the 9-precondition check is skipped (both are 422; the frontend handles each). Email validation is stricter than the DB `email LIKE '%@%'` check; implementations should use a standard email-format validator.
- **Rewrite after accepted ≠ error.** Producing a new unsigned/unaccepted version on Rewrite is expected behaviour (§7), not an error.
- **Payment before acceptance ≠ error.** Allowed; simply no email is sent (§6).
- **`EMAIL_SEND_FAILED` status.** 502 Bad Gateway (upstream/provider dependency failure; added to conventions §4). Used by Resend and (16G PR 4) the invoice link send; **not** raised by Accept (Accept's send is non-fatal).

---

## 13. Implementation notes / risks

- **Migration `V10` only** (applied). Added the four nullable `invoice` columns (§4); `accepted_signature_file_id` is a **non-unique** FK → `stored_file`. No enum change is needed (`attachment_kind = 'SIGNATURE'` already exists and stays unused by this flow).
- **Append-only preserved.** Accept, payment, payment void, Create Invoice from the accepted quote (Path A) and (16G PR 4) remote signing **append** new invoice versions; only `last_emailed_at` is updated in place (Resend, and the post-commit email-success stamps after Accept, after Path A from 16G PR 3 and after remote signing since 16G PR 4). Carry-forward on Accept/Payment/Void copies the acceptance fields (and, from 16G PR 2, the frozen words); Rewrite **nulls** the acceptance fields and freezes fresh words.
- **Transaction boundaries.** Persist acceptance + signed PDF **in one transaction** (file-write-first + `TransactionSynchronization` rollback-cleanup, mirroring the existing attachment/invoice write pattern). The **email send happens after commit**; an email failure must **not** roll back acceptance. Set `last_emailed_at` in a short follow-up update on send success. From 16G PR 3 no post-commit step (send or stamp) can turn a committed acceptance into an error (16G contract §6.2). *(Phase 15D: payment record (`D.7`) and payment void (`D.10`) **no longer email**, so there is no post-commit email step on those paths — each commits the new invoice version with `last_emailed_at = null` and stops; the salesperson uses Re-send (`D.9`) to email the updated signed PDF.)*
- **Signature ingestion is invoice-specific.** Reuse `FileStorageService.store(...)` + a `stored_file` row; **do not** create an `order_attachment` row, so the signature never appears in the Notes & Photos list. Validate `image/png` + 2 MB before persisting.
- **Signed PDF reuse.** `D.4` streams whatever the current invoice's `stored_file` is — once accepted, that's the signed PDF. No separate "signed PDF" endpoint is needed.
- **Dashboard accuracy (mirror invariant).** The dashboard reads `sales_order.last_emailed_at`, which must always equal the **current** invoice's `last_emailed_at` (§11.1). Whenever a new current invoice version is created (Create, Rewrite, Accept, payment, payment void, Path A and, since 16G PR 4, remote signing), set the mirror to that new version's value — `null` at creation — and update it to the timestamp only on a successful signed-PDF email; on Resend failure leave it unchanged, and never move it for an invoice link send. Do **not** stamp the mirror only on success: that would leave a stale timestamp on the row when the new current invoice is unemailed (e.g. rewrite-after-accepted, or a payment version, which never emails). Also add `invoice_accepted` (derived from the current invoice's `accepted_at`). This touched the Chunk 1 dashboard query/DTO (documented here, in `openapi.yaml` and, since 16G PR 1, in the Chunk 1 field rules).
- **Email provider.** Keep provider behind an interface; configuration (API key, from-address per business/store) is implementation detail. `EMAIL_SEND_FAILED` (502) is the provider-failure signal for Resend.
- **Risks:** (a) email deliverability / spam classification (provider + from-domain setup); (b) signature image size/quality from canvas (cap at 2 MB, PNG); (c) *Phase 15D removed payment auto-email, so the earlier double-send risk (Accept's own email plus a near-simultaneous payment email) no longer applies — payment record (`D.7`) and payment void (`D.10`) never email; the automatic emails are the signed-PDF emails at signing or conversion (Accept `D.8`; from 16G PR 3 Create Invoice from the accepted quote; since 16G PR 4 remote signing), and Re-send (`D.9`) is the explicit manual path*; (d) ensure the carried-forward signature `stored_file` is **not** deleted while still referenced by a newer version (non-unique FK; only delete orphaned PDF files, never the signature unless no version references it).

---

## Appendix — OpenAPI alignment

`docs/openapi.yaml` is a maintained source of truth (it already carries the full Phase 12 invoice/payment endpoints). Phase 13 updates it additively:
- New paths `POST …/invoices/current/accept`, `POST …/invoices/current/resend`, `GET …/invoices/current/signature` (tag `Invoices`; operationIds `acceptCurrentInvoice`, `resendCurrentInvoice`, `downloadCurrentInvoiceSignature`).
- `InvoiceDetail` and `CurrentInvoiceSummary` gain the five acceptance/email fields.
- New `SignatureAcceptRequest` multipart schema; new `BadGateway` (502) shared response; `EMAIL_SEND_FAILED` is conveyed through the existing free-form `error.code` string (OpenAPI does not enumerate code strings).
- `DashboardOrderRow` gains `invoice_accepted`.
- **16G (implemented):** `SignatureAcceptRequest` becomes `expected_version_number` + `signature` (PR 3; the `accepted_customer_name` property and its 150-character limit are removed); `InvoiceDetail` gains the three frozen identity fields and the `terms_source` enum `QUOTE` / `BUSINESS` (PR 2), and the derived `invoice_link_expires_at` (PR 4); `DashboardOrderRow.last_emailed_at` describes the mirror as above; the send-link and public invoice paths and schemas (PR 4) are listed in the [16G contract appendix](API-Contracts-Phase16G-Invoice-Link.md#appendix--openapi-alignment).