package com.flooring.salesportal.common.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The ONLY Phase 16E-A {@link QuoteEmailSender}: records every attempted send in memory and never
 * sends a real email — used for local dev and the test suite (mirrors
 * {@link RecordingInvoiceEmailSender}). A plain unconditional {@code @Component}; when a real
 * SMTP/provider sender is added on the Phase 17 deployment branch, that branch introduces the
 * selection mechanism.
 *
 * <p>Test API: {@link #failNextSend()} arms a one-shot failure (the next {@link #send} records the
 * request under {@link #failedEmails()} and throws {@link QuoteEmailException}), and
 * {@link #reset()} clears all recorded state + the failure flag. This bean is a singleton whose
 * state survives the per-test transaction rollback, so tests MUST {@code reset()} it in
 * {@code @BeforeEach}/{@code @AfterEach} to avoid cross-test pollution.
 */
@Component
public class RecordingQuoteEmailSender implements QuoteEmailSender {

    private static final Logger log = LoggerFactory.getLogger(RecordingQuoteEmailSender.class);

    private final List<QuoteEmailRequest> sent = Collections.synchronizedList(new ArrayList<>());
    private final List<QuoteEmailRequest> failed = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean failNextSend = new AtomicBoolean(false);

    @Override
    public void send(QuoteEmailRequest request) {
        if (failNextSend.compareAndSet(true, false)) {
            failed.add(request);
            throw new QuoteEmailException("Quote email send failed (forced by RecordingQuoteEmailSender).");
        }
        sent.add(request);
        // DEV-ONLY visibility (Phase 16E-C): recorded emails are otherwise invisible outside the
        // test API, so surface the message on the backend console — the body carries the public
        // /q/{token} link, making it copyable for manual QA. Logs ONLY what the request already
        // contains (recipient / subject / bodyText). DEBUG, not INFO (Codex round 3): the body is
        // a BEARER link, so it must never reach default-level logs — dev visibility requires the
        // DEBUG level, which the dev application.properties enables explicitly for this class;
        // production configs must NOT.
        log.debug("Dev-recorded quote email (no real email sent) — to: {} | subject: {}\n{}",
                request.recipientEmail(), request.subject(), request.bodyText());
    }

    /** Arm a one-shot failure: the NEXT send throws (and is recorded under {@link #failedEmails()}). */
    public void failNextSend() {
        failNextSend.set(true);
    }

    /** Snapshot of successfully "sent" quote emails, in send order. */
    public List<QuoteEmailRequest> sentEmails() {
        synchronized (sent) {
            return List.copyOf(sent);
        }
    }

    /** Snapshot of attempts that were forced to fail, in attempt order. */
    public List<QuoteEmailRequest> failedEmails() {
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
