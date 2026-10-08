# Phase 16D — Quotation UX / Product Lock

Status: **locked product + UX contract for Phase 16D onward.** Docs-only. No code is implemented by this document.

Implementation status (16F PR2: backend Path A; base `main` @ `3f778fd`): the 16D and 16E behaviour described here is **built**: Quote Draft (16D), Send by Email + Customer Quote (16E-B), and the public read-only quote page with viewed tracking (16E-C). 16F PR1 added the **backend only** of remote acceptance: the public accept endpoint (signature-only; accepted name from the `V17` issue snapshot), the stored signed quote PDF (section 15), the post-commit store notification, the protected accepted reads (section 14), the acceptance price write (decision D6b, an explicit amendment to the 16D-A separation, section 1.1), and the cancellation of an active quote link when an invoice is accepted in-app (decision D5(c), section 16). The quote email is now **link-only** (section 12). 16F PR2 added the **backend only** of Create Invoice from an accepted quote (Path A, `POST …/quote/create-invoice`; sections 14 and 16), the frozen quote terms on the invoice and the InvoiceDetail terms fields, and the amended `invoice_eligible` rule (decision D5(b) as amended on 8 October 2026). Still **planned 16F**: **all** 16F frontend (PR3): the public signing UI, the Accepted Quote view and its Create Invoice button, the Invoice tab rendering of the frozen terms, the issued/accepted refresh guards and the send-after-acceptance warning. The frontend is unchanged by PR1 and PR2. Delivery is **email only**; SMS is dormant (section 12).

This document **extends and clarifies** `docs/API-Contracts-Phase16B-Quotation.md`. It does **not** replace it. Phase 16B remains the authoritative contract for the broader quotation lifecycle (quote draft, issued/customer quote, accepted quote, send by email (SMS dormant), public token link, viewed/opened state, customer acceptance/signature, signed quote PDF, create-invoice-from-accepted-quote).

Where this document and Phase 16B differ on a **frontend workflow / visual / product** decision, **this document wins for 16D**. Where they differ on **backend contract/behavior**, the **live code on `main` and Phase 16B win** — see the corrections in §1.

Source-of-truth order: (1) live repo on `main`, (2) `docs/API-Contracts-Phase16B-Quotation.md`, (3) this document, (4) roadmap/handover notes.

---

## 1. Corrections to avoid — read first

These three points exist because earlier drafts described behavior that does not match the live backend. Do not repeat these mistakes.

### 1.1 Quote modularity — described accurately

Current backend reality (Phase 16C–16E, amended by 16F PR1 — see the D6b amendment below):

- Quote lines do **not** mutate the order's product/charge lines. That separation is real and must be preserved.
- `PUT /api/v1/{slug}/orders/{orderId}/quote/draft` saves the quote draft **only**. It does **not** change the order sale-price override or any `sales_order` header financials (decoupled in Phase 16D-A).
- Issuing/sending a quote (16E) does not change the order price or header financials either — and neither do Preview PDF or Cancel quote.
- Therefore quote autosave (and sending) never changes the order/header price or the next invoice — the quote draft is fully independent of order pricing.
- **Amendment, 16F decision D6b (backend built in 16F PR1):** customer **acceptance** is the one deliberate exception to the 16D-A separation: a signed quote is the final price. When the customer signs on the public link, the signed inc-GST total is applied as the order's **standard sale-price override**: `price_adjustment_inc_gst` (the same stored adjustment the Details of Sale override uses) = accepted total inc GST − the calculated line total at acceptance, replacing any earlier override, and the header sale price ex GST, total cost, GP and GP% are recomputed. At that moment Details of Sale's final sale price equals the accepted total and GP recalculates from it. Like any override the adjustment is then fixed, so later Products & Charges edits (only possible while the order is not LAID) shift the working price relative to the signed total; the manual override and reset in Details of Sale remain the correction tools. The accepted quote snapshot itself never changes, and Path A (backend built in 16F PR2) bills that frozen snapshot, never the working order price. The write happens even on a LAID order (acceptance is allowed when LAID: D4); the protected sale-price endpoints keep their LAID gate. Draft saves, Preview PDF, send/resend and cancel still never touch the order price, and quote lines still never touch Products & Charges.

What 16D states:

- The quote draft is **price-independent**. Quote lines are customer-facing and separate from operational product/charge lines; editing/saving a quote never edits Products & Charges and never changes the order sale price.
- The header "Sale total" is driven only by Products & Charges plus the sale-price override — set manually in Details of Sale or, on acceptance, to the accepted quote total (D6b). A quote save or send does **not** move it.
- The quote total **may differ** from the order's working price by design while the quote is a draft or sent. Acceptance aligns them at that moment (D6b), and Path A (backend built in 16F PR2) bills the accepted snapshot, never the live order price.

