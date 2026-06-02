package com.flooring.salesportal.catalog;

import com.flooring.salesportal.catalog.dto.AvailableProductResponse;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Native-SQL catalog read for {@code GET /orders/{orderId}/available-products} (Chunk 3 D.1),
 * mirroring the {@code DashboardOrderRepository} style: {@link NamedParameterJdbcTemplate}, a
 * shared WHERE builder for count + paged rows, {@code ILIKE ... ESCAPE '\'} search, and PostgreSQL
 * enum columns cast {@code ::text}.
 *
 * <p>Scoping is store + flooring type + active only — taken from the validated session and the
 * order, never the client. The {@code cost} column is deliberately not selected (conventions §10).
 */
@Repository
public class AvailableProductRepository {

    private static final String SELECT_COLUMNS = """
            SELECT
                product_id,
                code,
                name,
                flooring_type::text AS flooring_type,
                pricing_unit::text  AS pricing_unit,
                price,
                sqm_per_lm,
                stock_quantity,
                stock_unit::text    AS stock_unit
            """;

    private static final String FROM = """
            FROM store_product
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public AvailableProductRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AvailableProductResponse> findRows(int storeId,
                                                   String flooringType,
                                                   String escapedSearchTerm,
                                                   int pageSize,
                                                   long offset) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS).append(FROM);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendWhere(sql, params, storeId, flooringType, escapedSearchTerm);
        sql.append("ORDER BY code ASC\n");
        sql.append("LIMIT :pageSize OFFSET :offset");
        params.addValue("pageSize", pageSize);
        params.addValue("offset", offset, java.sql.Types.BIGINT);

        return jdbc.query(sql.toString(), params, ROW_MAPPER);
    }

    public long countRows(int storeId, String flooringType, String escapedSearchTerm) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*)\n").append(FROM);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendWhere(sql, params, storeId, flooringType, escapedSearchTerm);

        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count == null ? 0L : count;
    }

    private void appendWhere(StringBuilder sql,
                             MapSqlParameterSource params,
                             int storeId,
                             String flooringType,
                             String escapedSearchTerm) {
        sql.append("WHERE store_id = :storeId\n");
        sql.append("  AND flooring_type::text = :flooringType\n");
        sql.append("  AND is_active = TRUE\n");
        params.addValue("storeId", storeId);
        params.addValue("flooringType", flooringType);

        if (escapedSearchTerm != null) {
            sql.append("  AND (\n");
            sql.append("        code ILIKE :searchTerm ESCAPE '\\'\n");
            sql.append("     OR name ILIKE :searchTerm ESCAPE '\\'\n");
            sql.append("  )\n");
            params.addValue("searchTerm", "%" + escapedSearchTerm + "%");
        }
    }

    private static final RowMapper<AvailableProductResponse> ROW_MAPPER = (rs, n) ->
            new AvailableProductResponse(
                    rs.getLong("product_id"),
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getString("flooring_type"),
                    rs.getString("pricing_unit"),
                    rs.getBigDecimal("price"),
                    rs.getBigDecimal("sqm_per_lm"),
                    rs.getBigDecimal("stock_quantity"),
                    rs.getString("stock_unit"));
}
