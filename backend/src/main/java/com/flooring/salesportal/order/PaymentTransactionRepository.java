package com.flooring.salesportal.order;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Native-SQL access for {@code payment_transaction} (Phase 12 Chunk 4). Branch A (D.1 Create) needs
 * only the order's total paid-to-date so the first invoice can snapshot {@code total_paid} /
 * {@code balance_due}. {@code payment_transaction} links to {@code order_id} only (no invoice FK), so
 * the total is summed per order. Recording payments (D.7) is a later branch.
 */
@Repository
public class PaymentTransactionRepository {

    private static final String SUM_AMOUNT_BY_ORDER_SQL =
            "SELECT COALESCE(SUM(amount), 0) FROM payment_transaction WHERE order_id = :orderId";

    private final NamedParameterJdbcTemplate jdbc;

    public PaymentTransactionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Sum of all payments for the order, rounded to money scale 2 ({@code 0.00} when none exist). */
    public BigDecimal sumAmountByOrderId(long orderId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("orderId", orderId);
        BigDecimal total = jdbc.queryForObject(SUM_AMOUNT_BY_ORDER_SQL, params, BigDecimal.class);
        return (total == null ? BigDecimal.ZERO : total).setScale(2, RoundingMode.HALF_UP);
    }
}
