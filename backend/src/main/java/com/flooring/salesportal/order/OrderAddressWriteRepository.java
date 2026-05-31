package com.flooring.salesportal.order;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Native-SQL write path for the Phase 10C address endpoints:
 * {@code PUT /orders/{orderId}/addresses/installation}, {@code PUT .../addresses/billing}, and
 * {@code POST .../addresses/billing/copy-from-installation}.
 *
 * <p>The read-side {@link OrderAddress} entity is intentionally read-only (no generated id,
 * no setters, timestamps unmapped), so address save uses a focused native upsert rather than
 * JPA — the same deliberate native-SQL pattern as {@link OrderCustomerWriteRepository}. A single
 * {@code INSERT ... ON CONFLICT (order_id, address_type) DO UPDATE} expresses the full-replace
 * upsert atomically and respects {@code uq_order_address_order_type UNIQUE (order_id, address_type)},
 * so a second row for the same {@code (order_id, address_type)} can never be created.
 *
 * <p>Must be invoked from a {@code @Transactional} service method ({@link OrderService}).
 */
@Repository
public class OrderAddressWriteRepository {

    // ON CONFLICT targets uq_order_address_order_type UNIQUE (order_id, address_type):
    //   - no row        -> INSERT (created_at + updated_at both set from :ts)
    //   - existing row  -> UPDATE every address column from EXCLUDED (full replace, omitted
    //                      unit_number already bound as NULL), updated_at refreshed to :ts,
    //                      created_at deliberately NOT in the SET list so it is preserved.
    // address_type is the PostgreSQL `address_type` enum; the bound String is cast via
    // CAST(:addressType AS address_type).
    private static final String UPSERT_SQL = """
            INSERT INTO order_address
                (order_id, address_type, unit_number, street_number, street, suburb,
                 state_code, postcode, created_at, updated_at)
            VALUES
                (:orderId, CAST(:addressType AS address_type), :unitNumber, :streetNumber, :street,
                 :suburb, :stateCode, :postcode, :ts, :ts)
            ON CONFLICT (order_id, address_type) DO UPDATE SET
                unit_number   = EXCLUDED.unit_number,
                street_number = EXCLUDED.street_number,
                street        = EXCLUDED.street,
                suburb        = EXCLUDED.suburb,
                state_code    = EXCLUDED.state_code,
                postcode      = EXCLUDED.postcode,
                updated_at    = EXCLUDED.updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public OrderAddressWriteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert or full-replace the single address row for {@code (orderId, addressType)}.
     * {@code unitNumber} is passed already-trimmed or {@code null}; the required fields are
     * already-trimmed non-blank values. Must run inside the caller's transaction.
     */
    public void upsert(long orderId,
                       String addressType,
                       String unitNumber,
                       String streetNumber,
                       String street,
                       String suburb,
                       String stateCode,
                       String postcode,
                       LocalDateTime timestamp) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("addressType", addressType)
                .addValue("unitNumber", unitNumber)
                .addValue("streetNumber", streetNumber)
                .addValue("street", street)
                .addValue("suburb", suburb)
                .addValue("stateCode", stateCode)
                .addValue("postcode", postcode)
                .addValue("ts", Timestamp.valueOf(timestamp));
        jdbc.update(UPSERT_SQL, params);
    }
}
