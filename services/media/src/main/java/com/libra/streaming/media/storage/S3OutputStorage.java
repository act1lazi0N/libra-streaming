package com.libra.streaming.media.storage;

import com.libra.streaming.media.processing.application.OutputStorage;
import com.libra.streaming.media.processing.domain.JobLease;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Maps HLS output onto the private HLS area. Storage detail never crosses this boundary. */
@Component
public class S3OutputStorage implements OutputStorage {
    private final ObjectProvider<S3MediaStorage> storage;
    private final ObjectProvider<MediaStorageProperties> properties;

    public S3OutputStorage(ObjectProvider<S3MediaStorage> storage, ObjectProvider<MediaStorageProperties> properties) {
        this.storage = storage;
        this.properties = properties;
    }

    @Override
    public String prefix(JobLease lease) {
        var configured = properties.getIfAvailable();
        if (configured == null) { throw new Unavailable(); }
        return configured.hlsPrefix() + lease.uploadId() + "/attempt-" + lease.attempt();
    }

    @Override
    public void store(String key, Path file, String contentType) {
        try {
            available().put(S3MediaStorage.Area.HLS, key, file, contentType);
        } catch (MediaStorageException | IOException exception) {
            throw new Unavailable();
        }
    }

    @Override
    public Optional<Readback> read(String key, long expectedBytes, BooleanSupplier cancelled)
            throws InterruptedException {
        try {
            return available().digest(S3MediaStorage.Area.HLS, key, expectedBytes, cancelled)
                    .map(measured -> new Readback(measured.length(), measured.sha256()));
        } catch (MediaStorageException | IOException exception) {
            throw new Unavailable();
        }
    }

    private S3MediaStorage available() {
        var available = storage.getIfAvailable();
        if (available == null) { throw new Unavailable(); }
        return available;
    }
}
