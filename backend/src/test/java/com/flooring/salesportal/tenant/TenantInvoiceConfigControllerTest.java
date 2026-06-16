package com.flooring.salesportal.tenant;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
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
 * Integration tests for the Phase 15A authenticated tenant invoice-config endpoint:
 * {@code GET /api/v1/{slug}/invoice-config}.
 *
 * <p>Mirrors {@code QuickDescriptionControllerTest}: {@code @SpringBootTest @Transactional},
 * MockMvc via {@code webAppContextSetup} (no security filter — the endpoint relies on
 * {@code RequestContextGuard}), and {@code MockHttpSession} carrying the seeded Liam / business 1 /
 * store 1 identity. All data tweaks go through {@code JdbcTemplate} and roll back with the test
 * transaction, so they are deterministic even against a dirty local DB.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@Transactional
class TenantInvoiceConfigControllerTest {

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
        return "/api/v1/" + slug + "/invoice-config";
    }

    /** Populate every invoice-config column on the given business with known non-null values. */
    private void seedFullConfig(long businessId) {
        jdbcTemplate.update("""
                UPDATE business SET
                    abn                     = '11122233344',
                    bank_name               = 'Test Bank',
                    bsb                     = '062-000',
                    account_number          = '12345678',
                    account_name            = 'Aussie Floors Pty Ltd',
                    terms_and_conditions    = 'Payment due within 7 days.',
                    terms_hard              = 'Hard flooring terms: 25-year structural warranty.',
                    terms_soft              = 'Soft flooring terms: stain warranty applies.',
                    logo_path               = '/uploads/1/branding/logo.png',
                    stripe_payment_link_url = 'https://stripe.example/pay/aussie',
                    invoice_template_key    = 'premium'
                WHERE business_id = ?
                """, businessId);
    }

    /** Null out the 10 nullable invoice-config columns; pin template key to its default. */
    private void seedNullConfig(long businessId) {
        jdbcTemplate.update("""
                UPDATE business SET
                    abn                     = NULL,
                    bank_name               = NULL,
                    bsb                     = NULL,
                    account_number          = NULL,
                    account_name            = NULL,
                    terms_and_conditions    = NULL,
                    terms_hard              = NULL,
                    terms_soft              = NULL,
                    logo_path               = NULL,
                    stripe_payment_link_url = NULL,
                    invoice_template_key    = 'standard'
                WHERE business_id = ?
                """, businessId);
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
    // Happy path — full config, response shape, nulls, template default
    // ================================================================

    @Test
    void authenticated_returnsAllConfigFieldsWithValues() throws Exception {
        seedFullConfig(BUSINESS_AUSSIE);

        mockMvc.perform(get(url(SLUG_AUSSIE)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.abn").value("11122233344"))
                .andExpect(jsonPath("$.data.bank_name").value("Test Bank"))
                .andExpect(jsonPath("$.data.bsb").value("062-000"))
                .andExpect(jsonPath("$.data.account_number").value("12345678"))
                .andExpect(jsonPath("$.data.account_name").value("Aussie Floors Pty Ltd"))
                // Legacy single-block terms (V12) stays in the response for compatibility.
                .andExpect(jsonPath("$.data.terms_and_conditions").value("Payment due within 7 days."))
                // Per-flooring-type terms (V13) are returned under snake_case keys.
                .andExpect(jsonPath("$.data.terms_hard")
                        .value("Hard flooring terms: 25-year structural warranty."))
                .andExpect(jsonPath("$.data.terms_soft")
                        .value("Soft flooring terms: stain warranty applies."))
                .andExpect(jsonPath("$.data.logo_path").value("/uploads/1/branding/logo.png"))
                .andExpect(jsonPath("$.data.stripe_payment_link_url").value("https://stripe.example/pay/aussie"))
                .andExpect(jsonPath("$.data.invoice_template_key").value("premium"));
    }

    @Test
    void response_hasDataObject_andNoPaginationOrExcludedFields() throws Exception {
        seedFullConfig(BUSINESS_AUSSIE);

        mockMvc.perform(get(url(SLUG_AUSSIE)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").exists())
                // No pagination envelope (this is ApiResponse.ok, not a paged list).
                .andExpect(jsonPath("$.pagination").doesNotExist())
                // Excluded identity / branding / status fields must never appear.
                .andExpect(jsonPath("$.data.business_id").doesNotExist())
                .andExpect(jsonPath("$.data.name").doesNotExist())
                .andExpect(jsonPath("$.data.slug").doesNotExist())
                .andExpect(jsonPath("$.data.accent_colour").doesNotExist())
                .andExpect(jsonPath("$.data.is_active").doesNotExist());
    }

    @Test
    void nullableColumns_serializeAsExplicitNull() throws Exception {
        seedNullConfig(BUSINESS_AUSSIE);

        MvcResult result = mockMvc.perform(get(url(SLUG_AUSSIE)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andReturn();

        // Belt-and-braces: assert each nullable field is present as an EXPLICIT null (not omitted).
        // doesNotExist()/exists() cannot distinguish "present-null" from "absent", so check the raw
        // compact JSON body (Jackson emits no spaces) for the literal "key":null tokens.
        String json = result.getResponse().getContentAsString();
        Assertions.assertTrue(json.contains("\"abn\":null"), () -> "abn not explicit null: " + json);
        Assertions.assertTrue(json.contains("\"bank_name\":null"), () -> "bank_name not explicit null: " + json);
        Assertions.assertTrue(json.contains("\"bsb\":null"), () -> "bsb not explicit null: " + json);
        Assertions.assertTrue(json.contains("\"account_number\":null"), () -> "account_number not explicit null: " + json);
        Assertions.assertTrue(json.contains("\"account_name\":null"), () -> "account_name not explicit null: " + json);
        Assertions.assertTrue(json.contains("\"terms_and_conditions\":null"), () -> "terms_and_conditions not explicit null: " + json);
        Assertions.assertTrue(json.contains("\"terms_hard\":null"), () -> "terms_hard not explicit null: " + json);
        Assertions.assertTrue(json.contains("\"terms_soft\":null"), () -> "terms_soft not explicit null: " + json);
        Assertions.assertTrue(json.contains("\"logo_path\":null"), () -> "logo_path not explicit null: " + json);
        Assertions.assertTrue(json.contains("\"stripe_payment_link_url\":null"), () -> "stripe_payment_link_url not explicit null: " + json);
        // invoice_template_key is NOT NULL and must remain a non-null string.
        Assertions.assertTrue(json.contains("\"invoice_template_key\":\"standard\""),
                () -> "invoice_template_key missing or null: " + json);
    }

    @Test
    void invoiceTemplateKey_defaultsToStandard_whenNotCustomized() throws Exception {
        // Reset to the column DEFAULT ('standard') to assert the non-customized value flows through.
        jdbcTemplate.update(
                "UPDATE business SET invoice_template_key = DEFAULT WHERE business_id = ?", BUSINESS_AUSSIE);

        mockMvc.perform(get(url(SLUG_AUSSIE)).session(liamStore1Session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoice_template_key").value("standard"));
    }

    // ================================================================
    // Two-tenant data isolation (positive — against the real query path)
    // ================================================================

    @Test
    void authenticatedRead_isScopedToSessionBusinessOnly() throws Exception {
        // Seed Aussie (business 1) and Premier (business 2) with DIFFERENT sentinel configs, so a
        // scoping bug (reading by slug-of-another-tenant, or returning the wrong row) would surface
        // Premier values in Aussie's response.
        int aussieRows = jdbcTemplate.update("""
                UPDATE business SET
                    abn                     = '11122233344',
                    bank_name               = 'Aussie Isolation Bank',
                    bsb                     = '062-000',
                    account_number          = '11111111',
                    account_name            = 'Aussie Isolation Account',
                    terms_and_conditions    = 'Aussie terms only',
                    terms_hard              = 'Aussie hard terms only',
                    terms_soft              = 'Aussie soft terms only',
                    logo_path               = '/uploads/1/branding/aussie-isolation-logo.png',
                    stripe_payment_link_url = 'https://stripe.example/pay/aussie-isolation',
                    invoice_template_key    = 'aussie-template'
                WHERE business_id = ?
                """, BUSINESS_AUSSIE);
        Assertions.assertEquals(1, aussieRows,
                "Aussie (business 1) seed must affect exactly 1 row");

        // Capture the row count: a no-match UPDATE in Postgres affects 0 rows and does NOT error,
        // so without this guard the no-leak assertions could pass vacuously if business 2 were absent.
        int premierRows = jdbcTemplate.update("""
                UPDATE business SET
                    abn                     = '99988877766',
                    bank_name               = 'Premier Isolation Bank',
                    bsb                     = '999-999',
                    account_number          = '99999999',
                    account_name            = 'Premier Isolation Account',
                    terms_and_conditions    = 'Premier terms must not leak',
                    terms_hard              = 'Premier hard terms must not leak',
                    terms_soft              = 'Premier soft terms must not leak',
                    logo_path               = '/uploads/2/branding/premier-isolation-logo.png',
                    stripe_payment_link_url = 'https://stripe.example/pay/premier-isolation',
                    invoice_template_key    = 'premier-template'
                WHERE business_id = ?
                """, BUSINESS_PREMIER);
        Assertions.assertEquals(1, premierRows,
                "Premier (business 2) seed must affect exactly 1 row, else the no-leak check is vacuous");

        // Auth as Liam / business 1 / store 1 and read Aussie's config.
        MvcResult result = mockMvc.perform(get(url(SLUG_AUSSIE)).session(liamStore1Session()))
                .andExpect(status().isOk())
                // Only Aussie (business 1) values are returned.
                .andExpect(jsonPath("$.data.abn").value("11122233344"))
                .andExpect(jsonPath("$.data.bank_name").value("Aussie Isolation Bank"))
                .andExpect(jsonPath("$.data.account_name").value("Aussie Isolation Account"))
                .andExpect(jsonPath("$.data.terms_hard").value("Aussie hard terms only"))
                .andExpect(jsonPath("$.data.terms_soft").value("Aussie soft terms only"))
                .andExpect(jsonPath("$.data.invoice_template_key").value("aussie-template"))
                .andReturn();

        // Premier (business 2) sentinel values must NEVER appear anywhere in the raw response body.
        String json = result.getResponse().getContentAsString();
        Assertions.assertFalse(json.contains("Premier Isolation Bank"), () -> "bank_name leaked: " + json);
        Assertions.assertFalse(json.contains("Premier Isolation Account"), () -> "account_name leaked: " + json);
        Assertions.assertFalse(json.contains("Premier terms must not leak"), () -> "terms leaked: " + json);
        Assertions.assertFalse(json.contains("Premier hard terms must not leak"), () -> "terms_hard leaked: " + json);
        Assertions.assertFalse(json.contains("Premier soft terms must not leak"), () -> "terms_soft leaked: " + json);
        Assertions.assertFalse(json.contains("premier-template"), () -> "invoice_template_key leaked: " + json);
        Assertions.assertFalse(json.contains("99988877766"), () -> "abn leaked: " + json);
    }
}
