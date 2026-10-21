package com.flooring.salesportal.common.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The ONLY Phase 16G PR 4 {@link InvoiceAcceptanceNotificationSender}: records every attempted store
 * notification in memory and never sends a real email - used for local dev and the test suite (mirrors
 * {@link RecordingQuoteAcceptanceNotificationSender}). A plain unconditional {@code @Component}; a real
 * provider (Phase 17) introduces the selection mechanism.
 *
 * <p>Test API: {@link #failNextSend()} arms a one-shot failure (the next {@link #send} records the
 * request under {@link #failedNotifications()} and throws
 * {@link InvoiceAcceptanceNotificationException}), and {@link #reset()} clears all recorded state + the
 * failure flag. This bean is a singleton whose state survives the per-test transaction rollback, so
 * tests MUST {@code reset()} it in {@code @BeforeEach}/{@code @AfterEach}.
 *
 * <p>Logging: DEBUG only, and only the order id + invoice version number - never the recipient, the
 * body (accepted name / amount) or anything else from the request.
 */
@Component
public class RecordingInvoiceAcceptanceNotificationSender implements InvoiceAcceptanceNotificationSender {

    private static final Logger log = LoggerFactory.getLogger(RecordingInvoiceAcceptanceNotificationSender.class);

    private final List<InvoiceAcceptanceNotificationRequest> sent = Collections.synchronizedList(new ArrayList<>());
    private final List<InvoiceAcceptanceNotificationRequest> failed = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean failNextSend = new AtomicBoolean(false);
    private final AtomicReference<RuntimeException> failNextSendWith = new AtomicReference<>();

    @Override
    public void send(InvoiceAcceptanceNotificationRequest request) {
        RuntimeException armedFailure = failNextSendWith.getAndSet(null);
        if (armedFailure != null) {
            failed.add(request);
            throw armedFailure;
        }
        if (failNextSend.compareAndSet(true, false)) {
            failed.add(request);
            throw new InvoiceAcceptanceNotificationException(
                    "Invoice acceptance notification failed (forced by RecordingInvoiceAcceptanceNotificationSender).");
        }
        sent.add(request);
        log.debug("Dev-recorded invoice acceptance store notification (no real email sent) - order {} invoice v{}",
                request.orderId(), request.invoiceVersionNumber());
    }

    /** Arm a one-shot failure: the NEXT send throws (and is recorded under {@link #failedNotifications()}). */
    public void failNextSend() {
        failNextSend.set(true);
    }

    /** Snapshot of successfully "sent" store notifications, in send order. */
    public List<InvoiceAcceptanceNotificationRequest> sentNotifications() {
        synchronized (sent) {
            return List.copyOf(sent);
        }
    }

    /** Snapshot of attempts that were forced to fail, in attempt order. */
    public List<InvoiceAcceptanceNotificationRequest> failedNotifications() {
        synchronized (failed) {
            return List.copyOf(failed);
        }
    }

    /**
     * Arm a one-shot failure with an arbitrary runtime exception (not the transport exception): the NEXT
     * send records the request under {@link #failedNotifications()} and throws {@code failure} - the "any other
     * sender runtime failure" case of the 16G contract.
     */
    public void failNextSendWith(RuntimeException failure) {
        failNextSendWith.set(failure);
    }

    /** Clear recorded notifications/failures and disarm both failure hooks. */
    public void reset() {
        sent.clear();
        failed.clear();
        failNextSend.set(false);
        failNextSendWith.set(null);
    }
}
