package com.flooring.salesportal.order;

import com.flooring.salesportal.order.dto.ProductLineReadDto;
import com.flooring.salesportal.order.financial.LineFinancials;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Native-SQL read/write path for {@code order_product_line} (Chunk 3 D.3–D.6), mirroring the
 * existing native repositories ({@link OrderCustomerWriteRepository}, {@link OrderStatusRepository},
 * {@link com.flooring.salesportal.catalog.AvailableProductRepository}): {@link NamedParameterJdbcTemplate},
 * {@code ::text} enum casts on read, {@code CAST(:x AS pricing_unit)} on write, and an explicit
 * {@code updated_at} (the table has {@code DEFAULT now()} but no update trigger).
 *
 * <p>The row mapper builds {@link ProductLineReadDto}, which deliberately has NO {@code line_cost}
 * field, so reads/inserts/updates can never leak per-line cost (conventions §10 / Chunk 3 F.2);
 * {@code line_cost} is written and summed but never selected into the DTO. Every write/read is
 * scoped by {@code order_id} (and the order itself is pre-scoped to the session's business/store +
 * pessimistically locked by the service), so cross-order access is impossible.
 */
@Repository
public class OrderProductLineRepository {

    // Read projection = exactly the ProductLineRead contract columns (NO line_cost).
    private static final String READ_COLUMNS = """
                order_product_line_id,
                product_id,
                product_code_snapshot,
                product_name_snapshot,
                pricing_unit_snapshot::text AS pricing_unit_snapshot,
                price_snapshot,
                cost_snapshot,
                sqm_per_lm_snapshot,
                quantity_lm,
                quantity_sqm,
                unit_price,
                line_total,
                created_at,
                updated_at
            """;

    private static final String FIND_BY_ORDER_SQL = "SELECT\n" + READ_COLUMNS
            + "FROM order_product_line\n"
            + "WHERE order_id = :orderId\n"
            + "ORDER BY created_at ASC, order_product_line_id ASC";

    private static final String FIND_BY_LINE_SCOPED_SQL = "SELECT\n" + READ_COLUMNS
            + "FROM order_product_line\n"
            + "WHERE order_product_line_id = :lineId AND order_id = :orderId";

    private static final String SUM_FINANCIALS_SQL = """
            SELECT COALESCE(SUM(line_total), 0) AS subtotal,
                   COALESCE(SUM(line_cost), 0)  AS cost
            FROM order_product_line
            WHERE order_id = :orderId
            """;

    private static final String INSERT_SQL = """
            INSERT INTO order_product_line
                (order_id, product_id, product_code_snapshot, product_name_snapshot,
                 pricing_unit_snapshot, price_snapshot, cost_snapshot, sqm_per_lm_snapshot,
                 quantity_lm, quantity_sqm, unit_price, line_total, line_cost, created_at, updated_at)
            VALUES
                (:orderId, :productId, :productCodeSnapshot, :productNameSnapshot,
                 CAST(:pricingUnitSnapshot AS pricing_unit), :priceSnapshot, :costSnapshot, :sqmPerLmSnapshot,
                 :quantityLm, :quantitySqm, :unitPrice, :lineTotal, :lineCost, :ts, :ts)
            RETURNING
            """ + READ_COLUMNS;

    // Snapshot columns (product_id, *_snapshot, sqm_per_lm_snapshot) are deliberately NOT in the SET
    // list — they are immutable for the life of the line. updated_at is set explicitly (no trigger).
    private static final String UPDATE_SQL = """
            UPDATE order_product_line SET
                quantity_lm  = :quantityLm,
                quantity_sqm = :quantitySqm,
                unit_price   = :unitPrice,
                line_total   = :lineTotal,
                line_cost    = :lineCost,
                updated_at   = :ts
            WHERE order_product_line_id = :lineId AND order_id = :orderId
            RETURNING
            """ + READ_COLUMNS;

    private static final String DELETE_SQL = """
            DELETE FROM order_product_line
            WHERE order_product_line_id = :lineId AND order_id = :orderId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public OrderProductLineRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ProductLineReadDto> findByOrderId(long orderId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("orderId", orderId);
        return jdbc.query(FIND_BY_ORDER_SQL, params, ROW_MAPPER);
    }

    /** Scoped line lookup for PATCH recompute; empty when the line is not on this order. */
    public Optional<ProductLineReadDto> findByLineIdAndOrderId(long lineId, long orderId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("lineId", lineId)
                .addValue("orderId", orderId);
        try {
            return Optional.ofNullable(jdbc.queryForObject(FIND_BY_LINE_SCOPED_SQL, params, ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public LineFinancials sumFinancials(long orderId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("orderId", orderId);
        return jdbc.queryForObject(SUM_FINANCIALS_SQL, params, (rs, n) ->
                new LineFinancials(rs.getBigDecimal("subtotal"), rs.getBigDecimal("cost")));
    }

    public ProductLineReadDto insert(long orderId,
                                     long productId,
                                     String productCodeSnapshot,
                                     String productNameSnapshot,
                                     String pricingUnitSnapshot,
                                     BigDecimal priceSnapshot,
                                     BigDecimal costSnapshot,
                                     BigDecimal sqmPerLmSnapshot,
                                     BigDecimal quantityLm,
                                     BigDecimal quantitySqm,
                                     BigDecimal unitPrice,
                                     BigDecimal lineTotal,
                                     BigDecimal lineCost,
                                     LocalDateTime timestamp) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("productId", productId)
                .addValue("productCodeSnapshot", productCodeSnapshot)
                .addValue("productNameSnapshot", productNameSnapshot)
                .addValue("pricingUnitSnapshot", pricingUnitSnapshot)
                .addValue("priceSnapshot", priceSnapshot)
                .addValue("costSnapshot", costSnapshot)
                .addValue("sqmPerLmSnapshot", sqmPerLmSnapshot)
                .addValue("quantityLm", quantityLm)
                .addValue("quantitySqm", quantitySqm)
                .addValue("unitPrice", unitPrice)
                .addValue("lineTotal", lineTotal)
                .addValue("lineCost", lineCost)
                .addValue("ts", Timestamp.valueOf(timestamp));
        return jdbc.queryForObject(INSERT_SQL, params, ROW_MAPPER);
    }

    public ProductLineReadDto update(long lineId,
                                     long orderId,
                                     BigDecimal quantityLm,
                                     BigDecimal quantitySqm,
                                     BigDecimal unitPrice,
                                     BigDecimal lineTotal,
                                     BigDecimal lineCost,
                                     LocalDateTime timestamp) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("lineId", lineId)
                .addValue("orderId", orderId)
                .addValue("quantityLm", quantityLm)
                .addValue("quantitySqm", quantitySqm)
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

    private static final RowMapper<ProductLineReadDto> ROW_MAPPER = (rs, n) -> {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new ProductLineReadDto(
                rs.getLong("order_product_line_id"),
                rs.getLong("product_id"),
                rs.getString("product_code_snapshot"),
                rs.getString("product_name_snapshot"),
                rs.getString("pricing_unit_snapshot"),
                rs.getBigDecimal("price_snapshot"),
                rs.getBigDecimal("cost_snapshot"),
                rs.getBigDecimal("sqm_per_lm_snapshot"),
                rs.getBigDecimal("quantity_lm"),
                rs.getBigDecimal("quantity_sqm"),
                rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("line_total"),
                createdAt == null ? null : createdAt.toLocalDateTime(),
                updatedAt == null ? null : updatedAt.toLocalDateTime());
    };
}
