package com.flooring.salesportal.tenant;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public tenant lookup: GET /api/v1/public/businesses/{slug}.
 *
 * <p>Proves the endpoint is PUBLIC (no session required) but SAFE: it returns only the
 * branding whitelist (name / logo_path / accent_colour) and never leaks private tenant
 * config, even when those private columns are populated. Unknown, reserved, and inactive
 * slugs resolve to the existing 404 from {@code BusinessSlugResolver}.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class PublicBusinessControllerTest {

    // Seeded (V4): business 1 = "Aussie Floors Group" / slug "aussie-floors-group", active.
    private static final long BUSINESS_AUSSIE = 1L;
    private static final String SLUG_AUSSIE = "aussie-floors-group";
    private static final String PUBLIC_URL = "/api/v1/public/businesses/" + SLUG_AUSSIE;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    // ----------------------------------------------------------------
    // Public + happy path
    // ----------------------------------------------------------------

    @Test
    void getPublicBusiness_withoutLogin_returns200() throws Exception {
        // No session attached: the endpoint must be reachable with no authentication.
        mockMvc.perform(get(PUBLIC_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Aussie Floors Group"));
    }

    @Test
    void response_includesOnlyPublicBrandingFields() throws Exception {
        jdbcTemplate.update(
                "UPDATE business SET logo_path = ?, accent_colour = ? WHERE business_id = ?",
                "/uploads/1/branding/logo.png", "#1B5E20", BUSINESS_AUSSIE);

        mockMvc.perform(get(PUBLIC_URL))
                .andExpect(status().isOk())
                // The three public fields are present with their values.
                .andExpect(jsonPath("$.data.name").value("Aussie Floors Group"))
                .andExpect(jsonPath("$.data.logo_path").value("/uploads/1/branding/logo.png"))
                .andExpect(jsonPath("$.data.accent_colour").value("#1B5E20"))
                // Nothing private appears, even by its snake_case or camelCase name.
                .andExpect(jsonPath("$.data.abn").doesNotExist())
                .andExpect(jsonPath("$.data.bank_name").doesNotExist())
                .andExpect(jsonPath("$.data.bsb").doesNotExist())
                .andExpect(jsonPath("$.data.account_number").doesNotExist())
                .andExpect(jsonPath("$.data.account_name").doesNotExist())
                .andExpect(jsonPath("$.data.terms_and_conditions").doesNotExist())
                .andExpect(jsonPath("$.data.stripe_payment_link_url").doesNotExist())
                .andExpect(jsonPath("$.data.invoice_template_key").doesNotExist())
                // Internal identifiers / status flags must not leak either.
                .andExpect(jsonPath("$.data.business_id").doesNotExist())
                .andExpect(jsonPath("$.data.slug").doesNotExist())
                .andExpect(jsonPath("$.data.is_active").doesNotExist())
                .andExpect(jsonPath("$.data.email_domain").doesNotExist());
    }

    // ----------------------------------------------------------------
    // Private-field leak guard (active assertion, not a passive null-by-default test)
    // ----------------------------------------------------------------

    @Test
    void privateColumnsPopulated_stillAbsentFromPublicResponse() throws Exception {
        // Populate EVERY private column with a non-null sentinel and add a quick-add row, so
        // the absence below proves the whitelist DTO, not merely null seed data.
        jdbcTemplate.update("""
                UPDATE business SET
                    abn                     = '12345678901',
                    bank_name               = 'Sensitive Bank',
                    bsb                     = '123-456',
                    account_number          = '987654321',
                    account_name            = 'Aussie Floors Pty Ltd',
                    terms_and_conditions    = 'SECRET-TERMS-LEAK-CHECK',
                    stripe_payment_link_url = 'https://stripe.example/secret-link',
                    invoice_template_key    = 'premium'
                WHERE business_id = ?
                """, BUSINESS_AUSSIE);
        jdbcTemplate.update(
                "INSERT INTO business_quick_description (business_id, description, sort_order) VALUES (?, ?, 0)",
                BUSINESS_AUSSIE, "SECRET-QUICKADD-LEAK-CHECK");

        MvcResult result = mockMvc.perform(get(PUBLIC_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Aussie Floors Group"))
                // Every private field key must be absent from the response object.
                .andExpect(jsonPath("$.data.abn").doesNotExist())
                .andExpect(jsonPath("$.data.bank_name").doesNotExist())
                .andExpect(jsonPath("$.data.bsb").doesNotExist())
                .andExpect(jsonPath("$.data.account_number").doesNotExist())
                .andExpect(jsonPath("$.data.account_name").doesNotExist())
                .andExpect(jsonPath("$.data.terms_and_conditions").doesNotExist())
                .andExpect(jsonPath("$.data.stripe_payment_link_url").doesNotExist())
                .andExpect(jsonPath("$.data.invoice_template_key").doesNotExist())
                // Quick-add data must not appear under any key.
                .andExpect(jsonPath("$.data.quick_descriptions").doesNotExist())
                .andExpect(jsonPath("$.data.business_quick_description").doesNotExist())
                .andReturn();

        // Belt-and-braces: the raw body must not contain any private VALUE anywhere.
        String json = result.getResponse().getContentAsString();
        Assertions.assertFalse(json.contains("12345678901"), () -> "ABN leaked: " + json);
        Assertions.assertFalse(json.contains("Sensitive Bank"), () -> "bank_name leaked: " + json);
        Assertions.assertFalse(json.contains("123-456"), () -> "bsb leaked: " + json);
        Assertions.assertFalse(json.contains("987654321"), () -> "account_number leaked: " + json);
        Assertions.assertFalse(json.contains("Aussie Floors Pty Ltd"), () -> "account_name leaked: " + json);
        Assertions.assertFalse(json.contains("SECRET-TERMS-LEAK-CHECK"), () -> "terms leaked: " + json);
        Assertions.assertFalse(json.contains("stripe.example"), () -> "stripe link leaked: " + json);
        Assertions.assertFalse(json.contains("SECRET-QUICKADD-LEAK-CHECK"), () -> "quick-add leaked: " + json);
    }

    // ----------------------------------------------------------------
    // 404 cases — reuse the existing BusinessSlugResolver behaviour
    // ----------------------------------------------------------------

    @Test
    void unknownSlug_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/public/businesses/nonexistent-slug"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void reservedSlug_public_returns404() throws Exception {
        // 'public' is a V11 reserved slug and can never be a real business → clean 404.
        mockMvc.perform(get("/api/v1/public/businesses/public"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void inactiveBusiness_returns404() throws Exception {
        jdbcTemplate.update("UPDATE business SET is_active = FALSE WHERE business_id = ?", BUSINESS_AUSSIE);

        mockMvc.perform(get(PUBLIC_URL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
