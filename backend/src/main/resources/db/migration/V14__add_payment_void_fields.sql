-- V14__add_payment_void_fields.sql
-- Phase 15D PR1 (Payment Void + Stop Payment Auto-Email): adds the two nullable void-marker columns to
-- the existing payment_transaction table so a recorded payment can be SOFT-VOIDED. A void is never a
-- hard delete: the original row (amount, method, payment_reference, created_at) is preserved as an
-- immutable financial record and stays visible in payment history.
--
--   voided_at          set when the payment is voided; NULL = active. Every existing and newly created
--                      row defaults to active because the column is added NULL with NO default (V4 already
--                      seeds payment rows, so a NOT NULL column would fail on a fresh DB / prod apply).
--   voided_by_user_id  the SESSION actor who voided it; FK -> app_user(user_id), the same single-column
--                      FK style as invoice.created_by_user_id (V3). This is the logged-in user, NOT the
--                      order-bound salesperson.
--
-- A voided payment is excluded from the ACTIVE total_paid (the application's sumAmountByOrderId adds
-- "AND voided_at IS NULL"); count/list history queries do NOT filter it out. The amount > 0 CHECK
-- (chk_payment_amount_positive, V3) is deliberately left intact and unweakened — a void never writes a
-- negative payment row. There is no void_reason column in this MVP.
--
-- chk_payment_void_consistency keeps the two void columns atomic: either BOTH are null (active) or BOTH
-- are non-null (voided).

ALTER TABLE payment_transaction
    ADD COLUMN voided_at TIMESTAMP;

ALTER TABLE payment_transaction
    ADD COLUMN voided_by_user_id BIGINT;

ALTER TABLE payment_transaction
    ADD CONSTRAINT fk_payment_transaction_voided_by
    FOREIGN KEY (voided_by_user_id) REFERENCES app_user (user_id);

ALTER TABLE payment_transaction
    ADD CONSTRAINT chk_payment_void_consistency
    CHECK ((voided_at IS NULL AND voided_by_user_id IS NULL)
        OR (voided_at IS NOT NULL AND voided_by_user_id IS NOT NULL));
