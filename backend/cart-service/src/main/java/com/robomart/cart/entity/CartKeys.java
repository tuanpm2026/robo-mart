package com.robomart.cart.entity;

import java.util.UUID;

import com.robomart.common.exception.ValidationException;

/**
 * Cart identifiers live in two disjoint Redis namespaces so a guest cart id can never address an
 * authenticated user's cart (and vice versa):
 * <ul>
 *   <li>{@code u:{sub}} — the cart of an authenticated user (JWT subject relayed by the gateway as
 *       {@code X-User-Id}).</li>
 *   <li>{@code g:{uuid}} — an anonymous guest cart identified by a client-held random UUID
 *       ({@code X-Cart-Id}).</li>
 * </ul>
 * The un-prefixed part is the <em>public</em> id that is returned to clients.
 */
public final class CartKeys {

    static final String USER_PREFIX = "u:";
    static final String GUEST_PREFIX = "g:";
    private static final int MAX_USER_ID_LENGTH = 128;

    private CartKeys() {
    }

    public static String forUser(String userId) {
        if (userId == null || userId.isBlank() || userId.length() > MAX_USER_ID_LENGTH) {
            throw new ValidationException("Invalid user id");
        }
        return USER_PREFIX + userId;
    }

    /**
     * @throws ValidationException if {@code guestCartId} is not a canonical UUID
     */
    public static String forGuest(String guestCartId) {
        return GUEST_PREFIX + parseGuestId(guestCartId);
    }

    public static String newGuest() {
        return GUEST_PREFIX + UUID.randomUUID();
    }

    /** Strips the namespace prefix; un-namespaced (legacy) ids are returned unchanged. */
    public static String publicId(String cartKey) {
        if (cartKey == null) {
            return null;
        }
        if (cartKey.startsWith(USER_PREFIX)) {
            return cartKey.substring(USER_PREFIX.length());
        }
        if (cartKey.startsWith(GUEST_PREFIX)) {
            return cartKey.substring(GUEST_PREFIX.length());
        }
        return cartKey;
    }

    private static String parseGuestId(String guestCartId) {
        if (guestCartId == null) {
            throw new ValidationException("Guest cart id must be a UUID");
        }
        try {
            UUID uuid = UUID.fromString(guestCartId);
            String canonical = uuid.toString();
            if (!canonical.equalsIgnoreCase(guestCartId)) {
                throw new ValidationException("Guest cart id must be a UUID");
            }
            return canonical;
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Guest cart id must be a UUID");
        }
    }
}
