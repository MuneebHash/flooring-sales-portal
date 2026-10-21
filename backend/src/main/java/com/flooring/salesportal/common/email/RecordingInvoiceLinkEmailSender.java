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
 * The ONLY Phase 16G PR 4 {@link InvoiceLinkEmailSender}: records every attempted send in memory and
 * never sends a real email - used for local dev and the test suite (mirrors
 * {@link RecordingQuoteEmailSender}). A plain unconditional {@code @Component}; when a real provider is
 * added (Phase 17), that branch introduces the selection mechanism.
 *
 * <p>Test API: {@link #failNextSend()} arms a one-shot failure (the next {@link #send} records the
 * request under {@link #failedEmails()} and throws {@link InvoiceLinkEmailException}), and
 * {@link #reset()} clears all recorded state + the failure flag. This bean is a singleton whose state
 * survives the per-test transaction rollback, so tests MUST {@code reset()} it in
 * {@code @BeforeEach}/{@code @AfterEach} to avoid cross-test pollution. {@link #failNextSendWith} arms a one-shot
 * failure with any other runtime exception.
 */
@Component
public class RecordingInvoiceLinkEmailSender implements InvoiceLinkEmailSender {

    private static final Logger log = LoggerFactory.getLogger(RecordingInvoiceLinkEmailSender.class);

    private final List<InvoiceLinkEmailRequest> sent = Collections.synchronizedList(new ArrayList<>());
    private final List<InvoiceLinkEmailRequest> failed = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean failNextSend = new AtomicBoolean(false);
    private final AtomicReference<RuntimeException> failNextSendWith = new AtomicReference<>();

    @Override
    public void send(InvoiceLinkEmailRequest request) {
        RuntimeException armedFailure = failNextSendWith.getAndSet(null);
        if (armedFailure != null) {
            failed.add(request);
            throw armedFailure;
        }
        if (failNextSend.compareAndSet(true, false)) {
            failed.add(request);
            throw new InvoiceLinkEmailException(
                    "Invoice link email send failed (forced by RecordingInvoiceLinkEmailSender).");
        }
        sent.add(request);
        // DEV-ONLY visibility (the RecordingQuoteEmailSender rule): the body carries the public
        // /i/{token} BEARER link, so it is logged at DEBUG only - never at INFO or above. Nothing enables
        // DEBUG for this class by default (application.properties does NOT); seeing recorded messages
        // needs an explicit local switch, e.g. run the backend with
        // --logging.level.com.flooring.salesportal.common.email.RecordingInvoiceLinkEmailSender=DEBUG
        // (for example via -Dspring-boot.run.arguments on ./mvnw spring-boot:run). Production configs
        // must NOT enable it.
        log.debug("Dev-recorded invoice link email (no real email sent) - to: {} | subject: {}\n{}",
                request.recipientEmail(), request.subject(), request.bodyText());
    }

    /** Arm a one-shot failure: the NEXT send throws (and is recorded under {@link #failedEmails()}). */
    public void failNextSend() {
        failNextSend.set(true);
    }

    /** Snapshot of successfully "sent" invoice link emails, in send order. */
    public List<InvoiceLinkEmailRequest> sentEmails() {
        synchronized (sent) {
            return List.copyOf(sent);
        }
    }

    /** Snapshot of attempts that were forced to fail, in attempt order. */
    public List<InvoiceLinkEmailRequest> failedEmails() {
        synchronized (failed) {
            return List.copyOf(failed);
        }
    }

    /**
     * Arm a one-shot failure with an arbitrary runtime exception (not the transport exception): the NEXT
     * send records the request under {@link #failedEmails()} and throws {@code failure} - the "any other
     * sender runtime failure" case of the 16G contract.
     */
    public void failNextSendWith(RuntimeException failure) {
        failNextSendWith.set(failure);
    }

    /** Clear recorded sends/failures and disarm both failure hooks. */
    public void reset() {
        sent.clear();
        failed.clear();
        failNextSend.set(false);
        failNextSendWith.set(null);
    }
}
