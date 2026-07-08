package com.flooring.salesportal.common.sms;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The ONLY Phase 16E-A {@link SmsSender}: records every attempted send in memory and never sends a
 * real SMS — used for local dev and the test suite (mirrors
 * {@code RecordingInvoiceEmailSender} / {@code RecordingQuoteEmailSender}). A plain unconditional
 * {@code @Component}; when a real Twilio sender is added on the Phase 17 deployment branch, that
 * branch introduces the selection mechanism, the dependency, and the credentials — none of which
 * exist in this repo.
 *
 * <p>Test API: {@link #failNextSend()} arms a one-shot failure (the next {@link #send} records the
 * request under {@link #failedMessages()} and throws {@link SmsException}), and {@link #reset()}
 * clears all recorded state + the failure flag. This bean is a singleton whose state survives the
 * per-test transaction rollback, so tests MUST {@code reset()} it in
 * {@code @BeforeEach}/{@code @AfterEach} to avoid cross-test pollution.
 */
@Component
public class RecordingSmsSender implements SmsSender {

    private final List<SmsRequest> sent = Collections.synchronizedList(new ArrayList<>());
    private final List<SmsRequest> failed = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean failNextSend = new AtomicBoolean(false);

    @Override
    public void send(SmsRequest request) {
        if (failNextSend.compareAndSet(true, false)) {
            failed.add(request);
            throw new SmsException("SMS send failed (forced by RecordingSmsSender).");
        }
        sent.add(request);
    }

    /** Arm a one-shot failure: the NEXT send throws (and is recorded under {@link #failedMessages()}). */
    public void failNextSend() {
        failNextSend.set(true);
    }

    /** Snapshot of successfully "sent" messages, in send order. */
    public List<SmsRequest> sentMessages() {
        synchronized (sent) {
            return List.copyOf(sent);
        }
    }

    /** Snapshot of attempts that were forced to fail, in attempt order. */
    public List<SmsRequest> failedMessages() {
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