### 1.2 Quote PDF wording — implemented in Phase 16D-C

Current known state of the quote PDF template (`quote.html`) after Phase 16D-C:

- It uses quote-specific wording: title `QUOTATION`, recipient label `Quotation To`. It does **not** say `TAX INVOICE` or `Invoice To`.
- It renders an acceptance-ready quotation PDF with a display-only 40% deposit line, printable declaration/customer acceptance area, and terms applicable to the quotation.

These were backend template/PDF wording changes completed under the separately scoped Phase 16D-C backend task with its own verify gate and Codex review.

Since 16F PR1 the same template also renders the stored **signed** quote PDF: both declaration squares ticked, the signature image and `Accepted by {name} on {dd/MM/yyyy HH:mm}` (§15). The draft preview and the issued PDF keep the blank acceptance area.

### 1.3 Send Quote — email sending is built (16E)

History: 16D shipped the Send Quote button and its confirmation modal with delivery disabled (no backend send endpoint called). 16E wired email delivery.

Current behaviour (16E-A backend, 16E-B UI):

- `Send by Email` calls `send-email`, which issues a new quote version (or resends the unchanged one), stores the immutable issued PDF, mints the public link, and makes the Customer Quote sub-tab functional (§12, §13). Since 16F PR1 the email carries the public link only — no PDF attachment (§12).
- `Send by Phone/SMS` is shown **disabled** and no SMS call is ever made. SMS is dormant backend support only, outside the approved delivery scope (email only) — see §12.
- In the current build email delivery is **recording-only** (in-memory sender): no real email reaches the customer until the Phase 17 email provider.

---

## 2. Core quotation principle

A quote is a **customer-facing commercial document**. Products & Charges / Details of Sale are the **internal operational sales record**.

- Quote Draft is a customer-facing, editable presentation, initially seeded from the order, that then becomes its own working quote canvas.
- Quote lines **never** mutate order product/charge lines.
- A quote **may** differ from the internal/current order working price by design; saving or sending a quote does not change the order price. Acceptance does: the accepted total becomes the order's sale-price override (decision D6b, backend built in 16F PR1 — see §1.1).
- The accepted quote snapshot (created by the public accept, 16F PR1) is the source for quote-led invoice creation (Path A: backend built in 16F PR2; the Create Invoice button is planned 16F PR3).

Illustrative:

- Order working price $5,000, quote $4,700 with a discount line.
- Order working price $5,000, quote $5,500 sold at higher GP.
- The salesperson edits quote lines / adds adjustment lines to make the quote total equal the desired customer-facing number.

---

## 3. Details of Sale entry workflow

Details of Sale remains the entry point. Keep the existing button:

- before an invoice exists: `Create Invoice`
- after an invoice exists: `Rewrite Invoice`

Clicking this button opens an **action modal** (it no longer creates/rewrites directly).

**If the Quote tab has not been opened yet for this order**, the modal shows:

- `Create Invoice` or `Rewrite Invoice`
- `Create Quote`

**If the Quote tab has already been opened for this order**, the modal shows only:

- `Create Invoice` or `Rewrite Invoice`

Reason: once the Quote tab exists, quote work continues inside it. Showing `Create Quote` again would create confusion about duplicates/overwrites.

**GP warning:** the existing GP warning still appears when applicable, inside this same modal/flow. It must **not** remove or block `Create Quote`. `Create Quote` is available regardless of the GP warning, until the Quote tab exists.

**Invoice path:** choosing `Create Invoice` / `Rewrite Invoice` preserves the existing invoice flow unchanged.

**Create Quote action:** reveals the Quote tab, switches to it, opens the `Quote Draft` internal sub-tab. It does **not** create or rewrite an invoice, and does not wipe order data.

---

## 4. Quote tab visibility

- The Quote tab is **hidden by default**. When shown it sits between Payments and Invoice.
- It appears when the user chooses `Create Quote` from the Details of Sale action modal, or on order load when the backend already has quote state for the order (below).
- Once visible for an order, it **stays visible for that order** for the rest of the workspace session.
- Switching away and back must **not** lose in-progress quote work (keep the tab mounted / preserve its local state across tab switches).

**Visibility source of truth (locked — prevents guessing):**

- **On order load:** the Quote tab is shown **iff the quote-workspace probe (`GET /quote/workspace`) succeeds and returns a non-null `draft` or a non-null `current_issued`** (an active issued quote). This is the durable signal — visibility survives reload because it is derived from real backend state, not a transient flag.
- **Within a session:** once the user clicks `Create Quote`, the tab is shown for the rest of that session even before the first save has persisted a draft.
- **Probe failure is never "no quote":** while the probe is loading or after it fails, the tab stays hidden and `Create Quote` shows as checking / unavailable with a Retry; nothing is cleared or faked, and the Quote tab only mounts after a successful probe (so a fresh full-replace save can never overwrite a draft the frontend has not seen).
- On a LAID order with no saved quote state, `Create Quote` is hidden (a LAID order cannot save a new draft).
- Do **not** invent a separate "quote started" flag or persist visibility in the frontend only. Backend quote state (draft or active issued quote) is the durable source of truth; the session flag only bridges the gap between clicking `Create Quote` and the first successful autosave.

