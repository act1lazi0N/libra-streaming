package com.libra.streaming.media.storage;

import com.libra.streaming.media.processing.application.SourceStorage;
import com.libra.streaming.media.processing.domain.JobLease;
import java.io.IOException;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Maps source freezing onto the private source area. Storage detail never crosses this boundary. */
@Component
public class S3SourceStorage implements SourceStorage {
    private final ObjectProvider<S3MediaStorage> storage;
    private final ObjectProvider<MediaStorageProperties> properties;

    public S3SourceStorage(ObjectProvider<S3MediaStorage> storage, ObjectProvider<MediaStorageProperties> properties) {
        this.storage = storage;
        this.properties = properties;
    }

    @Override
    public Optional<StagedCopy> stage(String stagingKey, long declaredBytes, BooleanSupplier cancelled)
            throws InterruptedException {
        try {
            return available().stage(S3MediaStorage.Area.STAGING, stagingKey, declaredBytes, cancelled)
                    .map(copy -> new StagedCopy(copy.value(), copy.length(), copy.sha256()));
        } catch (MediaStorageException | IOException exception) {
            throw new Unavailable();
        }
    }

    @Override
    public String frozenKey(JobLease lease) {
        var configured = properties.getIfAvailable();
        if (configured == null) { throw new Unavailable(); }
        // The attempt number makes a late object from an old claim unable to share the selected key.
        return configured.sourcePrefix() + lease.uploadId() + "/attempt-" + lease.attempt() + "/source.mp4";
    }

    @Override
    public void store(String frozenKey, StagedCopy copy) {
        try {
            available().put(S3MediaStorage.Area.SOURCE, frozenKey, copy.file(), "video/mp4");
        } catch (MediaStorageException | IOException exception) {
            throw new Unavailable();
        }
    }

    @Override
    public Optional<Digest> digest(String frozenKey, long expectedBytes, BooleanSupplier cancelled)
            throws InterruptedException {
        try {
            return available().digest(S3MediaStorage.Area.SOURCE, frozenKey, expectedBytes, cancelled)
                    .map(measured -> new Digest(measured.length(), measured.sha256()));
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
