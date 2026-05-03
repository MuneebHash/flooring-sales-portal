-- V5__add_business_slug_and_price_adjustment.sql
-- Adds business slug support, manual price adjustment persistence,
-- and aligns seeded invoice due dates with the locked rule.


-- ============================================================
-- 1. BUSINESS SLUG
-- ============================================================

ALTER TABLE business
    ADD COLUMN slug VARCHAR(50);

UPDATE business
SET slug = CASE
    WHEN business_id = 1 THEN 'aussie-floors-group'
    WHEN business_id = 2 THEN 'premier-flooring-co'
    ELSE concat(
        COALESCE(
            NULLIF(
                regexp_replace(
                    regexp_replace(lower(trim(name)), '[^a-z0-9]+', '-', 'g'),
                    '(^-|-$)',
                    '',
                    'g'
                ),
                ''
            ),
            'business'
        ),
        '-',
        business_id
    )
END
WHERE slug IS NULL;

ALTER TABLE business
    ALTER COLUMN slug SET NOT NULL;

ALTER TABLE business
    ADD CONSTRAINT uq_business_slug UNIQUE (slug);

ALTER TABLE business
    ADD CONSTRAINT chk_business_slug_format
    CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$');

ALTER TABLE business
    ADD CONSTRAINT chk_business_slug_reserved
    CHECK (slug NOT IN ('admin', 'api', 'login', 'static', 'auth', 'health'));


-- ============================================================
-- 2. SALES ORDER PRICE ADJUSTMENT (INC GST)
-- ============================================================

ALTER TABLE sales_order
    ADD COLUMN price_adjustment_inc_gst DECIMAL(10,2) DEFAULT NULL;


-- ============================================================
-- 3. SEEDED INVOICE DUE DATE CORRECTION
-- ============================================================
-- Locked rule: invoice.due_date = sales_order.proposed_lay_date - 2 days.

UPDATE invoice i
SET due_date = so.proposed_lay_date - 2
FROM sales_order so
WHERE i.order_id = so.order_id
  AND i.invoice_id = 1
  AND i.order_id = 1
  AND i.due_date = DATE '2026-04-28'
  AND so.proposed_lay_date IS NOT NULL;
