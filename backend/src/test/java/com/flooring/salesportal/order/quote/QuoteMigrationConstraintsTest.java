package com.flooring.salesportal.order.quote;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 16C PR1 — V16 DB backstop tests (review-fix pass). Exercises the migration constraints
 * directly with JdbcTemplate (these tables are schema-only in PR1; only the DB enforces them):
 * the partial unique indexes (one ISSUED quote_version / one ACTIVE quote_token), the
 * ITEM/ADJUSTMENT shape CHECK on quote_version_line, and the non-negative sort_order CHECKs on both
 * line tables. Self-seeds its own order; runs in the test transaction and rolls back.
 *
 * <p>Phase 16F PR1 adds the V19 END-STATE checks (section at the bottom): both
 * {@code accepted_customer_name} columns are unbounded nullable TEXT, {@code invoice} gains the
 * nullable {@code source_quote_version_id} (non-unique FK + plain index) and {@code terms_snapshot},
 * and the pre-existing V3/V10/V16 columns + constraints are untouched. Schema inspection is scoped to
 * the application schema ({@code current_schema()}), so a scratch schema left by
 * {@link V19MigrationUpgradeTest} can never be counted. The UPGRADE path (V18 data -> V19) is proven
 * separately by {@link V19MigrationUpgradeTest}.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class QuoteMigrationConstraintsTest {

    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private int seq = 60_000;

    private long insertOrder() {
        int s = ++seq;
        String orderNumber = "QMIGT.ZZ9." + String.format("%05d", s % 100_000);
        return jdbcTemplate.queryForObject(
                "INSERT INTO sales_order (business_id, store_id, user_id, order_sequence_number, order_number, "
                        + " flooring_type, order_status, week_number, week_year) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, 'LEAD'::order_status, 1, 2026) RETURNING order_id",
                Long.class, BUSINESS_AUSSIE, STORE_SYD_CBD, USER_LIAM, s, orderNumber);
    }

    private long insertQuoteVersion(long orderId, int versionNumber, String status) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO quote_version (order_id, version_number, status, itemised, quote_total_ex_gst, "
                        + " quote_total_inc_gst, flooring_type_snapshot, created_by_user_id) "
                        + "VALUES (?, ?, ?, TRUE, 100.00, 110.00, 'SOFT', ?) RETURNING quote_version_id",
                Long.class, orderId, versionNumber, status, USER_LIAM);
    }

    private void insertQuoteToken(long versionId, String status, String hash) {
        jdbcTemplate.update(
                "INSERT INTO quote_token (quote_version_id, token_hash, status, expires_at) "
                        + "VALUES (?, ?, ?, now() + interval '7 days')",
                versionId, hash, status);
    }

    private long insertQuoteDraft(long orderId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO quote_draft (order_id, itemised, quote_total_ex_gst, quote_total_inc_gst) "
                        + "VALUES (?, TRUE, 0.00, 0.00) RETURNING quote_draft_id",
                Long.class, orderId);
    }

    // ---- FIX 1: one ISSUED quote_version per order ----

    @Test
    void duplicateIssuedQuoteVersion_sameOrder_rejected() {
        long orderId = insertOrder();
        insertQuoteVersion(orderId, 1, "ISSUED");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertQuoteVersion(orderId, 2, "ISSUED"));
    }

    @Test
    void oneIssuedPlusMultipleNonIssued_sameOrder_allowed() {
        long orderId = insertOrder();
        insertQuoteVersion(orderId, 1, "ISSUED");      // the single live issued version
        insertQuoteVersion(orderId, 2, "SUPERSEDED");
        insertQuoteVersion(orderId, 3, "SUPERSEDED");
        insertQuoteVersion(orderId, 4, "ACCEPTED");
        int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_version WHERE order_id = ?", Integer.class, orderId);
        Assertions.assertEquals(4, count, "one ISSUED alongside several non-ISSUED versions is allowed");
    }

    // ---- FIX 2: one ACTIVE quote_token per version ----

    @Test
    void duplicateActiveToken_sameVersion_rejected() {
        long orderId = insertOrder();
        long versionId = insertQuoteVersion(orderId, 1, "ISSUED");
        insertQuoteToken(versionId, "ACTIVE", "hash-active-1");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertQuoteToken(versionId, "ACTIVE", "hash-active-2"));
    }

    @Test
    void oneActivePlusDeadTokens_sameVersion_allowed() {
        long orderId = insertOrder();
        long versionId = insertQuoteVersion(orderId, 1, "ISSUED");
        insertQuoteToken(versionId, "ACTIVE", "hash-a");
        insertQuoteToken(versionId, "REPLACED", "hash-b");
        insertQuoteToken(versionId, "CONSUMED", "hash-c");
        int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_token WHERE quote_version_id = ?", Integer.class, versionId);
        Assertions.assertEquals(3, count, "dead tokens coexist with one ACTIVE token (kept for messaging)");
    }

    // ---- FIX 3: line-table shape + non-negative sort_order backstops ----

    @Test
    void invalidQuoteVersionLineShape_itemWithNullQuantity_rejected() {
        long orderId = insertOrder();
        long versionId = insertQuoteVersion(orderId, 1, "ISSUED");
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO quote_version_line (quote_version_id, line_type, description, quantity, "
                        + " unit_price_ex_gst, line_total_ex_gst, sort_order) "
                        + "VALUES (?, 'ITEM', 'bad item', NULL, NULL, 100.00, 0)", versionId));
    }

    @Test
    void negativeSortOrder_quoteDraftLine_rejected() {
        long orderId = insertOrder();
        long draftId = insertQuoteDraft(orderId);
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO quote_draft_line (quote_draft_id, line_type, description, quantity, "
                        + " unit_price_ex_gst, line_total_ex_gst, sort_order) "
                        + "VALUES (?, 'ITEM', 'x', 1.00, 100.00, 100.00, -1)", draftId));
    }

    @Test
    void negativeSortOrder_quoteVersionLine_rejected() {
        long orderId = insertOrder();
        long versionId = insertQuoteVersion(orderId, 1, "ISSUED");
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO quote_version_line (quote_version_id, line_type, description, quantity, "
                        + " unit_price_ex_gst, line_total_ex_gst, sort_order) "
                        + "VALUES (?, 'ITEM', 'x', 1.00, 100.00, 100.00, -1)", versionId));
    }

    // ================================================================
    // Phase 16F PR1 — V19 end state (accepted-name widening + invoice quote-source columns)
    // ================================================================

    /**
     * The longest name the V17 issue snapshot can derive (first + ' ' + middle + ' ' + last with the
     * V2 order_customer widths 100/100/100 = 302 chars) — the value V19 exists to store untruncated.
     */
    private static final String MAX_DERIVED_NAME =
            "F".repeat(100) + " " + "M".repeat(100) + " " + "L".repeat(100);

    /** Insert a stored_file row and return its id (invoice.stored_file_id is NOT NULL + unique). */
    private long insertStoredFile(String fileName, String mimeType) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO stored_file (file_name, storage_path, mime_type, file_size) "
                        + "VALUES (?, ?, ?, 1024) RETURNING stored_file_id",
                Long.class, fileName, "/uploads/1/orders/qmigt/" + fileName, mimeType);
    }

    /**
     * Minimal valid invoice row (every V2 NOT NULL + V3 CHECK satisfied: non-blank details, ex > 0,
     * inc >= ex, paid/balance >= 0, own PDF stored_file, created_by = Liam). The V19 columns are left
     * out — exactly like every PR1 application write.
     */
    private long insertInvoice(long orderId, int versionNumber) {
        long pdfFileId = insertStoredFile("qmigt-invoice-" + orderId + "-v" + versionNumber + ".pdf",
                "application/pdf");
        return jdbcTemplate.queryForObject(
                "INSERT INTO invoice (order_id, version_number, details_of_sale_snapshot, sale_price_ex_gst, "
                        + " sale_price_inc_gst, total_paid, balance_due, stored_file_id, created_by_user_id) "
                        + "VALUES (?, ?, 'Migration constraint test sale', 100.00, 110.00, 0.00, 110.00, ?, ?) "
                        + "RETURNING invoice_id",
                Long.class, orderId, versionNumber, pdfFileId, USER_LIAM);
    }

    /** Same minimal invoice, but referencing a quote version through the V19 column (16F PR2 shape). */
    private long insertInvoiceFromQuote(long orderId, int versionNumber, long sourceQuoteVersionId) {
        long pdfFileId = insertStoredFile("qmigt-invoice-" + orderId + "-v" + versionNumber + ".pdf",
                "application/pdf");
        return jdbcTemplate.queryForObject(
                "INSERT INTO invoice (order_id, version_number, details_of_sale_snapshot, sale_price_ex_gst, "
                        + " sale_price_inc_gst, total_paid, balance_due, stored_file_id, created_by_user_id, "
                        + " source_quote_version_id) "
                        + "VALUES (?, ?, 'Migration constraint test sale', 100.00, 110.00, 0.00, 110.00, ?, ?, ?) "
                        + "RETURNING invoice_id",
                Long.class, orderId, versionNumber, pdfFileId, USER_LIAM, sourceQuoteVersionId);
    }

    /** information_schema shape of one column in the APPLICATION schema (exactly one row expected). */
    private Map<String, Object> columnShape(String table, String column) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT data_type::text               AS data_type,
                       is_nullable::text             AS is_nullable,
                       character_maximum_length::int AS max_length,
                       column_default::text          AS column_default
                FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?
                """, table, column);
        Assertions.assertEquals(1, rows.size(),
                table + "." + column + " must exist exactly once in the application schema");
        return rows.get(0);
    }

    private void assertNullableColumn(String table, String column, String dataType) {
        Map<String, Object> shape = columnShape(table, column);
        Assertions.assertEquals(dataType, shape.get("data_type"), table + "." + column + " data type");
        Assertions.assertEquals("YES", shape.get("is_nullable"), table + "." + column + " must be nullable");
    }

    /**
     * pg_catalog view of one named FK on an application-schema table: constrained column(s),
     * referenced table/column(s), and the ON DELETE / ON UPDATE action codes ('a' = NO ACTION).
     */
    private Map<String, Object> foreignKey(String table, String constraintName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT c.contype::text     AS contype,
                       c.confdeltype::text AS on_delete,
                       c.confupdtype::text AS on_update,
                       rt.relname::text    AS referenced_table,
                       (SELECT string_agg(a.attname::text, ',' ORDER BY a.attnum)
                          FROM pg_attribute a
                         WHERE a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey))   AS columns,
                       (SELECT string_agg(a.attname::text, ',' ORDER BY a.attnum)
                          FROM pg_attribute a
                         WHERE a.attrelid = c.confrelid AND a.attnum = ANY (c.confkey)) AS referenced_columns
                FROM pg_constraint c
                JOIN pg_class t     ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                JOIN pg_class rt    ON rt.oid = c.confrelid
                WHERE n.nspname = current_schema() AND t.relname = ? AND c.conname = ?
                """, table, constraintName);
        Assertions.assertEquals(1, rows.size(), constraintName + " must exist exactly once on " + table);
        return rows.get(0);
    }

    /** Every named pg_constraint (PK/FK/UNIQUE/CHECK) on an application-schema table. */
    private Set<String> constraintNames(String table) {
        return new HashSet<>(jdbcTemplate.queryForList("""
                SELECT c.conname::text
                FROM pg_constraint c
                JOIN pg_class t     ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = current_schema() AND t.relname = ?
                """, String.class, table));
    }

    // ---- V19 §1: accepted_customer_name widened to unbounded nullable TEXT on BOTH tables ----

    @Test
    void v19_acceptedCustomerName_isUnboundedNullableText_onQuoteVersionAndInvoice() {
        for (String table : List.of("quote_version", "invoice")) {
            Map<String, Object> shape = columnShape(table, "accepted_customer_name");
            Assertions.assertEquals("text", shape.get("data_type"),
                    table + ".accepted_customer_name must be TEXT after V19 (was VARCHAR(150))");
            Assertions.assertNull(shape.get("max_length"),
                    table + ".accepted_customer_name must carry no length cap");
            Assertions.assertEquals("YES", shape.get("is_nullable"),
                    table + ".accepted_customer_name must stay nullable (unaccepted rows hold NULL)");
        }
    }

    @Test
    void v19_acceptedCustomerName_over300Chars_storedVerbatim_onQuoteVersion() {
        Assertions.assertEquals(302, MAX_DERIVED_NAME.length());
        long orderId = insertOrder();
        long versionId = insertQuoteVersion(orderId, 1, "ACCEPTED");

        int updated = jdbcTemplate.update(
                "UPDATE quote_version SET accepted_customer_name = ? WHERE quote_version_id = ?",
                MAX_DERIVED_NAME, versionId);

        Assertions.assertEquals(1, updated);
        Assertions.assertEquals(MAX_DERIVED_NAME, jdbcTemplate.queryForObject(
                        "SELECT accepted_customer_name FROM quote_version WHERE quote_version_id = ?",
                        String.class, versionId),
                "a 302-char accepted name must round-trip untruncated on quote_version");
    }

    @Test
    void v19_acceptedCustomerName_over300Chars_storedVerbatim_onInvoice() {
        Assertions.assertEquals(302, MAX_DERIVED_NAME.length());
        long orderId = insertOrder();
        long invoiceId = insertInvoice(orderId, 1);

        int updated = jdbcTemplate.update(
                "UPDATE invoice SET accepted_customer_name = ? WHERE invoice_id = ?",
                MAX_DERIVED_NAME, invoiceId);

        Assertions.assertEquals(1, updated);
        Assertions.assertEquals(MAX_DERIVED_NAME, jdbcTemplate.queryForObject(
                        "SELECT accepted_customer_name FROM invoice WHERE invoice_id = ?",
                        String.class, invoiceId),
                "a 302-char accepted name must round-trip untruncated on invoice");
    }

    // ---- V19 §2: invoice.source_quote_version_id + invoice.terms_snapshot (schema only in PR1) ----

    @Test
    void v19_invoiceQuoteSourceColumns_areNullable_withNoDefault() {
        Map<String, Object> source = columnShape("invoice", "source_quote_version_id");
        Assertions.assertEquals("bigint", source.get("data_type"));
        Assertions.assertEquals("YES", source.get("is_nullable"), "source_quote_version_id must be nullable");
        Assertions.assertNull(source.get("column_default"),
                "no default: existing rows and every PR1 invoice write leave source_quote_version_id NULL");

        Map<String, Object> terms = columnShape("invoice", "terms_snapshot");
        Assertions.assertEquals("text", terms.get("data_type"));
        Assertions.assertNull(terms.get("max_length"), "terms_snapshot must carry no length cap");
        Assertions.assertEquals("YES", terms.get("is_nullable"),
                "terms_snapshot must be nullable (NULL = frozen 'no terms' on a Path A invoice)");
        Assertions.assertNull(terms.get("column_default"),
                "no default: existing rows and every PR1 invoice write leave terms_snapshot NULL");
    }

    @Test
    void v19_invoiceWrittenWithoutV19Columns_leavesBothNull() {
        long orderId = insertOrder();
        long invoiceId = insertInvoice(orderId, 1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT source_quote_version_id, terms_snapshot FROM invoice WHERE invoice_id = ?", invoiceId);
        Assertions.assertNull(row.get("source_quote_version_id"));
        Assertions.assertNull(row.get("terms_snapshot"));
    }

    @Test
    void v19_fkInvoiceSourceQuoteVersion_referencesQuoteVersionPk_noCascade_indexNotUnique() {
        Map<String, Object> fk = foreignKey("invoice", "fk_invoice_source_quote_version");
        Assertions.assertEquals("f", fk.get("contype"), "fk_invoice_source_quote_version must be a FOREIGN KEY");
        Assertions.assertEquals("source_quote_version_id", fk.get("columns"));
        Assertions.assertEquals("quote_version", fk.get("referenced_table"));
        Assertions.assertEquals("quote_version_id", fk.get("referenced_columns"));
        // No ON DELETE / ON UPDATE clause (the V3/V16 convention): the default NO ACTION ('a') — never
        // CASCADE / SET NULL, so a quote version an invoice was built from can never silently vanish.
        Assertions.assertEquals("a", fk.get("on_delete"), "no ON DELETE action on fk_invoice_source_quote_version");
        Assertions.assertEquals("a", fk.get("on_update"), "no ON UPDATE action on fk_invoice_source_quote_version");

        // The FK column is covered by a plain (non-unique) btree index…
        String indexDef = jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = current_schema() AND tablename = 'invoice'
                  AND indexname = 'idx_invoice_source_quote_version'
                """, String.class);
        Assertions.assertTrue(indexDef.startsWith("CREATE INDEX "),
                "idx_invoice_source_quote_version must NOT be unique: " + indexDef);
        Assertions.assertTrue(indexDef.endsWith("(source_quote_version_id)"),
                "idx_invoice_source_quote_version must index exactly source_quote_version_id: " + indexDef);

        // …and NO unique index / unique constraint covers source_quote_version_id at all.
        Integer uniqueCovering = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_index i
                JOIN pg_class t     ON t.oid = i.indrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
                WHERE n.nspname = current_schema() AND t.relname = 'invoice'
                  AND i.indisunique AND a.attname = 'source_quote_version_id'
                """, Integer.class);
        Assertions.assertEquals(0, uniqueCovering, "source_quote_version_id must NOT be unique");
    }

    @Test
    void v19_twoInvoiceVersions_mayReferenceTheSameQuoteVersion() {
        // Behavioural non-uniqueness proof: every payment / void version of a Path A invoice carries
        // the same source reference forward (the V10 accepted_signature_file_id precedent).
        long orderId = insertOrder();
        long versionId = insertQuoteVersion(orderId, 1, "ACCEPTED");
        insertInvoiceFromQuote(orderId, 1, versionId);
        insertInvoiceFromQuote(orderId, 2, versionId);

        Integer referencing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoice WHERE source_quote_version_id = ?", Integer.class, versionId);
        Assertions.assertEquals(2, referencing, "two invoice versions may reference the same quote_version");
    }

    @Test
    void v19_invoiceSourceQuoteVersionId_unknownQuoteVersion_rejected() {
        long orderId = insertOrder();
        long missingVersionId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(quote_version_id), 0) + 1000000 FROM quote_version", Long.class);
        // FK violation is the LAST statement (a failed statement aborts the test transaction).
        assertThrows(DataIntegrityViolationException.class,
                () -> insertInvoiceFromQuote(orderId, 1, missingVersionId));
    }

    @Test
    void v19_quoteVersionReferencedByInvoice_cannotBeDeleted() {
        long orderId = insertOrder();
        long versionId = insertQuoteVersion(orderId, 1, "ACCEPTED");
        insertInvoiceFromQuote(orderId, 1, versionId);
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("DELETE FROM quote_version WHERE quote_version_id = ?", versionId));
    }

    // ---- V19 must leave every pre-existing column and constraint untouched ----

    @Test
    void v19_siblingAcceptanceColumns_unchanged() {
        assertNullableColumn("invoice", "accepted_at", "timestamp without time zone");
        assertNullableColumn("invoice", "accepted_signature_file_id", "bigint");
        assertNullableColumn("invoice", "last_emailed_at", "timestamp without time zone");
        assertNullableColumn("quote_version", "accepted_at", "timestamp without time zone");
        assertNullableColumn("quote_version", "accepted_signature_file_id", "bigint");
        assertNullableColumn("quote_version", "issued_pdf_file_id", "bigint");
        assertNullableColumn("quote_version", "signed_pdf_file_id", "bigint");
        assertNullableColumn("quote_version", "terms_snapshot", "text");
        assertNullableColumn("quote_version", "customer_name_snapshot", "text");
    }

    @Test
    void v19_existingInvoiceAndQuoteVersionConstraints_stillPresent() {
        Set<String> invoice = constraintNames("invoice");
        for (String name : List.of(
                "uq_invoice_file", "uq_invoice_order_version",
                "fk_invoice_order", "fk_invoice_file", "fk_invoice_created_by",
                "chk_invoice_version_positive", "chk_invoice_details_not_blank",
                "chk_invoice_sale_price_ex_positive", "chk_invoice_sale_price_inc_valid",
                "chk_invoice_total_paid_gte_zero", "chk_invoice_balance_due_gte_zero",
                "fk_invoice_accepted_signature_file", "fk_invoice_source_quote_version")) {
            Assertions.assertTrue(invoice.contains(name), "invoice must still carry " + name + "; has " + invoice);
        }

        Set<String> quoteVersion = constraintNames("quote_version");
        for (String name : List.of(
                "fk_quote_version_order", "fk_quote_version_created_by",
                "fk_quote_version_accepted_sig", "fk_quote_version_issued_pdf", "fk_quote_version_signed_pdf",
                "uq_quote_version_issued_pdf", "uq_quote_version_signed_pdf", "uq_quote_version_order_version",
                "chk_quote_version_status", "chk_quote_version_flooring", "chk_quote_version_channel",
                "chk_quote_version_number_positive")) {
            Assertions.assertTrue(quoteVersion.contains(name),
                    "quote_version must still carry " + name + "; has " + quoteVersion);
        }
    }

    @Test
    void v19_invoiceAcceptedSignatureFk_stillPresentAndEnforced() {
        Map<String, Object> fk = foreignKey("invoice", "fk_invoice_accepted_signature_file");
        Assertions.assertEquals("f", fk.get("contype"));
        Assertions.assertEquals("accepted_signature_file_id", fk.get("columns"));
        Assertions.assertEquals("stored_file", fk.get("referenced_table"));
        Assertions.assertEquals("stored_file_id", fk.get("referenced_columns"));

        long orderId = insertOrder();
        long invoiceId = insertInvoice(orderId, 1);
        long missingFileId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(stored_file_id), 0) + 1000000 FROM stored_file", Long.class);
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "UPDATE invoice SET accepted_signature_file_id = ? WHERE invoice_id = ?",
                missingFileId, invoiceId));
    }

    @Test
    void v19_quoteVersionStatusCheck_stillEnforced_draftNeverStored() {
        long orderId = insertOrder();
        assertThrows(DataIntegrityViolationException.class, () -> insertQuoteVersion(orderId, 1, "DRAFT"));
    }
}
