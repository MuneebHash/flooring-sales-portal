package com.flooring.salesportal.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class LogoutControllerTest {

    private static final String LOGOUT_URL = "/api/v1/aussie-floors-group/auth/logout";
    private static final String SELECT_STORE_URL = "/api/v1/aussie-floors-group/auth/select-store";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void noSession_returns401() throws Exception {
        mockMvc.perform(post(LOGOUT_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void unknownSlug_returns404() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user_id", 1L);
        session.setAttribute("business_id", 1L);

        mockMvc.perform(post("/api/v1/nonexistent-slug/auth/logout").session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void crossTenant_returns404() throws Exception {
        MockHttpSession session = new MockHttpSession();
        // Oliver in Premier (business 2), URL slug points at Aussie (business 1)
        session.setAttribute("user_id", 5L);
        session.setAttribute("business_id", 2L);

        mockMvc.perform(post(LOGOUT_URL).session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void validLogout_returns200_clearsSpSessionCookie_andSubsequentRequestIsUnauthorized() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user_id", 1L);
        session.setAttribute("business_id", 1L);
        session.setAttribute("store_id", 1);

        MvcResult result = mockMvc.perform(post(LOGOUT_URL).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.message").value("Logged out."))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        List<String> setCookies = response.getHeaders("Set-Cookie");
        boolean clearedSpSession = setCookies.stream().anyMatch(header ->
                header.contains("SP_SESSION=") && header.toLowerCase().contains("max-age=0"));
        assertTrue(clearedSpSession,
                "Logout must emit a Set-Cookie that clears SP_SESSION with Max-Age=0. Got: " + setCookies);
        boolean leaksSecureFlag = setCookies.stream()
                .filter(header -> header.contains("SP_SESSION="))
                .anyMatch(header -> header.toLowerCase().contains("secure"));
        assertTrue(!leaksSecureFlag,
                "SP_SESSION clear cookie must not set the Secure flag in this branch. Got: " + setCookies);

        // Reusing the same (now invalidated) session must be rejected
        mockMvc.perform(post(SELECT_STORE_URL)
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"store_id\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void logoutWithoutStoreSelected_returns200() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user_id", 1L);
        session.setAttribute("business_id", 1L);

        mockMvc.perform(post(LOGOUT_URL).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out."));
    }
}
