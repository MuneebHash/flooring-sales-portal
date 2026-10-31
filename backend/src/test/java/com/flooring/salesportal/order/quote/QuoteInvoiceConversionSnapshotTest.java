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
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 * Phase 16F PR2 - Path A ({@code POST /api/v1/{slug}/orders/{orderId}/quote/create-invoice}) proven
 * against REAL persisted state: the invoice row, its {@code stored_file} row, the PDF bytes on disk and
 * the rendered PDF text, never just the response code. Amended for Phase 16G PR 2 (#125): every invoice
 * version stores and renders its own words (the Invoice To name, both billing lines and the terms).
 * Amended for Phase 16G PR 3 (#69, #123; 16G contract sections 5 and 6): every Path A request carries the
 * mandatory {@code {"quote_version_id": N}} (N = the id of the order's latest ACCEPTED quote version, read
 * from the database), every D.8 request the mandatory {@code expected_version_number} (the current invoice
 * version) and no name part, and a successful Path A emails the signed PDF of the version it appended
 * after the conversion (the delivered message; in this transactional context the success stamp sets that
 * version's {@code last_emailed_at} and the dashboard mirror to the same value).
 *
 * <ul>
 *   <li><b>Snapshot fidelity</b> in both quote modes (itemised, and non-itemised over dormant itemised
 *       draft rows). After the quote is signed, the live details of sale, working price, product and
 *       charge lines, customer name, billing street and tenant terms all move on; the invoice still
 *       carries the signed snapshot (details, totals, accepted name and time, frozen terms), and its
 *       Invoice To is the accepted quote version's {@code V17} name and billing lines, copied into the
 *       invoice row's three identity columns, so the renamed customer and the changed street reach
 *       neither the row, the API nor the PDF. No quote line reaches the invoice or its PDF, no
 *       invoice line table exists, and Products and Charges are never written. Totals that do not
 *       round-trip through x 1.10 (1000.06 inc / 909.15 ex) are copied verbatim, never re-grossed.</li>
 *   <li><b>Acceptance, files and side effects</b>: the invoice references the quote's own signature
 *       {@code stored_file} (no new signature row or file), exactly one new stored file (the invoice
 *       PDF) is written and embeds that signature, that PDF is the ONE email Path A sends (16G PR 3: to
 *       the saved customer email; no quote email, store notification or SMS), the success stamp replaces
 *       the dashboard email mirror sentinel with the new version's own stamp, and every other order /
 *       quote / payment row - including a newer ISSUED quote and its
 *       ACTIVE token - is left untouched. The inherited signature time is the quote's to the
 *       microsecond (never the conversion time), and a 201-character accepted name is inherited
 *       untruncated.</li>
 *   <li><b>Terms</b>: the InvoiceDetail {@code terms_html} / {@code terms_source} rule on every
 *       InvoiceDetail response - {@code terms_html} is the version's stored {@code terms_snapshot}
 *       verbatim, reported as QUOTE (the signed quote's frozen terms, including frozen null) or BUSINESS
 *       (the business's per-flooring-type terms, sanitised once and frozen at Create / Rewrite, then
 *       carried by payment, void and D.8) - and the same terms in every PDF that Path A, payment, void
 *       and Path B produce; a later business terms edit or clear never reaches an existing version or
 *       the next payment version. Every InvoiceDetail response also carries the three frozen Invoice To
 *       keys. Frozen terms the sanitizer would rewrite are carried byte for byte, never re-sanitised.</li>
 * </ul>
 *
 * <p>Self-seeded (Phase 14D): orders are inserted under business 1 / store 1 / user 1 (slug
 * {@code aussie-floors-group}) with the {@code QINVS.ZZ9.} order-number prefix (seq base 140_000) and
 * {@code QINVS}-prefixed store charge / product codes; no V4 demo order, invoice or payment is used.
 * Accepted quotes always come from the real chain (draft PUT, protected send-email, token from the
 * recorded link-only email, public multipart accept without a session). Raw SQL only moves live state
 * on after the signature, sets tenant terms / the logo path, seeds the dashboard email sentinel, sets a
 * long customer name BEFORE the issue, and (in two hardening tests) gives the already-accepted version a
 * distinctive accepted_at or terms_snapshot after the real accept and before Path A.
 * Everything rolls back with the test transaction, and the services' rollback hooks delete the files
 * they wrote under the shared 16F storage root ({@code target/test-storage/quote-acceptance}). The
 * recording senders are singletons whose state survives the rollback, so all four (invoice email, quote
 * email, store notification, SMS) are reset before AND after every test.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
@Transactional
class QuoteInvoiceConversionSnapshotTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";
    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    /** Must equal the {@code @SpringBootTest} property above (asserted in {@link #setUp}). */
    private static final String STORAGE_BASE_DIR = "target/test-storage/quote-acceptance";

    private static final String ORDER_NUMBER_PREFIX = "QINVS.ZZ9.";
    private static final String CODE_PREFIX = "QINVS";

    private static final String SOFT = "SOFT";
    private static final String HARD = "HARD";

    private static final String VALID_EMAIL = "quote.invoice.snapshot@example.com";
    private static final String VALID_MOBILE = "0412345678";
    /** The V17 issue-time name snapshot of the seeded customer ('Quote' + 'Tester'): the frozen accepted name. */
    private static final String SNAPSHOT_NAME = "Quote Tester";

    // Exactly 100 characters each (the order_customer VARCHAR(100) maximum), so the V17 snapshot
    // "first last" is 201 characters: beyond the old VARCHAR(150) accepted-name width and the D.8 cap.
    private static final String LONG_FIRST_NAME =
            "Alexandria Bernadette Cassiopeia Dorothea Evangeline Francesca Genevieve Henrietta Isadora Josephine";
    private static final String LONG_LAST_NAME =
            "Kensington Livingstone Montgomery Northcott Pemberton Quartermaine Rutherford Stanhope Tremaine Ward";

    private static final String FROZEN_DETAILS = "Supply and lay QINVS frozen carpet details";
    private static final String LIVE_DETAILS = "QINVS live details rewritten after the signature";

    private static final String LINE_ALPHA = "QLINE ALPHA CARPET";
    private static final String LINE_BETA = "QLINE BETA UNDERLAY";

    private static final String ORIGINAL_BILLING_STREET = "Original Billing Lane";
    private static final String CHANGED_BILLING_STREET = "Changed Billing Road";
    private static final String LIVE_FIRST_NAME = "Livefirst";
    private static final String LIVE_LAST_NAME = "Livelast";
    // The seeded BILLING address as the V17 issue snapshot and D.1 / D.2 freeze it (16G PR 2): no unit.
    private static final String ORIGINAL_BILLING_LINE1 = "12 " + ORIGINAL_BILLING_STREET;
    private static final String BILLING_LINE2 = "Sydney NSW 2000";

    /** proposed_lay_date 2026-12-01 minus 2 days. */
    private static final LocalDate EXPECTED_DUE_DATE = LocalDate.of(2026, 11, 29);

    /**
     * A fixed PAST signature instant with microseconds, SQL-set on the accepted quote version after the
     * real accept: must equal the SQL literal {@code TIMESTAMP '2026-02-03 04:05:06.123456'} below.
     */
    private static final LocalDateTime INHERITED_ACCEPTED_AT = LocalDateTime.of(2026, 2, 3, 4, 5, 6, 123_456_000);

    // Tenant terms. The TEXT is what the PDF checks look for; the HTML is what the business row stores.
    private static final String ISSUE_TERMS_TEXT = "Frozen at issue terms QF1";
    private static final String ISSUE_TERMS_HTML = "<p>" + ISSUE_TERMS_TEXT + "</p>";
    private static final String AFTER_ACCEPTANCE_TERMS_TEXT = "Live terms after acceptance QL2";
    private static final String AFTER_ACCEPTANCE_TERMS_HTML = "<p>" + AFTER_ACCEPTANCE_TERMS_TEXT + "</p>";
    private static final String EDITED_TERMS_TEXT = "Edited live terms after conversion QE3";
    private static final String EDITED_TERMS_HTML = "<p>" + EDITED_TERMS_TEXT + "</p>";
    // Raw per-type live terms that the sanitizer must rewrite (event handler, link, script, image dropped).
    private static final String LIVE_SOFT_TERMS_TEXT = "Live soft terms QS9";
    private static final String RAW_LIVE_SOFT_TERMS = "<p onclick=\"steal()\">" + LIVE_SOFT_TERMS_TEXT
            + " <a href=\"https://example.com/terms\">see store</a></p><script>alert('x')</script>";
    private static final String LIVE_HARD_TERMS_TEXT = "Live hard terms QH9";
    private static final String RAW_LIVE_HARD_TERMS = "<div><strong>" + LIVE_HARD_TERMS_TEXT
            + "</strong><img src=\"https://example.com/terms.png\" /></div>";
    private static final String KEYS_TERMS_TEXT = "Live terms for the key checks QK1";
    private static final String KEYS_TERMS_HTML = "<p>" + KEYS_TERMS_TEXT + "</p>";
    private static final String KEYS_EDITED_TERMS_TEXT = "Edited live terms for the key checks QK2";
    private static final String KEYS_EDITED_TERMS_HTML = "<p>" + KEYS_EDITED_TERMS_TEXT + "</p>";
    private static final String PATH_B_TERMS_TEXT = "Path B live terms QB1";
    private static final String PATH_B_TERMS_HTML = "<p>" + PATH_B_TERMS_TEXT + "</p>";
    private static final String PATH_B_EDITED_TERMS_TEXT = "Path B edited live terms QB2";
    private static final String PATH_B_EDITED_TERMS_HTML = "<p>" + PATH_B_EDITED_TERMS_TEXT + "</p>";
    // Frozen terms the sanitizer WOULD rewrite (id is not on its safelist), yet XML-safe so the template
    // renders them: any re-sanitising of a frozen value becomes visible, and the leading spaces and the
    // trailing newline make any trim visible too.
    private static final String UNSANITISED_FROZEN_TERMS_TEXT = "Frozen terms PR2V";
    private static final String UNSANITISED_FROZEN_TERMS =
            "  <p class=\"q\" id=\"k\">" + UNSANITISED_FROZEN_TERMS_TEXT + "</p>\n";
    // Live tenant terms that must never replace the frozen value above (set before issue, edited after Path A).
    private static final String FALLBACK_LIVE_TERMS_TEXT = "Live fallback terms QX5";
    private static final String FALLBACK_LIVE_TERMS_HTML = "<p>" + FALLBACK_LIVE_TERMS_TEXT + "</p>";
    private static final String FALLBACK_EDITED_TERMS_TEXT = "Edited live fallback terms QX6";
    private static final String FALLBACK_EDITED_TERMS_HTML = "<p>" + FALLBACK_EDITED_TERMS_TEXT + "</p>";

    private static final String FOOTER = "Generated by the Flooring Sales Portal";
    // The "Terms & Conditions" heading renders uppercase (CSS); page 1 only ever says "terms" in lowercase.
    private static final String TERMS_HEADING = "TERMS";

    // 16G PR 3 (contract 6.1): the Path A 201 message when the post-commit signed-PDF email was delivered.
    private static final String CREATED_FROM_QUOTE_MESSAGE =
            "Invoice created from accepted quote and emailed to the customer.";

    /**
     * openapi InvoiceDetail: the Phase 12 / 13 keys, the two Phase 16F PR2 terms keys and the three
     * Phase 16G PR 2 frozen Invoice To keys, ALWAYS present.
     */
    private static final Set<String> INVOICE_DETAIL_KEYS = Set.of(
            "invoice_id", "order_id", "version_number", "invoice_date", "due_date",
            "details_of_sale_snapshot", "sale_price_ex_gst", "sale_price_inc_gst", "total_paid", "balance_due",
            "created_by_user_id", "created_at", "pdf_download_path", "accepted_at", "accepted_customer_name",
            "accepted_signature_present", "accepted_signature_download_path", "last_emailed_at",
            "terms_html", "terms_source",
            // 16G PR 2 (#125): the version's frozen Invoice To words, always present (nullable).
            "customer_name_snapshot", "customer_address_line1_snapshot", "customer_address_line2_snapshot",
            // 16G PR 4: the derived link field, always present (nullable; never on E.1 or a public payload).
            "invoice_link_expires_at");

    // The plaintext token inside the delivered link .../q/{token} (URL-safe Base64).
    private static final Pattern PUBLIC_LINK_TOKEN = Pattern.compile("/q/([A-Za-z0-9_-]{43,128})");

    // A real, decodable 1x1 PNG (the public accept decodes and re-encodes the upload).
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    // The signed-caption timestamp pattern (InvoicePdfGenerator).
    private static final DateTimeFormatter DISPLAY_DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH);
    // The issued / due date pattern (InvoicePdfGenerator).
    private static final DateTimeFormatter DISPLAY_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH);

    // Exact money comparisons: JSON decimals are read as BigDecimal (never through a double).
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingInvoiceEmailSender invoiceEmailSender;

    @Autowired
    private RecordingQuoteEmailSender quoteEmailSender;

    @Autowired
    private RecordingQuoteAcceptanceNotificationSender acceptanceNotificationSender;

    // Path A never sends an SMS (16G PR 3 keeps it email only); SMS is dormant and recording-only.
    @Autowired
    private RecordingSmsSender smsSender;

    // The production sanitizer: the BUSINESS terms D.1 / D.2 freeze (and so terms_html) must equal exactly
    // what it makes of the raw tenant terms.
    @Autowired
    private InvoiceTermsSanitizer termsSanitizer;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    private MockMvc mockMvc;

    private int seq = 140_000;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        resetSenders();
        Assertions.assertEquals(STORAGE_BASE_DIR, storageBaseDir,
                "this class asserts files under the shared 16F storage base dir");
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
    // Helpers - session, URLs, JPA cache
    // ================================================================

    private static MockHttpSession liamStore1Session() {
        MockHttpSession s = new MockHttpSession();
        // Type trap: SessionContext casts (Long) user_id / business_id and (Integer) store_id.
        s.setAttribute("user_id", USER_LIAM);
        s.setAttribute("business_id", BUSINESS_AUSSIE);
        s.setAttribute("store_id", STORE_SYD_CBD);
        return s;
    }

    private static String orderUrl(long orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId;
    }

    private static String quoteUrl(long orderId, String suffix) {
        return orderUrl(orderId) + "/quote/" + suffix;
    }

    private static String invoicesUrl(long orderId) {
        return orderUrl(orderId) + "/invoices";
    }

    private static String publicQuoteUrl(String token) {
        return "/api/v1/public/quotes/" + token;
    }

    /**
     * Detach every hydrated entity. Tests share ONE transaction / persistence context across MockMvc
     * calls, so after a raw JDBC write a later JPA read (customer, address, order, business) would
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
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Assertions.assertEquals(expectedStatus, result.getResponse().getStatus(),
                () -> label + " returned an unexpected status; body: " + body);
        return result;
    }

    private static String body(MvcResult result) throws IOException {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static JsonNode readJson(MvcResult result) throws IOException {
        return JSON.readTree(body(result));
    }

    // ================================================================
    // Helpers - self-seeding (business 1 / store 1 / user 1 only)
    // ================================================================

    /**
     * An order that passes every invoice / quote gate: header with details of sale (the text the quote
     * freezes at issue), proposed lay date 2026-12-01 and lay date status CONFIRMED; customer
     * 'Quote' 'Tester' with a valid email; BILLING ("12 Original Billing Lane") and INSTALLATION
     * addresses; one priced charge line of 100.00 ex / 40.00 cost (below every quote total here).
     */
    private long invoiceReadyOrder(String flooringType) {
        int s = ++seq;
        // order_number must match chk_sales_order_number_format (V6): {code}.{LL#}.{#####}
        String orderNumber = ORDER_NUMBER_PREFIX + String.format("%05d", s % 100_000);
        Long orderId = jdbcTemplate.queryForObject(
                "INSERT INTO sales_order "
                        + "(business_id, store_id, user_id, order_sequence_number, order_number, flooring_type, "
                        + " order_status, week_number, week_year, details_of_sale, proposed_lay_date, lay_date_status) "
                        + "VALUES (?, ?, ?, ?, ?, ?::flooring_type, 'LEAD'::order_status, 1, 2026, ?, "
                        + " DATE '2026-12-01', 'CONFIRMED'::lay_date_status) RETURNING order_id",
                Long.class,
                BUSINESS_AUSSIE, STORE_SYD_CBD, USER_LIAM, s, orderNumber, flooringType, FROZEN_DETAILS);
        Assertions.assertNotNull(orderId, "order insert must return an id");
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, last_name, email, mobile) "
                        + "VALUES (?, 'Quote', 'Tester', ?, ?)",
                orderId, VALID_EMAIL, VALID_MOBILE);
        insertAddress(orderId, "BILLING", "12", ORIGINAL_BILLING_STREET);
        insertAddress(orderId, "INSTALLATION", "7", "Install Street");
        seedChargeLine(orderId, flooringType, "100.00", "40.00");
        clearJpaCache();
        return orderId;
    }

    private void insertAddress(long orderId, String addressType, String streetNumber, String street) {
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, ?::address_type, NULL, ?, ?, 'Sydney', 'NSW', '2000')",
                orderId, addressType, streetNumber, street);
    }

    /** One store_charge + order_charge_line (qty 1), with a QINVS-prefixed code. */
    private void seedChargeLine(long orderId, String flooringType, String lineTotal, String lineCost) {
        String code = CODE_PREFIX + "C" + (++seq);
        Long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, ?::flooring_type, ?, 'QINVS conversion test charge', ?, ?) RETURNING charge_id",
                Long.class, STORE_SYD_CBD, flooringType, code, new BigDecimal(lineTotal), new BigDecimal(lineCost));
        jdbcTemplate.update(
                "INSERT INTO order_charge_line "
                        + "(order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'QINVS conversion test charge', ?, ?, 1, ?, ?, ?)",
                orderId, chargeId, code,
                new BigDecimal(lineTotal), new BigDecimal(lineCost),
                new BigDecimal(lineTotal), new BigDecimal(lineTotal), new BigDecimal(lineCost));
    }

    /** One store_product + order_product_line (SQM, qty 1, factor 3.66), with a QINVS-prefixed code. */
    private void seedProductLine(long orderId, String flooringType, String lineTotal, String lineCost) {
        String code = CODE_PREFIX + "P" + (++seq);
        Long productId = jdbcTemplate.queryForObject(
                "INSERT INTO store_product (store_id, flooring_type, code, name, pricing_unit, price, cost) "
                        + "VALUES (?, ?::flooring_type, ?, 'QINVS conversion test product', 'SQM'::pricing_unit, ?, ?) "
                        + "RETURNING product_id",
                Long.class, STORE_SYD_CBD, flooringType, code, new BigDecimal(lineTotal), new BigDecimal(lineCost));
        jdbcTemplate.update(
                "INSERT INTO order_product_line "
                        + "(order_id, product_id, product_code_snapshot, product_name_snapshot, "
                        + " pricing_unit_snapshot, price_snapshot, cost_snapshot, quantity_lm, quantity_sqm, "
                        + " unit_price, line_total, line_cost, sqm_per_lm_snapshot) "
                        + "VALUES (?, ?, ?, 'QINVS conversion test product', 'SQM'::pricing_unit, ?, ?, "
                        + " 1.00, 3.66, ?, ?, ?, 3.66)",
                orderId, productId, code,
                new BigDecimal(lineTotal), new BigDecimal(lineCost),
                new BigDecimal(lineTotal), new BigDecimal(lineTotal), new BigDecimal(lineCost));
    }

    /** Tenant per-flooring-type terms (V13 columns), then a cache clear so the next request re-reads them. */
    private void setTenantTerms(String termsSoft, String termsHard) {
        jdbcTemplate.update("UPDATE business SET terms_soft = ?, terms_hard = ? WHERE business_id = ?",
                termsSoft, termsHard, BUSINESS_AUSSIE);
        clearJpaCache();
    }

    /** Deterministic "no tenant terms" (dev-seeded terms text could otherwise fog the checks). */
    private void clearTenantTerms() {
        setTenantTerms(null, null);
    }

    /** No logo image in any PDF of this test, so the only image an invoice PDF can embed is a signature. */
    private void clearBusinessLogo() {
        jdbcTemplate.update("UPDATE business SET logo_path = NULL WHERE business_id = ?", BUSINESS_AUSSIE);
        clearJpaCache();
    }

    private void saveDraft(long orderId, String json) throws Exception {
        perform(put(quoteUrl(orderId, "draft")).session(liamStore1Session())
                .contentType(MediaType.APPLICATION_JSON).content(json), 200, "PUT quote draft");
    }

    /** Itemised: "QLINE ALPHA CARPET" 2 x 100 + "QLINE BETA UNDERLAY" 1 x 50 = 250.00 ex / 275.00 inc. */
    private void saveDistinctiveItemisedDraft(long orderId) throws Exception {
        saveDraft(orderId, """
                {"itemised": true, "lines": [
                  {"line_type":"ITEM","description":"QLINE ALPHA CARPET","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
                  {"line_type":"ITEM","description":"QLINE BETA UNDERLAY","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
                ]}""");
    }

    /** Non-itemised (header-only save: previously persisted itemised rows are RETAINED as dormant rows). */
    private void saveNonItemisedDraft(long orderId, String finalIncTotal) throws Exception {
        saveDraft(orderId, "{\"itemised\": false, \"final_total_inc_gst\": " + finalIncTotal + ", \"lines\": []}");
    }

    /** Issue (or re-issue) via the protected send-email and return the plaintext token from the link. */
    private String sendAndExtractToken(long orderId) throws Exception {
        int sentBefore = quoteEmailSender.sentEmails().size();
        perform(post(quoteUrl(orderId, "send-email")).session(liamStore1Session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 201, "POST quote send-email");
        List<QuoteEmailRequest> sent = quoteEmailSender.sentEmails();
        Assertions.assertEquals(sentBefore + 1, sent.size(), "exactly one quote email must have been recorded");
        Matcher matcher = PUBLIC_LINK_TOKEN.matcher(sent.get(sent.size() - 1).bodyText());
        Assertions.assertTrue(matcher.find(), "the recorded quote email must carry the /q/{token} link");
        return matcher.group(1);
    }

    private static MockMultipartFile signaturePart(byte[] png) {
        return new MockMultipartFile("signature", "signature.png", "image/png", png);
    }

    /** The REAL public accept: token-only (NO session), exactly one PNG {@code signature} part. */
    private void acceptPublicly(String token, byte[] signaturePng) throws Exception {
        MvcResult result = perform(multipart(publicQuoteUrl(token) + "/accept").file(signaturePart(signaturePng)),
                201, "public quote accept");
        JsonNode json = readJson(result);
        Assertions.assertEquals("INACTIVE", json.path("data").path("state").asText());
        Assertions.assertEquals("Quote accepted.", json.path("message").asText());
    }

    /** Ready order of {@code flooringType} + the distinctive itemised draft, sent as v1 and publicly accepted. */
    private long acceptedItemisedOrder(String flooringType, byte[] signaturePng) throws Exception {
        long orderId = invoiceReadyOrder(flooringType);
        saveDistinctiveItemisedDraft(orderId);
        acceptPublicly(sendAndExtractToken(orderId), signaturePng);
        Assertions.assertEquals("ACCEPTED", versionRow(orderId, 1).get("status"),
                "precondition: v1 accepted through the real public endpoint");
        return orderId;
    }

    // ================================================================
    // Helpers - the invoice endpoints
    // ================================================================

    /**
     * Path A (16G PR 3): POST the mandatory {@code {"quote_version_id": N}}, N = the id of the order's latest
     * ACCEPTED quote version read from the database (never hard-coded); 201 with the exact delivered
     * message. The post-commit email is proven on every call: exactly ONE more invoice email - the appended
     * version's stored PDF byte for byte, to the trimmed saved customer email - and no failed attempt, no
     * quote email, no store notification and no SMS; and (this transactional context) the success stamp set
     * the appended version's {@code last_emailed_at}, the dashboard mirror to the same value and the
     * response's {@code last_emailed_at}. Returns the result for body / JSON checks.
     */
    private MvcResult createInvoiceFromQuote(long orderId) throws Exception {
        long acceptedQuoteVersionId = latestAcceptedQuoteVersionId(orderId);
        SenderCounts before = senderCounts();
        MvcResult result = perform(post(quoteUrl(orderId, "create-invoice")).session(liamStore1Session())
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

    /** The order's CURRENT (highest) invoice version_number. */
    private int currentInvoiceVersion(long orderId) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT MAX(version_number) FROM invoice WHERE order_id = ?", Integer.class, orderId);
        Assertions.assertNotNull(version, "precondition: the order has an invoice");
        return version;
    }

    /** D.1 Create (empty body). */
    private MvcResult createInvoice(long orderId) throws Exception {
        MvcResult result = perform(post(invoicesUrl(orderId)).session(liamStore1Session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 201, "D.1 create");
        Assertions.assertEquals("Invoice created.", readJson(result).path("message").asText());
        return result;
    }

    /** D.2 Rewrite carrying the order's current version as expected_version_number (Batch A PR 2, #149). */
    private MvcResult rewriteInvoice(long orderId) throws Exception {
        MvcResult result = perform(post(invoicesUrl(orderId) + "/rewrite").session(liamStore1Session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expected_version_number\":" + currentInvoiceVersion(orderId) + "}"), 201, "D.2 rewrite");
        Assertions.assertEquals("Invoice rewritten.", readJson(result).path("message").asText());
        return result;
    }

    /** D.3 GET current (no top-level message). */
    private MvcResult getCurrentInvoice(long orderId) throws Exception {
        MvcResult result = perform(get(invoicesUrl(orderId) + "/current").session(liamStore1Session()),
                200, "D.3 get current");
        Assertions.assertFalse(readJson(result).has("message"), "D.3 has no top-level message");
        return result;
    }

    /**
     * D.8 in-app acceptance (16G PR 3): the signature + the mandatory expected_version_number = the order's
     * CURRENT invoice version (read from the database). No name part: the accepted name is the current
     * version's frozen customer_name_snapshot.
     */
    private MvcResult acceptInvoiceInApp(long orderId) throws Exception {
        return perform(multipart(invoicesUrl(orderId) + "/current/accept")
                .file(signaturePart(ONE_PIXEL_PNG))
                .param("expected_version_number", String.valueOf(currentInvoiceVersion(orderId)))
                .session(liamStore1Session()), 201, "D.8 accept");
    }

    /** 16G PR 4 send-link (empty body): returns the 201's data.invoice_link.expires_at. */
    private String sendInvoiceLink(long orderId) throws Exception {
        MvcResult result = perform(post(invoicesUrl(orderId) + "/current/send-link").session(liamStore1Session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 201, "send-link");
        JsonNode json = readJson(result);
        Assertions.assertEquals("Invoice link emailed to the customer.", json.path("message").asText());
        JsonNode expiresAt = json.path("data").path("invoice_link").path("expires_at");
        Assertions.assertTrue(expiresAt.isTextual(), () -> "send-link: data.invoice_link.expires_at: " + json);
        return expiresAt.asText();
    }

    /** D.9 resend (empty body). */
    private MvcResult resendInvoice(long orderId) throws Exception {
        MvcResult result = perform(post(invoicesUrl(orderId) + "/current/resend").session(liamStore1Session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 200, "D.9 resend");
        Assertions.assertEquals("Invoice re-sent to the customer.", readJson(result).path("message").asText());
        return result;
    }

    /** D.7 record a CASH payment (appends a new invoice version; never emails). */
    private MvcResult recordCashPayment(long orderId, String amount) throws Exception {
        MvcResult result = perform(post(orderUrl(orderId) + "/payments").session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_method\": \"CASH\", \"amount\": " + amount + "}"),
                201, "D.7 record payment");
        Assertions.assertEquals("Payment recorded. Current invoice updated.", readJson(result).path("message").asText());
        return result;
    }

    /** D.10 soft-void a payment (no body; appends a new invoice version; never emails). */
    private MvcResult voidPayment(long orderId, long paymentId) throws Exception {
        MvcResult result = perform(post(orderUrl(orderId) + "/payments/" + paymentId + "/void")
                .session(liamStore1Session()), 201, "D.10 void payment");
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
        MvcResult result = perform(get(invoicesUrl(orderId) + "/current/file").session(liamStore1Session()),
                200, "D.4 current invoice file");
        Assertions.assertEquals(MediaType.APPLICATION_PDF_VALUE, result.getResponse().getContentType());
        return result.getResponse().getContentAsByteArray();
    }

    /** The inline Content-Disposition file name of a binary download (never a storage path). */
    private static String inlineFileName(MvcResult result) {
        String header = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        Assertions.assertNotNull(header, "Content-Disposition must be set");
        ContentDisposition disposition = ContentDisposition.parse(header);
        Assertions.assertTrue(disposition.isInline(), () -> "inline disposition expected: " + header);
        Assertions.assertFalse(header.contains("/uploads/"), () -> "storage path must never leak: " + header);
        return disposition.getFilename();
    }

    /** Public GET (no session): 200 with the link's customer-facing state. */
    private void assertPublicQuoteState(String token, String expectedState) throws Exception {
        MvcResult result = perform(get(publicQuoteUrl(token)), 200, "public quote GET");
        Assertions.assertEquals(expectedState, readJson(result).path("data").path("state").asText(),
                "public link state");
    }

    // ================================================================
    // Helpers - DB rows, files and disk
    // ================================================================

    private Map<String, Object> versionRow(long orderId, int versionNumber) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM quote_version WHERE order_id = ? AND version_number = ?", orderId, versionNumber);
    }

    private Map<String, Object> tokenRow(String plainToken) {
        return jdbcTemplate.queryForMap("SELECT * FROM quote_token WHERE token_hash = ?", sha256Hex(plainToken));
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

    private int versionLineCount(long orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_version_line l "
                        + "JOIN quote_version v ON l.quote_version_id = v.quote_version_id WHERE v.order_id = ?",
                Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private int draftLineCount(long orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_draft_line l JOIN quote_draft d ON l.quote_draft_id = d.quote_draft_id "
                        + "WHERE d.order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private List<Map<String, Object>> productLines(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM order_product_line WHERE order_id = ? ORDER BY order_product_line_id", orderId);
    }

    private List<Map<String, Object>> chargeLines(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM order_charge_line WHERE order_id = ? ORDER BY order_charge_line_id", orderId);
    }

    private Timestamp salesOrderLastEmailedAt(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_emailed_at FROM sales_order WHERE order_id = ?", Timestamp.class, orderId);
    }

    private String orderNumber(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT order_number FROM sales_order WHERE order_id = ?", String.class, orderId);
    }

    private Map<String, Object> storedFileRow(long storedFileId) {
        return jdbcTemplate.queryForMap("SELECT * FROM stored_file WHERE stored_file_id = ?", storedFileId);
    }

    private long maxStoredFileId() {
        Long max = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(stored_file_id), 0) FROM stored_file", Long.class);
        return max == null ? 0L : max;
    }

    /** FileStorageService writes every order file under /uploads/{businessId}/orders/{orderId}/. */
    private static String orderStoragePrefix(long orderId) {
        return "/uploads/" + BUSINESS_AUSSIE + "/orders/" + orderId + "/";
    }

    /** The order's stored_file rows (precise: by its own storage prefix), keyed by stored_file_id. */
    private Map<Long, Map<String, Object>> storedFilesForOrder(long orderId) {
        Map<Long, Map<String, Object>> rows = new TreeMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT * FROM stored_file WHERE storage_path LIKE ? ORDER BY stored_file_id",
                orderStoragePrefix(orderId) + "%")) {
            rows.put(asLong(row.get("stored_file_id")), new LinkedHashMap<>(row));
        }
        return rows;
    }

    private Path storageRoot() {
        return Path.of(storageBaseDir).toAbsolutePath().normalize();
    }

    /** Physical file of a stored_file row: base-dir + storage_path (which starts with "/uploads/"). */
    private Path diskPath(String storagePath) {
        Assertions.assertTrue(storagePath.startsWith("/uploads/"), "unexpected storage_path " + storagePath);
        return storageRoot().resolve(storagePath.substring(1)).normalize();
    }

    private byte[] diskBytes(String storagePath) throws IOException {
        Path file = diskPath(storagePath);
        Assertions.assertTrue(Files.isRegularFile(file), "expected a file on disk at " + file);
        return Files.readAllBytes(file);
    }

    private Set<String> diskFilesForOrder(long orderId) throws IOException {
        Path dir = storageRoot().resolve(orderStoragePrefix(orderId).substring(1));
        if (!Files.isDirectory(dir)) {
            return new TreeSet<>();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static String fileNameOf(String storagePath) {
        return storagePath.substring(storagePath.lastIndexOf('/') + 1);
    }

    /** The stored PDF of one invoice version, read from disk through its stored_file row. */
    private byte[] invoicePdfFromDisk(long orderId, int versionNumber) throws IOException {
        String storagePath = jdbcTemplate.queryForObject(
                "SELECT sf.storage_path FROM invoice i JOIN stored_file sf ON sf.stored_file_id = i.stored_file_id "
                        + "WHERE i.order_id = ? AND i.version_number = ?",
                String.class, orderId, versionNumber);
        Assertions.assertNotNull(storagePath, "invoice v" + versionNumber + " must reference a stored_file");
        byte[] pdf = diskBytes(storagePath);
        assertPdfBytes(pdf);
        return pdf;
    }

    /**
     * Everything Path A must never write, as one comparable snapshot: every quote version / version line
     * / token row of the order, the quote draft + its lines, the WHOLE sales_order row except
     * {@code last_emailed_at} (so order_status, price_adjustment_inc_gst, sale_price_ex_gst, total_cost,
     * gp, gp_percent and updated_at among others), the product and charge lines, payments, and the
     * customer and address rows.
     */
    private Map<String, Object> untouchedState(long orderId) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("quote_version", jdbcTemplate.queryForList(
                "SELECT * FROM quote_version WHERE order_id = ? ORDER BY version_number", orderId));
        state.put("quote_version_line", jdbcTemplate.queryForList(
                "SELECT l.* FROM quote_version_line l JOIN quote_version v ON v.quote_version_id = l.quote_version_id "
                        + "WHERE v.order_id = ? ORDER BY l.quote_version_line_id", orderId));
        state.put("quote_token", jdbcTemplate.queryForList(
                "SELECT t.* FROM quote_token t JOIN quote_version v ON v.quote_version_id = t.quote_version_id "
                        + "WHERE v.order_id = ? ORDER BY t.quote_token_id", orderId));
        state.put("quote_draft", jdbcTemplate.queryForList(
                "SELECT * FROM quote_draft WHERE order_id = ? ORDER BY quote_draft_id", orderId));
        state.put("quote_draft_line", jdbcTemplate.queryForList(
                "SELECT l.* FROM quote_draft_line l JOIN quote_draft d ON d.quote_draft_id = l.quote_draft_id "
                        + "WHERE d.order_id = ? ORDER BY l.quote_draft_line_id", orderId));
        Map<String, Object> order = new LinkedHashMap<>(
                jdbcTemplate.queryForMap("SELECT * FROM sales_order WHERE order_id = ?", orderId));
        // The section 11.1 mirror reset is the ONE order write Path A makes (asserted separately).
        order.remove("last_emailed_at");
        state.put("sales_order", order);
        state.put("order_product_line", productLines(orderId));
        state.put("order_charge_line", chargeLines(orderId));
        state.put("payment_transaction", jdbcTemplate.queryForList(
                "SELECT * FROM payment_transaction WHERE order_id = ? ORDER BY payment_transaction_id", orderId));
        state.put("order_customer", jdbcTemplate.queryForList(
                "SELECT * FROM order_customer WHERE order_id = ? ORDER BY order_customer_id", orderId));
        state.put("order_address", jdbcTemplate.queryForList(
                "SELECT * FROM order_address WHERE order_id = ? ORDER BY order_address_id", orderId));
        return state;
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

    private String businessName() {
        return jdbcTemplate.queryForObject("SELECT name FROM business WHERE business_id = ?",
                String.class, BUSINESS_AUSSIE);
    }

    /**
     * One signed-PDF invoice email (16G contract 6.1): to the trimmed saved customer email, the standard
     * subject and body, and the stored PDF of invoice v{@code versionNumber} attached byte for byte under
     * its own file name.
     */
    private void assertSignedPdfEmail(long orderId, int versionNumber, InvoiceEmailRequest email, String label)
            throws IOException {
        String orderNumber = orderNumber(orderId);
        String businessName = businessName();
        String savedEmail = jdbcTemplate.queryForObject(
                "SELECT email FROM order_customer WHERE order_id = ?", String.class, orderId);
        Assertions.assertNotNull(savedEmail, "precondition: a saved customer email");
        Assertions.assertEquals(savedEmail.trim(), email.recipientEmail(), label + ": the trimmed saved customer email");
        Assertions.assertEquals("Your invoice from " + businessName, email.subject(), label + ": email subject");
        Assertions.assertEquals("Hi,\n\nPlease find your invoice " + orderNumber + " from " + businessName
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
        Assertions.assertEquals(invoiceStamp, salesOrderLastEmailedAt(orderId),
                label + ": the dashboard mirror carries the same stamp");
        JsonNode responseStamp = readJson(result).path("data").path("invoice").path("last_emailed_at");
        Assertions.assertTrue(responseStamp.isTextual(),
                () -> label + ": the response's last_emailed_at must be the stamp but was " + responseStamp);
        Assertions.assertEquals(invoiceStamp.toLocalDateTime().truncatedTo(ChronoUnit.SECONDS),
                LocalDateTime.parse(responseStamp.asText()), label + ": the response's last_emailed_at");
    }

    /**
     * Since the senders were last reset: exactly ONE invoice email - invoice v{@code versionNumber}'s signed
     * PDF (see {@link #assertSignedPdfEmail}) - and no failed attempt, no quote email, no store notification
     * and no SMS.
     */
    private void assertOnlyTheSignedPdfEmailed(long orderId, int versionNumber, String label) throws IOException {
        List<InvoiceEmailRequest> sent = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size(), label + ": exactly one invoice email");
        assertSignedPdfEmail(orderId, versionNumber, sent.get(0), label);
        Assertions.assertEquals(List.of(), invoiceEmailSender.failedEmails(), label + ": no failed invoice email attempt");
        Assertions.assertEquals(List.of(), quoteEmailSender.sentEmails(), label + ": no quote email sent");
        Assertions.assertEquals(List.of(), quoteEmailSender.failedEmails(), label + ": no quote email attempted");
        Assertions.assertEquals(List.of(), acceptanceNotificationSender.sentNotifications(),
                label + ": no store notification sent");
        Assertions.assertEquals(List.of(), acceptanceNotificationSender.failedNotifications(),
                label + ": no store notification attempted");
        Assertions.assertEquals(List.of(), smsSender.sentMessages(), label + ": no SMS sent");
        Assertions.assertEquals(List.of(), smsSender.failedMessages(), label + ": no SMS attempted");
    }

    /** No table (or view) in any schema looks like an invoice line table: invoices have no lines. */
    private void assertNoInvoiceLineTable() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_schema || '.' || table_name FROM information_schema.tables "
                        + "WHERE table_name ILIKE 'invoice%line%' ORDER BY 1", String.class);
        Assertions.assertEquals(List.of(), tables, "no invoice line table may exist (the invoice has no lines)");
    }

    private static long asLong(Object value) {
        Assertions.assertNotNull(value, "expected a non-null id");
        return ((Number) value).longValue();
    }

    private static LocalDateTime asLocalDateTime(Object value) {
        Assertions.assertNotNull(value, "expected a non-null timestamp");
        return ((Timestamp) value).toLocalDateTime();
    }

    private static void assertMoney(String expected, Object actual, String label) {
        Assertions.assertTrue(actual instanceof BigDecimal, () -> label + " must be a BigDecimal but was " + actual);
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual),
                () -> label + ": expected " + expected + " but was " + actual);
    }

    /** Exact 2dp money: the expected value AND scale 2 (copied verbatim, never re-rounded or re-scaled). */
    private static void assertMoneyAtScale2(String expected, Object actual, String label) {
        assertMoney(expected, actual, label);
        Assertions.assertEquals(2, ((BigDecimal) actual).scale(), () -> label + " must keep scale 2 but was " + actual);
    }

    private static void assertJsonMoney(String expected, JsonNode node, String label) {
        Assertions.assertNotNull(node, () -> label + " must be present");
        Assertions.assertTrue(node.isNumber(), () -> label + " must be a JSON number but was " + node);
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo(node.decimalValue()),
                () -> label + ": expected " + expected + " but was " + node);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ================================================================
    // Helpers - InvoiceDetail JSON
    // ================================================================

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            names.add(property.getKey());
        }
        return names;
    }

    /**
     * {@code data.invoice} of an InvoiceDetail response: EXACTLY the InvoiceDetail keys (so both terms
     * keys and the three frozen Invoice To keys are present, and no internal id such as
     * source_quote_version_id / stored_file_id / accepted_signature_file_id or the raw terms_snapshot
     * column is keyed), with the expected terms.
     */
    private static JsonNode assertInvoiceDetail(MvcResult result, int expectedVersion, String expectedSource,
                                                String expectedTermsHtml, String label) throws IOException {
        String body = body(result);
        JsonNode invoice = JSON.readTree(body).path("data").path("invoice");
        Assertions.assertTrue(invoice.isObject(), () -> label + ": data.invoice must be an object: " + body);
        Assertions.assertEquals(new TreeSet<>(INVOICE_DETAIL_KEYS), fieldNames(invoice),
                () -> label + ": InvoiceDetail key set: " + body);
        Assertions.assertEquals(expectedVersion, invoice.get("version_number").asInt(), label + ": version_number");
        Assertions.assertTrue(invoice.get("terms_source").isTextual(), () -> label + ": terms_source must be a string");
        Assertions.assertEquals(expectedSource, invoice.get("terms_source").asText(), label + ": terms_source");
        if (expectedTermsHtml == null) {
            Assertions.assertTrue(invoice.get("terms_html").isNull(),
                    () -> label + ": terms_html must be JSON null: " + invoice.get("terms_html"));
            Assertions.assertTrue(body.contains("\"terms_html\":null"),
                    () -> label + ": terms_html must be a PRESENT null key in the raw body: " + body);
        } else {
            Assertions.assertTrue(invoice.get("terms_html").isTextual(),
                    () -> label + ": terms_html must be a string: " + invoice.get("terms_html"));
            Assertions.assertEquals(expectedTermsHtml, invoice.get("terms_html").asText(), label + ": terms_html");
        }
        for (String internal : List.of("source_quote_version_id", "terms_snapshot", "stored_file_id",
                "accepted_signature_file_id", "storage_path", "/uploads/")) {
            Assertions.assertFalse(body.contains(internal),
                    () -> label + ": '" + internal + "' must never appear in an InvoiceDetail response: " + body);
        }
        return invoice;
    }

    /**
     * The three frozen Invoice To keys of an InvoiceDetail (16G PR 2, #125): each a PRESENT key holding
     * the version's stored word (an expected {@code null} must be a present JSON null, never absent).
     */
    private static void assertInvoiceToWords(JsonNode invoice, String expectedName, String expectedLine1,
                                             String expectedLine2, String label) {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("customer_name_snapshot", expectedName);
        expected.put("customer_address_line1_snapshot", expectedLine1);
        expected.put("customer_address_line2_snapshot", expectedLine2);
        for (Map.Entry<String, String> word : expected.entrySet()) {
            String key = word.getKey();
            Assertions.assertTrue(invoice.has(key), () -> label + ": '" + key + "' must be a present key: " + invoice);
            JsonNode value = invoice.get(key);
            if (word.getValue() == null) {
                Assertions.assertTrue(value.isNull(), () -> label + ": " + key + " must be JSON null but was " + value);
            } else {
                Assertions.assertTrue(value.isTextual(), () -> label + ": " + key + " must be a string but was " + value);
                Assertions.assertEquals(word.getValue(), value.asText(), label + ": " + key);
            }
        }
    }

    // ================================================================
    // Helpers - PDF content
    // ================================================================

    private static void assertPdfBytes(byte[] pdf) {
        Assertions.assertNotNull(pdf);
        Assertions.assertTrue(pdf.length > 500, () -> "PDF unexpectedly small: " + pdf.length + " bytes");
        Assertions.assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.US_ASCII),
                "file must begin with the PDF magic header");
    }

    private static String pdfText(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(document);
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

    /** One image XObject found in a page's resources: its declared size and its decoded pixels. */
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

    // PDFBox can inject / drop spaces between glyphs and wraps long text, so checks either collapse
    // whitespace runs or compare with all whitespace removed.
    private static String flat(String text) {
        return text.replaceAll("\\s+", " ");
    }

    private static String noSpace(String text) {
        return text.replaceAll("\\s", "");
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

    private static void assertPdfContains(String pdfText, String expected, String label) {
        Assertions.assertTrue(noSpace(pdfText).contains(noSpace(expected)),
                () -> label + ": the PDF must contain '" + expected + "' in: " + flat(pdfText));
    }

    private static void assertPdfLacks(String pdfText, String unexpected, String label) {
        Assertions.assertFalse(noSpace(pdfText).toUpperCase(Locale.ROOT).contains(noSpace(unexpected).toUpperCase(Locale.ROOT)),
                () -> label + ": the PDF must NOT contain '" + unexpected + "' in: " + flat(pdfText));
    }

    /**
     * The page text in VISUAL reading order. The invoice caption is "Accepted by" + a name span + "on" +
     * a timestamp span; in content-stream order PDFBox emits the static words before the spans
     * ("Accepted by on Quote Tester 08/10/2026 16:41"), so the caption is read position-sorted, which
     * is the order a reader sees on the page.
     */
    private static String pdfTextInReadingOrder(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    /**
     * The signed caption "Accepted by {name} on {dd/MM/yyyy HH:mm}" rendered from the quote's accepted_at,
     * read in visual order. Postgres keeps TIMESTAMP at microsecond precision (rounded), so the stored
     * value can sit up to 1 microsecond AFTER a rendered in-memory instant; accepting the rendering of
     * {@code acceptedAt - 1us} as well keeps the check exact yet immune to a minute boundary.
     */
    private static void assertAcceptedCaption(byte[] pdf, String acceptedName, LocalDateTime acceptedAt)
            throws IOException {
        String readingOrder = pdfTextInReadingOrder(pdf);
        String text = noSpace(readingOrder);
        String exact = noSpace("Accepted by " + acceptedName + " on " + DISPLAY_DATE_TIME.format(acceptedAt));
        String roundedBack = noSpace("Accepted by " + acceptedName + " on "
                + DISPLAY_DATE_TIME.format(acceptedAt.minusNanos(1_000)));
        Assertions.assertTrue(text.contains(exact) || text.contains(roundedBack),
                () -> "the invoice PDF must carry the caption '" + exact + "' in: " + flat(readingOrder));
    }

    /**
     * The PDF terms rule: the version's stored terms text present -> exactly 2 pages, the terms (and their
     * uppercase heading) on page 2 only; {@code expectedTermsText == null} -> exactly 1 page and no terms
     * heading. Either way the footer renders exactly once and none of {@code forbiddenTexts} appears.
     */
    private static void assertPdfTerms(byte[] pdf, String expectedTermsText, String label,
                                       String... forbiddenTexts) throws IOException {
        String text = pdfText(pdf);
        Assertions.assertEquals(1, countOccurrences(flat(text), FOOTER),
                () -> label + ": the footer must render exactly once: " + flat(text));
        for (String forbidden : forbiddenTexts) {
            assertPdfLacks(text, forbidden, label);
        }
        if (expectedTermsText == null) {
            Assertions.assertEquals(1, pageCount(pdf), () -> label + ": no terms -> a single page: " + flat(text));
            Assertions.assertFalse(text.contains(TERMS_HEADING),
                    () -> label + ": no terms -> no terms heading: " + flat(text));
            return;
        }
        Assertions.assertEquals(2, pageCount(pdf), () -> label + ": terms -> a dedicated second page: " + flat(text));
        String page1 = pageText(pdf, 1);
        String page2 = pageText(pdf, 2);
        Assertions.assertFalse(page1.contains(TERMS_HEADING),
                () -> label + ": page 1 never carries the terms heading: " + flat(page1));
        assertPdfLacks(page1, expectedTermsText, label + " (page 1)");
        Assertions.assertTrue(page2.contains(TERMS_HEADING),
                () -> label + ": page 2 carries the terms heading: " + flat(page2));
        assertPdfContains(page2, expectedTermsText, label + " (page 2)");
        Assertions.assertTrue(flat(page2).contains(FOOTER), () -> label + ": the footer sits on the terms page");
    }

    // ================================================================
    // Helpers - signature fixture
    // ================================================================

    /**
     * A distinctive 7 x 3 opaque RGB signature (every pixel a different colour), so the inherited
     * signature can be recognised in the invoice PDF by its dimensions AND its pixels.
     */
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

    private static BufferedImage decodePng(byte[] png, String label) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        Assertions.assertNotNull(image, () -> label + ": not a decodable PNG");
        return image;
    }

    // ================================================================
    // (3) Snapshot fidelity in BOTH quote modes
    // ================================================================

    /**
     * Itemised quote with distinctive line descriptions: after the signature the live details, working
     * price, product and charge lines, customer name, billing street and tenant terms all move on, and
     * Path A still bills the signed snapshot. Invoice To (16G PR 2, #125) is the accepted version's V17
     * name and billing lines - the renamed customer and the changed street reach neither the row, the
     * API nor the PDF; no quote line reaches the invoice row, the response or the PDF.
     */
    @Test
    void pathA_itemisedQuote_invoiceFreezesSignedSnapshot_afterLiveDetailsPriceLinesCustomerAndTermsMoveOn()
            throws Exception {
        assertPathAInvoiceFreezesTheSignedSnapshot(true);
    }

    /**
     * Non-itemised quote whose draft still carries DORMANT itemised rows (saved itemised first, then
     * non-itemised 330.00): the same fidelity, the dormant rows never reach the quote snapshot, the
     * invoice or its PDF.
     */
    @Test
    void pathA_nonItemisedQuoteOverDormantDraftRows_invoiceFreezesSignedSnapshot_afterLiveStateMovesOn()
            throws Exception {
        assertPathAInvoiceFreezesTheSignedSnapshot(false);
    }

    private void assertPathAInvoiceFreezesTheSignedSnapshot(boolean itemised) throws Exception {
        String mode = itemised ? "itemised" : "non-itemised";
        // Tenant terms BEFORE issue, so the quote freezes a populated terms_snapshot.
        setTenantTerms(ISSUE_TERMS_HTML, ISSUE_TERMS_HTML);
        long orderId = invoiceReadyOrder(SOFT);
        String orderNumber = orderNumber(orderId);
        saveDistinctiveItemisedDraft(orderId);                     // 250.00 ex / 275.00 inc
        String expectedEx = "250.00";
        String expectedInc = "275.00";
        if (!itemised) {
            saveNonItemisedDraft(orderId, "330.00");               // header-only: the itemised rows stay DORMANT
            Assertions.assertEquals(2, draftLineCount(orderId), "precondition: dormant itemised draft rows retained");
            expectedEx = "300.00";
            expectedInc = "330.00";
        }
        String token = sendAndExtractToken(orderId);
        Assertions.assertEquals(itemised ? 2 : 0, versionLineCount(orderId),
                mode + ": the issue snapshot holds the itemised lines only for an itemised quote");
        acceptPublicly(token, ONE_PIXEL_PNG);

        Map<String, Object> quote = versionRow(orderId, 1);
        Assertions.assertEquals("ACCEPTED", quote.get("status"));
        Assertions.assertEquals(itemised, quote.get("itemised"));
        Assertions.assertEquals(FROZEN_DETAILS, quote.get("details_of_sale_snapshot"),
                "precondition: the quote froze the details of sale at issue");
        assertMoney(expectedEx, quote.get("quote_total_ex_gst"), "quote_total_ex_gst");
        assertMoney(expectedInc, quote.get("quote_total_inc_gst"), "quote_total_inc_gst");
        String frozenTerms = (String) quote.get("terms_snapshot");
        Assertions.assertEquals(termsSanitizer.sanitize(ISSUE_TERMS_HTML), frozenTerms,
                "precondition: the tenant terms were sanitised once and frozen at issue");
        Assertions.assertTrue(frozenTerms.contains(ISSUE_TERMS_TEXT));
        Assertions.assertEquals(SNAPSHOT_NAME, quote.get("accepted_customer_name"));
        // The V17 issue snapshot (16G PR 2: Path A's Invoice To): the saved name and the ORIGINAL billing lines.
        Assertions.assertEquals(SNAPSHOT_NAME, quote.get("customer_name_snapshot"),
                "precondition: the quote froze the saved customer name at issue");
        Assertions.assertEquals(ORIGINAL_BILLING_LINE1, quote.get("customer_address_line1_snapshot"),
                "precondition: the quote froze the original billing line 1 at issue");
        Assertions.assertEquals(BILLING_LINE2, quote.get("customer_address_line2_snapshot"),
                "precondition: the quote froze billing line 2 at issue");
        LocalDateTime quoteAcceptedAt = asLocalDateTime(quote.get("accepted_at"));
        long quoteVersionId = asLong(quote.get("quote_version_id"));
        int quoteLinesAtAcceptance = versionLineCount(orderId);

        // Everything LIVE moves on AFTER the signature.
        setLiveDetailsOfSale(orderId, LIVE_DETAILS);
        jdbcTemplate.update("UPDATE sales_order SET price_adjustment_inc_gst = ? WHERE order_id = ?",
                new BigDecimal("777.77"), orderId);
        seedProductLine(orderId, SOFT, "60.00", "30.00");
        seedChargeLine(orderId, SOFT, "500.00", "200.00");
        // The rename and the street change must really land: nothing below may see them.
        Assertions.assertEquals(1, jdbcTemplate.update(
                        "UPDATE order_customer SET first_name = ?, last_name = ? WHERE order_id = ?",
                        LIVE_FIRST_NAME, LIVE_LAST_NAME, orderId),
                "precondition: the live customer is renamed");
        Assertions.assertEquals(1, jdbcTemplate.update(
                        "UPDATE order_address SET street = ? WHERE order_id = ? AND address_type = 'BILLING'::address_type",
                        CHANGED_BILLING_STREET, orderId),
                "precondition: the live billing street changes");
        setTenantTerms(AFTER_ACCEPTANCE_TERMS_HTML, AFTER_ACCEPTANCE_TERMS_HTML);
        clearJpaCache();
        // The live state the Invoice To must never show (16G PR 2): the renamed customer and the new street.
        List<String> liveIdentity = List.of(LIVE_FIRST_NAME, LIVE_LAST_NAME, CHANGED_BILLING_STREET);

        List<Map<String, Object>> productLinesBefore = productLines(orderId);
        List<Map<String, Object>> chargeLinesBefore = chargeLines(orderId);
        Assertions.assertEquals(1, productLinesBefore.size(), "precondition: one live product line");
        Assertions.assertEquals(2, chargeLinesBefore.size(), "precondition: two live charge lines");

        LocalDate dayBefore = LocalDate.now();
        MvcResult result = createInvoiceFromQuote(orderId);
        LocalDate dayAfter = LocalDate.now();

        // ---- Response: the signed snapshot, QUOTE terms ----
        String responseBody = body(result);
        JsonNode invoice = assertInvoiceDetail(result, 1, "QUOTE", frozenTerms, mode + " Path A response");
        Assertions.assertEquals(FROZEN_DETAILS, invoice.get("details_of_sale_snapshot").asText());
        assertJsonMoney(expectedEx, invoice.get("sale_price_ex_gst"), "response sale_price_ex_gst");
        assertJsonMoney(expectedInc, invoice.get("sale_price_inc_gst"), "response sale_price_inc_gst");
        assertJsonMoney("0.00", invoice.get("total_paid"), "response total_paid");
        assertJsonMoney(expectedInc, invoice.get("balance_due"), "response balance_due");
        Assertions.assertEquals(SNAPSHOT_NAME, invoice.get("accepted_customer_name").asText());
        Assertions.assertEquals(quoteAcceptedAt.truncatedTo(ChronoUnit.SECONDS),
                LocalDateTime.parse(invoice.get("accepted_at").asText()),
                "response accepted_at is the quote's signature time (formatted to seconds)");
        Assertions.assertTrue(invoice.get("accepted_signature_present").booleanValue());
        Assertions.assertEquals(EXPECTED_DUE_DATE.toString(), invoice.get("due_date").asText());
        // 16G PR 3: the signed PDF was emailed after the conversion and the version stamped (createInvoiceFromQuote
        // proves the email, its attachment and that the mirror carries the same stamp).
        Assertions.assertTrue(invoice.get("last_emailed_at").isTextual(),
                () -> mode + ": the response carries the success stamp: " + invoice.get("last_emailed_at"));
        // Invoice To (16G PR 2): the accepted version's V17 words, never the live customer or address.
        assertInvoiceToWords(invoice, SNAPSHOT_NAME, ORIGINAL_BILLING_LINE1, BILLING_LINE2, mode + " Path A response");
        List<String> neverInTheResponse = new ArrayList<>(
                List.of(LINE_ALPHA, LINE_BETA, LIVE_DETAILS, AFTER_ACCEPTANCE_TERMS_TEXT, "777.77"));
        neverInTheResponse.addAll(liveIdentity);
        for (String leaked : neverInTheResponse) {
            Assertions.assertFalse(responseBody.contains(leaked),
                    () -> mode + ": '" + leaked + "' must not reach the Path A response: " + responseBody);
        }
        // D.3 maps the same stored words (no live re-read).
        MvcResult currentResult = getCurrentInvoice(orderId);
        JsonNode current = assertInvoiceDetail(currentResult, 1, "QUOTE", frozenTerms, mode + " D.3 after Path A");
        assertInvoiceToWords(current, SNAPSHOT_NAME, ORIGINAL_BILLING_LINE1, BILLING_LINE2, mode + " D.3 after Path A");
        String currentBody = body(currentResult);
        for (String leaked : liveIdentity) {
            Assertions.assertFalse(currentBody.contains(leaked),
                    () -> mode + ": '" + leaked + "' must not reach D.3 after Path A: " + currentBody);
        }

        // ---- The persisted invoice row ----
        Assertions.assertEquals(1, invoiceCount(orderId), "Path A appended exactly one invoice version");
        Map<String, Object> row = invoiceRow(orderId, 1);
        Assertions.assertEquals(quote.get("details_of_sale_snapshot"), row.get("details_of_sale_snapshot"),
                "details_of_sale_snapshot = the quote's frozen details verbatim");
        Assertions.assertEquals(FROZEN_DETAILS, row.get("details_of_sale_snapshot"),
                "the ORIGINAL details text, never the live rewrite");
        // BigDecimal.equals: same value AND scale - copied verbatim, never recomputed.
        Assertions.assertEquals(quote.get("quote_total_ex_gst"), row.get("sale_price_ex_gst"),
                "sale_price_ex_gst = quote_total_ex_gst exactly");
        Assertions.assertEquals(quote.get("quote_total_inc_gst"), row.get("sale_price_inc_gst"),
                "sale_price_inc_gst = quote_total_inc_gst exactly");
        assertMoney(expectedEx, row.get("sale_price_ex_gst"), "invoice sale_price_ex_gst");
        assertMoney(expectedInc, row.get("sale_price_inc_gst"), "invoice sale_price_inc_gst");
        assertMoney("0.00", row.get("total_paid"), "invoice total_paid");
        assertMoney(expectedInc, row.get("balance_due"), "invoice balance_due");
        Assertions.assertEquals(SNAPSHOT_NAME, row.get("accepted_customer_name"),
                "the frozen accepted name, not the renamed live customer");
        Assertions.assertEquals(quote.get("accepted_customer_name"), row.get("accepted_customer_name"));
        Assertions.assertEquals(quote.get("accepted_at"), row.get("accepted_at"),
                "accepted_at = the quote's accepted_at (DB value), not the conversion time");
        Assertions.assertEquals(quote.get("terms_snapshot"), row.get("terms_snapshot"),
                "terms_snapshot = the quote's frozen terms verbatim");
        // Invoice To (16G PR 2): the three identity columns = the quote version's V17 columns verbatim.
        for (String column : List.of("customer_name_snapshot", "customer_address_line1_snapshot",
                "customer_address_line2_snapshot")) {
            Assertions.assertEquals(quote.get(column), row.get(column),
                    () -> "invoice." + column + " = quote_version." + column + " verbatim");
        }
        Assertions.assertEquals(SNAPSHOT_NAME, row.get("customer_name_snapshot"),
                "the frozen name, not the renamed live customer");
        Assertions.assertEquals(ORIGINAL_BILLING_LINE1, row.get("customer_address_line1_snapshot"),
                "the original billing street, not the changed live one");
        Assertions.assertEquals(BILLING_LINE2, row.get("customer_address_line2_snapshot"));
        for (Map.Entry<String, Object> column : row.entrySet()) {
            if (column.getValue() instanceof String value) {
                for (String leaked : liveIdentity) {
                    Assertions.assertFalse(value.contains(leaked),
                            () -> mode + ": '" + leaked + "' must not reach invoice." + column.getKey() + ": " + value);
                }
            }
        }
        Assertions.assertEquals(quoteVersionId, asLong(row.get("source_quote_version_id")));
        Assertions.assertEquals(asLong(quote.get("accepted_signature_file_id")),
                asLong(row.get("accepted_signature_file_id")));
        Assertions.assertEquals(EXPECTED_DUE_DATE, ((Date) row.get("due_date")).toLocalDate(),
                "due_date = proposed_lay_date - 2 days");
        LocalDate invoiceDate = ((Date) row.get("invoice_date")).toLocalDate();
        Assertions.assertTrue(!invoiceDate.isBefore(dayBefore) && !invoiceDate.isAfter(dayAfter),
                () -> "invoice_date must be today but was " + invoiceDate);
        Assertions.assertEquals(USER_LIAM, asLong(row.get("created_by_user_id")));
        Assertions.assertNotNull(row.get("last_emailed_at"), "16G PR 3: the emailed version is stamped");
        Assertions.assertEquals(row.get("last_emailed_at"), salesOrderLastEmailedAt(orderId),
                "the dashboard mirror carries the same stamp");

        // ---- The stored PDF (disk) == the streamed PDF (D.4) == the emailed PDF ----
        byte[] pdf = invoicePdfFromDisk(orderId, 1);
        Assertions.assertArrayEquals(pdf, downloadCurrentInvoicePdf(orderId),
                "D.4 streams the stored Path A PDF verbatim");
        List<InvoiceEmailRequest> emailed = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, emailed.size(), mode + ": Path A sent exactly one invoice email");
        Assertions.assertArrayEquals(pdf, emailed.get(0).pdfBytes(),
                "the one email attaches the stored Path A PDF, so it carries the same signed snapshot");
        String text = pdfText(pdf);
        String label = mode + " Path A PDF";
        assertPdfContains(text, FROZEN_DETAILS, label);
        assertPdfLacks(text, LIVE_DETAILS, label);
        // Totals: signed inc total twice (Total Inc. GST + Balance Due) and nothing paid.
        assertPdfContains(text, "Total Inc. GST $" + expectedInc, label);
        assertPdfContains(text, "Payment Made (-) $0.00", label);
        assertPdfContains(text, "Balance Due $" + expectedInc, label);
        assertPdfLacks(text, "$777.77", label);
        assertAcceptedCaption(pdf, SNAPSHOT_NAME, quoteAcceptedAt);
        // Invoice To (16G PR 2) is the accepted version's V17 snapshot, so the frozen name renders exactly
        // twice - Invoice To and the acceptance caption - and Invoice To shows the ORIGINAL billing lines.
        Assertions.assertEquals(2, countOccurrences(noSpace(text), noSpace(SNAPSHOT_NAME)),
                () -> label + ": the frozen name appears in Invoice To and the acceptance caption only: " + flat(text));
        assertPdfContains(text, SNAPSHOT_NAME + " " + ORIGINAL_BILLING_LINE1 + " " + BILLING_LINE2,
                label + " (Invoice To)");
        // The renamed customer and the changed billing street appear NOWHERE in the PDF.
        for (String leaked : liveIdentity) {
            assertPdfLacks(text, leaked, label);
        }
        // No quote line description anywhere in the invoice PDF (itemised lines are never copied).
        assertPdfLacks(text, LINE_ALPHA, label);
        assertPdfLacks(text, LINE_BETA, label);
        assertPdfLacks(text, "QLINE", label);
        // The frozen terms on page 2; the live (post-acceptance) terms nowhere.
        assertPdfTerms(pdf, ISSUE_TERMS_TEXT, label, AFTER_ACCEPTANCE_TERMS_TEXT);

        // ---- No invoice lines anywhere; Products and Charges untouched; quote lines untouched ----
        assertNoInvoiceLineTable();
        Assertions.assertEquals(productLinesBefore, productLines(orderId), "Path A never writes product lines");
        Assertions.assertEquals(chargeLinesBefore, chargeLines(orderId), "Path A never writes charge lines");
        Assertions.assertEquals(quoteLinesAtAcceptance, versionLineCount(orderId), "quote lines are never copied or moved");
        Assertions.assertEquals(orderNumber, orderNumber(orderId));
    }

    private void setLiveDetailsOfSale(long orderId, String details) {
        jdbcTemplate.update("UPDATE sales_order SET details_of_sale = ? WHERE order_id = ?", details, orderId);
        clearJpaCache();
    }

    /**
     * Totals that do NOT round-trip: a REAL non-itemised quote of 1000.06 inc freezes 909.15 ex
     * (1000.06 / 1.10 HALF_UP), while 909.15 x 1.10 = 1000.065 would re-gross to 1000.07. Path A copies
     * BOTH frozen totals verbatim, so the invoice row (value and scale), the JSON (Path A and D.3) and
     * the PDF show 909.15 / 1000.06 and never 1000.07.
     */
    @Test
    void pathA_nonItemisedTotalsThatDoNotRoundTrip_copiesExAndIncVerbatim_neverRegrossedTo1000_07()
            throws Exception {
        // Pure arithmetic precondition: 1000.06 / 1.10 gives 909.15, but 909.15 x 1.10 re-grosses to
        // 1000.07, not back to 1000.06.
        BigDecimal gstMultiplier = new BigDecimal("1.10");
        Assertions.assertEquals(new BigDecimal("909.15"),
                new BigDecimal("1000.06").divide(gstMultiplier, 2, RoundingMode.HALF_UP),
                "precondition: 1000.06 / 1.10 HALF_UP = 909.15");
        Assertions.assertEquals(new BigDecimal("1000.07"),
                new BigDecimal("909.15").multiply(gstMultiplier).setScale(2, RoundingMode.HALF_UP),
                "precondition: 909.15 x 1.10 HALF_UP = 1000.07 (a recomputed inc total would drift)");

        clearTenantTerms();
        long orderId = invoiceReadyOrder(SOFT);
        // The live line cost stays far below the frozen ex total: no below-cost refusal at save, send or accept.
        BigDecimal liveCost = jdbcTemplate.queryForObject(
                "SELECT (SELECT COALESCE(SUM(line_cost), 0) FROM order_charge_line WHERE order_id = ?) "
                        + "+ (SELECT COALESCE(SUM(line_cost), 0) FROM order_product_line WHERE order_id = ?)",
                BigDecimal.class, orderId, orderId);
        Assertions.assertNotNull(liveCost);
        Assertions.assertTrue(liveCost.compareTo(new BigDecimal("909.15")) < 0,
                () -> "precondition: the live cost " + liveCost + " keeps the quote above cost");

        saveNonItemisedDraft(orderId, "1000.06");
        acceptPublicly(sendAndExtractToken(orderId), ONE_PIXEL_PNG);

        // The fixture is what it claims to be: the REAL quote froze 909.15 ex / 1000.06 inc.
        Map<String, Object> quote = versionRow(orderId, 1);
        Assertions.assertEquals("ACCEPTED", quote.get("status"));
        Assertions.assertEquals(false, quote.get("itemised"));
        Assertions.assertEquals(0, versionLineCount(orderId), "a non-itemised quote snapshots no lines");
        assertMoneyAtScale2("909.15", quote.get("quote_total_ex_gst"), "quote_version.quote_total_ex_gst");
        assertMoneyAtScale2("1000.06", quote.get("quote_total_inc_gst"), "quote_version.quote_total_inc_gst");

        MvcResult result = createInvoiceFromQuote(orderId);

        // ---- The persisted invoice row: both totals verbatim, value AND scale ----
        Map<String, Object> row = invoiceRow(orderId, 1);
        assertMoneyAtScale2("909.15", row.get("sale_price_ex_gst"), "invoice sale_price_ex_gst");
        assertMoneyAtScale2("1000.06", row.get("sale_price_inc_gst"), "invoice sale_price_inc_gst");
        Assertions.assertEquals(quote.get("quote_total_ex_gst"), row.get("sale_price_ex_gst"));
        Assertions.assertEquals(quote.get("quote_total_inc_gst"), row.get("sale_price_inc_gst"));
        assertMoneyAtScale2("0.00", row.get("total_paid"), "invoice total_paid");
        assertMoneyAtScale2("1000.06", row.get("balance_due"), "invoice balance_due");

        // ---- JSON (Path A and D.3): the same exact decimals; 1000.07 nowhere ----
        Map<String, MvcResult> responses = new LinkedHashMap<>();
        responses.put("Path A", result);
        responses.put("D.3", getCurrentInvoice(orderId));
        for (Map.Entry<String, MvcResult> response : responses.entrySet()) {
            String label = response.getKey();
            String responseBody = body(response.getValue());
            JsonNode invoice = assertInvoiceDetail(response.getValue(), 1, "QUOTE", null, label);
            assertJsonMoney("909.15", invoice.get("sale_price_ex_gst"), label + " sale_price_ex_gst");
            assertJsonMoney("1000.06", invoice.get("sale_price_inc_gst"), label + " sale_price_inc_gst");
            assertJsonMoney("0.00", invoice.get("total_paid"), label + " total_paid");
            assertJsonMoney("1000.06", invoice.get("balance_due"), label + " balance_due");
            // BigDecimal.equals: the JSON decimals carry exactly two places, too.
            Assertions.assertEquals(new BigDecimal("909.15"), invoice.get("sale_price_ex_gst").decimalValue(), label);
            Assertions.assertEquals(new BigDecimal("1000.06"), invoice.get("sale_price_inc_gst").decimalValue(), label);
            Assertions.assertTrue(responseBody.contains("\"sale_price_ex_gst\":909.15"),
                    () -> label + ": raw ex total: " + responseBody);
            Assertions.assertTrue(responseBody.contains("\"sale_price_inc_gst\":1000.06"),
                    () -> label + ": raw inc total: " + responseBody);
            Assertions.assertFalse(responseBody.contains("1000.07"),
                    () -> label + ": a re-grossed inc total must never appear: " + responseBody);
        }

        // ---- The PDF: the frozen inc total in the template's money format, never $1,000.07 ----
        byte[] pdf = invoicePdfFromDisk(orderId, 1);
        Assertions.assertArrayEquals(pdf, downloadCurrentInvoicePdf(orderId));
        String text = pdfText(pdf);
        String label = "non-round-trip Path A PDF";
        assertPdfContains(text, "Total Inc. GST $1,000.06", label);
        assertPdfContains(text, "Payment Made (-) $0.00", label);
        assertPdfContains(text, "Balance Due $1,000.06", label);
        Assertions.assertEquals(2, countOccurrences(noSpace(text), "$1,000.06"),
                () -> label + ": the inc total renders exactly twice (total + balance): " + flat(text));
        assertPdfLacks(text, "1,000.07", label);
        assertPdfLacks(text, "1000.07", label);
    }

    // ================================================================
    // (7) Acceptance, files, side effects
    // ================================================================

    /**
     * The invoice references the quote's SAME signature stored_file (no new signature row or file);
     * exactly one stored_file row and one file on disk are added (the invoice PDF); that PDF embeds
     * exactly one image whose size and pixels are the inherited signature's. Since 16G PR 3 (contract 6)
     * that same stored PDF is the ONLY thing Path A sends - one invoice email to the saved customer email,
     * no quote email, store notification or SMS - and the success stamp sets the new version's
     * last_emailed_at and the dashboard mirror to the same value, so the mirror sentinel does not survive.
     * The email writes no file and no stored_file row.
     */
    @Test
    void pathA_inheritsQuoteSignatureStoredFile_writesOnlyTheInvoicePdf_embedsThatSignature_emailsOnlyThatPdf_stampsInvoiceAndMirror()
            throws Exception {
        clearBusinessLogo();
        clearTenantTerms();
        byte[] uploadedSignature = distinctiveSignaturePng();
        BufferedImage uploadedImage = decodePng(uploadedSignature, "upload");
        Assertions.assertEquals(7, uploadedImage.getWidth());
        Assertions.assertEquals(3, uploadedImage.getHeight());

        long orderId = acceptedItemisedOrder(SOFT, uploadedSignature);
        String orderNumber = orderNumber(orderId);
        Map<String, Object> quote = versionRow(orderId, 1);
        long quoteSignatureFileId = asLong(quote.get("accepted_signature_file_id"));
        Map<String, Object> quoteSignatureFile = storedFileRow(quoteSignatureFileId);
        Assertions.assertEquals("image/png", quoteSignatureFile.get("mime_type"));
        Assertions.assertEquals("quote-signature-" + orderNumber + "-v1.png", quoteSignatureFile.get("file_name"));
        String signaturePath = (String) quoteSignatureFile.get("storage_path");
        byte[] storedSignature = diskBytes(signaturePath);
        BufferedImage storedSignatureImage = decodePng(storedSignature, "stored quote signature");
        Assertions.assertEquals(7, storedSignatureImage.getWidth(), "the stored (normalised) signature keeps 7 px");
        Assertions.assertEquals(3, storedSignatureImage.getHeight(), "the stored (normalised) signature keeps 3 px");

        // A non-null dashboard mirror sentinel: only Path A's section 11.1 mirror writes (the reset, then the
        // 16G PR 3 success stamp) can replace it.
        Timestamp mirrorSentinel = Timestamp.valueOf(LocalDateTime.of(2026, 3, 4, 5, 6, 7));
        jdbcTemplate.update("UPDATE sales_order SET last_emailed_at = ? WHERE order_id = ?", mirrorSentinel, orderId);
        clearJpaCache();
        Assertions.assertEquals(mirrorSentinel, salesOrderLastEmailedAt(orderId), "precondition: the mirror sentinel is set");
        resetSenders();

        Map<Long, Map<String, Object>> filesBefore = storedFilesForOrder(orderId);
        Assertions.assertEquals(3, filesBefore.size(),
                "precondition: the issued quote PDF, the quote signature and the signed quote PDF");
        Set<String> diskBefore = diskFilesForOrder(orderId);
        for (Map<String, Object> quoteFile : filesBefore.values()) {
            String quoteFileName = fileNameOf((String) quoteFile.get("storage_path"));
            Assertions.assertTrue(diskBefore.contains(quoteFileName),
                    () -> "precondition: quote artifact " + quoteFile.get("file_name") + " is on disk");
        }
        long maxStoredFileIdBefore = maxStoredFileId();

        MvcResult result = createInvoiceFromQuote(orderId);

        // ---- Response ----
        String responseBody = body(result);
        JsonNode invoice = assertInvoiceDetail(result, 1, "QUOTE", null, "Path A response");
        Assertions.assertTrue(invoice.get("last_emailed_at").isTextual(),
                () -> "16G PR 3: last_emailed_at must carry the success stamp: " + responseBody);
        Assertions.assertTrue(invoice.get("accepted_signature_present").booleanValue());
        Assertions.assertEquals(orderUrl(orderId) + "/invoices/current/signature",
                invoice.get("accepted_signature_download_path").asText());

        // ---- The invoice row inherits the quote's signature stored_file ----
        Map<String, Object> row = invoiceRow(orderId, 1);
        Assertions.assertEquals(quoteSignatureFileId, asLong(row.get("accepted_signature_file_id")),
                "invoice.accepted_signature_file_id = quote_version.accepted_signature_file_id");
        // ---- 16G PR 3: the new version is stamped by its delivered email; the mirror follows it ----
        Timestamp invoiceStamp = (Timestamp) row.get("last_emailed_at");
        Assertions.assertNotNull(invoiceStamp, "the new version is stamped by its post-commit email");
        Assertions.assertEquals(invoiceStamp, salesOrderLastEmailedAt(orderId),
                "the dashboard mirror carries the new version's stamp");
        Assertions.assertNotEquals(mirrorSentinel, salesOrderLastEmailedAt(orderId),
                "the mirror sentinel never survives Path A");

        // ---- Exactly one new stored_file row: the invoice PDF ----
        Map<Long, Map<String, Object>> filesAfter = storedFilesForOrder(orderId);
        Assertions.assertEquals(filesBefore.size() + 1, filesAfter.size(), "exactly one stored_file row is added");
        for (Map.Entry<Long, Map<String, Object>> existing : filesBefore.entrySet()) {
            Assertions.assertEquals(existing.getValue(), filesAfter.get(existing.getKey()),
                    "a pre-existing stored_file row is never modified");
        }
        Set<Long> newIds = new TreeSet<>(filesAfter.keySet());
        newIds.removeAll(filesBefore.keySet());
        Assertions.assertEquals(1, newIds.size());
        long invoicePdfFileId = newIds.iterator().next();
        Assertions.assertEquals(invoicePdfFileId, asLong(row.get("stored_file_id")),
                "the one new stored_file row is the invoice's PDF");
        Map<String, Object> invoicePdfFile = filesAfter.get(invoicePdfFileId);
        Assertions.assertEquals("application/pdf", invoicePdfFile.get("mime_type"));
        Assertions.assertEquals("invoice-" + orderNumber + "-v1.pdf", invoicePdfFile.get("file_name"));
        Integer newPngRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stored_file WHERE stored_file_id > ? AND mime_type = 'image/png'",
                Integer.class, maxStoredFileIdBefore);
        Assertions.assertEquals(0, newPngRows, "no new image/png stored_file row (no signature copy)");
        Integer newRowsAnywhere = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stored_file WHERE stored_file_id > ?", Integer.class, maxStoredFileIdBefore);
        Assertions.assertEquals(1, newRowsAnywhere, "the invoice PDF is the only stored_file row written");

        // ---- Exactly one new file on disk ----
        String invoicePdfPath = (String) invoicePdfFile.get("storage_path");
        Set<String> expectedDisk = new TreeSet<>(diskBefore);
        expectedDisk.add(fileNameOf(invoicePdfPath));
        Assertions.assertEquals(expectedDisk, diskFilesForOrder(orderId), "exactly one new file on disk");
        byte[] pdf = diskBytes(invoicePdfPath);
        assertPdfBytes(pdf);
        Assertions.assertEquals(asLong(invoicePdfFile.get("file_size")), pdf.length);
        Assertions.assertArrayEquals(storedSignature, diskBytes(signaturePath),
                "the inherited signature file is untouched");

        // ---- The invoice's own signature endpoint (D.10) streams that same inherited file ----
        MvcResult signatureDownload = perform(get(invoicesUrl(orderId) + "/current/signature")
                .session(liamStore1Session()), 200, "D.10 signature download");
        Assertions.assertEquals("image/png", signatureDownload.getResponse().getContentType());
        Assertions.assertArrayEquals(storedSignature, signatureDownload.getResponse().getContentAsByteArray(),
                "D.10 streams the quote's stored (normalised) signature file verbatim");
        Assertions.assertEquals("signature-" + orderNumber + "-v1.png", inlineFileName(signatureDownload),
                "the D.10 name is built from the CURRENT invoice version, not the quote file name");

        // ---- The new PDF embeds exactly that signature ----
        List<EmbeddedImage> images = embeddedImages(pdf);
        Assertions.assertEquals(1, images.size(), "no logo -> the signature is the ONLY image in the PDF");
        EmbeddedImage embedded = images.get(0);
        Assertions.assertEquals(storedSignatureImage.getWidth(), embedded.width(), "embedded image width");
        Assertions.assertEquals(storedSignatureImage.getHeight(), embedded.height(), "embedded image height");
        for (int y = 0; y < storedSignatureImage.getHeight(); y++) {
            for (int x = 0; x < storedSignatureImage.getWidth(); x++) {
                int want = storedSignatureImage.getRGB(x, y) & 0xFFFFFF;
                int got = embedded.pixels().getRGB(x, y) & 0xFFFFFF;
                int px = x;
                int py = y;
                Assertions.assertEquals(want, got, () -> String.format(Locale.ROOT,
                        "embedded signature pixel (%d,%d): expected %06x but was %06x", px, py, want, got));
            }
        }
        String text = pdfText(pdf);
        assertAcceptedCaption(pdf, SNAPSHOT_NAME, asLocalDateTime(quote.get("accepted_at")));
        assertPdfLacks(text, "Customer signature", "a signed invoice shows no blank signature line");

        // ---- 16G PR 3: that stored PDF is the one email; nothing else is sent ----
        assertOnlyTheSignedPdfEmailed(orderId, 1, "Path A");
        Assertions.assertArrayEquals(pdf, invoiceEmailSender.sentEmails().get(0).pdfBytes(),
                "the email attaches exactly the PDF that embeds the inherited signature");
    }

    /**
     * Path A never writes anything but the new invoice version and the mirror: v1 is ACCEPTED, then a
     * changed draft is sent as v2 (ISSUED + ACTIVE token). Every quote version / line / token row, the
     * draft and its lines, the whole order row (status, working price, header financials, updated_at),
     * the product and charge lines, the payments and the customer / address rows are identical after
     * Path A, and the v2 public link still answers ACTIVE. Since 16G PR 3 the only other effects are the
     * new version's signed-PDF email and its success stamp (on that version and the mirror only).
     */
    @Test
    void pathA_leavesNewerIssuedQuoteAndActiveToken_draft_orderHeader_lines_payments_customerAndAddressesUntouched()
            throws Exception {
        clearTenantTerms();
        long orderId = invoiceReadyOrder(SOFT);
        seedProductLine(orderId, SOFT, "60.00", "30.00");
        clearJpaCache();
        saveDistinctiveItemisedDraft(orderId);                    // v1: 250.00 ex / 275.00 inc
        acceptPublicly(sendAndExtractToken(orderId), ONE_PIXEL_PNG);
        long acceptedVersionId = asLong(versionRow(orderId, 1).get("quote_version_id"));

        // An unsigned Path B invoice and an active payment (Path A carries active payments into total_paid).
        createInvoice(orderId);                                    // invoice v1
        recordCashPayment(orderId, "50.00");                       // invoice v2

        // A NEWER quote: the draft changes and is sent as v2 -> ISSUED with an ACTIVE token.
        saveNonItemisedDraft(orderId, "330.00");
        String newerToken = sendAndExtractToken(orderId);
        Assertions.assertEquals("ACCEPTED", versionRow(orderId, 1).get("status"));
        Assertions.assertEquals("ISSUED", versionRow(orderId, 2).get("status"));
        Assertions.assertEquals("ACTIVE", tokenRow(newerToken).get("status"));
        Assertions.assertTrue(draftLineCount(orderId) > 0, "precondition: the draft keeps dormant itemised rows");
        Assertions.assertEquals(1, productLines(orderId).size(), "precondition: one product line");
        Assertions.assertEquals(1, chargeLines(orderId).size(), "precondition: one charge line");
        assertPublicQuoteState(newerToken, "ACTIVE");

        Map<String, Object> before = untouchedState(orderId);
        resetSenders();

        MvcResult result = createInvoiceFromQuote(orderId);

        // The LATEST ACCEPTED version (v1) is billed, never the newer ISSUED v2.
        JsonNode invoice = assertInvoiceDetail(result, 3, "QUOTE", null, "Path A over a newer issued quote");
        assertJsonMoney("250.00", invoice.get("sale_price_ex_gst"), "sale_price_ex_gst");
        assertJsonMoney("275.00", invoice.get("sale_price_inc_gst"), "sale_price_inc_gst");
        assertJsonMoney("50.00", invoice.get("total_paid"), "total_paid = active payments");
        assertJsonMoney("225.00", invoice.get("balance_due"), "balance_due = quote inc - active payments");
        Assertions.assertEquals(acceptedVersionId, asLong(invoiceRow(orderId, 3).get("source_quote_version_id")));

        Map<String, Object> after = untouchedState(orderId);
        for (String table : before.keySet()) {
            Assertions.assertEquals(before.get(table), after.get(table), table + " must be untouched by Path A");
        }
        Assertions.assertEquals(before, after);
        // 16G PR 3: the mirror follows the appended v3's success stamp; the earlier versions stay unemailed.
        Object v3Stamp = invoiceRow(orderId, 3).get("last_emailed_at");
        Assertions.assertNotNull(v3Stamp, "the appended v3 is stamped by its post-commit email");
        Assertions.assertEquals(v3Stamp, salesOrderLastEmailedAt(orderId), "the dashboard mirror carries v3's stamp");
        for (int earlier = 1; earlier <= 2; earlier++) {
            Assertions.assertNull(invoiceRow(orderId, earlier).get("last_emailed_at"),
                    "the stamp is written on the appended version only, never on v" + earlier);
        }
        Assertions.assertEquals("ISSUED", versionRow(orderId, 2).get("status"));
        Assertions.assertEquals("ACTIVE", tokenRow(newerToken).get("status"));
        // The one email is v3's signed PDF; no quote email (the newer ISSUED quote is never re-sent), no store
        // notification, no SMS.
        assertOnlyTheSignedPdfEmailed(orderId, 3, "Path A");

        // The newer link still works publicly.
        assertPublicQuoteState(newerToken, "ACTIVE");
    }

    /**
     * The invoice inherits the quote's signature TIME, never the conversion time: after the real public
     * accept, the accepted version's accepted_at is moved by SQL to a fixed PAST instant with
     * microseconds. Path A (no prior invoice) copies it to the microsecond, the JSON shows it to the
     * second, the PDF caption shows it to the minute, and invoice_date is still today, so the conversion
     * date and the inherited signature date visibly differ.
     */
    @Test
    void pathA_noInvoice_inheritsTheQuoteAcceptedAtToTheMicrosecond_notTheConversionTime_invoiceDateStaysToday()
            throws Exception {
        clearTenantTerms();
        long orderId = acceptedItemisedOrder(SOFT, ONE_PIXEL_PNG);
        LocalDateTime realAcceptedAt = asLocalDateTime(versionRow(orderId, 1).get("accepted_at"));
        Assertions.assertNotEquals(INHERITED_ACCEPTED_AT.toLocalDate(), realAcceptedAt.toLocalDate(),
                "precondition: the real signature is from today, not from the fixed past day");

        // AFTER the real accept and BEFORE Path A: a distinctive past signature instant.
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE quote_version SET accepted_at = TIMESTAMP '2026-02-03 04:05:06.123456' "
                        + "WHERE order_id = ? AND version_number = 1 AND status = 'ACCEPTED'", orderId));
        clearJpaCache();
        Map<String, Object> quote = versionRow(orderId, 1);
        Assertions.assertEquals(INHERITED_ACCEPTED_AT, asLocalDateTime(quote.get("accepted_at")),
                "precondition: the accepted version now carries the fixed past instant (microseconds kept)");
        Assertions.assertEquals(0, invoiceCount(orderId), "precondition: no invoice yet (no-invoice branch)");

        LocalDate dayBefore = LocalDate.now();
        MvcResult result = createInvoiceFromQuote(orderId);
        LocalDate dayAfter = LocalDate.now();

        // ---- The invoice row: the inherited instant to the microsecond; invoice_date = today ----
        Map<String, Object> row = invoiceRow(orderId, 1);
        Assertions.assertEquals(INHERITED_ACCEPTED_AT, asLocalDateTime(row.get("accepted_at")),
                "invoice.accepted_at = the quote's accepted_at exactly, microseconds included");
        Assertions.assertEquals(123_456_000, ((Timestamp) row.get("accepted_at")).getNanos(),
                "the microseconds survive the copy (no truncation to seconds or millis)");
        Assertions.assertEquals(quote.get("accepted_at"), row.get("accepted_at"));
        Assertions.assertNotEquals(realAcceptedAt, asLocalDateTime(row.get("accepted_at")),
                "never the real (now overwritten) acceptance time");
        LocalDate invoiceDate = ((Date) row.get("invoice_date")).toLocalDate();
        Assertions.assertTrue(!invoiceDate.isBefore(dayBefore) && !invoiceDate.isAfter(dayAfter),
                () -> "invoice_date must be the conversion day (today) but was " + invoiceDate);
        Assertions.assertNotEquals(INHERITED_ACCEPTED_AT.toLocalDate(), invoiceDate,
                "the conversion date and the inherited signature date differ");
        Assertions.assertEquals(SNAPSHOT_NAME, row.get("accepted_customer_name"));

        // ---- JSON (Path A and D.3): the inherited instant formatted to seconds; invoice_date today ----
        JsonNode invoice = assertInvoiceDetail(result, 1, "QUOTE", null, "Path A response");
        Assertions.assertEquals("2026-02-03T04:05:06", invoice.get("accepted_at").asText());
        Assertions.assertEquals(invoiceDate.toString(), invoice.get("invoice_date").asText());
        Assertions.assertEquals(SNAPSHOT_NAME, invoice.get("accepted_customer_name").asText());
        JsonNode current = assertInvoiceDetail(getCurrentInvoice(orderId), 1, "QUOTE", null, "D.3 after Path A");
        Assertions.assertEquals("2026-02-03T04:05:06", current.get("accepted_at").asText());
        Assertions.assertEquals(invoiceDate.toString(), current.get("invoice_date").asText());

        // ---- The PDF: the caption carries the inherited minute; the issued date is today ----
        byte[] pdf = invoicePdfFromDisk(orderId, 1);
        Assertions.assertArrayEquals(pdf, downloadCurrentInvoicePdf(orderId));
        String readingOrder = pdfTextInReadingOrder(pdf);
        String label = "inherited-time Path A PDF";
        assertPdfContains(readingOrder, "Accepted by Quote Tester on 03/02/2026 04:05", label);
        assertPdfLacks(readingOrder, "Accepted by " + SNAPSHOT_NAME + " on " + DISPLAY_DATE_TIME.format(realAcceptedAt),
                label);
        Assertions.assertEquals(1, countOccurrences(noSpace(readingOrder), "03/02/2026"),
                () -> label + ": the signature date appears only in the caption: " + flat(readingOrder));
        assertPdfContains(readingOrder, DISPLAY_DATE.format(invoiceDate), label + " (issued date = today)");
    }

    /**
     * A long frozen name is inherited untruncated: the customer's first and last names are each 100
     * characters (the order_customer maximum), so the V17 issue snapshot and the quote's accepted name
     * are 201 characters, beyond the old 150-character width and the D.8 request cap. Path A has no
     * name-length gate: it copies the name verbatim into the invoice row and the JSON, and the PDF
     * caption carries all of it.
     */
    @Test
    void pathA_acceptedNameOver150Characters_isInheritedUntruncated_inRowJsonAndPdfCaption() throws Exception {
        Assertions.assertEquals(100, LONG_FIRST_NAME.length(), "fixture: a 100-character first name");
        Assertions.assertEquals(100, LONG_LAST_NAME.length(), "fixture: a 100-character last name");
        String expectedName = LONG_FIRST_NAME + " " + LONG_LAST_NAME;
        clearTenantTerms();

        long orderId = invoiceReadyOrder(SOFT);
        jdbcTemplate.update("UPDATE order_customer SET first_name = ?, last_name = ? WHERE order_id = ?",
                LONG_FIRST_NAME, LONG_LAST_NAME, orderId);
        clearJpaCache();
        Map<String, Object> customer = jdbcTemplate.queryForMap(
                "SELECT first_name, last_name, email FROM order_customer WHERE order_id = ?", orderId);
        Assertions.assertEquals(LONG_FIRST_NAME, customer.get("first_name"), "precondition: stored untruncated");
        Assertions.assertEquals(LONG_LAST_NAME, customer.get("last_name"), "precondition: stored untruncated");
        Assertions.assertEquals(VALID_EMAIL, customer.get("email"), "precondition: the email stays valid");

        // The real chain: the issue freezes the long V17 name; the public accept takes it as the accepted name.
        saveDistinctiveItemisedDraft(orderId);
        acceptPublicly(sendAndExtractToken(orderId), ONE_PIXEL_PNG);
        Map<String, Object> quote = versionRow(orderId, 1);
        Assertions.assertEquals("ACCEPTED", quote.get("status"));
        Assertions.assertEquals(expectedName, quote.get("customer_name_snapshot"),
                "precondition: the V17 issue snapshot holds the full name");
        String acceptedName = (String) quote.get("accepted_customer_name");
        Assertions.assertEquals(expectedName, acceptedName, "precondition: the quote's accepted name is the full snapshot");
        Assertions.assertEquals(201, acceptedName.length());
        Assertions.assertTrue(acceptedName.length() > 150, "precondition: longer than 150 characters");
        LocalDateTime quoteAcceptedAt = asLocalDateTime(quote.get("accepted_at"));

        MvcResult result = createInvoiceFromQuote(orderId);

        // ---- Row and JSON (Path A and D.3): the quote's accepted name, exactly and untruncated ----
        Map<String, Object> row = invoiceRow(orderId, 1);
        Assertions.assertEquals(acceptedName, row.get("accepted_customer_name"),
                "invoice.accepted_customer_name = quote_version.accepted_customer_name exactly");
        Integer storedLength = jdbcTemplate.queryForObject(
                "SELECT char_length(accepted_customer_name) FROM invoice WHERE order_id = ? AND version_number = 1",
                Integer.class, orderId);
        Assertions.assertEquals(201, storedLength, "the stored invoice name is untruncated");
        JsonNode invoice = assertInvoiceDetail(result, 1, "QUOTE", null, "long-name Path A response");
        Assertions.assertEquals(acceptedName, invoice.get("accepted_customer_name").asText());
        JsonNode current = assertInvoiceDetail(getCurrentInvoice(orderId), 1, "QUOTE", null, "long-name D.3");
        Assertions.assertEquals(acceptedName, current.get("accepted_customer_name").asText());

        // ---- The PDF caption carries the full name (it may wrap, so whitespace is ignored) ----
        byte[] pdf = invoicePdfFromDisk(orderId, 1);
        Assertions.assertArrayEquals(pdf, downloadCurrentInvoicePdf(orderId));
        assertAcceptedCaption(pdf, acceptedName, quoteAcceptedAt);
    }

    // ================================================================
    // (9) Terms API and PDF
    // ================================================================

    /** SOFT: frozen null terms -> QUOTE + JSON null, 1-page PDF without terms, even though live soft terms exist. */
    @Test
    void pathA_frozenNullTerms_softOrder_termsHtmlNullSourceQuote_singlePagePdfWithoutTerms_despiteLiveTerms()
            throws Exception {
        assertFrozenNullTermsOnPathA(SOFT);
    }

    /** HARD: frozen null terms -> QUOTE + JSON null, 1-page PDF without terms, even though live terms_hard exists. */
    @Test
    void pathA_frozenNullTerms_hardOrder_termsHtmlNullSourceQuote_singlePagePdfWithoutTerms_despiteLiveHardTerms()
            throws Exception {
        assertFrozenNullTermsOnPathA(HARD);
    }

    private void assertFrozenNullTermsOnPathA(String flooringType) throws Exception {
        clearTenantTerms();                                         // nothing to freeze at issue
        long orderId = acceptedItemisedOrder(flooringType, ONE_PIXEL_PNG);
        Map<String, Object> quote = versionRow(orderId, 1);
        Assertions.assertEquals(flooringType, quote.get("flooring_type_snapshot"));
        Assertions.assertNull(quote.get("terms_snapshot"), "precondition: the quote froze NULL terms");

        // Live terms exist for BOTH types before Path A (HARD orders read terms_hard).
        setTenantTerms(RAW_LIVE_SOFT_TERMS, RAW_LIVE_HARD_TERMS);
        Assertions.assertNotNull(termsSanitizer.sanitize(SOFT.equals(flooringType) ? RAW_LIVE_SOFT_TERMS : RAW_LIVE_HARD_TERMS),
                "precondition: the live terms for this flooring type are non-null");

        MvcResult result = createInvoiceFromQuote(orderId);
        String label = flooringType + " frozen-null Path A";
        assertInvoiceDetail(result, 1, "QUOTE", null, label + " response");
        Assertions.assertTrue(body(result).contains("\"terms_source\":\"QUOTE\""));

        Map<String, Object> row = invoiceRow(orderId, 1);
        Assertions.assertEquals(asLong(quote.get("quote_version_id")), asLong(row.get("source_quote_version_id")));
        Assertions.assertNull(row.get("terms_snapshot"), "the frozen null is copied verbatim");

        // D.3 reads the same rule (no fallback to the live terms).
        assertInvoiceDetail(getCurrentInvoice(orderId), 1, "QUOTE", null, label + " D.3");

        byte[] pdf = invoicePdfFromDisk(orderId, 1);
        Assertions.assertArrayEquals(pdf, downloadCurrentInvoicePdf(orderId));
        assertPdfTerms(pdf, null, label + " PDF", LIVE_SOFT_TERMS_TEXT, LIVE_HARD_TERMS_TEXT);
    }

    /**
     * Path B (a D.1 invoice, no quote at all): D.1 FREEZES the production sanitizer's output for the
     * business's per-type terms at that moment (terms_soft for SOFT, terms_hard for HARD) into the row's
     * terms_snapshot (16G PR 2, #125); D.1 and D.3 report terms_source BUSINESS with terms_html = that
     * frozen value, and the PDF renders the same. After the business CLEARS its terms, D.3 still returns
     * the frozen terms (never a null, never a re-read) and the row keeps them.
     */
    @Test
    void pathB_d1Invoice_termsSourceBusiness_termsHtmlIsTheFrozenSanitizedPerTypeTerms_softAndHard_keptWhenCleared()
            throws Exception {
        setTenantTerms(RAW_LIVE_SOFT_TERMS, RAW_LIVE_HARD_TERMS);
        String expectedSoft = termsSanitizer.sanitize(RAW_LIVE_SOFT_TERMS);
        String expectedHard = termsSanitizer.sanitize(RAW_LIVE_HARD_TERMS);
        Assertions.assertNotNull(expectedSoft);
        Assertions.assertNotNull(expectedHard);
        Assertions.assertNotEquals(RAW_LIVE_SOFT_TERMS, expectedSoft, "precondition: the sanitizer rewrites the raw soft terms");
        Assertions.assertNotEquals(RAW_LIVE_HARD_TERMS, expectedHard, "precondition: the sanitizer rewrites the raw hard terms");
        Assertions.assertFalse(expectedSoft.contains("script") || expectedSoft.contains("onclick"));

        long softOrder = invoiceReadyOrder(SOFT);
        long hardOrder = invoiceReadyOrder(HARD);

        assertInvoiceDetail(createInvoice(softOrder), 1, "BUSINESS", expectedSoft, "SOFT D.1");
        assertInvoiceDetail(createInvoice(hardOrder), 1, "BUSINESS", expectedHard, "HARD D.1");
        assertInvoiceDetail(getCurrentInvoice(softOrder), 1, "BUSINESS", expectedSoft, "SOFT D.3");
        assertInvoiceDetail(getCurrentInvoice(hardOrder), 1, "BUSINESS", expectedHard, "HARD D.3");
        for (long orderId : new long[] {softOrder, hardOrder}) {
            Map<String, Object> row = invoiceRow(orderId, 1);
            Assertions.assertNull(row.get("source_quote_version_id"), "D.1 writes no quote source");
            Assertions.assertEquals(orderId == softOrder ? expectedSoft : expectedHard, row.get("terms_snapshot"),
                    "D.1 freezes the sanitised per-type business terms into terms_snapshot");
        }
        assertPdfTerms(invoicePdfFromDisk(softOrder, 1), LIVE_SOFT_TERMS_TEXT, "SOFT D.1 PDF", LIVE_HARD_TERMS_TEXT);
        assertPdfTerms(invoicePdfFromDisk(hardOrder, 1), LIVE_HARD_TERMS_TEXT, "HARD D.1 PDF", LIVE_SOFT_TERMS_TEXT);

        // BUSINESS terms were frozen at D.1: clearing the business's terms changes nothing on the version.
        clearTenantTerms();
        assertInvoiceDetail(getCurrentInvoice(softOrder), 1, "BUSINESS", expectedSoft, "SOFT D.3 after clearing terms");
        assertInvoiceDetail(getCurrentInvoice(hardOrder), 1, "BUSINESS", expectedHard, "HARD D.3 after clearing terms");
        for (long orderId : new long[] {softOrder, hardOrder}) {
            Assertions.assertEquals(orderId == softOrder ? expectedSoft : expectedHard,
                    invoiceRow(orderId, 1).get("terms_snapshot"),
                    "the row keeps its frozen terms after the business clears its terms");
        }
    }

    /**
     * terms_html, terms_source and the three frozen Invoice To keys are present (exact InvoiceDetail key
     * set) on EVERY InvoiceDetail response: D.1 create, D.2 rewrite, D.3 GET current, D.8 accept, D.9
     * resend (all BUSINESS, with terms_html = the version's stored terms_snapshot: frozen by D.1 / D.2,
     * copied by D.8; 16G PR 2, #125), and Path A (QUOTE, after a quote signed strictly later than the
     * in-app invoice signature). Editing the live terms after the quote signature never changes the
     * Path A terms. 16G PR 4: every response also carries invoice_link_expires_at - null on D.1 and D.2, the
     * expires_at of the link sent for v2 on D.3, and null from D.8 on (D.8 kills that link as SIGNED; D.9,
     * Path A and the later D.3 describe signed versions).
     */
    @Test
    void everyInvoiceDetailResponse_carriesTermsHtmlAndTermsSource_d1_d2_d3_d8_d9_andPathA() throws Exception {
        setTenantTerms(KEYS_TERMS_HTML, KEYS_TERMS_HTML);
        String businessTerms = termsSanitizer.sanitize(KEYS_TERMS_HTML);
        Assertions.assertNotNull(businessTerms);
        long orderId = invoiceReadyOrder(SOFT);

        Map<String, JsonNode> responses = new LinkedHashMap<>();
        responses.put("D.1 create",
                assertInvoiceDetail(createInvoice(orderId), 1, "BUSINESS", businessTerms, "D.1 create"));
        responses.put("D.2 rewrite",
                assertInvoiceDetail(rewriteInvoice(orderId), 2, "BUSINESS", businessTerms, "D.2 rewrite"));
        String linkExpiresAt = sendInvoiceLink(orderId);   // 16G PR 4: an ACTIVE link for v2
        responses.put("D.3 get current",
                assertInvoiceDetail(getCurrentInvoice(orderId), 2, "BUSINESS", businessTerms, "D.3 get current"));
        JsonNode accepted = assertInvoiceDetail(acceptInvoiceInApp(orderId), 3, "BUSINESS", businessTerms, "D.8 accept");
        Assertions.assertTrue(accepted.get("accepted_signature_present").booleanValue());
        responses.put("D.8 accept", accepted);
        responses.put("D.9 resend",
                assertInvoiceDetail(resendInvoice(orderId), 3, "BUSINESS", businessTerms, "D.9 resend"));
        // The Path B terms_html above is each version's STORED terms: Create / Rewrite froze them, D.8 copied them.
        for (int version = 1; version <= 3; version++) {
            Map<String, Object> row = invoiceRow(orderId, version);
            Assertions.assertNull(row.get("source_quote_version_id"), "Path B v" + version + " has no quote source");
            Assertions.assertEquals(businessTerms, row.get("terms_snapshot"),
                    "Path B v" + version + " stores the frozen business terms");
        }

        // A quote signed AFTER the in-app invoice signature (strictly newer) -> Path A appends v4.
        saveDistinctiveItemisedDraft(orderId);
        acceptPublicly(sendAndExtractToken(orderId), ONE_PIXEL_PNG);
        Map<String, Object> quote = versionRow(orderId, 1);
        Assertions.assertTrue(asLocalDateTime(quote.get("accepted_at"))
                        .isAfter(asLocalDateTime(invoiceRow(orderId, 3).get("accepted_at"))),
                "precondition: the quote signature is strictly newer than the invoice signature");
        String frozen = (String) quote.get("terms_snapshot");
        Assertions.assertEquals(businessTerms, frozen, "precondition: the quote froze the then-live terms");
        Assertions.assertEquals(SNAPSHOT_NAME, quote.get("customer_name_snapshot"), "precondition: V17 name");
        Assertions.assertEquals(ORIGINAL_BILLING_LINE1, quote.get("customer_address_line1_snapshot"),
                "precondition: V17 billing line 1");
        Assertions.assertEquals(BILLING_LINE2, quote.get("customer_address_line2_snapshot"),
                "precondition: V17 billing line 2");
        setTenantTerms(KEYS_EDITED_TERMS_HTML, KEYS_EDITED_TERMS_HTML);   // live terms move on

        responses.put("Path A", assertInvoiceDetail(createInvoiceFromQuote(orderId), 4, "QUOTE", frozen, "Path A"));
        responses.put("D.3 after Path A",
                assertInvoiceDetail(getCurrentInvoice(orderId), 4, "QUOTE", frozen, "D.3 after Path A"));
        assertPdfTerms(invoicePdfFromDisk(orderId, 4), KEYS_TERMS_TEXT, "Path A PDF", KEYS_EDITED_TERMS_TEXT);

        // The three frozen Invoice To keys are present on every response: the saved customer and BILLING
        // address frozen by D.1 / D.2 (and copied by D.8), and the quote's V17 snapshot on Path A.
        for (Map.Entry<String, JsonNode> response : responses.entrySet()) {
            assertInvoiceToWords(response.getValue(), SNAPSHOT_NAME, ORIGINAL_BILLING_LINE1, BILLING_LINE2,
                    response.getKey());
        }

        // 16G PR 4: the derived link field on every response (the key set above already requires it): only D.3,
        // read while the link sent for v2 was ACTIVE and unexpired, reports that link's expires_at.
        for (Map.Entry<String, JsonNode> response : responses.entrySet()) {
            JsonNode linkField = response.getValue().get("invoice_link_expires_at");
            if (response.getKey().equals("D.3 get current")) {
                Assertions.assertEquals(linkExpiresAt, linkField.asText(),
                        "D.3 get current: the ACTIVE link's expires_at");
            } else {
                Assertions.assertTrue(linkField.isNull(),
                        () -> response.getKey() + ": invoice_link_expires_at must be null but was " + linkField);
            }
        }
        Assertions.assertEquals(List.of("SIGNED"), jdbcTemplate.queryForList(
                        "SELECT status FROM invoice_token WHERE order_id = ?", String.class, orderId),
                "D.8 killed the only link as SIGNED");
    }

    /** Populated frozen terms survive a payment and its void, after the live terms are edited. */
    @Test
    void pathA_populatedFrozenTerms_surviveLaterPaymentAndVoid_afterLiveTermsEdited_inApiAndEveryNewPdf()
            throws Exception {
        assertFrozenTermsSurvivePaymentAndVoid(ISSUE_TERMS_HTML, ISSUE_TERMS_TEXT);
    }

    /** Frozen NULL terms survive a payment and its void, after live terms are set: null API, no terms page. */
    @Test
    void pathA_frozenNullTerms_surviveLaterPaymentAndVoid_afterLiveTermsSet_apiNullAndNoTermsPage()
            throws Exception {
        assertFrozenTermsSurvivePaymentAndVoid(null, null);
    }

    private void assertFrozenTermsSurvivePaymentAndVoid(String termsAtIssueHtml, String termsAtIssueText)
            throws Exception {
        String label = termsAtIssueHtml == null ? "frozen-null" : "frozen-populated";
        if (termsAtIssueHtml == null) {
            clearTenantTerms();
        } else {
            setTenantTerms(termsAtIssueHtml, termsAtIssueHtml);
        }
        long orderId = acceptedItemisedOrder(SOFT, ONE_PIXEL_PNG);
        Map<String, Object> quote = versionRow(orderId, 1);
        long quoteVersionId = asLong(quote.get("quote_version_id"));
        String frozen = (String) quote.get("terms_snapshot");
        if (termsAtIssueHtml == null) {
            Assertions.assertNull(frozen, "precondition: frozen null terms");
        } else {
            Assertions.assertEquals(termsSanitizer.sanitize(termsAtIssueHtml), frozen, "precondition: frozen terms");
        }

        assertInvoiceDetail(createInvoiceFromQuote(orderId), 1, "QUOTE", frozen, label + " Path A");
        assertPdfTerms(invoicePdfFromDisk(orderId, 1), termsAtIssueText, label + " Path A PDF", EDITED_TERMS_TEXT);

        // The business edits its live terms AFTER the conversion.
        setTenantTerms(EDITED_TERMS_HTML, EDITED_TERMS_HTML);

        // Payment -> v2: carries the source + frozen terms; never the edited live terms.
        MvcResult payment = recordCashPayment(orderId, "50.00");
        assertSummaryHasNoTermsOrInvoiceToKeys(payment, label + " payment");
        assertInvoiceDetail(getCurrentInvoice(orderId), 2, "QUOTE", frozen, label + " D.3 after payment");
        assertCarriedQuoteSource(orderId, 2, quoteVersionId, frozen, label + " payment version");
        assertPdfTerms(invoicePdfFromDisk(orderId, 2), termsAtIssueText, label + " payment PDF", EDITED_TERMS_TEXT);

        // Void -> v3: the same.
        MvcResult voided = voidPayment(orderId, latestPaymentId(orderId));
        assertSummaryHasNoTermsOrInvoiceToKeys(voided, label + " void");
        assertInvoiceDetail(getCurrentInvoice(orderId), 3, "QUOTE", frozen, label + " D.3 after void");
        assertCarriedQuoteSource(orderId, 3, quoteVersionId, frozen, label + " void version");
        assertPdfTerms(invoicePdfFromDisk(orderId, 3), termsAtIssueText, label + " void PDF", EDITED_TERMS_TEXT);
    }

    /**
     * Payment / void responses use CurrentInvoiceSummaryDto, which is unchanged: no terms keys and (16G
     * PR 2) none of the three frozen Invoice To keys, which are InvoiceDetail-only.
     */
    private static void assertSummaryHasNoTermsOrInvoiceToKeys(MvcResult result, String label) throws IOException {
        String responseBody = body(result);
        JsonNode summary = JSON.readTree(responseBody).path("data").path("current_invoice");
        Assertions.assertTrue(summary.isObject(), () -> label + ": data.current_invoice must be an object: " + responseBody);
        Assertions.assertFalse(summary.has("terms_html"), () -> label + ": CurrentInvoiceSummary has no terms_html");
        Assertions.assertFalse(summary.has("terms_source"), () -> label + ": CurrentInvoiceSummary has no terms_source");
        Assertions.assertFalse(responseBody.contains("terms_"), () -> label + ": no terms key anywhere: " + responseBody);
        for (String key : List.of("customer_name_snapshot", "customer_address_line1_snapshot",
                "customer_address_line2_snapshot")) {
            Assertions.assertFalse(responseBody.contains(key),
                    () -> label + ": no '" + key + "' key anywhere: " + responseBody);
        }
    }

    /**
     * A payment / void version carries BOTH V19 columns, the three V20 Invoice To words (16G PR 2) and
     * the acceptance verbatim from Path A.
     */
    private void assertCarriedQuoteSource(long orderId, int versionNumber, long quoteVersionId, String frozen,
                                          String label) {
        Map<String, Object> pathA = invoiceRow(orderId, 1);
        Map<String, Object> row = invoiceRow(orderId, versionNumber);
        Assertions.assertEquals(quoteVersionId, asLong(row.get("source_quote_version_id")),
                label + ": source_quote_version_id carried");
        Assertions.assertEquals(frozen, row.get("terms_snapshot"), label + ": terms_snapshot carried verbatim");
        for (String word : List.of("customer_name_snapshot", "customer_address_line1_snapshot",
                "customer_address_line2_snapshot")) {
            Assertions.assertEquals(pathA.get(word), row.get(word), label + ": " + word + " copied verbatim");
        }
        Assertions.assertEquals(pathA.get("accepted_at"), row.get("accepted_at"), label + ": accepted_at carried");
        Assertions.assertEquals(pathA.get("accepted_customer_name"), row.get("accepted_customer_name"));
        Assertions.assertEquals(pathA.get("accepted_signature_file_id"), row.get("accepted_signature_file_id"));
    }

    /**
     * Frozen terms travel BYTE FOR BYTE and are never re-sanitised or trimmed: after the real accept,
     * the accepted version's terms_snapshot is replaced by SQL with XML-safe markup the sanitizer WOULD
     * rewrite (an id attribute), wrapped in leading spaces and a trailing newline. Path A (201 body +
     * row), D.3, and the payment and void versions (row + D.3) all carry exactly that string, and every
     * new stored PDF renders it on page 2, while different live tenant terms (set before the issue and
     * edited again after Path A) never replace it.
     */
    @Test
    void pathA_frozenTermsTheSanitizerWouldRewrite_carriedByteForByte_throughPathAPaymentAndVoid_rowsApiAndPdfs()
            throws Exception {
        String frozen = UNSANITISED_FROZEN_TERMS;
        Assertions.assertNotEquals(frozen, termsSanitizer.sanitize(frozen),
                "precondition: the sanitizer WOULD rewrite the fixture, so any re-sanitising is visible");
        String[] liveTexts = {FALLBACK_LIVE_TERMS_TEXT, FALLBACK_EDITED_TERMS_TEXT};

        // Live tenant terms exist from the start (the quote freezes them at issue; SQL then replaces them).
        setTenantTerms(FALLBACK_LIVE_TERMS_HTML, FALLBACK_LIVE_TERMS_HTML);
        long orderId = acceptedItemisedOrder(SOFT, ONE_PIXEL_PNG);
        Map<String, Object> quote = versionRow(orderId, 1);
        long quoteVersionId = asLong(quote.get("quote_version_id"));
        Assertions.assertEquals(termsSanitizer.sanitize(FALLBACK_LIVE_TERMS_HTML), quote.get("terms_snapshot"),
                "precondition: the issue froze the then-live terms");

        // AFTER the real accept and BEFORE Path A: the frozen terms become the unsanitised fixture.
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE quote_version SET terms_snapshot = ? WHERE order_id = ? AND version_number = 1 "
                        + "AND status = 'ACCEPTED'", frozen, orderId));
        clearJpaCache();
        Assertions.assertEquals(frozen, versionRow(orderId, 1).get("terms_snapshot"),
                "precondition: the accepted version carries the fixture byte for byte");
        Assertions.assertNotEquals(frozen, termsSanitizer.sanitize(FALLBACK_LIVE_TERMS_HTML),
                "precondition: a live fallback would be visible");

        // ---- Path A -> v1: 201 body, row, D.3 and the PDF ----
        assertInvoiceDetail(createInvoiceFromQuote(orderId), 1, "QUOTE", frozen, "Path A 201");
        Map<String, Object> pathARow = invoiceRow(orderId, 1);
        Assertions.assertEquals(frozen, pathARow.get("terms_snapshot"), "Path A row terms_snapshot byte for byte");
        Assertions.assertEquals(quoteVersionId, asLong(pathARow.get("source_quote_version_id")));
        assertInvoiceDetail(getCurrentInvoice(orderId), 1, "QUOTE", frozen, "D.3 after Path A");
        byte[] pathAPdf = invoicePdfFromDisk(orderId, 1);
        assertPdfTerms(pathAPdf, UNSANITISED_FROZEN_TERMS_TEXT, "Path A PDF", liveTexts);

        // The business edits its live terms AFTER the conversion.
        setTenantTerms(FALLBACK_EDITED_TERMS_HTML, FALLBACK_EDITED_TERMS_HTML);

        // ---- Payment -> v2: row and D.3 byte for byte; the new PDF renders the frozen terms ----
        assertSummaryHasNoTermsOrInvoiceToKeys(recordCashPayment(orderId, "50.00"), "payment");
        Assertions.assertEquals(2, invoiceCount(orderId));
        Assertions.assertEquals(frozen, invoiceRow(orderId, 2).get("terms_snapshot"),
                "payment row terms_snapshot byte for byte");
        assertCarriedQuoteSource(orderId, 2, quoteVersionId, frozen, "payment version");
        assertInvoiceDetail(getCurrentInvoice(orderId), 2, "QUOTE", frozen, "D.3 after payment");
        byte[] paymentPdf = invoicePdfFromDisk(orderId, 2);
        assertPdfTerms(paymentPdf, UNSANITISED_FROZEN_TERMS_TEXT, "payment PDF", liveTexts);

        // ---- Void -> v3: the same ----
        assertSummaryHasNoTermsOrInvoiceToKeys(voidPayment(orderId, latestPaymentId(orderId)), "void");
        Assertions.assertEquals(3, invoiceCount(orderId));
        Assertions.assertEquals(frozen, invoiceRow(orderId, 3).get("terms_snapshot"),
                "void row terms_snapshot byte for byte");
        assertCarriedQuoteSource(orderId, 3, quoteVersionId, frozen, "void version");
        assertInvoiceDetail(getCurrentInvoice(orderId), 3, "QUOTE", frozen, "D.3 after void");
        assertPdfTerms(invoicePdfFromDisk(orderId, 3), UNSANITISED_FROZEN_TERMS_TEXT, "void PDF", liveTexts);

        // The quote's own frozen value is untouched, and older stored PDFs never change.
        Assertions.assertEquals(frozen, versionRow(orderId, 1).get("terms_snapshot"));
        Assertions.assertArrayEquals(pathAPdf, invoicePdfFromDisk(orderId, 1));
        Assertions.assertArrayEquals(paymentPdf, invoicePdfFromDisk(orderId, 2));
    }

    /**
     * Path B freezes its terms (16G PR 2, #125): a D.1 invoice freezes the business's terms and its PDF
     * shows them; after the business edits its terms, a payment's new version RETAINS the D.1 frozen
     * terms - its row (terms_snapshot copied verbatim), its new PDF and D.3 (terms_html, source BUSINESS)
     * - and never shows the edited terms. The older stored PDF stays byte for byte unchanged.
     */
    @Test
    void pathB_paymentAfterBusinessTermsEdit_newVersionKeepsTheFrozenTermsInRowPdfAndApi_olderStoredPdfUnchanged()
            throws Exception {
        setTenantTerms(PATH_B_TERMS_HTML, PATH_B_TERMS_HTML);
        String frozen = termsSanitizer.sanitize(PATH_B_TERMS_HTML);
        Assertions.assertNotNull(frozen);
        long orderId = invoiceReadyOrder(SOFT);
        assertInvoiceDetail(createInvoice(orderId), 1, "BUSINESS", frozen, "D.1");
        byte[] v1Pdf = invoicePdfFromDisk(orderId, 1);
        assertPdfTerms(v1Pdf, PATH_B_TERMS_TEXT, "D.1 PDF", PATH_B_EDITED_TERMS_TEXT);

        // The business edits its terms AFTER the D.1 freeze.
        setTenantTerms(PATH_B_EDITED_TERMS_HTML, PATH_B_EDITED_TERMS_HTML);
        Assertions.assertNotEquals(frozen, termsSanitizer.sanitize(PATH_B_EDITED_TERMS_HTML),
                "precondition: a re-read of the edited business terms would be visible");

        assertSummaryHasNoTermsOrInvoiceToKeys(recordCashPayment(orderId, "50.00"), "Path B payment");
        Assertions.assertEquals(2, invoiceCount(orderId), "the payment appended exactly one version");
        assertPdfTerms(invoicePdfFromDisk(orderId, 2), PATH_B_TERMS_TEXT, "Path B payment PDF",
                PATH_B_EDITED_TERMS_TEXT);
        assertInvoiceDetail(getCurrentInvoice(orderId), 2, "BUSINESS", frozen, "Path B D.3 after payment");
        for (int version = 1; version <= 2; version++) {
            Map<String, Object> row = invoiceRow(orderId, version);
            Assertions.assertNull(row.get("source_quote_version_id"), "Path B v" + version + " has no quote source");
            Assertions.assertEquals(frozen, row.get("terms_snapshot"),
                    "Path B v" + version + " stores the D.1 frozen terms, never the edited business terms");
        }
        Assertions.assertArrayEquals(v1Pdf, invoicePdfFromDisk(orderId, 1), "an older stored PDF never auto-updates");
    }
}
