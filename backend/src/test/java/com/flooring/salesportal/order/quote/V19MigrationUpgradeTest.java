package com.flooring.salesportal.order.quote;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 16F PR1 — V19 UPGRADE-PATH proof: applying V19 to a POPULATED V18 database loses and
 * rewrites nothing.
 *
 * <p>The application schema of a test run is migrated straight to the latest version at context
 * start, so it can only show V19's END STATE (covered by the V19 section of
 * {@link QuoteMigrationConstraintsTest}). This class proves the UPGRADE: it replays the committed
 * V1–V18 scripts with Flyway into a unique throwaway scratch schema on the same database (the
 * application {@link DataSource}), fills that V18 database with acceptance-era rows, snapshots every
 * table, applies V19 alone, and compares.
 *
 * <p><b>Why the replay stays inside the scratch schema.</b> Flyway's PostgreSQL connection PREPENDS the
 * configured schema to the connection's original {@code search_path} for the migration (and restores
 * it afterwards), so every unqualified V1–V18 statement — {@code CREATE TYPE}, {@code CREATE TABLE},
 * V4's {@code setval(pg_get_serial_sequence('business', ...))}, the V17 backfill — resolves to the
 * scratch schema's objects (each object exists there before anything references it). The schema
 * history table also lives in the scratch schema, so the application's own
 * {@code flyway_schema_history} is never touched (asserted at the end).
 *
 * <p><b>Seeding.</b> Rows are written with SCHEMA-QUALIFIED SQL on ordinary pooled connections (no
 * session {@code search_path} change can leak back into the pool). Enum columns
 * ({@code flooring_type}, {@code order_status}) get UNTYPED literals, which resolve to the target
 * column's scratch-schema enum; a bound varchar parameter or a {@code ::flooring_type} cast would
 * resolve against the application schema's enum instead and fail. The only V4 rows relied on are the
 * tenant rows (business 1 / store 1 / user 1) that V4 recreates inside the scratch schema.
 *
 * <p>Not {@code @Transactional}: Flyway commits each migration on its own pooled connections, so the
 * seeded rows must be committed for the second Flyway run to upgrade them. Everything this test
 * creates lives in the scratch schema and is dropped ({@code DROP SCHEMA ... CASCADE}) in
 * {@code finally}; scratch schemas left by an earlier, killed run are dropped first. Retire this class
 * at the Phase 17 schema-only squash/baseline — the V1–V18 history it replays will no longer exist.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class V19MigrationUpgradeTest {

    private static final String SCRATCH_SCHEMA_PREFIX = "v19up_";
    /** Only schemas created by this class (prefix + 32 lowercase hex chars) are ever dropped. */
    private static final String SCRATCH_SCHEMA_REGEX = "^v19up_[0-9a-f]{32}$";

    // V4/V5/V6 tenant rows, recreated INSIDE the scratch schema by the V4 replay.
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;
    private static final long USER_LIAM = 1L;

    private static final String ORIGINAL_NAME = "Original Name";
    private static final String INV_NAME = "Inv Name";
    /** Exactly the old VARCHAR(150) cap — the longest value a V18 row can hold. */
    private static final String NAME_AT_OLD_CAP = "A".repeat(74) + " " + "B".repeat(75);
    /** Multibyte + typographic apostrophe: V19 must not re-encode, trim or otherwise touch a value. */
    private static final String MULTIBYTE_NAME = "Zo\u00eb O\u2019Brien-Nguy\u1ec5n";
    /** The longest V17-derived name: first(100) + ' ' + middle(100) + ' ' + last(100) = 302 chars. */
    private static final String MAX_DERIVED_NAME =
            "F".repeat(100) + " " + "M".repeat(100) + " " + "L".repeat(100);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void v19_upgradesPopulatedV18Schema_withoutDataLoss() {
        Assertions.assertEquals(150, NAME_AT_OLD_CAP.length());
        Assertions.assertEquals(302, MAX_DERIVED_NAME.length());

        dropLeftoverScratchSchemas();
        long appHistoryRowsBefore = appSchemaHistoryRows();
        String schema = SCRATCH_SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        try {
            // ---- 1. A real V18 database: the committed V1–V18 scripts, applied by Flyway ----
            flywayUpTo(schema, "18").migrate();
            Assertions.assertEquals("18", latestAppliedVersion(schema),
                    "the scratch schema must stop at V18 before the upgrade under test");
            assertNullableColumn(schema, "quote_version", "accepted_customer_name", "character varying", 150);
            assertNullableColumn(schema, "invoice", "accepted_customer_name", "character varying", 150);
            Assertions.assertNull(columnShape(schema, "invoice", "source_quote_version_id"),
                    "invoice.source_quote_version_id must not exist before V19");
            Assertions.assertNull(columnShape(schema, "invoice", "terms_snapshot"),
                    "invoice.terms_snapshot must not exist before V19");

            // ---- 2. Populate it with acceptance-era rows ----
            long orderA = insertScratchOrder(schema, 95_001, "V19UP.ZZ9.95001");
            long orderB = insertScratchOrder(schema, 95_002, "V19UP.ZZ9.95002");
            long qvAccepted = insertScratchAcceptedQuoteVersion(schema, orderA, 1, ORIGINAL_NAME);
            long qvAtOldCap = insertScratchAcceptedQuoteVersion(schema, orderB, 1, NAME_AT_OLD_CAP);
            long qvIssued = insertScratchIssuedQuoteVersion(schema, orderA, 2);
            jdbcTemplate.update(
                    "INSERT INTO " + qualified(schema, "quote_version_line")
                            + " (quote_version_id, line_type, description, quantity, unit_price_ex_gst, "
                            + "  line_total_ex_gst, sort_order) "
                            + "VALUES (?, 'ITEM', 'Carpet', 1.00, 100.00, 100.00, 0)",
                    qvIssued);
            jdbcTemplate.update(
                    "INSERT INTO " + qualified(schema, "quote_token")
                            + " (quote_version_id, token_hash, status, expires_at) "
                            + "VALUES (?, 'v19up-token-hash', 'ACTIVE', now() + interval '7 days')",
                    qvIssued);
            long signatureFileId = insertScratchStoredFile(schema, "signature-v19up.png", "image/png");
            long invAccepted = insertScratchAcceptedInvoice(schema, orderA, 1, INV_NAME, signatureFileId);
            // A carried-forward version sharing the signature file (the V10 non-unique FK precedent).
            long invCarried = insertScratchAcceptedInvoice(schema, orderA, 2, MULTIBYTE_NAME, signatureFileId);

            // The V18 cap is real: a 302-char name is rejected ("value too long", SQLState 22001) on
            // BOTH tables — so the post-V19 write in step 5 genuinely proves the widening.
            assertNameRejectedAsTooLong(schema, "quote_version", "quote_version_id", qvAccepted);
            assertNameRejectedAsTooLong(schema, "invoice", "invoice_id", invAccepted);

            // ---- 3. Snapshot every table over its PRE-V19 columns ----
            Map<String, List<String>> v18Columns = columnsByTable(schema);
            Map<String, Long> countsBefore = rowCounts(schema, v18Columns.keySet());
            Map<String, String> fingerprintsBefore = fingerprints(schema, v18Columns);
            Assertions.assertTrue(countsBefore.get("quote_version") >= 3 && countsBefore.get("invoice") >= 2
                            && countsBefore.get("quote_token") >= 1 && countsBefore.get("quote_version_line") >= 1,
                    "the V18 database under upgrade must be populated: " + countsBefore);
            long quoteVersionFileNode = relfilenode(schema, "quote_version");
            long invoiceFileNode = relfilenode(schema, "invoice");

            // ---- 4. Apply V19 alone ----
            MigrateResult toV19 = flywayUpTo(schema, "19").migrate();
            Assertions.assertEquals(1, toV19.migrationsExecuted, "exactly V19 must be applied by the upgrade");
            Assertions.assertEquals("19", latestAppliedVersion(schema), "the scratch schema must now be at V19");

            // ---- 5a. Both accepted names (and the old-cap, multibyte and NULL values) unchanged ----
            Assertions.assertEquals(ORIGINAL_NAME, acceptedName(schema, "quote_version", "quote_version_id", qvAccepted));
            Assertions.assertEquals(NAME_AT_OLD_CAP, acceptedName(schema, "quote_version", "quote_version_id", qvAtOldCap));
            Assertions.assertNull(acceptedName(schema, "quote_version", "quote_version_id", qvIssued),
                    "an unaccepted version's NULL name must stay NULL");
            Assertions.assertEquals(INV_NAME, acceptedName(schema, "invoice", "invoice_id", invAccepted));
            Assertions.assertEquals(MULTIBYTE_NAME, acceptedName(schema, "invoice", "invoice_id", invCarried));

            // ---- 5b. Both columns are now unbounded TEXT and still nullable ----
            assertNullableColumn(schema, "quote_version", "accepted_customer_name", "text", null);
            assertNullableColumn(schema, "invoice", "accepted_customer_name", "text", null);

            // ---- 5c. The new invoice columns: nullable, no default, NULL on EVERY pre-existing row ----
            assertNullableColumn(schema, "invoice", "source_quote_version_id", "bigint", null);
            assertNullableColumn(schema, "invoice", "terms_snapshot", "text", null);
            Assertions.assertNull(columnShape(schema, "invoice", "source_quote_version_id").get("column_default"),
                    "source_quote_version_id must have no default");
            Assertions.assertNull(columnShape(schema, "invoice", "terms_snapshot").get("column_default"),
                    "terms_snapshot must have no default");
            Assertions.assertEquals(0L, jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM " + qualified(schema, "invoice")
                                    + " WHERE source_quote_version_id IS NOT NULL OR terms_snapshot IS NOT NULL",
                            Long.class),
                    "V19 must not backfill: every existing invoice row keeps both new columns NULL");

            // ---- 5d. Same tables, same row counts, byte-identical pre-existing data everywhere ----
            Assertions.assertEquals(v18Columns.keySet(), columnsByTable(schema).keySet(),
                    "V19 must not add or drop a table");
            Assertions.assertEquals(countsBefore, rowCounts(schema, v18Columns.keySet()),
                    "V19 must not add or remove a row in any table");
            Assertions.assertEquals(fingerprintsBefore, fingerprints(schema, v18Columns),
                    "V19 must not change any pre-existing column value in any table");
            // V19's own claim: VARCHAR -> TEXT is binary-compatible and the two added columns are
            // nullable without a default, so neither table is rewritten (same physical file).
            Assertions.assertEquals(quoteVersionFileNode, relfilenode(schema, "quote_version"),
                    "V19 must not rewrite quote_version");
            Assertions.assertEquals(invoiceFileNode, relfilenode(schema, "invoice"),
                    "V19 must not rewrite invoice");

            // ---- 5e. V19's FK in the upgraded schema targets THAT schema's quote_version; plain index ----
            Map<String, Object> fk = foreignKey(schema, "invoice", "fk_invoice_source_quote_version");
            Assertions.assertEquals("source_quote_version_id", fk.get("columns"));
            Assertions.assertEquals(schema, fk.get("referenced_schema"),
                    "the upgraded FK must reference the upgraded schema's own quote_version");
            Assertions.assertEquals("quote_version", fk.get("referenced_table"));
            Assertions.assertEquals("quote_version_id", fk.get("referenced_columns"));
            String indexDef = jdbcTemplate.queryForObject("""
                    SELECT indexdef FROM pg_indexes
                    WHERE schemaname = ? AND tablename = 'invoice' AND indexname = 'idx_invoice_source_quote_version'
                    """, String.class, schema);
            Assertions.assertTrue(indexDef.startsWith("CREATE INDEX ") && indexDef.endsWith("(source_quote_version_id)"),
                    "idx_invoice_source_quote_version must be a plain, non-unique index: " + indexDef);

            // ---- 5f. The widened columns now hold the 302-char name that V18 rejected, verbatim ----
            Assertions.assertEquals(1,
                    updateAcceptedName(schema, "quote_version", "quote_version_id", qvAccepted, MAX_DERIVED_NAME));
            Assertions.assertEquals(MAX_DERIVED_NAME,
                    acceptedName(schema, "quote_version", "quote_version_id", qvAccepted));
            Assertions.assertEquals(1,
                    updateAcceptedName(schema, "invoice", "invoice_id", invAccepted, MAX_DERIVED_NAME));
            Assertions.assertEquals(MAX_DERIVED_NAME, acceptedName(schema, "invoice", "invoice_id", invAccepted));
        } finally {
            dropScratchSchema(schema);
        }

        Assertions.assertEquals(appHistoryRowsBefore, appSchemaHistoryRows(),
                "the scratch replay must never write the application schema's flyway_schema_history");
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM pg_namespace WHERE nspname = ?", Integer.class, schema),
                "the scratch schema must be dropped");
    }

    // ================================================================
    // Flyway + scratch-schema lifecycle
    // ================================================================

    /** A Flyway for the scratch schema only: creates it, keeps its own history table, never cleans. */
    private Flyway flywayUpTo(String schema, String targetVersion) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .target(targetVersion)
                .cleanDisabled(true)
                .load();
    }

    private String latestAppliedVersion(String schema) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM " + qualified(schema, "flyway_schema_history")
                        + " WHERE success AND type = 'SQL' ORDER BY installed_rank DESC LIMIT 1",
                String.class);
    }

    /** Row count of the APPLICATION schema's history table (unqualified -> default search_path). */
    private long appSchemaHistoryRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Long.class);
    }

    private void dropLeftoverScratchSchemas() {
        List<String> leftovers = jdbcTemplate.queryForList(
                "SELECT nspname::text FROM pg_namespace WHERE nspname ~ ?", String.class, SCRATCH_SCHEMA_REGEX);
        leftovers.forEach(this::dropScratchSchema);
    }

    private void dropScratchSchema(String schema) {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + quoteIdent(schema) + " CASCADE");
    }

    private static String quoteIdent(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String qualified(String schema, String table) {
        return quoteIdent(schema) + "." + quoteIdent(table);
    }

    // ================================================================
    // Scratch-schema seeding (schema-qualified, auto-commit)
    // ================================================================

    private long insertScratchOrder(String schema, int sequence, String orderNumber) {
        // Untyped 'SOFT' / 'LEAD' literals resolve to the scratch schema's enum types (see class doc).
        return jdbcTemplate.queryForObject(
                "INSERT INTO " + qualified(schema, "sales_order")
                        + " (business_id, store_id, user_id, order_sequence_number, order_number, "
                        + "  flooring_type, order_status, week_number, week_year) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT', 'LEAD', 1, 2026) RETURNING order_id",
                Long.class, BUSINESS_AUSSIE, STORE_SYD_CBD, USER_LIAM, sequence, orderNumber);
    }

    private long insertScratchAcceptedQuoteVersion(String schema, long orderId, int versionNumber,
                                                   String acceptedName) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO " + qualified(schema, "quote_version")
                        + " (order_id, version_number, status, itemised, quote_total_ex_gst, quote_total_inc_gst, "
                        + "  flooring_type_snapshot, customer_name_snapshot, accepted_at, accepted_customer_name, "
                        + "  created_by_user_id) "
                        + "VALUES (?, ?, 'ACCEPTED', TRUE, 100.00, 110.00, 'SOFT', ?, now(), ?, ?) "
                        + "RETURNING quote_version_id",
                Long.class, orderId, versionNumber, acceptedName, acceptedName, USER_LIAM);
    }

    private long insertScratchIssuedQuoteVersion(String schema, long orderId, int versionNumber) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO " + qualified(schema, "quote_version")
                        + " (order_id, version_number, status, itemised, quote_total_ex_gst, quote_total_inc_gst, "
                        + "  flooring_type_snapshot, terms_snapshot, details_of_sale_snapshot, created_by_user_id) "
                        + "VALUES (?, ?, 'ISSUED', TRUE, 100.00, 110.00, 'SOFT', 'Frozen quote terms', "
                        + "        'Supply and install carpet', ?) "
                        + "RETURNING quote_version_id",
                Long.class, orderId, versionNumber, USER_LIAM);
    }

    private long insertScratchStoredFile(String schema, String fileName, String mimeType) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO " + qualified(schema, "stored_file")
                        + " (file_name, storage_path, mime_type, file_size) "
                        + "VALUES (?, ?, ?, 1024) RETURNING stored_file_id",
                Long.class, fileName, "/uploads/1/orders/v19up/" + fileName, mimeType);
    }

    /** A valid accepted invoice version (every V2 NOT NULL + V3 CHECK satisfied; own PDF stored_file). */
    private long insertScratchAcceptedInvoice(String schema, long orderId, int versionNumber,
                                              String acceptedName, long signatureFileId) {
        long pdfFileId = insertScratchStoredFile(schema,
                "invoice-v19up-" + orderId + "-v" + versionNumber + ".pdf", "application/pdf");
        return jdbcTemplate.queryForObject(
                "INSERT INTO " + qualified(schema, "invoice")
                        + " (order_id, version_number, details_of_sale_snapshot, sale_price_ex_gst, "
                        + "  sale_price_inc_gst, total_paid, balance_due, stored_file_id, created_by_user_id, "
                        + "  accepted_at, accepted_customer_name, accepted_signature_file_id) "
                        + "VALUES (?, ?, 'Supply and install carpet', 100.00, 110.00, 0.00, 110.00, ?, ?, "
                        + "        now(), ?, ?) "
                        + "RETURNING invoice_id",
                Long.class, orderId, versionNumber, pdfFileId, USER_LIAM, acceptedName, signatureFileId);
    }

    // ================================================================
    // Reads, snapshots and assertions on the scratch schema
    // ================================================================

    private String acceptedName(String schema, String table, String idColumn, long id) {
        return jdbcTemplate.queryForObject(
                "SELECT accepted_customer_name FROM " + qualified(schema, table)
                        + " WHERE " + quoteIdent(idColumn) + " = ?",
                String.class, id);
    }

    private int updateAcceptedName(String schema, String table, String idColumn, long id, String name) {
        return jdbcTemplate.update(
                "UPDATE " + qualified(schema, table) + " SET accepted_customer_name = ? "
                        + "WHERE " + quoteIdent(idColumn) + " = ?",
                name, id);
    }

    private void assertNameRejectedAsTooLong(String schema, String table, String idColumn, long id) {
        DataAccessException rejected = Assertions.assertThrows(DataAccessException.class,
                () -> updateAcceptedName(schema, table, idColumn, id, MAX_DERIVED_NAME),
                "V18 " + table + ".accepted_customer_name is VARCHAR(150): a 302-char name must be rejected");
        Throwable cause = rejected.getMostSpecificCause();
        Assertions.assertEquals("22001", cause instanceof SQLException sql ? sql.getSQLState() : null,
                "expected 'value too long' (22001) from V18 " + table + ", got: " + cause);
    }

    /** information_schema shape of one scratch-schema column, or null when the column does not exist. */
    private Map<String, Object> columnShape(String schema, String table, String column) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT data_type::text               AS data_type,
                       is_nullable::text             AS is_nullable,
                       character_maximum_length::int AS max_length,
                       column_default::text          AS column_default
                FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ? AND column_name = ?
                """, schema, table, column);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void assertNullableColumn(String schema, String table, String column, String dataType,
                                      Integer maxLength) {
        Map<String, Object> shape = columnShape(schema, table, column);
        Assertions.assertNotNull(shape, table + "." + column + " must exist");
        Assertions.assertEquals(dataType, shape.get("data_type"), table + "." + column + " data type");
        Assertions.assertEquals(maxLength, shape.get("max_length"), table + "." + column + " length cap");
        Assertions.assertEquals("YES", shape.get("is_nullable"), table + "." + column + " must be nullable");
    }

    /** Base tables of the scratch schema (minus Flyway's history) -> column names in ordinal order. */
    private Map<String, List<String>> columnsByTable(String schema) {
        Map<String, List<String>> columns = new TreeMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                SELECT c.table_name::text AS table_name, c.column_name::text AS column_name
                FROM information_schema.columns c
                JOIN information_schema.tables t
                  ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                WHERE c.table_schema = ? AND t.table_type = 'BASE TABLE'
                  AND c.table_name <> 'flyway_schema_history'
                ORDER BY c.table_name, c.ordinal_position
                """, schema)) {
            columns.computeIfAbsent((String) row.get("table_name"), table -> new ArrayList<>())
                    .add((String) row.get("column_name"));
        }
        return columns;
    }

    /** The table's physical file node: it changes only when PostgreSQL rewrites the table. */
    private long relfilenode(String schema, String table) {
        return jdbcTemplate.queryForObject("""
                SELECT c.relfilenode
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relname = ? AND c.relkind = 'r'
                """, Long.class, schema, table);
    }

    private Map<String, Long> rowCounts(String schema, Iterable<String> tables) {
        Map<String, Long> counts = new TreeMap<>();
        for (String table : tables) {
            counts.put(table, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + qualified(schema, table), Long.class));
        }
        return counts;
    }

    /**
     * Per table: md5 over every row's text form restricted to the GIVEN columns (the pre-V19 column
     * list), rows sorted by that text. Equal fingerprints before and after V19 = no value changed.
     */
    private Map<String, String> fingerprints(String schema, Map<String, List<String>> columnsByTable) {
        Map<String, String> fingerprints = new TreeMap<>();
        for (Map.Entry<String, List<String>> table : columnsByTable.entrySet()) {
            String rowText = table.getValue().stream()
                    .map(V19MigrationUpgradeTest::quoteIdent)
                    .collect(Collectors.joining(", ", "ROW(", ")::text"));
            fingerprints.put(table.getKey(), jdbcTemplate.queryForObject(
                    "SELECT md5(COALESCE(string_agg(r, E'\\n' ORDER BY r), '')) FROM (SELECT " + rowText
                            + " AS r FROM " + qualified(schema, table.getKey()) + ") x",
                    String.class));
        }
        return fingerprints;
    }

    /** One named FK on a scratch-schema table, with the schema + table + column(s) it references. */
    private Map<String, Object> foreignKey(String schema, String table, String constraintName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT rn.nspname::text AS referenced_schema,
                       rt.relname::text AS referenced_table,
                       (SELECT string_agg(a.attname::text, ',' ORDER BY a.attnum)
                          FROM pg_attribute a
                         WHERE a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey))   AS columns,
                       (SELECT string_agg(a.attname::text, ',' ORDER BY a.attnum)
                          FROM pg_attribute a
                         WHERE a.attrelid = c.confrelid AND a.attnum = ANY (c.confkey)) AS referenced_columns
                FROM pg_constraint c
                JOIN pg_class t      ON t.oid = c.conrelid
                JOIN pg_namespace n  ON n.oid = t.relnamespace
                JOIN pg_class rt     ON rt.oid = c.confrelid
                JOIN pg_namespace rn ON rn.oid = rt.relnamespace
                WHERE c.contype = 'f' AND n.nspname = ? AND t.relname = ? AND c.conname = ?
                """, schema, table, constraintName);
        Assertions.assertEquals(1, rows.size(), constraintName + " must exist exactly once on " + schema + "." + table);
        return rows.get(0);
    }
}
