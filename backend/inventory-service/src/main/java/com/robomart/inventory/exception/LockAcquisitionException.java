package com.robomart.inventory.exception;

/**
 * Exception thrown when a distributed lock cannot be acquired within the timeout period.
 */
public class LockAcquisitionException extends RuntimeException {

    private final Long productId;
    private final String lockKey;

    public LockAcquisitionException(Long productId, String lockKey) {
        super(String.format(
                "Failed to acquire distributed lock for product %d: key=%s",
                productId, lockKey
        ));
        this.productId = productId;
        this.lockKey = lockKey;
    }

    public Long getProductId() {
        return productId;
    }

    public String getLockKey() {
        return lockKey;
    }
}
