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

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the Chunk 3 Branch 2 order line endpoints (D.3–D.6):
 * GET /lines, POST /product-lines, PATCH /product-lines/{lineId}, DELETE /product-lines/{lineId}.
 *
 * <p>Mirrors {@code OrderControllerTest} / {@code OrderCatalogControllerTest}: {@code @SpringBootTest
 * @Transactional}, MockMvc, {@code MockHttpSession} carrying the seeded Liam / business 1 / store 1
 * identity, and {@code JdbcTemplate} for data tweaks + persistence assertions (rolled back with the
 * test transaction). Seed (V4/V8): order 1 = SOFT/ACCEPTED with product line 1 (8 LM PLU-001 →
 * line_total 360.00, line_cost 176.00) and charge line 1 (32 INST-S → line_total 480.00, line_cost
 * 256.00); order 2 = HARD/LEAD empty; order 5 = store 2; order 6 = store 2 LAID; order 9 = business 2.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class OrderLineControllerTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";
    private static final String SLUG_PREMIER = "premier-flooring-co";

    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    private static final long ORDER_SOFT_FULL = 1L;       // SOFT, ACCEPTED, store 1, has line 1 + charge 1
    private static final long ORDER_HARD_EMPTY = 2L;      // HARD, LEAD, store 1, empty
    private static final long ORDER_OTHER_STORE = 5L;     // store 2 (cross-store)
    private static final long ORDER_OTHER_BUSINESS = 9L;  // business 2 (cross-business)
    private static final long ORDER_DOES_NOT_EXIST = 99_999L;

    private static final long PRODUCT_PLU = 1L;   // SOFT, LM, price 45.00, cost 22.00, sqm_per_lm 3.66
    private static final long PRODUCT_LOP = 2L;   // SOFT, LM, price 38.00, cost 18.00, sqm_per_lm 4.00 (4m roll)
    private static final long PRODUCT_SGT = 3L;   // HARD, SQM, price 89.00, cost 42.00, sqm_per_lm 3.66
    private static final long PRODUCT_OTHER_STORE = 5L; // store 2

    private static final long PRODUCT_LINE_1 = 1L;
    private static final long LINE_DOES_NOT_EXIST = 99_999L;

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

    private static String linesUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/lines";
    }

    private static String productLinesUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/product-lines";
    }

    private static String productLineUrl(Object orderId, Object lineId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/product-lines/" + lineId;
    }

    private void clearOrderLines(long orderId) {
        jdbcTemplate.update("DELETE FROM order_product_line WHERE order_id = ?", orderId);
        jdbcTemplate.update("DELETE FROM order_charge_line WHERE order_id = ?", orderId);
    }

    private void laidOrder(long orderId) {
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'LAID'::order_status WHERE order_id = ?", orderId);
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

    private void assertNoProductLines(long orderId) {
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_product_line WHERE order_id = ?", Integer.class, orderId);
        Assertions.assertEquals(Integer.valueOf(0), rows, "overflowing input must not create a line");
    }

    private BigDecimal queryProductLineMoney(String column, long lineId) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM order_product_line WHERE order_product_line_id = ?",
                BigDecimal.class, lineId);
    }

    // ================================================================
    // GET /lines
    // ================================================================

    @Test
    void getLines_noSession_returns401() throws Exception {
        mockMvc.perform(get(linesUrl(ORDER_SOFT_FULL)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void getLines_sessionWithoutStore_returns403() throws Exception {
        mockMvc.perform(get(linesUrl(ORDER_SOFT_FULL)).session(liamSessionNoStore()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void getLines_crossBusinessSlug_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/" + SLUG_PREMIER + "/orders/" + ORDER_SOFT_FULL + "/lines")
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void getLines_populatedOrder_returnsLinesAndSummary() throws Exception {
        mockMvc.perform(get(linesUrl(ORDER_SOFT_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.product_lines", hasSize(1)))
                .andExpect(jsonPath("$.data.product_lines[0].order_product_line_id").value(1))
                .andExpect(jsonPath("$.data.product_lines[0].product_id").value(1))
                .andExpect(jsonPath("$.data.product_lines[0].product_code_snapshot").value("PLU-001"))
                .andExpect(jsonPath("$.data.product_lines[0].pricing_unit_snapshot").value("LM"))
                .andExpect(jsonPath("$.data.product_lines[0].price_snapshot").value(45.0))
                .andExpect(jsonPath("$.data.product_lines[0].cost_snapshot").value(22.0))
                .andExpect(jsonPath("$.data.product_lines[0].sqm_per_lm_snapshot").value(3.66))
                .andExpect(jsonPath("$.data.product_lines[0].quantity_lm").value(8.0))
                .andExpect(jsonPath("$.data.product_lines[0].quantity_sqm").value(29.28))
                .andExpect(jsonPath("$.data.product_lines[0].unit_price").value(45.0))
                .andExpect(jsonPath("$.data.product_lines[0].line_total").value(360.0))
                .andExpect(jsonPath("$.data.product_lines[0].line_cost").doesNotExist())
                .andExpect(jsonPath("$.data.charge_lines", hasSize(1)))
                .andExpect(jsonPath("$.data.charge_lines[0].order_charge_line_id").value(1))
                .andExpect(jsonPath("$.data.charge_lines[0].charge_code_snapshot").value("INST-S"))
                .andExpect(jsonPath("$.data.charge_lines[0].cost_snapshot").value(8.0))
                .andExpect(jsonPath("$.data.charge_lines[0].line_total").value(480.0))
                .andExpect(jsonPath("$.data.charge_lines[0].line_cost").doesNotExist())
                .andExpect(jsonPath("$.data.order_financial_summary.product_subtotal").value(360.0))
                .andExpect(jsonPath("$.data.order_financial_summary.charge_subtotal").value(480.0))
                .andExpect(jsonPath("$.data.order_financial_summary.calculated_total_inc_gst").value(924.0))
                .andExpect(jsonPath("$.data.order_financial_summary.price_adjustment_inc_gst").value(nullValue()))
                .andExpect(jsonPath("$.data.order_financial_summary.final_sale_price_inc_gst").value(924.0))
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(840.0))
                .andExpect(jsonPath("$.data.order_financial_summary.total_cost").value(432.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(408.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_percent").value(48.57))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_warning").value(false));
    }

    @Test
    void getLines_emptyOrder_returnsEmptyArraysAndZeroSummary() throws Exception {
        mockMvc.perform(get(linesUrl(ORDER_HARD_EMPTY)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.product_lines", hasSize(0)))
                .andExpect(jsonPath("$.data.charge_lines", hasSize(0)))
                .andExpect(jsonPath("$.data.order_financial_summary.product_subtotal").value(0.0))
                .andExpect(jsonPath("$.data.order_financial_summary.charge_subtotal").value(0.0))
                .andExpect(jsonPath("$.data.order_financial_summary.calculated_total_inc_gst").value(0.0))
                .andExpect(jsonPath("$.data.order_financial_summary.price_adjustment_inc_gst").value(nullValue()))
                .andExpect(jsonPath("$.data.order_financial_summary.final_sale_price_inc_gst").value(0.0))
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(0.0))
                .andExpect(jsonPath("$.data.order_financial_summary.total_cost").value(0.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(0.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_percent").value(nullValue()))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_warning").value(false));
    }

    @Test
    void getLines_laidOrder_returns200() throws Exception {
        laidOrder(ORDER_SOFT_FULL);
        mockMvc.perform(get(linesUrl(ORDER_SOFT_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.product_lines", hasSize(1)));
    }

    @Test
    void getLines_crossStoreOrder_returns404() throws Exception {
        mockMvc.perform(get(linesUrl(ORDER_OTHER_STORE)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void getLines_crossBusinessOrder_returns404() throws Exception {
        mockMvc.perform(get(linesUrl(ORDER_OTHER_BUSINESS)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void getLines_nonexistentOrder_returns404() throws Exception {
        mockMvc.perform(get(linesUrl(ORDER_DOES_NOT_EXIST)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void getLines_invalidOrderId_returns400() throws Exception {
        mockMvc.perform(get(linesUrl("abc")).session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    // ================================================================
    // POST /product-lines
    // ================================================================

    @Test
    void addProductLine_noSession_returns401() throws Exception {
        mockMvc.perform(post(productLinesUrl(ORDER_SOFT_FULL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":1,\"quantity_lm\":8}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void addProductLine_sessionWithoutStore_returns403() throws Exception {
        mockMvc.perform(post(productLinesUrl(ORDER_SOFT_FULL))
                        .session(liamSessionNoStore())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":1,\"quantity_lm\":8}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void addProductLine_lm_defaultsUnitPriceToPriceSnapshot_andReturnsLineAndSummary() throws Exception {
        clearOrderLines(ORDER_SOFT_FULL);
        mockMvc.perform(post(productLinesUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":1,\"quantity_lm\":8}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Product line added."))
                .andExpect(jsonPath("$.data.product_line.product_id").value(1))
                .andExpect(jsonPath("$.data.product_line.product_code_snapshot").value("PLU-001"))
                .andExpect(jsonPath("$.data.product_line.pricing_unit_snapshot").value("LM"))
                .andExpect(jsonPath("$.data.product_line.price_snapshot").value(45.0))
                .andExpect(jsonPath("$.data.product_line.cost_snapshot").value(22.0))
                .andExpect(jsonPath("$.data.product_line.sqm_per_lm_snapshot").value(3.66))
                .andExpect(jsonPath("$.data.product_line.quantity_lm").value(8.0))
                .andExpect(jsonPath("$.data.product_line.quantity_sqm").value(29.28))
                .andExpect(jsonPath("$.data.product_line.unit_price").value(45.0))
                .andExpect(jsonPath("$.data.product_line.line_total").value(360.0))
                .andExpect(jsonPath("$.data.product_line.line_cost").doesNotExist())
                .andExpect(jsonPath("$.data.order_financial_summary.product_subtotal").value(360.0))
                .andExpect(jsonPath("$.data.order_financial_summary.charge_subtotal").value(0.0))
                .andExpect(jsonPath("$.data.order_financial_summary.calculated_total_inc_gst").value(396.0))
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(360.0))
                .andExpect(jsonPath("$.data.order_financial_summary.total_cost").value(176.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(184.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_percent").value(51.11))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_warning").value(false));
    }

    @Test
    void addProductLine_sqm_derivesLmAndPersistsHeader() throws Exception {
        mockMvc.perform(post(productLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":3,\"quantity_sqm\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.product_line.pricing_unit_snapshot").value("SQM"))
                .andExpect(jsonPath("$.data.product_line.quantity_sqm").value(10.0))
                .andExpect(jsonPath("$.data.product_line.quantity_lm").value(2.73))
                .andExpect(jsonPath("$.data.product_line.line_total").value(890.0))
                .andExpect(jsonPath("$.data.order_financial_summary.product_subtotal").value(890.0))
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(890.0))
                .andExpect(jsonPath("$.data.order_financial_summary.total_cost").value(420.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(470.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_percent").value(52.81));

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_product_line WHERE order_id = ?", Integer.class, ORDER_HARD_EMPTY);
        Assertions.assertEquals(Integer.valueOf(1), rows);
        assertMoney("890.00", queryMoney("sale_price_ex_gst", ORDER_HARD_EMPTY));
        assertMoney("420.00", queryMoney("total_cost", ORDER_HARD_EMPTY));
        assertMoney("470.00", queryMoney("gp", ORDER_HARD_EMPTY));
        assertMoney("52.81", queryMoney("gp_percent", ORDER_HARD_EMPTY));
    }

    @Test
    void addProductLine_fourMetreRoll_usesSqmPerLmSnapshot4() throws Exception {
        clearOrderLines(ORDER_SOFT_FULL);
        mockMvc.perform(post(productLinesUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":2,\"quantity_lm\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.product_line.product_code_snapshot").value("LOP-001"))
                .andExpect(jsonPath("$.data.product_line.sqm_per_lm_snapshot").value(4.0))
                .andExpect(jsonPath("$.data.product_line.quantity_lm").value(10.0))
                .andExpect(jsonPath("$.data.product_line.quantity_sqm").value(40.0))
                .andExpect(jsonPath("$.data.product_line.line_total").value(380.0));
    }

    @Test
    void addProductLine_unitPriceOverride_used() throws Exception {
        clearOrderLines(ORDER_SOFT_FULL);
        mockMvc.perform(post(productLinesUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":1,\"quantity_lm\":8,\"unit_price\":50}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.product_line.unit_price").value(50.0))
                .andExpect(jsonPath("$.data.product_line.price_snapshot").value(45.0))
                .andExpect(jsonPath("$.data.product_line.line_total").value(400.0));
    }

    @Test
    void addProductLine_inactiveProduct_returns422() throws Exception {
        jdbcTemplate.update("UPDATE store_product SET is_active = FALSE WHERE product_id = ?", PRODUCT_PLU);
        mockMvc.perform(post(productLinesUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":1,\"quantity_lm\":8}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_INACTIVE"));
    }

    @Test
    void addProductLine_flooringTypeMismatch_returns422() throws Exception {
        // HARD product (3) onto a SOFT order (1).
        mockMvc.perform(post(productLinesUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":3,\"quantity_sqm\":10}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FLOORING_TYPE_MISMATCH"));
    }

    @Test
    void addProductLine_crossStoreProduct_returns404() throws Exception {
        mockMvc.perform(post(productLinesUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":5,\"quantity_lm\":8}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void addProductLine_laidOrder_returns422OrderLocked() throws Exception {
        laidOrder(ORDER_HARD_EMPTY);
        mockMvc.perform(post(productLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":3,\"quantity_sqm\":10}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORDER_LOCKED"));
    }

    @Test
    void addProductLine_crossStoreOrder_returns404() throws Exception {
        mockMvc.perform(post(productLinesUrl(ORDER_OTHER_STORE))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":1,\"quantity_lm\":8}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void addProductLine_forbiddenField_returns400() throws Exception {
        mockMvc.perform(post(productLinesUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":1,\"quantity_lm\":8,\"cost_snapshot\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("cost_snapshot"));
    }

    @Test
    void addProductLine_lineTotalForbiddenField_returns400() throws Exception {
        mockMvc.perform(post(productLinesUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":1,\"quantity_lm\":8,\"line_total\":999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("line_total"));
    }

    @Test
    void addProductLine_missingProductId_returns400() throws Exception {
        mockMvc.perform(post(productLinesUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity_lm\":8}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("product_id"));
    }

    @Test
    void addProductLine_oversizedProductId_returns400_andAddsNoLine() throws Exception {
        // 18446744073709551617 = 2^64 + 1, parsed by Jackson as a BigIntegerNode beyond Long range.
        // longValue() would wrap it (to 1) — it must instead be rejected as VALIDATION_FAILED.
        mockMvc.perform(post(productLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":18446744073709551617,\"quantity_sqm\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("product_id"));

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_product_line WHERE order_id = ?", Integer.class, ORDER_HARD_EMPTY);
        Assertions.assertEquals(Integer.valueOf(0), rows, "oversized product_id must not create a line");
    }

    @Test
    void addProductLine_hugeQuantityLm_exceedsDbRange_returns400_andAddsNoLine() throws Exception {
        // 100000000 (1e8) has 9 integer digits — beyond DECIMAL(10,2) max 99999999.99.
        mockMvc.perform(post(productLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":3,\"quantity_lm\":100000000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("quantity_lm"));
        assertNoProductLines(ORDER_HARD_EMPTY);
    }

    @Test
    void addProductLine_hugeQuantitySqm_exceedsDbRange_returns400_andAddsNoLine() throws Exception {
        mockMvc.perform(post(productLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":3,\"quantity_sqm\":100000000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("quantity_sqm"));
        assertNoProductLines(ORDER_HARD_EMPTY);
    }

    @Test
    void addProductLine_hugeUnitPrice_exceedsDbRange_returns400_andAddsNoLine() throws Exception {
        mockMvc.perform(post(productLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":3,\"quantity_sqm\":10,\"unit_price\":100000000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("unit_price"));
        assertNoProductLines(ORDER_HARD_EMPTY);
    }

    @Test
    void addProductLine_lineTotalOverflowsThoughInputsFit_returns400_andAddsNoLine() throws Exception {
        // quantity_sqm 10000 and unit_price 99999.99 each fit DECIMAL(10,2), but the product
        // line_total = round(10000 × 99999.99, 2) = 999999900.00 (9 integer digits) overflows it.
        mockMvc.perform(post(productLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":3,\"quantity_sqm\":10000,\"unit_price\":99999.99}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertNoProductLines(ORDER_HARD_EMPTY);
    }

    @Test
    void addProductLine_summedHeaderAggregateOverflow_returns400() throws Exception {
        // Each line fits DECIMAL(10,2) on its own (line_total 99,990,000.00) and as a one-line header
        // aggregate, but a second identical line pushes the summed sale_price_ex_gst past
        // 99999999.99 → requirePersistableHeader rejects the mutation with 400 (not a DB 500).
        mockMvc.perform(post(productLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":3,\"quantity_sqm\":1000000,\"unit_price\":99.99}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post(productLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":3,\"quantity_sqm\":1000000,\"unit_price\":99.99}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void addProductLine_bothQuantities_returns400() throws Exception {
        mockMvc.perform(post(productLinesUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":1,\"quantity_lm\":8,\"quantity_sqm\":29.28}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void addProductLine_neitherQuantity_returns400() throws Exception {
        mockMvc.perform(post(productLinesUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void addProductLine_tinyQuantityRoundsDerivedToZero_returns400NotServerError() throws Exception {
        // product 2 (LOP-001) has sqm_per_lm 4.00; quantity_sqm 0.01 → quantity_lm = round(0.0025,2) = 0.00.
        clearOrderLines(ORDER_SOFT_FULL);
        mockMvc.perform(post(productLinesUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":2,\"quantity_sqm\":0.01}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void addProductLine_tinyLineTotalRoundsToZero_returns400NotServerError() throws Exception {
        // quantity_lm 0.01 (derived sqm 0.04 > 0) but line_total = round(0.01 × 0.01, 2) = 0.00.
        clearOrderLines(ORDER_SOFT_FULL);
        mockMvc.perform(post(productLinesUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":1,\"quantity_lm\":0.01,\"unit_price\":0.01}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void addProductLine_normalizesSuppliedDecimalsToTwoPlaces_beforeDerivationAndTotals() throws Exception {
        // Regression: the client sends 3dp values. The backend must round each supplied value to 2dp
        // (HALF_UP) BEFORE deriving the other quantity and computing line_total, so the stored
        // unit_price/quantity and line_total stay consistent. quantity_lm 1.235 → 1.24;
        // unit_price 1.235 → 1.24; quantity_sqm = round(1.24 × 3.66, 2) = 4.54;
        // line_total = round(1.24 × 1.24, 2) = 1.54. Before the fix, derivation/totals used the
        // unrounded 1.235 (quantity_sqm 4.52, line_total 1.53) while the columns rounded to 1.24.
        clearOrderLines(ORDER_SOFT_FULL);
        mockMvc.perform(post(productLinesUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":1,\"quantity_lm\":1.235,\"unit_price\":1.235}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.product_line.quantity_lm").value(1.24))
                .andExpect(jsonPath("$.data.product_line.unit_price").value(1.24))
                .andExpect(jsonPath("$.data.product_line.quantity_sqm").value(4.54))
                .andExpect(jsonPath("$.data.product_line.line_total").value(1.54));
    }

    @Test
    void addProductLine_persistsRowAndExcludesLineCost() throws Exception {
        clearOrderLines(ORDER_SOFT_FULL);
        mockMvc.perform(post(productLinesUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":1,\"quantity_lm\":8}"))
                .andExpect(status().isCreated());

        BigDecimal lineCost = jdbcTemplate.queryForObject(
                "SELECT line_cost FROM order_product_line WHERE order_id = ? ORDER BY order_product_line_id DESC LIMIT 1",
                BigDecimal.class, ORDER_SOFT_FULL);
        assertMoney("176.00", lineCost); // persisted server-side, never serialized
    }

    // ================================================================
    // PATCH /product-lines/{lineId}
    // ================================================================

    @Test
    void updateProductLine_quantityLm_reDerivesSqm_keepsSnapshots() throws Exception {
        mockMvc.perform(patch(productLineUrl(ORDER_SOFT_FULL, PRODUCT_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity_lm\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Product line updated."))
                .andExpect(jsonPath("$.data.product_line.quantity_lm").value(10.0))
                .andExpect(jsonPath("$.data.product_line.quantity_sqm").value(36.6))
                .andExpect(jsonPath("$.data.product_line.unit_price").value(45.0))
                .andExpect(jsonPath("$.data.product_line.sqm_per_lm_snapshot").value(3.66))
                .andExpect(jsonPath("$.data.product_line.product_code_snapshot").value("PLU-001"))
                .andExpect(jsonPath("$.data.product_line.line_total").value(450.0))
                .andExpect(jsonPath("$.data.product_line.line_cost").doesNotExist())
                .andExpect(jsonPath("$.data.order_financial_summary.product_subtotal").value(450.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(454.0));
    }

    @Test
    void updateProductLine_quantitySqm_reDerivesLm() throws Exception {
        mockMvc.perform(patch(productLineUrl(ORDER_SOFT_FULL, PRODUCT_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity_sqm\":14.64}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.product_line.quantity_sqm").value(14.64))
                .andExpect(jsonPath("$.data.product_line.quantity_lm").value(4.0))
                .andExpect(jsonPath("$.data.product_line.line_total").value(180.0));
    }

    @Test
    void updateProductLine_unitPrice_recomputesLineTotal_andHeader() throws Exception {
        mockMvc.perform(patch(productLineUrl(ORDER_SOFT_FULL, PRODUCT_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unit_price\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.product_line.unit_price").value(50.0))
                .andExpect(jsonPath("$.data.product_line.line_total").value(400.0))
                .andExpect(jsonPath("$.data.order_financial_summary.product_subtotal").value(400.0))
                .andExpect(jsonPath("$.data.order_financial_summary.charge_subtotal").value(480.0))
                .andExpect(jsonPath("$.data.order_financial_summary.total_cost").value(432.0));

        assertMoney("880.00", queryMoney("sale_price_ex_gst", ORDER_SOFT_FULL));
        assertMoney("448.00", queryMoney("gp", ORDER_SOFT_FULL));
    }

    @Test
    void updateProductLine_explicitNullUnitPrice_meansUnchanged() throws Exception {
        mockMvc.perform(patch(productLineUrl(ORDER_SOFT_FULL, PRODUCT_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity_lm\":10,\"unit_price\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.product_line.quantity_lm").value(10.0))
                .andExpect(jsonPath("$.data.product_line.unit_price").value(45.0))
                .andExpect(jsonPath("$.data.product_line.line_total").value(450.0));
    }

    @Test
    void updateProductLine_hugeQuantityLm_returns400_andLeavesLineUnchanged() throws Exception {
        mockMvc.perform(patch(productLineUrl(ORDER_SOFT_FULL, PRODUCT_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity_lm\":100000000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("quantity_lm"));
        assertMoney("8.00", queryProductLineMoney("quantity_lm", PRODUCT_LINE_1));
        assertMoney("360.00", queryProductLineMoney("line_total", PRODUCT_LINE_1));
    }

    @Test
    void updateProductLine_hugeQuantitySqm_returns400_andLeavesLineUnchanged() throws Exception {
        mockMvc.perform(patch(productLineUrl(ORDER_SOFT_FULL, PRODUCT_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity_sqm\":100000000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("quantity_sqm"));
        assertMoney("8.00", queryProductLineMoney("quantity_lm", PRODUCT_LINE_1));
        assertMoney("29.28", queryProductLineMoney("quantity_sqm", PRODUCT_LINE_1));
    }

    @Test
    void updateProductLine_hugeUnitPrice_returns400_andLeavesLineUnchanged() throws Exception {
        mockMvc.perform(patch(productLineUrl(ORDER_SOFT_FULL, PRODUCT_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unit_price\":100000000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("unit_price"));
        assertMoney("45.00", queryProductLineMoney("unit_price", PRODUCT_LINE_1));
        assertMoney("360.00", queryProductLineMoney("line_total", PRODUCT_LINE_1));
    }

    @Test
    void updateProductLine_bothQuantities_returns400() throws Exception {
        mockMvc.perform(patch(productLineUrl(ORDER_SOFT_FULL, PRODUCT_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity_lm\":10,\"quantity_sqm\":36.6}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void updateProductLine_emptyBody_returns400() throws Exception {
        mockMvc.perform(patch(productLineUrl(ORDER_SOFT_FULL, PRODUCT_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void updateProductLine_forbiddenProductId_returns400() throws Exception {
        mockMvc.perform(patch(productLineUrl(ORDER_SOFT_FULL, PRODUCT_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":2,\"quantity_lm\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("product_id"));
    }

    @Test
    void updateProductLine_forbiddenCostSnapshot_returns400_andLeavesLineUnchanged() throws Exception {
        mockMvc.perform(patch(productLineUrl(ORDER_SOFT_FULL, PRODUCT_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity_lm\":10,\"cost_snapshot\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("cost_snapshot"));
        assertMoney("8.00", queryProductLineMoney("quantity_lm", PRODUCT_LINE_1));
    }

    @Test
    void updateProductLine_lineTotalOverflowsThoughInputsFit_returns400_andLeavesLineUnchanged() throws Exception {
        // quantity_lm 10000000 and unit_price 99.99 each fit DECIMAL(10,2), but the LM-priced
        // line_total = round(10000000 × 99.99, 2) = 999900000.00 overflows it.
        mockMvc.perform(patch(productLineUrl(ORDER_SOFT_FULL, PRODUCT_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity_lm\":10000000,\"unit_price\":99.99}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertMoney("8.00", queryProductLineMoney("quantity_lm", PRODUCT_LINE_1));
        assertMoney("360.00", queryProductLineMoney("line_total", PRODUCT_LINE_1));
    }

    @Test
    void updateProductLine_makesSalePriceNegative_succeeds_andPersistsNegative() throws Exception {
        // Negative sale price is ALLOWED while editing an order. With a stored -600.00 inc-GST
        // discount, dropping the product line_total to 8.00 (unit_price 1 × 8 LM) makes
        // calculated (8+480)×1.1 = 536.80 → final 536.80-600 = -63.20 → sale_ex round(-63.20/1.1) = -57.45.
        // The PATCH succeeds and the real negative value is persisted — not clamped, not a DB 500.
        jdbcTemplate.update("UPDATE sales_order SET price_adjustment_inc_gst = ? WHERE order_id = ?",
                new BigDecimal("-600.00"), ORDER_SOFT_FULL);

        mockMvc.perform(patch(productLineUrl(ORDER_SOFT_FULL, PRODUCT_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unit_price\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Product line updated."))
                .andExpect(jsonPath("$.data.product_line.unit_price").value(1.0))
                .andExpect(jsonPath("$.data.product_line.line_total").value(8.0))
                .andExpect(jsonPath("$.data.order_financial_summary.calculated_total_inc_gst").value(536.8))
                .andExpect(jsonPath("$.data.order_financial_summary.price_adjustment_inc_gst").value(-600.0))
                .andExpect(jsonPath("$.data.order_financial_summary.final_sale_price_inc_gst").value(-63.2))
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(-57.45))
                .andExpect(jsonPath("$.data.order_financial_summary.total_cost").value(432.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(-489.45))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_percent").value(nullValue()))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_warning").value(false));

        // Line updated normally, and the negative header value is persisted as-is.
        assertMoney("1.00", queryProductLineMoney("unit_price", PRODUCT_LINE_1));
        assertMoney("8.00", queryProductLineMoney("line_total", PRODUCT_LINE_1));
        assertMoney("-57.45", queryMoney("sale_price_ex_gst", ORDER_SOFT_FULL));
        assertMoney("-489.45", queryMoney("gp", ORDER_SOFT_FULL));
        Assertions.assertNull(queryMoney("gp_percent", ORDER_SOFT_FULL),
                "gp_percent persists NULL when sale_price_ex_gst <= 0");
    }

    @Test
    void updateProductLine_missingLine_returns404() throws Exception {
        mockMvc.perform(patch(productLineUrl(ORDER_SOFT_FULL, LINE_DOES_NOT_EXIST))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity_lm\":10}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("LINE_NOT_FOUND"));
    }

    @Test
    void updateProductLine_lineOnAnotherOrder_returns404() throws Exception {
        // line 1 belongs to order 1, not order 2.
        mockMvc.perform(patch(productLineUrl(ORDER_HARD_EMPTY, PRODUCT_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity_sqm\":10}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("LINE_NOT_FOUND"));
    }

    @Test
    void updateProductLine_laidOrder_returns422() throws Exception {
        laidOrder(ORDER_SOFT_FULL);
        mockMvc.perform(patch(productLineUrl(ORDER_SOFT_FULL, PRODUCT_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity_lm\":10}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORDER_LOCKED"));
    }

    // ================================================================
    // DELETE /product-lines/{lineId}
    // ================================================================

    @Test
    void deleteProductLine_removesLine_keepsChargeInSummary() throws Exception {
        mockMvc.perform(delete(productLineUrl(ORDER_SOFT_FULL, PRODUCT_LINE_1))
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Product line deleted."))
                .andExpect(jsonPath("$.data.deleted_product_line_id").value(1))
                .andExpect(jsonPath("$.data.order_financial_summary.product_subtotal").value(0.0))
                .andExpect(jsonPath("$.data.order_financial_summary.charge_subtotal").value(480.0))
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(480.0))
                .andExpect(jsonPath("$.data.order_financial_summary.total_cost").value(256.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(224.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_percent").value(46.67));

        Integer productRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_product_line WHERE order_id = ?", Integer.class, ORDER_SOFT_FULL);
        Assertions.assertEquals(Integer.valueOf(0), productRows);
        Integer chargeRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_charge_line WHERE order_id = ?", Integer.class, ORDER_SOFT_FULL);
        Assertions.assertEquals(Integer.valueOf(1), chargeRows);
        assertMoney("480.00", queryMoney("sale_price_ex_gst", ORDER_SOFT_FULL));
    }

    @Test
    void deleteProductLine_makesSalePriceNegative_succeeds_andPersistsNegative() throws Exception {
        // Negative sale price is ALLOWED while editing an order. A stored -600.00 inc-GST discount on
        // order 1 is valid; deleting the product line drops the calculated total to (0+480)×1.1 =
        // 528.00 → final 528-600 = -72.00 → sale_ex round(-72.00/1.1) = -65.45. The DELETE succeeds and
        // the real negative value persists — not clamped, not a DB 500.
        jdbcTemplate.update("UPDATE sales_order SET price_adjustment_inc_gst = ? WHERE order_id = ?",
                new BigDecimal("-600.00"), ORDER_SOFT_FULL);

        mockMvc.perform(delete(productLineUrl(ORDER_SOFT_FULL, PRODUCT_LINE_1))
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Product line deleted."))
                .andExpect(jsonPath("$.data.deleted_product_line_id").value(1))
                .andExpect(jsonPath("$.data.order_financial_summary.product_subtotal").value(0.0))
                .andExpect(jsonPath("$.data.order_financial_summary.charge_subtotal").value(480.0))
                .andExpect(jsonPath("$.data.order_financial_summary.calculated_total_inc_gst").value(528.0))
                .andExpect(jsonPath("$.data.order_financial_summary.final_sale_price_inc_gst").value(-72.0))
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(-65.45))
                .andExpect(jsonPath("$.data.order_financial_summary.total_cost").value(256.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(-321.45))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_percent").value(nullValue()))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_warning").value(false));

        // Line deleted normally, and the negative header value is persisted as-is.
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_product_line WHERE order_product_line_id = ?",
                Integer.class, PRODUCT_LINE_1);
        Assertions.assertEquals(Integer.valueOf(0), rows, "the product line must be deleted");
        assertMoney("-65.45", queryMoney("sale_price_ex_gst", ORDER_SOFT_FULL));
        assertMoney("-321.45", queryMoney("gp", ORDER_SOFT_FULL));
    }

    @Test
    void deleteProductLine_missingLine_returns404() throws Exception {
        mockMvc.perform(delete(productLineUrl(ORDER_SOFT_FULL, LINE_DOES_NOT_EXIST))
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("LINE_NOT_FOUND"));
    }

    @Test
    void deleteProductLine_lineOnAnotherOrder_returns404() throws Exception {
        mockMvc.perform(delete(productLineUrl(ORDER_HARD_EMPTY, PRODUCT_LINE_1))
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("LINE_NOT_FOUND"));
    }

    @Test
    void deleteProductLine_laidOrder_returns422() throws Exception {
        laidOrder(ORDER_SOFT_FULL);
        mockMvc.perform(delete(productLineUrl(ORDER_SOFT_FULL, PRODUCT_LINE_1))
                        .session(liamStore1Session()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORDER_LOCKED"));
    }

    // ================================================================
    // gp_percent overflow (R4): response returns true value, persisted column is NULL
    // ================================================================

    @Test
    void mutation_gpPercentOverflow_responseHasValue_butPersistsNull() throws Exception {
        // product 3 (HARD, cost 42), unit_price 0.01 on a single SQM unit → sale_price_ex_gst 0.01,
        // total_cost 42.00, gp -41.99, gp_percent = -419900.00 (outside DECIMAL(5,2)).
        mockMvc.perform(post(productLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_id\":3,\"quantity_sqm\":1,\"unit_price\":0.01}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(0.01))
                .andExpect(jsonPath("$.data.order_financial_summary.total_cost").value(42.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(-41.99))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_percent").isNumber())
                .andExpect(jsonPath("$.data.order_financial_summary.gp_warning").value(true));

        // Persisted header: gp_percent NULLed (overflow), the rest stored normally.
        Assertions.assertNull(queryMoney("gp_percent", ORDER_HARD_EMPTY),
                "gp_percent must persist as NULL when it overflows DECIMAL(5,2)");
        assertMoney("0.01", queryMoney("sale_price_ex_gst", ORDER_HARD_EMPTY));
        assertMoney("42.00", queryMoney("total_cost", ORDER_HARD_EMPTY));
        assertMoney("-41.99", queryMoney("gp", ORDER_HARD_EMPTY));
    }
}
