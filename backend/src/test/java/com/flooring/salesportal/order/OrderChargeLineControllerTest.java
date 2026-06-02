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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the Chunk 3 Branch 3 order charge-line endpoints (D.7–D.9):
 * POST /charge-lines, PATCH /charge-lines/{lineId}, DELETE /charge-lines/{lineId}.
 *
 * <p>Mirrors {@code OrderLineControllerTest}: {@code @SpringBootTest @Transactional}, MockMvc,
 * {@code MockHttpSession} carrying the seeded Liam / business 1 / store 1 identity, and
 * {@code JdbcTemplate} for data tweaks + persistence assertions (rolled back with the test
 * transaction). Seed (V4): store_charge 1=INST-S SOFT 15/8, 2=UNDR-S SOFT 8/4, 3=INST-H HARD 22/12,
 * 4=UNDR-H HARD 10/5; order 1 = SOFT/ACCEPTED with product line 1 (8 LM PLU-001 → line_total 360.00,
 * line_cost 176.00) and charge line 1 (32 INST-S → line_total 480.00, line_cost 256.00); order 2 =
 * HARD/LEAD empty. Charges have a single quantity (no LM/SQM, no sqm_per_lm, no pricing unit).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class OrderChargeLineControllerTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";

    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    private static final long ORDER_SOFT_FULL = 1L;   // SOFT, ACCEPTED, store 1, product line 1 + charge line 1
    private static final long ORDER_HARD_EMPTY = 2L;  // HARD, LEAD, store 1, empty

    private static final long CHARGE_UNDR_S = 2L;     // SOFT, price 8.00, cost 4.00
    private static final long CHARGE_INST_H = 3L;     // HARD, price 22.00, cost 12.00
    private static final long CHARGE_UNDR_H = 4L;     // HARD, price 10.00, cost 5.00

    private static final long CHARGE_LINE_1 = 1L;     // seeded charge line on order 1 (32 INST-S)
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

    private static String chargeLinesUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/charge-lines";
    }

    private static String chargeLineUrl(Object orderId, Object lineId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/charge-lines/" + lineId;
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

    private BigDecimal queryChargeLineMoney(String column, long lineId) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM order_charge_line WHERE order_charge_line_id = ?",
                BigDecimal.class, lineId);
    }

    private int countChargeLines(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_charge_line WHERE order_id = ?", Integer.class, orderId);
    }

    /** Insert an active SOFT charge belonging to store 2 (unique on (store_id, code)); returns its id. */
    private long insertStore2Charge() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost, is_active) "
                        + "VALUES (2, 'SOFT'::flooring_type, 'TST-S2', 'Store 2 Test Charge', 20.00, 10.00, TRUE) "
                        + "RETURNING charge_id",
                Long.class);
    }

    // ================================================================
    // POST /charge-lines
    // ================================================================

    @Test
    void addChargeLine_noSession_returns401() throws Exception {
        mockMvc.perform(post(chargeLinesUrl(ORDER_HARD_EMPTY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charge_id\":4,\"quantity\":10}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void addChargeLine_sessionWithoutStore_returns403() throws Exception {
        mockMvc.perform(post(chargeLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamSessionNoStore())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charge_id\":4,\"quantity\":10}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void addChargeLine_catalogPrice_returnsLineAndSummary_andPersistsHeader() throws Exception {
        // Empty HARD order 2 + charge 4 (UNDR-H, price 10, cost 5) qty 10:
        // line_total 100, line_cost 50; calc 100×1.1=110, sale_ex 100, total_cost 50, gp 50, gp% 50.00.
        mockMvc.perform(post(chargeLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charge_id\":4,\"quantity\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Charge line added."))
                .andExpect(jsonPath("$.data.charge_line.charge_id").value(4))
                .andExpect(jsonPath("$.data.charge_line.charge_code_snapshot").value("UNDR-H"))
                .andExpect(jsonPath("$.data.charge_line.charge_name_snapshot").value("Hard Floor Underlay Supply"))
                .andExpect(jsonPath("$.data.charge_line.price_snapshot").value(10.0))
                .andExpect(jsonPath("$.data.charge_line.cost_snapshot").value(5.0))
                .andExpect(jsonPath("$.data.charge_line.quantity").value(10.0))
                .andExpect(jsonPath("$.data.charge_line.unit_price").value(10.0))
                .andExpect(jsonPath("$.data.charge_line.line_total").value(100.0))
                .andExpect(jsonPath("$.data.charge_line.line_cost").doesNotExist())
                .andExpect(jsonPath("$.data.charge_line.pricing_unit_snapshot").doesNotExist())
                .andExpect(jsonPath("$.data.charge_line.sqm_per_lm_snapshot").doesNotExist())
                .andExpect(jsonPath("$.data.order_financial_summary.product_subtotal").value(0.0))
                .andExpect(jsonPath("$.data.order_financial_summary.charge_subtotal").value(100.0))
                .andExpect(jsonPath("$.data.order_financial_summary.calculated_total_inc_gst").value(110.0))
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(100.0))
                .andExpect(jsonPath("$.data.order_financial_summary.total_cost").value(50.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(50.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_percent").value(50.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_warning").value(false));

        Assertions.assertEquals(1, countChargeLines(ORDER_HARD_EMPTY));
        assertMoney("100.00", queryMoney("sale_price_ex_gst", ORDER_HARD_EMPTY));
        assertMoney("50.00", queryMoney("total_cost", ORDER_HARD_EMPTY));
        assertMoney("50.00", queryMoney("gp", ORDER_HARD_EMPTY));
        assertMoney("50.00", queryMoney("gp_percent", ORDER_HARD_EMPTY));
    }

    @Test
    void addChargeLine_unitPriceOverride_used() throws Exception {
        mockMvc.perform(post(chargeLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charge_id\":4,\"quantity\":10,\"unit_price\":12}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.charge_line.unit_price").value(12.0))
                .andExpect(jsonPath("$.data.charge_line.price_snapshot").value(10.0))
                .andExpect(jsonPath("$.data.charge_line.line_total").value(120.0));
    }

    @Test
    void addChargeLine_onOrderWithExistingLines_preservesProductSubtotal() throws Exception {
        // Order 1 (SOFT): product 360 + charge1 480/256. Add charge 2 (UNDR-S, 8/4) qty 10 → line 80/40.
        // charge_subtotal 480+80=560, product_subtotal stays 360, total_cost 176+256+40=472,
        // calc (360+560)×1.1=1012, sale_ex 920, gp 448, gp% 44800/920=48.70.
        mockMvc.perform(post(chargeLinesUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charge_id\":2,\"quantity\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.charge_line.charge_code_snapshot").value("UNDR-S"))
                .andExpect(jsonPath("$.data.charge_line.line_total").value(80.0))
                .andExpect(jsonPath("$.data.order_financial_summary.product_subtotal").value(360.0))
                .andExpect(jsonPath("$.data.order_financial_summary.charge_subtotal").value(560.0))
                .andExpect(jsonPath("$.data.order_financial_summary.calculated_total_inc_gst").value(1012.0))
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(920.0))
                .andExpect(jsonPath("$.data.order_financial_summary.total_cost").value(472.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(448.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_percent").value(48.7));

        Assertions.assertEquals(2, countChargeLines(ORDER_SOFT_FULL));
    }

    @Test
    void addChargeLine_missingChargeId_returns400() throws Exception {
        mockMvc.perform(post(chargeLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("charge_id"));
    }

    @Test
    void addChargeLine_missingQuantity_returns400() throws Exception {
        mockMvc.perform(post(chargeLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charge_id\":4}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("quantity"));
    }

    @Test
    void addChargeLine_nonPositiveQuantity_returns400() throws Exception {
        mockMvc.perform(post(chargeLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charge_id\":4,\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("quantity"));
    }

    @Test
    void addChargeLine_nonPositiveUnitPrice_returns400() throws Exception {
        mockMvc.perform(post(chargeLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charge_id\":4,\"quantity\":10,\"unit_price\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("unit_price"));
    }

    @Test
    void addChargeLine_forbiddenCostSnapshot_returns400() throws Exception {
        mockMvc.perform(post(chargeLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charge_id\":4,\"quantity\":10,\"cost_snapshot\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("cost_snapshot"));
    }

    @Test
    void addChargeLine_lineTotalForbiddenField_returns400() throws Exception {
        mockMvc.perform(post(chargeLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charge_id\":4,\"quantity\":10,\"line_total\":999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("line_total"));
    }

    @Test
    void addChargeLine_forbiddenPricingUnitSnapshot_returns400() throws Exception {
        // Product-only snapshot field must still be rejected on a charge line (any *_snapshot key).
        mockMvc.perform(post(chargeLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charge_id\":4,\"quantity\":10,\"pricing_unit_snapshot\":\"LM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("pricing_unit_snapshot"));
    }

    @Test
    void addChargeLine_forbiddenSqmPerLmSnapshot_returns400() throws Exception {
        mockMvc.perform(post(chargeLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charge_id\":4,\"quantity\":10,\"sqm_per_lm_snapshot\":3.66}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("sqm_per_lm_snapshot"));
    }

    @Test
    void addChargeLine_inactiveCharge_returns422() throws Exception {
        jdbcTemplate.update("UPDATE store_charge SET is_active = FALSE WHERE charge_id = ?", CHARGE_UNDR_S);
        mockMvc.perform(post(chargeLinesUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charge_id\":2,\"quantity\":10}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CHARGE_INACTIVE"));
    }

    @Test
    void addChargeLine_flooringTypeMismatch_returns422() throws Exception {
        // HARD charge (3) onto a SOFT order (1).
        mockMvc.perform(post(chargeLinesUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charge_id\":3,\"quantity\":10}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FLOORING_TYPE_MISMATCH"));
    }

    @Test
    void addChargeLine_crossStoreCharge_returns404() throws Exception {
        long store2Charge = insertStore2Charge();
        mockMvc.perform(post(chargeLinesUrl(ORDER_SOFT_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charge_id\":" + store2Charge + ",\"quantity\":10}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHARGE_NOT_FOUND"));
    }

    @Test
    void addChargeLine_laidOrder_returns422OrderLocked() throws Exception {
        laidOrder(ORDER_HARD_EMPTY);
        mockMvc.perform(post(chargeLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charge_id\":4,\"quantity\":10}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORDER_LOCKED"));
    }

    @Test
    void addChargeLine_crossStoreOrder_returns404() throws Exception {
        // order 5 belongs to store 2.
        mockMvc.perform(post(chargeLinesUrl(5L))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charge_id\":4,\"quantity\":10}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void addChargeLine_persistsRowAndExcludesLineCost() throws Exception {
        mockMvc.perform(post(chargeLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charge_id\":4,\"quantity\":10}"))
                .andExpect(status().isCreated());

        BigDecimal lineCost = jdbcTemplate.queryForObject(
                "SELECT line_cost FROM order_charge_line WHERE order_id = ? ORDER BY order_charge_line_id DESC LIMIT 1",
                BigDecimal.class, ORDER_HARD_EMPTY);
        assertMoney("50.00", lineCost); // persisted server-side, never serialized
    }

    // ================================================================
    // PATCH /charge-lines/{lineId}
    // ================================================================

    @Test
    void updateChargeLine_quantity_recomputesLineTotalLineCost_andHeader() throws Exception {
        // Order 1 charge line 1 (32 INST-S @ 15, cost 8) → qty 40: line_total 600, line_cost 320.
        // product 360, charge 600, calc (360+600)×1.1=1056, sale_ex 960, total_cost 176+320=496, gp 464.
        mockMvc.perform(patch(chargeLineUrl(ORDER_SOFT_FULL, CHARGE_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":40}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Charge line updated."))
                .andExpect(jsonPath("$.data.charge_line.quantity").value(40.0))
                .andExpect(jsonPath("$.data.charge_line.unit_price").value(15.0))
                .andExpect(jsonPath("$.data.charge_line.line_total").value(600.0))
                .andExpect(jsonPath("$.data.charge_line.line_cost").doesNotExist())
                .andExpect(jsonPath("$.data.order_financial_summary.product_subtotal").value(360.0))
                .andExpect(jsonPath("$.data.order_financial_summary.charge_subtotal").value(600.0))
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(960.0))
                .andExpect(jsonPath("$.data.order_financial_summary.total_cost").value(496.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(464.0));

        assertMoney("320.00", queryChargeLineMoney("line_cost", CHARGE_LINE_1));
        assertMoney("960.00", queryMoney("sale_price_ex_gst", ORDER_SOFT_FULL));
        assertMoney("464.00", queryMoney("gp", ORDER_SOFT_FULL));
    }

    @Test
    void updateChargeLine_unitPrice_recomputesLineTotal_andHeader() throws Exception {
        // qty stays 32, unit_price → 20: line_total 640, line_cost stays 256 (cost_snapshot 8).
        // product 360, charge 640, calc 1100, sale_ex 1000, total_cost 432, gp 568.
        mockMvc.perform(patch(chargeLineUrl(ORDER_SOFT_FULL, CHARGE_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unit_price\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.charge_line.unit_price").value(20.0))
                .andExpect(jsonPath("$.data.charge_line.line_total").value(640.0))
                .andExpect(jsonPath("$.data.order_financial_summary.charge_subtotal").value(640.0))
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(1000.0))
                .andExpect(jsonPath("$.data.order_financial_summary.total_cost").value(432.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(568.0));

        assertMoney("1000.00", queryMoney("sale_price_ex_gst", ORDER_SOFT_FULL));
        assertMoney("568.00", queryMoney("gp", ORDER_SOFT_FULL));
        assertMoney("256.00", queryChargeLineMoney("line_cost", CHARGE_LINE_1));
    }

    @Test
    void updateChargeLine_explicitNullUnitPrice_meansUnchanged() throws Exception {
        mockMvc.perform(patch(chargeLineUrl(ORDER_SOFT_FULL, CHARGE_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":40,\"unit_price\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.charge_line.quantity").value(40.0))
                .andExpect(jsonPath("$.data.charge_line.unit_price").value(15.0))
                .andExpect(jsonPath("$.data.charge_line.line_total").value(600.0));
    }

    @Test
    void updateChargeLine_emptyBody_returns400() throws Exception {
        mockMvc.perform(patch(chargeLineUrl(ORDER_SOFT_FULL, CHARGE_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void updateChargeLine_chargeId_returns400() throws Exception {
        mockMvc.perform(patch(chargeLineUrl(ORDER_SOFT_FULL, CHARGE_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charge_id\":2,\"quantity\":40}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("charge_id"));
    }

    @Test
    void updateChargeLine_forbiddenCostSnapshot_returns400_andLeavesLineUnchanged() throws Exception {
        mockMvc.perform(patch(chargeLineUrl(ORDER_SOFT_FULL, CHARGE_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":40,\"cost_snapshot\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("cost_snapshot"));
        assertMoney("32.00", queryChargeLineMoney("quantity", CHARGE_LINE_1));
        assertMoney("480.00", queryChargeLineMoney("line_total", CHARGE_LINE_1));
    }

    @Test
    void updateChargeLine_forbiddenPricingUnitSnapshot_returns400_andLeavesLineUnchanged() throws Exception {
        mockMvc.perform(patch(chargeLineUrl(ORDER_SOFT_FULL, CHARGE_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":40,\"pricing_unit_snapshot\":\"LM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("pricing_unit_snapshot"));
        assertMoney("32.00", queryChargeLineMoney("quantity", CHARGE_LINE_1));
        assertMoney("480.00", queryChargeLineMoney("line_total", CHARGE_LINE_1));
    }

    @Test
    void updateChargeLine_forbiddenSqmPerLmSnapshot_returns400_andLeavesLineUnchanged() throws Exception {
        mockMvc.perform(patch(chargeLineUrl(ORDER_SOFT_FULL, CHARGE_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":40,\"sqm_per_lm_snapshot\":3.66}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("sqm_per_lm_snapshot"));
        assertMoney("32.00", queryChargeLineMoney("quantity", CHARGE_LINE_1));
    }

    @Test
    void updateChargeLine_lineOnAnotherOrder_returns404() throws Exception {
        // charge line 1 belongs to order 1, not order 2.
        mockMvc.perform(patch(chargeLineUrl(ORDER_HARD_EMPTY, CHARGE_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":10}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("LINE_NOT_FOUND"));
    }

    @Test
    void updateChargeLine_missingLine_returns404() throws Exception {
        mockMvc.perform(patch(chargeLineUrl(ORDER_SOFT_FULL, LINE_DOES_NOT_EXIST))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":10}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("LINE_NOT_FOUND"));
    }

    @Test
    void updateChargeLine_laidOrder_returns422() throws Exception {
        laidOrder(ORDER_SOFT_FULL);
        mockMvc.perform(patch(chargeLineUrl(ORDER_SOFT_FULL, CHARGE_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":40}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORDER_LOCKED"));
    }

    @Test
    void updateChargeLine_makesSalePriceNegative_succeeds_andPersistsNegative() throws Exception {
        // Negative sale price is ALLOWED while editing. With a stored -600.00 inc-GST discount on
        // order 1, dropping charge line 1 unit_price to 1 (32 × 1 = 32) makes calc (360+32)×1.1 =
        // 431.20 → final 431.20-600 = -168.80 → sale_ex round(-168.80/1.1) = -153.45. The PATCH
        // succeeds and the real negative value persists — not clamped, not a DB 500.
        jdbcTemplate.update("UPDATE sales_order SET price_adjustment_inc_gst = ? WHERE order_id = ?",
                new BigDecimal("-600.00"), ORDER_SOFT_FULL);

        mockMvc.perform(patch(chargeLineUrl(ORDER_SOFT_FULL, CHARGE_LINE_1))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unit_price\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Charge line updated."))
                .andExpect(jsonPath("$.data.charge_line.unit_price").value(1.0))
                .andExpect(jsonPath("$.data.charge_line.line_total").value(32.0))
                .andExpect(jsonPath("$.data.order_financial_summary.calculated_total_inc_gst").value(431.2))
                .andExpect(jsonPath("$.data.order_financial_summary.price_adjustment_inc_gst").value(-600.0))
                .andExpect(jsonPath("$.data.order_financial_summary.final_sale_price_inc_gst").value(-168.8))
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(-153.45))
                .andExpect(jsonPath("$.data.order_financial_summary.total_cost").value(432.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(-585.45))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_percent").value(nullValue()))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_warning").value(false));

        assertMoney("1.00", queryChargeLineMoney("unit_price", CHARGE_LINE_1));
        assertMoney("32.00", queryChargeLineMoney("line_total", CHARGE_LINE_1));
        assertMoney("-153.45", queryMoney("sale_price_ex_gst", ORDER_SOFT_FULL));
        assertMoney("-585.45", queryMoney("gp", ORDER_SOFT_FULL));
        Assertions.assertNull(queryMoney("gp_percent", ORDER_SOFT_FULL),
                "gp_percent persists NULL when sale_price_ex_gst <= 0");
    }

    // ================================================================
    // DELETE /charge-lines/{lineId}
    // ================================================================

    @Test
    void deleteChargeLine_removesLine_recalculatesSummary() throws Exception {
        // Order 1: delete charge line 1 → charge_subtotal 0, product 360, calc 396, sale_ex 360,
        // total_cost 176, gp 184, gp% 18400/360 = 51.11. Product line stays.
        mockMvc.perform(delete(chargeLineUrl(ORDER_SOFT_FULL, CHARGE_LINE_1))
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Charge line deleted."))
                .andExpect(jsonPath("$.data.deleted_charge_line_id").value(1))
                .andExpect(jsonPath("$.data.order_financial_summary.product_subtotal").value(360.0))
                .andExpect(jsonPath("$.data.order_financial_summary.charge_subtotal").value(0.0))
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(360.0))
                .andExpect(jsonPath("$.data.order_financial_summary.total_cost").value(176.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(184.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_percent").value(51.11));

        Assertions.assertEquals(0, countChargeLines(ORDER_SOFT_FULL));
        Integer productRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_product_line WHERE order_id = ?", Integer.class, ORDER_SOFT_FULL);
        Assertions.assertEquals(Integer.valueOf(1), productRows);
        assertMoney("360.00", queryMoney("sale_price_ex_gst", ORDER_SOFT_FULL));
        assertMoney("184.00", queryMoney("gp", ORDER_SOFT_FULL));
    }

    @Test
    void deleteChargeLine_lineOnAnotherOrder_returns404() throws Exception {
        mockMvc.perform(delete(chargeLineUrl(ORDER_HARD_EMPTY, CHARGE_LINE_1))
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("LINE_NOT_FOUND"));
    }

    @Test
    void deleteChargeLine_missingLine_returns404() throws Exception {
        mockMvc.perform(delete(chargeLineUrl(ORDER_SOFT_FULL, LINE_DOES_NOT_EXIST))
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("LINE_NOT_FOUND"));
    }

    @Test
    void deleteChargeLine_laidOrder_returns422() throws Exception {
        laidOrder(ORDER_SOFT_FULL);
        mockMvc.perform(delete(chargeLineUrl(ORDER_SOFT_FULL, CHARGE_LINE_1))
                        .session(liamStore1Session()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORDER_LOCKED"));
    }

    @Test
    void deleteChargeLine_makesSalePriceNegative_succeeds_andPersistsNegative() throws Exception {
        // Stored -600.00 inc-GST discount on order 1; deleting charge line 1 drops calc to 360×1.1 =
        // 396.00 → final 396-600 = -204.00 → sale_ex round(-204/1.1) = -185.45. total_cost 176, gp -361.45.
        jdbcTemplate.update("UPDATE sales_order SET price_adjustment_inc_gst = ? WHERE order_id = ?",
                new BigDecimal("-600.00"), ORDER_SOFT_FULL);

        mockMvc.perform(delete(chargeLineUrl(ORDER_SOFT_FULL, CHARGE_LINE_1))
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted_charge_line_id").value(1))
                .andExpect(jsonPath("$.data.order_financial_summary.charge_subtotal").value(0.0))
                .andExpect(jsonPath("$.data.order_financial_summary.calculated_total_inc_gst").value(396.0))
                .andExpect(jsonPath("$.data.order_financial_summary.final_sale_price_inc_gst").value(-204.0))
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(-185.45))
                .andExpect(jsonPath("$.data.order_financial_summary.total_cost").value(176.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(-361.45))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_percent").value(nullValue()));

        Assertions.assertEquals(0, countChargeLines(ORDER_SOFT_FULL));
        assertMoney("-185.45", queryMoney("sale_price_ex_gst", ORDER_SOFT_FULL));
        assertMoney("-361.45", queryMoney("gp", ORDER_SOFT_FULL));
    }

    // ================================================================
    // gp_percent overflow (R4): response returns true value, persisted column is NULL
    // ================================================================

    @Test
    void chargeMutation_gpPercentOverflow_responseHasValue_butPersistsNull() throws Exception {
        // Order 2 (HARD empty) + charge 3 (INST-H, cost 12), qty 1, unit_price 0.01 → sale_ex 0.01,
        // total_cost 12.00, gp -11.99, gp_percent = -119900.00 (outside DECIMAL(5,2)).
        mockMvc.perform(post(chargeLinesUrl(ORDER_HARD_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charge_id\":3,\"quantity\":1,\"unit_price\":0.01}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.order_financial_summary.sale_price_ex_gst").value(0.01))
                .andExpect(jsonPath("$.data.order_financial_summary.total_cost").value(12.0))
                .andExpect(jsonPath("$.data.order_financial_summary.gp").value(-11.99))
                .andExpect(jsonPath("$.data.order_financial_summary.gp_percent").isNumber())
                .andExpect(jsonPath("$.data.order_financial_summary.gp_warning").value(true));

        Assertions.assertNull(queryMoney("gp_percent", ORDER_HARD_EMPTY),
                "gp_percent must persist as NULL when it overflows DECIMAL(5,2)");
        assertMoney("0.01", queryMoney("sale_price_ex_gst", ORDER_HARD_EMPTY));
        assertMoney("12.00", queryMoney("total_cost", ORDER_HARD_EMPTY));
        assertMoney("-11.99", queryMoney("gp", ORDER_HARD_EMPTY));
    }
}
