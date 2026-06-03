package com.flooring.salesportal.order;

import com.flooring.salesportal.order.dto.ChargeLineReadDto;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Native-SQL WRITE path for {@code order_charge_line} (Chunk 3 D.7–D.9), mirroring
 * {@link OrderProductLineRepository}: {@link NamedParameterJdbcTemplate}, an explicit
 * {@code updated_at} (the table has {@code DEFAULT now()} but no update trigger), and order-scoped
 * single-row writes. The read-only browse/sum path stays in {@link OrderChargeLineReadRepository}
 * (write-split convention, as used by {@link OrderCustomerWriteRepository} /
 * {@link SalesOrderFinancialWriteRepository}).
 *
 * <p>The row mapper builds {@link ChargeLineReadDto}, which deliberately has NO {@code line_cost}
 * field, so the {@code RETURNING} read projection never selects {@code line_cost} and per-line cost
 * can never leak (conventions §10 / Chunk 3 F.2); {@code line_cost} is written but never returned.
 * Every write is scoped by {@code order_id} (and the order itself is pre-scoped to the session's
 * business/store + pessimistically locked by the service), so cross-order access is impossible.
 * Charges have no pricing unit and no {@code sqm_per_lm_snapshot} — those are product-only.
 */
@Repository
public class OrderChargeLineWriteRepository {

    // Read projection = exactly the ChargeLineRead contract columns (NO line_cost).
    private static final String READ_COLUMNS = """
                order_charge_line_id,
                charge_id,
                charge_code_snapshot,
                charge_name_snapshot,
                price_snapshot,
                cost_snapshot,
                quantity,
                unit_price,
                line_total,
                created_at,
                updated_at
            """;

    private static final String FIND_BY_LINE_SCOPED_SQL = "SELECT\n" + READ_COLUMNS
            + "FROM order_charge_line\n"
            + "WHERE order_charge_line_id = :lineId AND order_id = :orderId";

    private static final String INSERT_SQL = """
            INSERT INTO order_charge_line
                (order_id, charge_id, charge_code_snapshot, charge_name_snapshot,
                 price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost,
                 created_at, updated_at)
            VALUES
                (:orderId, :chargeId, :chargeCodeSnapshot, :chargeNameSnapshot,
                 :priceSnapshot, :costSnapshot, :quantity, :unitPrice, :lineTotal, :lineCost,
                 :ts, :ts)
            RETURNING
            """ + READ_COLUMNS;

    // Snapshot columns (charge_id, *_snapshot) and created_at are deliberately NOT in the SET list —
    // they are immutable for the life of the line. updated_at is set explicitly (no trigger).
    private static final String UPDATE_SQL = """
            UPDATE order_charge_line SET
                quantity   = :quantity,
                unit_price = :unitPrice,
                line_total = :lineTotal,
                line_cost  = :lineCost,
                updated_at = :ts
            WHERE order_charge_line_id = :lineId AND order_id = :orderId
            RETURNING
            """ + READ_COLUMNS;

    private static final String DELETE_SQL = """
            DELETE FROM order_charge_line
            WHERE order_charge_line_id = :lineId AND order_id = :orderId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public OrderChargeLineWriteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Scoped line lookup for PATCH/DELETE recompute; empty when the line is not on this order. */
    public Optional<ChargeLineReadDto> findByLineIdAndOrderId(long lineId, long orderId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("lineId", lineId)
                .addValue("orderId", orderId);
        try {
            return Optional.ofNullable(jdbc.queryForObject(FIND_BY_LINE_SCOPED_SQL, params, ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public ChargeLineReadDto insert(long orderId,
                                    long chargeId,
                                    String chargeCodeSnapshot,
                                    String chargeNameSnapshot,
                                    BigDecimal priceSnapshot,
                                    BigDecimal costSnapshot,
                                    BigDecimal quantity,
                                    BigDecimal unitPrice,
                                    BigDecimal lineTotal,
                                    BigDecimal lineCost,
                                    LocalDateTime timestamp) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("chargeId", chargeId)
                .addValue("chargeCodeSnapshot", chargeCodeSnapshot)
                .addValue("chargeNameSnapshot", chargeNameSnapshot)
                .addValue("priceSnapshot", priceSnapshot)
                .addValue("costSnapshot", costSnapshot)
                .addValue("quantity", quantity)
                .addValue("unitPrice", unitPrice)
                .addValue("lineTotal", lineTotal)
                .addValue("lineCost", lineCost)
                .addValue("ts", Timestamp.valueOf(timestamp));
        return jdbc.queryForObject(INSERT_SQL, params, ROW_MAPPER);
    }

    public ChargeLineReadDto update(long lineId,
                                    long orderId,
                                    BigDecimal quantity,
                                    BigDecimal unitPrice,
                                    BigDecimal lineTotal,
                                    BigDecimal lineCost,
                                    LocalDateTime timestamp) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("lineId", lineId)
                .addValue("orderId", orderId)
                .addValue("quantity", quantity)
                .addValue("unitPrice", unitPrice)
                .addValue("lineTotal", lineTotal)
                .addValue("lineCost", lineCost)
                .addValue("ts", Timestamp.valueOf(timestamp));
        return jdbc.queryForObject(UPDATE_SQL, params, ROW_MAPPER);
    }

    /** Hard delete scoped to the order. Returns the number of rows removed (0 = line not on order). */
    public int deleteByLineIdAndOrderId(long lineId, long orderId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("lineId", lineId)
                .addValue("orderId", orderId);
        return jdbc.update(DELETE_SQL, params);
    }

    private static final RowMapper<ChargeLineReadDto> ROW_MAPPER = (rs, n) -> {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new ChargeLineReadDto(
                rs.getLong("order_charge_line_id"),
                rs.getLong("charge_id"),
                rs.getString("charge_code_snapshot"),
                rs.getString("charge_name_snapshot"),
                rs.getBigDecimal("price_snapshot"),
                rs.getBigDecimal("cost_snapshot"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("line_total"),
                createdAt == null ? null : createdAt.toLocalDateTime(),
                updatedAt == null ? null : updatedAt.toLocalDateTime());
    };
}
