package com.flooring.salesportal.order.quote;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 16C PR2 — flat, pre-resolved data the quote template ({@code templates/quote.html}) needs.
 * Built by {@link QuotePdfModelAssembler} from the order / customer / billing address / quote
 * draft (preview) or the frozen issued snapshot (issued / signed) + tenant invoice config + store +
 * salesperson, then rendered by {@link QuotePdfGenerator}.
 *
 * <p>This is the quote analogue of {@code InvoicePdfGenerator.InvoicePdfModel}, but DELIBERATELY
 * carries none of the invoice-only fields — there is no invoice version number, invoice/due date,
 * payment made, balance due, emailed timestamp, quote version, or quote token. The rendered document
 * includes a printable "Customer Acceptance" area (two CSS-border declaration squares + the customer
 * signature line) and a display-only deposit line (Phase 16D-C).
 *
 * <p><b>Acceptance (Phase 16F PR1).</b> {@code acceptedAt} / {@code acceptedCustomerName} /
 * {@code signaturePng} are null on the DRAFT preview and the ISSUED PDF (the acceptance area renders
 * BLANK: empty squares + blank signature line). They are set ONLY on the SIGNED quote PDF rendered at
 * remote acceptance: the squares render ticked and the signature image + "Accepted by {name} on
 * {time}" fill the signature line. {@code signaturePng} is the raw PNG bytes; the generator embeds them
 * as a data URI. Previews and issued renders use the unsigned (24-argument) constructor below.
 *
 * <p>Every optional field is nullable and the template hides it when absent (logo fails soft to the
 * business-name text; blank terms render no terms page). {@code lines} is the ordered line set
 * (ITEM and ADJUSTMENT); {@code itemised} selects the itemised line table vs the single-amount
 * presentation. Money is BigDecimal scale 2; the generator formats it for display.
 */
public record QuotePdfModel(
        // Header / business
        String businessName,
        String abn,
        String logoDataUri,
        String flooringTypeLabel,
        // Store
        String storeName,
        String storeAddressLine1,
        String storeAddressLine2,
        String storePhone,
        String storeEmail,
        // Order / customer
        String orderNumber,
        String salespersonName,
        String customerName,
        String billingLine1,
        String billingLine2,
        String detailsOfSale,
        // Quote body
        boolean itemised,
        List<QuotePdfLine> lines,
        BigDecimal quoteTotalExGst,
        BigDecimal quoteTotalIncGst,
        // Bank / payment methods (nullable; hidden when absent)
        String bankName,
        String bsb,
        String accountName,
        String accountNumber,
        // Terms (sanitized HTML; null -> hide. Always rendered on a dedicated page when present)
        String termsHtml,
        // Acceptance (Phase 16F PR1) — null on preview / issued renders; set only on the signed PDF.
        LocalDateTime acceptedAt,
        String acceptedCustomerName,
        byte[] signaturePng) {

    /**
     * The UNSIGNED model (draft preview / issued PDF): every acceptance field null, so the template
     * renders the blank print-and-sign acceptance area exactly as before 16F.
     */
    public QuotePdfModel(String businessName, String abn, String logoDataUri, String flooringTypeLabel,
                         String storeName, String storeAddressLine1, String storeAddressLine2,
                         String storePhone, String storeEmail,
                         String orderNumber, String salespersonName, String customerName,
                         String billingLine1, String billingLine2, String detailsOfSale,
                         boolean itemised, List<QuotePdfLine> lines,
                         BigDecimal quoteTotalExGst, BigDecimal quoteTotalIncGst,
                         String bankName, String bsb, String accountName, String accountNumber,
                         String termsHtml) {
        this(businessName, abn, logoDataUri, flooringTypeLabel,
                storeName, storeAddressLine1, storeAddressLine2, storePhone, storeEmail,
                orderNumber, salespersonName, customerName, billingLine1, billingLine2, detailsOfSale,
                itemised, lines, quoteTotalExGst, quoteTotalIncGst,
                bankName, bsb, accountName, accountNumber,
                termsHtml,
                null, null, null);
    }

    /** True when this is the signed (accepted) rendering. */
    public boolean accepted() {
        return acceptedAt != null;
    }

    /**
     * One draft line for display. {@code lineType} is {@code ITEM} (quantity + unitPriceExGst present)
     * or {@code ADJUSTMENT} (quantity/unitPriceExGst null, signed {@code lineTotalExGst}). The generator
     * formats the money/quantity for the template; no cost is ever carried.
     */
    public record QuotePdfLine(
            String lineType,
            String description,
            BigDecimal quantity,
            BigDecimal unitPriceExGst,
            BigDecimal lineTotalExGst) {
    }
}
