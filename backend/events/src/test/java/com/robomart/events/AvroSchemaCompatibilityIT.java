package com.robomart.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.FeatureUpdate;
import org.apache.kafka.clients.admin.UpdateFeaturesOptions;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.GroupType;
import org.apache.kafka.common.TopicPartition;

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
import org.testcontainers.containers.Network;
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

    // Shared Docker network so the Schema Registry container can reach the Kafka
    // broker by its network alias (not by the host-side mapped port).
    private static final Network NETWORK = Network.newNetwork();

    // In-network listener: the broker advertises PLAINTEXT://kafka:19092 to peers on
    // the shared Docker network. withListener(host:port) also registers "kafka" as a
    // network alias on the Kafka container, so Schema Registry can resolve it by name.
    private static final String KAFKA_NETWORK_ALIAS = "kafka";
    private static final int KAFKA_NETWORK_PORT = 19092;
    private static final String KAFKA_IN_NETWORK_LISTENER =
            KAFKA_NETWORK_ALIAS + ":" + KAFKA_NETWORK_PORT;

    private static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer("confluentinc/cp-kafka:8.2.4")
                    .withNetwork(NETWORK)
                    .withListener(KAFKA_IN_NETWORK_LISTENER);

    @SuppressWarnings("rawtypes")
    private static GenericContainer schemaRegistry;

    private static HttpClient httpClient;
    private static String schemaRegistryUrl;

    @BeforeAll
    @SuppressWarnings("resource")
    static void startContainers() {
        KAFKA.start();

        schemaRegistry = new GenericContainer<>("confluentinc/cp-schema-registry:8.2.4")
                .withNetwork(NETWORK)
                .withExposedPorts(8081)
                .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
                .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
                // Reach Kafka over the shared network via its alias, NOT KAFKA.getBootstrapServers()
                // (which is the host-side localhost:<mappedPort> and unreachable from inside the bridge).
                .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS",
                        "PLAINTEXT://" + KAFKA_IN_NETWORK_LISTENER)
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
        NETWORK.close();
    }

    @Test
    void emptyClassicGroupMigratesToConsumerProtocolWithoutReplayingCommittedOffsets() throws Exception {
        String topic = "protocol-migration-" + UUID.randomUUID();
        String group = "protocol-group-" + UUID.randomUUID();
        var partition = new TopicPartition(topic, 0);
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            // This is a fresh disposable broker, not an upgrade of any developer/production data.
            var feature = admin.describeFeatures().featureMetadata().get(10, TimeUnit.SECONDS)
                    .finalizedFeatures().get("group.version");
            if (feature == null || feature.maxVersionLevel() < 1) admin.updateFeatures(Map.of("group.version", new FeatureUpdate(
                    (short) 1, FeatureUpdate.UpgradeType.UPGRADE)),
                    new UpdateFeaturesOptions()).all().get(30, TimeUnit.SECONDS);
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1)))
                    .all().get(10, TimeUnit.SECONDS);
            try (var producer = new KafkaProducer<String, String>(Map.of(
                    "bootstrap.servers", KAFKA.getBootstrapServers(), "acks", "all",
                    "key.serializer", "org.apache.kafka.common.serialization.StringSerializer",
                    "value.serializer", "org.apache.kafka.common.serialization.StringSerializer"))) {
                producer.send(new ProducerRecord<>(topic, "first")).get(10, TimeUnit.SECONDS);
                producer.send(new ProducerRecord<>(topic, "second")).get(10, TimeUnit.SECONDS);
            }
            try (var classic = protocolConsumer(group, "classic")) {
                classic.subscribe(List.of(topic));
                var record = nextRecord(classic);
                assertThat(record.value()).isEqualTo("first");
                classic.commitSync(Map.of(partition, new OffsetAndMetadata(record.offset() + 1)));
            }
            try (var modern = protocolConsumer(group, "consumer")) {
                modern.subscribe(List.of(topic));
                var record = nextRecord(modern);
                assertThat(record.value()).isEqualTo("second");
                assertThat(record.offset()).isEqualTo(1);
                modern.commitSync(Map.of(partition, new OffsetAndMetadata(record.offset() + 1)));
                assertThat(admin.describeConsumerGroups(List.of(group)).all()
                        .get(10, TimeUnit.SECONDS).get(group).type())
                        .isEqualTo(GroupType.CONSUMER);
                assertThat(modern.committed(Set.of(partition)).get(partition).offset()).isEqualTo(2);
            }
        }
    }

    private static KafkaConsumer<String, String> protocolConsumer(String group, String protocol) {
        return new KafkaConsumer<>(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(), "group.id", group, "group.protocol", protocol,
                "auto.offset.reset", "earliest", "enable.auto.commit", false, "max.poll.records", 1,
                "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"));
    }

    private static ConsumerRecord<String, String> nextRecord(
            KafkaConsumer<String, String> consumer) {
        long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < until) {
            var records = consumer.poll(Duration.ofMillis(250));
            if (!records.isEmpty()) return records.iterator().next();
        }
        throw new AssertionError("No record arrived within the bounded protocol-migration poll");
    }

    @Test
    void parsesBothLegacyAndExtendedRegistrationResponses() {
        assertThat(extractId("{\"id\":7}")).isEqualTo(7);
        assertThat(extractId("{\"version\":2,\"id\":7,\"guid\":\"metadata\",\"schema\":\"{}\"}")).isEqualTo(7);
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
     * Reads the stable ID field while allowing additional Schema Registry response metadata.
     */
    private static int extractId(String responseBody) {
        var id = tools.jackson.databind.json.JsonMapper.builder().build().readTree(responseBody).path("id");
        assertThat(id.isIntegralNumber()).as("Registration response must contain an integral schema ID").isTrue();
        return id.intValue();
    }
}
