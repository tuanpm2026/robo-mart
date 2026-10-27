package com.robomart.test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Patched source-built local fixture; no published obsolete MinIO binary is used. */
@TestConfiguration(proxyBeanMethods = false)
public class MinioContainerConfig {
    public static final String BUCKET = "robomart-test-images";
    private static final String ACCESS = "robomart-test";
    private static final String SECRET = "robomart-test-fixture-only";
    private static final GenericContainer<?> MINIO = new GenericContainer<>(new ImageFromDockerfile(
            "robomart/minio:security-9e49d5e", false).withFileFromPath("Dockerfile", dockerfile()))
            .withExposedPorts(9000).withEnv("MINIO_ROOT_USER", ACCESS).withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .waitingFor(Wait.forHttp("/minio/health/ready").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(10)));

    static {
        MINIO.start();
        try (var s3 = client()) {
            s3.createBucket(request -> request.bucket(BUCKET));
            s3.putBucketPolicy(request -> request.bucket(BUCKET).policy("{\"Version\":\"2012-10-17\",\"Statement\":[{"
                    + "\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":[\"s3:GetObject\"],"
                    + "\"Resource\":[\"arn:aws:s3:::" + BUCKET + "/*\"]}]}"));
        }
    }

    private static Path dockerfile() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path file = current.resolve("infra/docker/minio/Dockerfile");
            if (Files.isRegularFile(file)) return file;
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate the committed MinIO fixture Dockerfile");
    }

    public static String endpoint() { return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000); }
    public static String publicBaseUrl() { return endpoint() + "/" + BUCKET; }

    public static S3Client client() {
        return S3Client.builder().endpointOverride(URI.create(endpoint())).region(Region.US_EAST_1)
                .forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS, SECRET)))
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2))
                        .socketTimeout(Duration.ofSeconds(5))).build();
    }

    @Bean
    DynamicPropertyRegistrar imageStorageProperties() {
        return registry -> {
            registry.add("robomart.product.images.endpoint", MinioContainerConfig::endpoint);
            registry.add("robomart.product.images.public-base-url", MinioContainerConfig::publicBaseUrl);
            registry.add("robomart.product.images.bucket", () -> BUCKET);
            registry.add("robomart.product.images.region", () -> "us-east-1");
            registry.add("robomart.product.images.access-key", () -> ACCESS);
            registry.add("robomart.product.images.secret-key", () -> SECRET);
            registry.add("robomart.product.images.path-style", () -> "true");
        };
    }
}
