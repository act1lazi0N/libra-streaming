package com.libra.streaming.media.storage;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;

/** Read-only dependency availability; does not claim object write permission or durability. */
final class MediaStorageHealthIndicator implements HealthIndicator {
    private final S3Client client;
    private final MediaStorageProperties properties;

    MediaStorageHealthIndicator(S3Client client, MediaStorageProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public Health health() {
        try {
            client.headBucket(b -> b.bucket(properties.sourceBucket()));
            client.headBucket(b -> b.bucket(properties.hlsBucket()));
            return Health.up().build();
        } catch (SdkException failure) {
            return Health.down().build();
        }
    }
}
