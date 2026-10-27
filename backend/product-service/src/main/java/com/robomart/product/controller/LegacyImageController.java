package com.robomart.product.controller;

import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import com.robomart.product.service.ImageStorageService;

/** Preserve persisted /images URLs after copying the legacy volume to object storage. */
@Controller
public class LegacyImageController {
    private final ImageStorageService images;

    public LegacyImageController(ImageStorageService images) { this.images = images; }

    @GetMapping("/images/{productId:[0-9]+}/{filename}")
    public ResponseEntity<Void> image(@PathVariable String productId, @PathVariable String filename) {
        String key = productId + "/" + filename;
        if (!images.exists(key)) return ResponseEntity.notFound().build();
        return ResponseEntity.status(302).location(URI.create(images.publicUrl(key))).build();
    }
}
