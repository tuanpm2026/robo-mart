package com.robomart.product.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import com.robomart.common.exception.ValidationException;
import com.robomart.product.config.ImageStorageProperties;
import com.robomart.product.service.ImageStorageService;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class ImageStorageServiceTest {
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0, 1};
    private S3Client s3;
    private ImageStorageService images;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        var properties = new ImageStorageProperties();
        properties.setBucket("images");
        properties.setPublicBaseUrl("https://media.example.test/images/");
        images = new ImageStorageService(s3, properties, "https://api.example.test/");
    }

    @Test
    void uploadsValidatedBytesWithOwnedRandomKeyMimeAndChecksum() throws IOException {
        String url = images.store(1L, new MockMultipartFile("file", "untrusted.exe", "text/plain", JPEG));
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        var body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(request.capture(), body.capture());
        assertThat(url).startsWith("https://media.example.test/images/1/").endsWith(".jpg");
        assertThat(request.getValue().bucket()).isEqualTo("images");
        assertThat(request.getValue().key()).isEqualTo(url.substring("https://media.example.test/images/".length()));
        assertThat(request.getValue().contentType()).isEqualTo("image/jpeg");
        assertThat(request.getValue().metadata().get("sha256")).hasSize(64);
        assertThat(request.getValue().ifNoneMatch()).isEqualTo("*");
        try (var input = body.getValue().contentStreamProvider().newStream()) {
            assertThat(input.readAllBytes()).containsExactly(JPEG);
        }
    }

    @Test
    void rejectsEmptyOversizedAndUnsupportedContentBeforeAnyObjectWrite() {
        for (byte[] bytes : new byte[][]{new byte[0], new byte[6 * 1024 * 1024], "not-an-image".getBytes()}) {
            assertThatThrownBy(() -> images.store(1L, new MockMultipartFile("file", "fake.jpg", "image/jpeg", bytes)))
                    .isInstanceOf(ValidationException.class);
        }
        assertThatThrownBy(() -> images.store(0L, new MockMultipartFile("file", JPEG)))
                .isInstanceOf(ValidationException.class);
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void supportsPngAndWebpSignaturesWithoutTrustingClientMime() {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        assertThat(images.store(1L, new MockMultipartFile("file", png))).endsWith(".png");
        assertThat(images.store(1L, new MockMultipartFile("file", webp))).endsWith(".webp");
    }

    @Test
    void missingObjectsReturnFalseButProviderErrorsRemainVisible() {
        String key = "1/" + UUID.randomUUID() + ".jpg";
        org.mockito.Mockito.when(s3.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                .thenThrow(software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(404).build())
                .thenThrow(software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(403).build());
        assertThat(images.exists(key)).isFalse();
        assertThatThrownBy(() -> images.exists(key)).isInstanceOf(com.robomart.product.exception.ImageStorageException.class);
        assertThat(images.exists("../unsafe")).isFalse();
        assertThatThrownBy(() -> images.publicUrl("../unsafe")).isInstanceOf(ValidationException.class);
    }

    @Test
    void uploadFailureIsVisibleWhilePostCommitCleanupRemainsBestEffort() {
        var failure = software.amazon.awssdk.core.exception.SdkClientException.create("storage unavailable");
        org.mockito.Mockito.when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(failure);
        assertThatThrownBy(() -> images.store(1L, new MockMultipartFile("file", JPEG)))
                .isInstanceOf(com.robomart.product.exception.ImageStorageException.class).hasCause(failure);
        org.mockito.Mockito.when(s3.deleteObject(any(DeleteObjectRequest.class))).thenThrow(failure);
        org.assertj.core.api.Assertions.assertThatCode(() -> images.delete("https://media.example.test/images/1/"
                + UUID.randomUUID() + ".jpg")).doesNotThrowAnyException();
    }

    @Test
    void deletesOnlyOwnedPublicOrLegacyKeysAndNormalizesTrailingSlashes() {
        String key = "1/" + UUID.randomUUID() + ".png";
        images.delete("https://media.example.test/images/" + key);
        images.delete("https://api.example.test/images/" + key);
        var request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        org.mockito.Mockito.verify(s3, org.mockito.Mockito.times(2)).deleteObject(request.capture());
        assertThat(request.getAllValues()).allSatisfy(value -> {
            assertThat(value.bucket()).isEqualTo("images");
            assertThat(value.key()).isEqualTo(key);
        });
    }

    @Test
    void rejectsTraversalForeignUrlsQueryParametersAndUnownedObjectNames() {
        for (String url : new String[]{null, "https://elsewhere.test/images/1/a.jpg",
                "https://api.example.test/images/../../../etc/passwd",
                "https://media.example.test/images/1/not-owned.jpg",
                "https://media.example.test/images/1/" + UUID.randomUUID() + ".jpg?download=1"}) {
            images.delete(url);
        }
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }
}
