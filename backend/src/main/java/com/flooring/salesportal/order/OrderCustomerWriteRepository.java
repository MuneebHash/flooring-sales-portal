package com.flooring.salesportal.order;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Native-SQL write path for {@code PUT /orders/{orderId}/customer} (Phase 10B).
 *
 * <p>The read-side {@link OrderCustomer} entity is intentionally read-only (no generated id,
 * no setters, timestamps unmapped), so customer save uses a focused native upsert rather than
 * JPA — the same deliberate native-SQL pattern as {@link OrderCreateRepository} and the status
 * write path. A single {@code INSERT ... ON CONFLICT (order_id) DO UPDATE} expresses the
 * full-replace upsert atomically and respects {@code uq_order_customer_order}, so a second row
 * for the same {@code order_id} can never be created.
 *
 * <p>Must be invoked from a {@code @Transactional} service method ({@link OrderService#saveCustomer}).
 */
@Repository
public class OrderCustomerWriteRepository {

    // ON CONFLICT targets uq_order_customer_order UNIQUE (order_id):
    //   - no row        -> INSERT (created_at + updated_at both set from :ts)
    //   - existing row  -> UPDATE every customer column from EXCLUDED (full replace, omitted
    //                      optionals already bound as NULL), updated_at refreshed to :ts,
    //                      created_at deliberately NOT in the SET list so it is preserved.
    private static final String UPSERT_SQL = """
            INSERT INTO order_customer
                (order_id, first_name, middle_name, last_name, email, mobile,
                 home_phone, work_phone, company_name, created_at, updated_at)
            VALUES
                (:orderId, :firstName, :middleName, :lastName, :email, :mobile,
                 :homePhone, :workPhone, :companyName, :ts, :ts)
            ON CONFLICT (order_id) DO UPDATE SET
                first_name   = EXCLUDED.first_name,
                middle_name  = EXCLUDED.middle_name,
                last_name    = EXCLUDED.last_name,
                email        = EXCLUDED.email,
                mobile       = EXCLUDED.mobile,
                home_phone   = EXCLUDED.home_phone,
                work_phone   = EXCLUDED.work_phone,
                company_name = EXCLUDED.company_name,
                updated_at   = EXCLUDED.updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public OrderCustomerWriteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert or full-replace the single customer row for {@code orderId}. Optional values are
     * passed already-trimmed or {@code null}. Must run inside the caller's transaction.
     */
    public void upsert(long orderId,
                       String firstName,
                       String middleName,
                       String lastName,
                       String email,
                       String mobile,
                       String homePhone,
                       String workPhone,
                       String companyName,
                       LocalDateTime timestamp) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("firstName", firstName)
                .addValue("middleName", middleName)
                .addValue("lastName", lastName)
                .addValue("email", email)
                .addValue("mobile", mobile)
                .addValue("homePhone", homePhone)
                .addValue("workPhone", workPhone)
                .addValue("companyName", companyName)
                .addValue("ts", Timestamp.valueOf(timestamp));
        jdbc.update(UPSERT_SQL, params);
    }
}
