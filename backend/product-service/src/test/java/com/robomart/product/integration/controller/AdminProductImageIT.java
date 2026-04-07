package com.robomart.product.integration.controller;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import com.robomart.test.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class AdminProductImageIT {

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

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
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
                .body(buildMultipartBody("test.jpg", "image/jpeg", "fake-jpeg-bytes".getBytes()))
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
        byte[] oversized = new byte[6 * 1024 * 1024]; // 6MB

        var response = restClient.post()
                .uri("/api/v1/admin/products/" + productId + "/images")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(buildMultipartBody("big.jpg", "image/jpeg", oversized))
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldDeleteImageAndReturn204() {
        // Upload first
        var uploadResponse = restClient.post()
                .uri("/api/v1/admin/products/" + productId + "/images")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(buildMultipartBody("del.jpg", "image/jpeg", "data".getBytes()))
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
                .body(buildMultipartBody("first.jpg", "image/jpeg", "data1".getBytes()))
                .retrieve()
                .toEntity(String.class);
        assertThat(upload1.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var upload2 = restClient.post()
                .uri("/api/v1/admin/products/" + productId + "/images")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(buildMultipartBody("second.jpg", "image/jpeg", "data2".getBytes()))
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
