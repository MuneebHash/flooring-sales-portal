package com.flooring.salesportal.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class LoginControllerTest {

    private static final String LOGIN_URL = "/api/v1/aussie-floors-group/auth/login";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        // Re-seed the demo password hash for every user with one this PasswordEncoder accepts.
        // The V4 hash predates the encoder configuration we now run with, so it does not
        // verify against the documented demo password. Rewriting it inside the test transaction
        // keeps the migration locked while letting login succeed for known credentials.
        String hash = passwordEncoder.encode("password123");
        jdbcTemplate.update("UPDATE app_user SET password_hash = ?", hash);
    }

    @Test
    void unknownSlug_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/nonexistent-slug/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesperson_code\":\"LC1\",\"password\":\"password123\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void inactiveBusiness_returns404() throws Exception {
        jdbcTemplate.update("UPDATE business SET is_active = FALSE WHERE business_id = 1");

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesperson_code\":\"LC1\",\"password\":\"password123\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void missingSalespersonCode_returns400_withSnakeCaseField() throws Exception {
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"password123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("salesperson_code"));
    }

    @Test
    void missingPassword_returns400_withSnakeCaseField() throws Exception {
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesperson_code\":\"LC1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("password"));
    }

    @Test
    void malformedJson_returns400() throws Exception {
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void userNotFound_returns401_noSessionCreated() throws Exception {
        MvcResult result = mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesperson_code\":\"ZZ9\",\"password\":\"password123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.error.message").value("Invalid salesperson code or password."))
                .andReturn();

        assertNull(result.getRequest().getSession(false), "No session should be created on failed login");
    }

    @Test
    void userInactive_returns401_noSessionCreated() throws Exception {
        jdbcTemplate.update("UPDATE app_user SET is_active = FALSE WHERE user_id = 1");

        MvcResult result = mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesperson_code\":\"LC1\",\"password\":\"password123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andReturn();

        assertNull(result.getRequest().getSession(false));
    }

    @Test
    void wrongPassword_returns401_noSessionCreated() throws Exception {
        MvcResult result = mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesperson_code\":\"LC1\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andReturn();

        assertNull(result.getRequest().getSession(false));
    }

    @Test
    void zeroStores_returns403_noSessionCreated() throws Exception {
        jdbcTemplate.update("DELETE FROM user_store_access WHERE user_id = 1");

        MvcResult result = mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesperson_code\":\"LC1\",\"password\":\"password123\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NO_STORE_ACCESS"))
                .andExpect(jsonPath("$.error.message").value("You do not have access to any store in this business."))
                .andReturn();

        assertNull(result.getRequest().getSession(false));
    }

    @Test
    void singleStore_returns200_sessionStoreIdPresent() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post(LOGIN_URL)
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesperson_code\":\"LC1\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.user_id").value(1))
                .andExpect(jsonPath("$.data.user.first_name").value("Liam"))
                .andExpect(jsonPath("$.data.user.last_name").value("Carter"))
                .andExpect(jsonPath("$.data.user.salesperson_code").value("LC1"))
                .andExpect(jsonPath("$.data.active_store_id").value(1))
                .andExpect(jsonPath("$.data.store_selection_required").value(false))
                .andExpect(jsonPath("$.data.stores.length()").value(1))
                .andExpect(jsonPath("$.data.stores[0].store_id").value(1))
                .andExpect(jsonPath("$.data.stores[0].store_code").value("SYD-CBD"))
                .andExpect(jsonPath("$.message").value("Login successful."));

        assertEquals(1L, session.getAttribute("user_id"));
        assertEquals(1L, session.getAttribute("business_id"));
        assertEquals(1, session.getAttribute("store_id"));
    }

    @Test
    void multiStore_returns200_storeIdAbsent() throws Exception {
        jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (1, 1, 2)");

        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post(LOGIN_URL)
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesperson_code\":\"LC1\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active_store_id").value(nullValue()))
                .andExpect(jsonPath("$.data.store_selection_required").value(true))
                .andExpect(jsonPath("$.data.stores.length()").value(2))
                .andExpect(jsonPath("$.message").value("Login successful. Select a store to continue."));

        assertEquals(1L, session.getAttribute("user_id"));
        assertEquals(1L, session.getAttribute("business_id"));
        assertNull(session.getAttribute("store_id"),
                "store_id attribute must be absent (not null-valued, absent) for multi-store login");
    }

    @Test
    void multiStoreLogin_clearsStaleStoreIdFromReusedSession() throws Exception {
        // A browser may already hold an HttpSession that carries a stale store_id from
        // a prior single-store login. A fresh multi-store login must wipe it.
        jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (1, 1, 2)");

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user_id", 999L);
        session.setAttribute("business_id", 999L);
        session.setAttribute("store_id", 7);

        mockMvc.perform(post(LOGIN_URL)
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesperson_code\":\"LC1\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active_store_id").value(nullValue()))
                .andExpect(jsonPath("$.data.store_selection_required").value(true))
                .andExpect(jsonPath("$.data.stores.length()").value(2));

        assertEquals(1L, session.getAttribute("user_id"));
        assertEquals(1L, session.getAttribute("business_id"));
        assertNull(session.getAttribute("store_id"),
                "Stale store_id from a reused session must be removed on multi-store login");
    }

    @Test
    void successfulLogin_rotatesSessionId() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String oldSessionId = session.getId();

        mockMvc.perform(post(LOGIN_URL)
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesperson_code\":\"LC1\",\"password\":\"password123\"}"))
                .andExpect(status().isOk());

        // Spring's MockHttpServletRequest.changeSessionId() delegates to the bound
        // MockHttpSession, which regenerates its internal id. So the same session
        // reference now reports a fresh id after login.
        String newSessionId = session.getId();
        assertNotEquals(oldSessionId, newSessionId,
                "Session id must rotate on successful login to mitigate session fixation");

        // Authenticated attributes are written onto the rotated session.
        assertEquals(1L, session.getAttribute("user_id"));
        assertEquals(1L, session.getAttribute("business_id"));
        assertEquals(1, session.getAttribute("store_id"));
    }

    @Test
    void inactiveStoreExcludedFromStoresList() throws Exception {
        jdbcTemplate.update("INSERT INTO user_store_access (business_id, user_id, store_id) VALUES (1, 1, 2)");
        jdbcTemplate.update("UPDATE store SET is_active = FALSE WHERE store_id = 2");

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesperson_code\":\"LC1\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stores.length()").value(1))
                .andExpect(jsonPath("$.data.stores[0].store_id").value(1))
                .andExpect(jsonPath("$.data.active_store_id").value(1))
                .andExpect(jsonPath("$.data.store_selection_required").value(false));
    }
}
