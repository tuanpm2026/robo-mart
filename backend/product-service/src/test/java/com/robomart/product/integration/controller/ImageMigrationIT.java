package com.robomart.product.integration.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

import com.robomart.product.service.ImageMigrationCommand;
import com.robomart.product.service.ImageStorageService;
import com.robomart.test.MinioContainerConfig;
import com.robomart.test.ProductIntegrationTest;
import com.robomart.test.security.TestJwtDecoderConfig;
import software.amazon.awssdk.core.sync.RequestBody;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ProductIntegrationTest
@Import(TestJwtDecoderConfig.class)
class ImageMigrationIT {
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0, 1};
    @TempDir Path source;
    @Autowired ImageStorageService storage;
    @LocalServerPort int port;

    @Test
    void dryRunThenCopyAndRepeatPreserveSourcesAndLegacyHttpUrls() throws Exception {
        String key = "987/" + UUID.randomUUID() + ".jpg";
        Path image = source.resolve(key);
        Files.createDirectories(image.getParent());
        Files.write(image, JPEG);
        Files.writeString(source.resolve("ignored.txt"), "not an image");
        Files.createSymbolicLink(source.resolve("link"), image);
        var dryRun = ImageMigrationCommand.migrate(source, null, MinioContainerConfig.BUCKET, false);
        assertThat(dryRun).isEqualTo(new ImageMigrationCommand.Result(1, 0, 0, 2));
        assertThat(storage.exists(key)).isFalse();
        try (var client = MinioContainerConfig.client()) {
            try {
                assertThat(ImageMigrationCommand.migrate(source, client, MinioContainerConfig.BUCKET, true))
                        .isEqualTo(new ImageMigrationCommand.Result(1, 1, 0, 2));
                assertThat(ImageMigrationCommand.migrate(source, client, MinioContainerConfig.BUCKET, true))
                        .isEqualTo(new ImageMigrationCommand.Result(1, 0, 1, 2));
                assertThat(Files.readAllBytes(image)).containsExactly(JPEG);
                var http = RestClient.builder().requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(
                        java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build())).build();
                var redirect = http.get().uri("http://localhost:" + port + "/images/" + key)
                        .exchange((request, response) -> {
                            assertThat(response.getStatusCode().value()).isEqualTo(302);
                            return response.getHeaders().getLocation();
                        });
                assertThat(redirect).hasToString(storage.publicUrl(key));
                assertThat(http.get().uri(redirect).retrieve().body(byte[].class)).containsExactly(JPEG);
            } finally {
                client.deleteObject(request -> request.bucket(MinioContainerConfig.BUCKET).key(key));
            }
        }
    }

    @Test
    void conflictingDestinationIsNeverOverwrittenAndSourceIsRetained() throws Exception {
        String key = "988/" + UUID.randomUUID() + ".jpg";
        Path image = source.resolve(key);
        Files.createDirectories(image.getParent());
        Files.write(image, JPEG);
        byte[] other = JPEG.clone();
        other[4] = 2; // Same length: checksum verification must still refuse the conflict.
        try (var client = MinioContainerConfig.client()) {
            try {
                client.putObject(request -> request.bucket(MinioContainerConfig.BUCKET).key(key), RequestBody.fromBytes(other));
                assertThatThrownBy(() -> ImageMigrationCommand.migrate(source, client, MinioContainerConfig.BUCKET, true))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("checksum differs");
                assertThat(client.getObjectAsBytes(request -> request.bucket(MinioContainerConfig.BUCKET).key(key)).asByteArray())
                        .containsExactly(other);
                assertThat(Files.readAllBytes(image)).containsExactly(JPEG);
            } finally {
                client.deleteObject(request -> request.bucket(MinioContainerConfig.BUCKET).key(key));
            }
        }
    }
}
