package com.robomart.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs instead of delivering. Selected by {@code notification.email.provider=mock} (see
 * {@link com.robomart.notification.config.EmailProviderConfig}). Only metadata is logged — the body
 * contains order contents and amounts, and the recipient is masked.
 */
public class MockEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(MockEmailService.class);

    @Override
    public void sendEmail(String to, String subject, String body) {
        log.info("MOCK EMAIL (not delivered) — to={}, subject={}, bodyLength={}",
                maskRecipient(to), subject, body == null ? 0 : body.length());
    }

    /** Keeps the first character (and the e-mail domain, if any) so log lines stay correlatable. */
    static String maskRecipient(String recipient) {
        if (recipient == null || recipient.isEmpty()) {
            return "<none>";
        }
        int at = recipient.indexOf('@');
        String domain = at >= 0 ? recipient.substring(at) : "";
        return recipient.charAt(0) + "***" + domain;
    }
}
