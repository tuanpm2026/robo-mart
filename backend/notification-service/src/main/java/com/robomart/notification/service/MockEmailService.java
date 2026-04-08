package com.robomart.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!production")
public class MockEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(MockEmailService.class);

    @Override
    public void sendEmail(String to, String subject, String body) {
        log.info("MOCK EMAIL — To: {}, Subject: {}, Body:\n{}", to, subject, body);
    }
}
