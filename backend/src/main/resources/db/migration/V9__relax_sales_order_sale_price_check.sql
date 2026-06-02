-- V9__relax_sales_order_sale_price_check.sql
-- Negative sale price is allowed while building/editing an order (the live order header).
-- A product/charge line edit or delete on top of a stored negative price_adjustment_inc_gst
-- can legitimately drive sales_order.sale_price_ex_gst below 0. The real (negative) value must
-- persist and display in Details of Sale / the financial summary — it is NOT clamped, floored,
-- or silently changed. A line mutation must never be blocked just because the running sale price
-- turns negative.
--
-- The block on a negative FINAL sale price belongs only to invoice creation
-- (API-Conventions §14 precondition 9), which is enforced separately by the invoice CHECK
-- constraints (chk_invoice_sale_price_ex_positive / chk_invoice_sale_price_inc_valid). Those are
-- intentionally LEFT IN PLACE.
--
-- This migration drops ONLY the live-order header non-negative check that V3 added. It does NOT
-- touch V3 itself, the invoice checks, the total_cost non-negative check
-- (chk_sales_order_total_cost_gte_zero), or any gp / gp_percent behavior.

ALTER TABLE sales_order
    DROP CONSTRAINT chk_sales_order_sale_price_gte_zero;
