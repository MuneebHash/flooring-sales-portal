package com.flooring.salesportal.order;

import com.flooring.salesportal.common.email.RecordingInvoiceAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingInvoiceEmailSender;
import com.flooring.salesportal.common.email.RecordingInvoiceLinkEmailSender;
import com.flooring.salesportal.common.email.RecordingQuoteAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Phase 16G PR 4 — V21 UPGRADE-PATH proof: applying V21 to a POPULATED V20 database creates the
 * order-bound invoice link table {@code invoice_token}, replaces {@code chk_business_slug_reserved} with the
 * 16 V18 words plus {@code 'i'}, and changes nothing else.
 *
 * <p>The application schema of a test run is migrated straight to the latest version at context start, so it
 * never shows V21 meeting existing data. This class proves the UPGRADE, following
 * {@link V20MigrationUpgradeTest} for the replay, the seeding rules and the scratch-schema lifecycle: each test
 * replays the committed V1–V20 scripts with Flyway into its own throwaway scratch schema on the same database
 * (the application {@link DataSource}), fills that V20 database with realistic 16G-era history, snapshots it,
 * applies V21 alone, and checks one aspect of the result:
 * <ul>
 *   <li>every pre-existing table keeps its columns, every row and value (full {@code to_jsonb} row snapshots)
 *       and its physical file; every sequence keeps its position; every constraint and index is unchanged
 *       except the one replaced reserved-slug CHECK; the V1–V20 history rows are untouched; and V21 adds
 *       exactly its own table, identity sequence, constraints and indexes, with no rows (16G contract sections
 *       7.1 and 9.1);</li>
 *   <li>the {@code invoice_token} columns, types, lengths, nullability, identity primary key and
 *       {@code created_at DEFAULT now()} (section 7.1);</li>
 *   <li>its named constraints and indexes: {@code fk_invoice_token_order} and {@code fk_invoice_token_sent_by}
 *       (no ON DELETE action), {@code uq_invoice_token_hash}, {@code chk_invoice_token_status} with exactly the
 *       five statuses (section 7.2) and the partial unique index {@code uq_invoice_token_one_active_per_order}
 *       (sections 7.1 and 7.6);</li>
 *   <li>their behaviour on links for PRE-EXISTING orders sent by a PRE-EXISTING salesperson: one
 *       {@code ACTIVE} link per order, any number of dead rows beside it, a send's replacement in one
 *       transaction, and the refusals (a reused hash, a NULL required column, an unknown status, an over-long
 *       value, a foreign-key violation on insert and on delete);</li>
 *   <li>{@code chk_business_slug_reserved}: still exactly one such constraint, now the 16 V18 words plus
 *       {@code 'i'}, refusing all 17 words on INSERT and UPDATE and accepting ordinary and near-miss slugs
 *       (section 9.1);</li>
 *   <li>atomicity: a V20 database where a business already holds the slug {@code 'i'} refuses V21 as a whole,
 *       keeps its exact V20 state, and takes V21 once that slug is renamed;</li>
 *   <li>the V1–V20 scripts on the classpath are byte-identical to main (SHA-256 literals below) and exactly one
 *       V21 script exists.</li>
 * </ul>
 *
 * <p><b>The V20 fixture.</b> Our own business (ABN, bank, Stripe link, logo, accent, per-type and legacy terms,
 * two quick descriptions), its store (with an email), salesperson {@code UP1} with store access, a product and
 * two charges. Order one ({@code SOFT}, {@code ACCEPTED}, lay date {@code CONFIRMED}): customer, INSTALLATION and
 * BILLING addresses, a product line, a charge line, a note, a Lead Enquiry, a photo, a payment and a voided
 * payment, and five invoice versions as 16G writes them (a pre-PR-2 Create and payment whose terms stayed NULL,
 * a PR 2 Rewrite that froze the words and the business terms, a payment and its void that copied them). Order
 * two ({@code HARD}, {@code LAID}): a remotely accepted quote v1 (its replaced and consumed tokens, signature and
 * signed PDF), the Path A invoice that inherits it (the quote's V17 identity, frozen terms and acceptance; the
 * D6b sale-price override), and a later issued quote v2 with an {@code ACTIVE} quote token and the draft it came
 * from. Every one of the 23 V20 application tables holds at least one of these rows (asserted). Every expected
 * value is a LITERAL in this class. On the pre-PR-4 tree (no V21 script) the target-21 Flyway run applies no
 * migration, so the exactly-one-migration assertion fails, and nothing after it can pass without V21's table
 * and constraint.
 *
 * <p><b>Why the replay stays inside the scratch schema.</b> Flyway's PostgreSQL connection PREPENDS the
 * configured schema to the connection's {@code search_path} for the migration (and restores it afterwards), so
 * every unqualified V1–V21 statement resolves to the scratch schema's objects: V21's foreign keys reference
 * the scratch schema's own {@code sales_order} and {@code app_user} (asserted). Its history table also lives
 * there, so the application's own {@code flyway_schema_history} is never touched (asserted after every test).
 *
 * <p><b>Seeding.</b> Rows are written with SCHEMA-QUALIFIED SQL on ordinary pooled connections (no session
 * {@code search_path} change can leak back into the pool). Enum columns get UNTYPED literals, which resolve to
 * the target column's scratch-schema enum; a bound varchar parameter or a cast would resolve against the
 * application schema's enum instead and fail. The fixture is self-seeded (slug {@code v21up-upgrade-flooring},
 * store {@code V21UP}, salesperson {@code UP1}); the V4 replay still creates its demo rows inside the scratch
 * schema, and no assertion references them (they only ride along in the whole-table snapshots). Nothing is ever
 * written to the application schema (asserted after every test).
 *
 * <p>Not {@code @Transactional}: Flyway commits each migration on its own pooled connections, so the seeded rows
 * must be committed for the V21 run to upgrade them. Everything a test creates lives in its scratch schema,
 * which {@code tearDown} drops ({@code DROP SCHEMA ... CASCADE}); scratch schemas left by an earlier, killed run
 * of this class are dropped first, matched by this class's own prefix ({@code v21up_} plus 32 hex characters),
 * so another class's scratch schema is never touched. No HTTP layer is involved: the application only reads
 * its own schema, so the upgraded schema is checked with SQL. Retire this class at the Phase 17 schema-only
 * squash/baseline: the V1–V20 history it replays will no longer exist, and V21 joins that squash.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
class V21MigrationUpgradeTest {

    private static final String SCRATCH_SCHEMA_PREFIX = "v21up_";
    /** Only schemas created by this class (prefix + 32 lowercase hex chars) are ever dropped. */
    private static final String SCRATCH_SCHEMA_REGEX = "^v21up_[0-9a-f]{32}$";

    // ---- V21 itself ----

    private static final String V21_SCRIPT = "V21__create_invoice_token_and_reserve_slug_i.sql";
    private static final String V21_DESCRIPTION = "create invoice token and reserve slug i";

    /** The 16 words of the V18 {@code chk_business_slug_reserved}, in V18's order. */
    private static final List<String> V18_RESERVED_SLUGS = List.of(
            "admin", "api", "login", "static", "auth", "health",
            "dashboard", "orders", "select-store", "assets", "new",
            "logout", "account", "settings", "public", "q");
    /** 16G contract section 9.1: every V18 word, in V18's order, then {@code 'i'}; nothing removed. */
    private static final List<String> V21_RESERVED_SLUGS =
            Stream.concat(V18_RESERVED_SLUGS.stream(), Stream.of("i")).toList();

    /** 16G contract section 7.2: the only invoice link statuses (the quote reasons do not exist here). */
    private static final List<String> INVOICE_TOKEN_STATUSES =
            List.of("ACTIVE", "REPLACED", "REWRITTEN", "SIGNED", "EXPIRED");

    /**
     * {@code invoice_token} per information_schema, in ordinal order:
     * {@code column | data_type | character_maximum_length | is_nullable | column_default | is_identity identity_generation}.
     */
    private static final List<String> INVOICE_TOKEN_COLUMNS = List.of(
            "invoice_token_id | bigint | - | NO | - | YES BY DEFAULT",
            "order_id | bigint | - | NO | - | NO -",
            "token_hash | character varying | 255 | NO | - | NO -",
            "status | character varying | 16 | NO | - | NO -",
            "sent_by_user_id | bigint | - | NO | - | NO -",
            "sent_at | timestamp without time zone | - | NO | - | NO -",
            "expires_at | timestamp without time zone | - | NO | - | NO -",
            "emailed_at | timestamp without time zone | - | YES | - | NO -",
            "dead_at | timestamp without time zone | - | YES | - | NO -",
            "created_at | timestamp without time zone | - | NO | now() | NO -");

    /** Every constraint ({@code constraint <contype>}) and index V21 creates, all on {@code invoice_token}. */
    private static final List<String> V21_CONSTRAINTS_AND_INDEXES = List.of(
            "constraint c chk_invoice_token_status",
            "constraint f fk_invoice_token_order",
            "constraint f fk_invoice_token_sent_by",
            "constraint p invoice_token_pkey",
            "constraint u uq_invoice_token_hash",
            "index invoice_token_pkey",
            "index uq_invoice_token_hash",
            "index uq_invoice_token_one_active_per_order");

    /** Every relation ({@code relkind name}) V21 adds: the table, its identity sequence and its three indexes. */
    private static final List<String> V21_RELATIONS = List.of(
            "r invoice_token",
            "S invoice_token_invoice_token_id_seq",
            "i invoice_token_pkey",
            "i uq_invoice_token_hash",
            "i uq_invoice_token_one_active_per_order");

    /** The partial unique index's predicate as PostgreSQL stores it (the V21 {@code WHERE status = 'ACTIVE'}). */
    private static final String ACTIVE_PREDICATE = "((status)::text = 'ACTIVE'::text)";

    // ---- The committed V1-V20 scripts as they are on main ----

    /** One committed migration script as it is on main: its file name and the SHA-256 of its bytes. */
    private record MainScript(int version, String fileName, String sha256) {
    }

    /**
     * V1 to V20 on main, from {@code shasum -a 256 backend/src/main/resources/db/migration/V*.sql} on main
     * (dcb2384), equal to {@code git show main:<path> | shasum -a 256}. A changed byte in any of them fails.
     */
    private static final List<MainScript> MAIN_SCRIPTS = List.of(
            new MainScript(1, "V1__create_enums.sql",
                    "754667bf99e680a160aeb0fa46d11f1a49197700cc6af530e4f0d415f02515fa"),
            new MainScript(2, "V2__create_tables.sql",
                    "9ab18408942678c4dddd7f4ad696475a84703e8941744a31519682339ec73228"),
            new MainScript(3, "V3__add_constraints_and_indexes.sql",
                    "6532c21f577245eeb41cc959b90bdce49e7b25ce35f95fc551a4a55c79162d32"),
            new MainScript(4, "V4__seed_demo_data.sql",
                    "232aea0678034673eef7bf43b97dfea90418756a6469b0ef312c5b6e8f1f3f98"),
            new MainScript(5, "V5__add_business_slug_and_price_adjustment.sql",
                    "5c4f4e2066bb0208aac19d5f4c324a9ea65a29497764dcb2e2d1a1789628d4a1"),
            new MainScript(6, "V6__sync_salesperson_codes_and_order_numbers.sql",
                    "a03c926851d16ab83e32c51b2c52eacef0f554dde1371fdd6cf380fd3f33b0e4"),
            new MainScript(7, "V7__fix_demo_password_hashes.sql",
                    "ec50df8027c08314f69f80a09e06e32ee0aa8321c78c9d38c2d1603deb90a248"),
            new MainScript(8, "V8__add_product_sqm_per_lm_factor.sql",
                    "b25a78a93c18f2e220bd7b14f379081f50c57074664e7608ae8a65dd8b553cf4"),
            new MainScript(9, "V9__relax_sales_order_sale_price_check.sql",
                    "145a4e1f544ad1eb3d0f23d73361778c61248d104b8ed210d7b9ed09015a9138"),
            new MainScript(10, "V10__add_invoice_acceptance_email_fields.sql",
                    "c6dce8f8cba57146647ca82132938594ca32c8f9eb6fde98333e6803eba3add5"),
            new MainScript(11, "V11__reserve_app_route_words_in_business_slug.sql",
                    "d8aff94727d5c4538e1783c0aee87a6fadff8f2172f8bbf47944b462ce7953e4"),
            new MainScript(12, "V12__add_tenant_branding_and_quick_descriptions.sql",
                    "7250cff5817c40c4b2ee27c76aee46c45b73859d090d31691c254791cae3e5d9"),
            new MainScript(13, "V13__add_per_type_invoice_terms.sql",
                    "19be7d8081b9f69c2ff1da2c76ec063f786221e283fc2b4cfcdad28b8bf91f99"),
            new MainScript(14, "V14__add_payment_void_fields.sql",
                    "8751039f02eac3468c78fc3f6d6febacfb4b3a4ca75bddc86fc0b0d5eaf05752"),
            new MainScript(15, "V15__create_order_enquiry.sql",
                    "b53658466083ad07b7ddfe0e885a037bc75f89335f3bac82145e7f7dac94f775"),
            new MainScript(16, "V16__create_quote_tables.sql",
                    "b037cc17c2a53079827fc03afbb6732e5ffdb6fa21bf1fb928f8c77aa8899d36"),
            new MainScript(17, "V17__add_quote_version_customer_snapshot.sql",
                    "cfe9ecb3ed025d50eee0810028097c53a1467c02c18de7a13665528d88583fe8"),
            new MainScript(18, "V18__reserve_slug_q.sql",
                    "7bbafcd3a92bb9f1065622737fef72f230c594b1ad1c64f6069bde62433e6a1a"),
            new MainScript(19, "V19__quote_acceptance_and_invoice_quote_source.sql",
                    "791963ce9597fab6cbcf4501d3e8eabfbb6e1affff50be17049b02d0e9b54283"),
            new MainScript(20, "V20__add_invoice_customer_snapshot.sql",
                    "cb36afe02699c1e93f66fe51eb947a146ef56c59847acc003d4959ad93482651"));

