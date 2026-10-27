package com.robomart.product.service;

import java.io.IOException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.robomart.common.exception.ValidationException;
import com.robomart.product.config.ImageStorageProperties;
import com.robomart.product.exception.ImageStorageException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

@Service
public class ImageStorageService {
    private static final Logger log = LoggerFactory.getLogger(ImageStorageService.class);
    static final int MAX_FILE_SIZE = 5 * 1024 * 1024;
    private static final Pattern OWNED_KEY = Pattern.compile(
            "[1-9][0-9]*/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)");
    private final S3Client s3;
    private final String bucket;
    private final String publicBase;
    private final String legacyPrefix;

    public ImageStorageService(S3Client s3, ImageStorageProperties properties,
            @Value("${robomart.product.image-base-url}") String legacyBase) {
        this.s3 = s3;
        this.bucket = properties.getBucket();
        this.publicBase = properties.getPublicBaseUrl().replaceAll("/+$", "");
        this.legacyPrefix = legacyBase.replaceAll("/+$", "") + "/images/";
    }

    public String store(Long productId, MultipartFile file) {
        if (productId == null || productId < 1) throw new ValidationException("A persisted product is required");
        if (file.isEmpty()) throw new ValidationException("Uploaded file is empty");
        if (file.getSize() > MAX_FILE_SIZE) throw new ValidationException("File size exceeds 5MB limit");
        byte[] bytes;
        try (var input = file.getInputStream()) {
            bytes = input.readNBytes(MAX_FILE_SIZE + 1);
        } catch (IOException failure) {
            throw new ImageStorageException("Failed to read image", failure);
        }
        if (bytes.length == 0) throw new ValidationException("Uploaded file is empty");
        if (bytes.length > MAX_FILE_SIZE) throw new ValidationException("File size exceeds 5MB limit");
        String mime = detectMimeFromBytes(bytes);
        String extension = extension(mime);
        String key = productId + "/" + UUID.randomUUID() + "." + extension;
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(mime)
                            .cacheControl("public, max-age=31536000, immutable").ifNoneMatch("*")
                            .metadata(Map.of("sha256", sha256(bytes))).build(), RequestBody.fromBytes(bytes));
            return publicUrl(key);
        } catch (SdkException failure) {
            throw new ImageStorageException("Failed to store image object", failure);
        }
    }

    public void delete(String imageUrl) {
        var key = ownedKey(imageUrl);
        if (key.isEmpty()) {
            log.warn("Skipping deletion of an unowned image URL");
            return;
        }
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key.get()).build());
        } catch (SdkException failure) {
            // Best-effort post-commit/rollback cleanup must not turn an already committed response into failure.
            log.warn("Could not delete image object {}: {}", key.get(), failure.getClass().getSimpleName());
        }
    }

    public Optional<String> ownedKey(String url) {
        if (url == null) return Optional.empty();
        String prefix = url.startsWith(publicBase + "/") ? publicBase + "/" : legacyPrefix;
        if (!url.startsWith(prefix)) return Optional.empty();
        String key = url.substring(prefix.length());
        return isOwnedKey(key) ? Optional.of(key) : Optional.empty();
    }

    public boolean exists(String key) {
        if (!isOwnedKey(key)) return false;
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404) return false;
            throw new ImageStorageException("Failed to inspect image object", failure);
        } catch (SdkException failure) {
            throw new ImageStorageException("Failed to inspect image object", failure);
        }
    }

    public String publicUrl(String key) {
        if (!isOwnedKey(key)) throw new ValidationException("Invalid image object key");
        return publicBase + "/" + key;
    }

    public static boolean isOwnedKey(String key) { return OWNED_KEY.matcher(key).matches(); }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Required SHA-256 algorithm unavailable", impossible);
        }
    }

    static String extension(String mime) {
        return switch (mime) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> throw new ValidationException("Unsupported file type. Allowed: JPEG, PNG, WebP");
        };
    }

    static String detectMimeFromBytes(byte[] header) {
        if (header.length >= 3 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8
                && (header[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (header.length >= 8 && (header[0] & 0xFF) == 0x89 && header[1] == 0x50 && header[2] == 0x4E
                && header[3] == 0x47 && header[4] == 0x0D && header[5] == 0x0A && header[6] == 0x1A
                && header[7] == 0x0A) return "image/png";
        if (header.length >= 12 && header[0] == 0x52 && header[1] == 0x49 && header[2] == 0x46
                && header[3] == 0x46 && header[8] == 0x57 && header[9] == 0x45
                && header[10] == 0x42 && header[11] == 0x50) return "image/webp";
        return "application/octet-stream";
    }
}
