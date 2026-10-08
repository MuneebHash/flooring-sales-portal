package com.flooring.salesportal.order.quote;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.email.QuoteAcceptanceNotificationRequest;
import com.flooring.salesportal.common.email.QuoteEmailRequest;
import com.flooring.salesportal.common.email.RecordingQuoteAcceptanceNotificationSender;
import com.flooring.salesportal.common.email.RecordingQuoteEmailSender;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 16F PR1 — what a public quote ACCEPT ({@code POST /api/v1/public/quotes/{token}/accept}) does
 * to the ORDER's price and how it notifies the STORE.
 *
 * <ul>
 *   <li><b>D6b price coupling.</b> The signed inc-GST total becomes the order's working sale price:
 *       {@code price_adjustment_inc_gst = Q − calculated_total_inc_gst} (HALF_UP 2dp) REPLACES any
 *       existing manual override and the header scalars are recomputed by the order financial
 *       model — asserted with exact BigDecimal values on the DB columns and through the protected
 *       {@code GET /lines} and {@code GET /orders/{id}} reads. A below-cost quote (live cost above
 *       the FROZEN quote ex total) and a non-persistable derived price are public-safe 422s that
 *       persist nothing. A LAID order still accepts (and takes the price write) while the protected
 *       manual override keeps its LAID gate. Draft saves, previews, sends/resends and cancels never
 *       write the price; a send after an acceptance issues a new version without re-pricing.</li>
 *   <li><b>Store notification.</b> Exactly one, to the ORDER's own store email, carrying only the
 *       order number, version, frozen accepted name, accepted time and inc total — no link, token or
 *       storage reference; skipped for a NULL store email; a transport failure is non-fatal. The
 *       skip / failure WARN (captured with {@link OutputCaptureExtension}) names only the order id +
 *       quote version; the request type itself has no attachment / signature / token / link field.</li>
 * </ul>
 *
 * <p>Self-seeded (Phase 14D go-forward rule): orders (seq base 110_000, prefix {@code QAPRC.ZZ9.}),
 * customers, billing addresses and store catalog rows are INSERTed here; product/charge lines go
 * through the real line APIs (raw SQL only where a value is unreachable through the API); quotes are
 * issued through the protected send-email endpoint and the plaintext token is read from the recorded
 * email body. Only the V4–V6 business 1 / store 1 / user 1 / slug rows are relied on.
 *
 * <p>Runs in the class-level test transaction: the acceptance's {@code TransactionTemplate} JOINS it,
 * so the "post-commit" notification is recorded straight after the request (durable post-commit
 * behaviour needs a NON-transactional test and is not claimed here). Files land under the shared
 * {@code app.storage.base-dir=target/test-storage/quote-acceptance} and are removed by the services'
 * rollback hooks when the test transaction rolls back. Every request is bracketed by
 * {@code entityManager.clear()} because the services mix JPA reads with native writes inside this one
 * persistence context. Both recording senders are singletons and are reset before AND after each test.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = "app.storage.base-dir=target/test-storage/quote-acceptance")
