package com.robomart.product.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import com.robomart.product.service.ProductIndexLifecycle;

@Component("productSearch")
@ConditionalOnProperty(prefix = "product.search", name = "initialize-index", havingValue = "true", matchIfMissing = true)
public class ProductSearchReadiness implements HealthIndicator {
    private final ProductIndexLifecycle lifecycle;
    public ProductSearchReadiness(ProductIndexLifecycle lifecycle) { this.lifecycle = lifecycle; }

    @Override
    public Health health() {
        String state = lifecycle.state();
        // A network outage still permits the existing reduced PostgreSQL search tier.
        var health = "READY".equals(state) || "POSTGRES_FALLBACK".equals(state) ? Health.up() : Health.outOfService();
        return health.withDetail("projection", state).build();
    }
}
