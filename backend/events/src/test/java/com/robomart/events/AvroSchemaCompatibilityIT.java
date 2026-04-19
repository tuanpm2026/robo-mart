package com.robomart.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.avro.Schema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import com.robomart.events.cart.CartExpiryWarningEvent;
import com.robomart.events.inventory.StockLowAlertEvent;
import com.robomart.events.inventory.StockReleasedEvent;
import com.robomart.events.inventory.StockReservedEvent;
import com.robomart.events.order.OrderCancelledEvent;
import com.robomart.events.order.OrderCreatedEvent;
import com.robomart.events.order.OrderStatusChangedEvent;
import com.robomart.events.payment.PaymentProcessedEvent;
import com.robomart.events.payment.PaymentRefundedEvent;
import com.robomart.events.product.ProductCreatedEvent;
import com.robomart.events.product.ProductDeletedEvent;
import com.robomart.events.product.ProductUpdatedEvent;

/**
 * Integration test: verifies all 13 Avro schemas in the events module can be
 * registered successfully against a real Confluent Schema Registry instance,
 * and that re-registration is idempotent (backward-compatible with itself).
 *
 * Containers are started once for all tests in this class.
 * Run with: ./mvnw verify -pl :events -DskipITs=false
 */
@Testcontainers
class AvroSchemaCompatibilityIT {

    private static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.0");

    @SuppressWarnings("rawtypes")
    private static GenericContainer schemaRegistry;

    private static HttpClient httpClient;
    private static String schemaRegistryUrl;

