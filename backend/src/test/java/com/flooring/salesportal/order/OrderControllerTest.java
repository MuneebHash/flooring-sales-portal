package com.flooring.salesportal.order;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class OrderControllerTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";
    private static final String SLUG_PREMIER = "premier-flooring-co";
    private static final String ORDERS_URL = "/api/v1/" + SLUG_AUSSIE + "/orders";

    // Seeded session identity: Liam Carter (user 1) in business 1 / store 1 (SYD-CBD).
    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    // Seed snapshot:
    //   order 1: business 1 / store 1 / ACCEPTED / fully populated (customer + both addresses + financials)
    //   order 2: business 1 / store 1 / LEAD / header-only (no customer, no addresses, null financials)
    //   order 5: business 1 / store 2 / LEAD (cross-store, same business)
    //   order 9: business 2 / store 3 / LEAD (cross-business)
    private static final long ORDER_FULL = 1L;
    private static final long ORDER_EMPTY = 2L;
    private static final long ORDER_OTHER_STORE_SAME_BUSINESS = 5L;
    private static final long ORDER_OTHER_BUSINESS = 9L;
    private static final long ORDER_DOES_NOT_EXIST = 99_999L;

    // Business 1 seeded order_sequence_number values are 1..8, so the next create is 9.
    private static final int EXPECTED_NEXT_SEQ = 9;
    private static final String EXPECTED_NEW_ORDER_NUMBER = "SYD-CBD.LC1.00009";

    private static final String ISO_LOCAL_PATTERN = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}";

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

    private static String createUrl(String slug) {
        return "/api/v1/" + slug + "/orders";
    }

    private static String getUrl(String slug, Object orderId) {
        return "/api/v1/" + slug + "/orders/" + orderId;
    }

    private static String getUrl(Object orderId) {
        return getUrl(SLUG_AUSSIE, orderId);
    }

    private static String body(String flooringType) {
        return "{\"flooring_type\":\"" + flooringType + "\"}";
    }

    // ================================================================
    // POST /orders
    // ================================================================

    // ---- Standard-protected gating ----

    @Test
    void create_noSession_returns401() throws Exception {
        mockMvc.perform(post(ORDERS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SOFT")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void create_sessionWithoutStoreId_returns403() throws Exception {
        mockMvc.perform(post(ORDERS_URL)
                        .session(liamSessionNoStore())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SOFT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void create_slugBusinessDoesNotMatchSessionBusiness_returnsGeneric404() throws Exception {
        mockMvc.perform(post(createUrl(SLUG_PREMIER))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SOFT")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void create_unknownSlug_returnsGeneric404() throws Exception {
        mockMvc.perform(post(createUrl("nonexistent-slug"))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SOFT")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void create_inactiveBusiness_returnsGeneric404() throws Exception {
        jdbcTemplate.update("UPDATE business SET is_active = FALSE WHERE business_id = ?", BUSINESS_AUSSIE);

        mockMvc.perform(post(ORDERS_URL)
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SOFT")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ---- Body validation 400s ----

    @Test
    void create_missingFlooringType_returns400_fieldFlooringType() throws Exception {
        mockMvc.perform(post(ORDERS_URL)
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("flooring_type"));
    }

    @Test
    void create_invalidFlooringType_returns400_fieldFlooringType() throws Exception {
        mockMvc.perform(post(ORDERS_URL)
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("WOOD")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("flooring_type"));
    }

    @Test
    void create_malformedJson_returns400_malformedJson() throws Exception {
        mockMvc.perform(post(ORDERS_URL)
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_JSON"));
    }

    // ---- Successful creation ----

    @Test
    void create_soft_returns201_withContractShape() throws Exception {
        MvcResult result = mockMvc.perform(post(ORDERS_URL)
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SOFT")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Order created."))
                .andExpect(jsonPath("$.data.order_id").exists())
                .andExpect(jsonPath("$.data.order_number").value(EXPECTED_NEW_ORDER_NUMBER))
                .andExpect(jsonPath("$.data.order_sequence_number").value(EXPECTED_NEXT_SEQ))
                .andExpect(jsonPath("$.data.flooring_type").value("SOFT"))
                .andExpect(jsonPath("$.data.order_status").value("LEAD"))
                .andExpect(jsonPath("$.data.supply_only").value(false))
                .andExpect(jsonPath("$.data.plan_numbers").value(nullValue()))
                .andExpect(jsonPath("$.data.proposed_lay_date").value(nullValue()))
                .andExpect(jsonPath("$.data.lay_date_status").value(nullValue()))
                .andExpect(jsonPath("$.data.details_of_sale").value(nullValue()))
                .andExpect(jsonPath("$.data.last_emailed_at").value(nullValue()))
                .andExpect(jsonPath("$.data.week_year").isNumber())
                .andExpect(jsonPath("$.data.week_number").isNumber())
                .andExpect(jsonPath("$.data.created_at").value(matchesPattern(ISO_LOCAL_PATTERN)))
                .andExpect(jsonPath("$.data.updated_at").value(matchesPattern(ISO_LOCAL_PATTERN)))
                .andExpect(jsonPath("$.data.locked").value(false))
                .andReturn();

        // One clock: created_at and updated_at must be identical on create.
        String json = result.getResponse().getContentAsString();
        String createdAt = JsonPath.read(json, "$.data.created_at");
        String updatedAt = JsonPath.read(json, "$.data.updated_at");
        Assertions.assertEquals(createdAt, updatedAt, "created_at and updated_at must share one clock");
    }

    @Test
    void create_hard_returns201_flooringTypeHard() throws Exception {
        mockMvc.perform(post(ORDERS_URL)
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("HARD")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.flooring_type").value("HARD"))
                .andExpect(jsonPath("$.data.order_status").value("LEAD"));
    }

    @Test
    void create_response_containsOnlyHeaderFields_noLeakage() throws Exception {
        mockMvc.perform(post(ORDERS_URL)
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SOFT")))
                .andExpect(status().isCreated())
                // 16 header fields present.
                .andExpect(jsonPath("$.data.order_id").exists())
                .andExpect(jsonPath("$.data.order_number").exists())
                .andExpect(jsonPath("$.data.order_sequence_number").exists())
                .andExpect(jsonPath("$.data.flooring_type").exists())
                .andExpect(jsonPath("$.data.order_status").exists())
                .andExpect(jsonPath("$.data.supply_only").exists())
                .andExpect(jsonPath("$.data.week_year").exists())
                .andExpect(jsonPath("$.data.week_number").exists())
                .andExpect(jsonPath("$.data.created_at").exists())
                .andExpect(jsonPath("$.data.updated_at").exists())
                .andExpect(jsonPath("$.data.locked").exists())
                // Internal / non-create-contract fields must not appear.
                .andExpect(jsonPath("$.data.business_id").doesNotExist())
                .andExpect(jsonPath("$.data.store_id").doesNotExist())
                .andExpect(jsonPath("$.data.user_id").doesNotExist())
                .andExpect(jsonPath("$.data.customer").doesNotExist())
                .andExpect(jsonPath("$.data.install_address").doesNotExist())
                .andExpect(jsonPath("$.data.billing_address").doesNotExist())
                .andExpect(jsonPath("$.data.persisted_financials").doesNotExist())
                .andExpect(jsonPath("$.data.order_financial_summary").doesNotExist())
                .andExpect(jsonPath("$.data.price_adjustment_inc_gst").doesNotExist());
    }

    @Test
    void create_persistsRowScopedToSession() throws Exception {
        mockMvc.perform(post(ORDERS_URL)
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SOFT")))
                .andExpect(status().isCreated());

        // Visible within the same test transaction; rolled back afterwards.
        Long businessId = jdbcTemplate.queryForObject(
                "SELECT business_id FROM sales_order WHERE order_number = ?", Long.class, EXPECTED_NEW_ORDER_NUMBER);
        Integer storeId = jdbcTemplate.queryForObject(
                "SELECT store_id FROM sales_order WHERE order_number = ?", Integer.class, EXPECTED_NEW_ORDER_NUMBER);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM sales_order WHERE order_number = ?", Long.class, EXPECTED_NEW_ORDER_NUMBER);
        String orderStatus = jdbcTemplate.queryForObject(
                "SELECT order_status::text FROM sales_order WHERE order_number = ?", String.class, EXPECTED_NEW_ORDER_NUMBER);
        Boolean supplyOnly = jdbcTemplate.queryForObject(
                "SELECT supply_only FROM sales_order WHERE order_number = ?", Boolean.class, EXPECTED_NEW_ORDER_NUMBER);

        Assertions.assertEquals(Long.valueOf(BUSINESS_AUSSIE), businessId);
        Assertions.assertEquals(Integer.valueOf(STORE_SYD_CBD), storeId);
        Assertions.assertEquals(Long.valueOf(USER_LIAM), userId);
        Assertions.assertEquals("LEAD", orderStatus);
        Assertions.assertEquals(Boolean.FALSE, supplyOnly);
    }

    // ================================================================
    // GET /orders/{orderId}
    // ================================================================

    // ---- Standard-protected gating ----

    @Test
    void get_noSession_returns401() throws Exception {
        mockMvc.perform(get(getUrl(ORDER_FULL)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void get_sessionWithoutStoreId_returns403() throws Exception {
        mockMvc.perform(get(getUrl(ORDER_FULL)).session(liamSessionNoStore()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void get_slugBusinessDoesNotMatchSessionBusiness_returnsGeneric404() throws Exception {
        mockMvc.perform(get(getUrl(SLUG_PREMIER, ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void get_unknownSlug_returnsGeneric404() throws Exception {
        mockMvc.perform(get(getUrl("nonexistent-slug", ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void get_inactiveBusiness_returnsGeneric404() throws Exception {
        jdbcTemplate.update("UPDATE business SET is_active = FALSE WHERE business_id = ?", BUSINESS_AUSSIE);

        mockMvc.perform(get(getUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ---- Path validation 400s ----

    @Test
    void get_invalidOrderIdFormat_returns400_fieldOrderId() throws Exception {
        mockMvc.perform(get(getUrl("abc")).session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    @Test
    void get_nonPositiveOrderId_returns400_fieldOrderId() throws Exception {
        mockMvc.perform(get(getUrl("0")).session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    // ---- Resource-level 404s (no existence leak) ----

    @Test
    void get_orderDoesNotExist_returns404_orderNotFound() throws Exception {
        mockMvc.perform(get(getUrl(ORDER_DOES_NOT_EXIST)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void get_orderInAnotherStoreOfSameBusiness_returns404_orderNotFound() throws Exception {
        mockMvc.perform(get(getUrl(ORDER_OTHER_STORE_SAME_BUSINESS)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void get_orderInAnotherBusiness_returns404_orderNotFound() throws Exception {
        mockMvc.perform(get(getUrl(ORDER_OTHER_BUSINESS)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    // ---- Populated order: non-null customer + both addresses + financials ----

    @Test
    void get_fullyPopulatedOrder_returns200_withCustomerAddressesAndFinancials() throws Exception {
        mockMvc.perform(get(getUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                // header
                .andExpect(jsonPath("$.data.order_id").value(1))
                .andExpect(jsonPath("$.data.order_number").value("SYD-CBD.LC1.00001"))
                .andExpect(jsonPath("$.data.order_sequence_number").value(1))
                .andExpect(jsonPath("$.data.flooring_type").value("SOFT"))
                .andExpect(jsonPath("$.data.order_status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.supply_only").value(false))
                .andExpect(jsonPath("$.data.plan_numbers").value(nullValue()))
                .andExpect(jsonPath("$.data.proposed_lay_date").value("2026-05-01"))
                .andExpect(jsonPath("$.data.lay_date_status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.details_of_sale").value(
                        "Supply and install plush carpet to lounge and dining rooms. Furniture to be moved by installer."))
                .andExpect(jsonPath("$.data.last_emailed_at").value(nullValue()))
                .andExpect(jsonPath("$.data.week_year").value(2026))
                .andExpect(jsonPath("$.data.week_number").value(15))
                .andExpect(jsonPath("$.data.created_at").value(matchesPattern(ISO_LOCAL_PATTERN)))
                .andExpect(jsonPath("$.data.updated_at").value(matchesPattern(ISO_LOCAL_PATTERN)))
                .andExpect(jsonPath("$.data.locked").value(false))
                // customer
                .andExpect(jsonPath("$.data.customer.first_name").value("James"))
                .andExpect(jsonPath("$.data.customer.middle_name").value(nullValue()))
                .andExpect(jsonPath("$.data.customer.last_name").value("Wilson"))
                .andExpect(jsonPath("$.data.customer.email").value("james.wilson@email.com"))
                .andExpect(jsonPath("$.data.customer.mobile").value("0412345678"))
                .andExpect(jsonPath("$.data.customer.home_phone").value("0298765432"))
                .andExpect(jsonPath("$.data.customer.work_phone").value(nullValue()))
                .andExpect(jsonPath("$.data.customer.company_name").value(nullValue()))
                // install address
                .andExpect(jsonPath("$.data.install_address.unit_number").value(nullValue()))
                .andExpect(jsonPath("$.data.install_address.street_number").value("42"))
                .andExpect(jsonPath("$.data.install_address.street").value("Oxford Street"))
                .andExpect(jsonPath("$.data.install_address.suburb").value("Paddington"))
                .andExpect(jsonPath("$.data.install_address.state_code").value("NSW"))
                .andExpect(jsonPath("$.data.install_address.postcode").value("2021"))
                // billing address
                .andExpect(jsonPath("$.data.billing_address.unit_number").value("3"))
                .andExpect(jsonPath("$.data.billing_address.street_number").value("15"))
                .andExpect(jsonPath("$.data.billing_address.street").value("Pitt Street"))
                .andExpect(jsonPath("$.data.billing_address.suburb").value("Sydney"))
                .andExpect(jsonPath("$.data.billing_address.state_code").value("NSW"))
                .andExpect(jsonPath("$.data.billing_address.postcode").value("2000"))
                // persisted financials
                .andExpect(jsonPath("$.data.persisted_financials.sale_price_ex_gst").value(840.00))
                .andExpect(jsonPath("$.data.persisted_financials.total_cost").value(432.00))
                .andExpect(jsonPath("$.data.persisted_financials.gp").value(408.00))
                .andExpect(jsonPath("$.data.persisted_financials.gp_percent").value(48.57));
    }

    // ---- Empty order: null customer / addresses, null financial scalars ----

    @Test
    void get_emptyOrder_returns200_withNullCustomerAddressesAndFinancials() throws Exception {
        mockMvc.perform(get(getUrl(ORDER_EMPTY)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_id").value(2))
                .andExpect(jsonPath("$.data.order_number").value("SYD-CBD.LC1.00002"))
                .andExpect(jsonPath("$.data.flooring_type").value("HARD"))
                .andExpect(jsonPath("$.data.order_status").value("LEAD"))
                .andExpect(jsonPath("$.data.locked").value(false))
                .andExpect(jsonPath("$.data.customer").value(nullValue()))
                .andExpect(jsonPath("$.data.install_address").value(nullValue()))
                .andExpect(jsonPath("$.data.billing_address").value(nullValue()))
                // persisted_financials object is present, every scalar null.
                .andExpect(jsonPath("$.data.persisted_financials").exists())
                .andExpect(jsonPath("$.data.persisted_financials.sale_price_ex_gst").value(nullValue()))
                .andExpect(jsonPath("$.data.persisted_financials.total_cost").value(nullValue()))
                .andExpect(jsonPath("$.data.persisted_financials.gp").value(nullValue()))
                .andExpect(jsonPath("$.data.persisted_financials.gp_percent").value(nullValue()));
    }

    // ---- LAID order: GET still allowed, locked derived true ----

    @Test
    void get_laidOrder_returns200_lockedTrue() throws Exception {
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'LAID'::order_status WHERE order_id = ?", ORDER_FULL);

        mockMvc.perform(get(getUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_status").value("LAID"))
                .andExpect(jsonPath("$.data.locked").value(true));
    }

    // ---- Independent address nullability: install present, billing absent ----

    @Test
    void get_orderWithInstallButNoBilling_returnsInstallNonNull_billingNull() throws Exception {
        jdbcTemplate.update("DELETE FROM order_address WHERE order_id = ? AND address_type = 'BILLING'::address_type",
                ORDER_FULL);

        mockMvc.perform(get(getUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.install_address.street").value("Oxford Street"))
                .andExpect(jsonPath("$.data.billing_address").value(nullValue()));
    }

    // ---- Response shape: no live financial summary / internal fields ----

    @Test
    void get_response_excludesLiveFinancialSummaryAndInternalFields() throws Exception {
        mockMvc.perform(get(getUrl(ORDER_FULL)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.persisted_financials").exists())
                // The live order_financial_summary block belongs to Chunk 3 — must not appear.
                .andExpect(jsonPath("$.data.order_financial_summary").doesNotExist())
                .andExpect(jsonPath("$.data.persisted_financials.product_subtotal").doesNotExist())
                .andExpect(jsonPath("$.data.persisted_financials.charge_subtotal").doesNotExist())
                .andExpect(jsonPath("$.data.persisted_financials.calculated_total_inc_gst").doesNotExist())
                .andExpect(jsonPath("$.data.persisted_financials.final_sale_price_inc_gst").doesNotExist())
                .andExpect(jsonPath("$.data.persisted_financials.gp_warning").doesNotExist())
                // Internal / scoping fields must not leak.
                .andExpect(jsonPath("$.data.business_id").doesNotExist())
                .andExpect(jsonPath("$.data.store_id").doesNotExist())
                .andExpect(jsonPath("$.data.user_id").doesNotExist())
                .andExpect(jsonPath("$.data.price_adjustment_inc_gst").doesNotExist());
    }
}
