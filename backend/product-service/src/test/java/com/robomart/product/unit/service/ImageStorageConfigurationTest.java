package com.robomart.product.unit.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.robomart.product.config.ImageStorageConfig;
import com.robomart.product.config.ImageStorageProperties;
import com.robomart.product.service.ImageMigrationCommand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageStorageConfigurationTest {
    @TempDir Path directory;

    @Test
    void credentialsMustBePairedAndPublicUrlsMustBeSafeAbsoluteUrls() {
        var properties = new ImageStorageProperties();
        assertThat(properties.isCredentialPairValid()).isTrue();
        properties.setAccessKey("fixture");
        assertThat(properties.isCredentialPairValid()).isFalse();
        assertThatThrownBy(() -> ImageStorageConfig.createClient(properties)).isInstanceOf(IllegalArgumentException.class);
        properties.setSecretKey("fixture-secret");
        assertThat(properties.isCredentialPairValid()).isTrue();
        for (String invalid : new String[]{"", "/images", "file:///tmp/images", "https://user:pass@media.test/images",
                "https://media.test/images?token=secret", "https://media.test/images#fragment", "https://media.test/a/../images", "[invalid"}) {
            properties.setPublicBaseUrl(invalid);
            assertThat(properties.isPublicBaseUrlValid()).as(invalid).isFalse();
        }
        properties.setPublicBaseUrl("https://media.test/images");
        assertThat(properties.isPublicBaseUrlValid()).isTrue();
    }

    @Test
    void defaultIamAndExplicitCompatibleClientsConstructWithoutOpeningConnections() {
        var properties = new ImageStorageProperties();
        try (var client = ImageStorageConfig.createClient(properties)) {
            assertThat(client.serviceName()).isEqualTo("s3");
        }
        properties.setEndpoint("http://localhost:19002");
        properties.setAccessKey("fixture");
        properties.setSecretKey("fixture-only");
        properties.setPathStyle(true);
        try (var client = ImageStorageConfig.createClient(properties)) {
            assertThat(client.serviceName()).isEqualTo("s3");
        }
    }

    @Test
    void migrationDefaultsToSourceOnlyAndRejectsUnexpectedArgumentsOrSymlinkRoots() throws Exception {
        assertThatCode(() -> ImageMigrationCommand.main(new String[]{"--source", directory.toString()})).doesNotThrowAnyException();
        for (String[] args : new String[][]{{}, {"--apply", directory.toString()}, {"--source", directory.toString(), "--delete"}}) {
            assertThatThrownBy(() -> ImageMigrationCommand.main(args)).isInstanceOf(IllegalArgumentException.class);
        }
        Path link = directory.resolve("link");
        Files.createSymbolicLink(link, directory);
        assertThatThrownBy(() -> ImageMigrationCommand.migrate(link, null, "images", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImageMigrationCommand.migrate(directory, null, "images", true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unixBackslashesAreNotReinterpretedAsObjectPathSeparators() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(directory.getFileSystem().getSeparator().equals("/"));
        Files.write(directory.resolve("1\\" + UUID.randomUUID() + ".jpg"), new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});
        assertThat(ImageMigrationCommand.migrate(directory, null, "images", false))
                .isEqualTo(new ImageMigrationCommand.Result(0, 0, 0, 1));
    }

    @Test
    void migrationRejectsInvalidSourceContentWithoutWritingOrDeleting() throws Exception {
        Path image = directory.resolve("1/" + UUID.randomUUID() + ".png");
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});
        assertThatThrownBy(() -> ImageMigrationCommand.migrate(directory, null, "images", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MIME mismatch");
        Files.write(image, new byte[0]);
        assertThatThrownBy(() -> ImageMigrationCommand.migrate(directory, null, "images", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("size");
        assertThat(image).exists();
    }
}
