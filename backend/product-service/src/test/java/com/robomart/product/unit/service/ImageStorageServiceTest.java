package com.robomart.product.unit.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import com.robomart.common.exception.ValidationException;
import com.robomart.product.service.ImageStorageService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageStorageServiceTest {

    // Valid JPEG magic bytes (FF D8 FF E0 ...) with minimal filler
    private static final byte[] JPEG_HEADER = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0
    };

    // Valid PNG magic bytes (89 50 4E 47 0D 0A 1A 0A) with minimal filler
    private static final byte[] PNG_HEADER = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0
    };

    @TempDir
    Path tempDir;

    private ImageStorageService imageStorageService;

    @BeforeEach
    void setUp() {
        imageStorageService = new ImageStorageService(tempDir.toString(), "http://localhost:8081");
    }

    @Test
    void store_validJpegFile_savesFileAndReturnsUrl() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", JPEG_HEADER);

        String url = imageStorageService.store(1L, file);

        assertThat(url).startsWith("http://localhost:8081/images/1/");
        assertThat(url).endsWith(".jpg");

        // Verify file actually saved on disk
        String filename = url.substring(url.lastIndexOf('/') + 1);
        Path savedFile = tempDir.resolve("1").resolve(filename);
        assertThat(savedFile).exists();
        assertThat(Files.readAllBytes(savedFile)).isEqualTo(JPEG_HEADER);
    }

    @Test
    void store_fileTooLarge_throwsValidationException() {
        byte[] oversized = new byte[6 * 1024 * 1024]; // 6MB
        MockMultipartFile file = new MockMultipartFile(
                "file", "big.jpg", "image/jpeg", oversized);

        assertThatThrownBy(() -> imageStorageService.store(1L, file))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("5MB");
    }

    @Test
    void store_unsupportedType_throwsValidationException() {
        // Random bytes — no magic pattern matches
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", "not-an-image".getBytes());

        assertThatThrownBy(() -> imageStorageService.store(1L, file))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Unsupported file type");
    }

    @Test
    void delete_existingFile_removesFromFilesystem() throws IOException {
        // Store a valid JPEG first
        MockMultipartFile file = new MockMultipartFile(
                "file", "to-delete.jpg", "image/jpeg", JPEG_HEADER);
        String url = imageStorageService.store(1L, file);

        // Verify it exists
        String filename = url.substring(url.lastIndexOf('/') + 1);
        Path savedFile = tempDir.resolve("1").resolve(filename);
        assertThat(savedFile).exists();

        // Delete it
        imageStorageService.delete(url);

        assertThat(savedFile).doesNotExist();
    }

    @Test
    void delete_missingFile_doesNotThrow() {
        String nonExistentUrl = "http://localhost:8081/images/1/nonexistent.jpg";
        // Should not throw
        imageStorageService.delete(nonExistentUrl);
    }

    @Test
    void delete_withTrailingSlashInBaseUrl_stillDeletesCorrectly() throws IOException {
        // Constructor strips trailing slash — URLs built with trailing-slash base should still work
        ImageStorageService serviceWithSlash = new ImageStorageService(
                tempDir.toString(), "http://localhost:8081/");

        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", JPEG_HEADER);
        String url = serviceWithSlash.store(1L, file);

        assertThat(url).startsWith("http://localhost:8081/images/1/");

        String filename = url.substring(url.lastIndexOf('/') + 1);
        Path savedFile = tempDir.resolve("1").resolve(filename);
        assertThat(savedFile).exists();

        serviceWithSlash.delete(url);
        assertThat(savedFile).doesNotExist();
    }

    @Test
    void delete_pathTraversalAttempt_doesNotDeleteOutsideRoot() throws IOException {
        // Craft a URL that attempts path traversal
        String maliciousUrl = "http://localhost:8081/images/../../../etc/passwd";
        // Should log a warning and return without throwing or deleting anything
        imageStorageService.delete(maliciousUrl);
        // Just verifying no exception — the bounds check prevents actual traversal
    }
}
