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

import java.sql.Timestamp;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    // order 6: business 1 / store 2 / LAID — cross-store AND laid, to prove 404 wins over 422.
    private static final long ORDER_LAID_OTHER_STORE = 6L;

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

    // ================================================================
    // PUT /orders/{orderId}/customer
    // ================================================================

    // Distinct full-replace value set, different from the seeded order-1 customer, so replace tests
    // can prove every column changed.
    private static final String VALID_CUSTOMER_BODY = """
            {
              "first_name":"Patricia",
              "middle_name":"Anne",
              "last_name":"Hughes",
              "email":"patricia.hughes@example.com",
              "mobile":"0400111222",
              "home_phone":"0299998888",
              "work_phone":"0288887777",
              "company_name":"Hughes Pty Ltd"
            }
            """;

    // Required fields only; all four optionals omitted -> must be written as null on replace.
    private static final String REQUIRED_ONLY_BODY = """
            {
              "first_name":"Patricia",
              "last_name":"Hughes",
              "email":"patricia.hughes@example.com",
              "mobile":"0400111222"
            }
            """;

    private static String customerUrl(String slug, Object orderId) {
        return "/api/v1/" + slug + "/orders/" + orderId + "/customer";
    }

    private static String customerUrl(Object orderId) {
        return customerUrl(SLUG_AUSSIE, orderId);
    }

    // ---- Standard-protected gating (valid JSON so the request reaches the service guard) ----

    @Test
    void customer_noSession_returns401() throws Exception {
        mockMvc.perform(put(customerUrl(ORDER_EMPTY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CUSTOMER_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void customer_sessionWithoutStoreId_returns403() throws Exception {
        mockMvc.perform(put(customerUrl(ORDER_EMPTY))
                        .session(liamSessionNoStore())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CUSTOMER_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void customer_slugBusinessDoesNotMatchSessionBusiness_returnsGeneric404() throws Exception {
        mockMvc.perform(put(customerUrl(SLUG_PREMIER, ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CUSTOMER_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void customer_unknownSlug_returnsGeneric404() throws Exception {
        mockMvc.perform(put(customerUrl("nonexistent-slug", ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CUSTOMER_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void customer_inactiveBusiness_returnsGeneric404() throws Exception {
        jdbcTemplate.update("UPDATE business SET is_active = FALSE WHERE business_id = ?", BUSINESS_AUSSIE);

        mockMvc.perform(put(customerUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CUSTOMER_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ---- Path validation 400s ----

    @Test
    void customer_invalidOrderIdFormat_returns400_fieldOrderId() throws Exception {
        mockMvc.perform(put(customerUrl("abc"))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CUSTOMER_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    @Test
    void customer_nonPositiveOrderId_returns400_fieldOrderId() throws Exception {
        mockMvc.perform(put(customerUrl("0"))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CUSTOMER_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    // ---- Resource-level 404s (no existence leak) ----

    @Test
    void customer_orderDoesNotExist_returns404_orderNotFound() throws Exception {
        mockMvc.perform(put(customerUrl(ORDER_DOES_NOT_EXIST))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CUSTOMER_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void customer_orderInAnotherStoreSameBusiness_returns404_orderNotFound() throws Exception {
        mockMvc.perform(put(customerUrl(ORDER_OTHER_STORE_SAME_BUSINESS))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CUSTOMER_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void customer_orderInAnotherBusiness_returns404_orderNotFound() throws Exception {
        mockMvc.perform(put(customerUrl(ORDER_OTHER_BUSINESS))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CUSTOMER_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    // Cross-store order with an INVALID body still returns 404 — scoped lookup fails before body
    // validation (gate-first), so we never leak existence via a 400.
    @Test
    void customer_crossStoreOrderWithInvalidBody_returns404_orderNotFound() throws Exception {
        mockMvc.perform(put(customerUrl(ORDER_OTHER_STORE_SAME_BUSINESS))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    // Cross-store order that is ALSO laid returns 404, never 422 — existence check wins.
    @Test
    void customer_crossStoreLaidOrder_returns404_notLocked() throws Exception {
        mockMvc.perform(put(customerUrl(ORDER_LAID_OTHER_STORE))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CUSTOMER_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    // ---- LAID lock 422 (after scoped lookup) ----

    @Test
    void customer_laidInScopeOrder_returns422_orderLocked() throws Exception {
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'LAID'::order_status WHERE order_id = ?", ORDER_EMPTY);

        mockMvc.perform(put(customerUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CUSTOMER_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORDER_LOCKED"))
                .andExpect(jsonPath("$.error.message").value("Order is laid and cannot be edited."));
    }

    // LAID in-scope order with an INVALID body still returns 422 — LAID gate runs before body
    // validation (gate-first).
    @Test
    void customer_laidInScopeOrderWithInvalidBody_returns422_orderLocked() throws Exception {
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'LAID'::order_status WHERE order_id = ?", ORDER_EMPTY);

        mockMvc.perform(put(customerUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORDER_LOCKED"));
    }

    // ---- Body validation 400s (in-scope, editable order) ----

    @Test
    void customer_malformedJson_returns400_malformedJson() throws Exception {
        mockMvc.perform(put(customerUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_JSON"));
    }

    @Test
    void customer_missingRequiredFields_returns400_firstFieldFirstName() throws Exception {
        mockMvc.perform(put(customerUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("first_name"));
    }

    @Test
    void customer_blankRequiredField_returns400_fieldFirstName() throws Exception {
        String body = """
                {
                  "first_name":"   ",
                  "last_name":"Hughes",
                  "email":"patricia.hughes@example.com",
                  "mobile":"0400111222"
                }
                """;
        mockMvc.perform(put(customerUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("first_name"));
    }

    @Test
    void customer_emailWithoutAt_returns400_fieldEmail() throws Exception {
        String body = """
                {
                  "first_name":"Patricia",
                  "last_name":"Hughes",
                  "email":"patricia.hughes.example.com",
                  "mobile":"0400111222"
                }
                """;
        mockMvc.perform(put(customerUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("email"));
    }

    @Test
    void customer_blankProvidedOptional_returns400_fieldMiddleName() throws Exception {
        String body = """
                {
                  "first_name":"Patricia",
                  "last_name":"Hughes",
                  "email":"patricia.hughes@example.com",
                  "mobile":"0400111222",
                  "middle_name":"   "
                }
                """;
        mockMvc.perform(put(customerUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("middle_name"));
    }

    // ---- Max-length validation (order_customer VARCHAR limits from V2) ----

    @Test
    void customer_firstNameOverDbLimit_returns400_fieldFirstName() throws Exception {
        String overlong = "a".repeat(101); // first_name VARCHAR(100)
        String body = "{"
                + "\"first_name\":\"" + overlong + "\","
                + "\"last_name\":\"Hughes\","
                + "\"email\":\"patricia.hughes@example.com\","
                + "\"mobile\":\"0400111222\"}";
        mockMvc.perform(put(customerUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("first_name"));
    }

    @Test
    void customer_emailOverDbLimit_returns400_fieldEmail() throws Exception {
        // email VARCHAR(255). Still contains '@', so the only failure is length.
        String overlong = "a".repeat(250) + "@example.com";
        String body = "{"
                + "\"first_name\":\"Patricia\","
                + "\"last_name\":\"Hughes\","
                + "\"email\":\"" + overlong + "\","
                + "\"mobile\":\"0400111222\"}";
        mockMvc.perform(put(customerUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("email"));
    }

    @Test
    void customer_mobileOverDbLimit_returns400_fieldMobile() throws Exception {
        String overlong = "1".repeat(21); // mobile VARCHAR(20)
        String body = "{"
                + "\"first_name\":\"Patricia\","
                + "\"last_name\":\"Hughes\","
                + "\"email\":\"patricia.hughes@example.com\","
                + "\"mobile\":\"" + overlong + "\"}";
        mockMvc.perform(put(customerUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("mobile"));
    }

    @Test
    void customer_companyNameOverDbLimit_returns400_fieldCompanyName() throws Exception {
        String overlong = "c".repeat(151); // company_name VARCHAR(150)
        String body = "{"
                + "\"first_name\":\"Patricia\","
                + "\"last_name\":\"Hughes\","
                + "\"email\":\"patricia.hughes@example.com\","
                + "\"mobile\":\"0400111222\","
                + "\"company_name\":\"" + overlong + "\"}";
        mockMvc.perform(put(customerUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("company_name"));
    }

    // A value exactly at the limit is accepted (boundary check).
    @Test
    void customer_firstNameAtDbLimit_returns200() throws Exception {
        String atLimit = "a".repeat(100); // first_name VARCHAR(100)
        String body = "{"
                + "\"first_name\":\"" + atLimit + "\","
                + "\"last_name\":\"Hughes\","
                + "\"email\":\"patricia.hughes@example.com\","
                + "\"mobile\":\"0400111222\"}";
        mockMvc.perform(put(customerUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer.first_name").value(atLimit));
    }

    // ---- Successful create (order had no customer row) ----

    @Test
    void customer_createForOrderWithNoCustomer_returns200_andPersists() throws Exception {
        // Order 2 starts with no order_customer row.
        Integer before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_customer WHERE order_id = ?", Integer.class, ORDER_EMPTY);
        Assertions.assertEquals(Integer.valueOf(0), before, "precondition: order 2 has no customer");

        mockMvc.perform(put(customerUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CUSTOMER_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Customer saved."))
                .andExpect(jsonPath("$.data.customer.first_name").value("Patricia"))
                .andExpect(jsonPath("$.data.customer.middle_name").value("Anne"))
                .andExpect(jsonPath("$.data.customer.last_name").value("Hughes"))
                .andExpect(jsonPath("$.data.customer.email").value("patricia.hughes@example.com"))
                .andExpect(jsonPath("$.data.customer.mobile").value("0400111222"))
                .andExpect(jsonPath("$.data.customer.home_phone").value("0299998888"))
                .andExpect(jsonPath("$.data.customer.work_phone").value("0288887777"))
                .andExpect(jsonPath("$.data.customer.company_name").value("Hughes Pty Ltd"));

        // Exactly one row, scoped to the order, with the saved values.
        Integer after = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_customer WHERE order_id = ?", Integer.class, ORDER_EMPTY);
        Assertions.assertEquals(Integer.valueOf(1), after, "exactly one customer row created");
        String firstName = jdbcTemplate.queryForObject(
                "SELECT first_name FROM order_customer WHERE order_id = ?", String.class, ORDER_EMPTY);
        Assertions.assertEquals("Patricia", firstName);
    }

    // ---- Successful replace (order already had a customer row) ----

    @Test
    void customer_replaceExistingCustomer_returns200_allColumnsReplaced() throws Exception {
        // Order 1 starts with James Wilson (seed).
        mockMvc.perform(put(customerUrl(ORDER_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CUSTOMER_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Customer saved."))
                .andExpect(jsonPath("$.data.customer.first_name").value("Patricia"))
                .andExpect(jsonPath("$.data.customer.last_name").value("Hughes"));

        // Still exactly one row (uq_order_customer_order respected — no duplicate insert).
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_customer WHERE order_id = ?", Integer.class, ORDER_FULL);
        Assertions.assertEquals(Integer.valueOf(1), count, "replace must not create a second row");

        // Every column replaced.
        Assertions.assertEquals("Patricia", jdbcTemplate.queryForObject(
                "SELECT first_name FROM order_customer WHERE order_id = ?", String.class, ORDER_FULL));
        Assertions.assertEquals("Anne", jdbcTemplate.queryForObject(
                "SELECT middle_name FROM order_customer WHERE order_id = ?", String.class, ORDER_FULL));
        Assertions.assertEquals("Hughes", jdbcTemplate.queryForObject(
                "SELECT last_name FROM order_customer WHERE order_id = ?", String.class, ORDER_FULL));
        Assertions.assertEquals("patricia.hughes@example.com", jdbcTemplate.queryForObject(
                "SELECT email FROM order_customer WHERE order_id = ?", String.class, ORDER_FULL));
        Assertions.assertEquals("0400111222", jdbcTemplate.queryForObject(
                "SELECT mobile FROM order_customer WHERE order_id = ?", String.class, ORDER_FULL));
        Assertions.assertEquals("0299998888", jdbcTemplate.queryForObject(
                "SELECT home_phone FROM order_customer WHERE order_id = ?", String.class, ORDER_FULL));
        Assertions.assertEquals("0288887777", jdbcTemplate.queryForObject(
                "SELECT work_phone FROM order_customer WHERE order_id = ?", String.class, ORDER_FULL));
        Assertions.assertEquals("Hughes Pty Ltd", jdbcTemplate.queryForObject(
                "SELECT company_name FROM order_customer WHERE order_id = ?", String.class, ORDER_FULL));
    }

    // Omitted optional fields overwrite previous non-null values with null (no merge).
    @Test
    void customer_omittedOptionals_clearPreviousValuesToNull() throws Exception {
        // Order 1 seed has home_phone '0298765432'.
        mockMvc.perform(put(customerUrl(ORDER_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUIRED_ONLY_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer.middle_name").value(nullValue()))
                .andExpect(jsonPath("$.data.customer.home_phone").value(nullValue()))
                .andExpect(jsonPath("$.data.customer.work_phone").value(nullValue()))
                .andExpect(jsonPath("$.data.customer.company_name").value(nullValue()));

        Assertions.assertNull(jdbcTemplate.queryForObject(
                "SELECT middle_name FROM order_customer WHERE order_id = ?", String.class, ORDER_FULL));
        Assertions.assertNull(jdbcTemplate.queryForObject(
                "SELECT home_phone FROM order_customer WHERE order_id = ?", String.class, ORDER_FULL));
        Assertions.assertNull(jdbcTemplate.queryForObject(
                "SELECT work_phone FROM order_customer WHERE order_id = ?", String.class, ORDER_FULL));
        Assertions.assertNull(jdbcTemplate.queryForObject(
                "SELECT company_name FROM order_customer WHERE order_id = ?", String.class, ORDER_FULL));
    }

    // Explicit null optional fields are written as null too (same as omitted).
    @Test
    void customer_explicitNullOptionals_writtenAsNull() throws Exception {
        String body = """
                {
                  "first_name":"Patricia",
                  "middle_name":null,
                  "last_name":"Hughes",
                  "email":"patricia.hughes@example.com",
                  "mobile":"0400111222",
                  "home_phone":null,
                  "work_phone":null,
                  "company_name":null
                }
                """;
        mockMvc.perform(put(customerUrl(ORDER_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer.home_phone").value(nullValue()));

        Assertions.assertNull(jdbcTemplate.queryForObject(
                "SELECT home_phone FROM order_customer WHERE order_id = ?", String.class, ORDER_FULL));
    }

    // Leading/trailing whitespace is trimmed before storage.
    @Test
    void customer_storesTrimmedValues() throws Exception {
        String body = """
                {
                  "first_name":"  Patricia  ",
                  "middle_name":"  Anne  ",
                  "last_name":"  Hughes  ",
                  "email":"  patricia.hughes@example.com  ",
                  "mobile":"  0400111222  ",
                  "home_phone":"  0299998888  ",
                  "work_phone":"  0288887777  ",
                  "company_name":"  Hughes Pty Ltd  "
                }
                """;
        mockMvc.perform(put(customerUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer.first_name").value("Patricia"))
                .andExpect(jsonPath("$.data.customer.company_name").value("Hughes Pty Ltd"));

        Assertions.assertEquals("Patricia", jdbcTemplate.queryForObject(
                "SELECT first_name FROM order_customer WHERE order_id = ?", String.class, ORDER_EMPTY));
        Assertions.assertEquals("Hughes Pty Ltd", jdbcTemplate.queryForObject(
                "SELECT company_name FROM order_customer WHERE order_id = ?", String.class, ORDER_EMPTY));
    }

    // Replace preserves created_at and moves updated_at forward.
    @Test
    void customer_replacePreservesCreatedAt_updatesUpdatedAt() throws Exception {
        Timestamp createdBefore = jdbcTemplate.queryForObject(
                "SELECT created_at FROM order_customer WHERE order_id = ?", Timestamp.class, ORDER_FULL);
        Timestamp updatedBefore = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM order_customer WHERE order_id = ?", Timestamp.class, ORDER_FULL);
        Assertions.assertNotNull(createdBefore);
        Assertions.assertNotNull(updatedBefore);

        mockMvc.perform(put(customerUrl(ORDER_FULL))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CUSTOMER_BODY))
                .andExpect(status().isOk());

        Timestamp createdAfter = jdbcTemplate.queryForObject(
                "SELECT created_at FROM order_customer WHERE order_id = ?", Timestamp.class, ORDER_FULL);
        Timestamp updatedAfter = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM order_customer WHERE order_id = ?", Timestamp.class, ORDER_FULL);

        Assertions.assertEquals(createdBefore, createdAfter, "created_at must be preserved on replace");
        Assertions.assertTrue(updatedAfter.after(createdAfter),
                "updated_at must move past created_at on replace");
    }

    // ---- Response shape: only the customer wrapper, no internal IDs / timestamps / order fields ----

    @Test
    void customer_response_excludesInternalIdsTimestampsAndOrderFields() throws Exception {
        mockMvc.perform(put(customerUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CUSTOMER_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer").exists())
                // Internal IDs / timestamps must not leak on the nested customer.
                .andExpect(jsonPath("$.data.customer.order_customer_id").doesNotExist())
                .andExpect(jsonPath("$.data.customer.order_id").doesNotExist())
                .andExpect(jsonPath("$.data.customer.created_at").doesNotExist())
                .andExpect(jsonPath("$.data.customer.updated_at").doesNotExist())
                .andExpect(jsonPath("$.data.customer.business_id").doesNotExist())
                .andExpect(jsonPath("$.data.customer.store_id").doesNotExist())
                .andExpect(jsonPath("$.data.customer.user_id").doesNotExist())
                // No order header fields on the response data.
                .andExpect(jsonPath("$.data.order_id").doesNotExist())
                .andExpect(jsonPath("$.data.order_status").doesNotExist())
                .andExpect(jsonPath("$.data.order_number").doesNotExist())
                .andExpect(jsonPath("$.data.install_address").doesNotExist())
                .andExpect(jsonPath("$.data.billing_address").doesNotExist())
                .andExpect(jsonPath("$.data.persisted_financials").doesNotExist())
                .andExpect(jsonPath("$.data.order_financial_summary").doesNotExist());
    }

    // ---- Phase 10A GET reflects the saved customer within the same transaction ----

    @Test
    void customer_thenGet_reflectsSavedCustomer() throws Exception {
        mockMvc.perform(put(customerUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CUSTOMER_BODY))
                .andExpect(status().isOk());

        mockMvc.perform(get(getUrl(ORDER_EMPTY)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer.first_name").value("Patricia"))
                .andExpect(jsonPath("$.data.customer.middle_name").value("Anne"))
                .andExpect(jsonPath("$.data.customer.last_name").value("Hughes"))
                .andExpect(jsonPath("$.data.customer.email").value("patricia.hughes@example.com"))
                .andExpect(jsonPath("$.data.customer.mobile").value("0400111222"))
                .andExpect(jsonPath("$.data.customer.home_phone").value("0299998888"))
                .andExpect(jsonPath("$.data.customer.work_phone").value("0288887777"))
                .andExpect(jsonPath("$.data.customer.company_name").value("Hughes Pty Ltd"));
    }
}
