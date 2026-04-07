package com.robomart.product.service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.robomart.common.exception.ValidationException;
import com.robomart.product.exception.ImageStorageException;

@Service
public class ImageStorageService {

    private static final Logger log = LoggerFactory.getLogger(ImageStorageService.class);

    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024L;

    private final Path rootStoragePath;
    private final String imageBaseUrl;
    private final String imagePrefix;

    public ImageStorageService(
            @Value("${robomart.product.image-storage-path}") String storagePath,
            @Value("${robomart.product.image-base-url}") String imageBaseUrl) {
        this.rootStoragePath = Path.of(storagePath);
        // Normalize: strip trailing slash to prevent double-slash in URLs and broken prefix matching
        String normalizedBase = imageBaseUrl.endsWith("/")
                ? imageBaseUrl.substring(0, imageBaseUrl.length() - 1)
                : imageBaseUrl;
        this.imageBaseUrl = normalizedBase;
        this.imagePrefix = normalizedBase + "/images/";
        createDirectoryIfAbsent(this.rootStoragePath);
    }

    public String store(Long productId, MultipartFile file) {
        validateFileSize(file);
        String detectedMime = detectMime(file);
        validateMimeType(detectedMime);
        String ext = extFromMime(detectedMime);
        String filename = UUID.randomUUID() + "." + ext;
        Path productDir = rootStoragePath.resolve(productId.toString());
        createDirectoryIfAbsent(productDir);
        Path destination = productDir.resolve(filename);
        try {
            Files.copy(file.getInputStream(), destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ImageStorageException("Failed to store image: " + filename, e);
        }
        return imageBaseUrl + "/images/" + productId + "/" + filename;
    }

    public void delete(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith(imagePrefix)) {
            log.warn("Image URL does not match expected prefix, skipping delete: {}", imageUrl);
            return;
        }
        String relativePath = imageUrl.substring(imagePrefix.length());
        Path filePath = rootStoragePath.resolve(relativePath).normalize();
        // Bounds check: prevent path traversal attacks
        if (!filePath.startsWith(rootStoragePath.toAbsolutePath().normalize())) {
            log.warn("Path traversal attempt detected for image URL: {}", imageUrl);
            return;
        }
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.warn("Could not delete image file: {}", filePath, e);
        }
    }

    // Package-private for testing
    String detectMime(MultipartFile file) {
        try (InputStream raw = file.getInputStream();
             BufferedInputStream bis = new BufferedInputStream(raw)) {
            byte[] header = bis.readNBytes(12);
            return detectMimeFromBytes(header);
        } catch (IOException e) {
            throw new ImageStorageException("Failed to read file for MIME detection", e);
        }
    }

    // Package-private for testing
    static String detectMimeFromBytes(byte[] header) {
        // JPEG: FF D8 FF
        if (header.length >= 3
                && (header[0] & 0xFF) == 0xFF
                && (header[1] & 0xFF) == 0xD8
                && (header[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (header.length >= 8
                && (header[0] & 0xFF) == 0x89
                && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47
                && header[4] == 0x0D && header[5] == 0x0A
                && header[6] == 0x1A && header[7] == 0x0A) {
            return "image/png";
        }
        // WebP: RIFF....WEBP (bytes 0-3 = RIFF, bytes 8-11 = WEBP)
        if (header.length >= 12
                && header[0] == 0x52 && header[1] == 0x49 && header[2] == 0x46 && header[3] == 0x46
                && header[8] == 0x57 && header[9] == 0x45 && header[10] == 0x42 && header[11] == 0x50) {
            return "image/webp";
        }
        return "application/octet-stream";
    }

    private void validateFileSize(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ValidationException("Uploaded file is empty");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ValidationException("File size exceeds 5MB limit: " + file.getOriginalFilename());
        }
    }

    private void validateMimeType(String detectedMime) {
        if (!ALLOWED_TYPES.contains(detectedMime)) {
            throw new ValidationException("Unsupported file type. Allowed: JPEG, PNG, WebP");
        }
    }

    private static String extFromMime(String mime) {
        return switch (mime) {
            case "image/jpeg" -> "jpg";
            case "image/png"  -> "png";
            case "image/webp" -> "webp";
            default -> "bin";
        };
    }

    private void createDirectoryIfAbsent(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new ImageStorageException("Could not create storage directory: " + path, e);
        }
    }
}
