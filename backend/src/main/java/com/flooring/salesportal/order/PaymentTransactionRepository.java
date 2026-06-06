package com.flooring.salesportal.order;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Native-SQL access for {@code payment_transaction} (Phase 12 Chunk 4). Branch A (D.1 Create) uses
 * {@link #sumAmountByOrderId} so the first invoice can snapshot {@code total_paid} / {@code balance_due}.
 * Branch D adds the payment endpoints: {@link #countByOrderId} / {@link #findByOrderId} for the D.6 list
 * and {@link #insert} for D.7. {@code payment_transaction} links to {@code order_id} only (no invoice FK),
 * so totals are summed per order.
 *
 * <p>The {@link PaymentRow} returned by reads / insert carries only the E.3 contract columns
 * ({@code payment_transaction_id}, {@code payment_method}, {@code amount}, {@code payment_reference},
 * {@code created_at}). The backend-only gateway columns ({@code gateway_transaction_id},
 * {@code response_status}, {@code response_message}) are never selected and are left {@code null} on
 * insert, so they cannot leak. {@code payment_method} is the PostgreSQL {@code payment_method} enum:
 * cast via {@code CAST(:paymentMethod AS payment_method)} on write and read back as text
 * ({@code payment_method::text}) — the same proven pattern as the other enum columns.
 */
@Repository
public class PaymentTransactionRepository {

    private static final String SUM_AMOUNT_BY_ORDER_SQL =
            "SELECT COALESCE(SUM(amount), 0) FROM payment_transaction WHERE order_id = :orderId";

    private static final String COUNT_BY_ORDER_SQL =
            "SELECT COUNT(*) FROM payment_transaction WHERE order_id = :orderId";

    // E.3 projection only (NO gateway columns). payment_method read back as text for the row mapper.
    private static final String READ_COLUMNS = """
                payment_transaction_id,
                payment_method::text AS payment_method,
                amount,
                payment_reference,
                created_at
            """;

    // Newest first; payment_transaction_id DESC is a stable tiebreaker when created_at ties (D.6).
    private static final String FIND_BY_ORDER_SQL = "SELECT\n" + READ_COLUMNS
            + "FROM payment_transaction\n"
            + "WHERE order_id = :orderId\n"
            + "ORDER BY created_at DESC, payment_transaction_id DESC\n"
            + "LIMIT :limit OFFSET :offset";

    // Gateway columns are intentionally omitted (left NULL on a manual MVP payment).
    private static final String INSERT_SQL = """
            INSERT INTO payment_transaction
                (order_id, payment_method, amount, payment_reference, created_at)
            VALUES
                (:orderId, CAST(:paymentMethod AS payment_method), :amount, :paymentReference, :createdAt)
            RETURNING
            """ + READ_COLUMNS;

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

    /** Total payment count for the order (D.6 pagination total_items). */
    public long countByOrderId(long orderId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("orderId", orderId);
        Long count = jdbc.queryForObject(COUNT_BY_ORDER_SQL, params, Long.class);
        return count == null ? 0L : count;
    }

    /** One page of payments for the order, newest first (D.6). */
    public List<PaymentRow> findByOrderId(long orderId, int limit, long offset) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(FIND_BY_ORDER_SQL, params, ROW_MAPPER);
    }

    /**
     * Insert one payment row and return its E.3 columns (gateway columns left {@code null}). A
     * {@code null} {@code paymentReference} persists as SQL NULL. {@code createdAt} is supplied by the
     * caller (wall-clock {@code LocalDateTime.now()}) so rows ordered within one DB transaction stay
     * distinct.
     */
    public PaymentRow insert(long orderId,
                             String paymentMethod,
                             BigDecimal amount,
                             String paymentReference,
                             LocalDateTime createdAt) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("paymentMethod", paymentMethod)
                .addValue("amount", amount)
                .addValue("paymentReference", paymentReference)
                .addValue("createdAt", Timestamp.valueOf(createdAt));
        return jdbc.queryForObject(INSERT_SQL, params, ROW_MAPPER);
    }

    private static final RowMapper<PaymentRow> ROW_MAPPER = (rs, n) -> {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new PaymentRow(
                rs.getLong("payment_transaction_id"),
                rs.getString("payment_method"),
                rs.getBigDecimal("amount"),
                rs.getString("payment_reference"),
                createdAt == null ? null : createdAt.toLocalDateTime());
    };

    /** The E.3 payment columns returned by a list read / insert (no gateway columns). */
    public record PaymentRow(
            long paymentTransactionId,
            String paymentMethod,
            BigDecimal amount,
            String paymentReference,
            LocalDateTime createdAt) {
    }
}
