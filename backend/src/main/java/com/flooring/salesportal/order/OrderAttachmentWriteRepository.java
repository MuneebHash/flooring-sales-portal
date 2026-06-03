package com.flooring.salesportal.order;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Native-SQL WRITE path for order attachments (Chunk 3 D.15 upload / D.16 delete), mirroring
 * {@link OrderChargeLineWriteRepository}: {@link NamedParameterJdbcTemplate} with {@code RETURNING}
 * so the generated ids / server-set {@code created_at} are read back in one round-trip.
 *
 * <p>An upload always writes a NEW {@code stored_file} row and a NEW {@code order_attachment} row —
 * {@code order_attachment.stored_file_id} is UNIQUE (V3), so each attachment references a distinct
 * file row (Chunk 3 D.15). Delete is order-scoped and removes the {@code order_attachment} row first,
 * then the linked {@code stored_file} row (the FK direction requires that order).
 */
@Repository
public class OrderAttachmentWriteRepository {

    private static final String INSERT_STORED_FILE_SQL = """
            INSERT INTO stored_file (file_name, storage_path, mime_type, file_size)
            VALUES (:fileName, :storagePath, :mimeType, :fileSize)
            RETURNING stored_file_id
            """;

    // attachment_kind is a PostgreSQL enum; cast the bound varchar param explicitly.
    private static final String INSERT_ATTACHMENT_SQL = """
            INSERT INTO order_attachment (order_id, stored_file_id, attachment_kind)
            VALUES (:orderId, :storedFileId, CAST(:attachmentKind AS attachment_kind))
            RETURNING order_attachment_id, created_at
            """;

    private static final String DELETE_ATTACHMENT_SQL = """
            DELETE FROM order_attachment
            WHERE order_attachment_id = :attachmentId AND order_id = :orderId
            """;

    private static final String DELETE_STORED_FILE_SQL = """
            DELETE FROM stored_file
            WHERE stored_file_id = :storedFileId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public OrderAttachmentWriteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertStoredFile(String fileName, String storagePath, String mimeType, long fileSize) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("fileName", fileName)
                .addValue("storagePath", storagePath)
                .addValue("mimeType", mimeType)
                .addValue("fileSize", fileSize);
        Long id = jdbc.queryForObject(INSERT_STORED_FILE_SQL, params, Long.class);
        if (id == null) {
            throw new IllegalStateException("stored_file insert did not return an id");
        }
        return id;
    }

    public AttachmentInsert insertAttachment(long orderId, long storedFileId, String attachmentKind) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("storedFileId", storedFileId)
                .addValue("attachmentKind", attachmentKind);
        return jdbc.queryForObject(INSERT_ATTACHMENT_SQL, params, INSERT_ROW_MAPPER);
    }

    /** Order-scoped hard delete of the attachment row. Returns rows removed (0 = not on this order). */
    public int deleteOrderAttachment(long attachmentId, long orderId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("attachmentId", attachmentId)
                .addValue("orderId", orderId);
        return jdbc.update(DELETE_ATTACHMENT_SQL, params);
    }

    /** Hard delete of the linked file row (1-to-1 with the attachment via the V3 UNIQUE constraint). */
    public int deleteStoredFile(long storedFileId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("storedFileId", storedFileId);
        return jdbc.update(DELETE_STORED_FILE_SQL, params);
    }

    private static final RowMapper<AttachmentInsert> INSERT_ROW_MAPPER = (rs, n) -> {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new AttachmentInsert(
                rs.getLong("order_attachment_id"),
                createdAt == null ? null : createdAt.toLocalDateTime());
    };

    /** The generated id + server-set timestamp returned by the {@code order_attachment} insert. */
    public record AttachmentInsert(long orderAttachmentId, LocalDateTime createdAt) {
    }
}
