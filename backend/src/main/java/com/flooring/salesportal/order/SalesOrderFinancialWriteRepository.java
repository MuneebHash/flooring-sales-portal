package com.flooring.salesportal.order;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Native-SQL write path that persists the recomputed header financial scalars on {@code sales_order}
 * after a product-line mutation (Chunk 3 D.4–D.6; conventions §12). Only the four derived scalars +
 * {@code updated_at} are in the SET list — {@code price_adjustment_inc_gst} is NOT written by Branch 2
 * (it is owned by the future sale-price override endpoint), and no line/customer/address column is
 * touched. {@code updated_at} is set explicitly (the table has no update trigger).
 *
 * <p>{@code gp_percent} is bound as the already range-checked value: the service passes NULL when the
 * true computed percent would overflow {@code DECIMAL(5,2)} (locked review decision R4) — the
 * response summary still carries the true value, only the persisted column is nulled. A negative
 * {@code sale_price_ex_gst} is allowed while editing an order and is persisted as-is — never clamped
 * or floored; the V3 {@code chk_sales_order_sale_price_gte_zero} CHECK was relaxed in V9 so the real
 * negative value can be stored. (A negative final sale price is blocked only at invoice creation.)
 *
 * <p>Must be invoked from a {@code @Transactional} service method ({@link OrderProductLineService}).
 */
@Repository
public class SalesOrderFinancialWriteRepository {

    private static final String UPDATE_SQL = """
            UPDATE sales_order SET
                sale_price_ex_gst = :salePriceExGst,
                total_cost        = :totalCost,
                gp                = :gp,
                gp_percent        = :gpPercent,
                updated_at        = :ts
            WHERE order_id = :orderId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public SalesOrderFinancialWriteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void updateHeaderFinancials(long orderId,
                                       BigDecimal salePriceExGst,
                                       BigDecimal totalCost,
                                       BigDecimal gp,
                                       BigDecimal gpPercent,
                                       LocalDateTime timestamp) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("salePriceExGst", salePriceExGst)
                .addValue("totalCost", totalCost)
                .addValue("gp", gp)
                .addValue("gpPercent", gpPercent)
                .addValue("ts", Timestamp.valueOf(timestamp));
        jdbc.update(UPDATE_SQL, params);
    }
}
