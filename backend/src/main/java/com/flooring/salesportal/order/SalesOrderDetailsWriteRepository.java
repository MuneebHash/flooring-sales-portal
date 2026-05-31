package com.flooring.salesportal.order;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Native-SQL write path for {@code PUT /orders/{orderId}/details-of-sale} (Phase 10D).
 *
 * <p>Unlike the customer/address write paths this is an {@code UPDATE}, not an upsert: the
 * {@code sales_order} row always already exists (the service has just located and {@code FOR UPDATE}
 * locked it through the scoped lookup), so there is no insert branch. The same deliberate
 * native-SQL pattern as {@link OrderCustomerWriteRepository} / {@link OrderAddressWriteRepository}
 * is used so the {@code lay_date_status} enum column and the explicit {@code updated_at} refresh
 * are written predictably.
 *
 * <p>Only the five in-scope detail columns plus {@code updated_at} are in the SET list.
 * {@code created_at}, the financial columns ({@code sale_price_ex_gst}, {@code total_cost},
 * {@code gp}, {@code gp_percent}), and every product/charge/invoice/payment field are deliberately
 * NOT touched. Full-replace semantics: the service passes already-normalised values, with omitted
 * optionals as {@code null}; nothing is COALESCEd against the existing row.
 *
 * <p>{@code CAST(:layDateStatus AS lay_date_status)} casts the bound text to the PostgreSQL enum.
 * A {@code null} bind is safe: {@code CAST(NULL AS lay_date_status)} yields a typed SQL NULL, so a
 * null lay-date status writes {@code NULL} without a separate branch. {@code proposed_lay_date} is
 * bound as a {@link java.sql.Date} (or {@code null}).
 *
 * <p>Must be invoked from a {@code @Transactional} service method ({@link OrderService}).
 */
@Repository
public class SalesOrderDetailsWriteRepository {

    private static final String UPDATE_SQL = """
            UPDATE sales_order SET
                supply_only       = :supplyOnly,
                plan_numbers      = :planNumbers,
                proposed_lay_date = :proposedLayDate,
                lay_date_status   = CAST(:layDateStatus AS lay_date_status),
                details_of_sale   = :detailsOfSale,
                updated_at        = :ts
            WHERE order_id = :orderId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public SalesOrderDetailsWriteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Full-replace the five in-scope detail columns and refresh {@code updated_at}. All values are
     * passed already-validated/normalised: {@code planNumbers}, {@code proposedLayDate},
     * {@code layDateStatus}, and {@code detailsOfSale} are either a clean value or {@code null}.
     * Must run inside the caller's transaction.
     */
    public void update(long orderId,
                       boolean supplyOnly,
                       String planNumbers,
                       LocalDate proposedLayDate,
                       String layDateStatus,
                       String detailsOfSale,
                       LocalDateTime timestamp) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("supplyOnly", supplyOnly)
                .addValue("planNumbers", planNumbers)
                .addValue("proposedLayDate", proposedLayDate == null ? null : Date.valueOf(proposedLayDate))
                .addValue("layDateStatus", layDateStatus)
                .addValue("detailsOfSale", detailsOfSale)
                .addValue("ts", Timestamp.valueOf(timestamp));
        jdbc.update(UPDATE_SQL, params);
    }
}
