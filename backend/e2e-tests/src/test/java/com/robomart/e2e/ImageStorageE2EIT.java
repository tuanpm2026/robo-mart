package com.robomart.e2e;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Gateway authorization, real object upload/public read and committed deletion. */
class ImageStorageE2EIT {
    @Test
    void adminUploadsPublicImageAndDeletionRemovesTheObject() {
        var api = E2eSupport.api();
        var product = E2eSupport.availableProduct();
        String token = E2eSupport.adminToken();
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0, 1};
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("files", new ByteArrayResource(jpeg) {
            @Override public String getFilename() { return "e2e.jpg"; }
        });
        var uploaded = api.post().uri("/api/v1/admin/products/" + product.id() + "/images")
                .headers(headers -> headers.setBearerAuth(token)).contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts).retrieve().toEntity(String.class);
        assertThat(uploaded.getStatusCode().value()).isEqualTo(201);
        var image = E2eSupport.json(uploaded.getBody()).get(0);
        long id = image.path("id").asLong();
        String url = image.path("imageUrl").asString();
        assertThat(id).isPositive();
        try {
            assertThat(url).startsWith("http://localhost:19002/robomart-images/");
            assertThat(E2eSupport.client(url).get().retrieve().body(byte[].class)).containsExactly(jpeg);
            var detail = api.get().uri("/api/v1/products/" + product.id()).retrieve().body(String.class);
            assertThat(detail).contains(url);
        } finally {
            var deleted = api.delete().uri("/api/v1/admin/products/" + product.id() + "/images/" + id)
                    .headers(headers -> headers.setBearerAuth(token)).retrieve().toBodilessEntity();
            assertThat(deleted.getStatusCode().value()).isEqualTo(204);
        }
        assertThat(E2eSupport.client(url).get().retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(404);
        assertThat(api.get().uri("/api/v1/products/" + product.id()).retrieve().body(String.class)).doesNotContain(url);
    }
}
