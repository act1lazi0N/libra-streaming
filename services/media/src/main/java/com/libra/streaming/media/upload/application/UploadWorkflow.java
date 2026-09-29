package com.libra.streaming.media.upload.application;

import com.libra.streaming.media.upload.domain.UploadDescriptor;
import java.util.UUID;

/** Coordinates the Core authority check before any Media-owned write or URL grant. */
public final class UploadWorkflow {
    private final UploadPersistence uploads;
    private final CandidateBinding core;
    private final UploadGrantSigner signer;
    private final StagingInspector staging;

    public UploadWorkflow(UploadPersistence uploads, CandidateBinding core, UploadGrantSigner signer,
            StagingInspector staging) {
        this.uploads = uploads;
        this.core = core;
        this.signer = signer;
        this.staging = staging;
    }

    public Created ensure(UploadDescriptor command) {
        core.requireCurrent(command);
        boolean existing;
        try {
            uploads.read(command.uploadId());
            existing = true;
        } catch (UploadNotFound exception) {
            existing = false;
        }
        return new Created(uploads.ensure(command), existing);
    }

    public UploadPersistence.UploadSnapshot read(UUID uploadId) {
        return uploads.read(uploadId);
    }

    public UploadGrantSigner.Grant issueUrl(UUID uploadId) {
        var source = uploads.grantSource(uploadId);
        core.requireCurrent(source.snapshot().command());
        return signer.sign(source);
    }

    public UploadPersistence.UploadSnapshot complete(UUID uploadId) {
        var current = uploads.read(uploadId);
        if ("SUBMITTED".equals(current.uploadState()) && current.jobId() != null) { return current; }
        var source = uploads.grantSource(uploadId);
        core.requireCurrent(source.snapshot().command());
        staging.requireComplete(source);
        return uploads.queue(uploadId, current.assetId(), current.assetVersion());
    }

    public record Created(UploadPersistence.UploadSnapshot snapshot, boolean existing) {}

    public static final class UploadNotFound extends RuntimeException {
        public UploadNotFound() { super("NOT_FOUND"); }
    }
}
