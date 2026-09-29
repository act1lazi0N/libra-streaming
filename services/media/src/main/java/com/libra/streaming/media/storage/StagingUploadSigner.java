package com.libra.streaming.media.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/** A browser capability for one server-created staging object. Never log the result. */
public final class StagingUploadSigner {
    private final S3Presigner presigner;
    private final MediaStorageProperties properties;

    StagingUploadSigner(S3Presigner presigner, MediaStorageProperties properties) {
        this.presigner = presigner;
        this.properties = properties;
    }

    public Grant sign(UUID uploadId, String stagingKey, long byteLength, String sha256,
            Instant expiresAt, Instant now) {
        if (uploadId == null || !((properties.stagingPrefix() + uploadId + "/source.mp4").equals(stagingKey))
                || byteLength < 1 || byteLength > properties.maxObjectBytes()
                || sha256 == null || !sha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid staging grant source");
        }
        Instant deadline = expiresAt.isBefore(now.plus(Duration.ofMinutes(15)))
                ? expiresAt : now.plus(Duration.ofMinutes(15));
        Duration duration = Duration.between(now, deadline);
        if (duration.isZero() || duration.isNegative()) { throw new IllegalArgumentException("Upload expired"); }
        String checksum = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256));
        var request = PutObjectRequest.builder().bucket(properties.sourceBucket()).key(stagingKey)
                .contentType("video/mp4").contentLength(byteLength).checksumSHA256(checksum).build();
        var signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(duration).putObjectRequest(request).build());
        Map<String, String> headers = new LinkedHashMap<>();
        signed.signedHeaders().forEach((name, values) -> {
            // Browsers set Content-Length from the Blob and forbid scripts from setting it.
            if (!name.equalsIgnoreCase("host") && !name.equalsIgnoreCase("content-length")) {
                headers.put(name.equalsIgnoreCase("content-type") ? "Content-Type" : name.toLowerCase(),
                        String.join(",", values));
            }
        });
        return new Grant(uploadId, "PUT", signed.url().toString(), deadline, Map.copyOf(headers));
    }

    public record Grant(UUID uploadId, String method, String url, Instant expiresAt,
            Map<String, String> requiredHeaders) {
        @Override public String toString() { return "Grant[uploadId=" + uploadId + ", url=REDACTED]"; }
    }
}
