package com.robomart.test;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;

/**
 * Race-tolerant helpers for managing an Elasticsearch index from integration tests.
 *
 * <p>The Elasticsearch Testcontainer is shared (reused) across all product-service integration
 * test classes. Several classes own the lifecycle of the {@code products} index (delete + recreate
 * with the explicit mapping so {@code brand} stays a {@code keyword}), while others merely save
 * documents into it. Under CI ordering/timing the naive {@code exists() -> delete() -> create()}
 * sequence can fail with {@code resource_already_exists_exception}: Elasticsearch acknowledges an
 * index delete before the cluster state has fully propagated, so the immediately-following create
 * collides with the still-present index.
 *
 * <p>{@link #resetIndex(ElasticsearchOperations, Class)} makes index setup deterministic and
 * independent of test execution order by:
 * <ol>
 *   <li>deleting the index and <em>waiting</em> until {@code exists()} reports {@code false}
 *       (so the delete has fully propagated before recreating);</li>
 *   <li>creating the index with its mapping, treating an already-exists outcome as success;</li>
 *   <li>(re)applying the explicit mapping so the field types are guaranteed correct even if the
 *       index had previously been auto-created with dynamic mappings by another test class;</li>
 *   <li>refreshing the index.</li>
 * </ol>
 */
public final class ElasticsearchTestSupport {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchTestSupport.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final long POLL_INTERVAL_MILLIS = 100;

    private ElasticsearchTestSupport() {
    }

    /**
     * Deletes (if present) and recreates the index for {@code documentClass} with its declared
     * mapping, tolerating Elasticsearch eventual-consistency races so the operation is safe to call
     * from {@code @BeforeEach} regardless of which test class ran before.
     */
    public static void resetIndex(ElasticsearchOperations operations, Class<?> documentClass) {
        IndexOperations indexOps = operations.indexOps(documentClass);

        deleteAndAwaitGone(indexOps);
        createTolerant(indexOps);

        // Guarantee the explicit field mapping (e.g. brand as keyword) even if the index already
        // existed with a dynamic mapping created by a document save in another test class.
        try {
            indexOps.putMapping();
        } catch (RuntimeException ex) {
            log.debug("putMapping after index reset was a no-op or failed harmlessly: {}", ex.getMessage());
        }

        indexOps.refresh();
    }

    private static void deleteAndAwaitGone(IndexOperations indexOps) {
        if (indexOps.exists()) {
            try {
                indexOps.delete();
            } catch (RuntimeException ex) {
                // Concurrent/duplicate delete or already-gone — ignore and verify via exists() below.
                log.debug("Index delete during reset failed harmlessly: {}", ex.getMessage());
            }
        }
        awaitUntil(() -> !indexOps.exists(), "index to be deleted");
    }

    private static void createTolerant(IndexOperations indexOps) {
        try {
            indexOps.createWithMapping();
        } catch (RuntimeException ex) {
            if (isResourceAlreadyExists(ex)) {
                // Another actor created the index between our delete and create — treat as success.
                log.debug("Index already existed during reset create; treating as success");
            } else {
                throw ex;
            }
        }
        awaitUntil(indexOps::exists, "index to be created");
    }

    private static boolean isResourceAlreadyExists(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("resource_already_exists_exception")) {
                return true;
            }
        }
        return false;
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition, String description) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("Timed out waiting for " + description);
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + description, ie);
            }
        }
    }
}
