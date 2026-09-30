package com.libra.streaming.media.upload.infrastructure;

import com.libra.streaming.media.upload.application.UploadPersistence;
import com.libra.streaming.media.upload.application.UploadWorkflow;
import com.libra.streaming.media.upload.application.UploadFailure;
import com.libra.streaming.media.upload.domain.UploadDescriptor;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class JdbcUploadAdapter implements UploadPersistence {
    private final MediaPersistenceService persistence;

    public JdbcUploadAdapter(MediaPersistenceService persistence) {
        this.persistence = persistence;
    }

    @Override
    public UploadSnapshot ensure(UploadDescriptor command) {
        return snapshot(persistence.ensure(new MediaPersistenceService.Ensure(command.uploadId(),
                command.requestId(), command.contentId(), command.bindingId(), command.assetId(),
                command.assetVersion(), command.byteLength(), command.sha256(), command.expiresAt())));
    }

    @Override
    public UploadSnapshot read(UUID uploadId) {
        try {
            return snapshot(persistence.read(uploadId));
        } catch (UploadFailure exception) {
            if ("NOT_FOUND".equals(exception.code())) { throw new UploadWorkflow.UploadNotFound(); }
            throw exception;
        }
    }

    @Override
    public UploadSource grantSource(UUID uploadId) {
        var source = persistence.grantSource(uploadId);
        return new UploadSource(snapshot(source.snapshot()), source.stagingKey());
    }

    @Override
    public UploadSnapshot queue(UUID uploadId, UUID assetId, long assetVersion) {
        return snapshot(persistence.queue(uploadId, assetId, assetVersion));
    }

    private static UploadSnapshot snapshot(MediaPersistenceService.Snapshot source) {
        return new UploadSnapshot(source.uploadId(), source.requestId(), source.contentId(),
                source.bindingId(), source.assetId(), source.assetVersion(), source.byteLength(),
                source.sha256(), source.uploadState(), source.assetState(), source.jobId(),
                source.attemptCount(), source.failureCode(), source.expiresAt());
    }
}
