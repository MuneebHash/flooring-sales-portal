-- V20: freeze the invoice "Invoice To" identity on every invoice version (Phase 16G PR 2, #125)
--
-- Until this migration every invoice render read the Invoice To name and billing lines LIVE from
-- order_customer / order_address, so a customer edit after a version was written changed what that
-- version showed on screen and on every later payment, void or signing PDF. From 16G PR 2 each
-- version stores its own words (docs/API-Contracts-Phase16G-Invoice-Link.md section 4):
--   customer_name_snapshot          - first [middle] last, each trimmed, single spaces (the V17 rule)
--   customer_address_line1_snapshot - [unit/]street_number street of the BILLING address
--   customer_address_line2_snapshot - suburb state_code postcode of the BILLING address
-- Create and Rewrite freeze them from the saved customer and BILLING address; payment, void and the
-- in-store accept copy the previous version's values verbatim (including null); Path A copies the
-- accepted quote version's V17 snapshot columns verbatim (including null).
--
-- TEXT and nullable, like the V17 quote_version columns they mirror: the values are derived and
-- server-composed (a fixed cap could make an invoice INSERT fail for a legitimately long identity),
-- and an order can lack a saved customer or BILLING row. No default; nothing else changes shape.
--
-- Terms reuse the existing V19 invoice.terms_snapshot. No terms column is added and terms are NOT
-- backfilled (16G contract section 4.1): an existing NULL terms_snapshot stays NULL and a populated one
-- stays verbatim. So for an older Path B version (whose terms were read live until now) its own stored
-- PDF keeps the terms page it was rendered with (stored PDFs are never regenerated or replaced), but
-- its InvoiceDetail now reports terms_html null with terms_source BUSINESS, and every later payment,
-- void or in-store accept version copies the NULL and renders no terms page. Only a new version with
-- frozen terms brings them back: a Rewrite (which clears a signature and is refused on a LAID order)
-- or Path A.
--
-- Migration policy: V1-V19 are never edited; this is a NEW migration. The CI "Locked migration
-- protection" guard covers V1-V13 only; V14-V20 are outside it, are still never edited, and V20
-- joins every other pre-production migration in the Phase 17 schema-only squash/baseline and its
-- CI re-lock.

ALTER TABLE invoice ADD COLUMN customer_name_snapshot          TEXT;
ALTER TABLE invoice ADD COLUMN customer_address_line1_snapshot TEXT;
ALTER TABLE invoice ADD COLUMN customer_address_line2_snapshot TEXT;

-- Backfill EVERY existing invoice row, older versions included, from its own order's CURRENT
-- order_customer row and BILLING order_address row - for rows saved through the API, the values the
-- live render was showing until this migration (pre-production data; the 16G decision record of
-- 13 October 2026 says production had no invoice rows, a dated statement rather than a check made
-- here). The SET body is the V17 backfill copied verbatim (V17 itself is unchanged); only the UPDATE
-- target (quote_version -> invoice) and its alias (qv -> inv) differ. It keeps V17's exact derivation:
--   name  = first [middle] last, blank parts skipped, whole result blank->NULL;
--   line1 = [unit/]street_number street from the FIRST BILLING address (lowest PK);
--   line2 = suburb state_code postcode.
-- A missing customer row or BILLING row leaves the matching columns NULL. As in V17, BTRIM strips
-- spaces only: a row written outside the API with tab or newline padding, or with a name part made
-- only of other Unicode whitespace, backfills differently from the Java derivation (String.trim /
-- isBlank); the API trims and rejects blank values on save, so saved rows cannot differ.
UPDATE invoice inv
SET customer_name_snapshot = (
        SELECT NULLIF(BTRIM(CONCAT_WS(' ',
                   NULLIF(BTRIM(oc.first_name), ''),
                   NULLIF(BTRIM(oc.middle_name), ''),
                   NULLIF(BTRIM(oc.last_name), ''))), '')
        FROM order_customer oc
        WHERE oc.order_id = inv.order_id
    ),
    customer_address_line1_snapshot = (
        SELECT NULLIF(BTRIM(
                   CASE WHEN oa.unit_number IS NOT NULL AND BTRIM(oa.unit_number) <> ''
                        THEN BTRIM(oa.unit_number) || '/' ELSE '' END
                   || oa.street_number || ' ' || oa.street), '')
        FROM order_address oa
        WHERE oa.order_id = inv.order_id AND oa.address_type = 'BILLING'
        ORDER BY oa.order_address_id
        LIMIT 1
    ),
    customer_address_line2_snapshot = (
        SELECT NULLIF(BTRIM(oa.suburb || ' ' || oa.state_code || ' ' || oa.postcode), '')
        FROM order_address oa
        WHERE oa.order_id = inv.order_id AND oa.address_type = 'BILLING'
        ORDER BY oa.order_address_id
        LIMIT 1
    );
