package com.flooring.salesportal.order;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Phase 16G PR 2 (#125) — V20 UPGRADE-PATH proof: applying V20 to a POPULATED V19 database freezes the
 * Invoice To words onto EVERY existing invoice version, from that version's own order, and changes
 * nothing else.
 *
 * <p>The application schema of a test run is migrated straight to the latest version at context start,
 * so its invoice rows are all written by the PR 2 code and can never show the backfill. This class
 * proves the UPGRADE: it replays the committed V1–V19 scripts with Flyway into a unique throwaway
 * scratch schema on the same database (the application {@link DataSource}), fills that V19 database
 * with pre-PR-2 invoice history, snapshots every table, applies V20 alone, and compares. It follows
 * {@link com.flooring.salesportal.order.quote.V19MigrationUpgradeTest} for the replay and cleanup.
 *
 * <p><b>What V20 must do</b> (16G contract section 4.1 and the V20 script header): add
 * {@code invoice.customer_name_snapshot}, {@code customer_address_line1_snapshot} and
 * {@code customer_address_line2_snapshot} as nullable {@code TEXT} with no default, and fill them on
 * every existing row, older versions included, from the order's saved {@code order_customer} row and its
 * BILLING {@code order_address} row with the V17 derivation: the trimmed, non-blank first, middle and
 * last names joined by single spaces; {@code [unit/]street_number street};
 * {@code suburb state_code postcode}. A missing row gives NULL, never {@code ''}. Terms are NOT
 * backfilled: {@code terms_snapshot} stays NULL where it was NULL and byte-identical where it was set.
 *
 * <p><b>The fixtures are built to catch a wrong V20.</b> Order A has five versions: unsigned without
 * terms, unsigned with odd populated terms, signed in store, Path A from an accepted quote, and a payment
 * version carried from it. Its INSTALLATION address is written BEFORE its BILLING address (lower key), its
 * names carry stray spaces, and its accepted quote holds its own V17 snapshot and accepted name that
 * differ from the saved customer. The business has per-type and legacy terms set. So a V20 that filled
 * only the current version, took the first or the installation address, skipped the trim or the
 * blank-part rule, wrote {@code ''}, copied the quote's snapshot or the accepted name, or backfilled,
 * trimmed or cleared terms fails here. Orders B to E cover a whitespace-only middle name with no unit,
 * a customer with no BILLING row, a BILLING row with no customer, and neither. Every expected value is a
 * LITERAL in this class, never computed with V20's SQL. On the pre-PR-2 tree (no V20 script) the
 * target-20 Flyway run applies nothing (Flyway's {@code failOnMissingTarget} is off by default), so the
 * exactly-one-migration assertion fails, and the columns and values the later assertions read do not
 * exist.
 *
 * <p><b>Why the replay stays inside the scratch schema.</b> Flyway's PostgreSQL connection PREPENDS the
 * configured schema to the connection's {@code search_path} for the migration (and restores it
 * afterwards), so every unqualified V1–V20 statement, the V20 backfill included, resolves to the scratch
 * schema's objects. Its history table also lives there, so the application's own
 * {@code flyway_schema_history} is never touched (asserted at the end).
 *
 * <p><b>Seeding.</b> Rows are written with SCHEMA-QUALIFIED SQL on ordinary pooled connections (no
 * session {@code search_path} change can leak back into the pool). Enum columns ({@code flooring_type},
 * {@code order_status}, {@code lay_date_status}, {@code address_type}) get UNTYPED literals, which
 * resolve to the target column's scratch-schema enum; a bound varchar parameter or a cast would resolve
 * against the application schema's enum instead and fail. The fixtures are self-seeded: the class
 * inserts its own business (its own slug), store and salesperson into the scratch schema and uses their
 * generated ids for every row it writes. No {@code user_store_access} row is written, because no foreign
 * key on these rows needs one. The V4 replay still creates its demo rows inside the scratch schema; no
 * assertion references them (they only ride along in the whole-table counts and fingerprints).
 *
 * <p>Not {@code @Transactional}: Flyway commits each migration on its own pooled connections, so the
 * seeded rows must be committed for the second Flyway run to upgrade them. Everything this test creates
 * lives in the scratch schema and is dropped ({@code DROP SCHEMA ... CASCADE}) in {@code finally};
 * scratch schemas left by an earlier, killed run of this class are dropped first, matched by this
 * class's own prefix ({@code v20up_} plus 32 hex characters), so another class's scratch schema is
 * never touched. No HTTP layer is involved: the application only reads its own schema, so the upgraded
 * rows are checked with SQL. Retire this class at the Phase 17 schema-only squash/baseline: the V1–V19
 * history it replays will no longer exist, and V20 joins that squash.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class V20MigrationUpgradeTest {

    private static final String SCRATCH_SCHEMA_PREFIX = "v20up_";
    /** Only schemas created by this class (prefix + 32 lowercase hex chars) are ever dropped. */
    private static final String SCRATCH_SCHEMA_REGEX = "^v20up_[0-9a-f]{32}$";

    /** The three V20 columns, in the order V20 adds them. */
    private static final List<String> V20_COLUMNS = List.of(
            "customer_name_snapshot", "customer_address_line1_snapshot", "customer_address_line2_snapshot");

    // ---- Expected backfill per order, as literals: [name, billing line 1, billing line 2] ----

    /** A: '  Jordan ' / 'Avery' / 'Wilson ' and BILLING unit 4; never its INSTALLATION address. */
    private static final List<String> WORDS_A =
            Arrays.asList("Jordan Avery Wilson", "4/12 Oxford Street", "Paddington NSW 2021");
    /**
     * B: the whitespace-only middle name is skipped (single spaces); no unit, so no "/"; the padded street
     * number and postcode are trimmed by the whole-line BTRIM.
     */
    private static final List<String> WORDS_B =
            Arrays.asList("Zo\u00eb O\u2019Brien", "12 King Street", "Newtown NSW 2042");
    /** C: a customer but only an INSTALLATION address: name set, both billing lines NULL. */
    private static final List<String> WORDS_C = Arrays.asList("Mei Chen", null, null);
    /** D: a BILLING address (unit ' 2 ' trimmed) but no customer row: name NULL, both lines set. */
    private static final List<String> WORDS_D = Arrays.asList(null, "2/5 Beach Road", "Bondi NSW 2026");
    /** E: neither a customer row nor any address: all three NULL. */
    private static final List<String> WORDS_E = Arrays.asList(null, null, null);

    // ---- Terms: set values must survive byte for byte; the business's terms must never be copied in ----

    /** Leading spaces, double quotes, newlines, and 2-, 3- and 4-byte UTF-8 characters. */
    private static final String ODD_TERMS =
            "  <p id=\"t\">Frozen terms V20</p>\n<p>caf\u00e9 \u2014 customer\u2019s copy \ud83d\udcdc</p>\n";
    /** The accepted quote's own frozen terms, copied onto its Path A invoice versions. */
    private static final String QUOTE_TERMS = "<p>Quote terms \u2013 40% deposit, balance before laying.</p>";
    private static final String BUSINESS_TERMS_SOFT = "<p>Business SOFT terms: V20 must never copy these</p>";
    private static final String BUSINESS_TERMS_HARD = "<p>Business HARD terms: V20 must never copy these</p>";
    private static final String BUSINESS_LEGACY_TERMS = "<p>Legacy terms_and_conditions: never copied</p>";

    // ---- Order A's accepted quote: its own V17 snapshot and accepted name differ from the saved customer ----

    private static final String QUOTE_NAME_SNAPSHOT = "Jordan Wilson";
    private static final String QUOTE_LINE1_SNAPSHOT = "9 Old Mill Road";
    private static final String QUOTE_LINE2_SNAPSHOT = "Glebe NSW 2037";
    private static final String QUOTE_ACCEPTED_AT = "2026-10-02 09:30:00";

    private static final String TENANT_SLUG = "v20up-upgrade-flooring";
    private static final String DETAILS_OF_SALE = "Supply and install carpet (V20 upgrade fixture)";
    private static final BigDecimal SALE_PRICE_INC_GST = new BigDecimal("1100.00");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * One upgrade, checked end to end:
     * <ol>
     *   <li>a real V19 schema (the committed V1–V19 scripts), where the three invoice columns do not
     *       exist yet and only the V17 {@code quote_version} carries columns of those names;</li>
     *   <li>our own tenant, orders A to E and ten invoice versions of pre-PR-2 history, read back as
     *       written;</li>
     *   <li>a snapshot of every table's pre-V20 columns, row counts, constraints and indexes;</li>
     *   <li>V20 applied alone: exactly one migration executed, the scratch schema now at 20;</li>
     *   <li>the three columns are nullable {@code TEXT} with no default, appended to {@code invoice}
     *       only; every version of every order holds exactly its literal expected words (NULL where a
     *       row is missing, never {@code ''}); {@code terms_snapshot} (NULL and set, compared as UTF-8
     *       bytes), the acceptance columns, {@code last_emailed_at}, {@code source_quote_version_id},
     *       {@code stored_file_id} and {@code accepted_signature_file_id} are unchanged on every row; the
     *       source quote version keeps its own V17 snapshot; and every table keeps its rows, its
     *       pre-V20 values (fingerprint), its constraints and its indexes;</li>
     *   <li>the scratch schema is dropped and the application's {@code flyway_schema_history} row count
     *       is unchanged.</li>
     * </ol>
     * On the pre-PR-2 tree step 4 fails: with no V20 script the target-20 run applies no migration
     * (Flyway's {@code failOnMissingTarget} is off by default), and nothing after it could pass without
     * V20's columns and backfill.
     */
    @Test
    void v20_upgradesPopulatedV19Schema_backfillsEveryInvoiceVersion_andChangesNothingElse() {
        dropLeftoverScratchSchemas();
        long appHistoryRowsBefore = appSchemaHistoryRows();
        String schema = SCRATCH_SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        try {
            // ---- 1. A real V19 database: the committed V1–V19 scripts, applied by Flyway ----
            flywayUpTo(schema, "19").migrate();
            Assertions.assertEquals(versionsOneTo(19), appliedSqlVersions(schema),
                    "the scratch schema must hold exactly V1 to V19 before the upgrade under test");
            for (String column : V20_COLUMNS) {
                Assertions.assertNull(columnShape(schema, "invoice", column),
                        "invoice." + column + " must not exist before V20");
                Assertions.assertEquals(List.of("quote_version"), tablesWithColumn(schema, column),
                        "before V20 only the V17 quote_version has a column named " + column);
            }

            // ---- 2. Populate it: our own tenant, five orders, one accepted quote, ten invoice versions ----
            ScratchTenant tenant = insertScratchTenant(schema);
            long orderA = insertScratchOrder(schema, tenant, 1, true);   // LAID: the backfill ignores status
            long orderB = insertScratchOrder(schema, tenant, 2, false);
            long orderC = insertScratchOrder(schema, tenant, 3, false);
            long orderD = insertScratchOrder(schema, tenant, 4, false);
            long orderE = insertScratchOrder(schema, tenant, 5, false);
            List<Long> orders = List.of(orderA, orderB, orderC, orderD, orderE);

            // A: stray spaces in the names. The INSTALLATION address is written first (lower key), so a
            // backfill that took the order's first address, or any non-BILLING address, is caught.
            insertScratchCustomer(schema, orderA, "  Jordan ", "Avery", "Wilson ");
            insertScratchAddress(schema, orderA, AddressType.INSTALLATION,
                    null, "88", "Glenmore Road", "Edgecliff", "NSW", "2027");
            insertScratchAddress(schema, orderA, AddressType.BILLING,
                    "4", "12", "Oxford Street", "Paddington", "NSW", "2021");
            // B: a whitespace-only middle name and a BILLING address with no unit, whose street number and
            // postcode carry edge spaces (allowed by the V3 CHECKs): only the whole-line BTRIM cleans them.
            insertScratchCustomer(schema, orderB, "Zo\u00eb", "   ", "O\u2019Brien");
            insertScratchAddress(schema, orderB, AddressType.BILLING,
                    null, " 12", "King Street", "Newtown", "NSW", "2042 ");
            // C: a customer and only an INSTALLATION address.
            insertScratchCustomer(schema, orderC, "Mei", null, "Chen");
            insertScratchAddress(schema, orderC, AddressType.INSTALLATION,
                    null, "7", "Harbour Street", "Mosman", "NSW", "2088");
            // D: a BILLING address (with a padded unit) and no customer row.
            insertScratchAddress(schema, orderD, AddressType.BILLING,
                    " 2 ", "5", "Beach Road", "Bondi", "NSW", "2026");
            // E: no customer row and no address at all.

            long quoteSignatureFile = insertScratchStoredFile(schema, "quote-signature-a-v20up.png", "image/png");
            long quoteVersionA = insertScratchAcceptedQuoteVersion(schema, tenant, orderA, quoteSignatureFile);
            long inStoreSignatureFile = insertScratchStoredFile(schema, "invoice-signature-a-v20up.png", "image/png");
            long dSignatureFile = insertScratchStoredFile(schema, "invoice-signature-d-v20up.png", "image/png");
            Acceptance inStoreA = new Acceptance("2026-10-01 10:15:30", "Jordan Wilson", inStoreSignatureFile);
            Acceptance fromQuoteA = new Acceptance(QUOTE_ACCEPTED_AT, QUOTE_NAME_SNAPSHOT, quoteSignatureFile);

            // A v1: a Create - unsigned, no terms, no quote source.
            SeededInvoice a1 = insertScratchInvoice(schema, tenant, orderA, 1, "0.00", null, null, null, null);
            // A v2: unsigned with terms_snapshot set to odd bytes and no quote source. No 16F writer
            // produces this, but it is a valid V19 row and V20 must keep the value verbatim.
            SeededInvoice a2 = insertScratchInvoice(schema, tenant, orderA, 2, "0.00", null, null, null, ODD_TERMS);
            // A v3: signed in store (D.8) and emailed; no terms, no quote source.
            SeededInvoice a3 = insertScratchInvoice(schema, tenant, orderA, 3, "0.00", inStoreA,
                    "2026-10-01 10:16:05", null, null);
            // A v4: Path A from the accepted quote - its signature, accepted name and frozen terms.
            SeededInvoice a4 = insertScratchInvoice(schema, tenant, orderA, 4, "0.00", fromQuoteA, null,
                    quoteVersionA, QUOTE_TERMS);
            // A v5: a payment version carried from v4 (same signature file: the V10 non-unique FK).
            SeededInvoice a5 = insertScratchInvoice(schema, tenant, orderA, 5, "500.00", fromQuoteA, null,
                    quoteVersionA, QUOTE_TERMS);
            SeededInvoice b1 = insertScratchInvoice(schema, tenant, orderB, 1, "0.00", null, null, null, null);
            SeededInvoice c1 = insertScratchInvoice(schema, tenant, orderC, 1, "0.00", null, null, null, null);
            SeededInvoice d1 = insertScratchInvoice(schema, tenant, orderD, 1, "0.00", null, null, null, null);
            // D v2: a recorded acceptance on an order with no customer row. The app never writes this
            // row; it is here so a backfill that fell back to accepted_customer_name is caught.
            SeededInvoice d2 = insertScratchInvoice(schema, tenant, orderD, 2, "0.00",
                    new Acceptance("2026-10-03 14:00:00", "Dana Signed In Store", dSignatureFile), null, null, null);
            SeededInvoice e1 = insertScratchInvoice(schema, tenant, orderE, 1, "0.00", null, null, null, null);
            List<SeededInvoice> seeded = List.of(a1, a2, a3, a4, a5, b1, c1, d1, d2, e1);

            Assertions.assertEquals(invoiceIds(seeded), invoiceIdsOfOrders(schema, orders),
                    "the five orders must hold exactly the ten seeded invoice versions");
            for (SeededInvoice invoice : seeded) {
                Assertions.assertEquals(invoice, readInvoice(schema, invoice.invoiceId()),
                        "the V19 row must read back exactly as seeded");
            }

            // ---- 3. Snapshot every table over its PRE-V20 columns, and the schema's constraints/indexes ----
            Map<String, List<String>> v19Columns = columnsByTable(schema);
            Map<String, Long> countsBefore = rowCounts(schema, v19Columns.keySet());
            Map<String, String> fingerprintsBefore = fingerprints(schema, v19Columns);
            List<String> constraintsAndIndexesBefore = constraintsAndIndexes(schema);
            Assertions.assertTrue(constraintsAndIndexesBefore.stream().anyMatch(definition ->
                            definition.startsWith("constraint invoice.fk_invoice_source_quote_version ")),
                    "the constraint snapshot must cover the invoice table: " + constraintsAndIndexesBefore);

            // ---- 4. Apply V20 alone ----
            MigrateResult toV20 = flywayUpTo(schema, "20").migrate();
            Assertions.assertEquals(1, toV20.migrationsExecuted, "exactly one migration must run in the upgrade");
            Assertions.assertEquals(List.of("20"),
                    toV20.getSuccessfulMigrations().stream().map(migration -> migration.version).toList(),
                    "the one migration applied by the upgrade must be V20");
            Assertions.assertEquals(versionsOneTo(20), appliedSqlVersions(schema),
                    "the scratch schema must now hold exactly V1 to V20");
            Assertions.assertEquals("20", latestAppliedVersion(schema), "the scratch schema must now be at V20");

            // ---- 5a. Shape: three nullable TEXT columns, no default, appended to invoice and nowhere else ----
            for (String column : V20_COLUMNS) {
                Map<String, Object> shape = columnShape(schema, "invoice", column);
                Assertions.assertNotNull(shape, "invoice." + column + " must exist after V20");
                Assertions.assertEquals("text", shape.get("data_type"), "invoice." + column + " data type");
                Assertions.assertNull(shape.get("max_length"), "invoice." + column + " must be unbounded");
                Assertions.assertEquals("YES", shape.get("is_nullable"), "invoice." + column + " must be nullable");
                Assertions.assertNull(shape.get("column_default"), "invoice." + column + " must have no default");
                Assertions.assertEquals(List.of("invoice", "quote_version"), tablesWithColumn(schema, column),
                        "V20 must add " + column + " to invoice only (quote_version has had it since V17)");
            }
            Map<String, List<String>> expectedV20Columns = new TreeMap<>(v19Columns);
            List<String> invoiceColumnsAfterV20 = new ArrayList<>(v19Columns.get("invoice"));
            invoiceColumnsAfterV20.addAll(V20_COLUMNS);
            expectedV20Columns.put("invoice", invoiceColumnsAfterV20);
            Assertions.assertEquals(expectedV20Columns, columnsByTable(schema),
                    "V20 must append exactly the three columns to invoice, and add, drop or change no other "
                            + "table or column");

            // ---- 5b. The backfill: EVERY version of every order, from that order's own saved rows ----
            Map<Long, List<String>> expectedWords = new TreeMap<>();
            for (SeededInvoice versionOfA : List.of(a1, a2, a3, a4, a5)) {
                expectedWords.put(versionOfA.invoiceId(), WORDS_A);
            }
            expectedWords.put(b1.invoiceId(), WORDS_B);
            expectedWords.put(c1.invoiceId(), WORDS_C);
            expectedWords.put(d1.invoiceId(), WORDS_D);
            expectedWords.put(d2.invoiceId(), WORDS_D);
            expectedWords.put(e1.invoiceId(), WORDS_E);
            Assertions.assertEquals(expectedWords, wordsByInvoiceId(schema, orders),
                    "invoice_id -> [customer_name_snapshot, customer_address_line1_snapshot, "
                            + "customer_address_line2_snapshot] after V20, for every version of orders A to E");
            Assertions.assertEquals(0L, jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM " + qualified(schema, "invoice")
                                    + " WHERE customer_name_snapshot = '' OR customer_address_line1_snapshot = '' "
                                    + "   OR customer_address_line2_snapshot = ''",
                            Long.class),
                    "V20 must store a missing or blank value as NULL, never '', on every invoice row");

            // ---- 5c. Terms are not backfilled, and nothing else on any seeded row moved ----
            for (SeededInvoice invoice : seeded) {
                Assertions.assertEquals(invoice, readInvoice(schema, invoice.invoiceId()),
                        "V20 must leave terms_snapshot, the acceptance and email columns, source_quote_version_id "
                                + "and stored_file_id of order " + invoice.orderId() + " v"
                                + invoice.versionNumber() + " exactly as they were");
            }
            Assertions.assertEquals(utf8Hex(ODD_TERMS), termsUtf8Hex(schema, a2.invoiceId()),
                    "a set terms_snapshot must stay byte-identical (spaces, quotes, newlines, multibyte)");
            Assertions.assertEquals(utf8Hex(QUOTE_TERMS), termsUtf8Hex(schema, a4.invoiceId()),
                    "the Path A version's frozen quote terms must stay byte-identical");
            Assertions.assertEquals(utf8Hex(QUOTE_TERMS), termsUtf8Hex(schema, a5.invoiceId()),
                    "the carried payment version's frozen quote terms must stay byte-identical");

            // ---- 5d. The source quote version keeps its own V17 snapshot (V20 never touches quote_version) ----
            Assertions.assertEquals(
                    Arrays.asList(QUOTE_NAME_SNAPSHOT, QUOTE_LINE1_SNAPSHOT, QUOTE_LINE2_SNAPSHOT),
                    quoteVersionWords(schema, quoteVersionA),
                    "the accepted quote version's V17 snapshot must be unchanged");

            // ---- 5e. Same rows, byte-identical pre-V20 values, same constraints and indexes everywhere ----
            Assertions.assertEquals(countsBefore, rowCounts(schema, v19Columns.keySet()),
                    "V20 must not add or remove a row in any table");
            Assertions.assertEquals(fingerprintsBefore, fingerprints(schema, v19Columns),
                    "V20 must not change any pre-existing column value in any table");
            Assertions.assertEquals(constraintsAndIndexesBefore, constraintsAndIndexes(schema),
                    "V20 must not add, drop or change a constraint or an index");
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
    // Fixture records
    // ================================================================

    /** This class's own tenant rows inside the scratch schema (generated ids, never the V4 ones). */
    private record ScratchTenant(long businessId, int storeId, long userId) {
    }

    /** The V10 acceptance written on a signed version; {@code acceptedAt} as 'yyyy-MM-dd HH:mm:ss'. */
    private record Acceptance(String acceptedAt, String acceptedCustomerName, long signatureFileId) {
    }

    /**
     * One seeded invoice version: the pre-V20 columns V20 must leave untouched, exactly as inserted
     * (timestamps as their text form). Re-read after V20 and compared as a whole record.
     */
    private record SeededInvoice(long invoiceId,
                                 long orderId,
                                 int versionNumber,
                                 long storedFileId,
                                 String acceptedAt,
                                 String acceptedCustomerName,
                                 Long acceptedSignatureFileId,
                                 String lastEmailedAt,
                                 Long sourceQuoteVersionId,
                                 String termsSnapshot) {
    }

    private enum AddressType { BILLING, INSTALLATION }

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

    /** The versions of the successfully applied SQL migrations in the scratch schema, in install order. */
    private List<String> appliedSqlVersions(String schema) {
        return jdbcTemplate.queryForList(
                "SELECT version FROM " + qualified(schema, "flyway_schema_history")
                        + " WHERE success AND type = 'SQL' ORDER BY installed_rank",
                String.class);
    }

    private String latestAppliedVersion(String schema) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM " + qualified(schema, "flyway_schema_history")
                        + " WHERE success AND type = 'SQL' ORDER BY installed_rank DESC LIMIT 1",
                String.class);
    }

    private static List<String> versionsOneTo(int lastVersion) {
        return IntStream.rangeClosed(1, lastVersion).mapToObj(String::valueOf).toList();
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

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    // ================================================================
    // Scratch-schema seeding (schema-qualified, auto-commit)
    // ================================================================

    /**
     * Our own business (with per-type and legacy terms, so a terms backfill would be visible), store
     * and salesperson. The ids are generated: the V4 replay reset each identity past its demo rows.
     */
    private ScratchTenant insertScratchTenant(String schema) {
        long businessId = jdbcTemplate.queryForObject(
                "INSERT INTO " + qualified(schema, "business")
                        + " (name, slug, email_domain, terms_soft, terms_hard, terms_and_conditions) "
                        + "VALUES ('V20 Upgrade Flooring', ?, 'v20up.example', ?, ?, ?) RETURNING business_id",
                Long.class, TENANT_SLUG, BUSINESS_TERMS_SOFT, BUSINESS_TERMS_HARD, BUSINESS_LEGACY_TERMS);
        int storeId = jdbcTemplate.queryForObject(
                "INSERT INTO " + qualified(schema, "store")
                        + " (business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'V20 Upgrade Store', 'V20UP', '0290002020', 'store@v20up.example', "
                        + "        '1 Upgrade Street', 'Sydney', 'NSW', '2000') "
                        + "RETURNING store_id",
                Integer.class, businessId);
        long userId = jdbcTemplate.queryForObject(
                "INSERT INTO " + qualified(schema, "app_user")
                        + " (business_id, first_name, last_name, salesperson_code, email, password_hash) "
                        + "VALUES (?, 'Val', 'Upgrade', 'VU1', 'val.upgrade@v20up.example', 'v20up-not-a-login-hash') "
                        + "RETURNING user_id",
                Long.class, businessId);
        return new ScratchTenant(businessId, storeId, userId);
    }

    private long insertScratchOrder(String schema, ScratchTenant tenant, int sequence, boolean laid) {
        // Untyped enum literals resolve to the scratch schema's types (see class doc). A LAID order
        // carries the V3 lay-date pair.
        String statusAndLayDate = laid ? "'LAID', DATE '2026-10-20', 'CONFIRMED'" : "'ACCEPTED', NULL, NULL";
        return jdbcTemplate.queryForObject(
                "INSERT INTO " + qualified(schema, "sales_order")
                        + " (business_id, store_id, user_id, order_sequence_number, order_number, flooring_type, "
                        + "  order_status, proposed_lay_date, lay_date_status, week_number, week_year) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT', " + statusAndLayDate + ", 42, 2026) "
                        + "RETURNING order_id",
                Long.class, tenant.businessId(), tenant.storeId(), tenant.userId(), sequence,
                String.format("V20UP.VU1.%05d", sequence));
    }

    private void insertScratchCustomer(String schema, long orderId, String firstName, String middleName,
                                       String lastName) {
        jdbcTemplate.update(
                "INSERT INTO " + qualified(schema, "order_customer")
                        + " (order_id, first_name, middle_name, last_name, email, mobile) "
                        + "VALUES (?, ?, ?, ?, ?, '0412002020')",
                orderId, firstName, middleName, lastName, "customer" + orderId + "@v20up.example");
    }

    private void insertScratchAddress(String schema, long orderId, AddressType type, String unitNumber,
                                      String streetNumber, String street, String suburb, String stateCode,
                                      String postcode) {
        // address_type is an untyped literal built from this class's own enum constant (see class doc).
        jdbcTemplate.update(
                "INSERT INTO " + qualified(schema, "order_address")
                        + " (order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, '" + type.name() + "', ?, ?, ?, ?, ?, ?)",
                orderId, unitNumber, streetNumber, street, suburb, stateCode, postcode);
    }

    private long insertScratchStoredFile(String schema, String fileName, String mimeType) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO " + qualified(schema, "stored_file")
                        + " (file_name, storage_path, mime_type, file_size) "
                        + "VALUES (?, ?, ?, 1024) RETURNING stored_file_id",
                Long.class, fileName, "/uploads/v20up/" + fileName, mimeType);
    }

    /** Order A's ACCEPTED quote: its own frozen terms, its own V17 snapshot and accepted name, its signature. */
    private long insertScratchAcceptedQuoteVersion(String schema, ScratchTenant tenant, long orderId,
                                                   long signatureFileId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO " + qualified(schema, "quote_version")
                        + " (order_id, version_number, status, itemised, quote_total_ex_gst, quote_total_inc_gst, "
                        + "  flooring_type_snapshot, terms_snapshot, details_of_sale_snapshot, "
                        + "  customer_name_snapshot, customer_address_line1_snapshot, customer_address_line2_snapshot, "
                        + "  accepted_at, accepted_customer_name, accepted_signature_file_id, created_by_user_id) "
                        + "VALUES (?, 1, 'ACCEPTED', FALSE, 1000.00, 1100.00, 'SOFT', ?, ?, ?, ?, ?, "
                        + "        CAST(? AS timestamp), ?, ?, ?) "
                        + "RETURNING quote_version_id",
                Long.class, orderId, QUOTE_TERMS, DETAILS_OF_SALE, QUOTE_NAME_SNAPSHOT, QUOTE_LINE1_SNAPSHOT,
                QUOTE_LINE2_SNAPSHOT, QUOTE_ACCEPTED_AT, QUOTE_NAME_SNAPSHOT, signatureFileId, tenant.userId());
    }

    /**
     * A valid V19 invoice version (every V2 NOT NULL and V3 CHECK satisfied: 1000.00 ex / 1100.00 inc,
     * balance = inc - paid, its own PDF {@code stored_file}, created by our salesperson). A null
     * {@code acceptance} leaves it unsigned. Returns the record of what was written.
     */
    private SeededInvoice insertScratchInvoice(String schema, ScratchTenant tenant, long orderId, int versionNumber,
                                               String totalPaid, Acceptance acceptance, String lastEmailedAt,
                                               Long sourceQuoteVersionId, String termsSnapshot) {
        long pdfFileId = insertScratchStoredFile(schema,
                "invoice-v20up-" + orderId + "-v" + versionNumber + ".pdf", "application/pdf");
        BigDecimal paid = new BigDecimal(totalPaid);
        String acceptedAt = acceptance == null ? null : acceptance.acceptedAt();
        String acceptedName = acceptance == null ? null : acceptance.acceptedCustomerName();
        Long signatureFileId = acceptance == null ? null : acceptance.signatureFileId();
        long invoiceId = jdbcTemplate.queryForObject(
                "INSERT INTO " + qualified(schema, "invoice")
                        + " (order_id, version_number, details_of_sale_snapshot, sale_price_ex_gst, "
                        + "  sale_price_inc_gst, total_paid, balance_due, stored_file_id, created_by_user_id, "
                        + "  accepted_at, accepted_customer_name, accepted_signature_file_id, last_emailed_at, "
                        + "  source_quote_version_id, terms_snapshot) "
                        + "VALUES (?, ?, ?, 1000.00, ?, ?, ?, ?, ?, CAST(? AS timestamp), ?, ?, "
                        + "        CAST(? AS timestamp), ?, ?) "
                        + "RETURNING invoice_id",
                Long.class, orderId, versionNumber, DETAILS_OF_SALE, SALE_PRICE_INC_GST, paid,
                SALE_PRICE_INC_GST.subtract(paid), pdfFileId, tenant.userId(), acceptedAt, acceptedName,
                signatureFileId, lastEmailedAt, sourceQuoteVersionId, termsSnapshot);
        return new SeededInvoice(invoiceId, orderId, versionNumber, pdfFileId, acceptedAt, acceptedName,
                signatureFileId, lastEmailedAt, sourceQuoteVersionId, termsSnapshot);
    }

    // ================================================================
    // Reads, snapshots and assertions on the scratch schema
    // ================================================================

    private static List<Long> invoiceIds(List<SeededInvoice> invoices) {
        return invoices.stream().map(SeededInvoice::invoiceId).sorted().toList();
    }

    private List<Long> invoiceIdsOfOrders(String schema, List<Long> orderIds) {
        return jdbcTemplate.queryForList(
                "SELECT invoice_id FROM " + qualified(schema, "invoice")
                        + " WHERE order_id IN (" + placeholders(orderIds.size()) + ") ORDER BY invoice_id",
                Long.class, orderIds.toArray());
    }

    /** The same columns as {@link SeededInvoice}, read back from the scratch schema. */
    private SeededInvoice readInvoice(String schema, long invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT invoice_id, order_id, version_number, stored_file_id, accepted_at::text AS accepted_at, "
                        + "       accepted_customer_name, accepted_signature_file_id, "
                        + "       last_emailed_at::text AS last_emailed_at, source_quote_version_id, terms_snapshot "
                        + "FROM " + qualified(schema, "invoice") + " WHERE invoice_id = ?",
                (rs, rowNum) -> new SeededInvoice(
                        rs.getLong("invoice_id"),
                        rs.getLong("order_id"),
                        rs.getInt("version_number"),
                        rs.getLong("stored_file_id"),
                        rs.getString("accepted_at"),
                        rs.getString("accepted_customer_name"),
                        rs.getObject("accepted_signature_file_id", Long.class),
                        rs.getString("last_emailed_at"),
                        rs.getObject("source_quote_version_id", Long.class),
                        rs.getString("terms_snapshot")),
                invoiceId);
    }

    /** invoice_id -> [name, billing line 1, billing line 2] for every invoice row of the given orders. */
    private Map<Long, List<String>> wordsByInvoiceId(String schema, List<Long> orderIds) {
        Map<Long, List<String>> words = new TreeMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT invoice_id, customer_name_snapshot, customer_address_line1_snapshot, "
                        + "       customer_address_line2_snapshot "
                        + "FROM " + qualified(schema, "invoice")
                        + " WHERE order_id IN (" + placeholders(orderIds.size()) + ")",
                orderIds.toArray())) {
            words.put((Long) row.get("invoice_id"), Arrays.asList(
                    (String) row.get("customer_name_snapshot"),
                    (String) row.get("customer_address_line1_snapshot"),
                    (String) row.get("customer_address_line2_snapshot")));
        }
        return words;
    }

    private List<String> quoteVersionWords(String schema, long quoteVersionId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT customer_name_snapshot, customer_address_line1_snapshot, customer_address_line2_snapshot "
                        + "FROM " + qualified(schema, "quote_version") + " WHERE quote_version_id = ?",
                quoteVersionId);
        return Arrays.asList(
                (String) row.get("customer_name_snapshot"),
                (String) row.get("customer_address_line1_snapshot"),
                (String) row.get("customer_address_line2_snapshot"));
    }

    /** The stored terms_snapshot as lowercase hex of its UTF-8 bytes (null when the column is NULL). */
    private String termsUtf8Hex(String schema, long invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT encode(convert_to(terms_snapshot, 'UTF8'), 'hex') FROM " + qualified(schema, "invoice")
                        + " WHERE invoice_id = ?",
                String.class, invoiceId);
    }

    private static String utf8Hex(String value) {
        return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
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

    /** The scratch-schema tables that have a column of this name, sorted. */
    private List<String> tablesWithColumn(String schema, String column) {
        return jdbcTemplate.queryForList("""
                SELECT table_name::text
                FROM information_schema.columns
                WHERE table_schema = ? AND column_name = ?
                ORDER BY table_name
                """, String.class, schema, column);
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

    private Map<String, Long> rowCounts(String schema, Iterable<String> tables) {
        Map<String, Long> counts = new TreeMap<>();
        for (String table : tables) {
            counts.put(table, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + qualified(schema, table), Long.class));
        }
        return counts;
    }

    /**
     * Per table: md5 over every row's text form restricted to the GIVEN columns (the pre-V20 column
     * list), rows sorted by that text. Equal fingerprints before and after V20 = no value changed.
     */
    private Map<String, String> fingerprints(String schema, Map<String, List<String>> columnsByTable) {
        Map<String, String> fingerprints = new TreeMap<>();
        for (Map.Entry<String, List<String>> table : columnsByTable.entrySet()) {
            String rowText = table.getValue().stream()
                    .map(V20MigrationUpgradeTest::quoteIdent)
                    .collect(Collectors.joining(", ", "ROW(", ")::text"));
            fingerprints.put(table.getKey(), jdbcTemplate.queryForObject(
                    "SELECT md5(COALESCE(string_agg(r, E'\\n' ORDER BY r), '')) FROM (SELECT " + rowText
                            + " AS r FROM " + qualified(schema, table.getKey()) + ") x",
                    String.class));
        }
        return fingerprints;
    }

    /**
     * Every constraint (table, name and full definition) and every index definition on the scratch
     * schema's application tables, sorted. Equal lists before and after V20 = no constraint or index was
     * added, dropped or changed.
     */
    private List<String> constraintsAndIndexes(String schema) {
        List<String> definitions = new ArrayList<>(jdbcTemplate.queryForList("""
                SELECT 'constraint ' || t.relname::text || '.' || c.conname::text || ' '
                       || pg_get_constraintdef(c.oid)
                FROM pg_constraint c
                JOIN pg_class t     ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = ? AND t.relname <> 'flyway_schema_history'
                ORDER BY 1
                """, String.class, schema));
        definitions.addAll(jdbcTemplate.queryForList("""
                SELECT 'index ' || indexdef
                FROM pg_indexes
                WHERE schemaname = ? AND tablename <> 'flyway_schema_history'
                ORDER BY tablename, indexname
                """, String.class, schema));
        return definitions;
    }
}
