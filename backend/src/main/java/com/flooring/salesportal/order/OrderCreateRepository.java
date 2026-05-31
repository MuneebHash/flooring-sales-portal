package com.flooring.salesportal.order;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Native-SQL write path for {@code POST /orders} (Phase 10A).
 *
 * <p>The three steps — transaction-scoped advisory lock, {@code MAX(order_sequence_number)+1},
 * and the INSERT — are separate methods but MUST be invoked in order from a single
 * {@code @Transactional} service method ({@link OrderService#createOrder}). Native SQL is used
 * deliberately: {@code pg_advisory_xact_lock} is a Postgres function call, and the PostgreSQL
 * enum columns are cast explicitly ({@code CAST(:x AS flooring_type)}) — the same proven pattern
 * as the existing {@code OrderStatusRepository}.
 */
@Repository
public class OrderCreateRepository {

    // pg_advisory_xact_lock(bigint): held until the surrounding transaction commits/rolls back.
    // Serialises per-business sequence allocation so concurrent creates cannot read the same MAX.
    private static final String LOCK_SQL =
            "SELECT pg_advisory_xact_lock(:businessId)";

    private static final String NEXT_SEQ_SQL = """
            SELECT COALESCE(MAX(order_sequence_number), 0) + 1
            FROM sales_order
            WHERE business_id = :businessId
            """;

    // order_status / supply_only set explicitly to the contract defaults rather than relying on
    // DB column defaults. created_at and updated_at are bound from one caller-supplied instant so
    // they cannot disagree with the ISO week derived from the same instant. Enum value is cast.
    private static final String INSERT_SQL = """
            INSERT INTO sales_order
                (business_id, store_id, user_id, order_sequence_number, order_number,
                 flooring_type, order_status, supply_only, week_number, week_year,
                 created_at, updated_at)
            VALUES
                (:businessId, :storeId, :userId, :seq, :orderNumber,
                 CAST(:flooringType AS flooring_type), CAST('LEAD' AS order_status), FALSE, :weekNumber, :weekYear,
                 :ts, :ts)
            RETURNING order_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public OrderCreateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Acquire the per-business transaction-scoped advisory lock. Must run inside the transaction. */
    public void lockBusinessForSequence(long businessId) {
        // pg_advisory_xact_lock returns void; the RowMapper just forces the statement to execute.
        jdbc.queryForObject(LOCK_SQL,
                new MapSqlParameterSource("businessId", businessId),
                (rs, rowNum) -> Boolean.TRUE);
    }

    /** Next per-business order_sequence_number. Caller must hold the advisory lock first. */
    public int nextSequenceNumber(long businessId) {
        Integer next = jdbc.queryForObject(NEXT_SEQ_SQL,
                new MapSqlParameterSource("businessId", businessId),
                Integer.class);
        return next == null ? 1 : next;
    }

    /** Insert the order shell and return the generated order_id. Must run inside the same transaction. */
    public long insertOrderShell(long businessId,
                                 int storeId,
                                 long userId,
                                 int sequenceNumber,
                                 String orderNumber,
                                 String flooringType,
                                 int weekNumber,
                                 int weekYear,
                                 LocalDateTime timestamp) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("businessId", businessId)
                .addValue("storeId", storeId)
                .addValue("userId", userId)
                .addValue("seq", sequenceNumber)
                .addValue("orderNumber", orderNumber)
                .addValue("flooringType", flooringType)
                .addValue("weekNumber", weekNumber)
                .addValue("weekYear", weekYear)
                .addValue("ts", Timestamp.valueOf(timestamp));
        Long orderId = jdbc.queryForObject(INSERT_SQL, params, Long.class);
        return orderId == null ? 0L : orderId;
    }
}
