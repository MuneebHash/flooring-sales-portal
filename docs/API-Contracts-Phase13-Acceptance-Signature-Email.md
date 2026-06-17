# Sales Portal MVP — API Contracts · Phase 13 (Invoice Acceptance + Signature + Email) (Lock-Ready)

**Status:** Phase 13 MVP contract. Additive to the locked **Phase 12 / Chunk 4** invoices + payments contract (`docs/API-Contracts-Chunk-4.md`). This branch is **docs/contracts only** — no backend code, no frontend code, no migration, no commit.

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

Phase 13 is a **practical MVP signature-capture + email workflow**, not a full legal e-sign platform. It keeps the Phase 12 *current-invoice-only* model: no invoice-status enum, no version history UI, no old-version access.

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

- Customer **email is required and valid** before Create Invoice, Rewrite Invoice, Accept, and Resend. Backend contract rule, not frontend-only.
- Customer **signature capture** on the Invoice tab (draw on iPad/touchscreen); **accepted customer name** captured alongside.
- **Accept Invoice** (one endpoint): requires an existing current invoice, a signature, an accepted name, and a valid customer email. Stores `accepted_at`, `accepted_customer_name`, and a signature file reference; regenerates the signed PDF; **auto-emails** the accepted PDF; updates `last_emailed_at`.
- Accepted invoice is **not directly mutated** afterward (see §4, §7).
- **Re-send Invoice**: re-emails the current accepted invoice PDF; updates `last_emailed_at`. Re-send Invoice is shown once the current invoice is **accepted**, including when the initial automatic email failed — it does **not** depend on a prior successful email (an accepted invoice with `last_emailed_at = null` can still be re-sent).
- **Download PDF** remains available before acceptance (Phase 12 `D.4`). **Manual send before signature/acceptance is not allowed** (there is no pre-acceptance send endpoint).
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

Phase 13 adds four nullable columns to the existing `invoice` table. **No migration is created on this branch** — this documents the expected `V10` (next migration after the existing `V9`). `V1`–`V6` remain locked.

**`V10` — `invoice` additions (contract-level; do not create here):**

| Column | Type | Null | Notes |
|--------|------|------|-------|
| `accepted_at` | `TIMESTAMP` | NULL | Set when the current invoice is accepted; null = unaccepted. |
| `accepted_customer_name` | `VARCHAR(150)` | NULL | Name captured with the signature. |
| `accepted_signature_file_id` | `BIGINT` | NULL | FK → `stored_file(stored_file_id)`. **Not unique** (a carried-forward version references the same signature file). |
| `last_emailed_at` | `TIMESTAMP` | NULL | Last time **this invoice version** was emailed; null = never. Mutable delivery marker (see below). |

Notes:
- `accepted_signature_file_id` is a **non-unique** nullable FK (contrast `invoice.stored_file_id`, the PDF, which is UNIQUE per row). Acceptance and payment-carry-forward may point multiple invoice versions at the same signature `stored_file`.
- **Append-only vs mutable.** The `invoice` table is append-only today (Create / Rewrite / Payment each *insert* a new row; the current invoice is `max(version_number)`). Phase 13 preserves this:
  - The **sale snapshot** and the **acceptance fields** (`accepted_at`, `accepted_customer_name`, `accepted_signature_file_id`) are **immutable once written** — acceptance does not mutate an existing row; it **appends** a new current version (§5.1, §6).
  - `last_emailed_at` is a **delivery marker**, not part of the immutable snapshot; it **may be updated in place** on the current invoice row (e.g. Resend bumps it without creating a version).
