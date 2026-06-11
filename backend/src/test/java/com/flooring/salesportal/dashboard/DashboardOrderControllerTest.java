package com.flooring.salesportal.dashboard;

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

import org.junit.jupiter.api.Assertions;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.isA;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class DashboardOrderControllerTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";
    private static final String SLUG_PREMIER = "premier-flooring-co";
    private static final String LIST_URL = "/api/v1/" + SLUG_AUSSIE + "/orders";

    // Seeded session identity: Liam Carter (user 1) in business 1 / store 1.
    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
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

    // ----------------------------------------------------------------
    // Standard-protected gating (RequestContextGuard checks)
    // ----------------------------------------------------------------

    @Test
    void noSession_returns401() throws Exception {
        mockMvc.perform(get(LIST_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void sessionWithoutStoreId_returns403() throws Exception {
        mockMvc.perform(get(LIST_URL).session(liamSessionNoStore()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void slugBusinessDoesNotMatchSessionBusiness_returns404() throws Exception {
        // Liam belongs to business 1 (Aussie). Hit the slug for business 2 (Premier).
        mockMvc.perform(get("/api/v1/" + SLUG_PREMIER + "/orders").session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void userLostAccessToSessionStore_returns403() throws Exception {
        jdbcTemplate.update("DELETE FROM user_store_access WHERE user_id = ? AND store_id = ?",
                USER_LIAM, STORE_SYD_CBD);

        mockMvc.perform(get(LIST_URL).session(liamStore1Session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void unknownSlug_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/nonexistent-slug/orders").session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void inactiveBusiness_returns404() throws Exception {
        jdbcTemplate.update("UPDATE business SET is_active = FALSE WHERE business_id = ?", BUSINESS_AUSSIE);

        mockMvc.perform(get(LIST_URL).session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ----------------------------------------------------------------
    // Tenant + store scoping (data isolation)
    // ----------------------------------------------------------------

    @Test
    void listReturnsOnlySelectedStoreOrders_andExcludesOtherStoresAndBusinesses() throws Exception {
        mockMvc.perform(get(LIST_URL).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(4)))
                .andExpect(jsonPath("$.data[*].order_id",
                        containsInAnyOrder(1, 2, 3, 4)))
                .andExpect(jsonPath("$.pagination.total_items").value(4))
                .andExpect(jsonPath("$.pagination.total_pages").value(1));
    }

    // ----------------------------------------------------------------
    // Empty-order nullability (LEFT JOINs preserve orders with no customer / no install address)
    // ----------------------------------------------------------------

    @Test
    void emptyOrderWithoutCustomer_appearsWithCustomerNull() throws Exception {
        mockMvc.perform(get(LIST_URL + "?search=LC1.00002").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].order_id").value(2))
                .andExpect(jsonPath("$.data[0].customer").value(nullValue()));
    }

    @Test
    void emptyOrderWithoutInstallAddress_appearsWithInstallAddressNull() throws Exception {
        mockMvc.perform(get(LIST_URL + "?search=LC1.00002").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].order_id").value(2))
                .andExpect(jsonPath("$.data[0].install_address").value(nullValue()));
    }

    @Test
    void populatedOrder_returnsCustomerAndInstallAddressShape() throws Exception {
        mockMvc.perform(get(LIST_URL + "?search=LC1.00001").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].order_id").value(1))
                .andExpect(jsonPath("$.data[0].order_number").value("SYD-CBD.LC1.00001"))
                .andExpect(jsonPath("$.data[0].order_sequence_number").value(1))
                .andExpect(jsonPath("$.data[0].flooring_type").value("SOFT"))
                .andExpect(jsonPath("$.data[0].order_status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data[0].customer.first_name").value("James"))
                .andExpect(jsonPath("$.data[0].customer.last_name").value("Wilson"))
                .andExpect(jsonPath("$.data[0].customer.email").value("james.wilson@email.com"))
                .andExpect(jsonPath("$.data[0].install_address.unit_number").value(nullValue()))
                .andExpect(jsonPath("$.data[0].install_address.street_number").value("42"))
                .andExpect(jsonPath("$.data[0].install_address.street").value("Oxford Street"))
                .andExpect(jsonPath("$.data[0].install_address.suburb").value("Paddington"))
                .andExpect(jsonPath("$.data[0].install_address.state_code").value("NSW"))
                .andExpect(jsonPath("$.data[0].install_address.postcode").value("2021"))
                .andExpect(jsonPath("$.data[0].week_year").value(2026))
                .andExpect(jsonPath("$.data[0].week_number").value(15));
    }

    // ----------------------------------------------------------------
    // Search behavior
    // ----------------------------------------------------------------

    @Test
    void searchMatchesOrderNumber_evenWhenCustomerIsNull() throws Exception {
        // Order 2 (SYD-CBD.LC1.00002) has no order_customer row.
        mockMvc.perform(get(LIST_URL + "?search=00002").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].order_id").value(2))
                .andExpect(jsonPath("$.data[0].customer").value(nullValue()));
    }

    @Test
    void searchMatchesFirstName() throws Exception {
        mockMvc.perform(get(LIST_URL + "?search=James").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].order_id").value(1));
    }

    @Test
    void searchMatchesLastName_caseInsensitive() throws Exception {
        mockMvc.perform(get(LIST_URL + "?search=wilSON").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].order_id").value(1));
    }

    @Test
    void searchMatchesEmail() throws Exception {
        mockMvc.perform(get(LIST_URL + "?search=james.wilson@email.com").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].order_id").value(1));
    }

    @Test
    void searchMatchesCompanyName() throws Exception {
        jdbcTemplate.update("UPDATE order_customer SET company_name = 'AcmeCorp' WHERE order_id = 1");

        mockMvc.perform(get(LIST_URL + "?search=AcmeCorp").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].order_id").value(1));
    }

    @Test
    void searchMatchesMobile() throws Exception {
        mockMvc.perform(get(LIST_URL + "?search=0412345").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].order_id").value(1));
    }

    @Test
    void searchWildcardLiteral_underscoreIsEscaped() throws Exception {
        // Insert a customer on order 3 whose first_name contains a literal underscore.
        // Without escaping, ILIKE '%_%' would match every non-empty string and return all 4 orders.
        // With escaping, '_' must be matched as a literal character — so only order 3 matches.
        jdbcTemplate.update("""
                INSERT INTO order_customer
                    (order_id, first_name, last_name, email, mobile)
                VALUES
                    (3, 'Test_Person', 'Doe', 'doe@example.com', '5550001')
                """);

        mockMvc.perform(get(LIST_URL + "?search=_").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].order_id").value(3));
    }

    @Test
    void searchWildcardLiteral_percentIsEscaped() throws Exception {
        // Update order 1's customer to embed a literal percent sign in first_name.
        // Without escaping, ILIKE '%%%' would match everything; with escaping, only this row matches.
        // Use .param(...) so the literal '%' reaches the servlet decoded; embedding %25 in the URL
        // string lets MockMvc pass it through without decoding, which made the controller see "%25".
        jdbcTemplate.update("UPDATE order_customer SET first_name = 'Fifty%Off' WHERE order_id = 1");

        mockMvc.perform(get(LIST_URL).param("search", "%").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].order_id").value(1));
    }

    // ----------------------------------------------------------------
    // Filters
    // ----------------------------------------------------------------

    @Test
    void statusFilter_returnsOnlyMatchingRows() throws Exception {
        mockMvc.perform(get(LIST_URL + "?status=ACCEPTED").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].order_id").value(1))
                .andExpect(jsonPath("$.data[0].order_status").value("ACCEPTED"));
    }

    @Test
    void commaSeparatedStatus_returns400ValidationFailed() throws Exception {
        mockMvc.perform(get(LIST_URL + "?status=LEAD,ACCEPTED").session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("status"));
    }

    @Test
    void weekYearAndWeekNumberFilters_returnOnlyMatchingRows() throws Exception {
        mockMvc.perform(get(LIST_URL + "?week_year=2026&week_number=14").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].order_id", containsInAnyOrder(3, 4)));
    }

    // ----------------------------------------------------------------
    // Validation 400s
    // ----------------------------------------------------------------

    @Test
    void invalidPage_returns400() throws Exception {
        mockMvc.perform(get(LIST_URL + "?page=0").session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("page"));
    }

    @Test
    void nonNumericPage_returns400() throws Exception {
        mockMvc.perform(get(LIST_URL + "?page=abc").session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("page"));
    }

    @Test
    void invalidPageSize_tooLow_returns400() throws Exception {
        mockMvc.perform(get(LIST_URL + "?page_size=0").session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("page_size"));
    }

    @Test
    void invalidPageSize_tooHigh_returns400() throws Exception {
        mockMvc.perform(get(LIST_URL + "?page_size=101").session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("page_size"));
    }

    @Test
    void invalidStatus_returns400() throws Exception {
        mockMvc.perform(get(LIST_URL + "?status=NEW").session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("status"));
    }

    @Test
    void invalidWeekYear_returns400() throws Exception {
        mockMvc.perform(get(LIST_URL + "?week_year=1999").session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("week_year"));
    }

    @Test
    void invalidWeekNumber_returns400() throws Exception {
        mockMvc.perform(get(LIST_URL + "?week_number=54").session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("week_number"));
    }

    // ----------------------------------------------------------------
    // Pagination
    // ----------------------------------------------------------------

    @Test
    void paginationTotalsReflectFilteredResult_notJustPageLength() throws Exception {
        // Store 1 has 4 orders. With page_size=2 we get 2 rows on page 1 but total_items must still be 4.
        mockMvc.perform(get(LIST_URL + "?page=1&page_size=2").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.pagination.page").value(1))
                .andExpect(jsonPath("$.pagination.page_size").value(2))
                .andExpect(jsonPath("$.pagination.total_items").value(4))
                .andExpect(jsonPath("$.pagination.total_pages").value(2));
    }

    @Test
    void countQueryReflectsStatusFilter_notJustPageLength() throws Exception {
        // Only one ACCEPTED order in store 1 (order 1). total_items must reflect the filter.
        mockMvc.perform(get(LIST_URL + "?status=ACCEPTED&page_size=10").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.pagination.total_items").value(1))
                .andExpect(jsonPath("$.pagination.total_pages").value(1));
    }

    @Test
    void countQueryReflectsSearchFilter() throws Exception {
        // 'SN1' matches order_number of orders 3 and 4 only.
        mockMvc.perform(get(LIST_URL + "?search=SN1&page_size=10").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.pagination.total_items").value(2))
                .andExpect(jsonPath("$.pagination.total_pages").value(1));
    }

    @Test
    void pagePastEnd_returns200_withEmptyData() throws Exception {
        mockMvc.perform(get(LIST_URL + "?page=99&page_size=20").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)))
                .andExpect(jsonPath("$.pagination.page").value(99))
                .andExpect(jsonPath("$.pagination.page_size").value(20))
                .andExpect(jsonPath("$.pagination.total_items").value(4))
                .andExpect(jsonPath("$.pagination.total_pages").value(1));
    }

    @Test
    void emptyResultSet_returns200_withTotalsZero() throws Exception {
        // No CANCELLED orders in store 1's seeded data.
        mockMvc.perform(get(LIST_URL + "?status=CANCELLED").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)))
                .andExpect(jsonPath("$.pagination.total_items").value(0))
                .andExpect(jsonPath("$.pagination.total_pages").value(0));
    }

    // ----------------------------------------------------------------
    // Forbidden response shape — gp_percent / gp_warning MUST NOT appear
    // ----------------------------------------------------------------

    @Test
    void responseRowDoesNotContainGpPercentOrGpWarningKeys_anywhere() throws Exception {
        mockMvc.perform(get(LIST_URL).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(4)))
                // Cover every row, regardless of whether customer/install_address are populated.
                .andExpect(jsonPath("$.data[0].gp_percent").doesNotExist())
                .andExpect(jsonPath("$.data[0].gp_warning").doesNotExist())
                .andExpect(jsonPath("$.data[1].gp_percent").doesNotExist())
                .andExpect(jsonPath("$.data[1].gp_warning").doesNotExist())
                .andExpect(jsonPath("$.data[2].gp_percent").doesNotExist())
                .andExpect(jsonPath("$.data[2].gp_warning").doesNotExist())
                .andExpect(jsonPath("$.data[3].gp_percent").doesNotExist())
                .andExpect(jsonPath("$.data[3].gp_warning").doesNotExist())
                // Also confirm no top-level / pagination-block leakage.
                .andExpect(jsonPath("$.gp_percent").doesNotExist())
                .andExpect(jsonPath("$.gp_warning").doesNotExist())
                .andExpect(jsonPath("$.pagination.gp_percent").doesNotExist())
                .andExpect(jsonPath("$.pagination.gp_warning").doesNotExist());
    }

    // ----------------------------------------------------------------
    // Unknown query params ignored / repeated status rejected / default ordering
    // ----------------------------------------------------------------

    @Test
    void unknownQueryParams_areIgnored_andDataReturnsNormally() throws Exception {
        mockMvc.perform(get(LIST_URL + "?unknown_param=abc&another_one=42").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(4)))
                .andExpect(jsonPath("$.data[*].order_id", containsInAnyOrder(1, 2, 3, 4)))
                .andExpect(jsonPath("$.pagination.page").value(1))
                .andExpect(jsonPath("$.pagination.page_size").value(20))
                .andExpect(jsonPath("$.pagination.total_items").value(4))
                .andExpect(jsonPath("$.pagination.total_pages").value(1));
    }

    @Test
    void repeatedStatusParam_returns400ValidationFailed() throws Exception {
        // Two distinct ?status=... occurrences in the same query string.
        mockMvc.perform(get(LIST_URL + "?status=LEAD&status=ACCEPTED").session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("status"));
    }

    // ----------------------------------------------------------------
    // Phase 13 — invoice_accepted (boolean, derived from the CURRENT invoice) + last_emailed_at mirror
    // ----------------------------------------------------------------

    /** Insert an extra (unaccepted) invoice version for the order, so current = MAX(version_number) moves. */
    private void insertUnacceptedInvoiceVersion(long orderId, int version) {
        Long storedFileId = jdbcTemplate.queryForObject(
                "INSERT INTO stored_file (file_name, storage_path, mime_type, file_size) "
                        + "VALUES (?, ?, 'application/pdf', 1000) RETURNING stored_file_id",
                Long.class, "invoice-v" + version + ".pdf", "/uploads/1/orders/" + orderId + "/v" + version + ".pdf");
        jdbcTemplate.update(
                "INSERT INTO invoice (order_id, version_number, invoice_date, due_date, "
                        + "details_of_sale_snapshot, sale_price_ex_gst, sale_price_inc_gst, total_paid, "
                        + "balance_due, stored_file_id, created_by_user_id) "
                        + "VALUES (?, ?, CURRENT_DATE, CURRENT_DATE, ?, 840.00, 924.00, 500.00, 424.00, ?, ?)",
                orderId, version, "snapshot v" + version, storedFileId, USER_LIAM);
    }

    private int maxInvoiceVersion(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version_number), 0) FROM invoice WHERE order_id = ?", Integer.class, orderId);
    }

    /** Mark the order's CURRENT (max version_number) invoice as accepted. */
    private void acceptCurrentInvoice(long orderId) {
        jdbcTemplate.update("""
                UPDATE invoice SET accepted_at = TIMESTAMP '2026-04-22 14:31:10'
                WHERE invoice_id = (SELECT invoice_id FROM invoice WHERE order_id = ?
                                    ORDER BY version_number DESC LIMIT 1)
                """, orderId);
    }

    @Test
    void invoiceAccepted_falseWhenNoInvoiceExists() throws Exception {
        // Order 2 has no invoice at all -> invoice_accepted is false (never null / never missing).
        mockMvc.perform(get(LIST_URL + "?search=LC1.00002").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].order_id").value(2))
                .andExpect(jsonPath("$.data[0].invoice_accepted").value(false));
    }

    @Test
    void invoiceAccepted_falseWhenCurrentInvoiceUnaccepted() throws Exception {
        // Order 1 has the seeded invoice (accepted_at NULL) -> false.
        mockMvc.perform(get(LIST_URL + "?search=LC1.00001").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].order_id").value(1))
                .andExpect(jsonPath("$.data[0].invoice_accepted").value(false));
    }

    @Test
    void invoiceAccepted_trueWhenCurrentInvoiceAccepted() throws Exception {
        acceptCurrentInvoice(1);

        mockMvc.perform(get(LIST_URL + "?search=LC1.00001").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].invoice_accepted").value(true));
    }

    @Test
    void invoiceAccepted_derivedFromCurrentVersionOnly() throws Exception {
        // Accept the current version, then add a newer UNACCEPTED version: the current (max version)
        // invoice decides, so the row flips back to false — an old accepted version must not keep the
        // dashboard showing accepted.
        acceptCurrentInvoice(1);
        insertUnacceptedInvoiceVersion(1, maxInvoiceVersion(1) + 1);

        mockMvc.perform(get(LIST_URL + "?search=LC1.00001").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].invoice_accepted").value(false));
    }

    @Test
    void invoiceAccepted_presentOnEveryRow_andNoInvoiceStatusEnumLeaks() throws Exception {
        // The indefinite-path projection silently skips rows missing the key (and everyItem passes
        // vacuously on an empty projection), so anchor the assertion: the first row must carry the
        // boolean, and the projection size must equal the row count.
        mockMvc.perform(get(LIST_URL).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].invoice_accepted").isBoolean())
                // Every row carries invoice_accepted as a boolean, regardless of invoice state.
                .andExpect(jsonPath("$.data[*].invoice_accepted", everyItem(isA(Boolean.class))))
                .andExpect(result -> {
                    String json = result.getResponse().getContentAsString();
                    int rows = com.jayway.jsonpath.JsonPath.read(json, "$.data.length()");
                    int withKey = ((java.util.List<?>) com.jayway.jsonpath.JsonPath
                            .read(json, "$.data[*].invoice_accepted")).size();
                    Assertions.assertEquals(rows, withKey, "invoice_accepted present on every row");
                })
                // No invoice-status enum on the dashboard (Phase 13 contract §11): the indefinite path
                // collects matches across all rows, so zero matches = the key appears on no row.
                .andExpect(jsonPath("$.data[*].invoice_status", hasSize(0)));
    }

    @Test
    void lastEmailedAt_nullByDefault_keyStillPresent() throws Exception {
        // The seeded mirror column is NULL (nothing has been emailed). jsonPath doesNotExist() treats
        // JSON null as absent, so assert the key is present-as-null on the raw single-row body.
        MvcResult result = mockMvc.perform(get(LIST_URL + "?search=LC1.00001").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andReturn();

        String json = result.getResponse().getContentAsString();
        Assertions.assertTrue(json.contains("\"last_emailed_at\":null"),
                () -> "last_emailed_at must be present as null: " + json);
    }

    @Test
    void lastEmailedAt_rendersMirrorTimestamp() throws Exception {
        jdbcTemplate.update(
                "UPDATE sales_order SET last_emailed_at = TIMESTAMP '2026-04-22 14:31:12' WHERE order_id = 1");

        mockMvc.perform(get(LIST_URL + "?search=LC1.00001").session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].last_emailed_at").value("2026-04-22T14:31:12"));
    }

    @Test
    void defaultOrdering_isCreatedAtDescending() throws Exception {
        // Seeded sales_order rows all share created_at because V4 inserts them in a single
        // statement, so explicitly stagger created_at to expose the ordering and assert it.
        jdbcTemplate.update("UPDATE sales_order SET created_at = TIMESTAMP '2026-01-01 09:00:00' WHERE order_id = 1");
        jdbcTemplate.update("UPDATE sales_order SET created_at = TIMESTAMP '2026-01-02 09:00:00' WHERE order_id = 2");
        jdbcTemplate.update("UPDATE sales_order SET created_at = TIMESTAMP '2026-01-03 09:00:00' WHERE order_id = 3");
        jdbcTemplate.update("UPDATE sales_order SET created_at = TIMESTAMP '2026-01-04 09:00:00' WHERE order_id = 4");

        mockMvc.perform(get(LIST_URL).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(4)))
                .andExpect(jsonPath("$.data[0].order_id").value(4))
                .andExpect(jsonPath("$.data[1].order_id").value(3))
                .andExpect(jsonPath("$.data[2].order_id").value(2))
                .andExpect(jsonPath("$.data[3].order_id").value(1));
    }
}