@Transactional
class QuoteAcceptancePriceAndNotificationTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";

    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    private static final String CUSTOMER_EMAIL = "quote.accept.price@example.com";
    private static final String CUSTOMER_MOBILE = "0412345678";
    private static final String STORE_ONE_NOTIFY_EMAIL = "qaprc.store.one@example.com";
    private static final String OTHER_STORE_NOTIFY_EMAIL = "qaprc.other.store@example.com";

    private static final String PUBLIC_CONTACT_STORE_MESSAGE =
            "This quote can no longer be accepted online. Please contact the store.";
    private static final String ORDER_LOCKED_MESSAGE = "Order is laid and cannot be edited.";

    // The delivered public link is <app-base>/q/{token}; the base is not this class's concern.
    private static final Pattern PUBLIC_LINK_PATTERN = Pattern.compile("/q/([A-Za-z0-9_-]{43,128})");

    // A real, decodable 1x1 PNG (passes the magic + ImageIO safe-decode gate and embeds in the PDF).
    private static final byte[] SIGNATURE_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    // PNG framing: the 8-byte signature and the fixed 12-byte IEND chunk (length 0, "IEND", CRC AE426082).
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] PNG_IEND_CHUNK =
            {0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

    private static final DateTimeFormatter NOTIFICATION_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH);

    // Every QuoteAcceptanceService store-notification outcome log ("... skipped" / "... failed ...") starts so.
    private static final String NOTIFICATION_LOG_MARKER = "Quote acceptance store notification";

    // Money in JSON responses is read as exact decimals (never via binary doubles).
    private static final ObjectMapper EXACT_JSON =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingQuoteEmailSender quoteEmailSender;

    @Autowired
    private RecordingQuoteAcceptanceNotificationSender notificationSender;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${app.storage.base-dir}")
    private String storageBaseDir;

    private MockMvc mockMvc;

    private int seq = 110_000;

    private int catalogSeq = 0;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        quoteEmailSender.reset();
        notificationSender.reset();
    }

    @AfterEach
    void tearDown() {
        // Singletons: never leak recorded sends or an armed failNextSend into another test/class.
        quoteEmailSender.reset();
        notificationSender.reset();
    }

    // ================================================================
    // Below cost — blocks acceptance (public wording), persists nothing
    // ================================================================

    @Test
    void accept_liveCostAboveFrozenItemisedQuoteEx_422PublicBelowCost_nothingPersisted() throws Exception {
        long orderId = readyOrder();
        saveItemisedTwoLineDraft(orderId);                    // frozen at issue: 250.00 ex / 275.00 inc
        String token = sendEmailAndExtractToken(orderId);
        Assertions.assertEquals(Boolean.TRUE, versionRow(orderId, 1).get("itemised"));
        assertMoneyDb("250.00", versionRow(orderId, 1).get("quote_total_ex_gst"), "frozen quote_total_ex_gst");

        assertBelowCostAcceptRejected(orderId, token);
    }

    @Test
    void accept_liveCostAboveFrozenNonItemisedQuoteEx_422PublicBelowCost_nothingPersisted() throws Exception {
        long orderId = readyOrder();
        saveNonItemisedDraft(orderId, "275.00");              // frozen at issue: 250.00 ex / 275.00 inc
        String token = sendEmailAndExtractToken(orderId);
        Assertions.assertEquals(Boolean.FALSE, versionRow(orderId, 1).get("itemised"));
        assertMoneyDb("250.00", versionRow(orderId, 1).get("quote_total_ex_gst"), "frozen quote_total_ex_gst");

        assertBelowCostAcceptRejected(orderId, token);
    }

    @Test
    void accept_liveCostEqualToFrozenQuoteEx_isNotBelowCost_acceptsAtZeroGp() throws Exception {
        long orderId = readyOrder();
        saveNonItemisedDraft(orderId, "275.00");              // frozen 250.00 ex / 275.00 inc
        String token = sendEmailAndExtractToken(orderId);
        // Live cost rises to EXACTLY the frozen ex total: strictly-below is the rule, so equal passes.
        long chargeId = seedCatalogCharge("400.00", "250.00");
        addChargeLine(orderId, chargeId, "1");                // line 400.00 / cost 250.00 -> X = 440.00

        acceptOk(token);

        Assertions.assertEquals("ACCEPTED", versionRow(orderId, 1).get("status"));
        // adjustment = 275.00 - 440.00; sale ex = 250.00; gp = 250.00 - 250.00; gp% = 0.00.
        assertOrderPrice(orderId, "-165.00", "250.00", "250.00", "0.00", "0.00");
    }

    // ================================================================
    // D6b — the signed inc total becomes the order's working sale price
    // ================================================================

    @Test
    void accept_d6b_replacesManualOverride_signedIncTotalBecomesWorkingPrice_exactRounding() throws Exception {
        long orderId = readyOrder();
        long productId = seedCatalogProduct("41.30", "19.95");     // SQM-priced SOFT product
        long chargeId = seedCatalogCharge("26.15", "9.10");
        addProductLineSqm(orderId, productId, "12.50");            // 516.25 / cost 249.375 -> 249.38
        addChargeLine(orderId, chargeId, "2");                     // 52.30 / cost 18.20
        // calculated_total_inc_gst X = round(568.55 x 1.10 = 625.405, HALF_UP) = 625.41; cost 267.58.

        // An EXISTING manual override that the acceptance must replace (not add to).
        JsonNode manual = putSalePrice(orderId, "700.00").path("data").path("order_financial_summary");
        assertJsonMoney("625.41", manual, "calculated_total_inc_gst");
        assertJsonMoney("74.59", manual, "price_adjustment_inc_gst");
        assertMoneyDb("74.59", orderHeader(orderId).get("price_adjustment_inc_gst"), "pre-acceptance manual adjustment");

        // Q = 1234.57 inc (odd cents; a non-itemised quote carries Q verbatim, ex = 1122.34).
        saveNonItemisedDraft(orderId, "1234.57");
        String token = sendEmailAndExtractToken(orderId);
        Map<String, Object> issued = versionRow(orderId, 1);
        assertMoneyDb("1234.57", issued.get("quote_total_inc_gst"), "frozen quote_total_inc_gst");
        assertMoneyDb("1122.34", issued.get("quote_total_ex_gst"), "frozen quote_total_ex_gst");
        assertMoneyDb("74.59", orderHeader(orderId).get("price_adjustment_inc_gst"),
                "draft save + send never write the order price");
        Map<String, Object> draftBefore = draftRow(orderId);
        Map<String, Object> linesBefore = lineTotals(orderId);

        acceptOk(token);

        // Persisted header — exact (scale-2) BigDecimal equality.
        Map<String, Object> header = orderHeader(orderId);
        assertMoneyDb("609.16", header.get("price_adjustment_inc_gst"),
                "adjustment = Q - X = 1234.57 - 625.41, REPLACING the manual 74.59");
        assertMoneyDb("1122.34", header.get("sale_price_ex_gst"), "round(1234.57 / 1.10 = 1122.3363.., HALF_UP)");
        assertMoneyDb("267.58", header.get("total_cost"), "249.38 + 18.20");
        assertMoneyDb("854.76", header.get("gp"), "1122.34 - 267.58");
        assertMoneyDb("76.16", header.get("gp_percent"), "854.76 x 100 / 1122.34 = 76.1587..");
        Assertions.assertEquals(issued.get("quote_total_ex_gst"), header.get("sale_price_ex_gst"),
                "the order's sale ex-GST equals the frozen quote ex total (parity)");
        Assertions.assertEquals("LEAD", header.get("order_status"), "acceptance never changes the order status");
        Assertions.assertNull(header.get("last_emailed_at"), "the invoice-only email mirror is untouched");

        // One acceptance instant: accepted_at == token dead_at == sales_order.updated_at.
        Map<String, Object> accepted = versionRow(orderId, 1);
        Assertions.assertEquals("ACCEPTED", accepted.get("status"));
        Assertions.assertNotNull(accepted.get("accepted_at"));
        Assertions.assertEquals(accepted.get("accepted_at"), tokenRow(token).get("dead_at"));
        Assertions.assertEquals(accepted.get("accepted_at"), header.get("updated_at"));

        // Nothing else on the order moves: the draft and the product/charge lines are unchanged.
        Assertions.assertEquals(draftBefore, draftRow(orderId), "quote_draft untouched by the acceptance");
        Assertions.assertEquals(linesBefore, lineTotals(orderId), "order lines untouched by the acceptance");

        // Ordinary protected reads now show the signed price.
        JsonNode summary = getJson(linesUrl(orderId)).path("data").path("order_financial_summary");
        assertJsonMoney("516.25", summary, "product_subtotal");
        assertJsonMoney("52.30", summary, "charge_subtotal");
        assertJsonMoney("625.41", summary, "calculated_total_inc_gst");
        assertJsonMoney("609.16", summary, "price_adjustment_inc_gst");
        assertJsonMoney("1234.57", summary, "final_sale_price_inc_gst");
        assertJsonMoney("1122.34", summary, "sale_price_ex_gst");
        assertJsonMoney("267.58", summary, "total_cost");
        assertJsonMoney("854.76", summary, "gp");
        assertJsonMoney("76.16", summary, "gp_percent");
        assertJsonFalse(summary, "gp_warning");

        JsonNode persisted = getJson(orderUrl(orderId)).path("data").path("persisted_financials");
        assertJsonMoney("1122.34", persisted, "sale_price_ex_gst");
        assertJsonMoney("267.58", persisted, "total_cost");
        assertJsonMoney("854.76", persisted, "gp");
        assertJsonMoney("76.16", persisted, "gp_percent");
    }

    @Test
    void accept_laidOrder_allowed_writesSignedPrice_protectedSalePriceOverrideStillOrderLocked() throws Exception {
        long orderId = readyOrder();
        long chargeId = seedCatalogCharge("200.00", "80.00");
        addChargeLine(orderId, chargeId, "1");                // X = 220.00, cost 80.00
        saveNonItemisedDraft(orderId, "330.00");              // frozen 300.00 ex / 330.00 inc
        String token = sendEmailAndExtractToken(orderId);
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'LAID'::order_status WHERE order_id = ?", orderId);

        acceptOk(token);

        Assertions.assertEquals("ACCEPTED", versionRow(orderId, 1).get("status"));
        // adjustment = 330.00 - 220.00; sale ex = 300.00; gp = 220.00; gp% = 73.333.. -> 73.33.
        assertOrderPrice(orderId, "110.00", "300.00", "80.00", "220.00", "73.33");
        Map<String, Object> afterAccept = orderHeader(orderId);
        Assertions.assertEquals("LAID", afterAccept.get("order_status"), "acceptance never changes the order status");
        JsonNode summary = getJson(linesUrl(orderId)).path("data").path("order_financial_summary");
        assertJsonMoney("330.00", summary, "final_sale_price_inc_gst");

        // The protected manual override keeps its LAID gate — unchanged by 16F.
        perform(put(salePriceUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"final_sale_price_inc_gst\": 500.00}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORDER_LOCKED"))
                .andExpect(jsonPath("$.error.message").value(ORDER_LOCKED_MESSAGE));
        Assertions.assertEquals(afterAccept, orderHeader(orderId), "the rejected manual override wrote nothing");
    }

    @Test
    void accept_zeroTotalQuote_noLines_nullDetailsOfSale_accepts_zeroPriceWithNullGpPercent() throws Exception {
        long orderId = readyOrder();
        Assertions.assertNull(jdbcTemplate.queryForObject(
                "SELECT details_of_sale FROM sales_order WHERE order_id = ?", String.class, orderId));
        saveNonItemisedDraft(orderId, "0.00");
        String token = sendEmailAndExtractToken(orderId);
        Map<String, Object> issued = versionRow(orderId, 1);
        assertMoneyDb("0.00", issued.get("quote_total_inc_gst"), "frozen quote_total_inc_gst");
        assertMoneyDb("0.00", issued.get("quote_total_ex_gst"), "frozen quote_total_ex_gst");
        Assertions.assertNull(issued.get("details_of_sale_snapshot"), "blank details frozen as NULL");
        Map<String, Object> before = orderHeader(orderId);
        Assertions.assertNull(before.get("price_adjustment_inc_gst"), "self-seeded order starts unpriced");
        Assertions.assertNull(before.get("sale_price_ex_gst"), "self-seeded order starts unpriced");

        acceptOk(token);

        Assertions.assertEquals("ACCEPTED", versionRow(orderId, 1).get("status"));
        // NULL -> 0.00 proves the write happened; gp_percent stays NULL (sale ex is not > 0).
        assertOrderPrice(orderId, "0.00", "0.00", "0.00", "0.00", null);

        JsonNode summary = getJson(linesUrl(orderId)).path("data").path("order_financial_summary");
        assertJsonMoney("0.00", summary, "calculated_total_inc_gst");
        assertJsonMoney("0.00", summary, "price_adjustment_inc_gst");
        assertJsonMoney("0.00", summary, "final_sale_price_inc_gst");
        assertJsonMoney("0.00", summary, "sale_price_ex_gst");
        assertJsonMoney("0.00", summary, "total_cost");
        assertJsonMoney("0.00", summary, "gp");
        assertJsonNull(summary, "gp_percent");
        assertJsonFalse(summary, "gp_warning");

        JsonNode persisted = getJson(orderUrl(orderId)).path("data").path("persisted_financials");
        assertJsonMoney("0.00", persisted, "sale_price_ex_gst");
        assertJsonMoney("0.00", persisted, "total_cost");
        assertJsonMoney("0.00", persisted, "gp");
        assertJsonNull(persisted, "gp_percent");
    }

    @Test
    void accept_derivedAdjustmentBeyondDecimal10_2_422PublicBusinessRuleViolation_nothingPersisted() throws Exception {
        long orderId = readyOrder();
        saveNonItemisedDraft(orderId, "110.00");              // frozen 100.00 ex / 110.00 inc
        String token = sendEmailAndExtractToken(orderId);
        // Zero-cost SQL lines (so the below-cost rule passes) in BOTH tables: their line_total sum
        // 100,000,000.00 x 1.10 derives adjustment 110.00 - 110,000,000.00 = -109,999,890.00, beyond
        // DECIMAL(10,2). Either line alone would derive a persistable -54,999,890.00.
        insertZeroCostProductLineSql(orderId, "50000000.00");
        insertZeroCostChargeLineSql(orderId, "50000000.00");
        jdbcTemplate.update("UPDATE sales_order SET price_adjustment_inc_gst = 12.34, sale_price_ex_gst = 56.78, "
                + "total_cost = 0.00, gp = 56.78, gp_percent = 100.00, updated_at = TIMESTAMP '2025-01-01 09:00:00' "
                + "WHERE order_id = ?", orderId);

        Map<String, Object> headerBefore = orderHeader(orderId);
        int storedFilesBefore = storedFileCountForOrder(orderId);
        Set<String> diskBefore = filesOnDiskForOrder(orderId);

        MvcResult result = perform(acceptRequest(token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.message").value(PUBLIC_CONTACT_STORE_MESSAGE))
                .andExpect(jsonPath("$.error.details").doesNotExist())
                .andReturn();
        assertNoFigures(result.getResponse().getContentAsString());

        assertNothingPersisted(orderId, token, headerBefore, storedFilesBefore, diskBefore);
    }

    @Test
    void quoteDraftSave_preview_send_resend_supersede_cancel_neverWriteTheOrderPrice() throws Exception {
        long orderId = readyOrder();
        long chargeId = seedCatalogCharge("500.00", "210.00");
        addChargeLine(orderId, chargeId, "1");                // X = 550.00
        putSalePrice(orderId, "700.00");                      // manual adjustment 150.00
        jdbcTemplate.update("UPDATE sales_order SET updated_at = TIMESTAMP '2025-01-01 09:00:00' WHERE order_id = ?",
                orderId);
        Map<String, Object> pinned = orderHeader(orderId);
        assertMoneyDb("150.00", pinned.get("price_adjustment_inc_gst"), "manual adjustment before the quote flow");

        saveItemisedTwoLineDraft(orderId);                    // 275.00 inc — differs from the order's 700.00
        Assertions.assertEquals(pinned, orderHeader(orderId), "PUT quote/draft (itemised) must not write the price");

        perform(post(previewUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isOk());
        Assertions.assertEquals(pinned, orderHeader(orderId), "POST quote/preview-pdf must not write the price");

        String first = sendEmailAndExtractToken(orderId);
        Assertions.assertEquals(pinned, orderHeader(orderId), "the first send must not write the price");

        String resent = sendEmailAndExtractToken(orderId);   // unchanged draft -> same version, token REPLACED
        Assertions.assertEquals(1, versionCount(orderId), "an unchanged resend reuses version 1");
        Assertions.assertEquals("REPLACED", tokenRow(first).get("status"));
        Assertions.assertEquals("ACTIVE", tokenRow(resent).get("status"));
        Assertions.assertEquals(pinned, orderHeader(orderId), "a resend must not write the price");

        saveNonItemisedDraft(orderId, "999.99");
        Assertions.assertEquals(pinned, orderHeader(orderId), "PUT quote/draft (non-itemised) must not write the price");

        String superseding = sendEmailAndExtractToken(orderId);   // changed draft -> v2, v1 SUPERSEDED
        Assertions.assertEquals("SUPERSEDED", versionRow(orderId, 1).get("status"));
        Assertions.assertEquals("ISSUED", versionRow(orderId, 2).get("status"));
        Assertions.assertEquals(pinned, orderHeader(orderId), "a superseding send must not write the price");

        perform(post(cancelUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        Assertions.assertEquals("CANCELLED", tokenRow(superseding).get("status"));
        Assertions.assertEquals(pinned, orderHeader(orderId), "cancel must not write the price");

        assertNoNotification();
    }

    @Test
    void sendAfterAcceptance_issuesNewVersion_keepsManualPrice_secondAcceptanceAppliesItsSignedPrice() throws Exception {
        setStoreOneEmail(STORE_ONE_NOTIFY_EMAIL);
        long orderId = readyOrder();
        long chargeId = seedCatalogCharge("300.00", "120.00");
        addChargeLine(orderId, chargeId, "1");                // X = 330.00, cost 120.00
        saveItemisedTwoLineDraft(orderId);                    // v1 frozen 250.00 ex / 275.00 inc (itemised)
        String tokenV1 = sendEmailAndExtractToken(orderId);

        acceptOk(tokenV1);
        // adjustment = 275.00 - 330.00; sale ex 250.00; gp 130.00; gp% 52.00.
        assertOrderPrice(orderId, "-55.00", "250.00", "120.00", "130.00", "52.00");
        Map<String, Object> v1Accepted = acceptedFields(orderId, 1);

        // The salesperson re-prices manually AFTER the acceptance (allowed — the order is not LAID).
        JsonNode manual = putSalePrice(orderId, "512.34").path("data").path("order_financial_summary");
        assertJsonMoney("182.34", manual, "price_adjustment_inc_gst");
        assertOrderPrice(orderId, "182.34", "465.76", "120.00", "345.76", "74.24");
        Map<String, Object> manualHeader = orderHeader(orderId);

        saveNonItemisedDraft(orderId, "888.88");              // v2 will freeze 808.07 ex / 888.88 inc
        String tokenV2 = sendEmailAndExtractToken(orderId);

        Assertions.assertEquals(manualHeader, orderHeader(orderId),
                "a send after acceptance must not re-write the order price (manual value kept)");
        Assertions.assertEquals(2, versionCount(orderId), "no ISSUED version existed -> a NEW version");
        Map<String, Object> v2 = versionRow(orderId, 2);
        Assertions.assertEquals("ISSUED", v2.get("status"));
        assertMoneyDb("808.07", v2.get("quote_total_ex_gst"), "v2 frozen ex");
        assertMoneyDb("888.88", v2.get("quote_total_inc_gst"), "v2 frozen inc");
        Assertions.assertEquals(v1Accepted, acceptedFields(orderId, 1), "the accepted v1 is untouched by the new issue");
        Assertions.assertEquals("CONSUMED", tokenRow(tokenV1).get("status"));
        Assertions.assertEquals("ACTIVE", tokenRow(tokenV2).get("status"));

        acceptOk(tokenV2);

        // v2's signed price: adjustment = 888.88 - 330.00; sale ex 808.07; gp 688.07; gp% 85.149.. -> 85.15.
        assertOrderPrice(orderId, "558.88", "808.07", "120.00", "688.07", "85.15");
        Assertions.assertEquals("ACCEPTED", versionRow(orderId, 2).get("status"));
        Assertions.assertEquals(v1Accepted, acceptedFields(orderId, 1), "v1 stays signed history");

        List<QuoteAcceptanceNotificationRequest> sent = notificationSender.sentNotifications();
        Assertions.assertEquals(2, sent.size(), "one store notification per acceptance");
        Assertions.assertEquals(1, sent.get(0).quoteVersionNumber());
        QuoteAcceptanceNotificationRequest second = sent.get(1);
        Assertions.assertEquals(2, second.quoteVersionNumber());
        Assertions.assertEquals(orderId, second.orderId());
        Assertions.assertEquals(STORE_ONE_NOTIFY_EMAIL, second.recipientEmail());
        Assertions.assertTrue(second.bodyText().contains("(version 2)"), second.bodyText());
        Assertions.assertTrue(second.bodyText().contains("$888.88"), second.bodyText());
        Assertions.assertTrue(notificationSender.failedNotifications().isEmpty());

        JsonNode workspace = getJson(workspaceUrl(orderId)).path("data");
        Assertions.assertTrue(workspace.path("current_issued").isNull(), "no ISSUED version remains");
        JsonNode accepted = workspace.path("accepted");
        Assertions.assertEquals(2, accepted.path("version_number").asInt(), "latest accepted = v2");
        assertJsonMoney("888.88", accepted, "quote_total_inc_gst");
    }

    // ================================================================
    // Store notification
    // ================================================================

    @Test
    void notification_exactlyOne_toStoreEmail_frozenAcceptedName_noLinkTokenOrStoragePath() throws Exception {
        setStoreOneEmail(STORE_ONE_NOTIFY_EMAIL);
        long orderId = insertOrder("LEAD");
        seedCustomer(orderId, "Harriet", "Okafor-Lindqvist");
        seedBillingAddress(orderId);
        saveNonItemisedDraft(orderId, "12345.60");
        String token = sendEmailAndExtractToken(orderId);
        // A live customer edit AFTER issue never reaches the notification (accepted name = V17 snapshot).
        jdbcTemplate.update("UPDATE order_customer SET first_name = 'Renamed', last_name = 'Afterwards' "
                + "WHERE order_id = ?", orderId);
        String storeEmail = jdbcTemplate.queryForObject(
                "SELECT email FROM store WHERE store_id = ? AND business_id = ?",
                String.class, STORE_SYD_CBD, BUSINESS_AUSSIE);
        String orderNumber = orderNumberOf(orderId);

        acceptOk(token);

        List<QuoteAcceptanceNotificationRequest> sent = notificationSender.sentNotifications();
        Assertions.assertEquals(1, sent.size(), "exactly one store notification");
        Assertions.assertTrue(notificationSender.failedNotifications().isEmpty());
        QuoteAcceptanceNotificationRequest notification = sent.get(0);
        Assertions.assertEquals(storeEmail, notification.recipientEmail(), "recipient = the order's store email");
        Assertions.assertNotEquals(CUSTOMER_EMAIL, notification.recipientEmail(), "never the customer");
        Assertions.assertEquals("Quote accepted: " + orderNumber, notification.subject());
        Assertions.assertEquals(orderId, notification.orderId());
        Assertions.assertEquals(1, notification.quoteVersionNumber());

        Map<String, Object> accepted = versionRow(orderId, 1);
        Assertions.assertEquals("Harriet Okafor-Lindqvist", accepted.get("accepted_customer_name"));
        String acceptedTime = NOTIFICATION_TIME.format(((Timestamp) accepted.get("accepted_at")).toLocalDateTime());
        String body = notification.bodyText();
        Assertions.assertTrue(body.contains(orderNumber), body);
        Assertions.assertTrue(body.contains("(version 1)"), body);
        Assertions.assertTrue(body.contains("Harriet Okafor-Lindqvist"), body);
        Assertions.assertTrue(body.contains(acceptedTime), body);
        Assertions.assertTrue(body.contains("$12,345.60"), body);
        Assertions.assertEquals("Quote " + orderNumber + " (version 1) was accepted online by Harriet Okafor-Lindqvist on "
                        + acceptedTime + ".\n\n"
                        + "Accepted total (inc GST): $12,345.60.\n\n"
                        + "The signed quote is available in the sales portal.",
                body);

        List<String> storagePaths = List.of(
                storagePathOf(asLong(accepted.get("accepted_signature_file_id"))),
                storagePathOf(asLong(accepted.get("signed_pdf_file_id"))),
                issuedPdfStoragePath(orderId, 1));
        for (String text : List.of(notification.subject(), body)) {
            Assertions.assertFalse(text.contains("http"), "no link: " + text);
            Assertions.assertFalse(text.contains("/q/"), "no public quote path: " + text);
            Assertions.assertFalse(text.contains(token), "no token: " + text);
            Assertions.assertFalse(text.contains("/uploads/"), "no storage path: " + text);
            for (String storagePath : storagePaths) {
                Assertions.assertFalse(text.contains(storagePath), "no storage path: " + text);
            }
            Assertions.assertFalse(text.contains("Renamed"), "never the live customer: " + text);
            Assertions.assertFalse(text.toLowerCase(Locale.ROOT).contains("cost"), "no cost: " + text);
        }

        // Never duplicated: replaying the consumed link is a 410 and records nothing more.
        perform(acceptRequest(token))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("QUOTE_LINK_INACTIVE"));
        Assertions.assertEquals(1, notificationSender.sentNotifications().size());
        Assertions.assertTrue(notificationSender.failedNotifications().isEmpty());
    }

    @Test
    void notification_recipientIsTheOrdersOwnStoreEmail_notAnotherStoreOfTheBusiness() throws Exception {
        setStoreOneEmail(STORE_ONE_NOTIFY_EMAIL);
        int otherStoreId = insertStore("QAPRC-NOTIFY", OTHER_STORE_NOTIFY_EMAIL);
        long orderId = insertOrderInStore(otherStoreId, "LEAD");
        // Issued layer seeded directly (no store-2 session exists for the protected send): an ISSUED
        // non-itemised version (200.00 ex / 220.00 inc) + one ACTIVE token whose plaintext we hold.
        String token = seedIssuedNonItemisedVersionWithToken(orderId, "Second Store Customer", "200.00", "220.00");

        acceptOk(token);

        List<QuoteAcceptanceNotificationRequest> sent = notificationSender.sentNotifications();
        Assertions.assertEquals(1, sent.size());
        Assertions.assertEquals(OTHER_STORE_NOTIFY_EMAIL, sent.get(0).recipientEmail(),
                "the recipient follows the ORDER's store, not store 1");
        Assertions.assertEquals(orderId, sent.get(0).orderId());
        Assertions.assertTrue(sent.get(0).bodyText().contains("Second Store Customer"), sent.get(0).bodyText());
        Assertions.assertEquals("ACCEPTED", versionRow(orderId, 1).get("status"));
        // No lines: X = 0.00 -> adjustment = 220.00; sale ex 200.00; gp 200.00; gp% 100.00.
        assertOrderPrice(orderId, "220.00", "200.00", "0.00", "200.00", "100.00");
    }

    /**
     * A blank store email skips delivery (nothing recorded) while the acceptance + signed price persist.
     * {@code chk_store_email_format} admits only NULL or a non-blank address, so NULL is the only
     * reachable "blank" store email. The skip is a WARN naming only the order id + quote version — never
     * the replaced store address, the customer email, the accepted name, the link/token, money or a path.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void notification_nullStoreEmail_skipped_acceptanceAndSignedPriceStillPersist(CapturedOutput output)
            throws Exception {
        long orderId = readyOrder();
        long chargeId = seedCatalogCharge("100.00", "40.00");
        addChargeLine(orderId, chargeId, "1");                // X = 110.00, cost 40.00
        saveNonItemisedDraft(orderId, "220.00");              // frozen 200.00 ex / 220.00 inc
        String token = sendEmailAndExtractToken(orderId);
        String replacedStoreEmail = storeOneEmail();          // the committed (V4) address the NULL replaces
        jdbcTemplate.update("UPDATE store SET email = NULL WHERE store_id = ? AND business_id = ?",
                STORE_SYD_CBD, BUSINESS_AUSSIE);

        int logMark = output.getAll().length();
        acceptOk(token);
        String acceptLog = output.getAll().substring(logMark);

        Map<String, Object> version = versionRow(orderId, 1);
        Assertions.assertEquals("ACCEPTED", version.get("status"));
        Assertions.assertNotNull(version.get("accepted_at"));
        Assertions.assertNotNull(version.get("accepted_signature_file_id"));
        Assertions.assertNotNull(version.get("signed_pdf_file_id"));
        Assertions.assertEquals("CONSUMED", tokenRow(token).get("status"));
        assertOrderPrice(orderId, "110.00", "200.00", "40.00", "160.00", "80.00");
        assertNoNotification();

        Assertions.assertEquals("Quote Accepter", version.get("accepted_customer_name"));
        assertSafeNotificationLog(acceptLog, "Quote acceptance store notification skipped", orderId,
                sensitiveValues(replacedStoreEmail, CUSTOMER_EMAIL, "Quote Accepter", token));
    }

    /**
     * A transport failure is non-fatal: acceptance, signature, signed PDF, consumed token and price stay;
     * the attempt is recorded once as failed. The WARN names only the order id + quote version — never the
     * recipient, the accepted name, the link/token, money, a path or the exception's own message.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void notification_transportFailure_nonFatal_acceptanceConsumedTokenAndPriceStay(CapturedOutput output)
            throws Exception {
        setStoreOneEmail(STORE_ONE_NOTIFY_EMAIL);
        long orderId = readyOrder();
        long chargeId = seedCatalogCharge("100.00", "40.00");
        addChargeLine(orderId, chargeId, "1");                // X = 110.00, cost 40.00
        saveNonItemisedDraft(orderId, "220.00");              // frozen 200.00 ex / 220.00 inc
        String token = sendEmailAndExtractToken(orderId);
        notificationSender.failNextSend();

        int logMark = output.getAll().length();
        acceptOk(token);
        String acceptLog = output.getAll().substring(logMark);

        assertSafeNotificationLog(acceptLog, "Quote acceptance store notification failed", orderId,
                sensitiveValues(STORE_ONE_NOTIFY_EMAIL, CUSTOMER_EMAIL, "Quote Accepter", token));
        Assertions.assertFalse(acceptLog.contains("failed unexpectedly"),
                "a QuoteAcceptanceNotificationException takes the transport-failure branch: " + acceptLog);
        Assertions.assertFalse(acceptLog.contains("forced by RecordingQuoteAcceptanceNotificationSender"),
                "the exception message is never logged: " + acceptLog);

        Map<String, Object> version = versionRow(orderId, 1);
        Assertions.assertEquals("ACCEPTED", version.get("status"));
        Assertions.assertNotNull(version.get("accepted_at"));
        Assertions.assertEquals("Quote Accepter", version.get("accepted_customer_name"));
        Path signatureFile = physicalPath(storagePathOf(asLong(version.get("accepted_signature_file_id"))));
        Path signedPdfFile = physicalPath(storagePathOf(asLong(version.get("signed_pdf_file_id"))));
        Assertions.assertTrue(Files.exists(signatureFile), "signature PNG stays stored: " + signatureFile);
        Assertions.assertTrue(Files.exists(signedPdfFile), "signed PDF stays stored: " + signedPdfFile);
        Map<String, Object> token1 = tokenRow(token);
        Assertions.assertEquals("CONSUMED", token1.get("status"));
        Assertions.assertNotNull(token1.get("dead_at"));
        assertOrderPrice(orderId, "110.00", "200.00", "40.00", "160.00", "80.00");

        List<QuoteAcceptanceNotificationRequest> failed = notificationSender.failedNotifications();
        Assertions.assertEquals(1, failed.size(), "the failed attempt is recorded once");
        Assertions.assertEquals(STORE_ONE_NOTIFY_EMAIL, failed.get(0).recipientEmail());
        Assertions.assertEquals(orderId, failed.get(0).orderId());
        Assertions.assertEquals(1, failed.get(0).quoteVersionNumber());
        Assertions.assertTrue(notificationSender.sentNotifications().isEmpty(), "no retry / no duplicate send");
    }

    /**
     * Structural guard (spec §3): the store-notification request is EXACTLY (recipientEmail, subject,
     * bodyText, orderId, quoteVersionNumber) — the signed PDF stays portal-only, so there is no
     * attachment / PDF / signature component and no token or public-link component to fill by mistake.
     */
    @Test
    void notificationRequest_recordComponents_exactlyRecipientSubjectBodyOrderIdVersion_noAttachmentSignatureTokenOrLink() {
        Assertions.assertTrue(QuoteAcceptanceNotificationRequest.class.isRecord(), "the request is a record");
        RecordComponent[] components = QuoteAcceptanceNotificationRequest.class.getRecordComponents();

        Assertions.assertEquals(
                List.of("recipientEmail", "subject", "bodyText", "orderId", "quoteVersionNumber"),
                Arrays.stream(components).map(RecordComponent::getName).toList(),
                "the record components, in order");
        Assertions.assertEquals(
                List.of(String.class, String.class, String.class, long.class, int.class),
                Arrays.stream(components).map(RecordComponent::getType).toList(),
                "the record component types, in order");
        for (RecordComponent component : components) {
            String name = component.getName().toLowerCase(Locale.ROOT);
            for (String forbidden : List.of("pdf", "attach", "signature", "token", "link")) {
                Assertions.assertFalse(name.contains(forbidden),
                        "notification request component '" + component.getName() + "' must not carry '"
                                + forbidden + "'");
            }
        }
    }

    // ================================================================
    // Rollback cleanup — files written by a rolled-back acceptance are deleted
    // ================================================================

    @Test
    void accept_testTransactionRollback_deletesTheSignatureAndSignedPdfFiles() throws Exception {
        long orderId = readyOrder();
        saveNonItemisedDraft(orderId, "330.00");
        String token = sendEmailAndExtractToken(orderId);
        acceptOk(token);

        Map<String, Object> version = versionRow(orderId, 1);
        long versionId = asLong(version.get("quote_version_id"));
        Path signatureFile = physicalPath(storagePathOf(asLong(version.get("accepted_signature_file_id"))));
        Path signedPdfFile = physicalPath(storagePathOf(asLong(version.get("signed_pdf_file_id"))));
        Assertions.assertTrue(Files.exists(signatureFile), "signature PNG written: " + signatureFile);
        Assertions.assertTrue(Files.exists(signedPdfFile), "signed PDF written: " + signedPdfFile);
        // Spec §1 SERVER NORMALISATION: the stored signature is the upload RE-ENCODED as a clean PNG, never the
        // raw upload bytes — the same IMAGE (dimensions + pixels), so it is never compared byte-for-byte.
        byte[] storedSignature = Files.readAllBytes(signatureFile);
        Assertions.assertEquals((long) storedSignature.length,
                storedFileSize(asLong(version.get("accepted_signature_file_id"))),
                "stored_file.file_size is the stored (normalised) byte length");
        assertNormalisedSignature(SIGNATURE_PNG, storedSignature);
        Assertions.assertEquals("%PDF-",
                new String(Files.readAllBytes(signedPdfFile), 0, 5, StandardCharsets.US_ASCII));

        // Roll the test transaction back: the rows go AND the rollback hooks remove both new files.
        TestTransaction.flagForRollback();
        TestTransaction.end();

        Assertions.assertFalse(Files.exists(signatureFile), "rolled-back signature PNG must be deleted");
        Assertions.assertFalse(Files.exists(signedPdfFile), "rolled-back signed PDF must be deleted");
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_version WHERE quote_version_id = ?", Integer.class, versionId);
        Assertions.assertEquals(0, rows, "the accepted version row rolled back with its files");
    }

    // ================================================================
    // Shared scenario: below-cost rejection
    // ================================================================

    /**
     * After issue the LIVE cost rises above the frozen quote ex total (a 300.00-cost charge line vs
     * 250.00) → 422 {@code QUOTE_BELOW_COST} with the public wording only, and nothing is persisted.
     */
    private void assertBelowCostAcceptRejected(long orderId, String token) throws Exception {
        long chargeId = seedCatalogCharge("400.00", "300.00");
        addChargeLine(orderId, chargeId, "1");
        assertMoneyDb("300.00", orderHeader(orderId).get("total_cost"), "live total_cost after the cost rise");

        Map<String, Object> headerBefore = orderHeader(orderId);
        int storedFilesBefore = storedFileCountForOrder(orderId);
        Set<String> diskBefore = filesOnDiskForOrder(orderId);
        Assertions.assertTrue(diskBefore.contains(fileName(issuedPdfStoragePath(orderId, 1))),
                "the issued PDF is on disk before the attempt: " + diskBefore);

        MvcResult result = perform(acceptRequest(token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("QUOTE_BELOW_COST"))
                .andExpect(jsonPath("$.error.message").value(PUBLIC_CONTACT_STORE_MESSAGE))
                .andExpect(jsonPath("$.error.details").doesNotExist())
                .andReturn();
        assertNoFigures(result.getResponse().getContentAsString());

        assertNothingPersisted(orderId, token, headerBefore, storedFilesBefore, diskBefore);
    }

    /** A rejected accept leaves the issued layer, files, order price and notifications exactly as they were. */
    private void assertNothingPersisted(long orderId, String token, Map<String, Object> headerBefore,
                                        int storedFilesBefore, Set<String> diskBefore) throws Exception {
        Map<String, Object> version = versionRow(orderId, 1);
        Assertions.assertEquals("ISSUED", version.get("status"), "the version stays ISSUED");
        Assertions.assertNull(version.get("accepted_at"));
        Assertions.assertNull(version.get("accepted_customer_name"));
        Assertions.assertNull(version.get("accepted_signature_file_id"));
        Assertions.assertNull(version.get("signed_pdf_file_id"));
        Map<String, Object> tokenAfter = tokenRow(token);
        Assertions.assertEquals("ACTIVE", tokenAfter.get("status"), "the token stays ACTIVE");
        Assertions.assertNull(tokenAfter.get("dead_at"));
        Assertions.assertEquals(storedFilesBefore, storedFileCountForOrder(orderId), "no new stored_file rows");
        Assertions.assertEquals(diskBefore, filesOnDiskForOrder(orderId),
                "no new files under the order's storage dir (only the issued PDF)");
        Assertions.assertEquals(headerBefore, orderHeader(orderId), "order price columns unchanged");
        assertNoNotification();
    }

    // ================================================================
    // Request helpers (every request is bracketed by a persistence-context clear)
    // ================================================================

    private MockHttpSession liamStore1Session() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("user_id", USER_LIAM);
        s.setAttribute("business_id", BUSINESS_AUSSIE);
        s.setAttribute("store_id", STORE_SYD_CBD);
        return s;
    }

    private static String orderUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId;
    }

    private static String linesUrl(Object orderId) {
        return orderUrl(orderId) + "/lines";
    }

    private static String productLinesUrl(Object orderId) {
        return orderUrl(orderId) + "/product-lines";
    }

    private static String chargeLinesUrl(Object orderId) {
        return orderUrl(orderId) + "/charge-lines";
    }

    private static String salePriceUrl(Object orderId) {
        return orderUrl(orderId) + "/sale-price";
    }

    private static String draftUrl(Object orderId) {
        return orderUrl(orderId) + "/quote/draft";
    }

    private static String previewUrl(Object orderId) {
        return orderUrl(orderId) + "/quote/preview-pdf";
    }

    private static String sendEmailUrl(Object orderId) {
        return orderUrl(orderId) + "/quote/send-email";
    }

    private static String cancelUrl(Object orderId) {
        return orderUrl(orderId) + "/quote/cancel";
    }

    private static String workspaceUrl(Object orderId) {
        return orderUrl(orderId) + "/quote/workspace";
    }

    private static String publicAcceptUrl(String token) {
        return "/api/v1/public/quotes/" + token + "/accept";
    }

    /** Detach hydrated entities around a request — services mix JPA reads with native writes. */
    private ResultActions perform(RequestBuilder request) throws Exception {
        entityManager.clear();
        ResultActions actions = mockMvc.perform(request);
        entityManager.clear();
        return actions;
    }

    /** The public accept: ONE signature PNG part, no form field, NO session (the token is the credential). */
    private static MockMultipartHttpServletRequestBuilder acceptRequest(String token) {
        return multipart(publicAcceptUrl(token))
                .file(new MockMultipartFile("signature", "signature.png", "image/png", SIGNATURE_PNG));
    }

    private void acceptOk(String token) throws Exception {
        perform(acceptRequest(token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.state").value("INACTIVE"))
                .andExpect(jsonPath("$.message").value("Quote accepted."));
    }

    private JsonNode getJson(String url) throws Exception {
        MvcResult result = perform(get(url).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andReturn();
        return EXACT_JSON.readTree(result.getResponse().getContentAsString());
    }

    private void addProductLineSqm(long orderId, long productId, String quantitySqm) throws Exception {
        perform(post(productLinesUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\": " + productId + ", \"quantity_sqm\": " + quantitySqm + "}"))
                .andExpect(status().isCreated());
    }

    private void addChargeLine(long orderId, long chargeId, String quantity) throws Exception {
        perform(post(chargeLinesUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charge_id\": " + chargeId + ", \"quantity\": " + quantity + "}"))
                .andExpect(status().isCreated());
    }

    private JsonNode putSalePrice(long orderId, String finalSalePriceIncGst) throws Exception {
        MvcResult result = perform(put(salePriceUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"final_sale_price_inc_gst\": " + finalSalePriceIncGst + "}"))
                .andExpect(status().isOk())
                .andReturn();
        return EXACT_JSON.readTree(result.getResponse().getContentAsString());
    }

    /** Save a two-ITEM itemised draft (Carpet 2×100 + Underlay 1×50 = 250.00 ex / 275.00 inc). */
    private void saveItemisedTwoLineDraft(long orderId) throws Exception {
        perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemised\": true, \"lines\": ["
                                + "{\"line_type\":\"ITEM\",\"description\":\"Carpet\",\"quantity\":2,"
                                + "\"unit_price_ex_gst\":100,\"line_total_ex_gst\":200,\"sort_order\":0},"
                                + "{\"line_type\":\"ITEM\",\"description\":\"Underlay\",\"quantity\":1,"
                                + "\"unit_price_ex_gst\":50,\"line_total_ex_gst\":50,\"sort_order\":1}"
                                + "]}"))
                .andExpect(status().isOk());
    }

    /** Save a non-itemised draft: the inc total is carried verbatim, ex = round(inc / 1.10). */
    private void saveNonItemisedDraft(long orderId, String finalTotalIncGst) throws Exception {
        perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemised\": false, \"final_total_inc_gst\": " + finalTotalIncGst
                                + ", \"lines\": []}"))
                .andExpect(status().isOk());
    }

    /** Issue/resend through the protected send-email and return the plaintext token from the new email. */
    private String sendEmailAndExtractToken(long orderId) throws Exception {
        int emailsBefore = quoteEmailSender.sentEmails().size();
        perform(post(sendEmailUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        List<QuoteEmailRequest> sent = quoteEmailSender.sentEmails();
        Assertions.assertEquals(emailsBefore + 1, sent.size(), "exactly one quote email per send");
        QuoteEmailRequest email = sent.get(sent.size() - 1);
        Assertions.assertEquals(orderId, email.orderId());
        Matcher matcher = PUBLIC_LINK_PATTERN.matcher(email.bodyText());
        Assertions.assertTrue(matcher.find(), "the quote email must carry the /q/{token} link: " + email.bodyText());
        return matcher.group(1);
    }

    // ================================================================
    // Seeding (self-seeded; rolled back with the test transaction)
    // ================================================================

    private long insertOrder(String status) {
        return insertOrderInStore(STORE_SYD_CBD, status);
    }

    private long insertOrderInStore(int storeId, String status) {
        int s = ++seq;
        String orderNumber = "QAPRC.ZZ9." + String.format("%05d", s % 100_000);
        return jdbcTemplate.queryForObject(
                "INSERT INTO sales_order "
                        + "(business_id, store_id, user_id, order_sequence_number, order_number, "
                        + " flooring_type, order_status, week_number, week_year) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, ?::order_status, 1, 2026) "
                        + "RETURNING order_id",
                Long.class,
                BUSINESS_AUSSIE, storeId, USER_LIAM, s, orderNumber, status);
    }

    /** A send-ready LEAD order in store 1: customer "Quote Accepter" (valid email) + billing address. */
    private long readyOrder() {
        long orderId = insertOrder("LEAD");
        seedCustomer(orderId, "Quote", "Accepter");
        seedBillingAddress(orderId);
        return orderId;
    }

    private void seedCustomer(long orderId, String firstName, String lastName) {
        jdbcTemplate.update(
                "INSERT INTO order_customer (order_id, first_name, last_name, email, mobile) "
                        + "VALUES (?, ?, ?, ?, ?)",
                orderId, firstName, lastName, CUSTOMER_EMAIL, CUSTOMER_MOBILE);
    }

    private void seedBillingAddress(long orderId) {
        jdbcTemplate.update(
                "INSERT INTO order_address "
                        + "(order_id, address_type, unit_number, street_number, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'BILLING'::address_type, NULL, '12', 'Test Street', 'Sydney', 'NSW', '2000')",
                orderId);
    }

    /** A store-1 SOFT catalog product priced per SQM (default sqm_per_lm 3.66). */
    private long seedCatalogProduct(String price, String cost) {
        String code = "QAPRCP" + (++catalogSeq);
        return jdbcTemplate.queryForObject(
                "INSERT INTO store_product (store_id, flooring_type, code, name, pricing_unit, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Quote acceptance price test product', "
                        + "'SQM'::pricing_unit, ?, ?) RETURNING product_id",
                Long.class, STORE_SYD_CBD, code, new BigDecimal(price), new BigDecimal(cost));
    }

    /** A store-1 SOFT catalog charge. */
    private long seedCatalogCharge(String price, String cost) {
        String code = "QAPRCC" + (++catalogSeq);
        return jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Quote acceptance price test charge', ?, ?) "
                        + "RETURNING charge_id",
                Long.class, STORE_SYD_CBD, code, new BigDecimal(price), new BigDecimal(cost));
    }

    /** A zero-cost product line written via SQL (header untouched); respects every V2/V3/V8 CHECK. */
    private void insertZeroCostProductLineSql(long orderId, String lineTotal) {
        long productId = seedCatalogProduct(lineTotal, "0.00");
        BigDecimal total = new BigDecimal(lineTotal);
        jdbcTemplate.update(
                "INSERT INTO order_product_line "
                        + "(order_id, product_id, product_code_snapshot, product_name_snapshot, pricing_unit_snapshot, "
                        + " price_snapshot, cost_snapshot, sqm_per_lm_snapshot, quantity_lm, quantity_sqm, "
                        + " unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, 'QAPRC-SQL', 'Width test product', 'SQM'::pricing_unit, "
                        + " ?, 0.00, 3.66, 0.27, 1.00, ?, ?, 0.00)",
                orderId, productId, total, total, total);
    }

    /** A zero-cost charge line written via SQL (header untouched); respects every V2/V3 CHECK. */
    private void insertZeroCostChargeLineSql(long orderId, String lineTotal) {
        long chargeId = seedCatalogCharge(lineTotal, "0.00");
        BigDecimal total = new BigDecimal(lineTotal);
        jdbcTemplate.update(
                "INSERT INTO order_charge_line "
                        + "(order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, 'QAPRC-SQL', 'Width test charge', ?, 0.00, 1.00, ?, ?, 0.00)",
                orderId, chargeId, total, total, total);
    }

    private void setStoreOneEmail(String email) {
        jdbcTemplate.update("UPDATE store SET email = ? WHERE store_id = ? AND business_id = ?",
                email, STORE_SYD_CBD, BUSINESS_AUSSIE);
    }

    private String storeOneEmail() {
        return jdbcTemplate.queryForObject(
                "SELECT email FROM store WHERE store_id = ? AND business_id = ?",
                String.class, STORE_SYD_CBD, BUSINESS_AUSSIE);
    }

    /** Another (active) store of business 1 with its own email. */
    private int insertStore(String storeCode, String email) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO store (business_id, name, store_code, phone, email, street, suburb, state_code, postcode) "
                        + "VALUES (?, 'QAPRC Notification Test Store', ?, '0290009999', ?, '1 Notify Street', "
                        + "'Sydney', 'NSW', '2000') RETURNING store_id",
                Integer.class, BUSINESS_AUSSIE, storeCode, email);
    }

    /** An ISSUED non-itemised v1 (with the V17 name snapshot) + an ACTIVE 7-day token; returns the plaintext. */
    private String seedIssuedNonItemisedVersionWithToken(long orderId, String customerNameSnapshot,
                                                         String totalExGst, String totalIncGst) {
        String plainToken = String.format("QAPRCtoken%033d", System.nanoTime());   // 43 URL-safe chars
        Long versionId = jdbcTemplate.queryForObject(
                "INSERT INTO quote_version (order_id, version_number, status, itemised, quote_total_ex_gst, "
                        + " quote_total_inc_gst, flooring_type_snapshot, customer_name_snapshot, created_by_user_id) "
                        + "VALUES (?, 1, 'ISSUED', FALSE, ?, ?, 'SOFT', ?, ?) RETURNING quote_version_id",
                Long.class, orderId, new BigDecimal(totalExGst), new BigDecimal(totalIncGst),
                customerNameSnapshot, USER_LIAM);
        jdbcTemplate.update(
                "INSERT INTO quote_token (quote_version_id, token_hash, status, expires_at) VALUES (?, ?, 'ACTIVE', ?)",
                versionId, sha256Hex(plainToken), Timestamp.valueOf(LocalDateTime.now().plusDays(7)));
        return plainToken;
    }

    // ================================================================
    // DB / disk probes
    // ================================================================

    private Map<String, Object> orderHeader(long orderId) {
        return jdbcTemplate.queryForMap(
                "SELECT price_adjustment_inc_gst, sale_price_ex_gst, total_cost, gp, gp_percent, updated_at, "
                        + " last_emailed_at, order_status::text AS order_status "
                        + "FROM sales_order WHERE order_id = ?",
                orderId);
    }

    private Map<String, Object> versionRow(long orderId, int versionNumber) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM quote_version WHERE order_id = ? AND version_number = ?", orderId, versionNumber);
    }

    private Map<String, Object> acceptedFields(long orderId, int versionNumber) {
        return jdbcTemplate.queryForMap(
                "SELECT status, accepted_at, accepted_customer_name, accepted_signature_file_id, signed_pdf_file_id, "
                        + " quote_total_ex_gst, quote_total_inc_gst "
                        + "FROM quote_version WHERE order_id = ? AND version_number = ?",
                orderId, versionNumber);
    }

    private int versionCount(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_version WHERE order_id = ?", Integer.class, orderId);
    }

    private Map<String, Object> tokenRow(String plainToken) {
        return jdbcTemplate.queryForMap(
                "SELECT status, dead_at FROM quote_token WHERE token_hash = ?", sha256Hex(plainToken));
    }

    private Map<String, Object> draftRow(long orderId) {
        return jdbcTemplate.queryForMap(
                "SELECT itemised, quote_total_ex_gst, quote_total_inc_gst, updated_at FROM quote_draft WHERE order_id = ?",
                orderId);
    }

    private Map<String, Object> lineTotals(long orderId) {
        return jdbcTemplate.queryForMap(
                "SELECT (SELECT COUNT(*) FROM order_product_line WHERE order_id = ?) AS product_lines, "
                        + " (SELECT COALESCE(SUM(line_total), 0) FROM order_product_line WHERE order_id = ?) AS product_total, "
                        + " (SELECT COALESCE(SUM(line_cost), 0) FROM order_product_line WHERE order_id = ?) AS product_cost, "
                        + " (SELECT COUNT(*) FROM order_charge_line WHERE order_id = ?) AS charge_lines, "
                        + " (SELECT COALESCE(SUM(line_total), 0) FROM order_charge_line WHERE order_id = ?) AS charge_total, "
                        + " (SELECT COALESCE(SUM(line_cost), 0) FROM order_charge_line WHERE order_id = ?) AS charge_cost",
                orderId, orderId, orderId, orderId, orderId, orderId);
    }

    private String orderNumberOf(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT order_number FROM sales_order WHERE order_id = ?", String.class, orderId);
    }

    private String storagePathOf(long storedFileId) {
        return jdbcTemplate.queryForObject(
                "SELECT storage_path FROM stored_file WHERE stored_file_id = ?", String.class, storedFileId);
    }

    private long storedFileSize(long storedFileId) {
        return jdbcTemplate.queryForObject(
                "SELECT file_size FROM stored_file WHERE stored_file_id = ?", Long.class, storedFileId);
    }

    private String issuedPdfStoragePath(long orderId, int versionNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT sf.storage_path FROM quote_version v "
                        + "JOIN stored_file sf ON sf.stored_file_id = v.issued_pdf_file_id "
                        + "WHERE v.order_id = ? AND v.version_number = ?",
                String.class, orderId, versionNumber);
    }

    /** stored_file rows under this order's virtual storage dir ({@code /uploads/1/orders/{orderId}/}). */
    private int storedFileCountForOrder(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stored_file WHERE storage_path LIKE ?",
                Integer.class, "/uploads/" + BUSINESS_AUSSIE + "/orders/" + orderId + "/%");
    }

    /** The physical file names currently in this order's storage dir (empty when the dir is absent). */
    private Set<String> filesOnDiskForOrder(long orderId) throws IOException {
        Path dir = physicalPath("/uploads/" + BUSINESS_AUSSIE + "/orders/" + orderId);
        if (!Files.isDirectory(dir)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .collect(Collectors.toSet());
        }
    }

    /** Physical path = base-dir + storage_path (resolved exactly like FileStorageService). */
    private Path physicalPath(String storagePath) {
        Path base = Path.of(storageBaseDir).toAbsolutePath().normalize();
        String relative = storagePath.startsWith("/") ? storagePath.substring(1) : storagePath;
        return base.resolve(relative).normalize();
    }

    private static String fileName(String storagePath) {
        return storagePath.substring(storagePath.lastIndexOf('/') + 1);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static long asLong(Object value) {
        Assertions.assertNotNull(value, "expected a non-null id");
        return ((Number) value).longValue();
    }

    // ================================================================
    // Assertions
    // ================================================================

    /** Persisted DECIMAL money: exact BigDecimal equality INCLUDING the column's scale 2. */
    private static void assertMoneyDb(String expected, Object actual, String what) {
        Assertions.assertNotNull(actual, what + " must be persisted (was NULL)");
        Assertions.assertEquals(new BigDecimal(expected), actual, what);
    }

    private void assertOrderPrice(long orderId, String priceAdjustmentIncGst, String salePriceExGst,
                                  String totalCost, String gp, String gpPercent) {
        Map<String, Object> header = orderHeader(orderId);
        assertMoneyDb(priceAdjustmentIncGst, header.get("price_adjustment_inc_gst"), "sales_order.price_adjustment_inc_gst");
        assertMoneyDb(salePriceExGst, header.get("sale_price_ex_gst"), "sales_order.sale_price_ex_gst");
        assertMoneyDb(totalCost, header.get("total_cost"), "sales_order.total_cost");
        assertMoneyDb(gp, header.get("gp"), "sales_order.gp");
        if (gpPercent == null) {
            Assertions.assertNull(header.get("gp_percent"), "sales_order.gp_percent must be NULL");
        } else {
            assertMoneyDb(gpPercent, header.get("gp_percent"), "sales_order.gp_percent");
        }
    }

    /** JSON money read as an exact decimal; equal by value. */
    private static void assertJsonMoney(String expected, JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        Assertions.assertNotNull(value, field + " must be present in " + parent);
        Assertions.assertTrue(value.isNumber(), field + " must be a JSON number but was " + value);
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo(value.decimalValue()),
                field + ": expected " + expected + " but was " + value);
    }

    private static void assertJsonNull(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        Assertions.assertNotNull(value, field + " must be present (explicit null) in " + parent);
        Assertions.assertTrue(value.isNull(), field + " must be null but was " + value);
    }

    private static void assertJsonFalse(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        Assertions.assertTrue(value != null && value.isBoolean() && !value.booleanValue(),
                field + " must be false but was " + value);
    }

    /** A public rejection carries the code + the fixed sentence only: no money sign and no figure at all. */
    private static void assertNoFigures(String responseBody) {
        Assertions.assertFalse(responseBody.contains("$"), "public rejection must not carry money: " + responseBody);
        Assertions.assertTrue(responseBody.chars().noneMatch(Character::isDigit),
                "public rejection must not carry any figure (cost / GP / limit): " + responseBody);
    }

    /**
     * Spec §1 SERVER NORMALISATION: the stored signature is the decoded upload re-encoded as a clean PNG —
     * PNG magic, the stream ends exactly at IEND, the SAME width/height and the same pixels (alpha for every
     * pixel; RGB wherever the pixel is not fully transparent, since a fully transparent pixel has no colour).
     */
    private static void assertNormalisedSignature(byte[] uploaded, byte[] stored) throws IOException {
        Assertions.assertTrue(stored.length > PNG_MAGIC.length + PNG_IEND_CHUNK.length,
                "stored signature too short: " + stored.length + " bytes");
        Assertions.assertArrayEquals(PNG_MAGIC, Arrays.copyOfRange(stored, 0, PNG_MAGIC.length),
                "the stored signature must start with the PNG magic");
        Assertions.assertArrayEquals(PNG_IEND_CHUNK,
                Arrays.copyOfRange(stored, stored.length - PNG_IEND_CHUNK.length, stored.length),
                "the stored signature must end exactly at the IEND chunk (no trailing bytes)");

        BufferedImage expected = ImageIO.read(new ByteArrayInputStream(uploaded));
        BufferedImage actual = ImageIO.read(new ByteArrayInputStream(stored));
        Assertions.assertNotNull(expected, "the uploaded signature must be a decodable PNG");
        Assertions.assertNotNull(actual, "the stored signature must be a decodable PNG");
        Assertions.assertEquals(expected.getWidth(), actual.getWidth(), "the stored signature keeps the width");
        Assertions.assertEquals(expected.getHeight(), actual.getHeight(), "the stored signature keeps the height");
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                int want = expected.getRGB(x, y);
                int got = actual.getRGB(x, y);
                int wantAlpha = want >>> 24;
                boolean same = wantAlpha == (got >>> 24)
                        && (wantAlpha == 0 || (want & 0xFFFFFF) == (got & 0xFFFFFF));
                Assertions.assertTrue(same, String.format(Locale.ROOT,
                        "the stored signature keeps the pixels: (%d,%d) upload %08x stored %08x", x, y, want, got));
            }
        }
    }

    private void assertNoNotification() {
        Assertions.assertTrue(notificationSender.sentNotifications().isEmpty(),
                "no store notification may be sent: " + notificationSender.sentNotifications());
        Assertions.assertTrue(notificationSender.failedNotifications().isEmpty(),
                "no store notification may be attempted: " + notificationSender.failedNotifications());
    }

    /** The non-null, non-blank values that must never be logged (a NULL column has nothing to leak). */
    private static List<String> sensitiveValues(String... values) {
        return Arrays.stream(values).filter(Objects::nonNull).filter(value -> !value.isBlank()).toList();
    }

    /**
     * Spec §3 log hygiene, on the console output captured during ONE accept request: exactly one
     * store-notification line, at WARN, with {@code expectedOutcome} and only "order {id} quote v1" as
     * identifiers. No {@code sensitiveValues} entry (store / customer email, accepted name, plaintext token)
     * and no public "/q/" link appears ANYWHERE in the request's output; the notification line itself also
     * carries no email address ("@"), no money ("$") and no storage path ("/uploads/"). Those three markers
     * are scoped to that line on purpose: the signed-PDF render legitimately prints openhtmltopdf INFO lines
     * naming parser classes such as {@code SAXParserImpl$JAXPSAXParser}, and may WARN about a tenant logo's
     * own "/uploads/..." path on a database where a demo logo_path is seeded.
     */
    private static void assertSafeNotificationLog(String requestLog, String expectedOutcome, long orderId,
                                                  List<String> sensitiveValues) {
        List<String> lines = requestLog.lines().filter(line -> line.contains(NOTIFICATION_LOG_MARKER)).toList();
        Assertions.assertEquals(1, lines.size(),
                "exactly one store-notification log line per acceptance; request output:\n" + requestLog);
        String line = lines.get(0);
        Assertions.assertTrue(line.contains("WARN"), "the notification outcome is logged at WARN: " + line);
        Assertions.assertTrue(line.contains(expectedOutcome), "expected '" + expectedOutcome + "' in: " + line);
        Assertions.assertTrue(line.contains("order " + orderId + " quote v1"),
                "the log identifies only the order id + quote version: " + line);
        for (String value : sensitiveValues) {
            Assertions.assertFalse(requestLog.contains(value),
                    "'" + value + "' must never be logged; request output:\n" + requestLog);
        }
        Assertions.assertFalse(requestLog.contains("/q/"), "no public quote link may be logged:\n" + requestLog);
        for (String marker : List.of("@", "$", "/uploads/")) {
            Assertions.assertFalse(line.contains(marker),
                    "the notification log line must not carry '" + marker + "': " + line);
        }
    }
}
