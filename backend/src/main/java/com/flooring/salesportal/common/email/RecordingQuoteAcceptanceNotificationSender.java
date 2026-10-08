package com.flooring.salesportal.common.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The ONLY Phase 16F {@link QuoteAcceptanceNotificationSender}: records every attempted store
 * notification in memory and never sends a real email — used for local dev and the test suite
 * (mirrors {@link RecordingQuoteEmailSender}). A plain unconditional {@code @Component}; a real
 * provider (Phase 17) introduces the selection mechanism.
 *
 * <p>Test API: {@link #failNextSend()} arms a one-shot failure (the next {@link #send} records the
 * request under {@link #failedNotifications()} and throws
 * {@link QuoteAcceptanceNotificationException}), and {@link #reset()} clears all recorded state + the
 * failure flag. This bean is a singleton whose state survives the per-test transaction rollback, so
 * tests MUST {@code reset()} it in {@code @BeforeEach}/{@code @AfterEach}.
 *
 * <p>Logging: DEBUG only, and only the order id + quote version number — never the recipient, the
 * body (accepted name / amount) or anything else from the request.
 */
@Component
public class RecordingQuoteAcceptanceNotificationSender implements QuoteAcceptanceNotificationSender {

    private static final Logger log = LoggerFactory.getLogger(RecordingQuoteAcceptanceNotificationSender.class);

    private final List<QuoteAcceptanceNotificationRequest> sent = Collections.synchronizedList(new ArrayList<>());
    private final List<QuoteAcceptanceNotificationRequest> failed = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean failNextSend = new AtomicBoolean(false);

    @Override
    public void send(QuoteAcceptanceNotificationRequest request) {
        if (failNextSend.compareAndSet(true, false)) {
            failed.add(request);
            throw new QuoteAcceptanceNotificationException(
                    "Quote acceptance notification failed (forced by RecordingQuoteAcceptanceNotificationSender).");
        }
        sent.add(request);
        log.debug("Dev-recorded quote acceptance store notification (no real email sent) — order {} quote v{}",
                request.orderId(), request.quoteVersionNumber());
    }

    /** Arm a one-shot failure: the NEXT send throws (and is recorded under {@link #failedNotifications()}). */
    public void failNextSend() {
        failNextSend.set(true);
    }

    /** Snapshot of successfully "sent" store notifications, in send order. */
    public List<QuoteAcceptanceNotificationRequest> sentNotifications() {
        synchronized (sent) {
            return List.copyOf(sent);
        }
    }

    /** Snapshot of attempts that were forced to fail, in attempt order. */
    public List<QuoteAcceptanceNotificationRequest> failedNotifications() {
        synchronized (failed) {
            return List.copyOf(failed);
        }
    }

    /** Clear recorded notifications/failures and disarm {@link #failNextSend()}. */
    public void reset() {
        sent.clear();
        failed.clear();
        failNextSend.set(false);
    }
}
