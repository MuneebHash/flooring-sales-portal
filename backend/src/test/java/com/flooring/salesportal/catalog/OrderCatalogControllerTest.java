package com.flooring.salesportal.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the Chunk 3 catalog search endpoints (D.1 / D.2):
 * {@code GET /orders/{orderId}/available-products} and {@code GET /orders/{orderId}/available-charges}.
 *
 * <p>Mirrors {@code DashboardOrderControllerTest}: {@code @SpringBootTest @Transactional}, MockMvc,
 * and {@code MockHttpSession} carrying the seeded Liam / business 1 / store 1 identity. All data
 * tweaks go through {@code JdbcTemplate} and roll back with the test transaction, so the
 * deterministic V4/V8 seed is preserved.
 *
 * <p>Seeded store-1 catalog (V4 + V8):
 * <ul>
 *   <li>SOFT products: 2 LOP-001 (sqm_per_lm 4.00), 1 PLU-001 (sqm_per_lm 3.66)</li>
 *   <li>HARD products: 4 HVP-001, 3 SGT-001</li>
 *   <li>SOFT charges: 1 INST-S, 2 UNDR-S</li>
 *   <li>HARD charges: 3 INST-H, 4 UNDR-H</li>
 * </ul>
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class OrderCatalogControllerTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";
    private static final String SLUG_PREMIER = "premier-flooring-co";

    // Seeded session identity: Liam Carter (user 1) in business 1 / store 1 (SYD-CBD).
    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    // Seed snapshot (business 1 / store 1 unless noted):
    private static final long ORDER_SOFT = 1L;            // SOFT, ACCEPTED, store 1
    private static final long ORDER_HARD = 2L;            // HARD, LEAD, store 1
    private static final long ORDER_OTHER_STORE = 5L;     // business 1 / store 2 (cross-store)
    private static final long ORDER_OTHER_BUSINESS = 9L;  // business 2 / store 3 (cross-business)
    private static final long ORDER_DOES_NOT_EXIST = 99_999L;

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

    private static String productsUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/available-products";
    }

    private static String chargesUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/available-charges";
    }

    // ================================================================
    // Standard-protected gating (RequestContextGuard)
    // ================================================================

    @Test
    void products_noSession_returns401() throws Exception {
        mockMvc.perform(get(productsUrl(ORDER_SOFT)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void products_sessionWithoutStore_returns403() throws Exception {
        mockMvc.perform(get(productsUrl(ORDER_SOFT)).session(liamSessionNoStore()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void products_crossBusinessSlug_returns404() throws Exception {
        // Liam belongs to business 1 (Aussie). Hit business 2 (Premier) slug.
        mockMvc.perform(get("/api/v1/" + SLUG_PREMIER + "/orders/" + ORDER_SOFT + "/available-products")
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void products_unknownSlug_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/nonexistent-slug/orders/" + ORDER_SOFT + "/available-products")
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void products_userLostStoreAccess_returns403() throws Exception {
        jdbcTemplate.update("DELETE FROM user_store_access WHERE user_id = ? AND store_id = ?",
                USER_LIAM, STORE_SYD_CBD);

        mockMvc.perform(get(productsUrl(ORDER_SOFT)).session(liamStore1Session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void charges_noSession_returns401() throws Exception {
        mockMvc.perform(get(chargesUrl(ORDER_SOFT)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ================================================================
    // available-products — scoping by store + flooring type + active
    // ================================================================

    @Test
    void products_softOrder_returnsOnlyActiveSoftStoreProducts_codeAsc() throws Exception {
        mockMvc.perform(get(productsUrl(ORDER_SOFT)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].product_id", containsInAnyOrder(2, 1)))
                // default ordering is code ascending: LOP-001 before PLU-001
                .andExpect(jsonPath("$.data[0].code").value("LOP-001"))
                .andExpect(jsonPath("$.data[1].code").value("PLU-001"))
                .andExpect(jsonPath("$.data[0].flooring_type").value("SOFT"))
                .andExpect(jsonPath("$.data[1].flooring_type").value("SOFT"))
                .andExpect(jsonPath("$.data[1].name").value("Plush Carpet Premium"))
                .andExpect(jsonPath("$.data[1].pricing_unit").value("LM"))
                .andExpect(jsonPath("$.data[1].price").value(45.0));
    }

    @Test
    void products_hardOrder_returnsOnlyActiveHardStoreProducts_codeAsc() throws Exception {
        mockMvc.perform(get(productsUrl(ORDER_HARD)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].product_id", containsInAnyOrder(4, 3)))
                .andExpect(jsonPath("$.data[0].code").value("HVP-001"))
                .andExpect(jsonPath("$.data[1].code").value("SGT-001"))
                .andExpect(jsonPath("$.data[0].flooring_type").value("HARD"))
                .andExpect(jsonPath("$.data[1].flooring_type").value("HARD"));
    }

    @Test
    void products_inactiveProductExcluded() throws Exception {
        jdbcTemplate.update("UPDATE store_product SET is_active = FALSE WHERE product_id = 1");

        mockMvc.perform(get(productsUrl(ORDER_SOFT)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].product_id").value(2))
                .andExpect(jsonPath("$.data[0].code").value("LOP-001"));
    }

    @Test
    void products_includeSqmPerLm_perProductFactor() throws Exception {
        // LOP-001 was re-seeded to a 4 m roll (sqm_per_lm 4.00) by V8; PLU-001 stays 3.66.
        mockMvc.perform(get(productsUrl(ORDER_SOFT)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("LOP-001"))
                .andExpect(jsonPath("$.data[0].sqm_per_lm").value(4.0))
                .andExpect(jsonPath("$.data[1].code").value("PLU-001"))
                .andExpect(jsonPath("$.data[1].sqm_per_lm").value(3.66));
    }

    @Test
    void products_includeStockFields() throws Exception {
        mockMvc.perform(get(productsUrl(ORDER_SOFT)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].code").value("PLU-001"))
                .andExpect(jsonPath("$.data[1].stock_quantity").value(320.0))
                .andExpect(jsonPath("$.data[1].stock_unit").value("LM"));
    }

    @Test
    void products_nullStockFields_serializedAsNull() throws Exception {
        // chk_store_product_stock_pair requires both null together.
        jdbcTemplate.update(
                "UPDATE store_product SET stock_quantity = NULL, stock_unit = NULL WHERE product_id = 1");

        mockMvc.perform(get(productsUrl(ORDER_SOFT)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].code").value("PLU-001"))
                .andExpect(jsonPath("$.data[1].stock_quantity").value(nullValue()))
                .andExpect(jsonPath("$.data[1].stock_unit").value(nullValue()));
    }

    @Test
    void products_doNotReturnCost() throws Exception {
        mockMvc.perform(get(productsUrl(ORDER_SOFT)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].cost").doesNotExist())
                .andExpect(jsonPath("$.data[0].cost_snapshot").doesNotExist())
                .andExpect(jsonPath("$.data[1].cost").doesNotExist())
                .andExpect(jsonPath("$.data[1].cost_snapshot").doesNotExist());
    }

    // ================================================================
    // available-products — search
    // ================================================================

    @Test
    void products_searchByCode_caseInsensitive() throws Exception {
        mockMvc.perform(get(productsUrl(ORDER_SOFT)).param("search", "plu").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].code").value("PLU-001"));
    }

    @Test
    void products_searchByName_caseInsensitive_matchesMultiple() throws Exception {
        // Both SOFT product names contain "Carpet".
        mockMvc.perform(get(productsUrl(ORDER_SOFT)).param("search", "CARPET").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].product_id", containsInAnyOrder(2, 1)));
    }

    @Test
    void products_searchUnderscore_isEscaped_notWildcard() throws Exception {
        // Give PLU-001 a literal underscore in its name. Without escaping, ILIKE '%_%' matches any
        // non-empty string and would return both SOFT products; with escaping only PLU-001 matches.
        jdbcTemplate.update("UPDATE store_product SET name = 'Plush_Special' WHERE product_id = 1");

        mockMvc.perform(get(productsUrl(ORDER_SOFT)).param("search", "_").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].product_id").value(1));
    }

    @Test
    void products_searchPercent_isEscaped_notWildcard() throws Exception {
        jdbcTemplate.update("UPDATE store_product SET name = 'Fifty%Off' WHERE product_id = 1");

        mockMvc.perform(get(productsUrl(ORDER_SOFT)).param("search", "%").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].product_id").value(1));
    }

    @Test
    void products_searchNoMatch_returnsEmpty() throws Exception {
        mockMvc.perform(get(productsUrl(ORDER_SOFT)).param("search", "zzz-no-match")
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)))
                .andExpect(jsonPath("$.pagination.total_items").value(0))
                .andExpect(jsonPath("$.pagination.total_pages").value(0));
    }

    // ================================================================
    // available-products — pagination
    // ================================================================

    @Test
    void products_defaultPagination() throws Exception {
        mockMvc.perform(get(productsUrl(ORDER_SOFT)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.page").value(1))
                .andExpect(jsonPath("$.pagination.page_size").value(20))
                .andExpect(jsonPath("$.pagination.total_items").value(2))
                .andExpect(jsonPath("$.pagination.total_pages").value(1));
    }

    @Test
    void products_pageSizeOne_totalsReflectFullResult() throws Exception {
        mockMvc.perform(get(productsUrl(ORDER_SOFT)).param("page", "1").param("page_size", "1")
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].code").value("LOP-001"))
                .andExpect(jsonPath("$.pagination.page").value(1))
                .andExpect(jsonPath("$.pagination.page_size").value(1))
                .andExpect(jsonPath("$.pagination.total_items").value(2))
                .andExpect(jsonPath("$.pagination.total_pages").value(2));
    }

    @Test
    void products_secondPage_returnsNextItem() throws Exception {
        mockMvc.perform(get(productsUrl(ORDER_SOFT)).param("page", "2").param("page_size", "1")
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].code").value("PLU-001"))
                .andExpect(jsonPath("$.pagination.page").value(2));
    }

    @Test
    void products_pagePastEnd_returns200_withEmptyData_andCorrectTotals() throws Exception {
        mockMvc.perform(get(productsUrl(ORDER_SOFT)).param("page", "99").param("page_size", "20")
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)))
                .andExpect(jsonPath("$.pagination.page").value(99))
                .andExpect(jsonPath("$.pagination.page_size").value(20))
                .andExpect(jsonPath("$.pagination.total_items").value(2))
                .andExpect(jsonPath("$.pagination.total_pages").value(1));
    }

    @Test
    void products_emptyResultSet_returns200_withTotalsZero() throws Exception {
        jdbcTemplate.update("UPDATE store_product SET is_active = FALSE WHERE product_id IN (1, 2)");

        mockMvc.perform(get(productsUrl(ORDER_SOFT)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)))
                .andExpect(jsonPath("$.pagination.total_items").value(0))
                .andExpect(jsonPath("$.pagination.total_pages").value(0));
    }

    // ================================================================
    // available-products — validation 400s
    // ================================================================

    @Test
    void products_invalidPage_returns400() throws Exception {
        mockMvc.perform(get(productsUrl(ORDER_SOFT)).param("page", "0").session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("page"));
    }

    @Test
    void products_nonNumericPage_returns400() throws Exception {
        mockMvc.perform(get(productsUrl(ORDER_SOFT)).param("page", "abc").session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("page"));
    }

    @Test
    void products_pageSizeTooLow_returns400() throws Exception {
        mockMvc.perform(get(productsUrl(ORDER_SOFT)).param("page_size", "0").session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("page_size"));
    }

    @Test
    void products_pageSizeTooHigh_returns400() throws Exception {
        mockMvc.perform(get(productsUrl(ORDER_SOFT)).param("page_size", "101").session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("page_size"));
    }

    @Test
    void products_nonNumericOrderId_returns400_orderIdField() throws Exception {
        mockMvc.perform(get(productsUrl("abc")).session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    @Test
    void products_nonPositiveOrderId_returns400_orderIdField() throws Exception {
        mockMvc.perform(get(productsUrl("0")).session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    // ================================================================
    // available-products — order scoping 404s & LAID
    // ================================================================

    @Test
    void products_crossStoreOrder_returns404() throws Exception {
        mockMvc.perform(get(productsUrl(ORDER_OTHER_STORE)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void products_crossBusinessOrder_returns404() throws Exception {
        mockMvc.perform(get(productsUrl(ORDER_OTHER_BUSINESS)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void products_nonexistentOrder_returns404() throws Exception {
        mockMvc.perform(get(productsUrl(ORDER_DOES_NOT_EXIST)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void products_laidOrder_returns200() throws Exception {
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'LAID' WHERE order_id = ?", ORDER_HARD);

        mockMvc.perform(get(productsUrl(ORDER_HARD)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].product_id", containsInAnyOrder(4, 3)));
    }

    // ================================================================
    // available-charges — scoping, shape, cost/field exclusion
    // ================================================================

    @Test
    void charges_softOrder_returnsOnlyActiveSoftStoreCharges_codeAsc() throws Exception {
        mockMvc.perform(get(chargesUrl(ORDER_SOFT)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].charge_id", containsInAnyOrder(1, 2)))
                .andExpect(jsonPath("$.data[0].code").value("INST-S"))
                .andExpect(jsonPath("$.data[1].code").value("UNDR-S"))
                .andExpect(jsonPath("$.data[0].flooring_type").value("SOFT"))
                .andExpect(jsonPath("$.data[0].name").value("Carpet Installation"))
                .andExpect(jsonPath("$.data[0].price").value(15.0));
    }

    @Test
    void charges_hardOrder_returnsHardStoreCharges_codeAsc() throws Exception {
        mockMvc.perform(get(chargesUrl(ORDER_HARD)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].charge_id", containsInAnyOrder(3, 4)))
                .andExpect(jsonPath("$.data[0].code").value("INST-H"))
                .andExpect(jsonPath("$.data[1].code").value("UNDR-H"));
    }

    @Test
    void charges_inactiveChargeExcluded() throws Exception {
        jdbcTemplate.update("UPDATE store_charge SET is_active = FALSE WHERE charge_id = 1");

        mockMvc.perform(get(chargesUrl(ORDER_SOFT)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].charge_id").value(2))
                .andExpect(jsonPath("$.data[0].code").value("UNDR-S"));
    }

    @Test
    void charges_doNotReturnCost_pricingUnit_orStockFields() throws Exception {
        mockMvc.perform(get(chargesUrl(ORDER_SOFT)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                // present identity + price fields
                .andExpect(jsonPath("$.data[0].charge_id").exists())
                .andExpect(jsonPath("$.data[0].code").exists())
                .andExpect(jsonPath("$.data[0].name").exists())
                .andExpect(jsonPath("$.data[0].flooring_type").exists())
                .andExpect(jsonPath("$.data[0].price").exists())
                // forbidden fields
                .andExpect(jsonPath("$.data[0].cost").doesNotExist())
                .andExpect(jsonPath("$.data[0].cost_snapshot").doesNotExist())
                .andExpect(jsonPath("$.data[0].pricing_unit").doesNotExist())
                .andExpect(jsonPath("$.data[0].stock_quantity").doesNotExist())
                .andExpect(jsonPath("$.data[0].stock_unit").doesNotExist());
    }

    // ================================================================
    // available-charges — search, pagination, validation
    // ================================================================

    @Test
    void charges_searchByName_caseInsensitive() throws Exception {
        // Both SOFT charge names contain "Carpet".
        mockMvc.perform(get(chargesUrl(ORDER_SOFT)).param("search", "carpet").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].charge_id", containsInAnyOrder(1, 2)));
    }

    @Test
    void charges_searchByCode_caseInsensitive() throws Exception {
        mockMvc.perform(get(chargesUrl(ORDER_SOFT)).param("search", "undr").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].code").value("UNDR-S"));
    }

    @Test
    void charges_pageSizeOne_totalsReflectFullResult() throws Exception {
        mockMvc.perform(get(chargesUrl(ORDER_SOFT)).param("page", "1").param("page_size", "1")
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].code").value("INST-S"))
                .andExpect(jsonPath("$.pagination.total_items").value(2))
                .andExpect(jsonPath("$.pagination.total_pages").value(2));
    }

    @Test
    void charges_pagePastEnd_returns200_withEmptyData() throws Exception {
        mockMvc.perform(get(chargesUrl(ORDER_SOFT)).param("page", "99").param("page_size", "20")
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)))
                .andExpect(jsonPath("$.pagination.total_items").value(2))
                .andExpect(jsonPath("$.pagination.total_pages").value(1));
    }

    @Test
    void charges_invalidPageSize_returns400() throws Exception {
        mockMvc.perform(get(chargesUrl(ORDER_SOFT)).param("page_size", "101").session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("page_size"));
    }

    @Test
    void charges_nonNumericOrderId_returns400_orderIdField() throws Exception {
        mockMvc.perform(get(chargesUrl("abc")).session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    // ================================================================
    // available-charges — order scoping 404s & LAID
    // ================================================================

    @Test
    void charges_crossStoreOrder_returns404() throws Exception {
        mockMvc.perform(get(chargesUrl(ORDER_OTHER_STORE)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void charges_crossBusinessOrder_returns404() throws Exception {
        mockMvc.perform(get(chargesUrl(ORDER_OTHER_BUSINESS)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void charges_nonexistentOrder_returns404() throws Exception {
        mockMvc.perform(get(chargesUrl(ORDER_DOES_NOT_EXIST)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void charges_laidOrder_returns200() throws Exception {
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'LAID' WHERE order_id = ?", ORDER_HARD);

        mockMvc.perform(get(chargesUrl(ORDER_HARD)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].charge_id", containsInAnyOrder(3, 4)));
    }
}
