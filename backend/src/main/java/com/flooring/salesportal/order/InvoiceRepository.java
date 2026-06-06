package com.flooring.salesportal.order;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Native-SQL access for {@code invoice} (Phase 12 Chunk 4), mirroring the existing native repos
 * ({@link OrderAttachmentWriteRepository}, {@link OrderProductLineRepository}):
 * {@link NamedParameterJdbcTemplate} with {@code RETURNING} so the generated id + server-set
 * {@code created_at} are read back in one round-trip.
 *
 * <p>Branch A (D.1 Create) writes: (a) detect whether any invoice already exists for an order,
 * (b) write the backing {@code stored_file} PDF row, and (c) insert the version-1 {@code invoice} row.
 * Branch B (D.3 Read / D.4 File) reads the CURRENT invoice = the {@code MAX(version_number)} row per
 * order (resolved via {@code ORDER BY version_number DESC LIMIT 1}; there is no pointer column). The
 * {@link InvoiceRow} returned by {@link #insertInvoice} / {@link #findCurrentByOrderId} carries only
 * the E.2 contract columns — {@code stored_file_id} is written but never selected back, so it cannot
 * leak into a response. The file lookup ({@link #findCurrentFileByOrderId}) returns the linked
 * {@code stored_file} metadata for binary streaming only (never exposed as JSON).
 */
@Repository
public class InvoiceRepository {

    private static final String EXISTS_BY_ORDER_SQL =
            "SELECT EXISTS(SELECT 1 FROM invoice WHERE order_id = :orderId)";

    // Shared stored_file insert (same shape as OrderAttachmentWriteRepository); the invoice PDF is a
    // stored_file row referenced 1-to-1 by invoice.stored_file_id (UNIQUE uq_invoice_file).
    private static final String INSERT_STORED_FILE_SQL = """
            INSERT INTO stored_file (file_name, storage_path, mime_type, file_size)
            VALUES (:fileName, :storagePath, :mimeType, :fileSize)
            RETURNING stored_file_id
            """;

    // RETURNING projection = exactly the E.2 invoice_detail columns (NO stored_file_id).
    private static final String RETURN_COLUMNS = """
                invoice_id,
                order_id,
                version_number,
                invoice_date,
                due_date,
                details_of_sale_snapshot,
                sale_price_ex_gst,
                sale_price_inc_gst,
                total_paid,
                balance_due,
                created_by_user_id,
                created_at
            """;

    private static final String INSERT_INVOICE_SQL = """
            INSERT INTO invoice
                (order_id, version_number, invoice_date, due_date, details_of_sale_snapshot,
                 sale_price_ex_gst, sale_price_inc_gst, total_paid, balance_due,
                 stored_file_id, created_by_user_id)
            VALUES
                (:orderId, :versionNumber, :invoiceDate, :dueDate, :detailsOfSaleSnapshot,
                 :salePriceExGst, :salePriceIncGst, :totalPaid, :balanceDue,
                 :storedFileId, :createdByUserId)
            RETURNING
            """ + RETURN_COLUMNS;

    // Current invoice = highest version_number for the order. SELECT the same E.2 projection so the
    // shared ROW_MAPPER applies; stored_file_id is NOT selected (never leaks).
    private static final String FIND_CURRENT_SQL = "SELECT\n" + RETURN_COLUMNS
            + "FROM invoice\n"
            + "WHERE order_id = :orderId\n"
            + "ORDER BY version_number DESC\n"
            + "LIMIT 1";

    // Current invoice's linked stored_file metadata, for binary streaming (D.4). storage_path is
    // server-internal — used only to read the bytes, never returned in any response.
    private static final String FIND_CURRENT_FILE_SQL = """
            SELECT sf.file_name, sf.storage_path, sf.mime_type, sf.file_size
            FROM invoice i
            JOIN stored_file sf ON sf.stored_file_id = i.stored_file_id
            WHERE i.order_id = :orderId
            ORDER BY i.version_number DESC
            LIMIT 1
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public InvoiceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** True when at least one invoice (any version) already exists for the order (D.1 -> 409). */
    public boolean existsByOrderId(long orderId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("orderId", orderId);
        return Boolean.TRUE.equals(jdbc.queryForObject(EXISTS_BY_ORDER_SQL, params, Boolean.class));
    }

    /** Insert the invoice PDF's {@code stored_file} row and return the generated id. */
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

    /** Insert one invoice row and return its E.2 columns (stored_file_id is written but not returned). */
    public InvoiceRow insertInvoice(long orderId,
                                    int versionNumber,
                                    LocalDate invoiceDate,
                                    LocalDate dueDate,
                                    String detailsOfSaleSnapshot,
                                    BigDecimal salePriceExGst,
                                    BigDecimal salePriceIncGst,
                                    BigDecimal totalPaid,
                                    BigDecimal balanceDue,
                                    long storedFileId,
                                    long createdByUserId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("versionNumber", versionNumber)
                .addValue("invoiceDate", Date.valueOf(invoiceDate))
                .addValue("dueDate", dueDate == null ? null : Date.valueOf(dueDate))
                .addValue("detailsOfSaleSnapshot", detailsOfSaleSnapshot)
                .addValue("salePriceExGst", salePriceExGst)
                .addValue("salePriceIncGst", salePriceIncGst)
                .addValue("totalPaid", totalPaid)
                .addValue("balanceDue", balanceDue)
                .addValue("storedFileId", storedFileId)
                .addValue("createdByUserId", createdByUserId);
        return jdbc.queryForObject(INSERT_INVOICE_SQL, params, ROW_MAPPER);
    }

    /** The current (highest {@code version_number}) invoice's E.2 columns, or empty if none exist (D.3). */
    public Optional<InvoiceRow> findCurrentByOrderId(long orderId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("orderId", orderId);
        return jdbc.query(FIND_CURRENT_SQL, params, ROW_MAPPER).stream().findFirst();
    }

    /** The current invoice's linked {@code stored_file} metadata for streaming, or empty if none (D.4). */
    public Optional<InvoiceFile> findCurrentFileByOrderId(long orderId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("orderId", orderId);
        return jdbc.query(FIND_CURRENT_FILE_SQL, params, FILE_ROW_MAPPER).stream().findFirst();
    }

    private static final RowMapper<InvoiceRow> ROW_MAPPER = (rs, n) -> {
        Date invoiceDate = rs.getDate("invoice_date");
        Date dueDate = rs.getDate("due_date");
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new InvoiceRow(
                rs.getLong("invoice_id"),
                rs.getLong("order_id"),
                rs.getInt("version_number"),
                invoiceDate == null ? null : invoiceDate.toLocalDate(),
                dueDate == null ? null : dueDate.toLocalDate(),
                rs.getString("details_of_sale_snapshot"),
                rs.getBigDecimal("sale_price_ex_gst"),
                rs.getBigDecimal("sale_price_inc_gst"),
                rs.getBigDecimal("total_paid"),
                rs.getBigDecimal("balance_due"),
                rs.getLong("created_by_user_id"),
                createdAt == null ? null : createdAt.toLocalDateTime());
    };

    private static final RowMapper<InvoiceFile> FILE_ROW_MAPPER = (rs, n) -> new InvoiceFile(
            rs.getString("file_name"),
            rs.getString("storage_path"),
            rs.getString("mime_type"),
            rs.getLong("file_size"));

    /** The E.2 invoice columns returned by an insert / current read (no stored_file_id / storage_path). */
    public record InvoiceRow(
            long invoiceId,
            long orderId,
            int versionNumber,
            LocalDate invoiceDate,
            LocalDate dueDate,
            String detailsOfSaleSnapshot,
            BigDecimal salePriceExGst,
            BigDecimal salePriceIncGst,
            BigDecimal totalPaid,
            BigDecimal balanceDue,
            long createdByUserId,
            LocalDateTime createdAt) {
    }

    /**
     * The current invoice's {@code stored_file} metadata for binary streaming (D.4). {@code storagePath}
     * is server-internal (used only to read the bytes off disk) and is NEVER returned to a client.
     */
    public record InvoiceFile(String fileName, String storagePath, String mimeType, long fileSize) {
    }
}
