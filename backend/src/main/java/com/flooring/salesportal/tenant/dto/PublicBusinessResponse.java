package com.flooring.salesportal.tenant.dto;

/**
 * Public tenant lookup payload for GET /api/v1/public/businesses/{slug}.
 *
 * <p>Intentionally a hand-written whitelist of the only branding fields that may be served
 * without authentication: business name, logo path/URL, and accent colour. Private tenant
 * config (ABN, bank details, T&amp;Cs, Stripe link, quick-add descriptions, invoice template)
 * is deliberately absent from this record and is exposed only via the authenticated config
 * endpoint (Phase 14C). Global Jackson SNAKE_CASE serialises these as
 * {@code name}, {@code logo_path}, {@code accent_colour}.
 */
public record PublicBusinessResponse(
        String name,
        String logoPath,
        String accentColour
) {
}
