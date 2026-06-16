package com.flooring.salesportal.tenant.dto;

/**
 * Phase 15A — authenticated tenant invoice-config payload for
 * {@code GET /api/v1/{slug}/invoice-config}.
 *
 * <p>A hand-written whitelist of the per-tenant invoice-legal / bank / payment fields a
 * salesperson may read. Eight fields are nullable; {@code invoiceTemplateKey} is always
 * non-null (DB default {@code 'standard'}). Global Jackson {@code SNAKE_CASE} serialises these
 * as {@code abn}, {@code bank_name}, {@code bsb}, {@code account_number}, {@code account_name},
 * {@code terms_and_conditions}, {@code logo_path}, {@code stripe_payment_link_url},
 * {@code invoice_template_key}.
 *
 * <p>Deliberately EXCLUDES {@code business_id}/internal ids, {@code name}, {@code slug},
 * {@code accent_colour} (app-chrome branding, not invoice data), {@code is_active}, and any
 * store / user / order / product / cost data. {@code logo_path} is included intentionally: it is
 * already public (branding) and is needed for invoice rendering in a later Phase 15 branch.
 */
public record TenantInvoiceConfigResponse(
        String abn,
        String bankName,
        String bsb,
        String accountNumber,
        String accountName,
        String termsAndConditions,
        String logoPath,
        String stripePaymentLinkUrl,
        String invoiceTemplateKey
) {
}
