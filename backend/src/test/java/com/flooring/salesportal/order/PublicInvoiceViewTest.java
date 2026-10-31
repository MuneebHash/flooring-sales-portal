package com.flooring.salesportal.order;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flooring.salesportal.common.email.InvoiceLinkEmailRequest;
import com.flooring.salesportal.common.email.QuoteEmailRequest;
import com.flooring.salesportal.common.email.RecordingInvoiceAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingInvoiceEmailSender;
import com.flooring.salesportal.common.email.RecordingInvoiceLinkEmailSender;
import com.flooring.salesportal.common.email.RecordingQuoteAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
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

import java.io.IOException;
import java.math.BigDecimal;
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
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 16G PR 4 - the public, token-only invoice VIEW {@code GET /api/v1/public/invoices/{token}}
 * ({@link PublicInvoiceController} → {@link PublicInvoiceService#getView}). Authority:
 * docs/API-Contracts-Phase16G-Invoice-Link.md section 9.3 (the view, its 27 always-present keys, the
 * dead payload, the invariant 500 and the leak rule), with sections 4.5 (the protected
 * {@code invoice_link_expires_at}), 7.2 (the dead reasons and their messages), 7.4 (binding to the order's
 * CURRENT version), 7.5 (the lifecycle that produces each dead reason), 7.7 (lazy expiry), 7.8 (surface
 * isolation), 9.2 (the token-only rules of every public endpoint), 12 (LAID and CANCELLED), 14 (the codes)
 * and the PR 4 row of section 16 (the key and value leak scan of every public invoice payload, the real
 * Spring Security filter chain, no session and no {@code Set-Cookie}).
 *
 * <p><b>What is proven.</b>
 * <ol>
 *   <li>An {@code ACTIVE} link answers 200 {@code Invoice loaded.} with EXACTLY the 27 contract keys, every one
 *       present even when null: {@code state} {@code ACTIVE}, {@code message} null, the order's CURRENT version
 *       (its {@code version_number}, {@code yyyy-MM-dd} dates, frozen Invoice To words, details and terms, and
 *       its money written as numbers with two decimals, {@code gst_amount} = inc − ex), the order's number
 *       and flooring type, the PRESENTED link's own {@code expires_at} in whole seconds, and the business
 *       presentation read live: the name, {@code logo_path} VERBATIM, the accent, ABN and direct-deposit
 *       details trimmed (blank → null) and the Stripe link only as an absolute {@code https} URL. A view
 *       writes nothing.</li>
 *   <li>The stored words, dates and money are returned verbatim - nulls stay null, whitespace is kept - and a
 *       later live customer rename, address change, details change, business-terms change, line or price
 *       change or payment row never reaches the view (no live fallback, no recomputation).</li>
 *   <li>A business-settings change after the version shows on the next view (presentation is live).</li>
 *   <li>A payment and a void after the send are shown (the link is bound to the order, not to a version), and
 *       neither they nor a LAID or CANCELLED status touch the link.</li>
 *   <li>Every dead reason - {@code REPLACED} (a second send), {@code REWRITTEN} (D.2), {@code SIGNED} (the
 *       remote accept, D.8 and Path A) and {@code EXPIRED} (lazy expiry by the view, the accept or the pdf) -
 *       answers 200 with the 27 keys and only {@code state}, {@code business_name} (live) and the exact
 *       per-state {@code message}; accept and pdf answer the matching 410; a dead link never changes its
 *       reason, even past its expiry.</li>
 *   <li>An {@code ACTIVE} link over a SIGNED current version (planted) is a generic 500 with no data and no
 *       document value, and nothing is written.</li>
 *   <li>EVERY public payload this class produces (200 ACTIVE, 200 dead, 201, 400, 404, 409, 410, 415, 422 and
 *       500) is scanned in {@link #publicCall}: only allowlisted keys (never an id, file, token, contact, staff,
 *       marker, {@code accepted_*}, {@code terms_source}, {@code invoice_link_expires_at}, cost, GP, line or
 *       deposit key), numbers only under {@code version_number} and the five money keys, and none of the
 *       distinguishable seeded secrets as a value (customer email / mobile / phones, store contact,
 *       salesperson, installation address, charge code / name / cost, GP, slug, plaintext tokens and their
 *       hashes, storage paths, stored file names, ids as text). A dedicated test adds the distinguishable-id
 *       number check and a planted source quote version id.</li>
 *   <li>The protected {@code invoice_link_expires_at} is on the staff InvoiceDetail only, and every public
 *       response - each status above - is produced without a session and without a {@code Set-Cookie}
 *       through the REAL Spring Security filter chain.</li>
 * </ol>
 *
 * <p><b>Fixtures (self-seeded, Phase 14D rule).</b> {@link #setUp} inserts, inside the test transaction, a
 * fresh tenant: business {@value #SLUG} with SOFT and HARD terms and a PADDED presentation (logo path, accent,
 * ABN, direct-deposit details and Stripe link, each with surrounding spaces), store {@value #STORE_CODE} (with
 * a phone and an email the public surface must never show), salesperson {@value #SALESPERSON_CODE} and the
 * store access row. Each order is invoice-ready (customer {@value #FROZEN_NAME} with an email, a mobile and
 * two phones, INSTALLATION and BILLING rows, details of sale, a CONFIRMED lay date 45 days ahead, one
 * {@value #CHARGE_CODE_PREFIX} charge line of 236.40 ex at a cost of 41.37), and every invoice, link,
 * payment, void, Rewrite, status change, signature, quote and Path A conversion goes through the real
 * endpoints. Raw SQL only seeds and plants state (stored words, dates and money, presentation, live edits, a
 * backdated expiry, a signed current version), each write followed by a persistence-context clear. No V4
 * business, store, user or order is read or written.
 *
 * <p><b>Transaction.</b> The class is {@code @Transactional}: the public service's programmatic transactions
 * (gate, snapshot, signing) and the protected ones join the test transaction, so everything rolls back and
 * the services' rollback hooks delete the files written under {@value #STORAGE_BASE_DIR}. The committed
 * variants (a committed lazy-expiry flip, post-commit email ordering, the snapshot under concurrency) belong
 * to the commit and concurrency integration classes. The five recording senders are singletons whose state
 * survives the rollback, so they are reset before and after each test.
 */
@ExtendWith(SpringExtension.class)
// The SAME storage property as the other invoice/quote suites, so this class joins their cached Spring
// context. Files stay apart: every file lives under this test's own business and order directories.
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
@Transactional
class PublicInvoiceViewTest {

    /** Must equal the {@code @SpringBootTest} property above (asserted in {@link #setUp}). */
    private static final String STORAGE_BASE_DIR = "target/test-storage/quote-acceptance";

    // ---- The self-seeded tenant (never a V4 identity) ----
    private static final String SLUG = "pr4-public-view";
    private static final String BUSINESS_NAME = "Ironvale Public View Floors";
    private static final String RENAMED_BUSINESS_NAME = "Ironvale Renamed Live Floors";
    private static final String STORE_CODE = "IPV-ONE";
    private static final String STORE_NAME = "Quillmere Hidden Showroom";
    private static final String STORE_PHONE = "0277419988";
    private static final String STORE_EMAIL = "ipv.store.hidden@example.com";
    private static final String STORE_STREET = "55 Concealed Arcade";
    private static final String STORE_SUBURB = "Quillmere Heights";
    private static final String SALESPERSON_CODE = "PV1";
    private static final String SALESPERSON_FIRST_NAME = "Percival";
    private static final String SALESPERSON_LAST_NAME = "Vexworth";
    private static final String SALESPERSON_EMAIL = "percival.vexworth@ipv-staff.example.com";
    private static final String CHARGE_CODE_PREFIX = "IPVIE";
    private static final String CHARGE_NAME = "Ironvale concealed install charge";

    // ---- The saved customer and order of every invoice ----
    private static final String CUSTOMER_FIRST_NAME = "Marlowe";
    private static final String CUSTOMER_LAST_NAME = "Quince";
    /** What D.1 freezes from the saved customer into v1's customer_name_snapshot. */
    private static final String FROZEN_NAME = "Marlowe Quince";
    private static final String CUSTOMER_EMAIL = "hidden.customer.pr4@example.com";
    private static final String CUSTOMER_MOBILE = "0499417263";
    private static final String CUSTOMER_HOME_PHONE = "0283416172";
    private static final String CUSTOMER_WORK_PHONE = "0383416173";
    /** What D.1 freezes from the saved BILLING row. */
    private static final String BILLING_LINE1 = "12 Oxford Street";
    private static final String BILLING_LINE2 = "Paddington NSW 2021";
    private static final String INSTALL_STREET = "Installwood Lane";
    private static final String INSTALL_SUBURB = "Ryeholt";
    private static final String DETAILS_OF_SALE = "Supply and install wool carpet (Ironvale public view test)";
    private static final String SOFT_TERMS = "<p>Ironvale soft terms IPVS.</p>";
    private static final String HARD_TERMS = "<p>Ironvale hard terms IPVH.</p>";

    // ---- Money: one 236.40 ex charge line at a 41.37 cost; every value distinguishable from the others ----
    private static final String CHARGE_PRICE = "236.40";
    private static final String CHARGE_COST = "41.37";
    private static final String EXPECTED_EX = "236.40";
    private static final String EXPECTED_GST = "23.64";
    private static final String EXPECTED_INC = "260.04";
    /** The order's live GP amount (236.40 - 41.37) and GP % (/ 236.40): staff-only values, never public. */
    private static final String GP_AMOUNT = "195.03";
    private static final String GP_PERCENT = "82.50";
    private static final String PAYMENT_AMOUNT = "30.00";
    private static final String BALANCE_AFTER_PAYMENT = "230.04";
    /** Path A: the non-itemised quote total (inc), and the order's live GP after the D6b price write (250.00 ex). */
    private static final String QUOTE_TOTAL_INC = "275.00";
    private static final String QUOTE_GP_AMOUNT = "208.63";
    private static final String QUOTE_GP_PERCENT = "83.45";

    // ---- The business presentation: seeded padded, and what the ACTIVE view must show ----
    private static final String RAW_LOGO_PATH = "  /uploads/9/branding/logo.png  ";
    private static final String RAW_ACCENT = "  #0F766E  ";
    private static final String RAW_ABN = "  51 824 753 556  ";
    private static final String RAW_ACCOUNT_NAME = "  Ironvale Floors Trading  ";
    private static final String RAW_BANK_NAME = "  Kestrel Mutual Bank  ";
    private static final String RAW_BSB = " 062-000  ";
    private static final String RAW_ACCOUNT_NUMBER = "  10203040  ";
    private static final String RAW_STRIPE = "  https://buy.stripe.com/x  ";
    /** The seeded presentation as the view shows it: the logo path verbatim, everything else trimmed. */
    private static final Presentation PADDED_SHOWN = new Presentation(BUSINESS_NAME, RAW_LOGO_PATH, "#0F766E",
            "51 824 753 556", "Ironvale Floors Trading", "Kestrel Mutual Bank", "062-000", "10203040",
            "https://buy.stripe.com/x");

    // ---- Public surface ----
    private static final String PUBLIC_INVOICES = "/api/v1/public/invoices/";
    private static final String PUBLIC_QUOTES = "/api/v1/public/quotes/";
    private static final Pattern INVOICE_LINK_TOKEN = Pattern.compile("/i/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");
    private static final Pattern QUOTE_LINK_TOKEN = Pattern.compile("/q/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");
    /** A well-formed (43 URL-safe characters) token that was never minted. */
    private static final String UNKNOWN_TOKEN = "A".repeat(43);
    private static final DateTimeFormatter WHOLE_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final Pattern WHOLE_SECONDS_SHAPE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");

    // ---- Exact public messages and codes ----
    private static final String LOADED_MESSAGE = "Invoice loaded.";
    private static final String ACCEPTED_MESSAGE = "Invoice accepted.";
    private static final String REPLACED_CODE = "INVOICE_LINK_REPLACED";
    private static final String REPLACED_MESSAGE =
            "This invoice link has been replaced. Please use the latest invoice link.";
    private static final String REWRITTEN_CODE = "INVOICE_LINK_REWRITTEN";
    private static final String REWRITTEN_MESSAGE =
            "This invoice has been revised. Please contact the store for the updated invoice.";
    private static final String SIGNED_CODE = "INVOICE_LINK_SIGNED";
    private static final String SIGNED_MESSAGE =
            "This invoice has already been signed. Please contact the store if you need a copy.";
    private static final String EXPIRED_CODE = "INVOICE_LINK_EXPIRED";
    private static final String EXPIRED_MESSAGE = "This invoice link has expired. Please contact the store.";
    private static final String TOKEN_NOT_FOUND_CODE = "INVOICE_TOKEN_NOT_FOUND";
    private static final String TOKEN_NOT_FOUND_MESSAGE = "Invoice not found.";
    private static final String QUOTE_TOKEN_NOT_FOUND_CODE = "QUOTE_TOKEN_NOT_FOUND";
    private static final String QUOTE_TOKEN_NOT_FOUND_MESSAGE = "Quote not found.";
    private static final String STALE_CODE = "INVOICE_VERSION_STALE";
    private static final String STALE_MESSAGE = "Invoice updated, please refresh";
    private static final String INTERNAL_ERROR_CODE = "INTERNAL_SERVER_ERROR";
    private static final String INTERNAL_ERROR_MESSAGE = "An unexpected error occurred.";
    private static final String VALIDATION_FAILED_CODE = "VALIDATION_FAILED";
    private static final String VALIDATION_FAILED_MESSAGE = "One or more fields are invalid.";
    private static final String SIGNATURE_REQUIRED_CODE = "SIGNATURE_REQUIRED";
    private static final String SIGNATURE_REQUIRED_MESSAGE = "A customer signature is required to accept the invoice.";

    // ---- The public view's keys (16G contract section 9.3, OpenAPI PublicInvoiceView) ----
    private static final List<String> VIEW_KEYS = List.of(
            "state", "business_name", "business_logo_url", "accent_color", "business_abn",
            "payment_account_name", "payment_bank_name", "payment_bsb", "payment_account_number",
            "payment_stripe_link_url", "order_number", "flooring_type", "version_number", "invoice_date",
            "due_date", "customer_name", "customer_address_line1", "customer_address_line2", "details_of_sale",
            "sale_price_ex_gst", "gst_amount", "sale_price_inc_gst", "total_paid", "balance_due", "terms_html",
            "expires_at", "message");
    private static final Set<String> MONEY_KEYS = Set.of(
            "sale_price_ex_gst", "gst_amount", "sale_price_inc_gst", "total_paid", "balance_due");
    /** Every key any public invoice response may carry: the view, the accept result and the error envelope. */
    private static final Set<String> ALLOWED_PUBLIC_KEYS = allowedPublicKeys();
    /** The contract's "never present" list, by name (any {@code accepted_*} key is refused separately). */
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "invoice_id", "order_id", "invoice_token_id", "quote_version_id", "source_quote_version_id",
            "stored_file_id", "file_id", "storage_path", "file_name", "token", "token_hash", "hash",
            "customer_email", "email", "mobile", "phone", "home_phone", "work_phone",
            "created_by_user_id", "sent_by_user_id", "user_id", "business_id", "store_id",
            "salesperson", "salesperson_name", "salesperson_code", "staff", "store", "store_name", "store_phone",
            "store_email", "last_emailed_at", "terms_source", "invoice_link_expires_at", "cost", "total_cost",
            "line_cost", "gp", "gp_percent", "lines", "line_items", "deposit", "deposit_amount",
            "pdf_download_path", "sent_at", "emailed_at", "dead_at", "created_at");
    /** The same names (and {@code accepted_*}) as JSON keys anywhere in a raw body. */
    private static final Pattern FORBIDDEN_KEY_IN_BODY = Pattern.compile(
            "\"(?:invoice_id|order_id|invoice_token_id|quote_version_id|source_quote_version_id|stored_file_id|"
                    + "storage_path|file_name|token|token_hash|customer_email|email|mobile|phone|created_by_user_id|"
                    + "sent_by_user_id|salesperson|store_phone|store_email|last_emailed_at|terms_source|"
                    + "invoice_link_expires_at|cost|total_cost|gp|gp_percent|lines|deposit|accepted_[a-z_]*)\"\\s*:");

    // A real, decodable 1x1 PNG (the shared signature fixture of the invoice and quote suites).
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    private MockMvc mockMvc;
    private Tenant home;
    /** Every order's proposed lay date (45 days ahead, so the due date is never today). */
    private LocalDate layDate;
    private int orderSequence;
    /** This test's orders: their ids, files, tokens and quote rows are looked up for every leak scan. */
    private final List<Long> trackedOrders = new ArrayList<>();
    /** Distinguishable seeded or minted secrets: never a substring of any public payload. */
    private final Set<String> forbiddenValues = new LinkedHashSet<>();
    /** How many public responses went through {@link #publicCall} (and so through every public check). */
    private int publicResponses;

    @BeforeEach
    void setUp() {
        // apply(springSecurity()): the REAL Spring Security filter chain, so a security change that put the
        // public surface behind a session (or made it create one) is caught here.
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        resetRecorders();
        Assertions.assertEquals(STORAGE_BASE_DIR, storageBaseDir,
                "this class reads its files under the shared invoice/quote storage dir");
        Assertions.assertEquals(27, VIEW_KEYS.size(), "the contract view has exactly 27 keys");
        layDate = LocalDate.now().plusDays(45);
        orderSequence = 0;
        trackedOrders.clear();
        forbiddenValues.clear();
        publicResponses = 0;
        seedTenant();
    }

    @AfterEach
    void tearDown() {
        // Singletons: never leak recorded messages or an armed failure into another test or class.
        resetRecorders();
    }

    private void resetRecorders() {
        invoiceEmailSender.reset();
        invoiceLinkEmailSender.reset();
        invoiceAcceptanceNotificationSender.reset();
        quoteEmailSender.reset();
        quoteAcceptanceNotificationSender.reset();
    }

    // ================================================================
    // 1. The ACTIVE view: exact keys, the current version, the presented link, the live presentation
    // ================================================================

    /**
     * An {@code ACTIVE} link answers 200 {@code Invoice loaded.} whose data has EXACTLY the 27 contract keys
     * (each present), {@code state} {@code ACTIVE} and a null {@code message}. The document is the current
     * version v1 as D.1 stored it: {@code version_number} 1, the stored {@code invoice_date} and the
     * {@code due_date} (lay date - 2) as {@code yyyy-MM-dd}, the frozen Invoice To name and billing lines, the
     * details of sale, the frozen SOFT terms verbatim, and the money as JSON numbers with two decimals
     * (236.40 ex, 23.64 GST = inc - ex, 260.04 inc, 0.00 paid, 260.04 due); the order's number and
     * flooring type; the presented link's own {@code expires_at} (sent_at + 7 days) in whole seconds. The
     * presentation is read live from the business: the name, the logo path with its surrounding spaces kept
     * verbatim, and the accent, ABN, account name, bank, BSB, account number and HTTPS Stripe link trimmed. The
     * view writes nothing.
     */
    @Test
    void activeView_returnsExactlyThe27Keys_theCurrentVersionsStoredWordsDatesAndMoney_thePresentedLinksExpiry_andTheLivePresentation()
            throws Exception {
        long orderId = invoicedOrder();
        String token = sendLink(orderId);

        View view = viewActive(orderId, token, PADDED_SHOWN, "ACTIVE view");
        JsonNode data = view.data();

        // The fixture values, spelled out (viewActive compares with the stored rows).
        Assertions.assertEquals("1", view.numbers().get("version_number"), "v1 is the current version");
        assertMoneyTexts(view, EXPECTED_EX, EXPECTED_GST, EXPECTED_INC, "0.00", EXPECTED_INC, "ACTIVE view");
        assertExactWords(data, FROZEN_NAME, BILLING_LINE1, BILLING_LINE2, DETAILS_OF_SALE,
                textColumn(invoiceId(orderId, 1), "terms_snapshot"), "the words D.1 froze");
        String terms = data.path("terms_html").textValue();
        Assertions.assertNotNull(terms, "fixture: D.1 froze the business's SOFT terms");
        Assertions.assertTrue(terms.contains("Ironvale soft terms IPVS."), () -> "the frozen SOFT terms: " + terms);
        Assertions.assertEquals(layDate.minusDays(2).toString(), data.path("due_date").textValue(),
                "the stored due date (lay date - 2)");
        Assertions.assertEquals(STORE_CODE + "." + SALESPERSON_CODE + ".00001", data.path("order_number").textValue(),
                "the order number, in the locked format");
        Assertions.assertEquals("SOFT", data.path("flooring_type").textValue(), "the order's flooring type");

        Map<String, Object> link = tokenRow(token);
        Assertions.assertEquals(timestamp(link, "sent_at").plusDays(7), timestamp(link, "expires_at"),
                "fixture: the link expires 7 days after it was sent");
        Assertions.assertTrue(WHOLE_SECONDS_SHAPE.matcher(data.path("expires_at").textValue()).matches(),
                () -> "expires_at is whole seconds: " + data.path("expires_at"));
        Assertions.assertEquals("ACTIVE", link.get("status"), "a view never writes the link");
        Assertions.assertNull(link.get("dead_at"), "a view never writes the link");
    }

    /**
     * Blank presentation values are hidden: an accent, ABN, account name, bank, BSB, account number and Stripe
     * link of spaces (or other whitespace) are each null, and so are SQL NULLs. The logo path is the business's
     * {@code logo_path} VERBATIM, so a blank path is returned as it is stored and a NULL path is null. Every key
     * is still present.
     */
    @Test
    void activeView_blankOrNullPresentationValuesAreNull_theLogoPathIsReturnedVerbatim_everyKeyStillPresent()
            throws Exception {
        long orderId = invoicedOrder();
        String token = sendLink(orderId);

        setPresentation("   ", "   ", " \t ", "   ", "\n  ", "   ", "    ", "   ");
        viewActive(orderId, token, new Presentation(BUSINESS_NAME, "   ", null, null, null, null, null, null, null),
                "blank presentation");

        setPresentation(null, null, null, null, null, null, null, null);
        viewActive(orderId, token, new Presentation(BUSINESS_NAME, null, null, null, null, null, null, null, null),
                "NULL presentation");
    }

    /**
     * The Stripe link is shown only as an absolute {@code https} URL (trimmed); an {@code http}, upper-case
     * {@code HTTP}, {@code ftp}, {@code javascript:}, scheme-relative or relative value, a blank value and SQL
     * NULL are each null. Nothing else in the view changes.
     */
    @Test
    void activeView_stripeLink_onlyAnAbsoluteHttpsUrlIsShownTrimmed_everyOtherValueIsNull() throws Exception {
        long orderId = invoicedOrder();
        String token = sendLink(orderId);

        List<String[]> cases = List.of(
                new String[] {"https://buy.stripe.com/x", "https://buy.stripe.com/x"},
                new String[] {"  https://buy.stripe.com/x  ", "https://buy.stripe.com/x"},
                new String[] {"http://buy.stripe.com/x", null},
                new String[] {"HTTP://BUY.STRIPE.COM/X", null},
                new String[] {"ftp://buy.stripe.com/x", null},
                new String[] {"javascript:alert(1)", null},
                new String[] {"//buy.stripe.com/x", null},
                new String[] {"/pay", null},
                new String[] {"   ", null},
                new String[] {null, null});
        for (String[] stripeCase : cases) {
            String raw = stripeCase[0];
            String shown = stripeCase[1];
            Assertions.assertEquals(1, jdbcTemplate.update(
                    "UPDATE business SET stripe_payment_link_url = ? WHERE business_id = ?", raw, home.businessId()));
            clearJpaCache();
            viewActive(orderId, token, PADDED_SHOWN.withStripe(shown), "Stripe link '" + raw + "'");
        }
    }

    // ================================================================
    // 2. Stored words, dates and money: verbatim, no live fallback, no recomputation
    // ================================================================

    /**
     * The view shows the current version's STORED words and dates verbatim: with v1 planted as name
     * {@code "  Spaced  Name  "}, billing line 1 NULL, billing line 2 {@code "   "}, details with surrounding
     * whitespace, a tab and a newline, terms NULL, an invoice date of 15 January 2026 and no due date, the view
     * returns exactly those (null stays null, no trim, the stored dates). A later live customer rename, billing
     * and installation address change, order details change and business-terms change (SOFT, HARD and the
     * legacy column) never reach the view - there is no live fallback, not even for the null terms. Planted
     * terms with surrounding whitespace are returned verbatim too, and survive a further live terms change.
     */
    @Test
    void activeView_returnsTheVersionsStoredWordsAndDatesVerbatim_nullsAndWhitespaceKept_neverTheLiveCustomerAddressDetailsOrTerms()
            throws Exception {
        long orderId = invoicedOrder();
        String token = sendLink(orderId);
        long v1 = invoiceId(orderId, 1);
        String spacedName = "  Spaced  Name  ";
        String blankLine2 = "   ";
        String spacedDetails = "  Frozen details\twith a tab\nand a newline  ";
        plantWords(v1, spacedName, null, blankLine2, spacedDetails, null);
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE invoice SET invoice_date = DATE '2026-01-15', due_date = NULL WHERE invoice_id = ?", v1));
        clearJpaCache();

        JsonNode data = viewActive(orderId, token, PADDED_SHOWN, "planted words").data();
        assertExactWords(data, spacedName, null, blankLine2, spacedDetails, null, "planted words");
        assertText(data, "invoice_date", "2026-01-15", "planted words: the stored invoice date");
        assertText(data, "due_date", null, "planted words: the stored (absent) due date");

        // Live edits after the version: the customer, both addresses, the order's details and the business terms.
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE order_customer SET first_name = 'Livia', middle_name = 'Renamed', last_name = 'Liveworth' "
                        + "WHERE order_id = ?", orderId));
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE order_address SET unit_number = '9', street_number = '400', street = 'Live Billing Road', "
                        + "suburb = 'Liveton', postcode = '2999' WHERE order_id = ? AND address_type = 'BILLING'::address_type",
                orderId));
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE order_address SET street = 'Live Install Road', suburb = 'Liveburg' "
                        + "WHERE order_id = ? AND address_type = 'INSTALLATION'::address_type", orderId));
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE sales_order SET details_of_sale = 'Live details written after the version' WHERE order_id = ?",
                orderId));
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE business SET terms_soft = '<p>Live soft terms LIVE1.</p>', terms_hard = '<p>Live hard terms LIVE2.</p>', "
                        + "terms_and_conditions = '<p>Legacy live terms LIVE3.</p>' WHERE business_id = ?",
                home.businessId()));
        clearJpaCache();

        View afterLiveEdits = viewActive(orderId, token, PADDED_SHOWN, "after the live edits");
        assertExactWords(afterLiveEdits.data(), spacedName, null, blankLine2, spacedDetails, null, "after the live edits");
        assertText(afterLiveEdits.data(), "invoice_date", "2026-01-15", "after the live edits");
        assertText(afterLiveEdits.data(), "due_date", null, "after the live edits");
        for (String live : List.of("Livia", "Liveworth", "Live Billing Road", "Liveton", "Live Install Road",
                "Liveburg", "Live details written", "LIVE1", "LIVE2", "LIVE3", FROZEN_NAME, BILLING_LINE1)) {
            Assertions.assertFalse(afterLiveEdits.body().contains(live),
                    () -> "no live or earlier word '" + live + "' in: " + afterLiveEdits.body());
        }

        String spacedTerms = "\n  <p>Frozen terms IPVF.</p>  \n";
        plantWords(v1, spacedName, null, blankLine2, spacedDetails, spacedTerms);
        data = viewActive(orderId, token, PADDED_SHOWN, "planted terms").data();
        assertExactWords(data, spacedName, null, blankLine2, spacedDetails, spacedTerms, "planted terms");

        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE business SET terms_soft = '<p>Live soft terms LIVE4.</p>' WHERE business_id = ?",
                home.businessId()));
        clearJpaCache();
        data = viewActive(orderId, token, PADDED_SHOWN, "after a second terms edit").data();
        assertExactWords(data, spacedName, null, blankLine2, spacedDetails, spacedTerms, "after a second terms edit");
    }

    /**
     * The money is the current version's STORED money, never recomputed: after the version, a dearer live
     * charge line, an order price adjustment and a payment row written without a new version change nothing in
     * the view (236.40 / 23.64 / 260.04 / 0.00 / 260.04); money planted on the version (111.11 ex, 122.22 inc,
     * 22.22 paid, 100.00 due) is shown verbatim, with GST = inc - ex of the version (11.11).
     */
    @Test
    void activeView_moneyIsTheCurrentVersionsStoredMoney_neverRecomputedFromTheLiveLinesPriceOrPayments()
            throws Exception {
        long orderId = invoicedOrder();
        String token = sendLink(orderId);

        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE order_charge_line SET price_snapshot = 512.30, unit_price = 512.30, line_total = 512.30 "
                        + "WHERE order_id = ?", orderId));
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE sales_order SET price_adjustment_inc_gst = -12.34 WHERE order_id = ?", orderId));
        Assertions.assertEquals(1, jdbcTemplate.update(
                "INSERT INTO payment_transaction (order_id, payment_method, amount) VALUES (?, 'CASH'::payment_method, 50.00)",
                orderId));
        clearJpaCache();
        View view = viewActive(orderId, token, PADDED_SHOWN, "after live line, price and payment edits");
        assertMoneyTexts(view, EXPECTED_EX, EXPECTED_GST, EXPECTED_INC, "0.00", EXPECTED_INC,
                "after live line, price and payment edits");

        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE invoice SET sale_price_ex_gst = 111.11, sale_price_inc_gst = 122.22, total_paid = 22.22, "
                        + "balance_due = 100.00 WHERE invoice_id = ?", invoiceId(orderId, 1)));
        clearJpaCache();
        view = viewActive(orderId, token, PADDED_SHOWN, "planted money");
        assertMoneyTexts(view, "111.11", "11.11", "122.22", "22.22", "100.00", "planted money");
    }

    // ================================================================
    // 3. Live presentation
    // ================================================================

    /**
     * The business presentation is read live: after the version and the send, renaming the business and
     * changing its logo, accent, ABN, bank details and Stripe link shows the new values on the next view,
     * while the document (the version's words and money) is unchanged. Clearing them shows nulls again.
     */
    @Test
    void activeView_businessPresentationIsReadLive_aSettingsChangeAfterTheVersionShowsOnTheNextView() throws Exception {
        long orderId = invoicedOrder();
        String token = sendLink(orderId);
        viewActive(orderId, token, PADDED_SHOWN, "before the settings change");

        renameBusiness(RENAMED_BUSINESS_NAME);
        setPresentation("/uploads/9/branding/logo-v2.png", "#7C3AED", "11 222 333 444", "Ironvale Renamed Trading",
                "Harrier Credit Union", "112-879", "99887766", "https://buy.stripe.com/live_changed");
        View changed = viewActive(orderId, token, new Presentation(RENAMED_BUSINESS_NAME,
                        "/uploads/9/branding/logo-v2.png", "#7C3AED", "11 222 333 444", "Ironvale Renamed Trading",
                        "Harrier Credit Union", "112-879", "99887766", "https://buy.stripe.com/live_changed"),
                "after the settings change");
        assertExactWords(changed.data(), FROZEN_NAME, BILLING_LINE1, BILLING_LINE2, DETAILS_OF_SALE,
                textColumn(invoiceId(orderId, 1), "terms_snapshot"), "the document is unchanged");
        assertMoneyTexts(changed, EXPECTED_EX, EXPECTED_GST, EXPECTED_INC, "0.00", EXPECTED_INC,
                "the money is unchanged");

        setPresentation(null, null, null, null, null, null, null, null);
        viewActive(orderId, token, new Presentation(RENAMED_BUSINESS_NAME, null, null, null, null, null, null, null,
                null), "after clearing the settings");
    }

    // ================================================================
    // 4. Payments, voids and status changes after the send
    // ================================================================

    /**
     * The link is bound to the ORDER: after a payment (v2) the same link shows v2 - its version number, its
     * 30.00 paid and 230.04 due, and its own words (planted on v2 so content from two versions can never pass
     * as one) - and after the void (v3, which copies v2's words) it shows v3 with 0.00 paid and 260.04 due.
     * The link row is not touched by either (still ACTIVE, same expiry, no dead_at), and the view's
     * {@code expires_at} stays the link's.
     */
    @Test
    void activeView_followsTheOrdersCurrentVersion_afterAPaymentAndAVoid_theSameLinkStaysActiveAndUntouched()
            throws Exception {
        long orderId = invoicedOrder();
        String token = sendLink(orderId);
        Map<String, Object> linkBefore = tokenRow(token);
        viewActive(orderId, token, PADDED_SHOWN, "v1");

        long paymentId = recordCashPayment(orderId, PAYMENT_AMOUNT);
        Assertions.assertEquals(2, currentVersion(orderId), "fixture: the payment appended v2");
        plantWords(invoiceId(orderId, 2), "Version Two Name", "2/40 Version Two Road", "Versionton NSW 2222",
                "Version two details", "<p>Version two terms V2T.</p>");
        View afterPayment = viewActive(orderId, token, PADDED_SHOWN, "after the payment");
        Assertions.assertEquals("2", afterPayment.numbers().get("version_number"), "the payment's version is shown");
        assertMoneyTexts(afterPayment, EXPECTED_EX, EXPECTED_GST, EXPECTED_INC, PAYMENT_AMOUNT, BALANCE_AFTER_PAYMENT,
                "after the payment");
        assertExactWords(afterPayment.data(), "Version Two Name", "2/40 Version Two Road", "Versionton NSW 2222",
                "Version two details", "<p>Version two terms V2T.</p>", "after the payment: v2's own words");
        Assertions.assertEquals(linkBefore, tokenRow(token), "a payment never touches the link");

        voidPayment(orderId, paymentId);
        Assertions.assertEquals(3, currentVersion(orderId), "fixture: the void appended v3");
        View afterVoid = viewActive(orderId, token, PADDED_SHOWN, "after the void");
        Assertions.assertEquals("3", afterVoid.numbers().get("version_number"), "the void's version is shown");
        assertMoneyTexts(afterVoid, EXPECTED_EX, EXPECTED_GST, EXPECTED_INC, "0.00", EXPECTED_INC, "after the void");
        assertExactWords(afterVoid.data(), "Version Two Name", "2/40 Version Two Road", "Versionton NSW 2222",
                "Version two details", "<p>Version two terms V2T.</p>", "after the void: v3 carries v2's words");
        Assertions.assertEquals(linkBefore, tokenRow(token), "a void never touches the link");
    }

    /**
     * A status change never kills an invoice link (section 12): after the order is set LAID, and then
     * CANCELLED, through the real status endpoint, the same link is still ACTIVE, its row is untouched, and the
     * view is the very same document as before.
     */
    @Test
    void activeView_aStatusChangeNeverKillsTheLink_theViewIsTheSameDocumentWhenTheOrderIsLaidOrCancelled()
            throws Exception {
        long orderId = invoicedOrder();
        String token = sendLink(orderId);
        Map<String, Object> linkBefore = tokenRow(token);
        JsonNode baseline = viewActive(orderId, token, PADDED_SHOWN, "LEAD").data();

        for (String status : List.of("LAID", "CANCELLED")) {
            staffCall(patch(orderUrl(orderId) + "/status").session(session()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"order_status\": \"" + status + "\"}"), 200, "fixture: status " + status);
            Assertions.assertEquals(status, jdbcTemplate.queryForObject(
                    "SELECT order_status::text FROM sales_order WHERE order_id = ?", String.class, orderId),
                    "fixture: the order is " + status);
            JsonNode data = viewActive(orderId, token, PADDED_SHOWN, status).data();
            Assertions.assertEquals(baseline, data, status + ": the same document");
            Assertions.assertEquals(linkBefore, tokenRow(token), status + ": the link is untouched");
        }
    }

    // ================================================================
    // 5. Dead links: 200 with only state, business_name and message; 410 on the actions
    // ================================================================

    /**
     * A second send replaces the first link ({@code REPLACED}, {@code dead_at} = the new link's
     * {@code sent_at}): the old link answers 200 with only {@code state} {@code REPLACED}, the business name
     * and the exact message (every other key null, no number at all), accept and pdf answer 410
     * {@code INVOICE_LINK_REPLACED} whatever the body or parameter, and nothing is written; the new link is
     * the ACTIVE document. A replaced link past its expiry is still {@code REPLACED}.
     */
    @Test
    void deadLink_replacedByASecondSend_viewShowsOnlyStateBusinessNameAndMessage_actionsAre410_newLinkIsActive()
            throws Exception {
        long orderId = invoicedOrder();
        String first = sendLink(orderId);
        String second = sendLink(orderId);

        Map<String, Object> firstRow = tokenRow(first);
        Assertions.assertEquals("REPLACED", firstRow.get("status"), "fixture: the second send replaced the first link");
        Assertions.assertEquals(tokenRow(second).get("sent_at"), firstRow.get("dead_at"),
                "fixture: the replaced link died when the new one was sent");

        assertDeadEverywhere(orderId, first, "REPLACED", REPLACED_CODE, REPLACED_MESSAGE, BUSINESS_NAME, "replaced link");
        viewActive(orderId, second, PADDED_SHOWN, "the new link");
        assertDeadReasonSticksPastExpiry(first, "REPLACED", REPLACED_MESSAGE, BUSINESS_NAME, "replaced link");
    }

    /**
     * A manual Rewrite (D.2) appends v2 and kills the link as {@code REWRITTEN}: the view shows only
     * {@code state} {@code REWRITTEN}, the business name and the exact message, and the actions answer 410
     * {@code INVOICE_LINK_REWRITTEN} (even naming the new v2); the reason sticks past the expiry, and a new
     * link shows the revised v2 while the old one stays {@code REWRITTEN}.
     */
    @Test
    void deadLink_rewrittenByAManualRewrite_viewShowsOnlyStateBusinessNameAndMessage_actionsAre410() throws Exception {
        long orderId = invoicedOrder();
        String token = sendLink(orderId);
        rewrite(orderId, 1);
        Assertions.assertEquals(2, currentVersion(orderId), "fixture: the Rewrite appended v2");
        Map<String, Object> row = tokenRow(token);
        Assertions.assertEquals("REWRITTEN", row.get("status"), "fixture: the Rewrite killed the link");
        Assertions.assertNotNull(row.get("dead_at"), "fixture: the Rewrite stamped dead_at");

        assertDeadEverywhere(orderId, token, "REWRITTEN", REWRITTEN_CODE, REWRITTEN_MESSAGE, BUSINESS_NAME,
                "rewritten link");
        assertDeadReasonSticksPastExpiry(token, "REWRITTEN", REWRITTEN_MESSAGE, BUSINESS_NAME, "rewritten link");

        String fresh = sendLink(orderId);
        View revised = viewActive(orderId, fresh, PADDED_SHOWN, "a new link after the Rewrite");
        Assertions.assertEquals("2", revised.numbers().get("version_number"), "the new link shows the revised v2");
        Assertions.assertEquals("REWRITTEN", tokenRow(token).get("status"), "the old link keeps its reason");
    }

    /**
     * The remote accept signs through the link (201 exactly {@code {"data":{"state":"SIGNED"},"message":
     * "Invoice accepted."}}) and kills it as {@code SIGNED} at the signing time: the view shows only
     * {@code state} {@code SIGNED}, the business name and the exact message, the actions answer 410
     * {@code INVOICE_LINK_SIGNED}; the dead payload's business name is still read live (a rename shows), and
     * the reason sticks past the expiry.
     */
    @Test
    void deadLink_signedByTheRemoteAccept_viewShowsOnlyStateLiveBusinessNameAndMessage_actionsAre410() throws Exception {
        long orderId = invoicedOrder();
        String token = sendLink(orderId);
        acceptRemotely(token, 1);
        Assertions.assertEquals(2, currentVersion(orderId), "fixture: the remote accept appended v2");
        Map<String, Object> row = tokenRow(token);
        Assertions.assertEquals("SIGNED", row.get("status"), "fixture: the remote accept killed the link");
        Assertions.assertEquals(invoiceRow(orderId, 2).get("accepted_at"), row.get("dead_at"),
                "fixture: the link died at the signing time");

        assertDeadEverywhere(orderId, token, "SIGNED", SIGNED_CODE, SIGNED_MESSAGE, BUSINESS_NAME, "remotely signed link");

        renameBusiness(RENAMED_BUSINESS_NAME);
        assertDeadView(publicCall(publicView(token), 200, "after a rename"), "SIGNED", RENAMED_BUSINESS_NAME,
                SIGNED_MESSAGE, "after a rename: the dead payload's business name is live");
        assertDeadReasonSticksPastExpiry(token, "SIGNED", SIGNED_MESSAGE, RENAMED_BUSINESS_NAME, "remotely signed link");
    }

    /**
     * The in-store accept (D.8) signs v1 and kills the order's link as {@code SIGNED}
     * ({@code dead_at} = the signing time): the view shows only {@code state} {@code SIGNED}, the business name
     * and the exact message, the actions answer 410 {@code INVOICE_LINK_SIGNED}, and the reason sticks.
     */
    @Test
    void deadLink_signedInStoreByD8_viewShowsOnlyStateBusinessNameAndMessage_actionsAre410() throws Exception {
        long orderId = invoicedOrder();
        String token = sendLink(orderId);
        acceptInStore(orderId, 1);
        Assertions.assertEquals(2, currentVersion(orderId), "fixture: D.8 appended v2");
        Map<String, Object> row = tokenRow(token);
        Assertions.assertEquals("SIGNED", row.get("status"), "fixture: D.8 killed the link");
        Assertions.assertEquals(invoiceRow(orderId, 2).get("accepted_at"), row.get("dead_at"),
                "fixture: the link died at the signing time");

        assertDeadEverywhere(orderId, token, "SIGNED", SIGNED_CODE, SIGNED_MESSAGE, BUSINESS_NAME, "D.8-signed link");
        assertDeadReasonSticksPastExpiry(token, "SIGNED", SIGNED_MESSAGE, BUSINESS_NAME, "D.8-signed link");
    }

    /**
     * Create Invoice from the accepted quote (Path A) appends a SIGNED v2 carrying its source quote version and
     * kills the order's link as {@code SIGNED}: the view shows only {@code state} {@code SIGNED}, the business
     * name and the exact message (the scan in {@link #publicCall} also proves the quote version id, the quote
     * token and its hash never appear), and the actions answer 410 {@code INVOICE_LINK_SIGNED}.
     */
    @Test
    void deadLink_signedByCreateInvoiceFromTheAcceptedQuote_viewShowsOnlyStateBusinessNameAndMessage_actionsAre410()
            throws Exception {
        long orderId = invoicedOrder();
        String token = sendLink(orderId);
        forbiddenValues.addAll(List.of(QUOTE_GP_AMOUNT, QUOTE_GP_PERCENT));
        String quoteToken = issueQuote(orderId);
        acceptQuoteRemotely(quoteToken);
        createInvoiceFromAcceptedQuote(orderId);
        Assertions.assertEquals(2, currentVersion(orderId), "fixture: Path A appended v2");
        Assertions.assertNotNull(invoiceRow(orderId, 2).get("source_quote_version_id"),
                "fixture: v2 carries its source quote version");
        Assertions.assertNotNull(invoiceRow(orderId, 2).get("accepted_at"), "fixture: v2 is signed");
        Assertions.assertEquals("SIGNED", tokenRow(token).get("status"), "fixture: Path A killed the link");

        assertDeadEverywhere(orderId, token, "SIGNED", SIGNED_CODE, SIGNED_MESSAGE, BUSINESS_NAME, "Path A-signed link");
    }

    /**
     * Lazy expiry: an {@code ACTIVE} link past its {@code expires_at} is still reported as no live link by the
     * staff InvoiceDetail ({@code invoice_link_expires_at} null) WITHOUT being flipped by that read; the next
     * public GET flips it to {@code EXPIRED} (with a {@code dead_at}) and answers 200 with only {@code state}
     * {@code EXPIRED}, the business name and the exact message. No invoice row changes. The actions answer 410
     * {@code INVOICE_LINK_EXPIRED}, and the next view is still {@code EXPIRED}. An over-age link first touched
     * by the accept or by the pdf is flipped the same way (410 {@code INVOICE_LINK_EXPIRED}), and its view then
     * shows {@code EXPIRED}.
     */
    @Test
    void deadLink_expiredLazilyByAPublicRequest_viewShowsOnlyStateBusinessNameAndMessage_staffReadNeverFlipsIt()
            throws Exception {
        long orderId = invoicedOrder();
        String token = sendLink(orderId);
        backdateExpiry(token);

        JsonNode detail = currentInvoiceDetail(orderId);
        Assertions.assertTrue(detail.has("invoice_link_expires_at") && detail.path("invoice_link_expires_at").isNull(),
                () -> "an over-age ACTIVE link is no live link on InvoiceDetail: " + detail);
        Assertions.assertEquals("ACTIVE", tokenRow(token).get("status"), "a staff read never expires the link");
        List<Map<String, Object>> invoicesBefore = invoiceRows(orderId);

        MvcResult view = publicCall(publicView(token), 200, "expired link, first view");
        assertDeadView(view, "EXPIRED", BUSINESS_NAME, EXPIRED_MESSAGE, "expired link, first view");
        Map<String, Object> row = tokenRow(token);
        Assertions.assertEquals("EXPIRED", row.get("status"), "the public view flipped the link");
        Assertions.assertNotNull(row.get("dead_at"), "the flip stamped dead_at");
        Assertions.assertEquals(invoicesBefore, invoiceRows(orderId), "an expiry flip never alters an invoice row");

        assertDeadEverywhere(orderId, token, "EXPIRED", EXPIRED_CODE, EXPIRED_MESSAGE, BUSINESS_NAME, "expired link");
        Assertions.assertEquals(row, tokenRow(token), "an EXPIRED link never changes again");

        for (String firstTouch : List.of("accept", "pdf")) {
            long otherOrderId = invoicedOrder();
            String otherToken = sendLink(otherOrderId);
            backdateExpiry(otherToken);
            List<Map<String, Object>> otherInvoicesBefore = invoiceRows(otherOrderId);
            RequestBuilder action = "accept".equals(firstTouch)
                    ? publicAccept(otherToken, "1", signaturePart())
                    : publicPdf(otherToken, "1");
            String label = "an over-age link first touched by the " + firstTouch;
            assertExactError(publicCall(action, 410, label), EXPIRED_CODE, EXPIRED_MESSAGE, label);
            Assertions.assertEquals("EXPIRED", tokenRow(otherToken).get("status"), label + ": flipped to EXPIRED");
            Assertions.assertEquals(otherInvoicesBefore, invoiceRows(otherOrderId), label + ": no invoice row changes");
            assertDeadView(publicCall(publicView(otherToken), 200, label + ": view"), "EXPIRED", BUSINESS_NAME,
                    EXPIRED_MESSAGE, label + ": view");
        }
    }

    // ================================================================
    // 6. The invariant: an ACTIVE link over a signed current version
    // ================================================================

    /**
     * An {@code ACTIVE} link whose order's current version is signed (planted: the invariant of section 7.6
     * broken) is never presented as signable: the view answers 500 with exactly the generic error envelope
     * (no data, no details) and none of the document's values; nothing is written (the link stays ACTIVE, no
     * invoice row, no file, no email). The pdf and the accept refuse the same way.
     */
    @Test
    void invariantBreach_anActiveLinkOverASignedCurrentVersion_isAGeneric500WithNoContent_andNothingIsWritten()
            throws Exception {
        long orderId = invoicedOrder();
        String token = sendLink(orderId);
        long currentId = invoiceId(orderId, currentVersion(orderId));
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE invoice SET accepted_at = now(), accepted_customer_name = 'Planted Outside Signer' "
                        + "WHERE invoice_id = ?", currentId));
        clearJpaCache();
        Footprint before = footprint(orderId);
        List<String> documentValues = List.of(orderNumber(orderId), FROZEN_NAME, BILLING_LINE1, BILLING_LINE2,
                DETAILS_OF_SALE, "Ironvale soft terms", BUSINESS_NAME, "Kestrel Mutual Bank", "51 824 753 556",
                "buy.stripe.com", "/uploads/9/branding/logo.png", "#0F766E", EXPECTED_EX, EXPECTED_INC, EXPECTED_GST,
                "Planted Outside Signer", "ACTIVE", "SOFT");

        for (RequestBuilder request : List.of(publicView(token), publicPdf(token, "1"),
                publicAccept(token, "1", signaturePart()))) {
            MvcResult result = publicCall(request, 500, "invariant breach");
            assertExactError(result, INTERNAL_ERROR_CODE, INTERNAL_ERROR_MESSAGE, "invariant breach");
            String body = bodyOf(result);
            for (String value : documentValues) {
                Assertions.assertFalse(body.contains(value), () -> "the 500 carries no '" + value + "': " + body);
            }
            assertNothingWritten(orderId, before, "invariant breach");
        }
        Assertions.assertEquals("ACTIVE", tokenRow(token).get("status"), "the link is left as it was");
    }

    // ================================================================
    // 7. The leak scan
    // ================================================================

    /**
     * The ACTIVE payload with every sensitive value seeded - and, planted on the current unsigned version, a
     * source quote version id (the view has no field for it) - carries only the 27 allowlisted keys: none of
     * the contract's never-present keys (checked by name, by the {@code accepted_} prefix and by a raw-body
     * key regex), no value equal to a distinguishable seeded secret, and no id as a JSON number unless it
     * equals a legitimately expected number of the payload. The same version's staff InvoiceDetail DOES carry
     * the protected values ({@code invoice_id}, {@code created_by_user_id}, {@code terms_source} QUOTE,
     * {@code invoice_link_expires_at}), so their absence from the public payload is meaningful.
     */
    @Test
    void leakScan_theActivePayloadWithEverySecretSeededAndASourceQuoteVersionPlanted_carriesOnlyThe27AllowlistedFields()
            throws Exception {
        long orderId = invoicedOrder();
        issueQuote(orderId);
        long quoteVersionId = jdbcTemplate.queryForObject(
                "SELECT quote_version_id FROM quote_version WHERE order_id = ? AND status = 'ISSUED'", Long.class, orderId);
        String token = sendLink(orderId);
        long currentId = invoiceId(orderId, currentVersion(orderId));
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE invoice SET source_quote_version_id = ? WHERE invoice_id = ?", quoteVersionId, currentId));
        clearJpaCache();

        View view = viewActive(orderId, token, PADDED_SHOWN, "leak scan");
        String body = view.body();

        // Keys: exactly the allowlist; none of the never-present names.
        Set<String> keys = new TreeSet<>();
        collectKeys(JSON.readTree(body), keys);
        for (String key : keys) {
            Assertions.assertFalse(FORBIDDEN_KEYS.contains(key) || key.startsWith("accepted_"),
                    () -> "never-present key '" + key + "' in: " + body);
        }
        for (String key : List.of("invoice_id", "order_id", "invoice_token_id", "quote_version_id",
                "source_quote_version_id", "stored_file_id", "storage_path", "file_name", "token", "token_hash",
                "customer_email", "mobile", "phone", "created_by_user_id", "sent_by_user_id", "salesperson",
                "store_phone", "store_email", "last_emailed_at", "accepted_at", "accepted_customer_name",
                "accepted_signature_present", "accepted_signature_download_path", "terms_source",
                "invoice_link_expires_at", "cost", "total_cost", "gp", "gp_percent", "lines", "deposit")) {
            Assertions.assertFalse(body.contains("\"" + key + "\""), () -> "key '" + key + "' in: " + body);
        }

        // Values: every distinguishable secret, the planted source quote version id included.
        String lower = body.toLowerCase(Locale.ROOT);
        for (String secret : sensitiveValues()) {
            Assertions.assertFalse(lower.contains(secret.toLowerCase(Locale.ROOT)),
                    () -> "secret '" + secret + "' in: " + body);
        }
        Set<BigDecimal> payloadNumbers = new LinkedHashSet<>();
        view.numbers().values().forEach(text -> payloadNumbers.add(new BigDecimal(text)));
        Map<String, Object> current = invoiceRow(orderId, currentVersion(orderId));
        BigDecimal ex = (BigDecimal) current.get("sale_price_ex_gst");
        BigDecimal inc = (BigDecimal) current.get("sale_price_inc_gst");
        Set<BigDecimal> expectedNumbers = new LinkedHashSet<>(List.of(
                BigDecimal.valueOf(((Number) current.get("version_number")).longValue()), ex, inc.subtract(ex), inc,
                (BigDecimal) current.get("total_paid"), (BigDecimal) current.get("balance_due")));
        Set<Long> ids = sensitiveIds();
        Assertions.assertTrue(ids.contains(quoteVersionId), "fixture: the planted quote version id is scanned");
        Assertions.assertTrue(ids.contains(currentId), "fixture: the current invoice id is scanned");
        for (long id : ids) {
            BigDecimal idValue = BigDecimal.valueOf(id);
            boolean distinguishable = expectedNumbers.stream().noneMatch(n -> n.compareTo(idValue) == 0);
            if (distinguishable) {
                Assertions.assertTrue(payloadNumbers.stream().noneMatch(n -> n.compareTo(idValue) == 0),
                        () -> "id " + id + " appears as a number in: " + body);
            }
            Assertions.assertFalse(body.contains("\"" + id + "\""), () -> "id " + id + " as a string in: " + body);
        }
        Assertions.assertFalse(body.contains("/uploads/" + home.businessId() + "/orders/"),
                () -> "the storage path prefix in: " + body);

        // The staff InvoiceDetail of the same version does carry the protected values.
        JsonNode detail = currentInvoiceDetail(orderId);
        Assertions.assertEquals(currentId, detail.path("invoice_id").asLong(), "staff: invoice_id");
        Assertions.assertEquals(home.userId(), detail.path("created_by_user_id").asLong(), "staff: created_by_user_id");
        Assertions.assertEquals("QUOTE", detail.path("terms_source").asText(), "staff: the planted source shows as QUOTE");
        Assertions.assertEquals(view.data().path("expires_at").textValue(),
                detail.path("invoice_link_expires_at").asText(), "staff: invoice_link_expires_at is the live link's expiry");
    }

    /**
     * Surface isolation and unknown parity on every public invoice endpoint: a never-minted token, the stored
     * HASH presented as a token, a live quote token, a too-short and a too-long token and two tokens with
     * characters outside the URL-safe alphabet all answer the identical 404 {@code INVOICE_TOKEN_NOT_FOUND}
     * {@code Invoice not found.} on the view, the accept (whatever the body) and the pdf, and write nothing;
     * the live invoice token is unknown to every public QUOTE endpoint (404 {@code QUOTE_TOKEN_NOT_FOUND}
     * {@code Quote not found.}). Each token works on its own surface, and both links stay ACTIVE.
     */
    @Test
    void unknownMalformedHashAndQuoteTokens_answerTheIdentical404OnEveryInvoiceEndpoint_andTheInvoiceTokenIsUnknownToTheQuoteSurface()
            throws Exception {
        long orderId = invoicedOrder();
        String quoteToken = issueQuote(orderId);
        String invoiceToken = sendLink(orderId);

        viewActive(orderId, invoiceToken, PADDED_SHOWN, "the invoice token on its own surface");
        Assertions.assertEquals("ACTIVE", readJson(publicCall(publicQuoteView(quoteToken), 200,
                "the quote token on its own surface")).path("data").path("state").asText(),
                "positive control: the quote token is a live quote link");

        Footprint before = footprint(orderId);
        Map<String, Object> invoiceLinkBefore = tokenRow(invoiceToken);
        List<String> notInvoiceTokens = List.of(
                UNKNOWN_TOKEN,
                sha256Hex(invoiceToken),
                quoteToken,
                "A".repeat(42),
                "A".repeat(129),
                "A".repeat(42) + "=",
                invoiceToken.substring(0, 42) + "+");
        for (String presented : notInvoiceTokens) {
            String label = "token '" + presented + "'";
            for (RequestBuilder request : List.of(publicView(presented), publicPdf(presented, "1"),
                    publicPdf(presented, null), publicAccept(presented, "1", signaturePart()),
                    publicAccept(presented, null))) {
                assertExactError(publicCall(request, 404, label), TOKEN_NOT_FOUND_CODE, TOKEN_NOT_FOUND_MESSAGE, label);
            }
            assertNothingWritten(orderId, before, label);
        }

        for (RequestBuilder request : List.of(publicQuoteView(invoiceToken),
                post(PUBLIC_QUOTES + invoiceToken + "/viewed"),
                get(PUBLIC_QUOTES + invoiceToken + "/pdf"),
                multipart(PUBLIC_QUOTES + invoiceToken + "/accept").file(signaturePart()))) {
            assertExactError(publicCall(request, 404, "the invoice token on the quote surface"),
                    QUOTE_TOKEN_NOT_FOUND_CODE, QUOTE_TOKEN_NOT_FOUND_MESSAGE, "the invoice token on the quote surface");
        }
        assertNothingWritten(orderId, before, "the invoice token on the quote surface");
        Assertions.assertEquals(invoiceLinkBefore, tokenRow(invoiceToken), "the invoice link is untouched");
        Assertions.assertEquals("ACTIVE", jdbcTemplate.queryForObject(
                "SELECT status FROM quote_token WHERE token_hash = ?", String.class, sha256Hex(quoteToken)),
                "the quote link is untouched");
    }

    // ================================================================
    // 8. invoice_link_expires_at is staff-only; no session and no cookie on any public response
    // ================================================================

    /**
     * The protected InvoiceDetail carries {@code invoice_link_expires_at} = the live link's expiry, while the
     * public view of that link carries the same instant only as its own {@code expires_at} and never the
     * protected key - on the ACTIVE payload, on a 409 and a 400 of the same link, and on the dead payload after
     * a resend replaced it (where the protected key on staff moves to the new link).
     */
    @Test
    void invoiceLinkExpiresAt_isOnTheStaffInvoiceDetailOnly_neverAKeyOfAnyPublicPayload() throws Exception {
        long orderId = invoicedOrder();
        String token = sendLink(orderId);
        String expiry = WHOLE_SECONDS.format(timestamp(tokenRow(token), "expires_at"));

        JsonNode detail = currentInvoiceDetail(orderId);
        Assertions.assertEquals(expiry, detail.path("invoice_link_expires_at").asText(),
                "staff: the live link's expiry");

        View view = viewActive(orderId, token, PADDED_SHOWN, "ACTIVE view");
        Assertions.assertFalse(view.data().has("invoice_link_expires_at"), "public: never the protected key");
        Assertions.assertEquals(expiry, view.data().path("expires_at").textValue(),
                "public: the presented link's own expiry");
        List<String> bodies = List.of(view.body(),
                bodyOf(publicCall(publicPdf(token, "2"), 409, "stale pdf")),
                bodyOf(publicCall(publicPdf(token, "0"), 400, "invalid pdf version")));
        for (String body : bodies) {
            Assertions.assertFalse(body.contains("invoice_link_expires_at"), () -> "never the protected key: " + body);
        }

        String newer = sendLink(orderId);
        MvcResult dead = publicCall(publicView(token), 200, "replaced link");
        assertDeadView(dead, "REPLACED", BUSINESS_NAME, REPLACED_MESSAGE, "replaced link");
        Assertions.assertFalse(bodyOf(dead).contains("invoice_link_expires_at"), "dead: never the protected key");
        Assertions.assertEquals(WHOLE_SECONDS.format(timestamp(tokenRow(newer), "expires_at")),
                currentInvoiceDetail(orderId).path("invoice_link_expires_at").asText(),
                "staff: the protected key follows the order's new live link");
    }

    /**
     * Every public status this surface can answer - 200 (view and stored pdf), 201 (remote accept), 400
     * (pdf without a version), 404 (unknown token), 409 (stale pdf, stale accept), 410 (signed link), 415 (a
     * non-multipart accept), 422 (no signature) and 500 (the invariant) - goes through {@link #publicCall}:
     * no session is created, no cookie is set, and each JSON payload passes the leak scan. The 200 pdf is the
     * stored v1 PDF verbatim under its exact inline file name.
     */
    @Test
    void everyPublicStatus_200_201_400_404_409_410_415_422_500_isAnsweredWithoutASessionOrACookie_andLeaksNothing()
            throws Exception {
        long orderId = invoicedOrder();
        String token = sendLink(orderId);

        viewActive(orderId, token, PADDED_SHOWN, "200 view");
        MvcResult pdf = publicCall(publicPdf(token, "1"), 200, "200 pdf");
        Assertions.assertEquals("inline; filename=\"invoice-" + orderNumber(orderId) + "-v1.pdf\"",
                pdf.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION), "the stored file name, inline");
        Assertions.assertArrayEquals(storedPdfBytes(invoiceId(orderId, 1)), pdf.getResponse().getContentAsByteArray(),
                "the stored v1 PDF, verbatim");

        MvcResult missingVersion = publicCall(publicPdf(token, null), 400, "400 pdf without version_number");
        assertExactError(missingVersion, VALIDATION_FAILED_CODE, VALIDATION_FAILED_MESSAGE,
                List.<String[]>of(new String[] {"version_number", "Required."}), "400 pdf without version_number");

        assertExactError(publicCall(publicView(UNKNOWN_TOKEN), 404, "404 unknown token"),
                TOKEN_NOT_FOUND_CODE, TOKEN_NOT_FOUND_MESSAGE, "404 unknown token");
        assertExactError(publicCall(publicPdf(token, "2"), 409, "409 stale pdf"), STALE_CODE, STALE_MESSAGE,
                "409 stale pdf");
        assertExactError(publicCall(publicAccept(token, "2", signaturePart()), 409, "409 stale accept"),
                STALE_CODE, STALE_MESSAGE, "409 stale accept");
        assertExactError(publicCall(post(PUBLIC_INVOICES + token + "/accept").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version_number\": 1}"), 415, "415 JSON accept"),
                VALIDATION_FAILED_CODE, VALIDATION_FAILED_MESSAGE, "415 JSON accept");
        assertExactError(publicCall(publicAccept(token, "1"), 422, "422 no signature"),
                SIGNATURE_REQUIRED_CODE, SIGNATURE_REQUIRED_MESSAGE, "422 no signature");
        Assertions.assertEquals(1, currentVersion(orderId), "nothing was signed by the refusals");

        acceptRemotely(token, 1);
        assertDeadView(publicCall(publicView(token), 200, "200 dead view"), "SIGNED", BUSINESS_NAME, SIGNED_MESSAGE,
                "200 dead view");
        assertExactError(publicCall(publicPdf(token, "2"), 410, "410 signed pdf"), SIGNED_CODE, SIGNED_MESSAGE,
                "410 signed pdf");

        long otherOrderId = invoicedOrder();
        String otherToken = sendLink(otherOrderId);
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE invoice SET accepted_at = now(), accepted_customer_name = 'Planted Outside Signer' "
                        + "WHERE invoice_id = ?", invoiceId(otherOrderId, 1)));
        clearJpaCache();
        assertExactError(publicCall(publicView(otherToken), 500, "500 invariant"), INTERNAL_ERROR_CODE,
                INTERNAL_ERROR_MESSAGE, "500 invariant");

        Assertions.assertEquals(12, publicResponses, "every public response above went through the public checks");
    }

    // ================================================================
    // Assertions - the view
    // ================================================================

    /** What the ACTIVE view must show for the live business presentation (explicit, never re-derived here). */
    private record Presentation(String businessName, String logoUrl, String accentColor, String abn,
                                String accountName, String bankName, String bsb, String accountNumber,
                                String stripeLinkUrl) {

        Presentation withStripe(String stripe) {
            return new Presentation(businessName, logoUrl, accentColor, abn, accountName, bankName, bsb,
                    accountNumber, stripe);
        }
    }

    /** One asserted ACTIVE view: its data, its raw number texts by member, and the raw body. */
    private record View(JsonNode data, Map<String, String> numbers, String body) {
    }

    /**
     * GET the ACTIVE view of {@code token}, assert it exactly ({@link #assertActiveView}) and assert that the
     * read wrote nothing (no invoice, link, order, stored file, file on disk or email change: nothing is stamped
     * on a read).
     */
    private View viewActive(long orderId, String token, Presentation presentation, String label) throws Exception {
        Footprint before = footprint(orderId);
        MvcResult result = publicCall(publicView(token), 200, label);
        JsonNode data = assertActiveView(result, orderId, token, presentation, label);
        assertNothingWritten(orderId, before, label + ": a view writes nothing");
        String body = bodyOf(result);
        return new View(data, rawNumbersByField(body), body);
    }

    /**
     * The ACTIVE view, exactly: the envelope (data + {@code Invoice loaded.}), the 27 keys each present,
     * {@code state} ACTIVE, {@code message} null, {@code presentation} as given, the order's number and
     * flooring type, and the order's CURRENT version - its number, dates, frozen words, details, terms
     * (verbatim, null-aware) and money as the raw two-decimal texts of the stored values (GST = inc - ex) - and
     * the PRESENTED link's {@code expires_at} in whole seconds. Returns data.
     */
    private JsonNode assertActiveView(MvcResult result, long orderId, String token, Presentation presentation,
                                      String label) throws Exception {
        JsonNode root = readJson(result);
        assertLoadedEnvelope(root, label);
        JsonNode data = root.path("data");
        assertExactly27Keys(data, label);
        assertText(data, "state", "ACTIVE", label);
        assertText(data, "message", null, label);

        assertText(data, "business_name", presentation.businessName(), label);
        assertText(data, "business_logo_url", presentation.logoUrl(), label);
        assertText(data, "accent_color", presentation.accentColor(), label);
        assertText(data, "business_abn", presentation.abn(), label);
        assertText(data, "payment_account_name", presentation.accountName(), label);
        assertText(data, "payment_bank_name", presentation.bankName(), label);
        assertText(data, "payment_bsb", presentation.bsb(), label);
        assertText(data, "payment_account_number", presentation.accountNumber(), label);
        assertText(data, "payment_stripe_link_url", presentation.stripeLinkUrl(), label);

        assertText(data, "order_number", orderNumber(orderId), label);
        assertText(data, "flooring_type", jdbcTemplate.queryForObject(
                "SELECT flooring_type::text FROM sales_order WHERE order_id = ?", String.class, orderId), label);

        Map<String, Object> current = invoiceRow(orderId, currentVersion(orderId));
        assertText(data, "invoice_date", ((Date) current.get("invoice_date")).toLocalDate().toString(), label);
        Date dueDate = (Date) current.get("due_date");
        assertText(data, "due_date", dueDate == null ? null : dueDate.toLocalDate().toString(), label);
        assertText(data, "customer_name", (String) current.get("customer_name_snapshot"), label);
        assertText(data, "customer_address_line1", (String) current.get("customer_address_line1_snapshot"), label);
        assertText(data, "customer_address_line2", (String) current.get("customer_address_line2_snapshot"), label);
        assertText(data, "details_of_sale", (String) current.get("details_of_sale_snapshot"), label);
        assertText(data, "terms_html", (String) current.get("terms_snapshot"), label);

        Map<String, String> numbers = rawNumbersByField(bodyOf(result));
        Assertions.assertEquals(Set.of("version_number", "sale_price_ex_gst", "gst_amount", "sale_price_inc_gst",
                "total_paid", "balance_due"), numbers.keySet(), () -> label + ": the only numbers of the view");
        BigDecimal ex = (BigDecimal) current.get("sale_price_ex_gst");
        BigDecimal inc = (BigDecimal) current.get("sale_price_inc_gst");
        Assertions.assertEquals(String.valueOf(((Number) current.get("version_number")).intValue()),
                numbers.get("version_number"), () -> label + ": version_number is the current version");
        Assertions.assertEquals(ex.toPlainString(), numbers.get("sale_price_ex_gst"), () -> label + ": ex GST");
        Assertions.assertEquals(inc.subtract(ex).toPlainString(), numbers.get("gst_amount"), () -> label + ": GST");
        Assertions.assertEquals(inc.toPlainString(), numbers.get("sale_price_inc_gst"), () -> label + ": inc GST");
        Assertions.assertEquals(((BigDecimal) current.get("total_paid")).toPlainString(), numbers.get("total_paid"),
                () -> label + ": total paid");
        Assertions.assertEquals(((BigDecimal) current.get("balance_due")).toPlainString(), numbers.get("balance_due"),
                () -> label + ": balance due");
        for (String money : MONEY_KEYS) {
            Assertions.assertTrue(numbers.get(money).matches("-?\\d+\\.\\d{2}"),
                    () -> label + ": " + money + " is written with two decimals: " + numbers.get(money));
        }

        assertText(data, "expires_at", WHOLE_SECONDS.format(timestamp(tokenRow(token), "expires_at")), label);
        return data;
    }

    /** The view's words, exactly (null-aware): Invoice To name, billing lines, details and terms. */
    private static void assertExactWords(JsonNode data, String name, String line1, String line2, String details,
                                         String terms, String label) {
        assertText(data, "customer_name", name, label);
        assertText(data, "customer_address_line1", line1, label);
        assertText(data, "customer_address_line2", line2, label);
        assertText(data, "details_of_sale", details, label);
        assertText(data, "terms_html", terms, label);
    }

    /** The view's five money values as the server wrote them (two decimals, scale kept). */
    private static void assertMoneyTexts(View view, String ex, String gst, String inc, String paid, String due,
                                         String label) {
        Assertions.assertEquals(ex, view.numbers().get("sale_price_ex_gst"), () -> label + ": ex GST");
        Assertions.assertEquals(gst, view.numbers().get("gst_amount"), () -> label + ": GST (inc - ex)");
        Assertions.assertEquals(inc, view.numbers().get("sale_price_inc_gst"), () -> label + ": inc GST");
        Assertions.assertEquals(paid, view.numbers().get("total_paid"), () -> label + ": total paid");
        Assertions.assertEquals(due, view.numbers().get("balance_due"), () -> label + ": balance due");
    }

    /**
     * A dead link's view, exactly: the envelope, the 27 keys, {@code state}, {@code business_name} and
     * {@code message} as given and every other key null - no document content and no number at all.
     */
    private void assertDeadView(MvcResult result, String state, String businessName, String message, String label)
            throws Exception {
        JsonNode root = readJson(result);
        assertLoadedEnvelope(root, label);
        JsonNode data = root.path("data");
        assertExactly27Keys(data, label);
        ObjectNode expected = JSON.createObjectNode();
        for (String key : VIEW_KEYS) {
            expected.putNull(key);
        }
        expected.put("state", state);
        expected.put("business_name", businessName);
        expected.put("message", message);
        Assertions.assertEquals(expected, data,
                () -> label + ": a dead link carries only state, business_name and message: " + data);
        Assertions.assertTrue(rawNumbers(bodyOf(result)).isEmpty(), () -> label + ": no number in a dead payload");
    }

    /**
     * A dead link everywhere: the view (200, the minimal payload), then the accept - with the current version
     * and a valid signature, and with an empty body - and the pdf - with the current version and with no
     * version at all - each the exact 410 (the token gate runs before any body or parameter check), and
     * nothing written by any of them.
     */
    private void assertDeadEverywhere(long orderId, String token, String state, String code, String message,
                                      String businessName, String label) throws Exception {
        Footprint before = footprint(orderId);
        Map<String, Object> linkBefore = tokenRow(token);
        assertDeadView(publicCall(publicView(token), 200, label + ": view"), state, businessName, message, label);
        String current = String.valueOf(currentVersion(orderId));
        for (RequestBuilder request : List.of(publicAccept(token, current, signaturePart()), publicAccept(token, null),
                publicPdf(token, current), publicPdf(token, null))) {
            assertExactError(publicCall(request, 410, label + ": action"), code, message, label + ": action");
        }
        assertNothingWritten(orderId, before, label);
        Assertions.assertEquals(linkBefore, tokenRow(token), () -> label + ": a dead link is never written again");
    }

    /** A dead link past its expiry keeps its reason: the view still shows it and the row's status and dead_at stay. */
    private void assertDeadReasonSticksPastExpiry(String token, String state, String message, String businessName,
                                                  String label) throws Exception {
        backdateExpiry(token);
        Map<String, Object> before = tokenRow(token);
        assertDeadView(publicCall(publicView(token), 200, label + ": past expiry"), state, businessName, message,
                label + ": past expiry");
        Assertions.assertEquals(before, tokenRow(token), () -> label + ": a dead link never becomes EXPIRED");
    }

    private static void assertLoadedEnvelope(JsonNode root, String label) {
        Assertions.assertEquals(Set.of("data", "message"), keysOf(root), () -> label + ": the envelope: " + root);
        Assertions.assertEquals(LOADED_MESSAGE, root.path("message").asText(), () -> label + ": the message");
    }

    private static void assertExactly27Keys(JsonNode data, String label) {
        Assertions.assertTrue(data.isObject(), () -> label + ": data is an object: " + data);
        Assertions.assertEquals(new TreeSet<>(VIEW_KEYS), new TreeSet<>(keysOf(data)),
                () -> label + ": exactly the 27 contract keys: " + data);
        for (String key : VIEW_KEYS) {
            Assertions.assertTrue(data.has(key), () -> label + ": key " + key + " is present (even when null)");
        }
    }

    /** One view key, null-aware: null expects JSON null; otherwise a JSON string equal to {@code expected}. */
    private static void assertText(JsonNode data, String key, String expected, String label) {
        JsonNode node = data.get(key);
        Assertions.assertNotNull(node, () -> label + ": key " + key + " must be present");
        if (expected == null) {
            Assertions.assertTrue(node.isNull(), () -> label + ": " + key + " must be null but was " + node);
        } else {
            Assertions.assertTrue(node.isTextual(), () -> label + ": " + key + " must be a string but was " + node);
            Assertions.assertEquals(expected, node.textValue(), () -> label + ": " + key);
        }
    }

    /** Exactly the error envelope {@code {"error":{"code","message"}}}: no data, no details. */
    private static void assertExactError(MvcResult result, String code, String message, String label) throws Exception {
        assertExactError(result, code, message, List.of(), label);
    }

    /** Exactly the error envelope with these details ({field, message} pairs, in order; none = no details key). */
    private static void assertExactError(MvcResult result, String code, String message, List<String[]> details,
                                         String label) throws Exception {
        ObjectNode expected = JSON.createObjectNode();
        ObjectNode error = expected.putObject("error");
        error.put("code", code);
        error.put("message", message);
        if (!details.isEmpty()) {
            ArrayNode array = error.putArray("details");
            for (String[] detail : details) {
                array.addObject().put("field", detail[0]).put("message", detail[1]);
            }
        }
        JsonNode actual = readJson(result);
        Assertions.assertEquals(expected, actual, () -> label + ": the exact error envelope: " + actual);
    }

    // ================================================================
    // The public call: no session, no cookie, and the leak scan of every payload
    // ================================================================

    /**
     * Perform a PUBLIC request (built without a session or cookie) through the real filter chain and assert
     * its status, that no session was created and no cookie set, and - for a JSON payload - the leak scan
     * ({@link #assertNoLeak}). Every public request of this class goes through here.
     */
    private MvcResult publicCall(RequestBuilder request, int expectedStatus, String label) throws Exception {
        clearJpaCache();
        MvcResult result = mockMvc.perform(request).andReturn();
        clearJpaCache();
        MockHttpServletResponse response = result.getResponse();
        Assertions.assertEquals(expectedStatus, response.getStatus(),
                () -> label + ": unexpected status; body: " + bodyOf(result));
        Cookie[] requestCookies = result.getRequest().getCookies();
        Assertions.assertTrue(requestCookies == null || requestCookies.length == 0,
                () -> label + ": a public request carries no cookie");
        Assertions.assertNull(result.getRequest().getSession(false),
                () -> label + ": a public request never has or creates a session");
        Assertions.assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty(),
                () -> label + ": a public response never sets a cookie: " + response.getHeaders(HttpHeaders.SET_COOKIE));
        Assertions.assertEquals(0, response.getCookies().length, () -> label + ": a public response adds no cookie");
        publicResponses++;

        String contentType = response.getContentType();
        Assertions.assertNotNull(contentType, () -> label + ": a content type");
        if (MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_JSON)) {
            boolean invoiceSurface = result.getRequest().getRequestURI().startsWith(PUBLIC_INVOICES);
            assertNoLeak(bodyOf(result), invoiceSurface || expectedStatus >= 400, label);
        } else {
            Assertions.assertEquals(200, expectedStatus, () -> label + ": only the stored PDF is not JSON");
            Assertions.assertEquals(MediaType.APPLICATION_PDF, MediaType.parseMediaType(contentType),
                    () -> label + ": the binary answer is the stored PDF");
        }
        return result;
    }

    /**
     * The leak scan of one public payload. Always: no distinguishable seeded or minted secret anywhere in the
     * body (case-insensitive) and no id as a string value. For an invoice-surface payload and any error
     * envelope ({@code strictShape}): every key on the public allowlist and none of the never-present names
     * (also as a raw-body key), and numbers only under {@code version_number} (a positive integer) and the five
     * money keys (two decimals).
     */
    private void assertNoLeak(String body, boolean strictShape, String label) throws Exception {
        JsonNode root = JSON.readTree(body);
        String lower = body.toLowerCase(Locale.ROOT);
        for (String secret : sensitiveValues()) {
            Assertions.assertFalse(lower.contains(secret.toLowerCase(Locale.ROOT)),
                    () -> label + ": leaked '" + secret + "' in: " + body);
        }
        Set<String> idTexts = new LinkedHashSet<>();
        sensitiveIds().forEach(id -> idTexts.add(String.valueOf(id)));
        List<String> texts = new ArrayList<>();
        collectTexts(root, texts);
        for (String text : texts) {
            Assertions.assertFalse(idTexts.contains(text.trim()), () -> label + ": an id as a value ('" + text + "'): " + body);
        }
        if (!strictShape) {
            return;
        }
        Set<String> keys = new TreeSet<>();
        collectKeys(root, keys);
        for (String key : keys) {
            Assertions.assertTrue(ALLOWED_PUBLIC_KEYS.contains(key), () -> label + ": key '" + key + "' is not allowlisted: " + body);
            Assertions.assertFalse(FORBIDDEN_KEYS.contains(key) || key.startsWith("accepted_"),
                    () -> label + ": never-present key '" + key + "': " + body);
        }
        Matcher forbiddenKey = FORBIDDEN_KEY_IN_BODY.matcher(body);
        Assertions.assertFalse(forbiddenKey.find(), () -> label + ": never-present key in the raw body: " + body);
        for (NumberToken number : rawNumbers(body)) {
            if (MONEY_KEYS.contains(number.field())) {
                Assertions.assertTrue(number.text().matches("-?\\d+\\.\\d{2}"),
                        () -> label + ": money with two decimals: " + number);
            } else {
                Assertions.assertEquals("version_number", number.field(),
                        () -> label + ": a number outside version_number and money: " + number + " in " + body);
                Assertions.assertTrue(number.text().matches("[1-9]\\d*"), () -> label + ": a version number: " + number);
            }
        }
    }

    /**
     * Every distinguishable secret of this test: the seeded contact, staff, address, charge, cost and GP
     * values and the slug, every minted token and its hash, and for each order every invoice and quote token
     * hash, the storage directory prefix and every stored file's storage path and file name.
     */
    private Set<String> sensitiveValues() {
        Set<String> values = new LinkedHashSet<>(forbiddenValues);
        values.add("/uploads/" + home.businessId() + "/orders/");
        for (long orderId : trackedOrders) {
            for (Map<String, Object> file : jdbcTemplate.queryForList(
                    "SELECT storage_path, file_name FROM stored_file WHERE storage_path LIKE ?",
                    orderStoragePrefix(orderId) + "%")) {
                values.add((String) file.get("storage_path"));
                values.add((String) file.get("file_name"));
            }
            values.addAll(jdbcTemplate.queryForList(
                    "SELECT token_hash FROM invoice_token WHERE order_id = ?", String.class, orderId));
            values.addAll(jdbcTemplate.queryForList(
                    "SELECT qt.token_hash FROM quote_token qt JOIN quote_version qv ON qv.quote_version_id = qt.quote_version_id "
                            + "WHERE qv.order_id = ?", String.class, orderId));
        }
        return values;
    }

    /** Every internal id of this test's tenant and orders (rows, tokens, files, quotes, payments, lines). */
    private Set<Long> sensitiveIds() {
        Set<Long> ids = new LinkedHashSet<>(List.of(home.businessId(), (long) home.storeId(), home.userId()));
        for (long orderId : trackedOrders) {
            ids.add(orderId);
            ids.addAll(longs("SELECT invoice_id FROM invoice WHERE order_id = ?", orderId));
            ids.addAll(longs("SELECT invoice_token_id FROM invoice_token WHERE order_id = ?", orderId));
            ids.addAll(longs("SELECT stored_file_id FROM stored_file WHERE storage_path LIKE ?",
                    orderStoragePrefix(orderId) + "%"));
            ids.addAll(longs("SELECT quote_version_id FROM quote_version WHERE order_id = ?", orderId));
            ids.addAll(longs("SELECT qt.quote_token_id FROM quote_token qt JOIN quote_version qv "
                    + "ON qv.quote_version_id = qt.quote_version_id WHERE qv.order_id = ?", orderId));
            ids.addAll(longs("SELECT quote_draft_id FROM quote_draft WHERE order_id = ?", orderId));
            ids.addAll(longs("SELECT payment_transaction_id FROM payment_transaction WHERE order_id = ?", orderId));
            ids.addAll(longs("SELECT order_charge_line_id FROM order_charge_line WHERE order_id = ?", orderId));
            ids.addAll(longs("SELECT charge_id FROM order_charge_line WHERE order_id = ?", orderId));
            ids.addAll(longs("SELECT order_customer_id FROM order_customer WHERE order_id = ?", orderId));
            ids.addAll(longs("SELECT order_address_id FROM order_address WHERE order_id = ?", orderId));
        }
        return ids;
    }

    private List<Long> longs(String sql, Object... args) {
        return jdbcTemplate.queryForList(sql, Long.class, args);
    }

    private static Set<String> allowedPublicKeys() {
        Set<String> keys = new LinkedHashSet<>(VIEW_KEYS);
        keys.addAll(List.of("data", "error", "code", "details", "field", "section"));
        return Set.copyOf(keys);
    }

    private static Set<String> keysOf(JsonNode node) {
        Set<String> keys = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static void collectKeys(JsonNode node, Set<String> keys) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                keys.add(entry.getKey());
                collectKeys(entry.getValue(), keys);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectKeys(child, keys));
        }
    }

    private static void collectTexts(JsonNode node, List<String> texts) {
        if (node.isTextual()) {
            texts.add(node.textValue());
        } else if (node.isObject() || node.isArray()) {
            node.forEach(child -> collectTexts(child, texts));
        }
    }

    /** A JSON number exactly as the server wrote it, with the name of the member that holds it. */
    private record NumberToken(String field, String text) {
    }

    /** Every JSON number in {@code body} with its raw text (scale kept), streamed so nothing is normalised. */
    private static List<NumberToken> rawNumbers(String body) throws IOException {
        List<NumberToken> numbers = new ArrayList<>();
        try (JsonParser parser = JSON.getFactory().createParser(body)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
                    numbers.add(new NumberToken(parser.currentName(), parser.getText()));
                }
            }
        }
        return numbers;
    }

    /** The raw number texts of a view body by member name (each member appears once). */
    private static Map<String, String> rawNumbersByField(String body) throws IOException {
        Map<String, String> byField = new LinkedHashMap<>();
        for (NumberToken number : rawNumbers(body)) {
            Assertions.assertNull(byField.put(number.field(), number.text()),
                    () -> "one number per member: " + number.field() + " in " + body);
        }
        return byField;
    }

    // ================================================================
    // "Nothing written"
    // ================================================================

    /** Everything a public request could write for one order, plus the database-wide counts and the senders. */
    private record Footprint(List<Map<String, Object>> invoices, List<Map<String, Object>> tokens,
                             List<Map<String, Object>> orderRow, long allInvoices, long allStoredFiles,
                             long allInvoiceTokens, int orderStoredFiles, Set<String> orderFiles,
                             int invoiceEmails, int failedInvoiceEmails, int notifications, int linkEmails) {
    }

    private Footprint footprint(long orderId) throws IOException {
        return new Footprint(
                invoiceRows(orderId),
                rows("SELECT * FROM invoice_token WHERE order_id = ? ORDER BY invoice_token_id", orderId),
                rows("SELECT * FROM sales_order WHERE order_id = ?", orderId),
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoice", Long.class),
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stored_file", Long.class),
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoice_token", Long.class),
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stored_file WHERE storage_path LIKE ?", Integer.class,
                        orderStoragePrefix(orderId) + "%"),
                filesOnDisk(orderId),
                invoiceEmailSender.sentEmails().size(),
                invoiceEmailSender.failedEmails().size(),
                invoiceAcceptanceNotificationSender.sentNotifications().size()
                        + invoiceAcceptanceNotificationSender.failedNotifications().size(),
                invoiceLinkEmailSender.sentEmails().size() + invoiceLinkEmailSender.failedEmails().size());
    }

    private void assertNothingWritten(long orderId, Footprint before, String label) throws IOException {
        Footprint after = footprint(orderId);
        Assertions.assertEquals(before.invoices(), after.invoices(), () -> label + ": no invoice row appended or changed");
        Assertions.assertEquals(before.tokens(), after.tokens(), () -> label + ": no invoice_token row written");
        Assertions.assertEquals(before.orderRow(), after.orderRow(), () -> label + ": the order row is untouched");
        Assertions.assertEquals(before.allInvoices(), after.allInvoices(), () -> label + ": no invoice row anywhere");
        Assertions.assertEquals(before.allStoredFiles(), after.allStoredFiles(), () -> label + ": no stored_file row");
        Assertions.assertEquals(before.allInvoiceTokens(), after.allInvoiceTokens(), () -> label + ": no new link");
        Assertions.assertEquals(before.orderStoredFiles(), after.orderStoredFiles(),
                () -> label + ": no stored_file row for the order");
        Assertions.assertEquals(before.orderFiles(), after.orderFiles(), () -> label + ": no file in the order's directory");
        Assertions.assertEquals(before.invoiceEmails(), after.invoiceEmails(), () -> label + ": no signed-PDF email");
        Assertions.assertEquals(before.failedInvoiceEmails(), after.failedInvoiceEmails(),
                () -> label + ": no signed-PDF email attempted");
        Assertions.assertEquals(before.notifications(), after.notifications(), () -> label + ": no store notification");
        Assertions.assertEquals(before.linkEmails(), after.linkEmails(), () -> label + ": no link email");
    }

    // ================================================================
    // Fixtures - the self-seeded tenant and orders
    // ================================================================

    /** The seeded business, its one store and its one salesperson (the session of every staff request). */
    private record Tenant(long businessId, int storeId, long userId) {
    }

    /**
     * The tenant: the business (terms, and the PADDED presentation {@link #PADDED_SHOWN} describes), one store
     * with a phone and an email, one salesperson with access to it. Every seeded contact, staff, address,
     * charge, cost and GP value and the slug are registered as secrets no public payload may contain.
     */
    private void seedTenant() {
        long businessId = jdbcTemplate.queryForObject(
                "INSERT INTO business (name, slug, is_active, terms_soft, terms_hard, logo_path, accent_colour, abn, "
                        + " account_name, bank_name, bsb, account_number, stripe_payment_link_url) "
                        + "VALUES (?, ?, TRUE, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING business_id",
                Long.class, BUSINESS_NAME, SLUG, SOFT_TERMS, HARD_TERMS, RAW_LOGO_PATH, RAW_ACCENT, RAW_ABN,
                RAW_ACCOUNT_NAME, RAW_BANK_NAME, RAW_BSB, RAW_ACCOUNT_NUMBER, RAW_STRIPE);
        int storeId = jdbcTemplate.queryForObject(
                "INSERT INTO store (business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'NSW', '2150') RETURNING store_id",
                Integer.class, businessId, STORE_NAME, STORE_CODE, STORE_PHONE, STORE_EMAIL, STORE_STREET, STORE_SUBURB);
        long userId = jdbcTemplate.queryForObject(
                "INSERT INTO app_user (business_id, first_name, last_name, salesperson_code, email, password_hash) "
                        + "VALUES (?, ?, ?, ?, ?, 'not-a-login-hash') RETURNING user_id",
                Long.class, businessId, SALESPERSON_FIRST_NAME, SALESPERSON_LAST_NAME, SALESPERSON_CODE,
                SALESPERSON_EMAIL);
        jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (?, ?, ?)",
                businessId, userId, storeId);
        home = new Tenant(businessId, storeId, userId);
        forbiddenValues.addAll(List.of(STORE_NAME, STORE_PHONE, STORE_EMAIL, STORE_STREET, STORE_SUBURB,
                SALESPERSON_FIRST_NAME, SALESPERSON_LAST_NAME, SALESPERSON_EMAIL, CUSTOMER_EMAIL, CUSTOMER_MOBILE,
                CUSTOMER_HOME_PHONE, CUSTOMER_WORK_PHONE, INSTALL_STREET, INSTALL_SUBURB, CHARGE_CODE_PREFIX,
                CHARGE_NAME, CHARGE_COST, GP_AMOUNT, GP_PERCENT, SLUG));
        clearJpaCache();
    }

    /**
     * An invoice-ready SOFT order with no invoice yet: customer {@value #FROZEN_NAME} (email, mobile, home and
     * work phones), INSTALLATION and BILLING rows, details of sale, a CONFIRMED lay date 45 days ahead, and one
     * {@value #CHARGE_CODE_PREFIX} charge line of 236.40 ex at a 41.37 cost (260.04 inc).
     */
    private long invoiceReadyOrder() {
        int sequence = ++orderSequence;
        // order_number must match chk_sales_order_number_format (V6): {store}.{LL#}.{#####}
        String orderNumber = STORE_CODE + "." + SALESPERSON_CODE + "." + String.format("%05d", sequence);
        long orderId = jdbcTemplate.queryForObject(
                "INSERT INTO sales_order (business_id, store_id, user_id, order_sequence_number, order_number, "
                        + " flooring_type, order_status, week_number, week_year, details_of_sale, "
                        + " proposed_lay_date, lay_date_status) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, 'LEAD'::order_status, 42, 2026, ?, ?, "
                        + " 'CONFIRMED'::lay_date_status) RETURNING order_id",
                Long.class, home.businessId(), home.storeId(), home.userId(), sequence, orderNumber,
                DETAILS_OF_SALE, Date.valueOf(layDate));
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, last_name, email, mobile, home_phone, work_phone) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                orderId, CUSTOMER_FIRST_NAME, CUSTOMER_LAST_NAME, CUSTOMER_EMAIL, CUSTOMER_MOBILE,
                CUSTOMER_HOME_PHONE, CUSTOMER_WORK_PHONE);
        jdbcTemplate.update(
                "INSERT INTO order_address (order_id, address_type, unit_number, street_number, street, suburb, "
                        + " state_code, postcode) VALUES (?, 'INSTALLATION'::address_type, NULL, '7', ?, ?, 'NSW', '2112')",
                orderId, INSTALL_STREET, INSTALL_SUBURB);
        jdbcTemplate.update(
                "INSERT INTO order_address (order_id, address_type, unit_number, street_number, street, suburb, "
                        + " state_code, postcode) "
                        + "VALUES (?, 'BILLING'::address_type, NULL, '12', 'Oxford Street', 'Paddington', 'NSW', '2021')",
                orderId);
        String chargeCode = CHARGE_CODE_PREFIX + sequence;
        long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, ?, ?, ?) RETURNING charge_id",
                Long.class, home.storeId(), chargeCode, CHARGE_NAME, new BigDecimal(CHARGE_PRICE),
                new BigDecimal(CHARGE_COST));
        jdbcTemplate.update(
                "INSERT INTO order_charge_line (order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?)",
                orderId, chargeId, chargeCode, CHARGE_NAME, new BigDecimal(CHARGE_PRICE), new BigDecimal(CHARGE_COST),
                new BigDecimal(CHARGE_PRICE), new BigDecimal(CHARGE_PRICE), new BigDecimal(CHARGE_COST));
        trackedOrders.add(orderId);
        clearJpaCache();
        return orderId;
    }

    /** An invoice-ready order with its invoice v1 created through D.1 (unsigned, its words frozen). */
    private long invoicedOrder() throws Exception {
        long orderId = invoiceReadyOrder();
        staffCall(post(orderUrl(orderId) + "/invoices").session(session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 201, "fixture: D.1 create");
        long v1 = invoiceId(orderId, 1);
        Assertions.assertEquals(FROZEN_NAME, textColumn(v1, "customer_name_snapshot"), "fixture: D.1 froze the name");
        Assertions.assertEquals(BILLING_LINE1, textColumn(v1, "customer_address_line1_snapshot"), "fixture: line 1");
        Assertions.assertEquals(BILLING_LINE2, textColumn(v1, "customer_address_line2_snapshot"), "fixture: line 2");
        Assertions.assertEquals(0, new BigDecimal(EXPECTED_INC).compareTo(
                (BigDecimal) invoiceRow(orderId, 1).get("sale_price_inc_gst")), "fixture: v1 is 260.04 inc");
        return orderId;
    }

    /**
     * The protected send-link (201) for the order's current invoice; returns the plaintext token read from the
     * recorded link email (the link appears exactly once) and registers it and its hash as secrets.
     */
    private String sendLink(long orderId) throws Exception {
        int before = invoiceLinkEmailSender.sentEmails().size();
        MvcResult result = staffCall(post(orderUrl(orderId) + "/invoices/current/send-link").session(session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 201, "fixture: send-link");
        List<InvoiceLinkEmailRequest> sent = invoiceLinkEmailSender.sentEmails();
        Assertions.assertEquals(before + 1, sent.size(), "fixture: one link email per send");
        InvoiceLinkEmailRequest email = sent.get(sent.size() - 1);
        Assertions.assertEquals(orderId, email.orderId(), "fixture: the link email is this order's");
        String token = extractToken(INVOICE_LINK_TOKEN, email.bodyText());
        registerToken(token);
        Assertions.assertFalse(bodyOf(result).contains(token), "fixture: the send-link response carries no token");
        return token;
    }

    /** A non-itemised 275.00 inc quote draft, issued by email; returns its plaintext public token. */
    private String issueQuote(long orderId) throws Exception {
        staffCall(put(orderUrl(orderId) + "/quote/draft").session(session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemised\": false, \"final_total_inc_gst\": " + QUOTE_TOTAL_INC + ", \"lines\": []}"),
                200, "fixture: quote draft");
        int before = quoteEmailSender.sentEmails().size();
        staffCall(post(orderUrl(orderId) + "/quote/send-email").session(session())
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 201, "fixture: quote send-email");
        List<QuoteEmailRequest> sent = quoteEmailSender.sentEmails();
        Assertions.assertEquals(before + 1, sent.size(), "fixture: one quote email");
        String token = extractToken(QUOTE_LINK_TOKEN, sent.get(sent.size() - 1).bodyText());
        registerToken(token);
        return token;
    }

    /** The public quote accept (signature only, no session): 201 {"data":{"state":"INACTIVE"}}. */
    private void acceptQuoteRemotely(String quoteToken) throws Exception {
        MvcResult result = publicCall(multipart(PUBLIC_QUOTES + quoteToken + "/accept").file(signaturePart()), 201,
                "fixture: public quote accept");
        Assertions.assertEquals("INACTIVE", readJson(result).path("data").path("state").asText(),
                "fixture: the quote was accepted");
    }

    /** Path A: Create Invoice from the order's accepted quote, naming that version (201). */
    private void createInvoiceFromAcceptedQuote(long orderId) throws Exception {
        long acceptedId = jdbcTemplate.queryForObject(
                "SELECT quote_version_id FROM quote_version WHERE order_id = ? AND status = 'ACCEPTED'", Long.class, orderId);
        staffCall(post(orderUrl(orderId) + "/quote/create-invoice").session(session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"quote_version_id\": " + acceptedId + "}"),
                201, "fixture: Path A create-invoice");
    }

    /** The public remote accept of {@code expectedVersion}: 201 with exactly the SIGNED result. */
    private void acceptRemotely(String token, int expectedVersion) throws Exception {
        MvcResult result = publicCall(publicAccept(token, String.valueOf(expectedVersion), signaturePart()), 201,
                "remote accept");
        ObjectNode expected = JSON.createObjectNode();
        expected.putObject("data").put("state", "SIGNED");
        expected.put("message", ACCEPTED_MESSAGE);
        Assertions.assertEquals(expected, readJson(result), "the remote accept's exact 201 body");
    }

    /** D.8 in-store accept of {@code expectedVersion} with a valid PNG (201). */
    private void acceptInStore(long orderId, int expectedVersion) throws Exception {
        staffCall(multipart(orderUrl(orderId) + "/invoices/current/accept").file(signaturePart())
                .param("expected_version_number", String.valueOf(expectedVersion)).session(session()),
                201, "fixture: D.8 in-store accept");
    }

    /** D.2 manual Rewrite carrying {@code currentVersion} as its expected_version_number (201; Batch A PR 2, #149). */
    private void rewrite(long orderId, int currentVersion) throws Exception {
        staffCall(post(orderUrl(orderId) + "/invoices/rewrite").session(session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expected_version_number\":" + currentVersion + "}"), 201, "fixture: D.2 rewrite");
    }

    /** D.7 CASH payment (appends a version): 201; returns the payment id. */
    private long recordCashPayment(long orderId, String amount) throws Exception {
        MvcResult result = staffCall(post(orderUrl(orderId) + "/payments").session(session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payment_method\": \"CASH\", \"amount\": " + amount + "}"), 201, "fixture: D.7 payment");
        long paymentId = readJson(result).path("data").path("payment_transaction").path("payment_transaction_id").asLong();
        Assertions.assertTrue(paymentId > 0, "fixture: D.7 returns the payment id");
        return paymentId;
    }

    /** D.10 void (appends a version): 201. */
    private void voidPayment(long orderId, long paymentId) throws Exception {
        staffCall(post(orderUrl(orderId) + "/payments/" + paymentId + "/void").session(session()), 201,
                "fixture: D.10 void");
    }

    /** D.3 current invoice: 200; returns data.invoice. */
    private JsonNode currentInvoiceDetail(long orderId) throws Exception {
        MvcResult result = staffCall(get(orderUrl(orderId) + "/invoices/current").session(session()), 200, "D.3");
        return readJson(result).path("data").path("invoice");
    }

    private void registerToken(String token) {
        forbiddenValues.add(token);
        forbiddenValues.add(sha256Hex(token));
    }

    /** Plant one version's stored words (name, billing lines, details, terms), then clear JPA. */
    private void plantWords(long invoiceId, String name, String line1, String line2, String details, String terms) {
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE invoice SET customer_name_snapshot = ?, customer_address_line1_snapshot = ?, "
                        + "customer_address_line2_snapshot = ?, details_of_sale_snapshot = ?, terms_snapshot = ? "
                        + "WHERE invoice_id = ?", name, line1, line2, details, terms, invoiceId),
                "fixture: plant exactly one version's words");
        clearJpaCache();
    }

    private void setPresentation(String logoPath, String accent, String abn, String accountName, String bankName,
                                 String bsb, String accountNumber, String stripe) {
        Assertions.assertEquals(1, jdbcTemplate.update(
                "UPDATE business SET logo_path = ?, accent_colour = ?, abn = ?, account_name = ?, bank_name = ?, "
                        + "bsb = ?, account_number = ?, stripe_payment_link_url = ? WHERE business_id = ?",
                logoPath, accent, abn, accountName, bankName, bsb, accountNumber, stripe, home.businessId()),
                "fixture: set the business presentation");
        clearJpaCache();
    }

    private void renameBusiness(String name) {
        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE business SET name = ? WHERE business_id = ?",
                name, home.businessId()), "fixture: rename the business");
        clearJpaCache();
    }

    /** Move one link's expiry into the past by the JVM clock (the clock the services compare with). */
    private void backdateExpiry(String token) {
        Assertions.assertEquals(1, jdbcTemplate.update("UPDATE invoice_token SET expires_at = ? WHERE token_hash = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(5)), sha256Hex(token)),
                "fixture: backdate exactly one link");
        clearJpaCache();
    }

    /**
     * Detach every hydrated entity. The MockMvc calls of a test share one transaction and persistence
     * context, so after a raw JDBC write a later JPA read (order, business) would otherwise return the stale
     * cached entity.
     */
    private void clearJpaCache() {
        entityManager.clear();
    }

    // ================================================================
    // Requests
    // ================================================================

    private static RequestBuilder publicView(String token) {
        return get(PUBLIC_INVOICES + token);
    }

    private static RequestBuilder publicQuoteView(String token) {
        return get(PUBLIC_QUOTES + token);
    }

    /** The public pdf with {@code versionNumber} as its query parameter (none when null). */
    private static RequestBuilder publicPdf(String token, String versionNumber) {
        return versionNumber == null
                ? get(PUBLIC_INVOICES + token + "/pdf")
                : get(PUBLIC_INVOICES + token + "/pdf").param("version_number", versionNumber);
    }

    /** The public accept: the form field {@code expected_version_number} (none when null) and these file parts. */
    private static RequestBuilder publicAccept(String token, String expectedVersion, MockMultipartFile... files) {
        MockMultipartHttpServletRequestBuilder request = multipart(PUBLIC_INVOICES + token + "/accept");
        if (expectedVersion != null) {
            request.param("expected_version_number", expectedVersion);
        }
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        return request;
    }

    private static MockMultipartFile signaturePart() {
        return new MockMultipartFile("signature", "signature.png", "image/png", ONE_PIXEL_PNG);
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        // Type trap: SessionContext casts (Long) user_id / business_id and (Integer) store_id.
        session.setAttribute("user_id", Long.valueOf(home.userId()));
        session.setAttribute("business_id", Long.valueOf(home.businessId()));
        session.setAttribute("store_id", Integer.valueOf(home.storeId()));
        return session;
    }

    private static String orderUrl(long orderId) {
        return "/api/v1/" + SLUG + "/orders/" + orderId;
    }

    /** Perform a STAFF request (with its session) and assert its status, with the body in the failure message. */
    private MvcResult staffCall(RequestBuilder request, int expectedStatus, String label) throws Exception {
        clearJpaCache();
        MvcResult result = mockMvc.perform(request).andReturn();
        clearJpaCache();
        Assertions.assertEquals(expectedStatus, result.getResponse().getStatus(),
                () -> label + ": unexpected status; body: " + bodyOf(result));
        return result;
    }

    private static String extractToken(Pattern link, String body) {
        Matcher matcher = link.matcher(body);
        Assertions.assertTrue(matcher.find(), () -> "the email body must carry the public link: " + body);
        String token = matcher.group(1);
        Assertions.assertFalse(matcher.find(), () -> "the public link must appear exactly once: " + body);
        Assertions.assertEquals(body.indexOf(token), body.lastIndexOf(token), "the token appears exactly once");
        return token;
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

    private List<Map<String, Object>> invoiceRows(long orderId) {
        return rows("SELECT * FROM invoice WHERE order_id = ? ORDER BY version_number", orderId);
    }

    private Map<String, Object> invoiceRow(long orderId, int versionNumber) {
        return new LinkedHashMap<>(jdbcTemplate.queryForMap(
                "SELECT * FROM invoice WHERE order_id = ? AND version_number = ?", orderId, versionNumber));
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

    private String textColumn(long invoiceId, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM invoice WHERE invoice_id = ?", String.class,
                invoiceId);
    }

    private String orderNumber(long orderId) {
        return jdbcTemplate.queryForObject("SELECT order_number FROM sales_order WHERE order_id = ?", String.class, orderId);
    }

    /** The link's row by the plaintext token's hash (status, sender, times; never the hash compared here). */
    private Map<String, Object> tokenRow(String token) {
        return new LinkedHashMap<>(jdbcTemplate.queryForMap(
                "SELECT invoice_token_id, order_id, status, sent_by_user_id, sent_at, expires_at, emailed_at, dead_at "
                        + "FROM invoice_token WHERE token_hash = ?", sha256Hex(token)));
    }

    private static LocalDateTime timestamp(Map<String, Object> row, String column) {
        Object value = row.get(column);
        Assertions.assertNotNull(value, () -> column + " must be set");
        return ((Timestamp) value).toLocalDateTime();
    }

    /** The order's storage directory, as FileStorageService lays it out: /uploads/{businessId}/orders/{orderId}/. */
    private String orderStoragePrefix(long orderId) {
        return "/uploads/" + home.businessId() + "/orders/" + orderId + "/";
    }

    private Set<String> filesOnDisk(long orderId) throws IOException {
        Path directory = Path.of(storageBaseDir).toAbsolutePath().normalize()
                .resolve(orderStoragePrefix(orderId).substring(1)).normalize();
        if (!Files.isDirectory(directory)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            Set<String> names = new TreeSet<>();
            files.forEach(file -> names.add(file.getFileName().toString()));
            return names;
        }
    }

    /** The version's stored PDF read from disk (base dir + storage_path). */
    private byte[] storedPdfBytes(long invoiceId) throws IOException {
        String storagePath = jdbcTemplate.queryForObject(
                "SELECT sf.storage_path FROM invoice i JOIN stored_file sf ON sf.stored_file_id = i.stored_file_id "
                        + "WHERE i.invoice_id = ?", String.class, invoiceId);
        Path onDisk = Path.of(storageBaseDir).toAbsolutePath().normalize().resolve(storagePath.substring(1)).normalize();
        Assertions.assertTrue(Files.isRegularFile(onDisk), () -> "the invoice PDF must exist on disk: " + onDisk);
        byte[] bytes = Files.readAllBytes(onDisk);
        Assertions.assertEquals("%PDF-", new String(bytes, 0, 5, StandardCharsets.US_ASCII), "PDF header");
        return bytes;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