- **Signature storage** reuses the existing `stored_file` + `FileStorageService` mechanism (server-internal `storage_path` under `/uploads/{businessId}/orders/{orderId}/{uuid}.png`, **never** exposed). The signature is **not** inserted into `order_attachment` and **not** surfaced in the Notes & Photos attachments list. The `attachment_kind` enum value `SIGNATURE` (already present in `V1`, still rejected by the Chunk 3 attachment-upload endpoint) is **not** required for this; signatures are invoice-specific and linked only via `invoice.accepted_signature_file_id`.
- **Customer email** lives on `order_customer.email VARCHAR(255) NOT NULL` (DB CHECK `trim(email) <> '' AND email LIKE '%@%'`). One row per order. The Phase 12 invoice preconditions do **not** require email; Phase 13's email gate (§12) is the dedicated check, validating more strictly than the DB `LIKE '%@%'`.
- **Dashboard mirror.** `sales_order.last_emailed_at` already exists (`V2`) and already feeds the dashboard row. Phase 13 redefines it as a **mirror of the CURRENT invoice's `last_emailed_at`** — it must always equal `invoice.last_emailed_at` for the current version, **including `null`**: it is set to the new current invoice's value (`null`) whenever a new version is created, and to the timestamp on a successful email. This prevents the dashboard from showing a stale "emailed" time from a previous version after a new, unemailed current invoice is created. See the **§11.1 mirror invariant** for the per-operation table. `invoice.last_emailed_at` remains the authoritative per-invoice marker shown on the Invoice tab.
- Money stays `DECIMAL(10,2)`, scale 2, `HALF_UP`. Timestamps are `YYYY-MM-DDTHH:mm:ss` (server-local, no timezone). Dates are `YYYY-MM-DD`.

**No** invoice-status column is added. **No** `V*` change to `order_customer`, `payment_transaction`, or `stored_file` is required *for Phase 13*. *(Phase 15D adds the soft-void columns `voided_at` + `voided_by_user_id` to `payment_transaction` via migration `V14` — see Chunk 4 D.10.)*

---

## 5. Endpoint contracts

All Phase 13 endpoints are **Standard protected** (all 7 conventions §9 checks), scoped to the session `(business_id, store_id)`, and resolve the **current invoice** as the `max(version_number)` row for the order. Cross-tenant / cross-store / cross-order misses return **404**, never 403. Path style and JSON conventions follow Chunk 4 exactly (snake_case bodies; `{ "data": … , "message": … }` success wrapper; `{ "error": { "code", "message", "details" } }` error wrapper).

The invoice DTOs `current_invoice_summary` (E.1) and `invoice_detail` (E.2) from Chunk 4 §E gain **five** fields in Phase 13 (always present; nullable values serialized as `null`):

| Field | Type | Notes |
|-------|------|-------|
| `accepted_at` | timestamp \| null | null = unaccepted |
| `accepted_customer_name` | string \| null | |
| `accepted_signature_present` | boolean | `true` iff a signature is stored |
| `accepted_signature_download_path` | string \| null | relative URL to `D.10`; null when no signature |
| `last_emailed_at` | timestamp \| null | null = never emailed |

