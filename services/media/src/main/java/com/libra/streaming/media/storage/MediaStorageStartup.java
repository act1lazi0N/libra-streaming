package com.libra.streaming.media.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CORSConfiguration;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutBucketCorsRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.core.exception.SdkException;

/** Explicit one-shot setup and probe for a disposable runtime; both flags default off. */
final class MediaStorageStartup implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(MediaStorageStartup.class);
    private final S3MediaStorage storage;
    private final S3Client client;
    private final MediaStorageProperties properties;

    MediaStorageStartup(S3MediaStorage storage, S3Client client, MediaStorageProperties properties) {
        this.storage = storage;
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            if (properties.initializeBuckets()) { initialize(); }
            // Always validate both configured buckets with the intended identity before readiness.
            client.headBucket(b -> b.bucket(properties.sourceBucket()));
            client.headBucket(b -> b.bucket(properties.hlsBucket()));
            if (properties.probeOnStartup()) { probe(); }
        } catch (SdkException failure) {
            LOG.warn("MEDIA_STORAGE_STARTUP_FAILED status={}",
                    failure instanceof S3Exception s3 ? s3.statusCode() : "transport");
            throw MediaStorageException.from(failure);
        }
    }

    private void initialize() {
        ensureBucket(properties.sourceBucket());
        ensureBucket(properties.hlsBucket());
        client.putBucketCors(PutBucketCorsRequest.builder().bucket(properties.sourceBucket())
                .corsConfiguration(CORSConfiguration.builder().corsRules(CORSRule.builder()
                        .allowedOrigins(properties.browserOrigin())
                        .allowedMethods("PUT", "HEAD")
                        .allowedHeaders("Content-Type")
                        .maxAgeSeconds(300)
                        .build()).build()).build());
        LOG.info("MEDIA_STORAGE_INITIALIZATION_PASS");
    }

    private void ensureBucket(String bucket) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception exception) {
            if (exception.statusCode() != 404) { throw exception; }
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }

    private void probe() throws Exception {
        String key = properties.sourcePrefix() + "probe/" + UUID.randomUUID() + ".bin";
        byte[] expected = new byte[64];
        new SecureRandom().nextBytes(expected);
        Path source = storage.scratchFile();
        Path downloaded = null;
        boolean uploaded = false;
        boolean verified = false;
        try {
            Files.write(source, expected);
            storage.put(S3MediaStorage.Area.SOURCE, key, source, "application/octet-stream");
            uploaded = true;
            var metadata = storage.head(S3MediaStorage.Area.SOURCE, key)
                    .orElseThrow(() -> new IllegalStateException("Storage probe object missing"));
            if (metadata.length() != expected.length) { throw new IllegalStateException("Storage probe length mismatch"); }
            downloaded = storage.download(S3MediaStorage.Area.SOURCE, key);
            if (!MessageDigest.isEqual(expected, Files.readAllBytes(downloaded))) {
                throw new IllegalStateException("Storage probe content mismatch");
            }
            verified = true;
        } finally {
            try {
                if (uploaded) { storage.delete(S3MediaStorage.Area.SOURCE, key); }
            } finally {
                Files.deleteIfExists(source);
                if (downloaded != null) { Files.deleteIfExists(downloaded); }
            }
        }
        if (verified) { LOG.info("MEDIA_STORAGE_PROBE_PASS"); }
    }
}
