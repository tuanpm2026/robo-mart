package com.robomart.product.service;

import java.time.Duration;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.robomart.common.exception.ValidationException;

/** A bounded, retryable page is the unit of reindex work; callers continue with nextAfterId. */
@Service
public class ProductReindexService {
    private final ProductProjectionStore store;
    private final ProductIndexWriter writer;

    public ProductReindexService(ProductProjectionStore store, ProductIndexWriter writer) {
        this.store = store;
        this.writer = writer;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Result page(long afterId, int size) {
        if (afterId < 0 || size < 1 || size > 100) throw new ValidationException("Reindex requires afterId >= 0 and size 1..100");
        var snapshots = store.page(afterId, size + 1);
        long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        long next = afterId;
        int processed = 0;
        for (var snapshot : snapshots) {
            if (processed == size || System.nanoTime() >= until) break;
            writer.write(snapshot);
            next = snapshot.getId();
            processed++;
        }
        return new Result(processed, next, snapshots.size() > processed);
    }

    public record Result(int processed, long nextAfterId, boolean hasNext) { }
}