Updated **`invoice_detail` (E.2)** example (returned by Phase 12 `D.1`/`D.2`/`D.3` and Phase 13 Accept/Resend):

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
  "accepted_at": "2026-04-22T14:31:10",
  "accepted_customer_name": "Jordan Wilson",
  "accepted_signature_present": true,
  "accepted_signature_download_path": "/api/v1/aussiefloors/orders/1/invoices/current/signature",
  "last_emailed_at": "2026-04-22T14:31:12"
}
```

An **unaccepted** current invoice returns `accepted_at: null`, `accepted_customer_name: null`, `accepted_signature_present: false`, `accepted_signature_download_path: null`, `last_emailed_at: null`. The same five fields are added to **`current_invoice_summary` (E.1)** (returned inside the `D.7` payment response) so the frontend can render acceptance/email state after a payment without an extra `GET`.

`accepted_signature_file_id` and `stored_file.storage_path` are **server-internal** and never returned (mirrors how `stored_file_id` is hidden behind `pdf_download_path`).

---

### 5.1 D.8 — POST `/api/v1/{slug}/orders/{orderId}/invoices/current/accept`

**Purpose.** Accept/sign the **current** invoice, store the signature + acceptance metadata, regenerate the signed PDF, and automatically email the accepted PDF to the customer.

**Scope:** Standard protected. Path param `orderId` — positive integer.

**Request — `multipart/form-data`** (file-binary exception):

| Part | Type | Required | Rules |
|------|------|----------|-------|
| `accepted_customer_name` | text | yes | non-blank after trim; max 150 chars |
| `signature` | file | yes | **`image/png` only**; non-empty; **max 2 MB** (`2_097_152` bytes; `==` allowed, `>` rejected) |

`image/png` is the canonical format (HTML canvas `toBlob('image/png')`). Any other part name → 400 `VALIDATION_FAILED`.

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

The acceptance (signature + `accepted_at`) is **durably persisted even if the email fails**; we never discard a captured signature because the provider hiccuped. After a `201`, `accepted_at` is non-null; `last_emailed_at` is non-null **iff** the auto-email succeeded (null ⇒ email failed ⇒ frontend offers Re-send).

**Business rules (in order):**
1. Order in scope, else 404 `ORDER_NOT_FOUND`.
2. A current invoice exists, else 422 `INVOICE_REQUIRED` (accept the *first* invoice via `D.1` Create before signing).
3. Current invoice **not already accepted** (`accepted_at IS NULL`), else 409 `INVOICE_ALREADY_ACCEPTED` (use Re-send to email an already-accepted invoice).
4. `accepted_customer_name` present/non-blank, else 422 `ACCEPTED_CUSTOMER_NAME_REQUIRED`.
5. `signature` part present and non-empty, else 422 `SIGNATURE_REQUIRED`.
6. `signature` is `image/png` and ≤ 2 MB, else 400 `SIGNATURE_INVALID`.
7. Customer email present and valid (§12), else 422 `CUSTOMER_EMAIL_REQUIRED` / `CUSTOMER_EMAIL_INVALID`.
8. **Persist (one transaction):** store the signature as a `stored_file`; **append** a new current invoice version (`version_number = max + 1`) that **carries forward** the sale snapshot (`details_of_sale_snapshot`, `sale_price_ex_gst`, `sale_price_inc_gst`, `due_date`) and `total_paid`/`balance_due` **unchanged**, sets `accepted_at = now`, `accepted_customer_name`, `accepted_signature_file_id`, and `last_emailed_at = null`; generate the **signed** PDF (`stored_file`, linked via `invoice.stored_file_id`). Set the dashboard mirror `sales_order.last_emailed_at` to this new current version's value (`null`) — see §11.1. PDF/file/insert failure → full rollback.
9. **Post-commit:** email the signed PDF to the customer. On success set `last_emailed_at` to the send timestamp on **both** `invoice` and the `sales_order` mirror. On failure leave **both** `invoice.last_emailed_at` and `sales_order.last_emailed_at` = `null` (non-fatal; see message above).
10. Return the updated current invoice (E.2).

**LAID:** **allowed** (parallels payment, not rewrite — see Chunk 4 F.8). Acceptance carries the snapshot forward and never makes unsent live edits official.

**Status codes:** 201; 400 (`VALIDATION_FAILED`, `SIGNATURE_INVALID`); 401; 403; 404 (`ORDER_NOT_FOUND`); 409 (`INVOICE_ALREADY_ACCEPTED`); 422 (`INVOICE_REQUIRED`, `ACCEPTED_CUSTOMER_NAME_REQUIRED`, `SIGNATURE_REQUIRED`, `CUSTOMER_EMAIL_REQUIRED`, `CUSTOMER_EMAIL_INVALID`); 500 (transactional rollback). Note: an email **send** failure does **not** error this endpoint (it returns 201, non-fatal).

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

---

### 5.3 D.10 — GET `/api/v1/{slug}/orders/{orderId}/invoices/current/signature`

**Purpose.** Stream the **accepted signature image** for the current invoice (file-binary exception). For displaying the captured signature in the app; **not** an old-version signature-history endpoint.

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
- **`D.2` POST `…/invoices/rewrite` (Rewrite).** Now requires a valid customer email (§12). See §7 for the full rewrite-after-acceptance behaviour. Rewrite remains **blocked when LAID** → 422 `ORDER_LOCKED`.
- **`D.7` POST `…/payments` (Record payment).** See §6. A payment never requires acceptance; if the current invoice was accepted, acceptance/signature carries forward (the customer does not re-sign) and the regenerated signed PDF is produced — but **Phase 15D: no email is sent** (the old "re-email after a payment on an accepted invoice" behaviour was removed). `last_emailed_at` stays null and the message is `"Payment recorded. Current invoice updated."`. To email the updated PDF, use Re-send (`D.9`).
- **`D.10` POST `…/payments/{paymentTransactionId}/void` (Void payment — Phase 15D).** See §6 and Chunk 4 D.10. Soft-voids a payment and regenerates the current invoice exactly like `D.7` (acceptance/signature carries forward, no re-sign, no email); the voided payment is excluded from the active `total_paid`.
- **`D.3` GET `…/invoices/current`**, **`D.4` GET `…/invoices/current/file`** — unchanged behaviour; `D.3`'s `invoice_detail` now carries the five new fields. `D.4` continues to stream the current PDF (which is the **signed** PDF once accepted).

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

This preserves both invariants: **(1)** payments do not require acceptance, and **(2)** invoices are emailed **only** via the explicit accepted-flow actions — **Accept** auto-emails on acceptance, and **Re-send** re-emails on demand; payment record/void never email.

**Email model.** `EMAIL_SEND_FAILED` / **502** is raised **only** by an explicit **Re-send** (`D.9`) failure, where emailing is the whole operation. `D.7` (record) and `D.10` (void) never email and so never return `502` for an email reason.

Payment **edit** remains **not allowed**. Payment **void** (soft void) is implemented in Phase 15D (`D.10`); the original `payment_transaction` row is preserved (never hard-deleted) and stays visible in payment history with `voided_at` / `voided_by_name`.

---

## 7. Rewrite (and acceptance) interaction rules

- **Manual Rewrite** (`D.2`) re-snapshots the **live** order state and is what makes live edits official. It requires a valid customer email (§12) and stays **blocked when LAID** (422 `ORDER_LOCKED`).
- **Rewrite after an accepted invoice** produces a **new current invoice version that is unsigned/unaccepted**: `accepted_at`, `accepted_customer_name`, and `accepted_signature_file_id` are **cleared (null)** on the new version, and `last_emailed_at` is **null** on it. The customer **must sign/accept again** (the sale changed, so the prior signature no longer applies). No email is sent by Rewrite. **Critically, the dashboard mirror `sales_order.last_emailed_at` must be reset to `null`** to match the new unemailed current invoice (§11.1) — otherwise the dashboard would keep showing the *previous* accepted version's emailed time for an invoice that now needs re-acceptance.
- **Acceptance does not mutate** an accepted invoice. Once accepted, the only ways the current invoice changes are: a **payment** (carries acceptance forward, re-emails — §6) or a **manual Rewrite** (clears acceptance, requires re-acceptance — above). There is no "edit accepted invoice" path.
- Acceptance itself (`D.8`) **appends** a new current version carrying the snapshot forward (it does not re-snapshot live state), so accepting never silently makes unsent live edits official.

Summary of acceptance state per transition (on the resulting **current** invoice):

| Transition | accepted_at | signature | email sent |
|------------|-------------|-----------|------------|
| Create (`D.1`) | null | none | no |
| Manual Rewrite (`D.2`) | null (cleared) | none (cleared) | no |
| Accept (`D.8`) | set | stored | yes (auto) |
| Resend (`D.9`) | unchanged | unchanged | yes (re-send) |
| Payment (`D.7`) on **accepted** invoice | carried forward | carried forward | yes (auto) |
| Payment (`D.7`) on **unaccepted** invoice | null | none | no |

---

## 8. PDF rules

- The **Invoice tab** displays the final customer-facing invoice layout; the **downloaded/emailed PDF visually matches** it as closely as practical.
- The PDF includes: store logo/header styling, customer details, billing address, details of sale, totals, **total paid**, **balance due**, terms, and a **customer signature area**.
- **Before acceptance:** the signature area is **blank** (the PDF is still downloadable via `D.4`).
- **After acceptance:** the PDF **embeds the accepted signature image**, the **accepted customer name**, and the **accepted timestamp**.
- PDFs are produced **only as side effects** of Create (`D.1`), Rewrite (`D.2`), Payment (`D.7`), and **Accept (`D.8`)** — there is no standalone (re)generate endpoint. Each invoice version has its own UNIQUE `stored_file_id`; the current PDF is always rebuilt fresh, never derived from a previous PDF.
- Download is `D.4` (`…/invoices/current/file`), `application/pdf`, credentialed blob, `Content-Disposition: inline; filename="invoice-{order_number}-v{version_number}.pdf"`.
- Implementation extends the existing `templates/invoice.html` (openhtmltopdf-pdfbox + Thymeleaf) with a signature/acceptance block, and the `InvoicePdfGenerator` model with `accepted_customer_name`, `accepted_at`, and the signature image. **Full invoice-history UI is not MVP.**

---

## 9. Email rules

- **On Accept (`D.8`)**, the backend **automatically emails** the accepted invoice PDF to the customer.
- The customer **email comes from the current order's customer record** (`order_customer.email`).
- A valid customer email must exist before invoice **creation, rewrite, acceptance, and email send** (§12). The **email-format gate is a fatal precondition** (422 before any state change); the **actual send** on Accept is **non-fatal** (acceptance persists; `last_emailed_at` stays null on failure; Re-send retries).
- **Re-send Invoice** (`D.9`) is shown once the current invoice is **accepted**, including when the initial automatic email failed (an accepted invoice with `last_emailed_at = null`). Re-send depends on the invoice being **accepted**, **not** on a prior successful email. It emails the current accepted PDF and, on success, updates `last_emailed_at`.
- `last_emailed_at` is stored on the invoice and shown on the Invoice screen; `sales_order.last_emailed_at` is a **dashboard mirror that always equals the current invoice's `last_emailed_at`, including `null`** — set to the new current version's value whenever a version is created, and to the send timestamp on a successful email (§11.1).
- **Manual send before signature/acceptance is not allowed** — there is no pre-acceptance send endpoint. The customer may still **Download PDF** before acceptance (`D.4`).
- The **email provider is provider-independent** behind the backend (SendGrid / SES / Resend / SMTP — **not** decided here). The contract only requires: a provider-independent endpoint behaviour, a clear `EMAIL_SEND_FAILED` (502) on provider failure (Resend path), and the non-fatal accept-send behaviour above.
- Email content/branding may be **simple MVP wording** (e.g. subject "Your invoice from {business_name}", a short body, the accepted invoice PDF attached). **Advanced email audit history is post-MVP.**

---

## 10. Frontend UX contract (Invoice tab)

Behavioural contract (frontend wiring is **not** implemented on this branch):
- **Signature pad** on the Invoice tab: the customer draws their signature on the iPad/touchscreen (canvas → `image/png`). An **accepted customer name** field is captured/displayed with the signature.
- **Accept Invoice** button appears under the signature section and is **enabled only when a signature has been drawn** (and a name entered). It calls `D.8`.
- **Customer-email error** is surfaced **at/before invoice creation**, not deferred to acceptance: if Create/Rewrite returns `CUSTOMER_EMAIL_REQUIRED` / `CUSTOMER_EMAIL_INVALID`, prompt the user to add/fix the customer email (Customer tab) before continuing.
- **State rendering** is derived from the current invoice fields (no invoice-status enum):
  - `accepted_at == null` → show the signature pad + Accept Invoice; **Download PDF** allowed; **no** send control.
  - `accepted_at != null && last_emailed_at != null` → show **emailed success** state (with `last_emailed_at`) + **Re-send Invoice**; display the captured signature via `accepted_signature_download_path`.
  - `accepted_at != null && last_emailed_at == null` → accepted but email failed → show a clear "email failed" notice + **Re-send Invoice**.
- After a **payment** on an accepted invoice, the `D.7` response's `current_invoice` already carries updated `last_emailed_at` + acceptance fields, so the UI updates the emailed state without a re-fetch.
- **Manual "Send" before acceptance is not offered** in the UI (no endpoint exists). Download PDF is always offered.

---

## 11. Dashboard contract

The dashboard list (`GET /api/v1/{slug}/orders`, Chunk 1) shows, per order row (`DashboardOrderRow`), **both** (a) whether the current invoice has been accepted and (b) when the invoice was last emailed — **without** an invoice-status enum. Each row exposes these two fields together:

- **`invoice_accepted`** — `boolean` (**new in Phase 13**). Whether the order's current invoice is accepted: `true` iff its `accepted_at IS NOT NULL`; `false` when unaccepted or when no invoice exists.
- **`last_emailed_at`** — `string | null` (ISO `YYYY-MM-DDTHH:mm:ss` timestamp or `null`). The date/time the **current** invoice was last emailed to the customer. This field is **already present** on the dashboard row (sourced from `sales_order.last_emailed_at`), so Phase 13 does **not** add or duplicate it — it only redefines how the source column is maintained (below).

Both fields are **required keys** on the row (`invoice_accepted` always a boolean; `last_emailed_at` always present, value `null` when the current invoice has not been emailed). The dashboard **must not** expose a full invoice-status enum, and no other dashboard fields change.

### 11.1 Dashboard mirror invariant (`sales_order.last_emailed_at`)

**General rule.** `sales_order.last_emailed_at` is a **dashboard mirror of the CURRENT invoice's `last_emailed_at`** — it must always equal `invoice.last_emailed_at` for the current (`max(version_number)`) invoice, **including `null`**.

- Whenever a **new current invoice version is created** (Create, Rewrite, Accept, payment), the mirror must be set to **that new current invoice's** `last_emailed_at` — which is `null` at the moment the version is created, and only becomes a timestamp if/when that version's email succeeds.
- On a **successful email** for the current invoice (Accept auto-email, Resend, or payment-after-accepted auto-email), set **both** `invoice.last_emailed_at` and `sales_order.last_emailed_at` to the **same** timestamp.
- The dashboard must **never** show an old emailed timestamp carried over from a **previous** invoice version when the current invoice has not (yet) been emailed. (This is the failure the rule prevents: e.g. a payment-after-accepted whose email fails leaves the new current invoice `last_emailed_at = null`, so the mirror must also become `null` — not retain the prior version's timestamp.)

**Per-operation mirror behaviour** (current invoice's `last_emailed_at` == `sales_order.last_emailed_at` in every row):

| Operation | New current invoice's `last_emailed_at` | `sales_order.last_emailed_at` (mirror) |
|-----------|------------------------------------------|-----------------------------------------|
| Create Invoice (`D.1`) | `null` (unsigned/unemailed) | `null` |
| Rewrite Invoice (`D.2`) | `null` (unsigned/unemailed; even when rewriting a previously accepted/emailed invoice) | `null` |
| Accept (`D.8`) — email succeeds | `<timestamp>` | `<same timestamp>` |
| Accept (`D.8`) — email fails | `null` (acceptance still persists) | `null` |
| Resend (`D.9`) — succeeds | `<timestamp>` | `<same timestamp>` |
| Resend (`D.9`) — fails (502) | unchanged (no new version; no success timestamp written) | unchanged |
| Payment after **accepted** (`D.7`) — email succeeds | `<timestamp>` | `<same timestamp>` |
| Payment after **accepted** (`D.7`) — email fails | `null` (payment + version still succeed; `201`; Re-send) | `null` |
| Payment **before** acceptance (`D.7`) | `null` (unaccepted/unemailed) | `null` |

Resend is the only operation that does **not** create a new current version, so on a Resend **failure** nothing is written and the current invoice's `last_emailed_at` (and therefore the mirror) is **unchanged**; on Resend success both are set to the new timestamp.

---

## 12. Error contract

Error envelope is unchanged (conventions §3): `{ "error": { "code", "message", "details"? } }`, codes `UPPER_SNAKE_CASE`. **New** Phase 13 codes (to be added to `com.flooring.salesportal.common.error.ErrorCode` in the existing style — `NAME(HttpStatus.X, "Sentence-case message.")`):

| Code | HTTP | Default message | Where |
|------|------|-----------------|-------|
| `CUSTOMER_EMAIL_REQUIRED` | 422 | "A valid customer email is required before this action." | `D.1`, `D.2`, `D.8`, `D.9` |
| `CUSTOMER_EMAIL_INVALID` | 422 | "Customer email is not a valid email address." | `D.1`, `D.2`, `D.8`, `D.9` |
| `ACCEPTED_CUSTOMER_NAME_REQUIRED` | 422 | "Accepted customer name is required." | `D.8` |
| `SIGNATURE_REQUIRED` | 422 | "A customer signature is required to accept the invoice." | `D.8` |
| `SIGNATURE_INVALID` | 400 | "Signature must be a PNG image no larger than 2 MB." | `D.8` |
| `INVOICE_ALREADY_ACCEPTED` | 409 | "This invoice has already been accepted. Use Re-send to email it again." | `D.8` |
| `INVOICE_NOT_ACCEPTED` | 422 | "This invoice has not been accepted yet." | `D.9`, `D.10` |
| `EMAIL_SEND_FAILED` | 502 | "The invoice could not be emailed. Please try again." | **`D.9` only.** The non-fatal accept-send (`D.8`) and payment-after-accepted (`D.7`) paths surface an email failure via `last_emailed_at = null` + a success message, **without** raising this error and **without** returning 502. |

**Reused** existing codes (do **not** rename or duplicate):

| Code | HTTP | Phase 13 use |
|------|------|--------------|
| `INVOICE_REQUIRED` | 422 | No current invoice exists for Accept/Resend |
| `INVOICE_NOT_FOUND` | 404 | No current invoice for the signature `GET` (empty state) |
| `INVOICE_PRECONDITIONS_NOT_MET` | 422 | The existing 9 Create/Rewrite preconditions (email gate is separate, above) |
| `PAYMENT_EXCEEDS_BALANCE` | 422 | Overpayment on `D.7` (unchanged) |
| `ORDER_LOCKED` | 422 | Rewrite blocked when LAID (unchanged); Accept/Resend/signature are allowed when LAID |
| `ORDER_NOT_FOUND` | 404 | Order not in scope (cross-tenant returns 404, never 403) |
| `VALIDATION_FAILED` | 400 | Malformed body, non-empty Resend body, unexpected multipart part |

Clarifications:
- **Email gate vs preconditions.** On Create/Rewrite the email gate (`CUSTOMER_EMAIL_*`) is evaluated **before** the 9 `INVOICE_PRECONDITIONS_NOT_MET` checks; if the email gate fails, that request returns the email code and the 9-precondition check is skipped (both are 422; the frontend handles each). Email validation is stricter than the DB `email LIKE '%@%'` check; implementations should use a standard email-format validator.
- **Rewrite after accepted ≠ error.** Producing a new unsigned/unaccepted version on Rewrite is expected behaviour (§7), not an error.
- **Payment before acceptance ≠ error.** Allowed; simply no email is sent (§6).
- **`EMAIL_SEND_FAILED` status.** 502 Bad Gateway (upstream/provider dependency failure; added to conventions §4). Used by Resend; **not** raised by Accept (Accept's send is non-fatal).

---

## 13. Implementation notes / risks

- **Migration `V10` only.** Not created on this branch. Adds the four nullable `invoice` columns (§4); `accepted_signature_file_id` is a **non-unique** FK → `stored_file`. No enum change is needed (`attachment_kind = 'SIGNATURE'` already exists and stays unused by this flow).
- **Append-only preserved.** Accept and payment **append** new invoice versions; only `last_emailed_at` is updated in place (Resend, and the post-commit email-success stamp on Accept/Payment). Carry-forward on Accept/Payment must copy the acceptance fields; Rewrite must **null** them. Update `OrderPaymentService.regenerateInvoiceVersion` (and the equivalent acceptance path) to copy `accepted_*` forward.
- **Transaction boundaries.** Persist acceptance + signed PDF **in one transaction** (file-write-first + `TransactionSynchronization` rollback-cleanup, mirroring the existing attachment/invoice write pattern). The **email send happens after commit**; an email failure must **not** roll back acceptance. Set `last_emailed_at` in a short follow-up update on send success. The **same post-commit, non-fatal pattern applies to the payment-after-accepted email** (`D.7`, §6): the payment + new invoice version commit first, then the email is attempted; a send failure leaves `last_emailed_at` null and does **not** roll back the payment or return 502.
- **Signature ingestion is invoice-specific.** Reuse `FileStorageService.store(...)` + a `stored_file` row; **do not** create an `order_attachment` row, so the signature never appears in the Notes & Photos list. Validate `image/png` + 2 MB before persisting.
- **Signed PDF reuse.** `D.4` streams whatever the current invoice's `stored_file` is — once accepted, that's the signed PDF. No separate "signed PDF" endpoint is needed.
- **Dashboard accuracy (mirror invariant).** The dashboard reads `sales_order.last_emailed_at`, which must always equal the **current** invoice's `last_emailed_at` (§11.1). Whenever a new current invoice version is created (Create, Rewrite, Accept, payment), set the mirror to that new version's value — `null` at creation — and update it to the timestamp only on a successful email; on Resend failure leave it unchanged. Do **not** stamp the mirror only on success: that would leave a stale timestamp on the row when the new current invoice is unemailed (e.g. rewrite-after-accepted, or a payment whose email failed). Also add `invoice_accepted` (derived from the current invoice's `accepted_at`). This touches the Chunk 1 dashboard query/DTO at implementation time (documented here + in `openapi.yaml`; Chunk 1 prose to be updated in the implementation PR).
- **Email provider.** Keep provider behind an interface; configuration (API key, from-address per business/store) is implementation detail. `EMAIL_SEND_FAILED` (502) is the provider-failure signal for Resend.
- **Risks:** (a) email deliverability / spam classification (provider + from-domain setup); (b) signature image size/quality from canvas (cap at 2 MB, PNG); (c) a payment immediately after acceptance triggers a second email — acceptable and intended (updated balance), but watch for double-send if Accept's own email and a near-simultaneous payment both fire; (d) ensure the carried-forward signature `stored_file` is **not** deleted while still referenced by a newer version (non-unique FK; only delete orphaned PDF files, never the signature unless no version references it).

---

## Appendix — OpenAPI alignment

`docs/openapi.yaml` is a maintained source of truth (it already carries the full Phase 12 invoice/payment endpoints). Phase 13 updates it additively:
- New paths `POST …/invoices/current/accept`, `POST …/invoices/current/resend`, `GET …/invoices/current/signature` (tag `Invoices`; operationIds `acceptCurrentInvoice`, `resendCurrentInvoice`, `downloadCurrentInvoiceSignature`).
- `InvoiceDetail` and `CurrentInvoiceSummary` gain the five acceptance/email fields.
- New `SignatureAcceptRequest` multipart schema; new `BadGateway` (502) shared response; `EMAIL_SEND_FAILED` is conveyed through the existing free-form `error.code` string (OpenAPI does not enumerate code strings).
- `DashboardOrderRow` gains `invoice_accepted`.
