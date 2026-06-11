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

    // RETURNING projection = the E.2 invoice_detail columns (NO stored_file_id) plus the Phase 13
    // acceptance/email columns. accepted_signature_file_id is SERVER-INTERNAL: it rides on InvoiceRow
    // only so the services can derive accepted_signature_present / the signature download path (and so
    // later Phase 13 branches can carry it forward); the DTO mapping never exposes the id itself.
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
                created_at,
                accepted_at,
                accepted_customer_name,
                accepted_signature_file_id,
                last_emailed_at
            """;

    // The four Phase 13 acceptance/email columns are written explicitly: Create / Rewrite / payment
    // pass nulls (an unaccepted version), the Accept flow (D.8) passes the captured acceptance values
    // with last_emailed_at = null (stamped post-commit on email success), and the later 13C
    // payment-carry-forward branch passes the previous version's acceptance values.
    private static final String INSERT_INVOICE_SQL = """
            INSERT INTO invoice
                (order_id, version_number, invoice_date, due_date, details_of_sale_snapshot,
                 sale_price_ex_gst, sale_price_inc_gst, total_paid, balance_due,
                 stored_file_id, created_by_user_id,
                 accepted_at, accepted_customer_name, accepted_signature_file_id, last_emailed_at)
            VALUES
                (:orderId, :versionNumber, :invoiceDate, :dueDate, :detailsOfSaleSnapshot,
                 :salePriceExGst, :salePriceIncGst, :totalPaid, :balanceDue,
                 :storedFileId, :createdByUserId,
                 :acceptedAt, :acceptedCustomerName, :acceptedSignatureFileId, :lastEmailedAt)
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

    // Dashboard mirror (Phase 13 §11.1): sales_order.last_emailed_at must always equal the CURRENT
    // invoice's last_emailed_at, including NULL. Called whenever a new invoice version is created.
    private static final String UPDATE_SALES_ORDER_LAST_EMAILED_SQL = """
            UPDATE sales_order
            SET last_emailed_at = :lastEmailedAt
            WHERE order_id = :orderId
            """;

    // In-place delivery stamp (Phase 13 §4: last_emailed_at is a mutable delivery marker, NOT part of
    // the immutable acceptance snapshot). Used by the post-commit email-success update on D.8 Accept
    // and by D.9 Resend — the only writes that touch an existing invoice row.
    private static final String UPDATE_INVOICE_LAST_EMAILED_SQL = """
            UPDATE invoice
            SET last_emailed_at = :lastEmailedAt
            WHERE invoice_id = :invoiceId
            """;

    // Signature stored_file metadata by id (Phase 13 D.10 download / future 13C PDF embed). The id
    // comes from invoice.accepted_signature_file_id on the CURRENT row — deliberately NOT a join on
    // ORDER BY version_number, which could skip a current row whose signature column is NULL and
    // wrongly surface an older version's signature.
    private static final String FIND_STORED_FILE_SQL = """
            SELECT file_name, storage_path, mime_type, file_size
            FROM stored_file
            WHERE stored_file_id = :storedFileId
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

    /**
     * Insert one invoice row and return its E.2 columns (stored_file_id is written but not returned).
     * The four acceptance/email parameters are nullable: Create / Rewrite / payment pass nulls; the
     * Accept flow (D.8) passes {@code acceptedAt} / {@code acceptedCustomerName} /
     * {@code acceptedSignatureFileId} with {@code lastEmailedAt = null} (the email-success timestamp is
     * stamped post-commit via {@link #updateInvoiceLastEmailedAt}).
     */
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
                                    long createdByUserId,
                                    LocalDateTime acceptedAt,
                                    String acceptedCustomerName,
                                    Long acceptedSignatureFileId,
                                    LocalDateTime lastEmailedAt) {
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
                .addValue("createdByUserId", createdByUserId)
                .addValue("acceptedAt", acceptedAt == null ? null : Timestamp.valueOf(acceptedAt))
                .addValue("acceptedCustomerName", acceptedCustomerName)
                .addValue("acceptedSignatureFileId", acceptedSignatureFileId)
                .addValue("lastEmailedAt", lastEmailedAt == null ? null : Timestamp.valueOf(lastEmailedAt));
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

    /**
     * Set the dashboard mirror {@code sales_order.last_emailed_at} to the CURRENT invoice's
     * {@code last_emailed_at} (Phase 13 §11.1 mirror invariant) — including {@code null}, so the
     * dashboard never shows a stale emailed time from a previous version. Callers invoke this with the
     * new version's value whenever a new invoice version is created (Create / Rewrite / payment — and
     * the later Phase 13 Accept branch); the caller already holds the order row {@code FOR UPDATE}
     * inside its transaction. {@code updated_at} is deliberately untouched — this is a system-side
     * delivery marker, not a user edit.
     */
    public void updateSalesOrderLastEmailedAt(long orderId, LocalDateTime lastEmailedAt) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("lastEmailedAt", lastEmailedAt == null ? null : Timestamp.valueOf(lastEmailedAt));
        jdbc.update(UPDATE_SALES_ORDER_LAST_EMAILED_SQL, params);
    }

    /**
     * Stamp {@code invoice.last_emailed_at} IN PLACE on one row (Phase 13 §4: the delivery marker is
     * the only mutable invoice column — the sale snapshot and acceptance fields stay append-only).
     * Used by the post-commit email-success update (D.8 Accept) and by D.9 Resend; the caller updates
     * the {@code sales_order} mirror to the CURRENT invoice's value in the same transaction
     * ({@link #updateSalesOrderLastEmailedAt}) so the §11.1 invariant holds.
     */
    public void updateInvoiceLastEmailedAt(long invoiceId, LocalDateTime lastEmailedAt) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("invoiceId", invoiceId)
                .addValue("lastEmailedAt", lastEmailedAt == null ? null : Timestamp.valueOf(lastEmailedAt));
        jdbc.update(UPDATE_INVOICE_LAST_EMAILED_SQL, params);
    }

    /**
     * One {@code stored_file} row's metadata by id, for binary streaming (Phase 13 D.10: the current
     * invoice's {@code accepted_signature_file_id}). {@code storage_path} is server-internal — used
     * only to read the bytes, never returned in any response.
     */
    public Optional<InvoiceFile> findStoredFileById(long storedFileId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("storedFileId", storedFileId);
        return jdbc.query(FIND_STORED_FILE_SQL, params, FILE_ROW_MAPPER).stream().findFirst();
    }

    private static final RowMapper<InvoiceRow> ROW_MAPPER = (rs, n) -> {
        Date invoiceDate = rs.getDate("invoice_date");
        Date dueDate = rs.getDate("due_date");
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp acceptedAt = rs.getTimestamp("accepted_at");
        Timestamp lastEmailedAt = rs.getTimestamp("last_emailed_at");
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
                createdAt == null ? null : createdAt.toLocalDateTime(),
                acceptedAt == null ? null : acceptedAt.toLocalDateTime(),
                rs.getString("accepted_customer_name"),
                rs.getObject("accepted_signature_file_id", Long.class),
                lastEmailedAt == null ? null : lastEmailedAt.toLocalDateTime());
    };

    private static final RowMapper<InvoiceFile> FILE_ROW_MAPPER = (rs, n) -> new InvoiceFile(
            rs.getString("file_name"),
            rs.getString("storage_path"),
            rs.getString("mime_type"),
            rs.getLong("file_size"));

    /**
     * The E.2 invoice columns returned by an insert / current read (no stored_file_id / storage_path),
     * plus the Phase 13 acceptance/email columns. {@code acceptedSignatureFileId} is SERVER-INTERNAL —
     * the services derive {@code accepted_signature_present} / the signature download path from it and
     * never serialize the id itself. Create / Rewrite / payment inserts leave all four acceptance/email
     * columns NULL; the Accept flow (D.8) writes the three acceptance columns on its appended version
     * (payment carry-forward of acceptance is the later 13C branch).
     */
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
            LocalDateTime createdAt,
            LocalDateTime acceptedAt,
            String acceptedCustomerName,
            Long acceptedSignatureFileId,
            LocalDateTime lastEmailedAt) {
    }

    /**
     * The current invoice's {@code stored_file} metadata for binary streaming (D.4). {@code storagePath}
     * is server-internal (used only to read the bytes off disk) and is NEVER returned to a client.
     */
    public record InvoiceFile(String fileName, String storagePath, String mimeType, long fileSize) {
    }
}
