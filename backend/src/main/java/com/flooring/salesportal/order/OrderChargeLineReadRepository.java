package com.flooring.salesportal.order;

import com.flooring.salesportal.order.dto.ChargeLineReadDto;
import com.flooring.salesportal.order.financial.LineFinancials;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * Native-SQL READ path for {@code order_charge_line} (Chunk 3 D.3). Branch 2 implements product-line
 * CRUD only, but charge lines already on an order must (a) appear in the GET /lines response and
 * (b) fold into {@code charge_subtotal} / {@code total_cost} of the financial summary (conventions
 * §12, Chunk 3 E.1). Hence this read-only repository — no charge create/patch/delete here.
 *
 * <p>The row mapper builds {@link ChargeLineReadDto}, which has NO {@code line_cost} field, so per-line
 * cost never leaks; {@code line_cost} is only summed into {@link LineFinancials#cost()}.
 */
@Repository
public class OrderChargeLineReadRepository {

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

    private static final String FIND_BY_ORDER_SQL = "SELECT\n" + READ_COLUMNS
            + "FROM order_charge_line\n"
            + "WHERE order_id = :orderId\n"
            + "ORDER BY created_at ASC, order_charge_line_id ASC";

    private static final String SUM_FINANCIALS_SQL = """
            SELECT COALESCE(SUM(line_total), 0) AS subtotal,
                   COALESCE(SUM(line_cost), 0)  AS cost
            FROM order_charge_line
            WHERE order_id = :orderId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public OrderChargeLineReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ChargeLineReadDto> findByOrderId(long orderId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("orderId", orderId);
        return jdbc.query(FIND_BY_ORDER_SQL, params, ROW_MAPPER);
    }

    public LineFinancials sumFinancials(long orderId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("orderId", orderId);
        return jdbc.queryForObject(SUM_FINANCIALS_SQL, params, (rs, n) ->
                new LineFinancials(rs.getBigDecimal("subtotal"), rs.getBigDecimal("cost")));
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
