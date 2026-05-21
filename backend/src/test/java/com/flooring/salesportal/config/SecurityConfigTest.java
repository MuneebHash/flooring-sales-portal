package com.flooring.salesportal.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@ExtendWith(SpringExtension.class)
@SpringBootTest
class SecurityConfigTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void unauthenticatedRequest_isNotBlockedBySpringSecurityDefaults() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/some-slug/nonexistent-path"))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertNotEquals(401, status,
                "Spring Security defaults must not block requests with a 401");

        String wwwAuth = result.getResponse().getHeader("WWW-Authenticate");
        assertTrue(wwwAuth == null || !wwwAuth.toLowerCase().contains("basic"),
                "Stock Spring Security httpBasic challenge must not appear: " + wwwAuth);
    }
}
