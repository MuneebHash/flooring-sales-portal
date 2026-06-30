package com.flooring.salesportal.order.quote;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 16C PR1 — {@code GET /quote/workspace} + {@code PUT /quote/draft} (quote money/data core).
 *
 * <p>Self-seeded (Phase 14D go-forward rule): each test INSERTs its own {@code sales_order} (and,
 * where a cost basis is needed, a {@code store_charge} + {@code order_charge_line}) via JdbcTemplate
 * rather than depending on the V4 demo data. Session identity is Liam / business 1 / store 1; the
 * second business + its store/user are foundational rows discovered from the DB. All tests run in
 * the test transaction and roll back. That this whole {@code @SpringBootTest} boots also proves the
 * V16 migration applies cleanly on the test DB.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class QuoteControllerTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";

    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private MockMvc mockMvc;

    private int seq = 70_000;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    // ================================================================
    // Helpers
    // ================================================================

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

    private static String workspaceUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/quote/workspace";
    }

    private static String draftUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/quote/draft";
    }

    private long insertOrder(long businessId, int storeId, long userId, String status) {
        int s = ++seq;
        // order_number must match chk_sales_order_number_format (V6): {code}.{LL#}.{#####}
        String orderNumber = "QUOTET.ZZ9." + String.format("%05d", s % 100_000);
        return jdbcTemplate.queryForObject(
                "INSERT INTO sales_order "
                        + "(business_id, store_id, user_id, order_sequence_number, order_number, "
                        + " flooring_type, order_status, week_number, week_year) "
                        + "VALUES (?, ?, ?, ?, ?, 'SOFT'::flooring_type, ?::order_status, 1, 2026) "
                        + "RETURNING order_id",
                Long.class,
                businessId, storeId, userId, s, orderNumber, status);
    }

    private long leadOrderInSession() {
        return insertOrder(BUSINESS_AUSSIE, STORE_SYD_CBD, USER_LIAM, "LEAD");
    }

    private long laidOrderInSession() {
        return insertOrder(BUSINESS_AUSSIE, STORE_SYD_CBD, USER_LIAM, "LAID");
    }

    /** Give an order a cost basis by seeding one store_charge + order_charge_line. */
    private void seedChargeLine(long orderId, int storeId, String lineTotal, String lineCost) {
        int s = ++seq;
        String code = "QC" + (s % 100_000);
        long chargeId = jdbcTemplate.queryForObject(
                "INSERT INTO store_charge (store_id, flooring_type, code, name, price, cost) "
                        + "VALUES (?, 'SOFT'::flooring_type, ?, 'Quote test charge', ?, ?) RETURNING charge_id",
                Long.class, storeId, code, new BigDecimal(lineTotal), new BigDecimal(lineCost));
        jdbcTemplate.update(
                "INSERT INTO order_charge_line "
                        + "(order_id, charge_id, charge_code_snapshot, charge_name_snapshot, "
                        + " price_snapshot, cost_snapshot, quantity, unit_price, line_total, line_cost) "
                        + "VALUES (?, ?, ?, 'Quote test charge', ?, ?, 1, ?, ?, ?)",
                orderId, chargeId, code,
                new BigDecimal(lineTotal), new BigDecimal(lineCost),
                new BigDecimal(lineTotal), new BigDecimal(lineTotal), new BigDecimal(lineCost));
    }

    private int storeInBusiness(long businessId, Integer excludeStore) {
        String sql = "SELECT store_id FROM store WHERE business_id = ? AND is_active = TRUE"
                + (excludeStore == null ? "" : " AND store_id <> " + excludeStore)
                + " ORDER BY store_id LIMIT 1";
        return jdbcTemplate.queryForObject(sql, Integer.class, businessId);
    }

    private long userInBusiness(long businessId) {
        return jdbcTemplate.queryForObject(
                "SELECT user_id FROM app_user WHERE business_id = ? AND is_active = TRUE ORDER BY user_id LIMIT 1",
                Long.class, businessId);
    }

    private int draftRowCount(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_draft WHERE order_id = ?", Integer.class, orderId);
    }

    private int lineCount(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_draft_line l JOIN quote_draft d ON l.quote_draft_id = d.quote_draft_id "
                        + "WHERE d.order_id = ?", Integer.class, orderId);
    }

    private BigDecimal draftDecimal(long orderId, String col) {
        return jdbcTemplate.queryForObject(
                "SELECT " + col + " FROM quote_draft WHERE order_id = ?", BigDecimal.class, orderId);
    }

    private BigDecimal sumLineTotals(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(line_total_ex_gst), 0) FROM quote_draft_line l "
                        + "JOIN quote_draft d ON l.quote_draft_id = d.quote_draft_id WHERE d.order_id = ?",
                BigDecimal.class, orderId);
    }

    private BigDecimal orderDecimal(long orderId, String col) {
        return jdbcTemplate.queryForObject(
                "SELECT " + col + " FROM sales_order WHERE order_id = ?", BigDecimal.class, orderId);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        Assertions.assertTrue(actual != null && new BigDecimal(expected).compareTo(actual) == 0,
                "expected " + expected + " but was " + actual);
    }

    private static String previewUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/quote/preview-pdf";
    }

    /** Insert a draft + one ITEM line directly (no save-path guards) so preview can be exercised in isolation. */
    private long seedSimpleDraft(long orderId, String totalEx, String totalInc) {
        long draftId = jdbcTemplate.queryForObject(
                "INSERT INTO quote_draft (order_id, itemised, quote_total_ex_gst, quote_total_inc_gst) "
                        + "VALUES (?, TRUE, ?, ?) RETURNING quote_draft_id",
                Long.class, orderId, new BigDecimal(totalEx), new BigDecimal(totalInc));
        jdbcTemplate.update(
                "INSERT INTO quote_draft_line "
                        + "(quote_draft_id, line_type, description, quantity, unit_price_ex_gst, line_total_ex_gst, sort_order) "
                        + "VALUES (?, 'ITEM', 'Carpet', 1, ?, ?, 0)",
                draftId, new BigDecimal(totalEx), new BigDecimal(totalEx));
        return draftId;
    }

    private String orderNumber(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT order_number FROM sales_order WHERE order_id = ?", String.class, orderId);
    }

    private int countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int quoteVersionCountForOrder(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quote_version WHERE order_id = ?", Integer.class, orderId);
    }

    private java.sql.Timestamp draftUpdatedAt(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at FROM quote_draft WHERE order_id = ?", java.sql.Timestamp.class, orderId);
    }

    // ================================================================
    // GET workspace
    // ================================================================

    @Test
    void workspaceGet_noDraft_returnsNullDraftIssuedAccepted() throws Exception {
        long orderId = leadOrderInSession();

        mockMvc.perform(get(workspaceUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft").value(nullValue()))
                .andExpect(jsonPath("$.data.current_issued").value(nullValue()))
                .andExpect(jsonPath("$.data.accepted").value(nullValue()));
    }

    @Test
    void workspaceGet_existingDraft_returnsDraftWithLines() throws Exception {
        long orderId = leadOrderInSession();

        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0}
                                ]}"""))
                .andExpect(status().isOk());

        entityManager.clear();
        mockMvc.perform(get(workspaceUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft.itemised").value(true))
                .andExpect(jsonPath("$.data.draft.below_cost").value(false))
                .andExpect(jsonPath("$.data.draft.lines", hasSize(1)))
                .andExpect(jsonPath("$.data.draft.lines[0].line_type").value("ITEM"))
                .andExpect(jsonPath("$.data.draft.lines[0].description").value("Carpet"))
                .andExpect(jsonPath("$.data.draft.lines[0].quote_draft_line_id").isNumber())
                .andExpect(jsonPath("$.data.current_issued").value(nullValue()))
                .andExpect(jsonPath("$.data.accepted").value(nullValue()));
    }

    @Test
    void workspaceGet_laidOrder_returns200() throws Exception {
        long orderId = laidOrderInSession();

        mockMvc.perform(get(workspaceUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft").value(nullValue()));
    }

    @Test
    void workspaceGet_crossStore_returns404() throws Exception {
        int otherStore = storeInBusiness(BUSINESS_AUSSIE, STORE_SYD_CBD);
        long orderId = insertOrder(BUSINESS_AUSSIE, otherStore, USER_LIAM, "LEAD");

        mockMvc.perform(get(workspaceUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void workspaceGet_crossBusiness_returns404() throws Exception {
        long otherBusiness = 2L;
        int otherStore = storeInBusiness(otherBusiness, null);
        long otherUser = userInBusiness(otherBusiness);
        long orderId = insertOrder(otherBusiness, otherStore, otherUser, "LEAD");

        mockMvc.perform(get(workspaceUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void workspaceGet_missingOrder_returns404() throws Exception {
        mockMvc.perform(get(workspaceUrl(9_999_999L)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void workspaceGet_noSession_returns401() throws Exception {
        long orderId = leadOrderInSession();
        mockMvc.perform(get(workspaceUrl(orderId)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void workspaceGet_noStoreSelected_returns403() throws Exception {
        long orderId = leadOrderInSession();
        mockMvc.perform(get(workspaceUrl(orderId)).session(liamSessionNoStore()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // ================================================================
    // PUT draft — happy path
    // ================================================================

    @Test
    void putDraft_createsDraft_persistsTotalsAndLines() throws Exception {
        long orderId = leadOrderInSession();

        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
                                  {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
                                ]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Quote draft saved."))
                .andExpect(jsonPath("$.data.itemised").value(true))
                .andExpect(jsonPath("$.data.below_cost").value(false))
                .andExpect(jsonPath("$.data.lines", hasSize(2)));

        Assertions.assertEquals(1, draftRowCount(orderId));
        Assertions.assertEquals(2, lineCount(orderId));
        assertMoney("250.00", draftDecimal(orderId, "quote_total_ex_gst"));
        assertMoney("275.00", draftDecimal(orderId, "quote_total_inc_gst"));
        // Invariant: stored total ex == sum of persisted line totals.
        Assertions.assertEquals(0, draftDecimal(orderId, "quote_total_ex_gst").compareTo(sumLineTotals(orderId)));
    }

    @Test
    void putDraft_recomputesItemLineTotalServerSide_ignoresClientTotal() throws Exception {
        long orderId = leadOrderInSession();

        // Client sends a wrong line_total (9999); server recomputes 2 * 100 = 200.
        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":9999,"sort_order":0}
                                ]}"""))
                .andExpect(status().isOk());

        assertMoney("200.00", draftDecimal(orderId, "quote_total_ex_gst"));
        assertMoney("200.00", sumLineTotals(orderId));
    }

    @Test
    void putDraft_updatesOrderSalePriceOverride() throws Exception {
        long orderId = leadOrderInSession(); // no product/charge lines: calculated total = 0

        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":10,"unit_price_ex_gst":100,"line_total_ex_gst":1000,"sort_order":0}
                                ]}"""))
                .andExpect(status().isOk());

        // override: price_adjustment = quote_inc (1100) - calculated (0); sale ex = 1100/1.10 = 1000.
        assertMoney("1100.00", orderDecimal(orderId, "price_adjustment_inc_gst"));
        assertMoney("1000.00", orderDecimal(orderId, "sale_price_ex_gst"));
    }

    @Test
    void putDraft_secondPut_fullReplacesLines_noDuplicateDraft() throws Exception {
        long orderId = leadOrderInSession();

        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
                                  {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
                                ]}"""))
                .andExpect(status().isOk());

        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Tiles","quantity":1,"unit_price_ex_gst":300,"line_total_ex_gst":300,"sort_order":0}
                                ]}"""))
                .andExpect(status().isOk());

        Assertions.assertEquals(1, draftRowCount(orderId), "second PUT must not create a second draft");
        Assertions.assertEquals(1, lineCount(orderId), "lines fully replaced");
        assertMoney("300.00", draftDecimal(orderId, "quote_total_ex_gst"));
    }

    // ================================================================
    // PUT draft — money rules
    // ================================================================

    @Test
    void putDraft_finalBelowLineSum_insertsNegativeAdjustment() throws Exception {
        long orderId = leadOrderInSession();

        // lines ex 250 (inc 275). final 220 inc => target_ex 200 => -50 adjustment.
        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "final_total_inc_gst": 220, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
                                  {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
                                ]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines", hasSize(3)));

        Assertions.assertEquals(3, lineCount(orderId));
        assertMoney("200.00", draftDecimal(orderId, "quote_total_ex_gst"));
        assertMoney("220.00", draftDecimal(orderId, "quote_total_inc_gst"));
        Assertions.assertEquals(0, draftDecimal(orderId, "quote_total_ex_gst").compareTo(sumLineTotals(orderId)));
        // The adjustment is a visible negative line, appended at sort_order = max(existing) + 1 (= 2).
        BigDecimal adjTotal = jdbcTemplate.queryForObject(
                "SELECT line_total_ex_gst FROM quote_draft_line l JOIN quote_draft d ON l.quote_draft_id = d.quote_draft_id "
                        + "WHERE d.order_id = ? AND l.line_type = 'ADJUSTMENT'", BigDecimal.class, orderId);
        assertMoney("-50.00", adjTotal);
        Integer adjSortOrder = jdbcTemplate.queryForObject(
                "SELECT sort_order FROM quote_draft_line l JOIN quote_draft d ON l.quote_draft_id = d.quote_draft_id "
                        + "WHERE d.order_id = ? AND l.line_type = 'ADJUSTMENT'", Integer.class, orderId);
        Assertions.assertEquals(2, adjSortOrder, "adjustment sort_order is max(0,1) + 1");
    }

    @Test
    void putDraft_finalAboveLineSum_returns422_TotalExceedsLines_notPersisted() throws Exception {
        long orderId = leadOrderInSession();

        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "final_total_inc_gst": 400, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0}
                                ]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("QUOTE_TOTAL_EXCEEDS_LINES"));

        Assertions.assertEquals(0, draftRowCount(orderId), "rejected save must not persist");
    }

    @Test
    void putDraft_noopResave_doesNotTripExceeds() throws Exception {
        long orderId = leadOrderInSession();

        // Save with a reduction (creates an adjustment), then resave the RETURNED stored lines plus
        // the displayed inc total. The 1c tolerance must keep this no-op from tripping EXCEEDS.
        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "final_total_inc_gst": 220, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
                                  {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1}
                                ]}"""))
                .andExpect(status().isOk());

        // Resave: stored lines now sum to 200 ex (incl. the -50 adjustment), final 220 inc again.
        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "final_total_inc_gst": 220, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0},
                                  {"line_type":"ITEM","description":"Underlay","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":1},
                                  {"line_type":"ADJUSTMENT","description":"Adjustment","line_total_ex_gst":-50,"sort_order":2}
                                ]}"""))
                .andExpect(status().isOk());

        // line sum 200 ex, final 220 inc => target_ex 200 => delta 0 => no new adjustment.
        assertMoney("200.00", draftDecimal(orderId, "quote_total_ex_gst"));
        Assertions.assertEquals(3, lineCount(orderId), "no extra adjustment added on resave");
    }

    @Test
    void putDraft_belowCost_returns422_BelowCost_notPersisted_overrideUnchanged() throws Exception {
        long orderId = leadOrderInSession();
        seedChargeLine(orderId, STORE_SYD_CBD, "100.00", "80.00"); // order cost ex = 80
        jdbcTemplate.update("UPDATE sales_order SET price_adjustment_inc_gst = 123.45 WHERE order_id = ?", orderId);

        // quote ex 50 < cost 80 => below cost.
        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":0}
                                ]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("QUOTE_BELOW_COST"));

        Assertions.assertEquals(0, draftRowCount(orderId), "below-cost save must not persist a draft");
        assertMoney("123.45", orderDecimal(orderId, "price_adjustment_inc_gst")); // override untouched
    }

    @Test
    void putDraft_atCost_gpZero_allowed() throws Exception {
        long orderId = leadOrderInSession();
        seedChargeLine(orderId, STORE_SYD_CBD, "60.00", "50.00"); // order cost ex = 50

        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":1,"unit_price_ex_gst":50,"line_total_ex_gst":50,"sort_order":0}
                                ]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.below_cost").value(false));

        assertMoney("50.00", draftDecimal(orderId, "quote_total_ex_gst"));
    }

    @Test
    void putDraft_zeroTotal_savesDraft_skipsOverridePush() throws Exception {
        long orderId = leadOrderInSession(); // no cost lines => zero quote is at-cost (allowed)
        jdbcTemplate.update("UPDATE sales_order SET price_adjustment_inc_gst = 77.70 WHERE order_id = ?", orderId);

        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": []}"""))
                .andExpect(status().isOk());

        Assertions.assertEquals(1, draftRowCount(orderId));
        assertMoney("0.00", draftDecimal(orderId, "quote_total_inc_gst"));
        // Zero-total quote must NOT push into the override — existing override untouched.
        assertMoney("77.70", orderDecimal(orderId, "price_adjustment_inc_gst"));
    }

    @Test
    void putDraft_nonItemisedWithFinal_createsSingleSyntheticLine() throws Exception {
        long orderId = leadOrderInSession();

        // itemised=false + final 550 inc => single synthetic ITEM "Quoted works" ex 500.
        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": false, "final_total_inc_gst": 550, "lines": []}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.itemised").value(false))
                .andExpect(jsonPath("$.data.lines", hasSize(1)))
                .andExpect(jsonPath("$.data.lines[0].description").value("Quoted works"));

        Assertions.assertEquals(1, lineCount(orderId));
        assertMoney("500.00", draftDecimal(orderId, "quote_total_ex_gst"));
        assertMoney("550.00", draftDecimal(orderId, "quote_total_inc_gst"));
    }

    // ================================================================
    // PUT draft — gates / validation / security
    // ================================================================

    @Test
    void putDraft_laidOrder_returns422_OrderLocked_notPersisted() throws Exception {
        long orderId = laidOrderInSession();

        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":1,"unit_price_ex_gst":100,"line_total_ex_gst":100,"sort_order":0}
                                ]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORDER_LOCKED"));

        Assertions.assertEquals(0, draftRowCount(orderId));
    }

    @Test
    void putDraft_laidOrder_malformedBody_returns422_OrderLocked_notMalformedJson() throws Exception {
        long orderId = laidOrderInSession();

        // LAID gate runs BEFORE body parse: malformed JSON on a LAID order is still 422 ORDER_LOCKED.
        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{ not json"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORDER_LOCKED"));
    }

    @Test
    void putDraft_crossStore_returns404_writesNothing() throws Exception {
        int otherStore = storeInBusiness(BUSINESS_AUSSIE, STORE_SYD_CBD);
        long orderId = insertOrder(BUSINESS_AUSSIE, otherStore, USER_LIAM, "LEAD");

        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":1,"unit_price_ex_gst":100,"line_total_ex_gst":100,"sort_order":0}
                                ]}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));

        Assertions.assertEquals(0, draftRowCount(orderId));
    }

    @Test
    void putDraft_malformedJson_onValidOrder_returns400_MalformedJson() throws Exception {
        long orderId = leadOrderInSession();

        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_JSON"));
    }

    @Test
    void putDraft_invalidOrderId_returns400() throws Exception {
        mockMvc.perform(put(draftUrl("abc")).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"itemised": true, "lines": []}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    @Test
    void putDraft_unknownTopLevelField_returns400() throws Exception {
        long orderId = leadOrderInSession();

        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [], "sneaky": 1}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        Assertions.assertEquals(0, draftRowCount(orderId));
    }

    @Test
    void putDraft_costFieldOnLine_returns400() throws Exception {
        long orderId = leadOrderInSession();

        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":1,"unit_price_ex_gst":100,"line_total_ex_gst":100,"line_cost":40,"sort_order":0}
                                ]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        Assertions.assertEquals(0, draftRowCount(orderId));
    }

    @Test
    void putDraft_adjustmentWithQuantity_returns400() throws Exception {
        long orderId = leadOrderInSession();

        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ADJUSTMENT","description":"Discount","quantity":1,"line_total_ex_gst":-10,"sort_order":0}
                                ]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        Assertions.assertEquals(0, draftRowCount(orderId));
    }

    @Test
    void putDraft_invalidLineType_returns400() throws Exception {
        long orderId = leadOrderInSession();

        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"item","description":"Carpet","quantity":1,"unit_price_ex_gst":100,"line_total_ex_gst":100,"sort_order":0}
                                ]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void putDraft_noSession_returns401() throws Exception {
        long orderId = leadOrderInSession();

        mockMvc.perform(put(draftUrl(orderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": []}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ================================================================
    // PUT draft — review-fix coverage (line_total required, non-itemised strictness, response ids)
    // ================================================================

    @Test
    void putDraft_response_includesPersistedLineIds() throws Exception {
        long orderId = leadOrderInSession();

        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":0}
                                ]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].quote_draft_line_id").isNumber());
    }

    @Test
    void putDraft_itemMissingLineTotal_returns400() throws Exception {
        long orderId = leadOrderInSession();

        // OpenAPI requires line_total_ex_gst on every line input, including ITEM (value is recomputed).
        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"sort_order":0}
                                ]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("lines[0].line_total_ex_gst"));

        Assertions.assertEquals(0, draftRowCount(orderId));
    }

    @Test
    void putDraft_nonItemisedMissingFinal_returns400() throws Exception {
        long orderId = leadOrderInSession();

        // itemised=false requires final_total_inc_gst (the single quoted amount).
        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": false, "lines": []}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("final_total_inc_gst"));

        Assertions.assertEquals(0, draftRowCount(orderId));
    }

    @Test
    void putDraft_nonItemisedWithLines_returns400() throws Exception {
        long orderId = leadOrderInSession();

        // itemised=false must have an EMPTY lines array — the backend generates the synthetic line.
        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": false, "final_total_inc_gst": 550, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":1,"unit_price_ex_gst":100,"line_total_ex_gst":100,"sort_order":0}
                                ]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        Assertions.assertEquals(0, draftRowCount(orderId));
    }

    // ================================================================
    // PUT draft — sort_order bound (Codex fix: adjustment sort_order overflow)
    // ================================================================

    @Test
    void putDraft_sortOrderOverflow_withForcedAdjustment_returns400_notPersisted() throws Exception {
        long orderId = leadOrderInSession();
        jdbcTemplate.update("UPDATE sales_order SET price_adjustment_inc_gst = 88.80 WHERE order_id = ?", orderId);

        // sort_order = Integer.MAX_VALUE would make the auto-adjustment's max+1 overflow; the cap
        // rejects it at validation (before compute), so the adjustment path is never reached.
        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "final_total_inc_gst": 100, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":2,"unit_price_ex_gst":100,"line_total_ex_gst":200,"sort_order":2147483647}
                                ]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("lines[0].sort_order"));

        Assertions.assertEquals(0, draftRowCount(orderId), "rejected save must not persist a draft");
        Assertions.assertEquals(0, lineCount(orderId));
        assertMoney("88.80", orderDecimal(orderId, "price_adjustment_inc_gst")); // override untouched
    }

    @Test
    void putDraft_sortOrderAtMax_noAdjustment_saves() throws Exception {
        long orderId = leadOrderInSession();

        // sort_order at the cap (1000000) with no direct total => no adjustment => save succeeds.
        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":1,"unit_price_ex_gst":100,"line_total_ex_gst":100,"sort_order":1000000}
                                ]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines", hasSize(1)));

        Assertions.assertEquals(1, draftRowCount(orderId));
        Integer sortOrder = jdbcTemplate.queryForObject(
                "SELECT sort_order FROM quote_draft_line l JOIN quote_draft d ON l.quote_draft_id = d.quote_draft_id "
                        + "WHERE d.order_id = ?", Integer.class, orderId);
        Assertions.assertEquals(1_000_000, sortOrder);
    }

    @Test
    void putDraft_directReductionWithLineAtMaxSortOrder_savesAndResavesUnchanged() throws Exception {
        long orderId = leadOrderInSession();

        // ITEM at the cap (1,000,000) + a direct reduction forces an ADJUSTMENT. Before the fix the
        // adjustment got sort_order 1,000,001 (out of range) and the returned draft could not be resaved.
        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "final_total_inc_gst": 55, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":1,"unit_price_ex_gst":100,"line_total_ex_gst":100,"sort_order":1000000}
                                ]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines", hasSize(2)));

        Integer adjSortOrder = jdbcTemplate.queryForObject(
                "SELECT sort_order FROM quote_draft_line l JOIN quote_draft d ON l.quote_draft_id = d.quote_draft_id "
                        + "WHERE d.order_id = ? AND l.line_type = 'ADJUSTMENT'", Integer.class, orderId);
        Assertions.assertNotNull(adjSortOrder);
        Assertions.assertTrue(adjSortOrder >= 0 && adjSortOrder <= 1_000_000,
                "generated adjustment sort_order must stay in [0, 1000000], was " + adjSortOrder);
        Assertions.assertEquals(0, adjSortOrder, "lowest unused slot (only 1,000,000 used) is 0");

        // The returned draft (ITEM@1,000,000 + ADJUSTMENT@0) must resave unchanged -> 200, not 400.
        mockMvc.perform(put(draftUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itemised": true, "lines": [
                                  {"line_type":"ITEM","description":"Carpet","quantity":1,"unit_price_ex_gst":100,"line_total_ex_gst":100,"sort_order":1000000},
                                  {"line_type":"ADJUSTMENT","description":"Adjustment","line_total_ex_gst":-50,"sort_order":0}
                                ]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines", hasSize(2)));
    }

    // ================================================================
    // POST preview-pdf (Phase 16C PR2) — security / content / no-draft / LAID / below-cost / body
    // ================================================================

    @Test
    void previewPdf_validDraft_returns200Pdf_inlineQuoteFilename() throws Exception {
        long orderId = leadOrderInSession();
        seedSimpleDraft(orderId, "100.00", "110.00");
        entityManager.clear();

        byte[] pdf = mockMvc.perform(post(previewUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", containsString("inline")))
                .andExpect(header().string("Content-Disposition", containsString("quote-preview-")))
                .andExpect(header().string("Content-Disposition", containsString(orderNumber(orderId))))
                .andExpect(header().string("Content-Disposition", containsString(".pdf")))
                .andReturn().getResponse().getContentAsByteArray();

        Assertions.assertTrue(pdf.length > 500, "expected a real PDF body, was " + pdf.length + " bytes");
        Assertions.assertEquals("%PDF-", new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII),
                "preview body must begin with the PDF magic header");
    }

    @Test
    void previewPdf_noDraft_returns404QuoteNotFound() throws Exception {
        long orderId = leadOrderInSession(); // no quote draft seeded
        mockMvc.perform(post(previewUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUOTE_NOT_FOUND"));
    }

    @Test
    void previewPdf_laidOrderWithDraft_returns200() throws Exception {
        long orderId = laidOrderInSession();
        seedSimpleDraft(orderId, "100.00", "110.00");
        entityManager.clear();

        mockMvc.perform(post(previewUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }

    @Test
    void previewPdf_belowCostDraft_returns200() throws Exception {
        long orderId = leadOrderInSession();
        seedChargeLine(orderId, STORE_SYD_CBD, "10.00", "5000.00"); // high cost basis
        seedSimpleDraft(orderId, "100.00", "110.00");               // quote far below cost
        entityManager.clear();

        mockMvc.perform(post(previewUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }

    @Test
    void previewPdf_nonItemisedDraft_returns200() throws Exception {
        long orderId = leadOrderInSession();
        // A non-itemised draft is stored as one synthetic ITEM line; preview renders the single-amount form.
        long draftId = jdbcTemplate.queryForObject(
                "INSERT INTO quote_draft (order_id, itemised, quote_total_ex_gst, quote_total_inc_gst) "
                        + "VALUES (?, FALSE, 500.00, 550.00) RETURNING quote_draft_id",
                Long.class, orderId);
        jdbcTemplate.update(
                "INSERT INTO quote_draft_line "
                        + "(quote_draft_id, line_type, description, quantity, unit_price_ex_gst, line_total_ex_gst, sort_order) "
                        + "VALUES (?, 'ITEM', 'Quoted works', 1, 500.00, 500.00, 0)",
                draftId);
        entityManager.clear();

        mockMvc.perform(post(previewUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }

    @Test
    void previewPdf_crossStore_returns404() throws Exception {
        int otherStore = storeInBusiness(BUSINESS_AUSSIE, STORE_SYD_CBD);
        long orderId = insertOrder(BUSINESS_AUSSIE, otherStore, USER_LIAM, "LEAD");
        seedSimpleDraft(orderId, "100.00", "110.00");

        mockMvc.perform(post(previewUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void previewPdf_crossBusiness_returns404() throws Exception {
        long otherBusiness = 2L;
        int otherStore = storeInBusiness(otherBusiness, null);
        long otherUser = userInBusiness(otherBusiness);
        long orderId = insertOrder(otherBusiness, otherStore, otherUser, "LEAD");
        seedSimpleDraft(orderId, "100.00", "110.00");

        mockMvc.perform(post(previewUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void previewPdf_missingOrder_returns404() throws Exception {
        mockMvc.perform(post(previewUrl(9_999_999L)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void previewPdf_noSession_returns401() throws Exception {
        long orderId = leadOrderInSession();
        mockMvc.perform(post(previewUrl(orderId)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void previewPdf_noStoreSelected_returns403() throws Exception {
        long orderId = leadOrderInSession();
        mockMvc.perform(post(previewUrl(orderId)).session(liamSessionNoStore()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void previewPdf_invalidOrderId_returns400() throws Exception {
        mockMvc.perform(post(previewUrl("abc")).session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    @Test
    void previewPdf_emptyObjectBody_allowed_returns200() throws Exception {
        long orderId = leadOrderInSession();
        seedSimpleDraft(orderId, "100.00", "110.00");
        entityManager.clear();

        mockMvc.perform(post(previewUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }

    @Test
    void previewPdf_blankBody_allowed_returns200() throws Exception {
        long orderId = leadOrderInSession();
        seedSimpleDraft(orderId, "100.00", "110.00");
        entityManager.clear();

        mockMvc.perform(post(previewUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("   "))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }

    @Test
    void previewPdf_malformedBody_afterScopedLookup_returns400MalformedJson() throws Exception {
        long orderId = leadOrderInSession(); // in scope, no draft: body validated BEFORE the no-draft 404
        mockMvc.perform(post(previewUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_JSON"));
    }

    @Test
    void previewPdf_malformedBody_crossStore_stillReturns404() throws Exception {
        // Scoped lookup runs BEFORE body validation: a cross-store order with a malformed body is 404,
        // never 400 — the body is never parsed for an order the caller cannot see.
        int otherStore = storeInBusiness(BUSINESS_AUSSIE, STORE_SYD_CBD);
        long orderId = insertOrder(BUSINESS_AUSSIE, otherStore, USER_LIAM, "LEAD");
        mockMvc.perform(post(previewUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void previewPdf_bodyWithFields_returns400Validation() throws Exception {
        long orderId = leadOrderInSession();
        seedSimpleDraft(orderId, "100.00", "110.00");
        mockMvc.perform(post(previewUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"foo\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("foo"));
    }

    @Test
    void previewPdf_jsonNullBody_returns400Validation() throws Exception {
        long orderId = leadOrderInSession();
        seedSimpleDraft(orderId, "100.00", "110.00");
        // A JSON literal `null` is a non-object body and must be rejected (not treated as "no body").
        mockMvc.perform(post(previewUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void previewPdf_isReadOnly_writesNothing() throws Exception {
        long orderId = leadOrderInSession();
        seedSimpleDraft(orderId, "100.00", "110.00");
        jdbcTemplate.update("UPDATE sales_order SET price_adjustment_inc_gst = 77.70 WHERE order_id = ?", orderId);
        entityManager.clear();

        int versionsBefore = quoteVersionCountForOrder(orderId);
        int tokensBefore = countRows("quote_token");
        int storedBefore = countRows("stored_file");
        int draftRowsBefore = draftRowCount(orderId);
        int lineRowsBefore = lineCount(orderId);
        BigDecimal exBefore = draftDecimal(orderId, "quote_total_ex_gst");
        java.sql.Timestamp updatedBefore = draftUpdatedAt(orderId);

        mockMvc.perform(post(previewUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isOk());

        Assertions.assertEquals(0, quoteVersionCountForOrder(orderId), "preview must not create a quote_version");
        Assertions.assertEquals(versionsBefore, quoteVersionCountForOrder(orderId), "quote_version count unchanged");
        Assertions.assertEquals(tokensBefore, countRows("quote_token"), "quote_token count unchanged");
        Assertions.assertEquals(storedBefore, countRows("stored_file"), "stored_file count unchanged");
        Assertions.assertEquals(draftRowsBefore, draftRowCount(orderId), "quote_draft row count unchanged");
        Assertions.assertEquals(lineRowsBefore, lineCount(orderId), "quote_draft_line count unchanged");
        assertMoney(exBefore.toPlainString(), draftDecimal(orderId, "quote_total_ex_gst"));
        Assertions.assertEquals(updatedBefore, draftUpdatedAt(orderId), "quote_draft updated_at unchanged");
        assertMoney("77.70", orderDecimal(orderId, "price_adjustment_inc_gst")); // sale-price override NOT touched
    }
}
