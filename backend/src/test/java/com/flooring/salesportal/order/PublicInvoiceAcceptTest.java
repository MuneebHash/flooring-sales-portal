package com.flooring.salesportal.order;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.email.InvoiceAcceptanceNotificationRequest;
import com.flooring.salesportal.common.email.InvoiceEmailRequest;
import com.flooring.salesportal.common.email.InvoiceLinkEmailRequest;
import com.flooring.salesportal.common.email.QuoteEmailRequest;
import com.flooring.salesportal.common.email.RecordingInvoiceAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingInvoiceEmailSender;
import com.flooring.salesportal.common.email.RecordingInvoiceLinkEmailSender;
import com.flooring.salesportal.common.email.RecordingQuoteAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.ByteBuffer;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 16G PR 4 - the PUBLIC remote invoice accept {@code POST /api/v1/public/invoices/{token}/accept}
 * ({@link PublicInvoiceController} → {@link PublicInvoiceService#accept}). Authority:
 * docs/API-Contracts-Phase16G-Invoice-Link.md section 5.3 (the {@code expected_version_number} value rules),
 * 9.4 (the two parts, the PNG rules, the order of checks and the exact 201), 10.1 (the persisted signing
 * version), 10.2 (its creator), 10.3 (the customer's signed-PDF email), 10.4 (the store notification), 11
 * (the email markers), 12 (LAID and CANCELLED) and 14 (codes and messages), and the PR 4 row of section 16.
 *
 * <p><b>What is proven.</b> The framework (a non-multipart POST: 415, code {@code VALIDATION_FAILED}) and the
 * token gate (an unknown or malformed token: 404 {@code INVOICE_TOKEN_NOT_FOUND}; a dead link: its 410) answer
 * before the body is looked at. The body is validated with no database access in ONE 400
 * {@code VALIDATION_FAILED} that lists every offending part in the contract order - unknown file parts, then
 * every form field other than {@code expected_version_number} and every URL query-string parameter (a
 * query-string copy of {@code expected_version_number} included; only the multipart form field is the
 * carrier), then a repeated {@code signature}, then the one {@code expected_version_number} detail
 * ({@code Required.} / {@code Must appear at most once.} / {@code Must be a positive integer.}). Only after a
 * clean body: a missing or empty signature is 422 {@code SIGNATURE_REQUIRED}, and a declared type other than
 * {@code image/png}, more than 2,097,152 bytes or bytes outside the shared PNG rules (magic, a 13-byte first
 * {@code IHDR}, at most 8,192 px a side and 4,000,000 px, bit depth at most 8, colour type 0/2/3/4/6, a
 * decodable stream) is 400 {@code SIGNATURE_INVALID}; exactly 2,097,152 bytes signs and only the
 * server-normalised PNG is stored. Under the order lock the stale comparison is the authority (409
 * {@code INVOICE_VERSION_STALE} below and above the current version), a signed current version under an
 * {@code ACTIVE} link is the generic 500, and a null or blank frozen name is 422
 * {@code ACCEPTED_CUSTOMER_NAME_REQUIRED} with the customer-neutral sentence. Every refusal writes NOTHING:
 * no invoice row, no {@code stored_file} row, no file in the order's directory, no token change (the link
 * stays {@code ACTIVE}), no quote change, no email and no notification. A signing appends version N = current
 * + 1 copied column by column from the current version (dates, details, money, source quote version, the
 * sanitised terms - never business terms edited after the send - and the three frozen identity columns, nulls
 * and whitespace included), the dashboard mirror reset to the new version's value, accepted at the signing time by the
 * frozen name verbatim and in full (never the live customer), with the normalised signature and the signed
 * PDF as {@code stored_file} rows named by the contract, created by the staff member who SENT the link, the
 * presented token {@code SIGNED} at {@code accepted_at}, D5(c) applied, and no order price, status, line,
 * draft or payment write; the 201 is exactly {@code {"data":{"state":"SIGNED"},"message":"Invoice accepted."}}
 * and the link is dead afterwards. A cross-day signing keeps the current version's {@code invoice_date} in the
 * row, InvoiceDetail and the stored and emailed PDF, while the caption carries the signing time. There is no
 * customer-email gate (an app-invalid or missing saved email still signs; the customer email is skipped with
 * the exact WARN and the store is still notified); a valid email gets the exact signed-PDF email (trimmed
 * recipient, the stored bytes) and both markers carry its stamp; the store notification has the exact
 * recipient (the trimmed store email), subject and body and carries no link, token, signature, storage
 * reference, cost or GP; a store without an email is skipped with the exact WARN; a failed email and a failed
 * notification are each non-fatal and independent (the exact WARNs, the markers null only when the email
 * failed). LAID and CANCELLED orders sign.
 *
 * <p><b>Fixtures (self-seeded, Phase 14D rule).</b> {@link #setUp} inserts, inside the test transaction, a
 * fresh tenant: a business (slug {@value #SLUG}) with SOFT and HARD terms, one store ({@value #STORE_CODE}),
 * the order user {@value #ORDER_USER_CODE} and the link sender {@value #SENDER_CODE} (both with access to the
 * store). Each test inserts its own invoice-ready order (customer "Avery Lindqvist", INSTALLATION and BILLING
 * addresses, details of sale, a CONFIRMED lay date 45 days ahead, one {@value #CHARGE_CODE_PREFIX} charge
 * line), creates invoice v1 through the real D.1 (as the order user), records payments and issues quotes
 * through the real protected endpoints, sends the invoice link through the real send-link (as the sender,
 * so the plaintext token is read from the recorded link email) and signs through the real public endpoint
 * WITHOUT any session. No V4 business, store, user or order is read or written. Raw SQL only seeds and plants
 * state (a frozen name, words, an invoice date, a saved or store email, a removed customer, a status, a source
 * quote version, edited business terms, a stale mirror, a signed current version), each write followed by a
 * persistence-context clear. The two recorders' own failure hooks inject the delivery failures; no bean is
 * replaced or spied.
 *
 * <p><b>Transaction and security.</b> The class is {@code @Transactional}: every programmatic transaction of
 * the services joins the test transaction, so the "post-commit" email, stamp and notification run inside it
 * (their commit-boundary variants live in the commit integration classes) and everything rolls back; the
 * services' rollback hooks delete the files they wrote under {@value #STORAGE_BASE_DIR}. MockMvc runs the REAL
 * Spring Security filter chain, and every public request goes through {@link #publicCall}, which asserts that
 * no session exists after the request and no cookie is set, whatever the status. The recording senders are
 * singletons whose state survives the rollback, so all five are reset before and after each test.
 */
@ExtendWith(SpringExtension.class)
// The SAME storage property as the other invoice/quote suites, so this class joins their cached Spring
// context. Files stay apart: every file lives under this test's own business and order directories.
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
@Transactional
class PublicInvoiceAcceptTest {

    /** Must equal the {@code @SpringBootTest} property above (asserted in {@link #setUp}). */
    private static final String STORAGE_BASE_DIR = "target/test-storage/quote-acceptance";

    // ---- The self-seeded tenant (class-unique identifiers, never a V4 identity) ----
    private static final String SLUG = "pr4-public-accept";
    private static final String BUSINESS_NAME = "Acceptwood Public Flooring";
    private static final String STORE_CODE = "IPA-ONE";
    private static final String STORE_EMAIL = "showroom@acceptwood.example.com";
    /** A store email with surrounding spaces (chk_store_email_format allows them): notified trimmed. */
    private static final String PADDED_STORE_EMAIL = "  showroom@acceptwood.example.com  ";
    /** The order's own salesperson (sales_order.user_id): creates v1, records payments, issues quotes. */
    private static final String ORDER_USER_CODE = "PA1";
    /** The staff member who SENDS the invoice link: the creator of a remotely signed version. */
    private static final String SENDER_CODE = "PA2";
    private static final String CHARGE_CODE_PREFIX = "IPACC";

    // ---- The saved customer of every order ----
    private static final String CUSTOMER_FIRST_NAME = "Avery";
    private static final String CUSTOMER_LAST_NAME = "Lindqvist";
    /** What D.1 freezes into v1's customer_name_snapshot: the remote accepted name. */
    private static final String FROZEN_NAME = "Avery Lindqvist";
    private static final String CUSTOMER_EMAIL = "avery.lindqvist@example.com";
    /** A saved email with surrounding spaces, which chk_order_customer_email_format allows: emailed trimmed. */
    private static final String PADDED_CUSTOMER_EMAIL = "  avery.lindqvist@example.com  ";
    /** DB-valid (trimmed non-empty, contains '@') but app-invalid (no interior dot in the domain). */
    private static final String APP_INVALID_EMAIL = "avery@localhost";
    private static final String DETAILS_OF_SALE = "Supply and lay plush carpet (public invoice accept test)";
    /** Raw business terms the sanitiser changes (the script goes), so a frozen value is visibly the sanitised one. */
    private static final String SOFT_TERMS = "<p>Acceptwood soft terms AWS1.</p><script>alert('acceptwood')</script>";
    private static final String SOFT_TERMS_TEXT = "Acceptwood soft terms AWS1.";
    /** Business terms edited live after the link was sent: a signing version never picks them up. */
    private static final String EDITED_SOFT_TERMS = "<p>Acceptwood edited live terms AWX9.</p>";
    private static final String EDITED_SOFT_TERMS_TEXT = "Acceptwood edited live terms AWX9.";
    private static final String HARD_TERMS = "<p>Acceptwood hard terms AWH1.</p>";
    /** The live BILLING street and suburb: never rendered when the version's frozen lines are null. */
    private static final String LIVE_BILLING_STREET = "Oxford Street";
    private static final String LIVE_BILLING_SUBURB = "Paddington";

    // ---- Frozen names planted on the current version ----
    /** 36 six-letter words and one eight-letter word joined by single spaces: exactly 260 characters. */
    private static final String LONG_FROZEN_NAME = longName();
    /** Edge whitespace and a doubled inner space: only a VERBATIM copy keeps this exact string. */
    private static final String PADDED_FROZEN_NAME = "  Casey  Padded Frozen ";

    // ---- The public surface ----
    private static final String PUBLIC_INVOICES = "/api/v1/public/invoices/";
    private static final Pattern INVOICE_LINK_PATTERN = Pattern.compile("/i/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");
    private static final Pattern QUOTE_LINK_PATTERN = Pattern.compile("/q/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");
    private static final String SIGNATURE_PART = "signature";
    private static final String EXPECTED_VERSION_FIELD = "expected_version_number";
    private static final String ACCEPTED_NAME_FIELD = "accepted_customer_name";
    /** The ONLY 201 body of the public accept (16G contract 9.4): no id, amount, name, timestamp, file or token. */
    private static final String ACCEPTED_BODY = "{\"data\":{\"state\":\"SIGNED\"},\"message\":\"Invoice accepted.\"}";
    /** The 27 keys of PublicInvoiceView (16G contract 9.3), always present. */
    private static final Set<String> PUBLIC_VIEW_KEYS = new TreeSet<>(List.of(
            "state", "business_name", "business_logo_url", "accent_color", "business_abn",
            "payment_account_name", "payment_bank_name", "payment_bsb", "payment_account_number",
            "payment_stripe_link_url", "order_number", "flooring_type", "version_number", "invoice_date",
            "due_date", "customer_name", "customer_address_line1", "customer_address_line2", "details_of_sale",
            "sale_price_ex_gst", "gst_amount", "sale_price_inc_gst", "total_paid", "balance_due", "terms_html",
            "expires_at", "message"));
    /** The columns a signing version copies verbatim from the current version (16G contract 10.1). */
    private static final List<String> CARRIED_COLUMNS = List.of(
            "invoice_date", "due_date", "details_of_sale_snapshot", "sale_price_ex_gst", "sale_price_inc_gst",
            "total_paid", "balance_due", "source_quote_version_id", "terms_snapshot", "customer_name_snapshot",
            "customer_address_line1_snapshot", "customer_address_line2_snapshot");

    // ---- PNG fixtures (the public quote accept's kinds) ----
    // A real, decodable 1x1 PNG: the accept decodes it and embeds the normalised bytes in the signed PDF.
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    private static final int MAX_SIGNATURE_BYTES = 2_097_152;
    // IHDR layout after the 8-byte magic: length(4) "IHDR"(4) width(4) height(4) bitDepth(1) colourType(1)
    // compression(1) filter(1) interlace(1), then CRC(4) over type + data.
    private static final int PNG_MAGIC_LENGTH = 8;
    private static final int IHDR_TYPE_OFFSET = 12;
    private static final int IHDR_BIT_DEPTH_OFFSET = 24;
    private static final int IHDR_COLOUR_TYPE_OFFSET = 25;
    private static final int IHDR_CRC_OFFSET = 29;
    private static final int IHDR_CHUNK_END = 33;
    private static final int MAX_SIGNATURE_SIDE_PX = 8_192;
    private static final long MAX_SIGNATURE_PIXELS = 4_000_000L;
    /** Payload of an ancillary tEXt chunk added to an upload: server normalisation must drop it. */
    private static final String TEXT_CHUNK_MARKER = "public-invoice-accept-ancillary-text-marker";

    // ---- Codes and exact messages ----
    private static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    private static final String VALIDATION_FAILED_MESSAGE = "One or more fields are invalid.";
    private static final String NOT_ALLOWED = "Not allowed.";
    private static final String REQUIRED = "Required.";
    private static final String AT_MOST_ONCE = "Must appear at most once.";
    private static final String POSITIVE_INTEGER = "Must be a positive integer.";
    private static final String SIGNATURE_REQUIRED = "SIGNATURE_REQUIRED";
    private static final String SIGNATURE_REQUIRED_MESSAGE = "A customer signature is required to accept the invoice.";
    private static final String SIGNATURE_INVALID = "SIGNATURE_INVALID";
    private static final String SIGNATURE_INVALID_MESSAGE = "Signature must be a PNG image no larger than 2 MB.";
    private static final String STALE = "INVOICE_VERSION_STALE";
    private static final String STALE_MESSAGE = "Invoice updated, please refresh";
    private static final String NAME_REQUIRED = "ACCEPTED_CUSTOMER_NAME_REQUIRED";
    private static final String CONTACT_STORE_MESSAGE =
            "This invoice can no longer be accepted online. Please contact the store.";
    private static final String LINK_SIGNED = "INVOICE_LINK_SIGNED";
    private static final String LINK_SIGNED_MESSAGE =
            "This invoice has already been signed. Please contact the store if you need a copy.";
    private static final String INTERNAL_ERROR = "INTERNAL_SERVER_ERROR";
    private static final String INTERNAL_ERROR_MESSAGE = "An unexpected error occurred.";
    private static final String GENERIC_500_BODY =
            "{\"error\":{\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"An unexpected error occurred.\"}}";
    private static final String LOADED_MESSAGE = "Invoice loaded.";
    private static final String LINK_SENT_MESSAGE = "Invoice link emailed to the customer.";
    private static final String TOKEN_NOT_FOUND = "INVOICE_TOKEN_NOT_FOUND";
    private static final String TOKEN_NOT_FOUND_MESSAGE = "Invoice not found.";

    // ---- The PublicInvoiceService WARNs (order id + version only) ----
    private static final String CUSTOMER_EMAIL_SKIPPED_WARN = "Signed invoice email skipped for order %d invoice v%d: "
            + "no valid saved customer email (non-fatal; markers stay null)";
    private static final String CUSTOMER_EMAIL_FAILED_WARN = "Signed invoice email failed for order %d invoice v%d "
            + "(non-fatal; the committed invoice stands, markers stay null)";
    private static final String STORE_NOTIFICATION_SKIPPED_WARN = "Invoice acceptance store notification skipped: "
            + "no store email for the order's store (order %d invoice v%d; signing persisted)";
    private static final String STORE_NOTIFICATION_FAILED_WARN =
            "Invoice acceptance store notification failed (order %d invoice v%d; signing persisted)";

    // ---- PDF and notification text ----
    /** How the invoice PDF prints the issued / due dates, and the caption / notification timestamp. */
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DISPLAY_DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH);
    /** The "Issued / Due" eyebrow and the next column's "Sales Person / Store" eyebrow (uppercase by CSS, no spaces). */
    private static final String ISSUED_DUE_HEADING = "ISSUED/DUE";
    private static final String NEXT_COLUMN_HEADING = "SALESPERSON/STORE";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingInvoiceEmailSender invoiceEmailSender;

    @Autowired
    private RecordingInvoiceLinkEmailSender linkEmailSender;

    @Autowired
    private RecordingInvoiceAcceptanceNotificationSender notificationSender;

    @Autowired
    private RecordingQuoteEmailSender quoteEmailSender;

    @Autowired
    private RecordingQuoteAcceptanceNotificationSender quoteNotificationSender;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    private MockMvc mockMvc;

    private long businessId;
    private int storeId;
    private long orderUserId;
    private long senderUserId;
    /** Every order's proposed lay date (45 days ahead, so the due date is never today). */
    private LocalDate layDate;
    private int orderSequence;

    @BeforeEach
    void setUp() {
        // The REAL Spring Security filter chain (not a filter-less MockMvc).
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        resetRecorders();
        Assertions.assertEquals(STORAGE_BASE_DIR, storageBaseDir,
                "this class reads its files under the shared invoice/quote storage dir");
        Assertions.assertEquals(0, count("SELECT COUNT(*) FROM business WHERE slug = ?", SLUG),
                "no committed row of this class may exist: it only ever writes inside its rolled-back transaction");
        layDate = LocalDate.now().plusDays(45);
        orderSequence = 0;
        seedTenant();
    }

    @AfterEach
    void tearDown() {
        // Singletons: never leak recorded messages or an armed failure into another test or class.
        resetRecorders();
    }

    private void resetRecorders() {
        invoiceEmailSender.reset();
        linkEmailSender.reset();
        notificationSender.reset();
        quoteEmailSender.reset();
        quoteNotificationSender.reset();
    }

    // ================================================================
    // 1. The multipart body: one aggregated 400 (contract 9.4 step 2, 5.3)
    // ================================================================

    /**
     * Every offending part is listed in ONE 400 {@code VALIDATION_FAILED} ("One or more fields are invalid."),
     * one detail per offence, in the contract order: unknown file parts (a second file named {@code photo}, a
     * FILE part named {@code expected_version_number}); then every form field other than
     * {@code expected_version_number} ({@code name}, {@code accepted_customer_name}, {@code declaration}) and
     * every URL query-string parameter ({@code x}, and a query-string copy of {@code expected_version_number},
     * which beside the form field is only {@code Not allowed.} and on its own also leaves the form field
     * {@code Required.}); then a repeated {@code signature}; then the one {@code expected_version_number}
     * detail: {@code Must appear at most once.} when repeated, {@code Required.} when there is no form value,
     * {@code Must be a positive integer.} for blank, {@code 0}, {@code -3}, {@code abc} and {@code 2.5}.
     * Several offences at once produce all their details in that order in one response, and the body is
     * answered before any stage-3 check (a stale value with an unknown field is still the 400). Nothing is
     * written by any of them (the link stays {@code ACTIVE}); the clean body then signs.
     */
    @Test
    void accept_multipartBody_everyOffendingPartIsListedInOneAggregated400_inTheContractOrder_nothingWritten()
            throws Exception {
        long orderId = invoicedOrder();
        String token = sendLink(orderId);
        Assertions.assertEquals(1, currentVersion(orderId), "fixture: v1 is the current, unsigned version behind the link");

        // Unknown file parts.
        assertAcceptDetails(orderId, accept(token, version("1"), pngSignature(), file(photoPart())),
                List.of(detail("photo", NOT_ALLOWED)), "a second file part named photo");
        assertAcceptDetails(orderId, accept(token, pngSignature(), file(textPart(EXPECTED_VERSION_FIELD, "1"))),
                List.of(detail(EXPECTED_VERSION_FIELD, NOT_ALLOWED), detail(EXPECTED_VERSION_FIELD, REQUIRED)),
                "only a FILE part named expected_version_number (not the form field)");

        // Unknown form fields: no name, no declaration flag (the declarations are a frontend-only gate).
        for (String name : List.of("name", ACCEPTED_NAME_FIELD, "declaration")) {
            assertAcceptDetails(orderId, accept(token, version("1"), pngSignature(), field(name, FROZEN_NAME)),
                    List.of(detail(name, NOT_ALLOWED)), "the form field " + name);
        }
        assertAcceptDetails(orderId, accept(token, version("1"), field(SIGNATURE_PART, "data:image/png;base64,AAAA")),
                List.of(detail(SIGNATURE_PART, NOT_ALLOWED)),
                "a TEXT form field named signature (the signature must be a file part)");

        // URL query-string parameters: never data on a mutation, and never the carrier.
        assertAcceptDetails(orderId, acceptWithQuery(token, "x=1", version("1"), pngSignature()),
                List.of(detail("x", NOT_ALLOWED)), "the query-string parameter x");
        assertAcceptDetails(orderId, acceptWithQuery(token, EXPECTED_VERSION_FIELD + "=1", version("1"), pngSignature()),
                List.of(detail(EXPECTED_VERSION_FIELD, NOT_ALLOWED)),
                "a query-string copy beside the form field: Not allowed. only, no Required.");
        assertAcceptDetails(orderId, acceptWithQuery(token, EXPECTED_VERSION_FIELD + "=7", version("1"), pngSignature()),
                List.of(detail(EXPECTED_VERSION_FIELD, NOT_ALLOWED)),
                "a different query-string value beside the current form value: Not allowed. only "
                        + "(never stale, never repeated)");
        assertAcceptDetails(orderId, acceptWithQuery(token, EXPECTED_VERSION_FIELD + "=1", pngSignature()),
                List.of(detail(EXPECTED_VERSION_FIELD, NOT_ALLOWED), detail(EXPECTED_VERSION_FIELD, REQUIRED)),
                "a query-string expected_version_number only: Not allowed. AND the form field Required.");
        assertAcceptDetails(orderId, acceptWithQuery(token, "expected%5Fversion%5Fnumber=1", pngSignature()),
                List.of(detail(EXPECTED_VERSION_FIELD, NOT_ALLOWED), detail(EXPECTED_VERSION_FIELD, REQUIRED)),
                "a percent-encoded query-string expected_version_number only");

        // Repeated parts.
        assertAcceptDetails(orderId, accept(token, version("1"), pngSignature(), pngSignature()),
                List.of(detail(SIGNATURE_PART, AT_MOST_ONCE)), "two signature parts");
        for (String[] values : List.of(new String[] {"1", "1"}, new String[] {"1", "abc"}, new String[] {"", "1"})) {
            assertAcceptDetails(orderId, accept(token, version(values), pngSignature()),
                    List.of(detail(EXPECTED_VERSION_FIELD, AT_MOST_ONCE)),
                    "expected_version_number repeated " + Arrays.toString(values));
        }

        // A missing or malformed value.
        assertAcceptDetails(orderId, accept(token, pngSignature()),
                List.of(detail(EXPECTED_VERSION_FIELD, REQUIRED)), "no expected_version_number at all");
        for (String value : List.of("", "   ", "0", "-3", "abc", "2.5")) {
            assertAcceptDetails(orderId, accept(token, version(value), pngSignature()),
                    List.of(detail(EXPECTED_VERSION_FIELD, POSITIVE_INTEGER)),
                    "expected_version_number '" + value + "'");
        }

        // Several offences at once: ONE 400 with every detail, in order (file parts, then the parameters in
        // request order - the query string first - then the repeated signature, then the version detail).
        assertAcceptDetails(orderId,
                acceptWithQuery(token, "x=1&" + EXPECTED_VERSION_FIELD + "=1", file(photoPart()),
                        file(textPart("scan", "scan")), field("name", FROZEN_NAME), field(ACCEPTED_NAME_FIELD, FROZEN_NAME),
                        field("declaration", "true"), pngSignature(), pngSignature(), version("0")),
                List.of(detail("photo", NOT_ALLOWED), detail("scan", NOT_ALLOWED), detail("x", NOT_ALLOWED),
                        detail(EXPECTED_VERSION_FIELD, NOT_ALLOWED), detail("name", NOT_ALLOWED),
                        detail(ACCEPTED_NAME_FIELD, NOT_ALLOWED), detail("declaration", NOT_ALLOWED),
                        detail(SIGNATURE_PART, AT_MOST_ONCE), detail(EXPECTED_VERSION_FIELD, POSITIVE_INTEGER)),
                "every kind of offence at once, with the form value 0 beside a query copy");
        assertAcceptDetails(orderId,
                accept(token, file(photoPart()), field("declaration", "true"), pngSignature(), pngSignature()),
                List.of(detail("photo", NOT_ALLOWED), detail("declaration", NOT_ALLOWED),
                        detail(SIGNATURE_PART, AT_MOST_ONCE), detail(EXPECTED_VERSION_FIELD, REQUIRED)),
                "unknown parts, a repeated signature and no expected_version_number");
        assertAcceptDetails(orderId, accept(token, field("name", FROZEN_NAME), version("1", "2")),
                List.of(detail("name", NOT_ALLOWED), detail(EXPECTED_VERSION_FIELD, AT_MOST_ONCE)),
                "an unknown field, no signature and a repeated expected_version_number: the 400, not the 422");

        // The body is answered before the order lock: a stale value with an unknown field is the 400, not 409.
        assertAcceptDetails(orderId, accept(token, version("7"), pngSignature(), field("declaration", "true")),
                List.of(detail("declaration", NOT_ALLOWED)), "a stale value with an unknown field");

        assertSignsRemotely(orderId, token, accept(token, version("1"), pngSignature()),
                "positive control: the clean two-part body");
    }

    /**
     * Stages 0 and 1 answer before the body (contract 9.4 steps 0 and 1): a POST that is not multipart (a
     * JSON body, or no body and no content type) is 415 with the code {@code VALIDATION_FAILED} through the
     * framework, even for a valid link; an unknown well-shaped token and a malformed one are the identical
     * 404 {@code INVOICE_TOKEN_NOT_FOUND} "Invoice not found." with a clean body and with an invalid one (the
     * body is never looked at). Nothing is written, and the real link then signs.
     */
    @Test
    void accept_theTransportAndTheTokenGateAnswerBeforeTheBody_nonMultipart415_unknownOrMalformedToken404_nothingWritten()
            throws Exception {
        long orderId = invoicedOrder();
        String token = sendLink(orderId);

        assertAcceptRefused(orderId, post(PUBLIC_INVOICES + token + "/accept").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version_number\": 1}"), 415, VALIDATION_FAILED, VALIDATION_FAILED_MESSAGE,
                "a JSON POST to a valid link");
        assertAcceptRefused(orderId, post(PUBLIC_INVOICES + token + "/accept"), 415, VALIDATION_FAILED,
                VALIDATION_FAILED_MESSAGE, "a POST with no body and no content type to a valid link");

        Map<String, String> badTokens = new LinkedHashMap<>();
        badTokens.put("an unknown well-shaped token", "A".repeat(43));
        badTokens.put("a malformed token (too short)", "not-a-real-invoice-token");
        for (Map.Entry<String, String> bad : badTokens.entrySet()) {
            assertAcceptRefused(orderId, accept(bad.getValue(), version("1"), pngSignature()), 404, TOKEN_NOT_FOUND,
                    TOKEN_NOT_FOUND_MESSAGE, bad.getKey() + " with a clean body");
            assertAcceptRefused(orderId, accept(bad.getValue(), field("declaration", "true"), file(photoPart())), 404,
                    TOKEN_NOT_FOUND, TOKEN_NOT_FOUND_MESSAGE, bad.getKey() + " with an invalid body");
        }

        assertSignsRemotely(orderId, token, accept(token, version("1"), pngSignature()), "positive control: the real link");
    }

    // ================================================================
    // 2. The signature rules (contract 9.4 step 2 and the PNG rules)
    // ================================================================

    /**
     * Only after a clean body: a missing signature part, an empty one (also when declared {@code image/jpeg}:
     * required before the type) is 422 {@code SIGNATURE_REQUIRED} with its default message; a declared type
     * other than {@code image/png}, 2,097,153 bytes, wrong magic, a truncated {@code IHDR}, an {@code IHDR}
     * length other than 13, a width or height of 8,193 px, 2,000 x 2,001 px (over 4,000,000 px), a 16-bit
     * depth, colour type 1 and an undecodable {@code IDAT} (garbage payload with a valid CRC; a stream cut
     * inside {@code IDAT}) are each 400 {@code SIGNATURE_INVALID} with its default message. The fixtures'
     * preconditions are checked (declared dimensions, bit depth, a decodable 16-bit image, a valid patched
     * CRC, a passing pre-decode header for the undecodable ones, every one under the size cap) so each
     * rejection is about the rule it names. A bad body still answers first (400 {@code VALIDATION_FAILED})
     * and the signature rules answer before the stale check. Nothing is written by any of them.
     */
    @Test
    void accept_signature_missingOrEmpty422_wrongTypeOversizeOrOutsideThePngRules400_onlyAfterACleanBody_nothingWritten()
            throws Exception {
        long orderId = invoicedOrder();
        String token = sendLink(orderId);

        assertAcceptRefused(orderId, accept(token, version("1")), 422, SIGNATURE_REQUIRED, SIGNATURE_REQUIRED_MESSAGE,
                "no signature part");
        assertAcceptRefused(orderId, accept(token, version("1"), signature(new byte[0])), 422, SIGNATURE_REQUIRED,
                SIGNATURE_REQUIRED_MESSAGE, "an empty signature part");
        assertAcceptRefused(orderId, accept(token, version("1"), file(new MockMultipartFile(SIGNATURE_PART, "signature.jpg",
                        "image/jpeg", new byte[0]))), 422, SIGNATURE_REQUIRED, SIGNATURE_REQUIRED_MESSAGE,
                "an empty signature part declared image/jpeg (required is checked before the type)");

        // The body rules answer first.
        assertAcceptDetails(orderId, accept(token, field("declaration", "true")),
                List.of(detail("declaration", NOT_ALLOWED), detail(EXPECTED_VERSION_FIELD, REQUIRED)),
                "no signature with a bad body: the 400, not the 422");
        assertAcceptDetails(orderId, accept(token, file(jpegSignature())), List.of(detail(EXPECTED_VERSION_FIELD, REQUIRED)),
                "a JPEG signature with no expected_version_number: the 400, not SIGNATURE_INVALID");
        assertAcceptDetails(orderId,
                accept(token, version("0"), signature(Arrays.copyOf(ONE_PIXEL_PNG, MAX_SIGNATURE_BYTES + 1))),
                List.of(detail(EXPECTED_VERSION_FIELD, POSITIVE_INTEGER)),
                "an oversized signature with the value 0: the 400, not SIGNATURE_INVALID");

        // Fixtures, each proven to break only the rule it names.
        byte[] stroke = strokeSignaturePng();
        Assertions.assertNotNull(ImageIO.read(new ByteArrayInputStream(stroke)), "the source PNG itself decodes");
        byte[] oneByteOver = Arrays.copyOf(ONE_PIXEL_PNG, MAX_SIGNATURE_BYTES + 1);
        byte[] notPng = "definitely not a png image".getBytes(StandardCharsets.US_ASCII);
        byte[] magicThenGarbage = pngMagicFollowedByGarbage();
        byte[] truncatedIhdr = Arrays.copyOf(ONE_PIXEL_PNG, 20);
        byte[] ihdrLength14 = withIhdrLength(ONE_PIXEL_PNG, 14);
        Assertions.assertEquals(14, ByteBuffer.wrap(ihdrLength14).getInt(PNG_MAGIC_LENGTH), "the IHDR declares 14 bytes");
        byte[] tooWide = pngOfSize(8_193, 1, BufferedImage.TYPE_INT_RGB);
        Assertions.assertArrayEquals(new int[] {8_193, 1}, declaredPngDimensions(tooWide));
        byte[] tooTall = pngOfSize(1, 8_193, BufferedImage.TYPE_INT_RGB);
        Assertions.assertArrayEquals(new int[] {1, 8_193}, declaredPngDimensions(tooTall));
        byte[] tooManyPixels = pngOfSize(2_000, 2_001, BufferedImage.TYPE_BYTE_BINARY);
        Assertions.assertArrayEquals(new int[] {2_000, 2_001}, declaredPngDimensions(tooManyPixels));
        Assertions.assertTrue(2_000L * 2_001L > MAX_SIGNATURE_PIXELS, "fixture: over the pixel cap, under the side cap");
        byte[] sixteenBit = pngOfSize(4, 4, BufferedImage.TYPE_USHORT_GRAY);
        Assertions.assertEquals(16, sixteenBit[IHDR_BIT_DEPTH_OFFSET] & 0xFF, "the IHDR declares bit depth 16");
        Assertions.assertNotNull(ImageIO.read(new ByteArrayInputStream(sixteenBit)),
                "the 16-bit PNG decodes: only the bit-depth rule can reject it");
        byte[] colourTypeOne = withIhdrByte(ONE_PIXEL_PNG, IHDR_COLOUR_TYPE_OFFSET, 1);
        Assertions.assertTrue(chunkCrcValid(colourTypeOne, PNG_MAGIC_LENGTH), "the patched IHDR keeps a valid CRC");
        byte[] garbageIdat = withGarbageIdatPayload(stroke);
        byte[] truncatedIdat = truncatedInsideIdat(stroke);
        for (byte[] undecodable : List.of(garbageIdat, truncatedIdat)) {
            assertPreDecodeHeaderPasses(undecodable, "an undecodable-IDAT fixture");
            assertImageIoCannotDecode(undecodable, "an undecodable-IDAT fixture");
        }
        for (byte[] small : List.of(notPng, magicThenGarbage, truncatedIhdr, ihdrLength14, tooWide, tooTall, tooManyPixels,
                sixteenBit, colourTypeOne, garbageIdat, truncatedIdat)) {
            Assertions.assertTrue(small.length < MAX_SIGNATURE_BYTES, "fixture: the rejection is never about the size");
        }

        Map<String, MockMultipartFile> invalid = new LinkedHashMap<>();
        invalid.put("declared image/jpeg (valid PNG bytes)", jpegSignature());
        invalid.put("a valid PNG padded after IEND to 2,097,153 bytes", signatureFile(oneByteOver));
        invalid.put("wrong magic (not PNG bytes, declared image/png)", signatureFile(notPng));
        invalid.put("the PNG magic followed by garbage (no IHDR)", signatureFile(magicThenGarbage));
        invalid.put("a truncated IHDR (20 bytes)", signatureFile(truncatedIhdr));
        invalid.put("an IHDR length of 14, not 13", signatureFile(ihdrLength14));
        invalid.put("width 8,193 px", signatureFile(tooWide));
        invalid.put("height 8,193 px", signatureFile(tooTall));
        invalid.put("2,000 x 2,001 px (4,002,000 px)", signatureFile(tooManyPixels));
        invalid.put("bit depth 16", signatureFile(sixteenBit));
        invalid.put("colour type 1 (IHDR byte patched, CRC recomputed)", signatureFile(colourTypeOne));
        invalid.put("an undecodable IDAT (garbage payload, valid CRC)", signatureFile(garbageIdat));
        invalid.put("a stream cut half-way through IDAT", signatureFile(truncatedIdat));
        for (Map.Entry<String, MockMultipartFile> invalidCase : invalid.entrySet()) {
            JsonNode error = assertAcceptRefused(orderId, accept(token, version("1"), file(invalidCase.getValue())), 400,
                    SIGNATURE_INVALID, SIGNATURE_INVALID_MESSAGE, invalidCase.getKey());
            Assertions.assertFalse(error.has("details"), () -> invalidCase.getKey() + ": no details: " + error);
        }

        // The signature rules run before the stage-3 checks: an invalid PNG with a stale value is still 400.
        assertAcceptRefused(orderId, accept(token, version("9"), signature(magicThenGarbage)), 400, SIGNATURE_INVALID,
                SIGNATURE_INVALID_MESSAGE, "an invalid PNG with a stale value");

        assertSignsRemotely(orderId, token, accept(token, version("1"), pngSignature()), "positive control: a valid PNG");
    }

    /**
     * The size boundary: a valid signature-pad PNG carrying an ancillary {@code tEXt} chunk, padded with
     * zeros after {@code IEND} to EXACTLY 2,097,152 bytes, signs (201). Only the server-normalised PNG is
     * stored: {@code signature-{order_number}-v2.png}, {@code image/png}, its {@code file_size} the stored
     * length (far below the upload's), no {@code tEXt} payload, no bytes after {@code IEND}, and the same
     * dimensions and pixels as the upload.
     */
    @Test
    void accept_validPngPaddedToExactly2097152Bytes_signs_andOnlyTheServerNormalisedPngIsStored() throws Exception {
        long orderId = invoicedOrder();
        String token = sendLink(orderId);
        byte[] upload = withTextChunk(strokeSignaturePng(), "Comment", TEXT_CHUNK_MARKER);
        Assertions.assertTrue(pngChunkTypes(upload, "upload").contains("tEXt"), "fixture: the upload carries a tEXt chunk");
        byte[] exactlyTwoMiB = Arrays.copyOf(upload, MAX_SIGNATURE_BYTES);
        Assertions.assertEquals(2_097_152, exactlyTwoMiB.length, "fixture: exactly the largest allowed upload");

        Signing signing = assertSignsRemotely(orderId, token, accept(token, version("1"), signature(exactlyTwoMiB)),
                "a signature of exactly 2,097,152 bytes");

        Map<String, Object> signatureFile = signatureFileRow(signing.invoiceId());
        Assertions.assertEquals("signature-" + orderNumber(orderId) + "-v2.png", signatureFile.get("file_name"),
                "the signature's stored file name");
        Assertions.assertEquals("image/png", signatureFile.get("mime_type"), "the signature's mime type");
        byte[] stored = diskBytes(orderId, (String) signatureFile.get("storage_path"));
        Assertions.assertEquals(stored.length, asLong(signatureFile.get("file_size")),
                "stored_file.file_size is the stored (normalised) length, never the 2 MiB upload size");
        Assertions.assertTrue(stored.length < exactlyTwoMiB.length / 100,
                "the bytes after IEND are not stored: " + stored.length + " bytes");
        Assertions.assertFalse(new String(stored, StandardCharsets.ISO_8859_1).contains(TEXT_CHUNK_MARKER),
                "the uploaded tEXt payload is not stored");
        assertNormalisedSignature(exactlyTwoMiB, stored, "the 2,097,152-byte upload");
    }

    // ================================================================
    // 3. The stale comparison under the lock (contract 9.4 step 3.3, 5.2)
    // ================================================================

    /**
     * The link was sent while v1 was current; a payment then appended v2. The authoritative comparison under
     * the order lock refuses every value other than the current version with 409 {@code INVOICE_VERSION_STALE}
     * ("Invoice updated, please refresh", no details): 1 (lower: the version the page showed before the
     * payment), 3 (higher: not yet existing), a forged {@code Long.MAX_VALUE} and a forged 2^32 + 2 (2 only if
     * truncated to an int). Nothing is written and the token stays {@code ACTIVE}; the current version 2 then
     * signs (v3).
     */
    @Test
    void accept_staleExpectedVersion_belowOrAboveTheCurrent_returns409InvoiceVersionStale_nothingWritten_linkStaysActive()
            throws Exception {
        long orderId = invoicedOrder();
        String token = sendLink(orderId);
        recordCashPayment(orderId, "30.00");
        Assertions.assertEquals(2, currentVersion(orderId), "fixture: the payment appended the current version v2");
        Assertions.assertEquals("ACTIVE", tokenRow(token).get("status"), "fixture: a payment never kills the link");

        Map<String, Long> staleValues = new LinkedHashMap<>();
        staleValues.put("1, the version shown before the payment (lower)", 1L);
        staleValues.put("3, a version that does not exist yet (higher)", 3L);
        staleValues.put("a forged Long.MAX_VALUE", Long.MAX_VALUE);
        staleValues.put("a forged 2^32 + 2 (2 only if truncated to an int)", (1L << 32) + 2);
        for (Map.Entry<String, Long> stale : staleValues.entrySet()) {
            JsonNode error = assertAcceptRefused(orderId,
                    accept(token, version(String.valueOf(stale.getValue())), pngSignature()), 409, STALE, STALE_MESSAGE,
                    "stale " + stale.getKey());
            Assertions.assertFalse(error.has("details"), () -> "the stale 409 carries no details: " + error);
        }

        Signing signing = assertSignsRemotely(orderId, token, accept(token, version("2"), pngSignature()),
                "positive control: the current version 2");
        Assertions.assertEquals(3, signing.versionNumber(), "the signing version follows the current version");
    }

    // ================================================================
    // 4. The frozen accepted name (contract 9.4 step 3.5)
    // ================================================================

    /**
     * The current version's frozen {@code customer_name_snapshot} NULL, empty, spaces only or tabs / newlines
     * only (set by SQL): 422 {@code ACCEPTED_CUSTOMER_NAME_REQUIRED} with the customer-neutral sentence (never
     * the staff default), no details, and nothing written, although the live saved customer still has a name
     * (it never fills in). The stale comparison answers before it. With a frozen name back, the same request
     * signs.
     */
    @Test
    void accept_frozenNameNullOrBlank_returns422WithTheCustomerNeutralMessage_nothingWritten_theLiveNameNeverFillsIn()
            throws Exception {
        long orderId = invoicedOrder();
        long v1 = invoiceId(orderId, 1);
        String token = sendLink(orderId);
        Assertions.assertEquals(FROZEN_NAME, savedCustomerName(orderId), "fixture: the live saved customer has a name");

        for (String blank : Arrays.asList(null, "", "   ", "\t\n ")) {
            setFrozenName(v1, blank);
            String label = "frozen name " + (blank == null ? "NULL"
                    : "'" + blank.replace("\t", "\\t").replace("\n", "\\n") + "'");
            JsonNode error = assertAcceptRefused(orderId, accept(token, version("1"), pngSignature()), 422, NAME_REQUIRED,
                    CONTACT_STORE_MESSAGE, label);
            Assertions.assertFalse(error.has("details"), () -> label + ": no details: " + error);
        }

        setFrozenName(v1, null);
        assertAcceptRefused(orderId, accept(token, version("2"), pngSignature()), 409, STALE, STALE_MESSAGE,
                "a NULL frozen name with a stale value: the stale comparison answers first");

        setFrozenName(v1, FROZEN_NAME);
        assertSignsRemotely(orderId, token, accept(token, version("1"), pngSignature()),
                "positive control: the same version with a frozen name");
    }

    /**
     * A frozen name of 260 characters with inner spaces (far over the former 150-character limit), planted on
     * the current version and different from the live saved customer, IS the accepted name: the appended row's
     * {@code accepted_customer_name} and {@code customer_name_snapshot} hold it verbatim and in full (260
     * characters), InvoiceDetail returns it, the signed PDF's caption carries it (whitespace-normalised, as the
     * PDF wraps it) and never the live name, and the store notification names it verbatim.
     */
    @Test
    void accept_frozenNameOf260Characters_isTheAcceptedNameVerbatimAndInFull_neverTheLiveCustomerName() throws Exception {
        Assertions.assertEquals(260, LONG_FROZEN_NAME.length(), "fixture: a 260-character frozen name");
        Assertions.assertTrue(LONG_FROZEN_NAME.contains(" "), "fixture: the name has inner spaces");
        long orderId = invoicedOrder();
        setFrozenName(invoiceId(orderId, 1), LONG_FROZEN_NAME);
        String token = sendLink(orderId);
        Assertions.assertNotEquals(LONG_FROZEN_NAME, savedCustomerName(orderId), "fixture: the live saved name differs");
        Assertions.assertFalse(LONG_FROZEN_NAME.contains(FROZEN_NAME), "fixture: the live name is not inside the long name");

        Signing signing = assertSignsRemotely(orderId, token, accept(token, version("1"), pngSignature()),
                "a 260-character frozen name");

        Map<String, Object> v2 = invoiceRow(signing.invoiceId());
        Assertions.assertEquals(LONG_FROZEN_NAME, v2.get("accepted_customer_name"),
                "invoice.accepted_customer_name: the frozen name verbatim and in full");
        Assertions.assertEquals(260, count("SELECT char_length(accepted_customer_name) FROM invoice WHERE invoice_id = ?",
                signing.invoiceId()), "the stored accepted name is untruncated");
        Assertions.assertEquals(LONG_FROZEN_NAME, v2.get("customer_name_snapshot"), "the frozen Invoice To name is copied");
        Assertions.assertEquals(LONG_FROZEN_NAME, getCurrent(orderId).path("accepted_customer_name").asText(),
                "InvoiceDetail returns the accepted name in full");

        byte[] signedPdf = pdfOnDisk(signing.invoiceId());
        assertAcceptedCaption(signedPdf, LONG_FROZEN_NAME, acceptedAt(signing.invoiceId()), "the signed PDF");
        assertTextLacks(readingOrderText(signedPdf), "Accepted by " + FROZEN_NAME,
                "the signed PDF (the live name is never the caption)");

        List<InvoiceAcceptanceNotificationRequest> notifications = notificationSender.sentNotifications();
        Assertions.assertEquals(1, notifications.size(), "one store notification");
        String notificationBody = notifications.get(0).bodyText();
        Assertions.assertTrue(notificationBody.contains("was accepted online by " + LONG_FROZEN_NAME + " on "),
                () -> "the notification names the frozen name verbatim: " + notificationBody);
        Assertions.assertFalse(notificationBody.contains(FROZEN_NAME), "the notification never names the live customer");
    }

    // ================================================================
    // 5. The persisted signing version (contract 10.1, 10.2, 10.5)
    // ================================================================

    /**
     * A remote signing of the current version v2 (a payment version by the order user, carrying D.1's frozen,
     * sanitised SOFT terms and, planted by SQL, a non-null source quote version of the same order; the
     * business terms are then edited live) appends v3 and nothing else of the invoice: v3 copies every
     * carried column from v2 verbatim (invoice and due date, details, ex / inc, paid, balance, source quote
     * version, the sanitised terms - never the edited live terms - and the three identity columns);
     * {@code accepted_at} is the signing time; the accepted name is v2's frozen name; the signature is the
     * normalised upload ({@code tEXt} and trailing bytes dropped) stored as
     * {@code signature-{order}-v3.png}; the signed PDF is stored as {@code invoice-{order}-v3.pdf}
     * (the bytes on disk, emailed verbatim); the creator is the link's {@code sent_by_user_id} (the sender,
     * not the order user); {@code last_emailed_at} is stamped after the recorded delivery and equals the
     * mirror; the presented token is {@code SIGNED} with {@code dead_at} = {@code accepted_at}; the ISSUED
     * quote version and its {@code ACTIVE} quote link are {@code CANCELLED} at the signing time (D5(c)); the
     * order row (apart from the mirror), its lines, the quote draft and the payments are unchanged; v1 and v2
     * are untouched. The 201 body is exactly the contract's. InvoiceDetail reports the signed v3 (creator,
     * name, {@code QUOTE} terms, no live link), and the link is dead: GET is the minimal {@code SIGNED} payload,
     * accept (with a valid and with an invalid body: the token gate answers first) and pdf are 410
     * {@code INVOICE_LINK_SIGNED}.
     */
    @Test
    void accept_appendsTheNextVersionCopiedVerbatimFromTheCurrentOne_createdByTheLinkSender_tokenSignedAtTheSigningTime()
            throws Exception {
        long orderId = invoicedOrder();
        recordCashPayment(orderId, "30.00");
        saveQuoteDraft(orderId, "132.00");
        long quoteVersionId = sendQuoteEmail(orderId);
        String quoteToken = lastQuoteToken();
        long v1 = invoiceId(orderId, 1);
        long v2 = invoiceId(orderId, 2);
        Assertions.assertEquals(1, jdbcTemplate.update(
                        "UPDATE invoice SET source_quote_version_id = ? WHERE invoice_id = ?", quoteVersionId, v2),
                "fixture: the current (payment) version carries a source quote version of this order");
        clearJpaCache();
        String token = sendLink(orderId);
        String orderNumber = orderNumber(orderId);

        Map<String, Object> v1Before = invoiceRow(v1);
        Map<String, Object> v2Before = invoiceRow(v2);
        String frozenTerms = (String) v2Before.get("terms_snapshot");
        Assertions.assertNotNull(frozenTerms, "fixture: v2 carries D.1's frozen SOFT terms");
        Assertions.assertEquals(v1Before.get("terms_snapshot"), frozenTerms, "fixture: terms carried by the payment");
        Assertions.assertNotEquals(SOFT_TERMS, frozenTerms,
                "fixture: the frozen terms are the SANITISED value, not the raw one");
        Assertions.assertTrue(frozenTerms.contains(SOFT_TERMS_TEXT) && !frozenTerms.contains("script")
                && !frozenTerms.contains("alert"), () -> "fixture: the sanitiser dropped the script: " + frozenTerms);
        // The business terms are edited live after the send: a signing version copies, it never re-reads.
        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE business SET terms_soft = ? WHERE business_id = ?",
                EDITED_SOFT_TERMS, businessId), "fixture: edit the live business terms after the send");
        clearJpaCache();
        Assertions.assertEquals(quoteVersionId, asLong(v2Before.get("source_quote_version_id")),
                "fixture: the planted source");
        Assertions.assertEquals(orderUserId, asLong(v2Before.get("created_by_user_id")), "fixture: v2's creator is PA1");
        assertMoney("30.00", v2Before.get("total_paid"), "fixture: v2 is the payment's version");
        Map<String, Object> tokenBefore = tokenRow(token);
        Assertions.assertEquals(senderUserId, asLong(tokenBefore.get("sent_by_user_id")), "fixture: PA2 sent the link");
        Assertions.assertNotEquals(orderUserId, senderUserId, "fixture: the sender is not the order user");
        Map<String, Object> orderBefore = orderRowWithoutMirror(orderId);
        Assertions.assertNull(orderLastEmailedAt(orderId), "fixture: the mirror is null (v2 never emailed)");
        List<Map<String, Object>> chargeLinesBefore = orderChildRows("order_charge_line", orderId);
        List<Map<String, Object>> productLinesBefore = orderChildRows("order_product_line", orderId);
        List<Map<String, Object>> draftBefore = orderChildRows("quote_draft", orderId);
        List<Map<String, Object>> draftLinesBefore = draftLineRows(orderId);
        List<Map<String, Object>> paymentsBefore = orderChildRows("payment_transaction", orderId);
        Assertions.assertEquals(1, draftBefore.size(), "fixture: the order has a quote draft");
        Assertions.assertEquals(1, paymentsBefore.size(), "fixture: the order has one payment");
        Assertions.assertEquals("ISSUED", quoteVersionRow(quoteVersionId).get("status"), "fixture: an ISSUED quote version");
        Assertions.assertEquals("ACTIVE", quoteTokenRow(quoteToken).get("status"), "fixture: its ACTIVE quote link");

        // A signature-pad PNG with an ancillary tEXt chunk and bytes after IEND: only its pixels may be stored.
        byte[] withText = withTextChunk(strokeSignaturePng(), "Comment", TEXT_CHUNK_MARKER);
        byte[] upload = Arrays.copyOf(withText, withText.length + 512);

        Signing signing = assertSignsRemotely(orderId, token, accept(token, version("2"), signature(upload)),
                "remote signing of v2");
        Assertions.assertEquals(3, signing.versionNumber(), "the signing version is current + 1");
        long v3 = signing.invoiceId();
        Map<String, Object> v3Row = invoiceRow(v3);

        // The carried columns, verbatim from the current version.
        for (String column : CARRIED_COLUMNS) {
            Assertions.assertEquals(v2Before.get(column), v3Row.get(column),
                    () -> "the signing version copies " + column + " from the current version");
        }
        Assertions.assertEquals(quoteVersionId, asLong(v3Row.get("source_quote_version_id")),
                "the non-null source is carried");
        Assertions.assertEquals(frozenTerms, v3Row.get("terms_snapshot"),
                "the frozen, sanitised terms are carried verbatim (never the edited live terms)");

        // Acceptance: the signing time and the frozen name.
        LocalDateTime acceptedAt = asLocalDateTime(v3Row.get("accepted_at"));
        assertWithin(acceptedAt, signing.started(), signing.ended(), "accepted_at is the signing time");
        Assertions.assertEquals(FROZEN_NAME, v3Row.get("accepted_customer_name"), "the accepted name is v2's frozen name");
        Assertions.assertEquals(v2Before.get("customer_name_snapshot"), v3Row.get("accepted_customer_name"),
                "the accepted name IS the current version's customer_name_snapshot");

        // The normalised signature.
        Map<String, Object> signatureFile = storedFileRow(v3Row.get("accepted_signature_file_id"));
        Assertions.assertEquals("signature-" + orderNumber + "-v3.png", signatureFile.get("file_name"),
                "signature file name");
        Assertions.assertEquals("image/png", signatureFile.get("mime_type"), "signature mime type");
        byte[] storedSignature = diskBytes(orderId, (String) signatureFile.get("storage_path"));
        Assertions.assertEquals(storedSignature.length, asLong(signatureFile.get("file_size")), "signature file_size");
        Assertions.assertFalse(new String(storedSignature, StandardCharsets.ISO_8859_1).contains(TEXT_CHUNK_MARKER),
                "the ancillary tEXt payload is not stored");
        assertNormalisedSignature(upload, storedSignature, "the stored signature");

        // The signed PDF.
        Map<String, Object> pdfFile = storedFileRow(jdbcTemplate.queryForObject(
                "SELECT stored_file_id FROM invoice WHERE invoice_id = ?", Long.class, v3));
        Assertions.assertEquals("invoice-" + orderNumber + "-v3.pdf", pdfFile.get("file_name"), "signed PDF file name");
        Assertions.assertEquals("application/pdf", pdfFile.get("mime_type"), "signed PDF mime type");
        byte[] storedPdf = diskBytes(orderId, (String) pdfFile.get("storage_path"));
        Assertions.assertEquals(storedPdf.length, asLong(pdfFile.get("file_size")), "signed PDF file_size");
        Assertions.assertEquals("%PDF-", new String(storedPdf, 0, 5, StandardCharsets.US_ASCII), "PDF header");
        assertAcceptedCaption(storedPdf, FROZEN_NAME, acceptedAt, "the stored signed PDF");
        Assertions.assertTrue(noSpace(documentText(storedPdf)).contains(noSpace(SOFT_TERMS_TEXT)),
                "the signed PDF renders the carried terms");
        assertTextLacks(documentText(storedPdf), EDITED_SOFT_TERMS_TEXT, "the signed PDF (never the edited live terms)");

        // The creator is the staff member who sent the link.
        Assertions.assertEquals(asLong(tokenBefore.get("sent_by_user_id")), asLong(v3Row.get("created_by_user_id")),
                "created_by_user_id = invoice_token.sent_by_user_id");
        Assertions.assertEquals(senderUserId, asLong(v3Row.get("created_by_user_id")), "the creator is PA2, the sender");

        // The markers: stamped after the recorded delivery, the mirror equal.
        Timestamp stamp = (Timestamp) v3Row.get("last_emailed_at");
        Assertions.assertNotNull(stamp, "the appended version's last_emailed_at is stamped after the delivery");
        Assertions.assertEquals(stamp, orderLastEmailedAt(orderId), "the mirror equals the appended version's stamp");
        assertWithin(stamp.toLocalDateTime(), signing.started(), signing.ended(), "the stamp is the delivery time");
        Assertions.assertFalse(stamp.toLocalDateTime().isBefore(acceptedAt), "stamped after the signing");
        List<InvoiceEmailRequest> emails = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, emails.size(), "one signed-PDF email");
        Assertions.assertEquals(3, emails.get(0).invoiceVersionNumber(), "the email carries the appended version");
        Assertions.assertArrayEquals(storedPdf, emails.get(0).pdfBytes(), "the emailed PDF is the stored signed PDF");

        // The presented token: SIGNED at the signing time.
        Map<String, Object> tokenAfter = tokenRow(token);
        Assertions.assertEquals("SIGNED", tokenAfter.get("status"), "the presented link is SIGNED");
        Assertions.assertEquals(v3Row.get("accepted_at"), tokenAfter.get("dead_at"), "dead_at = accepted_at");

        // D5(c): the ISSUED quote version and its ACTIVE quote link are cancelled at the signing time.
        Assertions.assertEquals("CANCELLED", quoteVersionRow(quoteVersionId).get("status"), "D5(c): the quote version");
        Map<String, Object> quoteTokenAfter = quoteTokenRow(quoteToken);
        Assertions.assertEquals("CANCELLED", quoteTokenAfter.get("status"), "D5(c): the quote link");
        Assertions.assertEquals(v3Row.get("accepted_at"), quoteTokenAfter.get("dead_at"), "D5(c): at the signing time");

        // Nothing else: no order price, status, line, draft or payment write; earlier versions untouched.
        Assertions.assertEquals(orderBefore, orderRowWithoutMirror(orderId), "the order row (apart from the mirror)");
        Assertions.assertEquals(chargeLinesBefore, orderChildRows("order_charge_line", orderId), "the order's charge lines");
        Assertions.assertEquals(productLinesBefore, orderChildRows("order_product_line", orderId),
                "the order's product lines");
        Assertions.assertEquals(draftBefore, orderChildRows("quote_draft", orderId), "the quote draft");
        Assertions.assertEquals(draftLinesBefore, draftLineRows(orderId), "the quote draft lines");
        Assertions.assertEquals(paymentsBefore, orderChildRows("payment_transaction", orderId), "the payments");
        Assertions.assertEquals(v1Before, invoiceRow(v1), "v1 is untouched");
        Assertions.assertEquals(v2Before, invoiceRow(v2), "v2 is untouched");

        // InvoiceDetail of the signed version.
        JsonNode detail = getCurrent(orderId);
        Assertions.assertEquals(3, detail.path("version_number").asInt(), "D.3: the signed v3 is current");
        Assertions.assertEquals(FROZEN_NAME, detail.path("accepted_customer_name").asText(), "D.3: the accepted name");
        Assertions.assertTrue(detail.path("accepted_signature_present").asBoolean(), "D.3: signed");
        Assertions.assertEquals(senderUserId, detail.path("created_by_user_id").asLong(), "D.3: the creator is the sender");
        Assertions.assertEquals("QUOTE", detail.path("terms_source").asText(), "D.3: a carried source reports QUOTE");
        Assertions.assertEquals(frozenTerms, detail.path("terms_html").asText(), "D.3: the carried terms");
        Assertions.assertTrue(detail.has("invoice_link_expires_at") && detail.get("invoice_link_expires_at").isNull(),
                () -> "D.3: no live link after the signing: " + detail);
        Assertions.assertEquals(v2Before.get("invoice_date").toString(), detail.path("invoice_date").asText(),
                "D.3: the carried invoice date");

        // The link is dead.
        assertDeadView(publicGet(token, "the public GET after the signing"), "SIGNED", LINK_SIGNED_MESSAGE,
                "the public GET after the signing");
        JsonNode again = assertAcceptRefused(orderId, accept(token, version("3"), pngSignature()), 410, LINK_SIGNED,
                LINK_SIGNED_MESSAGE, "a second accept through the signed link");
        Assertions.assertFalse(again.has("details"), "the 410 carries no details");
        assertAcceptRefused(orderId, accept(token, field("declaration", "true"), file(photoPart())), 410, LINK_SIGNED,
                LINK_SIGNED_MESSAGE, "the signed link with an invalid body: the token gate answers before the body");
        assertGone(publicCall(get(PUBLIC_INVOICES + token + "/pdf").param("version_number", "3"), 410,
                "the public pdf after the signing"), LINK_SIGNED, LINK_SIGNED_MESSAGE, "the public pdf after the signing");
    }

    /**
     * The words are copied VERBATIM, nulls included: the current version carries a null
     * {@code terms_snapshot}, null billing lines and a padded frozen name (planted by SQL) while the live
     * customer, BILLING address and business terms are all non-null. The signing version copies every
     * carried column (null terms, null lines, null source, the padded name), the accepted name is the padded
     * name verbatim, InvoiceDetail reports {@code BUSINESS} with null {@code terms_html}, and the signed PDF
     * shows neither the business's terms nor the live billing address (no live fallback).
     */
    @Test
    void accept_copiesTheCurrentVersionsWordsVerbatim_nullTermsAndBillingLinesAndAPaddedName_neverTheLiveCustomerOrTerms()
            throws Exception {
        long orderId = invoicedOrder();
        long v1 = invoiceId(orderId, 1);
        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE invoice SET terms_snapshot = NULL, "
                        + "customer_address_line1_snapshot = NULL, customer_address_line2_snapshot = NULL, "
                        + "customer_name_snapshot = ? WHERE invoice_id = ?", PADDED_FROZEN_NAME, v1),
                "fixture: null terms and billing lines and a padded frozen name on the current version");
        clearJpaCache();
        String token = sendLink(orderId);
        Map<String, Object> v1Before = invoiceRow(v1);
        Assertions.assertNull(v1Before.get("source_quote_version_id"), "fixture: a Path B version has no source");
        Assertions.assertNotNull(jdbcTemplate.queryForObject("SELECT terms_soft FROM business WHERE business_id = ?",
                String.class, businessId), "fixture: the business still has SOFT terms");

        Signing signing = assertSignsRemotely(orderId, token, accept(token, version("1"), pngSignature()),
                "remote signing of a version with null words");
        Map<String, Object> v2 = invoiceRow(signing.invoiceId());
        for (String column : CARRIED_COLUMNS) {
            Assertions.assertEquals(v1Before.get(column), v2.get(column),
                    () -> "the signing version copies " + column + " verbatim, null included");
        }
        Assertions.assertNull(v2.get("terms_snapshot"), "null terms stay null (no live fallback)");
        Assertions.assertNull(v2.get("customer_address_line1_snapshot"), "a null billing line 1 stays null");
        Assertions.assertNull(v2.get("customer_address_line2_snapshot"), "a null billing line 2 stays null");
        Assertions.assertNull(v2.get("source_quote_version_id"), "a null source stays null");
        Assertions.assertEquals(PADDED_FROZEN_NAME, v2.get("customer_name_snapshot"), "the padded name is copied verbatim");
        Assertions.assertEquals(PADDED_FROZEN_NAME, v2.get("accepted_customer_name"),
                "the accepted name is verbatim, never trimmed");

        JsonNode detail = getCurrent(orderId);
        Assertions.assertEquals("BUSINESS", detail.path("terms_source").asText(), "D.3: no source reports BUSINESS");
        Assertions.assertTrue(detail.get("terms_html").isNull(), () -> "D.3: null terms_html: " + detail);
        Assertions.assertEquals(PADDED_FROZEN_NAME, detail.path("customer_name_snapshot").asText(), "D.3: the padded name");
        Assertions.assertTrue(detail.get("customer_address_line1_snapshot").isNull(), "D.3: a null billing line 1");
        Assertions.assertTrue(detail.get("customer_address_line2_snapshot").isNull(), "D.3: a null billing line 2");
        Assertions.assertEquals(PADDED_FROZEN_NAME, detail.path("accepted_customer_name").asText(),
                "D.3: the accepted name");

        byte[] signedPdf = pdfOnDisk(signing.invoiceId());
        String text = documentText(signedPdf);
        assertTextLacks(text, SOFT_TERMS_TEXT, "the signed PDF (null terms render no terms)");
        assertTextLacks(text, LIVE_BILLING_STREET, "the signed PDF (the live billing street never fills in)");
        assertTextLacks(text, LIVE_BILLING_SUBURB, "the signed PDF (the live billing suburb never fills in)");
        assertAcceptedCaption(signedPdf, PADDED_FROZEN_NAME, acceptedAt(signing.invoiceId()), "the signed PDF");
    }

    // ================================================================
    // 6. Cross-day signing (contract 10.1, 4.2)
    // ================================================================

    /**
     * The current version v1 is dated D1 = today - 9 days (by SQL) and is signed today (D2). The public page
     * shows D1; the appended v2 stores {@code invoice_date} = D1 and InvoiceDetail returns D1; the stored
     * signed PDF prints D1 (and the carried due date) in the Issued / Due block and never D2 there, while the
     * caption "Accepted by {name} on {dd/MM/yyyy HH:mm}" carries the D2 signing time; {@code accepted_at} is
     * the signing time, on D2. The EMAILED attachment is byte-identical to the stored signed PDF and so
     * prints D1 as well.
     */
    @Test
    void accept_crossDay_theSigningVersionKeepsD1_theStoredAndEmailedPdfPrintD1_theCaptionCarriesTheD2SigningTime()
            throws Exception {
        long orderId = invoicedOrder();
        long v1 = invoiceId(orderId, 1);
        LocalDate d1 = LocalDate.now().minusDays(9);
        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE invoice SET invoice_date = ? WHERE invoice_id = ?",
                Date.valueOf(d1), v1), "fixture: date the current version D1");
        clearJpaCache();
        String token = sendLink(orderId);
        LocalDate dueDate = ((Date) invoiceRow(v1).get("due_date")).toLocalDate();
        Assertions.assertEquals(layDate.minusDays(2), dueDate, "fixture: D.1 set the due date two days before the lay date");
        JsonNode view = publicGet(token, "the public GET before the signing").path("data");
        Assertions.assertEquals(d1.toString(), view.path("invoice_date").asText(),
                "the page shows the current version's D1");

        Signing signing = assertSignsRemotely(orderId, token, accept(token, version("1"), pngSignature()),
                "a cross-day remote signing");

        Map<String, Object> v2 = invoiceRow(signing.invoiceId());
        Assertions.assertEquals(d1, ((Date) v2.get("invoice_date")).toLocalDate(),
                "the signing version keeps the current version's invoice_date D1");
        Assertions.assertEquals(d1.toString(), getCurrent(orderId).path("invoice_date").asText(), "D.3 invoice_date = D1");

        LocalDateTime acceptedAt = acceptedAt(signing.invoiceId());
        assertWithin(acceptedAt, signing.started(), signing.ended(), "accepted_at is the signing time");
        LocalDate d2 = acceptedAt.toLocalDate();
        Assertions.assertTrue(!d2.isBefore(signing.started().toLocalDate()) && !d2.isAfter(signing.ended().toLocalDate()),
                () -> "accepted_at is on the signing day: " + acceptedAt);
        Assertions.assertTrue(d1.isBefore(d2), "fixture: D1 is before the signing day D2");

        byte[] storedPdf = pdfOnDisk(signing.invoiceId());
        String issuedDue = issuedDueBlock(storedPdf);
        Assertions.assertEquals(DISPLAY_DATE.format(d1) + "Due" + DISPLAY_DATE.format(dueDate), issuedDue,
                "the stored signed PDF prints D1 (and the carried due date) where the invoice date is printed");
        Assertions.assertFalse(issuedDue.contains(DISPLAY_DATE.format(d2)),
                () -> "the signing day D2 is never printed as the invoice date: " + issuedDue);
        assertAcceptedCaption(storedPdf, FROZEN_NAME, acceptedAt, "the stored signed PDF (the D2 signing time)");

        List<InvoiceEmailRequest> sent = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size(), "one signed-PDF email");
        Assertions.assertEquals(2, sent.get(0).invoiceVersionNumber(), "the email carries the appended version");
        Assertions.assertArrayEquals(storedPdf, sent.get(0).pdfBytes(),
                "the emailed attachment is byte-identical to the stored signed PDF");
        Assertions.assertEquals(issuedDue, issuedDueBlock(sent.get(0).pdfBytes()), "the emailed PDF prints D1 too");
    }

    // ================================================================
    // 7. After the signing: the customer email and the store notification (contract 10.3, 10.4, 11)
    // ================================================================

    /**
     * There is NO customer-email gate on the remote accept: after the link was sent (to a valid email), the
     * saved email made app-invalid (DB-valid, no interior dot), or the customer row removed altogether (no
     * saved email at all; a blank email cannot exist in the schema), still signs (201). The customer's
     * signed-PDF email is SKIPPED: nothing is sent or attempted, both markers stay null (a stale non-null
     * mirror planted before the signing is reset to the new version's null), and exactly one WARN with the
     * exact text (order id and version only, no throwable) goes to the PublicInvoiceService logger. The store
     * notification is still sent, to the store's email, for the signed version, and stamps no marker.
     */
    @Test
    void accept_hasNoCustomerEmailGate_anAppInvalidOrMissingSavedEmailSigns_theEmailIsSkippedWithAWarn_theStoreIsNotified()
            throws Exception {
        long invalidEmailOrderId = invoicedOrder();
        String invalidEmailToken = sendLink(invalidEmailOrderId);
        setCustomerEmail(invalidEmailOrderId, APP_INVALID_EMAIL);
        // A stale, non-null dashboard mirror (planted): the signing must reset it to the new version's null.
        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE sales_order SET last_emailed_at = ? WHERE order_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(1)), invalidEmailOrderId), "fixture: plant a stale mirror");
        clearJpaCache();
        Signing invalidEmail = assertSignsRemotely(invalidEmailOrderId, invalidEmailToken,
                accept(invalidEmailToken, version("1"), pngSignature()), "an app-invalid saved email after the send");
        assertCustomerEmailSkipped(invalidEmailOrderId, invalidEmail, "an app-invalid saved email");

        long noCustomerOrderId = invoicedOrder();
        String noCustomerToken = sendLink(noCustomerOrderId);
        Assertions.assertEquals(1, jdbcTemplate.update("DELETE FROM order_customer WHERE order_id = ?", noCustomerOrderId),
                "fixture: remove the saved customer (and with it the saved email)");
        clearJpaCache();
        Signing noCustomer = assertSignsRemotely(noCustomerOrderId, noCustomerToken,
                accept(noCustomerToken, version("1"), pngSignature()), "no saved customer after the send");
        assertCustomerEmailSkipped(noCustomerOrderId, noCustomer, "no saved customer");

        Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty() && invoiceEmailSender.failedEmails().isEmpty(),
                "no signed-PDF email was sent or attempted for either order");
        Assertions.assertEquals(2, notificationSender.sentNotifications().size(), "both signings notified the store");
    }

    /**
     * With a valid saved email (surrounding spaces kept in the database) and a store email with surrounding
     * spaces, a remote signing of a 1,100.00 inc-GST invoice sends exactly one customer email - to the TRIMMED
     * saved email, subject {@code Your invoice from {business}}, the exact short body, the appended version's
     * own stored PDF and file name, the order id and version 2 - and stamps both markers with the same value;
     * and exactly one store notification - to the TRIMMED store email, subject {@code Invoice accepted:
     * {order_number}}, the exact body (version 2, the frozen name, the {@code dd/MM/yyyy HH:mm} of
     * {@code accepted_at}, {@code $1,100.00}), the order id and version 2 - carrying no link, token, hash,
     * signature, storage reference, cost or GP. No WARN is logged.
     */
    @Test
    void accept_emailsTheStoredSignedPdfToTheTrimmedSavedEmail_andNotifiesTheTrimmedStoreEmailWithTheExactText()
            throws Exception {
        long orderId = invoiceReadyOrder(PADDED_CUSTOMER_EMAIL, "1000.00", "400.00");
        create(orderId);
        setStoreEmail(PADDED_STORE_EMAIL);
        String token = sendLink(orderId);
        String orderNumber = orderNumber(orderId);
        assertMoney("1100.00", invoiceRow(invoiceId(orderId, 1)).get("sale_price_inc_gst"),
                "fixture: a 1,000.00 ex GST sale is 1,100.00 inc GST");
        Assertions.assertEquals(PADDED_CUSTOMER_EMAIL, savedEmail(orderId), "fixture: the saved email keeps its spaces");

        Signing signing = assertSignsRemotely(orderId, token, accept(token, version("1"), pngSignature()),
                "a remote signing with a valid saved email and a store email");
        Assertions.assertTrue(signing.serviceLog().isEmpty(),
                () -> "no WARN when both deliveries are addressable: " + describe(signing.serviceLog()));
        long v2 = signing.invoiceId();

        // The customer's signed-PDF email.
        List<InvoiceEmailRequest> sent = invoiceEmailSender.sentEmails();
        Assertions.assertEquals(1, sent.size(), "exactly one signed-PDF email");
        Assertions.assertTrue(invoiceEmailSender.failedEmails().isEmpty(), "no failed attempt");
        InvoiceEmailRequest email = sent.get(0);
        Assertions.assertEquals(PADDED_CUSTOMER_EMAIL.trim(), email.recipientEmail(), "the trimmed saved email");
        Assertions.assertEquals("Your invoice from " + BUSINESS_NAME, email.subject(), "subject");
        Assertions.assertEquals("Hi,\n\nPlease find your invoice " + orderNumber + " from " + BUSINESS_NAME
                + " attached.\n\nThank you.", email.bodyText(), "body");
        Assertions.assertArrayEquals(pdfOnDisk(v2), email.pdfBytes(), "the appended version's own stored PDF");
        Assertions.assertEquals("invoice-" + orderNumber + "-v2.pdf", email.pdfFileName(), "the attachment's file name");
        Assertions.assertEquals(storedPdfFileName(v2), email.pdfFileName(), "the stored file name of the appended PDF");
        Assertions.assertEquals(orderId, email.orderId(), "order id");
        Assertions.assertEquals(2, email.invoiceVersionNumber(), "the appended version");
        Timestamp stamp = (Timestamp) invoiceRow(v2).get("last_emailed_at");
        Assertions.assertNotNull(stamp, "the appended version's last_emailed_at is stamped");
        Assertions.assertEquals(stamp, orderLastEmailedAt(orderId), "the mirror carries the same stamp");

        // The store notification.
        List<InvoiceAcceptanceNotificationRequest> notifications = notificationSender.sentNotifications();
        Assertions.assertEquals(1, notifications.size(), "exactly one store notification");
        Assertions.assertTrue(notificationSender.failedNotifications().isEmpty(), "no failed notification");
        InvoiceAcceptanceNotificationRequest notification = notifications.get(0);
        Assertions.assertEquals(PADDED_STORE_EMAIL.trim(), notification.recipientEmail(), "the trimmed store email");
        Assertions.assertEquals("Invoice accepted: " + orderNumber, notification.subject(), "subject");
        LocalDateTime acceptedAt = acceptedAt(v2);
        Set<String> expectedBodies = new TreeSet<>();
        for (LocalDateTime at : List.of(acceptedAt, acceptedAt.minusNanos(1_000))) {
            expectedBodies.add("Invoice " + orderNumber + " (version 2) was accepted online by " + FROZEN_NAME
                    + " on " + DISPLAY_DATE_TIME.format(at) + ".\n\nInvoice total (inc GST): $1,100.00.\n\n"
                    + "The signed invoice is available in the sales portal.");
        }
        Assertions.assertTrue(expectedBodies.contains(notification.bodyText()),
                () -> "the exact notification body (the accepted time to the minute, rounding-safe): "
                        + notification.bodyText() + " expected one of " + expectedBodies);
        Assertions.assertEquals(orderId, notification.orderId(), "order id");
        Assertions.assertEquals(2, notification.invoiceVersionNumber(), "the signed version");
        String everything = notification.subject() + "\n" + notification.bodyText();
        for (String leak : List.of("/i/", "http", token, sha256Hex(token), "signature-", ".png", ".pdf", "/uploads/",
                "storage", "400.00", "$400", "cost", "Cost", "GP", "margin")) {
            Assertions.assertFalse(everything.contains(leak), () -> "the notification never carries '" + leak + "': "
                    + everything);
        }
    }

    /**
     * A store with no email (null): the store notification is SKIPPED - nothing recorded or attempted - with
     * exactly one WARN with the exact text on the PublicInvoiceService logger, while the signing and the
     * customer's signed-PDF email (and its markers) stand.
     */
    @Test
    void accept_withNoStoreEmail_skipsTheStoreNotificationWithAWarn_theSigningAndTheCustomerEmailStand() throws Exception {
        long orderId = invoicedOrder();
        String token = sendLink(orderId);
        setStoreEmail(null);

        Signing signing = assertSignsRemotely(orderId, token, accept(token, version("1"), pngSignature()),
                "a remote signing with no store email");

        Assertions.assertTrue(notificationSender.sentNotifications().isEmpty(), "no store notification recorded");
        Assertions.assertTrue(notificationSender.failedNotifications().isEmpty(), "no store notification attempted");
        assertSingleWarn(signing.serviceLog(),
                String.format(STORE_NOTIFICATION_SKIPPED_WARN, orderId, signing.versionNumber()),
                orderId, signing.versionNumber(), "no store email");
        Assertions.assertEquals(1, invoiceEmailSender.sentEmails().size(), "the customer email is still sent");
        Timestamp stamp = (Timestamp) invoiceRow(signing.invoiceId()).get("last_emailed_at");
        Assertions.assertNotNull(stamp, "the customer email is still stamped");
        Assertions.assertEquals(stamp, orderLastEmailedAt(orderId), "the mirror carries the same stamp");
    }

    /**
     * The two after-signing deliveries are independent and never fail the signing (contract 10.3, 10.4,
     * 6.2). With the customer email failing at the transport AND the store notification failing with a
     * runtime exception, the signing still answers the exact 201, both deliveries are attempted (each recorded
     * as failed, the notification after the failed email), both markers stay null, and exactly the two WARNs
     * with the exact texts are logged in that order (order id and version only, no throwable). With only the
     * notification failing, the customer email is delivered and stamped (the markers carry its stamp) and
     * only the notification WARN is logged.
     */
    @Test
    void accept_afterTheSigning_theCustomerEmailAndTheStoreNotificationFailIndependently_neverFailingTheSigning()
            throws Exception {
        long bothFailOrderId = invoicedOrder();
        String bothFailToken = sendLink(bothFailOrderId);
        invoiceEmailSender.failNextSend();
        notificationSender.failNextSendWith(new IllegalStateException("simulated notification provider failure"));

        Signing bothFail = assertSignsRemotely(bothFailOrderId, bothFailToken,
                accept(bothFailToken, version("1"), pngSignature()), "the customer email and the notification both fail");

        Assertions.assertTrue(invoiceEmailSender.sentEmails().isEmpty(), "no signed-PDF email delivered");
        Assertions.assertEquals(1, invoiceEmailSender.failedEmails().size(), "the signed-PDF email was attempted once");
        Assertions.assertTrue(notificationSender.sentNotifications().isEmpty(), "no store notification delivered");
        Assertions.assertEquals(1, notificationSender.failedNotifications().size(),
                "the store notification is still attempted after the failed email");
        Assertions.assertNull(invoiceRow(bothFail.invoiceId()).get("last_emailed_at"), "the version's marker stays null");
        Assertions.assertNull(orderLastEmailedAt(bothFailOrderId), "the mirror stays null");
        assertWarns(bothFail.serviceLog(), List.of(
                        String.format(CUSTOMER_EMAIL_FAILED_WARN, bothFailOrderId, bothFail.versionNumber()),
                        String.format(STORE_NOTIFICATION_FAILED_WARN, bothFailOrderId, bothFail.versionNumber())),
                bothFailOrderId, bothFail.versionNumber(), "both deliveries fail");

        long notificationFailOrderId = invoicedOrder();
        String notificationFailToken = sendLink(notificationFailOrderId);
        notificationSender.failNextSend();

        Signing notificationFail = assertSignsRemotely(notificationFailOrderId, notificationFailToken,
                accept(notificationFailToken, version("1"), pngSignature()), "only the store notification fails");

        Assertions.assertEquals(1, invoiceEmailSender.sentEmails().stream()
                .filter(email -> email.orderId() == notificationFailOrderId).count(), "the customer email is delivered");
        Timestamp stamp = (Timestamp) invoiceRow(notificationFail.invoiceId()).get("last_emailed_at");
        Assertions.assertNotNull(stamp, "the delivered email is stamped");
        Assertions.assertEquals(stamp, orderLastEmailedAt(notificationFailOrderId), "the mirror carries the same stamp");
        Assertions.assertEquals(1, notificationSender.failedNotifications().stream()
                .filter(notification -> notification.orderId() == notificationFailOrderId).count(),
                "the notification was attempted and failed");
        assertSingleWarn(notificationFail.serviceLog(),
                String.format(STORE_NOTIFICATION_FAILED_WARN, notificationFailOrderId, notificationFail.versionNumber()),
                notificationFailOrderId, notificationFail.versionNumber(), "only the store notification fails");
    }

    // ================================================================
    // 8. LAID and CANCELLED (contract 12)
    // ================================================================

    /** The remote accept has no LAID and no CANCELLED rule: both orders sign (201) and keep their status. */
    @Test
    void accept_isAllowedOnALaidAndOnACancelledOrder_theOrderKeepsItsStatus() throws Exception {
        for (String status : List.of("LAID", "CANCELLED")) {
            long orderId = invoicedOrder();
            String token = sendLink(orderId);
            setOrderStatus(orderId, status);

            Signing signing = assertSignsRemotely(orderId, token, accept(token, version("1"), pngSignature()),
                    "a " + status + " order");

            Assertions.assertNotNull(invoiceRow(signing.invoiceId()).get("accepted_at"), status + ": the version is signed");
            Assertions.assertEquals(status, orderStatus(orderId), status + ": the order keeps its status");
        }
    }

    // ================================================================
    // 9. The never-signable-when-signed invariant (contract 9.4 step 3.4, 7.6)
    // ================================================================

    /**
     * The current version is signed by SQL while the link stays {@code ACTIVE} (a state no writer produces):
     * the stale comparison still answers first (409 for another value), and the matching value gets exactly
     * the generic 500 envelope ({@code INTERNAL_SERVER_ERROR}, "An unexpected error occurred.", no data, no
     * details) with nothing written - no version, file, token change, email or notification.
     */
    @Test
    void accept_currentVersionSignedUnderAnActiveLink_returnsTheGeneric500_nothingWritten_theLinkStaysActive()
            throws Exception {
        long orderId = invoicedOrder();
        String token = sendLink(orderId);
        Assertions.assertEquals(1, jdbcTemplate.update(
                        "UPDATE invoice SET accepted_at = ?, accepted_customer_name = ? WHERE invoice_id = ?",
                        Timestamp.valueOf(LocalDateTime.now().minusMinutes(5)), FROZEN_NAME, invoiceId(orderId, 1)),
                "fixture: sign the current version by SQL under the ACTIVE link");
        clearJpaCache();

        assertAcceptRefused(orderId, accept(token, version("2"), pngSignature()), 409, STALE, STALE_MESSAGE,
                "a signed current version with a stale value: the stale comparison answers first");
        MvcResult[] holder = new MvcResult[1];
        JsonNode error = assertAcceptRefused(orderId, accept(token, version("1"), pngSignature()), 500, INTERNAL_ERROR,
                INTERNAL_ERROR_MESSAGE, "a signed current version under an ACTIVE link", holder);
        Assertions.assertFalse(error.has("details"), "the generic 500 carries no details");
        Assertions.assertEquals(JSON.readTree(GENERIC_500_BODY), JSON.readTree(bodyOf(holder[0])),
                "exactly the generic error envelope");
        Assertions.assertEquals("ACTIVE", tokenRow(token).get("status"), "the link stays ACTIVE");
    }

    // ================================================================
    // Fixtures - the self-seeded tenant and orders
    // ================================================================

    private void seedTenant() {
        businessId = jdbcTemplate.queryForObject(
                "INSERT INTO business (name, slug, is_active, abn, terms_soft, terms_hard) "
                        + "VALUES (?, ?, TRUE, '51 824 753 556', ?, ?) RETURNING business_id",
                Long.class, BUSINESS_NAME, SLUG, SOFT_TERMS, HARD_TERMS);
        storeId = jdbcTemplate.queryForObject(
                "INSERT INTO store (business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'Acceptwood Showroom', ?, '0290001234', ?, '1 Showroom Way', 'Burwood', 'NSW', "
                        + " '2134') RETURNING store_id",
                Integer.class, businessId, STORE_CODE, STORE_EMAIL);
        orderUserId = insertUser("Parker", "Aldine", ORDER_USER_CODE, "parker.aldine@acceptwood.example.com");
        senderUserId = insertUser("Petra", "Alcott", SENDER_CODE, "petra.alcott@acceptwood.example.com");
        for (long userId : List.of(orderUserId, senderUserId)) {
            jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (?, ?, ?)",
                    businessId, userId, storeId);
        }
        clearJpaCache();
    }

    private long insertUser(String firstName, String lastName, String salespersonCode, String email) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO app_user (business_id, first_name, last_name, salesperson_code, email, password_hash) "
                        + "VALUES (?, ?, ?, ?, ?, 'not-a-login-hash') RETURNING user_id",
                Long.class, businessId, firstName, lastName, salespersonCode, email);
    }

    /**
     * An invoice-ready SOFT order of the store (order user PA1) with no invoice yet: customer "Avery
     * Lindqvist" with {@code customerEmail}, INSTALLATION and BILLING rows, details of sale, a CONFIRMED lay
     * date 45 days ahead and one charge line of {@code price} ex GST at {@code cost}.
     */
    private long invoiceReadyOrder(String customerEmail, String price, String cost) {
        int sequence = ++orderSequence;
        // order_number must match chk_sales_order_number_format (V6): {store}.{LL#}.{#####}
        String orderNumber = STORE_CODE + "." + ORDER_USER_CODE + "." + String.format("%05d", sequence);
        long orderId = jdbcTemplate.queryForObject(
                "INSERT INTO sales_order (business_id, store_id, user_id, order_sequence_number, order_number, "
                        + " flooring_type, order_status, week_number, week_year, details_of_sale, "
                        + " proposed_lay_date, lay_date_status) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, 'LEAD'::order_status, 42, 2026, ?, ?, "
                        + " 'CONFIRMED'::lay_date_status) RETURNING order_id",
                Long.class, businessId, storeId, orderUserId, sequence, orderNumber, DETAILS_OF_SALE, Date.valueOf(layDate));
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, last_name, email, mobile) VALUES (?, ?, ?, ?, ?)",
                orderId, CUSTOMER_FIRST_NAME, CUSTOMER_LAST_NAME, customerEmail, "0412345678");
        jdbcTemplate.update(
                "INSERT INTO order_address (order_id, address_type, unit_number, street_number, street, suburb, "
                        + " state_code, postcode) "
                        + "VALUES (?, 'INSTALLATION'::address_type, NULL, '7', 'Install Lane', 'Ryde', 'NSW', '2112')",
                orderId);
        jdbcTemplate.update(
                "INSERT INTO order_address (order_id, address_type, unit_number, street_number, street, suburb, "
                        + " state_code, postcode) "
                        + "VALUES (?, 'BILLING'::address_type, NULL, '12', ?, ?, 'NSW', '2021')",
                orderId, LIVE_BILLING_STREET, LIVE_BILLING_SUBURB);
        String chargeCode = CHARGE_CODE_PREFIX + sequence;
        long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Acceptwood accept test charge', ?, ?) RETURNING charge_id",
                Long.class, storeId, chargeCode, new BigDecimal(price), new BigDecimal(cost));
        jdbcTemplate.update(
                "INSERT INTO order_charge_line (order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Acceptwood accept test charge', ?, ?, 1, ?, ?, ?)",
                orderId, chargeId, chargeCode, new BigDecimal(price), new BigDecimal(cost), new BigDecimal(price),
                new BigDecimal(price), new BigDecimal(cost));
        clearJpaCache();
        return orderId;
    }

    /** An invoice-ready order (100.00 ex / 40.00 cost) with its invoice v1 created through D.1 by the order user. */
    private long invoicedOrder() throws Exception {
        long orderId = invoiceReadyOrder(CUSTOMER_EMAIL, "100.00", "40.00");
        create(orderId);
        Assertions.assertEquals(FROZEN_NAME, invoiceRow(invoiceId(orderId, 1)).get("customer_name_snapshot"),
                "fixture: D.1 froze the saved name");
        return orderId;
    }

    /** Plant one version's frozen Invoice To name (the remote accepted name), then clear JPA. */
    private void setFrozenName(long invoiceId, String name) {
        Assertions.assertEquals(1, jdbcTemplate.update(
                        "UPDATE invoice SET customer_name_snapshot = ? WHERE invoice_id = ?", name, invoiceId),
                "fixture: set exactly one version's frozen name");
        clearJpaCache();
    }

    private void setCustomerEmail(long orderId, String email) {
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE order_customer SET email = ? WHERE order_id = ?", email, orderId), "fixture: set the saved email");
        clearJpaCache();
    }

    private void setStoreEmail(String email) {
        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE store SET email = ? WHERE store_id = ?", email, storeId),
                "fixture: set the store email");
        clearJpaCache();
    }

    private void setOrderStatus(long orderId, String status) {
        Assertions.assertEquals(1, jdbcTemplate.update(
                        "UPDATE sales_order SET order_status = ?::order_status WHERE order_id = ?", status, orderId),
                "fixture: set the order status");
        clearJpaCache();
    }

    /**
     * Detach every hydrated entity. The MockMvc calls of a test share one transaction and persistence
     * context, so after a raw JDBC write a later JPA read (order, customer, store, business) would otherwise
     * return the stale cached entity.
     */
    private void clearJpaCache() {
        entityManager.clear();
    }

    // ================================================================
    // Protected (staff) requests - always with a session
    // ================================================================

    private MockHttpSession session(long userId) {
        MockHttpSession session = new MockHttpSession();
        // Type trap: SessionContext casts (Long) user_id / business_id and (Integer) store_id.
        session.setAttribute("user_id", Long.valueOf(userId));
        session.setAttribute("business_id", Long.valueOf(businessId));
        session.setAttribute("store_id", Integer.valueOf(storeId));
        return session;
    }

    private static String orderUrl(long orderId) {
        return "/api/v1/" + SLUG + "/orders/" + orderId;
    }

    /**
     * Perform a protected request and assert its status, with the response body in the failure message. It
     * also asserts the request carried its session, which shows that {@link #publicCall}'s "no session" check
     * observes a real difference.
     */
    private MvcResult perform(RequestBuilder request, int expectedStatus, String label) throws Exception {
        clearJpaCache();
        MvcResult result = mockMvc.perform(request).andReturn();
        clearJpaCache();
        Assertions.assertEquals(expectedStatus, result.getResponse().getStatus(),
                () -> label + ": unexpected status; body: " + bodyOf(result));
        Assertions.assertNotNull(result.getRequest().getSession(false),
                () -> label + ": a staff request carries its session");
        return result;
    }

    /** D.1 Create (empty body) by the order user: 201. */
    private void create(long orderId) throws Exception {
        perform(post(orderUrl(orderId) + "/invoices").session(session(orderUserId))
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 201, "fixture: D.1 create");
    }

    /** D.7 CASH payment by the order user (appends a version, never emails, never kills the link): 201. */
    private void recordCashPayment(long orderId, String amount) throws Exception {
        perform(post(orderUrl(orderId) + "/payments").session(session(orderUserId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payment_method\": \"CASH\", \"amount\": " + amount + "}"), 201, "fixture: D.7 payment");
    }

    /** A non-itemised quote draft (the final inc-GST total only) by the order user: 200. */
    private void saveQuoteDraft(long orderId, String finalIncTotal) throws Exception {
        perform(put(orderUrl(orderId) + "/quote/draft").session(session(orderUserId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemised\": false, \"final_total_inc_gst\": " + finalIncTotal + ", \"lines\": []}"), 200,
                "fixture: save a quote draft");
    }

    /** Issue the quote by email (an ISSUED version with an ACTIVE quote link): 201; returns the version id. */
    private long sendQuoteEmail(long orderId) throws Exception {
        perform(post(orderUrl(orderId) + "/quote/send-email").session(session(orderUserId))
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 201, "fixture: issue the quote by email");
        return jdbcTemplate.queryForObject(
                "SELECT quote_version_id FROM quote_version WHERE order_id = ? AND status = 'ISSUED'", Long.class, orderId);
    }

    /** The plaintext quote token of the latest recorded quote email (base-URL agnostic). */
    private String lastQuoteToken() {
        List<QuoteEmailRequest> sent = quoteEmailSender.sentEmails();
        Assertions.assertFalse(sent.isEmpty(), "fixture: a quote email was recorded");
        Matcher matcher = QUOTE_LINK_PATTERN.matcher(sent.get(sent.size() - 1).bodyText());
        Assertions.assertTrue(matcher.find(), "fixture: the quote email carries the /q/{token} link");
        return matcher.group(1);
    }

    /**
     * The protected send-link by the SENDER PA2 (not the order user): 201 with the exact message, one more
     * recorded link email for this order, whose body carries the {@code /i/{token}} link exactly once. Returns
     * the plaintext token (its row is then found by its SHA-256 hash and is {@code ACTIVE}, sent by PA2).
     */
    private String sendLink(long orderId) throws Exception {
        int sentBefore = linkEmailSender.sentEmails().size();
        MvcResult result = perform(post(orderUrl(orderId) + "/invoices/current/send-link").session(session(senderUserId))
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 201, "fixture: send the invoice link (PA2)");
        Assertions.assertEquals(LINK_SENT_MESSAGE, readJson(result).path("message").asText(), "fixture: send-link message");
        List<InvoiceLinkEmailRequest> sent = linkEmailSender.sentEmails();
        Assertions.assertEquals(sentBefore + 1, sent.size(), "fixture: one link email per send");
        InvoiceLinkEmailRequest email = sent.get(sent.size() - 1);
        Assertions.assertEquals(orderId, email.orderId(), "fixture: the link email is this order's");
        String token = extractInvoiceToken(email.bodyText());
        Map<String, Object> row = tokenRow(token);
        Assertions.assertEquals("ACTIVE", row.get("status"), "fixture: the new link is ACTIVE");
        Assertions.assertEquals(orderId, asLong(row.get("order_id")), "fixture: the link belongs to the order");
        Assertions.assertEquals(senderUserId, asLong(row.get("sent_by_user_id")), "fixture: PA2 sent the link");
        return token;
    }

    private static String extractInvoiceToken(String body) {
        Matcher matcher = INVOICE_LINK_PATTERN.matcher(body);
        Assertions.assertTrue(matcher.find(), () -> "the link email must carry the /i/{token} link: " + body);
        String token = matcher.group(1);
        Assertions.assertFalse(matcher.find(), "the public link appears exactly once in the body");
        Assertions.assertEquals(1, countOccurrences(body, "/i/"), "the /i/ path appears exactly once in the body");
        return token;
    }

    /** D.3 current invoice (the order user): 200; returns data.invoice. */
    private JsonNode getCurrent(long orderId) throws Exception {
        MvcResult result = perform(get(orderUrl(orderId) + "/invoices/current").session(session(orderUserId)), 200,
                "D.3 current");
        return readJson(result).path("data").path("invoice");
    }

    // ================================================================
    // Public requests - token only, NEVER a session
    // ================================================================

    /** One addition to a public accept multipart: a form field or a file part. */
    @FunctionalInterface
    private interface Part {
        void addTo(MockMultipartHttpServletRequestBuilder request);
    }

    /** The form field {@code expected_version_number} with these values (more than one = repeated). */
    private static Part version(String... values) {
        return request -> request.param(EXPECTED_VERSION_FIELD, values);
    }

    private static Part field(String name, String... values) {
        return request -> request.param(name, values);
    }

    private static Part file(MockMultipartFile file) {
        return request -> request.file(file);
    }

    /** A signature part with {@code bytes}, declared {@code image/png}. */
    private static Part signature(byte[] bytes) {
        return file(signatureFile(bytes));
    }

    private static MockMultipartFile signatureFile(byte[] bytes) {
        return new MockMultipartFile(SIGNATURE_PART, "signature.png", "image/png", bytes);
    }

    /** The valid signature part: a real PNG declared {@code image/png}. */
    private static Part pngSignature() {
        return signature(ONE_PIXEL_PNG);
    }

    private static MockMultipartFile jpegSignature() {
        return new MockMultipartFile(SIGNATURE_PART, "signature.jpg", "image/jpeg", ONE_PIXEL_PNG);
    }

    private static MockMultipartFile photoPart() {
        return new MockMultipartFile("photo", "photo.png", "image/png", ONE_PIXEL_PNG);
    }

    private static MockMultipartFile textPart(String name, String value) {
        return new MockMultipartFile(name, name + ".txt", "text/plain", value.getBytes(StandardCharsets.UTF_8));
    }

    /** The public accept for {@code token} carrying exactly {@code parts}; no session, ever. */
    private static MockMultipartHttpServletRequestBuilder accept(String token, Part... parts) {
        MockMultipartHttpServletRequestBuilder request = multipart(PUBLIC_INVOICES + token + "/accept");
        for (Part part : parts) {
            part.addTo(request);
        }
        return request;
    }

    /**
     * The public accept with {@code rawQuery} as the URL query string (sent as given: the {@link URI}
     * overload keeps MockMvc from encoding it again) and {@code parts}; no session, ever.
     */
    private static MockMultipartHttpServletRequestBuilder acceptWithQuery(String token, String rawQuery, Part... parts) {
        MockMultipartHttpServletRequestBuilder request =
                multipart(URI.create(PUBLIC_INVOICES + token + "/accept?" + rawQuery));
        for (Part part : parts) {
            part.addTo(request);
        }
        return request;
    }

    /**
     * THE helper of every public invoice request: perform it (built without a session), assert its status,
     * that it went to the public invoice surface, that NO session exists after it (none sent, none created by
     * the filter chain or the service) and that no cookie / {@code Set-Cookie} header was set - for every
     * status.
     */
    private MvcResult publicCall(RequestBuilder request, int expectedStatus, String label) throws Exception {
        clearJpaCache();
        MvcResult result = mockMvc.perform(request).andReturn();
        clearJpaCache();
        Assertions.assertEquals(expectedStatus, result.getResponse().getStatus(),
                () -> label + ": unexpected status; body: " + bodyOf(result));
        String uri = result.getRequest().getRequestURI();
        Assertions.assertTrue(uri.startsWith(PUBLIC_INVOICES),
                () -> label + ": the public helper only serves the public invoice surface: " + uri);
        Assertions.assertNull(result.getRequest().getSession(false),
                () -> label + ": a public invoice request never carries or creates a session");
        List<String> setCookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        Assertions.assertTrue(setCookies.isEmpty(), () -> label + ": no Set-Cookie on the public surface: " + setCookies);
        Assertions.assertEquals(0, result.getResponse().getCookies().length,
                () -> label + ": no cookie on the public surface");
        return result;
    }

    /** {@link #publicCall} with the PublicInvoiceService logger captured for the duration of the request. */
    private Logged publicCallLogged(RequestBuilder request, int expectedStatus, String label) throws Exception {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PublicInvoiceService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MvcResult result = publicCall(request, expectedStatus, label);
            return new Logged(result, List.copyOf(appender.list));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** The public GET of {@code token}: 200 "Invoice loaded."; returns the whole body. */
    private JsonNode publicGet(String token, String label) throws Exception {
        JsonNode body = readJson(publicCall(get(PUBLIC_INVOICES + token), 200, label));
        Assertions.assertEquals(LOADED_MESSAGE, body.path("message").asText(), label + ": message");
        return body;
    }

    private record Logged(MvcResult result, List<ILoggingEvent> events) {
    }

    // ================================================================
    // Assertions - refusals ("nothing written") and signings
    // ================================================================

    private record Detail(String field, String message) {
    }

    private static Detail detail(String field, String message) {
        return new Detail(field, message);
    }

    /**
     * A public accept that must be REFUSED: the expected status, code and exact message, the error envelope
     * as the only top-level key (no data), and nothing written (see {@link #assertNothingWritten}). Returns
     * the error object.
     */
    private JsonNode assertAcceptRefused(long orderId, RequestBuilder request, int expectedStatus, String expectedCode,
                                         String expectedMessage, String label) throws Exception {
        return assertAcceptRefused(orderId, request, expectedStatus, expectedCode, expectedMessage, label, new MvcResult[1]);
    }

    private JsonNode assertAcceptRefused(long orderId, RequestBuilder request, int expectedStatus, String expectedCode,
                                         String expectedMessage, String label, MvcResult[] resultHolder) throws Exception {
        Footprint before = footprint(orderId);
        MvcResult result = publicCall(request, expectedStatus, label);
        resultHolder[0] = result;
        JsonNode body = readJson(result);
        JsonNode error = body.path("error");
        Assertions.assertTrue(error.isObject(), () -> label + ": the error envelope: " + body);
        Assertions.assertEquals(Set.of("error"), fieldNames(body), () -> label + ": an error carries no data: " + body);
        Assertions.assertEquals(expectedCode, error.path("code").asText(), () -> label + ": error code; body: " + body);
        Assertions.assertEquals(expectedMessage, error.path("message").asText(),
                () -> label + ": error message; body: " + body);
        assertNothingWritten(orderId, before, label);
        return error;
    }

    /**
     * 400 {@code VALIDATION_FAILED} ("One or more fields are invalid.") whose details are EXACTLY
     * {@code expected} - field and message, in order, no section - and nothing written.
     */
    private void assertAcceptDetails(long orderId, RequestBuilder request, List<Detail> expected, String label)
            throws Exception {
        JsonNode error = assertAcceptRefused(orderId, request, 400, VALIDATION_FAILED, VALIDATION_FAILED_MESSAGE, label);
        JsonNode details = error.path("details");
        Assertions.assertTrue(details.isArray(), () -> label + ": details: " + error);
        List<Detail> actual = new ArrayList<>();
        for (JsonNode node : details) {
            Assertions.assertEquals(Set.of("field", "message"), fieldNames(node), () -> label + ": detail keys: " + node);
            actual.add(new Detail(node.path("field").asText(null), node.path("message").asText(null)));
        }
        Assertions.assertEquals(expected, actual, () -> label + ": every offending part, in the contract order: " + error);
    }

    /** A 410 JSON error on a public read (the pdf): the exact code and message, no data. */
    private static void assertGone(MvcResult result, String expectedCode, String expectedMessage, String label)
            throws IOException {
        JsonNode body = readJson(result);
        Assertions.assertEquals(Set.of("error"), fieldNames(body), () -> label + ": only the error envelope: " + body);
        Assertions.assertEquals(expectedCode, body.path("error").path("code").asText(), () -> label + ": code: " + body);
        Assertions.assertEquals(expectedMessage, body.path("error").path("message").asText(),
                () -> label + ": message: " + body);
    }

    /** The minimal dead-state GET payload: the 27 keys, only state, business_name and message non-null. */
    private static void assertDeadView(JsonNode body, String state, String message, String label) {
        JsonNode data = body.path("data");
        Assertions.assertEquals(PUBLIC_VIEW_KEYS, fieldNames(data), () -> label + ": the 27 public view keys: " + data);
        Assertions.assertEquals(state, data.path("state").asText(), () -> label + ": state: " + data);
        Assertions.assertEquals(BUSINESS_NAME, data.path("business_name").asText(), () -> label + ": business name");
        Assertions.assertEquals(message, data.path("message").asText(), () -> label + ": the dead-state message");
        for (String key : PUBLIC_VIEW_KEYS) {
            if (!Set.of("state", "business_name", "message").contains(key)) {
                Assertions.assertTrue(data.get(key).isNull(), () -> label + ": " + key + " is null on a dead link: " + data);
            }
        }
    }

    /** Everything a public accept can write, for one order, plus the database-wide counts and the recorders. */
    private record Footprint(List<Map<String, Object>> invoices, List<Map<String, Object>> order,
                             List<Map<String, Object>> invoiceTokens, List<Map<String, Object>> quoteVersions,
                             List<Map<String, Object>> quoteTokens, List<Map<String, Object>> payments,
                             long orderStoredFiles, Set<String> orderFiles, long allInvoices, long allStoredFiles,
                             long allInvoiceTokens, List<Integer> recorderCounts) {
    }

    private Footprint footprint(long orderId) throws IOException {
        return new Footprint(
                rows("SELECT * FROM invoice WHERE order_id = ? ORDER BY version_number", orderId),
                rows("SELECT * FROM sales_order WHERE order_id = ?", orderId),
                rows("SELECT * FROM invoice_token WHERE order_id = ? ORDER BY invoice_token_id", orderId),
                rows("SELECT * FROM quote_version WHERE order_id = ? ORDER BY version_number", orderId),
                rows("SELECT t.* FROM quote_token t JOIN quote_version v ON v.quote_version_id = t.quote_version_id "
                        + "WHERE v.order_id = ? ORDER BY t.quote_token_id", orderId),
                rows("SELECT * FROM payment_transaction WHERE order_id = ? ORDER BY 1", orderId),
                count("SELECT COUNT(*) FROM stored_file WHERE storage_path LIKE ?", orderStoragePrefix(orderId) + "%"),
                filesOnDisk(orderId),
                count("SELECT COUNT(*) FROM invoice"),
                count("SELECT COUNT(*) FROM stored_file"),
                count("SELECT COUNT(*) FROM invoice_token"),
                List.of(invoiceEmailSender.sentEmails().size(), invoiceEmailSender.failedEmails().size(),
                        notificationSender.sentNotifications().size(), notificationSender.failedNotifications().size(),
                        linkEmailSender.sentEmails().size(), linkEmailSender.failedEmails().size(),
                        quoteEmailSender.sentEmails().size(), quoteNotificationSender.sentNotifications().size()));
    }

    /**
     * NOTHING written since {@code before}: the order's invoice rows, order row, invoice tokens (the link
     * stays as it was, {@code ACTIVE} included), quote versions, quote tokens and payments are identical; no
     * invoice, stored_file or invoice_token row was added anywhere; no stored_file row and no file in the
     * order's storage directory; and no email or notification was sent or attempted.
     */
    private void assertNothingWritten(long orderId, Footprint before, String label) throws IOException {
        Footprint after = footprint(orderId);
        Assertions.assertEquals(before.invoices(), after.invoices(), label + ": no invoice row is appended or changed");
        Assertions.assertEquals(before.order(), after.order(), label + ": the order row is untouched");
        Assertions.assertEquals(before.invoiceTokens(), after.invoiceTokens(),
                label + ": the invoice link is untouched (it stays ACTIVE)");
        Assertions.assertEquals(before.quoteVersions(), after.quoteVersions(), label + ": no quote version change");
        Assertions.assertEquals(before.quoteTokens(), after.quoteTokens(), label + ": no quote link change");
        Assertions.assertEquals(before.payments(), after.payments(), label + ": no payment change");
        Assertions.assertEquals(before.orderStoredFiles(), after.orderStoredFiles(),
                label + ": no stored_file row for the order");
        Assertions.assertEquals(before.orderFiles(), after.orderFiles(), label + ": no file in the order's directory");
        Assertions.assertEquals(before.allInvoices(), after.allInvoices(), label + ": no invoice row anywhere");
        Assertions.assertEquals(before.allStoredFiles(), after.allStoredFiles(), label + ": no stored_file row anywhere");
        Assertions.assertEquals(before.allInvoiceTokens(), after.allInvoiceTokens(),
                label + ": no invoice_token row anywhere");
        Assertions.assertEquals(before.recorderCounts(), after.recorderCounts(),
                label + ": no email or notification is sent or attempted");
    }

    /** A completed remote signing: the appended version, its id, the request window and the service log. */
    private record Signing(int versionNumber, long invoiceId, LocalDateTime started, LocalDateTime ended,
                           List<ILoggingEvent> serviceLog) {
    }

    /**
     * A public accept that must SIGN: 201 with EXACTLY {@code {"data":{"state":"SIGNED"},"message":"Invoice
     * accepted."}} as JSON; exactly one invoice row appended (current + 1, now the current one) with the
     * earlier rows untouched; the signature and signed-PDF {@code stored_file} rows and files in the order's
     * directory; the presented token {@code SIGNED} with {@code dead_at} = the appended {@code accepted_at},
     * its other columns unchanged, every other token of the order unchanged and no token added; and no link
     * email. The signing's own email and notification outcomes are each test's to assert.
     */
    private Signing assertSignsRemotely(long orderId, String token, RequestBuilder request, String label) throws Exception {
        int current = currentVersion(orderId);
        Footprint before = footprint(orderId);
        Map<String, Object> tokenBefore = tokenRow(token);
        Assertions.assertEquals("ACTIVE", tokenBefore.get("status"), label + ": fixture: the presented link is ACTIVE");
        LocalDateTime started = LocalDateTime.now();
        Logged logged = publicCallLogged(request, 201, label);
        LocalDateTime ended = LocalDateTime.now();

        String contentType = logged.result().getResponse().getContentType();
        Assertions.assertNotNull(contentType, label + ": a JSON body");
        Assertions.assertTrue(MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_JSON),
                () -> label + ": JSON, was " + contentType);
        Assertions.assertEquals(JSON.readTree(ACCEPTED_BODY), JSON.readTree(bodyOf(logged.result())),
                () -> label + ": the 201 body is exactly {data:{state:SIGNED}, message:Invoice accepted.}: "
                        + bodyOf(logged.result()));

        Footprint after = footprint(orderId);
        int rowsBefore = before.invoices().size();
        Assertions.assertEquals(rowsBefore + 1, after.invoices().size(), label + ": one invoice row is appended");
        Assertions.assertEquals(before.invoices(), after.invoices().subList(0, rowsBefore),
                label + ": the earlier versions are untouched");
        Assertions.assertEquals(before.allInvoices() + 1, after.allInvoices(), label + ": one invoice row in all");
        Assertions.assertEquals(current + 1, currentVersion(orderId), label + ": the appended version is current + 1");
        Assertions.assertEquals(before.orderStoredFiles() + 2, after.orderStoredFiles(),
                label + ": the signature and the signed PDF stored_file rows, in the order's directory");
        Assertions.assertEquals(before.allStoredFiles() + 2, after.allStoredFiles(),
                label + ": two stored_file rows in all");
        Assertions.assertEquals(before.orderFiles().size() + 2, after.orderFiles().size(),
                label + ": two new files in the order's directory");
        Assertions.assertTrue(after.orderFiles().containsAll(before.orderFiles()), label + ": no file is removed");
        Assertions.assertEquals(before.allInvoiceTokens(), after.allInvoiceTokens(),
                label + ": no invoice_token row is added");

        long appendedId = invoiceId(orderId, current + 1);
        Map<String, Object> appended = invoiceRow(appendedId);
        Assertions.assertNotNull(appended.get("accepted_at"), label + ": the appended version is signed");
        Assertions.assertNotNull(appended.get("accepted_signature_file_id"), label + ": with a stored signature");
        Map<String, Object> tokenAfter = tokenRow(token);
        Assertions.assertEquals("SIGNED", tokenAfter.get("status"), label + ": the presented link is SIGNED");
        Assertions.assertEquals(appended.get("accepted_at"), tokenAfter.get("dead_at"), label + ": dead_at = accepted_at");
        for (String column : List.of("invoice_token_id", "order_id", "token_hash", "sent_by_user_id", "sent_at",
                "expires_at", "emailed_at", "created_at")) {
            Assertions.assertEquals(tokenBefore.get(column), tokenAfter.get(column),
                    () -> label + ": the link's " + column + " is unchanged");
        }
        Assertions.assertEquals(withoutToken(before.invoiceTokens(), tokenBefore),
                withoutToken(after.invoiceTokens(), tokenAfter), label + ": every other link of the order is unchanged");
        Assertions.assertEquals(before.recorderCounts().subList(4, 6), after.recorderCounts().subList(4, 6),
                label + ": a signing sends no link email");
        return new Signing(current + 1, appendedId, started, ended, logged.events());
    }

    private static List<Map<String, Object>> withoutToken(List<Map<String, Object>> tokens, Map<String, Object> token) {
        return tokens.stream()
                .filter(row -> !row.get("invoice_token_id").equals(token.get("invoice_token_id")))
                .toList();
    }

    /**
     * The customer's signed-PDF email was SKIPPED for {@code orderId}: nothing sent or attempted for the
     * order, both markers null, exactly one WARN (the skip, exact text), and the store notification sent to
     * the store's email for the signed version.
     */
    private void assertCustomerEmailSkipped(long orderId, Signing signing, String label) {
        Assertions.assertTrue(invoiceEmailSender.sentEmails().stream().noneMatch(email -> email.orderId() == orderId),
                label + ": no signed-PDF email is sent");
        Assertions.assertTrue(invoiceEmailSender.failedEmails().stream().noneMatch(email -> email.orderId() == orderId),
                label + ": no signed-PDF email is attempted");
        Assertions.assertNull(invoiceRow(signing.invoiceId()).get("last_emailed_at"),
                label + ": the version's marker stays null");
        Assertions.assertNull(orderLastEmailedAt(orderId), label + ": the mirror stays null");
        assertSingleWarn(signing.serviceLog(), String.format(CUSTOMER_EMAIL_SKIPPED_WARN, orderId, signing.versionNumber()),
                orderId, signing.versionNumber(), label);
        List<InvoiceAcceptanceNotificationRequest> notifications = notificationSender.sentNotifications().stream()
                .filter(notification -> notification.orderId() == orderId)
                .toList();
        Assertions.assertEquals(1, notifications.size(), label + ": the store is still notified");
        Assertions.assertEquals(STORE_EMAIL, notifications.get(0).recipientEmail(), label + ": to the store's email");
        Assertions.assertEquals(signing.versionNumber(), notifications.get(0).invoiceVersionNumber(),
                label + ": for the signed version");
    }

    /** Exactly one PublicInvoiceService event: WARN, the exact text, order id + version, no throwable, no address. */
    private static void assertSingleWarn(List<ILoggingEvent> events, String expectedMessage, long orderId, int version,
                                         String label) {
        Assertions.assertEquals(1, events.size(),
                () -> label + ": exactly one PublicInvoiceService log event, but was " + describe(events));
        ILoggingEvent event = events.get(0);
        Assertions.assertEquals(Level.WARN, event.getLevel(), label + ": logged at WARN");
        String message = event.getFormattedMessage();
        Assertions.assertEquals(expectedMessage, message, label + ": the WARN text");
        Assertions.assertTrue(message.contains("order " + orderId + " invoice v" + version),
                () -> label + ": the WARN names the order and the version: " + message);
        Assertions.assertFalse(message.contains("@"), () -> label + ": the WARN never carries an address: " + message);
        Assertions.assertNull(event.getThrowableProxy(), label + ": the WARN carries no throwable");
    }

    /** Exactly these PublicInvoiceService events, in order: each a WARN with the exact text, no throwable, no address. */
    private static void assertWarns(List<ILoggingEvent> events, List<String> expectedMessages, long orderId, int version,
                                    String label) {
        Assertions.assertEquals(expectedMessages, events.stream().map(ILoggingEvent::getFormattedMessage).toList(),
                () -> label + ": exactly these PublicInvoiceService log events, in order: " + describe(events));
        for (ILoggingEvent event : events) {
            Assertions.assertEquals(Level.WARN, event.getLevel(), label + ": logged at WARN");
            Assertions.assertTrue(event.getFormattedMessage().contains("order " + orderId + " invoice v" + version),
                    () -> label + ": the WARN names the order and the version: " + event.getFormattedMessage());
            Assertions.assertFalse(event.getFormattedMessage().contains("@"), label + ": the WARN never carries an address");
            Assertions.assertNull(event.getThrowableProxy(), label + ": the WARN carries no throwable");
        }
    }

    private static List<String> describe(List<ILoggingEvent> events) {
        return events.stream().map(event -> event.getLevel() + " " + event.getFormattedMessage()).toList();
    }

    private static void assertWithin(LocalDateTime value, LocalDateTime started, LocalDateTime ended, String label) {
        Assertions.assertTrue(!value.isBefore(started.minusSeconds(1)) && !value.isAfter(ended.plusSeconds(1)),
                () -> label + ": " + value + " outside [" + started + ", " + ended + "]");
    }

    private static void assertMoney(String expected, Object actual, String label) {
        Assertions.assertTrue(actual instanceof BigDecimal, () -> label + " must be a BigDecimal but was " + actual);
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual),
                () -> label + ": expected " + expected + " but was " + actual);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext();) {
            names.add(it.next());
        }
        return names;
    }

    private static String bodyOf(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode readJson(MvcResult result) throws IOException {
        return JSON.readTree(bodyOf(result));
    }

    // ================================================================
    // Rows and files
    // ================================================================

    private List<Map<String, Object>> rows(String sql, Object... args) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, args)) {
            rows.add(new LinkedHashMap<>(row));
        }
        return rows;
    }

    /** Every row of an order-keyed table ({@code order_id} column) for the order, in primary-key order. */
    private List<Map<String, Object>> orderChildRows(String table, long orderId) {
        return rows("SELECT * FROM " + table + " WHERE order_id = ? ORDER BY 1", orderId);
    }

    /** The order's quote draft lines, in primary-key order. */
    private List<Map<String, Object>> draftLineRows(long orderId) {
        return rows("SELECT l.* FROM quote_draft_line l JOIN quote_draft d ON d.quote_draft_id = l.quote_draft_id "
                + "WHERE d.order_id = ? ORDER BY 1", orderId);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private long invoiceId(long orderId, int versionNumber) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT invoice_id FROM invoice WHERE order_id = ? AND version_number = ?",
                Long.class, orderId, versionNumber);
        Assertions.assertNotNull(id, () -> "invoice v" + versionNumber + " must exist");
        return id;
    }

    /** The order's current invoice version: the highest version_number (state-derived, never assumed). */
    private int currentVersion(long orderId) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT MAX(version_number) FROM invoice WHERE order_id = ?", Integer.class, orderId);
        Assertions.assertNotNull(version, "the order must have a current invoice");
        return version;
    }

    /** The whole invoice row, for unchanged-row and column-by-column comparisons. */
    private Map<String, Object> invoiceRow(long invoiceId) {
        return new LinkedHashMap<>(jdbcTemplate.queryForMap("SELECT * FROM invoice WHERE invoice_id = ?", invoiceId));
    }

    /** The invoice_token row of a plaintext token, found by its SHA-256 hash (the plaintext is never stored). */
    private Map<String, Object> tokenRow(String plainToken) {
        return new LinkedHashMap<>(jdbcTemplate.queryForMap(
                "SELECT * FROM invoice_token WHERE token_hash = ?", sha256Hex(plainToken)));
    }

    private Map<String, Object> quoteVersionRow(long quoteVersionId) {
        return jdbcTemplate.queryForMap("SELECT * FROM quote_version WHERE quote_version_id = ?", quoteVersionId);
    }

    private Map<String, Object> quoteTokenRow(String plainToken) {
        return jdbcTemplate.queryForMap("SELECT * FROM quote_token WHERE token_hash = ?", sha256Hex(plainToken));
    }

    private Map<String, Object> storedFileRow(Object storedFileId) {
        Assertions.assertNotNull(storedFileId, "a stored_file reference");
        return jdbcTemplate.queryForMap("SELECT * FROM stored_file WHERE stored_file_id = ?", storedFileId);
    }

    private Map<String, Object> signatureFileRow(long invoiceId) {
        return storedFileRow(invoiceRow(invoiceId).get("accepted_signature_file_id"));
    }

    /** The order row without the mirror (the only order column a signing writes). */
    private Map<String, Object> orderRowWithoutMirror(long orderId) {
        Map<String, Object> row = new LinkedHashMap<>(
                jdbcTemplate.queryForMap("SELECT * FROM sales_order WHERE order_id = ?", orderId));
        row.remove("last_emailed_at");
        return row;
    }

    private LocalDateTime acceptedAt(long invoiceId) {
        Object value = invoiceRow(invoiceId).get("accepted_at");
        Assertions.assertNotNull(value, "the version must be accepted");
        return ((Timestamp) value).toLocalDateTime();
    }

    private String savedCustomerName(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT first_name || ' ' || last_name FROM order_customer WHERE order_id = ?", String.class, orderId);
    }

    private String savedEmail(long orderId) {
        return jdbcTemplate.queryForObject("SELECT email FROM order_customer WHERE order_id = ?", String.class, orderId);
    }

    private String orderNumber(long orderId) {
        return jdbcTemplate.queryForObject("SELECT order_number FROM sales_order WHERE order_id = ?", String.class, orderId);
    }

    private String orderStatus(long orderId) {
        return jdbcTemplate.queryForObject("SELECT order_status::text FROM sales_order WHERE order_id = ?",
                String.class, orderId);
    }

    private Timestamp orderLastEmailedAt(long orderId) {
        return jdbcTemplate.queryForObject("SELECT last_emailed_at FROM sales_order WHERE order_id = ?",
                Timestamp.class, orderId);
    }

    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }

    private static LocalDateTime asLocalDateTime(Object value) {
        return ((Timestamp) value).toLocalDateTime();
    }

    /** FileStorageService writes every order file under /uploads/{businessId}/orders/{orderId}/. */
    private String orderStoragePrefix(long orderId) {
        return "/uploads/" + businessId + "/orders/" + orderId + "/";
    }

    /** The file names on disk in the order's storage directory (empty when the directory does not exist). */
    private Set<String> filesOnDisk(long orderId) throws IOException {
        Path directory = Path.of(storageBaseDir).toAbsolutePath().normalize()
                .resolve(orderStoragePrefix(orderId).substring(1)).normalize();
        if (!Files.isDirectory(directory)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /** The physical file of a stored_file row of {@code orderId}: base dir + storage_path, in the order's directory. */
    private byte[] diskBytes(long orderId, String storagePath) throws IOException {
        Assertions.assertTrue(storagePath.startsWith(orderStoragePrefix(orderId)),
                () -> "the file must be stored under its own order directory: " + storagePath);
        Path onDisk = Path.of(storageBaseDir).toAbsolutePath().normalize().resolve(storagePath.substring(1)).normalize();
        Assertions.assertTrue(Files.isRegularFile(onDisk), () -> "the file must exist on disk: " + onDisk);
        return Files.readAllBytes(onDisk);
    }

    /** The version's stored PDF read from disk, checked against its stored_file row. */
    private byte[] pdfOnDisk(long invoiceId) throws IOException {
        Map<String, Object> file = jdbcTemplate.queryForMap(
                "SELECT sf.storage_path, sf.mime_type, sf.file_size, i.order_id FROM invoice i "
                        + "JOIN stored_file sf ON sf.stored_file_id = i.stored_file_id WHERE i.invoice_id = ?",
                invoiceId);
        Assertions.assertEquals("application/pdf", file.get("mime_type"), "stored_file mime type");
        byte[] bytes = diskBytes(asLong(file.get("order_id")), (String) file.get("storage_path"));
        Assertions.assertEquals(asLong(file.get("file_size")), bytes.length, "file_size matches the bytes");
        Assertions.assertEquals("%PDF-", new String(bytes, 0, 5, StandardCharsets.US_ASCII), "PDF header");
        return bytes;
    }

    /** The stored file name of the version's PDF ({@code stored_file.file_name}). */
    private String storedPdfFileName(long invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT sf.file_name FROM invoice i JOIN stored_file sf ON sf.stored_file_id = i.stored_file_id "
                        + "WHERE i.invoice_id = ?", String.class, invoiceId);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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

    // ================================================================
    // PDF text
    // ================================================================

    /** Content-stream order (the meta row's columns come out one after another). */
    private static String documentText(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /**
     * Visual reading order: the signed caption is "Accepted by" + a name span + "on" + a timestamp span, and
     * in content-stream order PDFBox emits the static words before the spans.
     */
    private static String readingOrderText(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    /**
     * The Issued / Due block (content-stream order, all whitespace removed): everything between the "Issued /
     * Due" eyebrow and the next column's "Sales Person / Store" eyebrow - the invoice date, "Due", the due date.
     */
    private static String issuedDueBlock(byte[] pdf) throws IOException {
        String raw = documentText(pdf);
        String text = noSpace(raw);
        int start = text.indexOf(ISSUED_DUE_HEADING);
        int end = text.indexOf(NEXT_COLUMN_HEADING, start + 1);
        Assertions.assertTrue(start >= 0 && end > start, () -> "the PDF must carry the Issued / Due block: " + flat(raw));
        return text.substring(start + ISSUED_DUE_HEADING.length(), end);
    }

    /**
     * The signed caption "Accepted by {name} on {dd/MM/yyyy HH:mm}" in reading order, all whitespace removed on
     * both sides (a long name wraps). Postgres keeps microseconds, so the stored accepted_at may sit up to 1
     * microsecond after the rendered in-memory instant; the rendering of {@code acceptedAt - 1us} is accepted
     * too, which keeps the check exact across a minute boundary.
     */
    private static void assertAcceptedCaption(byte[] pdf, String acceptedName, LocalDateTime acceptedAt, String label)
            throws IOException {
        String readingOrder = readingOrderText(pdf);
        String text = noSpace(readingOrder);
        String exact = noSpace("Accepted by " + acceptedName + " on " + DISPLAY_DATE_TIME.format(acceptedAt));
        String roundedBack = noSpace("Accepted by " + acceptedName + " on "
                + DISPLAY_DATE_TIME.format(acceptedAt.minusNanos(1_000)));
        Assertions.assertTrue(text.contains(exact) || text.contains(roundedBack),
                () -> label + ": the PDF must carry the caption '" + exact + "' in: " + flat(readingOrder));
    }

    private static void assertTextLacks(String text, String unexpected, String label) {
        Assertions.assertFalse(noSpace(text).contains(noSpace(unexpected)),
                () -> label + ": the PDF must NOT contain '" + unexpected + "' in: " + flat(text));
    }

    // PDFBox can inject or drop spaces between glyphs and wraps long text, so checks either collapse
    // whitespace runs or compare with all whitespace removed.
    private static String flat(String text) {
        return text.replaceAll("\\s+", " ");
    }

    private static String noSpace(String text) {
        return text.replaceAll("\\s", "");
    }

    private static String longName() {
        String[] words = {"Aldous", "Briony", "Callum", "Dermot", "Esther", "Fergus", "Gideon", "Harlan", "Imogen",
                "Jasper"};
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 36; i++) {
            if (i > 0) {
                name.append(' ');
            }
            name.append(words[i % words.length]);
        }
        return name.append(" Montague").toString();
    }

    // ================================================================
    // PNG fixtures (the public quote accept's kinds)
    // ================================================================

    private static byte[] pngOfSize(int width, int height, int imageType) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Assertions.assertTrue(ImageIO.write(new BufferedImage(width, height, imageType), "png", out),
                "a PNG ImageWriter must be available");
        return out.toByteArray();
    }

    /** The width/height a PNG header DECLARES (IHDR bytes 16..23, big-endian) - no pixel decoding. */
    private static int[] declaredPngDimensions(byte[] png) {
        ByteBuffer header = ByteBuffer.wrap(png);
        return new int[] {header.getInt(16), header.getInt(20)};
    }

    private static byte[] pngMagicFollowedByGarbage() {
        byte[] garbage = "this-is-not-an-IHDR-chunk-just-garbage-after-the-magic".getBytes(StandardCharsets.US_ASCII);
        byte[] bytes = new byte[PNG_MAGIC_LENGTH + garbage.length];
        System.arraycopy(ONE_PIXEL_PNG, 0, bytes, 0, PNG_MAGIC_LENGTH);
        System.arraycopy(garbage, 0, bytes, PNG_MAGIC_LENGTH, garbage.length);
        return bytes;
    }

    /** {@code png} with its IHDR length field replaced by {@code length}: the first chunk is no 13-byte IHDR. */
    private static byte[] withIhdrLength(byte[] png, int length) {
        byte[] patched = png.clone();
        ByteBuffer.wrap(patched).putInt(PNG_MAGIC_LENGTH, length);
        return patched;
    }

    /**
     * A signature-pad-like PNG: a transparent 48x16 ARGB canvas with a dark, NON-antialiased stroke, so
     * every pixel is fully opaque or fully transparent and the pixel comparison covers many pixels.
     */
    private static byte[] strokeSignaturePng() throws IOException {
        BufferedImage image = new BufferedImage(48, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.setColor(new Color(0x1A, 0x2B, 0x3C));
            graphics.setStroke(new BasicStroke(2f));
            graphics.drawLine(2, 12, 20, 3);
            graphics.drawLine(20, 3, 30, 13);
            graphics.drawLine(30, 13, 45, 4);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Assertions.assertTrue(ImageIO.write(image, "png", out), "a PNG ImageWriter must be available");
        return out.toByteArray();
    }

    /** {@code png} with an ancillary {@code tEXt} chunk ({@code keyword}\0{@code text}) inserted right after IHDR. */
    private static byte[] withTextChunk(byte[] png, String keyword, String text) {
        byte[] type = "tEXt".getBytes(StandardCharsets.US_ASCII);
        byte[] data = (keyword + '\0' + text).getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(data);
        byte[] chunk = ByteBuffer.allocate(4 + type.length + data.length + 4)
                .putInt(data.length).put(type).put(data).putInt((int) crc.getValue())
                .array();
        byte[] result = new byte[png.length + chunk.length];
        System.arraycopy(png, 0, result, 0, IHDR_CHUNK_END);
        System.arraycopy(chunk, 0, result, IHDR_CHUNK_END, chunk.length);
        System.arraycopy(png, IHDR_CHUNK_END, result, IHDR_CHUNK_END + chunk.length, png.length - IHDR_CHUNK_END);
        return result;
    }

    /** {@code png} with one IHDR byte replaced and the IHDR CRC recomputed, so only that field is invalid. */
    private static byte[] withIhdrByte(byte[] png, int offset, int value) {
        byte[] patched = png.clone();
        patched[offset] = (byte) value;
        CRC32 crc = new CRC32();
        crc.update(patched, IHDR_TYPE_OFFSET, IHDR_CRC_OFFSET - IHDR_TYPE_OFFSET);
        ByteBuffer.wrap(patched).putInt(IHDR_CRC_OFFSET, (int) crc.getValue());
        return patched;
    }

    /**
     * The chunk types of a PNG stream, walked chunk by chunk; asserts the stream ENDS exactly at the IEND
     * chunk (no bytes after IEND).
     */
    private static List<String> pngChunkTypes(byte[] png, String label) {
        List<String> types = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(png);
        int offset = PNG_MAGIC_LENGTH;
        while (true) {
            Assertions.assertTrue(offset + 12 <= png.length, label + ": truncated PNG chunk at offset " + offset);
            int length = buffer.getInt(offset);
            Assertions.assertTrue(length >= 0 && offset + 12L + length <= png.length,
                    label + ": PNG chunk at offset " + offset + " overruns the stream");
            String type = new String(png, offset + 4, 4, StandardCharsets.US_ASCII);
            types.add(type);
            offset += 12 + length;
            if ("IEND".equals(type)) {
                Assertions.assertEquals(png.length, offset,
                        label + ": the stream must end exactly at IEND (no trailing bytes), chunks " + types);
                return types;
            }
        }
    }

    private static BufferedImage decodePng(byte[] png, String label) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        Assertions.assertNotNull(image, label + ": not a decodable PNG");
        return image;
    }

    /**
     * SERVER NORMALISATION (16G contract 9.4, the public quote accept's rule): the stored signature is the
     * decoded upload RE-ENCODED as a clean PNG, so it is never compared byte-for-byte with the upload.
     * Instead: PNG magic; a chunk stream that starts with IHDR and ends exactly at IEND (trailing bytes
     * dropped) with no textual ancillary chunk (tEXt / zTXt / iTXt dropped); the SAME width/height; and the
     * same pixels - alpha for every pixel, RGB for every pixel that is not fully transparent.
     */
    private static void assertNormalisedSignature(byte[] uploaded, byte[] stored, String label) throws IOException {
        Assertions.assertTrue(stored.length > PNG_MAGIC_LENGTH, label + ": stored signature too short");
        Assertions.assertArrayEquals(Arrays.copyOf(ONE_PIXEL_PNG, PNG_MAGIC_LENGTH),
                Arrays.copyOf(stored, PNG_MAGIC_LENGTH), label + ": the stored signature must start with the PNG magic");
        List<String> chunks = pngChunkTypes(stored, label + " (stored)");
        Assertions.assertEquals("IHDR", chunks.get(0), label + ": IHDR must be the first chunk " + chunks);
        for (String textual : List.of("tEXt", "zTXt", "iTXt")) {
            Assertions.assertFalse(chunks.contains(textual),
                    label + ": the uploaded ancillary " + textual + " chunk must not be stored " + chunks);
        }
        BufferedImage expected = decodePng(uploaded, label + " (upload)");
        BufferedImage actual = decodePng(stored, label + " (stored)");
        Assertions.assertEquals(expected.getWidth(), actual.getWidth(), label + ": the stored width must match");
        Assertions.assertEquals(expected.getHeight(), actual.getHeight(), label + ": the stored height must match");
        int visiblePixels = 0;
        int mismatches = 0;
        String firstMismatch = null;
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                int want = expected.getRGB(x, y);
                int got = actual.getRGB(x, y);
                int wantAlpha = want >>> 24;
                if (wantAlpha != 0) {
                    visiblePixels++;
                }
                boolean same = wantAlpha == (got >>> 24) && (wantAlpha == 0 || (want & 0xFFFFFF) == (got & 0xFFFFFF));
                if (!same) {
                    mismatches++;
                    if (firstMismatch == null) {
                        firstMismatch = String.format(Locale.ROOT, "(%d,%d) upload %08x stored %08x", x, y, want, got);
                    }
                }
            }
        }
        Assertions.assertTrue(visiblePixels > 0, label + ": the upload must have visible pixels to compare");
        Assertions.assertEquals(0, mismatches,
                label + ": the stored pixels must equal the uploaded pixels; first mismatch " + firstMismatch);
    }

    /** Offset of the first {@code type} chunk (walking the chunk stream after the magic), or -1. */
    private static int chunkOffset(byte[] png, String type) {
        ByteBuffer buffer = ByteBuffer.wrap(png);
        int offset = PNG_MAGIC_LENGTH;
        while (offset + 8 <= png.length) {
            if (type.equals(new String(png, offset + 4, 4, StandardCharsets.US_ASCII))) {
                return offset;
            }
            offset += 12 + buffer.getInt(offset);
        }
        return -1;
    }

    /** True iff the chunk at {@code chunkOffset} is complete and its CRC (over type + data) is correct. */
    private static boolean chunkCrcValid(byte[] png, int chunkOffset) {
        int length = ByteBuffer.wrap(png).getInt(chunkOffset);
        if (length < 0 || chunkOffset + 12L + length > png.length) {
            return false;
        }
        CRC32 crc = new CRC32();
        crc.update(png, chunkOffset + 4, 4 + length);
        return ByteBuffer.wrap(png).getInt(chunkOffset + 8 + length) == (int) crc.getValue();
    }

    /** {@code png} with its first IDAT payload overwritten by non-zlib bytes and that chunk's CRC recomputed. */
    private static byte[] withGarbageIdatPayload(byte[] png) {
        byte[] corrupt = png.clone();
        int idat = chunkOffset(corrupt, "IDAT");
        Assertions.assertTrue(idat > 0, "the source PNG must have an IDAT chunk");
        int length = ByteBuffer.wrap(corrupt).getInt(idat);
        byte[] garbage = "corrupt-idat-payload|not-a-zlib-stream|".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < length; i++) {
            corrupt[idat + 8 + i] = garbage[i % garbage.length];
        }
        CRC32 crc = new CRC32();
        crc.update(corrupt, idat + 4, 4 + length);
        ByteBuffer.wrap(corrupt).putInt(idat + 8 + length, (int) crc.getValue());
        Assertions.assertTrue(chunkCrcValid(corrupt, idat), "the corrupted IDAT chunk must keep a valid CRC");
        return corrupt;
    }

    /** {@code png} cut off half-way through its IDAT payload (no IDAT CRC, no IEND). */
    private static byte[] truncatedInsideIdat(byte[] png) {
        int idat = chunkOffset(png, "IDAT");
        Assertions.assertTrue(idat > 0, "the source PNG must have an IDAT chunk");
        int length = ByteBuffer.wrap(png).getInt(idat);
        Assertions.assertTrue(length >= 8, "the IDAT payload must be long enough to cut in half: " + length);
        return Arrays.copyOf(png, idat + 8 + length / 2);
    }

    /**
     * Precondition of a decode-failure fixture: everything checked BEFORE decoding passes - the PNG magic, a
     * first chunk that is a 13-byte IHDR with a valid CRC, dimensions within the safe-decode bounds, bit
     * depth 8 and colour type 6 (RGBA).
     */
    private static void assertPreDecodeHeaderPasses(byte[] png, String label) {
        Assertions.assertArrayEquals(Arrays.copyOf(ONE_PIXEL_PNG, PNG_MAGIC_LENGTH),
                Arrays.copyOf(png, PNG_MAGIC_LENGTH), label + ": PNG magic");
        Assertions.assertEquals(13, ByteBuffer.wrap(png).getInt(PNG_MAGIC_LENGTH), label + ": IHDR length 13");
        Assertions.assertEquals("IHDR", new String(png, IHDR_TYPE_OFFSET, 4, StandardCharsets.US_ASCII),
                label + ": the first chunk must be IHDR");
        Assertions.assertTrue(chunkCrcValid(png, PNG_MAGIC_LENGTH), label + ": the IHDR CRC must be valid");
        int[] dimensions = declaredPngDimensions(png);
        Assertions.assertTrue(dimensions[0] > 0 && dimensions[1] > 0
                        && dimensions[0] <= MAX_SIGNATURE_SIDE_PX && dimensions[1] <= MAX_SIGNATURE_SIDE_PX
                        && (long) dimensions[0] * dimensions[1] <= MAX_SIGNATURE_PIXELS,
                label + ": dimensions within the safe-decode bounds: " + dimensions[0] + "x" + dimensions[1]);
        Assertions.assertEquals(8, png[IHDR_BIT_DEPTH_OFFSET] & 0xFF, label + ": bit depth 8");
        Assertions.assertEquals(6, png[IHDR_COLOUR_TYPE_OFFSET] & 0xFF, label + ": colour type 6 (RGBA)");
    }

    /** The JDK PNG reader cannot decode {@code png}: it throws or returns null. */
    private static void assertImageIoCannotDecode(byte[] png, String label) {
        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException | RuntimeException expected) {
            return;
        }
        Assertions.assertNull(decoded, label + ": ImageIO must not decode the fixture");
    }
}
