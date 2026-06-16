-- V13: per-flooring-type invoice terms (Phase 15B-2)

-- ============================================================
-- PER-TYPE INVOICE TERMS
-- ============================================================
-- A business needs two separate, business-wide invoice terms blocks: one for SOFT
-- (carpet/soft) orders and one for HARD (timber/tile/etc.) orders. One order has
-- exactly one flooring_type (SOFT or HARD), so an invoice renders exactly ONE of these.
--
-- Both columns are NULLABLE with NO DEFAULT: V4 already seeds business rows, so a
-- NOT NULL column without a default would fail on a fresh DB / prod apply. They are
-- left NULL by default — no seed, no backfill from the legacy single-block column.
--
-- The legacy business.terms_and_conditions column (V12) stays in place, untouched and
-- unrenamed, as deprecated/legacy compatibility. It is NOT backfilled into these columns.
--
-- Like the other private invoice-legal fields (V12), these columns are PRIVATE: they are
-- read only by the authenticated invoice-config endpoint and must never be exposed by the
-- public business lookup (they are deliberately left unmapped on the Business entity).

ALTER TABLE business ADD COLUMN terms_hard TEXT;
ALTER TABLE business ADD COLUMN terms_soft TEXT;
