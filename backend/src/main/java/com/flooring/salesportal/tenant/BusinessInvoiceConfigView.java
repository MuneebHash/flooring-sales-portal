package com.flooring.salesportal.tenant;

/**
 * Phase 15A — Spring Data interface projection for a business's PRIVATE invoice config.
 *
 * <p>Read exclusively by {@link BusinessRepository#findInvoiceConfigByBusinessId(Long)} via a
 * NATIVE query. These invoice-legal/bank/payment columns ({@code V12}) plus the per-flooring-type
 * terms columns {@code terms_hard} / {@code terms_soft} ({@code V13}) and {@code logo_path} are
 * deliberately NOT mapped onto the {@link Business} entity (so the public lookup endpoint cannot
 * leak them). This projection reads the columns straight from the {@code business} table without
 * touching the entity mapping, preserving that leak guard.
 *
 * <p>Every getter returns {@code String}: ten fields are nullable; {@code invoice_template_key}
 * is NOT NULL (DB default {@code 'standard'}). Getter names map 1:1 to the quoted native-SQL
 * aliases in the repository query.
 */
public interface BusinessInvoiceConfigView {

    String getAbn();

    String getBankName();

    String getBsb();

    String getAccountNumber();

    String getAccountName();

    String getTermsAndConditions();

    String getTermsHard();

    String getTermsSoft();

    String getLogoPath();

    String getStripePaymentLinkUrl();

    String getInvoiceTemplateKey();
}
