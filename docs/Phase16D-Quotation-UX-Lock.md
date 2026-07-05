# Phase 16D — Quotation UX / Product Lock

Status: **locked product + UX contract for Phase 16D onward.** Docs-only. No code is implemented by this document.

This document **extends and clarifies** `docs/API-Contracts-Phase16B-Quotation.md`. It does **not** replace it. Phase 16B remains the authoritative contract for the broader quotation lifecycle (quote draft, issued/customer quote, accepted quote, send email/SMS, public token link, viewed/opened state, customer acceptance/signature, signed quote PDF, create-invoice-from-accepted-quote).

Where this document and Phase 16B differ on a **frontend workflow / visual / product** decision, **this document wins for 16D**. Where they differ on **backend contract/behavior**, the **live code on `main` and Phase 16B win** — see the corrections in §1.

Source-of-truth order: (1) live repo on `main`, (2) `docs/API-Contracts-Phase16B-Quotation.md`, (3) this document, (4) roadmap/handover notes.

---

## 1. Corrections to avoid — read first

These three points exist because earlier drafts described behavior that does not match the live backend. Do not repeat these mistakes.

### 1.1 Quote modularity — described accurately

Current backend reality on `main` (Phase 16C):

- Quote lines do **not** mutate the order's product/charge lines. That separation is real and must be preserved.
- `PUT /api/v1/{slug}/orders/{orderId}/quote/draft` saves the quote draft **only**. It does **not** change the order sale-price override or any `sales_order` header financials (decoupled in Phase 16D-A).
- Therefore quote autosave never changes the order/header price or the next invoice — the quote draft is fully independent of order pricing.

What 16D states:

- The quote draft is **price-independent**. Quote lines are customer-facing and separate from operational product/charge lines; editing/saving a quote never edits Products & Charges and never changes the order sale price.
- The header "Sale total" is driven only by Products & Charges plus the manual sale-price override — a quote save does **not** move it.
- The quote total **may differ** from the order's working price by design; they reconcile later only via the 16F accepted-snapshot invoice path (Path A).

### 1.2 Quote PDF wording — desired future, not a current bug

Current known state of the quote PDF template (`quote.html`):

- It already uses quote-specific wording: title `QUOTE`, recipient label `Quote To`. It does **not** say `TAX INVOICE` or `Invoice To`.

Desired future wording preference (product direction only):

- `QUOTE` → `QUOTATION`
- `Quote To` → `Quotation To`
- terms wording should clearly read as **terms applicable to this quote**

These are **backend template wording changes** and are **out of 16D frontend scope**. Document them as desired direction only. Do **not** reopen the backend PDF in 16D unless it is explicitly scoped later as its own small backend task (own verify gate + Codex review).

### 1.3 Send Quote — visible in 16D, real sending is 16E

16D may show the Send Quote button and its confirmation modal, but must **not** wire real delivery.

- 16D: button visible → click opens a confirmation modal → modal shows `Send by Email` / `Send by Phone/SMS` / `Cancel`, with Email/SMS **disabled or clearly marked "Coming in 16E"** → **no backend send endpoint is called**.
- 16E: wire real `send-email` / `send-sms`, issue a quote version, store the issued PDF, mint token/link, and make the Customer Quote tab functional.

---

## 2. Core quotation principle

A quote is a **customer-facing commercial document**. Products & Charges / Details of Sale are the **internal operational sales record**.

- Quote Draft is a customer-facing, editable presentation, initially seeded from the order, that then becomes its own working quote canvas.
- Quote lines **never** mutate order product/charge lines.
- A quote **may** differ from the internal/current order working price by design; saving a quote does not change the order price (see §1.1).
- The accepted quote snapshot becomes the source for quote-led invoice creation later (16F).

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

- The Quote tab is **hidden by default**.
- It appears only after the user chooses `Create Quote` from the Details of Sale action modal.
- Once visible for an order, it **stays visible for that order** for the rest of the workspace session.
- Switching away and back must **not** lose in-progress quote work (keep the tab mounted / preserve its local state across tab switches).

**Visibility source of truth (locked — prevents guessing):**

- **On order load:** the Quote tab is shown **iff a backend quote draft already exists** for that order (i.e. `GET /quote/workspace` returns a non-null `draft`). This is the durable signal — visibility survives reload because it is derived from real backend state, not a transient flag.
- **Within a session:** once the user clicks `Create Quote`, the tab is shown for the rest of that session even before the first save has persisted a draft.
- Do **not** invent a separate "quote started" flag or persist visibility in the frontend only. Draft-existence is the durable source of truth; the session flag only bridges the gap between clicking `Create Quote` and the first successful autosave.

---

## 5. Quote tab internal sub-tabs

The Quote tab has three internal sub-tabs:

1. **Quote Draft** — editable quotation canvas.
2. **Customer Quote** — latest sent/issued quote snapshot, read-only.
3. **Accepted Quote** — latest accepted/signed quote snapshot, read-only.

For 16D:

- **Quote Draft** is functional.
- **Customer Quote** shows a clean empty state: `No quote has been sent yet.`
- **Accepted Quote** shows a clean empty state: `No quote has been accepted yet.`

These are real lifecycle tabs, not throwaway placeholders. (`current_issued` and `accepted` are always null in 16C, so no real data drives Customer/Accepted quote yet — that is 16E/16F.)

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
- if a saved quote draft already has lines, **the saved quote draft lines are the source of truth** — do not silently overwrite them from Products & Charges just because the order changed. Re-seeding only happens on an explicit user rebuild. This includes retained dormant rows on a non-itemised draft: toggling itemised back ON restores them and must not re-seed (§9).

### 8.2 Quote pricing is independent of the order (locked)