    /** A plain versioned migration file name (no repeatable, undo or dotted-version scripts exist). */
    private static final Pattern VERSIONED_SCRIPT = Pattern.compile("V([1-9][0-9]*)__[a-z0-9_]+\\.sql");

    /** A quoted literal in a deparsed CHECK, e.g. {@code 'admin'::character varying}. */
    private static final Pattern QUOTED_LITERAL = Pattern.compile("'((?:[^']|'')*)'::(?:character varying|text)");

    // ---- Our own tenant inside the scratch schema (never a V4 identity) ----

    private static final String BUSINESS_NAME = "V21 Upgrade Flooring";
    private static final String TENANT_SLUG = "v21up-upgrade-flooring";
    private static final String STORE_CODE = "V21UP";
    private static final String STORE_EMAIL = "showroom@v21up.example";
    private static final String SALESPERSON_CODE = "UP1";
    /** A second salesperson, written only after the upgrade, whom nothing but one invoice link references. */
    private static final String SECOND_SALESPERSON_CODE = "UP2";
    /** Prefix of this class's product and charge codes. */
    private static final String CODE_PREFIX = "V21UP-";
    private static final String ORDER_ONE_NUMBER = "V21UP.UP1.00001";
    private static final String ORDER_TWO_NUMBER = "V21UP.UP1.00002";
    private static final String ORDER_THREE_NUMBER = "V21UP.UP1.00003";

    /** The SOFT terms frozen onto order one's Rewrite: an em dash, an accented letter and a 4-byte emoji. */
    private static final String BUSINESS_TERMS_SOFT =
            "<p>Deposit 40% — balance before laying.</p>\n<p>Café customer’s copy 🧶</p>";
    private static final String BUSINESS_TERMS_HARD = "<p>HARD terms: acclimatise timber for 48 hours.</p>";
    private static final String BUSINESS_LEGACY_TERMS = "<p>Legacy terms_and_conditions</p>";

    // ---- Order one (SOFT, ACCEPTED): five Path B versions ----

    private static final String ORDER_ONE_DETAILS = "Supply and lay wool carpet to lounge and hall (V21 upgrade fixture)";
    private static final String ORDER_ONE_NAME = "Harper Quinn Lee";
    private static final String ORDER_ONE_LINE1 = "3/21 Crown Street";
    private static final String ORDER_ONE_LINE2 = "Surry Hills NSW 2010";

    // ---- Order two (HARD, LAID): the accepted quote, its Path A invoice, a later issued quote ----

    private static final String ORDER_TWO_DETAILS = "Supply and lay spotted gum timber (V21 upgrade fixture)";
    private static final String ORDER_TWO_NAME = "Zoë O’Brien-Nguyễn";
    private static final String ORDER_TWO_LINE1 = "40 Harbour Road";
    private static final String ORDER_TWO_LINE2 = "Manly NSW 2095";
    /** Leading spaces, double quotes and a newline: frozen at issue, copied verbatim onto the Path A version. */
    private static final String QUOTE_TERMS = "  <p id=\"q\">Quote terms frozen at issue</p>\n";
    /** Microsecond precision, as the JVM clock writes it. */
    private static final String QUOTE_ACCEPTED_AT = "2026-10-10 15:58:41.123456";
    private static final String ACTIVE_QUOTE_TOKEN_HASH = sha256Hex("v21up-quote-token-active");

    // ---- invoice_token rows written after the upgrade ----

    private static final String TOKEN_SENT_AT = "2026-10-20 09:30:00.123456";
    private static final String TOKEN_EXPIRES_AT = "2026-10-27 09:30:00.123456";
    private static final String TOKEN_DEAD_AT = "2026-10-21 10:00:00";
    private static final Set<String> TOKEN_TIMESTAMP_COLUMNS =
            Set.of("sent_at", "expires_at", "emailed_at", "dead_at", "created_at");

    private static final Comparator<SchemaObject> SCHEMA_OBJECT_ORDER = Comparator.comparing(SchemaObject::table)
            .thenComparing(SchemaObject::kind)
            .thenComparing(SchemaObject::name);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // This class sends no email. The shared context's recording senders are reset anyway, as in every 16G class,
    // so no recorded message or armed failure crosses a class boundary.
    @Autowired
    private RecordingInvoiceEmailSender invoiceEmailSender;

    @Autowired
    private RecordingInvoiceLinkEmailSender invoiceLinkEmailSender;

    @Autowired
    private RecordingInvoiceAcceptanceNotificationSender invoiceAcceptanceNotificationSender;

    @Autowired
    private RecordingQuoteEmailSender quoteEmailSender;

    @Autowired
    private RecordingQuoteAcceptanceNotificationSender quoteAcceptanceNotificationSender;

    private TransactionTemplate transactionTemplate;

    /** The running test's scratch schema; null until the test creates one. Dropped in {@link #tearDown}. */
    private String schema;

    private long appHistoryRowsBefore;

    /** Rows the V20 fixture wrote, per scratch table (the snapshot must contain them). */
    private final Map<String, Integer> seededRows = new TreeMap<>();

    @BeforeEach
    void setUp() {
        resetRecorders();
        transactionTemplate = new TransactionTemplate(transactionManager);
        dropLeftoverScratchSchemas();
        appHistoryRowsBefore = appSchemaHistoryRows();
    }

