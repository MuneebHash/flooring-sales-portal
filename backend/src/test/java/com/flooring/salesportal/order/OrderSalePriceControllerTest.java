package com.flooring.salesportal.order;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the Chunk 3 Branch 6 sale-price endpoints (D.10 PUT /sale-price,
 * D.11 POST /sale-price/reset).
 *
 * <p>Mirrors {@code OrderChargeLineControllerTest}: {@code @SpringBootTest @Transactional}, MockMvc,
 * {@code MockHttpSession} carrying the seeded Liam / business 1 / store 1 identity, and
 * {@code JdbcTemplate} for data tweaks + persistence assertions (rolled back with the test
 * transaction). Seed (V4): order 1 = SOFT/ACCEPTED, store 1, product line 1 (line_total 360.00,
 * line_cost 176.00) + charge line 1 (32 INST-S → line_total 480.00, line_cost 256.00), so
 * product_subtotal 360, charge_subtotal 480, calculated_total_inc_gst 924.00, total_cost 432.00,
 * price_adjustment_inc_gst NULL. Order 2 = HARD/LEAD/store 1/empty. Order 5 = store 2 (cross-store).
 * Order 9 = business 2 (cross-business).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class OrderSalePriceControllerTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";

    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    private static final long ORDER_SOFT_FULL = 1L;       // SOFT, ACCEPTED, store 1: calc 924.00, cost 432.00
    private static final long ORDER_HARD_EMPTY = 2L;      // HARD, LEAD, store 1, empty
    private static final long ORDER_CROSS_STORE = 5L;     // business 1, store 2
    private static final long ORDER_CROSS_BUSINESS = 9L;  // business 2, store 3
    private static final long ORDER_DOES_NOT_EXIST = 99_999L;

    private static final long CHARGE_UNDR_S = 2L;         // SOFT, price 8.00, cost 4.00
    private static final long CHARGE_UNDR_H = 4L;         // HARD, price 10.00, cost 5.00

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private MockHttpSession liamStore1Session() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("user_id", USER_LIAM);
        s.setAttribute("business_id", BUSINESS_AUSSIE);
        s.setAttribute("store_id", STORE_SYD_CBD);
        return s;
    }

    private MockHttpSession liamSessionNoStore() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("user_id", USER_LIAM);
        s.setAttribute("business_id", BUSINESS_AUSSIE);
        return s;
    }

    private static String salePriceUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/sale-price";
    }

    private static String resetUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/sale-price/reset";
    }

    private static String chargeLinesUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/charge-lines";
    }

    private void laidOrder(long orderId) {
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'LAID'::order_status WHERE order_id = ?", orderId);
    }

    private void setStoredAdjustment(long orderId, String adjustment) {
        jdbcTemplate.update("UPDATE sales_order SET price_adjustment_inc_gst = ? WHERE order_id = ?",
                new BigDecimal(adjustment), orderId);
    }

    private void assertMoney(String expected, BigDecimal actual) {
        Assertions.assertNotNull(actual);
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    private BigDecimal queryMoney(String column, long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM sales_order WHERE order_id = ?", BigDecimal.class, orderId);
    }

    // ================================================================
    // D.10 PUT /sale-price — auth / scoping / LAID gates
    // ================================================================

    @Test
    void overrideSalePrice_noSession_returns401() throws Exception {
        mockMvc.perform(put(salePriceUrl(ORDER_SOFT_FULL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"final_sale_price_inc_gst\":800.00}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void overrideSalePrice_sessionWithoutStore_returns403() throws Exception {
        mockMvc.perform(put(salePriceUrl(ORDER_SOFT_FULL))
                        .session(liamSessionNoStore())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"final_sale_price_inc_gst\":800.00}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void overrideSalePrice_invalidOrderId_returns400() throws Exception {
        mockMvc.perform(put(salePriceUrl("abc"))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"final_sale_price_inc_gst\":800.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    @Test
    void overrideSalePrice_missingOrder_returns404() throws Exception {
        mockMvc.perform(put(salePriceUrl(ORDER_DOES_NOT_EXIST))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"final_sale_price_inc_gst\":800.00}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void overrideSalePrice_crossStoreOrder_returns404() throws Exception {
        mockMvc.perform(put(salePriceUrl(ORDER_CROSS_STORE))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"final_sale_price_inc_gst\":800.00}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void overrideSalePrice_crossBusinessOrder_returns404() throws Exception {
        mockMvc.perform(put(salePriceUrl(ORDER_CROSS_BUSINESS))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"final_sale_price_inc_gst\":800.00}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void overrideSalePrice_laidOrder_returns422_andDoesNotPersist() throws Exception {
        laidOrder(ORDER_SOFT_FULL);
        mockMvc.perform(put(salePriceUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"final_sale_price_inc_gst\":800.00}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORDER_LOCKED"));
        Assertions.assertNull(queryMoney("price_adjustment_inc_gst", ORDER_SOFT_FULL),
                "a blocked LAID override must not persist an adjustment");
    }

    // ================================================================
    // D.10 PUT /sale-price — body validation (400)
    // ================================================================

    @Test
    void overrideSalePrice_malformedJson_returns400() throws Exception {
        mockMvc.perform(put(salePriceUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_JSON"));
    }

    @Test
    void overrideSalePrice_missingFinalSalePrice_returns400() throws Exception {
        mockMvc.perform(put(salePriceUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("final_sale_price_inc_gst"));
    }

    @Test
    void overrideSalePrice_zeroFinalSalePrice_returns400() throws Exception {
        mockMvc.perform(put(salePriceUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"final_sale_price_inc_gst\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("final_sale_price_inc_gst"));
    }

    @Test
    void overrideSalePrice_negativeFinalSalePrice_returns400() throws Exception {
        mockMvc.perform(put(salePriceUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"final_sale_price_inc_gst\":-5.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("final_sale_price_inc_gst"));
    }

    @Test
    void overrideSalePrice_forbiddenPriceAdjustmentField_returns400() throws Exception {
        mockMvc.perform(put(salePriceUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"final_sale_price_inc_gst\":800.00,\"price_adjustment_inc_gst\":-200.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("price_adjustment_inc_gst"));
    }

    @Test
    void overrideSalePrice_unknownField_returns400() throws Exception {
        mockMvc.perform(put(salePriceUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"final_sale_price_inc_gst\":800.00,\"foo\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("foo"));
    }

    // ================================================================
    // D.10 PUT /sale-price — success (override math + persistence)
    // ================================================================

    @Test
    void overrideSalePrice_discount_persistsNegativeAdjustment() throws Exception {
        // Order 1: calc 924.00. Type 800.00 → adjustment 800.00 − 924.00 = −124.00 (discount).
        // final 800.00, sale_ex 727.27, total_cost 432.00, gp 295.27, gp% 40.60.
        mockMvc.perform(put(salePriceUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"final_sale_price_inc_gst\":800.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Sale price updated."))
                .andExpect(jsonPath("$.data.order_financial_summary.calculated_total_inc_gst").value(924.0))
                .andExpect(jsonPath("$.data.order_financial_summary.price_adjustment_inc_gst").value(-124.0))
                .andExpect(jsonPath("$.data.order_financial_summary.final_sale_price_inc_gst").value(800.0))
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(727.27))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(295.27))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_percent").value(40.6))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_warning").value(false));

        assertMoney("-124.00", queryMoney("price_adjustment_inc_gst", ORDER_SOFT_FULL));
        assertMoney("727.27", queryMoney("sale_price_ex_gst", ORDER_SOFT_FULL));
        assertMoney("295.27", queryMoney("gp", ORDER_SOFT_FULL));
    }

    @Test
    void overrideSalePrice_uplift_persistsPositiveAdjustment() throws Exception {
        // Order 1: calc 924.00. Type 1100.00 → adjustment +176.00 (uplift).
        // final 1100.00, sale_ex 1000.00, total_cost 432.00, gp 568.00, gp% 56.80.
        mockMvc.perform(put(salePriceUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"final_sale_price_inc_gst\":1100.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_financial_summary.price_adjustment_inc_gst").value(176.0))
                .andExpect(jsonPath("$.data.order_financial_summary.final_sale_price_inc_gst").value(1100.0))
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(1000.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(568.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_percent").value(56.8));

        assertMoney("176.00", queryMoney("price_adjustment_inc_gst", ORDER_SOFT_FULL));
    }

    @Test
    void overrideSalePrice_exactCalculatedTotal_persistsZeroAdjustmentNotNull() throws Exception {
        // Order 1: type the calc exactly (924.00) → adjustment 0.00, stored as 0.00 (NOT null).
        mockMvc.perform(put(salePriceUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"final_sale_price_inc_gst\":924.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_financial_summary.price_adjustment_inc_gst").value(0.0))
                .andExpect(jsonPath("$.data.order_financial_summary.final_sale_price_inc_gst").value(924.0))
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(840.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(408.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_percent").value(48.57));

        BigDecimal stored = queryMoney("price_adjustment_inc_gst", ORDER_SOFT_FULL);
        Assertions.assertNotNull(stored, "zero adjustment must persist as 0.00, not NULL");
        assertMoney("0.00", stored);
    }

    @Test
    void overrideSalePrice_response_containsFullFinancialSummary() throws Exception {
        // Same numbers as the discount case; asserts every order_financial_summary field is present.
        mockMvc.perform(put(salePriceUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"final_sale_price_inc_gst\":800.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_financial_summary.product_subtotal").value(360.0))
                .andExpect(jsonPath("$.data.order_financial_summary.charge_subtotal").value(480.0))
                .andExpect(jsonPath("$.data.order_financial_summary.calculated_total_inc_gst").value(924.0))
                .andExpect(jsonPath("$.data.order_financial_summary.price_adjustment_inc_gst").value(-124.0))
                .andExpect(jsonPath("$.data.order_financial_summary.final_sale_price_inc_gst").value(800.0))
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(727.27))
                .andExpect(jsonPath("$.data.order_financial_summary.total_cost").value(432.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(295.27))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_percent").value(40.6))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_warning").value(false));
    }

    @Test
    void overrideSalePrice_persistsHeaderFields() throws Exception {
        // Order 1 uplift to 1100.00 → header scalars persisted alongside the adjustment.
        mockMvc.perform(put(salePriceUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"final_sale_price_inc_gst\":1100.00}"))
                .andExpect(status().isOk());

        assertMoney("176.00", queryMoney("price_adjustment_inc_gst", ORDER_SOFT_FULL));
        assertMoney("1000.00", queryMoney("sale_price_ex_gst", ORDER_SOFT_FULL));
        assertMoney("432.00", queryMoney("total_cost", ORDER_SOFT_FULL));
        assertMoney("568.00", queryMoney("gp", ORDER_SOFT_FULL));
        assertMoney("56.80", queryMoney("gp_percent", ORDER_SOFT_FULL));
    }

    @Test
    void overrideSalePrice_emptyOrder_allowedWhenPositive() throws Exception {
        // Order 2 (empty): calc 0.00. Type 100.00 → adjustment 100.00, final 100.00, sale_ex 90.91,
        // total_cost 0.00, gp 90.91, gp% 100.00.
        mockMvc.perform(put(salePriceUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"final_sale_price_inc_gst\":100.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_financial_summary.product_subtotal").value(0.0))
                .andExpect(jsonPath("$.data.order_financial_summary.charge_subtotal").value(0.0))
                .andExpect(jsonPath("$.data.order_financial_summary.calculated_total_inc_gst").value(0.0))
                .andExpect(jsonPath("$.data.order_financial_summary.price_adjustment_inc_gst").value(100.0))
                .andExpect(jsonPath("$.data.order_financial_summary.final_sale_price_inc_gst").value(100.0))
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(90.91))
                .andExpect(jsonPath("$.data.order_financial_summary.total_cost").value(0.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(90.91))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_percent").value(100.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_warning").value(false));

        assertMoney("100.00", queryMoney("price_adjustment_inc_gst", ORDER_HARD_EMPTY));
        assertMoney("90.91", queryMoney("sale_price_ex_gst", ORDER_HARD_EMPTY));
    }

    @Test
    void overrideSalePrice_derivedAdjustmentOverflowsDecimal_returns400_andDoesNotPersist() throws Exception {
        // Force calculated_total far above DECIMAL(10,2): one charge line at line_total 99999999.99 on the
        // empty HARD order 2 → calc round(99999999.99 × 1.10, 2) = 109999999.99. Type 0.01 → derived
        // adjustment 0.01 − 109999999.99 = −109999999.98 (9 integer digits) → out of DECIMAL(10,2) range.
        jdbcTemplate.update(
                "INSERT INTO order_charge_line (order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + "price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost, created_at, updated_at) "
                        + "VALUES (?, ?, 'UNDR-H', 'Hard Floor Underlay Supply', 10.00, 5.00, 1.00, 99999999.99, 99999999.99, 5.00, now(), now())",
                ORDER_HARD_EMPTY, CHARGE_UNDR_H);

        mockMvc.perform(put(salePriceUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"final_sale_price_inc_gst\":0.01}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("final_sale_price_inc_gst"));

        Assertions.assertNull(queryMoney("price_adjustment_inc_gst", ORDER_HARD_EMPTY),
                "an out-of-range override must not persist an adjustment");
    }

    @Test
    void overrideSalePrice_extremeNonFiniteNumber_returns400Validation() throws Exception {
        // 1e309 exceeds Double.MAX_VALUE: Jackson keeps it as a numeric DoubleNode (isNumber() == true)
        // whose textual form is "Infinity", which is not a valid BigDecimal. Must be a clean 400
        // VALIDATION_FAILED, never a 500. No adjustment persisted.
        mockMvc.perform(put(salePriceUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"final_sale_price_inc_gst\":1e309}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("final_sale_price_inc_gst"));

        Assertions.assertNull(queryMoney("price_adjustment_inc_gst", ORDER_SOFT_FULL),
                "a non-finite override must not persist an adjustment");
    }

    @Test
    void overrideSalePrice_extremeNegativeNonFiniteNumber_returns400Validation() throws Exception {
        // -1e309 → numeric DoubleNode whose textual form is "-Infinity" (not a valid BigDecimal).
        // Same clean-400 guard from the negative side; no 500, no adjustment persisted.
        mockMvc.perform(put(salePriceUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"final_sale_price_inc_gst\":-1e309}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("final_sale_price_inc_gst"));

        Assertions.assertNull(queryMoney("price_adjustment_inc_gst", ORDER_SOFT_FULL),
                "a non-finite override must not persist an adjustment");
    }

    @Test
    void overrideSalePrice_storedAdjustment_reappliedAfterLaterChargeLineMutation() throws Exception {
        // Proves §12 reapply: a stored adjustment is reapplied on a subsequent line mutation. The
        // adjustment is seeded via SQL (not a chained API override) so the first JPA load in this single
        // test transaction reads the fresh DB value — in production each request is its own transaction.
        // Order 1 with stored −124.00; add charge 2 (UNDR-S 8/4) qty 10 → line 80/40.
        // charge_subtotal 480+80=560, product 360, calc (360+560)×1.1=1012.00, final 1012−124=888.00,
        // sale_ex round(888/1.1)=807.27, total_cost 176+256+40=472.00, gp 335.27.
        setStoredAdjustment(ORDER_SOFT_FULL, "-124.00");

        mockMvc.perform(post(chargeLinesUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charge_id\":" + CHARGE_UNDR_S + ",\"quantity\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.order_financial_summary.charge_subtotal").value(560.0))
                .andExpect(jsonPath("$.data.order_financial_summary.calculated_total_inc_gst").value(1012.0))
                .andExpect(jsonPath("$.data.order_financial_summary.price_adjustment_inc_gst").value(-124.0))
                .andExpect(jsonPath("$.data.order_financial_summary.final_sale_price_inc_gst").value(888.0))
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(807.27))
                .andExpect(jsonPath("$.data.order_financial_summary.total_cost").value(472.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(335.27));

        assertMoney("-124.00", queryMoney("price_adjustment_inc_gst", ORDER_SOFT_FULL));
        assertMoney("807.27", queryMoney("sale_price_ex_gst", ORDER_SOFT_FULL));
    }

    // ================================================================
    // D.11 POST /sale-price/reset
    // ================================================================

    @Test
    void resetSalePrice_afterStoredAdjustment_clearsToNull() throws Exception {
        setStoredAdjustment(ORDER_SOFT_FULL, "-124.00");

        mockMvc.perform(post(resetUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Sale price reset."))
                .andExpect(jsonPath("$.data.order_financial_summary.price_adjustment_inc_gst").value(nullValue()))
                .andExpect(jsonPath("$.data.order_financial_summary.final_sale_price_inc_gst").value(924.0))
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(840.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(408.0));

        Assertions.assertNull(queryMoney("price_adjustment_inc_gst", ORDER_SOFT_FULL),
                "reset must clear the stored adjustment to NULL");
    }

    @Test
    void resetSalePrice_afterApiOverride_finalEqualsCalculatedTotal() throws Exception {
        // End-to-end: override the price, then reset. The reset summary's final must equal the calc total.
        mockMvc.perform(put(salePriceUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"final_sale_price_inc_gst\":800.00}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(resetUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_financial_summary.price_adjustment_inc_gst").value(nullValue()))
                .andExpect(jsonPath("$.data.order_financial_summary.calculated_total_inc_gst").value(924.0))
                .andExpect(jsonPath("$.data.order_financial_summary.final_sale_price_inc_gst").value(924.0));

        Assertions.assertNull(queryMoney("price_adjustment_inc_gst", ORDER_SOFT_FULL));
        assertMoney("840.00", queryMoney("sale_price_ex_gst", ORDER_SOFT_FULL));
    }

    @Test
    void resetSalePrice_idempotentWhenNoAdjustment_returns200() throws Exception {
        // Order 1 starts with NULL adjustment (seed). Reset is a 200 no-op that recomputes the summary.
        mockMvc.perform(post(resetUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Sale price reset."))
                .andExpect(jsonPath("$.data.order_financial_summary.price_adjustment_inc_gst").value(nullValue()))
                .andExpect(jsonPath("$.data.order_financial_summary.calculated_total_inc_gst").value(924.0))
                .andExpect(jsonPath("$.data.order_financial_summary.final_sale_price_inc_gst").value(924.0))
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(840.0));

        Assertions.assertNull(queryMoney("price_adjustment_inc_gst", ORDER_SOFT_FULL));
    }

    @Test
    void resetSalePrice_laidOrder_returns422() throws Exception {
        laidOrder(ORDER_SOFT_FULL);
        mockMvc.perform(post(resetUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORDER_LOCKED"));
    }

    @Test
    void resetSalePrice_invalidOrderId_returns400() throws Exception {
        mockMvc.perform(post(resetUrl("abc"))
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    @Test
    void resetSalePrice_missingOrder_returns404() throws Exception {
        mockMvc.perform(post(resetUrl(ORDER_DOES_NOT_EXIST))
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void resetSalePrice_crossStoreOrder_returns404() throws Exception {
        mockMvc.perform(post(resetUrl(ORDER_CROSS_STORE))
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void resetSalePrice_crossBusinessOrder_returns404() throws Exception {
        mockMvc.perform(post(resetUrl(ORDER_CROSS_BUSINESS))
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void resetSalePrice_noSession_returns401() throws Exception {
        mockMvc.perform(post(resetUrl(ORDER_SOFT_FULL)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void resetSalePrice_sessionWithoutStore_returns403() throws Exception {
        mockMvc.perform(post(resetUrl(ORDER_SOFT_FULL))
                        .session(liamSessionNoStore()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
