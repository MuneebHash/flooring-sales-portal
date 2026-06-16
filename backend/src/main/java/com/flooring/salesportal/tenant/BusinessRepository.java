package com.flooring.salesportal.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BusinessRepository extends JpaRepository<Business, Long> {

    Optional<Business> findBySlug(String slug);

    /**
     * Phase 15A — read a business's PRIVATE invoice config (V12 invoice-legal / bank / payment
     * columns + {@code logo_path}) by authenticated business id.
     *
     * <p>NATIVE SQL on purpose: these columns are intentionally UNMAPPED on {@link Business} (the
     * leak guard for the public lookup), so a derived/JPQL query cannot reference them. Native SQL
     * reads them straight from the {@code business} table without mapping them onto the entity.
     *
     * <p>Aliases are DOUBLE-QUOTED so PostgreSQL preserves their camelCase exactly — otherwise an
     * unquoted alias is folded to lower-case and the {@link BusinessInvoiceConfigView} getter
     * binding becomes Hibernate/Spring-Data-version dependent. Quoting makes the projection mapping
     * deterministic.
     */
    @Query(value = """
            SELECT abn                     AS "abn",
                   bank_name               AS "bankName",
                   bsb                     AS "bsb",
                   account_number          AS "accountNumber",
                   account_name            AS "accountName",
                   terms_and_conditions    AS "termsAndConditions",
                   logo_path               AS "logoPath",
                   stripe_payment_link_url AS "stripePaymentLinkUrl",
                   invoice_template_key    AS "invoiceTemplateKey"
            FROM business
            WHERE business_id = :businessId
            """, nativeQuery = true)
    Optional<BusinessInvoiceConfigView> findInvoiceConfigByBusinessId(@Param("businessId") Long businessId);
}
