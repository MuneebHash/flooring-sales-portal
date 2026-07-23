-- V17: freeze the quote "Quotation To" identity at issue (Phase 16E-C fix — Codex P1)
--
-- The public quote payload rendered customer name + billing address from the LIVE
-- order_customer/order_address rows, so a pre-LAID customer edit AFTER a quote was issued leaked
-- the new person's name/billing to the old token holder. These three nullable snapshot columns
-- freeze the customer-facing "Quotation To" block onto the issued quote_version at issue time —
-- exactly like the existing flooring_type/terms/details_of_sale snapshots. Nullable: an order can
-- legitimately be issued with no saved customer name or billing address (the PDF hides the lines).
--
-- Append-only: V1-V16 stay locked (this is a NEW migration; the CI "Locked migration protection"
-- guard covers V1-V13 and the Phase 17 squash/baseline will fold V14-V17 into one).
--
-- TEXT, not VARCHAR (Codex round-2 P2): these are derived server-composed values (name =
-- first+middle+last can exceed a fixed cap; line1 composes unit/street_number/street), and the
-- sibling snapshot columns (terms_snapshot, details_of_sale_snapshot) are already unbounded TEXT
-- — a cap here could make an issue INSERT fail for a legitimately long identity.

ALTER TABLE quote_version ADD COLUMN customer_name_snapshot          TEXT;
ALTER TABLE quote_version ADD COLUMN customer_address_line1_snapshot TEXT;
ALTER TABLE quote_version ADD COLUMN customer_address_line2_snapshot TEXT;

-- Backfill existing (pre-V17) versions from the CURRENT order_customer/order_address rows — the
-- same values the public payload was serving live until this migration, and the best available
-- approximation of issue-time state for pre-production dev data. The derivations mirror the
-- application helpers exactly:
--   name  = first [middle] last, blank parts skipped, whole result blank->NULL
--           (CONCAT_WS skips NULLs like appendIfPresent skips blanks);
--   line1 = [unit/]street_number street from the FIRST BILLING address (lowest PK — the
--           application takes the first BILLING row of the order's address list);
--   line2 = suburb state_code postcode.
UPDATE quote_version qv
SET customer_name_snapshot = (
        SELECT NULLIF(BTRIM(CONCAT_WS(' ',
                   NULLIF(BTRIM(oc.first_name), ''),
                   NULLIF(BTRIM(oc.middle_name), ''),
                   NULLIF(BTRIM(oc.last_name), ''))), '')
        FROM order_customer oc
        WHERE oc.order_id = qv.order_id
    ),
    customer_address_line1_snapshot = (
        SELECT NULLIF(BTRIM(
                   CASE WHEN oa.unit_number IS NOT NULL AND BTRIM(oa.unit_number) <> ''
                        THEN BTRIM(oa.unit_number) || '/' ELSE '' END
                   || oa.street_number || ' ' || oa.street), '')
        FROM order_address oa
        WHERE oa.order_id = qv.order_id AND oa.address_type = 'BILLING'
        ORDER BY oa.order_address_id
        LIMIT 1
    ),
    customer_address_line2_snapshot = (
        SELECT NULLIF(BTRIM(oa.suburb || ' ' || oa.state_code || ' ' || oa.postcode), '')
        FROM order_address oa
        WHERE oa.order_id = qv.order_id AND oa.address_type = 'BILLING'
        ORDER BY oa.order_address_id
        LIMIT 1
    );
