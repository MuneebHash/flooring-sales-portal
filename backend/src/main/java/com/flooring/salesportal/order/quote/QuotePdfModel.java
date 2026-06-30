package com.flooring.salesportal.order.quote;

import java.math.BigDecimal;
import java.util.List;

/**
 * Phase 16C PR2 — flat, pre-resolved data the quote preview template ({@code templates/quote.html})
 * needs. Built by {@link QuotePdfModelAssembler} from the order / customer / billing address / quote
 * draft + tenant invoice config + store + salesperson, then rendered by {@link QuotePdfGenerator}.
 *
 * <p>This is the quote analogue of {@code InvoicePdfGenerator.InvoicePdfModel}, but DELIBERATELY
 * carries NONE of the invoice-only fields — there is no invoice version number, invoice/due date,
 * payment made, balance due, acceptance, signature, accepted timestamp, emailed timestamp, quote
 * version, or quote token. A preview is an on-demand, read-only render of the editable draft; it is
 * never stored and never advances any lifecycle. The header layout fields (business / store /
 * salesperson / logo / terms) and the per-flooring-type terms mirror the invoice document's
 * "Aire Compact" style (title {@code QUOTE}, not {@code TAX INVOICE}).
 *
 * <p>Every optional field is nullable and the template hides it when absent (logo fails soft to the
 * business-name text; blank terms render no terms page). {@code lines} is the ordered draft line set
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
        String termsHtml) {

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
