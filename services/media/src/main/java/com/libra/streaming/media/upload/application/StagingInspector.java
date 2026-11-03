package com.libra.streaming.media.upload.application;

/** HEAD precondition only; worker milestones verify bytes and freeze the source. */
public interface StagingInspector {
    void requireComplete(UploadPersistence.UploadSource source);
}
