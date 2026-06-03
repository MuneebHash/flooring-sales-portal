package com.flooring.salesportal.catalog;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Native-SQL lookup of a single {@code store_charge} by id, scoped to the active store, for
 * snapshotting when a charge line is added (Chunk 3 D.7, conventions §10).
 *
 * <p>Distinct from {@link AvailableChargeRepository}, which deliberately never selects {@code cost}
 * (cost-free catalog browse). This query DOES select {@code cost} plus {@code is_active} and
 * {@code flooring_type}, because the add-charge-line flow must capture {@code cost_snapshot} (a NOT
 * NULL column) and enforce the active / flooring-type rules. Scoping is by {@code store_id} from the
 * validated session — a charge in another store returns empty (→ 404 {@code CHARGE_NOT_FOUND}, no
 * cross-store existence leak). Enum columns are read via {@code ::text}.
 *
 * <p>Charges have no pricing unit and no {@code sqm_per_lm} — those are product-only columns and are
 * intentionally absent here (the LM↔SQM conversion does not apply to charges, Chunk 3 F.3).
 */
@Repository
public class StoreChargeSnapshotRepository {

    /** Snapshot inputs read from {@code store_charge}; {@code active}/{@code flooringType} drive the 422 rules. */
    public record StoreChargeSnapshot(
            String code,
            String name,
            BigDecimal price,
            BigDecimal cost,
            boolean active,
            String flooringType
    ) {
    }

    private static final String SELECT_SQL = """
            SELECT
                code,
                name,
                price,
                cost,
                is_active,
                flooring_type::text AS flooring_type
            FROM store_charge
            WHERE charge_id = :chargeId
              AND store_id  = :storeId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public StoreChargeSnapshotRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoreChargeSnapshot> findByChargeIdAndStoreId(long chargeId, int storeId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("chargeId", chargeId)
                .addValue("storeId", storeId);
        try {
            StoreChargeSnapshot row = jdbc.queryForObject(SELECT_SQL, params, (rs, n) ->
                    new StoreChargeSnapshot(
                            rs.getString("code"),
                            rs.getString("name"),
                            rs.getBigDecimal("price"),
                            rs.getBigDecimal("cost"),
                            rs.getBoolean("is_active"),
                            rs.getString("flooring_type")));
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
