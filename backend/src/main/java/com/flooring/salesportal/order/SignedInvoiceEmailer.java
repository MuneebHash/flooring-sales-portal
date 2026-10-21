package com.flooring.salesportal.order;

import com.flooring.salesportal.common.email.InvoiceEmailException;
import com.flooring.salesportal.common.email.InvoiceEmailRequest;
import com.flooring.salesportal.common.email.InvoiceEmailSender;
import com.flooring.salesportal.order.InvoiceRepository.InvoiceRow;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * The post-commit signed-PDF email of a signed invoice version and its success stamp (16G contract §6),
 * shared by every signing or conversion route: in-store D.8 and Path A ({@link OrderInvoiceService}) and,
 * since 16G PR 4, the remote invoice accept ({@link PublicInvoiceService}). Moved out of
 * {@code OrderInvoiceService} in 16G PR 4 with its behaviour unchanged; it takes the business name and
 * the order's own business and store ids (captured by the committing transaction) instead of a session
 * {@code RequestContext}, so the token-only public surface can use it without one. D.9 Resend keeps
 * using {@link #stampEmailSuccess} inside its own stamp transaction.
 *
 * <p>Package-private on purpose: only the invoice services in this package send a signed invoice. Its WARNs
 * go to the CALLER's logger, so the D.8 and Path A lines keep the {@code OrderInvoiceService} category and
 * text they had before the extraction, and the remote accept's lines are {@code PublicInvoiceService}'s.
 */
@Component
class SignedInvoiceEmailer {

    private final InvoiceEmailSender invoiceEmailSender;
    private final InvoiceRepository invoiceRepository;
    private final SalesOrderRepository salesOrderRepository;
    // The success stamp runs in its own short transaction AFTER the signing / conversion commit and the
    // delivery, so it cannot share the caller's (already committed) transaction.
    private final TransactionTemplate transactionTemplate;

    SignedInvoiceEmailer(InvoiceEmailSender invoiceEmailSender,
                         InvoiceRepository invoiceRepository,
                         SalesOrderRepository salesOrderRepository,
                         PlatformTransactionManager transactionManager) {
        this.invoiceEmailSender = invoiceEmailSender;
        this.invoiceRepository = invoiceRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * The post-commit signed-PDF email (16G contract §6). Called only after the signing / conversion
     * transaction has COMMITTED, with what that transaction captured: the appended row, this version's own
     * PDF bytes and file name, the order number, the business name, the order's business and store ids and
     * the trimmed saved customer email (null when the route has no email gate and the customer has no valid
     * email). Nothing here re-reads the recipient or the attachment, and NOTHING here throws (16G contract
     * §6.2):
     * <ul>
     *   <li>no recipient → the send is skipped with a WARN ({@link SignedEmailOutcome#SKIPPED}; Path A and
     *       the remote accept only, D.8 gates the email before persisting);</li>
     *   <li>any sender failure - the transport's {@link InvoiceEmailException} or any other runtime
     *       failure - is WARN-logged ({@link SignedEmailOutcome#FAILED});</li>
     *   <li>after a delivery the existing short stamp transaction ({@link #stampEmailSuccess}) records
     *       {@code last_emailed_at} + the §11.1 mirror; ANY failure of that transaction, its commit
     *       included, is WARN-logged and the committed row is returned instead (both markers stay null,
     *       Re-send stays available) - the outcome is still {@link SignedEmailOutcome#DELIVERED}.</li>
     * </ul>
     * Each WARN goes to {@code log} (the calling service's logger) and carries the order id and the version
     * number only - never the recipient, the body, the exception or its text. The returned row is the
     * response basis. After a recorded delivery it is read
     * in the stamp transaction: for D.8 the stamp's re-read CURRENT row (its existing behaviour, the F49
     * window of contract §6.3, not changed here); with {@code respondWithAppendedVersion} (Path A) the
     * version this request appended, so a version committed by another request between the commit and
     * the stamp never becomes Path A's response. Without a recorded delivery it is the committed row
     * itself (no further database read).
     */
    PostCommitEmail emailAfterCommit(Logger log, long orderId, CommittedSignedInvoice committed,
                                     boolean respondWithAppendedVersion) {
        int versionNumber = committed.row().versionNumber();
        if (committed.recipientEmail() == null) {
            log.warn("Signed invoice email skipped for order {} invoice v{}: no valid saved customer email "
                    + "(non-fatal; markers stay null)", orderId, versionNumber);
            return new PostCommitEmail(SignedEmailOutcome.SKIPPED, committed.row());
        }
        try {
            invoiceEmailSender.send(new InvoiceEmailRequest(
                    committed.recipientEmail(),
                    emailSubject(committed.businessName()),
                    emailBody(committed.orderNumber(), committed.businessName()),
                    committed.pdfBytes(),
                    committed.pdfFileName(),
                    orderId,
                    versionNumber));
        } catch (Exception ex) {
            // The signing / conversion is committed and durable; last_emailed_at stays null on BOTH the
            // invoice and the sales_order mirror, and the 201 message points the user at Re-send.
            log.warn("Signed invoice email failed for order {} invoice v{} (non-fatal; the committed invoice "
                    + "stands, markers stay null)", orderId, versionNumber);
            return new PostCommitEmail(SignedEmailOutcome.FAILED, committed.row());
        }
        try {
            long appendedInvoiceId = committed.row().invoiceId();
            InvoiceRow stamped = transactionTemplate.execute(status -> {
                InvoiceRow current = stampEmailSuccess(orderId, committed.businessId(), committed.storeId(),
                        appendedInvoiceId, LocalDateTime.now());
                if (!respondWithAppendedVersion || current.invoiceId() == appendedInvoiceId) {
                    return current;
                }
                // Path A: another version became current after the conversion committed. The response
                // still describes the version this request created, as stamped just now.
                return invoiceRepository.findByInvoiceId(appendedInvoiceId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Appended invoice disappeared while stamping last_emailed_at for order " + orderId));
            });
            return new PostCommitEmail(SignedEmailOutcome.DELIVERED, stamped != null ? stamped : committed.row());
        } catch (Exception ex) {
            // Delivered but not recorded: the stamp transaction rolled back as a whole, so both markers
            // stay null and the Invoice tab offers Re-send; the response keeps the delivered message.
            log.warn("Email-success stamp failed for order {} invoice v{} after delivery (non-fatal; markers "
                    + "stay null)", orderId, versionNumber);
            return new PostCommitEmail(SignedEmailOutcome.DELIVERED, committed.row());
        }
    }

    /**
     * Email-success stamp (runs inside the CALLER's short transaction): set {@code invoice.last_emailed_at}
     * in place, then set the §11.1 mirror to the CURRENT invoice's value re-read from the database - so
     * both columns carry the SAME timestamp. The order row (scoped to the order's own business and store)
     * is taken {@code FOR UPDATE} FIRST (the same lock every version-creating transaction holds from its
     * first statement), so the current-invoice re-read cannot race a concurrent rewrite / payment and
     * overwrite the mirror with a stale value. Returns the current row (the D.8 and D.9 response basis;
     * Path A responds with the version it appended, see {@link #emailAfterCommit}).
     */
    InvoiceRow stampEmailSuccess(long orderId, long businessId, int storeId, long invoiceId, LocalDateTime sentAt) {
        salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreIdForUpdate(orderId, businessId, storeId)
                .orElseThrow(() -> new IllegalStateException(
                        "Order disappeared while stamping last_emailed_at: " + orderId));
        invoiceRepository.updateInvoiceLastEmailedAt(invoiceId, sentAt);
        InvoiceRow current = invoiceRepository.findCurrentByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException(
                        "Current invoice disappeared while stamping last_emailed_at for order " + orderId));
        invoiceRepository.updateSalesOrderLastEmailedAt(orderId, current.lastEmailedAt());
        return current;
    }

    // Simple MVP wording (Phase 13 contract §9): subject + short body, PDF attached.
    static String emailSubject(String businessName) {
        return "Your invoice from " + businessName;
    }

    static String emailBody(String orderNumber, String businessName) {
        return "Hi,\n\nPlease find your invoice " + orderNumber + " from " + businessName
                + " attached.\n\nThank you.";
    }

    /**
     * A committed signing (D.8, the remote accept) or Path A conversion and everything its post-commit
     * email needs, captured inside the committing transaction (never serialized): the appended row, the
     * order number, the business name, the order's own business and store ids (the stamp's order lock),
     * the trimmed saved customer email (null = no valid email, so the send is skipped) and this version's
     * own PDF bytes and file name.
     */
    record CommittedSignedInvoice(InvoiceRow row, String orderNumber, String businessName,
                                  long businessId, int storeId,
                                  String recipientEmail, byte[] pdfBytes, String pdfFileName) {
    }

    /** The post-commit email outcome (16G contract §6.1); each staff route's 201 message is chosen from it. */
    enum SignedEmailOutcome { DELIVERED, FAILED, SKIPPED }

    /** A post-commit email outcome + the row the response is built from (stamped current, or committed). */
    record PostCommitEmail(SignedEmailOutcome outcome, InvoiceRow responseRow) {
    }
}