    @AfterEach
    void tearDown() {
        try {
            String created = schema;
            schema = null;
            dropLeftoverScratchSchemas();
            if (created != null) {
                Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM pg_namespace WHERE nspname = ?", Integer.class, created),
                        "the scratch schema must be dropped");
            }
            Assertions.assertEquals(appHistoryRowsBefore, appSchemaHistoryRows(),
                    "the scratch replay must never write the application schema's flyway_schema_history");
            Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM business WHERE slug = ?", Integer.class, TENANT_SLUG),
                    "the scratch fixture must never write a business into the application schema");
            Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM store WHERE store_code = ?", Integer.class, STORE_CODE),
                    "the scratch fixture must never write a store into the application schema");
        } finally {
            resetRecorders();
        }
    }

    // ================================================================
    // The upgrade keeps everything that existed and adds exactly V21's objects
    // ================================================================

    /**
     * One upgrade of the populated V20 database, compared with its pre-V21 snapshot:
     * <ol>
     *   <li>the fixture populated every one of the 23 V20 application tables, and the snapshot holds its rows;</li>
     *   <li>exactly one table is new ({@code invoice_token}, empty: V21 backfills no link); every pre-existing
     *       table keeps its column shapes, every row and value byte for byte (full {@code to_jsonb} rows) and its
     *       physical file (V21 rewrites no table);</li>
     *   <li>every constraint and index is unchanged except {@code chk_business_slug_reserved}, which is still a
     *       single constraint and changes from the 16 V18 words to those words plus {@code 'i'}; V21's own
     *       objects are exactly the five {@code invoice_token} constraints and three indexes;</li>
     *   <li>every pre-existing sequence keeps its position, and the only new one is the unused identity sequence
     *       of {@code invoice_token}; the only new relations are V21's five; no enum type, function or trigger
     *       changes; the V1–V20 history rows are untouched and one successful V21 row follows them;</li>
     *   <li>the literal V20 values (frozen words and terms, NULL terms, the Path A provenance, the quote token
     *       statuses, the tenant's slug and payment fields) read back after the upgrade exactly as written.</li>
     * </ol>
     */
    @Test
    void v21_upgradesAPopulatedV20Database_keepsEveryPreExistingRowValueAndSchemaObject_andAddsExactlyItsOwn() {
        UpgradedV20Database database = upgradePopulatedV20Database();
        SeededV20 seeded = database.seeded();
        SchemaSnapshot before = database.beforeV21();
        SchemaSnapshot after = snapshot();

        // ---- 1. The populated V20 database: every application table holds at least one of our rows ----
        Assertions.assertEquals(before.rows().keySet(), seededRows.keySet(),
                "the V20 fixture must populate every V20 application table");
        for (Map.Entry<String, Integer> seededTable : seededRows.entrySet()) {
            Assertions.assertTrue(before.rows().get(seededTable.getKey()).size() >= seededTable.getValue(),
                    "the pre-V21 snapshot of " + seededTable.getKey() + " must hold the " + seededTable.getValue()
                            + " seeded rows");
        }

        // ---- 2. One new, empty table; every pre-existing table unchanged (columns, rows, physical file) ----
        Set<String> preExistingTables = before.columns().keySet();
        Set<String> expectedTables = new TreeSet<>(preExistingTables);
        expectedTables.add("invoice_token");
        Assertions.assertEquals(expectedTables, after.columns().keySet(),
                "V21 must add exactly one table, invoice_token, and drop none");
        Assertions.assertEquals(preExistingTables, before.fileNodes().keySet(),
                "the pre-V21 snapshot must cover the physical file of every table");
        for (String table : preExistingTables) {
            Assertions.assertEquals(before.columns().get(table), after.columns().get(table),
                    "V21 must not add, drop or change a column of " + table);
            Assertions.assertEquals(before.rows().get(table), after.rows().get(table),
                    "V21 must not add, remove or change any row or value of " + table);
            Assertions.assertEquals(before.fileNodes().get(table), after.fileNodes().get(table),
                    "V21 must not rewrite " + table);
        }
        Assertions.assertEquals(List.of(), after.rows().get("invoice_token"),
                "V21 must not create any invoice link (nothing is backfilled)");

        // ---- 3. Constraints and indexes: only the replaced reserved-slug CHECK and V21's own ----
        Predicate<SchemaObject> replacedSlugCheck = object -> object.table().equals("business")
                && object.name().equals("chk_business_slug_reserved");
        Predicate<SchemaObject> onInvoiceToken = object -> object.table().equals("invoice_token");
        Assertions.assertEquals(List.of(), before.constraintsAndIndexes().stream().filter(onInvoiceToken).toList(),
                "nothing may exist on invoice_token before V21");
        Assertions.assertEquals(
                before.constraintsAndIndexes().stream().filter(replacedSlugCheck.negate()).toList(),
                after.constraintsAndIndexes().stream()
                        .filter(replacedSlugCheck.negate().and(onInvoiceToken.negate())).toList(),
                "V21 must not add, drop or change any constraint or index other than chk_business_slug_reserved "
                        + "and its own invoice_token objects");
        SchemaObject slugCheckBefore = single(before.constraintsAndIndexes(), replacedSlugCheck,
                "exactly one chk_business_slug_reserved before V21");
        SchemaObject slugCheckAfter = single(after.constraintsAndIndexes(), replacedSlugCheck,
                "exactly one chk_business_slug_reserved after V21 (replaced, not duplicated)");
        Assertions.assertEquals("constraint c", slugCheckAfter.kind(), "chk_business_slug_reserved stays a CHECK");
        Assertions.assertEquals(V18_RESERVED_SLUGS, quotedLiterals(slugCheckBefore.definition()),
                "the V20 database carries the V18 reserved words");
        Assertions.assertEquals(V21_RESERVED_SLUGS, quotedLiterals(slugCheckAfter.definition()),
                "V21 keeps every V18 word and adds 'i'");
        Assertions.assertEquals(V21_CONSTRAINTS_AND_INDEXES,
                after.constraintsAndIndexes().stream().filter(onInvoiceToken)
                        .map(object -> object.kind() + " " + object.name()).sorted().toList(),
                "V21 must create exactly its five named invoice_token constraints and three indexes");

        // ---- 4. Sequences, relations, types/functions/triggers and the Flyway history ----
        Map<String, String> expectedSequences = new TreeMap<>(before.sequences());
        expectedSequences.put("invoice_token_invoice_token_id_seq", "unused");
        Assertions.assertEquals(expectedSequences, after.sequences(),
                "every sequence keeps its position; the only new one is invoice_token's unused identity");
        Assertions.assertEquals(
                Stream.concat(before.relations().stream(), V21_RELATIONS.stream()).sorted().toList(),
                after.relations(),
                "V21 must add exactly its table, identity sequence and three indexes, and drop no relation");
        Assertions.assertEquals(before.otherObjects(), after.otherObjects(),
                "V21 must not add or change an enum type, a function or a trigger");
        Assertions.assertEquals(before.history().size() + 1, after.history().size(),
                "V21 must add exactly one history row");
        Assertions.assertEquals(before.history(), after.history().subList(0, before.history().size()),
                "the V1 to V20 history rows must be untouched");

        // ---- 5. The literal V20 values read back exactly as written ----
        Assertions.assertEquals(Arrays.asList(null, ORDER_ONE_NAME, ORDER_ONE_LINE1, ORDER_ONE_LINE2),
                invoiceTermsAndWords(seeded.orderOneFirstInvoiceId()),
                "order one v1 (pre-PR-2): its NULL terms_snapshot stays NULL, its backfilled words stay");
        Assertions.assertEquals(
                Arrays.asList(BUSINESS_TERMS_SOFT, ORDER_ONE_NAME, ORDER_ONE_LINE1, ORDER_ONE_LINE2),
                invoiceTermsAndWords(seeded.orderOneCurrentInvoiceId()),
                "order one v5: the frozen business terms (multibyte) and words stay byte-identical");
        Assertions.assertEquals(Arrays.asList("200.00", "900.00", null, null, null),
                invoiceMoneyAndAcceptance(seeded.orderOneCurrentInvoiceId()),
                "order one v5: total paid, balance due, and no acceptance or quote source");
        Assertions.assertEquals(Arrays.asList(QUOTE_TERMS, ORDER_TWO_NAME, ORDER_TWO_LINE1, ORDER_TWO_LINE2),
                invoiceTermsAndWords(seeded.orderTwoInvoiceId()),
                "order two's Path A version: the quote's frozen terms and V17 identity stay byte-identical");
        Assertions.assertEquals(Arrays.asList("0.00", "2200.00", QUOTE_ACCEPTED_AT, ORDER_TWO_NAME,
                        String.valueOf(seeded.acceptedQuoteVersionId())),
                invoiceMoneyAndAcceptance(seeded.orderTwoInvoiceId()),
                "order two's Path A version: its amounts, inherited acceptance (microseconds) and quote source");
        Assertions.assertEquals(List.of("REPLACED", "CONSUMED"), quoteTokenStatuses(seeded.acceptedQuoteVersionId()),
                "the accepted quote's tokens keep their dead statuses");
        Assertions.assertEquals(List.of("ACTIVE"), quoteTokenStatuses(seeded.issuedQuoteVersionId()),
                "the issued quote's link stays ACTIVE: V21 never touches quote_token");
        Assertions.assertEquals(
                Arrays.asList(TENANT_SLUG, "51 824 753 556", "062-000", "1234 5678",
                        "https://buy.stripe.com/test_v21up"),
                businessIdentity(seeded.businessId()),
                "the tenant's slug and payment helper fields survive the constraint replacement");
    }

    // ================================================================
    // invoice_token: shape
    // ================================================================

    /**
     * {@code invoice_token} has exactly the contract's ten columns in order (section 7.1): BIGINT identity
     * {@code invoice_token_id} ({@code GENERATED BY DEFAULT}), {@code order_id} and {@code sent_by_user_id}
     * BIGINT NOT NULL, {@code token_hash} VARCHAR(255) and {@code status} VARCHAR(16) NOT NULL, {@code sent_at}
     * and {@code expires_at} TIMESTAMP NOT NULL, {@code emailed_at} and {@code dead_at} nullable TIMESTAMP, and
     * {@code created_at} TIMESTAMP NOT NULL DEFAULT {@code now()}. It is an ordinary permanent table. On a row
     * for a pre-existing order: the id is generated, {@code created_at} is the inserting transaction's time,
     * {@code emailed_at}/{@code dead_at} start NULL and the timestamps keep microseconds; an explicit id is
     * accepted (BY DEFAULT), and the primary key refuses a second row with an existing id.
     */
    @Test
    void v21_invoiceToken_hasExactlyTheContractColumnsTypesAndNullability_anIdentityPrimaryKey_andCreatedAtDefaultNow() {
        UpgradedV20Database database = upgradePopulatedV20Database();
        SeededV20 seeded = database.seeded();

        Assertions.assertEquals(INVOICE_TOKEN_COLUMNS, invoiceTokenColumns(),
                "invoice_token columns: name | type | length | nullable | default | identity");
        Assertions.assertEquals("r p", jdbcTemplate.queryForObject("""
                        SELECT c.relkind::text || ' ' || c.relpersistence::text
                        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = ? AND c.relname = 'invoice_token'
                        """, String.class, schema),
                "invoice_token must be an ordinary, permanent (logged) table");

        Map<String, Object> inserted = jdbcTemplate.queryForMap(
                "INSERT INTO " + q("invoice_token")
                        + " (order_id, token_hash, status, sent_by_user_id, sent_at, expires_at) "
                        + "VALUES (?, ?, 'ACTIVE', ?, CAST(? AS timestamp), CAST(? AS timestamp)) "
                        + "RETURNING invoice_token_id, created_at = now()::timestamp AS created_at_is_now, "
                        + "          emailed_at, dead_at, sent_at::text AS sent_at, expires_at::text AS expires_at",
                seeded.orderOne(), tokenHash(1), seeded.userId(), TOKEN_SENT_AT, TOKEN_EXPIRES_AT);
        long generatedId = ((Number) inserted.get("invoice_token_id")).longValue();
        Assertions.assertTrue(generatedId > 0, "the identity must generate the id");
        Assertions.assertEquals(Boolean.TRUE, inserted.get("created_at_is_now"),
                "created_at must default to now() (the inserting transaction's time)");
        Assertions.assertNull(inserted.get("emailed_at"), "emailed_at starts NULL (no delivery recorded)");
        Assertions.assertNull(inserted.get("dead_at"), "dead_at starts NULL (the link is live)");
        Assertions.assertEquals(TOKEN_SENT_AT, inserted.get("sent_at"), "sent_at keeps microseconds, no zone shift");
        Assertions.assertEquals(TOKEN_EXPIRES_AT, inserted.get("expires_at"),
                "expires_at keeps microseconds, no zone shift");

        Map<String, Object> explicitId = tokenValues(seeded.orderOne(), tokenHash(2), "REPLACED", seeded.userId());
        explicitId.put("invoice_token_id", generatedId + 1000);
        Assertions.assertEquals(generatedId + 1000, insertTokenRow(explicitId),
                "GENERATED BY DEFAULT: an explicit invoice_token_id is accepted");
        Map<String, Object> duplicateId = tokenValues(seeded.orderOne(), tokenHash(3), "REPLACED", seeded.userId());
        duplicateId.put("invoice_token_id", generatedId);
        assertUniqueViolation("invoice_token_pkey", () -> insertTokenRow(duplicateId),
                "a second row with an existing invoice_token_id");
    }

    // ================================================================
    // invoice_token: named constraints and indexes
    // ================================================================

    /**
     * Exactly five constraints and three indexes on {@code invoice_token}, with their exact definitions:
     * the primary key; {@code fk_invoice_token_order} to THIS schema's {@code sales_order(order_id)} and
     * {@code fk_invoice_token_sent_by} to THIS schema's {@code app_user(user_id)}, both with no ON DELETE or ON
     * UPDATE action (NO ACTION), simple match, not deferrable, validated; {@code uq_invoice_token_hash} =
     * {@code UNIQUE (token_hash)}; {@code chk_invoice_token_status} an allow-list of exactly the five statuses;
     * and {@code uq_invoice_token_one_active_per_order} a unique, immediate b-tree index on {@code (order_id)}
     * with the predicate {@code status = 'ACTIVE'} (pg_index / pg_indexes).
     */
    @Test
    void v21_invoiceToken_hasExactlyItsNamedConstraintsAndIndexes_noActionForeignKeys_uniqueHash_fiveStatuses_partialActiveIndex() {
        upgradePopulatedV20Database();

        Map<String, String> constraints = invoiceTokenConstraints();
        Assertions.assertEquals(
                new TreeSet<>(List.of("chk_invoice_token_status", "fk_invoice_token_order", "fk_invoice_token_sent_by",
                        "invoice_token_pkey", "uq_invoice_token_hash")),
                constraints.keySet(), "invoice_token must carry exactly these constraints");
        Assertions.assertEquals("p PRIMARY KEY (invoice_token_id)", constraints.get("invoice_token_pkey"));
        Assertions.assertEquals("u UNIQUE (token_hash)", constraints.get("uq_invoice_token_hash"));
        Assertions.assertEquals("f FOREIGN KEY (order_id) REFERENCES " + schema + ".sales_order(order_id)",
                constraints.get("fk_invoice_token_order"));
        Assertions.assertEquals("f FOREIGN KEY (sent_by_user_id) REFERENCES " + schema + ".app_user(user_id)",
                constraints.get("fk_invoice_token_sent_by"));
        String statusCheck = constraints.get("chk_invoice_token_status");
        Assertions.assertTrue(statusCheck.startsWith("c CHECK (((status)::text = ANY "),
                "chk_invoice_token_status must be an IN allow-list on status: " + statusCheck);
        Assertions.assertEquals(INVOICE_TOKEN_STATUSES, quotedLiterals(statusCheck),
                "chk_invoice_token_status must allow exactly the five statuses: " + statusCheck);

        Assertions.assertEquals(
                new ForeignKeyShape(schema, "sales_order", "order_id", "order_id", "a", "a", "s", false, true),
                foreignKey("fk_invoice_token_order"),
                "fk_invoice_token_order: this schema's sales_order, NO ACTION on delete and update, immediate");
        Assertions.assertEquals(
                new ForeignKeyShape(schema, "app_user", "sent_by_user_id", "user_id", "a", "a", "s", false, true),
                foreignKey("fk_invoice_token_sent_by"),
                "fk_invoice_token_sent_by: this schema's app_user, NO ACTION on delete and update, immediate");

        Assertions.assertEquals(List.of(
                        new IndexShape("invoice_token_pkey", "btree", true, true, true, true, "invoice_token_id", null),
                        new IndexShape("uq_invoice_token_hash", "btree", true, false, true, true, "token_hash", null),
                        new IndexShape("uq_invoice_token_one_active_per_order", "btree", true, false, true, true,
                                "order_id", ACTIVE_PREDICATE)),
                invoiceTokenIndexes(),
                "invoice_token must carry exactly these three indexes; only the per-order one is partial");
        Assertions.assertEquals(
                "CREATE UNIQUE INDEX uq_invoice_token_one_active_per_order ON " + schema
                        + ".invoice_token USING btree (order_id) WHERE " + ACTIVE_PREDICATE,
                jdbcTemplate.queryForObject(
                        "SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND indexname = ?",
                        String.class, schema, "uq_invoice_token_one_active_per_order"),
                "pg_indexes: the partial unique index on (order_id) WHERE status = 'ACTIVE'");
    }

    // ================================================================
    // invoice_token: behaviour on links for pre-existing orders
    // ================================================================

    /**
     * The partial unique index on rows for the PRE-EXISTING orders, sent by the PRE-EXISTING salesperson: one
     * {@code ACTIVE} link for order one; a second {@code ACTIVE} link for it is refused, and so is reviving one of
     * its dead rows; any number of dead rows of every dead status (two or more each, three {@code REPLACED})
     * coexist with it; the LAID order two holds its own {@code ACTIVE} link at the same time (one per ORDER);
     * a hash an existing QUOTE token uses is free on the invoice surface (separate tables, section 7.8); and a
     * send's replacement in ONE transaction (the old link {@code REPLACED} first, then the new {@code ACTIVE}
     * one, section 7.5) commits and leaves exactly the new link live.
     */
    @Test
    void v21_invoiceToken_allowsOneActiveLinkPerOrder_anyNumberOfDeadRowsBesideIt_andOneActiveLinkForEachOtherOrder() {
        UpgradedV20Database database = upgradePopulatedV20Database();
        SeededV20 seeded = database.seeded();
        long orderOne = seeded.orderOne();
        long userId = seeded.userId();

        long firstActive = insertToken(orderOne, tokenHash(1), "ACTIVE", userId);
        assertUniqueViolation("uq_invoice_token_one_active_per_order",
                () -> insertToken(orderOne, tokenHash(2), "ACTIVE", userId),
                "a second ACTIVE link for the same order");

        List<String> deadStatuses = List.of("REPLACED", "REPLACED", "REPLACED", "REWRITTEN", "REWRITTEN",
                "SIGNED", "SIGNED", "EXPIRED", "EXPIRED");
        List<Long> deadIds = new ArrayList<>();
        for (int i = 0; i < deadStatuses.size(); i++) {
            deadIds.add(insertToken(orderOne, tokenHash(10 + i), deadStatuses.get(i), userId));
        }
        Assertions.assertEquals(
                Map.of("ACTIVE", 1L, "REPLACED", 3L, "REWRITTEN", 2L, "SIGNED", 2L, "EXPIRED", 2L),
                tokenStatusCounts(orderOne),
                "dead rows of every status, repeated, coexist with the order's one ACTIVE link");
        assertUniqueViolation("uq_invoice_token_one_active_per_order",
                () -> jdbcTemplate.update("UPDATE " + q("invoice_token")
                        + " SET status = 'ACTIVE', dead_at = NULL WHERE invoice_token_id = ?", deadIds.get(0)),
                "reviving a dead row beside the order's live link");

        long secondOrderActive = insertToken(seeded.orderTwo(), tokenHash(20), "ACTIVE", userId);
        long quoteHashReuse = insertToken(seeded.orderTwo(), ACTIVE_QUOTE_TOKEN_HASH, "REPLACED", userId);
        Assertions.assertTrue(quoteHashReuse > 0,
                "a quote token's hash is not reserved in invoice_token (hash uniqueness is per table)");
        Assertions.assertEquals(List.of(firstActive), activeTokenIds(orderOne));
        Assertions.assertEquals(List.of(secondOrderActive), activeTokenIds(seeded.orderTwo()));

        Long replacement = transactionTemplate.execute(status -> {
            Assertions.assertEquals(1, jdbcTemplate.update("UPDATE " + q("invoice_token")
                            + " SET status = 'REPLACED', dead_at = CAST(? AS timestamp) "
                            + "WHERE order_id = ? AND status = 'ACTIVE'", TOKEN_DEAD_AT, orderOne),
                    "the send kills exactly the order's one live link");
            return insertToken(orderOne, tokenHash(30), "ACTIVE", userId);
        });
        Assertions.assertNotNull(replacement);
        Assertions.assertEquals(List.of(replacement), activeTokenIds(orderOne),
                "after the replacement the new link is the order's only live link");
        Assertions.assertEquals("REPLACED", tokenStatus(firstActive), "the old link is REPLACED");
        Assertions.assertEquals(
                Map.of("ACTIVE", 1L, "REPLACED", 4L, "REWRITTEN", 2L, "SIGNED", 2L, "EXPIRED", 2L),
                tokenStatusCounts(orderOne), "order one after the replacement");
        Assertions.assertEquals(List.of(orderOne, seeded.orderTwo()), jdbcTemplate.queryForList(
                        "SELECT order_id FROM " + q("invoice_token") + " WHERE status = 'ACTIVE' ORDER BY order_id",
                        Long.class),
                "exactly one ACTIVE link per order across the table");
    }

    /**
     * The refusals, each reported by the named constraint or column: a token hash is never reused, across
     * orders, statuses or the same order ({@code uq_invoice_token_hash}); every required column refuses NULL
     * ({@code order_id}, {@code token_hash}, {@code status}, {@code sent_by_user_id}, {@code sent_at},
     * {@code expires_at}, and an explicit NULL {@code created_at}) while {@code emailed_at}/{@code dead_at}
     * accept values or NULL; unknown statuses, including the quote reasons and case or whitespace variants, are
     * refused by {@code chk_invoice_token_status}; {@code status} is capped at 16 and {@code token_hash} at 255
     * characters; a link for a missing order or from a missing user is refused by its foreign key; and an order or
     * a user that only an invoice link references cannot be deleted (no ON DELETE). Nothing refused is written.
     */
    @Test
    void v21_invoiceToken_refusesAReusedHash_nullRequiredColumns_unknownStatuses_overlongValues_andForeignKeyViolations() {
        UpgradedV20Database database = upgradePopulatedV20Database();
        SeededV20 seeded = database.seeded();
        long orderOne = seeded.orderOne();
        long orderTwo = seeded.orderTwo();
        long userId = seeded.userId();

        // ---- uq_invoice_token_hash: across orders, across statuses and on the same order ----
        String liveHash = tokenHash(1);
        String deadHash = tokenHash(2);
        insertToken(orderOne, liveHash, "ACTIVE", userId);
        insertToken(orderOne, deadHash, "REPLACED", userId);
        assertUniqueViolation("uq_invoice_token_hash", () -> insertToken(orderTwo, liveHash, "REPLACED", userId),
                "a live link's hash reused on another order's dead row");
        assertUniqueViolation("uq_invoice_token_hash", () -> insertToken(orderTwo, deadHash, "ACTIVE", userId),
                "a dead link's hash reused as another order's live link");
        assertUniqueViolation("uq_invoice_token_hash", () -> insertToken(orderOne, deadHash, "EXPIRED", userId),
                "a hash reused on the same order");

        // ---- NOT NULL on every required column; emailed_at and dead_at are optional ----
        int next = 100;
        for (String column : List.of("order_id", "token_hash", "status", "sent_by_user_id", "sent_at", "expires_at",
                "created_at")) {
            Map<String, Object> values = tokenValues(orderTwo, tokenHash(next++), "REPLACED", userId);
            values.put(column, null);
            assertNotNullViolation(column, () -> insertTokenRow(values),
                    "an invoice_token row with an explicit NULL " + column);
        }
        String deliveredHash = tokenHash(next++);
        Map<String, Object> delivered = tokenValues(orderTwo, deliveredHash, "REPLACED", userId);
        delivered.put("emailed_at", "2026-10-20 09:30:01");
        insertTokenRow(delivered);

        // ---- chk_invoice_token_status: only the five statuses ----
        for (String unknown : List.of("CANCELLED", "CONSUMED", "SUPERSEDED", "INACTIVE", "DRAFT", "PAID", "active",
                "Signed", "ACTIVE ", " EXPIRED", "")) {
            String hash = tokenHash(next++);
            assertCheckViolation("chk_invoice_token_status", () -> insertToken(orderTwo, hash, unknown, userId),
                    "the unknown status '" + unknown + "'");
        }

        // ---- VARCHAR(16) status and VARCHAR(255) token_hash ----
        String sixteenCharacters = "REPLACED-FOREVER";
        Assertions.assertEquals(16, sixteenCharacters.length());
        String hashForSixteen = tokenHash(next++);
        assertCheckViolation("chk_invoice_token_status",
                () -> insertToken(orderTwo, hashForSixteen, sixteenCharacters, userId),
                "a 16-character unknown status (it fits VARCHAR(16), so the CHECK refuses it)");
        String hashForSeventeen = tokenHash(next++);
        assertValueTooLong(() -> insertToken(orderTwo, hashForSeventeen, sixteenCharacters + "X", userId),
                "a 17-character status (over VARCHAR(16))");
        insertToken(orderTwo, "f".repeat(255), "REPLACED", userId);
        assertValueTooLong(() -> insertToken(orderTwo, "e".repeat(256), "REPLACED", userId),
                "a 256-character token_hash (over VARCHAR(255))");

        // ---- Foreign keys on insert ----
        long missingOrderId = unusedId("sales_order", "order_id");
        long missingUserId = unusedId("app_user", "user_id");
        String hashForMissingOrder = tokenHash(next++);
        assertForeignKeyViolation("fk_invoice_token_order",
                () -> insertToken(missingOrderId, hashForMissingOrder, "REPLACED", userId),
                "a link for an order that does not exist");
        String hashForMissingUser = tokenHash(next++);
        assertForeignKeyViolation("fk_invoice_token_sent_by",
                () -> insertToken(orderTwo, hashForMissingUser, "REPLACED", missingUserId),
                "a link sent by a user who does not exist");

        // ---- Foreign keys on delete: an order or a user only an invoice link references stays ----
        long secondUser = insertSecondSalesperson(seeded.businessId());
        long orderThree = insertOrderThree(seeded);
        String orderThreeHash = tokenHash(next);
        long orderThreeLink = insertToken(orderThree, orderThreeHash, "ACTIVE", secondUser);
        assertForeignKeyViolation("fk_invoice_token_order",
                () -> jdbcTemplate.update("DELETE FROM " + q("sales_order") + " WHERE order_id = ?", orderThree),
                "deleting an order an invoice link references");
        assertForeignKeyViolation("fk_invoice_token_sent_by",
                () -> jdbcTemplate.update("DELETE FROM " + q("app_user") + " WHERE user_id = ?", secondUser),
                "deleting the user who sent an invoice link");
        Assertions.assertEquals(List.of(orderThreeLink), activeTokenIds(orderThree), "the link survives");
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + q("sales_order") + " WHERE order_id = ?", Integer.class, orderThree));
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + q("app_user") + " WHERE user_id = ?", Integer.class, secondUser));

        // ---- Nothing refused was written ----
        Assertions.assertEquals(List.of(
                        orderOne + " ACTIVE " + liveHash,
                        orderOne + " REPLACED " + deadHash,
                        orderTwo + " REPLACED " + deliveredHash + " emailed",
                        orderTwo + " REPLACED " + "f".repeat(255),
                        orderThree + " ACTIVE " + orderThreeHash),
                jdbcTemplate.queryForList("SELECT order_id || ' ' || status || ' ' || token_hash"
                                + " || CASE WHEN emailed_at IS NULL THEN '' ELSE ' emailed' END "
                                + "FROM " + q("invoice_token") + " ORDER BY invoice_token_id", String.class),
                "only the accepted rows exist");
    }

    // ================================================================
    // chk_business_slug_reserved
    // ================================================================

    /**
     * After the upgrade there is still exactly ONE {@code chk_business_slug_reserved} (replaced in place, not
     * duplicated), the business table's CHECK constraints are the same four names, and only that constraint
     * lists reserved words: the 16 V18 words in V18's order, then {@code 'i'} (section 9.1). {@code 'i'} and,
     * still, {@code 'q'} are refused on INSERT and on UPDATE, and so is every one of the 17 words; refused
     * statements write nothing. Ordinary slugs and near misses ({@code 'ii'}, {@code 'i-flooring'},
     * {@code 'logins'}, ...) are accepted on INSERT and UPDATE: the rule is an exact-word deny-list.
     */
    @Test
    void v21_replacesChkBusinessSlugReserved_withTheSixteenV18WordsPlusI_refusingAllSeventeenOnInsertAndUpdate() {
        UpgradedV20Database database = upgradePopulatedV20Database();
        long businessId = database.seeded().businessId();

        Assertions.assertEquals(1, jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace
                        WHERE n.nspname = ? AND c.conname = 'chk_business_slug_reserved'
                        """, Integer.class, schema),
                "exactly one constraint named chk_business_slug_reserved");
        Assertions.assertEquals(List.of("chk_business_email_domain_not_blank", "chk_business_name_not_blank",
                        "chk_business_slug_format", "chk_business_slug_reserved"),
                businessCheckNames(), "the business CHECK constraints after V21");
        Assertions.assertEquals(List.of("chk_business_slug_reserved"), businessChecksListingReservedWords(),
                "no second reserved-slug constraint on business");
        Assertions.assertEquals(V21_RESERVED_SLUGS, reservedSlugWords(),
                "the 16 V18 words in V18's order, then 'i'");

        assertCheckViolation("chk_business_slug_reserved", () -> insertBusiness("I Flooring", "i"),
                "INSERT of a business with the slug 'i'");
        assertCheckViolation("chk_business_slug_reserved", () -> updateBusinessSlug(businessId, "i"),
                "UPDATE of a business slug to 'i'");
        assertCheckViolation("chk_business_slug_reserved", () -> insertBusiness("Q Flooring", "q"),
                "INSERT of a business with the slug 'q' (still reserved)");
        assertCheckViolation("chk_business_slug_reserved", () -> updateBusinessSlug(businessId, "q"),
                "UPDATE of a business slug to 'q' (still reserved)");
        for (String word : V21_RESERVED_SLUGS) {
            assertCheckViolation("chk_business_slug_reserved", () -> insertBusiness("Reserved " + word, word),
                    "INSERT of a business with the reserved slug '" + word + "'");
            assertCheckViolation("chk_business_slug_reserved", () -> updateBusinessSlug(businessId, word),
                    "UPDATE of a business slug to the reserved '" + word + "'");
        }
        Assertions.assertEquals(TENANT_SLUG, businessSlug(businessId), "a refused UPDATE changes nothing");
        Assertions.assertEquals(0, businessesWithSlugIn(V21_RESERVED_SLUGS), "a refused INSERT writes nothing");

        long ironbark = insertBusiness("Ironbark Flooring", "ironbark-flooring");
        Assertions.assertEquals("ironbark-flooring", businessSlug(ironbark), "an ordinary slug is accepted");
        List<String> nearMisses = List.of("ii", "i1", "i-flooring", "iq", "qi", "q1", "q-flooring", "logins",
                "public-floors", "new-south-flooring");
        for (String nearMiss : nearMisses) {
            long nearMissBusiness = insertBusiness("Near miss " + nearMiss, nearMiss);
            Assertions.assertEquals(nearMiss, businessSlug(nearMissBusiness),
                    "the near-miss slug '" + nearMiss + "' is accepted (exact-word rule)");
        }
        Assertions.assertEquals(1, updateBusinessSlug(ironbark, "ironbark-flooring-group"),
                "an UPDATE to an ordinary slug is accepted");
        Assertions.assertEquals(1, updateBusinessSlug(businessId, "v21up-renamed-flooring"),
                "the pre-existing business can still be renamed to an ordinary slug");
        Assertions.assertEquals("v21up-renamed-flooring", businessSlug(businessId));
    }

    // ================================================================
    // Atomicity: a database V21 cannot accept
    // ================================================================

    /**
     * A V20 database where a business already holds the slug {@code 'i'} (legal before V21): the V21 run fails
     * on the re-added CHECK (23514, {@code chk_business_slug_reserved} on {@code business}) and is rolled back as
     * a whole: still exactly V1 to V20 with no V21 history row (not even a failed one), no
     * {@code invoice_token}, the V18 constraint intact, and the full snapshot unchanged. Once that business is
     * renamed, V21 applies, and {@code 'i'} is refused from then on.
     */
    @Test
    void v21_onAV20DatabaseWhereABusinessAlreadyHoldsSlugI_failsAsAWhole_keepsTheExactV20State_andAppliesAfterARename() {
        populatedV20Database();
        long squatter = insertBusiness("I Flooring", "i");
        Assertions.assertEquals("i", businessSlug(squatter), "at V20 the slug 'i' is still allowed");
        SchemaSnapshot before = snapshot();

        FlywayException refused = Assertions.assertThrows(FlywayException.class,
                () -> flywayUpTo("21").migrate(),
                "V21 must refuse a database where a business already holds the slug 'i'");
        PSQLException cause = psqlCause(refused);
        Assertions.assertEquals("23514", cause.getSQLState(), "the re-added CHECK refuses the existing row");
        ServerErrorMessage serverError = cause.getServerErrorMessage();
        Assertions.assertNotNull(serverError, "the refusal must come from the server");
        Assertions.assertEquals("chk_business_slug_reserved", serverError.getConstraint());
        Assertions.assertEquals("business", serverError.getTable());

        Assertions.assertEquals(versionsOneTo(20), appliedSqlVersions(), "the scratch schema stays at V20");
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + q("flyway_schema_history") + " WHERE version = '21'", Integer.class),
                "a rolled-back V21 leaves no history row, not even a failed one");
        Assertions.assertFalse(tableExists("invoice_token"), "the rolled-back V21 leaves no invoice_token");
        Assertions.assertEquals(V18_RESERVED_SLUGS, reservedSlugWords(), "the V18 constraint stays in place");
        assertSameSchemaState(before, snapshot(), "after the refused V21");

        Assertions.assertEquals(1, updateBusinessSlug(squatter, "i-flooring"), "rename the business holding 'i'");
        applyV21Alone();
        Assertions.assertTrue(tableExists("invoice_token"), "V21 applies once no business holds 'i'");
        Assertions.assertEquals(V21_RESERVED_SLUGS, reservedSlugWords());
        assertCheckViolation("chk_business_slug_reserved", () -> updateBusinessSlug(squatter, "i"),
                "after V21 the slug 'i' is refused");
    }

    // ================================================================
    // The committed scripts
    // ================================================================

    /**
     * The V1–V20 scripts Flyway reads ({@code classpath:db/migration}) are each on the classpath exactly once,
     * under their main file names, with the SHA-256 of their main bytes (a committed migration is never
     * edited); exactly one V21 script exists, {@value #V21_SCRIPT}; versions 1 to 21 are all present with no
     * gap, duplicate, or other script among them.
     */
    @Test
    void migrationScripts_v1ToV20AreByteIdenticalToMain_andExactlyOneV21ScriptIsOnTheClasspath() throws IOException {
        Assertions.assertEquals(IntStream.rangeClosed(1, 20).boxed().toList(),
                MAIN_SCRIPTS.stream().map(MainScript::version).toList(), "the main hash table covers V1 to V20");

        Map<String, List<Resource>> resourcesByName = new TreeMap<>();
        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/*.sql")) {
            resourcesByName.computeIfAbsent(resource.getFilename(), name -> new ArrayList<>()).add(resource);
        }
        Map<Integer, List<String>> namesByVersion = new TreeMap<>();
        for (String name : resourcesByName.keySet()) {
            Matcher matcher = VERSIONED_SCRIPT.matcher(name);
            Assertions.assertTrue(matcher.matches(), "every db/migration script must be a plain versioned script: "
                    + name);
            namesByVersion.computeIfAbsent(Integer.valueOf(matcher.group(1)), version -> new ArrayList<>()).add(name);
        }

        for (MainScript script : MAIN_SCRIPTS) {
            Assertions.assertEquals(List.of(script.fileName()), namesByVersion.get(script.version()),
                    "exactly one V" + script.version() + " script, named as on main");
            List<Resource> copies = resourcesByName.get(script.fileName());
            Assertions.assertEquals(1, copies.size(), script.fileName() + " must be on the classpath exactly once");
            Assertions.assertEquals(script.sha256(), sha256Hex(bytesOf(copies.get(0))),
                    script.fileName() + " must be byte-identical to main (a committed migration is never edited)");
        }
        Assertions.assertEquals(List.of(V21_SCRIPT), namesByVersion.get(21), "exactly one V21 script");
        Assertions.assertEquals(1, resourcesByName.get(V21_SCRIPT).size(), V21_SCRIPT + " must be on the classpath once");
        Assertions.assertEquals(IntStream.rangeClosed(1, 21).boxed().toList(),
                namesByVersion.keySet().stream().filter(version -> version <= 21).toList(),
                "versions 1 to 21 are all present, with no gap");
    }

    // ================================================================
    // Fixture records
    // ================================================================

    /** A populated V20 database upgraded to V21, with the snapshot taken just before V21. */
    private record UpgradedV20Database(SeededV20 seeded, SchemaSnapshot beforeV21) {
    }

    /** The ids of the V20 fixture rows the tests use (all inside the scratch schema). */
    private record SeededV20(long businessId,
                             int storeId,
                             long userId,
                             long orderOne,
                             long orderTwo,
                             long orderOneFirstInvoiceId,
                             long orderOneCurrentInvoiceId,
                             long orderTwoInvoiceId,
                             long acceptedQuoteVersionId,
                             long issuedQuoteVersionId) {
    }

    /**
     * One V20 invoice version as the 16G code writes it: amounts as text, dates as 'yyyy-MM-dd', timestamps as
     * 'yyyy-MM-dd HH:mm:ss[.ffffff]'.
     */
    private record InvoiceSeed(long orderId,
                               String orderNumber,
                               int versionNumber,
                               String invoiceDate,
                               String dueDate,
                               String details,
                               String exGst,
                               String incGst,
                               String totalPaid,
                               String balanceDue,
                               String acceptedAt,
                               String acceptedCustomerName,
                               Long signatureFileId,
                               String lastEmailedAt,
                               Long sourceQuoteVersionId,
                               String termsSnapshot,
                               String nameSnapshot,
                               String line1Snapshot,
                               String line2Snapshot) {

        /** The next version (a payment, a void or a Rewrite): new date and amounts, same words, not emailed. */
        InvoiceSeed next(String nextInvoiceDate, String nextTotalPaid, String nextBalanceDue) {
            return new InvoiceSeed(orderId, orderNumber, versionNumber + 1, nextInvoiceDate, dueDate, details, exGst,
                    incGst, nextTotalPaid, nextBalanceDue, acceptedAt, acceptedCustomerName, signatureFileId, null,
                    sourceQuoteVersionId, termsSnapshot, nameSnapshot, line1Snapshot, line2Snapshot);
        }

        InvoiceSeed withTerms(String terms) {
            return new InvoiceSeed(orderId, orderNumber, versionNumber, invoiceDate, dueDate, details, exGst, incGst,
                    totalPaid, balanceDue, acceptedAt, acceptedCustomerName, signatureFileId, lastEmailedAt,
                    sourceQuoteVersionId, terms, nameSnapshot, line1Snapshot, line2Snapshot);
        }
    }

    /** One constraint ({@code constraint <contype>}) or index of the scratch schema, with its full definition. */
    private record SchemaObject(String table, String kind, String name, String definition) {
    }

    /**
     * Everything V21 could touch: per table the column shapes, every row ({@code to_jsonb}) and the physical
     * file; the constraints and indexes; the sequences; the relations; the enum types, functions and triggers;
     * and the scratch Flyway history.
     */
    private record SchemaSnapshot(Map<String, List<String>> columns,
                                  Map<String, List<String>> rows,
                                  Map<String, Long> fileNodes,
                                  List<SchemaObject> constraintsAndIndexes,
                                  Map<String, String> sequences,
                                  List<String> relations,
                                  List<String> otherObjects,
                                  List<String> history) {
    }

    private record ForeignKeyShape(String referencedSchema,
                                   String referencedTable,
                                   String columns,
                                   String referencedColumns,
                                   String onDelete,
                                   String onUpdate,
                                   String matchType,
                                   boolean deferrable,
                                   boolean validated) {
    }

    private record IndexShape(String name,
                              String method,
                              boolean unique,
                              boolean primary,
                              boolean immediate,
                              boolean valid,
                              String columns,
                              String predicate) {
    }

    // ================================================================
    // Lifecycle: the replay, the fixture and V21
    // ================================================================

    private UpgradedV20Database upgradePopulatedV20Database() {
        SeededV20 seeded = populatedV20Database();
        SchemaSnapshot beforeV21 = snapshot();
        applyV21Alone();
        return new UpgradedV20Database(seeded, beforeV21);
    }

    /**
     * A real V20 database (the committed V1–V20 scripts, applied by Flyway) holding the class fixture. Before
     * returning it proves the V20 starting point: no {@code invoice_token}, the V18 word list, {@code 'q'}
     * refused and {@code 'i'} still allowed (probed in a transaction that is rolled back).
     */
    private SeededV20 populatedV20Database() {
        Assertions.assertNull(schema, "one scratch schema per test");
        schema = SCRATCH_SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");

        flywayUpTo("20").migrate();
        Assertions.assertEquals(versionsOneTo(20), appliedSqlVersions(),
                "the scratch schema must hold exactly V1 to V20 before the upgrade under test");
        Assertions.assertFalse(tableExists("invoice_token"), "invoice_token must not exist before V21");
        Assertions.assertEquals(V18_RESERVED_SLUGS, reservedSlugWords(), "a V20 database carries the V18 words");

        SeededV20 seeded = seedV20History();

        assertCheckViolation("chk_business_slug_reserved", () -> updateBusinessSlug(seeded.businessId(), "q"),
                "at V20 the slug 'q' is already reserved");
        Assertions.assertEquals(1, updateBusinessSlugAndRollBack(seeded.businessId(), "i"),
                "at V20 the slug 'i' is still allowed");
        Assertions.assertEquals(TENANT_SLUG, businessSlug(seeded.businessId()), "the 'i' probe was rolled back");
        return seeded;
    }

    /** Applies V21 alone and checks that exactly V21 ran and was recorded as a successful SQL migration. */
    private void applyV21Alone() {
        MigrateResult toV21 = flywayUpTo("21").migrate();
        Assertions.assertEquals(1, toV21.migrationsExecuted, "exactly one migration must run in the upgrade");
        Assertions.assertEquals(List.of("21"),
                toV21.getSuccessfulMigrations().stream().map(migration -> migration.version).toList(),
                "the one migration applied by the upgrade must be V21");
        Assertions.assertEquals(versionsOneTo(21), appliedSqlVersions(),
                "the scratch schema must now hold exactly V1 to V21");
        Assertions.assertEquals(
                Map.of("description", V21_DESCRIPTION, "type", "SQL", "script", V21_SCRIPT, "success", true),
                jdbcTemplate.queryForMap("SELECT description, type, script, success FROM "
                        + q("flyway_schema_history") + " WHERE version = '21'"),
                "the V21 history row");
    }

    /** A Flyway for the scratch schema only: creates it, keeps its own history table, never cleans. */
    private Flyway flywayUpTo(String targetVersion) {
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
    private List<String> appliedSqlVersions() {
        return jdbcTemplate.queryForList(
                "SELECT version FROM " + q("flyway_schema_history")
                        + " WHERE success AND type = 'SQL' ORDER BY installed_rank",
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
        leftovers.forEach(leftover -> jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + quoteIdent(leftover) + " CASCADE"));
    }

    private void resetRecorders() {
        invoiceEmailSender.reset();
        invoiceLinkEmailSender.reset();
        invoiceAcceptanceNotificationSender.reset();
        quoteEmailSender.reset();
        quoteAcceptanceNotificationSender.reset();
    }

    private static String quoteIdent(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** A table of the running test's scratch schema, schema-qualified. */
    private String q(String table) {
        return quoteIdent(schema) + "." + quoteIdent(table);
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class,
                q(table)));
    }

    // ================================================================
    // The V20 fixture (schema-qualified, auto-commit)
    // ================================================================

    private SeededV20 seedV20History() {
        // ---- Our tenant: business (payment helpers, branding, terms), quick-adds, store, salesperson, catalog ----
        long businessId = insertRow("business", "business_id",
                "(name, email_domain, slug, logo_path, accent_colour, abn, bank_name, bsb, account_number, "
                        + " account_name, terms_and_conditions, stripe_payment_link_url, terms_hard, terms_soft) "
                        + "VALUES (?, 'v21up.example', ?, '/uploads/v21up/branding/logo.png', '#1F4E79', "
                        + "        '51 824 753 556', 'Upgrade Bank', '062-000', '1234 5678', "
                        + "        'V21 Upgrade Flooring Pty Ltd', ?, 'https://buy.stripe.com/test_v21up', ?, ?)",
                BUSINESS_NAME, TENANT_SLUG, BUSINESS_LEGACY_TERMS, BUSINESS_TERMS_HARD, BUSINESS_TERMS_SOFT);
        insertRow("business_quick_description", "business_quick_description_id",
                "(business_id, description, sort_order) VALUES (?, 'Supply and lay wool carpet', 0)", businessId);
        insertRow("business_quick_description", "business_quick_description_id",
                "(business_id, description, sort_order) VALUES (?, 'Uplift and dispose of old flooring', 1)",
                businessId);
        int storeId = Math.toIntExact(insertRow("store", "store_id",
                "(business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'V21 Upgrade Showroom', ?, '0290002121', ?, '21 Upgrade Street', 'Sydney', "
                        + "        'NSW', '2000')",
                businessId, STORE_CODE, STORE_EMAIL));
        long userId = insertRow("app_user", "user_id",
                "(business_id, first_name, last_name, salesperson_code, email, password_hash) "
                        + "VALUES (?, 'Una', 'Upgrade', ?, 'una.upgrade@v21up.example', 'v21up-not-a-login-hash')",
                businessId, SALESPERSON_CODE);
        insertRow("user_store_access", "user_store_access_id",
                "(business_id, user_id, store_id) VALUES (?, ?, ?)", businessId, userId, storeId);
        long productId = insertRow("store_product", "product_id",
                "(store_id, flooring_type, code, name, pricing_unit, price, cost) "
                        + "VALUES (?, 'SOFT', ?, 'Wool carpet (V21 upgrade)', 'SQM', 55.00, 30.00)",
                storeId, CODE_PREFIX + "CPT");
        long softCharge = insertRow("store_charge", "charge_id",
                "(store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT', ?, 'Carpet laying labour', 12.00, 8.00)",
                storeId, CODE_PREFIX + "LAB");
        long hardCharge = insertRow("store_charge", "charge_id",
                "(store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'HARD', ?, 'Timber laying labour', 40.00, 25.00)",
                storeId, CODE_PREFIX + "HLB");

        // ---- Order one: SOFT, ACCEPTED, lay date CONFIRMED; its details, payments and five invoice versions ----
        long orderOne = insertRow("sales_order", "order_id",
                "(business_id, store_id, user_id, order_sequence_number, order_number, flooring_type, order_status, "
                        + " supply_only, plan_numbers, proposed_lay_date, lay_date_status, sale_price_ex_gst, "
                        + " total_cost, gp, gp_percent, week_number, week_year, details_of_sale, last_emailed_at, "
                        + " price_adjustment_inc_gst) "
                        + "VALUES (?, ?, ?, 1, ?, 'SOFT', 'ACCEPTED', FALSE, 'Plan 21-A', DATE '2026-11-20', "
                        + "        'CONFIRMED', 1000.00, 556.32, 443.68, 44.37, 47, 2026, ?, NULL, NULL)",
                businessId, storeId, userId, ORDER_ONE_NUMBER, ORDER_ONE_DETAILS);
        insertRow("order_customer", "order_customer_id",
                "(order_id, first_name, middle_name, last_name, email, mobile, home_phone) "
                        + "VALUES (?, 'Harper', 'Quinn', 'Lee', 'harper.lee@v21up.example', '0412345678', "
                        + "        '0295552121')",
                orderOne);
        insertRow("order_address", "order_address_id",
                "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'INSTALLATION', NULL, '8', 'Wattle Street', 'Ultimo', 'NSW', '2007')",
                orderOne);
        insertRow("order_address", "order_address_id",
                "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'BILLING', '3', '21', 'Crown Street', 'Surry Hills', 'NSW', '2010')",
                orderOne);
        insertRow("order_product_line", "order_product_line_id",
                "(order_id, product_id, product_code_snapshot, product_name_snapshot, pricing_unit_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity_lm, quantity_sqm, unit_price, line_total, "
                        + " line_cost, sqm_per_lm_snapshot) "
                        + "VALUES (?, ?, ?, 'Wool carpet (V21 upgrade)', 'SQM', 55.00, 30.00, 4.00, 14.64, 55.00, "
                        + "        805.20, 439.20, 3.66)",
                orderOne, productId, CODE_PREFIX + "CPT");
        insertRow("order_charge_line", "order_charge_line_id",
                "(order_id, charge_id, charge_code_snapshot, charge_name_snapshot, price_snapshot, cost_snapshot, "
                        + " quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Carpet laying labour', 12.00, 8.00, 14.64, 12.00, 175.68, 117.12)",
                orderOne, softCharge, CODE_PREFIX + "LAB");
        insertRow("order_note", "order_note_id", "(order_id, note_text) VALUES (?, ?)",
                orderOne, "Customer prefers a morning lay. Gate code 2121.");
        insertRow("order_enquiry", "order_enquiry_id",
                "(order_id, lead_type, carpet, hard_floor, rooms_to_cover, fully_installed, uplift, furniture, "
                        + " stairs, subfloor_concrete, subfloor_timber, subfloor_tile, brief_summary) "
                        + "VALUES (?, 'FLOOR', TRUE, FALSE, 'Lounge and hallway', TRUE, NULL, FALSE, NULL, TRUE, "
                        + "        FALSE, FALSE, 'Wants a durable wool blend.')",
                orderOne);
        long photo = insertStoredFile(businessId, orderOne, "lounge-before.jpg", "image/jpeg");
        insertRow("order_attachment", "order_attachment_id",
                "(order_id, stored_file_id, attachment_kind) VALUES (?, ?, 'PHOTO')", orderOne, photo);
        insertRow("payment_transaction", "payment_transaction_id",
                "(order_id, payment_method, amount, payment_reference) VALUES (?, 'CASH', 200.00, 'Receipt 2101')",
                orderOne);
        insertRow("payment_transaction", "payment_transaction_id",
                "(order_id, payment_method, amount, payment_reference, voided_at, voided_by_user_id) "
                        + "VALUES (?, 'EFTPOS', 50.00, 'Receipt 2102', TIMESTAMP '2026-10-09 11:00:00', ?)",
                orderOne, userId);

        // v1: a pre-PR-2 Create - words from the V20 backfill, terms never backfilled (NULL).
        InvoiceSeed createVersion = new InvoiceSeed(orderOne, ORDER_ONE_NUMBER, 1, "2026-10-05", "2026-11-18",
                ORDER_ONE_DETAILS, "1000.00", "1100.00", "0.00", "1100.00", null, null, null, null, null, null,
                ORDER_ONE_NAME, ORDER_ONE_LINE1, ORDER_ONE_LINE2);
        long orderOneFirstInvoice = insertInvoice(businessId, userId, createVersion);
        // v2: a pre-PR-2 payment of 200.00 - the NULL terms copied.
        InvoiceSeed paymentVersion = createVersion.next("2026-10-06", "200.00", "900.00");
        insertInvoice(businessId, userId, paymentVersion);
        // v3: a PR 2 Rewrite - froze the saved name, billing lines and the business SOFT terms.
        InvoiceSeed rewriteVersion = paymentVersion.next("2026-10-07", "200.00", "900.00")
                .withTerms(BUSINESS_TERMS_SOFT);
        insertInvoice(businessId, userId, rewriteVersion);
        // v4: a payment of 50.00; v5: its void - both copy v3's words and terms verbatim.
        InvoiceSeed secondPaymentVersion = rewriteVersion.next("2026-10-08", "250.00", "850.00");
        insertInvoice(businessId, userId, secondPaymentVersion);
        long orderOneCurrentInvoice = insertInvoice(businessId, userId,
                secondPaymentVersion.next("2026-10-09", "200.00", "900.00"));

        // ---- Order two: HARD, LAID; the accepted quote, its Path A invoice, a later issued quote ----
        long orderTwo = insertRow("sales_order", "order_id",
                "(business_id, store_id, user_id, order_sequence_number, order_number, flooring_type, order_status, "
                        + " supply_only, plan_numbers, proposed_lay_date, lay_date_status, sale_price_ex_gst, "
                        + " total_cost, gp, gp_percent, week_number, week_year, details_of_sale, last_emailed_at, "
                        + " price_adjustment_inc_gst) "
                        + "VALUES (?, ?, ?, 2, ?, 'HARD', 'LAID', FALSE, NULL, DATE '2026-10-15', 'CONFIRMED', "
                        + "        2000.00, 1000.00, 1000.00, 50.00, 42, 2026, ?, TIMESTAMP '2026-10-10 16:05:00', "
                        + "        2200.00)",
                businessId, storeId, userId, ORDER_TWO_NUMBER, ORDER_TWO_DETAILS);
        insertRow("order_customer", "order_customer_id",
                "(order_id, first_name, middle_name, last_name, email, mobile, company_name) "
                        + "VALUES (?, ?, NULL, ?, 'zoe.obrien@v21up.example', '0498765432', 'Harbour Holdings')",
                orderTwo, "Zoë", "O’Brien-Nguyễn");
        insertRow("order_address", "order_address_id",
                "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'INSTALLATION', '12', '40', 'Harbour Road', 'Manly', 'NSW', '2095')",
                orderTwo);
        insertRow("order_address", "order_address_id",
                "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'BILLING', NULL, '40', 'Harbour Road', 'Manly', 'NSW', '2095')",
                orderTwo);
        insertRow("order_charge_line", "order_charge_line_id",
                "(order_id, charge_id, charge_code_snapshot, charge_name_snapshot, price_snapshot, cost_snapshot, "
                        + " quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Timber laying labour', 40.00, 25.00, 25.00, 40.00, 1000.00, 625.00)",
                orderTwo, hardCharge, CODE_PREFIX + "HLB");
        long draftId = insertRow("quote_draft", "quote_draft_id",
                "(order_id, itemised, quote_total_ex_gst, quote_total_inc_gst) VALUES (?, TRUE, 1900.00, 2090.00)",
                orderTwo);
        insertRow("quote_draft_line", "quote_draft_line_id",
                "(quote_draft_id, line_type, description, quantity, unit_price_ex_gst, line_total_ex_gst, sort_order) "
                        + "VALUES (?, 'ITEM', 'Spotted gum timber, supply and lay', 25.00, 76.00, 1900.00, 0)",
                draftId);

        long issuedPdfOne = insertStoredFile(businessId, orderTwo, "quote-" + ORDER_TWO_NUMBER + "-v1.pdf",
                "application/pdf");
        long quoteSignature = insertStoredFile(businessId, orderTwo,
                "quote-signature-" + ORDER_TWO_NUMBER + "-v1.png", "image/png");
        long signedPdfOne = insertStoredFile(businessId, orderTwo, "quote-signed-" + ORDER_TWO_NUMBER + "-v1.pdf",
                "application/pdf");
        long acceptedQuote = insertRow("quote_version", "quote_version_id",
                "(order_id, version_number, status, itemised, quote_total_ex_gst, quote_total_inc_gst, "
                        + " flooring_type_snapshot, terms_snapshot, details_of_sale_snapshot, sent_channel, "
                        + " first_sent_at, last_sent_at, last_emailed_at, viewed_at, accepted_at, "
                        + " accepted_customer_name, accepted_signature_file_id, issued_pdf_file_id, signed_pdf_file_id, "
                        + " created_by_user_id, customer_name_snapshot, customer_address_line1_snapshot, "
                        + " customer_address_line2_snapshot) "
                        + "VALUES (?, 1, 'ACCEPTED', TRUE, 2000.00, 2200.00, 'HARD', ?, ?, 'EMAIL', "
                        + "        TIMESTAMP '2026-10-09 09:00:00', TIMESTAMP '2026-10-09 12:30:00', "
                        + "        TIMESTAMP '2026-10-09 12:30:00', TIMESTAMP '2026-10-09 13:10:00', "
                        + "        CAST(? AS timestamp), ?, ?, ?, ?, ?, ?, ?, ?)",
                orderTwo, QUOTE_TERMS, ORDER_TWO_DETAILS, QUOTE_ACCEPTED_AT, ORDER_TWO_NAME, quoteSignature,
                issuedPdfOne, signedPdfOne, userId, ORDER_TWO_NAME, ORDER_TWO_LINE1, ORDER_TWO_LINE2);
        insertRow("quote_version_line", "quote_version_line_id",
                "(quote_version_id, line_type, description, quantity, unit_price_ex_gst, line_total_ex_gst, "
                        + " sort_order) "
                        + "VALUES (?, 'ITEM', 'Spotted gum timber, supply and lay', 25.00, 84.00, 2100.00, 0)",
                acceptedQuote);
        insertRow("quote_version_line", "quote_version_line_id",
                "(quote_version_id, line_type, description, quantity, unit_price_ex_gst, line_total_ex_gst, "
                        + " sort_order) "
                        + "VALUES (?, 'ADJUSTMENT', 'Loyalty reduction', NULL, NULL, -100.00, 1)",
                acceptedQuote);
        insertRow("quote_token", "quote_token_id",
                "(quote_version_id, token_hash, status, expires_at, dead_at) "
                        + "VALUES (?, ?, 'REPLACED', TIMESTAMP '2026-10-16 09:00:00', TIMESTAMP '2026-10-09 12:30:00')",
                acceptedQuote, sha256Hex("v21up-quote-token-replaced"));
        insertRow("quote_token", "quote_token_id",
                "(quote_version_id, token_hash, status, expires_at, dead_at) "
                        + "VALUES (?, ?, 'CONSUMED', TIMESTAMP '2026-10-16 12:30:00', CAST(? AS timestamp))",
                acceptedQuote, sha256Hex("v21up-quote-token-consumed"), QUOTE_ACCEPTED_AT);

        // The Path A version: the accepted quote's signature, accepted name and time, frozen terms, V17 identity.
        long orderTwoInvoice = insertInvoice(businessId, userId, new InvoiceSeed(orderTwo, ORDER_TWO_NUMBER, 1,
                "2026-10-10", "2026-10-13", ORDER_TWO_DETAILS, "2000.00", "2200.00", "0.00", "2200.00",
                QUOTE_ACCEPTED_AT, ORDER_TWO_NAME, quoteSignature, "2026-10-10 16:05:00", acceptedQuote, QUOTE_TERMS,
                ORDER_TWO_NAME, ORDER_TWO_LINE1, ORDER_TWO_LINE2));

        // A later quote v2, issued before the order was laid; its link is still ACTIVE.
        long issuedPdfTwo = insertStoredFile(businessId, orderTwo, "quote-" + ORDER_TWO_NUMBER + "-v2.pdf",
                "application/pdf");
        long issuedQuote = insertRow("quote_version", "quote_version_id",
                "(order_id, version_number, status, itemised, quote_total_ex_gst, quote_total_inc_gst, "
                        + " flooring_type_snapshot, terms_snapshot, details_of_sale_snapshot, sent_channel, "
                        + " first_sent_at, last_sent_at, last_emailed_at, issued_pdf_file_id, created_by_user_id, "
                        + " customer_name_snapshot, customer_address_line1_snapshot, customer_address_line2_snapshot) "
                        + "VALUES (?, 2, 'ISSUED', TRUE, 1900.00, 2090.00, 'HARD', ?, ?, 'EMAIL', "
                        + "        TIMESTAMP '2026-10-11 09:00:00', TIMESTAMP '2026-10-11 09:00:00', "
                        + "        TIMESTAMP '2026-10-11 09:00:00', ?, ?, ?, ?, ?)",
                orderTwo, QUOTE_TERMS, ORDER_TWO_DETAILS, issuedPdfTwo, userId, ORDER_TWO_NAME, ORDER_TWO_LINE1,
                ORDER_TWO_LINE2);
        insertRow("quote_version_line", "quote_version_line_id",
                "(quote_version_id, line_type, description, quantity, unit_price_ex_gst, line_total_ex_gst, "
                        + " sort_order) "
                        + "VALUES (?, 'ITEM', 'Spotted gum timber, supply and lay', 25.00, 76.00, 1900.00, 0)",
                issuedQuote);
        insertRow("quote_token", "quote_token_id",
                "(quote_version_id, token_hash, status, expires_at) "
                        + "VALUES (?, ?, 'ACTIVE', TIMESTAMP '2026-10-18 09:00:00')",
                issuedQuote, ACTIVE_QUOTE_TOKEN_HASH);

        return new SeededV20(businessId, storeId, userId, orderOne, orderTwo, orderOneFirstInvoice,
                orderOneCurrentInvoice, orderTwoInvoice, acceptedQuote, issuedQuote);
    }

    /** INSERT ... RETURNING the id into a scratch table, counted as a fixture row. */
    private long insertRow(String table, String idColumn, String columnsAndValues, Object... arguments) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO " + q(table) + " " + columnsAndValues + " RETURNING " + quoteIdent(idColumn),
                Long.class, arguments);
        Assertions.assertNotNull(id, table + " insert must return its id");
        seededRows.merge(table, 1, Integer::sum);
        return id;
    }

    private long insertStoredFile(long businessId, long orderId, String fileName, String mimeType) {
        return insertRow("stored_file", "stored_file_id",
                "(file_name, storage_path, mime_type, file_size) VALUES (?, ?, ?, ?)",
                fileName, "/uploads/" + businessId + "/orders/" + orderId + "/" + fileName, mimeType, 2048L);
    }

    /** One invoice version with its own PDF {@code stored_file}, created by our salesperson. */
    private long insertInvoice(long businessId, long createdByUserId, InvoiceSeed seed) {
        long pdfFileId = insertStoredFile(businessId, seed.orderId(),
                "invoice-" + seed.orderNumber() + "-v" + seed.versionNumber() + ".pdf", "application/pdf");
        return insertRow("invoice", "invoice_id",
                "(order_id, version_number, invoice_date, due_date, details_of_sale_snapshot, sale_price_ex_gst, "
                        + " sale_price_inc_gst, total_paid, balance_due, stored_file_id, created_by_user_id, "
                        + " accepted_at, accepted_customer_name, accepted_signature_file_id, last_emailed_at, "
                        + " source_quote_version_id, terms_snapshot, customer_name_snapshot, "
                        + " customer_address_line1_snapshot, customer_address_line2_snapshot) "
                        + "VALUES (?, ?, CAST(? AS date), CAST(? AS date), ?, ?, ?, ?, ?, ?, ?, "
                        + "        CAST(? AS timestamp), ?, ?, CAST(? AS timestamp), ?, ?, ?, ?, ?)",
                seed.orderId(), seed.versionNumber(), seed.invoiceDate(), seed.dueDate(), seed.details(),
                new BigDecimal(seed.exGst()), new BigDecimal(seed.incGst()), new BigDecimal(seed.totalPaid()),
                new BigDecimal(seed.balanceDue()), pdfFileId, createdByUserId, seed.acceptedAt(),
                seed.acceptedCustomerName(), seed.signatureFileId(), seed.lastEmailedAt(),
                seed.sourceQuoteVersionId(), seed.termsSnapshot(), seed.nameSnapshot(), seed.line1Snapshot(),
                seed.line2Snapshot());
    }

    // ================================================================
    // Writes after the upgrade (not fixture rows)
    // ================================================================

    private static String tokenHash(int sequence) {
        return sha256Hex("v21up-invoice-token-" + sequence);
    }

    /** The required columns of one link (dead rows also carry dead_at), in insertion order. */
    private static Map<String, Object> tokenValues(long orderId, String tokenHash, String status, long sentByUserId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("order_id", orderId);
        values.put("token_hash", tokenHash);
        values.put("status", status);
        values.put("sent_by_user_id", sentByUserId);
        values.put("sent_at", TOKEN_SENT_AT);
        values.put("expires_at", TOKEN_EXPIRES_AT);
        if (!"ACTIVE".equals(status)) {
            values.put("dead_at", TOKEN_DEAD_AT);
        }
        return values;
    }

    private long insertToken(long orderId, String tokenHash, String status, long sentByUserId) {
        return insertTokenRow(tokenValues(orderId, tokenHash, status, sentByUserId));
    }

    /** INSERT one invoice_token row from column values; a null value is written as an explicit SQL NULL. */
    private long insertTokenRow(Map<String, Object> values) {
        List<String> expressions = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();
        for (Map.Entry<String, Object> column : values.entrySet()) {
            if (column.getValue() == null) {
                expressions.add("NULL");
            } else if (TOKEN_TIMESTAMP_COLUMNS.contains(column.getKey())) {
                expressions.add("CAST(? AS timestamp)");
                arguments.add(column.getValue());
            } else {
                expressions.add("?");
                arguments.add(column.getValue());
            }
        }
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO " + q("invoice_token") + " (" + String.join(", ", values.keySet()) + ") VALUES ("
                        + String.join(", ", expressions) + ") RETURNING invoice_token_id",
                Long.class, arguments.toArray());
        Assertions.assertNotNull(id, "invoice_token insert must return its id");
        return id;
    }

    private long insertSecondSalesperson(long businessId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO " + q("app_user")
                        + " (business_id, first_name, last_name, salesperson_code, email, password_hash) "
                        + "VALUES (?, 'Uri', 'Upgrade', ?, 'uri.upgrade@v21up.example', 'v21up-not-a-login-hash') "
                        + "RETURNING user_id",
                Long.class, businessId, SECOND_SALESPERSON_CODE);
    }

    /** An order with no child rows at all (so only an invoice link can ever reference it). */
    private long insertOrderThree(SeededV20 seeded) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO " + q("sales_order")
                        + " (business_id, store_id, user_id, order_sequence_number, order_number, flooring_type, "
                        + "  order_status, week_number, week_year) "
                        + "VALUES (?, ?, ?, 3, ?, 'SOFT', 'LEAD', 43, 2026) RETURNING order_id",
                Long.class, seeded.businessId(), seeded.storeId(), seeded.userId(), ORDER_THREE_NUMBER);
    }

    /** An id no row of the table holds. */
    private long unusedId(String table, String idColumn) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(" + quoteIdent(idColumn) + "), 0) + 1000 FROM " + q(table), Long.class);
    }

    private long insertBusiness(String name, String slug) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO " + q("business") + " (name, slug) VALUES (?, ?) RETURNING business_id",
                Long.class, name, slug);
    }

    private int updateBusinessSlug(long businessId, String slug) {
        return jdbcTemplate.update("UPDATE " + q("business") + " SET slug = ? WHERE business_id = ?", slug, businessId);
    }

    /** The UPDATE's row count inside a transaction that is then rolled back. */
    private int updateBusinessSlugAndRollBack(long businessId, String slug) {
        Integer updated = transactionTemplate.execute(status -> {
            int rows = updateBusinessSlug(businessId, slug);
            status.setRollbackOnly();
            return rows;
        });
        return updated == null ? 0 : updated;
    }

    // ================================================================
    // Reads on the scratch schema
    // ================================================================

    private String businessSlug(long businessId) {
        return jdbcTemplate.queryForObject(
                "SELECT slug FROM " + q("business") + " WHERE business_id = ?", String.class, businessId);
    }

    private List<String> businessIdentity(long businessId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT slug, abn, bsb, account_number, stripe_payment_link_url FROM " + q("business")
                        + " WHERE business_id = ?", businessId);
        return Arrays.asList((String) row.get("slug"), (String) row.get("abn"), (String) row.get("bsb"),
                (String) row.get("account_number"), (String) row.get("stripe_payment_link_url"));
    }

    private int businessesWithSlugIn(List<String> slugs) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + q("business") + " WHERE slug IN ("
                        + String.join(", ", Collections.nCopies(slugs.size(), "?")) + ")",
                Integer.class, slugs.toArray());
    }

    /** [terms_snapshot, customer_name_snapshot, customer_address_line1_snapshot, customer_address_line2_snapshot]. */
    private List<String> invoiceTermsAndWords(long invoiceId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT terms_snapshot, customer_name_snapshot, customer_address_line1_snapshot, "
                        + "       customer_address_line2_snapshot FROM " + q("invoice") + " WHERE invoice_id = ?",
                invoiceId);
        return Arrays.asList((String) row.get("terms_snapshot"), (String) row.get("customer_name_snapshot"),
                (String) row.get("customer_address_line1_snapshot"),
                (String) row.get("customer_address_line2_snapshot"));
    }

    /** [total_paid, balance_due, accepted_at, accepted_customer_name, source_quote_version_id] as text. */
    private List<String> invoiceMoneyAndAcceptance(long invoiceId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT total_paid::text AS total_paid, balance_due::text AS balance_due, "
                        + "       accepted_at::text AS accepted_at, accepted_customer_name, "
                        + "       source_quote_version_id::text AS source_quote_version_id "
                        + "FROM " + q("invoice") + " WHERE invoice_id = ?",
                invoiceId);
        return Arrays.asList((String) row.get("total_paid"), (String) row.get("balance_due"),
                (String) row.get("accepted_at"), (String) row.get("accepted_customer_name"),
                (String) row.get("source_quote_version_id"));
    }

    private List<String> quoteTokenStatuses(long quoteVersionId) {
        return jdbcTemplate.queryForList(
                "SELECT status FROM " + q("quote_token") + " WHERE quote_version_id = ? ORDER BY quote_token_id",
                String.class, quoteVersionId);
    }

    private Map<String, Long> tokenStatusCounts(long orderId) {
        Map<String, Long> counts = new TreeMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT status, COUNT(*) AS n FROM " + q("invoice_token") + " WHERE order_id = ? GROUP BY status",
                orderId)) {
            counts.put((String) row.get("status"), ((Number) row.get("n")).longValue());
        }
        return counts;
    }

    private List<Long> activeTokenIds(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT invoice_token_id FROM " + q("invoice_token")
                        + " WHERE order_id = ? AND status = 'ACTIVE' ORDER BY invoice_token_id",
                Long.class, orderId);
    }

    private String tokenStatus(long invoiceTokenId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM " + q("invoice_token") + " WHERE invoice_token_id = ?", String.class,
                invoiceTokenId);
    }

    /** The words of {@code chk_business_slug_reserved}, in the order the constraint lists them. */
    private List<String> reservedSlugWords() {
        return quotedLiterals(jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(c.oid)
                FROM pg_constraint c
                JOIN pg_class t     ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = ? AND t.relname = 'business' AND c.conname = 'chk_business_slug_reserved'
                """, String.class, schema));
    }

    private List<String> businessCheckNames() {
        return sorted(jdbcTemplate.queryForList("""
                SELECT c.conname::text
                FROM pg_constraint c
                JOIN pg_class t     ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = ? AND t.relname = 'business' AND c.contype = 'c'
                """, String.class, schema));
    }

    /** The business CHECK constraints whose definition lists at least one of the 17 reserved words. */
    private List<String> businessChecksListingReservedWords() {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                SELECT c.conname::text AS name, pg_get_constraintdef(c.oid) AS definition
                FROM pg_constraint c
                JOIN pg_class t     ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = ? AND t.relname = 'business' AND c.contype = 'c'
                """, schema)) {
            if (quotedLiterals((String) row.get("definition")).stream().anyMatch(V21_RESERVED_SLUGS::contains)) {
                names.add((String) row.get("name"));
            }
        }
        return sorted(names);
    }

    private static List<String> quotedLiterals(String definition) {
        List<String> literals = new ArrayList<>();
        Matcher matcher = QUOTED_LITERAL.matcher(definition);
        while (matcher.find()) {
            literals.add(matcher.group(1).replace("''", "'"));
        }
        return literals;
    }

    /** information_schema shape of every invoice_token column, in ordinal order (see INVOICE_TOKEN_COLUMNS). */
    private List<String> invoiceTokenColumns() {
        return jdbcTemplate.queryForList("""
                SELECT column_name::text || ' | ' || data_type::text
                       || ' | ' || COALESCE(character_maximum_length::text, '-')
                       || ' | ' || is_nullable::text
                       || ' | ' || COALESCE(column_default::text, '-')
                       || ' | ' || is_identity::text || ' ' || COALESCE(identity_generation::text, '-')
                FROM information_schema.columns
                WHERE table_schema = ? AND table_name = 'invoice_token'
                ORDER BY ordinal_position
                """, String.class, schema);
    }

    /** invoice_token constraint name -> "contype definition" (NOT NULL entries of newer servers excluded). */
    private Map<String, String> invoiceTokenConstraints() {
        Map<String, String> constraints = new TreeMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                SELECT c.conname::text AS name, c.contype::text || ' ' || pg_get_constraintdef(c.oid) AS definition
                FROM pg_constraint c
                JOIN pg_class t     ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = ? AND t.relname = 'invoice_token' AND c.contype <> 'n'
                """, schema)) {
            constraints.put((String) row.get("name"), (String) row.get("definition"));
        }
        return constraints;
    }

    /** One named foreign key of invoice_token, with the schema, table and column(s) it references. */
    private ForeignKeyShape foreignKey(String constraintName) {
        List<ForeignKeyShape> shapes = jdbcTemplate.query("""
                SELECT rn.nspname::text AS referenced_schema,
                       rt.relname::text AS referenced_table,
                       (SELECT string_agg(a.attname::text, ',' ORDER BY a.attnum)
                          FROM pg_attribute a
                         WHERE a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey))   AS columns,
                       (SELECT string_agg(a.attname::text, ',' ORDER BY a.attnum)
                          FROM pg_attribute a
                         WHERE a.attrelid = c.confrelid AND a.attnum = ANY (c.confkey)) AS referenced_columns,
                       c.confdeltype::text   AS on_delete,
                       c.confupdtype::text   AS on_update,
                       c.confmatchtype::text AS match_type,
                       c.condeferrable       AS deferrable,
                       c.convalidated        AS validated
                FROM pg_constraint c
                JOIN pg_class t      ON t.oid = c.conrelid
                JOIN pg_namespace n  ON n.oid = t.relnamespace
                JOIN pg_class rt     ON rt.oid = c.confrelid
                JOIN pg_namespace rn ON rn.oid = rt.relnamespace
                WHERE c.contype = 'f' AND n.nspname = ? AND t.relname = 'invoice_token' AND c.conname = ?
                """, (rs, rowNum) -> new ForeignKeyShape(
                        rs.getString("referenced_schema"),
                        rs.getString("referenced_table"),
                        rs.getString("columns"),
                        rs.getString("referenced_columns"),
                        rs.getString("on_delete"),
                        rs.getString("on_update"),
                        rs.getString("match_type"),
                        rs.getBoolean("deferrable"),
                        rs.getBoolean("validated")),
                schema, constraintName);
        Assertions.assertEquals(1, shapes.size(), constraintName + " must exist exactly once on invoice_token");
        return shapes.get(0);
    }

    /** Every index on invoice_token (pg_index), sorted by name. */
    private List<IndexShape> invoiceTokenIndexes() {
        List<IndexShape> indexes = new ArrayList<>(jdbcTemplate.query("""
                SELECT ic.relname::text  AS name,
                       am.amname::text   AS method,
                       i.indisunique     AS is_unique,
                       i.indisprimary    AS is_primary,
                       i.indimmediate    AS is_immediate,
                       i.indisvalid      AS is_valid,
                       (SELECT string_agg(a.attname::text, ',' ORDER BY k.ord)
                          FROM unnest(i.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)
                          JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum) AS columns,
                       pg_get_expr(i.indpred, i.indrelid) AS predicate
                FROM pg_index i
                JOIN pg_class ic    ON ic.oid = i.indexrelid
                JOIN pg_class t     ON t.oid = i.indrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                JOIN pg_am am       ON am.oid = ic.relam
                WHERE n.nspname = ? AND t.relname = 'invoice_token'
                """, (rs, rowNum) -> new IndexShape(
                        rs.getString("name"),
                        rs.getString("method"),
                        rs.getBoolean("is_unique"),
                        rs.getBoolean("is_primary"),
                        rs.getBoolean("is_immediate"),
                        rs.getBoolean("is_valid"),
                        rs.getString("columns"),
                        rs.getString("predicate")),
                schema));
        indexes.sort(Comparator.comparing(IndexShape::name));
        return indexes;
    }

    // ================================================================
    // The schema snapshot
    // ================================================================

    private SchemaSnapshot snapshot() {
        Map<String, List<String>> columns = columnShapes();
        Map<String, List<String>> rows = new TreeMap<>();
        for (String table : columns.keySet()) {
            rows.put(table, sorted(jdbcTemplate.queryForList(
                    "SELECT to_jsonb(t)::text FROM " + q(table) + " t", String.class)));
        }
        return new SchemaSnapshot(columns, rows, fileNodes(), constraintsAndIndexes(), sequences(), relations(),
                otherObjects(), history());
    }

    /** Base tables (minus Flyway's history) -> one shape line per column, in ordinal order. */
    private Map<String, List<String>> columnShapes() {
        Map<String, List<String>> columns = new TreeMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                SELECT c.table_name::text AS table_name,
                       c.column_name::text || ' | ' || c.data_type::text || ' ' || c.udt_name::text
                       || ' | ' || COALESCE(c.character_maximum_length::text, '-')
                       || ' | ' || COALESCE(c.numeric_precision::text, '-') || ',' || COALESCE(c.numeric_scale::text, '-')
                       || ' | ' || COALESCE(c.datetime_precision::text, '-')
                       || ' | ' || c.is_nullable::text
                       || ' | ' || COALESCE(c.column_default::text, '-')
                       || ' | ' || c.is_identity::text || ' ' || COALESCE(c.identity_generation::text, '-') AS shape
                FROM information_schema.columns c
                JOIN information_schema.tables t
                  ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                WHERE c.table_schema = ? AND t.table_type = 'BASE TABLE'
                  AND c.table_name <> 'flyway_schema_history'
                ORDER BY c.table_name, c.ordinal_position
                """, schema)) {
            columns.computeIfAbsent((String) row.get("table_name"), table -> new ArrayList<>())
                    .add((String) row.get("shape"));
        }
        return columns;
    }

    /** Ordinary table -> relfilenode (changes only when PostgreSQL rewrites the table). */
    private Map<String, Long> fileNodes() {
        Map<String, Long> nodes = new TreeMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                SELECT c.relname::text AS table_name, c.relfilenode::bigint AS file_node
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history'
                """, schema)) {
            nodes.put((String) row.get("table_name"), ((Number) row.get("file_node")).longValue());
        }
        return nodes;
    }

    /** Every constraint and index (table, kind, name, full definition) of the application tables, sorted. */
    private List<SchemaObject> constraintsAndIndexes() {
        List<SchemaObject> objects = new ArrayList<>(jdbcTemplate.query("""
                SELECT t.relname::text AS table_name, 'constraint ' || c.contype::text AS kind,
                       c.conname::text AS name, pg_get_constraintdef(c.oid) AS definition
                FROM pg_constraint c
                JOIN pg_class t     ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = ? AND t.relname <> 'flyway_schema_history' AND c.contype <> 'n'
                UNION ALL
                SELECT tablename::text, 'index', indexname::text, indexdef
                FROM pg_indexes
                WHERE schemaname = ? AND tablename <> 'flyway_schema_history'
                """, (rs, rowNum) -> new SchemaObject(rs.getString("table_name"), rs.getString("kind"),
                        rs.getString("name"), rs.getString("definition")),
                schema, schema));
        objects.sort(SCHEMA_OBJECT_ORDER);
        return objects;
    }

    /** Sequence -> last_value ("unused" before the first nextval). */
    private Map<String, String> sequences() {
        Map<String, String> sequences = new TreeMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT sequencename::text AS name, COALESCE(last_value::text, 'unused') AS last_value "
                        + "FROM pg_sequences WHERE schemaname = ?", schema)) {
            sequences.put((String) row.get("name"), (String) row.get("last_value"));
        }
        return sequences;
    }

    /** Every relation of the scratch schema as "relkind name" (tables, sequences, indexes), sorted. */
    private List<String> relations() {
        return sorted(jdbcTemplate.queryForList(
                "SELECT c.relkind::text || ' ' || c.relname::text FROM pg_class c "
                        + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ?",
                String.class, schema));
    }

    /** The scratch schema's enum types (with their labels), functions and user triggers, sorted. */
    private List<String> otherObjects() {
        return sorted(jdbcTemplate.queryForList("""
                SELECT 'enum ' || t.typname::text || ' ' || string_agg(e.enumlabel::text, ',' ORDER BY e.enumsortorder)
                FROM pg_type t
                JOIN pg_namespace n ON n.oid = t.typnamespace
                JOIN pg_enum e      ON e.enumtypid = t.oid
                WHERE n.nspname = ?
                GROUP BY t.typname
                UNION ALL
                SELECT 'function ' || p.proname::text || '(' || pg_get_function_identity_arguments(p.oid) || ')'
                FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = ?
                UNION ALL
                SELECT 'trigger ' || c.relname::text || '.' || g.tgname::text
                FROM pg_trigger g
                JOIN pg_class c     ON c.oid = g.tgrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND NOT g.tgisinternal
                """, String.class, schema, schema, schema));
    }

    /** The scratch Flyway history, one {@code to_jsonb} row per entry, in installed order. */
    private List<String> history() {
        return jdbcTemplate.queryForList(
                "SELECT to_jsonb(h)::text FROM " + q("flyway_schema_history") + " h ORDER BY h.installed_rank",
                String.class);
    }

    private static void assertSameSchemaState(SchemaSnapshot expected, SchemaSnapshot actual, String context) {
        Assertions.assertEquals(expected.columns().keySet(), actual.columns().keySet(), context + ": tables");
        for (String table : expected.columns().keySet()) {
            Assertions.assertEquals(expected.columns().get(table), actual.columns().get(table),
                    context + ": columns of " + table);
            Assertions.assertEquals(expected.rows().get(table), actual.rows().get(table),
                    context + ": rows of " + table);
        }
        Assertions.assertEquals(expected.fileNodes(), actual.fileNodes(), context + ": physical files");
        Assertions.assertEquals(expected.constraintsAndIndexes(), actual.constraintsAndIndexes(),
                context + ": constraints and indexes");
        Assertions.assertEquals(expected.sequences(), actual.sequences(), context + ": sequences");
        Assertions.assertEquals(expected.relations(), actual.relations(), context + ": relations");
        Assertions.assertEquals(expected.otherObjects(), actual.otherObjects(),
                context + ": enum types, functions and triggers");
        Assertions.assertEquals(expected.history(), actual.history(), context + ": Flyway history");
    }

    private static SchemaObject single(List<SchemaObject> objects, Predicate<SchemaObject> filter, String what) {
        List<SchemaObject> matching = objects.stream().filter(filter).toList();
        Assertions.assertEquals(1, matching.size(), what + ": " + matching);
        return matching.get(0);
    }

    private static List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }

    // ================================================================
    // Database refusals
    // ================================================================

    /** The PostgreSQL server error behind a refused statement (fails when the statement is accepted). */
    private static PSQLException serverError(Executable statement, String what) {
        DataAccessException refused = Assertions.assertThrows(DataAccessException.class, statement,
                () -> what + " must be refused by the database");
        Throwable cause = refused.getMostSpecificCause();
        if (cause instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
            return psql;
        }
        return Assertions.fail(what + ": expected a PostgreSQL server error, got " + cause, refused);
    }

    private static void assertRefusedByConstraint(String sqlState, String constraint, Executable statement,
                                                  String what) {
        PSQLException error = serverError(statement, what);
        Assertions.assertEquals(sqlState, error.getSQLState(), what + ": SQLState (" + error.getMessage() + ")");
        Assertions.assertEquals(constraint, error.getServerErrorMessage().getConstraint(),
                what + ": the refusing constraint (" + error.getMessage() + ")");
    }

    private static void assertUniqueViolation(String constraint, Executable statement, String what) {
        assertRefusedByConstraint("23505", constraint, statement, what);
    }

    private static void assertForeignKeyViolation(String constraint, Executable statement, String what) {
        assertRefusedByConstraint("23503", constraint, statement, what);
    }

    private static void assertCheckViolation(String constraint, Executable statement, String what) {
        assertRefusedByConstraint("23514", constraint, statement, what);
    }

    private static void assertNotNullViolation(String column, Executable statement, String what) {
        PSQLException error = serverError(statement, what);
        Assertions.assertEquals("23502", error.getSQLState(), what + ": SQLState (" + error.getMessage() + ")");
        Assertions.assertEquals("invoice_token", error.getServerErrorMessage().getTable(), what + ": table");
        Assertions.assertEquals(column, error.getServerErrorMessage().getColumn(), what + ": column");
    }

    private static void assertValueTooLong(Executable statement, String what) {
        PSQLException error = serverError(statement, what);
        Assertions.assertEquals("22001", error.getSQLState(),
                what + ": SQLState, value too long (" + error.getMessage() + ")");
    }

    private static PSQLException psqlCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
                return psql;
            }
        }
        return Assertions.fail("expected a PostgreSQL server error in the cause chain of " + failure, failure);
    }

    // ================================================================
    // Hashing and resources
    // ================================================================

    private static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static byte[] bytesOf(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        }
    }
}
