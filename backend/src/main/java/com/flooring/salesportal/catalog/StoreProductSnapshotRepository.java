package com.flooring.salesportal.catalog;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Native-SQL lookup of a single {@code store_product} by id, scoped to the active store, for
 * snapshotting when a product line is added (Chunk 3 D.4, conventions §10).
 *
 * <p>Distinct from {@link AvailableProductRepository}, which deliberately never selects {@code cost}
 * (cost-free catalog browse). This query DOES select {@code cost} plus {@code is_active} and
 * {@code flooring_type}, because the add-line flow must capture {@code cost_snapshot} (a NOT NULL
 * column) and enforce the active / flooring-type rules. Scoping is by {@code store_id} from the
 * validated session — a product in another store returns empty (→ 404 {@code PRODUCT_NOT_FOUND},
 * no cross-store existence leak). Enum columns are read via {@code ::text}.
 */
@Repository
public class StoreProductSnapshotRepository {

    /** Snapshot inputs read from {@code store_product}; {@code active}/{@code flooringType} drive the 422 rules. */
    public record StoreProductSnapshot(
            String code,
            String name,
            String pricingUnit,
            BigDecimal price,
            BigDecimal cost,
            BigDecimal sqmPerLm,
            boolean active,
            String flooringType
    ) {
    }

    private static final String SELECT_SQL = """
            SELECT
                code,
                name,
                pricing_unit::text  AS pricing_unit,
                price,
                cost,
                sqm_per_lm,
                is_active,
                flooring_type::text AS flooring_type
            FROM store_product
            WHERE product_id = :productId
              AND store_id   = :storeId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public StoreProductSnapshotRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoreProductSnapshot> findByProductIdAndStoreId(long productId, int storeId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("productId", productId)
                .addValue("storeId", storeId);
        try {
            StoreProductSnapshot row = jdbc.queryForObject(SELECT_SQL, params, (rs, n) ->
                    new StoreProductSnapshot(
                            rs.getString("code"),
                            rs.getString("name"),
                            rs.getString("pricing_unit"),
                            rs.getBigDecimal("price"),
                            rs.getBigDecimal("cost"),
                            rs.getBigDecimal("sqm_per_lm"),
                            rs.getBoolean("is_active"),
                            rs.getString("flooring_type")));
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
