-- V8__add_product_sqm_per_lm_factor.sql
-- Replaces the fixed LM↔SQM conversion (1 LM = 3.66 SQM) with a per-product
-- conversion factor. Most carpet rolls are 3.66 m wide (1 LM = 3.66 SQM), but
-- some rolls are 4 m wide (1 LM = 4.00 SQM). Each product now carries its own
-- factor, and each product line snapshots the product's factor at line creation.
--
-- `sqm_per_lm` means "how many SQM are in 1 LM".


-- ============================================================
-- 1. CATALOG: store_product.sqm_per_lm
-- ============================================================

ALTER TABLE store_product
    ADD COLUMN sqm_per_lm DECIMAL(6,2) NOT NULL DEFAULT 3.66;

ALTER TABLE store_product
    ADD CONSTRAINT chk_store_product_sqm_per_lm_positive
    CHECK (sqm_per_lm > 0);


-- ============================================================
-- 2. PRODUCT LINE SNAPSHOT: order_product_line.sqm_per_lm_snapshot
-- ============================================================
-- Added nullable, backfilled to the existing fixed conversion, then locked
-- to NOT NULL so every line carries the factor used at its creation.

ALTER TABLE order_product_line
    ADD COLUMN sqm_per_lm_snapshot DECIMAL(6,2);

UPDATE order_product_line
SET sqm_per_lm_snapshot = 3.66
WHERE sqm_per_lm_snapshot IS NULL;

ALTER TABLE order_product_line
    ALTER COLUMN sqm_per_lm_snapshot SET NOT NULL;

ALTER TABLE order_product_line
    ADD CONSTRAINT chk_opl_sqm_per_lm_snapshot_positive
    CHECK (sqm_per_lm_snapshot > 0);


-- ============================================================
-- 3. SEED A 4 m ROLL PRODUCT
-- ============================================================
-- product_id 2 (LOP-001, Loop Pile Carpet) is an existing unused SOFT product.
-- Re-seed it as a 4 m wide roll: 1 LM = 4.00 SQM.
-- Existing order lines (order 1 uses product_id 1) keep their 3.66 snapshot;
-- their quantities and totals are unchanged.

UPDATE store_product
SET sqm_per_lm = 4.00
WHERE product_id = 2
  AND code = 'LOP-001';
