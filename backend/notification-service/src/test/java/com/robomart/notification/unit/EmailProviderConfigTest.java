package com.robomart.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.robomart.notification.config.EmailProviderConfig;
import com.robomart.notification.service.MockEmailService;

class EmailProviderConfigTest {

    @Test
    void mockProviderIsSelectedExplicitly() {
        assertThat(EmailProviderConfig.create("mock")).isInstanceOf(MockEmailService.class);
        assertThat(EmailProviderConfig.create(" MOCK ")).isInstanceOf(MockEmailService.class);
    }

    @Test
    void unsupportedProviderFailsFastWithExplanation() {
        assertThatThrownBy(() -> EmailProviderConfig.create("smtp"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notification.email.provider='smtp'")
                .hasMessageContaining("Supported providers: [mock]");
    }

    @Test
    void mockEmailServiceDoesNotThrow() {
        new MockEmailService().sendEmail("user@example.com", "Subject", "secret body");
        new MockEmailService().sendEmail(null, "Subject", null);
    }
}
