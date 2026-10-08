-- V19: quote acceptance + invoice quote-source preparation (Phase 16F PR1)
--
-- 1. Accepted customer names become unbounded TEXT on BOTH acceptance records.
--    16F takes the accepted name for a remotely signed quote from the issued V17
--    customer_name_snapshot (TEXT, derived first [middle] last — up to 302 characters with the
--    V2 order_customer widths), and a Path A invoice (16F PR2) inherits that same name. A
--    VARCHAR(150) cap would force truncation or reject a legitimately long frozen name, and
--    truncation is not allowed. VARCHAR -> TEXT is a binary-compatible change in PostgreSQL: no
--    table rewrite, no data change, nullability unchanged (both stay NULL-able), no constraint
--    exists on either column. The in-app invoice acceptance request (Phase 13 D.8) keeps its own
--    150-character request validation — widening storage does not widen that request contract.
--
-- 2. invoice.source_quote_version_id + invoice.terms_snapshot — SCHEMA ONLY in PR1. Nothing in PR1
--    writes or reads them; every existing invoice row and every PR1 write leaves both NULL. 16F PR2
--    (create invoice from the accepted quote, "Path A") populates them, carries both forward on
--    payment / payment void, and clears both on manual rewrite. The terms-source rule PR2
--    implements: source_quote_version_id PRESENT -> the invoice uses terms_snapshot, even when
--    terms_snapshot is NULL (frozen "no terms"); source_quote_version_id ABSENT -> the live
--    per-flooring-type tenant terms (Path B, unchanged).
--    source_quote_version_id is deliberately NOT unique: every payment / void version of a Path A
--    invoice carries the same reference forward (the V10 accepted_signature_file_id precedent).
--    No ON DELETE clause (default RESTRICT — the V3/V16 convention for FKs to order-owned rows).
--    The plain index covers the FK column (the V3 "FK columns get an index" convention); it is not
--    unique and enforces nothing.
--
-- Migration policy: V1-V18 are never edited; this is a NEW migration. The CI "Locked migration
-- protection" guard covers V1-V13 only; V14-V19 are outside it and fold into the Phase 17
-- schema-only squash/baseline.

ALTER TABLE quote_version
    ALTER COLUMN accepted_customer_name TYPE TEXT;

ALTER TABLE invoice
    ALTER COLUMN accepted_customer_name TYPE TEXT;

ALTER TABLE invoice
    ADD COLUMN source_quote_version_id BIGINT;

ALTER TABLE invoice
    ADD CONSTRAINT fk_invoice_source_quote_version
    FOREIGN KEY (source_quote_version_id) REFERENCES quote_version (quote_version_id);

CREATE INDEX idx_invoice_source_quote_version ON invoice (source_quote_version_id);

ALTER TABLE invoice
    ADD COLUMN terms_snapshot TEXT;
