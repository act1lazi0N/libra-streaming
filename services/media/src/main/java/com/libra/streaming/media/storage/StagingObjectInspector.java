package com.libra.streaming.media.storage;

import com.libra.streaming.media.upload.application.StagingInspector;
import com.libra.streaming.media.upload.application.UploadFailure;
import com.libra.streaming.media.upload.application.UploadPersistence;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class StagingObjectInspector implements StagingInspector {
    private final ObjectProvider<S3MediaStorage> storage;

    public StagingObjectInspector(ObjectProvider<S3MediaStorage> storage) { this.storage = storage; }

    @Override
    public void requireComplete(UploadPersistence.UploadSource source) {
        var available = storage.getIfAvailable();
        if (available == null) { throw new UploadFailure("STORAGE_UNAVAILABLE"); }
        S3MediaStorage.ObjectMetadata metadata;
        try {
            metadata = available.head(S3MediaStorage.Area.STAGING, source.stagingKey())
                    .orElseThrow(() -> new UploadFailure("SOURCE_MISSING"));
        } catch (MediaStorageException exception) {
            throw new UploadFailure("STORAGE_UNAVAILABLE");
        }
        if (metadata.length() != source.snapshot().byteLength()
                || !"video/mp4".equals(metadata.contentType())) {
            throw new UploadFailure("SOURCE_MISSING");
        }
    }
}
