package com.flooring.salesportal.order.quote;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Phase 16E-A — native-SQL access for the append-only issued quote layer ({@code quote_version} /
 * {@code quote_version_line} / {@code quote_token}), mirroring {@link com.flooring.salesportal.order.InvoiceRepository}:
 * {@link NamedParameterJdbcTemplate} with {@code RETURNING}, plain row records (no JPA entities —
 * the send flow mixes reads and in-place status/marker updates in one transaction, and a
 * first-level cache could hand back stale entities after a native UPDATE).
 *
 * <p>{@code issued_pdf_file_id} / {@code signed_pdf_file_id} are written but NEVER selected back
 * onto {@link QuoteVersionRow} (streaming goes through the {@code stored_file} join in
 * {@link #findIssuedFileByOrderId}), and {@code token_hash} is written but never selected — so
 * neither internal file ids nor token material can leak into a response. All callers run inside a
 * transaction that holds the order row {@code FOR UPDATE}, which serialises version allocation and
 * token transitions per order (the "one ACTIVE token per order" application invariant; the DB
 * backstops are per-version/per-order partial unique indexes in V16).
 */
@Repository
public class QuoteVersionRepository {

    // The QuoteVersionRow projection: contract §4.3 columns minus the server-internal stored_file
    // ids and acceptance fields (acceptance is 16F; nothing in 16E-A reads it).
    private static final String VERSION_COLUMNS = """
                quote_version_id,
                order_id,
                version_number,
                status,
                itemised,
                quote_total_ex_gst,
                quote_total_inc_gst,
                flooring_type_snapshot,
                terms_snapshot,
                details_of_sale_snapshot,
                sent_channel,
                first_sent_at,
                last_sent_at,
                last_emailed_at,
                viewed_at,
                created_at
            """;

    // At most one row by uq_quote_version_one_issued_per_order (V16 partial unique index).
    private static final String FIND_ISSUED_SQL = "SELECT" + VERSION_COLUMNS
            + "FROM quote_version WHERE order_id = :orderId AND status = 'ISSUED'";

    private static final String FIND_BY_ID_SQL = "SELECT" + VERSION_COLUMNS
            + "FROM quote_version WHERE quote_version_id = :quoteVersionId";

    private static final String MAX_VERSION_SQL =
            "SELECT COALESCE(MAX(version_number), 0) FROM quote_version WHERE order_id = :orderId";

    private static final String EXISTS_ACCEPTED_SQL =
            "SELECT EXISTS(SELECT 1 FROM quote_version WHERE order_id = :orderId AND status = 'ACCEPTED')";

    // Same shape as InvoiceRepository.INSERT_STORED_FILE_SQL — the issued quote PDF is a stored_file
    // row referenced 1-to-1 by quote_version.issued_pdf_file_id (UNIQUE uq_quote_version_issued_pdf).
    private static final String INSERT_STORED_FILE_SQL = """
            INSERT INTO stored_file (file_name, storage_path, mime_type, file_size)
            VALUES (:fileName, :storagePath, :mimeType, :fileSize)
            RETURNING stored_file_id
            """;

    // A new version is always inserted as ISSUED with no delivery markers; sent_channel /
    // first_sent_at / last_sent_at are stamped by stampAttemptMarkers in the same transaction,
    // after the token mint (they mark the send ATTEMPT — locked 16E-A decision). created_at
    // defaults to now(); acceptance columns stay NULL until 16F.
    private static final String INSERT_VERSION_SQL = "\n"
            + """
            INSERT INTO quote_version
                (order_id, version_number, status, itemised, quote_total_ex_gst, quote_total_inc_gst,
                 flooring_type_snapshot, terms_snapshot, details_of_sale_snapshot,
                 issued_pdf_file_id, created_by_user_id)
            VALUES
                (:orderId, :versionNumber, 'ISSUED', :itemised, :quoteTotalExGst, :quoteTotalIncGst,
                 :flooringTypeSnapshot, :termsSnapshot, :detailsOfSaleSnapshot,
                 :issuedPdfFileId, :createdByUserId)
            RETURNING
            """ + VERSION_COLUMNS;

    private static final String INSERT_VERSION_LINE_SQL = """
            INSERT INTO quote_version_line
                (quote_version_id, line_type, description, quantity, unit_price_ex_gst,
                 line_total_ex_gst, sort_order)
            VALUES
                (:quoteVersionId, :lineType, :description, :quantity, :unitPriceExGst,
                 :lineTotalExGst, :sortOrder)
            """;

    // Secondary PK tiebreaker: duplicate sort_order values are contract-legal ("display order", no
    // uniqueness), and Postgres gives UNSPECIFIED relative order for tied keys — without the
    // tiebreaker the positional changed-detection compare could nondeterministically flag an
    // unchanged draft as changed (spurious new version). Version lines are inserted in the draft's
    // (sort_order, id) read order, so ascending PK reproduces that order exactly on ties.
    private static final String FIND_VERSION_LINES_SQL = """
            SELECT line_type, description, quantity, unit_price_ex_gst, line_total_ex_gst, sort_order
            FROM quote_version_line
            WHERE quote_version_id = :quoteVersionId
            ORDER BY sort_order ASC, quote_version_line_id ASC
            """;

    // Lifecycle flips are guarded on the FROM status so they can only ever move an ISSUED row —
    // 0 updated rows means the caller's view of the world was wrong (an invariant bug, not a race:
    // every caller holds the order FOR UPDATE), surfaced via the boolean return.
    private static final String UPDATE_STATUS_FROM_ISSUED_SQL = """
            UPDATE quote_version SET status = :newStatus
            WHERE quote_version_id = :quoteVersionId AND status = 'ISSUED'
            """;

    // Attempt markers (locked 16E-A decision): stamped in the pre-delivery transaction and KEPT on
    // a delivery failure. first_sent_at is write-once (COALESCE); sent_channel / last_sent_at
    // reflect the latest attempt. last_emailed_at is NOT touched here — it is success-only.
    private static final String STAMP_ATTEMPT_MARKERS_SQL = """
            UPDATE quote_version
            SET sent_channel = :sentChannel,
                first_sent_at = COALESCE(first_sent_at, :sentAt),
                last_sent_at = :sentAt
            WHERE quote_version_id = :quoteVersionId
            """;

    // Success-only email stamp (mirrors invoice.last_emailed_at): the ONLY quote_version write that
    // happens outside the issuing transaction, in the short post-delivery follow-up transaction.
    private static final String STAMP_LAST_EMAILED_SQL = """
            UPDATE quote_version SET last_emailed_at = :emailedAt
            WHERE quote_version_id = :quoteVersionId
            """;

    private static final String INSERT_TOKEN_SQL = """
            INSERT INTO quote_token (quote_version_id, token_hash, status, expires_at)
            VALUES (:quoteVersionId, :tokenHash, 'ACTIVE', :expiresAt)
            """;

    // Kill the version's live token with a REASON (REPLACED / SUPERSEDED / CANCELLED). Rows are
    // NEVER deleted (contract §4.5 — dead tokens keep their row + reason for public messaging).
    // 0 updated rows is fine: a version can legitimately have no ACTIVE token (defensive).
    private static final String KILL_ACTIVE_TOKEN_SQL = """
            UPDATE quote_token SET status = :newStatus, dead_at = :deadAt
            WHERE quote_version_id = :quoteVersionId AND status = 'ACTIVE'
            """;

    private static final String FIND_ACTIVE_TOKEN_EXPIRY_SQL = """
            SELECT expires_at FROM quote_token
            WHERE quote_version_id = :quoteVersionId AND status = 'ACTIVE'
            """;

    // The active issued version's stored PDF metadata for streaming/resend. storage_path is
    // server-internal — used only to read the bytes, never returned in any response.
    private static final String FIND_ISSUED_FILE_SQL = """
            SELECT sf.file_name, sf.storage_path, sf.mime_type, sf.file_size
            FROM quote_version v
            JOIN stored_file sf ON sf.stored_file_id = v.issued_pdf_file_id
            WHERE v.order_id = :orderId AND v.status = 'ISSUED'
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public QuoteVersionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The order's single active ISSUED version, or empty (≤1 by the V16 partial unique index). */
    public Optional<QuoteVersionRow> findIssuedByOrderId(long orderId) {
        return jdbc.query(FIND_ISSUED_SQL, new MapSqlParameterSource("orderId", orderId), VERSION_ROW_MAPPER)
                .stream().findFirst();
    }

    /** One version row by id (fresh SELECT — used to re-read after in-place marker stamps). */
    public Optional<QuoteVersionRow> findById(long quoteVersionId) {
        return jdbc.query(FIND_BY_ID_SQL, new MapSqlParameterSource("quoteVersionId", quoteVersionId),
                VERSION_ROW_MAPPER).stream().findFirst();
    }

    /** Highest version_number over ALL of the order's versions (0 when none) — next = max + 1. */
    public int maxVersionNumber(long orderId) {
        Integer max = jdbc.queryForObject(MAX_VERSION_SQL,
                new MapSqlParameterSource("orderId", orderId), Integer.class);
        return max == null ? 0 : max;
    }

    /** True when any ACCEPTED version exists for the order (cancel's defensive 409 branch). */
    public boolean existsAcceptedByOrderId(long orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(EXISTS_ACCEPTED_SQL,
                new MapSqlParameterSource("orderId", orderId), Boolean.class));
    }

    /** Insert the issued PDF's {@code stored_file} row and return the generated id. */
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

    /** Insert one ISSUED {@code quote_version} row from the issue snapshot; returns its row. */
    public QuoteVersionRow insertVersion(long orderId,
                                         int versionNumber,
                                         QuoteIssueSnapshot snapshot,
                                         long issuedPdfFileId,
                                         long createdByUserId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("versionNumber", versionNumber)
                .addValue("itemised", snapshot.itemised())
                .addValue("quoteTotalExGst", snapshot.quoteTotalExGst())
                .addValue("quoteTotalIncGst", snapshot.quoteTotalIncGst())
                .addValue("flooringTypeSnapshot", snapshot.flooringType())
                .addValue("termsSnapshot", snapshot.termsHtml())
                .addValue("detailsOfSaleSnapshot", snapshot.detailsOfSale())
                .addValue("issuedPdfFileId", issuedPdfFileId)
                .addValue("createdByUserId", createdByUserId);
        return jdbc.queryForObject(INSERT_VERSION_SQL, params, VERSION_ROW_MAPPER);
    }

    /** Batch-insert the snapshot's line set (already MODE-SCOPED — empty for a non-itemised issue). */
    public void insertVersionLines(long quoteVersionId, List<QuoteIssueSnapshot.Line> lines) {
        if (lines.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = lines.stream()
                .map(line -> new MapSqlParameterSource()
                        .addValue("quoteVersionId", quoteVersionId)
                        .addValue("lineType", line.lineType())
                        .addValue("description", line.description())
                        .addValue("quantity", line.quantity())
                        .addValue("unitPriceExGst", line.unitPriceExGst())
                        .addValue("lineTotalExGst", line.lineTotalExGst())
                        .addValue("sortOrder", line.sortOrder()))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(INSERT_VERSION_LINE_SQL, batch);
    }

    /** The version's snapshot lines in display order (changed-detection line comparison). */
    public List<QuoteVersionLineRow> findVersionLines(long quoteVersionId) {
        return jdbc.query(FIND_VERSION_LINES_SQL,
                new MapSqlParameterSource("quoteVersionId", quoteVersionId), LINE_ROW_MAPPER);
    }

    /**
     * Flip an ISSUED version to {@code SUPERSEDED} / {@code CANCELLED}. Guarded on the FROM status;
     * throws if no row moved — every caller has just read the ISSUED row under the order lock, so a
     * miss is an invariant bug, never a benign race.
     */
    public void moveIssuedVersionTo(long quoteVersionId, String newStatus) {
        int updated = jdbc.update(UPDATE_STATUS_FROM_ISSUED_SQL, new MapSqlParameterSource()
                .addValue("quoteVersionId", quoteVersionId)
                .addValue("newStatus", newStatus));
        if (updated != 1) {
            throw new IllegalStateException(
                    "quote_version " + quoteVersionId + " was not ISSUED when moving to " + newStatus);
        }
    }

    /** Stamp the pre-delivery attempt markers (kept even when delivery then fails — locked rule). */
    public void stampAttemptMarkers(long quoteVersionId, String sentChannel, LocalDateTime sentAt) {
        jdbc.update(STAMP_ATTEMPT_MARKERS_SQL, new MapSqlParameterSource()
                .addValue("quoteVersionId", quoteVersionId)
                .addValue("sentChannel", sentChannel)
                .addValue("sentAt", Timestamp.valueOf(sentAt)));
    }

    /** Success-only email stamp, in place (never touches sales_order.last_emailed_at — invoice-only mirror). */
    public void stampLastEmailedAt(long quoteVersionId, LocalDateTime emailedAt) {
        jdbc.update(STAMP_LAST_EMAILED_SQL, new MapSqlParameterSource()
                .addValue("quoteVersionId", quoteVersionId)
                .addValue("emailedAt", Timestamp.valueOf(emailedAt)));
    }

    /** Insert a fresh ACTIVE token (hash only — the plaintext is never persisted anywhere). */
    public void insertActiveToken(long quoteVersionId, String tokenHash, LocalDateTime expiresAt) {
        jdbc.update(INSERT_TOKEN_SQL, new MapSqlParameterSource()
                .addValue("quoteVersionId", quoteVersionId)
                .addValue("tokenHash", tokenHash)
                .addValue("expiresAt", Timestamp.valueOf(expiresAt)));
    }

    /** Kill the version's ACTIVE token with a reason status + dead_at (row kept; 0 rows is fine). */
    public void killActiveToken(long quoteVersionId, String newStatus, LocalDateTime deadAt) {
        jdbc.update(KILL_ACTIVE_TOKEN_SQL, new MapSqlParameterSource()
                .addValue("quoteVersionId", quoteVersionId)
                .addValue("newStatus", newStatus)
                .addValue("deadAt", Timestamp.valueOf(deadAt)));
    }

    /** The version's ACTIVE token expiry, or empty (workspace summary; never the hash). */
    public Optional<LocalDateTime> findActiveTokenExpiry(long quoteVersionId) {
        return jdbc.query(FIND_ACTIVE_TOKEN_EXPIRY_SQL,
                        new MapSqlParameterSource("quoteVersionId", quoteVersionId),
                        (rs, n) -> rs.getTimestamp("expires_at").toLocalDateTime())
                .stream().findFirst();
    }

    /** The active issued version's stored PDF metadata for streaming/resend, or empty. */
    public Optional<QuoteFile> findIssuedFileByOrderId(long orderId) {
        return jdbc.query(FIND_ISSUED_FILE_SQL, new MapSqlParameterSource("orderId", orderId), FILE_ROW_MAPPER)
                .stream().findFirst();
    }

    private static final RowMapper<QuoteVersionRow> VERSION_ROW_MAPPER = (rs, n) -> new QuoteVersionRow(
            rs.getLong("quote_version_id"),
            rs.getLong("order_id"),
            rs.getInt("version_number"),
            rs.getString("status"),
            rs.getBoolean("itemised"),
            rs.getBigDecimal("quote_total_ex_gst"),
            rs.getBigDecimal("quote_total_inc_gst"),
            rs.getString("flooring_type_snapshot"),
            rs.getString("terms_snapshot"),
            rs.getString("details_of_sale_snapshot"),
            rs.getString("sent_channel"),
            toLocalDateTime(rs.getTimestamp("first_sent_at")),
            toLocalDateTime(rs.getTimestamp("last_sent_at")),
            toLocalDateTime(rs.getTimestamp("last_emailed_at")),
            toLocalDateTime(rs.getTimestamp("viewed_at")),
            toLocalDateTime(rs.getTimestamp("created_at")));

    private static final RowMapper<QuoteVersionLineRow> LINE_ROW_MAPPER = (rs, n) -> new QuoteVersionLineRow(
            rs.getString("line_type"),
            rs.getString("description"),
            rs.getBigDecimal("quantity"),
            rs.getBigDecimal("unit_price_ex_gst"),
            rs.getBigDecimal("line_total_ex_gst"),
            rs.getInt("sort_order"));

    private static final RowMapper<QuoteFile> FILE_ROW_MAPPER = (rs, n) -> new QuoteFile(
            rs.getString("file_name"),
            rs.getString("storage_path"),
            rs.getString("mime_type"),
            rs.getLong("file_size"));

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    /**
     * One {@code quote_version} row (contract §4.3) minus the server-internal stored_file ids and
     * the 16F acceptance columns. Everything a summary/changed-detection/PDF path needs; nothing
     * that could leak.
     */
    public record QuoteVersionRow(
            long quoteVersionId,
            long orderId,
            int versionNumber,
            String status,
            boolean itemised,
            BigDecimal quoteTotalExGst,
            BigDecimal quoteTotalIncGst,
            String flooringTypeSnapshot,
            String termsSnapshot,
            String detailsOfSaleSnapshot,
            String sentChannel,
            LocalDateTime firstSentAt,
            LocalDateTime lastSentAt,
            LocalDateTime lastEmailedAt,
            LocalDateTime viewedAt,
            LocalDateTime createdAt) {
    }

    /** One immutable snapshot line, in display order (changed-detection / 16F re-render input). */
    public record QuoteVersionLineRow(
            String lineType,
            String description,
            BigDecimal quantity,
            BigDecimal unitPriceExGst,
            BigDecimal lineTotalExGst,
            int sortOrder) {
    }

    /** Stored-PDF metadata for binary streaming; {@code storagePath} is never returned to a client. */
    public record QuoteFile(String fileName, String storagePath, String mimeType, long fileSize) {
    }
}
