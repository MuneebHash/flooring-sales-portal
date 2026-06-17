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
import java.util.Optional;

/**
 * Native-SQL access for {@code payment_transaction} (Phase 12 Chunk 4). Branch A (D.1 Create) uses
 * {@link #sumAmountByOrderId} so the first invoice can snapshot {@code total_paid} / {@code balance_due}.
 * Branch D adds the payment endpoints: {@link #countByOrderId} / {@link #findByOrderId} for the D.6 list
 * and {@link #insert} for D.7. {@code payment_transaction} links to {@code order_id} only (no invoice FK),
 * so totals are summed per order.
 *
 * <p>The {@link PaymentRow} returned by reads / insert carries the E.3 contract columns
 * ({@code payment_transaction_id}, {@code payment_method}, {@code amount}, {@code payment_reference},
 * {@code created_at}) plus the Phase 15D void markers ({@code voided_at} and the joined
 * {@code voided_by_name}). The backend-only gateway columns ({@code gateway_transaction_id},
 * {@code response_status}, {@code response_message}) and the raw {@code voided_by_user_id} are never
 * selected back, so they cannot leak. {@code payment_method} is the PostgreSQL {@code payment_method}
 * enum: cast via {@code CAST(:paymentMethod AS payment_method)} on write and read back as text
 * ({@code payment_method::text}) — the same proven pattern as the other enum columns.
 *
 * <p>Phase 15D void semantics: {@link #sumAmountByOrderId} (the ACTIVE total_paid) excludes voided rows
 * ({@code AND voided_at IS NULL}); the history reads ({@link #findByOrderId} / {@link #countByOrderId})
 * deliberately do NOT filter, so a voided payment stays visible. {@code voided_by_name} is built from a
 * LEFT JOIN to {@code app_user} on {@code voided_by_user_id} (LEFT, never INNER, so active rows — whose
 * {@code voided_by_user_id} is null — are not dropped and resolve to a null name).
 */
@Repository
public class PaymentTransactionRepository {

    // ACTIVE total only: a voided payment no longer counts toward total_paid (Phase 15D).
    private static final String SUM_AMOUNT_BY_ORDER_SQL =
            "SELECT COALESCE(SUM(amount), 0) FROM payment_transaction WHERE order_id = :orderId AND voided_at IS NULL";

    // History count is NOT filtered by voided_at — voided rows remain visible in the list (Phase 15D).
    private static final String COUNT_BY_ORDER_SQL =
            "SELECT COUNT(*) FROM payment_transaction WHERE order_id = :orderId";

    // Read projection (E.3 + Phase 15D void markers), table-qualified for the LEFT JOIN to app_user that
    // resolves voided_by_name. NO gateway columns; the raw voided_by_user_id is never selected.
    // voided_by_name is null for an active row (no joined app_user) and "First Last" once voided.
    private static final String READ_COLUMNS = """
                pt.payment_transaction_id,
                pt.payment_method::text AS payment_method,
                pt.amount,
                pt.payment_reference,
                pt.created_at,
                pt.voided_at,
                NULLIF(btrim(concat_ws(' ', vu.first_name, vu.last_name)), '') AS voided_by_name
            """;

    private static final String READ_FROM = """
            FROM payment_transaction pt
            LEFT JOIN app_user vu ON vu.user_id = pt.voided_by_user_id
            """;

    // Newest first; payment_transaction_id DESC is a stable tiebreaker when created_at ties (D.6).
    private static final String FIND_BY_ORDER_SQL = "SELECT\n" + READ_COLUMNS + READ_FROM
            + "WHERE pt.order_id = :orderId\n"
            + "ORDER BY pt.created_at DESC, pt.payment_transaction_id DESC\n"
            + "LIMIT :limit OFFSET :offset";

    // One payment scoped to its order (Phase 15D void): used to resolve + authorize a void target. The
    // caller scopes the ORDER to (business, store) first, so matching on (payment id, order id) here is
    // tenant/store-safe and a miss yields PAYMENT_NOT_FOUND without leaking cross-tenant existence.
    private static final String FIND_BY_PAYMENT_AND_ORDER_SQL = "SELECT\n" + READ_COLUMNS + READ_FROM
            + "WHERE pt.payment_transaction_id = :paymentId AND pt.order_id = :orderId";

    // Insert RETURNING is single-table (no join), so voided_by_name is a literal NULL — a freshly
    // inserted payment is always ACTIVE (voided_at null, voided_by_name null). Same ROW_MAPPER applies.
    private static final String INSERT_RETURN_COLUMNS = """
                payment_transaction_id,
                payment_method::text AS payment_method,
                amount,
                payment_reference,
                created_at,
                voided_at,
                NULL::text AS voided_by_name
            """;

    // Gateway columns are intentionally omitted (left NULL on a manual MVP payment).
    private static final String INSERT_SQL = """
            INSERT INTO payment_transaction
                (order_id, payment_method, amount, payment_reference, created_at)
            VALUES
                (:orderId, CAST(:paymentMethod AS payment_method), :amount, :paymentReference, :createdAt)
            RETURNING
            """ + INSERT_RETURN_COLUMNS;

