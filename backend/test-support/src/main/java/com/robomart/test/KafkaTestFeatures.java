package com.robomart.test;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.FeatureUpdate;
import org.apache.kafka.clients.admin.UpdateFeaturesOptions;

/** Feature setup only for newly created disposable Testcontainers brokers. */
public final class KafkaTestFeatures {
    private KafkaTestFeatures() { }

    public static void enableConsumerProtocol(String bootstrap) {
        try (var admin = Admin.create(Map.of("bootstrap.servers", bootstrap))) {
            var feature = admin.describeFeatures().featureMetadata().get(10, TimeUnit.SECONDS)
                    .finalizedFeatures().get("group.version");
            if (feature == null || feature.maxVersionLevel() < 1) {
                admin.updateFeatures(Map.of("group.version", new FeatureUpdate((short) 1, FeatureUpdate.UpgradeType.UPGRADE)),
                        new UpdateFeaturesOptions()).all().get(30, TimeUnit.SECONDS);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted configuring disposable Kafka consumer feature", failure);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException("Could not configure disposable Kafka consumer feature", failure);
        }
    }
}
