package com.flooring.salesportal.common.sms;

/**
 * Everything an {@link SmsSender} needs to deliver one SMS (Phase 16E-A). Pre-resolved by the
 * service — the sender never reads the database. {@code messageText} is the short quote text
 * containing the public quote link (the ONLY place the plaintext token ever appears on the SMS
 * path; it is never persisted and never serialized in a protected API response). {@code orderId} /
 * {@code quoteVersionNumber} are carried for logging and for test assertions against
 * {@link RecordingSmsSender}; they are not part of the delivered message.
 */
public record SmsRequest(
        String recipientMobile,
        String messageText,
        long orderId,
        int quoteVersionNumber) {
}
