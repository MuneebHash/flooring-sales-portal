package com.flooring.salesportal.tenant;

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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the Phase 14C tenant quick-add descriptions endpoint:
 * {@code GET /api/v1/{slug}/quick-descriptions}.
 *
 * <p>Mirrors {@code OrderCatalogControllerTest}: {@code @SpringBootTest @Transactional}, MockMvc,
 * and {@code MockHttpSession} carrying the seeded Liam / business 1 / store 1 identity. All data
 * tweaks go through {@code JdbcTemplate} and roll back with the test transaction.
 *
 * <p>{@code business_quick_description} is NOT seeded by any migration, so every assertion test
 * deletes the target business's rows first and inserts its own known rows — deterministic even
 * against a dirty local DB. The business name used in {@code [BUSINESS_NAME]} substitution
 * assertions is read from the DB at runtime (state-derived, not hardcoded).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class QuickDescriptionControllerTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";
    private static final String SLUG_PREMIER = "premier-flooring-co";

    // Seeded session identity: Liam Carter (user 1) in business 1 / store 1 (SYD-CBD).
    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final long BUSINESS_PREMIER = 2L;
    private static final int STORE_SYD_CBD = 1;

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

    private static String url(String slug) {
        return "/api/v1/" + slug + "/quick-descriptions";
    }

    private void clearQuickDescriptions(long businessId) {
        jdbcTemplate.update("DELETE FROM business_quick_description WHERE business_id = ?", businessId);
    }

    private void insertQuickDescription(long businessId, String description, int sortOrder) {
        jdbcTemplate.update(
                "INSERT INTO business_quick_description (business_id, description, sort_order) VALUES (?, ?, ?)",
                businessId, description, sortOrder);
    }

    private String businessName(long businessId) {
        return jdbcTemplate.queryForObject(
                "SELECT name FROM business WHERE business_id = ?", String.class, businessId);
    }

    // ================================================================
    // Standard-protected gating (RequestContextGuard)
    // ================================================================

    @Test
    void noSession_returns401() throws Exception {
        mockMvc.perform(get(url(SLUG_AUSSIE)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void sessionWithoutStore_returns403() throws Exception {
        mockMvc.perform(get(url(SLUG_AUSSIE)).session(liamSessionNoStore()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void unknownSlug_returns404() throws Exception {
        mockMvc.perform(get(url("nonexistent-slug")).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void crossBusinessSlug_returns404() throws Exception {
        // Liam belongs to business 1 (Aussie). Hitting business 2 (Premier) slug must 404
        // without confirming the other tenant exists.
        mockMvc.perform(get(url(SLUG_PREMIER)).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ================================================================
    // Happy path — ordering, substitution, empty, response shape
    // ================================================================

    @Test
    void authenticated_returnsOrderedBySortOrder() throws Exception {
        clearQuickDescriptions(BUSINESS_AUSSIE);
        // Insert out of natural order; the endpoint must order by sort_order ascending.
        insertQuickDescription(BUSINESS_AUSSIE, "Third", 3);
        insertQuickDescription(BUSINESS_AUSSIE, "First", 1);
        insertQuickDescription(BUSINESS_AUSSIE, "Second", 2);

        mockMvc.perform(get(url(SLUG_AUSSIE)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data", contains("First", "Second", "Third")));
    }

    @Test
    void equalSortOrder_ordersByIdAsc() throws Exception {
        clearQuickDescriptions(BUSINESS_AUSSIE);
        // sort_order has no unique constraint; equal values must fall back to id ascending
        // (insertion order here), keeping output stable.
        insertQuickDescription(BUSINESS_AUSSIE, "Alpha", 1);
        insertQuickDescription(BUSINESS_AUSSIE, "Bravo", 1);
        insertQuickDescription(BUSINESS_AUSSIE, "Charlie", 1);

        mockMvc.perform(get(url(SLUG_AUSSIE)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", contains("Alpha", "Bravo", "Charlie")));
    }

    @Test
    void businessNameToken_isReplacedWithRealBusinessName() throws Exception {
        clearQuickDescriptions(BUSINESS_AUSSIE);
        insertQuickDescription(
                BUSINESS_AUSSIE,
                "[BUSINESS_NAME] to supply and install ________ on ________.",
                1);
        // Two occurrences in one row — String.replace must substitute ALL of them.
        insertQuickDescription(
                BUSINESS_AUSSIE,
                "[BUSINESS_NAME] confirms [BUSINESS_NAME] will move the furniture.",
                2);

        String name = businessName(BUSINESS_AUSSIE); // e.g. "Aussie Floors Group"

        mockMvc.perform(get(url(SLUG_AUSSIE)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0]")
                        .value(name + " to supply and install ________ on ________."))
                .andExpect(jsonPath("$.data[1]")
                        .value(name + " confirms " + name + " will move the furniture."));
    }

    @Test
    void emptyBusinessRows_returnsEmptyList() throws Exception {
        clearQuickDescriptions(BUSINESS_AUSSIE);

        mockMvc.perform(get(url(SLUG_AUSSIE)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void crossTenantRows_areNotLeaked() throws Exception {
        clearQuickDescriptions(BUSINESS_AUSSIE);
        clearQuickDescriptions(BUSINESS_PREMIER);
        // Premier (business 2) owns a row; Aussie (business 1) owns a different row.
        insertQuickDescription(BUSINESS_PREMIER, "PREMIER ONLY", 1);
        insertQuickDescription(BUSINESS_AUSSIE, "AUSSIE ONLY", 1);

        // Liam (business 1) must see only Aussie's row, never Premier's.
        mockMvc.perform(get(url(SLUG_AUSSIE)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data", contains("AUSSIE ONLY")));
    }

    @Test
    void response_isPlainStringList_withNoStructuralOrPrivateFields() throws Exception {
        clearQuickDescriptions(BUSINESS_AUSSIE);
        insertQuickDescription(BUSINESS_AUSSIE, "Just the description text.", 1);

        mockMvc.perform(get(url(SLUG_AUSSIE)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0]").value("Just the description text."))
                // No pagination envelope (this is ApiResponse.ok, not a paged list).
                .andExpect(jsonPath("$.pagination").doesNotExist())
                // Entries are plain strings — no structural or private fields exposed.
                .andExpect(jsonPath("$.data[0].business_id").doesNotExist())
                .andExpect(jsonPath("$.data[0].sort_order").doesNotExist())
                .andExpect(jsonPath("$.data[0].business_quick_description_id").doesNotExist())
                .andExpect(jsonPath("$.data[0].abn").doesNotExist())
                .andExpect(jsonPath("$.data[0].bank_name").doesNotExist())
                .andExpect(jsonPath("$.data[0].account_number").doesNotExist())
                .andExpect(jsonPath("$.data[0].terms_and_conditions").doesNotExist())
                .andExpect(jsonPath("$.data[0].stripe_payment_link_url").doesNotExist())
                .andExpect(jsonPath("$.data[0].invoice_template_key").doesNotExist());
    }
}
