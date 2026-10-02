package com.libra.streaming.media.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** Internal only: all keys are constrained to one configured private area. */
final class S3MediaStorage {
    enum Area { STAGING, SOURCE, HLS }

    private static final Set<String> CONTENT_TYPES = Set.of("application/octet-stream", "video/mp4",
            "application/vnd.apple.mpegurl", "video/mp2t");
    private final S3Client client;
    private final MediaStorageProperties properties;
    private final Path scratch;

    S3MediaStorage(S3Client client, MediaStorageProperties properties) throws IOException {
        this.client = client;
        this.properties = properties;
        Path configured = properties.scratchDirectory().normalize();
        if (Files.isSymbolicLink(configured)) { throw new IllegalArgumentException("Scratch directory is a link"); }
        Files.createDirectories(configured);
        scratch = configured.toRealPath();
        if (!Files.isDirectory(scratch) || !Files.isWritable(scratch)) {
            throw new IllegalArgumentException("Scratch directory is not writable");
        }
    }

    Path scratchFile() throws IOException {
        return Files.createTempFile(scratch, "media-", ".part");
    }

    void put(Area area, String key, Path source, String contentType) throws IOException {
        var location = location(area, key);
        if (!CONTENT_TYPES.contains(contentType)) { throw new IllegalArgumentException("Unsupported content type"); }
        Path real = source.toRealPath();
        if (!real.startsWith(scratch) || !Files.isRegularFile(real)) {
            throw new IllegalArgumentException("Source must be a regular scratch file");
        }
        long size = Files.size(real);
        if (size < 1 || size > properties.maxObjectBytes()) { throw new IllegalArgumentException("Object size exceeds limit"); }
        try {
            client.putObject(PutObjectRequest.builder().bucket(location.bucket()).key(location.key())
                    .contentType(contentType).contentLength(size).build(), RequestBody.fromFile(real));
        } catch (SdkException failure) { throw MediaStorageException.from(failure); }
    }

    Optional<ObjectMetadata> head(Area area, String key) {
        var location = location(area, key);
        try {
            var response = client.headObject(HeadObjectRequest.builder()
                    .bucket(location.bucket()).key(location.key()).build());
            return Optional.of(new ObjectMetadata(response.contentLength(), response.contentType()));
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) { return Optional.empty(); }
            throw MediaStorageException.from(exception);
        } catch (SdkException failure) { throw MediaStorageException.from(failure); }
    }

    Path download(Area area, String key) throws IOException {
        var location = location(area, key);
        Path destination = scratchFile();
        boolean completed = false;
        try (var response = client.getObject(GetObjectRequest.builder()
                .bucket(location.bucket()).key(location.key()).build());
                var output = Files.newOutputStream(destination)) {
            long declared = response.response().contentLength();
            if (declared > properties.maxObjectBytes()) { throw new IllegalArgumentException("Object size exceeds limit"); }
            byte[] buffer = new byte[65536];
            long copied = 0;
            int read;
            while ((read = response.read(buffer)) != -1) {
                copied += read;
                if (copied > properties.maxObjectBytes()) { throw new IllegalArgumentException("Object size exceeds limit"); }
                output.write(buffer, 0, read);
            }
            if (declared >= 0 && copied != declared) { throw new IOException("Object length mismatch"); }
            completed = true;
            return destination;
        } catch (SdkException failure) {
            throw MediaStorageException.from(failure);
        } finally {
            if (!completed) { Files.deleteIfExists(destination); }
        }
    }

    /**
     * Copies at most {@code declaredBytes + 1} bytes to scratch while counting and hashing exactly what was read.
     * One extra byte is enough to prove an oversized object without reading the rest of it.
     */
    Optional<Measured<Path>> stage(Area area, String key, long declaredBytes, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        Path destination = scratchFile();
        boolean completed = false;
        try {
            Optional<Measured<Void>> measured;
            try (var output = Files.newOutputStream(destination)) {
                measured = measure(area, key, declaredBytes, output, cancelled);
            }
            completed = measured.isPresent();
            return measured.map(value -> new Measured<>(destination, value.length(), value.sha256()));
        } finally {
            if (!completed) { Files.deleteIfExists(destination); }
        }
    }

    Optional<Measured<Void>> digest(Area area, String key, long expectedBytes, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return measure(area, key, expectedBytes, OutputStream.nullOutputStream(), cancelled)
                .map(value -> new Measured<>(null, value.length(), value.sha256()));
    }

    private Optional<Measured<Void>> measure(Area area, String key, long declaredBytes, OutputStream sink,
            BooleanSupplier cancelled) throws IOException, InterruptedException {
        var location = location(area, key);
        long ceiling = Math.min(declaredBytes, properties.maxObjectBytes()) + 1;
        MessageDigest digest = sha256();
        try (var response = client.getObject(GetObjectRequest.builder()
                .bucket(location.bucket()).key(location.key()).build())) {
            byte[] buffer = new byte[65536];
            long copied = 0;
            while (copied < ceiling) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
                int read = response.read(buffer, 0, (int) Math.min(buffer.length, ceiling - copied));
                if (read == -1) { break; }
                digest.update(buffer, 0, read);
                sink.write(buffer, 0, read);
                copied += read;
            }
            if (copied >= ceiling) {
                response.abort();
            } else if (response.response().contentLength() != null && copied != response.response().contentLength()) {
                throw new IOException("Object length mismatch");
            }
            return Optional.of(new Measured<>(null, copied, HexFormat.of().formatHex(digest.digest())));
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) { return Optional.empty(); }
            throw MediaStorageException.from(exception);
        } catch (SdkException failure) {
            throw MediaStorageException.from(failure);
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }

    void delete(Area area, String key) {
        var location = location(area, key);
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(location.bucket()).key(location.key()).build());
        } catch (SdkException failure) { throw MediaStorageException.from(failure); }
    }

    private Location location(Area area, String key) {
        if (area == null || key == null || key.length() > 512 || key.contains("//")
                || !key.matches("[A-Za-z0-9][A-Za-z0-9._/-]*")) {
            throw new IllegalArgumentException("Invalid media object key");
        }
        String prefix = switch (area) {
            case STAGING -> properties.stagingPrefix();
            case SOURCE -> properties.sourcePrefix();
            case HLS -> properties.hlsPrefix();
        };
        if (!key.startsWith(prefix) || key.length() == prefix.length()) {
            throw new IllegalArgumentException("Media object key is outside its area");
        }
        for (String segment : key.substring(prefix.length()).split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Invalid media object key segment");
            }
        }
        return new Location(area == Area.HLS ? properties.hlsBucket() : properties.sourceBucket(), key);
    }

    record ObjectMetadata(long length, String contentType) {}
    /** Bytes actually read and the SHA-256 of exactly those bytes; the value is the scratch file, when any. */
    record Measured<T>(T value, long length, String sha256) {}
    private record Location(String bucket, String key) {}
}
