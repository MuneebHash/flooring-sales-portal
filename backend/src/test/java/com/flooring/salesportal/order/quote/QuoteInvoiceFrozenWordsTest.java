package com.flooring.salesportal.order.quote;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.email.InvoiceEmailRequest;
import com.flooring.salesportal.common.email.QuoteEmailRequest;
import com.flooring.salesportal.common.email.RecordingInvoiceEmailSender;
import com.flooring.salesportal.common.email.RecordingQuoteAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import com.flooring.salesportal.common.sms.RecordingSmsSender;
import com.flooring.salesportal.order.InvoiceRepository;
import com.flooring.salesportal.order.InvoiceRepository.InvoiceRow;
import com.flooring.salesportal.order.InvoiceTermsSanitizer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 16G PR 2 (#125) - the frozen invoice words on Path A
 * ({@code POST /api/v1/{slug}/orders/{orderId}/quote/create-invoice}) and on every version that follows
 * it (docs/API-Contracts-Phase16G-Invoice-Link.md section 4 and the PR 2 row of section 16).
 *
 * <p>The rule under test: a version's "words" are its Invoice To name, its two billing lines and its terms
 * ({@code invoice.customer_name_snapshot}, {@code customer_address_line1_snapshot},
 * {@code customer_address_line2_snapshot} - all V20 - and {@code terms_snapshot}). Path A copies them
 * VERBATIM, nulls included, from the accepted quote version's own V17 identity columns and its frozen
 * {@code terms_snapshot}; payment, void and resend never re-read the live customer, address or business
 * terms; a Rewrite freezes fresh BUSINESS words; and the shared insert boundary still refuses a source
 * quote version of another order. Every version an endpoint writes is checked on its persisted row (SQL),
 * its InvoiceDetail (exact key set, {@code terms_source}) and its stored PDF read from disk (Invoice To
 * block, page-2 terms).
 *
 * <p><b>Why each test fails on the pre-PR-2 code (16F).</b> Before PR 2 every invoice render, Path A and
 * payment / void included, built Invoice To from the LIVE saved customer and BILLING address, a Path B
 * version re-read the business's terms at every render and read ({@code terms_source LIVE}), Create and
 * Rewrite wrote {@code terms_snapshot} NULL, the V20 columns did not exist and InvoiceDetail had no
 * identity keys. So in every scenario below the live state is deliberately moved AWAY from the expected
 * frozen words before the version is written (and again afterwards), and the tests assert that the live
 * values appear nowhere: on the 16F code the PDFs would show the live identity and the API / rows would
 * lack the frozen words. (Path A already copied the quote's frozen TERMS in 16F; those terms checks are
 * kept as the PR 2 "verbatim incl. null, never live" rule, but every test also carries an identity or
 * BUSINESS-terms assertion that only PR 2 satisfies.)
 *
 * <p><b>Fixtures - fully self-seeded.</b> {@link #seedTenant} inserts this class's own business (slug
 * {@value #SLUG}, ABN and bank details, no terms until a test sets them), store, salesperson and store
 * access; every order, customer, address, charge line and quote is seeded under that tenant, and the
 * session is that tenant's user / business / store. Nothing reads a V4 demo identity or row. Accepted
 * quotes always come from the real chain: protected draft PUT, protected send-email (the V17 identity and
 * the per-type business terms are frozen at that send), the token read from the recorded link-only quote
 * email, and the PUBLIC multipart accept with no session. Raw SQL only seeds rows (including the BILLING
 * row the null-words test adds after the acceptance), moves the live customer, BILLING address and
 * business terms between versions, and in two tests overwrites stored words with values no writer
 * produces (a Path A version's identity as V20 backfilled it; an accepted quote version's padded,
 * unsanitised words), each write followed by an {@link EntityManager#clear()} so the next request
 * re-reads it. The only rows no endpoint produces are those of the ownership test: a bare
 * quote_version row for the "other order" and a stored_file row for its direct
 * {@link InvoiceRepository#insertInvoice} call. Everything runs in the test transaction and rolls back; the
 * services' rollback hooks delete the files they wrote under the storage root this class shares with the
 * other quote suites (inside this test's own business and order directories). The recording
 * senders are singletons, so they are reset before AND after every test.
 *
 * <p><b>Amended for Phase 16G PR 3</b> (#123; contract sections 5.5 and 6): every Path A request carries the
 * mandatory {@code {"quote_version_id": N}} (N = the order's latest ACCEPTED quote version id, read from the
 * database), and every successful Path A emails the signed PDF of the version it appended after the
 * conversion: the delivered message, exactly one invoice email whose attachment is that version's stored
 * PDF byte for byte (so it carries exactly the frozen words checked here), no quote email, store
 * notification or SMS, and (in this transactional context) the success stamp on that version and the
 * dashboard mirror. Payment and void still never email.
 */
@ExtendWith(SpringExtension.class)
// The SAME storage property as the other quote suites, so this class joins their cached Spring context
// (each distinct property set starts another context with its own connection pool). Files stay apart:
// every PDF and signature lives under this test's own business and order directories.
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
@Transactional
class QuoteInvoiceFrozenWordsTest {

    /** Must equal the {@code @SpringBootTest} property above (asserted in {@link #setUp}). */
    private static final String STORAGE_BASE_DIR = "target/test-storage/quote-acceptance";

    // ---- The self-seeded tenant ----
    private static final String SLUG = "pr2-frozen-words-b2";
    private static final String BUSINESS_NAME = "Frozen Words Flooring";
    private static final String STORE_CODE = "FWB2";
    private static final String SALESPERSON_CODE = "FW2";
    private static final String ORDER_NUMBER_PREFIX = STORE_CODE + "." + SALESPERSON_CODE + ".";
    private static final String CUSTOMER_EMAIL = "frozen.words.customer@example.com";
    private static final String CUSTOMER_MOBILE = "0412345678";
    private static final String FROZEN_DETAILS = "Supply and lay carpet for the frozen words checks";

    private static final String SOFT = "SOFT";
    private static final String HARD = "HARD";

    // ---- Customer identities. The parts are what is saved; name / line1 / line2 are the Invoice To
    // strings they must freeze to, written out literally (never re-derived by test code). ----

    /** Saved when quote v1 is issued, so it is the accepted version's V17 identity. */
    private static final Identity QUOTED = new Identity(
            "Quinn", null, "Signedquote", "4", "12", "Snapshot Street", "Paddington", "NSW", "2021",
            "Quinn Signedquote", "4/12 Snapshot Street", "Paddington NSW 2021");
    /** Saved when a NEWER quote v2 is issued after v1 was accepted (v2 stays ISSUED). */
    private static final Identity NEWER_QUOTED = new Identity(
            "Yara", "Belle", "Newerissue", null, "88", "Supersede Avenue", "Carlton", "VIC", "3053",
            "Yara Belle Newerissue", "88 Supersede Avenue", "Carlton VIC 3053");
    /** The live saved identity when Path A (or the Rewrite) runs. Middle name and unit both present. */
    private static final Identity LIVE_AT_CONVERSION = new Identity(
            "Zane", "Kit", "Liverename", "7", "300", "Liveafter Road", "Fremantle", "WA", "6160",
            "Zane Kit Liverename", "7/300 Liveafter Road", "Fremantle WA 6160");
    /** Saved AFTER the version under test was written (payment / void / D.3 must never pick it up). */
    private static final Identity LIVE_LATER = new Identity(
            "Wren", null, "Laterlive", null, "5", "Postconvert Lane", "Hobart", "TAS", "7000",
            "Wren Laterlive", "5 Postconvert Lane", "Hobart TAS 7000");
    /** Saved when an earlier, unsigned Path B invoice (D.1) was frozen. */
    private static final Identity PATH_B_ERA = new Identity(
            "Pia", null, "Pathbefore", null, "21", "Earlier Parade", "Manly", "NSW", "2095",
            "Pia Pathbefore", "21 Earlier Parade", "Manly NSW 2095");

    // ---- Business terms. SOFT and HARD differ so the per-type pick is visible; each raw value carries
    // markup the production sanitizer rewrites, so a frozen value equal to the SANITISED form (and unequal
    // to the raw one) proves where and when it was sanitised. ----
    private static final Terms ISSUE_TERMS =
            new Terms("Quote terms frozen at issue QF1 soft", "Quote terms frozen at issue QF1 hard");
    private static final Terms NEWER_ISSUE_TERMS =
            new Terms("Newer issued quote terms QN2 soft", "Newer issued quote terms QN2 hard");
    private static final Terms CONVERSION_TERMS =
            new Terms("Business terms at conversion QC3 soft", "Business terms at conversion QC3 hard");
    private static final Terms LATER_TERMS =
            new Terms("Business terms edited later QL4 soft", "Business terms edited later QL4 hard");
    private static final Terms PATH_B_ERA_TERMS =
            new Terms("Business terms at the Path B invoice QB0 soft", "Business terms at the Path B invoice QB0 hard");

    private static final String PDF_FOOTER = "Generated by the Flooring Sales Portal";
    // The "Terms & Conditions" heading renders uppercase (CSS); page 1 only ever says "terms" in lowercase.
    private static final String TERMS_HEADING = "TERMS";
    // The Invoice To eyebrow and the heading of the next meta column render uppercase (CSS).
    private static final String INVOICE_TO_HEADING = "INVOICE TO";
    private static final String NEXT_COLUMN_HEADING = "ISSUED / DUE";

    // 16G PR 3 (contract 6.1): the Path A 201 message when the post-commit signed-PDF email was delivered.
    private static final String CREATED_FROM_QUOTE_MESSAGE =
            "Invoice created from accepted quote and emailed to the customer.";

    /**
     * The InvoiceDetail keys, ALWAYS present: the 20 Phase 12 / 13 / 16F keys, the three PR 2 frozen identity
     * keys and the derived invoice_link_expires_at (PR 4, contract 4.5).
     */
    private static final Set<String> INVOICE_DETAIL_KEYS = Set.of(
            "invoice_id", "order_id", "version_number", "invoice_date", "due_date",
            "details_of_sale_snapshot", "sale_price_ex_gst", "sale_price_inc_gst", "total_paid", "balance_due",
            "created_by_user_id", "created_at", "pdf_download_path",
            "customer_name_snapshot", "customer_address_line1_snapshot", "customer_address_line2_snapshot",
            "accepted_at", "accepted_customer_name", "accepted_signature_present",
            "accepted_signature_download_path", "last_emailed_at", "terms_html", "terms_source",
            // 16G PR 4: the derived link field, always present (nullable; never on E.1 or a public payload).
            "invoice_link_expires_at");

    /** E.1 CurrentInvoiceSummary (payment / void responses): UNCHANGED by PR 2 - no identity, no terms. */
    private static final Set<String> CURRENT_INVOICE_SUMMARY_KEYS = Set.of(
            "invoice_id", "version_number", "invoice_date", "due_date", "sale_price_inc_gst", "total_paid",
            "balance_due", "created_by_user_id", "created_at", "pdf_download_path", "accepted_at",
            "accepted_customer_name", "accepted_signature_present", "accepted_signature_download_path",
            "last_emailed_at");

    // The plaintext token inside the delivered link .../q/{token} (URL-safe Base64).
    private static final Pattern PUBLIC_LINK_TOKEN = Pattern.compile("/q/([A-Za-z0-9_-]{43,128})");

    // A real, decodable 1x1 PNG (the public accept decodes and re-encodes the upload).
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    // Exact money comparisons: JSON decimals are read as BigDecimal (never through a double).
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private InvoiceRepository invoiceRepository;

    // The production sanitizer: a frozen business-terms value must equal exactly what it makes of the raw terms.
    @Autowired
    private InvoiceTermsSanitizer termsSanitizer;

    @Autowired
    private RecordingInvoiceEmailSender invoiceEmailSender;

    @Autowired
    private RecordingQuoteEmailSender quoteEmailSender;

    @Autowired
    private RecordingQuoteAcceptanceNotificationSender acceptanceNotificationSender;

    // Path A never sends an SMS (16G PR 3 keeps it email only); SMS is dormant and recording-only.
    @Autowired
    private RecordingSmsSender smsSender;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    private MockMvc mockMvc;

    /** This test's own tenant (seeded inside the test transaction by {@link #setUp}). */
    private Tenant tenant;

    private int orderSeq;
    private int codeSeq;

    @BeforeEach
    void setUp() {
        Assertions.assertEquals(STORAGE_BASE_DIR, storageBaseDir,
                "this class reads stored files under its own storage base dir");
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        resetSenders();
        tenant = seedTenant();
    }

    @AfterEach
    void tearDown() {
        // Singletons: never leak recorded messages or an armed failNextSend into another test or class.
        resetSenders();
    }

    private void resetSenders() {
        invoiceEmailSender.reset();
        quoteEmailSender.reset();
        acceptanceNotificationSender.reset();
        smsSender.reset();
    }

    // ================================================================
    // Fixture types
    // ================================================================

    /** The self-seeded tenant: one business, one store, one salesperson with access to that store. */
    private record Tenant(long businessId, int storeId, long userId, String slug) {

        /** The RequestContextGuard session. Type trap: (Long) user_id / business_id, (Integer) store_id. */
        MockHttpSession session() {
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("user_id", userId);
            session.setAttribute("business_id", businessId);
            session.setAttribute("store_id", storeId);
            return session;
        }
    }

    /**
     * A customer as saved (order_customer name parts + the BILLING order_address parts) and the Invoice To
     * strings it must freeze to ({@code name}, {@code line1}, {@code line2}), spelled out literally.
     */
    private record Identity(String firstName, String middleName, String lastName,
                            String unitNumber, String streetNumber, String street,
                            String suburb, String stateCode, String postcode,
                            String name, String line1, String line2) {

        /** Text unique to this identity, for the "appears nowhere" checks (no shared postcodes or numbers). */
        List<String> markers() {
            return List.of(name, lastName, street, suburb);
        }
    }

    /** Per-flooring-type business terms: the visible text, and the raw HTML stored on the business row. */
    private record Terms(String softText, String hardText) {

        String softRaw() {
            return "<p class=\"terms\" onclick=\"steal()\">" + softText + "</p>";
        }

        String hardRaw() {
            return "<div id=\"hard-terms\"><strong>" + hardText + "</strong></div>";
        }

        List<String> texts() {
            return List.of(softText, hardText);
        }
    }

    /**
     * One version's words: the Invoice To name, the two billing lines and the stored terms HTML. Record
     * equality compares all four exactly (null-safe), so one assertEquals proves every word at once.
     */
    private record Words(String name, String line1, String line2, String termsHtml) {
    }

    // ================================================================
    // Helpers - self-seeding
    // ================================================================

    /**
     * Business (active, ABN + bank details, NO per-type terms), its store, one salesperson and that
     * salesperson's store access, all with RETURNING ids. The slug matches chk_business_slug_format, is not
     * a reserved word, and is unique (the whole tenant rolls back with the test).
     */
    private Tenant seedTenant() {
        Long businessId = jdbcTemplate.queryForObject(
                "INSERT INTO business (name, slug, is_active, abn, bank_name, bsb, account_name, account_number, "
                        + " terms_soft, terms_hard) "
                        + "VALUES (?, ?, TRUE, '51 824 753 556', 'Frozen Words Bank', '062-000', "
                        + " 'Frozen Words Flooring Pty Ltd', '12345678', NULL, NULL) RETURNING business_id",
                Long.class, BUSINESS_NAME, SLUG);
        Assertions.assertNotNull(businessId, "business insert must return an id");
        Integer storeId = jdbcTemplate.queryForObject(
                "INSERT INTO store (business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'Frozen Words Store', ?, '0299990000', 'store.frozen.words@example.com', "
                        + " '1 Storefront Way', 'Parramatta', 'NSW', '2150') RETURNING store_id",
                Integer.class, businessId, STORE_CODE);
        Assertions.assertNotNull(storeId, "store insert must return an id");
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO app_user (business_id, first_name, last_name, salesperson_code, email, password_hash) "
                        + "VALUES (?, 'Sam', 'Seller', ?, 'sam.seller.frozen.words@example.com', "
                        + " 'not-a-real-hash-frozen-words') RETURNING user_id",
                Long.class, businessId, SALESPERSON_CODE);
        Assertions.assertNotNull(userId, "app_user insert must return an id");
        jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (?, ?, ?)",
                businessId, userId, storeId);
        clearJpaCache();
        return new Tenant(businessId, storeId, userId, SLUG);
    }

    /**
     * An order of {@code flooringType} that passes every quote, invoice and Path A gate: details of sale,
     * proposed lay date 2026-12-01 + CONFIRMED, the customer saved as {@code identity} with a valid email,
     * an INSTALLATION address, the BILLING address of {@code identity} when {@code withBilling}, and one
     * priced charge line of 100.00 ex / 40.00 cost (below every quote total used here).
     */
    private long seedInvoiceReadyOrder(String flooringType, Identity identity, boolean withBilling) {
        int s = ++orderSeq;
        // order_number must match chk_sales_order_number_format (V6): {code}.{LL#}.{#####}
        Long orderId = jdbcTemplate.queryForObject(
                "INSERT INTO sales_order "
                        + "(business_id, store_id, user_id, order_sequence_number, order_number, flooring_type, "
                        + " order_status, week_number, week_year, details_of_sale, proposed_lay_date, lay_date_status) "
                        + "VALUES (?, ?, ?, ?, ?, ?::flooring_type, 'LEAD'::order_status, 42, 2026, ?, "
                        + " DATE '2026-12-01', 'CONFIRMED'::lay_date_status) RETURNING order_id",
                Long.class,
                tenant.businessId(), tenant.storeId(), tenant.userId(), s,
                ORDER_NUMBER_PREFIX + String.format("%05d", s), flooringType, FROZEN_DETAILS);
        Assertions.assertNotNull(orderId, "order insert must return an id");
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, middle_name, last_name, email, mobile) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                orderId, identity.firstName(), identity.middleName(), identity.lastName(),
                CUSTOMER_EMAIL, CUSTOMER_MOBILE);
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'INSTALLATION'::address_type, NULL, '9', 'Install Close', 'Ryde', 'NSW', '2112')",
                orderId);
        if (withBilling) {
            insertBillingAddress(orderId, identity);
        }
        seedChargeLine(orderId, flooringType);
        clearJpaCache();
        return orderId;
    }

    private void insertBillingAddress(long orderId, Identity identity) {
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'BILLING'::address_type, ?, ?, ?, ?, ?, ?)",
                orderId, identity.unitNumber(), identity.streetNumber(), identity.street(),
                identity.suburb(), identity.stateCode(), identity.postcode());
        clearJpaCache();
    }

    /** One store_charge + order_charge_line (qty 1, 100.00 ex, 40.00 cost) in this tenant's store. */
    private void seedChargeLine(long orderId, String flooringType) {
        String code = "FWC" + (++codeSeq);
        BigDecimal price = new BigDecimal("100.00");
        BigDecimal cost = new BigDecimal("40.00");
        Long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, ?::flooring_type, ?, 'Frozen words supply and lay', ?, ?) RETURNING charge_id",
                Long.class, tenant.storeId(), flooringType, code, price, cost);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line "
                        + "(order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Frozen words supply and lay', ?, ?, 1, ?, ?, ?)",
                orderId, chargeId, code, price, cost, price, price, cost);
    }

    /**
     * Move the LIVE saved customer and BILLING address to {@code identity} (raw SQL, exactly one row each),
     * then clear the persistence context so the next request reads the new values.
     */
    private void saveLiveIdentity(long orderId, Identity identity) {
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE order_customer SET first_name = ?, middle_name = ?, last_name = ? WHERE order_id = ?",
                identity.firstName(), identity.middleName(), identity.lastName(), orderId), "one customer row");
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE order_address SET unit_number = ?, street_number = ?, street = ?, suburb = ?, "
                        + "state_code = ?, postcode = ? WHERE order_id = ? AND address_type = 'BILLING'::address_type",
                identity.unitNumber(), identity.streetNumber(), identity.street(), identity.suburb(),
                identity.stateCode(), identity.postcode(), orderId), "one BILLING row");
        clearJpaCache();
    }

    /** The business's per-flooring-type terms (V13 columns), as raw HTML; then a persistence-context clear. */
    private void setBusinessTerms(Terms terms) {
        jdbcTemplate.update("UPDATE business SET terms_soft = ?, terms_hard = ? WHERE business_id = ?",
                terms.softRaw(), terms.hardRaw(), tenant.businessId());
        clearJpaCache();
    }

    /** A bare quote_version of {@code orderId} (V16 NOT NULL columns only): enough to be a referenced source. */
    private long insertBareAcceptedQuoteVersion(long orderId) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO quote_version (order_id, version_number, status, itemised, quote_total_ex_gst, "
                        + " quote_total_inc_gst, flooring_type_snapshot, created_by_user_id) "
                        + "VALUES (?, 1, 'ACCEPTED', TRUE, 250.00, 275.00, 'SOFT', ?) RETURNING quote_version_id",
                Long.class, orderId, tenant.userId());
        Assertions.assertNotNull(id);
        return id;
    }

    /** A stored_file row for a directly inserted invoice (no physical file: nothing reads it). */
    private long insertStoredFileRow(long orderId) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO stored_file (file_name, storage_path, mime_type, file_size) "
                        + "VALUES (?, ?, 'application/pdf', 1024) RETURNING stored_file_id",
                Long.class, "invoice-direct-" + orderId + "-v1.pdf",
                "/uploads/" + tenant.businessId() + "/orders/" + orderId + "/direct-insert-v1.pdf");
        Assertions.assertNotNull(id);
        return id;
    }

    // ================================================================
    // Helpers - requests
    // ================================================================

    private String orderUrl(long orderId) {
        return "/api/v1/" + tenant.slug() + "/orders/" + orderId;
    }

    private String quoteUrl(long orderId, String suffix) {
        return orderUrl(orderId) + "/quote/" + suffix;
    }

    private String invoicesUrl(long orderId) {
        return orderUrl(orderId) + "/invoices";
    }

    private static String publicQuoteUrl(String token) {
        return "/api/v1/public/quotes/" + token;
    }

    /**
     * Detach every hydrated entity. The MockMvc calls of one test share the test transaction's persistence
     * context, so after a raw JDBC write a later JPA read (customer, address, order, business) would
     * otherwise return the stale cached entity.
     */
    private void clearJpaCache() {
        entityManager.clear();
    }

    /** Perform a request and assert its status, with the response body in the failure message. */
    private MvcResult perform(RequestBuilder request, int expectedStatus, String label) throws Exception {
        clearJpaCache();
        MvcResult result = mockMvc.perform(request).andReturn();
        clearJpaCache();
        String responseBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Assertions.assertEquals(expectedStatus, result.getResponse().getStatus(),
                () -> label + " returned an unexpected status; body: " + responseBody);
        return result;
    }

    private static String body(MvcResult result) throws IOException {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static JsonNode readJson(MvcResult result) throws IOException {
        return JSON.readTree(body(result));
    }

    private void saveDraft(long orderId, String json) throws Exception {
        perform(put(quoteUrl(orderId, "draft")).session(tenant.session())
                .contentType(MediaType.APPLICATION_JSON).content(json), 200, "PUT quote draft");
    }

    /** Itemised: Carpet 2 x 100 + Underlay 1 x 50 = 250.00 ex / 275.00 inc. */
    private void saveItemisedDraft(long orderId) throws Exception {
        saveDraft(orderId, """
                {"itemised": true, "lines": [
                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
                  {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
                ]}""");
    }

    /** Non-itemised 330.00 inc / 300.00 ex (header-only save). */
    private void saveNonItemisedDraft(long orderId) throws Exception {
        saveDraft(orderId, "{\"itemised\": false, \"final_total_inc_gst\": 330.00, \"lines\": []}");
    }

    /** Issue (or re-issue) via the protected send-email and return the plaintext token from the link. */
    private String sendAndExtractToken(long orderId) throws Exception {
        int sentBefore = quoteEmailSender.sentEmails().size();
        perform(post(quoteUrl(orderId, "send-email")).session(tenant.session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 201, "POST quote send-email");
        List<QuoteEmailRequest> sent = quoteEmailSender.sentEmails();
        Assertions.assertEquals(sentBefore + 1, sent.size(), "exactly one quote email must have been recorded");
        Matcher matcher = PUBLIC_LINK_TOKEN.matcher(sent.get(sent.size() - 1).bodyText());
        Assertions.assertTrue(matcher.find(), "the recorded quote email must carry the /q/{token} link");
        return matcher.group(1);
    }

    /** The REAL public accept: token-only (NO session), exactly one PNG {@code signature} part. */
    private void acceptPublicly(String token, byte[] signaturePng) throws Exception {
        MvcResult result = perform(multipart(publicQuoteUrl(token) + "/accept")
                        .file(new MockMultipartFile("signature", "signature.png", "image/png", signaturePng)),
                201, "public quote accept");
        JsonNode json = readJson(result);
        Assertions.assertEquals("INACTIVE", json.path("data").path("state").asText());
        Assertions.assertEquals("Quote accepted.", json.path("message").asText());
    }

    /** Draft (itemised) + send v1 + public accept: returns the accepted version's row. */
    private Map<String, Object> issueAndAcceptQuote(long orderId, byte[] signaturePng) throws Exception {
        saveItemisedDraft(orderId);
        acceptPublicly(sendAndExtractToken(orderId), signaturePng);
        Map<String, Object> accepted = quoteVersionRow(orderId, 1);
        Assertions.assertEquals("ACCEPTED", accepted.get("status"), "precondition: v1 accepted publicly");
        return accepted;
    }

    /**
     * Path A (16G PR 3): POST the mandatory {@code {"quote_version_id": N}}, N = the id of the order's latest
     * ACCEPTED quote version read from the database (never hard-coded); 201 with the exact delivered message.
     * The post-commit email is proven on every call: exactly ONE more invoice email - the appended version's
     * stored PDF byte for byte (so exactly the words the tests check in that PDF), to the trimmed saved
     * customer email - and no failed attempt, no quote email, no store notification and no SMS; and (this
     * transactional context) the success stamp on the appended version, the dashboard mirror and the
     * response.
     */
    private MvcResult createInvoiceFromQuote(long orderId) throws Exception {
        long acceptedQuoteVersionId = latestAcceptedQuoteVersionId(orderId);
        SenderCounts before = senderCounts();
        MvcResult result = perform(post(quoteUrl(orderId, "create-invoice")).session(tenant.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quote_version_id\": " + acceptedQuoteVersionId + "}"),
                201, "Path A create-invoice");
        JsonNode json = readJson(result);
        Assertions.assertEquals(CREATED_FROM_QUOTE_MESSAGE, json.path("message").asText());
        int versionNumber = json.path("data").path("invoice").path("version_number").asInt();
        Assertions.assertEquals(before.plusOneInvoiceEmail(), senderCounts(),
                "Path A sends exactly one invoice email (its signed PDF) and nothing else");
        List<InvoiceEmailRequest> sent = invoiceEmailSender.sentEmails();
        assertSignedPdfEmail(orderId, versionNumber, sent.get(sent.size() - 1), "Path A");
        assertBothMarkersStamped(orderId, versionNumber, result, "Path A");
        return result;
    }

    /** The id of the order's LATEST ACCEPTED quote version (highest version_number among ACCEPTED). */
    private long latestAcceptedQuoteVersionId(long orderId) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT quote_version_id FROM quote_version WHERE order_id = ? AND status = 'ACCEPTED' "
                        + "ORDER BY version_number DESC LIMIT 1", Long.class, orderId);
        Assertions.assertNotNull(id, "precondition: the order has an ACCEPTED quote version");
        return id;
    }

    /** How many messages every recording sender holds (sent and failed): what one request sent is the difference. */
    private record SenderCounts(int invoiceSent, int invoiceFailed, int quoteSent, int quoteFailed,
                                int notificationsSent, int notificationsFailed, int smsSent, int smsFailed) {

        /** The same counts plus exactly one more delivered invoice email. */
        SenderCounts plusOneInvoiceEmail() {
            return new SenderCounts(invoiceSent + 1, invoiceFailed, quoteSent, quoteFailed,
                    notificationsSent, notificationsFailed, smsSent, smsFailed);
        }
    }

    private SenderCounts senderCounts() {
        return new SenderCounts(
                invoiceEmailSender.sentEmails().size(), invoiceEmailSender.failedEmails().size(),
                quoteEmailSender.sentEmails().size(), quoteEmailSender.failedEmails().size(),
                acceptanceNotificationSender.sentNotifications().size(),
                acceptanceNotificationSender.failedNotifications().size(),
                smsSender.sentMessages().size(), smsSender.failedMessages().size());
    }

    /**
     * One signed-PDF invoice email (16G contract 6.1): to the trimmed saved customer email, the standard
     * subject and body for this tenant, and the stored PDF of invoice v{@code versionNumber} attached byte
     * for byte under its own file name.
     */
    private void assertSignedPdfEmail(long orderId, int versionNumber, InvoiceEmailRequest email, String label)
            throws IOException {
        String orderNumber = jdbcTemplate.queryForObject(
                "SELECT order_number FROM sales_order WHERE order_id = ?", String.class, orderId);
        String savedEmail = jdbcTemplate.queryForObject(
                "SELECT email FROM order_customer WHERE order_id = ?", String.class, orderId);
        Assertions.assertNotNull(savedEmail, "precondition: a saved customer email");
        Assertions.assertEquals(savedEmail.trim(), email.recipientEmail(), label + ": the trimmed saved customer email");
        Assertions.assertEquals("Your invoice from " + BUSINESS_NAME, email.subject(), label + ": email subject");
        Assertions.assertEquals("Hi,\n\nPlease find your invoice " + orderNumber + " from " + BUSINESS_NAME
                + " attached.\n\nThank you.", email.bodyText(), label + ": email body");
        Assertions.assertArrayEquals(invoicePdfFromDisk(orderId, versionNumber), email.pdfBytes(),
                label + ": the attachment is invoice v" + versionNumber + "'s stored PDF, byte for byte");
        Assertions.assertEquals("invoice-" + orderNumber + "-v" + versionNumber + ".pdf", email.pdfFileName(),
                label + ": attachment file name");
        Assertions.assertEquals(orderId, email.orderId(), label + ": email order id");
        Assertions.assertEquals(versionNumber, email.invoiceVersionNumber(), label + ": email invoice version");
    }

    /**
     * Delivered and stamped (this transactional context): invoice v{@code versionNumber}'s last_emailed_at
     * is set, the sales_order mirror carries the SAME value (section 11.1), and the response's
     * last_emailed_at shows it (formatted to the second).
     */
    private void assertBothMarkersStamped(long orderId, int versionNumber, MvcResult result, String label)
            throws IOException {
        Object stamp = invoiceRow(orderId, versionNumber).get("last_emailed_at");
        Assertions.assertNotNull(stamp, label + ": the appended version's last_emailed_at is stamped");
        Timestamp invoiceStamp = (Timestamp) stamp;
        Assertions.assertEquals(invoiceStamp, jdbcTemplate.queryForObject(
                        "SELECT last_emailed_at FROM sales_order WHERE order_id = ?", Timestamp.class, orderId),
                label + ": the dashboard mirror carries the same stamp");
        JsonNode responseStamp = readJson(result).path("data").path("invoice").path("last_emailed_at");
        Assertions.assertTrue(responseStamp.isTextual(),
                () -> label + ": the response's last_emailed_at must be the stamp but was " + responseStamp);
        Assertions.assertEquals(invoiceStamp.toLocalDateTime().truncatedTo(ChronoUnit.SECONDS),
                LocalDateTime.parse(responseStamp.asText()), label + ": the response's last_emailed_at");
    }

    /** D.1 Create (empty body). */
    private MvcResult createInvoice(long orderId) throws Exception {
        MvcResult result = perform(post(invoicesUrl(orderId)).session(tenant.session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 201, "D.1 create");
        Assertions.assertEquals("Invoice created.", readJson(result).path("message").asText());
        return result;
    }

    /** D.2 Rewrite carrying {@code currentVersion} as expected_version_number (Batch A PR 2, #149). */
    private MvcResult rewriteInvoice(long orderId, int currentVersion) throws Exception {
        MvcResult result = perform(post(invoicesUrl(orderId) + "/rewrite").session(tenant.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expected_version_number\":" + currentVersion + "}"), 201, "D.2 rewrite");
        Assertions.assertEquals("Invoice rewritten.", readJson(result).path("message").asText());
        return result;
    }

    /** D.3 GET current (no top-level message). */
    private MvcResult getCurrentInvoice(long orderId) throws Exception {
        MvcResult result = perform(get(invoicesUrl(orderId) + "/current").session(tenant.session()),
                200, "D.3 get current");
        Assertions.assertFalse(readJson(result).has("message"), "D.3 has no top-level message");
        return result;
    }

    /** D.9 resend (empty body). */
    private MvcResult resendInvoice(long orderId) throws Exception {
        MvcResult result = perform(post(invoicesUrl(orderId) + "/current/resend").session(tenant.session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 200, "D.9 resend");
        Assertions.assertEquals("Invoice re-sent to the customer.", readJson(result).path("message").asText());
        return result;
    }

    /** D.7 record a CASH payment (appends a new invoice version; never emails). */
    private MvcResult recordCashPayment(long orderId, String amount) throws Exception {
        MvcResult result = perform(post(orderUrl(orderId) + "/payments").session(tenant.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_method\": \"CASH\", \"amount\": " + amount + "}"),
                201, "D.7 record payment");
        Assertions.assertEquals("Payment recorded. Current invoice updated.", readJson(result).path("message").asText());
        return result;
    }

    /** D.10 soft-void a payment (no body; appends a new invoice version; never emails). */
    private MvcResult voidPayment(long orderId, long paymentId) throws Exception {
        MvcResult result = perform(post(orderUrl(orderId) + "/payments/" + paymentId + "/void")
                .session(tenant.session()), 201, "D.10 void payment");
        Assertions.assertEquals("Payment voided. Current invoice updated.", readJson(result).path("message").asText());
        return result;
    }

    private long latestPaymentId(long orderId) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT payment_transaction_id FROM payment_transaction WHERE order_id = ? "
                        + "ORDER BY payment_transaction_id DESC LIMIT 1", Long.class, orderId);
        Assertions.assertNotNull(id, "a payment must exist");
        return id;
    }

    /** D.4: the current invoice PDF streamed by the API. */
    private byte[] downloadCurrentInvoicePdf(long orderId) throws Exception {
        MvcResult result = perform(get(invoicesUrl(orderId) + "/current/file").session(tenant.session()),
                200, "D.4 current invoice file");
        Assertions.assertEquals(MediaType.APPLICATION_PDF_VALUE, result.getResponse().getContentType());
        return result.getResponse().getContentAsByteArray();
    }

    /** The bean call of the ownership test: 20 arguments, the last three the V20 identity words. */
    private InvoiceRow insertDirect(long orderId, long storedFileId, long sourceQuoteVersionId, Words words) {
        return invoiceRepository.insertInvoice(orderId, 1, LocalDate.now(), LocalDate.of(2026, 11, 29),
                FROZEN_DETAILS, new BigDecimal("250.00"), new BigDecimal("275.00"),
                new BigDecimal("0.00"), new BigDecimal("275.00"), storedFileId, tenant.userId(),
                null, null, null, null,
                sourceQuoteVersionId, words.termsHtml(),
                words.name(), words.line1(), words.line2());
    }

    // ================================================================
    // Helpers - rows, words, files
    // ================================================================

    private Map<String, Object> quoteVersionRow(long orderId, int versionNumber) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM quote_version WHERE order_id = ? AND version_number = ?", orderId, versionNumber);
    }

    private Map<String, Object> invoiceRow(long orderId, int versionNumber) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM invoice WHERE order_id = ? AND version_number = ?", orderId, versionNumber);
    }

    private int invoiceCount(long orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoice WHERE order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private static Object column(Map<String, Object> row, String column, String label) {
        // A missing key means a missing column (pre-V20 schema), never "null": fail instead of passing.
        Assertions.assertTrue(row.containsKey(column), () -> label + ": the row has no column " + column);
        return row.get(column);
    }

    /** The accepted quote version's own V17 identity columns + its frozen terms_snapshot. */
    private static Words wordsOfQuoteVersion(Map<String, Object> quoteVersion) {
        String label = "quote_version " + quoteVersion.get("quote_version_id");
        return new Words(
                (String) column(quoteVersion, "customer_name_snapshot", label),
                (String) column(quoteVersion, "customer_address_line1_snapshot", label),
                (String) column(quoteVersion, "customer_address_line2_snapshot", label),
                (String) column(quoteVersion, "terms_snapshot", label));
    }

    /** An invoice row's V20 identity columns + its V19 terms_snapshot. */
    private static Words wordsOfInvoiceRow(Map<String, Object> invoice) {
        String label = "invoice v" + invoice.get("version_number");
        return new Words(
                (String) column(invoice, "customer_name_snapshot", label),
                (String) column(invoice, "customer_address_line1_snapshot", label),
                (String) column(invoice, "customer_address_line2_snapshot", label),
                (String) column(invoice, "terms_snapshot", label));
    }

    /** The InvoiceDetail's three identity keys + terms_html (each must be PRESENT: a string or JSON null). */
    private static Words wordsOfDetail(JsonNode invoice) {
        return new Words(
                textOrNull(invoice, "customer_name_snapshot"),
                textOrNull(invoice, "customer_address_line1_snapshot"),
                textOrNull(invoice, "customer_address_line2_snapshot"),
                textOrNull(invoice, "terms_html"));
    }

    private static String textOrNull(JsonNode node, String key) {
        JsonNode value = node.get(key);
        Assertions.assertNotNull(value, () -> key + " must be present in the InvoiceDetail: " + node);
        if (value.isNull()) {
            return null;
        }
        Assertions.assertTrue(value.isTextual(), () -> key + " must be a JSON string or null: " + value);
        return value.asText();
    }

    /** Every text value of a DB row, joined: the haystack of the "appears nowhere in the row" checks. */
    private static String rowText(Map<String, Object> row) {
        return row.values().stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(Collectors.joining(" | "));
    }

    private static long asLong(Object value) {
        Assertions.assertNotNull(value, "expected a non-null id");
        return ((Number) value).longValue();
    }

    /** The production sanitizer's output for raw business terms (asserted non-null and different from raw). */
    private String sanitized(String rawTerms) {
        String html = termsSanitizer.sanitize(rawTerms);
        Assertions.assertNotNull(html, () -> "fixture: the raw terms must keep visible text: " + rawTerms);
        Assertions.assertNotEquals(rawTerms, html,
                () -> "fixture: the raw terms carry markup the sanitizer rewrites: " + rawTerms);
        return html;
    }

    private Path storageRoot() {
        return Path.of(storageBaseDir).toAbsolutePath().normalize();
    }

    /** The bytes on disk behind a stored_file row: storage base dir + storage_path ("/uploads/..."). */
    private byte[] storedFileBytes(long storedFileId) throws IOException {
        Map<String, Object> file = jdbcTemplate.queryForMap(
                "SELECT storage_path, file_size FROM stored_file WHERE stored_file_id = ?", storedFileId);
        String storagePath = (String) file.get("storage_path");
        Assertions.assertTrue(storagePath.startsWith("/uploads/"), () -> "unexpected storage_path " + storagePath);
        Path onDisk = storageRoot().resolve(storagePath.substring(1)).normalize();
        Assertions.assertTrue(Files.isRegularFile(onDisk), () -> "expected a file on disk at " + onDisk);
        byte[] bytes = Files.readAllBytes(onDisk);
        Assertions.assertEquals(asLong(file.get("file_size")), bytes.length, "stored file_size matches the disk bytes");
        return bytes;
    }

    /** The stored PDF of one invoice version, read from disk through its stored_file row. */
    private byte[] invoicePdfFromDisk(long orderId, int versionNumber) throws IOException {
        byte[] pdf = storedFileBytes(asLong(invoiceRow(orderId, versionNumber).get("stored_file_id")));
        Assertions.assertTrue(pdf.length > 500, () -> "PDF unexpectedly small: " + pdf.length + " bytes");
        Assertions.assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.US_ASCII), "PDF magic header");
        return pdf;
    }

    // ================================================================
    // Helpers - InvoiceDetail
    // ================================================================

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * {@code data.invoice} of an InvoiceDetail response: EXACTLY the PR 2 key set (so the three identity
     * keys and both terms keys are present even when null), the expected version and terms_source, and no
     * internal id or raw column name anywhere in the body.
     */
    private static JsonNode assertInvoiceDetail(MvcResult result, int expectedVersion, String expectedSource,
                                                String label) throws IOException {
        String responseBody = body(result);
        JsonNode invoice = JSON.readTree(responseBody).path("data").path("invoice");
        Assertions.assertTrue(invoice.isObject(), () -> label + ": data.invoice must be an object: " + responseBody);
        Assertions.assertEquals(new TreeSet<>(INVOICE_DETAIL_KEYS), fieldNames(invoice),
                () -> label + ": the InvoiceDetail key set: " + responseBody);
        Assertions.assertEquals(expectedVersion, invoice.get("version_number").asInt(), label + ": version_number");
        Assertions.assertEquals(expectedSource, invoice.get("terms_source").asText(), label + ": terms_source");
        for (String internal : List.of("source_quote_version_id", "terms_snapshot", "stored_file_id",
                "accepted_signature_file_id", "storage_path", "/uploads/")) {
            Assertions.assertFalse(responseBody.contains(internal),
                    () -> label + ": '" + internal + "' must never appear in an InvoiceDetail: " + responseBody);
        }
        return invoice;
    }

    /** A null word is a PRESENT JSON null key in the raw body (never omitted, never ""). */
    private static void assertPresentNullKeys(MvcResult result, String label, String... keys) throws IOException {
        String responseBody = body(result);
        for (String key : keys) {
            Assertions.assertTrue(responseBody.contains("\"" + key + "\":null"),
                    () -> label + ": " + key + " must be a present JSON null: " + responseBody);
        }
    }

    /** Payment / void responses use the unchanged E.1 CurrentInvoiceSummary: no identity or terms keys. */
    private static void assertCurrentInvoiceSummaryUnchanged(MvcResult result, int expectedVersion, String label)
            throws IOException {
        String responseBody = body(result);
        JsonNode summary = JSON.readTree(responseBody).path("data").path("current_invoice");
        Assertions.assertTrue(summary.isObject(), () -> label + ": data.current_invoice must be an object: " + responseBody);
        Assertions.assertEquals(new TreeSet<>(CURRENT_INVOICE_SUMMARY_KEYS), fieldNames(summary),
                () -> label + ": E.1 keys are unchanged by PR 2: " + responseBody);
        Assertions.assertEquals(expectedVersion, summary.get("version_number").asInt(), label + ": version_number");
        Assertions.assertFalse(responseBody.contains("_snapshot") || responseBody.contains("terms_"),
                () -> label + ": no word key anywhere in a payment / void response: " + responseBody);
    }

    // ================================================================
    // Helpers - PDF content
    // ================================================================

    /** Text in content-stream order (document order of the template's blocks). */
    private static String pdfText(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /**
     * Text in VISUAL reading order. The signed caption is "Accepted by" + a name span + "on" + a time span;
     * in content-stream order PDFBox emits the static words before the spans, so the caption is read
     * position-sorted, the order a reader sees on the page.
     */
    private static String pdfTextInReadingOrder(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    private static String pageText(byte[] pdf, int page) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            return stripper.getText(document);
        }
    }

    private static int pageCount(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            return document.getNumberOfPages();
        }
    }

    // PDFBox can inject or drop spaces between glyphs and wraps long text, so comparisons ignore whitespace.
    private static String noSpace(String text) {
        return text.replaceAll("\\s", "");
    }

    private static String flat(String text) {
        return text.replaceAll("\\s+", " ");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }

    /** None of {@code forbidden} occurs in {@code haystack} (whitespace and case ignored). */
    private static void assertNoneOf(String haystack, List<String> forbidden, String label) {
        String normalised = noSpace(haystack).toUpperCase(Locale.ROOT);
        for (String unexpected : forbidden) {
            Assertions.assertFalse(normalised.contains(noSpace(unexpected).toUpperCase(Locale.ROOT)),
                    () -> label + ": '" + unexpected + "' must appear nowhere, but it is in: " + flat(haystack));
        }
    }

    /**
     * The Invoice To block, positionally: in content-stream (document) order the "INVOICE TO" heading is
     * followed by EXACTLY the version's name, billing line 1 and billing line 2 (a null word renders as an
     * empty line, i.e. nothing) and then directly by the next column's "ISSUED / DUE" heading.
     */
    private static void assertInvoiceTo(byte[] pdf, Words words, String label) throws IOException {
        String text = pdfText(pdf);
        String expected = INVOICE_TO_HEADING + nullToEmpty(words.name()) + nullToEmpty(words.line1())
                + nullToEmpty(words.line2()) + NEXT_COLUMN_HEADING;
        Assertions.assertTrue(noSpace(text).contains(noSpace(expected)),
                () -> label + ": the Invoice To block must read [" + expected + "] in: " + flat(text));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Terms present: exactly 2 pages, the heading and {@code termsText} on page 2 only, footer exactly once. */
    private static void assertTermsPage(byte[] pdf, String termsText, String label) throws IOException {
        String all = pdfText(pdf);
        Assertions.assertEquals(2, pageCount(pdf), () -> label + ": terms -> a dedicated second page: " + flat(all));
        String page1 = pageText(pdf, 1);
        String page2 = pageText(pdf, 2);
        Assertions.assertFalse(page1.contains(TERMS_HEADING),
                () -> label + ": page 1 never carries the terms heading: " + flat(page1));
        Assertions.assertFalse(noSpace(page1).contains(noSpace(termsText)),
                () -> label + ": page 1 never carries the terms text: " + flat(page1));
        Assertions.assertTrue(page2.contains(TERMS_HEADING), () -> label + ": page 2 carries the terms heading");
        Assertions.assertTrue(noSpace(page2).contains(noSpace(termsText)),
                () -> label + ": page 2 must show [" + termsText + "]: " + flat(page2));
        Assertions.assertEquals(1, countOccurrences(flat(all), PDF_FOOTER),
                () -> label + ": the footer renders exactly once: " + flat(all));
        Assertions.assertTrue(flat(page2).contains(PDF_FOOTER), () -> label + ": the footer sits on the terms page");
    }

    /** No terms: exactly 1 page, no terms heading, footer exactly once. */
    private static void assertNoTermsPage(byte[] pdf, String label) throws IOException {
        String all = pdfText(pdf);
        Assertions.assertEquals(1, pageCount(pdf), () -> label + ": no terms -> a single page: " + flat(all));
        Assertions.assertFalse(all.contains(TERMS_HEADING), () -> label + ": no terms heading: " + flat(all));
        Assertions.assertEquals(1, countOccurrences(flat(all), PDF_FOOTER),
                () -> label + ": the footer renders exactly once: " + flat(all));
    }

    /** The signed caption "Accepted by {name} on ..." (read in visual order), and no blank signature line. */
    private static void assertSignedBy(byte[] pdf, String acceptedName, String label) throws IOException {
        String readingOrder = pdfTextInReadingOrder(pdf);
        Assertions.assertTrue(noSpace(readingOrder).contains(noSpace("Accepted by " + acceptedName + " on")),
                () -> label + ": the caption 'Accepted by " + acceptedName + " on' is missing: " + flat(readingOrder));
        Assertions.assertFalse(readingOrder.contains("Customer signature"),
                () -> label + ": a signed PDF shows no blank signature line: " + flat(readingOrder));
    }

    /** One image XObject found in a page's resources: its declared size and decoded pixels. */
    private record EmbeddedImage(int width, int height, BufferedImage pixels) {
    }

    private static List<EmbeddedImage> embeddedImages(byte[] pdf) throws IOException {
        List<EmbeddedImage> images = new ArrayList<>();
        try (PDDocument document = PDDocument.load(pdf)) {
            for (PDPage page : document.getPages()) {
                PDResources resources = page.getResources();
                for (COSName name : resources.getXObjectNames()) {
                    if (resources.isImageXObject(name)) {
                        PDImageXObject image = (PDImageXObject) resources.getXObject(name);
                        images.add(new EmbeddedImage(image.getWidth(), image.getHeight(), image.getImage()));
                    }
                }
            }
        }
        return images;
    }

    /**
     * The tenant has no logo, so the ONLY image a signed invoice PDF can embed is the signature: exactly
     * one image, with the size and every pixel of the stored quote signature.
     */
    private static void assertEmbedsSignature(byte[] pdf, byte[] storedSignaturePng, String label) throws IOException {
        BufferedImage want = ImageIO.read(new ByteArrayInputStream(storedSignaturePng));
        Assertions.assertNotNull(want, "the stored signature must be a decodable PNG");
        List<EmbeddedImage> images = embeddedImages(pdf);
        Assertions.assertEquals(1, images.size(), () -> label + ": the signature is the only embedded image");
        EmbeddedImage got = images.get(0);
        Assertions.assertEquals(want.getWidth(), got.width(), label + ": embedded signature width");
        Assertions.assertEquals(want.getHeight(), got.height(), label + ": embedded signature height");
        for (int y = 0; y < want.getHeight(); y++) {
            for (int x = 0; x < want.getWidth(); x++) {
                int expectedRgb = want.getRGB(x, y) & 0xFFFFFF;
                int actualRgb = got.pixels().getRGB(x, y) & 0xFFFFFF;
                int px = x;
                int py = y;
                Assertions.assertEquals(expectedRgb, actualRgb, () -> String.format(Locale.ROOT,
                        "%s: embedded signature pixel (%d,%d): expected %06x but was %06x",
                        label, px, py, expectedRgb, actualRgb));
            }
        }
    }

    /** A distinctive 7 x 3 opaque RGB signature (every pixel a different colour). */
    private static byte[] distinctiveSignaturePng() throws IOException {
        BufferedImage image = new BufferedImage(7, 3, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 7; x++) {
                image.setRGB(x, y, ((30 + x * 30) << 16) | ((40 + y * 90) << 8) | (200 - x * 20));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Assertions.assertTrue(ImageIO.write(image, "png", out), "a PNG ImageWriter must be available");
        return out.toByteArray();
    }

    @SafeVarargs
    private static List<String> allOf(List<String>... lists) {
        return Stream.of(lists).flatMap(List::stream).toList();
    }

    // ================================================================
    // Path A stores and renders the ACCEPTED version's own words (contract 4.2, Path A row)
    // ================================================================

    /**
     * Path A copies the accepted version's OWN V17 identity and frozen terms, whatever the live state or a
     * newer quote say. Quote v1 is issued while {@code QUOTED} (with a unit number) and the ISSUE terms are
     * saved, and accepted publicly. Then the live state moves twice: a NEWER quote v2 is issued (it stays
     * ISSUED) for {@code NEWER_QUOTED} and the NEWER terms, and the live customer, BILLING address and
     * business terms move again to {@code LIVE_AT_CONVERSION} and the CONVERSION terms. Path A must write
     * v1's words: the invoice row's {@code customer_name_snapshot} / {@code customer_address_line1_snapshot}
     * / {@code customer_address_line2_snapshot} / {@code terms_snapshot} equal v1's V17 and terms columns
     * (read by SQL, and literally), {@code source_quote_version_id} = v1; the Path A response and D.3 show
     * the same words with terms_source QUOTE; the stored PDF (= the D.4 stream) shows them in the Invoice To
     * block and on page 2; and neither the newer version's nor the live identity or terms appear anywhere
     * in the row, the API bodies or the PDF.
     *
     * <p>Fails on 16F: Path A built Invoice To from the live customer and BILLING address, so the PDF read
     * "Zane Kit Liverename / 7/300 Liveafter Road / Fremantle WA 6160", and the row / InvoiceDetail had no
     * identity words at all.
     */
    @Test
    void pathA_writesTheAcceptedVersionsOwnV17IdentityAndTerms_notTheLiveCustomerBillingAndTerms_norANewerIssuedQuotes()
            throws Exception {
        setBusinessTerms(ISSUE_TERMS);
        long orderId = seedInvoiceReadyOrder(SOFT, QUOTED, true);
        Map<String, Object> accepted = issueAndAcceptQuote(orderId, ONE_PIXEL_PNG);
        long acceptedVersionId = asLong(accepted.get("quote_version_id"));
        Words acceptedWords = new Words(QUOTED.name(), QUOTED.line1(), QUOTED.line2(), sanitized(ISSUE_TERMS.softRaw()));
        Assertions.assertEquals(acceptedWords, wordsOfQuoteVersion(accepted),
                "precondition: v1 froze the QUOTED identity and the sanitised SOFT issue terms at send");

        // A NEWER quote v2 is issued for another identity and other terms (it stays ISSUED, never accepted).
        saveLiveIdentity(orderId, NEWER_QUOTED);
        setBusinessTerms(NEWER_ISSUE_TERMS);
        saveNonItemisedDraft(orderId);
        sendAndExtractToken(orderId);
        Map<String, Object> newer = quoteVersionRow(orderId, 2);
        Assertions.assertEquals("ISSUED", newer.get("status"), "precondition: v2 is the newer ISSUED version");
        Assertions.assertEquals(
                new Words(NEWER_QUOTED.name(), NEWER_QUOTED.line1(), NEWER_QUOTED.line2(),
                        sanitized(NEWER_ISSUE_TERMS.softRaw())),
                wordsOfQuoteVersion(newer), "precondition: v2 froze its own, different words");

        // The live customer, BILLING address and business terms move on again before the conversion.
        saveLiveIdentity(orderId, LIVE_AT_CONVERSION);
        setBusinessTerms(CONVERSION_TERMS);
        Map<String, Object> acceptedBeforePathA = quoteVersionRow(orderId, 1);
        Map<String, Object> newerBeforePathA = quoteVersionRow(orderId, 2);

        MvcResult pathA = createInvoiceFromQuote(orderId);

        // ---- The persisted row: v1's own columns, verbatim ----
        Assertions.assertEquals(1, invoiceCount(orderId), "Path A appended exactly one invoice version");
        Map<String, Object> row = invoiceRow(orderId, 1);
        Assertions.assertEquals(wordsOfQuoteVersion(accepted), wordsOfInvoiceRow(row),
                "the invoice words = the accepted version's V17 identity columns and terms_snapshot, verbatim");
        Assertions.assertEquals(acceptedWords, wordsOfInvoiceRow(row), "the invoice words, literally");
        Assertions.assertEquals(acceptedVersionId, asLong(row.get("source_quote_version_id")),
                "source_quote_version_id = the accepted version (never the newer ISSUED v2)");
        Assertions.assertEquals(QUOTED.name(), row.get("accepted_customer_name"), "the inherited accepted name");

        // ---- InvoiceDetail: the Path A response and D.3 ----
        MvcResult current = getCurrentInvoice(orderId);
        for (Map.Entry<String, MvcResult> response : Map.of("Path A response", pathA, "D.3", current).entrySet()) {
            JsonNode invoice = assertInvoiceDetail(response.getValue(), 1, "QUOTE", response.getKey());
            Assertions.assertEquals(acceptedWords, wordsOfDetail(invoice), response.getKey() + ": the frozen words");
        }

        // ---- The stored PDF (disk) == the streamed PDF (D.4) ----
        byte[] pdf = invoicePdfFromDisk(orderId, 1);
        Assertions.assertArrayEquals(pdf, downloadCurrentInvoicePdf(orderId), "D.4 streams the stored Path A PDF");
        assertInvoiceTo(pdf, acceptedWords, "Path A PDF");
        assertTermsPage(pdf, ISSUE_TERMS.softText(), "Path A PDF");
        assertSignedBy(pdf, QUOTED.name(), "Path A PDF");
        Assertions.assertEquals(2, countOccurrences(noSpace(pdfText(pdf)), noSpace(QUOTED.name())),
                "the frozen name renders twice: Invoice To and the inherited acceptance caption");

        // ---- Neither the newer version's nor the live words appear anywhere ----
        List<String> forbidden = allOf(NEWER_QUOTED.markers(), LIVE_AT_CONVERSION.markers(),
                NEWER_ISSUE_TERMS.texts(), CONVERSION_TERMS.texts(), List.of(ISSUE_TERMS.hardText()));
        assertNoneOf(rowText(row), forbidden, "Path A invoice row");
        assertNoneOf(body(pathA), forbidden, "Path A response");
        assertNoneOf(body(current), forbidden, "D.3 after Path A");
        assertNoneOf(pdfText(pdf), forbidden, "Path A PDF");

        // Path A reads the quote's words; it never writes them (both quote rows are exactly as before).
        Assertions.assertEquals(acceptedBeforePathA, quoteVersionRow(orderId, 1), "the accepted v1 row is untouched");
        Assertions.assertEquals(newerBeforePathA, quoteVersionRow(orderId, 2), "the newer ISSUED v2 row is untouched");
    }

    /**
     * Path A is not a copy writer: it never copies the previous invoice version's words. An unsigned Path B
     * invoice v1 is created (D.1) while {@code PATH_B_ERA} and the PATH B terms are saved, so v1 freezes
     * those words. The customer, BILLING address and terms then move to {@code QUOTED} / ISSUE terms, quote
     * v1 is issued and accepted, and the live state moves once more to {@code LIVE_AT_CONVERSION} /
     * CONVERSION terms. Path A's invoice v2 carries the accepted quote's words (row, Path A response, D.3,
     * PDF), never v1's and never the live ones, and the Path B v1 row and its stored PDF are unchanged.
     *
     * <p>Fails on 16F: Path A's Invoice To was the live customer ("Zane Kit Liverename"), D.1 froze no
     * words, and the v2 row / InvoiceDetail had no identity words.
     */
    @Test
    void pathA_afterAnUnsignedPathBInvoice_writesTheQuotesWords_neverThePreviousInvoiceVersionsWordsOrTheLiveOnes()
            throws Exception {
        setBusinessTerms(PATH_B_ERA_TERMS);
        long orderId = seedInvoiceReadyOrder(SOFT, PATH_B_ERA, true);
        MvcResult d1 = createInvoice(orderId);
        Words pathBWords = new Words(PATH_B_ERA.name(), PATH_B_ERA.line1(), PATH_B_ERA.line2(),
                sanitized(PATH_B_ERA_TERMS.softRaw()));
        Assertions.assertEquals(pathBWords, wordsOfDetail(assertInvoiceDetail(d1, 1, "BUSINESS", "D.1")),
                "precondition: D.1 froze the then-saved identity and business terms");
        Map<String, Object> pathBRow = invoiceRow(orderId, 1);
        Assertions.assertEquals(pathBWords, wordsOfInvoiceRow(pathBRow), "precondition: the D.1 row words");
        byte[] pathBPdf = invoicePdfFromDisk(orderId, 1);

        saveLiveIdentity(orderId, QUOTED);
        setBusinessTerms(ISSUE_TERMS);
        Map<String, Object> accepted = issueAndAcceptQuote(orderId, ONE_PIXEL_PNG);
        Words acceptedWords = new Words(QUOTED.name(), QUOTED.line1(), QUOTED.line2(), sanitized(ISSUE_TERMS.softRaw()));
        Assertions.assertEquals(acceptedWords, wordsOfQuoteVersion(accepted), "precondition: the quote's own words");

        saveLiveIdentity(orderId, LIVE_AT_CONVERSION);
        setBusinessTerms(CONVERSION_TERMS);

        MvcResult pathA = createInvoiceFromQuote(orderId);

        Assertions.assertEquals(2, invoiceCount(orderId), "Path A appended v2 after the unsigned Path B v1");
        Map<String, Object> row = invoiceRow(orderId, 2);
        Assertions.assertEquals(wordsOfQuoteVersion(accepted), wordsOfInvoiceRow(row),
                "v2 words = the accepted quote's V17 identity and terms, not v1's");
        Assertions.assertEquals(acceptedWords, wordsOfInvoiceRow(row), "v2 words, literally");
        Assertions.assertEquals(asLong(accepted.get("quote_version_id")), asLong(row.get("source_quote_version_id")));

        MvcResult current = getCurrentInvoice(orderId);
        for (Map.Entry<String, MvcResult> response : Map.of("Path A response", pathA, "D.3", current).entrySet()) {
            JsonNode invoice = assertInvoiceDetail(response.getValue(), 2, "QUOTE", response.getKey());
            Assertions.assertEquals(acceptedWords, wordsOfDetail(invoice), response.getKey() + ": the quote's words");
        }

        byte[] pdf = invoicePdfFromDisk(orderId, 2);
        Assertions.assertArrayEquals(pdf, downloadCurrentInvoicePdf(orderId));
        assertInvoiceTo(pdf, acceptedWords, "Path A v2 PDF");
        assertTermsPage(pdf, ISSUE_TERMS.softText(), "Path A v2 PDF");

        List<String> forbidden = allOf(PATH_B_ERA.markers(), LIVE_AT_CONVERSION.markers(),
                PATH_B_ERA_TERMS.texts(), CONVERSION_TERMS.texts());
        assertNoneOf(rowText(row), forbidden, "Path A v2 row");
        assertNoneOf(body(pathA), forbidden, "Path A response");
        assertNoneOf(body(current), forbidden, "D.3 after Path A");
        assertNoneOf(pdfText(pdf), forbidden, "Path A v2 PDF");

        // The previous version is append-only: its row and stored PDF never change.
        Assertions.assertEquals(pathBRow, invoiceRow(orderId, 1), "the Path B v1 row is untouched");
        Assertions.assertArrayEquals(pathBPdf, invoicePdfFromDisk(orderId, 1), "the Path B v1 PDF is untouched");
    }

    // ================================================================
    // The Path A words survive payment, void and resend (4.2, the copy writers)
    // ================================================================

    /**
     * A HARD order (so the quote froze terms_hard) is converted by Path A while the live state already
     * differs ({@code LIVE_AT_CONVERSION} / CONVERSION terms); then the live customer, BILLING address and
     * business terms move AGAIN ({@code LIVE_LATER} / LATER terms). The D.7 payment version v2 and the D.10
     * void version v3 each copy Path A's four words verbatim (row), carry its source quote version and its
     * inherited acceptance (accepted_at, accepted name, the SAME signature stored_file), report them on D.3
     * (QUOTE) and render them in their own stored PDFs: Invoice To, the frozen hard terms on page 2, the
     * inherited caption and the quote's signature pixels. The payment and void responses keep the unchanged
     * E.1 shape, and neither emails (16G PR 3: Path A's own signed-PDF email is the only one before the
     * resend). A D.9 resend then answers with v3's words and emails v3's stored PDF verbatim. The Path A
     * row and its PDF never change, and no live or later word appears in any row, body or PDF.
     *
     * <p>Fails on 16F: payment and void re-rendered Invoice To from the live customer and BILLING address
     * (the v2 / v3 PDFs read "Wren Laterlive / 5 Postconvert Lane / Hobart TAS 7000") and nothing stored
     * or returned the identity words.
     */
    @Test
    void paymentVoidAndResendAfterPathA_copyThePathAWordsVerbatim_afterFurtherLiveEdits_sourceAndSignatureCarried()
            throws Exception {
        setBusinessTerms(ISSUE_TERMS);
        long orderId = seedInvoiceReadyOrder(HARD, QUOTED, true);
        Map<String, Object> accepted = issueAndAcceptQuote(orderId, distinctiveSignaturePng());
        Words pathAWords = new Words(QUOTED.name(), QUOTED.line1(), QUOTED.line2(), sanitized(ISSUE_TERMS.hardRaw()));
        Assertions.assertEquals(pathAWords, wordsOfQuoteVersion(accepted),
                "precondition: the HARD quote froze the QUOTED identity and the sanitised terms_hard");
        byte[] storedSignature = storedFileBytes(asLong(accepted.get("accepted_signature_file_id")));

        saveLiveIdentity(orderId, LIVE_AT_CONVERSION);
        setBusinessTerms(CONVERSION_TERMS);
        createInvoiceFromQuote(orderId);
        Map<String, Object> pathARow = invoiceRow(orderId, 1);
        Assertions.assertEquals(pathAWords, wordsOfInvoiceRow(pathARow), "precondition: Path A froze the quote's words");
        byte[] pathAPdf = invoicePdfFromDisk(orderId, 1);
        // 16G PR 3: Path A emailed v1's signed PDF (proven in full by createInvoiceFromQuote); from here on the
        // payment and the void add no email and the resend exactly one.
        List<InvoiceEmailRequest> emailsAfterPathA = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, emailsAfterPathA.size(), "precondition: Path A's one signed-PDF email");
        Assertions.assertArrayEquals(pathAPdf, emailsAfterPathA.get(0).pdfBytes(),
                "precondition: Path A emailed the PDF carrying its frozen words");

        // Further live edits AFTER the conversion.
        saveLiveIdentity(orderId, LIVE_LATER);
        setBusinessTerms(LATER_TERMS);
        List<String> forbidden = allOf(LIVE_AT_CONVERSION.markers(), LIVE_LATER.markers(),
                CONVERSION_TERMS.texts(), LATER_TERMS.texts(), List.of(ISSUE_TERMS.softText()));

        // ---- D.7 payment -> v2 ----
        MvcResult paid = recordCashPayment(orderId, "50.00");
        assertCurrentInvoiceSummaryUnchanged(paid, 2, "payment");
        assertCopiedPathAVersion(orderId, 2, pathARow, pathAWords, storedSignature, forbidden, "payment v2");
        byte[] paymentPdf = invoicePdfFromDisk(orderId, 2);

        // ---- D.10 void -> v3 ----
        MvcResult voided = voidPayment(orderId, latestPaymentId(orderId));
        assertCurrentInvoiceSummaryUnchanged(voided, 3, "void");
        assertCopiedPathAVersion(orderId, 3, pathARow, pathAWords, storedSignature, forbidden, "void v3");
        Assertions.assertEquals(emailsAfterPathA, invoiceEmailSender.sentEmails(),
                "payment and void never email (the only invoice email is Path A's own)");
        Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), "payment and void never attempt an email");

        // ---- D.9 resend: v3's words in the response; v3's stored PDF emailed verbatim ----
        MvcResult resent = resendInvoice(orderId);
        JsonNode resentDetail = assertInvoiceDetail(resent, 3, "QUOTE", "D.9 resend");
        Assertions.assertEquals(pathAWords, wordsOfDetail(resentDetail), "D.9: the copied words");
        assertNoneOf(body(resent), forbidden, "D.9 response");
        List<InvoiceEmailRequest> sent = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(emailsAfterPathA.size() + 1, sent.size(), "D.9 emails exactly once");
        Assertions.assertArrayEquals(invoicePdfFromDisk(orderId, 3), sent.get(sent.size() - 1).pdfBytes(),
                "D.9 attaches v3's stored PDF verbatim");

        // ---- Append-only: earlier rows and PDFs never change ----
        Assertions.assertEquals(pathARow, invoiceRow(orderId, 1), "the Path A row is never modified");
        Assertions.assertArrayEquals(pathAPdf, invoicePdfFromDisk(orderId, 1), "the Path A PDF is never rewritten");
        Assertions.assertArrayEquals(paymentPdf, invoicePdfFromDisk(orderId, 2), "the payment PDF is never rewritten");
    }

    /**
     * One version appended after Path A by payment or void: the row's four words equal the Path A row's
     * (and {@code pathAWords}) verbatim; the source and the inherited acceptance columns are carried; D.3
     * reports the same words with QUOTE; the stored PDF (= D.4) renders Invoice To, the frozen hard terms on
     * page 2, the inherited caption and the quote's signature; none of {@code forbidden} appears.
     */
    private void assertCopiedPathAVersion(long orderId, int versionNumber, Map<String, Object> pathARow,
                                          Words pathAWords, byte[] storedSignature, List<String> forbidden,
                                          String label) throws Exception {
        Map<String, Object> row = invoiceRow(orderId, versionNumber);
        Assertions.assertEquals(wordsOfInvoiceRow(pathARow), wordsOfInvoiceRow(row),
                label + ": the four words are copied verbatim from the Path A version");
        Assertions.assertEquals(pathAWords, wordsOfInvoiceRow(row), label + ": the copied words, literally");
        for (String carried : List.of("source_quote_version_id", "accepted_at", "accepted_customer_name",
                "accepted_signature_file_id")) {
            Assertions.assertNotNull(pathARow.get(carried), () -> "precondition: the Path A row has " + carried);
            Assertions.assertEquals(pathARow.get(carried), row.get(carried), label + ": " + carried + " carried");
        }
        assertNoneOf(rowText(row), forbidden, label + " row");

        MvcResult current = getCurrentInvoice(orderId);
        JsonNode invoice = assertInvoiceDetail(current, versionNumber, "QUOTE", label + " D.3");
        Assertions.assertEquals(pathAWords, wordsOfDetail(invoice), label + " D.3: the copied words");
        Assertions.assertTrue(invoice.get("accepted_signature_present").booleanValue(),
                label + " D.3: the inherited signature is still present");
        assertNoneOf(body(current), forbidden, label + " D.3");

        byte[] pdf = invoicePdfFromDisk(orderId, versionNumber);
        Assertions.assertArrayEquals(pdf, downloadCurrentInvoicePdf(orderId), label + ": D.4 streams the stored PDF");
        assertInvoiceTo(pdf, pathAWords, label + " PDF");
        assertTermsPage(pdf, ISSUE_TERMS.hardText(), label + " PDF");
        assertSignedBy(pdf, QUOTED.name(), label + " PDF");
        assertEmbedsSignature(pdf, storedSignature, label + " PDF");
        assertNoneOf(pdfText(pdf), forbidden, label + " PDF");
    }

    /**
     * Payment and void copy the CURRENT version's words, never its source quote's. A quote-sourced version
     * can hold Invoice To words that differ from its source quote version's V17 snapshot: V20 backfilled
     * every pre-PR-2 Path A version from the order's saved customer and BILLING row, not from the quote. The
     * Path A version's three identity words are planted by SQL ("as backfilled") to differ from the
     * accepted quote's snapshot, and then the live customer and business terms move on as well. The D.7
     * payment (v2) and the D.10 void (v3) must copy the ROW's words verbatim into their rows, D.3 (QUOTE)
     * and their PDFs' Invoice To, with the quote's frozen terms and the inherited caption carried, and never
     * the source quote's billing lines or the live identity and terms.
     *
     * <p>Fails if a copy writer re-read the source quote version for a QUOTE row (the copies would show
     * "4/12 Snapshot Street / Paddington NSW 2021") or the live customer ("5 Postconvert Lane").
     */
    @Test
    void paymentAndVoid_onAQuoteSourcedVersionWhoseWordsDifferFromItsSourceQuote_copyTheVersionsOwnWords()
            throws Exception {
        setBusinessTerms(ISSUE_TERMS);
        long orderId = seedInvoiceReadyOrder(SOFT, QUOTED, true);
        Map<String, Object> accepted = issueAndAcceptQuote(orderId, ONE_PIXEL_PNG);
        createInvoiceFromQuote(orderId);
        String frozenTerms = sanitized(ISSUE_TERMS.softRaw());
        Map<String, Object> pathARow = invoiceRow(orderId, 1);
        Assertions.assertEquals(new Words(QUOTED.name(), QUOTED.line1(), QUOTED.line2(), frozenTerms),
                wordsOfInvoiceRow(pathARow), "precondition: Path A copied the accepted quote's words");

        // As V20 backfilled a pre-PR-2 Path A version: the identity of the then-saved customer, not the quote's.
        Words backfilled = new Words("Bea Backfilled", "9/77 Backfill Street", "Glebe NSW 2037", frozenTerms);
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE invoice SET customer_name_snapshot = ?, customer_address_line1_snapshot = ?, "
                        + "customer_address_line2_snapshot = ? WHERE invoice_id = ?",
                backfilled.name(), backfilled.line1(), backfilled.line2(), asLong(pathARow.get("invoice_id"))));
        clearJpaCache();
        Assertions.assertEquals(backfilled, wordsOfInvoiceRow(invoiceRow(orderId, 1)), "fixture: the planted row words");
        Assertions.assertNotEquals(wordsOfQuoteVersion(accepted), backfilled,
                "fixture: the row's words differ from its source quote version's V17 snapshot");

        // The live customer and the business terms move on too.
        saveLiveIdentity(orderId, LIVE_LATER);
        setBusinessTerms(LATER_TERMS);
        // The quote's own billing lines and the live words must appear nowhere. (The quote's NAME does appear:
        // it is the inherited accepted name, in accepted_customer_name and the signed caption.)
        List<String> forbidden = allOf(List.of(QUOTED.street(), QUOTED.suburb()), LIVE_LATER.markers(),
                LATER_TERMS.texts());

        MvcResult paid = recordCashPayment(orderId, "50.00");
        assertCurrentInvoiceSummaryUnchanged(paid, 2, "payment");
        assertCopiesTheCurrentVersionsWords(orderId, 2, backfilled, forbidden, "payment v2");

        MvcResult voided = voidPayment(orderId, latestPaymentId(orderId));
        assertCurrentInvoiceSummaryUnchanged(voided, 3, "void");
        assertCopiesTheCurrentVersionsWords(orderId, 3, backfilled, forbidden, "void v3");
    }

    /**
     * One payment or void version of a quote-sourced invoice: its row, its D.3 InvoiceDetail (QUOTE) and its
     * stored PDF (Invoice To block, page-2 terms, inherited caption) hold exactly {@code expected}, and none
     * of {@code forbidden} appears in the row, the response or the PDF.
     */
    private void assertCopiesTheCurrentVersionsWords(long orderId, int versionNumber, Words expected,
                                                     List<String> forbidden, String label) throws Exception {
        Map<String, Object> row = invoiceRow(orderId, versionNumber);
        Assertions.assertEquals(expected, wordsOfInvoiceRow(row), label + ": the previous version's own words, verbatim");
        assertNoneOf(rowText(row), forbidden, label + " row");
        MvcResult current = getCurrentInvoice(orderId);
        JsonNode invoice = assertInvoiceDetail(current, versionNumber, "QUOTE", label + " D.3");
        Assertions.assertEquals(expected, wordsOfDetail(invoice), label + " D.3: the copied words");
        assertNoneOf(body(current), forbidden, label + " D.3");
        byte[] pdf = invoicePdfFromDisk(orderId, versionNumber);
        assertInvoiceTo(pdf, expected, label + " PDF");
        assertTermsPage(pdf, ISSUE_TERMS.softText(), label + " PDF");
        assertSignedBy(pdf, QUOTED.name(), label + " PDF");
        assertNoneOf(pdfText(pdf), forbidden, label + " PDF");
    }

    // ================================================================
    // Null quote billing and null quote terms: no live fallback (4.2, 4.3)
    // ================================================================

    /**
     * Quote v1 is issued and accepted while the order has NO BILLING address row and the business has NO
     * terms, so v1's V17 billing lines and its terms_snapshot are NULL (its name is not). After the
     * acceptance the BILLING row that Path A's retained live precondition requires is added and the
     * business sets terms for both types. Path A must still store the quote's words verbatim: the name, NULL
     * billing lines and NULL terms on the row; the three identity keys and terms_html PRESENT on the
     * InvoiceDetail (the two lines and terms_html as JSON null) with terms_source QUOTE; a PDF whose Invoice
     * To block holds the name only and that has no terms page (one page, footer exactly once). The live
     * street and terms appear nowhere. A following payment version copies the same nulls (copy writers
     * never substitute the live values for a null).
     *
     * <p>Fails on 16F: Path A and payment rendered Invoice To from the live BILLING row added after the
     * acceptance, so the PDFs showed "7/300 Liveafter Road / Fremantle WA 6160", and the row and API had no
     * identity words to be null.
     */
    @Test
    void pathA_quoteIssuedWithoutBillingOrTerms_storesNullLinesAndNullTerms_neverTheLiveOnes_alsoOnTheNextPayment()
            throws Exception {
        long orderId = seedInvoiceReadyOrder(SOFT, QUOTED, false);
        Integer billingRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_address WHERE order_id = ? AND address_type = 'BILLING'::address_type",
                Integer.class, orderId);
        Assertions.assertEquals(0, billingRows, "precondition: no BILLING address when the quote is issued");
        Map<String, Object> accepted = issueAndAcceptQuote(orderId, ONE_PIXEL_PNG);
        Words nullWords = new Words(QUOTED.name(), null, null, null);
        Assertions.assertEquals(nullWords, wordsOfQuoteVersion(accepted),
                "precondition: the issue froze the name, NULL billing lines and NULL terms");

        // After the acceptance: the BILLING row Path A's retained precondition needs, and business terms.
        insertBillingAddress(orderId, LIVE_AT_CONVERSION);
        setBusinessTerms(CONVERSION_TERMS);
        List<String> forbidden = allOf(LIVE_AT_CONVERSION.markers(), CONVERSION_TERMS.texts());

        MvcResult pathA = createInvoiceFromQuote(orderId);

        Map<String, Object> row = invoiceRow(orderId, 1);
        Assertions.assertEquals(nullWords, wordsOfInvoiceRow(row),
                "the quote's name and its NULL billing lines and terms, verbatim (no live fallback)");
        Assertions.assertEquals(wordsOfQuoteVersion(accepted), wordsOfInvoiceRow(row));
        Assertions.assertEquals(asLong(accepted.get("quote_version_id")), asLong(row.get("source_quote_version_id")));
        assertNoneOf(rowText(row), forbidden, "Path A row");

        MvcResult current = getCurrentInvoice(orderId);
        for (Map.Entry<String, MvcResult> response : Map.of("Path A response", pathA, "D.3", current).entrySet()) {
            String label = response.getKey();
            JsonNode invoice = assertInvoiceDetail(response.getValue(), 1, "QUOTE", label);
            Assertions.assertEquals(nullWords, wordsOfDetail(invoice), label + ": the name, null lines, null terms");
            assertPresentNullKeys(response.getValue(), label,
                    "customer_address_line1_snapshot", "customer_address_line2_snapshot", "terms_html");
            Assertions.assertTrue(body(response.getValue()).contains("\"customer_name_snapshot\":\"" + QUOTED.name() + "\""),
                    () -> label + ": the frozen name is still copied");
            assertNoneOf(body(response.getValue()), forbidden, label);
        }

        byte[] pdf = invoicePdfFromDisk(orderId, 1);
        Assertions.assertArrayEquals(pdf, downloadCurrentInvoicePdf(orderId));
        assertInvoiceTo(pdf, nullWords, "Path A PDF");
        assertNoTermsPage(pdf, "Path A PDF");
        assertNoneOf(pdfText(pdf), forbidden, "Path A PDF");

        // ---- D.7 payment -> v2 copies the nulls; still no live fallback ----
        MvcResult paid = recordCashPayment(orderId, "50.00");
        assertCurrentInvoiceSummaryUnchanged(paid, 2, "payment");
        Map<String, Object> v2 = invoiceRow(orderId, 2);
        Assertions.assertEquals(nullWords, wordsOfInvoiceRow(v2), "the payment version copies the nulls verbatim");
        Assertions.assertEquals(row.get("source_quote_version_id"), v2.get("source_quote_version_id"));
        MvcResult afterPayment = getCurrentInvoice(orderId);
        Assertions.assertEquals(nullWords, wordsOfDetail(assertInvoiceDetail(afterPayment, 2, "QUOTE", "D.3 v2")));
        assertPresentNullKeys(afterPayment, "D.3 v2",
                "customer_address_line1_snapshot", "customer_address_line2_snapshot", "terms_html");
        byte[] paymentPdf = invoicePdfFromDisk(orderId, 2);
        assertInvoiceTo(paymentPdf, nullWords, "payment v2 PDF");
        assertNoTermsPage(paymentPdf, "payment v2 PDF");
        assertNoneOf(pdfText(paymentPdf), forbidden, "payment v2 PDF");
    }

    // ================================================================
    // Path A copies the quote's words exactly: it never normalises them (4.2, Path A row: verbatim)
    // ================================================================

    /**
     * Path A copies the accepted version's words EXACTLY as stored. No issue writes the values planted
     * here (the V17 derivation trims each part and joins with single spaces), so a Path A that re-derived,
     * trimmed, blank-to-null'ed or re-sanitised the quote's words would change them: the accepted
     * version's name and line 1 are set by SQL to padded text with a double inner space, its line 2 to
     * NULL while the saved BILLING row has a suburb, and its terms to HTML the production sanitizer would
     * rewrite (an {@code id} attribute, leading spaces, a trailing newline; the fixture first proves
     * sanitize() changes it). The live customer, BILLING address and business terms then move on. Path A
     * must store exactly those strings and that null on the row, return them unchanged on the Path A
     * response and D.3 (QUOTE; the null a present JSON null) and render them in its PDF (Invoice To, the
     * planted terms on page 2); the next payment copies them exactly again. Neither the quote's issued
     * billing lines nor the live words appear anywhere, and the quote row is not written.
     *
     * <p>Fails if Path A passed the quote's words through the Create / Rewrite derivation (blankToNull,
     * trim) or re-sanitised its terms: the row and API would hold "Quilla  Plantedquote" /
     * "3/41  Planted Lane" without their edge padding, or the sanitised terms.
     */
    @Test
    void pathA_copiesTheAcceptedVersionsWordsExactly_paddedTextANullLineAndUnsanitisedTerms_neverNormalised()
            throws Exception {
        setBusinessTerms(ISSUE_TERMS);
        long orderId = seedInvoiceReadyOrder(SOFT, QUOTED, true);
        Map<String, Object> accepted = issueAndAcceptQuote(orderId, ONE_PIXEL_PNG);
        long acceptedVersionId = asLong(accepted.get("quote_version_id"));

        // Words no issue writes: edge padding and a double inner space, a NULL line 2, and terms that are
        // not sanitizer output.
        String plantedTermsText = "Planted quote terms QP5";
        Words planted = new Words("  Quilla  Plantedquote ", " 3/41  Planted Lane  ", null,
                "  <p id=\"qp\" class=\"x\">" + plantedTermsText + "</p>\n");
        Assertions.assertNotEquals(planted.termsHtml(), termsSanitizer.sanitize(planted.termsHtml()),
                "fixture: sanitising the planted terms again would change them");
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE quote_version SET customer_name_snapshot = ?, customer_address_line1_snapshot = ?, "
                        + "customer_address_line2_snapshot = ?, terms_snapshot = ? WHERE quote_version_id = ?",
                planted.name(), planted.line1(), planted.line2(), planted.termsHtml(), acceptedVersionId));
        clearJpaCache();
        Map<String, Object> plantedQuote = quoteVersionRow(orderId, 1);
        Assertions.assertEquals(planted, wordsOfQuoteVersion(plantedQuote), "fixture: the planted quote words");

        // The live customer, BILLING address (which has a suburb) and business terms move on as well.
        saveLiveIdentity(orderId, LIVE_AT_CONVERSION);
        setBusinessTerms(CONVERSION_TERMS);
        // The quote's NAME does appear: it is the inherited accepted name, in accepted_customer_name and the
        // signed caption. Its issued billing lines and terms, and the live words, must appear nowhere.
        List<String> forbidden = allOf(List.of(QUOTED.street(), QUOTED.suburb()), LIVE_AT_CONVERSION.markers(),
                ISSUE_TERMS.texts(), CONVERSION_TERMS.texts());

        MvcResult pathA = createInvoiceFromQuote(orderId);

        Map<String, Object> row = invoiceRow(orderId, 1);
        Assertions.assertEquals(planted, wordsOfInvoiceRow(row),
                "Path A stores the accepted version's words exactly: padding, inner spaces, the null and the terms");
        Assertions.assertEquals(acceptedVersionId, asLong(row.get("source_quote_version_id")));
        assertNoneOf(rowText(row), forbidden, "Path A row");

        MvcResult current = getCurrentInvoice(orderId);
        for (Map.Entry<String, MvcResult> response : Map.of("Path A response", pathA, "D.3", current).entrySet()) {
            String label = response.getKey();
            JsonNode invoice = assertInvoiceDetail(response.getValue(), 1, "QUOTE", label);
            Assertions.assertEquals(planted, wordsOfDetail(invoice), label + ": the planted words, unchanged");
            assertPresentNullKeys(response.getValue(), label, "customer_address_line2_snapshot");
            assertNoneOf(body(response.getValue()), forbidden, label);
        }

        byte[] pdf = invoicePdfFromDisk(orderId, 1);
        Assertions.assertArrayEquals(pdf, downloadCurrentInvoicePdf(orderId), "D.4 streams the stored Path A PDF");
        assertInvoiceTo(pdf, planted, "Path A PDF");
        assertTermsPage(pdf, plantedTermsText, "Path A PDF");
        assertSignedBy(pdf, QUOTED.name(), "Path A PDF");
        assertNoneOf(pdfText(pdf), forbidden, "Path A PDF");

        // ---- D.7 payment -> v2 copies them exactly again ----
        MvcResult paid = recordCashPayment(orderId, "50.00");
        assertCurrentInvoiceSummaryUnchanged(paid, 2, "payment");
        Assertions.assertEquals(planted, wordsOfInvoiceRow(invoiceRow(orderId, 2)),
                "the payment version copies the Path A words exactly");
        JsonNode afterPayment = assertInvoiceDetail(getCurrentInvoice(orderId), 2, "QUOTE", "D.3 v2");
        Assertions.assertEquals(planted, wordsOfDetail(afterPayment), "D.3 v2: the copied words");

        // Path A and the payment read the quote's words; neither writes them.
        Assertions.assertEquals(plantedQuote, quoteVersionRow(orderId, 1), "the accepted quote row is untouched");
    }

    // ================================================================
    // The shared insert boundary: source ownership with the words (4.2, last bullet)
    // ================================================================

    /**
     * {@link InvoiceRepository#insertInvoice} (the autowired bean, the boundary every writer shares) with
     * order A's id and order B's quote_version as the source - passing identity words and terms - throws the
     * ownership {@link IllegalStateException} before inserting anything (through the bean it may arrive
     * wrapped as the cause of {@link InvalidDataAccessApiUsageException} by the @Repository exception
     * translation): order A gains no invoice row and no row anywhere carries the rejected words. With A's
     * OWN accepted quote version the same call succeeds, and the returned {@link InvoiceRow} and the
     * persisted row carry exactly the words passed: a null line stays NULL (never ""), and padded text is
     * stored verbatim (the boundary neither trims nor re-derives).
     *
     * <p>Fails on 16F: insertInvoice had no identity parameters and InvoiceRow / the invoice table no
     * identity columns, so the words could be neither passed nor stored.
     */
    @Test
    void insertInvoice_crossOrderSourceWithWords_throwsBeforeInsert_ownAcceptedSourceStoresExactlyTheWordsPassed()
            throws Exception {
        long orderA = seedInvoiceReadyOrder(SOFT, QUOTED, true);
        long ownVersionId = asLong(issueAndAcceptQuote(orderA, ONE_PIXEL_PNG).get("quote_version_id"));
        long orderB = seedInvoiceReadyOrder(SOFT, LIVE_AT_CONVERSION, true);
        long foreignVersionId = insertBareAcceptedQuoteVersion(orderB);
        long storedFileId = insertStoredFileRow(orderA);
        Assertions.assertEquals(0, invoiceCount(orderA), "precondition: order A has no invoice");

        // ---- Cross-order source: refused before the insert ----
        Words rejected = new Words("Rejected Insert Name", "Rejected Insert Line One", "Rejected Insert Line Two",
                "<p>Rejected insert terms</p>");
        RuntimeException refused = Assertions.assertThrows(RuntimeException.class,
                () -> insertDirect(orderA, storedFileId, foreignVersionId, rejected));
        Throwable ownership = refused instanceof IllegalStateException ? refused : refused.getCause();
        if (ownership != refused) {
            Assertions.assertInstanceOf(InvalidDataAccessApiUsageException.class, refused,
                    () -> "only the @Repository translation may wrap the ownership failure: " + refused);
        }
        Assertions.assertInstanceOf(IllegalStateException.class, ownership,
                () -> "the ownership check throws IllegalStateException: " + refused);
        Assertions.assertEquals("Invoice source quote_version " + foreignVersionId + " belongs to order " + orderB
                        + ", not to the invoice's order " + orderA, ownership.getMessage(),
                "the cross-order ownership failure (not some other error)");
        Assertions.assertEquals(0, invoiceCount(orderA), "order A gains no invoice row");
        Integer rowsWithRejectedWords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoice WHERE customer_name_snapshot = ? OR customer_address_line1_snapshot = ? "
                        + "OR customer_address_line2_snapshot = ? OR terms_snapshot = ?",
                Integer.class, rejected.name(), rejected.line1(), rejected.line2(), rejected.termsHtml());
        Assertions.assertEquals(0, rowsWithRejectedWords, "the rejected words were never written anywhere");

        // ---- Own accepted source: stored exactly as passed ----
        Words passed = new Words("Direct Insert Name", null, "  Direct Line Two 2000  ", "  <p>Direct terms</p>\n");
        InvoiceRow inserted = insertDirect(orderA, storedFileId, ownVersionId, passed);
        Assertions.assertEquals(orderA, inserted.orderId());
        Assertions.assertEquals(ownVersionId, inserted.sourceQuoteVersionId(), "the returned row carries the source");
        Assertions.assertEquals(passed, new Words(inserted.customerNameSnapshot(), inserted.customerAddressLine1Snapshot(),
                        inserted.customerAddressLine2Snapshot(), inserted.termsSnapshot()),
                "the returned InvoiceRow carries exactly the words passed");
        Assertions.assertEquals(1, invoiceCount(orderA));
        Map<String, Object> persisted = invoiceRow(orderA, 1);
        Assertions.assertEquals(passed, wordsOfInvoiceRow(persisted), "the persisted row carries exactly the words passed");
        Assertions.assertNull(persisted.get("customer_address_line1_snapshot"), "a null word is stored NULL, never ''");
        Assertions.assertEquals(ownVersionId, asLong(persisted.get("source_quote_version_id")));
    }

    // ================================================================
    // A Rewrite after Path A freezes fresh BUSINESS words (4.2 D.2 row, 4.4)
    // ================================================================

    /**
     * After Path A (v1: the accepted quote's {@code QUOTED} words and frozen ISSUE terms, QUOTE, signed),
     * a D.2 Rewrite appends v2 with FRESH business words: source_quote_version_id NULL, the acceptance
     * cleared, Invoice To = the CURRENT saved customer and BILLING address ({@code LIVE_AT_CONVERSION}:
     * middle name and unit both present, frozen to the literal strings), terms_snapshot = the CURRENT
     * business SOFT terms through the production sanitizer, terms_source BUSINESS - on the row, the D.2
     * response and an unsigned PDF that shows none of the quote's words. When the live customer and terms
     * then move again ({@code LIVE_LATER} / LATER terms), D.3 still returns v2's stored words, and the Path A
     * row is unchanged.
     *
     * <p>Fails on 16F: the Rewrite wrote terms_snapshot NULL and no identity words, answered terms_source
     * LIVE, and D.3 re-read the business's current terms after the later edit.
     */
    @Test
    void rewriteAfterPathA_freezesFreshBusinessWords_currentSavedCustomerAndSanitisedTerms_sourceAndAcceptanceCleared()
            throws Exception {
        setBusinessTerms(ISSUE_TERMS);
        long orderId = seedInvoiceReadyOrder(SOFT, QUOTED, true);
        Map<String, Object> accepted = issueAndAcceptQuote(orderId, ONE_PIXEL_PNG);
        Words quoteWords = new Words(QUOTED.name(), QUOTED.line1(), QUOTED.line2(), sanitized(ISSUE_TERMS.softRaw()));
        Assertions.assertEquals(quoteWords, wordsOfQuoteVersion(accepted), "precondition: the quote's own words");

        saveLiveIdentity(orderId, LIVE_AT_CONVERSION);
        setBusinessTerms(CONVERSION_TERMS);
        createInvoiceFromQuote(orderId);
        Map<String, Object> pathARow = invoiceRow(orderId, 1);
        Assertions.assertEquals(quoteWords, wordsOfInvoiceRow(pathARow), "precondition: Path A froze the quote's words");
        Assertions.assertNotNull(pathARow.get("source_quote_version_id"), "precondition: v1 is quote-sourced");
        Assertions.assertNotNull(pathARow.get("accepted_at"), "precondition: v1 is signed");

        MvcResult rewrite = rewriteInvoice(orderId, 1);

        Words freshWords = new Words(LIVE_AT_CONVERSION.name(), LIVE_AT_CONVERSION.line1(), LIVE_AT_CONVERSION.line2(),
                sanitized(CONVERSION_TERMS.softRaw()));
        Map<String, Object> v2 = invoiceRow(orderId, 2);
        Assertions.assertEquals(freshWords, wordsOfInvoiceRow(v2),
                "the Rewrite freezes the CURRENT saved customer, BILLING lines and sanitised SOFT business terms");
        for (String cleared : List.of("source_quote_version_id", "accepted_at", "accepted_customer_name",
                "accepted_signature_file_id")) {
            Assertions.assertTrue(v2.containsKey(cleared) && v2.get(cleared) == null,
                    () -> "the Rewrite clears " + cleared + " but it is " + v2.get(cleared));
        }

        JsonNode rewritten = assertInvoiceDetail(rewrite, 2, "BUSINESS", "D.2 rewrite");
        Assertions.assertEquals(freshWords, wordsOfDetail(rewritten), "D.2: the fresh business words");
        Assertions.assertFalse(rewritten.get("accepted_signature_present").booleanValue(), "D.2: acceptance cleared");
        Assertions.assertTrue(rewritten.get("accepted_at").isNull(), "D.2: accepted_at cleared");
        Assertions.assertTrue(rewritten.get("accepted_customer_name").isNull(), "D.2: accepted_customer_name cleared");

        byte[] pdf = invoicePdfFromDisk(orderId, 2);
        Assertions.assertArrayEquals(pdf, downloadCurrentInvoicePdf(orderId));
        assertInvoiceTo(pdf, freshWords, "rewrite v2 PDF");
        assertTermsPage(pdf, CONVERSION_TERMS.softText(), "rewrite v2 PDF");
        String text = pdfText(pdf);
        Assertions.assertTrue(text.contains("Customer signature"), "the rewritten version is unsigned (blank line)");
        Assertions.assertFalse(text.contains("Accepted by"), "the rewritten version carries no acceptance caption");
        // Neither the quote's words nor the HARD business terms (this is a SOFT order) reach the rewrite.
        List<String> forbidden = allOf(QUOTED.markers(), ISSUE_TERMS.texts(), List.of(CONVERSION_TERMS.hardText()));
        assertNoneOf(text, forbidden, "rewrite v2 PDF");
        assertNoneOf(rowText(v2), forbidden, "rewrite v2 row");
        assertNoneOf(body(rewrite), forbidden, "D.2 response");

        // The live customer and business terms move on: D.3 keeps returning v2's stored words.
        saveLiveIdentity(orderId, LIVE_LATER);
        setBusinessTerms(LATER_TERMS);
        MvcResult current = getCurrentInvoice(orderId);
        JsonNode currentDetail = assertInvoiceDetail(current, 2, "BUSINESS", "D.3 after later live edits");
        Assertions.assertEquals(freshWords, wordsOfDetail(currentDetail),
                "D.3 reads the version's stored words, never the later live customer or terms");
        assertNoneOf(body(current), allOf(LIVE_LATER.markers(), LATER_TERMS.texts()), "D.3 after later live edits");

        Assertions.assertEquals(pathARow, invoiceRow(orderId, 1), "the Path A row is never modified");
    }
}
