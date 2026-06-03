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
import java.sql.Timestamp;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the Chunk 3 Branch 4 order notes endpoints (D.12 GET list / D.13 POST append).
 *
 * <p>Mirrors {@code OrderChargeLineControllerTest}: {@code @SpringBootTest @Transactional}, MockMvc,
 * {@code MockHttpSession} carrying the seeded Liam / business 1 / store 1 identity, and
 * {@code JdbcTemplate} for data tweaks + persistence assertions (rolled back with the test
 * transaction). Seed (V4): order 1 = SOFT/ACCEPTED, store 1, with one seeded note
 * ({@link #SEEDED_NOTE_TEXT}) and non-null financials (sale_price_ex_gst 840.00, total_cost 432.00,
 * gp 408.00, gp_percent 48.57); order 2 = HARD/LEAD, store 1, empty (no notes); order 5 = store 2
 * (cross-store, same business); order 9 = business 2 (cross-business).
 *
 * <p>Notes are append-only and EXEMPT from the LAID lock — both GET and POST are allowed on a LAID
 * order and never return ORDER_LOCKED.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class OrderNoteControllerTest {

    private static final String SLUG_AUSSIE = "aussie-floors-group";
    private static final String SLUG_PREMIER = "premier-flooring-co";

    private static final long USER_LIAM = 1L;
    private static final long BUSINESS_AUSSIE = 1L;
    private static final int STORE_SYD_CBD = 1;

    private static final long ORDER_WITH_NOTE = 1L;       // SOFT, ACCEPTED, store 1, 1 seeded note + financials
    private static final long ORDER_EMPTY = 2L;           // HARD, LEAD, store 1, no notes
    private static final long ORDER_OTHER_STORE = 5L;     // store 2, business 1 (cross-store)
    private static final long ORDER_OTHER_BUSINESS = 9L;  // business 2 (cross-business)
    private static final long ORDER_DOES_NOT_EXIST = 99_999L;

    private static final String SEEDED_NOTE_TEXT =
            "Customer prefers Saturday morning installation. Access via rear lane behind Oxford Street.";

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

    private static String notesUrl(Object orderId) {
        return "/api/v1/" + SLUG_AUSSIE + "/orders/" + orderId + "/notes";
    }

    private static String notesUrl(String slug, Object orderId) {
        return "/api/v1/" + slug + "/orders/" + orderId + "/notes";
    }

    private void laidOrder(long orderId) {
        jdbcTemplate.update("UPDATE sales_order SET order_status = 'LAID'::order_status WHERE order_id = ?", orderId);
    }

    private int countNotes(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_note WHERE order_id = ?", Integer.class, orderId);
    }

    private String latestNoteText(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT note_text FROM order_note WHERE order_id = ? ORDER BY order_note_id DESC LIMIT 1",
                String.class, orderId);
    }

    /** Insert a note with an explicit created_at so ordering can be asserted deterministically. */
    private void insertNote(long orderId, String text, String createdAt) {
        jdbcTemplate.update(
                "INSERT INTO order_note (order_id, note_text, created_at) VALUES (?, ?, ?)",
                orderId, text, Timestamp.valueOf(createdAt));
    }

    private BigDecimal queryMoney(String column, long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM sales_order WHERE order_id = ?", BigDecimal.class, orderId);
    }

    private void assertMoney(String expected, BigDecimal actual) {
        Assertions.assertNotNull(actual);
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    // ================================================================
    // GET /notes
    // ================================================================

    @Test
    void getNotes_noSession_returns401() throws Exception {
        mockMvc.perform(get(notesUrl(ORDER_WITH_NOTE)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void getNotes_sessionWithoutStore_returns403() throws Exception {
        mockMvc.perform(get(notesUrl(ORDER_WITH_NOTE))
                        .session(liamSessionNoStore()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void getNotes_invalidOrderId_returns400() throws Exception {
        mockMvc.perform(get(notesUrl("abc"))
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    @Test
    void getNotes_nonPositiveOrderId_returns400() throws Exception {
        mockMvc.perform(get(notesUrl("0"))
                        .session(liamStore1Session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    @Test
    void getNotes_crossBusinessSlug_returns404() throws Exception {
        // Right session (business 1) but the slug resolves to business 2 → generic NOT_FOUND, no leak.
        mockMvc.perform(get(notesUrl(SLUG_PREMIER, ORDER_WITH_NOTE))
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void getNotes_crossStoreOrder_returns404() throws Exception {
        // order 5 belongs to store 2.
        mockMvc.perform(get(notesUrl(ORDER_OTHER_STORE))
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void getNotes_crossBusinessOrder_returns404() throws Exception {
        // order 9 belongs to business 2.
        mockMvc.perform(get(notesUrl(ORDER_OTHER_BUSINESS))
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void getNotes_nonexistentOrder_returns404() throws Exception {
        mockMvc.perform(get(notesUrl(ORDER_DOES_NOT_EXIST))
                        .session(liamStore1Session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void getNotes_populatedOrder_returnsSeededNote() throws Exception {
        mockMvc.perform(get(notesUrl(ORDER_WITH_NOTE))
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].order_note_id").value(1))
                .andExpect(jsonPath("$.data[0].note_text").value(SEEDED_NOTE_TEXT))
                .andExpect(jsonPath("$.data[0].created_at").exists());
    }

    @Test
    void getNotes_emptyOrder_returnsEmptyDataArray() throws Exception {
        mockMvc.perform(get(notesUrl(ORDER_EMPTY))
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)))
                .andExpect(jsonPath("$.pagination.total_items").value(0))
                .andExpect(jsonPath("$.pagination.total_pages").value(0));
    }

    @Test
    void getNotes_laidOrder_returns200() throws Exception {
        laidOrder(ORDER_WITH_NOTE);
        mockMvc.perform(get(notesUrl(ORDER_WITH_NOTE))
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].note_text").value(SEEDED_NOTE_TEXT));
    }

    @Test
    void getNotes_defaultPagination_returnsPage1Size20() throws Exception {
        mockMvc.perform(get(notesUrl(ORDER_WITH_NOTE))
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.page").value(1))
                .andExpect(jsonPath("$.pagination.page_size").value(20))
                .andExpect(jsonPath("$.pagination.total_items").value(1))
                .andExpect(jsonPath("$.pagination.total_pages").value(1))
                .andExpect(jsonPath("$.data", hasSize(1)));
    }

    @Test
    void getNotes_invalidPage_returns400() throws Exception {
        mockMvc.perform(get(notesUrl(ORDER_WITH_NOTE))
                        .session(liamStore1Session())
                        .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("page"));
    }

    @Test
    void getNotes_pageSizeTooLow_returns400() throws Exception {
        mockMvc.perform(get(notesUrl(ORDER_WITH_NOTE))
                        .session(liamStore1Session())
                        .param("page_size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("page_size"));
    }

    @Test
    void getNotes_pageSizeTooHigh_returns400() throws Exception {
        mockMvc.perform(get(notesUrl(ORDER_WITH_NOTE))
                        .session(liamStore1Session())
                        .param("page_size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("page_size"));
    }

    @Test
    void getNotes_orderedByCreatedAtDesc_newestFirst() throws Exception {
        // The single seeded note cannot verify ordering — insert 3 notes with distinct created_at.
        insertNote(ORDER_EMPTY, "oldest", "2026-01-01 09:00:00");
        insertNote(ORDER_EMPTY, "middle", "2026-02-01 09:00:00");
        insertNote(ORDER_EMPTY, "newest", "2026-03-01 09:00:00");

        mockMvc.perform(get(notesUrl(ORDER_EMPTY))
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].note_text").value("newest"))
                .andExpect(jsonPath("$.data[1].note_text").value("middle"))
                .andExpect(jsonPath("$.data[2].note_text").value("oldest"));
    }

    @Test
    void getNotes_responseDoesNotExposeScopingColumns() throws Exception {
        mockMvc.perform(get(notesUrl(ORDER_WITH_NOTE))
                        .session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].order_note_id").exists())
                .andExpect(jsonPath("$.data[0].note_text").exists())
                .andExpect(jsonPath("$.data[0].created_at").exists())
                .andExpect(jsonPath("$.data[0].order_id").doesNotExist())
                .andExpect(jsonPath("$.data[0].business_id").doesNotExist())
                .andExpect(jsonPath("$.data[0].store_id").doesNotExist())
                .andExpect(jsonPath("$.data[0].user_id").doesNotExist())
                .andExpect(jsonPath("$.data[0].created_by_user_id").doesNotExist());
    }

    // ================================================================
    // POST /notes
    // ================================================================

    @Test
    void addNote_noSession_returns401() throws Exception {
        mockMvc.perform(post(notesUrl(ORDER_EMPTY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note_text\":\"hello\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void addNote_sessionWithoutStore_returns403() throws Exception {
        mockMvc.perform(post(notesUrl(ORDER_EMPTY))
                        .session(liamSessionNoStore())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note_text\":\"hello\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void addNote_invalidOrderId_returns400() throws Exception {
        mockMvc.perform(post(notesUrl("abc"))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note_text\":\"hello\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
    }

    @Test
    void addNote_crossStoreOrder_returns404() throws Exception {
        mockMvc.perform(post(notesUrl(ORDER_OTHER_STORE))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note_text\":\"hello\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void addNote_nonexistentOrder_returns404() throws Exception {
        mockMvc.perform(post(notesUrl(ORDER_DOES_NOT_EXIST))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note_text\":\"hello\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void addNote_laidOrder_returns201_andInsertsNote() throws Exception {
        // Notes are EXEMPT from the LAID lock — POST is allowed and the row is inserted.
        laidOrder(ORDER_EMPTY);
        mockMvc.perform(post(notesUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note_text\":\"note on a laid order\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Note added."))
                .andExpect(jsonPath("$.data.note.note_text").value("note on a laid order"));

        Assertions.assertEquals(1, countNotes(ORDER_EMPTY));
    }

    @Test
    void addNote_malformedJson_returns400() throws Exception {
        mockMvc.perform(post(notesUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_JSON"));
    }

    @Test
    void addNote_emptyBody_returns400_fieldNoteText() throws Exception {
        mockMvc.perform(post(notesUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("note_text"));
    }

    @Test
    void addNote_emptyObject_returns400_fieldNoteText() throws Exception {
        mockMvc.perform(post(notesUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("note_text"));
    }

    @Test
    void addNote_missingNoteText_returns400_fieldNoteText() throws Exception {
        // note_text present but explicit JSON null counts as missing.
        mockMvc.perform(post(notesUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note_text\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("note_text"));
    }

    @Test
    void addNote_blankNoteText_returns400_fieldNoteText() throws Exception {
        mockMvc.perform(post(notesUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note_text\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("note_text"));
    }

    @Test
    void addNote_unknownField_returns400_andDoesNotInsert() throws Exception {
        mockMvc.perform(post(notesUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note_text\":\"x\",\"foo\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("foo"));
        Assertions.assertEquals(0, countNotes(ORDER_EMPTY));
    }

    @Test
    void addNote_backendControlledOrderId_returns400_andDoesNotInsert() throws Exception {
        mockMvc.perform(post(notesUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note_text\":\"x\",\"order_id\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("order_id"));
        Assertions.assertEquals(0, countNotes(ORDER_EMPTY));
    }

    @Test
    void addNote_backendControlledCreatedAt_returns400_andDoesNotInsert() throws Exception {
        mockMvc.perform(post(notesUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note_text\":\"x\",\"created_at\":\"2020-01-01T00:00:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("created_at"));
        Assertions.assertEquals(0, countNotes(ORDER_EMPTY));
    }

    @Test
    void addNote_backendControlledCreatedByUserId_returns400_andDoesNotInsert() throws Exception {
        mockMvc.perform(post(notesUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note_text\":\"x\",\"created_by_user_id\":7}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("created_by_user_id"));
        Assertions.assertEquals(0, countNotes(ORDER_EMPTY));
    }

    @Test
    void addNote_success_returns201_withDataNoteShapeMessage_andNoLeak() throws Exception {
        mockMvc.perform(post(notesUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note_text\":\"First site note\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Note added."))
                .andExpect(jsonPath("$.data.note.order_note_id").isNumber())
                .andExpect(jsonPath("$.data.note.note_text").value("First site note"))
                .andExpect(jsonPath("$.data.note.created_at").exists())
                .andExpect(jsonPath("$.data.note.order_id").doesNotExist())
                .andExpect(jsonPath("$.data.note.business_id").doesNotExist())
                .andExpect(jsonPath("$.data.note.store_id").doesNotExist())
                .andExpect(jsonPath("$.data.note.user_id").doesNotExist())
                .andExpect(jsonPath("$.data.note.created_by_user_id").doesNotExist());
    }

    @Test
    void addNote_success_persistsRow() throws Exception {
        mockMvc.perform(post(notesUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note_text\":\"Persisted note\"}"))
                .andExpect(status().isCreated());

        Assertions.assertEquals(1, countNotes(ORDER_EMPTY));
        Assertions.assertEquals("Persisted note", latestNoteText(ORDER_EMPTY));
    }

    @Test
    void addNote_storesTrimmedText() throws Exception {
        mockMvc.perform(post(notesUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note_text\":\"  trimmed me  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.note.note_text").value("trimmed me"));

        Assertions.assertEquals("trimmed me", latestNoteText(ORDER_EMPTY));
    }

    @Test
    void addNote_doesNotChangeFinancialFields() throws Exception {
        // Order 1 has non-null financials; adding a note must not recompute or touch them.
        mockMvc.perform(post(notesUrl(ORDER_WITH_NOTE))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note_text\":\"does not affect money\"}"))
                .andExpect(status().isCreated());

        assertMoney("840.00", queryMoney("sale_price_ex_gst", ORDER_WITH_NOTE));
        assertMoney("432.00", queryMoney("total_cost", ORDER_WITH_NOTE));
        assertMoney("408.00", queryMoney("gp", ORDER_WITH_NOTE));
        assertMoney("48.57", queryMoney("gp_percent", ORDER_WITH_NOTE));
        // The note was still appended (order 1 already had the seeded note).
        Assertions.assertEquals(2, countNotes(ORDER_WITH_NOTE));
    }

    // ================================================================
    // Gate ordering (guard → order scope → body)
    // ================================================================

    @Test
    void addNote_noSessionMalformedJson_returns401_notMalformed() throws Exception {
        // Guard runs before the body is parsed.
        mockMvc.perform(post(notesUrl(ORDER_EMPTY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void addNote_crossStoreMalformedJson_returns404_notMalformed() throws Exception {
        // Scoped order lookup runs before the body is parsed.
        mockMvc.perform(post(notesUrl(ORDER_OTHER_STORE))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void addNote_laidInScopeMalformedJson_returns400_notLocked() throws Exception {
        // Notes have NO LAID gate, so a malformed body on a LAID in-scope order is a normal 400
        // MALFORMED_JSON — never 422 ORDER_LOCKED.
        laidOrder(ORDER_EMPTY);
        mockMvc.perform(post(notesUrl(ORDER_EMPTY))
                        .session(liamStore1Session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_JSON"));
    }
}
