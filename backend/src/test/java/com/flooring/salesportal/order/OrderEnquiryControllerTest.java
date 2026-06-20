package com.flooring.salesportal.order;

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

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 15F lead enquiry form — {@code PUT /orders/{orderId}/enquiry} + the {@code enquiry} object
 * embedded on the GET workspace read (R1, no standalone GET).
 *
 * <p>Self-seeded (Phase 14D go-forward rule): each test INSERTs its own {@code sales_order} rows via
 * JdbcTemplate rather than depending on the V4 demo orders, so order state (LEAD vs LAID) and tenancy
 * are controlled exactly. The session identity (Liam / business 1 / store 1) and the existence of a
 * second business with its own store + user are foundational tenant rows; cross-tenant ids are
 * discovered from the DB, not hardcoded. All tests run inside the test transaction and roll back.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class OrderEnquiryControllerTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";

    // Seeded session identity: Liam Carter (user 1) in business 1 / store 1 (SYD-CBD).
    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    // A fully-populated enquiry body: all 19 data fields set, tri-state fields a mix of Yes/No.
    private static final String FULL_ENQUIRY_BODY = """
            {
              "lead_type": "FLOOR",
              "carpet": true,
              "hard_floor": true,
              "product_fit_timing": "Within 4 weeks",
              "product_usage_context": "Family home, two children and a dog",
              "rooms_to_cover": "Lounge, hallway, three bedrooms",
              "textured_or_plain": "Textured",
              "light_or_dark_tones": "Light",
              "colours_interested": "Beige and soft grey",
              "current_flooring_and_reason": "Old worn carpet, replacing before sale",
              "products_discussed": "Wool twist, hybrid plank",
              "fully_installed": true,
              "uplift": false,
              "furniture": true,
              "stairs": false,
              "subfloor_concrete": true,
              "subfloor_timber": true,
              "subfloor_tile": false,
              "brief_summary": "Keen buyer, follow up with quote next week"
            }
            """;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Detaches managed entities between a native write and a JPA read within the single test-scoped
    // transaction, simulating the separate persistence contexts distinct HTTP requests get in
    // production (the native upsert bypasses Hibernate's identity map).
    @PersistenceContext
    private EntityManager entityManager;

    private MockMvc mockMvc;

    // Per-method (PER_METHOD lifecycle) order_sequence_number source; high to avoid the V4 1..8 range.
    private int seq = 90_000;

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

    private static String enquiryUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/enquiry";
    }

    private static String workspaceUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId;
    }

    private long insertOrder(long businessId, int storeId, long userId, String status) {
        int s = ++seq;
        // order_number must match chk_sales_order_number_format (V6):
        //   ^[A-Z0-9-]+\.[A-Z]{2}[0-9]\.[0-9]{5}$  e.g. SYD-CBD.LC1.00001
        String orderNumber = "ENQTST.ZZ9." + String.format("%05d", s % 100_000);
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

    private Boolean boolCol(long orderId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM order_enquiry WHERE order_id = ?", Boolean.class, orderId);
    }

    private String textCol(long orderId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM order_enquiry WHERE order_id = ?", String.class, orderId);
    }

    private int enquiryRowCount(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_enquiry WHERE order_id = ?", Integer.class, orderId);
    }

    // ================================================================
    // GET workspace — enquiry embed (R1)
    // ================================================================

    @Test
    void workspaceGet_noEnquiryRow_returnsEnquiryNull() throws Exception {
        long orderId = leadOrderInSession();

        entityManager.clear();
        mockMvc.perform(get(workspaceUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enquiry").value(nullValue()));
    }

    @Test
    void workspaceGet_afterPut_returnsSavedEnquiry() throws Exception {
        long orderId = leadOrderInSession();

        mockMvc.perform(put(enquiryUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(FULL_ENQUIRY_BODY))
                .andExpect(status().isOk());

        entityManager.clear();
        mockMvc.perform(get(workspaceUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enquiry.lead_type").value("FLOOR"))
                .andExpect(jsonPath("$.data.enquiry.carpet").value(true))
                .andExpect(jsonPath("$.data.enquiry.hard_floor").value(true))
                .andExpect(jsonPath("$.data.enquiry.rooms_to_cover").value("Lounge, hallway, three bedrooms"))
                .andExpect(jsonPath("$.data.enquiry.fully_installed").value(true))
                .andExpect(jsonPath("$.data.enquiry.uplift").value(false))
                .andExpect(jsonPath("$.data.enquiry.subfloor_concrete").value(true))
                .andExpect(jsonPath("$.data.enquiry.subfloor_tile").value(false))
                .andExpect(jsonPath("$.data.enquiry.brief_summary").value("Keen buyer, follow up with quote next week"));
    }

    @Test
    void workspaceGet_onLaidOrder_stillWorks() throws Exception {
        long orderId = laidOrderInSession();

        entityManager.clear();
        mockMvc.perform(get(workspaceUrl(orderId)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.locked").value(true))
                .andExpect(jsonPath("$.data.enquiry").value(nullValue()));
    }

    // ================================================================
    // PUT enquiry — happy path + envelope
    // ================================================================

    @Test
    void putEnquiry_createsRow_returnsDataEnquiry() throws Exception {
        long orderId = leadOrderInSession();

        mockMvc.perform(put(enquiryUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(FULL_ENQUIRY_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Enquiry saved."))
                .andExpect(jsonPath("$.data.enquiry.carpet").value(true))
                .andExpect(jsonPath("$.data.enquiry.hard_floor").value(true))
                .andExpect(jsonPath("$.data.enquiry.fully_installed").value(true))
                .andExpect(jsonPath("$.data.enquiry.stairs").value(false));

        Assertions.assertEquals(1, enquiryRowCount(orderId));
    }

    @Test
    void putEnquiry_persistsAll19Fields() throws Exception {
        long orderId = leadOrderInSession();

        mockMvc.perform(put(enquiryUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(FULL_ENQUIRY_BODY))
                .andExpect(status().isOk());

        // 1 lead_type
        Assertions.assertEquals("FLOOR", textCol(orderId, "lead_type"));
        // 2 product booleans
        Assertions.assertEquals(Boolean.TRUE, boolCol(orderId, "carpet"));
        Assertions.assertEquals(Boolean.TRUE, boolCol(orderId, "hard_floor"));
        // 9 text fields
        Assertions.assertEquals("Within 4 weeks", textCol(orderId, "product_fit_timing"));
        Assertions.assertEquals("Family home, two children and a dog", textCol(orderId, "product_usage_context"));
        Assertions.assertEquals("Lounge, hallway, three bedrooms", textCol(orderId, "rooms_to_cover"));
        Assertions.assertEquals("Textured", textCol(orderId, "textured_or_plain"));
        Assertions.assertEquals("Light", textCol(orderId, "light_or_dark_tones"));
        Assertions.assertEquals("Beige and soft grey", textCol(orderId, "colours_interested"));
        Assertions.assertEquals("Old worn carpet, replacing before sale", textCol(orderId, "current_flooring_and_reason"));
        Assertions.assertEquals("Wool twist, hybrid plank", textCol(orderId, "products_discussed"));
        Assertions.assertEquals("Keen buyer, follow up with quote next week", textCol(orderId, "brief_summary"));
        // 4 tri-state booleans
        Assertions.assertEquals(Boolean.TRUE, boolCol(orderId, "fully_installed"));
        Assertions.assertEquals(Boolean.FALSE, boolCol(orderId, "uplift"));
        Assertions.assertEquals(Boolean.TRUE, boolCol(orderId, "furniture"));
        Assertions.assertEquals(Boolean.FALSE, boolCol(orderId, "stairs"));
        // 3 subfloor booleans
        Assertions.assertEquals(Boolean.TRUE, boolCol(orderId, "subfloor_concrete"));
        Assertions.assertEquals(Boolean.TRUE, boolCol(orderId, "subfloor_timber"));
        Assertions.assertEquals(Boolean.FALSE, boolCol(orderId, "subfloor_tile"));
    }

    @Test
    void putEnquiry_secondPut_replacesRow_noDuplicate() throws Exception {
        long orderId = leadOrderInSession();

        mockMvc.perform(put(enquiryUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(FULL_ENQUIRY_BODY))
                .andExpect(status().isOk());

        String secondBody = """
                {
                  "carpet": false,
                  "hard_floor": true,
                  "brief_summary": "Updated after second visit",
                  "fully_installed": false
                }
                """;
        entityManager.clear();
        mockMvc.perform(put(enquiryUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(secondBody))
                .andExpect(status().isOk());

        // Exactly one row (upsert, not insert) and values fully replaced by the second body.
        Assertions.assertEquals(1, enquiryRowCount(orderId), "second PUT must not create a second row");
        Assertions.assertEquals(Boolean.FALSE, boolCol(orderId, "carpet"));
        Assertions.assertEquals(Boolean.TRUE, boolCol(orderId, "hard_floor"));
        Assertions.assertEquals("Updated after second visit", textCol(orderId, "brief_summary"));
        Assertions.assertEquals(Boolean.FALSE, boolCol(orderId, "fully_installed"));
        // Omitted-this-time fields are full-replaced: text -> null, tri-state -> null, flags -> false.
        Assertions.assertNull(textCol(orderId, "rooms_to_cover"));
        Assertions.assertNull(boolCol(orderId, "uplift"));
        Assertions.assertEquals(Boolean.FALSE, boolCol(orderId, "subfloor_concrete"));
    }

    // ================================================================
    // Nullable boolean semantics
    // ================================================================

    @Test
    void putEnquiry_omittedTriStateFields_becomeNull_notFalse() throws Exception {
        long orderId = leadOrderInSession();

        String body = """
                {
                  "carpet": true,
                  "hard_floor": false,
                  "subfloor_concrete": true
                }
                """;
        mockMvc.perform(put(enquiryUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                // Response serializes the unanswered tri-state fields as JSON null (not false / absent).
                .andExpect(jsonPath("$.data.enquiry.fully_installed").value(nullValue()))
                .andExpect(jsonPath("$.data.enquiry.uplift").value(nullValue()))
                .andExpect(jsonPath("$.data.enquiry.furniture").value(nullValue()))
                .andExpect(jsonPath("$.data.enquiry.stairs").value(nullValue()));

        // DB: the four tri-state columns are SQL NULL, never coerced to false.
        Assertions.assertNull(boolCol(orderId, "fully_installed"));
        Assertions.assertNull(boolCol(orderId, "uplift"));
        Assertions.assertNull(boolCol(orderId, "furniture"));
        Assertions.assertNull(boolCol(orderId, "stairs"));
    }

    @Test
    void putEnquiry_omittedNotNullBooleans_defaultFalse() throws Exception {
        long orderId = leadOrderInSession();

        // Empty object: no booleans, no text. NOT NULL flags coerce to false; tri-state stay null.
        mockMvc.perform(put(enquiryUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        Assertions.assertEquals(Boolean.FALSE, boolCol(orderId, "carpet"));
        Assertions.assertEquals(Boolean.FALSE, boolCol(orderId, "hard_floor"));
        Assertions.assertEquals(Boolean.FALSE, boolCol(orderId, "subfloor_concrete"));
        Assertions.assertEquals(Boolean.FALSE, boolCol(orderId, "subfloor_timber"));
        Assertions.assertEquals(Boolean.FALSE, boolCol(orderId, "subfloor_tile"));
        Assertions.assertNull(boolCol(orderId, "fully_installed"));
        Assertions.assertNull(boolCol(orderId, "stairs"));
    }

    @Test
    void putEnquiry_blankBody_returns200_allDefault() throws Exception {
        long orderId = leadOrderInSession();

        // The enquiry has no required fields, so an absent/blank body parses to a null DTO and
        // writes an all-default row (200) — it does NOT 400 like customer/details, which have
        // required fields. This backs openapi requestBody.required = false for this endpoint.
        mockMvc.perform(put(enquiryUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Enquiry saved."))
                .andExpect(jsonPath("$.data.enquiry.carpet").value(false))
                .andExpect(jsonPath("$.data.enquiry.fully_installed").value(nullValue()));

        Assertions.assertEquals(1, enquiryRowCount(orderId));
        Assertions.assertEquals(Boolean.FALSE, boolCol(orderId, "carpet"));
        Assertions.assertEquals(Boolean.FALSE, boolCol(orderId, "hard_floor"));
        Assertions.assertNull(boolCol(orderId, "uplift"));
        Assertions.assertNull(textCol(orderId, "brief_summary"));
    }

    // ================================================================
    // lead_type — single choice FLOOR / PHONE / INTERNET / null
    // ================================================================

    @Test
    void putEnquiry_leadType_persists() throws Exception {
        long orderId = leadOrderInSession();

        mockMvc.perform(put(enquiryUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lead_type\": \"PHONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enquiry.lead_type").value("PHONE"));

        Assertions.assertEquals("PHONE", textCol(orderId, "lead_type"));
    }

    @Test
    void putEnquiry_omittedLeadType_becomesNull() throws Exception {
        long orderId = leadOrderInSession();

        mockMvc.perform(put(enquiryUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"carpet\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enquiry.lead_type").value(nullValue()));

        Assertions.assertNull(textCol(orderId, "lead_type"));
    }

    @Test
    void putEnquiry_blankLeadType_becomesNull() throws Exception {
        long orderId = leadOrderInSession();

        mockMvc.perform(put(enquiryUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lead_type\": \"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enquiry.lead_type").value(nullValue()));

        Assertions.assertNull(textCol(orderId, "lead_type"));
    }

    @Test
    void putEnquiry_leadType_fullReplaceClearsToNull() throws Exception {
        long orderId = leadOrderInSession();

        mockMvc.perform(put(enquiryUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lead_type\": \"INTERNET\"}"))
                .andExpect(status().isOk());
        Assertions.assertEquals("INTERNET", textCol(orderId, "lead_type"));

        // Second PUT omits lead_type -> full-replace clears it back to null (one row).
        entityManager.clear();
        mockMvc.perform(put(enquiryUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"carpet\": true}"))
                .andExpect(status().isOk());
        Assertions.assertNull(textCol(orderId, "lead_type"));
        Assertions.assertEquals(1, enquiryRowCount(orderId));
    }

    @Test
    void putEnquiry_invalidLeadType_returns400_fieldLeadType() throws Exception {
        long orderId = leadOrderInSession();

        // Lowercase is NOT accepted (no silent case-folding); any non-enum value is a 400.
        mockMvc.perform(put(enquiryUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lead_type\": \"floor\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("lead_type"));

        Assertions.assertEquals(0, enquiryRowCount(orderId), "invalid lead_type must not be written");
    }

    @Test
    void putEnquiry_invalidLeadType_onLaidOrder_returns422_beforeValidation() throws Exception {
        long orderId = laidOrderInSession();

        // The LAID gate (422) runs before lead_type validation (400) and before any write.
        mockMvc.perform(put(enquiryUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lead_type\": \"BOGUS\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORDER_LOCKED"));

        Assertions.assertEquals(0, enquiryRowCount(orderId));
    }

    @Test
    void putEnquiry_subfloor_supportsMultipleTrue() throws Exception {
        long orderId = leadOrderInSession();

        String body = """
                {
                  "subfloor_concrete": true,
                  "subfloor_timber": true,
                  "subfloor_tile": true
                }
                """;
        mockMvc.perform(put(enquiryUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        Assertions.assertEquals(Boolean.TRUE, boolCol(orderId, "subfloor_concrete"));
        Assertions.assertEquals(Boolean.TRUE, boolCol(orderId, "subfloor_timber"));
        Assertions.assertEquals(Boolean.TRUE, boolCol(orderId, "subfloor_tile"));
    }

    @Test
    void putEnquiry_carpetAndHardFloor_bothTrue() throws Exception {
        long orderId = leadOrderInSession();

        mockMvc.perform(put(enquiryUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carpet\": true, \"hard_floor\": true}"))
                .andExpect(status().isOk());

        Assertions.assertEquals(Boolean.TRUE, boolCol(orderId, "carpet"));
        Assertions.assertEquals(Boolean.TRUE, boolCol(orderId, "hard_floor"));
    }

    @Test
    void putEnquiry_carpetAndHardFloor_bothFalse() throws Exception {
        long orderId = leadOrderInSession();

        mockMvc.perform(put(enquiryUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carpet\": false, \"hard_floor\": false}"))
                .andExpect(status().isOk());

        Assertions.assertEquals(Boolean.FALSE, boolCol(orderId, "carpet"));
        Assertions.assertEquals(Boolean.FALSE, boolCol(orderId, "hard_floor"));
    }

    // ================================================================
    // LAID lock (protected write) — gate-first ordering
    // ================================================================

    @Test
    void putEnquiry_onLaidOrder_returns422_andDoesNotWrite() throws Exception {
        long orderId = laidOrderInSession();

        mockMvc.perform(put(enquiryUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(FULL_ENQUIRY_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORDER_LOCKED"))
                .andExpect(jsonPath("$.error.message").value("Order is laid and cannot be edited."));

        Assertions.assertEquals(0, enquiryRowCount(orderId), "LAID order must not be written");
    }

    @Test
    void putEnquiry_laidCheckRunsBeforeBodyParse() throws Exception {
        long orderId = laidOrderInSession();

        // Malformed JSON on a LAID in-scope order still returns 422 ORDER_LOCKED, not 400, because
        // the LAID gate runs before the raw body is parsed.
        mockMvc.perform(put(enquiryUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content("{ this is not json"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORDER_LOCKED"));
    }

    // ================================================================
    // Tenant scoping — no existence leak
    // ================================================================

    @Test
    void putEnquiry_crossStoreSameBusiness_returns404() throws Exception {
        int otherStore = storeInBusiness(BUSINESS_AUSSIE, STORE_SYD_CBD);
        long orderId = insertOrder(BUSINESS_AUSSIE, otherStore, USER_LIAM, "LEAD");

        mockMvc.perform(put(enquiryUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(FULL_ENQUIRY_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));

        Assertions.assertEquals(0, enquiryRowCount(orderId), "out-of-scope order must not be written");
    }

    @Test
    void putEnquiry_crossBusiness_returns404() throws Exception {
        long otherBusiness = 2L;
        int otherStore = storeInBusiness(otherBusiness, null);
        long otherUser = userInBusiness(otherBusiness);
        long orderId = insertOrder(otherBusiness, otherStore, otherUser, "LEAD");

        mockMvc.perform(put(enquiryUrl(orderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(FULL_ENQUIRY_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));

        Assertions.assertEquals(0, enquiryRowCount(orderId), "cross-business order must not be written");
    }

    @Test
    void putEnquiry_noSession_returns401() throws Exception {
        long orderId = leadOrderInSession();

        mockMvc.perform(put(enquiryUrl(orderId))
                        .contentType(MediaType.APPLICATION_JSON).content(FULL_ENQUIRY_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ================================================================
    // Client-sent id fields are ignored, not rejected
    // ================================================================

    @Test
    void putEnquiry_idFieldsInBody_areIgnored_notRejected() throws Exception {
        long pathOrderId = leadOrderInSession();
        long bogusOrderId = 999_999L;

        String body = """
                {
                  "order_id": 999999,
                  "business_id": 777,
                  "store_id": 555,
                  "carpet": true,
                  "hard_floor": false
                }
                """;
        // Unknown keys are ignored (no reject-scan): 200, scoping comes from the path, not the body.
        mockMvc.perform(put(enquiryUrl(pathOrderId)).session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enquiry.carpet").value(true));

        // The PATH order got the row; the body's order_id had no effect.
        Assertions.assertEquals(1, enquiryRowCount(pathOrderId));
        Assertions.assertEquals(Boolean.TRUE, boolCol(pathOrderId, "carpet"));
        Assertions.assertEquals(0, enquiryRowCount(bogusOrderId), "body order_id must not be used for scoping");
    }
}
