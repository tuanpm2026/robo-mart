package com.robomart.cart.unit;

import org.junit.jupiter.api.Test;

import com.robomart.cart.entity.CartKeys;
import com.robomart.common.exception.ValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CartKeysTest {

    private static final String UUID_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Test
    void shouldNamespaceUserAndGuestCartsSeparately() {
        assertThat(CartKeys.forUser(UUID_ID)).isEqualTo("u:" + UUID_ID);
        assertThat(CartKeys.forGuest(UUID_ID)).isEqualTo("g:" + UUID_ID);
        assertThat(CartKeys.forUser(UUID_ID)).isNotEqualTo(CartKeys.forGuest(UUID_ID));
    }

    @Test
    void shouldCanonicalizeGuestUuid() {
        assertThat(CartKeys.forGuest(UUID_ID.toUpperCase())).isEqualTo("g:" + UUID_ID);
    }

    @Test
    void shouldRejectNonUuidGuestIds() {
        assertThatThrownBy(() -> CartKeys.forGuest("victim-sub")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> CartKeys.forGuest("u:" + UUID_ID)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> CartKeys.forGuest("1-1-1-1-1")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> CartKeys.forGuest(null)).isInstanceOf(ValidationException.class);
    }

    @Test
    void shouldRejectInvalidUserIds() {
        assertThatThrownBy(() -> CartKeys.forUser(null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> CartKeys.forUser(" ")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> CartKeys.forUser("a".repeat(129))).isInstanceOf(ValidationException.class);
    }

    @Test
    void shouldGenerateRandomGuestKeys() {
        String key = CartKeys.newGuest();
        assertThat(key).startsWith("g:");
        assertThat(CartKeys.forGuest(CartKeys.publicId(key))).isEqualTo(key);
        assertThat(CartKeys.newGuest()).isNotEqualTo(key);
    }

    @Test
    void shouldExposePublicIdWithoutPrefix() {
        assertThat(CartKeys.publicId("u:abc")).isEqualTo("abc");
        assertThat(CartKeys.publicId("g:" + UUID_ID)).isEqualTo(UUID_ID);
        assertThat(CartKeys.publicId("legacy")).isEqualTo("legacy");
        assertThat(CartKeys.publicId(null)).isNull();
    }
}
