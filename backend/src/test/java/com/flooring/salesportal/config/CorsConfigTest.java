package com.flooring.salesportal.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

@ExtendWith(SpringExtension.class)
@SpringBootTest
class CorsConfigTest {

    private static final String ALLOWED_ORIGIN_5173 = "http://localhost:5173";
    private static final String ALLOWED_ORIGIN_5174 = "http://localhost:5174";
    private static final String DISALLOWED_ORIGIN = "http://evil.com";
    private static final String CORS_TARGET_PATH = "/api/v1/some-slug/orders";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // springSecurity() engages the FilterChainProxy so the CORS filter wired via
        // SecurityConfig.cors(...) is actually exercised.
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void preflight_fromAllowedOrigin5173_returnsCorsHeaders() throws Exception {
        MvcResult result = mockMvc.perform(options(CORS_TARGET_PATH)
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN_5173)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus(),
                "Preflight from allowed origin must succeed with 200");
        assertEquals(ALLOWED_ORIGIN_5173,
                result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("true",
                result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        assertAllowedMethodsContainAll(
                result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS));
    }

    @Test
    void preflight_fromAllowedOrigin5174_returnsCorsHeaders() throws Exception {
        MvcResult result = mockMvc.perform(options(CORS_TARGET_PATH)
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN_5174)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus(),
                "Preflight from allowed origin must succeed with 200");
        assertEquals(ALLOWED_ORIGIN_5174,
                result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("true",
                result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        assertAllowedMethodsContainAll(
                result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS));
    }

    @Test
    void preflight_fromDisallowedOrigin_hasNoAllowOriginHeader() throws Exception {
        MvcResult result = mockMvc.perform(options(CORS_TARGET_PATH)
                        .header(HttpHeaders.ORIGIN, DISALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andReturn();

        assertNull(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN),
                "Disallowed origin must NOT receive Access-Control-Allow-Origin");
    }

    @Test
    void normalGet_withAllowedOrigin_carriesAllowOriginHeader() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/some-slug/nonexistent-path")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN_5173))
                .andReturn();

        assertEquals(ALLOWED_ORIGIN_5173,
                result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("true",
                result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void normalGet_withoutOrigin_hasNoAllowOriginHeaderAndIsUnaffected() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/some-slug/nonexistent-path"))
                .andReturn();

        assertNull(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN),
                "Request with no Origin header must NOT receive Access-Control-Allow-Origin");
        assertNull(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS),
                "Request with no Origin header must NOT receive Access-Control-Allow-Credentials");
    }

    private static void assertAllowedMethodsContainAll(String allowMethodsHeader) {
        assertNotNull(allowMethodsHeader, "Access-Control-Allow-Methods must be present");
        for (String method : new String[]{"GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"}) {
            assertTrue(allowMethodsHeader.contains(method),
                    "Access-Control-Allow-Methods must contain " + method
                            + " (was: " + allowMethodsHeader + ")");
        }
    }
}
