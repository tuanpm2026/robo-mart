package com.robomart.product.integration.controller;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;
import com.robomart.product.service.ProductImageService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.robomart.test.ProductIntegrationTest;
import com.robomart.test.security.TestJwt;
import com.robomart.test.security.TestJwtDecoderConfig;

import org.springframework.context.annotation.Import;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;

@ProductIntegrationTest
@Import(TestJwtDecoderConfig.class)
class AdminProductImageIT {

    // Valid JPEG magic bytes: FF D8 FF E0 (minimum for JPEG detection)
    private static final byte[] JPEG_MAGIC_BYTES = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0
    };

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void overrideStoragePath(DynamicPropertyRegistry registry) {
        registry.add("robomart.product.image-storage-path", tempDir::toString);
        registry.add("robomart.product.image-base-url", () -> "http://localhost");
    }

    @LocalServerPort
    private int port;

    private RestClient restClient;
    private Long productId;
    @Autowired private ProductImageService images;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private String detail() {
        return restClient.get().uri("/api/v1/products/" + productId).retrieve().body(String.class);
    }

    @Test
    void publicImageReadsAndCacheReflectUploadAndCommittedDeletionButPreserveRollback() {
        assertThat(JsonMapper.builder().build().readTree(detail()).path("data").path("images").size()).isZero();
        var uploaded = images.uploadImages(productId, List.of(new MockMultipartFile(
                "file", "image.jpg", "image/jpeg", JPEG_MAGIC_BYTES))).getFirst();
        String path = java.net.URI.create(uploaded.imageUrl()).getPath();
        Path file = tempDir.resolve(path.substring("/images/".length()));
        assertThat(Files.exists(file)).isTrue();
        var anonymous = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { }).build();
        var response = anonymous.get().uri(path).retrieve().toEntity(byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(JPEG_MAGIC_BYTES);
        assertThat(detail()).contains(uploaded.imageUrl());

        var transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> {
            images.deleteImage(productId, uploaded.id());
            assertThat(Files.exists(file)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(Files.exists(file)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_images WHERE id=?", Integer.class, uploaded.id())).isEqualTo(1);
        assertThat(detail()).contains(uploaded.imageUrl());

        transaction.executeWithoutResult(status -> {
            images.deleteImage(productId, uploaded.id());
            assertThat(Files.exists(file)).isTrue();
        });
        assertThat(Files.exists(file)).isFalse();
        assertThat(anonymous.get().uri(path).retrieve().toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(JsonMapper.builder().build().readTree(detail()).path("data").path("images").size()).isZero();
    }

    @Test
    void reorderingImagesEvictsTheCachedDetailAfterCommit() {
        var uploaded = images.uploadImages(productId, List.of(
                new MockMultipartFile("file", "first.jpg", "image/jpeg", JPEG_MAGIC_BYTES),
                new MockMultipartFile("file", "second.jpg", "image/jpeg", JPEG_MAGIC_BYTES)));
        var mapper = JsonMapper.builder().build();
        assertThat(mapper.readTree(detail()).path("data").path("images").get(0).path("id").asLong())
                .isEqualTo(uploaded.get(0).id());
        var response = restClient.put().uri("/api/v1/admin/products/" + productId + "/images/order")
                .contentType(MediaType.APPLICATION_JSON).body(java.util.Map.of("items", List.of(
                        java.util.Map.of("imageId", uploaded.get(1).id(), "displayOrder", 0),
                        java.util.Map.of("imageId", uploaded.get(0).id(), "displayOrder", 1))))
                .retrieve().toBodilessEntity();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(detail()).path("data").path("images").get(0).path("id").asLong())
                .isEqualTo(uploaded.get(1).id());
    }

    @Test
    void uploadRollbackRemovesNewFilesAndLeavesNoImageRow() {
        var files = new java.util.ArrayList<Path>();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var uploaded = images.uploadImages(productId, List.of(new MockMultipartFile(
                    "file", "rollback.jpg", "image/jpeg", JPEG_MAGIC_BYTES))).getFirst();
            Path file = tempDir.resolve(java.net.URI.create(uploaded.imageUrl()).getPath().substring("/images/".length()));
            files.add(file);
            assertThat(Files.exists(file)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(files).allSatisfy(file -> assertThat(Files.exists(file)).isFalse());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_images WHERE product_id=?", Integer.class, productId)).isZero();
    }

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                // Service-level security now requires an ADMIN JWT on /api/v1/admin/**.
                .defaultHeader("Authorization", TestJwt.adminBearer())
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    // Don't throw — we assert status codes directly
                })
                .build();

        // Create a product to use for image tests
        String createBody = """
                {
                  "name": "Image Test Product",
                  "description": "For image upload testing",
                  "categoryId": 1,
                  "price": 29.99,
                  "brand": "TestBrand",
                  "sku": "IMG-IT-%d"
                }
                """.formatted(System.currentTimeMillis() % 100000);

        var createResponse = restClient.post()
                .uri("/api/v1/admin/products")
                .contentType(MediaType.APPLICATION_JSON)
                .body(createBody)
                .retrieve()
                .toEntity(String.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String responseBody = createResponse.getBody();
        String idStr = responseBody.substring(responseBody.indexOf("\"id\":") + 5);
        productId = Long.parseLong(idStr.substring(0, idStr.indexOf(",")));
    }

    @Test
    void shouldUploadImageAndReturn201() {
        var response = restClient.post()
                .uri("/api/v1/admin/products/" + productId + "/images")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(buildMultipartBody("test.jpg", "image/jpeg", JPEG_MAGIC_BYTES))
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("imageUrl");
        assertThat(response.getBody()).contains("displayOrder");
    }

    @Test
    void shouldReturn400WhenFileTypeInvalid() {
        var response = restClient.post()
                .uri("/api/v1/admin/products/" + productId + "/images")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(buildMultipartBody("doc.pdf", "application/pdf", "pdf-content".getBytes()))
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldReturn400WhenFileTooLarge() {
        byte[] oversized = new byte[6 * 1024 * 1024]; // 6MB — exceeds max-file-size: 5MB

        // When the upload exceeds the server's multipart size limit, Tomcat rejects the
        // request early and closes the TCP connection. The client may receive either a
        // 4xx response or a ResourceAccessException (broken pipe) depending on how much
        // of the body has been sent before the server closes the socket. Both outcomes
        // confirm the server refused the oversized upload.
        try {
            var response = restClient.post()
                    .uri("/api/v1/admin/products/" + productId + "/images")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(buildMultipartBody("big.jpg", "image/jpeg", oversized))
                    .retrieve()
                    .toEntity(String.class);
            assertThat(response.getStatusCode().is4xxClientError())
                    .as("Expected 4xx for oversized upload but got: %s", response.getStatusCode())
                    .isTrue();
        } catch (ResourceAccessException e) {
            // Broken pipe: server closed the connection before the body was fully sent —
            // this is an acceptable outcome proving the server rejected the oversized upload.
            assertThat(e.getMessage()).containsAnyOf("Broken pipe", "Connection reset", "closed");
        }
    }

    @Test
    void shouldDeleteImageAndReturn204() {
        // Upload first
        var uploadResponse = restClient.post()
                .uri("/api/v1/admin/products/" + productId + "/images")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(buildMultipartBody("del.jpg", "image/jpeg", JPEG_MAGIC_BYTES))
                .retrieve()
                .toEntity(String.class);

        assertThat(uploadResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Extract image ID from response: [{"id":X,...}]
        String body = uploadResponse.getBody();
        String idStr = body.substring(body.indexOf("\"id\":") + 5);
        long imageId = Long.parseLong(idStr.substring(0, idStr.indexOf(",")));

        // Delete
        var deleteResponse = restClient.delete()
                .uri("/api/v1/admin/products/" + productId + "/images/" + imageId)
                .retrieve()
                .toEntity(Void.class);

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void shouldReorderImagesAndReturnSortedList() {
        // Upload two images
        var upload1 = restClient.post()
                .uri("/api/v1/admin/products/" + productId + "/images")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(buildMultipartBody("first.jpg", "image/jpeg", JPEG_MAGIC_BYTES))
                .retrieve()
                .toEntity(String.class);
        assertThat(upload1.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var upload2 = restClient.post()
                .uri("/api/v1/admin/products/" + productId + "/images")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(buildMultipartBody("second.jpg", "image/jpeg", JPEG_MAGIC_BYTES))
                .retrieve()
                .toEntity(String.class);
        assertThat(upload2.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Extract IDs
        long id1 = extractFirstId(upload1.getBody());
        long id2 = extractFirstId(upload2.getBody());

        // Reorder: swap them
        String reorderBody = """
                {
                  "items": [
                    { "imageId": %d, "displayOrder": 1 },
                    { "imageId": %d, "displayOrder": 0 }
                  ]
                }
                """.formatted(id1, id2);

        var reorderResponse = restClient.put()
                .uri("/api/v1/admin/products/" + productId + "/images/order")
                .contentType(MediaType.APPLICATION_JSON)
                .body(reorderBody)
                .retrieve()
                .toEntity(String.class);

        assertThat(reorderResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reorderResponse.getBody()).contains("displayOrder");
    }

    private MultiValueMap<String, Object> buildMultipartBody(String filename, String contentType, byte[] content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        org.springframework.http.HttpEntity<ByteArrayResource> part =
                new org.springframework.http.HttpEntity<>(resource,
                        buildPartHeaders(contentType, filename));
        body.add("files", part);
        return body;
    }

    private org.springframework.http.HttpHeaders buildPartHeaders(String contentType, String filename) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.setContentDispositionFormData("files", filename);
        return headers;
    }

    private long extractFirstId(String jsonArray) {
        String idStr = jsonArray.substring(jsonArray.indexOf("\"id\":") + 5);
        return Long.parseLong(idStr.substring(0, idStr.indexOf(",")));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProductImageResponse(Long id, String imageUrl, Integer displayOrder) {}
}
