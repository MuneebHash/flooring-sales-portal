package com.flooring.salesportal.common.email;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The ONLY Phase 13B {@link InvoiceEmailSender}: records every attempted send in memory and never
 * sends a real email — used for local dev and the test suite. A plain unconditional {@code @Component}
 * (the repo has no profile/conditional-bean machinery); when a real SMTP/provider sender is added on a
 * later deployment branch, that branch introduces the selection mechanism.
 *
 * <p>Test API: {@link #failNextSend()} arms a one-shot failure (the next {@link #send} records the
 * request under {@link #failedEmails()} and throws {@link InvoiceEmailException}), and {@link #reset()}
 * clears all recorded state + the failure flag. This bean is a singleton whose state survives the
 * per-test transaction rollback, so tests MUST {@code reset()} it in {@code @BeforeEach}/{@code
 * @AfterEach} to avoid cross-test pollution.
 */
@Component
public class RecordingInvoiceEmailSender implements InvoiceEmailSender {

    private final List<InvoiceEmailRequest> sent = Collections.synchronizedList(new ArrayList<>());
    private final List<InvoiceEmailRequest> failed = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean failNextSend = new AtomicBoolean(false);

    @Override
    public void send(InvoiceEmailRequest request) {
        if (failNextSend.compareAndSet(true, false)) {
            failed.add(request);
            throw new InvoiceEmailException("Email send failed (forced by RecordingInvoiceEmailSender).");
        }
        sent.add(request);
    }

    /** Arm a one-shot failure: the NEXT send throws (and is recorded under {@link #failedEmails()}). */
    public void failNextSend() {
        failNextSend.set(true);
    }

    /** Snapshot of successfully "sent" emails, in send order. */
    public List<InvoiceEmailRequest> sentEmails() {
        synchronized (sent) {
            return List.copyOf(sent);
        }
    }

    /** Snapshot of attempts that were forced to fail, in attempt order. */
    public List<InvoiceEmailRequest> failedEmails() {
        synchronized (failed) {
            return List.copyOf(failed);
        }
    }

    /** Clear recorded sends/failures and disarm {@link #failNextSend()}. */
    public void reset() {
        sent.clear();
        failed.clear();
        failNextSend.set(false);
    }
}
