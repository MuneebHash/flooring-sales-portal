package com.flooring.salesportal.auth;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class SelectStoreControllerTest {

    private static final String SELECT_STORE_URL = "/api/v1/aussie-floors-group/auth/select-store";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private MockHttpSession liamMultiStoreSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user_id", 1L);
        session.setAttribute("business_id", 1L);
        return session;
    }

    private MockHttpSession liamSessionWithStore(int storeId) {
        MockHttpSession session = liamMultiStoreSession();
        session.setAttribute("store_id", storeId);
        return session;
    }

    @Test
    void noSession_returns401() throws Exception {
        mockMvc.perform(post(SELECT_STORE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"store_id\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void unknownSlug_returns404() throws Exception {
        MockHttpSession session = liamMultiStoreSession();

        mockMvc.perform(post("/api/v1/nonexistent-slug/auth/select-store")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"store_id\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void crossTenant_returns404() throws Exception {
        MockHttpSession session = new MockHttpSession();
        // Oliver belongs to Premier (business 2); URL slug resolves to Aussie (business 1)
        session.setAttribute("user_id", 5L);
        session.setAttribute("business_id", 2L);

        mockMvc.perform(post(SELECT_STORE_URL)
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"store_id\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void storeNotInBusiness_returns404() throws Exception {
        MockHttpSession session = liamMultiStoreSession();
        // Store 3 belongs to business 2 (Premier), not business 1 (Aussie)
        mockMvc.perform(post(SELECT_STORE_URL)
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"store_id\":3}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void inactiveStore_returns404() throws Exception {
        jdbcTemplate.update("UPDATE store SET is_active = FALSE WHERE store_id = 1");
        MockHttpSession session = liamMultiStoreSession();

        mockMvc.perform(post(SELECT_STORE_URL)
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"store_id\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void noUserStoreAccess_returns403() throws Exception {
        // user 1 has access to store 1 only. Asking for store 2 in same business.
        MockHttpSession session = liamMultiStoreSession();

        mockMvc.perform(post(SELECT_STORE_URL)
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"store_id\":2}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void firstSelection_returns200_andSetsSessionStoreId() throws Exception {
        MockHttpSession session = liamMultiStoreSession();

        mockMvc.perform(post(SELECT_STORE_URL)
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"store_id\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.user_id").value(1))
                .andExpect(jsonPath("$.data.active_store.store_id").value(1))
                .andExpect(jsonPath("$.data.active_store.store_code").value("SYD-CBD"))
                .andExpect(jsonPath("$.message").value("Store selected."));

        assertEquals(1, session.getAttribute("store_id"));
    }

    @Test
    void sameStoreReselect_isIdempotent_noSessionWrites() throws Exception {
        MockHttpSession session = liamSessionWithStore(1);
        Object initialUserId = session.getAttribute("user_id");
        Object initialBusinessId = session.getAttribute("business_id");
        Object initialStoreId = session.getAttribute("store_id");

        mockMvc.perform(post(SELECT_STORE_URL)
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"store_id\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active_store.store_id").value(1));

        assertEquals(initialUserId, session.getAttribute("user_id"));
        assertEquals(initialBusinessId, session.getAttribute("business_id"));
        assertEquals(initialStoreId, session.getAttribute("store_id"));
    }

    @Test
    void differentStoreAttempt_returns409_sessionUnchanged() throws Exception {
        jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (1, 1, 2)");
        MockHttpSession session = liamSessionWithStore(1);
        Object initialStoreId = session.getAttribute("store_id");

        mockMvc.perform(post(SELECT_STORE_URL)
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"store_id\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STORE_ALREADY_SELECTED"))
                .andExpect(jsonPath("$.error.message")
                        .value("A different store is already selected in this session. Log out to switch stores."));

        assertEquals(initialStoreId, session.getAttribute("store_id"));
    }

    @Test
    void missingStoreId_returns400() throws Exception {
        MockHttpSession session = liamMultiStoreSession();

        mockMvc.perform(post(SELECT_STORE_URL)
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("store_id"));
    }

    @Test
    void userLookupFailure_doesNotWriteSessionStoreId() throws Exception {
        // Proves ordering: AuthService.selectStore must complete every read (including the
        // app_user lookup) before it touches the session. We construct a state where every
        // earlier check passes but the final user load returns empty, then assert no
        // store_id was written.
        Long testUserId = 999L;
        jdbcTemplate.update("""
                INSERT INTO app_user
                    (user_id, business_id, first_name, last_name, salesperson_code, email, password_hash)
                VALUES
                    (?, 1, 'Test', 'User', 'TU1', 'tu1@aussiefloors.com.au', 'irrelevant-not-bcrypt-hash')
                """, testUserId);
        jdbcTemplate.update(
                "INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (1, ?, 1)",
                testUserId);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user_id", testUserId);
        session.setAttribute("business_id", 1L);

        // Orphan the access row by removing the user. Drop the composite user FK so the
        // delete is allowed; both DDL and DML happen inside the @Transactional test
        // boundary and roll back at the end.
        jdbcTemplate.execute("ALTER TABLE user_store_access DROP CONSTRAINT fk_user_store_access_user");
        jdbcTemplate.update("DELETE FROM app_user WHERE user_id = ?", testUserId);

        mockMvc.perform(post(SELECT_STORE_URL)
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"store_id\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        assertNull(session.getAttribute("store_id"),
                "store_id must NOT be written when the later app_user lookup fails");
    }

    @Test
    void nonPositiveStoreId_returns400() throws Exception {
        MockHttpSession session = liamMultiStoreSession();

        mockMvc.perform(post(SELECT_STORE_URL)
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"store_id\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("store_id"));

        mockMvc.perform(post(SELECT_STORE_URL)
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"store_id\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }
}
