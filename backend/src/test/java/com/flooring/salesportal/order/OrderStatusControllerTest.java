package com.flooring.salesportal.order;

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

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class OrderStatusControllerTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";
    private static final String SLUG_PREMIER = "premier-flooring-co";

    // Seeded session identity: Liam Carter (user 1) in business 1 / store 1.
    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    // Seed snapshot:
    //   order 1: business 1 / store 1 / status ACCEPTED / order_number SYD-CBD.LC1.00001
    //   order 5: business 1 / store 2 / status LEAD   (cross-store in same business)
    //   order 9: business 2 / store 3 / status LEAD   (cross-business)
    private static final long ORDER_STORE1_ID = 1L;
    private static final long ORDER_OTHER_STORE_SAME_BUSINESS_ID = 5L;
    private static final long ORDER_OTHER_BUSINESS_ID = 9L;
    private static final long ORDER_DOES_NOT_EXIST_ID = 99_999L;
    private static final String ORDER_1_NUMBER = "SYD-CBD.LC1.00001";

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

    private static String url(String slug, Object orderId) {
        return "/api/v1/" + slug + "/orders/" + orderId + "/status";
    }

    private static String url(Object orderId) {
        return url(SLUG_AUSSIE, orderId);
    }

    private static String body(String status) {
        return "{\"order_status\":\"" + status + "\"}";
    }

    private LocalDateTime readUpdatedAt(long orderId) {
        Timestamp ts = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM sales_order WHERE order_id = ?",
                Timestamp.class, orderId);
        return ts == null ? null : ts.toLocalDateTime();
    }

    private String readOrderStatus(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT order_status::text FROM sales_order WHERE order_id = ?",
                String.class, orderId);
    }

    // ----------------------------------------------------------------
    // Standard-protected gating (RequestContextGuard checks)
    // ----------------------------------------------------------------

    @Test
    void noSession_returns401() throws Exception {
        mockMvc.perform(patch(url(ORDER_STORE1_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ACCEPTED")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void sessionWithoutStoreId_returns403() throws Exception {
        mockMvc.perform(patch(url(ORDER_STORE1_ID))
                        .session(liamSessionNoStore())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ACCEPTED")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void slugBusinessDoesNotMatchSessionBusiness_returnsGeneric404() throws Exception {
        // Liam belongs to business 1 (Aussie). Hit the slug for business 2 (Premier).
        // This must come from RequestContextGuard as a generic NOT_FOUND, not ORDER_NOT_FOUND.
        mockMvc.perform(patch(url(SLUG_PREMIER, ORDER_STORE1_ID))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ACCEPTED")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void userLostAccessToSessionStore_returns403() throws Exception {
        jdbcTemplate.update("DELETE FROM user_store_access WHERE user_id = ? AND store_id = ?",
                USER_LIAM, STORE_SYD_CBD);

        mockMvc.perform(patch(url(ORDER_STORE1_ID))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ACCEPTED")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void unknownSlug_returnsGeneric404() throws Exception {
        mockMvc.perform(patch(url("nonexistent-slug", ORDER_STORE1_ID))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ACCEPTED")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void inactiveBusiness_returnsGeneric404() throws Exception {
        jdbcTemplate.update("UPDATE business SET is_active = FALSE WHERE business_id = ?", BUSINESS_AUSSIE);

        mockMvc.perform(patch(url(ORDER_STORE1_ID))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ACCEPTED")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ----------------------------------------------------------------
    // Path / body validation 400s
    // ----------------------------------------------------------------

    @Test
    void invalidOrderIdFormat_returns400_withFieldOrderId() throws Exception {
        mockMvc.perform(patch(url("abc"))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ACCEPTED")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    @Test
    void nonPositiveOrderId_returns400_withFieldOrderId() throws Exception {
        mockMvc.perform(patch(url("0"))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ACCEPTED")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    @Test
    void missingOrderStatusField_returns400_withFieldOrderStatus() throws Exception {
        mockMvc.perform(patch(url(ORDER_STORE1_ID))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_status"));
    }

    @Test
    void invalidOrderStatusValue_returns400_withFieldOrderStatus() throws Exception {
        mockMvc.perform(patch(url(ORDER_STORE1_ID))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("NEW")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_status"));
    }

    @Test
    void malformedJsonBody_returns400_malformedJson() throws Exception {
        mockMvc.perform(patch(url(ORDER_STORE1_ID))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_JSON"));
    }

    // ----------------------------------------------------------------
    // Resource-level 404s — cross-store / cross-business / nonexistent
    // all return ORDER_NOT_FOUND with no existence leak.
    // ----------------------------------------------------------------

    @Test
    void orderDoesNotExist_returns404_orderNotFound() throws Exception {
        mockMvc.perform(patch(url(ORDER_DOES_NOT_EXIST_ID))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ACCEPTED")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void orderInAnotherStoreOfSameBusiness_returns404_orderNotFound() throws Exception {
        mockMvc.perform(patch(url(ORDER_OTHER_STORE_SAME_BUSINESS_ID))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ACCEPTED")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void orderInAnotherBusiness_returns404_orderNotFound() throws Exception {
        mockMvc.perform(patch(url(ORDER_OTHER_BUSINESS_ID))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ACCEPTED")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    // ----------------------------------------------------------------
    // Successful real updates
    // ----------------------------------------------------------------

    @Test
    void acceptedToLaid_returns200_locksOrder() throws Exception {
        mockMvc.perform(patch(url(ORDER_STORE1_ID))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("LAID")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_id").value(ORDER_STORE1_ID))
                .andExpect(jsonPath("$.data.order_number").value(ORDER_1_NUMBER))
                .andExpect(jsonPath("$.data.order_status").value("LAID"))
                .andExpect(jsonPath("$.data.previous_order_status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.locked").value(true));
    }

    @Test
    void laidToFollowUp_unlocks_lockedFalseAndPreviousLaid() throws Exception {
        // Set order 1 to LAID directly so the PATCH exercises the unlock path.
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'LAID'::order_status WHERE order_id = ?",
                ORDER_STORE1_ID);

        mockMvc.perform(patch(url(ORDER_STORE1_ID))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("FOLLOW_UP")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_status").value("FOLLOW_UP"))
                .andExpect(jsonPath("$.data.previous_order_status").value("LAID"))
                .andExpect(jsonPath("$.data.locked").value(false));
    }

    @Test
    void cancelledToLead_anyAllowedTransitionIsAccepted() throws Exception {
        // No transition matrix: a status that the dashboard would normally treat as terminal
        // must still be allowed to move to LEAD.
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'CANCELLED'::order_status WHERE order_id = ?",
                ORDER_STORE1_ID);

        mockMvc.perform(patch(url(ORDER_STORE1_ID))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("LEAD")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_status").value("LEAD"))
                .andExpect(jsonPath("$.data.previous_order_status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.locked").value(false));
    }

    @Test
    void acceptedToCancelled_lockedFalse() throws Exception {
        mockMvc.perform(patch(url(ORDER_STORE1_ID))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CANCELLED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.previous_order_status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.locked").value(false));
    }

    // ----------------------------------------------------------------
    // No-op behaviour
    // ----------------------------------------------------------------

    @Test
    void sameStatusNoOp_returns200_andPreviousEqualsCurrent() throws Exception {
        mockMvc.perform(patch(url(ORDER_STORE1_ID))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ACCEPTED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.previous_order_status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.locked").value(false));
    }

    @Test
    void sameStatusNoOp_doesNotChangeUpdatedAt() throws Exception {
        // Pin updated_at to a past value so the comparison is unambiguous.
        jdbcTemplate.update(
                "UPDATE sales_order SET updated_at = TIMESTAMP '2025-01-01 09:00:00' WHERE order_id = ?",
                ORDER_STORE1_ID);
        LocalDateTime pre = readUpdatedAt(ORDER_STORE1_ID);

        mockMvc.perform(patch(url(ORDER_STORE1_ID))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ACCEPTED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated_at").value("2025-01-01T09:00:00"));

        LocalDateTime post = readUpdatedAt(ORDER_STORE1_ID);
        org.junit.jupiter.api.Assertions.assertEquals(pre, post,
                "No-op must not change sales_order.updated_at");
    }

    @Test
    void realUpdate_changesUpdatedAt() throws Exception {
        jdbcTemplate.update(
                "UPDATE sales_order SET updated_at = TIMESTAMP '2025-01-01 09:00:00' WHERE order_id = ?",
                ORDER_STORE1_ID);
        LocalDateTime pre = readUpdatedAt(ORDER_STORE1_ID);

        mockMvc.perform(patch(url(ORDER_STORE1_ID))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("LAID")))
                .andExpect(status().isOk());

        LocalDateTime post = readUpdatedAt(ORDER_STORE1_ID);
        org.junit.jupiter.api.Assertions.assertTrue(post.isAfter(pre),
                "Real update must set sales_order.updated_at to a newer value");
        org.junit.jupiter.api.Assertions.assertEquals("LAID", readOrderStatus(ORDER_STORE1_ID));
    }

    // ----------------------------------------------------------------
    // Response shape — exactly the 6 contract fields, nothing else
    // ----------------------------------------------------------------

    @Test
    void response_containsExactlyTheSixContractFields_underData() throws Exception {
        mockMvc.perform(patch(url(ORDER_STORE1_ID))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("LAID")))
                .andExpect(status().isOk())
                // All six contract fields present.
                .andExpect(jsonPath("$.data.order_id").exists())
                .andExpect(jsonPath("$.data.order_number").exists())
                .andExpect(jsonPath("$.data.order_status").exists())
                .andExpect(jsonPath("$.data.previous_order_status").exists())
                .andExpect(jsonPath("$.data.locked").exists())
                .andExpect(jsonPath("$.data.updated_at").exists())
                // Internal / non-contract fields must not appear under data.
                .andExpect(jsonPath("$.data.business_id").doesNotExist())
                .andExpect(jsonPath("$.data.store_id").doesNotExist())
                .andExpect(jsonPath("$.data.user_id").doesNotExist())
                .andExpect(jsonPath("$.data.created_at").doesNotExist())
                .andExpect(jsonPath("$.data.gp").doesNotExist())
                .andExpect(jsonPath("$.data.gp_percent").doesNotExist())
                .andExpect(jsonPath("$.data.week_year").doesNotExist())
                .andExpect(jsonPath("$.data.week_number").doesNotExist())
                .andExpect(jsonPath("$.data.flooring_type").doesNotExist())
                .andExpect(jsonPath("$.data.order_sequence_number").doesNotExist())
                .andExpect(jsonPath("$.data.supply_only").doesNotExist())
                .andExpect(jsonPath("$.data.plan_numbers").doesNotExist())
                .andExpect(jsonPath("$.data.proposed_lay_date").doesNotExist())
                .andExpect(jsonPath("$.data.lay_date_status").doesNotExist())
                .andExpect(jsonPath("$.data.sale_price_ex_gst").doesNotExist())
                .andExpect(jsonPath("$.data.total_cost").doesNotExist())
                .andExpect(jsonPath("$.data.details_of_sale").doesNotExist())
                .andExpect(jsonPath("$.data.last_emailed_at").doesNotExist())
                .andExpect(jsonPath("$.data.price_adjustment_inc_gst").doesNotExist());
    }

    @Test
    void response_orderNumberReturnedVerbatim() throws Exception {
        mockMvc.perform(patch(url(ORDER_STORE1_ID))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("FOLLOW_UP")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_number").value(ORDER_1_NUMBER));
    }

    @Test
    void response_updatedAtUsesIsoLocalSecondsFormat() throws Exception {
        mockMvc.perform(patch(url(ORDER_STORE1_ID))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("LAID")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated_at").value(matchesPattern(ISO_LOCAL_PATTERN)));
    }
}
