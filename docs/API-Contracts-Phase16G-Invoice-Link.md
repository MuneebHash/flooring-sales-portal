# Sales Portal MVP — API Contracts · Phase 16G (Invoice Link, Remote Invoice Signing, Frozen Invoice Words, Version Preconditions) (Lock-Ready)

**Status:** Phase 16G contract, written in 16G PR 1 (documentation and OpenAPI only) before any 16G code. It locks the rules that 16G PRs 2 to 6 implemented. **All six 16G PRs have merged (#139, #140, #141, #143, #146 and #154, the last on 23 October 2026), and every rule names the PR that implemented it.** The rules come from Muneeb's 16G decisions (13 October 2026, reviewed by Fable and GPT), as amended before PR 1. The evidence base is the 16G read-only verify gate audit (audited at `91e0506`).

**Where this contract sits.** It is additive to `docs/API-Conventions.md`, the Chunk 1 to 4 contracts, [the Phase 13 contract](API-Contracts-Phase13-Acceptance-Signature-Email.md), [the 16B quotation contract](API-Contracts-Phase16B-Quotation.md) and [the UX lock](Phase16D-Quotation-UX-Lock.md), and it amends them where a section below says so. Those documents were updated in place in PR 1 and point here. For the invoice link, the public invoice surface, remote invoice signing, the frozen invoice words (#125), the version preconditions (#69, #123), the automatic signed-PDF email on Path A and the 16G quote changes (#120 to #124, #126), **this contract is authoritative**.

**Source of truth priority:**
1. `docs/API-Conventions.md`
2. Locked Chunk 1–3 contracts
3. Locked Chunk 4 contract (Phase 12 invoices + payments)
4. Phase 13 contract (invoice acceptance + signature + email)
5. Phase 16B quotation contract
6. **This Phase 16G contract**: authoritative for the rules listed above, which it amends in documents 1 to 5
7. Locked DB schema: V1–V21, including the two 16G migrations, V20 in PR 2 and V21 in PR 4 (section 2)

**Order number format (locked):** `{store_code}.{salesperson_code}.{order_sequence_number_padded_5}`, for example `SYD-CBD.LC1.00042`. It is the document number shown under `TAX INVOICE` and used in invoice and signature file names.

**File-binary exception (conventions §3):** the public invoice `accept` endpoint takes a `multipart/form-data` signature upload, and the public invoice `pdf` endpoint streams stored PDF bytes. The protected send-link endpoint is plain JSON.

**Auth-model warning (locked):** the protected send-link endpoint is Standard-protected (HttpSession, all conventions §9 checks). The public invoice endpoints are a separate, slugless, sessionless surface authenticated **only by the secret invoice token**. They must not reuse, populate or weaken the session / `RequestContextGuard` model, and must not expose any tenant data beyond the documented view of the single order's current invoice behind the token (section 9.3).

## Contents

- [1. Purpose and scope](#1-purpose-and-scope)
- [2. Implementation plan](#2-implementation-plan)
- [3. Decisions and amendments](#3-decisions-and-amendments)
- [4. Every invoice version freezes its words (#125)](#4-every-invoice-version-freezes-its-words-125)
- [5. Version preconditions (#69, #123)](#5-version-preconditions-69-123)
- [6. Signed invoices are emailed after commit](#6-signed-invoices-are-emailed-after-commit)
- [7. The invoice link (`invoice_token`)](#7-the-invoice-link-invoice_token)
- [8. Protected send link](#8-protected-send-link)
- [9. Public invoice surface](#9-public-invoice-surface)
- [10. Remote signing: transaction and side effects](#10-remote-signing-transaction-and-side-effects)
- [11. Email markers and the dashboard mirror](#11-email-markers-and-the-dashboard-mirror)
- [12. Order status rules](#12-order-status-rules)
- [13. 16G quote changes (#120, #121, #122, #124, #126)](#13-16g-quote-changes-120-121-122-124-126)
- [14. Error contract](#14-error-contract)
- [15. Frontend summary](#15-frontend-summary)
- [16. Mandatory tests by PR](#16-mandatory-tests-by-pr)
- [17. Known limitations and follow-ups](#17-known-limitations-and-follow-ups)
- [Appendix — OpenAPI alignment](#appendix--openapi-alignment)

---

## 1. Purpose and scope

16G lets the store email the customer a link to the order's latest invoice. The customer reviews it on their phone and signs it remotely, the store is notified, and the customer is emailed the signed PDF. Around that, 16G:

- freezes the customer-facing words (Invoice To name, billing lines and terms) into every invoice version (#125);
- makes one mandatory server-side version check guard every signing and conversion request (#69, #123);
- emails the signed invoice PDF whenever an invoice is signed or converted, by any route, including Create Invoice from the accepted quote (Path A);
- completes the quote follow-ups #120, #121, #122 and #124, and gives both public pages specific dead-link messages (#126).

**Out of scope (confirmed):** tracking link opens; a link status display; a staff cancel control or cancel endpoint for invoice links; a view-only mode after signing; SMS; rate limiting and a decode concurrency cap (Phase 17, [#128](https://github.com/MuneebHash/flooring-sales-portal/issues/128)); a production email provider (Phase 17); a frontend test runner; invoice version history UI; expected-version preconditions on Rewrite, payment or void (Rewrite gained its precondition after 16G, in Batch A PR 2, #149, section 5; payment and void still have none). Also out of scope, and tracked as follow-ups (section 17): the broader delayed-email version-binding defects (audit F49); binding the protected Invoice tab PDF and signature reads to the displayed version ([#114](https://github.com/MuneebHash/flooring-sales-portal/issues/114), audit F53); PNG content validation on in-store D.8 (audit F55); rollback hooks on an unknown commit outcome (audit F60).

---

## 2. Implementation plan

| PR | Scope |
|---|---|
| 1 | This contract; the amendments to API-Conventions, Chunk 1, Chunk 4, Phase 13, 16B, the UX lock, `CLAUDE.md`, `docs/Phases.md` and `docs/openapi.yaml`; #138 folded in. Docs and OpenAPI only. |
| 2 | #125 (section 4): one new migration (three invoice identity columns plus a backfill), every invoice writer, the Invoice tab's Invoice To from InvoiceDetail, the `terms_source` value `LIVE` renamed `BUSINESS`, tests. |
| 3 | #69 + #123 (section 5): mandatory stale checks on the server **and** in the two existing clients (Invoice tab and Quote tab); D.8 takes the name from the frozen version and loses its name part; D.8's signing version copies `invoice_date` from the current version (section 5.4); D.8 post-commit work made non-throwing; Path A's automatic signed-PDF email (section 6); one shared token hash helper (section 7.3). |
| 4 | Remote invoice backend (sections 7 to 12): the `invoice_token` migration, which also reserves the slug `i`; the protected send-link; the public API and remote signing, whose signing version copies `invoice_date` from the current version (section 10.1); the link email and the customer signed-PDF email; the store notification; quote-link cancellation on remote signing; invoice-token kills in D.8, Path A and Rewrite; the derived InvoiceDetail field `invoice_link_expires_at` (section 4.5). |
| 5 | Remote invoice frontend: the `/i/{token}` page, Accept Remotely, the Invoice tab Preview PDF label, #126 on both public pages. |
| 6 | #120 polling (on server state) and the signing-draft reset when a refresh installs another invoice version, #121 financial refresh and an edit-gated Update Sale Price, #122 version-bound accepted quote reads, #124 provenance boolean and whole-second quote timestamps, and the derived `QuoteIssuedSummary.last_email_delivered` (section 13). |

- PRs land in this order. The PR 3 client changes ship in PR 3 with the server change, not in PR 5, so no interval exists in which a deployed client and server disagree on a request shape.
- **Migrations:** exactly two new migrations, V20 in PR 2 and V21 in PR 4. V1 to V21 are never edited. Both are outside the CI guard (V1–V13) and fold into the Phase 17 schema-only squash/baseline.
- PRs 2 to 6 may make narrowly scoped corrections to this contract, the other contracts, the UX lock, `CLAUDE.md` and `docs/openapi.yaml` where implementation shows PR 1 got a detail wrong. Each such correction is called out in that PR's report.

---

## 3. Decisions and amendments

| Rule | Section | Amends | PR |
|---|---|---|---|
| Every invoice version stores and renders its own name, billing lines and terms | 4 | 16F D6 (live Invoice To) and D7 (live Path B terms); Phase 13 §8; Chunk 4 D.1, D.2, E.2, F.2; 16B §4.6, §7.1 | 2 |
| `terms_source` value `LIVE` renamed `BUSINESS` | 4.4 | Chunk 4 E.2; Phase 13 §5; 16B §4.6; OpenAPI `InvoiceDetail` | 2 |
| Mandatory version precondition on D.8, remote accept and Path A | 5 | Phase 13 §5.1, §12; 16B §7.1, §12; Chunk 4 F.9 | 3, 4 |
| D.8 takes the accepted name from the frozen version; the `accepted_customer_name` part and its 150-character request limit are removed | 5.4 | Phase 13 §4, §5.1, §10, §12; 16B §4.6 | 3 |
| A signing version (D.8, remote accept) copies `invoice_date` from the current version; `accepted_at` still records the signing time | 4.2, 5.4, 10.1 | Phase 13 §5.1; Chunk 4 E.1, E.2 | 3, 4 |
| A signed invoice is always emailed after commit, by every signing or conversion route; Path A emails it with no email gate | 6 | 16F D6 ("no email sent"); Phase 13 §6, §7, §9, §11.1; 16B §7.1, §13 | 3, 4 |
| Post-commit email and stamp work never turn a committed signing into a 500 | 6.2 | Phase 13 §5.1 | 3, 4 |
| Invoice link: separate order-bound token table, lifecycle, lazy expiry | 7 | Phase 13 §2, §9, §10; Chunk 4 B | 4 |
| Protected send-link; link-only email; markers on the token row only | 8, 11 | Phase 13 §9, §11.1; API-Conventions §14, §16 | 4 |
| Public invoice page `/i/{token}` and API `/api/v1/public/invoices/{token}` | 9 | API-Conventions §1, §4; `docs/Phases.md` §5 exposure exception | 4, 5 |
| Remote signing cancels an active issued quote and its link (D5(c) extended from D.8) | 10.5 | 16F D5(c); 16B §2, §5, §13; UX lock §16 | 4 |
| Store notification on remote signing (new invoice sender, D3/D12 by analogy) | 10.6 | — (new) | 4 |
| Send link and remote signing allowed when LAID; no CANCELLED rule | 12 | API-Conventions §16; Chunk 4 F.8 | 4 |
| Specific dead-link messages on both public pages | 13.1 | 16B §7.2; UX lock §13 | 5 |
| #120, #121, #122, #124 | 13.2–13.5 | 16B §7.1, §11; UX lock §10, §13, §14 | 6 |
| Staff waiting state from the server: InvoiceDetail `invoice_link_expires_at` (derived, never displayed) drives Invoice tab polling | 4.5, 13.2 | UX lock §20; Chunk 4 E.2; Phase 13 §5 | 4 (field), 6 (polling) |
| A refresh or poll that installs another invoice version clears the signing draft | 13.2 | UX lock §20 | 6 |
| Quote email delivery outcome from the server: `QuoteIssuedSummary.last_email_delivered` drives Not delivered | 13.5 | 16B §7.1, §11; UX lock §13 | 6 |

---

## 4. Every invoice version freezes its words (#125)

**PR 2.** Before PR 2 (16F, D6 and D7) every invoice render read the Invoice To name and billing lines live, and a Path B invoice (Create, Rewrite and their payment, void and D.8 versions) read the business's per-flooring-type terms live. After PR 2 nothing customer-facing is read live at render time except the business presentation (name, logo, accent, ABN, bank details, store contact and salesperson), which #125 does not freeze.

### 4.1 Data model (PR 2 migration)

The PR 2 migration adds three nullable columns to `invoice`:

| Column | Type | Null | Content |
|---|---|---|---|
| `customer_name_snapshot` | `TEXT` | yes | The Invoice To name: first, middle (when present) and last name, each trimmed, joined by single spaces. The existing Invoice To derivation, and the same derivation as the `V17` quote snapshot. |
| `customer_address_line1_snapshot` | `TEXT` | yes | Billing line 1: `[unit/]street_number street`. |
| `customer_address_line2_snapshot` | `TEXT` | yes | Billing line 2: `suburb state_code postcode`. |

- Terms reuse the existing `invoice.terms_snapshot` (`TEXT NULL`, `V19`). No new terms column is added.
- **Backfill:** the migration fills the three columns for existing rows from the order's saved customer row and BILLING address row, with the derivation above. A missing customer or billing row leaves the matching columns null. Terms are **not** backfilled. An existing Path B row whose `terms_snapshot` is null keeps the terms page in its own stored PDF, but from PR 2 its InvoiceDetail and every later payment, void and signing version show no terms, until a Rewrite or Path A writes a version with frozen terms (section 4.4).
- The decision record says production has no invoice rows. That is a dated statement from the decision (13 October 2026), not a database check made by this contract.
- No column is exposed except through the InvoiceDetail fields in section 4.5 and the public view in section 9.3.

### 4.2 Who writes the words

| Writer | Name and billing lines | `terms_snapshot` | `source_quote_version_id` |
|---|---|---|---|
| D.1 Create | frozen from the current saved customer and BILLING address | frozen: the business's per-flooring-type terms for the order's flooring type, sanitised once now (null when none are set) | null |
| D.2 manual Rewrite | frozen from the current saved customer and BILLING address | frozen, as Create | null (cleared) |
| D.7 payment | copied from the current invoice version, verbatim, including null | copied verbatim, including null | carried |
| D.10 payment void | copied, as payment | copied, as payment | carried |
| D.8 in-store accept | copied, as payment | copied, as payment | carried |
| Remote invoice accept (PR 4) | copied, as payment | copied, as payment | carried |
| Path A (`quote/create-invoice`) | the accepted quote version's `V17` `customer_name_snapshot`, `customer_address_line1_snapshot` and `customer_address_line2_snapshot`, verbatim, including null | the accepted version's `terms_snapshot`, verbatim, including null | the accepted version id |

- A copy writer never substitutes the current customer record or the business's current terms, even when the copied value is null.
- Payments are not words. A payment or void still appends a version with the new `total_paid` and `balance_due`, carrying the same words.
- **Invoice date (D.8 implemented in PR 3; remote accept in PR 4; not part of #125):** a signing version copies `invoice_date` from the current version it signs, so its signed PDF shows the date of the invoice the customer reviewed and the version check matched, while `accepted_at` still records the signing time (sections 5.4 and 10.1). Create, Rewrite and Path A date their new document the day it is written (`LocalDate.now()`), and payment and void keep doing so (unchanged, outside 16G). Before PR 3, D.8 also dated its version the signing day.
- The source-ownership check at the shared invoice insert is unchanged (16B §4.6).

### 4.3 Rendering

- Every invoice version renders its stored words: its PDF, its InvoiceDetail and, from PR 4, the public view. A stored null name or billing line renders as absent, never filled from the live customer.
- `terms_snapshot` null means no terms page, with no live fallback, on every version and both terms sources. The 16F render-time live terms branch is removed.
- The PDF layout is unchanged (Aire Compact; terms on page 2).

### 4.4 `terms_source`

`terms_source` keeps its name and stays always present on InvoiceDetail. It reports where the version's stored terms came from. It never triggers a lookup.

| Value | Meaning (since PR 2) |
|---|---|
| `QUOTE` | The version has a source quote version: its terms are the frozen terms of the signed quote, copied by Path A and carried by payment, void and signing versions. Null means the quote had no terms. |
| `BUSINESS` | No source quote version: its terms are the business's per-flooring-type terms, frozen into the version at Create or Rewrite and carried by payment, void and signing versions. Null means none were set at that moment. |

- **Implemented in PR 2.** Before PR 2 the API returned `QUOTE` or `LIVE`, with the 16F meaning (16B §4.6): `LIVE` terms were the business's current terms, read at every render. PR 2 renamed the value to `BUSINESS` in the API and the frontend types (the OpenAPI enum already listed `BUSINESS` from PR 1; PR 2 removed its `LIVE` caveat) and changed the Invoice tab terms-source line (UX lock §20). `LIVE` is no longer returned.
- **Versions written before PR 2.** A Path B version written before PR 2 has a null `terms_snapshot` (16F read its terms live), and the PR 2 migration does not backfill terms (section 4.1). Such a version reports `BUSINESS` with null `terms_html` (worded by the Invoice tab as none were set), although its own stored PDF keeps the terms page it was rendered with; later payment, void and signing versions copy the null. Only a new version with frozen terms brings terms back: a Rewrite (which clears a signature and is refused on a LAID order) or Path A.
- The value is selected by source presence, never by whether the HTML is null (unchanged).

### 4.5 InvoiceDetail (E.2) changes (PR 2, PR 4)

InvoiceDetail carries three always-present nullable frozen identity fields beyond 16F (implemented in PR 2), and a fourth always-present nullable field, the derived link field (implemented in PR 4). E.1 `current_invoice_summary` is unchanged. The source quote version id is still never exposed.

| Field | Type | PR | Content |
|---|---|---|---|
| `customer_name_snapshot` | string, nullable | 2 (implemented) | the version's frozen Invoice To name (section 4.1) |
| `customer_address_line1_snapshot` | string, nullable | 2 (implemented) | the version's frozen billing line 1 |
| `customer_address_line2_snapshot` | string, nullable | 2 (implemented) | the version's frozen billing line 2 |
| `invoice_link_expires_at` | timestamp, nullable | 4 | the `expires_at` of the order's `ACTIVE`, unexpired invoice link; otherwise null |

**`invoice_link_expires_at` (PR 4)** is response metadata, not a frozen word:

- It is computed in the read from server state: the order's `ACTIVE` `invoice_token` whose `expires_at` is still in the future (JVM clock) gives that `expires_at`. It is null when no link was ever sent, when the link is dead (`REPLACED`, `REWRITTEN`, `SIGNED` or `EXPIRED`), and when an `ACTIVE` token is already past `expires_at` but no public request has flipped it yet.
- It describes the order's link, not the version. It is not an `invoice` column, adds nothing to the PR 2 or PR 4 migrations beyond the `invoice_token` table, and no writer freezes or copies it.
- Reading it writes nothing and never expires a token. Lazy expiry stays on the public surface (section 7.7).
- Every InvoiceDetail response carries it, computed after the request's own writes: D.3, and the D.1, D.2, D.8, D.9 and Path A responses. D.2, D.8 and Path A kill an `ACTIVE` link in their own transaction, so they return null. A signed current invoice always gives null (section 7.6).
- The Invoice tab uses it only to decide whether to poll (section 13.2). It is never displayed: no link status line, no expiry display, no cancel control.
- It is not part of E.1 `current_invoice_summary` (the payment and void responses) or of any public invoice payload (the public view carries the presented token's own `expires_at`, section 9.3).

Shape (the three `customer_*_snapshot` fields are returned since PR 2; `invoice_link_expires_at` is returned since PR 4):

```json
{
  "invoice_id": 4,
  "order_id": 1,
  "version_number": 4,
  "invoice_date": "2026-11-02",
  "due_date": "2026-11-12",
  "details_of_sale_snapshot": "Supply and install plush carpet to lounge and dining rooms.",
  "sale_price_ex_gst": 840.00,
  "sale_price_inc_gst": 924.00,
  "total_paid": 500.00,
  "balance_due": 424.00,
  "created_by_user_id": 1,
  "created_at": "2026-11-02T14:30:00",
  "pdf_download_path": "/api/v1/aussie-floors-group/orders/1/invoices/current/file",
  "customer_name_snapshot": "Jordan Wilson",
  "customer_address_line1_snapshot": "4/12 Oxford Street",
  "customer_address_line2_snapshot": "Paddington NSW 2021",
  "accepted_at": null,
  "accepted_customer_name": null,
  "accepted_signature_present": false,
  "accepted_signature_download_path": null,
  "last_emailed_at": null,
  "terms_html": "<p>…</p>",
  "terms_source": "BUSINESS",
  "invoice_link_expires_at": "2026-11-09T14:40:00"
}
```

### 4.6 Invoice tab (PR 2)

The Invoice tab builds Invoice To only from the three frozen identity fields, never from the live customer and billing props. The terms-source line follows section 4.4 (UX lock §20). `invoice_link_expires_at` is never displayed; since PR 6 the tab reads it only for polling (section 13.2).

---

## 5. Version preconditions (#69, #123)

**PR 3** for D.8 and Path A (server and both existing clients). **PR 4** for the remote accept. **Batch A PR 2** (#149, after 16G) for D.2 Rewrite (server and the Details of Sale tab). One contract covers all four.

### 5.1 Carriers

| Operation | Mandatory carrier | Compared with (read under the order lock) | Stale result |
|---|---|---|---|
| D.8 in-store invoice accept | multipart form field `expected_version_number` | the current invoice's `version_number` (highest for the order) | 409 `INVOICE_VERSION_STALE`, message exactly `Invoice updated, please refresh` |
| Remote invoice accept | multipart form field `expected_version_number` | the same | the same |
| Path A (`POST …/quote/create-invoice`) | JSON body `{"quote_version_id": N}` (own request schema, `CreateInvoiceFromQuoteRequest`) | the id of the order's latest `ACCEPTED` quote version, selected independently of the draft and of `current_issued` | 409 `QUOTE_VERSION_STALE`, message exactly `The accepted quote changed while the confirmation was open. Review the accepted quote before creating an invoice.` |
| D.2 Rewrite (`POST …/invoices/rewrite`, Batch A PR 2) | JSON body `{"expected_version_number": N}` (own request schema, `RewriteInvoiceRequest`) | the current invoice's `version_number` (highest for the order) | 409 `INVOICE_VERSION_STALE`, message exactly `Invoice updated, please refresh` |

### 5.2 Comparison rules

- The value is **only compared** with the row the server reads under the order lock. It is never used to select, look up or load a row. A forged, foreign or old value can only mismatch, giving the stale 409; it never reaches another order's data.
- The value is mandatory. There is no "omit to skip the check" mode. A client still running pre-16G code gets 400 and must reload. The same holds for Rewrite since Batch A PR 2: a client from before that PR, still posting `{}`, gets 400 (`Required.`) and must reload.
- The public invoice identity is `version_number` (1, 2, 3 …). The public surface never carries `invoice_id` or any other internal id.
- Frontend pre-checks (the Invoice tab's re-read before posting; since Batch A PR 2 also the Details of Sale tab's binding read when the Rewrite confirmation opens) and the public token lookup are early, non-authoritative hints. Only the comparison under the lock decides.
- A version committed by another request while this request waits for the lock is caught: the comparison runs after the lock is granted.
- Every check in this section runs before the first file write, so a refused request writes no file, `stored_file` row, invoice row or token change.
- `EmptyObjectRequest` and the shared empty-body validator are unchanged for the other endpoints that use them.
- Payment and void gain no expected-version precondition. Rewrite gained none in 16G (follow-up in section 17) and has one since Batch A PR 2 (#149; sections 5.1, 5.3 and 5.8).

### 5.3 Value validation

- Multipart (D.8, remote accept): exactly one `expected_version_number` form field holding a positive integer. Missing gives 400 `VALIDATION_FAILED` with detail field `expected_version_number`, message `Required.`. Not a positive integer gives `Must be a positive integer.`. Repeated gives `Must appear at most once.`. Only the multipart form field counts: a URL query-string `expected_version_number` is not the carrier (on its own the form field is `Required.`; beside the form field it is refused at the part allowlist with `Not allowed.`), as the conventions take no query-string data on a mutation.
- JSON (Path A): a JSON object with exactly the one field `quote_version_id`, a positive integer. An absent or blank body, a missing or null field, `Required.`. The key given more than once, `Must appear at most once.` (as the multipart rule). Not a positive integer, `Must be a positive integer.`. Any other field, `Not allowed.` (one detail per field, in the same 400 as any `quote_version_id` detail, which comes last). A non-object body (a JSON `null` is a non-object body), 400 `VALIDATION_FAILED` on `body`, `Must be an object.`. Unparseable JSON, 400 `MALFORMED_JSON`. (Before PR 3 the body had to be empty and an absent body was accepted.)
- JSON (D.2 Rewrite, Batch A PR 2): the Path A rules with the field `expected_version_number`. A JSON object with exactly that one field, a positive integer. An absent or blank body, `{}`, a missing or null field, `Required.`. The key given more than once at the top level of the object (an escaped spelling of it included; a key nested inside a value is not counted), `Must appear at most once.`, whatever the values. Not a positive integer (a string, a boolean, a fraction such as `1.5` or `1.0`, zero, a negative, an object or array, or beyond the signed 64-bit range), `Must be a positive integer.`. Any other field, `due_date` included, `Not allowed.` (one detail per field, in body order, in the same 400 as any `expected_version_number` detail, which comes last). A non-object body (a JSON `null` included), 400 `VALIDATION_FAILED` on `body`, `Must be an object.`. Unparseable JSON, 400 `MALFORMED_JSON`. (Before Batch A PR 2 the Rewrite body had to be empty and an absent body was accepted.)

### 5.4 D.8 order of checks (PR 3)

`POST /api/v1/{slug}/orders/{orderId}/invoices/current/accept`, `multipart/form-data` with exactly two parts: the `expected_version_number` field and the `signature` file.

0. Transport-level rejections happen first, in the framework (unchanged global behaviour): a body over the multipart limit gives 400 `FILE_TOO_LARGE`; a non-multipart request gives 415 (code `VALIDATION_FAILED`).
1. Standard protected guard (404 / 401 / 403); `orderId` a positive integer (400).
2. The order scoped to the session business and store, locked (404 `ORDER_NOT_FOUND`).
3. Current invoice read under the lock. None: 422 `INVOICE_REQUIRED` (before the version value is checked).
4. `expected_version_number` validation (section 5.3): 400.
5. Stale comparison: 409 `INVOICE_VERSION_STALE`.
6. Already accepted (current `accepted_at` set): 409 `INVOICE_ALREADY_ACCEPTED` (default staff wording, unchanged).
7. Part allowlist: any file part other than one `signature`, any form field other than `expected_version_number`, any URL query-string parameter (including a query-string copy of `expected_version_number`), a repeated `signature`: 400 `VALIDATION_FAILED` (`Not allowed.` / `Must appear at most once.`). **`accepted_customer_name` is now an unexpected part** and is refused here like any other.
8. Frozen name: the current version's `customer_name_snapshot` null or blank gives 422 `ACCEPTED_CUSTOMER_NAME_REQUIRED` (default message). The accepted name is that stored value, verbatim: full length, never typed, never truncated. The 150-character request limit is removed with the part.
9. Signature: missing or empty, 422 `SIGNATURE_REQUIRED`; declared type not `image/png` or over 2,097,152 bytes, 400 `SIGNATURE_INVALID` (D.8 keeps its declared-type check; content validation is follow-up F55).
10. Customer email gate on the saved customer email: 422 `CUSTOMER_EMAIL_REQUIRED` / `CUSTOMER_EMAIL_INVALID` (unchanged).
11. Persist in one transaction (the Phase 13 §5.1 persistence, with the copied words of section 4.2). The new version copies `invoice_date` from the current version (before PR 3, D.8 dated it with `LocalDate.now()`, the signing day), so the signed PDF shows the invoice date of the version the stale check matched; `accepted_at` is still the signing time. From PR 4 the transaction also kills an `ACTIVE` invoice token as `SIGNED` (section 7.5). D5(c) is unchanged.
12. Post-commit email and stamp, never throwing (section 6).

### 5.5 Path A order of checks (PR 3)

`POST /api/v1/{slug}/orders/{orderId}/quote/create-invoice`, body `{"quote_version_id": N}`.

1. Standard protected guard; `orderId` a positive integer (400).
2. The order scoped and locked (404 `ORDER_NOT_FOUND`).
3. The latest `ACCEPTED` quote version read under the lock. None: 422 `QUOTE_NOT_ACCEPTED`.
4. Body validation (section 5.3): 400 `VALIDATION_FAILED` / `MALFORMED_JSON`.
5. Stale comparison with the latest accepted version's id: 409 `QUOTE_VERSION_STALE`.
6. Signature precedence against the current invoice (16F D5(b) as amended on 8 October 2026, unchanged): 409 `INVOICE_ALREADY_ACCEPTED` with the Path A message.
7. Collected preconditions (422 `INVOICE_PRECONDITIONS_NOT_MET`), then overpayment (422 `BUSINESS_RULE_VIOLATION`), unchanged.
8. Inherited signature read (500 on incomplete metadata or a missing file, unchanged), then persist (16B §7.1, with the words of section 4.2; from PR 4 also the `ACTIVE` invoice-token kill, section 7.5).
9. Post-commit customer email (section 6).

Latest-accepted selection, the strict signature-time rule, the preconditions, the overpayment check and the LAID allowance are unchanged. Path A still never changes a quote version or quote token.

### 5.6 Remote accept order of checks (PR 4)

See section 9.4. The token gate, body validation and PNG pre-validation run before the order lock; the authoritative stale comparison runs under the lock after the token and its expiry are re-read.

### 5.7 Client reactions

- **Invoice tab (PR 3, implemented):** D.8 sends `expected_version_number` = the `version_number` of the invoice on screen, and no longer sends `accepted_customer_name`. On 409 `INVOICE_VERSION_STALE` it clears the signature pad and the declaration ticks (even if the re-read then fails), shows the message, re-reads the current invoice and requires a fresh user action. It never retries on its own. Its existing best-effort pre-check before posting stays as an early hint (unchanged wording), as do its single-flight, unmount and order-change guards.
- **Quote tab (PR 3, implemented):** Create Invoice sends `{"quote_version_id": N}` with the accepted version shown in the open confirmation. A 409 `QUOTE_VERSION_STALE` shows its message (the same text as the existing changed-quote notice) and refreshes the issued/accepted state, as every 409 does. No automatic retry. The existing refresh and confirmation safeguards and the Path A notice stay.
- **Public invoice page (PR 5, implemented):** sends `expected_version_number` = the `version_number` of the invoice shown. On 409 `INVOICE_VERSION_STALE` it re-reads the link in place, clears the pad and the ticks, shows the message and waits for a fresh signature (section 9.4).
- **Details of Sale tab (Batch A PR 2, implemented):** opening the Rewrite confirmation reads the current invoice and binds the confirmation to its `version_number` (Confirm stays disabled until that read lands; a failed read offers Try again). A signed bound version shows a warning that the Rewrite clears the signature and the customer will need to sign the new invoice again; it is a warning, not a block. Rewrite sends `{"expected_version_number": N}` with the bound version. On 409 `INVOICE_VERSION_STALE` it closes the confirmation, shows the message and re-reads the current invoice in the background (that read never re-opens or re-binds a confirmation); a fresh explicit click takes a new binding. It never retries on its own.

### 5.8 D.2 Rewrite order of checks (Batch A PR 2, #149)

`POST /api/v1/{slug}/orders/{orderId}/invoices/rewrite`, body `{"expected_version_number": N}` (`RewriteInvoiceRequest`).

1. Standard protected guard (404 / 401 / 403); `orderId` a positive integer (400).
2. The order scoped to the session business and store, locked (404 `ORDER_NOT_FOUND`).
3. LAID: 422 `ORDER_LOCKED`, before the invoice read, the body and the value (unchanged).
4. Current invoice read under the lock. None: 422 `INVOICE_REQUIRED`, whatever the body (before Batch A PR 2 the empty-body check ran before this read).
5. Body validation (section 5.3): 400 `VALIDATION_FAILED` / `MALFORMED_JSON`.
6. Stale comparison with that current version's `version_number`: 409 `INVOICE_VERSION_STALE`, before any snapshot, PDF, file, insert, mirror reset or link kill, so a stale Rewrite writes nothing.
7. The Phase 13 customer email gate (422 `CUSTOMER_EMAIL_REQUIRED` / `CUSTOMER_EMAIL_INVALID`), the preconditions (422 `INVOICE_PRECONDITIONS_NOT_MET`) and the overpaid backstop (unchanged, still a 500: [#135](https://github.com/MuneebHash/flooring-sales-portal/issues/135)), then persist as Chunk 4 D.2: a fresh snapshot of the live order as version current + 1, its words frozen (section 4.2), the mirror reset and an `ACTIVE` invoice link killed as `REWRITTEN` (section 7.5).

There is no already-accepted step: a matching Rewrite of a signed current version still appends an unsigned version and clears the signature, by design (Phase 13 §7). The binding only makes sure the salesperson saw the version being replaced. **Blind spot:** only a new invoice version makes a Rewrite stale. A send-link, which writes no invoice version, or an edit of the saved customer identity does not: a Rewrite carrying the current version still succeeds, kills the link as `REWRITTEN` and freezes the identity saved at that moment. Lock order: a remote accept, D.8, a payment, a void or Path A that commits first makes an old-version Rewrite stale (409, nothing written); a Rewrite that commits first makes a queued remote accept 410 `INVOICE_LINK_REWRITTEN` (its token gate runs before any version comparison) and a queued D.8 carrying the old version 409, while a payment or a void (no precondition) succeeds against the new version and Path A succeeds while its own preconditions hold.

---

## 6. Signed invoices are emailed after commit

**Policy (staff rule):** a signed invoice is always emailed to the saved customer email when it is signed or converted, by any route: in-store D.8 (since Phase 13), Create Invoice from the accepted quote (Path A, **since PR 3**, amending 16F D6 "no email sent") and remote signing (**since PR 4**). Payment and payment void still never auto-email, including when they carry a signed version forward. Manual Re-send (D.9) stays the retry route. Invoice link emails (section 8) are not signed-PDF emails.

### 6.1 Per route

| Route | Email gate before writing | Saved email missing or invalid at send time | Delivery fails | Response message |
|---|---|---|---|---|
| D.8 (non-throwing since PR 3) | yes: 422 `CUSTOMER_EMAIL_*`, nothing written (unchanged) | not reachable (gated) | non-fatal: 201, markers null | `Invoice accepted and emailed to the customer.` when the delivery returned success; otherwise `Invoice accepted. The invoice could not be emailed — use Re-send Invoice to try again.` (unchanged) |
| Path A (since PR 3) | **none** (unchanged: no email gate) | send skipped, WARN log, markers null, 201 | non-fatal: 201, markers null | delivered: `Invoice created from accepted quote and emailed to the customer.`; failed: `Invoice created from accepted quote. The invoice could not be emailed — use Re-send Invoice to try again.`; skipped: `Invoice created from accepted quote. It was not emailed because the customer has no valid email address — add one on the Customer tab, then use Re-send Invoice.` |
| Remote accept (PR 4) | **none** | send skipped, WARN log, markers null, 201 | non-fatal: 201, markers null | always `Invoice accepted.` with `{"state": "SIGNED"}` (the public response never reports the email outcome) |

- **Recipient:** the saved customer email (`order_customer.email`, trimmed), read inside the committing transaction (D.8 parity).
- **Email:** the existing `InvoiceEmailRequest` and `InvoiceEmailSender` used by D.8 and D.9, carrying the PDF of the version the request appended (subject `Your invoice from {business_name}`, the existing short body, the PDF attached). Recording-only until the Phase 17 provider.
- **Stamp on success:** after a successful delivery a short follow-up transaction stamps `invoice.last_emailed_at` on the appended version and sets the `sales_order.last_emailed_at` mirror to the current version's value (the existing stamp, Phase 13 §11.1).
- On failure or a skip, the appended version's `last_emailed_at` and the mirror stay null. Re-send (D.9) emails the current signed PDF later.

### 6.2 Commit boundary

- **Before commit:** any failure (validation, render, storage, insert, commit) rolls everything back and deletes only this request's new files. No email and no store notification are sent, no marker moves. This is the existing transactional 500 or 4xx.
- **After commit:** the signing or conversion stands. Delivery, a skipped delivery, a sender runtime failure of any kind, and a failure of the stamp transaction are all non-fatal. Each is logged at WARN with the order id and version number (never the recipient, body, token, link or exception), and the response is still 201. **No post-commit step may turn a committed signing or conversion into a 500.** Before PR 3, D.8 caught only the transport exception, so another sender failure or a stamp failure returned 500 after commit; PR 3 fixed D.8 and applied the same rule to Path A (implemented), and PR 4 applied it to remote signing.
- Path A's 201 always describes the version it appended: after a recorded delivery that version is re-read in the stamp transaction, so a version another request commits between the commit and the stamp never becomes its response (the mirror still follows the current version). D.8 keeps its response rule (section 6.3).
- A stamp failure after a successful delivery leaves both markers null, so the Invoice tab shows "Accepted but not emailed yet" and offers Re-send. The D.8 and Path A messages report the delivery outcome; the markers report what was recorded. For example, a D.8 whose delivery succeeds and whose stamp then fails returns 201 with `Invoice accepted and emailed to the customer.` and both markers null; a D.8 whose delivery fails returns 201 with the not-emailed message.
- No customer email and no store notification are ever sent for a rolled-back request or a stale or other refused request.

### 6.3 Known limitation (not fixed in 16G)

The delayed-email windows found by the gate audit (F49) remain: a D.8 or D.9 response can describe a version newer than the one emailed, a customer can receive a PDF that is no longer current, and D.9 reads its row and file in separate statements. Making post-commit failures non-throwing does not fix them. They are tracked as [#148](https://github.com/MuneebHash/flooring-sales-portal/issues/148) (section 17).

---

## 7. The invoice link (`invoice_token`)

**PR 4** (backend), except the shared hash helper (PR 3).

### 7.1 Table (PR 4 migration)

| Column | Type | Null | Notes |
|---|---|---|---|
| `invoice_token_id` | `BIGINT` identity PK | no | |
| `order_id` | `BIGINT` | no | FK `fk_invoice_token_order` → `sales_order(order_id)`. The link belongs to the order, not to one invoice version. |
| `token_hash` | `VARCHAR(255)` | no | SHA-256 of the plaintext token, lowercase hex. Unique (`uq_invoice_token_hash`). The plaintext is never stored. |
| `status` | `VARCHAR(16)` | no | `ACTIVE` · `REPLACED` · `REWRITTEN` · `SIGNED` · `EXPIRED` (`chk_invoice_token_status`). |
| `sent_by_user_id` | `BIGINT` | no | FK `fk_invoice_token_sent_by` → `app_user`. The session user who sent the link. Recorded as `created_by_user_id` on a version this link signs (section 10.2). |
| `sent_at` | `TIMESTAMP` | no | Send-attempt marker: the JVM time of the send transaction that minted the token, stamped before delivery. |
| `expires_at` | `TIMESTAMP` | no | `sent_at` + 7 days (JVM clock, the quote rule as implemented). |
| `emailed_at` | `TIMESTAMP` | yes | Success-only delivery marker for this token's email; null when delivery failed or has not been confirmed. |
| `dead_at` | `TIMESTAMP` | yes | When the token left `ACTIVE`. |
| `created_at` | `TIMESTAMP` | no | Database default. |

- `uq_invoice_token_one_active_per_order`: a partial unique index on `order_id` `WHERE status = 'ACTIVE'`. At most one `ACTIVE` invoice link per order, enforced by the database and by every transition running under the order row lock.
- Rows are never deleted. A dead row keeps its status as the reason, so the public page can show the right message.
- No separate reason column: the status is the reason (quote parity).
- No shared table with `quote_token`, no shared public DTO, and no rule limiting an order to one live link across the quote and invoice surfaces.

### 7.2 Statuses and public states

| `invoice_token.status` | Cause | Public `state` | GET (200) / action (410) message |
|---|---|---|---|
| `ACTIVE` | a send minted it and nothing has killed it yet | `ACTIVE` | — (viewable and signable) |
| `REPLACED` | Accept Remotely was used again; a newer link replaced it | `REPLACED` | `This invoice link has been replaced. Please use the latest invoice link.` (410 `INVOICE_LINK_REPLACED`) |
| `REWRITTEN` | a manual Rewrite (D.2) appended a new version | `REWRITTEN` | `This invoice has been revised. Please contact the store for the updated invoice.` (410 `INVOICE_LINK_REWRITTEN`) |
| `SIGNED` | the invoice was signed: remotely, in store (D.8), or by Create Invoice from the accepted quote (Path A) | `SIGNED` | `This invoice has already been signed. Please contact the store if you need a copy.` (410 `INVOICE_LINK_SIGNED`) |
| `EXPIRED` | an `ACTIVE` token past `expires_at`, flipped lazily on access | `EXPIRED` | `This invoice link has expired. Please contact the store.` (410 `INVOICE_LINK_EXPIRED`) |

These are the only invoice dead reasons. The quote reasons `CANCELLED`, `SUPERSEDED` and `INACTIVE` do not exist on the invoice surface.

### 7.3 Minting, hashing and the shared helper

- Mint: 32 bytes from a CSPRNG, encoded as unpadded URL-safe Base64 (43 characters), on every send.
- Store only the SHA-256 lowercase-hex hash. Look up by hash, then compare in constant time.
- Shape gate before any database access: a token that does not match `^[A-Za-z0-9_-]{43,128}$` is treated exactly like an unknown token (section 9.2).
- **PR 3** adds one shared token hash helper. The two existing quote copies (`QuoteSendService`, `PublicQuoteService`) use it with unchanged behaviour, and the invoice code uses it since PR 4. The "duplicate, don't extract" code comments were never a recorded decision. The separate notification sender interfaces (16F D3) stay separate.
- The plaintext exists only in method scope and in the delivered email body. It is never persisted, never returned by any protected or public response, and never logged at INFO or above.

### 7.4 Binding

The token identifies the order. Every public request resolves the order's **current** invoice version (the highest `version_number`), not the version that was current when the link was sent. The page therefore shows payments and voids made after the send.

### 7.5 Lifecycle

| Event | Token outcome | PR |
|---|---|---|
| Send the first link | new `ACTIVE` token, `expires_at` = `sent_at` + 7 days | 4 |
| Send again while a link is `ACTIVE` | the old token → `REPLACED` (`dead_at` set), then a new `ACTIVE` token | 4 |
| Payment (D.7) or void (D.10) before signing | the `ACTIVE` token survives; the next public read shows the new version and amounts | 4 |
| Manual Rewrite (D.2) | the `ACTIVE` token → `REWRITTEN` | 4 |
| D.8 in-store accept, remote accept, or Path A conversion | the `ACTIVE` token → `SIGNED` | 4 |
| Seven days after `sent_at` | lazy expiry → `EXPIRED`, on the next public request for that token (section 7.7) | 4 |
| Create (D.1) | not applicable: no invoice existed, so no token can | — |

- Each invoice-triggered transition runs in the **same order-locked transaction as the invoice insert that causes it** (Rewrite, D.8, Path A, remote accept). A send and its replacement run in one transaction under the same lock. A rolled-back writer leaves the token unchanged.
- The status update is guarded (`… WHERE status = 'ACTIVE'`); zero rows (no live link) is a no-op.
- A writer that kills an `ACTIVE` token which is already past `expires_at` (because no public request flipped it) records its own reason, not `EXPIRED` (quote parity).
- Dead tokens never change status again. Only `ACTIVE` tokens can expire.

### 7.6 Invariants

- **One live link:** at most one `ACTIVE` invoice token per order (section 7.1).
- **Never signable when signed:** while a token is `ACTIVE`, the order's current invoice version is unsigned. Every signing writer kills the token in its insert transaction, and the send refuses a signed current invoice (section 8).
- **Never mixed:** a public response never combines one version's content with another version's acceptance state (section 9.3).

### 7.7 Lazy expiry

On any public request, an `ACTIVE` token found past `expires_at` takes the order row lock, is re-read, and is flipped to `EXPIRED` only if it is still `ACTIVE` and still past expiry. The flip commits in its own transaction before the response: GET returns 200 with the `EXPIRED` state; accept and pdf return 410 `INVOICE_LINK_EXPIRED`. An expiry flip never alters any invoice row. The public accept repeats the expiry check after taking its lock (section 9.4).

Staff reads never flip expiry. InvoiceDetail's `invoice_link_expires_at` (section 4.5) reports an over-age `ACTIVE` token as null, without writing; the token stays `ACTIVE` until a public request flips it or a writer kills it.

### 7.8 Surface isolation

- Invoice lookups read only `invoice_token`; quote lookups read only `quote_token`.
- A quote token presented on an invoice endpoint, and an invoice token presented on a quote endpoint, are indistinguishable from an unknown token on that surface (404 `INVOICE_TOKEN_NOT_FOUND` "Invoice not found." and 404 `QUOTE_TOKEN_NOT_FOUND` "Quote not found." respectively).
- No quote-named route serves an invoice. Quote routes and quote DTOs are unchanged.
- Hash uniqueness holds within each table. A match in the other table is never consulted.

---

## 8. Protected send link

**PR 4.** `POST /api/v1/{slug}/orders/{orderId}/invoices/current/send-link` (operation `sendInvoiceLink`). The Invoice tab's **Accept Remotely** button calls it (since PR 5).

**Request:** an empty JSON object `{}` (`EmptyObjectRequest`; any field gives 400 `VALIDATION_FAILED` "Not allowed.", a non-object 400 on `body`, unparseable 400 `MALFORMED_JSON`). No signature, name or declaration is sent.

**Order of checks:**
1. Standard protected guard (404 / 401 / 403); `orderId` a positive integer (400).
2. The order scoped to the session business and store, locked (404 `ORDER_NOT_FOUND`).
3. Empty-body validation (400).
4. Current invoice read under the lock. None: 422 `INVOICE_REQUIRED`.
5. Current invoice signed (`accepted_at` set): 409 `INVOICE_ALREADY_ACCEPTED` (default message "This invoice has already been accepted. Use Re-send to email it again."). No link is created and any existing token is untouched.
6. Saved customer email gate, as D.1: missing → 422 `CUSTOMER_EMAIL_REQUIRED`; malformed → 422 `CUSTOMER_EMAIL_INVALID`.
7. Persist in the same transaction: mark an existing `ACTIVE` token `REPLACED`, then insert the new `ACTIVE` token (`sent_by_user_id` = session user, `sent_at` = now, `expires_at` = `sent_at` + 7 days). Commit.
8. Deliver after commit, outside the lock: one link-only email to the saved customer email through the new `InvoiceLinkEmailSender` with an `InvoiceLinkEmailRequest` (recipient, subject, plain-text body, order id and the current version number for logging only). Subject `Your invoice from {business_name}`; a short plain-text body with the order number and the link `{app.public-base-url}/i/{token}` exactly once. **No PDF attachment.** Recording-only until the Phase 17 provider, with no bearer link logged at INFO or above.
9. On a successful delivery a short follow-up transaction stamps the token's `emailed_at`. A stamp failure is logged at WARN and the response is still 201 (the link was delivered; failing the request would invite a resend that replaces the delivered link).

**Not required:** a signature, the declaration ticks, an unchanged version (the link follows the latest version), or any quote state.

**Markers:** link sends write only the `invoice_token` markers (`sent_at`, `emailed_at`). They **never** touch `invoice.last_emailed_at` or the `sales_order.last_emailed_at` mirror, which keep their meaning: a signed PDF was emailed (section 11).

**Delivery failure:** 502 `EMAIL_SEND_FAILED` with the message `The invoice link could not be emailed. Please try again.`. The new token stays `ACTIVE` (undelivered) and the old token stays `REPLACED`. A retry is another send, which replaces the undelivered link. This keep-the-new-link rule applies to every send, including a pure resend (the same tradeoff the quote send accepts, 16B §7.1).

**Response — 201 Created** (no token, hash, link, storage path or recipient):

```json
{
  "data": {
    "invoice_link": {
      "sent_at": "2026-11-02T14:40:00",
      "expires_at": "2026-11-09T14:40:00"
    }
  },
  "message": "Invoice link emailed to the customer."
}
```

The response's `expires_at` is informational. The Invoice tab never starts or times polling from it; since PR 6 it polls on InvoiceDetail's `invoice_link_expires_at` (section 13.2).

**LAID:** allowed. There is no CANCELLED rule (section 12).

**Status codes:** 201; 400 (`VALIDATION_FAILED`, `MALFORMED_JSON`); 401; 403; 404 (`ORDER_NOT_FOUND`, `NOT_FOUND`); 409 (`INVOICE_ALREADY_ACCEPTED`); 422 (`INVOICE_REQUIRED`, `CUSTOMER_EMAIL_REQUIRED`, `CUSTOMER_EMAIL_INVALID`); 500 (transactional rollback); 502 (`EMAIL_SEND_FAILED`).

---

## 9. Public invoice surface

**PR 4** (backend) and **PR 5** (page), both implemented.

### 9.1 Route and reserved slug

- Page: the top-level, slugless route `/i/{token}`, a sibling of `/q/{token}` in `App.tsx`. The link in the email is `{app.public-base-url}/i/{token}`.
- API: slugless `/api/v1/public/invoices/{token}`. Spring Security already permits every path and CORS already covers `/api/v1/**`, so no security change is needed.
- **Reserved slug `i` (PR 4):** the PR 4 migration drops and re-adds `chk_business_slug_reserved` with all 16 `V18` words plus `i`: `admin`, `api`, `login`, `static`, `auth`, `health`, `dashboard`, `orders`, `select-store`, `assets`, `new`, `logout`, `account`, `settings`, `public`, `q`, `i`. `V18` is never edited. PR 4 also added `i` to `RESERVED_BUSINESS_SLUGS` in `frontend/src/lib/tenant.ts`.

### 9.2 Rules for every public invoice endpoint

| Method | Full path | Purpose |
|---|---|---|
| GET | `/api/v1/public/invoices/{token}` | the `ACTIVE` document, or a minimal 200 dead-state payload |
| POST | `/api/v1/public/invoices/{token}/accept` | sign the invoice version currently shown |
| GET | `/api/v1/public/invoices/{token}/pdf?version_number=N` | stream the stored PDF of the version shown, while `ACTIVE` |

- Token-only: no slug, no session read or created, no `Set-Cookie`, no `RequestContextGuard`. The page calls them without credentials and never through the protected API client.
- Unknown or malformed token, on all three: 404 `INVOICE_TOKEN_NOT_FOUND`, message exactly `Invoice not found.`. Status, code and message are identical for both.
- There is no viewed-tracking endpoint and nothing is stamped on a read.
- Rate limiting is not implemented (Phase 17, [#128](https://github.com/MuneebHash/flooring-sales-portal/issues/128), widened to these endpoints by an issue comment). No 429 is documented.
- The page protects the token-bearing URL per element, as the quote page does (`referrerPolicy="no-referrer"` on the logo, `rel="noopener noreferrer"` on Pay online).

### 9.3 GET view

`GET /api/v1/public/invoices/{token}` → **200** `{ "data": PublicInvoiceView, "message": "Invoice loaded." }`.

- **Token gate:** shape, hash lookup and lazy expiry (section 7.7). An unknown or malformed token gives 404. A found token always yields 200 with a `state`.
- **One snapshot:** the token's status, the order, the current invoice version and the business presentation are read in **one** read-only `REPEATABLE_READ` transaction (the `QuoteService.getWorkspace` precedent), so the state and the content always come from the same committed moment. A token killed between the gate and this read is reported in its dead state, never with `ACTIVE` content. (Unlike the quote GET, which takes the state from the earlier resolve.) An expiry that falls due between the gate and this read (a token the snapshot still shows `ACTIVE`, now past `expires_at`; a dead token is reported in its own state and never changes status, section 7.5) sends the request through the gate once more (section 7.7), so the `EXPIRED` flip commits before the GET returns 200 with the `EXPIRED` state (PR 4 interpretation I-4).
- **Invariant check:** a token the snapshot shows `ACTIVE` whose current version is signed breaks section 7.6. The server never presents a signed version as signable: it returns 500 with the generic error envelope and no content.
- **ACTIVE payload:** the fields below. **Dead payload:** `state`, `business_name` and `message`; every other field is `null`, and there is no document content and no action. Every key is always present (null values serialised, conventions §3).

| Field | Source | ACTIVE | Dead |
|---|---|---|---|
| `state` | the token status (section 7.2) | `ACTIVE` | the dead state |
| `business_name` | business, read live | ✓ | ✓ |
| `business_logo_url` | `business.logo_path` verbatim, read live (the existing public branding exposure) | nullable | null |
| `accent_color` | business, read live | nullable | null |
| `business_abn` | business invoice config, read live | nullable | null |
| `payment_account_name`, `payment_bank_name`, `payment_bsb`, `payment_account_number` | direct-deposit details, read live | nullable | null |
| `payment_stripe_link_url` | the tenant Stripe payment link, read live, HTTPS only (else null) | nullable | null |
| `order_number` | `sales_order.order_number`: the document number under `TAX INVOICE` | ✓ | null |
| `flooring_type` | `sales_order.flooring_type` (fixed at order creation, conventions §17) | ✓ | null |
| `version_number` | the current invoice version's `version_number`: the page's identity for accept and pdf | ✓ | null |
| `invoice_date`, `due_date` | the current version (`due_date` nullable) | ✓ / nullable | null |
| `customer_name`, `customer_address_line1`, `customer_address_line2` | the current version's frozen words (section 4) | nullable | null |
| `details_of_sale` | the current version's `details_of_sale_snapshot` | nullable | null |
| `sale_price_ex_gst`, `gst_amount`, `sale_price_inc_gst` | the current version; `gst_amount` = inc − ex | ✓ | null |
| `total_paid` ("Payment Made"), `balance_due` ("Balance Due") | the current version | ✓ | null |
| `terms_html` | the current version's stored `terms_snapshot`, verbatim (sanitised when frozen; null = no terms) | nullable | null |
| `expires_at` | the presented token | ✓ | null |
| `message` | null when `ACTIVE`; the per-state message (section 7.2) when dead | null | ✓ |

- **Never present** in any public invoice response (the leak rule; tests in section 16): `invoice_id`, `order_id`, `invoice_token_id`, `quote_version_id`, `source_quote_version_id`, `stored_file_id` or any file id, a storage path or file name, the token or its hash, the customer email, mobile or phone, `created_by_user_id`, `sent_by_user_id`, staff, store contact or salesperson fields, `last_emailed_at`, any `accepted_*` field, `terms_source`, the protected `invoice_link_expires_at` (the view has its own `expires_at` for the presented token), cost, `total_cost`, `gp`, `gp_percent`, and any line or deposit field. The invoice is details plus totals: no itemised invoice rows, and nothing taken from the live Products & Charges.
- The live business presentation fields can differ from the stored PDF after a business-settings change (the quote page's accepted behaviour).
- `docs/Phases.md` §5 records the public exposure exception: an ACTIVE secret quote token **or an ACTIVE secret invoice token** returns these documented presentation fields for its single document.

**ACTIVE example**

```json
{
  "data": {
    "state": "ACTIVE",
    "business_name": "Aussie Floors Group",
    "business_logo_url": "/uploads/1/branding/logo.png",
    "accent_color": "#0F766E",
    "business_abn": "12 345 678 901",
    "payment_account_name": "Aussie Floors Group Pty Ltd",
    "payment_bank_name": "Example Bank",
    "payment_bsb": "062-000",
    "payment_account_number": "12345678",
    "payment_stripe_link_url": "https://buy.stripe.com/test_example",
    "order_number": "SYD-CBD.LC1.00042",
    "flooring_type": "SOFT",
    "version_number": 3,
    "invoice_date": "2026-11-02",
    "due_date": "2026-11-12",
    "customer_name": "Jordan Wilson",
    "customer_address_line1": "4/12 Oxford Street",
    "customer_address_line2": "Paddington NSW 2021",
    "details_of_sale": "Supply and install plush carpet to lounge and dining rooms.",
    "sale_price_ex_gst": 840.00,
    "gst_amount": 84.00,
    "sale_price_inc_gst": 924.00,
    "total_paid": 500.00,
    "balance_due": 424.00,
    "terms_html": "<p>…</p>",
    "expires_at": "2026-11-09T14:40:00",
    "message": null
  },
  "message": "Invoice loaded."
}
```

**Dead example (`REWRITTEN`)**

```json
{
  "data": {
    "state": "REWRITTEN",
    "business_name": "Aussie Floors Group",
    "business_logo_url": null,
    "accent_color": null,
    "business_abn": null,
    "payment_account_name": null,
    "payment_bank_name": null,
    "payment_bsb": null,
    "payment_account_number": null,
    "payment_stripe_link_url": null,
    "order_number": null,
    "flooring_type": null,
    "version_number": null,
    "invoice_date": null,
    "due_date": null,
    "customer_name": null,
    "customer_address_line1": null,
    "customer_address_line2": null,
    "details_of_sale": null,
    "sale_price_ex_gst": null,
    "gst_amount": null,
    "sale_price_inc_gst": null,
    "total_paid": null,
    "balance_due": null,
    "terms_html": null,
    "expires_at": null,
    "message": "This invoice has been revised. Please contact the store for the updated invoice."
  },
  "message": "Invoice loaded."
}
```

### 9.4 POST accept

`POST /api/v1/public/invoices/{token}/accept`, `multipart/form-data` with exactly two parts:

| Part | Kind | Rules |
|---|---|---|
| `expected_version_number` | form field | exactly once; a positive integer: the `version_number` of the invoice shown (section 5) |
| `signature` | file | exactly once; `image/png`; non-empty; at most 2,097,152 bytes; PNG rules below |

Nothing else is accepted: no name, no declaration flags, no money values. The two declarations are a frontend-only gate.

**PNG rules (the public quote accept's, unchanged):** the bytes start with the 8-byte PNG signature; the first chunk is a 13-byte `IHDR`; the `IHDR`, checked before any pixel is decoded, is at most 8,192 px per side and 4,000,000 px in total, with bit depth at most 8 and a valid PNG colour type (0, 2, 3, 4 or 6); the JDK PNG reader decodes it within those bounds. The server re-encodes the decoded pixels as a clean PNG: only those normalised bytes are stored and embedded in the signed PDF (same dimensions and pixels; ancillary chunks and bytes after `IEND` dropped). The page exports within these bounds (`toBoundedSignaturePng`).

**Order of checks** (nothing persists on a refusal except a committed expiry flip):

0. Transport-level rejections happen first, in the framework, before the token gate: a request over the global multipart limit gives 400 `FILE_TOO_LARGE`; a non-multipart request gives 415 (code `VALIDATION_FAILED`).
1. **Token gate** (its own transaction): the shape gate and hash lookup (unknown or malformed: 404 `INVOICE_TOKEN_NOT_FOUND`), then lazy expiry (the flip commits, then 410 `INVOICE_LINK_EXPIRED`), then any other dead status: 410 with its code (section 7.2). A dead link with an invalid body still gets its 410.
2. **Body validation**, with no database access and no lock: one 400 `VALIDATION_FAILED` listing every offending part (an unknown file part or form field, `Not allowed.`; a repeated `signature` or `expected_version_number`, `Must appear at most once.`; a missing `expected_version_number`, `Required.`; a malformed one, `Must be a positive integer.`). Then a missing or empty `signature`: 422 `SIGNATURE_REQUIRED` (its default message). Then a declared type that is not `image/png`, more than 2,097,152 bytes, or a PNG outside the rules above: 400 `SIGNATURE_INVALID` (default message).
3. **One transaction under the order row lock** (an unscoped `SELECT … FOR UPDATE` on the token's `order_id`, the public quote accept's lock, which serialises with every protected order mutation):
   1. Re-read the token by id. Dead: 410 for its true state. Past expiry: the flip is committed, then 410 `INVOICE_LINK_EXPIRED`.
   2. Read the current invoice version.
   3. **Stale comparison:** `expected_version_number` ≠ current `version_number` gives 409 `INVOICE_VERSION_STALE`, `Invoice updated, please refresh`. This is the authority; the earlier token read and any frontend check are only hints.
   4. Invariant: a signed current version under an `ACTIVE` token gives 500 with nothing written (section 7.6).
   5. Business rule: the current version's `customer_name_snapshot` null or blank gives 422 `ACCEPTED_CUSTOMER_NAME_REQUIRED` with the neutral customer sentence `This invoice can no longer be accepted online. Please contact the store.` (the D11 pattern; the code still names the cause). This is the only business-rule refusal on this endpoint. There is no customer-email gate, so blanking the customer email after the send never blocks signing.
   6. Persist (section 10). Before the first file write.
4. **After commit:** the customer signed-PDF email and the store notification (section 10). Never throwing.

**Response — 201 Created:** `{ "data": { "state": "SIGNED" }, "message": "Invoice accepted." }`. No id, amount, name, timestamp, file reference or token is returned. The link is now dead: GET returns 200 `state: SIGNED` with the minimal payload, and accept and pdf return 410 `INVOICE_LINK_SIGNED`.

**Example — stale (409)**

```json
{ "error": { "code": "INVOICE_VERSION_STALE", "message": "Invoice updated, please refresh" } }
```

**Example — dead link (410)**

```json
{ "error": { "code": "INVOICE_LINK_SIGNED", "message": "This invoice has already been signed. Please contact the store if you need a copy." } }
```

**Status codes:** 201; 400 (`VALIDATION_FAILED`, `SIGNATURE_INVALID`, `FILE_TOO_LARGE`); 404 (`INVOICE_TOKEN_NOT_FOUND`); 409 (`INVOICE_VERSION_STALE`); 410 (`INVOICE_LINK_*`); 415; 422 (`SIGNATURE_REQUIRED`, `ACCEPTED_CUSTOMER_NAME_REQUIRED`); 500 (transactional rollback or the invariant breach). A committed signing never becomes a 500 because of post-commit email or stamp work.

### 9.5 GET pdf

`GET /api/v1/public/invoices/{token}/pdf?version_number=N`: the stored PDF of the version the page shows. It is never regenerated, and no other version is reachable.

1. Token gate (404; lazy expiry; 410 for a dead link).
2. `version_number` query parameter: missing, `Required.`; not a positive integer, `Must be a positive integer.` (400 `VALIDATION_FAILED`).
3. One read-only snapshot: the token's status (dead: 410), the current invoice version and its stored file row. An expiry that falls due between the gate and this snapshot (a token the snapshot still shows `ACTIVE`, now past `expires_at`; a dead token keeps its own 410) sends the request through the gate once more (section 7.7), so the `EXPIRED` flip commits before the 410 `INVOICE_LINK_EXPIRED` (PR 4 interpretation I-4). `version_number` ≠ the current version gives 409 `INVOICE_VERSION_STALE`, `Invoice updated, please refresh`.
4. **200** `application/pdf`, `Content-Disposition: inline; filename="invoice-{order_number}-v{version_number}.pdf"` (the stored name), the stored bytes verbatim. A file missing on disk gives 500.

This is the read binding that keeps the Preview PDF button on the version shown: a payment or void between page load and preview gives 409, and the page re-reads instead of opening another version's PDF. History access (an older version's PDF) is not possible on the public surface.

### 9.6 Page behaviour

The page is specified in the UX lock §20. In short: the public quote page's layout with invoice wording (TAX INVOICE and the order number; Invoice To; Details of Sale; invoice date and due date; totals; Payment Made; Balance Due; ABN, direct deposit and Pay online; the stored terms); Preview PDF (section 9.5); the two Invoice tab declarations as a frontend-only gate; a signature pad; Accept Invoice. It never shows a signed or dead invoice: a dead state shows only its message (section 13.1).

---

## 10. Remote signing: transaction and side effects

**PR 4.**

### 10.1 Persist (one transaction, order row lock held, file-write-first)

- Store the normalised signature PNG and its `stored_file` row (`signature-{order_number}-v{N}.png`, `image/png`), N being the new version number.
- Render the signed invoice PDF from the current version's carried words (section 4) with the acceptance block filled (signature, `Accepted by {name} on {dd/MM/yyyy HH:mm}`; the existing invoice template, as D.8), and store it with its `stored_file` row (`invoice-{order_number}-v{N}.pdf`). The invoice template has no declaration squares; that is unchanged.
- Append version current + 1, with D.8's persistence from PR 3 (section 5.4): `invoice_date`, `due_date`, details, ex and inc totals, `total_paid`, `balance_due`, `source_quote_version_id`, `terms_snapshot` and the three frozen identity columns copied from the current version, so the signed PDF shows the invoice date of the version the customer reviewed and the stale check matched (section 4.2); `accepted_at` = now, the signing time; `accepted_customer_name` = the current version's `customer_name_snapshot`, verbatim; `accepted_signature_file_id` = the new signature file; `last_emailed_at` null; `created_by_user_id` = section 10.2.
- Reset the `sales_order.last_emailed_at` mirror to null.
- The presented token `ACTIVE → SIGNED`, `dead_at` = `accepted_at` (status-guarded).
- Quote-link cancellation (section 10.5).
- Nothing else: no order price write (D6b belongs to quote acceptance only), no order status, line, draft or payment change.
- Any failure rolls every write back and deletes only this request's files; the token stays `ACTIVE`.

### 10.2 Creator

`created_by_user_id` on the appended version = `invoice_token.sent_by_user_id`, the staff member who sent the link the customer signed through. The column stays `NOT NULL`; no migration relaxes it.

### 10.3 Customer email (after commit)

As section 6: the signed PDF of the appended version to the saved customer email; a missing or invalid saved email skips the send with a WARN log; success stamps the version's `last_emailed_at` and the mirror; failure leaves both null (Re-send retries). Recording-only until Phase 17.

### 10.4 Store notification (after commit)

- A new `InvoiceAcceptanceNotificationSender` interface with an `InvoiceAcceptanceNotificationRequest` (recipient, subject, plain-text body, order id and invoice version number for logging only) and a recording-only implementation. It is separate from the quote notification sender and from the customer email senders (the 16F D3 pattern).
- Recipient: the order's own store `store.email`, with no business, salesperson or customer fallback. Blank (null): skipped with a WARN log.
- Content: subject `Invoice accepted: {order_number}`; a text-only body with the order number, the signed version number, the accepted name, the accepted time and the inc-GST total. **No attachment, link, token, signature, storage reference, cost or GP.**
- Failure: WARN log only (order id and version number; never the recipient, body or exception message). No persisted marker or column (16F D12 parity). It never stamps `invoice.last_emailed_at` or the mirror.
- D.8 and Path A send no store notification (unchanged).

### 10.5 Quote links (D5(c) extended)

In the same transaction and under the same lock, remote invoice acceptance cancels the order's `ISSUED` quote version, if one exists, and its `ACTIVE` quote token (both `CANCELLED`), exactly as D.8 has done since 16F (D5(c)). With no `ISSUED` quote it is a no-op. An `ACCEPTED` quote is never revoked and never blocks signing (D5(d)). Quote acceptance still never creates an invoice, and quote sends, acceptance and cancellation never touch invoice links. Path A keeps its rule that it never changes a quote version or quote token; its 16G side effects are the customer PDF email (section 6) and, since PR 4, killing an `ACTIVE` invoice token as `SIGNED` (section 7.5).

A remote invoice signature sets `accepted_at` at signing time, so the 16F D5(b) comparison applies unchanged: a quote signed earlier can no longer be converted; a quote signed strictly later still can.

### 10.6 Never on rollback or refusal

No customer email and no store notification are sent for a rolled-back, stale, dead-link or otherwise refused request. Both run only after the acceptance transaction has committed, as plain calls after it returns (no outer transaction; the 16F quote accept precedent).

---

## 11. Email markers and the dashboard mirror

`invoice.last_emailed_at` means "this version's signed PDF was emailed successfully". `sales_order.last_emailed_at` is the dashboard mirror and always equals the current version's value, including null (Phase 13 §11.1). Link sends never move either.

| Operation | New current version's `last_emailed_at` | `sales_order.last_emailed_at` |
|---|---|---|
| D.1 Create, D.2 Rewrite | null | null |
| D.7 payment, D.10 void (signed or not) | null (never emails) | null |
| D.8 accept, email succeeds | timestamp | same timestamp |
| D.8 accept, email fails or the stamp fails (non-fatal from PR 3) | null | null |
| D.9 Re-send succeeds | unchanged version, stamped in place | same timestamp |
| D.9 Re-send fails (502) | unchanged | unchanged |
| Path A, email succeeds (PR 3) | timestamp | same timestamp |
| Path A, email fails or the stamp fails (PR 3) | null | null |
| Path A, saved email missing or invalid: skipped (PR 3) | null | null |
| Remote accept, email succeeds (PR 4) | timestamp | same timestamp |
| Remote accept, email fails, skipped or the stamp fails (PR 4) | null | null |
| Send link, delivered or failed (PR 4) | unchanged (no new version; only `invoice_token.sent_at` / `emailed_at`) | unchanged |
| Store notification (PR 4) | unchanged | unchanged |

The table shows the markers only. The D.8 and Path A response messages follow the delivery outcome, so a stamp failure after a successful delivery keeps the delivered message while both markers stay null (section 6.2).

The dashboard's `invoice_accepted` keeps reading the current version's `accepted_at`.

---

## 12. Order status rules

| Action | LAID | CANCELLED |
|---|---|---|
| Send link (PR 4) | allowed | no special rule (allowed, as for every invoice action) |
| Public GET, accept and pdf (PR 4) | allowed | no special rule |
| D.8, Path A, payment, void, Re-send | allowed (unchanged) | unchanged |
| D.2 Rewrite | 422 `ORDER_LOCKED` (unchanged) | unchanged |

A status change never kills an invoice link. API-Conventions §16 lists the two new LAID-allowed actions.

---

## 13. 16G quote changes (#120, #121, #122, #124, #126)

### 13.1 Specific dead-link messages on both public pages (#126, PR 5, implemented)

- **Quote page:** a dead GET state shows `data.message` (the existing per-state text, 16B §7.2). A 404 shows the backend message (`Quote not found.`). A PDF 410 or 404 shows that response's message. The accept path keeps showing 410 and 422 messages verbatim. The per-state texts are unchanged. A load that gets no readable answer keeps the existing generic sentence.
- **Invoice page:** a dead GET state shows `data.message` (section 7.2). A 404 shows `Invoice not found.`. A PDF or accept 410 shows its message. A load that gets no readable answer shows `We could not load this invoice. Please try again.` with a retry. The only invoice dead reasons are the four in section 7.2.

### 13.2 Polling and the invoice signing draft (#120, PR 6, implemented)

- **Quote tab:** while `current_issued` is non-null (a quote link waits for a signature), re-read the issued and accepted state every 5 seconds (since Batch A PR 2, Muneeb's decision 29 October 2026; 15 seconds in 16G PR 6) through `refreshSnapshots({ reportError: false })`, only while the browser tab is visible and the Quote tab is the active workspace tab. The 16F refresh guards stay (an older response never overwrites a newer one; pending reads are discarded on unmount or order change; draft rows, totals and the autosave baseline are never re-seeded). A failed poll changes nothing and shows no banner.
- **Invoice tab:** re-read the current invoice (`GET …/invoices/current`) every 5 seconds (since Batch A PR 2, Muneeb's decision 29 October 2026; 15 seconds in 16G PR 6) while the last applied InvoiceDetail shows `invoice_link_expires_at` non-null (section 4.5) and the displayed invoice unsigned, only while the browser tab is visible and the Invoice tab is the active workspace tab. Polling stops when a read shows the invoice signed, `invoice_link_expires_at` null, or when the returned `invoice_link_expires_at` time passes.
  - The condition is server state only. It holds after a page reload, for a link sent from another staff session or device, and for an `ACTIVE`, unexpired link kept after a failed send (502, section 8). It does not depend on a successful Accept Remotely in this workspace or on an expiry remembered from a send response.
  - The tab learns the field from every current-invoice read: the initial load, a reload, the re-read that follows every Accept Remotely attempt whatever its outcome (so a 201, or a 502 whose new link stays `ACTIVE`, starts polling at once), and each poll.
  - Failed polls are silent and keep the existing state and response guards. The field is never displayed: no link status line, no expiry display, no cancel.
- **Signing draft (Invoice tab):** any refresh or poll that installs a different invoice version (another `version_number`, including a changed version found by the pre-accept re-read) clears the signature ink and both declaration ticks before Accept Invoice can be enabled for that version. A refresh or poll that installs the unchanged version keeps the existing ink and ticks.
  - The existing response-order, order-change, unmount and mutation (single-flight) guards stay. An ignored or failed response never installs an older version and never clears a valid draft.
  - Before PR 6 the pre-accept re-read already cleared the ink on a changed version, and the ticks already reset on any version change. Since PR 6 the rule applies to every refresh and poll path, the ink included.
  - The PR 3 stale-409 handling (section 5.7) is unchanged and stays in PR 3.
- Polling only reads: it never writes and never flips an expiry. On the Quote tab it never touches draft rows, totals or the autosave baseline. On the Invoice tab, an installed version change invalidates the signing draft of the replaced version, as above; that is the only editing state a poll can change.

### 13.3 Financial refresh and Update Sale Price (#121, PR 6, implemented)

- Re-read the order financial summary through the existing guarded reader when a quote refresh (any trigger, including a poll) installs a new accepted version (a different `accepted.quote_version_id`), and whenever Details of Sale is opened.
- **Update Sale Price is disabled until the sale-price input has been edited**, so a stale, unedited value can never be re-submitted over the price that a remote acceptance wrote (D6b).

### 13.4 Version-bound accepted quote reads (#122, PR 6, implemented)

| Removed in PR 6 | Since PR 6 |
|---|---|
| `GET …/orders/{orderId}/quote/pdf?type=accepted` (latest accepted) | `GET /api/v1/{slug}/orders/{orderId}/quote/accepted/{quoteVersionId}/pdf` (`downloadAcceptedQuoteVersionPdf`) |
| `GET …/orders/{orderId}/quote/accepted/signature` (latest accepted) | `GET /api/v1/{slug}/orders/{orderId}/quote/accepted/{quoteVersionId}/signature` (`downloadAcceptedQuoteVersionSignature`) |

- `quoteVersionId` is the `quote_version_id` the Accepted Quote view displays. The read returns that version's stored signed PDF or stored normalised signature only if the version belongs to the order in scope and its status is `ACCEPTED`; otherwise 404 (`QUOTE_PDF_NOT_FOUND` / `QUOTE_SIGNATURE_NOT_FOUND`), with the order scope 404 first and no existence leak. A malformed id gives 400 `VALIDATION_FAILED`.
- `accepted.signature_download_path` carries the versioned path. The client builds the PDF path from `accepted.quote_version_id`, and discards any response for a version it no longer displays.
- File names, LAID allowance and portal-only rules are unchanged. `pdf?type=issued` is unchanged. There is still no list of older accepted quotes and no history UI.
- These are quote reads only. Binding the protected Invoice tab PDF and signature reads stays with [#114](https://github.com/MuneebHash/flooring-sales-portal/issues/114) (follow-up F53).

### 13.5 Provenance boolean, whole-second quote timestamps and the delivery outcome (#124, PR 6, implemented)

- `QuoteAcceptedSummary` gains one always-present boolean, **`invoice_created_from_this_version`**, computed inside the one-snapshot workspace read: `true` when the order's current invoice was created from this accepted quote version by Create Invoice (Path A), including the payment and void versions carried from it (its source quote version is this version); `false` when there is no invoice or the current invoice has another origin (Create, Rewrite, or a signing that was not this conversion). It reports the current invoice, because the Accepted Quote explanation describes the current invoice.
- When `invoice_eligible` is false, the Accepted Quote explanation reads `An invoice has already been created from this quote.` if `invoice_created_from_this_version` is true, otherwise `The current invoice was signed at the same time or later than this quote. A newer signed quote is required to create an invoice from a quote.`. The 19-character timestamp comparison is removed. No provenance id is added to InvoiceDetail.
- Every quote timestamp the API returns is formatted to whole seconds, `YYYY-MM-DDTHH:mm:ss` (API-Conventions §5): `QuoteAcceptedSummary.accepted_at`; `QuoteIssuedSummary.first_sent_at`, `last_sent_at`, `last_emailed_at`, `viewed_at` and `token_expires_at`; `QuoteDraft.updated_at`; `PublicQuoteView.expires_at`. Stored values are unchanged, and fractional seconds are not restored anywhere. Whole-second strings never decide delivery; `last_email_delivered` does (below).
- `QuoteIssuedSummary` gains one always-present, non-null boolean, **`last_email_delivered`**. The server computes it from the stored markers at full precision, before whole-second formatting: `true` when `last_emailed_at` is non-null and is not before `last_sent_at`, otherwise `false`.
  - The markers keep their roles: `last_sent_at` is the latest send attempt, stamped before delivery; `last_emailed_at` is the success marker, written only by the attempt-bound stamp below.
  - **Attempt-bound success stamp (PR 6, implemented).** After a successful delivery, the send's follow-up transaction stamps `last_emailed_at` only while this attempt is still the latest. Under the order lock the stamp already takes, it runs `UPDATE quote_version SET last_emailed_at = :emailedAt WHERE quote_version_id = :id AND last_sent_at = :thisAttemptSentAt`, where `:thisAttemptSentAt` is the `last_sent_at` value this attempt stored before delivery. A superseded attempt's late success writes nothing (0 rows, no error, logged at DEBUG). `last_emailed_at` therefore means the last successful email of an attempt that was still the latest when it completed, and the `last_email_delivered` predicate is unchanged. Before PR 6 the stamp had no attempt check: an older attempt that succeeded after a newer attempt had failed still stamped `last_emailed_at`, later than the newer `last_sent_at`, so the history read as delivered.
  - It is a derived response field: no column and no migration.
  - It appears wherever `QuoteIssuedSummary` is returned (the workspace `current_issued` and the send and cancel responses), never on a public quote or invoice payload.
- Customer Quote's existing **Not delivered** badge reads it: Not delivered when `sent_channel` is `EMAIL` and `last_email_delivered` is `false`. The email-channel applicability is unchanged, and the dormant SMS channel still never shows Not delivered. The client stops comparing the returned timestamp strings.
- Same-second histories (one date, times shown to the millisecond as stored):

| Stored last attempt (`last_sent_at`) | Stored last successful email (`last_emailed_at`) | `last_email_delivered` | Badge (email channel) |
|---|---|---|---|
| 12:22:10.400 | 12:22:10.600 | `true` | delivered |
| 12:22:10.400 | 12:22:10.200 | `false` | Not delivered |
| 12:22:10.400 | null | `false` | Not delivered |

The first two histories return identical whole-second strings (`…T12:22:10` for both markers) yet give different booleans and badges. The second is the regression case: a successful send followed, in the same second, by a failed resend.

---

## 14. Error contract

Envelope unchanged (conventions §3). `error.code` stays a free-form string; OpenAPI does not enumerate codes.

**New codes:**

| Code | HTTP | Default message | Where | PR |
|---|---|---|---|---|
| `INVOICE_VERSION_STALE` | 409 | `Invoice updated, please refresh` | D.8; remote accept; public pdf; D.2 Rewrite | 3 (D.8), 4 (public); Batch A PR 2 (D.2 Rewrite, #149) |
| `QUOTE_VERSION_STALE` | 409 | `The accepted quote changed while the confirmation was open. Review the accepted quote before creating an invoice.` | Path A | 3 |
| `INVOICE_TOKEN_NOT_FOUND` | 404 | `Invoice not found.` | all public invoice endpoints (unknown or malformed token) | 4 |
| `INVOICE_LINK_REPLACED` | 410 | `This invoice link has been replaced. Please use the latest invoice link.` | public accept, pdf | 4 |
| `INVOICE_LINK_REWRITTEN` | 410 | `This invoice has been revised. Please contact the store for the updated invoice.` | public accept, pdf | 4 |
| `INVOICE_LINK_SIGNED` | 410 | `This invoice has already been signed. Please contact the store if you need a copy.` | public accept, pdf | 4 |
| `INVOICE_LINK_EXPIRED` | 410 | `This invoice link has expired. Please contact the store.` | public accept, pdf | 4 |

**Reused codes with 16G meanings:**

| Code | HTTP | 16G use |
|---|---|---|
| `ACCEPTED_CUSTOMER_NAME_REQUIRED` | 422 | D.8: the current version's frozen name is blank (default message). Remote accept: the same, with `This invoice can no longer be accepted online. Please contact the store.` |
| `EMAIL_SEND_FAILED` | 502 | D.9 Re-send (unchanged) and send-link, with `The invoice link could not be emailed. Please try again.` |
| `INVOICE_ALREADY_ACCEPTED` | 409 | D.8 (unchanged), Path A (unchanged message) and send-link (default message) |
| `INVOICE_REQUIRED` | 422 | D.8 (unchanged) and send-link |
| `CUSTOMER_EMAIL_REQUIRED` / `CUSTOMER_EMAIL_INVALID` | 422 | D.8 (unchanged) and send-link. Never Path A or remote accept. |
| `SIGNATURE_REQUIRED` / `SIGNATURE_INVALID` | 422 / 400 | D.8 (unchanged) and remote accept (default messages) |
| `VALIDATION_FAILED` / `MALFORMED_JSON` | 400 | the carriers (section 5.3); unexpected parts, including D.8's former `accepted_customer_name` |
| `QUOTE_PDF_NOT_FOUND` / `QUOTE_SIGNATURE_NOT_FOUND` | 404 | the versioned accepted reads (section 13.4) |

Public invoice endpoints never return staff wording: the only messages a customer can meet are the ones listed in sections 7.2, 9.4 and 9.5, the default `SIGNATURE_REQUIRED` and `SIGNATURE_INVALID` texts, and the global transport responses.

---

## 15. Frontend summary

The detailed UX is in the UX lock §20 (Invoice tab and public invoice page) and §13–§14 (quote pages). In short:

- **Invoice tab:** Invoice To from InvoiceDetail and the terms-source wording for `BUSINESS` (PR 2); D.8 sends `expected_version_number`, drops the name part and handles the stale 409; the frozen-name gate (PR 3); the unsigned bottom bar became **Accept Invoice | Accept Remotely**, Accept Remotely sends the link and shows success or failure, and Download PDF became **Preview PDF** (PR 5); polling on the server field `invoice_link_expires_at` (never displayed) and the signing-draft reset when a refresh installs another version (PR 6).
- **Quote tab:** `{"quote_version_id": N}` and the stale 409 (PR 3); #120, #122 and #124, and the Not delivered badge from `last_email_delivered` (PR 6).
- **Details of Sale:** #121 (PR 6).
- **Public pages:** `/i/{token}` (PR 5); specific dead-link messages on both pages (PR 5).
- The 16G single-session browser smoke passed on 23 October 2026 before PR #154 merged; the two-session scenarios are deferred to Playwright after deployment.

---

## 16. Mandatory tests by PR

New backend tests self-seed (no dependence on the V4 legacy seed). No frontend test runner is added in 16G: frontend changes are verified by `npm run build` and a manual smoke test.

| PR | Mandatory tests |
|---|---|
| 2 | Create and Rewrite freeze the live name, billing lines and sanitised terms; payment, void and D.8 copy the previous version's words verbatim, including null terms; a customer rename and a business-terms edit after a version never change that version's PDF, InvoiceDetail or the next payment, void or D.8 version; Path A copies its own quote's `V17` snapshot and terms; null terms never fall back to live terms; `terms_source` is `QUOTE` or `BUSINESS` on every InvoiceDetail; the backfill fills existing rows and leaves terms untouched. |
| 3 | Mandatory value validation (missing, malformed, repeated) and stale rejection under the lock for D.8 and Path A, including a version committed while the request waits (holder-connection harness); D.8 refuses an `accepted_customer_name` part with 400; a blank frozen name gives 422; a frozen name longer than 150 characters is accepted and stored in full; a cross-day D.8 (the current version dated D1, the acceptance on D2) appends a version whose stored `invoice_date` is D1, whose signed PDF shows D1 and whose `accepted_at` is the D2 signing time; Path A email success, delivery failure and the missing or invalid email WARN skip, with no creation gate and the matching marker and mirror values; injected runtime failures in the D.8 and Path A sender and stamp after commit leave the operation committed with a 201; the shared hash helper keeps the quote token behaviour (existing quote tests stay green). |
| 4 | Remote stale rejection, including a version committed while the request waits; holder-connection interleavings in both lock orders for a double remote accept and for remote accept against D.8, payment, void, Rewrite and Path A, and link send against remote accept; lazy expiry at the locked re-read with no invoice change; the lifecycle (section 7.5), replacement and send failure (502 keeps the new link, old stays `REPLACED`); the partial unique index; the customer email and store notification only after commit, never on rollback or a stale refusal, with separate markers (the notification and link sends never stamp invoice markers); injected render, storage, insert and commit failures with request-owned cleanup; public endpoints exercised through the real Spring Security filter chain, with no session created and no `Set-Cookie`; token-kind isolation both ways; unknown and malformed parity; the strict allowlist and a key and value leak scan of every public invoice payload; `created_by_user_id` = the sender; a cross-day remote accept (the current version dated D1, the signing on D2) appends a version whose stored `invoice_date` is D1, whose signed PDF (stored and emailed) shows D1 and whose `accepted_at` is the D2 signing time; D5(c) on remote signing; the `chk_business_slug_reserved` replacement rejects `i` and still rejects `q`; `InvoiceDetail.invoice_link_expires_at` on every InvoiceDetail response: the `expires_at` of an `ACTIVE`, unexpired link, and null with no link, a dead link (`REPLACED`, `REWRITTEN`, `SIGNED`, `EXPIRED`) or an `ACTIVE` token past `expires_at`, where that read writes nothing (the token stays `ACTIVE`, no expiry flip); the field is absent from E.1 and from every public invoice payload. |
| 5 | `npm run build`; manual smoke: send link, open, Preview PDF, sign, dead link on reload, stale handling after a payment, Accept Remotely absent on a signed invoice, specific dead-link messages on both pages. |
| 6 | Backend: versioned accepted reads (ownership, `ACCEPTED` status and 404 on a miss); `invoice_created_from_this_version` true after Path A and its payment and void versions, false after a Rewrite or a later in-store signing; quote timestamps in whole seconds; `last_email_delivered` computed from the stored markers at full precision for the three histories in section 13.5 (true; false for a failed resend in the same second as a successful send, the regression case; false with no successful email), always present and absent from the public payloads; two overlapping resends where the newer attempt fails and the older completes later: the older attempt's success writes nothing (0 rows, no error), `last_email_delivered` is `false` and Customer Quote shows Not delivered. `npm run build` and a manual smoke: the Quote tab and Invoice tab polling; the Invoice tab polling condition after a reload, for a link sent from another staff session, for an `ACTIVE` link kept after a failed send, and stopping when a read shows the invoice signed, `invoice_link_expires_at` null or the returned expiry passed; a refresh or poll that installs a different version clears the ink and both ticks before Accept Invoice can be enabled, an unchanged-version poll keeps them, and an ignored or failed response clears nothing; Not delivered follows `last_email_delivered` on the email channel only; the financial refresh. |

---

## 17. Known limitations and follow-ups

The follow-up tickets proposed in the 16G decisions were filed on 22 October 2026:

- [#148](https://github.com/MuneebHash/flooring-sales-portal/issues/148): D.8 and D.9 post-commit email windows: the emailed PDF, the stamped row and the response can describe different versions (audit F49; the real defects only, not ordinary delivery timing); the issue also covers concurrent quote or invoice link sends delivering a superseded link last. Post-pilot hardening, with the Phase 17 email provider; related [#142](https://github.com/MuneebHash/flooring-sales-portal/issues/142).
- [#149](https://github.com/MuneebHash/flooring-sales-portal/issues/149): Rewrite from a stale screen clears a remote signature the salesperson never saw (F52). Batch A PR 2 (the Rewrite version precondition, section 5.8).
- [#150](https://github.com/MuneebHash/flooring-sales-portal/issues/150): In-store D.8 trusts the declared PNG type; apply the public path's content checks (F55). Phase 17.
- [#151](https://github.com/MuneebHash/flooring-sales-portal/issues/151): Public controller tests run without the security filter chain; test recorders retain bearer links (F57, F59). Phase 17 or Phase 19, still open.
- [#152](https://github.com/MuneebHash/flooring-sales-portal/issues/152): Rollback hooks on an unknown commit outcome (F60). Deferred (post-pilot) hardening.

The edits proposed to existing issues were posted as issue comments on 22 October 2026: #128 adds the invoice public endpoints; #138 lists the remaining drift (the two broken upload response refs and the 69 `nullable` + `allOf` properties); #136 adds the stale comments listed by the audit (F63); #114 adds the Invoice tab read binding (F53). W1 (the `business_quick_description` delete order) is [#153](https://github.com/MuneebHash/flooring-sales-portal/issues/153), Phase 17 onboarding.

---

## Appendix — OpenAPI alignment

`docs/openapi.yaml` was updated in PR 1, before any 16G code. Every new or changed element names, in its description, the PR that implemented it, and all six PRs have merged; a path or field alone is still not evidence of an implementation.

- **New paths:** `POST /orders/{orderId}/invoices/current/send-link` (`sendInvoiceLink`, PR 4); `GET /public/invoices/{token}` (`getPublicInvoice`), `POST /public/invoices/{token}/accept` (`acceptPublicInvoice`) and `GET /public/invoices/{token}/pdf` (`downloadPublicInvoicePdf`), PR 4, each with a `/api/v1` server override, `security: []` and the new `InvoiceToken` path parameter; `GET /orders/{orderId}/quote/accepted/{quoteVersionId}/pdf` (`downloadAcceptedQuoteVersionPdf`) and `GET /orders/{orderId}/quote/accepted/{quoteVersionId}/signature` (`downloadAcceptedQuoteVersionSignature`), PR 6.
- **Changed operations:** `acceptCurrentInvoice` (PR 3: expected version, no name part, stale 409, non-throwing post-commit; the message follows the delivery outcome, so a stamp failure after a successful delivery keeps the emailed message, section 6.2; the signing version copies `invoice_date` from the current version, where before PR 3 it was the signing day, section 5.4); `createInvoiceFromQuote` (PR 3: `CreateInvoiceFromQuoteRequest`, stale 409, automatic email whose message follows the delivery outcome; PR 2: frozen identity); `createInvoice`, `rewriteInvoice` (PR 2 freeze; PR 4 Rewrite kills the link); `recordPayment`, `voidPayment` (PR 2 copy; link survives); `downloadQuotePdf` `type=accepted` and `downloadAcceptedQuoteSignature` (deprecated in PR 1; removed in PR 6). Description notes only: `getCurrentInvoice` (the derived `invoice_link_expires_at`, PR 4; Invoice tab polling on it, PR 6), `getQuoteWorkspace` (PR 6, including `last_email_delivered`), `getPublicQuote` (#126 page behaviour, token-kind isolation), `acceptPublicQuote` (D5(c) on remote invoice signing, PR 4), `resendCurrentInvoice` (the retry route for the new signed-PDF emails) and `downloadCurrentInvoicePdf` (the Preview PDF label, PR 5).
- **New schemas:** `CreateInvoiceFromQuoteRequest`; `InvoiceLinkSendResult` and its wrapper; `PublicInvoiceState`; `PublicInvoiceView` and its wrapper; `PublicInvoiceAcceptRequest`; `PublicInvoiceAcceptResult` and its wrapper. New shared response `InvoiceLinkGone` (410). New parameters `InvoiceToken` (PR 4) and `QuoteVersionId` (PR 6).
- **Changed schemas:** `SignatureAcceptRequest` (`expected_version_number` + `signature`; no name part or 150-character limit); `InvoiceDetail` (three frozen identity fields, PR 2; the derived `invoice_link_expires_at`, PR 4; `terms_source` enum `QUOTE` / `BUSINESS`; an `invoice_date` description: who dates each version, and the signing versions that copy it: D.8 since PR 3 (before PR 3, the signing day) and remote signing since PR 4); `QuoteIssuedSummary` (the derived `last_email_delivered`, PR 6); `QuoteAcceptedSummary` (`invoice_created_from_this_version`; versioned `signature_download_path`); `Timestamp`, `PublicQuoteView.expires_at` and `QuoteAcceptedSummary.accepted_at` (the whole-second note, PR 6; `Timestamp` also notes that whole-second strings never decide delivery); `PublicQuoteView.message` (shown by the page since PR 5); `StandardError.code` (a description listing the 16G codes; still a free-form string, not an enum); `DashboardOrderRow.last_emailed_at` (#138 item 1).
- `EmptyObjectRequest` is unchanged and keeps its other eight consumers; `sendInvoiceLink` uses it too. (Since Batch A PR 2 `rewriteInvoice` uses `RewriteInvoiceRequest` instead, so seven other consumers remain.)
- #138 items 2 to 5 were corrected in PR 1: the three undocumented endpoints were documented as implemented at the PR 1 base (`getPublicBusiness`, `GET /public/businesses/{slug}` with the `/api/v1` server override and `security: []`; `getInvoiceConfig`, `GET /invoice-config`; `listQuickDescriptions`, `GET /quick-descriptions`), with the schemas `PublicBusinessResponse`, `TenantInvoiceConfigResponse` (each with a wrapper) and `QuickDescriptionsResponse`, the `BusinessSlug` parameter and a `Tenant` tag; `info.description` lists the 16B and 16G contracts; the rate-limit notes cite #128; the dangling "Not a documented response." was removed.