---

## 5. Quote tab internal sub-tabs

The Quote tab has three internal sub-tabs:

1. **Quote Draft** — editable quotation canvas.
2. **Customer Quote** — latest sent/issued quote snapshot, read-only.
3. **Accepted Quote** — latest accepted/signed quote snapshot, read-only.

Current state (after 16F PR2; PR1 and PR2 are backend only, so the frontend is unchanged):

- **Quote Draft** is functional (16D).
- **Customer Quote** is functional (16E-B) — it renders the active issued quote from `current_issued` (§13), and shows the clean empty state `No quote has been sent yet.` when there is no active issued quote (never sent; cancelled, including by an in-app invoice acceptance (D5(c), §16); accepted; or already lazily expired by a public visit after its link lapsed). Known gap until 16F PR3: after an acceptance `current_issued` is null, so this sub-tab shows that empty state although the quote was sent and signed.
- **Accepted Quote** still shows only the clean empty state `No quote has been accepted yet.` Since 16F PR1 the backend returns a non-null `accepted` summary after a remote acceptance (§14); the frontend ignores it until 16F PR3 builds the sub-tab (planned).

These are real lifecycle tabs, not throwaway placeholders.

---

## 6. Quote Draft visual design

Quote Draft should look almost exactly like the **Invoice tab/page**, rendered as a **quotation canvas** — not a generic admin form.

Screen wording direction:

- title `QUOTATION`
- recipient block `Quotation To`
- quote terms wording (terms applicable to this quote)
- no `Accept Invoice` control on Quote Draft

Canvas should include:

- business/store branding area (like the invoice)
- `QUOTATION` title
- `Quotation To` customer block
- order / store / salesperson details (as the invoice shows them)
- Details of Sale section
- a small **itemised toggle** near the top of the canvas
- an itemised lines area **only when itemised is ON**
- a totals section, positioned cleanly (moved lower when itemised lines are visible)
- terms and conditions applicable to this quote
- bottom action buttons: `Preview PDF` and `Send Quote`

There is **no** Save Draft button (see §10, autosave).

---

## 7. Itemised OFF behavior

Default / non-itemised quote is clean and invoice-like:

- no product/charge line breakdown
- no fake `Quoted works` line
- no "single quoted amount for works described above" line
- show the customer block
- show Details of Sale
- show the total
- show quote terms
- show `Preview PDF` / `Send Quote` actions

Non-itemised is a clean customer-facing quotation without a line breakdown.

**Backend mapping (amended Phase 16D-B PR2A):** non-itemised saves still send exactly `itemised: false`, `final_total_inc_gst`, and `lines: []`. The backend **no longer stores a synthetic line** — it updates the draft mode/totals only and **retains** previously saved itemised rows as dormant `quote_draft_line` rows (see §9). A read of a non-itemised draft returns those retained rows in `lines[]`; they must **not** be shown on the screen or the non-itemised PDF, and must not be rendered as editable rows while itemised is OFF.

---

## 8. Itemised ON behavior

When itemised is ON:

- itemised quote lines appear under Details of Sale
- lines are editable directly inside the quotation canvas (wording and amounts)
- totals move lower if needed to make room
- Preview PDF shows the itemised lines

### 8.1 Initial carry-over (seed)

When itemised is first turned ON and there are no existing quote draft lines, seed the quote lines from the order's current **sale-side** product/charge line data.

Use customer-facing sale data only:

