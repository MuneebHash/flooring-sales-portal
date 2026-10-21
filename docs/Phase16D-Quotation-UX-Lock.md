# Phase 16D — Quotation UX / Product Lock

Status: **locked product + UX contract for Phase 16D onward.** Docs-only. No code is implemented by this document.

Implementation status (16F COMPLETE: #117, #118 and #119 merged): the 16D and 16E behaviour described here is **built**: Quote Draft (16D), Send by Email + Customer Quote (16E-B), and the public read-only quote page with viewed tracking (16E-C). 16F PR1 added the **backend only** of remote acceptance: the public accept endpoint (signature-only; accepted name from the `V17` issue snapshot), the stored signed quote PDF (section 15), the post-commit store notification, the protected accepted reads (section 14), the acceptance price write (decision D6b, an explicit amendment to the 16D-A separation, section 1.1), and the cancellation of an active quote link when an invoice is accepted in-app (decision D5(c), section 16). The quote email is now **link-only** (section 12). 16F PR2 added the **backend only** of Create Invoice from an accepted quote (Path A, `POST …/quote/create-invoice`; sections 14 and 16), the frozen quote terms on the invoice and the InvoiceDetail terms fields, and the amended `invoice_eligible` rule (decision D5(b) as amended on 8 October 2026). The frontend is unchanged by PR1 and PR2. 16F PR3 built **all** of the 16F frontend: the public signing UI (section 13), the Accepted Quote view with `Preview signed PDF` and its Create Invoice button (section 14), the Invoice tab rendering of the InvoiceDetail terms and their source (section 16), the issued/accepted refresh guards (section 13) and the send-after-acceptance warning (section 12). The full send, sign and Create Invoice smoke test passed on the real stack on 9 October 2026 (run by Muneeb, as reported). The post-16F docs reconciliation is done (#137), as are the 16G read-only verify gate and Muneeb's 16G decisions. **16G PR 1** amended this document as the target UX for 16G PRs 2 to 6 (sections 10, 13, 14, 16 and 17, and the new section 20 for the Invoice tab and the public invoice page); nothing marked with a 16G PR is built until that PR merges. Delivery is **email only**; SMS is dormant (section 12).

This document **extends and clarifies** `docs/API-Contracts-Phase16B-Quotation.md`. It does **not** replace it. Phase 16B remains the authoritative contract for the broader quotation lifecycle (quote draft, issued/customer quote, accepted quote, send by email (SMS dormant), public token link, viewed/opened state, customer acceptance/signature, signed quote PDF, create-invoice-from-accepted-quote).

Where this document and Phase 16B differ on a **frontend workflow / visual / product** decision, **this document wins for 16D**. Where they differ on **backend contract/behavior**, the **live code on `main` and Phase 16B win** — see the corrections in §1.

Source-of-truth order: (1) live repo on `main`, (2) `docs/API-Contracts-Phase16B-Quotation.md`, (3) this document, (4) roadmap/handover notes. For the 16G rules (invoice link, remote invoice signing, frozen invoice words, version preconditions, #120 to #126), [`docs/API-Contracts-Phase16G-Invoice-Link.md`](API-Contracts-Phase16G-Invoice-Link.md) is the backend contract. This document holds their UX, including the Invoice tab and public invoice page UX in section 20, which goes beyond quotation.

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
- The accepted quote snapshot (created by the public accept, 16F PR1) is the source for quote-led invoice creation (Path A: backend built in 16F PR2; the Create Invoice button built in 16F PR3).

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

- **On order load:** the Quote tab is shown **iff the quote-workspace probe (`GET /quote/workspace`) succeeds and returns a non-null `draft`, `current_issued` or `accepted`** (a saved draft, an active issued quote or an accepted quote). This is the durable signal — visibility survives reload because it is derived from real backend state, not a transient flag.
- **Within a session:** once the user clicks `Create Quote`, the tab is shown for the rest of that session even before the first save has persisted a draft.
- **Probe failure is never "no quote":** while the probe is loading or after it fails, the tab stays hidden and `Create Quote` shows as checking / unavailable with a Retry; nothing is cleared or faked, and the Quote tab only mounts after a successful probe (so a fresh full-replace save can never overwrite a draft the frontend has not seen).
- On a LAID order with no saved quote state, `Create Quote` is hidden (a LAID order cannot save a new draft).
- Do **not** invent a separate "quote started" flag or persist visibility in the frontend only. Backend quote state (draft, active issued quote or accepted quote) is the durable source of truth; the session flag only bridges the gap between clicking `Create Quote` and the first successful autosave.

---

## 5. Quote tab internal sub-tabs

The Quote tab has three internal sub-tabs:

1. **Quote Draft** — editable quotation canvas.
2. **Customer Quote** — latest sent/issued quote snapshot, read-only.
3. **Accepted Quote** — latest accepted/signed quote snapshot, read-only.

Current state (after 16F PR3):

- **Quote Draft** is functional (16D).
- **Customer Quote** is functional (16E-B; accepted-aware since 16F PR3). It renders the active issued quote from `current_issued` (section 13). With no active issued quote it shows the accepted state when a quote has been accepted (the accepted version, name and time, and a `View accepted quote` control that opens Accepted Quote); otherwise it shows the clean empty state `No quote has been sent yet.` (never sent; cancelled, including by an in-app invoice acceptance (D5(c), section 16); or already lazily expired by a public visit after its link lapsed).
- **Accepted Quote** is functional (16F PR3). It renders the latest accepted quote from `workspace.accepted` (section 14), and shows the clean empty state `No quote has been accepted yet.` when there is none.

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

The D6b amendment (§1.1) does not change this: autosave stays price-independent. Only a remote **acceptance** writes the order price, and it happens outside the editing session (on the customer's device). Today (16F) an already-open workspace shows the accepted price only after it next reads the order, and the Quote tab's issued/accepted refresh (built in 16F PR3, section 13) re-reads only the issued and accepted quote state, never the draft or the order price.

**16G PR 6 (#121):** the workspace re-reads the order financial summary, through the existing guarded reader, when a quote refresh (any trigger, including the section 13 poll) installs a new accepted version, and whenever Details of Sale is opened. In Details of Sale, **Update Sale Price is disabled until the sale-price input has been edited**, so an unedited, stale price can never be re-submitted over the price an acceptance wrote. Quote draft autosave still never refreshes or writes the order price.

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
- **sending after an acceptance (decision D9):** the backend allows it — with no active issued version a send always issues a **new** quote version (with a new link) that needs a new signature, and the accepted version is never changed. **Built (16F PR3):** before the modal opens the Quote tab re-reads the issued and accepted state, and when an accepted quote exists the modal first shows the accepted state (version, accepted name and time) and warns `Sending again creates a new quote version that needs a new signature.` If that read fails the modal does not open; a notice explains it and offers a retry. Nothing is ever sent without the deliberate `Send by Email` click.
- `Send by Phone/SMS` is disabled and never calls the backend. SMS is dormant backend support, **not** approved customer delivery (email only), and no activation is planned. The button's current "Available soon" wording overstates this — a known follow-up listed in `docs/Phases.md` §8 ([#133](https://github.com/MuneebHash/flooring-sales-portal/issues/133), Phase 18); this document does not change the UI.

Hard rule: **no accidental one-click sending** — sending always passes through this confirmation modal.

---

## 13. Customer Quote — built (16E-B)

Read-only. Shows the active issued quote from the workspace `current_issued` summary (seeded by the probe, replaced by send responses and by the refreshes below, cleared by a successful cancel) — **never** from the live draft rows, totals, details or terms, which may have drifted since the issue was frozen. Never shows raw cost, GP, token or internal file data. Since 16F PR1 the backend also returns `current_issued` as null once the quote is accepted, or once an in-app invoice acceptance cancels it (D5(c)). Since 16F PR3 the Quote tab re-reads the issued and accepted state together from one workspace response (never the draft) when the Quote tab becomes active, on a sub-tab change and before the Send confirmation opens, so either change shows without a reload. An older response never overwrites a newer one or a send, cancel or Create Invoice result, pending reads are discarded on unmount or order change, and a failed read keeps the last known state with a retry. With no active issued quote but an accepted one, Customer Quote shows the accepted state and a control that opens Accepted Quote instead of the never-sent empty state.

**Polling (16G PR 6, #120):** while `current_issued` is non-null (a quote link waits for a signature), the Quote tab also re-reads the issued and accepted state every 15 seconds through `refreshSnapshots({ reportError: false })`, only while the browser tab is visible and the Quote tab is the active workspace tab. It keeps every guard above. A failed poll changes nothing and shows no banner, and a poll never re-seeds draft rows, totals or the autosave baseline. A poll that installs a new accepted version also triggers the order financial refresh (section 10).

- **Status badge:** `Sent`; `Opened` once the customer has opened the public link (`viewed_at` set — 16E-C, shown from the next workspace load, which from 16G PR 6 includes the next poll); `Not delivered` when the latest email attempt failed (with "The email could not be delivered. Resend to try again."). Today the tab derives Not delivered by comparing the returned `last_emailed_at` with `last_sent_at`. **From 16G PR 6** it reads the server's `current_issued.last_email_delivered` instead (Not delivered when the channel is email and it is `false`; contract §13.5) and no longer compares timestamp strings, which become whole seconds in the same PR. Not delivered still applies to the email channel only; the dormant SMS channel never shows it. Keep the status model simple — no further states for MVP.
- **Details:** channel, sent time, link expiry.
- **Actions:** `Preview PDF` (opens the **stored** issued PDF, never regenerated), `Resend` (through the §12 confirmation modal; disabled when LAID), `Cancel quote` (its own confirmation — "Cancel this quote?" / "The customer's quote link will stop working. You can send a new quote at any time."; allowed when LAID because it only kills the public link). Cancel on an order whose quote was already accepted (no active issued version) is rejected with 409 `QUOTE_ALREADY_ACCEPTED`, shown verbatim (reachable since 16F PR1).
- **PDF action (settled 16F decision):** `Preview PDF` only — **no separate download button**; the browser downloads from the preview. This is the built behaviour, and the Accepted Quote view follows the same rule (§14).
- **Document:** a read-only "Issued quote" view of the frozen version (version number, details of sale, the snapshot lines for an itemised issue, and the ex-GST / inc-GST totals). The full document, including terms, is the stored issued PDF.

The customer-facing side is the public quote page `/q/{token}` (16E-C; signing built in 16F PR3): it renders the issued snapshot (customer identity from the `V17` snapshot; business presentation fields read live), records the first view, offers the stored issued PDF, and today shows one generic dead-link message for expired, replaced, cancelled and inactive links. **From 16G PR 5 (#126)** it shows the specific reason everywhere: a dead GET shows `data.message` (the per-state text), a 404 shows "Quote not found.", and a PDF 410 or 404 shows that response's message. Only a load with no readable answer keeps the generic sentence. The per-state 410 texts are unchanged (contract §7.2). The backend accept (`POST /api/v1/public/quotes/{token}/accept`, the signature image only; the accepted name comes from the `V17` issue snapshot, never typed) exists since 16F PR1, and the **signing UI is built (16F PR3)**: the page uses the Invoice tab's document layout with quotation wording, and after the terms come the two declarations (a frontend gate only, D8; the signed PDF renders them ticked), a signature pad (mouse, finger or pen) and `Accept Quote`, which posts the signature image only (exported within the safe-decode bounds) and is single-flight. A successful accept shows a local `Quote accepted.` confirmation for that session and removes the `PDF` and signing actions (public PDF access ends once signed); 410 and 422 messages show verbatim; an answer that cannot be read is reconciled by one re-read of the link before any retry, and the page never re-posts on its own. While the link is active the page keeps its `PDF` button (the stored issued PDF), the customer's way to get the PDF now that the email is link-only. Once signed the link is dead: a fresh load shows the dead-link message (token `CONSUMED`; today the generic sentence, from 16G PR 5 the per-state "This quote link is no longer active. Please contact the store."), and the signed PDF is never served publicly.

---

## 14. Accepted Quote: backend data (16F PR1), Create Invoice backend (16F PR2) and frontend (16F PR3) built

**Backend (built, 16F PR1; `invoice_eligible` amended in 16F PR2):** the quote workspace returns the order's **latest** accepted version as `accepted` (null when none), selected independently of the draft and of `current_issued`: the frozen ex/inc-GST totals, itemised flag, flooring type, details of sale, the snapshot lines (always empty for a non-itemised quote), the accepted customer name (`V17` issue snapshot, D1) and time, signature presence plus `signature_download_path`, `signed_pdf_available`, and `invoice_eligible`. `invoice_eligible` is true when the order has no invoice, when its current invoice is unsigned, or when this quote was signed strictly later than the current invoice (decision D5(b) as amended on 8 October 2026: the newer signature wins); it is false when the current invoice was signed at the same time or later than this quote. It reflects that signature rule only (never LAID, a draft or newer issued quote, missing invoice details, a zero total or overpayment). No cost, GP, token, storage path or file id. The stored signed PDF streams from `GET …/quote/pdf?type=accepted` and the signature image from `GET …/quote/accepted/signature` (both protected, LAID allowed). **From 16G PR 6 (#122)** both reads are version-addressed, `GET …/quote/accepted/{quoteVersionId}/pdf` and `GET …/quote/accepted/{quoteVersionId}/signature`, for the version on screen: they check that the version belongs to the order and is `ACCEPTED` (404 on a miss) and replace the latest-at-request-time reads (contract §7.1). From PR 6 the summary also carries `invoice_created_from_this_version` (#124, below). The stored signature is the server-normalised re-encoding of the customer's PNG (same dimensions and pixels; ancillary chunks and trailing bytes dropped; contract section 7.2), so that download returns the normalised image, not the original upload bytes.

**Create Invoice backend (built, 16F PR2):** `POST …/quote/create-invoice` (contract section 7.1) creates an invoice from the latest accepted quote. It creates version 1 when the order has no invoice, and otherwise appends a new invoice version when the current invoice is unsigned or this quote's signature is strictly newer (equal or older: 409, a newer signed quote is required; nothing is appended and the earlier signed invoice stays in history). The invoice takes the quote's frozen details of sale, ex/inc totals and terms (frozen "no terms" stays no terms) and inherits its signature, accepted name and accepted time (the same stored signature file). Invoice To is today the live saved customer and billing address; **from 16G PR 2** it is the accepted version's frozen `V17` "Quotation To" name and billing lines, stored on the invoice. Active payments are carried (payments above the accepted total: 422, void the excess first). The retained invoice checks still apply (customer first and last name, installation and billing addresses, proposed lay date and lay date status), plus a non-blank frozen details of sale and totals above zero. There is no customer-email check. Today no email is sent; **from 16G PR 3** the new signed invoice PDF is emailed to the saved customer email after commit, non-fatally: a delivery failure, or a missing or invalid saved email (skipped with a WARN), leaves the invoice created and Re-send Invoice is the retry route (contract §7.1). It is allowed on a LAID order, and it never changes the quote draft, an issued quote or its link, the order price or the Products & Charges lines. Since 16G PR 4 it marks an `ACTIVE` invoice link `SIGNED`.

**Frontend (built, 16F PR3):** the sub-tab uses the quotation canvas style, read-only and signed, rendered only from `accepted`: never the live draft, the live order customer or address, or live tenant terms (the accepted summary carries no frozen address or terms; the full frozen document, terms included, is the signed PDF). Shows:

- the frozen quote content: details of sale, the ex/inc-GST totals, and the snapshot lines only for an itemised quote
- signature section (image from `accepted.signature_download_path`, consumed verbatim; from 16G PR 6 that path addresses the displayed version, and a response for a version no longer displayed is discarded)
- accepted customer name (the issue-time `V17` snapshot; never typed)
- accepted date/time
- `Create Invoice` button (from the accepted quote; backend Path A built in 16F PR2, button built in 16F PR3), available when `invoice_eligible` is true. An unsigned draft or a newer issued quote never disables it, and LAID does not either. When `invoice_eligible` is false the button is disabled with an explanation. Today it is read from the current invoice: `An invoice has already been created from this quote.` when that invoice carries this quote's signature time (a 19-character timestamp comparison), otherwise (also when the invoice cannot be read) `The current invoice was signed at the same time or later than this quote. A newer signed quote is required to create an invoice from a quote.` **From 16G PR 6 (#124)** the first sentence shows exactly when `accepted.invoice_created_from_this_version` is true, and the second otherwise: no current-invoice read and no timestamp comparison.
- `Preview signed PDF` when `signed_pdf_available` — opens the stored signed PDF in a new tab; **no separate download button** (the browser downloads from the preview). Same rule as Customer Quote (§13). From 16G PR 6 it opens the version-addressed read for the version on screen.

Create Invoice asks for confirmation first (built, 16F PR3): the invoice is created from the signed quote, inherits its signature (the customer does not sign again) and carries the recorded payments; the text also says `This replaces the current unsigned invoice.` when there is one, or that a new invoice version replaces the current signed invoice while the earlier signed version stays in history. The confirmation is bound to the accepted version on screen and closes if a refresh changes it, and confirming waits until the current invoice has been read. Confirming also waits until any pending Details of Sale autosave is saved, and is blocked with an error when that save fails; it then re-reads the accepted quote state, and is refused with the changed-quote notice if the accepted version changed, or blocked with an error if that read fails. The request body is `{}` today (the server picks the latest accepted quote). **From 16G PR 3** it is `{"quote_version_id": N}`, the accepted version the confirmation shows, and the server refuses a stale value with 409 `QUOTE_VERSION_STALE` ("The accepted quote changed while the confirmation was open. Review the accepted quote before creating an invoice."). That 409 shows its message and refreshes the issued and accepted state, as every 409 does; nothing retries on its own, and the refresh, confirmation-binding and changed-quote safeguards above stay. 409 and 422 messages show verbatim, with the precondition list; after a success the tab re-reads the quote state and switches to the Invoice tab, which shows the new invoice with the inherited signature, name and time and the frozen quote terms, and Create Invoice stays disabled until that re-read confirms the new state. The success message is the response message, shown verbatim (from 16G PR 3 it says whether the signed PDF was emailed, could not be emailed, or was not emailed for lack of a valid customer email).

Create Invoice from the accepted quote uses the **accepted quote snapshot** (backend built in 16F PR2; button built in 16F PR3). After an invoice is created from an accepted quote, the normal Details of Sale button becomes `Rewrite Invoice` (an invoice now exists), and the salesperson can still use the direct Details-of-Sale invoice/rewrite flow as normal (a manual rewrite returns to the live order content and terms and clears the signature; because the current invoice is then unsigned, the accepted quote can be converted again).

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

Built in 16F PR3 (frontend): the public signing UI (section 13) and the Accepted Quote `Preview signed PDF` (section 14).

---

## 16. Invoice vs quote relationship

Quote and invoice are separate lifecycle objects.

- Direct invoice path: Details of Sale → `Create Invoice` / `Rewrite Invoice`.
- Quote path: Details of Sale → `Create Quote` → Quote Draft → Send Quote → Customer Quote → customer acceptance → Accepted Quote → Create Invoice from Accepted Quote (customer acceptance: backend built in 16F PR1, signing UI built in PR3; Accepted Quote view built in PR3; Create Invoice from Accepted Quote: backend built in PR2, UI built in PR3).

A quote is not mandatory for invoicing — an invoice can still be created directly from Details of Sale. A quote can be created before or after an invoice, as long as the Quote tab has not already been opened for that order. Once a quote is accepted and an invoice is created from it, the Details of Sale button reads `Rewrite Invoice`.

Since 16F PR1:

- Accepting the current invoice in-app (signing on the iPad) cancels any active issued quote and its link in the same transaction (decision D5(c)), so that quote can no longer be signed remotely (its link shows the cancelled message). With no active issued quote it does nothing. **Since 16G PR 4** a remote invoice signature (the customer signing through an invoice link) does the same, in its own transaction (D5(c) extended to remote invoice acceptance).
- An accepted quote never blocks invoice signing, in-app or remote, and is never revoked by it (D5(d)) — one direction only. If the invoice is signed, the accepted quote simply is not converted (unless a later quote is signed strictly after the invoice, below).
- A remote acceptance sets the order's sale-price override to the accepted total (D6b, §1.1). A direct Create/Rewrite Invoice from Details of Sale still uses the order's working price, which therefore equals the accepted total unless Products & Charges or the override changed after the acceptance.
- **A quote signed after the in-app invoice signature (decision D5(b) as amended on 8 October 2026):** D5(c) cancels only the quote link that is active when the invoice is signed in-app. A quote sent after that can still be signed remotely (there is no send or signing guard), and its D6b write changes the working order price. Because its signature is strictly newer than the invoice's, Create Invoice from that accepted quote is allowed: it appends a new invoice version, and the earlier signed invoice stays in history (contract section 13).

Built in 16F PR2 (Path A backend; the button built in PR3): Create Invoice from the accepted quote creates version 1 when there is no invoice. Otherwise it appends a new invoice version (version mechanics, active payments carried: D5(a)) when the current invoice is unsigned or the quote was signed strictly later than the current invoice (D5(b) as amended: the newer signature wins, and the earlier signed invoice version stays in history). An equal or older quote signature is refused (409). It is allowed on a LAID order in every permitted case (D4; the manual Rewrite LAID block does not apply). It never cancels or changes an issued quote or its link (only an invoice acceptance does, D5(c): in-app today, and remote too since 16G PR 4). Today it sends no email. **From 16G PR 3** it emails the new signed invoice PDF to the saved customer email after commit, with no email gate (a missing or invalid email skips the send with a WARN; a failed send leaves the invoice created; Re-send is the retry route), because a signed invoice is always emailed, by any route. **Since 16G PR 2** its Invoice To is the quote's frozen `V17` identity, stored on the invoice, and **since 16G PR 4** it marks an `ACTIVE` invoice link `SIGNED`. No store notification is sent.

Built in 16F PR3 (Invoice tab terms): the Invoice tab renders the invoice's terms only from InvoiceDetail `terms_html` (no tenant-config lookup and no fallback), sanitised with the same restricted allowlist, and shows a plain terms-source line from `terms_source`. The in-app acceptance gate now waits for that InvoiceDetail, the terms the invoice is accepted under, instead of the tenant invoice config. **Since 16G PR 2** the terms are the ones stored on the version and the source is frozen from the signed quote (`QUOTE`; a frozen null shows no terms even when the business has terms) or the business's terms frozen into the version at Create or Rewrite (`BUSINESS`, which replaced the 16F `LIVE`, the business's current terms), with the section 20 wording; the Invoice tab also builds Invoice To from the version's frozen fields (section 20). From 16G PR 3 the gate also uses the version's frozen name (section 20).

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

**16F — remote acceptance + invoice conversion (COMPLETE: PRs #117, #118, #119):**

- **PR1 — backend acceptance (BUILT):** public `POST /public/quotes/{token}/accept` (signature-only multipart; accepted name from the `V17` snapshot — D1; allowed when LAID — D4; public-safe 422 wording — D11); signed quote PDF stored; version `ACCEPTED`, token `CONSUMED`; post-commit store notification email (the order's store email, skipped if blank, no attachment, recording-only); accepted total → order sale-price override (D6b); in-app invoice acceptance cancels the active issued quote (D5(c)); protected reads (`workspace.accepted`, `pdf?type=accepted`, `GET …/quote/accepted/signature`); quote email link-only; migration `V19`.
- **PR2, backend Create Invoice from Accepted Quote, Path A (BUILT):** `POST …/quote/create-invoice`; inherits the signature (no re-sign; the same stored signature file); invoice fields from the accepted snapshot only; frozen quote terms on the invoice and InvoiceDetail `terms_html` / `terms_source` (D6/D7); the strict newer-signature rule and the matching `invoice_eligible` (D5(b) as amended); allowed when LAID; no email; no migration (uses the `V19` columns).
- **PR3, frontend (BUILT):** public signing UI; Accepted Quote tab functional (`Preview signed PDF` only); Create Invoice button; issued/accepted refresh with stale-response guards; send-after-acceptance warning (D9); Invoice tab terms from InvoiceDetail with their source.
- Out of scope: `Rewrite Quote` (D10).

**16G — remote invoice signing link and follow-ups (PLANNED; target UX locked in 16G PR 1; [16G contract](API-Contracts-Phase16G-Invoice-Link.md)):**

- **PR 2:** Invoice tab Invoice To from InvoiceDetail's frozen fields; terms-source wording for `BUSINESS` (section 20).
- **PR 3:** Invoice tab sends `expected_version_number`, drops the name part, handles the stale 409 and the frozen-name gate (section 20); Quote tab sends `{"quote_version_id": N}` and handles `QUOTE_VERSION_STALE` (section 14).
- **PR 5:** public invoice page `/i/{token}`; Accept Remotely; Download PDF renamed Preview PDF; specific dead-link messages on both public pages (#126; sections 13 and 20).
- **PR 6:** polling from server state (#120: the Quote tab in section 13, the Invoice tab on `invoice_link_expires_at` in section 20) and the Invoice tab signing-draft reset when a refresh installs another version (section 20), order financial refresh and the edit-gated Update Sale Price (#121, section 10), version-addressed accepted reads (#122) and the provenance boolean (#124) (section 14), and the Customer Quote Not delivered badge from `last_email_delivered` (section 13).

---

## 18. Cost discipline

The quote UI, quote PDF, and public/customer quote surface must **never** expose raw cost.

Forbidden (display, bind, or send): `cost`, `cost_snapshot`, `costSnapshot`, `unitCost`, `lineCost`, `totalCost`, `total_cost`, and any internal margin/cost field. Any key containing `cost` or ending `_snapshot` is rejected by the backend allow-list as a backstop, but the frontend must never bind or send them in the first place.

Allowed in the protected salesperson portal only: `gp_percent`, the below-cost warning, and quote totals. The public/customer quote surface is entirely cost-free.

The public accept (16F PR1) never shows cost or GP figures: a below-cost quote, a missing name snapshot and a price that cannot be stored all return the same customer-facing message `This quote can no longer be accepted online. Please contact the store.` (decision D11). The raw 422 `error.code` (`QUOTE_BELOW_COST` / `ACCEPTED_CUSTOMER_NAME_REQUIRED` / `BUSINESS_RULE_VIOLATION`) still names the cause in the response body — accepted under D11 ("code stays QUOTE_BELOW_COST"); the public page (16F PR3) shows only the message, never the code. The store notification carries no cost or GP either.

---

## 19. Purpose of this document

This is the visual/workflow source of truth for Phase 16D onward. It exists so implementation agents do not guess: where the Quote tab appears and when it persists, what `Create Quote` means, how Quote Draft looks, how itemised carry-over and adjustment math work, how autosave behaves (quote-only; it does not change order pricing — only the customer's acceptance does, D6b), what Customer Quote and Accepted Quote mean, how the quote PDF looks, and what belongs to 16D vs 16E vs 16F. Since 16G PR 1 it also holds the 16G Invoice tab and public invoice page UX (section 20).

---

## 20. Invoice tab and public invoice page (16G)

Target UX for 16G, written in 16G PR 1; each item names the PR that builds it. Backend rules: [`docs/API-Contracts-Phase16G-Invoice-Link.md`](API-Contracts-Phase16G-Invoice-Link.md). Muneeb reviews the Invoice tab result in the implementation smoke test and may ask for changes then.

### 20.1 Invoice tab — unsigned invoice

- **Invoice To (built in PR 2):** rendered only from InvoiceDetail's `customer_name_snapshot`, `customer_address_line1_snapshot` and `customer_address_line2_snapshot`, the words frozen on the version shown, never from the live customer or billing props. A null line is simply not shown. Since PR 3 the name under the signature pad, which D.8 records as the accepted name, is the same frozen `customer_name_snapshot`, so the Invoice To name and the accepted name agree on screen and in the signed PDF even after a rename without a Rewrite (before PR 3 it came from the saved customer).
- **Terms-source line:** the terms still come only from `terms_html`. `QUOTE` keeps its wording ("Terms source: frozen from the signed quote." / "Terms source: frozen from the signed quote, which has no terms."). **Since PR 2** `BUSINESS` reads "Terms source: the business’s terms, frozen when this invoice was created or rewritten." or, with no terms, "Terms source: the business’s terms, frozen when this invoice was created or rewritten (none were set)." No wording promises a live terms lookup.
- **Preview PDF (PR 5):** the top action **Download PDF** is renamed **Preview PDF**. It opens the current version's stored PDF in a new browser tab through the unchanged protected `D.4` read (`…/invoices/current/file`), with the popup-safe pattern of the quote previews (section 11): a blank tab opened in the click handler, the blob URL set when it arrives, and a clear error with a download fallback when the popup is blocked. The PDF is never regenerated. (This also meets the acceptance criteria of [#113](https://github.com/MuneebHash/flooring-sales-portal/issues/113).)
- **Signing area:** the two declarations (unchanged wording, a frontend-only gate) and the signature pad. The accepted name under the pad is never typed: since **PR 3** (built) it is the version's frozen `customer_name_snapshot`, shown in full (a long name wraps).
- **Frozen-name gate (built in PR 3):** when the version's frozen name is blank, Accept Invoice is disabled and the notice reads "This invoice has no customer name. Save the customer's name on the Customer tab, then rewrite the invoice before accepting it.", with the existing Go to Customer tab button. The 150-character notice is removed, because there is no length limit any more. (The gate replaced the earlier live-name notices.)
- **Bottom teal action bar (PR 5):** for an unsigned invoice it is split into two buttons, **Accept Invoice** | **Accept Remotely**, with a small icon on Accept Remotely (for example a send or mail glyph).
  - **Accept Invoice** keeps its gates (ink, both ticks, the frozen name, the InvoiceDetail loaded) and calls D.8 with `expected_version_number` (built in PR 3).
  - **Accept Remotely** needs no ink and no ticks. It is enabled when an unsigned current invoice is loaded and no accept or send is in flight, is single-flight, and calls `POST …/invoices/current/send-link`, which emails the link to the **saved** customer email. On success it shows the response message ("Invoice link emailed to the customer.") in the existing notice. On failure it shows the backend message verbatim: the 502 "The invoice link could not be emailed. Please try again."; the customer-email 422s with the existing Go to Customer tab pointer; a 409 `INVOICE_ALREADY_ACCEPTED`, which also re-reads the invoice like the existing drift handling. There is **no link status line, no expiry display and no cancel**. It is allowed on a LAID order.
- **Stale version (built in PR 3):** a 409 `INVOICE_VERSION_STALE` from Accept Invoice clears the pad and both ticks (even if the re-read then fails), shows "Invoice updated, please refresh", re-reads the current invoice and waits for a fresh signature. It never retries on its own. The existing best-effort re-read before posting stays as an early hint with its existing message, and Accept Invoice posts the `version_number` of the invoice on screen, never one adopted from a later read.
- **Polling (PR 6, #120):** the tab re-reads the current invoice every 15 seconds while the last InvoiceDetail it applied shows `invoice_link_expires_at` non-null (an `ACTIVE`, unexpired invoice link; contract §4.5) and the displayed invoice is unsigned, only while the browser tab is visible and the Invoice tab is the active workspace tab.
  - The condition is server state only. It holds after a page reload, for a link sent from another staff session or device, and for an `ACTIVE` link kept after a failed send (502). It never depends on a successful Accept Remotely in this workspace or on an expiry remembered from a send response.
  - The tab learns the field from every current-invoice read: the initial load, a reload, the re-read that follows every Accept Remotely attempt whatever its outcome, and each poll.
  - Polling stops when a read shows the invoice signed or `invoice_link_expires_at` null, or when the returned expiry passes. Failed polls are silent and keep the existing stale-response guards. A signed result renders the normal accepted state.
  - The field is never displayed: no link status line, no expiry display, no cancel.
- **Signing draft (PR 6):** any refresh or poll that installs a different invoice version (another `version_number`, including a changed version found by the pre-accept re-read) clears the signature ink and both declaration ticks before Accept Invoice can be enabled for that version. A refresh or poll that installs the unchanged version keeps the ink and ticks.
  - The existing response-order, order-change, unmount and mutation (single-flight) guards stay. An ignored or failed response never installs an older version and never clears a valid draft.
  - Today the pre-accept re-read already clears the ink on a changed version, and the ticks already reset on any version change; PR 6 applies the rule to every refresh and poll path, the ink included.
  - Polling stays read-only. On this tab the only editing state it can change is the signing draft of a version it replaces. The PR 3 stale-409 handling (above) is unchanged.

### 20.2 Invoice tab — signed invoice

Unchanged: Re-send Invoice in the bottom bar, the captured signature, and the accepted-but-not-emailed notice when `last_emailed_at` is null. **Accept Remotely is never shown for a signed invoice.** A remotely signed invoice displays its stored, normalised signature through the existing signature read.

### 20.3 Public invoice page `/i/{token}` (PR 5)

- **Surface:** a top-level, slugless, token-only page, a sibling of `/q/{token}`, with no app shell and no login. Its API calls never send credentials (a public invoice wrapper parallel to the public quote one). The page title is "Invoice {order_number}". Referrer protection is applied per element, as on the quote page.
- **Layout:** the public quote page's layout (the Invoice tab's document box) with invoice wording and the invoice rows:
  - the logo or business name, then **TAX INVOICE** and the order number;
  - Invoice To (the frozen name and billing lines), Details of Sale, the invoice date and due date;
  - the totals (ex GST, GST, inc GST), **Payment Made** and **Balance Due**;
  - the payment methods: ABN, direct deposit and Pay online (HTTPS Stripe link);
  - the stored terms, sanitised with the same restricted allowlist;
  - then the acceptance area.
  There is no deposit sentence and no line table: the invoice is details plus totals.
- **Preview PDF:** opens `GET /api/v1/public/invoices/{token}/pdf?version_number=N` for the version shown, popup-safe. A 409 there triggers the stale handling below.
- **Acceptance area:** the two Invoice tab declarations (a frontend-only gate; nothing is sent for them), a signature pad (`SignaturePad`, exported within the safe-decode bounds by `toBoundedSignaturePng`) and **Accept Invoice**. Accept is enabled with ink and both ticks, is single-flight, and posts `expected_version_number` (the version shown) and the `signature` only.
- **Outcomes:**
  - **201:** a local "Invoice accepted." confirmation for that session; the Preview PDF and signing actions are removed. A fresh load then shows the signed-link message.
  - **409 `INVOICE_VERSION_STALE`** (accept or Preview PDF): re-read the link once, in place; render the current version; clear the ink and both ticks; show "Invoice updated, please refresh"; wait for a fresh review and signature. Never retry automatically.
  - **404 / 410:** show the response message; signing ends.
  - **422:** show the message (the neutral sentence "This invoice can no longer be accepted online. Please contact the store." for a business-rule refusal, or the signature message). Ink and ticks stay.
  - **No readable answer** (a network failure, an unreadable 2xx, a 502, 503 or 504): one re-read of the link before anything else. ACTIVE with the same version: keep the ink and ticks and show "We could not confirm your acceptance. Your signature is still here, so you can try again." ACTIVE with another version: the stale handling. A dead state, including `SIGNED`: that state's message. The page never re-posts on its own and never infers its own success from a re-read.
  - **Any other error** (400, 415, 500): show the message and keep the ink and ticks for a retry.
- **Load and dead links:** a dead state shows `data.message` (replaced, rewritten, signed or expired; contract §7.2); a 404 shows "Invoice not found."; a load with no readable answer shows "We could not load this invoice. Please try again." with a retry. A dead link never shows invoice content, a signature or an action. There is no view-only mode after signing: the customer receives the signed PDF by email.

### 20.4 Both public pages (#126, PR 5)

The quote page shows the specific dead-link reason everywhere (section 13). The invoice page does the same with its four reasons. Neither shows a generic dead-link sentence for a known state.