    // Soft void (Phase 15D): stamp voided_at + voided_by_user_id IN PLACE; the original amount / method /
    // reference / created_at stay untouched (no hard delete, no negative row). The WHERE is scoped by
    // (payment id AND order id) — defence-in-depth so a void can never touch a payment outside the
    // already-scoped order even if the caller is wrong — and the "AND voided_at IS NULL" guard makes a
    // double-void safe (a second/concurrent void updates 0 rows -> caller -> 409).
    private static final String VOID_PAYMENT_SQL = """
            UPDATE payment_transaction
            SET voided_at = :voidedAt, voided_by_user_id = :voidedByUserId
            WHERE payment_transaction_id = :paymentId
              AND order_id = :orderId
              AND voided_at IS NULL
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PaymentTransactionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Sum of the order's ACTIVE (non-voided) payments, rounded to money scale 2 ({@code 0.00} when none).
     * A voided payment is excluded (Phase 15D), so voiding a payment drops total_paid and raises balance.
     */
    public BigDecimal sumAmountByOrderId(long orderId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("orderId", orderId);
        BigDecimal total = jdbc.queryForObject(SUM_AMOUNT_BY_ORDER_SQL, params, BigDecimal.class);
        return (total == null ? BigDecimal.ZERO : total).setScale(2, RoundingMode.HALF_UP);
    }

    /** Total payment count for the order, including voided rows (D.6 pagination total_items). */
    public long countByOrderId(long orderId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("orderId", orderId);
        Long count = jdbc.queryForObject(COUNT_BY_ORDER_SQL, params, Long.class);
        return count == null ? 0L : count;
    }

    /** One page of payments for the order, newest first, including voided rows (D.6). */
    public List<PaymentRow> findByOrderId(long orderId, int limit, long offset) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(FIND_BY_ORDER_SQL, params, ROW_MAPPER);
    }

    /**
     * One payment by id, scoped to its order (Phase 15D void target lookup). Empty when the id does not
     * exist or belongs to a different order; the caller has already scoped the order to (business, store),
     * so an empty result maps to PAYMENT_NOT_FOUND with no cross-tenant existence leak.
     */
    public Optional<PaymentRow> findByPaymentIdAndOrderId(long paymentId, long orderId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("paymentId", paymentId)
                .addValue("orderId", orderId);
        return jdbc.query(FIND_BY_PAYMENT_AND_ORDER_SQL, params, ROW_MAPPER).stream().findFirst();
    }

    /**
     * Insert one payment row and return its E.3 columns + void markers (gateway columns left {@code null};
     * a fresh row is always active). A {@code null} {@code paymentReference} persists as SQL NULL.
     * {@code createdAt} is supplied by the caller (wall-clock {@code LocalDateTime.now()}) so rows ordered
     * within one DB transaction stay distinct.
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

    /**
     * Soft-void one payment IN PLACE (Phase 15D): stamp {@code voided_at} + {@code voided_by_user_id}. The
     * {@code WHERE} is scoped by {@code (paymentId AND orderId)} (defence-in-depth — a void can never touch
     * a payment outside the already-scoped order), and the {@code AND voided_at IS NULL} guard makes a
     * double-void safe — returns the number of rows updated, so {@code 0} means the payment was already
     * voided (caller -> 409) and no second invoice version is regenerated.
     *
     * @param voidedByUserId the SESSION actor's user id (NOT the order-bound salesperson)
     * @return 1 when the active payment was voided, 0 when it was already voided (or not in this order)
     */
    public int voidPayment(long paymentId, long orderId, LocalDateTime voidedAt, long voidedByUserId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("paymentId", paymentId)
                .addValue("orderId", orderId)
                .addValue("voidedAt", Timestamp.valueOf(voidedAt))
                .addValue("voidedByUserId", voidedByUserId);
        return jdbc.update(VOID_PAYMENT_SQL, params);
    }

    private static final RowMapper<PaymentRow> ROW_MAPPER = (rs, n) -> {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp voidedAt = rs.getTimestamp("voided_at");
        return new PaymentRow(
                rs.getLong("payment_transaction_id"),
                rs.getString("payment_method"),
                rs.getBigDecimal("amount"),
                rs.getString("payment_reference"),
                createdAt == null ? null : createdAt.toLocalDateTime(),
                voidedAt == null ? null : voidedAt.toLocalDateTime(),
                rs.getString("voided_by_name"));
    };

    /**
     * The E.3 payment columns returned by a list read / insert (no gateway columns) plus the Phase 15D
     * void markers: {@code voidedAt} (null = active) and {@code voidedByName} (null = active; the joined
     * {@code app_user} display name once voided — the raw {@code voided_by_user_id} is never exposed).
     */
    public record PaymentRow(
            long paymentTransactionId,
            String paymentMethod,
            BigDecimal amount,
            String paymentReference,
            LocalDateTime createdAt,
            LocalDateTime voidedAt,
            String voidedByName) {
    }
}
