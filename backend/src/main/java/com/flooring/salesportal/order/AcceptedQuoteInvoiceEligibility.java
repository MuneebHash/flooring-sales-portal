package com.flooring.salesportal.order;

import java.time.LocalDateTime;

/**
 * Phase 16F PR2 - decision D5(b) as amended on 8 October 2026: the ONE signature-precedence rule
 * for creating an invoice from the latest accepted quote (Path A). Shared by the conversion endpoint
 * ({@code OrderInvoiceService#createInvoiceFromAcceptedQuote}, evaluated under the order row lock) and
 * the read-only workspace flag {@code accepted.invoice_eligible} ({@code QuoteService#getWorkspace}),
 * so the endpoint and the read can never drift apart.
 *
 * <p>Path A is allowed when the order has NO invoice, when its CURRENT invoice (highest
 * {@code version_number}) is unsigned ({@code accepted_at} null), or when the quote's signature is
 * STRICTLY newer than the current invoice's signature. An equal or older quote signature is refused:
 * the newer signature wins, and a tie never replaces a signed invoice. So the same signed quote cannot
 * be converted again while the current invoice still carries its signature (the conversion copies the
 * quote's {@code accepted_at}, and payment / void versions keep it); after a manual Rewrite (D.2)
 * clears the acceptance, the current invoice is unsigned and the quote is convertible again.
 *
 * <p>Signature timestamps are compared - never invoice creation time, version numbers or email
 * times. A payment- or void-created version carries its original signature time forward, so creating
 * that version later never makes its signature newer. Both timestamps are zone-less wall-clock values
 * ({@code LocalDateTime.now()} into {@code TIMESTAMP} columns), so the ordering is only faithful while
 * the server clock never steps backwards (e.g. a daylight-saving fall-back); running the JVM and the
 * database in UTC is part of the open timezone item (issue #34, Phase 17).
 *
 * <p>This is the signature-precedence rule ONLY. LAID, a newer draft or issued quote version, missing
 * invoice preconditions, a blank frozen details of sale, a zero total and overpayment never feed it;
 * the conversion endpoint validates those separately.
 */
public final class AcceptedQuoteInvoiceEligibility {

    private AcceptedQuoteInvoiceEligibility() {
    }

    /**
     * @param quoteAcceptedAt          the latest accepted quote version's {@code accepted_at}
     * @param currentInvoiceAcceptedAt the current invoice's {@code accepted_at}; {@code null} when the
     *                                 order has no invoice or its current invoice is unsigned
     * @return true when an invoice may be created (appended) from the accepted quote
     */
    public static boolean allows(LocalDateTime quoteAcceptedAt, LocalDateTime currentInvoiceAcceptedAt) {
        if (currentInvoiceAcceptedAt == null) {
            return true;
        }
        // Strict: equality is a refusal. A missing quote signature time can never be "newer".
        return quoteAcceptedAt != null && quoteAcceptedAt.isAfter(currentInvoiceAcceptedAt);
    }
}
