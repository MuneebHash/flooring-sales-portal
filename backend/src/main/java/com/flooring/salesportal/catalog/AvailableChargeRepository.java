package com.flooring.salesportal.catalog;

import com.flooring.salesportal.catalog.dto.AvailableChargeResponse;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Native-SQL catalog read for {@code GET /orders/{orderId}/available-charges} (Chunk 3 D.2),
 * mirroring {@link AvailableProductRepository}. {@code store_charge} has no pricing unit and no
 * stock columns, so only the charge identity + price are selected; {@code cost} is never selected
 * (conventions §10). Scoping is store + flooring type + active only, from the validated session
 * and the order.
 */
@Repository
public class AvailableChargeRepository {

    private static final String SELECT_COLUMNS = """
            SELECT
                charge_id,
                code,
                name,
                flooring_type::text AS flooring_type,
                price
            """;

    private static final String FROM = """
            FROM store_charge
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public AvailableChargeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AvailableChargeResponse> findRows(int storeId,
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

    private static final RowMapper<AvailableChargeResponse> ROW_MAPPER = (rs, n) ->
            new AvailableChargeResponse(
                    rs.getLong("charge_id"),
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getString("flooring_type"),
                    rs.getBigDecimal("price"));
}
