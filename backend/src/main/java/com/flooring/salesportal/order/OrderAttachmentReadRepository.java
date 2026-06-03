package com.flooring.salesportal.order;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Native-SQL READ path for order attachments (Chunk 3 D.14 list / D.16 + D.17 single lookup),
 * mirroring {@link OrderNoteRepository} / {@link OrderChargeLineWriteRepository}:
 * {@link NamedParameterJdbcTemplate}, a shared read projection, and order-scoped queries (the order
 * itself is pre-scoped to the session's business/store by the service, so cross-order access is
 * impossible).
 *
 * <p>The projection joins {@code order_attachment} to {@code stored_file} for the contract columns.
 * {@code storage_path} and {@code stored_file_id} are read into {@link AttachmentRow} for internal
 * use (file streaming on D.17, cascade delete on D.16) but the service maps to a response DTO that
 * never exposes them. Default ordering is {@code created_at DESC} (newest first), with
 * {@code order_attachment_id DESC} as a deterministic tie-breaker.
 */
@Repository
public class OrderAttachmentReadRepository {

    private static final String SELECT_COLUMNS = """
                oa.order_attachment_id,
                oa.stored_file_id,
                oa.attachment_kind,
                sf.file_name,
                sf.storage_path,
                sf.mime_type,
                sf.file_size,
                oa.created_at
            """;

    private static final String FROM_JOIN = """
            FROM order_attachment oa
            JOIN stored_file sf ON sf.stored_file_id = oa.stored_file_id
            """;

    private static final String FIND_BY_ORDER_SQL = "SELECT\n" + SELECT_COLUMNS + FROM_JOIN
            + "WHERE oa.order_id = :orderId\n"
            + "ORDER BY oa.created_at DESC, oa.order_attachment_id DESC\n"
            + "LIMIT :limit OFFSET :offset";

    private static final String FIND_BY_ATTACHMENT_SCOPED_SQL = "SELECT\n" + SELECT_COLUMNS + FROM_JOIN
            + "WHERE oa.order_attachment_id = :attachmentId AND oa.order_id = :orderId";

    private static final String COUNT_BY_ORDER_SQL =
            "SELECT COUNT(*) FROM order_attachment WHERE order_id = :orderId";

    private final NamedParameterJdbcTemplate jdbc;

    public OrderAttachmentReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long countByOrderId(long orderId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("orderId", orderId);
        Long count = jdbc.queryForObject(COUNT_BY_ORDER_SQL, params, Long.class);
        return count == null ? 0L : count;
    }

    public List<AttachmentRow> findByOrderId(long orderId, int limit, long offset) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("limit", limit)
                .addValue("offset", offset, Types.BIGINT);
        return jdbc.query(FIND_BY_ORDER_SQL, params, ROW_MAPPER);
    }

    /** Scoped single-attachment lookup for download / delete; empty when not on this order. */
    public Optional<AttachmentRow> findByAttachmentIdAndOrderId(long attachmentId, long orderId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("attachmentId", attachmentId)
                .addValue("orderId", orderId);
        try {
            return Optional.ofNullable(jdbc.queryForObject(FIND_BY_ATTACHMENT_SCOPED_SQL, params, ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private static final RowMapper<AttachmentRow> ROW_MAPPER = (rs, n) -> {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new AttachmentRow(
                rs.getLong("order_attachment_id"),
                rs.getLong("stored_file_id"),
                rs.getString("attachment_kind"),
                rs.getString("file_name"),
                rs.getString("storage_path"),
                rs.getString("mime_type"),
                rs.getLong("file_size"),
                createdAt == null ? null : createdAt.toLocalDateTime());
    };

    /**
     * Internal row carrying every joined column, including the server-internal {@code stored_file_id}
     * and {@code storage_path}. NOT a response type — the service maps it to
     * {@link com.flooring.salesportal.order.dto.AttachmentReadDto}, which omits both internal fields.
     */
    public record AttachmentRow(
            long orderAttachmentId,
            long storedFileId,
            String attachmentKind,
            String fileName,
            String storagePath,
            String mimeType,
            long fileSize,
            LocalDateTime createdAt
    ) {
    }
}