- product/charge name → line description
- quantity (use the quantity that matches the product's pricing unit — LM vs SQM — so `quantity × unit price = line total`)
- unit price ex GST
- line total ex GST

Never carry, display, or send cost. Never bind `cost_snapshot`, `price_snapshot`, or any cost field. (See §18.)

After seeding:

- quote lines are independent, quote-only, customer-facing lines
- editing quote lines does **not** edit Products & Charges
- editing quote lines does **not** mutate operational product/charge rows
- if a saved quote draft already has lines, **the saved quote draft lines are the source of truth** — do not silently overwrite them from Products & Charges just because the order changed. Re-seeding only happens on an explicit user rebuild. This includes retained dormant rows on a non-itemised draft: toggling itemised back ON restores them and must not re-seed (§9 — active until Batch A #108 changes it).

### 8.2 Quote draft pricing is independent of the order (locked; acceptance exception D6b)

The quote draft save is decoupled from order pricing (Phase 16D-A, see §1.1): saving a quote never writes `sales_order.price_adjustment_inc_gst` or any header financial. There is therefore **no** override to reconcile or preserve on carry-over — turning itemised ON and saving the seeded lines does not change the order price in any way. The one exception is customer **acceptance** (16F decision D6b, backend built in PR1), which writes the accepted total inc GST as the order's sale-price override (§1.1). It is not a draft save and has no effect on seeding or carry-over.

Seed the quote from the order's current sale-side line data as a **starting point only**. The salesperson then edits the quote freely; the quote total may differ from the order Sale total, and editing, saving or sending it has no effect on the order or the next invoice — of all quote actions, only the customer's acceptance changes the order price (D6b, §1.1).

### 8.3 Itemised math / adjustment helper

The editor is modular and helpful, never silently destructive.

- Do **not** auto-add an adjustment silently on the itemised toggle.
- If the itemised lines do not match the intended/current quote total, show a clear helper prompt.

**Discount case** — itemised lines $5,000, desired quote total $4,700, difference −$300:

- UI: `Itemised lines are $300 above the quote total.`
- Action offered: `Add discount adjustment`
- Adds a visible customer-facing adjustment line: `Discount / price adjustment: -$300`

**Higher-quote case** — itemised lines $5,000, desired quote total $5,500, difference +$500:

- UI must not silently add hidden margin.
- UI: `Quote total is $500 above the itemised lines. Adjust item prices or add an additional works / price adjustment line.`
- The salesperson edits line prices or adds a positive adjustment / additional-works line.

Rules:

- in itemised mode, the quote total must always equal the sum of the visible quote lines
- adjustment lines are visible customer-facing lines — no hidden math
- the salesperson should always understand why totals differ

---

## 9. Itemised line retention (locked — Phase 16D-B PR2A)

Edited itemised quote rows are permanent working data. They must survive switching the quote to non-itemised mode, page reloads, and returning days later.

1. **First itemised ON with no persisted itemised rows:** the frontend seeds the quote lines from Products & Charges (§8.1). This is the only seeding trigger.
2. **Edited itemised rows persist permanently:** they survive toggle-OFF, page reload, and coming back days later. The backend keeps them as dormant `quote_draft_line` rows while the draft is non-itemised — a non-itemised save never writes and never deletes lines.
3. **Toggle OFF:** the frontend saves non-itemised mode with the current itemised total as `final_total_inc_gst` (request shape unchanged: `itemised: false`, `final_total_inc_gst`, `lines: []`).
4. **Toggle ON later:** the frontend restores the persisted `quote_draft_line` rows returned by the backend. It must **not** re-seed from Products & Charges when persisted itemised rows exist.
5. **No warnings. No confirmation modals.** Toggling between modes is silent and non-destructive.

While non-itemised, the quote total is `final_total_inc_gst` and is fully independent of the retained rows (never validated against them; below-cost/GP use the final-total-derived ex total). In itemised mode the total must equal the sum of the visible lines as before (§8.3).

**Legacy note (dev data only):** drafts saved non-itemised before PR2A carry an old stored `Quoted works` row that is indistinguishable from retained rows (no schema flag; description matching is forbidden). It may appear in `lines[]` on read (never rendered in non-itemised mode) and, if the draft is toggled to itemised, in the editor; it heals on the next itemised save (full replace) or manual dev-data cleanup.

> **Planned change — Batch A, [#108](https://github.com/MuneebHash/flooring-sales-portal/issues/108) (approved, NOT implemented).** The explicit switch of a **draft** from non-itemised to itemised will refill the lines from the current Products & Charges and discard manual quote rows/adjustments (draft only; not continuous auto-sync; issued/accepted snapshots and converted invoices untouched). Whether a confirmation is shown first is still an open decision. Until the #108 PR merges, the retention rules above are the **active** behaviour; that PR amends this section, contract §6.1 and `openapi.yaml` together with its code.

---

## 10. Autosave

There is **no** visible Save Draft button. Quote Draft autosaves, behaving conceptually like the Details of Sale autosave.

Requirements:

- throttled/debounced (not per keystroke), with blur-flush where appropriate
- single-flight save queue; collapse to the latest pending draft while a save is in flight
- full-body PUT only; never send a partial or not-yet-loaded draft
- baseline local state only on a successful save; keep local edits on error; ignore stale responses
- no autosave when the order is LAID/locked
- visible autosave status: `Unsaved changes` / `Saving…` / `Saved` / `Could not save`
- never expose or send cost

**Important documented behavior:** the quote draft save is decoupled from order pricing (§1.1), so autosave does **not** change the order/header "Sale total" and does **not** require refreshing the order financial summary. Debounce should still be generous (e.g. ~800ms–1s) to avoid excessive PUTs. The frontend must not assume a quote save updates order financials.

The D6b amendment (§1.1) does not change this: autosave stays price-independent. Only a remote **acceptance** writes the order price, and it happens outside the editing session (on the customer's device). PR1 adds no frontend refresh, so an already-open workspace shows the accepted price only after it next reads the order; the issued/accepted refresh for the Quote tab is planned 16F PR3.

---

## 11. Preview PDF

Bottom button `Preview PDF`. Preview must always reflect the latest quote state (the backend renders the **persisted** draft).

Flow:

1. user clicks `Preview PDF`
2. flush any pending autosave first
3. if the save/flush succeeds, open the latest PDF in a new browser tab
4. if the save/flush fails, do **not** preview a stale PDF — show the validation/save error
5. preview opens in a **new tab** (not primarily a download)

Popup-safe behavior:

- open a blank tab synchronously in the click handler
- flush autosave, then fetch the PDF blob
- set the blank tab's location to the blob URL; revoke the URL on cleanup
- if the popup is blocked, show a clear error and offer a download fallback
- if the save/flush failed, close the blank tab so the user is not left with an empty tab

---

## 12. Send Quote

Bottom button `Send Quote`, visible because it is core to the quote workflow.

Current behavior (built in 16E-B; 16D shipped the same modal with delivery disabled):

- clicking it opens a **confirmation modal only** — it never sends immediately. The Customer Quote `Resend` button opens the same modal.
- modal title: `Are you sure you want to send this quote?`
- modal body: `Please double-check all quote details before sending.`
- actions: `Send by Email`, `Send by Phone/SMS` (disabled), `Cancel`
- `Send by Email` first flushes the Details of Sale autosave and then the quote autosave (the backend issues from **persisted** state); if either flush fails the send is blocked with an inline error. It is single-flight (no duplicate send on a double tap), mutually exclusive with Cancel quote, and disabled when the order is LAID.
- on success the modal closes, the confirmation `Quote sent by email.` shows, and Customer Quote renders the returned issued summary.
- errors show inside the modal: a missing/invalid customer email adds a pointer and a `Go to Customer tab` button; a delivery failure (502) says nothing was sent and it is safe to try again (the issued version/PDF/link were kept); other backend messages show verbatim.
- delivery is **recording-only** in the current build — the send is recorded in memory and no real email reaches the customer (Phase 17 adds the provider).
- the quote email is **link-only** (16F PR1): a short body with the public quote link and **no PDF attachment** (16E-A attached the issued PDF). The immutable issued PDF is still generated and stored on issue; the customer opens it from the public page (its `PDF` button) and the salesperson from Customer Quote `Preview PDF`.
- **sending after an acceptance (decision D9):** the backend allows it — with no active issued version a send always issues a **new** quote version (with a new link) that needs a new signature, and the accepted version is never changed. **Planned (16F PR3):** when an accepted quote exists, the modal first shows the accepted state and warns that sending again creates a new version needing a new signature. Until PR3 the modal shows no such warning.
- `Send by Phone/SMS` is disabled and never calls the backend. SMS is dormant backend support, **not** approved customer delivery (email only), and no activation is planned. The button's current "Available soon" wording overstates this — a known follow-up listed in `docs/Phases.md` §8 (no issue filed yet); this document does not change the UI.

Hard rule: **no accidental one-click sending** — sending always passes through this confirmation modal.

---

## 13. Customer Quote — built (16E-B)

Read-only. Shows the active issued quote from the workspace `current_issued` summary (seeded by the probe, replaced by send responses, cleared by a successful cancel) — **never** from the live draft rows, totals, details or terms, which may have drifted since the issue was frozen. Never shows raw cost, GP, token or internal file data. Since 16F PR1 the backend also returns `current_issued` as null once the quote is accepted, or once an in-app invoice acceptance cancels it (D5(c)); until the PR3 refresh lands, an open Quote tab learns of either only when the order is reloaded.

- **Status badge:** `Sent`; `Opened` once the customer has opened the public link (`viewed_at` set — 16E-C, shown from the next workspace load); `Not delivered` when the latest email attempt failed (with "The email could not be delivered. Resend to try again."). Keep the status model simple — no further states for MVP.
- **Details:** channel, sent time, link expiry.
- **Actions:** `Preview PDF` (opens the **stored** issued PDF, never regenerated), `Resend` (through the §12 confirmation modal; disabled when LAID), `Cancel quote` (its own confirmation — "Cancel this quote?" / "The customer's quote link will stop working. You can send a new quote at any time."; allowed when LAID because it only kills the public link). Cancel on an order whose quote was already accepted (no active issued version) is rejected with 409 `QUOTE_ALREADY_ACCEPTED`, shown verbatim (reachable since 16F PR1).
- **PDF action (settled 16F decision):** `Preview PDF` only — **no separate download button**; the browser downloads from the preview. This is the built behaviour, and the Accepted Quote view follows the same rule (§14).
- **Document:** a read-only "Issued quote" view of the frozen version (version number, details of sale, the snapshot lines for an itemised issue, and the ex-GST / inc-GST totals). The full document, including terms, is the stored issued PDF.

The customer-facing side is the public read-only quote page `/q/{token}` (16E-C): it renders the issued snapshot (customer identity from the `V17` snapshot; business presentation fields read live), records the first view, offers the stored issued PDF, and shows a per-state message for expired/replaced/cancelled/inactive links. Its customer-acceptance area is still static document content. The backend accept (`POST /api/v1/public/quotes/{token}/accept` — the signature image only; the accepted name comes from the `V17` issue snapshot, never typed) exists since 16F PR1, but the **signing UI is planned 16F PR3**: a signature pad (mouse or finger), the two declarations (a frontend gate only — D8; the signed PDF renders them ticked) and `Accept`, placed after the terms. The page keeps its `PDF` button (the stored issued PDF) — the customer's way to get the PDF now that the email is link-only. Once signed the link is dead: the page shows the inactive message (token `CONSUMED`) and the signed PDF is never served publicly.

---

## 14. Accepted Quote: backend data (16F PR1) and Create Invoice backend (16F PR2) built; visual planned (16F PR3)

**Backend (built, 16F PR1; `invoice_eligible` amended in 16F PR2):** the quote workspace returns the order's **latest** accepted version as `accepted` (null when none), selected independently of the draft and of `current_issued`: the frozen ex/inc-GST totals, itemised flag, flooring type, details of sale, the snapshot lines (always empty for a non-itemised quote), the accepted customer name (`V17` issue snapshot, D1) and time, signature presence plus `signature_download_path`, `signed_pdf_available`, and `invoice_eligible`. `invoice_eligible` is true when the order has no invoice, when its current invoice is unsigned, or when this quote was signed strictly later than the current invoice (decision D5(b) as amended on 8 October 2026: the newer signature wins); it is false when the current invoice was signed at the same time or later than this quote. It reflects that signature rule only (never LAID, a draft or newer issued quote, missing invoice details, a zero total or overpayment). No cost, GP, token, storage path or file id. The stored signed PDF streams from `GET …/quote/pdf?type=accepted` and the signature image from `GET …/quote/accepted/signature` (both protected, LAID allowed). The stored signature is the server-normalised re-encoding of the customer's PNG (same dimensions and pixels; ancillary chunks and trailing bytes dropped; contract section 7.2), so that download returns the normalised image, not the original upload bytes.

**Create Invoice backend (built, 16F PR2):** `POST …/quote/create-invoice` (contract section 7.1) creates an invoice from the latest accepted quote. It creates version 1 when the order has no invoice, and otherwise appends a new invoice version when the current invoice is unsigned or this quote's signature is strictly newer (equal or older: 409, a newer signed quote is required; nothing is appended and the earlier signed invoice stays in history). The invoice takes the quote's frozen details of sale, ex/inc totals and terms (frozen "no terms" stays no terms) and inherits its signature, accepted name and accepted time (the same stored signature file), while Invoice To stays the live saved customer and billing address. Active payments are carried (payments above the accepted total: 422, void the excess first). The retained invoice checks still apply (customer first and last name, installation and billing addresses, proposed lay date and lay date status), plus a non-blank frozen details of sale and totals above zero; there is no customer-email check and no email is sent. It is allowed on a LAID order, and it never changes the quote draft, an issued quote or its link, the order price or the Products & Charges lines.

**Frontend (planned 16F PR3):** today the sub-tab still shows only its empty state (§5). It will use the same quotation canvas style, read-only and signed, rendered only from `accepted` (never the live draft or order). Shows:

- the same customer-facing quote content
- signature section (image from `accepted.signature_download_path`, consumed verbatim)
- accepted customer name (the issue-time `V17` snapshot; never typed)
- accepted date/time
- `Create Invoice` button (from the accepted quote; backend Path A built in 16F PR2, button planned 16F PR3), available when `invoice_eligible` is true. An unsigned draft or a newer issued quote never disables it, and LAID does not either. When `invoice_eligible` is false the current invoice was signed at the same time or later than this quote, so a newer signed quote is needed (PR3 settles the exact wording).
- `Preview signed PDF` when `signed_pdf_available` — opens the stored signed PDF in a new tab; **no separate download button** (the browser downloads from the preview). Same rule as Customer Quote (§13).

Create Invoice from the accepted quote uses the **accepted quote snapshot** (backend built in 16F PR2; button planned 16F PR3). After an invoice is created from an accepted quote, the normal Details of Sale button becomes `Rewrite Invoice` (an invoice now exists), and the salesperson can still use the direct Details-of-Sale invoice/rewrite flow as normal (a manual rewrite returns to the live order content and terms and clears the signature; because the current invoice is then unsigned, the accepted quote can be converted again).

Accepted Quote shows the **latest** accepted quote only for MVP; older accepted-quote history stays out of the UI unless explicitly scoped later.

`Rewrite Quote` is **out of scope** for 16F (decision D10): cancel + new quote is the model. After an acceptance the salesperson edits the draft and sends again, which issues a new version that needs a new signature (D9, §12); the accepted version is never changed.

---

## 15. Quote PDF — Phase 16D-C implemented visual direction (+ 16F PR1 signed PDF)

The quote PDF is backend (`quote.html`). Phase 16D-C completed the separately scoped backend quote PDF update; 16F PR1 added the signed variant (below).

Implemented:

- title `QUOTATION`
- `Quotation To`
- terms wording reads as terms applicable to this quotation
- printable customer acceptance/signature area is present on the quotation PDF
- display-only 40% deposit line is present on both itemised and non-itemised quote PDFs
- non-itemised PDF: no fake `Quoted works` / "single quoted amount for works described above" line — clean Details of Sale + total + quote terms
- itemised PDF: show the line table **only when itemised is ON**, with clean columns: description / quantity / unit price / amount
- adjustment/discount lines shown visibly if present; total matches the visible lines
- never show raw cost

Built in 16F PR1 (backend):

- the **signed quote PDF**, generated and stored immutably at acceptance from the accepted version's frozen snapshot — the itemised flag, snapshot lines (line table only when itemised), totals, details of sale, frozen terms (no terms page when none were frozen) and the `V17` "Quotation To" identity; never the live draft, live customer/address or live terms. Business presentation (name, logo, ABN, bank, store contact, salesperson) is read live at acceptance, as for the issued PDF. Same `QUOTATION` template and 40% deposit line, with the acceptance area filled: both declaration squares **ticked**, the stored **signature image** (the server-normalised PNG — contract §7.2), and the caption `Accepted by {name} on {dd/MM/yyyy HH:mm}` (the accepted name and time). It is portal-only (`GET …/quote/pdf?type=accepted`, file `quote-{order}-v{n}-signed.pdf`) and never served on the public link. The draft preview and the issued PDF are unchanged (blank acceptance area).
- the accepted lifecycle: version `ACCEPTED` (its snapshot total is the legal billing number), token `CONSUMED` (the public link dies).

Still planned 16F: the public signing UI and the Accepted Quote `Preview signed PDF` (PR3).

---

## 16. Invoice vs quote relationship

Quote and invoice are separate lifecycle objects.

- Direct invoice path: Details of Sale → `Create Invoice` / `Rewrite Invoice`.
- Quote path: Details of Sale → `Create Quote` → Quote Draft → Send Quote → Customer Quote → customer acceptance → Accepted Quote → Create Invoice from Accepted Quote (customer acceptance: backend built in 16F PR1, signing UI planned PR3; Accepted Quote view planned PR3; Create Invoice from Accepted Quote: backend built in PR2, UI planned PR3).

A quote is not mandatory for invoicing — an invoice can still be created directly from Details of Sale. A quote can be created before or after an invoice, as long as the Quote tab has not already been opened for that order. Once a quote is accepted and an invoice is created from it, the Details of Sale button reads `Rewrite Invoice`.

Since 16F PR1:

- Accepting the current invoice in-app (signing on the iPad) cancels any active issued quote and its link in the same transaction (decision D5(c)), so that quote can no longer be signed remotely (its link shows the cancelled message). With no active issued quote it does nothing.
- An accepted quote never blocks in-app invoice signing and is never revoked by it (D5(d)) — one direction only. If the invoice is signed in-app, the accepted quote simply is not converted.
- A remote acceptance sets the order's sale-price override to the accepted total (D6b, §1.1). A direct Create/Rewrite Invoice from Details of Sale still uses the order's working price, which therefore equals the accepted total unless Products & Charges or the override changed after the acceptance.
- **A quote signed after the in-app invoice signature (decision D5(b) as amended on 8 October 2026):** D5(c) cancels only the quote link that is active when the invoice is signed in-app. A quote sent after that can still be signed remotely (there is no send or signing guard), and its D6b write changes the working order price. Because its signature is strictly newer than the invoice's, Create Invoice from that accepted quote is allowed: it appends a new invoice version, and the earlier signed invoice stays in history (contract section 13).

Built in 16F PR2 (Path A backend; the button is planned PR3): Create Invoice from the accepted quote creates version 1 when there is no invoice. Otherwise it appends a new invoice version (version mechanics, active payments carried: D5(a)) when the current invoice is unsigned or the quote was signed strictly later than the current invoice (D5(b) as amended: the newer signature wins, and the earlier signed invoice version stays in history). An equal or older quote signature is refused (409). It is allowed on a LAID order in every permitted case (D4; the manual Rewrite LAID block does not apply). It never cancels or changes an issued quote or its link (only an in-app invoice acceptance does, D5(c)), and it sends no email.

---

## 17. Phase split (16D / 16E / 16F)

**16D — frontend quote UX foundation (COMPLETE):**

- Details of Sale action modal entry
- hidden Quote tab until `Create Quote`
- Quote Draft canvas (invoice-style)
- itemised ON/OFF behavior (incl. carry-over + adjustment helper)
- autosave (quote-only; does not change order pricing)
- Preview PDF that flushes autosave first, opens in a new tab
- visible Send Quote button with a **disabled** confirmation modal (no real send) — superseded by 16E-B
- Customer Quote / Accepted Quote clean empty states

**16E — quote delivery (COMPLETE — email only):**

- 16E-A: `send-email` endpoint (plus dormant `send-sms` — not used, outside the approved scope); issue a quote version; store the issued PDF; mint token/link; cancel
- 16E-B: Send by Email wired through the confirmation modal; Customer Quote tab functional (Sent / Opened / Not delivered, stored issued PDF preview, Resend, Cancel quote)
- 16E-C: public read-only quote page `/q/{token}`; public viewed tracking (drives `Opened`); issue-time customer snapshots (`V17`); reserved slug `q` (`V18`)

**16F — remote acceptance + invoice conversion (IN PROGRESS — three PRs):**

- **PR1 — backend acceptance (BUILT):** public `POST /public/quotes/{token}/accept` (signature-only multipart; accepted name from the `V17` snapshot — D1; allowed when LAID — D4; public-safe 422 wording — D11); signed quote PDF stored; version `ACCEPTED`, token `CONSUMED`; post-commit store notification email (the order's store email, skipped if blank, no attachment, recording-only); accepted total → order sale-price override (D6b); in-app invoice acceptance cancels the active issued quote (D5(c)); protected reads (`workspace.accepted`, `pdf?type=accepted`, `GET …/quote/accepted/signature`); quote email link-only; migration `V19`.
- **PR2, backend Create Invoice from Accepted Quote, Path A (BUILT):** `POST …/quote/create-invoice`; inherits the signature (no re-sign; the same stored signature file); invoice fields from the accepted snapshot only; frozen quote terms on the invoice and InvoiceDetail `terms_html` / `terms_source` (D6/D7); the strict newer-signature rule and the matching `invoice_eligible` (D5(b) as amended); allowed when LAID; no email; no migration (uses the `V19` columns).
- **PR3 — frontend (PLANNED):** public signing UI; Accepted Quote tab functional (`Preview signed PDF` only); Create Invoice button; issued/accepted refresh with stale-response guards; send-after-acceptance warning (D9).
- Out of scope: `Rewrite Quote` (D10).

---

## 18. Cost discipline

The quote UI, quote PDF, and public/customer quote surface must **never** expose raw cost.

Forbidden (display, bind, or send): `cost`, `cost_snapshot`, `costSnapshot`, `unitCost`, `lineCost`, `totalCost`, `total_cost`, and any internal margin/cost field. Any key containing `cost` or ending `_snapshot` is rejected by the backend allow-list as a backstop, but the frontend must never bind or send them in the first place.

Allowed in the protected salesperson portal only: `gp_percent`, the below-cost warning, and quote totals. The public/customer quote surface is entirely cost-free.

The public accept (16F PR1) never shows cost or GP figures: a below-cost quote, a missing name snapshot and a price that cannot be stored all return the same customer-facing message `This quote can no longer be accepted online. Please contact the store.` (decision D11). The raw 422 `error.code` (`QUOTE_BELOW_COST` / `ACCEPTED_CUSTOMER_NAME_REQUIRED` / `BUSINESS_RULE_VIOLATION`) still names the cause in the response body — accepted under D11 ("code stays QUOTE_BELOW_COST"); the PR3 page should show only the message. The store notification carries no cost or GP either.

---

## 19. Purpose of this document

This is the visual/workflow source of truth for Phase 16D onward. It exists so implementation agents do not guess: where the Quote tab appears and when it persists, what `Create Quote` means, how Quote Draft looks, how itemised carry-over and adjustment math work, how autosave behaves (quote-only; it does not change order pricing — only the customer's acceptance does, D6b), what Customer Quote and Accepted Quote mean, how the quote PDF looks, and what belongs to 16D vs 16E vs 16F.
