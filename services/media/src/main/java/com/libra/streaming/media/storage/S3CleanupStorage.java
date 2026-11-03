package com.libra.streaming.media.storage;

import com.libra.streaming.media.lifecycle.application.CleanupStorage;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Maps scoped cleanup onto the private areas. Every key is named exactly or lies under one exactly named attempt
 * prefix; no area, upload directory or bucket is ever listed as a whole.
 */
@Component
public class S3CleanupStorage implements CleanupStorage {
    private static final int PAGE = 1000;

    private final ObjectProvider<S3MediaStorage> storage;
    private final ObjectProvider<MediaStorageProperties> properties;

    public S3CleanupStorage(ObjectProvider<S3MediaStorage> storage, ObjectProvider<MediaStorageProperties> properties) {
        this.storage = storage;
        this.properties = properties;
    }

    @Override
    public String sourceKey(UUID uploadId, int attempt) {
        return MediaObjectKeys.sourceKey(configured(), uploadId, attempt);
    }

    @Override
    public String outputPrefix(UUID uploadId, int attempt) {
        return MediaObjectKeys.outputPrefix(configured(), uploadId, attempt);
    }

    @Override
    public boolean deleteStaging(String key) {
        String prefix = configured().stagingPrefix();
        // Only the one object a staging grant can address: <staging-prefix><uploadId>/source.mp4.
        if (key == null || !key.startsWith(prefix) || !key.endsWith("/source.mp4")) {
            throw new IllegalArgumentException("Invalid staging key");
        }
        UUID.fromString(key.substring(prefix.length(), key.length() - "/source.mp4".length()));
        return delete(S3MediaStorage.Area.STAGING, key);
    }

    @Override
    public boolean deleteSource(String key) {
        if (key == null || !key.startsWith(configured().sourcePrefix())
                || !key.matches(".*/attempt-[1-3]/source\\.mp4")) {
            throw new IllegalArgumentException("Invalid source key");
        }
        return delete(S3MediaStorage.Area.SOURCE, key);
    }

    @Override
    public Batch deleteOutput(String prefix, int limit) {
        if (prefix == null || !prefix.startsWith(configured().hlsPrefix()) || !prefix.matches(".*/attempt-[1-3]")
                || limit < 1) {
            throw new IllegalArgumentException("Invalid output prefix");
        }
        var available = available();
        int deleted = 0;
        try {
            while (deleted < limit) {
                // Deleted keys leave the listing, so each page starts again from the front of what remains.
                var page = available.list(S3MediaStorage.Area.HLS, prefix + "/", Math.min(PAGE, limit - deleted));
                for (String key : page.keys()) {
                    available.delete(S3MediaStorage.Area.HLS, key);
                    deleted++;
                }
                if (page.exhausted() || page.keys().isEmpty()) { return new Batch(deleted, page.exhausted()); }
            }
            return new Batch(deleted, false);
        } catch (MediaStorageException failure) {
            throw new Unavailable();
        }
    }

    private boolean delete(S3MediaStorage.Area area, String key) {
        var available = available();
        try {
            boolean existed = available.head(area, key).isPresent();
            // Deleted even when the look found nothing: a write may have landed since.
            available.delete(area, key);
            return existed;
        } catch (MediaStorageException failure) {
            throw new Unavailable();
        }
    }

    private MediaStorageProperties configured() {
        var configured = properties.getIfAvailable();
        if (configured == null) { throw new Unavailable(); }
        return configured;
    }

    private S3MediaStorage available() {
        var available = storage.getIfAvailable();
        if (available == null) { throw new Unavailable(); }
        return available;
    }
}
