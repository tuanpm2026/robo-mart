package com.robomart.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** HTTP fixtures and read-only database probes restricted to the explicitly selected E2E project. */
public final class E2eSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private E2eSupport() {
    }

    public static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Resolve Docker without relying on the process search path. */
    public static String dockerExecutable() {
        String configured = env("E2E_DOCKER_BINARY", "");
        if (!configured.isBlank()) {
            try {
                Path executable = Path.of(configured);
                if (executable.isAbsolute() && Files.isRegularFile(executable) && Files.isExecutable(executable)) {
                    return executable.toRealPath().toString();
                }
            } catch (InvalidPathException | IOException exception) {
                throw new IllegalStateException(
                        "E2E_DOCKER_BINARY must identify an absolute executable Docker file",
                        exception);
            }
            throw new IllegalStateException(
                    "E2E_DOCKER_BINARY must identify an absolute executable Docker file");
        }
        for (String candidate :
                List.of("/usr/bin/docker", "/usr/local/bin/docker", "/opt/homebrew/bin/docker")) {
            Path executable = Path.of(candidate);
            if (Files.isRegularFile(executable) && Files.isExecutable(executable)) {
                try {
                    return executable.toRealPath().toString();
                } catch (IOException exception) {
                    throw new IllegalStateException(
                            "Cannot resolve the installed Docker executable", exception);
                }
            }
        }
        throw new IllegalStateException("Docker executable not found in standard locations; "
                + "set E2E_DOCKER_BINARY to an absolute executable file");
    }

    public static RestClient api() {
        return client(env("E2E_BASE_URL", "http://localhost:18080"));
    }

    public static RestClient client(String baseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(45));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultStatusHandler(status -> status.isError(), (request, response) -> { })
                .build();
    }

    public static JsonNode json(String body) {
        assertThat(body).as("HTTP response body").isNotBlank();
        return JSON.readTree(body);
    }

    public static String customerToken() {
        return token("CUSTOMER", "testcustomer", "testpassword123");
    }

    public static String adminToken() {
        return token("ADMIN", "demo-admin", "admin123");
    }

    private static String token(String role, String defaultUser, String defaultPassword) {
        String configured = env("E2E_" + role + "_TOKEN", "");
        if (!configured.isBlank()) {
            return configured;
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "password");
        form.add("client_id", env("E2E_CLIENT_ID", "robo-mart-frontend"));
        form.add("username", env("E2E_" + role + "_USERNAME", defaultUser));
        form.add("password", env("E2E_" + role + "_PASSWORD", defaultPassword));
        var response = client(env("E2E_KEYCLOAK_URL", "http://localhost:18180"))
                .post().uri("/realms/robomart/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().toEntity(String.class);
        // Never include the token response, credentials or bearer token in assertion messages.
        assertThat(response.getStatusCode())
                .as("Keycloak %s login status", role)
                .isEqualTo(HttpStatus.OK);
        String accessToken = json(response.getBody()).path("access_token").asString();
        if (accessToken == null || accessToken.isBlank()) {
            throw new AssertionError("Keycloak returned no access token for " + role);
        }
        return accessToken;
    }

    public static Product availableProduct() {
        var response = api().get().uri("/api/v1/products?page=0&size=100")
                .retrieve().toEntity(String.class);
        assertThat(response.getStatusCode()).as("Product catalog status").isEqualTo(HttpStatus.OK);
        JsonNode products = json(response.getBody()).path("data");
        assertThat(products.isArray()).as("Catalog must expose data as an array").isTrue();
        String selectedId = env("E2E_PRODUCT_ID", "");
        for (JsonNode product : products) {
            long id = product.path("id").asLong();
            if (!selectedId.isBlank() && id != Long.parseLong(selectedId)) {
                continue;
            }
            if (Long.parseLong(query("postgres", "inventory_db",
                    "SELECT count(*) FROM inventory_items WHERE product_id=" + id
                            + " AND available_quantity >= 10")) > 0) {
                return new Product(id, product.path("name").asString(),
                        product.path("price").decimalValue());
            }
        }
        throw new AssertionError("No catalog product has sufficient seeded inventory for E2E");
    }

    public static Map<String, Object> orderPayload(Product product, int quantity) {
        return Map.of(
                "items",
                List.of(
                        Map.of(
                                "productId",
                                Long.toString(product.id()),
                                "productName",
                                product.name(),
                                "quantity",
                                quantity,
                                "unitPrice",
                                product.price())),
                "shippingAddress",
                "1 E2E Test St, Austin, TX 75001, US");
    }

    public static void recordSagaEvidence(String scenario,long orderId,String boundary) {
        if(orderId<1) throw new IllegalArgumentException("Invalid evidence order");
        Map<String,Object> evidence=Map.of(
                "scenario",scenario,"orderId",orderId,"boundary",boundary,
                "order",query("postgres","order_db","SELECT status || '|' || orchestrator_owner FROM orders WHERE id="+orderId),
                "workflow",query("postgres","saga_db","SELECT state FROM saga_instances WHERE order_id='"+orderId+"'"),
                "providerCharges",query("postgres","payment_db","SELECT count(*) FROM mock_gateway_operations WHERE request_order_id='"+orderId+"' AND status='COMPLETED'"),
                "payment",query("postgres","payment_db","SELECT status || '|' || amount || '|' || refunded_amount FROM payments WHERE order_id='"+orderId+"'"),
                "reservation",query("postgres","inventory_db","SELECT status FROM reservations WHERE order_id='"+orderId+"'"));
        System.out.println("SAGA_EVIDENCE "+JSON.writeValueAsString(evidence));
    }

    public static void awaitStatus(long orderId, String expected) {
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(
                        () ->
                                assertThat(
                                                query(
                                                        "postgres",
                                                        "order_db",
                                                        "SELECT status FROM orders WHERE id="
                                                                + orderId))
                                        .isEqualTo(expected));
    }

    public static String container(String service) {
        String project = env("E2E_COMPOSE_PROJECT", "");
        if (!project.matches("(?:robomart-e2e|robomart-java25-e2e)(?:-[a-z0-9-]+)?")) {
            throw new IllegalStateException(
                    "E2E_COMPOSE_PROJECT must select an isolated robomart-e2e project");
        }
        if (!service.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("Invalid Compose service name");
        }
        List<String> ids = containers(service);
        if (ids.size() != 1)
            throw new IllegalStateException(
                    "Expected exactly one container for E2E service " + service);
        return ids.getFirst();
    }

    public static List<String> containers(String service) {
        String project = env("E2E_COMPOSE_PROJECT", "");
        if (!"robomart-java25-e2e-resume".equals(project) || !service.matches("[a-z0-9-]+"))
            throw new IllegalArgumentException("Invalid fixture target");
        String output =
                command(
                        List.of(
                                dockerExecutable(),
                                "ps",
                                "-aq",
                                "--filter",
                                "label=com.docker.compose.project=" + project,
                                "--filter",
                                "label=com.docker.compose.service=" + service));
        if (output.isBlank()) return List.of();
        var ids = output.lines().toList();
        if (ids.stream().anyMatch(id -> !id.matches("[a-f0-9]{12,64}")))
            throw new IllegalStateException("Invalid container identity");
        return ids;
    }

    public static void mutateFixture(String service, String id, String action) {
        if (!List.of("checkout-saga-service", "payment-service", "kafka").contains(service)
                || !List.of("kill", "stop", "start").contains(action)
                || !containers(service).contains(id))
            throw new IllegalArgumentException("Unowned fixture mutation");
        command(List.of(dockerExecutable(), action, id));
    }

    public static void configureSagaReplicas(String target, int replicas) {
        if (!List.of("static://payment-service:9095", "static://host.docker.internal:19096")
                        .contains(target)
                || replicas < 1
                || replicas > 2
                || !"robomart-java25-e2e-resume".equals(env("E2E_COMPOSE_PROJECT", "")))
            throw new IllegalArgumentException("Invalid Saga fixture configuration");
        var compose = fixtureCompose();
        var command =
                new ProcessBuilder(
                        dockerExecutable(),
                        "compose",
                        "--env-file",
                        "/dev/null",
                        "-p",
                        "robomart-java25-e2e-resume",
                        "-f",
                        compose.toString(),
                        "up",
                        "-d",
                        "--no-deps",
                        "--wait",
                        "--wait-timeout",
                        "180",
                        "--scale",
                        "checkout-saga-service=" + replicas,
                        "checkout-saga-service");
        command.environment().put("E2E_SAGA_PAYMENT_TARGET", target);
        runConfiguration(command);
    }

    public static void configureAdmission(boolean external) {
        if (!"robomart-java25-e2e-resume".equals(env("E2E_COMPOSE_PROJECT", "")))
            throw new IllegalArgumentException("Invalid admission fixture");
        var compose = fixtureCompose();
        var command =
                new ProcessBuilder(
                        dockerExecutable(),
                        "compose",
                        "--env-file",
                        "/dev/null",
                        "-p",
                        "robomart-java25-e2e-resume",
                        "-f",
                        compose.toString(),
                        "up",
                        "-d",
                        "--no-deps",
                        "--wait",
                        "--wait-timeout",
                        "180",
                        "order-service");
        command.environment().put("E2E_EXTERNAL_SAGA_ENABLED", Boolean.toString(external));
        runConfiguration(command);
    }

    private static Path fixtureCompose() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null) {
            Path compose = root.resolve("infra/docker/docker-compose.e2e.yml");
            if (Files.isRegularFile(compose)
                    && Files.isRegularFile(root.resolve("backend/pom.xml"))) return compose;
            root = root.getParent();
        }
        throw new IllegalStateException("Cannot locate this repository's isolated fixture");
    }

    private static void runConfiguration(ProcessBuilder builder) {
        Path log;
        try {
            log = Files.createTempFile("saga-fixture-config-", ".log");
            try {
                var process =
                        builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
                if (!process.waitFor(200, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IllegalStateException("Fixture startup timed out");
                }
                if (process.exitValue() != 0)
                    throw new IllegalStateException(
                            "Fixture startup failed: " + Files.readString(log));
            } finally {
                Files.deleteIfExists(log);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public static String query(String service, String database, String sql) {
        if (!database.matches("[a-z_]+")
                || !sql.stripLeading().toUpperCase(java.util.Locale.ROOT).startsWith("SELECT ")
                || sql.contains(";")) {
            throw new IllegalArgumentException("E2E database probes accept a single SELECT only");
        }
        String id = container(service);
        // Server-side read-only transactions also prevent side effects through SELECT functions.
        return command(
                List.of(
                        dockerExecutable(),
                        "exec",
                        "-e",
                        "PGOPTIONS=-c default_transaction_read_only=on",
                        id,
                        "sh",
                        "-c",
                        "exec psql -X -v ON_ERROR_STOP=1 -A -t -U \"$POSTGRES_USER\" -d \"$1\" -c"
                            + " \"$2\"",
                        "e2e-read-only",
                        database,
                        sql));
    }

    private static String command(List<String> arguments) {
        try {
            Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("E2E Docker probe timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) {
                throw new IllegalStateException("E2E Docker probe failed: " + output);
            }
            return output;
        } catch (IOException exception) {
            throw new IllegalStateException("Docker is required for E2E probes", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("E2E Docker probe interrupted", exception);
        }
    }

    public record Product(long id, String name, BigDecimal price) {
    }
}
