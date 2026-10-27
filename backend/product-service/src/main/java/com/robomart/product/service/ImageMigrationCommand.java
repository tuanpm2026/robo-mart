package com.robomart.product.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

import com.robomart.product.config.ImageStorageConfig;
import com.robomart.product.config.ImageStorageProperties;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Explicit dry-run/copy/verify CLI. It never starts Spring, changes SQL rows or deletes source files. */
public final class ImageMigrationCommand {
    private ImageMigrationCommand() { }

    public record Result(int eligible, int copied, int identical, int skipped) { }

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || !"--source".equals(args[0]) || args.length > 3
                || (args.length == 3 && !"--apply".equals(args[2]))) {
            throw new IllegalArgumentException("Usage: ImageMigrationCommand --source DIRECTORY [--apply]; default is dry-run");
        }
        var properties = new ImageStorageProperties();
        properties.setEndpoint(env("S3_ENDPOINT", ""));
        properties.setRegion(env("S3_REGION", "us-east-1"));
        properties.setBucket(env("S3_BUCKET", "robomart-images"));
        properties.setAccessKey(env("S3_ACCESS_KEY", ""));
        properties.setSecretKey(env("S3_SECRET_KEY", ""));
        properties.setPathStyle(Boolean.parseBoolean(env("S3_PATH_STYLE", "false")));
        boolean apply = args.length == 3;
        if (apply) {
            try (var client = ImageStorageConfig.createClient(properties)) {
                System.out.println(migrate(Path.of(args[1]), client, properties.getBucket(), true));
            }
        } else {
            // Dry-run is source-only and never resolves credentials or opens a network connection.
            System.out.println(migrate(Path.of(args[1]), null, properties.getBucket(), false));
        }
    }

    private static String env(String name, String fallback) {
        return System.getenv().getOrDefault(name, fallback);
    }

    public static Result migrate(Path source, S3Client client, String bucket, boolean apply) throws IOException {
        if (Files.isSymbolicLink(source) || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Migration source must be an existing non-symlink directory");
        }
        if (apply && client == null) throw new IllegalArgumentException("Apply requires an S3 client");
        Path root = source.toAbsolutePath().normalize();
        int eligible = 0;
        int copied = 0;
        int identical = 0;
        int skipped = 0;
        try (var files = Files.walk(root, 3)) {
            for (Path file : files.sorted().toList()) {
                if (file.equals(root) || Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) continue;
                String key = root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)
                        || !ImageStorageService.isOwnedKey(key)) {
                    skipped++;
                    continue;
                }
                byte[] bytes;
                try (var input = Files.newInputStream(file)) {
                    bytes = input.readNBytes(ImageStorageService.MAX_FILE_SIZE + 1);
                }
                if (bytes.length == 0 || bytes.length > ImageStorageService.MAX_FILE_SIZE) {
                    throw new IllegalStateException("Invalid legacy image size for " + key);
                }
                String mime = ImageStorageService.detectMimeFromBytes(bytes);
                String extension = ImageStorageService.extension(mime);
                if (!key.endsWith("." + extension)) throw new IllegalStateException("Legacy extension/MIME mismatch: " + key);
                eligible++;
                String hash = ImageStorageService.sha256(bytes);
                if (!apply) {
                    System.out.println("DRY-RUN " + key + " bytes=" + bytes.length + " sha256=" + hash);
                    continue;
                }
                boolean exists;
                try {
                    var head = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
                    if (head.contentLength() != bytes.length) throw new IllegalStateException("Destination differs: " + key);
                    exists = true;
                } catch (S3Exception failure) {
                    if (failure.statusCode() != 404) throw failure;
                    exists = false;
                }
                if (!exists) {
                    try {
                        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).ifNoneMatch("*")
                                .contentType(mime).metadata(Map.of("sha256", hash))
                                .cacheControl("public, max-age=31536000, immutable").build(), RequestBody.fromBytes(bytes));
                        copied++;
                    } catch (S3Exception raced) {
                        if (raced.statusCode() != 412) throw raced;
                        exists = true;
                    }
                }
                byte[] stored;
                try (var input = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
                    stored = input.readNBytes(ImageStorageService.MAX_FILE_SIZE + 1);
                }
                if (!hash.equals(ImageStorageService.sha256(stored))) {
                    throw new IllegalStateException("Destination checksum differs; nothing overwritten/deleted: " + key);
                }
                if (exists) identical++;
                System.out.println("VERIFIED " + key + " bytes=" + bytes.length + " sha256=" + hash);
            }
        }
        return new Result(eligible, copied, identical, skipped);
    }
}