The quote draft save is decoupled from order pricing (Phase 16D-A, see §1.1): saving a quote never writes `sales_order.price_adjustment_inc_gst` or any header financial. There is therefore **no** override to reconcile or preserve on carry-over — turning itemised ON and saving the seeded lines does not change the order price in any way.

Seed the quote from the order's current sale-side line data as a **starting point only**. The salesperson then edits the quote freely; the quote total may differ from the order Sale total with no effect on the order or the next invoice.

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

16D behavior:

- clicking it opens a **confirmation modal only** — it never sends immediately
- modal meaning: `Are you sure you want to send this quote?`
- modal body: `Please double-check all quote details before sending.`
- delivery options: `Send by Email`, `Send by Phone/SMS`, `Cancel`
- Email/SMS actions are **disabled or clearly marked "Coming in 16E"**
- no backend send endpoint is called

16E behavior: the Email/SMS actions wire to the real `send-email` / `send-sms` endpoints.

Hard rule: **no accidental one-click sending**, now or in 16E — sending always passes through this confirmation modal.

---

## 13. Customer Quote — future visual

Same quotation canvas style, read-only. Shows the latest issued/sent quote snapshot. Never shows raw cost.

Simple lifecycle states (MVP direction):

- first state: `Sent`
- after the customer opens the public quote link: `Opened / Viewed`

Future fields (16E and later): sent channel (Email/SMS), sent time, opened/viewed time, link expiry, download issued PDF, cancel/resend controls if scoped. Keep the status model simple — do not add many states beyond Sent and Opened/Viewed for MVP.

---

## 14. Accepted Quote — future visual

Same quotation canvas style, read-only and signed. Shows:

- the same customer-facing quote content
- signature section
- accepted customer name
- accepted date/time
- `Create Invoice` button (from the accepted quote)
- download signed PDF if available

Create Invoice from the accepted quote uses the **accepted quote snapshot**. After an invoice is created from an accepted quote, the normal Details of Sale button becomes `Rewrite Invoice` (an invoice now exists), and the salesperson can still use the direct Details-of-Sale invoice/rewrite flow as normal.

Accepted Quote shows the **latest** accepted quote only for MVP; older accepted-quote history stays out of the UI unless explicitly scoped later.

---

## 15. Quote PDF — desired visual direction (not 16D scope)

Documented as desired final direction. The quote PDF is backend (`quote.html`) and is **out of 16D frontend scope**; change it only under a separately scoped small backend task.

Desired:

- title `QUOTE` → `QUOTATION`
- `Quote To` → `Quotation To`
- terms wording should read as terms applicable to this quote, not invoice
- signed quote PDF includes the customer signature, accepted name, and accepted timestamp
- non-itemised PDF: no fake `Quoted works` / "single quoted amount for works described above" line — clean Details of Sale + total + quote terms
- itemised PDF: show the line table **only when itemised is ON**, with clean columns: description / quantity / unit price / amount
- adjustment/discount lines shown visibly if present; total matches the visible lines
- never show raw cost

Note: the current template already suppresses the itemised line table for non-itemised quotes and already uses `QUOTE` / `Quote To` — so the desired wording change is a preference, not a fix, and must not be treated as reopening merged 16C work in 16D.

---

## 16. Invoice vs quote relationship

Quote and invoice are separate lifecycle objects.

- Direct invoice path: Details of Sale → `Create Invoice` / `Rewrite Invoice`.
- Quote path: Details of Sale → `Create Quote` → Quote Draft → Send Quote → Customer Quote → Accepted Quote → Create Invoice from Accepted Quote.

A quote is not mandatory for invoicing — an invoice can still be created directly from Details of Sale. A quote can be created before or after an invoice, as long as the Quote tab has not already been opened for that order. Once a quote is accepted and an invoice is created from it, the Details of Sale button reads `Rewrite Invoice`.

---

## 17. Phase split (16D / 16E / 16F)

**16D — frontend quote UX foundation:**

- Details of Sale action modal entry
- hidden Quote tab until `Create Quote`
- Quote Draft canvas (invoice-style)
- itemised ON/OFF behavior (incl. carry-over + adjustment helper)
- autosave (quote-only; does not change order pricing)
- Preview PDF that flushes autosave first, opens in a new tab
- visible Send Quote button with a **disabled** confirmation modal (no real send)
- Customer Quote / Accepted Quote clean empty states

**16E — quote delivery:**

- `send-email` / `send-sms` endpoints
- issue a quote version; store the issued PDF; mint token/link
- sent / opened(viewed) state
- Customer Quote tab becomes functional

**16F — remote acceptance + invoice conversion:**

- public quote page; public viewed tracking
- customer signature; accepted quote; signed PDF
- Accepted Quote tab functional
- Create Invoice from Accepted Quote

---

## 18. Cost discipline

The quote UI, quote PDF, and public/customer quote surface must **never** expose raw cost.

Forbidden (display, bind, or send): `cost`, `cost_snapshot`, `costSnapshot`, `unitCost`, `lineCost`, `totalCost`, `total_cost`, and any internal margin/cost field. Any key containing `cost` or ending `_snapshot` is rejected by the backend allow-list as a backstop, but the frontend must never bind or send them in the first place.

Allowed in the protected salesperson portal only: `gp_percent`, the below-cost warning, and quote totals. The public/customer quote surface is entirely cost-free.

---

## 19. Purpose of this document

This is the visual/workflow source of truth for Phase 16D onward. It exists so implementation agents do not guess: where the Quote tab appears and when it persists, what `Create Quote` means, how Quote Draft looks, how itemised carry-over and adjustment math work, how autosave behaves (quote-only; it does not change order pricing), what Customer Quote and Accepted Quote mean, how the quote PDF should eventually look, and what belongs to 16D vs 16E vs 16F.
