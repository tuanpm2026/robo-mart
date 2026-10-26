package com.robomart.notification.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.robomart.notification.service.EmailService;
import com.robomart.notification.service.MockEmailService;

/**
 * Selects the {@link EmailService} from {@code notification.email.provider} (default {@code mock}).
 * The provider is explicit in every profile — previously the mock was silently excluded from the
 * {@code production} profile, which then failed to start with an unsatisfied dependency.
 *
 * <p>Only {@code mock} exists today. A real provider is not a drop-in: notifications are addressed
 * to the Keycloak user id carried in order/cart events, so it also needs user-id → e-mail
 * resolution. Any other value fails startup with that explanation instead of degrading silently.
 */
@Configuration
public class EmailProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(EmailProviderConfig.class);

    static final String MOCK = "mock";

    @Bean
    public EmailService emailService(@Value("${notification.email.provider:mock}") String provider) {
        return create(provider);
    }

    public static EmailService create(String provider) {
        if (MOCK.equalsIgnoreCase(provider.trim())) {
            log.info("notification.email.provider=mock: emails are logged (metadata only), not delivered");
            return new MockEmailService();
        }
        throw new IllegalStateException("Unsupported notification.email.provider='" + provider
                + "'. Supported providers: [mock]. Real delivery requires an EmailService implementation"
                + " plus user-id to e-mail resolution (notifications are addressed to Keycloak user ids).");
    }
}
