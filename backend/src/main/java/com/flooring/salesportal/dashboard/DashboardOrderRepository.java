package com.flooring.salesportal.dashboard;

import com.flooring.salesportal.dashboard.dto.DashboardCustomerResponse;
import com.flooring.salesportal.dashboard.dto.DashboardInstallAddressResponse;
import com.flooring.salesportal.dashboard.dto.DashboardOrderRowResponse;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public class DashboardOrderRepository {

    private static final String FROM_AND_JOINS = """
            FROM sales_order so
            LEFT JOIN order_customer oc ON oc.order_id = so.order_id
            LEFT JOIN order_address oa ON oa.order_id = so.order_id AND oa.address_type = 'INSTALLATION'
            """;

    private static final String SELECT_COLUMNS = """
            SELECT
                so.order_id,
                so.order_number,
                so.order_sequence_number,
                so.flooring_type::text   AS flooring_type,
                so.order_status::text    AS order_status,
                so.last_emailed_at,
                so.week_year,
                so.week_number,
                so.gp,
                oc.order_customer_id,
                oc.first_name            AS customer_first_name,
                oc.last_name             AS customer_last_name,
                oc.email                 AS customer_email,
                oa.order_address_id,
                oa.unit_number,
                oa.street_number,
                oa.street,
                oa.suburb,
                oa.state_code,
                oa.postcode
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public DashboardOrderRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<DashboardOrderRowResponse> findRows(long businessId,
                                                    int storeId,
                                                    String statusFilter,
                                                    Integer weekYear,
                                                    Integer weekNumber,
                                                    String escapedSearchTerm,
                                                    int pageSize,
                                                    long offset) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS).append(FROM_AND_JOINS);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendWhere(sql, params, businessId, storeId, statusFilter, weekYear, weekNumber, escapedSearchTerm);
        sql.append("ORDER BY so.created_at DESC, so.order_id DESC\n");
        sql.append("LIMIT :pageSize OFFSET :offset");
        params.addValue("pageSize", pageSize);
        params.addValue("offset", offset, java.sql.Types.BIGINT);

        return jdbc.query(sql.toString(), params, ROW_MAPPER);
    }

    public long countRows(long businessId,
                          int storeId,
                          String statusFilter,
                          Integer weekYear,
                          Integer weekNumber,
                          String escapedSearchTerm) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*)\n").append(FROM_AND_JOINS);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendWhere(sql, params, businessId, storeId, statusFilter, weekYear, weekNumber, escapedSearchTerm);

        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count == null ? 0L : count;
    }

    private void appendWhere(StringBuilder sql,
                             MapSqlParameterSource params,
                             long businessId,
                             int storeId,
                             String statusFilter,
                             Integer weekYear,
                             Integer weekNumber,
                             String escapedSearchTerm) {
        sql.append("WHERE so.business_id = :businessId\n");
        sql.append("  AND so.store_id    = :storeId\n");
        params.addValue("businessId", businessId);
        params.addValue("storeId", storeId);

        if (statusFilter != null) {
            sql.append("  AND so.order_status::text = :status\n");
            params.addValue("status", statusFilter);
        }
        if (weekYear != null) {
            sql.append("  AND so.week_year = :weekYear\n");
            params.addValue("weekYear", weekYear);
        }
        if (weekNumber != null) {
            sql.append("  AND so.week_number = :weekNumber\n");
            params.addValue("weekNumber", weekNumber);
        }
        if (escapedSearchTerm != null) {
            sql.append("  AND (\n");
            sql.append("        so.order_number   ILIKE :searchTerm ESCAPE '\\'\n");
            sql.append("     OR oc.first_name     ILIKE :searchTerm ESCAPE '\\'\n");
            sql.append("     OR oc.last_name      ILIKE :searchTerm ESCAPE '\\'\n");
            sql.append("     OR oc.email          ILIKE :searchTerm ESCAPE '\\'\n");
            sql.append("     OR oc.company_name   ILIKE :searchTerm ESCAPE '\\'\n");
            sql.append("     OR oc.mobile         ILIKE :searchTerm ESCAPE '\\'\n");
            sql.append("  )\n");
            params.addValue("searchTerm", "%" + escapedSearchTerm + "%");
        }
    }

    private static final RowMapper<DashboardOrderRowResponse> ROW_MAPPER = (rs, n) -> {
        DashboardCustomerResponse customer = null;
        rs.getLong("order_customer_id");
        if (!rs.wasNull()) {
            customer = new DashboardCustomerResponse(
                    rs.getString("customer_first_name"),
                    rs.getString("customer_last_name"),
                    rs.getString("customer_email"));
        }

        DashboardInstallAddressResponse installAddress = null;
        rs.getLong("order_address_id");
        if (!rs.wasNull()) {
            installAddress = new DashboardInstallAddressResponse(
                    rs.getString("unit_number"),
                    rs.getString("street_number"),
                    rs.getString("street"),
                    rs.getString("suburb"),
                    rs.getString("state_code"),
                    rs.getString("postcode"));
        }

        Timestamp emailedAt = rs.getTimestamp("last_emailed_at");

        return new DashboardOrderRowResponse(
                rs.getLong("order_id"),
                rs.getString("order_number"),
                rs.getInt("order_sequence_number"),
                rs.getString("flooring_type"),
                rs.getString("order_status"),
                customer,
                installAddress,
                emailedAt == null ? null : emailedAt.toLocalDateTime(),
                rs.getInt("week_year"),
                rs.getInt("week_number"),
                rs.getBigDecimal("gp"));
    };
}
