package com.flooring.salesportal.tenant;

/**
 * Phase 15A — Spring Data interface projection for a business's PRIVATE invoice config.
 *
 * <p>Read exclusively by {@link BusinessRepository#findInvoiceConfigByBusinessId(Long)} via a
 * NATIVE query. These eight invoice-legal/bank/payment columns plus {@code logo_path} live in
 * {@code V12} but are deliberately NOT mapped onto the {@link Business} entity (so the public
 * lookup endpoint cannot leak them). This projection reads the columns straight from the
 * {@code business} table without touching the entity mapping, preserving that leak guard.
 *
 * <p>Every getter returns {@code String}: the eight invoice-legal/bank/payment fields are
 * nullable; {@code invoice_template_key} is NOT NULL (DB default {@code 'standard'}). Getter
 * names map 1:1 to the quoted native-SQL aliases in the repository query.
 */
public interface BusinessInvoiceConfigView {

    String getAbn();

    String getBankName();

    String getBsb();

    String getAccountNumber();

    String getAccountName();

    String getTermsAndConditions();

    String getLogoPath();

    String getStripePaymentLinkUrl();

    String getInvoiceTemplateKey();
}
