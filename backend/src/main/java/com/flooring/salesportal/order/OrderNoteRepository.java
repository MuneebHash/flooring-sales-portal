package com.flooring.salesportal.order;

import com.flooring.salesportal.order.dto.NoteReadDto;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Native-SQL repository for {@code order_note} (Chunk 3 D.12 GET / D.13 POST), mirroring the
 * {@link OrderChargeLineWriteRepository} / {@link com.flooring.salesportal.catalog.AvailableProductRepository}
 * style: {@link NamedParameterJdbcTemplate}, a shared read projection, and {@code RETURNING} on the
 * insert so the created row (incl. the server-set {@code created_at}) is read back in one round-trip.
 *
 * <p>Notes are append-only — there is no update or delete path. Every query is scoped by
 * {@code order_id} (the order itself is pre-scoped to the session's business/store by the service),
 * so cross-order access is impossible. The read projection is exactly the three {@code NoteRead}
 * contract columns; {@code order_id} is scoping-only and is never selected, so it can never leak.
 * Default ordering is {@code created_at DESC} (newest first), with {@code order_note_id DESC} as a
 * deterministic tie-breaker for notes that share a timestamp.
 */
@Repository
public class OrderNoteRepository {

    // Read projection = exactly the NoteRead contract columns (order_id is scoping-only, never selected).
    private static final String READ_COLUMNS = """
                order_note_id,
                note_text,
                created_at
            """;

    private static final String FIND_BY_ORDER_SQL = "SELECT\n" + READ_COLUMNS
            + "FROM order_note\n"
            + "WHERE order_id = :orderId\n"
            + "ORDER BY created_at DESC, order_note_id DESC\n"
            + "LIMIT :limit OFFSET :offset";

    private static final String COUNT_BY_ORDER_SQL =
            "SELECT COUNT(*) FROM order_note WHERE order_id = :orderId";

    private static final String INSERT_SQL = """
            INSERT INTO order_note (order_id, note_text, created_at)
            VALUES (:orderId, :noteText, :ts)
            RETURNING
            """ + READ_COLUMNS;

    private final NamedParameterJdbcTemplate jdbc;

    public OrderNoteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long countByOrderId(long orderId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("orderId", orderId);
        Long count = jdbc.queryForObject(COUNT_BY_ORDER_SQL, params, Long.class);
        return count == null ? 0L : count;
    }

    public List<NoteReadDto> findByOrderId(long orderId, int limit, long offset) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("limit", limit)
                .addValue("offset", offset, Types.BIGINT);
        return jdbc.query(FIND_BY_ORDER_SQL, params, ROW_MAPPER);
    }

    public NoteReadDto insert(long orderId, String noteText, LocalDateTime timestamp) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("noteText", noteText)
                .addValue("ts", Timestamp.valueOf(timestamp));
        return jdbc.queryForObject(INSERT_SQL, params, ROW_MAPPER);
    }

    private static final RowMapper<NoteReadDto> ROW_MAPPER = (rs, n) -> {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new NoteReadDto(
                rs.getLong("order_note_id"),
                rs.getString("note_text"),
                createdAt == null ? null : createdAt.toLocalDateTime());
    };
}