    @BeforeAll
    @SuppressWarnings("resource")
    static void startContainers() {
        KAFKA.start();

        schemaRegistry = new GenericContainer<>("confluentinc/cp-schema-registry:7.8.0")
                .withExposedPorts(8081)
                .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
                .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
                .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", KAFKA.getBootstrapServers())
                .waitingFor(Wait.forHttp("/subjects").forStatusCode(200));
        schemaRegistry.start();

        schemaRegistryUrl = "http://" + schemaRegistry.getHost()
                + ":" + schemaRegistry.getMappedPort(8081);
        httpClient = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stopContainers() {
        if (schemaRegistry != null) {
            schemaRegistry.stop();
        }
        if (KAFKA.isRunning()) {
            KAFKA.stop();
        }
    }

    @Test
    void allThirteenAvroSchemasRegisterSuccessfully() throws Exception {
        Map<String, Schema> schemas = allSchemas();

        assertThat(schemas).hasSize(13);

        for (Map.Entry<String, Schema> entry : schemas.entrySet()) {
            String subject = entry.getKey();
            Schema schema = entry.getValue();
            int id = registerSchema(subject, schema);
            assertThat(id)
                    .as("Schema '%s' must be assigned a positive ID by Schema Registry", subject)
                    .isPositive();
        }
    }

    @Test
    void reRegistrationIsIdempotent() throws Exception {
        // Re-registering the exact same schema must return the same ID — this is the
        // Schema Registry definition of backward compatibility with itself.
        Map<String, Schema> schemas = allSchemas();

        for (Map.Entry<String, Schema> entry : schemas.entrySet()) {
            String subject = entry.getKey();
            Schema schema = entry.getValue();
            int firstId = registerSchema(subject, schema);
            int secondId = registerSchema(subject, schema);
            assertThat(secondId)
                    .as("Re-registering '%s' must return the same ID (idempotent)", subject)
                    .isEqualTo(firstId);
        }
    }

    @Test
    void allSchemasAreBackwardCompatibleWithRegisteredVersion() throws Exception {
        // Register all schemas first, then check compatibility of each against its
        // already-registered version — must return is_compatible=true.
        Map<String, Schema> schemas = allSchemas();

        for (Map.Entry<String, Schema> entry : schemas.entrySet()) {
            String subject = entry.getKey();
            Schema schema = entry.getValue();
            registerSchema(subject, schema);

            boolean compatible = checkCompatibility(subject, schema);
            assertThat(compatible)
                    .as("Schema '%s' must be backward-compatible with its registered version", subject)
                    .isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Map<String, Schema> allSchemas() {
        Map<String, Schema> schemas = new LinkedHashMap<>();
        schemas.put(subjectFor(BaseEventEnvelope.getClassSchema()),          BaseEventEnvelope.getClassSchema());
        schemas.put(subjectFor(CartExpiryWarningEvent.getClassSchema()),     CartExpiryWarningEvent.getClassSchema());
        schemas.put(subjectFor(StockLowAlertEvent.getClassSchema()),         StockLowAlertEvent.getClassSchema());
        schemas.put(subjectFor(StockReleasedEvent.getClassSchema()),         StockReleasedEvent.getClassSchema());
        schemas.put(subjectFor(StockReservedEvent.getClassSchema()),         StockReservedEvent.getClassSchema());
        schemas.put(subjectFor(OrderCancelledEvent.getClassSchema()),        OrderCancelledEvent.getClassSchema());
        schemas.put(subjectFor(OrderCreatedEvent.getClassSchema()),          OrderCreatedEvent.getClassSchema());
        schemas.put(subjectFor(OrderStatusChangedEvent.getClassSchema()),    OrderStatusChangedEvent.getClassSchema());
        schemas.put(subjectFor(PaymentProcessedEvent.getClassSchema()),      PaymentProcessedEvent.getClassSchema());
        schemas.put(subjectFor(PaymentRefundedEvent.getClassSchema()),       PaymentRefundedEvent.getClassSchema());
        schemas.put(subjectFor(ProductCreatedEvent.getClassSchema()),        ProductCreatedEvent.getClassSchema());
        schemas.put(subjectFor(ProductDeletedEvent.getClassSchema()),        ProductDeletedEvent.getClassSchema());
        schemas.put(subjectFor(ProductUpdatedEvent.getClassSchema()),        ProductUpdatedEvent.getClassSchema());
        return schemas;
    }

    private static String subjectFor(Schema schema) {
        return schema.getFullName() + "-value";
    }

    /**
     * POST /subjects/{subject}/versions
     * Returns the schema ID assigned by Schema Registry.
     */
    private int registerSchema(String subject, Schema schema) throws IOException, InterruptedException {
        String body = toSchemaRegistryPayload(schema);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(schemaRegistryUrl + "/subjects/" + subject + "/versions"))
                .header("Content-Type", "application/vnd.schemaregistry.v1+json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("Schema Registry must accept schema for subject '%s', got: %s", subject, response.body())
                .isEqualTo(200);

        return extractId(response.body());
    }

    /**
     * POST /compatibility/subjects/{subject}/versions/latest
     * Returns true if the schema is compatible with the latest registered version.
     */
    private boolean checkCompatibility(String subject, Schema schema) throws IOException, InterruptedException {
        String body = toSchemaRegistryPayload(schema);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(schemaRegistryUrl + "/compatibility/subjects/" + subject + "/versions/latest"))
                .header("Content-Type", "application/vnd.schemaregistry.v1+json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("Compatibility check for '%s' failed with: %s", subject, response.body())
                .isEqualTo(200);

        // Normalize whitespace around ':' before checking — Schema Registry may emit
        // "\"is_compatible\": true" (with a space) depending on implementation version.
        String normalizedBody = response.body().replaceAll(":\\s+", ":");
        return normalizedBody.contains("\"is_compatible\":true");
    }

    /**
     * Builds the Schema Registry registration payload.
     * The "schema" value must be the JSON-escaped Avro schema string.
     */
    private static String toSchemaRegistryPayload(Schema schema) {
        String schemaJson = schema.toString();
        // JSON-escape the schema string for embedding as a JSON string value
        String escaped = schemaJson
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        return "{\"schema\":\"" + escaped + "\",\"schemaType\":\"AVRO\"}";
    }

    /**
     * Extracts the integer "id" field from a Schema Registry response like {"id":1}.
     */
    private static int extractId(String responseBody) {
        // Response is {"id":<number>}
        int start = responseBody.indexOf("\"id\":") + 5;
        int end = responseBody.indexOf("}", start);
        if (end < 0) {
            end = responseBody.length();
        }
        return Integer.parseInt(responseBody.substring(start, end).trim());
    }
}
