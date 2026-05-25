package com.flooring.salesportal.order;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class OrderStatusRepository {

    record CurrentOrderSnapshot(
            long orderId,
            String orderNumber,
            String orderStatus,
            LocalDateTime updatedAt
    ) {
    }

    // Row-locks the order so the optional follow-up UPDATE in the same transaction
    // cannot race with a concurrent status patch on the same row.
    private static final String SELECT_FOR_UPDATE_SQL = """
            SELECT order_id,
                   order_number,
                   order_status::text AS order_status,
                   updated_at
            FROM sales_order
            WHERE order_id    = :orderId
              AND business_id = :businessId
              AND store_id    = :storeId
            FOR UPDATE
            """;

    // sales_order has no updated_at trigger; the patch must set it explicitly.
    // The order_status column is a PostgreSQL enum, so the bound string is cast.
    private static final String UPDATE_STATUS_SQL = """
            UPDATE sales_order
            SET order_status = CAST(:newStatus AS order_status),
                updated_at   = now()
            WHERE order_id    = :orderId
              AND business_id = :businessId
              AND store_id    = :storeId
            RETURNING updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public OrderStatusRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CurrentOrderSnapshot> findForUpdate(long orderId, long businessId, int storeId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("businessId", businessId)
                .addValue("storeId", storeId);
        try {
            CurrentOrderSnapshot row = jdbc.queryForObject(SELECT_FOR_UPDATE_SQL, params, (rs, n) -> {
                Timestamp ts = rs.getTimestamp("updated_at");
                return new CurrentOrderSnapshot(
                        rs.getLong("order_id"),
                        rs.getString("order_number"),
                        rs.getString("order_status"),
                        ts == null ? null : ts.toLocalDateTime());
            });
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public LocalDateTime updateStatus(long orderId, long businessId, int storeId, String newStatus) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("businessId", businessId)
                .addValue("storeId", storeId)
                .addValue("newStatus", newStatus);
        Timestamp ts = jdbc.queryForObject(UPDATE_STATUS_SQL, params, Timestamp.class);
        return ts == null ? null : ts.toLocalDateTime();
    }
}
